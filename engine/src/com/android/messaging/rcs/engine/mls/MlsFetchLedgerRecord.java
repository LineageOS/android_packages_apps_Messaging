/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * {@link MlsFetchLedger}'s counters, durable across a process restart: the server's throttle does
 * not reset when we do. Stored as {@code 1|<elapsed>:<callerOrdinal>,...}, oldest first; stamped
 * with {@code elapsedRealtime} and aged by {@link MlsMonotonicAge}. {@link #decode} answers
 * {@link #EMPTY} for nothing stored and {@code null} for unreadable. See docs/mls/budgets.md.
 */
public final class MlsFetchLedgerRecord {

    /** The record that is not there. */
    public static final MlsFetchLedgerRecord EMPTY =
            new MlsFetchLedgerRecord(new long[0], new int[0]);

    /**
     * A ceiling on what {@link #decode} accepts, generously above any live allowance so a record
     * written by a build with larger rations is still readable.
     */
    private static final int MAX_ENTRIES = 256;

    private static final String VERSION = "1";

    /** Charge stamps, {@code elapsedRealtime} at the moment of the charge, oldest first. */
    private final long[] mChargedElapsedMs;
    /** {@link MlsFetchLedger.Caller#ordinal()} of the charge at the same index. */
    private final int[] mCaller;

    private MlsFetchLedgerRecord(final long[] chargedElapsedMs, final int[] caller) {
        mChargedElapsedMs = chargedElapsedMs;
        mCaller = caller;
    }

    /**
     * Parse a stored record. Entries whose caller ordinal this build does not have are dropped
     * rather than refused: a charge for a removed caller is uncountable, and refusing the whole
     * record would deny every other caller.
     *
     * @return {@link #EMPTY} when nothing is stored, a record when it parses, or {@code null} when
     *     something is stored and cannot be read
     */
    public static MlsFetchLedgerRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        final int bar = raw.indexOf('|');
        if (bar < 0 || !VERSION.equals(raw.substring(0, bar))) return null;
        final String body = raw.substring(bar + 1);
        if (body.isEmpty()) return EMPTY;
        final String[] parts = body.split(",", -1);
        if (parts.length > MAX_ENTRIES) return null;
        final int callers = MlsFetchLedger.Caller.values().length;
        final long[] when = new long[parts.length];
        final int[] who = new int[parts.length];
        int kept = 0;
        for (final String part : parts) {
            final int colon = part.indexOf(':');
            if (colon <= 0 || colon == part.length() - 1) return null;
            final long t;
            final int c;
            try {
                t = Long.parseLong(part.substring(0, colon));
                c = Integer.parseInt(part.substring(colon + 1));
            } catch (final NumberFormatException e) {
                return null;
            }
            if (t < 0L || c < 0) return null;
            // A caller this build no longer has; not a parse failure.
            if (c >= callers) continue;
            when[kept] = t;
            who[kept] = c;
            kept++;
        }
        if (kept == parts.length) return new MlsFetchLedgerRecord(when, who);
        final long[] w = new long[kept];
        final int[] h = new int[kept];
        System.arraycopy(when, 0, w, 0, kept);
        System.arraycopy(who, 0, h, 0, kept);
        return new MlsFetchLedgerRecord(w, h);
    }

    /** The stored form. Round-trips through {@link #decode}. */
    public String encode() {
        final StringBuilder b = new StringBuilder(VERSION).append('|');
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            if (i > 0) b.append(',');
            b.append(mChargedElapsedMs[i]).append(':').append(mCaller[i]);
        }
        return b.toString();
    }

    public boolean isEmpty() {
        return mChargedElapsedMs.length == 0;
    }

    public int size() {
        return mChargedElapsedMs.length;
    }

    /** Charges by {@code caller} within {@link MlsFetchLedger#WINDOW_MS}. */
    public int spentBy(final MlsFetchLedger.Caller caller, final long nowElapsedMs) {
        if (caller == null) return 0;
        int n = 0;
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            if (mCaller[i] == caller.ordinal()
                    && MlsMonotonicAge.ageMs(mChargedElapsedMs[i], nowElapsedMs)
                            <= MlsFetchLedger.WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /**
     * Charges within the window by callers that {@link
     * MlsFetchLedger.Caller#chargesTheSharedCeiling}. The others are excluded from the sum as well
     * as the test, so they never refuse recovery.
     */
    public int spentAgainstCeiling(final long nowElapsedMs) {
        final MlsFetchLedger.Caller[] all = MlsFetchLedger.Caller.values();
        int n = 0;
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            final int o = mCaller[i];
            if (o < 0 || o >= all.length) continue;
            if (!all[o].chargesTheSharedCeiling) continue;
            if (MlsMonotonicAge.ageMs(mChargedElapsedMs[i], nowElapsedMs)
                    <= MlsFetchLedger.WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /** The record with everything older than the window dropped; {@code this} when unchanged. */
    public MlsFetchLedgerRecord pruned(final long nowElapsedMs) {
        int keep = 0;
        for (final long t : mChargedElapsedMs) {
            if (MlsMonotonicAge.ageMs(t, nowElapsedMs) <= MlsFetchLedger.WINDOW_MS) keep++;
        }
        if (keep == mChargedElapsedMs.length) return this;
        if (keep == 0) return EMPTY;
        final long[] when = new long[keep];
        final int[] who = new int[keep];
        int i = 0;
        for (int j = 0; j < mChargedElapsedMs.length; j++) {
            if (MlsMonotonicAge.ageMs(mChargedElapsedMs[j], nowElapsedMs)
                    <= MlsFetchLedger.WINDOW_MS) {
                when[i] = mChargedElapsedMs[j];
                who[i] = mCaller[j];
                i++;
            }
        }
        return new MlsFetchLedgerRecord(when, who);
    }

    /**
     * The record with one more charge at {@code nowElapsedMs}. Prunes first and trims from the
     * oldest past {@link #MAX_ENTRIES}.
     */
    public MlsFetchLedgerRecord charged(final MlsFetchLedger.Caller caller,
            final long nowElapsedMs) {
        if (caller == null) return this;
        final MlsFetchLedgerRecord kept = pruned(nowElapsedMs);
        final int drop = Math.max(0, kept.mChargedElapsedMs.length + 1 - MAX_ENTRIES);
        final int n = kept.mChargedElapsedMs.length - drop;
        final long[] when = new long[n + 1];
        final int[] who = new int[n + 1];
        System.arraycopy(kept.mChargedElapsedMs, drop, when, 0, n);
        System.arraycopy(kept.mCaller, drop, who, 0, n);
        when[n] = nowElapsedMs;
        who[n] = caller.ordinal();
        return new MlsFetchLedgerRecord(when, who);
    }
}
