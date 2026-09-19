/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * What one pass of the self-heal ladder costs, and how many yield looks it records. A re-look at a
 * live yield charges no self-heal attempt; if it did, the retry limit would escalate before a
 * rank-2 or rank-3 member reached its takeover budget. {@link #look} is the one arithmetic both
 * yield paths use. No fetches and no clock reads. See docs/mls/health-and-recovery.md.
 */
public final class MlsSelfHealPass {

    private MlsSelfHealPass() {}

    /**
     * Messages that may park at an unchanged group moment before the heal is judged stalled and
     * killed, so the ordinary FTD path can run. One or two parks can be a burst.
     */
    public static final int PARKS_BEFORE_UNSTICKING_A_HEAL = 3;

    /** @param parksAtThisMoment consecutive parks at the same group moment, this one included */
    public static boolean parksExhausted(final int parksAtThisMoment) {
        return parksAtThisMoment >= PARKS_BEFORE_UNSTICKING_A_HEAL;
    }

    /** What one pass charges the enclosing self-heal budget; {@link #FREE_LOOK} must stay 0. */
    public enum Kind {
        /** A live yield re-looked from local state; no fetch either. */
        FREE_LOOK(0),
        /** The full ladder ran: a fetch and possibly an era advance; one attempt. */
        CHARGED_ATTEMPT(1);

        /** Attempts this pass charges the enclosing self-heal budget. */
        public final int selfHealAttemptsCharged;

        Kind(final int selfHealAttemptsCharged) {
            this.selfHealAttemptsCharged = selfHealAttemptsCharged;
        }
    }

    /** @param liveYieldStillHolds whether a live yield re-look answered "still yielding" */
    public static Kind classify(final boolean liveYieldStillHolds) {
        return liveYieldStillHolds ? Kind.FREE_LOOK : Kind.CHARGED_ATTEMPT;
    }

    /**
     * {@link MlsAdvancerElection#takeoverReachableWithin}'s {@code enclosingAttempts}, computed
     * from what a look costs: 0 while looks are free, else the retry limit.
     */
    public static int enclosingSelfHealAttempts(final int selfHealRetryLimit) {
        if (Kind.FREE_LOOK.selfHealAttemptsCharged <= 0) return 0;
        return Math.max(0, selfHealRetryLimit);
    }

    /**
     * Whether this member can reach its takeover inside the budget that charges its looks. Called
     * after the election decides to yield; false means a wait with no end, which the caller logs.
     *
     * @param baseLooks          {@code MlsConfig.eraYieldLooks}
     * @param selfHealRetryLimit {@code MlsConfig.selfHealRetryLimit}
     */
    public static boolean takeoverReachable(final MlsAdvancerElection.Decision d,
            final int baseLooks, final int selfHealRetryLimit) {
        if (d == null) return false;
        return MlsAdvancerElection.takeoverReachableWithin(
                d.rank, d.ahead, baseLooks, enclosingSelfHealAttempts(selfHealRetryLimit));
    }

    /** Why a takeover is or is not reachable; the three causes of a false stay apart. */
    public enum Reach {
        /** Reachable inside the budget that charges this member's looks. */
        REACHABLE(false, null),
        /** No decision was handed in; not evidence in either direction. */
        NO_DECISION(false, "no election decision was handed in, so nothing was asked about the "
                + "takeover — this line is not evidence in either direction"),
        /** {@code MlsConfig.eraYieldLooks <= 0}: configured never to take over. */
        YIELD_FOREVER_BY_CONFIGURATION(false, "this device is configured to yield forever "
                + "(eraYieldLooks <= 0), so there is no takeover to reach — configured, not broken"),
        /** The enclosing self-heal budget escalates before the yield's own bound is reached. */
        ENCLOSING_BUDGET_ESCALATES_FIRST(true, "the enclosing self-heal budget escalates before "
                + "this yield's own bound is reached, so the takeover branch is unreachable and "
                + "this wait has no end (the budget collision — a look has started costing a "
                + "self-heal attempt again)");

        /** Whether a person should be warned. */
        public final boolean isACollision;
        /** One sentence for the log, or null. */
        public final String why;

        Reach(final boolean isACollision, final String why) {
            this.isACollision = isACollision;
            this.why = why;
        }
    }

    /** {@link #takeoverReachable}, with its {@code false} separated into its causes. */
    public static Reach reach(final MlsAdvancerElection.Decision d, final int baseLooks,
            final int selfHealRetryLimit) {
        if (takeoverReachable(d, baseLooks, selfHealRetryLimit)) return Reach.REACHABLE;
        if (d == null) return Reach.NO_DECISION;
        return baseLooks <= 0 ? Reach.YIELD_FOREVER_BY_CONFIGURATION
                : Reach.ENCLOSING_BUDGET_ESCALATES_FIRST;
    }

    /**
     * One look at a live yield. A path records it exactly when it did not exhaust the budget: the
     * re-look's exhausted arm falls through to the ladder, and the ladder's takes over and clears
     * the yield, so the two reach the same verdict.
     *
     * @param observationsHeld the yield's recorded look count before this pass
     * @param budget           {@link MlsAdvancerElection.Decision#looks}; {@code <= 0} is unbounded
     */
    public static Look look(final int observationsHeld, final int budget) {
        final int number = Math.max(0, observationsHeld) + 1;
        final boolean exhausted =
                MlsRecoveryPolicy.eraYieldObservationsExhausted(number, budget);
        return new Look(number, exhausted);
    }

    /** The verdict on one look. */
    public static final class Look {
        /** The 1-based look this pass evaluates. */
        public final int number;
        /** The yield's bound is spent: stop yielding and take the era advance over. */
        public final boolean exhausted;
        /** Whether the evaluating path writes {@link #number} back; never when exhausted. */
        public final boolean recorded;

        Look(final int number, final boolean exhausted) {
            this.number = number;
            this.exhausted = exhausted;
            this.recorded = !exhausted;
        }

        @Override public String toString() {
            return "look " + number + (exhausted ? " EXHAUSTED" : "")
                    + (recorded ? " recorded" : "");
        }
    }
}
