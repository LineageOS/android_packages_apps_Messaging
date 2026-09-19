/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Guard G4's durable peer-health streak: consecutive operations the peer itself reported as failed
 * (RCC.16 §7.7.2.2). It expires {@link #EVIDENCE_MS} of uptime after the last report, and
 * {@link #decode} answers {@code null} for an unreadable record, which the caller must not treat as
 * healthy. See docs/mls/budgets.md.
 */
public final class MlsPeerHealthRecord {

    /** Consecutive peer-reported failures before state changes toward that peer stop. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** How long a streak outlives the last failure the peer reported: one day of uptime. */
    public static final long EVIDENCE_MS = MlsEraBudgetRecord.DAY_MS;

    /**
     * Once saturated, the evidence stamp is refreshed at most this often, so a burst of reports is
     * not a burst of synchronous writes.
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
     * The streak now: 0 once the last report is older than {@link #EVIDENCE_MS}. Saturates at
     * {@link #MAX_CONSECUTIVE_FAILURES}.
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
     * This record plus one failure at {@code nowElapsedMs}. A stale record restarts at 1. Returns
     * {@code this} when saturated and the stamp is younger than {@link #STAMP_COALESCE_MS}.
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
