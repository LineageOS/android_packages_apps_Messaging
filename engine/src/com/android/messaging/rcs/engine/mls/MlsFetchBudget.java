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
 * A SERVER LOOK-UP is the scarce resource in recovery, and this is what rations it.
 *
 * <h2>The defect, and why a pass cap was the wrong bound</h2>
 *
 * <p>Device-measured, {@code local era=1 epoch=1} vs
 * {@code server era=1 epoch=7}. A {@code --ez health --ez fix --ez drive} ran the reconcile drive to
 * {@link MlsDriveLoop#DEFAULT_MAX_ITERATIONS} without settling — ten passes, each re-reading the same
 * two numbers, each reporting more work, none progressing. The self-heal immediately afterwards then
 * failed:
 *
 * <pre>
 * GetMlsGroupInfo FAIL: grpcStatus=8 "Resource has been exhausted (e.g. check quota)"
 * MlsProviderTransport: self-heal fetch FAILED for g:32725c4d… (era=1)
 * </pre>
 *
 * <p>After a 200-second wait the identical single fetch succeeded. <b>The exhaustion was
 * self-inflicted, and that is the whole finding:</b> a drive loop that cannot converge does not sit
 * still — it fetches once per pass until the server throttles it. Hitting the cap and exhausting the
 * quota are THE SAME EVENT, so the loop's failure mode is to destroy the resource its own recovery
 * depends on, on precisely the device that most needs to heal.
 *
 * <p>{@link MlsDriveLoop}'s cap bounds ITERATIONS. Iterations are free; look-ups are not. A bound on
 * the cheap thing does not bound the expensive one, and ten is far too many of the expensive one —
 * <b>the loop should not be making the tenth fetch.</b>
 *
 * <h2>What this class is NOT</h2>
 *
 * <p>It is not a backoff and it reads no clock. Treating {@code RESOURCE_EXHAUSTED} as a reason to
 * wait longer would hide the defect behind a delay: the fix is to stop spending look-ups a pass
 * cannot use, not to space out the spending. The one duration here is the measured cooldown, and it
 * exists so a retry that is already going to happen is scheduled past the window rather than into it.
 *
 * <p>It is also not the only guard. {@link MlsDriveLoop} refuses to re-drive a pass that declared it
 * changed nothing ({@link MlsHostAction.Redrive}), and that is what keeps a real drive to one or two
 * look-ups. This budget is the backstop for the case that rule cannot see: a pass whose actions
 * DIFFER each time while none of them progresses. Two rules, because the first is a producer's
 * honesty and the second does not depend on it.
 *
 * <p>Nor does it belong inside {@link MlsAdvancerElection}: that class deliberately reads its
 * liveness evidence from state the host already holds and never spends a look of its own, and its
 * author noted that rationing the fetch is a CALL SITE concern because {@code selfHeal} is one of
 * several drivers reaching the same RPC. A limit living inside one policy leaves the others free to
 * exhaust the same quota.
 *
 * <p>All methods are total, take explicit inputs, and read nothing.
 */
public final class MlsFetchBudget {

    private MlsFetchBudget() {}

    /**
     * How many server look-ups ONE recovery drive may spend.
     *
     * <p>Two, not ten. With {@link MlsHostAction.Redrive} in place a reconcile drive needs exactly
     * one look in the ordinary case (observe, decide, act or decline) and at most two when the first
     * pass genuinely acts and the second verifies. A third look inside a single drive is not a busy
     * conversation; it is the non-convergence this class is about, and refusing it is cheaper than
     * detecting it afterwards.
     *
     * <p>Deliberately NOT tied to {@link MlsDriveLoop#DEFAULT_MAX_ITERATIONS}. They bound different
     * resources and letting them track each other is how raising one silently raises the other —
     * which is the non-fix this class names explicitly.
     */
    public static final int RECOVERY_LOOKS = 2;

    /**
     * A LOOK is one pass being permitted to reach the server, and it costs <b>at least</b> one
     * {@code GetMlsGroupInfo} — the health probe every recovery pass opens with. A pass that then
     * escalates (an anchor check, a rebuild's state pack) spends more. So the budget bounds looks
     * from above and fetches from above by at least as much, and the wording here says "at least"
     * rather than claiming an exact fetch count this layer cannot know.
     */
    private static final String LOOK_COSTS = "each is AT LEAST one GetMlsGroupInfo";

    /** Whether a pass may spend a look. */
    public enum Verdict {
        /** Spend it. */
        SPEND,
        /** The drive's allowance is gone. Stop the drive; do not spend and do not escalate. */
        DENIED_BUDGET
    }

    /**
     * May a pass spend a server look-up?
     *
     * @param looksSpent how many this drive has already spent; negative is read as none
     * @param budget     the allowance, from {@link #RECOVERY_LOOKS}; anything below 1 is clamped to
     *                   1, because a budget of zero would fail a drive that was never allowed to ask
     *                   anything — a self-inflicted outage rather than a tight bound, the same trap
     *                   {@link MlsDriveLoop} clamps for
     */
    public static Verdict mayLook(final int looksSpent, final int budget) {
        final int spent = Math.max(0, looksSpent);
        return spent < Math.max(1, budget) ? Verdict.SPEND : Verdict.DENIED_BUDGET;
    }

    /**
     * The line to log when a look is refused — it has to say the quota is OURS, not the server's.
     *
     * <p>The person reading it is looking at a recovery that stopped, and the available wrong
     * conclusion is "Tachyon limits our recovery". It does not; we do.
     */
    public static String describeDenial(final int looksSpent, final int budget, final String what) {
        return "MLS drive '" + what + "' has spent its " + Math.max(1, budget)
                + "-look allowance (" + Math.max(0, looksSpent) + " pass(es) reached the server, "
                + LOOK_COSTS + ") and is STOPPING rather than asking again. This is OUR bound, not "
                + "the server's: a pass that is not progressing must not keep spending the look-ups "
                + "that recovery itself needs. The work is still owed and is re-driven "
                + "from a fresh trigger.";
    }

    /** The one-line look accounting a drive should log when it finishes. */
    public static String describeSpend(final int looksSpent, final int budget, final String what) {
        return "MLS drive '" + what + "' spent " + Math.max(0, looksSpent) + " of "
                + Math.max(1, budget) + " permitted look(s) — " + LOOK_COSTS + ".";
    }

    // -- the throttle, and what it is honest to say about it ---------------------------------------

    /**
     * The ONE measured cooldown after a self-inflicted {@code RESOURCE_EXHAUSTED} on
     * {@code GetMlsGroupInfo}: 200 seconds, after which the identical single fetch succeeded
     * on one device.
     *
     * <p><b>One datum, not a published limit.</b> The real window is unmeasured and may be shorter,
     * longer, or a function of the burst that provoked it. It is here so a retry we were going to
     * schedule anyway lands OUTSIDE the only window we have evidence for, rather than inside it —
     * which is what {@link MlsRetryPolicy}'s 30-second immediate ladder would otherwise do three
     * times before ever waiting long enough to matter.
     */
    public static final long MEASURED_THROTTLE_COOLDOWN_MS = 200_000L;

    /**
     * The first {@link MlsRetryPolicy} attempt number whose delay clears
     * {@link #MEASURED_THROTTLE_COOLDOWN_MS}.
     *
     * <p>COMPUTED from the ladder rather than written down, so re-tuning the ladder cannot silently
     * schedule a throttled conversation back inside the window. Today it is 4 — the first rung past
     * the three 30-second immediate steps, at 300 seconds.
     *
     * <p>Starting a retry at attempt 4 costs nothing else: the attempt number is also the outermost
     * give-up counter ({@link MlsRetryPolicy#MAX_ATTEMPTS_PER_ITEM} = 1000), so skipping three rungs
     * is not skipping three chances.
     */
    public static int firstAttemptClearingTheCooldown() {
        for (int a = 1; a < MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM; a++) {
            if (MlsRetryPolicy.retryDelayMs(a) > MEASURED_THROTTLE_COOLDOWN_MS) return a;
        }
        // Unreachable with any sane ladder; returning the last valid attempt beats returning one
        // the enqueue path would refuse as exhausted.
        return MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM - 1;
    }

    /**
     * What to log when a look came back unreadable — the classification the caller needs.
     *
     * <p>The gRPC trailer lives provider-side and says {@code grpcStatus=8 "Resource has been
     * exhausted"}, which reads exactly like a server wall. From here we cannot see the trailer, but
     * we CAN see the one thing that decides how to read it: <b>how many look-ups we had already
     * spent on this conversation moments earlier.</b> A failure after a burst of our own is a
     * throttle we caused; a failure on a first look is not evidence of one.
     *
     * <p>This is the difference between the next person spending an hour on a quota negotiation and
     * spending three minutes waiting, so it is stated in the log where they will meet it rather than
     * only in a commit message.
     *
     * @param looksSpentBefore look-ups already spent in this operation before the one that failed
     */
    public static String describeUnreadableLook(final int looksSpentBefore, final String what) {
        final String common = " If the provider's trailer says grpcStatus=8 RESOURCE_EXHAUSTED, "
                + "that is a throttle WE CAUSED and not a server wall — measured 2026-09-08, the "
                + "identical single fetch succeeded after " + (MEASURED_THROTTLE_COOLDOWN_MS / 1000L)
                + "s. Waiting is the remedy; asking again is the fault.";
        if (Math.max(0, looksSpentBefore) <= 0) {
            // Deliberately does NOT claim innocence. This operation has spent none of its own looks,
            // which bounds where a throttle could have come from; it does not rule one out, because
            // whatever ran immediately before us spends looks on the same conversation.
            return "the look for '" + what + "' came back unreadable, and this operation had spent "
                    + "none of its own GetMlsGroupInfo calls yet — so if it IS a throttle, it was "
                    + "provoked by whatever ran before this, not by this." + common;
        }
        return "the look for '" + what + "' came back unreadable after we had ALREADY SPENT "
                + looksSpentBefore + " look(s) on it in this operation alone (" + LOOK_COSTS + ")."
                + common;
    }
}
