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
 * What ONE PASS of the self-heal ladder costs, and how many looks it records.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The advancer takeover was once unreachable at rank &gt;= 2 because every
 * LOOK also charged a self-heal ATTEMPT: {@code selfHealRetryLimit} is 5 and the takeover budget is
 * {@code presenceLooks + (rank-1) x baseLooks} = 3 / 6 / 9, so the smaller budget escalated before
 * the larger one could ever be reached. The fix was not to tune the two ceilings against each other
 * — capping forces distinct ranks onto one value, and two members holding the same budget expire
 * together and both advance, which is the duel the election exists to prevent. The fix was to
 * DISSOLVE the collision: a look stops costing an attempt, because a yield is not a failed repair,
 * it is a deliberate decision not to repair yet. That fix is right and it is device-verified —
 * {@code era yield EXHAUSTED after 6/6 looks — TAKING OVER}, captured on a device.
 *
 * <p><b>What was missing was anything that would stop it being undone.</b>
 * {@link MlsAdvancerElection#takeoverReachableWithin} — the predicate written for exactly this pair
 * — had NO production call site, and the test guarding the fix passed its {@code enclosingAttempts}
 * argument as a literal {@code 0}. That {@code 0} is a claim about {@code MlsProviderTransport},
 * asserted by a constant typed into an engine test, and the production fact behind it was the ORDER
 * OF TWO STATEMENTS in {@code selfHealInner}: {@code relookLiveYield(key)} returns early ABOVE
 * {@code chargeSelfHealBudget(key)}. Moving the charge up puts the numbers back into collision and
 * leaves the whole host catalogue green.
 *
 * <p>So this class holds the two facts that were previously statement order and a comment, in the
 * one place a host test can read them:
 *
 * <ol>
 *   <li><b>What a pass costs</b> — {@link Kind}, and {@link #enclosingSelfHealAttempts} which
 *       COMPUTES {@code takeoverReachableWithin}'s enclosing-budget argument from that cost rather
 *       than writing it down. This is invariant I5: an invariant asserted with hand-supplied
 *       arguments is not asserted about production.</li>
 *   <li><b>How many looks one pass records</b> — {@link #look}, the single arithmetic both yield
 *       paths run. See the second half of this javadoc.</li>
 * </ol>
 *
 * <h2>I5 — where this class's own arguments come from</h2>
 *
 * <p>Stated here because I5 is enforced per-invariant, in each predicate's own javadoc, rather than
 * by a meta-test scanning the suite (which would be brittle and would fail for the wrong reasons):
 *
 * <ul>
 *   <li>{@link #enclosingSelfHealAttempts}'s answer comes from {@link Kind#selfHealAttemptsCharged}
 *       — the charge table — and from the caller's {@code MlsConfig.selfHealRetryLimit}. Neither is
 *       a literal in a test.</li>
 *   <li>The remaining production fact — that the transport actually SKIPS the charge on a free-look
 *       pass — is not expressible in Java from here, because it is a property of the transport's
 *       control flow. It is asserted by {@code MlsSelfHealChargeGuardTest}, which reads
 *       {@code MlsProviderTransport.java}, derives the per-look charge from the ORDER of the two
 *       invoked methods, and feeds THAT derived value to
 *       {@link MlsAdvancerElection#takeoverReachableWithin}. Between the two, re-introducing the
 *       per-look cost fails a named test whichever way it is re-introduced — through the charge
 *       table here, or through the statement order there.</li>
 * </ul>
 *
 * <h2>The look ledger — the same defect a second time, inside that fix</h2>
 *
 * <p>{@code relookLiveYield} and {@code eraYieldExhausted} BOTH evaluate the same look in the same
 * pass, and both used to compute {@code observations + 1} themselves. What reconciled them was a
 * comment:
 *
 * <pre>
 * // Do NOT record the look here. The full ladder re-runs this same arithmetic through
 * // advanceOrYield and must reach the identical verdict; incrementing twice for one pass
 * // would take over a look early and, worse, make the two paths disagree.
 * </pre>
 *
 * <p>That sentence is correct and nothing enforced it. Duplicating or reordering the increment takes
 * over a look early — the same class of defect, reached from the other direction.
 * {@link #look} is the reconciliation made executable: ONE arithmetic, and the rule that a path
 * records the look exactly when the look did NOT exhaust the budget. That rule is uniform across
 * both paths, which is why one function can serve both — the exhausted arm of the re-look falls
 * through to the ladder, and the exhausted arm of the ladder takes over and clears the yield, so
 * neither writes a count back. {@code MlsSelfHealPassTest} runs both paths of one pass through this
 * function and asserts they reach the identical verdict from an unmoved counter.
 *
 * <p><b>This class adds no fetch and no clock read.</b> Everything here is arithmetic over counts
 * the caller already holds — the same constraint {@link MlsAdvancerElection} states for itself, for
 * the same reason: one look is one {@code GetMlsGroupInfo}, and a policy that probed to decide
 * would reproduce the {@code RESOURCE_EXHAUSTED} we have already measured.
 */
public final class MlsSelfHealPass {

    private MlsSelfHealPass() {}

    /**
     * How many messages may park at an UNCHANGED group moment before we conclude the heal that
     * would close the gap is not running, and KILL it.
     *
     * <p>Three, for {@link MlsRecoveryPolicy#UNCONVERGED_BEFORE_REBUILD}'s reason one layer down:
     * one or two parks at the same moment is an ordinary burst arriving faster than a commit. Three
     * says the moment is not moving.
     *
     * <p>Here rather than in {@code MlsProviderTransport}, and here
     * rather than beside that constant because they answer different questions about different
     * counters — this one is about a heal that has stopped progressing, and killing it is a purely
     * local act that lets the honest §6.2 FTD path run.
     */
    public static final int PARKS_BEFORE_UNSTICKING_A_HEAL = 3;

    /**
     * Has this conversation parked enough messages at one unchanged moment to unstick the heal?
     *
     * @param parksAtThisMoment consecutive parks at the SAME group moment, this one included
     */
    public static boolean parksExhausted(final int parksAtThisMoment) {
        return parksAtThisMoment >= PARKS_BEFORE_UNSTICKING_A_HEAL;
    }

    /**
     * What one pass of the self-heal ladder charges the ENCLOSING self-heal budget.
     *
     * <p>The two arms are the whole of that fix, expressed as a value rather than as the
     * order of two statements. {@link #FREE_LOOK} charging {@code 0} is the single point of truth
     * that {@link #enclosingSelfHealAttempts} reads: change it to {@code 1} and every rank &gt;= 2
     * becomes unreachable again, and {@code MlsAdvancerElectionTest} fails saying so.
     */
    public enum Kind {
        /**
         * A live yield re-looked from LOCAL STATE. Costs nothing, in the literal sense: the moment
         * is an engine read and the electorate is held in the yield from when it started, so there
         * is no {@code GetMlsGroupInfo} either.
         *
         * <p>Charging an attempt for this is the category error above — the self-heal
         * budget counts REPAIR ATTEMPTS, and a yield is a decision not to repair yet, with its own
         * convergence test (the moment) and its own bound (the looks).
         */
        FREE_LOOK(0),
        /**
         * A repair was actually attempted: the full ladder ran, which means a fetch and, if it gets
         * that far, an era advance. One attempt against {@code MlsConfig.selfHealRetryLimit}.
         */
        CHARGED_ATTEMPT(1);

        /** Attempts this pass charges the enclosing self-heal budget. */
        public final int selfHealAttemptsCharged;

        Kind(final int selfHealAttemptsCharged) {
            this.selfHealAttemptsCharged = selfHealAttemptsCharged;
        }
    }

    /**
     * Classify one pass of the self-heal ladder.
     *
     * @param liveYieldStillHolds whether a live yield re-looked from local state and answered "still
     *                            yielding" — i.e. the pass ended without running the ladder
     */
    public static Kind classify(final boolean liveYieldStillHolds) {
        return liveYieldStillHolds ? Kind.FREE_LOOK : Kind.CHARGED_ATTEMPT;
    }

    /**
     * {@link MlsAdvancerElection#takeoverReachableWithin}'s {@code enclosingAttempts} argument,
     * COMPUTED from what a look costs — never written down.
     *
     * <p>This is the method that stops the predicate being decorative. Its answer is
     * {@code 0} ("looks are free, reachability is unconditional") precisely while
     * {@link Kind#FREE_LOOK} charges nothing, and becomes the real self-heal retry limit the moment
     * a look costs an attempt again — at which point the reachability assertion for rank &gt;= 2
     * turns false and fails.
     *
     * @param selfHealRetryLimit {@code MlsConfig.selfHealRetryLimit} — the enclosing budget's
     *                           allowance per window, read from the shipped configuration
     */
    public static int enclosingSelfHealAttempts(final int selfHealRetryLimit) {
        if (Kind.FREE_LOOK.selfHealAttemptsCharged <= 0) return 0;
        return Math.max(0, selfHealRetryLimit);
    }

    /**
     * <b>The one entry production takes into {@link MlsAdvancerElection#takeoverReachableWithin}.</b>
     *
     * <p>Answers "can the member this decision describes ever actually reach its takeover, inside
     * the budget that charges its looks?". False means the yield is a wait with no end: the member
     * will burn the enclosing budget and escalate before its own bound is reached, and the takeover
     * branch is dead code for it. The caller is expected to LOG that rather than throw — a
     * conversation that yields forever is a degraded conversation, not a crash — and the log line is
     * what makes the collision visible in a capture instead of only in a test.
     *
     * <p><b>This javadoc used to say "the production call site", and for a day it was false</b>
     *. Stage 4 added this method and nothing in {@code src/} called it, so
     * the predicate was one hop further from production than before and no closer to being
     * asserted about it — and the sentence claiming otherwise was written by the change that
     * introduced the gap. It is true now because {@code MlsProviderTransport.advanceOrYield} calls
     * this immediately after the election says we do not advance, which is the decision that starts
     * the wait. <b>Do not take this paragraph as the evidence.</b> The evidence is
     * {@code MlsPolicyInteractionGuardTest.thePredicatesNamedWithNoProductionCallSiteOnlyFall},
     * which walks the invoked-name chain from {@code takeoverReachableWithin} outward until it
     * lands in the provider layer and fails if it never does — so a wrapper cannot be mistaken for
     * a call site a second time, whatever any javadoc says about itself.
     *
     * @param d                  the election's own decision for this member
     * @param baseLooks          {@code MlsConfig.eraYieldLooks}
     * @param selfHealRetryLimit {@code MlsConfig.selfHealRetryLimit}
     */
    public static boolean takeoverReachable(final MlsAdvancerElection.Decision d,
            final int baseLooks, final int selfHealRetryLimit) {
        if (d == null) return false;
        return MlsAdvancerElection.takeoverReachableWithin(
                d.rank, d.ahead, baseLooks, enclosingSelfHealAttempts(selfHealRetryLimit));
    }

    /**
     * <b>Why a yielding member can or cannot reach its takeover</b> — the reasons kept APART.
     *
     * <p>{@link #takeoverReachable} answers one boolean, and <b>three different situations produce
     * its {@code false}</b>: an operator has configured this device never to take over, the
     * enclosing self-heal budget escalates before the yield's own bound (the collision above),
     * or there was no decision to ask about. A caller that logs the boolean asserts the collision
     * for all three — the exact defect this package keeps finding, and the reason
     * {@code MlsRebuildOutcome} and {@code Health.LOOK_REFUSED} were split: <b>a reader with
     * partial knowledge must not share a value with one that has none.</b>
     *
     * <p>So the caller switches on this instead. {@link #why} is null for the one value that is not
     * worth a line, and {@link #isACollision} is the only value that deserves a warning.
     */
    public enum Reach {
        /** The takeover is reachable inside the budget that charges this member's looks. */
        REACHABLE(false, null),
        /**
         * No decision was handed in, so nothing was asked and nothing is claimed. Not a collision,
         * and specifically not evidence that the budgets are fine.
         */
        NO_DECISION(false, "no election decision was handed in, so nothing was asked about the "
                + "takeover — this line is not evidence in either direction"),
        /**
         * {@code MlsConfig.eraYieldLooks <= 0}: an operator has asked this device never to take the
         * era advance over, and {@link MlsAdvancerElection#takeoverReachableWithin} honours that
         * verbatim. A configured wait, not a broken one.
         */
        YIELD_FOREVER_BY_CONFIGURATION(false, "this device is configured to yield forever "
                + "(eraYieldLooks <= 0), so there is no takeover to reach — configured, not broken"),
        /**
         * The collision, live: the enclosing self-heal budget escalates before the
         * yield's own bound is reached, so the takeover branch is dead code for this member and the
         * yield is a wait with no end. The way it comes back is a look starting to cost a self-heal
         * attempt again.
         */
        ENCLOSING_BUDGET_ESCALATES_FIRST(true, "the enclosing self-heal budget escalates before "
                + "this yield's own bound is reached, so the takeover branch is unreachable and "
                + "this wait has no end (the budget collision — a look has started costing a "
                + "self-heal attempt again)");

        /** True only for the value a person should be warned about. */
        public final boolean isACollision;
        /** One sentence for the log, or null when the value is not worth a line. */
        public final String why;

        Reach(final boolean isACollision, final String why) {
            this.isACollision = isACollision;
            this.why = why;
        }
    }

    /**
     * {@link #takeoverReachable}, with its {@code false} separated into the reasons that produce it.
     *
     * <p>Same arguments, same predicate — this only refuses to let three situations share one
     * value. See {@link Reach}.
     *
     * @param baseLooks          {@code MlsConfig.eraYieldLooks}
     * @param selfHealRetryLimit {@code MlsConfig.selfHealRetryLimit}
     */
    public static Reach reach(final MlsAdvancerElection.Decision d, final int baseLooks,
            final int selfHealRetryLimit) {
        if (takeoverReachable(d, baseLooks, selfHealRetryLimit)) return Reach.REACHABLE;
        if (d == null) return Reach.NO_DECISION;
        return baseLooks <= 0 ? Reach.YIELD_FOREVER_BY_CONFIGURATION
                : Reach.ENCLOSING_BUDGET_ESCALATES_FIRST;
    }

    /**
     * One look at a live yield, and the ONE place that decides whether it is recorded.
     *
     * <p>Both yield paths call this. {@link #recorded} is {@code !}{@link #exhausted} for both, and
     * that is not a coincidence to be re-derived at each call site — it is the reconciliation:
     *
     * <ul>
     *   <li>the re-look's exhausted arm FALLS THROUGH so the full ladder can pay for the fetch the
     *       takeover needs, and must leave the counter untouched or the ladder computes a look that
     *       never happened;</li>
     *   <li>the ladder's exhausted arm TAKES OVER and clears the yield outright, so there is nothing
     *       to write a count back into.</li>
     * </ul>
     *
     * @param observationsHeld the yield's recorded look count before this pass
     * @param budget           the election's own bound for this member
     *                         ({@link MlsAdvancerElection.Decision#looks}); {@code <= 0} means
     *                         "yield forever" and no look ever exhausts it
     */
    public static Look look(final int observationsHeld, final int budget) {
        final int number = Math.max(0, observationsHeld) + 1;
        final boolean exhausted =
                MlsRecoveryPolicy.eraYieldObservationsExhausted(number, budget);
        return new Look(number, exhausted);
    }

    /** The verdict on one look. See {@link MlsSelfHealPass#look}. */
    public static final class Look {
        /** The 1-based look this pass evaluates. Both paths of one pass must see the same value. */
        public final int number;
        /** The yield's own bound is spent: stop yielding and take the era advance over. */
        public final boolean exhausted;
        /**
         * Does the path that just evaluated this look write {@link #number} back?
         *
         * <p>Never on the exhausted arm — see the class javadoc's look-ledger section. Recording
         * there would make the ladder's re-run of the same pass compute {@code number + 1}, taking
         * over a look early and making the two paths disagree about a decision they must reach
         * identically.
         */
        public final boolean recorded;

        Look(final int number, final boolean exhausted) {
            this.number = number;
            this.exhausted = exhausted;
            this.recorded = !exhausted;
        }

        @Override public String toString() {
            return "look " + number + (exhausted ? " EXHAUSTED" : "") + (recorded ? " recorded" : "");
        }
    }
}
