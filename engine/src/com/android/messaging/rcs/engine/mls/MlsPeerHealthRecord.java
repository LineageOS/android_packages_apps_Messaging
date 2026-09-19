/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

/**
 * G4's peer-health streak, in the form that survives a process restart.
 *
 * <h2>What it counts</h2>
 *
 * <p>Consecutive operations toward one peer that THE PEER ITSELF reported as failed (§7.7.2.2).
 * At {@link #MAX_CONSECUTIVE_FAILURES} the guard stops sending that peer state changes, which is
 * guardrail G4: "peer stopped acknowledging must be a STOP condition, not a footnote".
 * It was in-memory, so a force-stop declared a wedged peer healthy again — and the peer we wedged
 * stayed wedged for a month across every restart on both sides.
 *
 * <h2>DURABILITY AND DECAY ARE ONE DECISION — read this before changing either</h2>
 *
 * <p>It is true that G4 is a TOTAL rather than a rate, and that a permanently spent
 * total converts a transient fault into a dead conversation. It argues against adding decay
 * casually, on the grounds that G4 asks for a STOP.
 *
 * <p>Both are true, and making the streak durable is what forces them together. <b>An in-memory
 * total already decays</b> — at every process restart, by an amount nobody chose, invisibly, and
 * most eagerly in exactly the crash loop where the streak matters. Persisting it removes that
 * accidental decay. Persisting it with NO bound would replace an arbitrary decay with none at all,
 * and the streak now gates the automatic rebuild — the last rung of self-repair —
 * so the result would be a conversation that can never be repaired automatically again, whose only
 * remaining exits are the peer successfully reading something of ours (unreachable precisely when
 * it is needed) and a notification that {@code reconcileAction}'s arms do not raise (that half is
 * still open).
 *
 * <p>So the streak carries an EVIDENCE STAMP and expires at {@link #EVIDENCE_MS} — not a decay of
 * the stop, a bound on how long a claim about a peer's health outlives the last observation
 * supporting it. Three things make that safe rather than a loosening:
 *
 * <ul>
 *   <li>The stop is unchanged while the evidence is fresh, and fresh means "the peer told us so
 *       within a day of uptime".</li>
 *   <li>It is SELF-RE-ARMING. The gate never blocked ordinary traffic — only the operations that
 *       re-Welcome a peer — so a still-wedged peer keeps failing to decrypt ordinary messages and
 *       keeps reporting it, and three fresh reports trip it again.</li>
 *   <li>It is strictly stronger than what shipped: a streak that survives a day of uptime is a
 *       longer stop than one cleared by the next force-stop.</li>
 * </ul>
 *
 * <p>If the other half lands — a guard refusal always reaching a person — this window can
 * be lengthened or dropped, because the trap it exists to avoid is a stop nobody is told about.
 *
 * <h2>The clock, and UNREADABLE vs ABSENT</h2>
 *
 * <p>Ages come from {@link MlsMonotonicAge} over {@code SystemClock.elapsedRealtime()}, so no clock
 * move can age a streak out early. {@link #decode} answers {@link #NONE} for a peer with no record
 * and {@code null} for a record it cannot read — the caller must treat the second as "I cannot say
 * this peer is healthy", never as a clean slate.
 */
public final class MlsPeerHealthRecord {

    /** Consecutive peer-reported failures before state changes toward that peer stop. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * How long a streak outlives the last failure the peer reported. One day, measured in uptime.
     *
     * <p>The same order as {@link MlsEraBudgetRecord#DAY_MS} on purpose: both answer "how long does
     * an observation about this conversation still bind us", and two different answers to that
     * question is how a total of seventeen stayed plausible while every budget read "within budget".
     */
    public static final long EVIDENCE_MS = MlsEraBudgetRecord.DAY_MS;

