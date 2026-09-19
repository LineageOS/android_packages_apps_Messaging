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
 * {@link MlsFetchLedger}'s counters, in the form that survives a process restart — following the
 * durable-record rule and {@link MlsEraBudgetRecord}'s shape.
 *
 * <h2>Why this is durable, when {@link MlsFetchBudget}'s counter was a local {@code int[]}</h2>
 *
 * <p>Because the bound it enforces is not ours. <b>The throttle lives on Tachyon's side and does not
 * care that we restarted.</b> A ledger that forgot its spend on every process death would hand back
 * a full allowance precisely in the situation that most needs it — a recovery ladder that crashes is
 * a burst whose accountant resets — and the burst would then re-provoke the
 * {@code RESOURCE_EXHAUSTED} the ledger exists to prevent, with nothing in the log saying why.
 *
 * <p>The per-drive counter was correctly transient: it bounded ONE drive, and a drive
 * does not outlive the process. This one bounds a conversation over a window, and the window does.
 *
 * <h2>The clock</h2>
 *
 * <p>Charges are stamped with {@code SystemClock.elapsedRealtime()} and aged by
 * {@link MlsMonotonicAge}, never the wall clock. A durable budget aged by
 * {@code System.currentTimeMillis()} is refilled by any large enough clock jump, silently — see that
 * class for the rule and the price it charges after a reboot.
 *
 * <h2>UNREADABLE is not ABSENT</h2>
 *
 * <p>{@link #decode} answers {@link #EMPTY} for a record that is not there and {@code null} for one
 * it cannot read. They must not share a value: an unreadable record read as "no charges" hands back
 * a full allowance on the strength of a parse failure. The host's posture on {@code null} is its own
 * decision and is stated where it is taken.
 */
public final class MlsFetchLedgerRecord {

    /** The record that is not there. */
    public static final MlsFetchLedgerRecord EMPTY = new MlsFetchLedgerRecord(new long[0], new int[0]);

    /**
     * A ceiling on what {@link #decode} will accept. Generously above any live allowance because a
     * record written by a build with larger rations must still be READABLE — refusing it would spend
     * a refusal on a version skew rather than on a real fault.
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
     * Parse a stored record.
     *
     * <p>The stored form is {@code 1|<elapsed>:<callerOrdinal>,…}. Caller ORDINALS rather than names
     * because the record is rewritten on every charge and the name is recoverable; an ordinal that
     * no longer exists — a constant removed between builds — is dropped rather than refused, because
     * a stale charge for a door that is gone is genuinely uncountable and refusing the whole record
     * over it would deny every other caller too.
     *
     * @return {@link #EMPTY} when there is nothing stored, a record when it parses, or {@code null}
     *     when something IS stored and cannot be read.
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
            // A charge for a caller this build no longer has. Not a parse failure — see the javadoc.
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
     * Charges within the window by every caller that {@link
     * MlsFetchLedger.Caller#chargesTheSharedCeiling}.
     *
     * <p>The exempt readers are excluded from the SUM as well as from the test, deliberately. If
     * they counted toward the total they would be refusing recovery without ever being refused
     * themselves, which is the starvation D3 rejected wearing the opposite hat.
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

    /**
     * The record with everything older than the window dropped.
     *
     * <p>Returns {@code this} when nothing changed so the caller can skip a write — the store is
     * consulted far more often than it is charged.
     */
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
     * The record with one more charge recorded at {@code nowElapsedMs}.
     *
     * <p>Prunes first, and trims from the OLDEST if the result would exceed {@link #MAX_ENTRIES}, so
     * a record cannot grow without bound. In normal operation there is nothing to trim: the rations
     * and the ceiling refuse long before that, and the exempt debug arm is the only caller that can
     * charge past a refusal — which it does not, because it charges nothing at all.
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
