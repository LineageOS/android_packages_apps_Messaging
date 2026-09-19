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
 * {@link MlsClaimLedger}'s counters for ONE PEER, in the form that survives a process restart —
 * following the durable-record rule and {@link MlsFetchLedgerRecord}'s shape.
 *
 * <h2>Why this is durable, and why it is more obviously so than the fetch ledger's</h2>
 *
 * <p>{@link MlsFetchLedgerRecord} is durable because Tachyon's throttle does not care that we
 * restarted. This one is durable for a stronger reason: <b>the packages are gone</b>. A pool
 * accountant that forgot its charges on every process death would hand back a full allowance in the
 * one situation that most needs it — a crash loop is a burst whose accountant resets — and the peer
 * would be drained with nothing in the log saying why.
 *
 * <h2>The clock</h2>
 *
 * <p>Charges are stamped with {@code SystemClock.elapsedRealtime()} and aged by
 * {@link MlsMonotonicAge}, never the wall clock. A durable budget aged by
 * {@code System.currentTimeMillis()} is refilled by any large enough clock jump, silently.
 *
 * <h2>UNREADABLE is not ABSENT</h2>
 *
 * <p>{@link #decode} answers {@link #EMPTY} for a record that is not there and {@code null} for one
 * it cannot read. They must not share a value: an unreadable record read as "no charges" hands back
 * a full allowance on the strength of a parse failure. The host's posture on {@code null} is its own
 * decision and is stated where it is taken.
 */
public final class MlsClaimLedgerRecord {

    /** The record that is not there. */
    public static final MlsClaimLedgerRecord EMPTY =
            new MlsClaimLedgerRecord(new long[0], new int[0]);

    /**
     * A ceiling on what {@link #decode} will accept. Generously above any live allowance because a
     * record written by a build with larger rations must still be READABLE — refusing it would spend
     * a refusal on a version skew rather than on a real fault.
     */
    private static final int MAX_ENTRIES = 256;

    private static final String VERSION = "1";

    /** Charge stamps, {@code elapsedRealtime} at the moment of the charge, oldest first. */
    private final long[] mChargedElapsedMs;
    /** {@link MlsClaimLedger.Caller#ordinal()} of the charge at the same index. */
    private final int[] mCaller;

    private MlsClaimLedgerRecord(final long[] chargedElapsedMs, final int[] caller) {
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
            // A charge for a caller this build no longer has. Not a parse failure — see the javadoc.
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

    /** Charges by {@code caller} within {@link MlsClaimLedger#WINDOW_MS}. */
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

    /**
     * Charges within the window by EVERY caller.
     *
     * <p>No caller is excluded, and the difference from {@link MlsFetchLedgerRecord} is deliberate:
     * there the health readers are held outside the sum so recovery cannot starve a diagnostic. Here
     * the diagnostic is {@link MlsClaimLedger.Caller#UNREFUSABLE} and cannot be starved by anything,
     * so excluding it would only make the count wrong about a pool that really was emptied.
     */
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

    /**
     * The record with everything older than the window dropped.
     *
     * <p>Returns {@code this} when nothing changed so the caller can skip a write — the store is
     * consulted far more often than it is charged.
     */
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

    /**
     * The record with one more charge recorded at {@code nowElapsedMs}.
     *
     * <p>Prunes first, and trims from the OLDEST if the result would exceed {@link #MAX_ENTRIES}, so
     * a record cannot grow without bound. Unlike the fetch ledger's, this one HAS a caller that can
     * charge past a refusal — every {@link MlsClaimLedger.Caller#isUnrefusable()} arm does, which is
     * the whole point of them — so the trim is not merely theoretical here.
     */
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
     * The record with ONE NAMED CHARGE REVERSED — the entry {@link #charged} wrote for
     * {@code caller} at {@code chargedAtElapsedMs}, and nothing else.
     *
     * <h2>Why a ledger that charges before it asks needs this at all</h2>
     *
     * <p>{@code MlsProviderTransport.spendOneClaim} charges BEFORE it dials, deliberately: a claim
     * that HAPPENED and went uncounted leaves the next caller told the pool is fuller than it is,
     * and that direction spends somebody else's key material. The mirror was
     * never handled. Contract v60's {@code OUTCOME_NOT_ATTEMPTED} asserts that <b>no dial was
     * spent</b> — an unbound provider, or one predating the contract — so its charge is a phantom,
     * and a phantom charge is not harmless: four of them inside the window make this ledger REFUSE a
     * real claim, at exactly the moment a provider has come back and the first real claim matters.
     * Device-measured: two such claims took a peer from 1 to 2 of 4.
     *
     * <h2>It is a REVERSAL, not a credit</h2>
     *
     * <p>It removes the entry matching BOTH the caller and the stamp, and a refund that cannot find
     * its charge returns {@code this} unchanged. Handing back an allowance that cannot be matched to
     * a charge is how a pool accountant starts under-counting a pool that really was drained — the
     * direction this whole class exists to forbid (see {@link #decode}'s UNREADABLE rule). So a
     * double refund credits once, and a refund of a charge that was pruned away credits nothing.
     *
     * <p><b>It does NOT prune</b>, so it is an exact inverse: {@code r.charged(c, t).refunded(c, t)}
     * reproduces {@code r.pruned(t)}, which the tests pin as a round trip rather than asserting the
     * count went down by one. A count is satisfied by removing the WRONG entry; the round trip is
     * not. The one case where it is not an inverse is a record already at {@link #MAX_ENTRIES},
     * where {@code charged} also dropped the oldest — that trim is not reversible and is not
     * pretended to be.
     */
    public MlsClaimLedgerRecord refunded(final MlsClaimLedger.Caller caller,
            final long chargedAtElapsedMs) {
        if (caller == null) return this;
        // BOTH halves of the key are required, and the CALLER half is the one that does work. Two
        // callers sharing a millisecond is ordinary — elapsedRealtime is not a unique key — so a
        // match on the stamp alone reverses whichever charge happens to be adjacent.
        //
        // The DIRECTION is not doing work and is not claimed to: entries carrying the same caller
        // AND the same stamp are indistinguishable, so first-match and last-match produce identical
        // records. Measured rather than assumed — an injected forward scan left every test in
        // MlsClaimNotAttemptedTest green, which is the honest reason this loop runs backwards: a
        // just-written charge is the last entry, so the common case stops immediately.
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