    /**
     * How often the evidence stamp is refreshed once the streak has SATURATED.
     *
     * <p>Past the trip point the count no longer moves, so the only thing a further report changes
     * is how long the stop outlives it — and reason-4 reports arrive in bursts. Refreshing on every
     * one of them would turn a burst into a burst of synchronous disk commits to record a number
     * nothing reads. A minute is far below {@link #EVIDENCE_MS} and far above a burst.
     */
    public static final long STAMP_COALESCE_MS = 60L * 1000L;

    /** No record: this peer has reported no failures. */
    public static final MlsPeerHealthRecord NONE = new MlsPeerHealthRecord(0, 0L);

    private static final String VERSION = "1";

    private final int mStreak;
    private final long mLastFailureElapsedMs;

    private MlsPeerHealthRecord(final int streak, final long lastFailureElapsedMs) {
        mStreak = streak;
        mLastFailureElapsedMs = lastFailureElapsedMs;
    }

    /**
     * Parse a stored record.
     *
     * @return {@link #NONE} when there is nothing stored, a record when it parses, or {@code null}
     *     when something IS stored and cannot be read.
     */
    public static MlsPeerHealthRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return NONE;
        final int bar = raw.indexOf('|');
        if (bar < 0 || !VERSION.equals(raw.substring(0, bar))) return null;
        final int at = raw.indexOf('@', bar + 1);
        if (at < 0) return null;
        final int streak;
        final long last;
        try {
            streak = Integer.parseInt(raw.substring(bar + 1, at));
            last = Long.parseLong(raw.substring(at + 1));
        } catch (final NumberFormatException e) {
            return null;
        }
        if (streak < 0 || streak > MAX_CONSECUTIVE_FAILURES || last < 0L) return null;
        return streak == 0 ? NONE : new MlsPeerHealthRecord(streak, last);
    }

    /** The stored form. Round-trips through {@link #decode}. */
    public String encode() {
        return VERSION + "|" + mStreak + "@" + mLastFailureElapsedMs;
    }

    /**
     * The streak as it stands now: 0 once the last report is older than {@link #EVIDENCE_MS}.
     *
     * <p>Saturates at {@link #MAX_CONSECUTIVE_FAILURES}. Past the trip point the only question is
     * whether it is tripped, and an uncapped counter would write to disk on every report of a burst
     * to record a number nothing reads.
     */
    public int streakAt(final long nowElapsedMs) {
        if (mStreak == 0) return 0;
        return stale(nowElapsedMs) ? 0 : mStreak;
    }

    /** Whether the last failure this record names is older than the evidence window. */
    public boolean stale(final long nowElapsedMs) {
        return mStreak != 0
                && MlsMonotonicAge.ageMs(mLastFailureElapsedMs, nowElapsedMs) > EVIDENCE_MS;
    }

    /** Whether state changes toward this peer must stop. */
    public boolean tripped(final long nowElapsedMs) {
        return streakAt(nowElapsedMs) >= MAX_CONSECUTIVE_FAILURES;
    }

    /**
     * This record plus one failure reported at {@code nowElapsedMs}.
     *
     * <p>A stale record restarts at 1 rather than resuming: "consecutive" is a claim about a run of
     * failures, and a report a day of uptime after the last one is the start of a new run, not the
     * fourth of an old one.
     *
     * <p>Returns {@code this} — nothing to write — when the streak has already saturated and the
     * stamp was refreshed less than {@link #STAMP_COALESCE_MS} ago.
     */
    public MlsPeerHealthRecord withFailureAt(final long nowElapsedMs) {
        final int base = streakAt(nowElapsedMs);
        if (base >= MAX_CONSECUTIVE_FAILURES
                && MlsMonotonicAge.ageMs(mLastFailureElapsedMs, nowElapsedMs) < STAMP_COALESCE_MS) {
            return this;
        }
        return new MlsPeerHealthRecord(Math.min(base + 1, MAX_CONSECUTIVE_FAILURES), nowElapsedMs);
    }

    /** Whether writing this record back would change anything the store already holds. */
    public boolean sameAs(final MlsPeerHealthRecord other) {
        return other != null && other.mStreak == mStreak
                && other.mLastFailureElapsedMs == mLastFailureElapsedMs;
    }
}
