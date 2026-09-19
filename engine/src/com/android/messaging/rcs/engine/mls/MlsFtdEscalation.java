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
 * When a repeated §7.7.2.2 failure report stops being resent and becomes a GROUP repair.
 *
 * <h2>Why this is not {@link MlsResendBudget}, and why it stopped being derived from it</h2>
 *
 * <p>These are two policies over one event, and they were tied together by a constant:
 *
 * <pre>    private static final int PEER_FTD_ESCALATE_AT = MlsResendBudget.MAX_PER_WINDOW;</pre>
 *
 * <p>We have <b>deliberately refused</b> to make exactly this kind of tie before — not deriving
 * {@code MlsFetchBudget.RECOVERY_LOOKS} from {@code MlsDriveLoop.DEFAULT_MAX_ITERATIONS} because
 * because <i>"tying them is how raising one silently raises the other"</i> — and
 * the tie was made here anyway, between an <b>escalation rung</b> and a <b>resend allowance</b>.
 * Raising the resend allowance from 2 to 3, a decision about how many times we may resend to a peer
 * in an hour, would silently move the threshold at which a repeated failure becomes an era advance:
 * the operation that re-creates the group, makes every member re-join by Welcome, and wedged a real
 * third party's phone for a month. No log line and no test would have said the
 * threshold had moved.
 *
 * <h2>The history says the equality was a COINCIDENCE, not a derivation</h2>
 *
 * <p>The rung was written first and stood on its own argument: <i>"one FTD is plausibly a single lost
 * message, but a second means resending is not working and the peer's STATE is the problem"</i>.
 * {@link MlsResendBudget#MAX_PER_WINDOW} arrived later and was chosen <b>to match it</b> — its own
 * javadoc still says so: <i>"The value matches the escalation threshold the reason-4 path already
 * used, so this change adds the window without quietly loosening the count."</i> Commit
 * {@code d0ea5999} then rewrote the rung as a reference to the allowance <i>"rather than being a
 * second copy of the number"</i>. That is de-duplication applied to two numbers that were equal by
 * accident, and it inverted which policy owns the value.
 *
 * <p>So the rung is given its value back, with its own justification, and its own window for the same
 * reason — a shared {@code WINDOW_MS} is the identical defect one level down. <b>Both values are
 * unchanged</b> (2 per hour), so this changes no behaviour today; it changes what a future edit to
 * one of them does to the other, which is nothing.
 *
 * <h2>One ledger, two counting rules, opposite terminal actions</h2>
 *
 * <p>The second half of the same event, and it was reconciled only by prose in
 * {@code resendBudgetSpent}'s javadoc. Both rules read {@code MlsResendLedger} for one
 * peer-and-conversation over one window; they differ in what they count and in what they do at the
 * cap. {@link ReportClass} makes that a table a test can walk.
 *
 * <p><b>And they share the rows, which nothing said.</b> A resend drawn on either path writes one
 * ledger row, and each rule counts rows the other's resends wrote. Concretely: a reason-less report
 * and a reason-4 report on the SAME message produce two rows for one chain, which is one REPEAT — so
 * a reason-less resend can carry the escalation rung, on a path whose own rule is that it must never
 * escalate. That is defensible on the merits (a resend of a message is a resend of that message
 * whichever report triggered it, and "resending the same material is not working" is true either
 * way) and it is kept, but it is stated here rather than left to be rediscovered.
 */
public final class MlsFtdEscalation {

    private MlsFtdEscalation() {}

    /**
     * How many REPEAT resends to one peer in one conversation before we stop resending and repair the
     * GROUP instead.
     *
     * <p><b>Its own number, deliberately not {@link MlsResendBudget#MAX_PER_WINDOW}.</b> Not from the
     * spec and not from Google Messages, whose type split (app message → resend, commit
     * failure → escalate) is readable but whose numeric threshold is not, so we did not
     * guess it. Two is our choice, for the reason it has always been our choice: one failure report
     * is plausibly a single lost message, but a second report about material we have already resent
     * means resending is not working and the peer's STATE is the problem.
     *
     * <p>It is <b>2</b> today and so is the resend allowance. They are equal and that is all they
     * are: see the class javadoc for which came first.
     */
    public static final int ESCALATE_AT = 2;

    /**
     * §10.3's chain cap: <i>"The sender shall stop a repeated chain of FTDs for the same Original
     * Message after a maximum of 5 attempts."</i>
     *
     * <p>A SPEC number, and the third distinct bound over the same event — worth stating next to the
     * other two so nobody derives any of them from another again (the class javadoc has that story).
     * {@link #ESCALATE_AT} asks "is resending working?" and answers by repairing the GROUP;
     * {@link MlsResendBudget#MAX_PER_WINDOW} asks "how hard may we resend?"; this one is the point
     * past which the SPEC says stop reporting at all, and past it RCC.16 hands off to RCC.71
     * plaintext fallback — which we do not do automatically.
     *
     * <p>It is the DEFAULT of
     * {@link MlsConfig#ftdMaxAttempts}, which is what production reads: the cap is raisable for the
     * reason-token sweep, and a spec number with a lab override is exactly the
     * shape {@code MlsConfig} exists for.
     */
    public static final int MAX_FTD_ATTEMPTS = 5;

    /**
     * Has this chain passed the cap?
     *
     * @param attempts    this message's attempt number, the one about to be sent included
     * @param maxAttempts the effective cap — {@link MlsConfig#ftdMaxAttempts}, NOT
     *                    {@link #MAX_FTD_ATTEMPTS} directly, because the lab override is the whole
     *                    reason the knob exists
     */
    public static boolean chainExhausted(final int attempts, final int maxAttempts) {
        return attempts > maxAttempts;
    }

    /**
     * The window the repeat count is taken over. One hour.
     *
     * <p><b>Its own window, for the same reason as its own count.</b> A window shared with
     * {@link MlsResendBudget#WINDOW_MS} would leave half the coupling in place: widening the storm
     * window to six hours for storm reasons would also mean a repeat six hours old still carried the
     * rung towards an era advance.
     *
     * <p>Sized on its own terms. It has to be long enough that a genuinely diverged peer cannot walk
     * the rung back down by waiting a few minutes between reports, and short enough that two failures
     * either side of a lunch break are not read as one broken peer. There is no spec deadline to
     * derive it from — §10.3 says nothing about pacing — so it is a judgement, and it is written down
     * as one.
     */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the escalation window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * <b>The rung.</b> Does this report escalate to a group repair?
     *
     * @param repeatResendsInWindow how many REPEAT resends this peer has drawn in this conversation
     *     inside {@link #WINDOW_MS}, <b>counting the one this report would draw</b> — in production
     *     {@code MlsResendLedger.repeatResendsToPeerSince(…, windowStart(now)) + 1}
     */
    public static boolean escalates(final int repeatResendsInWindow) {
        return repeatResendsInWindow >= ESCALATE_AT;
    }

    /** Which ledger count a report class is judged on. */
    public enum Counting {
        /**
         * {@code MlsResendLedger.repeatResendsToPeerSince} — rows beyond the first for each chain.
         * A loop is by definition repeats on one chain, so the storm guard is unweakened, while N
         * distinct messages each get the one resend §10.3 exists to give them.
         */
        REPEAT_RESENDS,
        /**
         * {@code MlsResendLedger.resendsToPeerSince} — every row. The blunter count, and the right
         * one for an arm that resends without knowing why.
         */
        TOTAL_RESENDS,
    }

    /** What happens when a report class reaches its cap. */
    public enum AtTheCap {
        /** Stop resending and repair the group — an era advance, which re-Welcomes every member. */
        ESCALATE_TO_A_GROUP_REPAIR,
        /** Stop, and do nothing else. */
        STOP,
    }

    /**
     * The classes of §7.7.2.2 negative-delivery report that draw a §10.3 resend, and the rule each is
     * judged by.
     *
     * <p>Walked by a host test so a class added later cannot inherit a terminal action by omission —
     * the same failure {@link MlsInboundRefusal} was written to stop one layer over, where two arms
     * whose comments said the message DECRYPTED both returned null.
     */
    public enum ReportClass {
        /**
         * Reason 4, {@code failed-to-decrypt}. The peer told us it could not decrypt, so repeated
         * failures about material we have already resent are evidence its STATE is broken, and
         * escalating is acting on what the peer told us.
         */
        DECRYPT_FAILURE(Counting.REPEAT_RESENDS, AtTheCap.ESCALATE_TO_A_GROUP_REPAIR),
        /**
         * {@code <failed/>} and nothing else — Google Messages' own wire shape on this path, and therefore
         * the common case rather than an anomaly.
         *
         * <p><b>STOPS rather than escalating, and the asymmetry is the point.</b> A report with no
         * reason tells us nothing about the cause, so repeated ones are not evidence of anything in
         * particular; advancing an era or rekeying on them would mutate group state on a conversation
         * that may be perfectly healthy. Stopping is the action that cannot be wrong.
         */
        NO_REASON_GIVEN(Counting.TOTAL_RESENDS, AtTheCap.STOP);

        private final Counting mCounting;
        private final AtTheCap mAtTheCap;

        ReportClass(final Counting counting, final AtTheCap atTheCap) {
            mCounting = counting;
            mAtTheCap = atTheCap;
        }

        /** Which ledger count this class is judged on. */
        public Counting counting() {
            return mCounting;
        }

        /** What this class does at its cap. */
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
