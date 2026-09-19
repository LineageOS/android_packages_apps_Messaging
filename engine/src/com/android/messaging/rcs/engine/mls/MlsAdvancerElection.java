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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WHO advances the era, and — the part this class adds — WHEN a member that is not the designated
 * advancer may stop waiting for one that is never going to act.
 *
 * <h2>The defect</h2>
 *
 * <p>{@link MlsRecoveryPolicy#weAreEraAdvancer} imposes the tie-break this project needs: the
 * numerically-lower E.164 advances, everyone else yields. That rule is right and it prevents duels.
 * What it has no notion of is <b>liveness</b>. Device-measured:
 *
 * <pre>
 * still yielding self-heal … (we are not the designated advancer: +15715550104 &gt; +15715550103)
 *     — look 2/3, group unmoved at (era=1 epoch=1)
 * </pre>
 *
 * <p>{@code +15715550103} is a device that was not connected and may never be. A correct
 * anti-duel rule with no liveness condition means an offline, uninstalled or simply absent member
 * holds every other member's self-heal hostage for the whole look budget, and the group stays
 * diverged for that entire time.
 *
 * <h2>What this class changes, and what it deliberately does not</h2>
 *
 * <p><b>Presence never changes WHO is designated.</b> That is the load-bearing decision here. The
 * designated advancer stays "the lowest E.164 in the conversation", computed from the roster alone,
 * so every member derives the SAME answer from the SAME input — which is the entire reason the rule
 * prevents duels. Liveness evidence is local and asymmetric (A may have heard from L all day while B
 * has never heard from it), so letting it move the designation would let two members disagree about
 * who is designated, and two members who disagree both advance. That is the duel, reintroduced.
 *
 * <p>What presence changes is only HOW LONG a non-designated member waits before taking over — and
 * the takeovers are <b>staggered by rank</b>, so they are ordered rather than simultaneous:
 *
 * <pre>
 * rank 0 (lowest E.164)  designated — advances immediately, never yields
 * rank 1                 may take over after   presenceLooks                unmoved looks
 * rank 2                 may take over after   presenceLooks + 1 × baseLooks
 * rank k                 may take over after   presenceLooks + (k-1) × baseLooks
 * </pre>
 *
 * <p>In one sentence: <b>your budget is your own presence budget, plus one base-length turn for
 * every member ahead of you.</b>
 *
 * <h2>Why the stagger is ADDITIVE and not multiplicative — a look costs a FETCH</h2>
 *
 * <p>{@code MlsProviderTransport.selfHeal} calls {@code fetchMissedCommits} once per pass and
 * evaluates the yield downstream in that same pass, so <b>one look is exactly one
 * {@code GetMlsGroupInfo}</b>. A look budget is therefore a fetch budget, and this is not
 * theoretical: measured on a device, a reconcile drive loop that could not converge fetched
 * once per pass until the server answered {@code grpcStatus=8 RESOURCE_EXHAUSTED}, and the identical
 * single fetch succeeded after a 200-second cooldown. The exhaustion was self-inflicted, and its
 * operator-visible shape is a quota error that reads like a server wall — the exact misreading this
 * project has a standing rule against.
 *
 * <p>A multiplicative stagger ({@code rank × presence × base}) makes the worst case grow as the
 * product of group size and patience: rank 9 behind a live member would be {@code 9 × 2 × 3 = 54}
 * fetches. Additive makes it {@code 6 + 8 × 3 = 30}, keeps the separation between adjacent ranks at
 * a full {@code baseLooks} (comfortably more than the one look that ordering strictly requires), and
 * is the honest shape anyway: waiting is per member ahead of you, not per member ahead of you times
 * how patient you feel.
 *
 * <p><b>Nothing here may add a fetch of its own.</b> A takeover policy that re-fetched to test
 * liveness would reproduce the exhaustion from the other direction. The presence evidence is
 * therefore read entirely from state the host already holds — see the ledger below — and never from
 * a probe. Rate-limiting the fetch itself is a separate concern at a separate call site, and belongs
 * to its own change rather than being smuggled in here.
 *
 * <p>This is what keeps the anti-duel property under timeout, and it works because of a mechanism
 * that already exists: {@link MlsRecoveryPolicy#eraYieldSatisfied} cancels a yield the moment the
 * GROUP MOVES. So when rank 1 takes over, its advance moves the group, and rank 2's next look sees
 * the movement and stands down without ever acting. Rank 2 only ever gets its turn if rank 1's
 * takeover ALSO failed to move the group — which is exactly when we want the next member to try.
 * The fallback order is therefore deterministic (next-lowest E.164 takes over), not a free-for-all.
 *
 * <h2>What counts as evidence that the advancer is absent</h2>
 *
 * <p>Honestly: not much, and this class is explicit about the difference between the signals that
 * are real and the ones that only look real.
 *
 * <ul>
 *   <li><b>Used — inbound attribution.</b> The one thing we genuinely know is whether bytes have
 *       arrived FROM a member: a decrypted message, an applied commit, an IMDN receipt, even a
 *       ciphertext we failed to decrypt (a message we cannot read still proves the sender is alive
 *       and sending, which matters because a behind member's inbound is largely undecryptable by
 *       definition). The host feeds that in as {@code heardAtSeq}.</li>
 *   <li><b>Used — the crude look budget</b>, kept as the floor. It is the only bound available when
 *       there is no attribution evidence at all, and there are real cases where there is none.</li>
 *   <li><b>Rejected — peer capability.</b> {@code ProviderTransport}'s own contract says a
 *       capability miss is "nothing known", NOT a negative capability assertion. A signal that
 *       cannot distinguish "absent" from "we did not manage to look it up" cannot be evidence of
 *       absence, and treating it as such is the shape of mistake this project has already paid for
 *       more than once.</li>
 *   <li><b>Rejected — {@code MlsPeerGuard.peerFailureCount}, and NOT because it is unfed.</b> It
 *       gained producers on 2026-09-08; an earlier draft of this list rejected
 *       it as identically zero and that reason is dead. The live reasons are better ones:
 *       <ul>
 *         <li><b>It measures the wrong axis.</b> The count rises on §7.7.2.2 reports <i>from the
 *             peer</i> — which only a peer that is receiving our messages and answering can send.
 *             A high count therefore means <b>present and wedged</b>, the opposite of absent. An
 *             advancer that is simply gone sends nothing and scores zero, identically to a perfectly
 *             healthy one, so the signal cannot separate the two states this class exists to
 *             separate.</li>
 *         <li><b>Read as impatience it would aim era advances at the peers least able to survive
 *             one.</b> Two facts, and a follow-up was needed because they were written here as
 *             one sentence — which put a guard's authority behind an anecdote:
 *             <ul>
 *               <li><b>STRUCTURAL AND GUARDED.</b> An era advance re-Welcomes EVERY member. It
 *                   cannot be a commit at all: the Era is GroupContext extension {@code 0xF001} and
 *                   a GroupContextExtensions proposal may neither change nor remove it (design
 *                   §9.2; Google Messages' {@code GroupContextExtensionProposalChangesEraError} /
 *                   {@code RemovesEraError}), so {@code ProdSession::commit_era_advance} returns
 *                   {@code Err} unconditionally and {@code eraAdvanceLocked} falls through to the
 *                   legacy create in the same call. Pinned, and mutation-checked, by
 *                   {@link MlsEraAdvanceChargeTest#theEngineRefusesToBuildAPreservingEraAdvance}.</li>
 *               <li><b>OBSERVED, AND NOTHING GUARDS IT.</b> ~20 of them wedged a third party's
 *                   iPhone for a month. That is an incident record, not
 *                   a property — nothing asserts the count or the duration and nothing could.</li>
 *             </ul>
 *             <b>The budgets below rest on the first alone</b>, and that was checked rather than
 *             assumed: the ACTIVE arm's 2× patience rests on "a duel with a live advancer costs two
 *             full re-creates, each re-Welcoming every member", as do the rank stagger and the
 *             {@code onPeerReportedFailure}/{@link Presence#NEVER_HEARD} fix. The magnitude is
 *             MOTIVATION for treating this as P0-class and is never a premise of a number.
 *             <p>What makes the rejection above concrete is the same incident: that iPhone was the
 *             lowest MSISDN and therefore the designated advancer, and it sent no positive traffic
 *             at all — so "take over faster from an advancer with a failure
 *             streak" is a rule that would have fired precisely there.</li>
 *         <li><b>{@code MlsPeerGuard} already owns that verdict, and owning it twice is the drift
 *             this module exists to end.</b> The guard decides WHETHER a peer may be sent a state
 *             change at all and is consulted on the era-advance path; this class decides WHO
 *             advances and WHEN. Reading the same counter here with a different threshold would let
 *             the election race to do something the guard is about to refuse.</li>
 *       </ul>
 *       What the fed signal DOES change is the presence ledger: the host now records a peer's
 *       failure report as having heard from it, so a loudly-wedged advancer reads {@link
 *       Presence#ACTIVE} — the most patient arm — instead of {@link Presence#NEVER_HEARD}, the
 *       least. That is the correct use of the news, and it needs no coupling to the guard.</li>
 *   <li><b>Rejected — a KeyPackage probe.</b> It would answer the question properly, but it costs a
 *       network round trip inside a decision taken under the conversation lock, and claiming a
 *       KeyPackage consumes one.</li>
 * </ul>
 *
 * <h2>Absence of evidence needs evidence of presence — the UNKNOWN arm</h2>
 *
 * <p>"We have never heard from member M" is only informative if we have heard from SOMEONE in this
 * conversation. On a freshly started process the ledger is empty for everyone, and reading that as
 * "all members are absent" would make a cold start maximally impatient. So an empty ledger is
 * {@link Presence#UNKNOWN} and draws the unchanged base budget.
 *
 * <p><b>The honest consequence for 1:1 conversations: this change is a no-op there.</b> A 1:1 has
 * exactly one other member, so the ledger is empty precisely when we have never heard from that
 * member — the {@code NEVER_HEARD} discriminator cannot fire, and a 1:1 keeps today's base budget.
 * Distinguishing "the peer is gone" from "we have not started hearing yet" needs a third member to
 * hear from, and a 1:1 does not have one. That is a limit of the evidence, not a policy choice, and
 * it is stated here rather than papered over with a 1:1 constant that would be guessing.
 *
 * <p><b>{@code MlsPeerGuard}'s newly-fed failure count does not close this gap either, and it is
 * worth saying why so it is not re-opened.</b> That count rises only when the peer TALKS to us. An
 * absent 1:1 peer sends nothing and scores zero — identically to a perfectly healthy one — so it
 * separates <i>wedged</i> from <i>not-wedged</i>, which is orthogonal to the <i>present</i> vs
 * <i>gone</i> axis a 1:1 needs. Nor does feeding failure reports into the ledger help: in a 1:1 the
 * only entry the ledger can ever hold is the peer's own, so a non-empty ledger yields {@code QUIET}
 * or {@code ACTIVE} and {@code NEVER_HEARD} stays structurally unreachable. Closing this needs
 * evidence of a kind we do not have — a reachability answer we would trust as a negative — not a
 * rearrangement of the evidence we do.
 *
 * <p>The rank
 * stagger is likewise inert in a 1:1 (there is only rank 1), which is correct: with two parties a
 * duel is maximally self-limiting — whoever lands first wins and the other immediately sees itself
 * behind — while the cost of waiting is the whole conversation.
 *
 * <p>All methods are total and take explicit inputs; nothing here reads a clock. The bound stays a
 * count of LOOKS for the reason rework 5.3 made it one: a stopwatch says nothing about whether the
 * peer acted, and a device asleep for a week must wake having burned none of its budget. Presence is
 * classified against a monotone SEQUENCE the host bumps on every attributable inbound, so even the
 * "have we heard from them since we started waiting?" question is answered without a clock.
 */
public final class MlsAdvancerElection {

    private MlsAdvancerElection() {}

    /** What we know about a member's participation in THIS conversation. */
    public enum Presence {
        /**
         * We have heard from nobody at all here, so the ledger is uninformative. A cold-started
         * process looks exactly like a dead group from in here; neither may be assumed.
         */
        UNKNOWN,
        /**
         * We have heard from other members of this conversation but never from this one. This is
         * evidence about the member specifically, which is what makes it usable.
         */
        NEVER_HEARD,
        /** Heard from before, but not since this yield began. Gone quiet — not proven gone. */
        QUIET,
        /** Heard from since this yield began. Demonstrably alive; give it room to act. */
        ACTIVE
    }

    /**
     * A member that is alive RIGHT NOW gets double the base. A duel with a live advancer is the
     * expensive case — it forks the group, which is the thing the tie-break exists to prevent — and
     * a member we have heard from during this very yield is the most likely of all to be
     * mid-advance.
     */
    private static final int ACTIVE_MULTIPLE = 2;

    /**
     * A member we have never heard from, in a conversation where we HAVE heard from others, is
     * charged the floor: one unmoved look. Not zero — {@link
     * MlsRecoveryPolicy#eraYieldObservationsExhausted} reads a limit of {@code <= 0} as "yield
     * forever", so zero would mean the exact opposite of what this arm is for.
     */
    private static final int LOOKS_NEVER_HEARD = 1;

    /** The election's answer, with everything needed to log WHY. */
    public static final class Decision {
        /** The lowest E.164 in the conversation, or null if the roster was unusable. */
        public final String designated;
        /** Our 0-based position in the ascending roster. 0 = we are the designated advancer. */
        public final int rank;
        /** The strongest-evidence presence among the members AHEAD of us in the order. */
        public final Presence ahead;
        /**
         * Unmoved looks to burn before we may take over. Only meaningful when
         * {@link #weAdvanceNow()} is false — check that first. {@code 0} means "yield forever",
         * which is what a base budget of {@code <= 0} configures.
         */
        public final int looks;

        Decision(final String designated, final int rank, final Presence ahead, final int looks) {
            this.designated = designated;
            this.rank = rank;
            this.ahead = ahead;
            this.looks = looks;
        }

        /** True when we ARE the designated advancer and must act rather than yield. */
        public boolean weAdvanceNow() { return rank <= 0; }

        @Override public String toString() {
            return weAdvanceNow()
                    ? "advancer(self)"
                    : "yield to " + designated + " (rank " + rank + ", ahead=" + ahead
                            + ", takeover after " + looks + " unmoved look(s))";
        }
    }

    /**
     * The whole decision.
     *
     * @param selfE164   our own number. Unknown/empty FAILS OPEN to "we advance", matching
     *                   {@link MlsRecoveryPolicy#weAreEraAdvancer}: a conversation that never
     *                   recovers is worse than a possible duel, and a duel is self-limiting.
     * @param members    every member of the conversation INCLUDING ourselves, in any order
     * @param heardAtSeq member -> the monotone sequence value at which we last heard from them.
     *                   Absent key = never heard. An empty map = {@link Presence#UNKNOWN}.
     * @param yieldStartedAtSeq the sequence value when this yield began; anything strictly greater
     *                   counts as having been heard from DURING the yield
     * @param baseLooks  the configured look budget ({@code MlsConfig.eraYieldLooks}). {@code <= 0}
     *                   means yield forever and is honoured verbatim — presence never overrides an
     *                   operator who has asked this device never to take over.
     */
    public static Decision decide(final String selfE164, final Collection<String> members,
            final Map<String, Long> heardAtSeq, final long yieldStartedAtSeq, final int baseLooks) {
        final List<String> order = order(members);
        if (selfE164 == null || selfE164.isEmpty() || order.isEmpty()) {
            // Fails open exactly as weAreEraAdvancer does.
            return new Decision(order.isEmpty() ? null : order.get(0), 0, Presence.UNKNOWN, 0);
        }
        final int rank = order.indexOf(selfE164);
        if (rank <= 0) {
            // rank 0 = we are lowest. rank -1 = we are not in the roster we were given, which is
            // itself a reason to act rather than wait for a group we may not be in.
            return new Decision(order.get(0), 0, Presence.UNKNOWN, 0);
        }
        final Presence ahead = strongestAhead(order, rank, heardAtSeq, yieldStartedAtSeq);
        return new Decision(order.get(0), rank, ahead, looksBeforeTakeover(rank, ahead, baseLooks));
    }

    /**
     * The roster in advancement order: ascending E.164, index 0 is the designated advancer.
     *
     * <p>Sorting is the fix to a second, quieter defect. The self-heal call site compared self
     * against {@code roster.get(0)} — the first OTHER member in the order the server's pack happened
     * to arrive in, not the lowest number. Two members handed different pack orders would elect
     * different advancers and both advance, which is the duel the tie-break exists to prevent; that
     * it did not bite is luck about pack ordering, not a property of the code.
     */
    public static List<String> order(final Collection<String> members) {
        final List<String> out = new ArrayList<String>();
        if (members == null) return out;
        for (final String m : members) {
            if (m != null && !m.isEmpty() && !out.contains(m)) out.add(m);
        }
        Collections.sort(out);
        return out;
    }

    /** The designated advancer: the lowest E.164, or null if there is no usable roster. */
    public static String designated(final Collection<String> members) {
        final List<String> order = order(members);
        return order.isEmpty() ? null : order.get(0);
    }

    /**
     * <b>A bounded wait whose bound exceeds the enclosing budget is not a bounded wait.</b>
     *
     * <p>Device-captured at rank 3 of a group: the takeover
     * branch never fired and could not. Each look was also a self-heal attempt,
     * {@code selfHealRetryLimit} is 5, and the takeover budget at rank 3 is 9 — so the enclosing
     * budget escalated at attempt 5 and the yield never reached its own bound. Rank 1 needs 3
     * (reachable), rank 2 needs 6, rank 3 needs 9; on any group where we are not first or second in
     * E.164 order the takeover was dead code under the shipped constants.
     *
     * <p>It was invisible to every host test here because they exercise this class in isolation:
     * the interaction is with a DIFFERENT policy in the same ladder, and nothing reconciled the two
     * ceilings. This predicate is that reconciliation, made assertable.
     *
     * <p><b>The production fix is not to tune one against the other.</b> Capping the takeover budget
     * at the retry limit would force ranks to share a value, and two members holding the same budget
     * expire together and both advance — the duel the election exists to prevent. There is no way to
     * give unboundedly many ranks distinct, reachable budgets inside a budget of five. The host
     * instead stopped charging an attempt for a look (a yield is not a failed repair; it is a
     * decision not to repair yet, with its own convergence test and its own bound), which dissolves
     * the relationship rather than balancing it. Pass {@code enclosingAttempts <= 0} to say exactly
     * that: looks are free, and reachability is unconditional.
     *
     * <p>Keep this asserted anyway. If a later change puts a per-look cost back — the obvious one
     * being a probe added to the takeover path — the numbers go back into collision silently, and a
     * failing test is the only thing that would say so.
     *
     * <h2>Where this predicate's arguments come from — invariant I5</h2>
     *
     * <p>For most of its life this predicate had <b>no production call site</b> and the test
     * guarding the fix supplied {@code enclosingAttempts} as a literal {@code 0}. That {@code 0} was
     * a claim about {@code MlsProviderTransport} asserted by a constant typed into an engine test,
     * and the production fact behind it was the ORDER OF TWO STATEMENTS in {@code selfHealInner}.
     * Re-introducing the bug failed nothing. Both halves are now bound:
     *
     * <ul>
     *   <li>{@link MlsSelfHealPass#takeoverReachable} is the production call site, and
     *       {@link MlsSelfHealPass#enclosingSelfHealAttempts} COMPUTES this argument from the charge
     *       a look makes plus {@code MlsConfig.selfHealRetryLimit} — never a literal.</li>
     *   <li>{@code MlsSelfHealChargeGuardTest} derives the same argument a second and independent
     *       way, by reading the statement order out of {@code MlsProviderTransport.java}. A per-look
     *       cost reintroduced through either route fails a named test.</li>
     * </ul>
     *
     * <p><b>Do not call this with a hand-written {@code enclosingAttempts} in new code.</b> Go
     * through {@link MlsSelfHealPass}; that is the only caller whose argument is production's.
     *
     * @param enclosingAttempts how many attempts the enclosing budget allows per window, or
     *                          {@code <= 0} if looks cost nothing against it. Obtain it from
     *                          {@link MlsSelfHealPass#enclosingSelfHealAttempts}
     */
    public static boolean takeoverReachableWithin(final int rank, final Presence ahead,
            final int baseLooks, final int enclosingAttempts) {
        if (baseLooks <= 0) return false;          // configured to yield forever, deliberately
        if (rank <= 0) return true;                // designated; never waits at all
        if (enclosingAttempts <= 0) return true;   // looks are free — nothing to collide with
        // Strictly less than, not equal: firing on the very last permitted attempt leaves no margin
        // for anything else in the window that also spends one.
        return looksBeforeTakeover(rank, ahead, baseLooks) < enclosingAttempts;
    }

    /**
     * What we know about one member's participation.
     *
     * @param heardAtSeq        the ledger; empty means we know nothing about anyone
     * @param yieldStartedAtSeq the sequence value when the yield began
     */
    public static Presence classify(final String member, final Map<String, Long> heardAtSeq,
            final long yieldStartedAtSeq) {
        if (heardAtSeq == null || heardAtSeq.isEmpty()) return Presence.UNKNOWN;
        if (member == null || member.isEmpty()) return Presence.UNKNOWN;
        final Long at = heardAtSeq.get(member);
        if (at == null) return Presence.NEVER_HEARD;
        return at.longValue() > yieldStartedAtSeq ? Presence.ACTIVE : Presence.QUIET;
    }

    /**
     * The strongest evidence of life among the members ahead of us — i.e. the most patient arm any
     * of them justifies.
     *
     * <p>Strongest, not the designated advancer's alone: taking over means claiming the advance from
     * EVERY member ahead of us in the order, so one live member anywhere ahead is a reason to keep
     * waiting. Jumping the queue over a live rank-1 is precisely the duel.
     */
    public static Presence strongestAhead(final List<String> order, final int rank,
            final Map<String, Long> heardAtSeq, final long yieldStartedAtSeq) {
        Presence worst = Presence.NEVER_HEARD;
        boolean any = false;
        for (int i = 0; i < rank && i < order.size(); i++) {
            final Presence p = classify(order.get(i), heardAtSeq, yieldStartedAtSeq);
            any = true;
            if (rankOfPresence(p) > rankOfPresence(worst)) worst = p;
        }
        return any ? worst : Presence.UNKNOWN;
    }

    /** How much patience a presence justifies, ascending. */
    private static int rankOfPresence(final Presence p) {
        switch (p) {
            case NEVER_HEARD: return 0;
            case QUIET:       return 1;
            case UNKNOWN:     return 2;
            case ACTIVE:      return 3;
            default:          return 2;
        }
    }

    /**
     * How many unmoved looks a member at {@code rank} must burn before taking the advance over —
     * and, because one look is one {@code GetMlsGroupInfo}, how many fetches it may spend doing so.
     *
     * <p>{@code presenceLooks + (rank - 1) × baseLooks}: your own presence budget, plus one
     * base-length turn for every member ahead of you. Strictly increasing in {@code rank} for every
     * fixed presence, so no two members of a group can ever hold the same budget — which is the
     * property the anti-duel stagger rests on — while the worst case grows LINEARLY with group size
     * rather than as a product. See the class javadoc for why that distinction is a fetch-quota
     * question and not a matter of taste.
     *
     * @param rank      our 0-based position; {@code <= 0} means we are the advancer and this does
     *                  not apply
     * @param ahead     the strongest evidence among members ahead of us
     * @param baseLooks the configured budget; {@code <= 0} = yield forever, returned verbatim
     */
    public static int looksBeforeTakeover(final int rank, final Presence ahead,
            final int baseLooks) {
        if (rank <= 0) return 0;             // designated — the caller must not be asking
        if (baseLooks <= 0) return 0;        // configured to yield forever; presence cannot override
        final int presenceLooks;
        switch (ahead == null ? Presence.UNKNOWN : ahead) {
            case NEVER_HEARD:
                // The whole point: a member this conversation has never heard from, in a
                // conversation that is demonstrably carrying traffic from others, is not coming.
                // Charged the floor rather than the base budget.
                presenceLooks = LOOKS_NEVER_HEARD;
                break;
            case ACTIVE:
                presenceLooks = ACTIVE_MULTIPLE * baseLooks;
                break;
            case QUIET:
            case UNKNOWN:
            default:
                // Gone quiet is not proven gone, and no evidence is not evidence — both keep the
                // budget this member had before any of this existed.
                presenceLooks = baseLooks;
                break;
        }
        return presenceLooks + (rank - 1) * baseLooks;
    }
}
