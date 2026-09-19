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
 * G2's era-advance budget, in the form that survives a process restart.
 *
 * <h2>Why this is durable at all</h2>
 *
 * <p>G2 bounds the operation that makes every member of a conversation RE-JOIN BY WELCOME — the era
 * advance, the automatic rebuild, the 1:1 reclaim. It exists because ~17 of those on one
 * conversation wedged a real person's phone for a month, and those 17 ran over
 * three weeks, i.e. across many process lifetimes.
 *
 * <p>Until this class the budget was a static {@code ArrayDeque} in {@code MlsPeerGuard}, so it
 * forgot everything each time the app died — and <b>the loop it bounds is one that kills the
 * process</b>. A crash-restart loop spent an unlimited number of G2 slots while
 * {@code MlsRebuildLimiter}, on disk for exactly that reason, kept counting. The counter reset
 * precisely when it was most needed, which makes it a speed bump rather than a circuit breaker.
 *
 * <h2>A RATE, never a total</h2>
 *
 * <p>Two paired bounds, {@link #MAX_PER_HOUR} within an hour and {@link #MAX_PER_DAY} within a day,
 * both rolling. Nothing here is terminal: an entry ages out and the allowance comes back, which is
 * a rolling window rather than a total — a budget that can be permanently spent converts a transient
 * fault into a dead conversation.
 *
 * <h2>The clock</h2>
 *
 * <p>Entries are stamped with {@code SystemClock.elapsedRealtime()} and aged by
 * {@link MlsMonotonicAge}, NOT with the wall clock. A durable budget aged by
 * {@code System.currentTimeMillis()} is refilled by any large enough clock jump, silently. See
 * {@link MlsMonotonicAge} for the rule and for the price it charges after a reboot.
 *
 * <p>The wall clock is not stored alongside the elapsed reading, deliberately: a field that is
 * present but must never be used in arithmetic is an invitation, and "when did this happen" is
 * recoverable anyway as {@code now - age}.
 *
 * <h2>UNREADABLE is not ABSENT</h2>
 *
 * <p>{@link #decode} answers {@link #EMPTY} for a record that is not there and {@code null} for one
 * it cannot read. They must not share a value: an unreadable record read as "no charges" hands back
 * a full allowance on the strength of a parse failure, which is the same defect as the empty-group-id
 * arm that let every 1:1 through uncharged.
 */
public final class MlsEraBudgetRecord {

    /** Advances allowed within {@link #HOUR_MS}. */
    public static final int MAX_PER_HOUR = 2;
    /** Advances allowed within {@link #DAY_MS}. */
    public static final int MAX_PER_DAY = 5;

    public static final long HOUR_MS = 60L * 60L * 1000L;
    public static final long DAY_MS = 24L * HOUR_MS;

    /** The record that is not there. */
    public static final MlsEraBudgetRecord EMPTY = new MlsEraBudgetRecord(new long[0]);

    /**
     * A ceiling on what {@link #decode} will accept, so a corrupt or hostile file cannot turn one
     * budget lookup into an unbounded parse. Generously above {@link #MAX_PER_DAY} because a record
     * written by a build with a larger allowance must still be READABLE — refusing it would spend a
     * refusal on a version skew rather than on a real fault.
     */
    private static final int MAX_ENTRIES = 64;

    private static final String VERSION = "1";

    /** Charge stamps, {@code elapsedRealtime} at the moment of the charge, oldest first. */
    private final long[] mChargedElapsedMs;

    private MlsEraBudgetRecord(final long[] chargedElapsedMs) {
        mChargedElapsedMs = chargedElapsedMs;
    }

    /**
     * Parse a stored record.
     *
     * @return {@link #EMPTY} when there is nothing stored, a record when it parses, or {@code null}
     *     when something IS stored and cannot be read — a case the caller must handle as a refusal,
     *     never as an empty budget.
     */
    public static MlsEraBudgetRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        final int bar = raw.indexOf('|');
        if (bar < 0 || !VERSION.equals(raw.substring(0, bar))) return null;
        final String body = raw.substring(bar + 1);
        if (body.isEmpty()) return EMPTY;
        final String[] parts = body.split(",", -1);
        if (parts.length > MAX_ENTRIES) return null;
        final long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (final NumberFormatException e) {
                return null;
            }
            if (out[i] < 0L) return null;
        }
        return new MlsEraBudgetRecord(out);
    }

    /** The stored form. Round-trips through {@link #decode}. */
    public String encode() {
        final StringBuilder b = new StringBuilder(VERSION).append('|');
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            if (i > 0) b.append(',');
            b.append(mChargedElapsedMs[i]);
        }
        return b.toString();
    }

    public boolean isEmpty() {
        return mChargedElapsedMs.length == 0;
    }

    public int size() {
        return mChargedElapsedMs.length;
    }

    /** Charges recorded within the last {@code windowMs}, aged by {@link MlsMonotonicAge}. */
    public int countWithin(final long windowMs, final long nowElapsedMs) {
        int n = 0;
        for (final long t : mChargedElapsedMs) {
            if (MlsMonotonicAge.ageMs(t, nowElapsedMs) <= windowMs) n++;
        }
        return n;
    }

    /** Whether either bound is exhausted, i.e. the caller must NOT advance now. */
    public boolean spent(final long nowElapsedMs) {
        return countWithin(HOUR_MS, nowElapsedMs) >= MAX_PER_HOUR
                || countWithin(DAY_MS, nowElapsedMs) >= MAX_PER_DAY;
    }

    /**
     * The record with everything older than the day window dropped.
     *
     * <p>Returns {@code this} when nothing changed so the caller can skip a write — the store is
     * consulted far more often than it is charged, and a prune is recomputable from the stamps, so
     * persisting one buys nothing.
     */
    public MlsEraBudgetRecord pruned(final long nowElapsedMs) {
        int keep = 0;
        for (final long t : mChargedElapsedMs) {
            if (MlsMonotonicAge.ageMs(t, nowElapsedMs) <= DAY_MS) keep++;
        }
        if (keep == mChargedElapsedMs.length) return this;
        if (keep == 0) return EMPTY;
        final long[] out = new long[keep];
        int i = 0;
        for (final long t : mChargedElapsedMs) {
            if (MlsMonotonicAge.ageMs(t, nowElapsedMs) <= DAY_MS) out[i++] = t;
        }
        return new MlsEraBudgetRecord(out);
    }

    /**
     * The record with one more charge recorded at {@code nowElapsedMs}.
     *
     * <p>Prunes first, and keeps at most {@link #MAX_PER_DAY} stamps so a record cannot grow without
     * bound even if it arrives oversized from a build with a different allowance. The trim drops the
     * EARLIEST RECORDED — insertion order, which is age order within one boot and is only ever
     * consulted here. In normal operation there is nothing to trim, because callers ask
     * {@link #spent} first and it refuses at {@link #MAX_PER_DAY}.
     */
    public MlsEraBudgetRecord charged(final long nowElapsedMs) {
        final long[] kept = pruned(nowElapsedMs).mChargedElapsedMs;
        final int drop = Math.max(0, kept.length + 1 - MAX_PER_DAY);
        final long[] out = new long[kept.length - drop + 1];
        System.arraycopy(kept, drop, out, 0, kept.length - drop);
        out[out.length - 1] = nowElapsedMs;
        return new MlsEraBudgetRecord(out);
    }
}
