/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * {@link MlsClaimLedger}'s counters for one peer, persisted so a crash loop cannot reset them and
 * stamped with {@code elapsedRealtime} so a wall-clock jump cannot refill them.
 * See docs/mls/budgets.md.
 */
public final class MlsClaimLedgerRecord {

    public static final MlsClaimLedgerRecord EMPTY =
            new MlsClaimLedgerRecord(new long[0], new int[0]);

    /** Well above any live allowance, so a record written with larger rations stays readable. */
    private static final int MAX_ENTRIES = 256;

    private static final String VERSION = "1";

    /** Oldest first. */
    private final long[] mChargedElapsedMs;
    /** {@link MlsClaimLedger.Caller#ordinal()} of the charge at the same index. */
    private final int[] mCaller;

    private MlsClaimLedgerRecord(final long[] chargedElapsedMs, final int[] caller) {
        mChargedElapsedMs = chargedElapsedMs;
        mCaller = caller;
    }

    /**
     * Parses {@code 1|<elapsed>:<callerOrdinal>,…}. Ordinals are persisted, so {@link
     * MlsClaimLedger.Caller} is append-only; an unknown ordinal is dropped.
     *
     * @return {@link #EMPTY} when nothing is stored, or null when what is stored cannot be read
     */
    public static MlsClaimLedgerRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        final int bar = raw.indexOf('|');
        if (bar < 0 || !VERSION.equals(raw.substring(0, bar))) return null;
        final String body = raw.substring(bar + 1);
        if (body.isEmpty()) return EMPTY;
        final String[] parts = body.split(",", -1);
        if (parts.length > MAX_ENTRIES) return null;
        final int callers = MlsClaimLedger.Caller.values().length;
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
            if (c >= callers) continue;
            when[kept] = t;
            who[kept] = c;
            kept++;
        }
        if (kept == parts.length) return new MlsClaimLedgerRecord(when, who);
        final long[] w = new long[kept];
        final int[] h = new int[kept];
        System.arraycopy(when, 0, w, 0, kept);
        System.arraycopy(who, 0, h, 0, kept);
        return new MlsClaimLedgerRecord(w, h);
    }

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

    public int spentBy(final MlsClaimLedger.Caller caller, final long nowElapsedMs) {
        if (caller == null) return 0;
        int n = 0;
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            if (mCaller[i] == caller.ordinal()
                    && MlsMonotonicAge.ageMs(mChargedElapsedMs[i], nowElapsedMs)
                            <= MlsClaimLedger.WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /** Unlike {@link MlsFetchLedgerRecord}, no caller is excluded; that would undercount. */
    public int spentAgainstCeiling(final long nowElapsedMs) {
        final int callers = MlsClaimLedger.Caller.values().length;
        int n = 0;
        for (int i = 0; i < mChargedElapsedMs.length; i++) {
            final int o = mCaller[i];
            if (o < 0 || o >= callers) continue;
            if (MlsMonotonicAge.ageMs(mChargedElapsedMs[i], nowElapsedMs)
                    <= MlsClaimLedger.WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /** Returns {@code this} when nothing aged out, so the caller can skip a write. */
    public MlsClaimLedgerRecord pruned(final long nowElapsedMs) {
        int keep = 0;
        for (final long t : mChargedElapsedMs) {
            if (MlsMonotonicAge.ageMs(t, nowElapsedMs) <= MlsClaimLedger.WINDOW_MS) keep++;
        }
        if (keep == mChargedElapsedMs.length) return this;
        if (keep == 0) return EMPTY;
        final long[] when = new long[keep];
        final int[] who = new int[keep];
        int i = 0;
        for (int j = 0; j < mChargedElapsedMs.length; j++) {
            if (MlsMonotonicAge.ageMs(mChargedElapsedMs[j], nowElapsedMs)
                    <= MlsClaimLedger.WINDOW_MS) {
                when[i] = mChargedElapsedMs[j];
                who[i] = mCaller[j];
                i++;
            }
        }
        return new MlsClaimLedgerRecord(when, who);
    }

    /** Prunes first, then trims the oldest past {@link #MAX_ENTRIES} (unrefusable callers). */
    public MlsClaimLedgerRecord charged(final MlsClaimLedger.Caller caller,
            final long nowElapsedMs) {
        if (caller == null) return this;
        final MlsClaimLedgerRecord kept = pruned(nowElapsedMs);
        final int drop = Math.max(0, kept.mChargedElapsedMs.length + 1 - MAX_ENTRIES);
        final int n = kept.mChargedElapsedMs.length - drop;
        final long[] when = new long[n + 1];
        final int[] who = new int[n + 1];
        System.arraycopy(kept.mChargedElapsedMs, drop, when, 0, n);
        System.arraycopy(kept.mCaller, drop, who, 0, n);
        when[n] = nowElapsedMs;
        who[n] = caller.ordinal();
        return new MlsClaimLedgerRecord(when, who);
    }

    /**
     * Removes the matching charge, or returns {@code this} when there is none, so a double refund
     * credits once. Does not prune: {@code r.charged(c, t).refunded(c, t)} equals
     * {@code r.pruned(t)}.
     */
    public MlsClaimLedgerRecord refunded(final MlsClaimLedger.Caller caller,
            final long chargedAtElapsedMs) {
        if (caller == null) return this;
        // Caller and stamp: two callers can share a millisecond. A fresh charge is last.
        int at = -1;
        for (int i = mChargedElapsedMs.length - 1; i >= 0; i--) {
            if (mChargedElapsedMs[i] == chargedAtElapsedMs && mCaller[i] == caller.ordinal()) {
                at = i;
                break;
            }
        }
        if (at < 0) return this;
        final int n = mChargedElapsedMs.length - 1;
        if (n == 0) return EMPTY;
        final long[] when = new long[n];
        final int[] who = new int[n];
        System.arraycopy(mChargedElapsedMs, 0, when, 0, at);
        System.arraycopy(mCaller, 0, who, 0, at);
        System.arraycopy(mChargedElapsedMs, at + 1, when, at, n - at);
        System.arraycopy(mCaller, at + 1, who, at, n - at);
        return new MlsClaimLedgerRecord(when, who);
    }
}
