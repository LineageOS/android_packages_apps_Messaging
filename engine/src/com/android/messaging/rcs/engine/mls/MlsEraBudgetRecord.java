/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The era budget (G2), durable across a process restart: at most {@link #MAX_PER_HOUR} and
 * {@link #MAX_PER_DAY} re-creations, both rolling windows, stamped with {@code elapsedRealtime} and
 * aged by {@link MlsMonotonicAge}. Stored as {@code 1|<elapsed>,<elapsed>,...}, oldest first; the
 * wall clock is not stored. See docs/mls/budgets.md.
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
     * A ceiling on what {@link #decode} accepts, generously above {@link #MAX_PER_DAY} so a record
     * written by a build with a larger allowance is still readable.
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
     * @return {@link #EMPTY} when nothing is stored, a record when it parses, or {@code null} when
     *     something is stored and cannot be read, which the caller handles as a refusal
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
     * The record with everything older than the day window dropped; {@code this} when unchanged.
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
     * The record with one more charge at {@code nowElapsedMs}. Prunes first and keeps at most
     * {@link #MAX_PER_DAY} stamps, dropping the earliest recorded.
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
