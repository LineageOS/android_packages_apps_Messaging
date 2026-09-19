/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Rations server look-ups inside one recovery drive: {@link MlsDriveLoop} bounds iterations, which
 * are free, and this bounds looks, which the server throttles. Not a backoff and reads no clock;
 * all methods are total and read nothing. See docs/mls/budgets.md.
 */
public final class MlsFetchBudget {

    private MlsFetchBudget() {}

    /**
     * How many server look-ups one recovery drive may spend: one to observe and act, one to verify.
     * Deliberately independent of {@link MlsDriveLoop#DEFAULT_MAX_ITERATIONS}.
     */
    public static final int RECOVERY_LOOKS = 2;

    /**
     * A look is one pass reaching the server and costs at least one group-info read; a pass that
     * escalates spends more, so log lines say "at least".
     */
    private static final String LOOK_COSTS = "each is AT LEAST one GetMlsGroupInfo";

    /** Whether a pass may spend a look. */
    public enum Verdict {
        /** Spend it. */
        SPEND,
        /** The drive's allowance is gone: stop the drive, neither spending nor escalating. */
        DENIED_BUDGET
    }

    /**
     * May a pass spend a server look-up?
     *
     * @param looksSpent how many this drive has already spent; negative reads as none
     * @param budget     the allowance, normally {@link #RECOVERY_LOOKS}; below 1 is clamped to 1
     */
    public static Verdict mayLook(final int looksSpent, final int budget) {
        final int spent = Math.max(0, looksSpent);
        return spent < Math.max(1, budget) ? Verdict.SPEND : Verdict.DENIED_BUDGET;
    }

    /** The line to log when a look is refused; it says the bound is ours, not the server's. */
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

    // -- the throttle

    /**
     * The only known throttle cooldown after a burst of group-info reads: 200 s. A single
     * observation, not a published limit; used so a retry that will happen anyway lands outside it.
     */
    public static final long MEASURED_THROTTLE_COOLDOWN_MS = 200_000L;

    /**
     * The first {@link MlsRetryPolicy} attempt whose delay clears {@link
     * #MEASURED_THROTTLE_COOLDOWN_MS}, computed from the ladder so retuning it cannot schedule a
     * throttled conversation back inside the window. Skipping rungs costs nothing: the give-up
     * counter is {@link MlsRetryPolicy#MAX_ATTEMPTS_PER_ITEM}.
     */
    public static int firstAttemptClearingTheCooldown() {
        for (int a = 1; a < MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM; a++) {
            if (MlsRetryPolicy.retryDelayMs(a) > MEASURED_THROTTLE_COOLDOWN_MS) return a;
        }
        // Unreachable with any sane ladder; the last valid attempt beats one the enqueue path
        // refuses.
        return MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM - 1;
    }

    /**
     * What to log when a look came back unreadable. A throttle (grpcStatus 8) after a burst of our
     * own looks is one we caused, so the line states how many this operation had already spent.
     *
     * @param looksSpentBefore look-ups already spent in this operation before the one that failed
     */
    public static String describeUnreadableLook(final int looksSpentBefore, final String what) {
        final String common = " If the provider's trailer says grpcStatus=8 RESOURCE_EXHAUSTED, "
                + "that is a throttle WE CAUSED and not a server wall — the "
                + "identical single fetch succeeds after " + (MEASURED_THROTTLE_COOLDOWN_MS / 1000L)
                + "s. Waiting is the remedy; asking again is the fault.";
        if (Math.max(0, looksSpentBefore) <= 0) {
            // Zero of our own looks does not rule out a throttle: whatever ran before us spends on
            // the same conversation.
            return "the look for '" + what + "' came back unreadable, and this operation had spent "
                    + "none of its own GetMlsGroupInfo calls yet — so if it IS a throttle, it was "
                    + "provoked by whatever ran before this, not by this." + common;
        }
        return "the look for '" + what + "' came back unreadable after we had ALREADY SPENT "
                + looksSpentBefore + " look(s) on it in this operation alone (" + LOOK_COSTS + ")."
                + common;
    }
}
