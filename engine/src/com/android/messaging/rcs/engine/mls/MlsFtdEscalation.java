/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * The RCC.16 §10 failure-to-decrypt ladder: inbound decrypt failures and the FTD reports they
 * produce, inbound negative-delivery reports about our messages, and when a repeated report stops
 * drawing resends and becomes a group repair. Three bounds apply to one event and are deliberately
 * independent: {@link #ESCALATE_AT}/{@link #WINDOW_MS}, {@link MlsResendBudget}, and
 * {@link #MAX_FTD_ATTEMPTS}. Both {@link ReportClass} rules read the same resend-ledger rows, so a
 * resend drawn by either counts toward both. See docs/mls/health-and-recovery.md.
 */
public final class MlsFtdEscalation {

    private MlsFtdEscalation() {}

    /**
     * Repeat resends to one peer in one conversation, within {@link #WINDOW_MS}, before we stop
     * resending and repair the group: one report is plausibly a lost message, a second about
     * material already resent means the peer's state is the problem. Equal to {@link
     * MlsResendBudget#MAX_PER_WINDOW} today, but not derived from it.
     */
    public static final int ESCALATE_AT = 2;

    /**
     * RCC.16 §10.3's chain cap: stop reporting a message after 5 attempts. Past it RCC.16 hands off
     * to plaintext fallback, which the app does not do automatically. The default of
     * {@link MlsConfig#ftdMaxAttempts}, which production reads.
     */
    public static final int MAX_FTD_ATTEMPTS = 5;

    /**
     * Has this chain passed the cap?
     *
     * @param attempts    this message's attempt number, the one about to be sent included
     * @param maxAttempts the effective cap, {@link MlsConfig#ftdMaxAttempts}
     */
    public static boolean chainExhausted(final int attempts, final int maxAttempts) {
        return attempts > maxAttempts;
    }

    /**
     * The window the repeat count is taken over: one hour, its own value rather than
     * {@link MlsResendBudget#WINDOW_MS}. Long enough that a diverged peer cannot walk the rung down
     * by waiting, short enough that two unrelated failures hours apart are not one broken peer.
     */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the escalation window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * Does this report escalate to a group repair?
     *
     * @param repeatResendsInWindow repeat resends this peer has drawn in this conversation inside
     *     {@link #WINDOW_MS}, counting the one this report would draw
     */
    public static boolean escalates(final int repeatResendsInWindow) {
        return repeatResendsInWindow >= ESCALATE_AT;
    }

    /** Which ledger count a report class is judged on. */
    public enum Counting {
        /**
         * {@code MlsResendLedger.repeatResendsToPeerSince}: rows beyond the first per chain. A loop
         * is repeats on one chain, while N distinct messages each get their one resend.
         */
        REPEAT_RESENDS,
        /**
         * {@code MlsResendLedger.resendsToPeerSince}: every row, for an arm that resends without a
         * cause.
         */
        TOTAL_RESENDS,
    }

    /** What happens when a report class reaches its cap. */
    public enum AtTheCap {
        /** Stop resending and repair the group: an era advance, which re-Welcomes every member. */
        ESCALATE_TO_A_GROUP_REPAIR,
        /** Stop, and do nothing else. */
        STOP,
    }

    /**
     * The classes of RCC.16 §7.7.2.2 negative-delivery report that draw a §10.3 resend, and the
     * rule each is judged by. Walked by a host test, so a new class cannot inherit a terminal
     * action by omission.
     */
    public enum ReportClass {
        /**
         * Reason 4, {@code failed-to-decrypt}: repeated failures about resent material are evidence
         * the peer's state is broken, so it escalates.
         */
        DECRYPT_FAILURE(Counting.REPEAT_RESENDS, AtTheCap.ESCALATE_TO_A_GROUP_REPAIR),
        /**
         * {@code <failed/>} with no reason, the common shape from peers. Stops rather than
         * escalating: a report with no cause is not evidence for mutating group state.
         */
        NO_REASON_GIVEN(Counting.TOTAL_RESENDS, AtTheCap.STOP);

        private final Counting mCounting;
        private final AtTheCap mAtTheCap;

        ReportClass(final Counting counting, final AtTheCap atTheCap) {
            mCounting = counting;
            mAtTheCap = atTheCap;
        }

        public Counting counting() {
            return mCounting;
        }

        public AtTheCap atTheCap() {
            return mAtTheCap;
        }

        /** Whether this class may reach {@link MlsFtdEscalation#escalates(int)} at all. */
        public boolean mayEscalate() {
            return mAtTheCap == AtTheCap.ESCALATE_TO_A_GROUP_REPAIR;
        }
    }

    /** One line for the log, naming the rule this report class is being judged by. */
    public static String line(final ReportClass c, final int countInWindow) {
        return c + " is judged on " + c.counting() + " (" + countInWindow + "/"
                + (c.mayEscalate() ? ESCALATE_AT : MlsResendBudget.MAX_PER_WINDOW)
                + ") and at its cap it will " + c.atTheCap();
    }
}
