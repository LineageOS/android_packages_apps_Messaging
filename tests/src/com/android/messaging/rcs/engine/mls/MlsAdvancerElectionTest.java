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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.android.messaging.rcs.engine.mls.MlsAdvancerElection.Decision;
import com.android.messaging.rcs.engine.mls.MlsAdvancerElection.Presence;

/**
 * The designated-advancer yield must not wait forever on an
 * advancer that is never coming, AND must not stop waiting in a way that reintroduces the duel the
 * yield exists to prevent.
 *
 * <p>The anti-duel tests carry the weight here. A test that one member takes over is nearly free;
 * the property worth pinning is that when SEVERAL members' budgets are close to expiring they do not
 * all advance — the stagger orders them, and the first takeover moves the group, which stands the
 * rest down through {@link MlsRecoveryPolicy#eraYieldSatisfied}.
 */
public class MlsAdvancerElectionTest {

    // The real device fixture: one line yields to another that was not connected and may never be.
    private static final String AU = "+15715550104";     // 00AU — the yielding member
    private static final String D0286 = "+15715550103";  // 0286 — designated, absent
    private static final String T010T = "+15715550109";  // 010T — third member, live
    private static final int BASE = MlsConfig.DEF_ERA_YIELD_LOOKS;   // 3

    private static Map<String, Long> heard(final Object... pairs) {
        final Map<String, Long> m = new HashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], Long.valueOf(((Number) pairs[i + 1]).longValue()));
        }
        return m;
    }

    // ---- the designation itself -------------------------------------------------------------

    @Test public void theDesignatedAdvancerIsTheLowestE164AndNothingElseMovesIt() {
        final List<String> group = Arrays.asList(AU, D0286, T010T);
        assertEquals(D0286, MlsAdvancerElection.designated(group));
        // Every member derives the SAME answer from the same roster — that is what stops the duel.
        for (final String self : group) {
            assertEquals(D0286, MlsAdvancerElection.decide(
                    self, group, heard(), 0L, BASE).designated);
        }
        // ...including when we have evidence the designated member is absent. Presence must NEVER
        // move the designation: liveness is local and asymmetric, so a presence-weighted designation
        // would let two members disagree about who is designated, and two who disagree both advance.
        final Map<String, Long> auHeardOnlyFrom010T = heard(T010T, 5L);
        assertEquals(D0286, MlsAdvancerElection.decide(
                AU, group, auHeardOnlyFrom010T, 0L, BASE).designated);
    }

    @Test public void theRosterIsSortedRatherThanTakenInPackOrder() {
        // The quieter of the two defects. The self-heal site elected against roster.get(0) — the
        // first OTHER member in the order the server's pack happened to arrive in. Two members
        // handed different pack orders elect different advancers and BOTH advance.
        assertEquals(Arrays.asList(D0286, AU, T010T),
                MlsAdvancerElection.order(Arrays.asList(T010T, AU, D0286)));
        assertEquals(Arrays.asList(D0286, AU, T010T),
                MlsAdvancerElection.order(Arrays.asList(AU, D0286, T010T)));
    }

    @Test public void anUnusableRosterFailsOpen() {
        // Same contract as MlsRecoveryPolicy.weAreEraAdvancer: a conversation that never recovers is
        // worse than a possible duel, and a duel is self-limiting.
        assertTrue(MlsAdvancerElection.decide(null, Arrays.asList(AU, D0286), heard(), 0L, BASE)
                .weAdvanceNow());
        assertTrue(MlsAdvancerElection.decide("", Arrays.asList(AU, D0286), heard(), 0L, BASE)
                .weAdvanceNow());
        assertTrue(MlsAdvancerElection.decide(AU, new ArrayList<String>(), heard(), 0L, BASE)
                .weAdvanceNow());
        assertNull(MlsAdvancerElection.designated(null));
        // Not in the roster we were handed: acting beats waiting for a group we may not be in.
        assertTrue(MlsAdvancerElection.decide(AU, Arrays.asList(D0286, T010T), heard(), 0L, BASE)
                .weAdvanceNow());
    }

    @Test public void theLowestMemberNeverYields() {
        final Decision d = MlsAdvancerElection.decide(
                D0286, Arrays.asList(AU, D0286, T010T), heard(AU, 9L), 0L, BASE);
        assertTrue(d.weAdvanceNow());
        assertEquals(0, d.rank);
    }

    // ---- classifying the evidence -----------------------------------------------------------

    @Test public void anEmptyLedgerIsUnknownNotAbsent() {
        // Evidence of absence requires evidence of presence. A freshly started process has heard
        // from nobody; reading that as "everyone is absent" would make a cold start maximally
        // impatient, which is the direction that can fork a group.
        assertEquals(Presence.UNKNOWN, MlsAdvancerElection.classify(D0286, heard(), 0L));
        assertEquals(Presence.UNKNOWN, MlsAdvancerElection.classify(D0286, null, 0L));
    }

    @Test public void neverHeardIsOnlyClaimedWhenWeHaveHeardFromSomeoneElse() {
        // This is the 0286 case exactly: the conversation is demonstrably carrying traffic from
        // 010T, and 0286 has contributed nothing to it.
        assertEquals(Presence.NEVER_HEARD,
                MlsAdvancerElection.classify(D0286, heard(T010T, 4L), 0L));
    }

    @Test public void quietAndActiveAreSeparatedByTheYieldsOwnStartingSequence() {
        // Heard from at sequence 4, yield began at 7 -> we have not heard from them since we started
        // waiting. Gone quiet, not proven gone.
        assertEquals(Presence.QUIET, MlsAdvancerElection.classify(D0286, heard(D0286, 4L), 7L));
        // Heard from at 9, yield began at 7 -> alive right now, and quite possibly mid-advance.
        assertEquals(Presence.ACTIVE, MlsAdvancerElection.classify(D0286, heard(D0286, 9L), 7L));
        // The boundary is strict: heard AT the instant the yield began is not "since".
        assertEquals(Presence.QUIET, MlsAdvancerElection.classify(D0286, heard(D0286, 7L), 7L));
    }

    @Test public void theStrongestEvidenceAheadWins() {
        // Taking over claims the advance from EVERY member ahead of us, so one live member anywhere
        // ahead is a reason to keep waiting. Jumping the queue over a live rank-1 IS the duel.
        final List<String> order = MlsAdvancerElection.order(Arrays.asList(AU, D0286, T010T));
        // We are T010T (rank 2). 0286 never heard from, AU heard from during the yield.
        assertEquals(Presence.ACTIVE,
                MlsAdvancerElection.strongestAhead(order, 2, heard(AU, 9L), 7L));
        // Both ahead of us silent -> nothing ahead is alive.
        assertEquals(Presence.NEVER_HEARD,
                MlsAdvancerElection.strongestAhead(order, 2, heard(T010T, 9L), 7L));
        // Nobody ahead of us at all (rank 0) is UNKNOWN, not NEVER_HEARD — there is no member the
        // claim could be about.
        assertEquals(Presence.UNKNOWN,
                MlsAdvancerElection.strongestAhead(order, 0, heard(AU, 9L), 7L));
    }

    // ---- the budget --------------------------------------------------------------------------

    @Test public void anAbsentAdvancerIsAbandonedAfterOneLookRatherThanTheFullBudget() {
        // The measured symptom: "look 2/3, group unmoved" while yielding to a device that is not
        // connected and may never be.
        assertEquals(1, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, BASE));
        assertTrue("a never-heard advancer must cost strictly less than the fixed budget did",
                MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, BASE)
                        < MlsAdvancerElection.looksBeforeTakeover(1, Presence.UNKNOWN, BASE));
    }

    @Test public void aLiveAdvancerBuysMorePatienceThanNoEvidenceDoes() {
        // A duel with a LIVE advancer is the expensive case — it forks the group, which is what the
        // tie-break exists to prevent — and a member heard from during this very yield is the most
        // likely of all to be mid-advance.
        assertEquals(2 * BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.ACTIVE, BASE));
        assertEquals(BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.QUIET, BASE));
        assertEquals(BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.UNKNOWN, BASE));
        assertEquals("no evidence at all keeps today's behaviour, unchanged",
                BASE, MlsAdvancerElection.looksBeforeTakeover(1, null, BASE));
    }

    @Test public void aYieldForeverConfigurationIsNeverOverriddenByPresence() {
        // MlsConfig documents 0 as "yield indefinitely" and MlsRecoveryPolicy reads <= 0 that way.
        // An operator who has asked a device never to take over must not be talked out of it by the
        // strongest possible absence evidence.
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, 0));
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(2, Presence.NEVER_HEARD, 0));
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, -1));
        assertFalse("and the primitive must agree",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, 0));
    }

    @Test public void theFloorIsOneLookAndNeverZero() {
        // Zero would mean the OPPOSITE of impatience — eraYieldObservationsExhausted reads <= 0 as
        // "yield forever" — so the never-heard arm must never produce it for a real rank.
        for (int rank = 1; rank <= 8; rank++) {
            assertTrue("rank " + rank,
                    MlsAdvancerElection.looksBeforeTakeover(rank, Presence.NEVER_HEARD, BASE) >= 1);
        }
    }

    // ---- THE ANTI-DUEL PROPERTY --------------------------------------------------------------

    @Test public void severalMembersWithExpiringBudgetsDoNotAllTakeOver() {
        // The property this whole design is arranged around, stated directly.
        //
        // Group of five, all yielding to a designated advancer none of them has ever heard from —
        // i.e. every non-designated member has the STRONGEST possible reason to take over, and the
        // crude "look budget exhausted" rule would fire for all four at once.
        final List<String> group = Arrays.asList(
                "+15710000001", "+15710000002", "+15710000003", "+15710000004", "+15710000005");
        final String absent = "+15710000001";
        // The ledger is non-empty — so NEVER_HEARD is claimable rather than UNKNOWN — but holds only
        // the TOP of the roster, so for every member under test nobody AHEAD of it has been heard
        // from. That isolates the stagger: identical, maximal absence evidence for all four.
        final Map<String, Long> ledger = heard("+15710000005", 5L);

        int previous = 0;
        for (final String self : group) {
            final Decision d = MlsAdvancerElection.decide(self, group, ledger, 9L, BASE);
            if (self.equals(absent)) {
                assertTrue("the designated advancer never yields", d.weAdvanceNow());
                continue;
            }
            assertFalse(self + " must yield", d.weAdvanceNow());
            assertEquals(Presence.NEVER_HEARD, d.ahead);
            assertTrue("takeover budgets must be STRICTLY ordered by rank, or several members "
                    + "expire together and duel: " + self + " got " + d.looks
                    + " which is not greater than " + previous, d.looks > previous);
            previous = d.looks;
        }
    }

    @Test public void theNextLowestTakesOverFirstAndTheRestStandDown() {
        // The fallback order is itself deterministic. Rank 1 acts first; its advance MOVES THE
        // GROUP; every remaining yielder's next look sees the movement and stands down through
        // eraYieldSatisfied without ever reaching its own budget. That is the mechanism, so the
        // second half of this test asserts the stand-down, not just the ordering.
        final List<String> group = Arrays.asList(AU, D0286, T010T);
        final Map<String, Long> ledger = heard(AU, 1L, T010T, 2L);   // never heard from 0286

        final Decision rank1 = MlsAdvancerElection.decide(AU, group, ledger, 9L, BASE);
        final Decision rank2 = MlsAdvancerElection.decide(T010T, group, ledger, 9L, BASE);
        assertEquals(1, rank1.rank);
        assertEquals(2, rank2.rank);
        assertTrue("the next-lowest E.164 must reach its takeover first",
                rank1.looks < rank2.looks);

        // Rank 1 takes over at its budget...
        assertTrue(MlsRecoveryPolicy.eraYieldObservationsExhausted(rank1.looks, rank1.looks));
        // ...and rank 2 has not reached its own at that point...
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(rank1.looks, rank2.looks));
        // ...and rank 1's advance moves the group, which is what actually stands rank 2 down: the
        // moment check runs BEFORE the bound in eraYieldExhausted, so even a rank 2 that HAD
        // reached its budget reports the yield as having worked rather than acting.
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(
                new MlsAppMessage.Moment(1, 1L), new MlsAppMessage.Moment(2, 0L)));
    }

    @Test public void aMemberBehindALiveMemberWaitsLongerThanTheLiveMemberItself() {
        // Rank 2 must not be able to overtake a rank 1 that is alive, even when rank 2's view of the
        // designated advancer is the most damning possible.
        final List<String> group = Arrays.asList(AU, D0286, T010T);
        // T010T (rank 2) has heard from AU (rank 1) during the yield, never from 0286 (rank 0).
        final Decision rank2 = MlsAdvancerElection.decide(T010T, group, heard(AU, 12L), 9L, BASE);
        assertEquals(Presence.ACTIVE, rank2.ahead);
        assertEquals("2*BASE for the live member, plus one BASE-length turn for the member ahead",
                3 * BASE, rank2.looks);
        // AU's own budget against the absent 0286 is far shorter, so AU acts long before T010T.
        final Decision rank1 = MlsAdvancerElection.decide(AU, group, heard(T010T, 4L), 9L, BASE);
        assertEquals(Presence.NEVER_HEARD, rank1.ahead);
        assertTrue(rank1.looks < rank2.looks);
    }

    // ---- a WEDGED advancer is a PRESENT advancer ---------------------------------------------

    @Test public void aLoudlyWedgedAdvancerGetsTheMostPatientArmNotTheLeast() {
        // THE P0 SHAPE, pinned. The third party's iPhone we wedged for a month was the lowest
        // MSISDN — therefore the designated advancer — and it sent no positive traffic
        // at all. Its only traffic to us was §7.7.2.2 failure reports.
        //
        // If those reports do not reach the ledger, that peer has no entry, classifies NEVER_HEARD,
        // and draws the FLOOR budget: we would take the era advance over from it faster than from
        // any other peer — and every era advance forces every member to rebuild by Welcome, which is
        // the mechanism that wedged it. So the host records a failure report as presence, and the
        // policy consequence is asserted here.
        final List<String> group = Arrays.asList(AU, D0286, T010T);
        final Map<String, Long> ledgerWithoutTheReport = heard(T010T, 4L);
        final Map<String, Long> ledgerWithTheReport = heard(T010T, 4L, D0286, 12L);

        assertEquals("without the report the wedged advancer is invisible",
                Presence.NEVER_HEARD,
                MlsAdvancerElection.decide(AU, group, ledgerWithoutTheReport, 9L, BASE).ahead);
        final Decision withReport =
                MlsAdvancerElection.decide(AU, group, ledgerWithTheReport, 9L, BASE);
        assertEquals(Presence.ACTIVE, withReport.ahead);
        assertTrue("a peer that is talking to us must buy MORE patience, not less",
                withReport.looks
                        > MlsAdvancerElection.decide(AU, group, ledgerWithoutTheReport, 9L, BASE).looks);
    }

    @Test public void activeIsStrictlyTheMostPatientArmThereIs() {
        // Stated as an invariant rather than left implicit in the constants, because the safety
        // argument above depends on it: whatever the arms are tuned to, a member we have heard from
        // during this yield must never be overtaken sooner than one we have not.
        for (final Presence p : Presence.values()) {
            if (p == Presence.ACTIVE) continue;
            assertTrue(p + " must not be more patient than ACTIVE",
                    MlsAdvancerElection.looksBeforeTakeover(1, Presence.ACTIVE, BASE)
                            > MlsAdvancerElection.looksBeforeTakeover(1, p, BASE));
        }
    }

    @Test public void anyLedgerEntryAtAllRulesOutNeverHeard() {
        // The property the host's fifth call site relies on: it does not matter WHICH inbound path
        // recorded the member, only that one did. A failure report and a decrypted message are the
        // same evidence here — bytes arrived — and whether the news was good is MlsPeerGuard's
        // question, not this class's.
        assertEquals(Presence.QUIET, MlsAdvancerElection.classify(D0286, heard(D0286, 1L), 9L));
        assertEquals(Presence.ACTIVE, MlsAdvancerElection.classify(D0286, heard(D0286, 12L), 9L));
    }

    // ---- the budget is a FETCH budget ---------------------------------------------------------

    @Test public void noTwoRanksEverShareABudget() {
        // The anti-duel stagger rests entirely on this. If two members of a group can hold the same
        // budget they expire together and both advance, which is the fork the tie-break exists to
        // prevent — so it is asserted directly rather than inferred from a worked example, and for
        // every presence arm, not just the interesting one.
        for (final Presence p : Presence.values()) {
            int previous = Integer.MIN_VALUE;
            for (int rank = 1; rank <= 32; rank++) {
                final int looks = MlsAdvancerElection.looksBeforeTakeover(rank, p, BASE);
                assertTrue(p + " rank " + rank + " -> " + looks + " does not exceed " + previous,
                        looks > previous);
                previous = looks;
            }
        }
    }

    @Test public void theWorstCaseGrowsLinearlyWithGroupSizeBecauseALookCostsAFetch() {
        // selfHeal fetches once per pass and evaluates the yield in that same pass, so one look is
        // one GetMlsGroupInfo. Measured 2026-09-08 on 00AU: a loop that could not converge fetched
        // until the server answered grpcStatus=8 RESOURCE_EXHAUSTED, and the identical single fetch
        // succeeded after a 200s cooldown — self-inflicted, and it reads like a server wall.
        //
        // The multiplicative form this replaced charged rank x presence x base, so patience and
        // group size multiplied. Additive keeps the step at exactly baseLooks per member ahead.
        for (final Presence p : Presence.values()) {
            for (int rank = 2; rank <= 32; rank++) {
                assertEquals(p + " rank " + rank + ": each additional member ahead must cost exactly "
                        + "one base-length turn, never a multiple of the whole budget",
                        BASE,
                        MlsAdvancerElection.looksBeforeTakeover(rank, p, BASE)
                                - MlsAdvancerElection.looksBeforeTakeover(rank - 1, p, BASE));
            }
        }
        // Concretely: the 10th-lowest member of a group, behind a demonstrably live member — the
        // most patient arm there is — spends 30 fetches, not the 54 the multiplicative form charged.
        assertEquals(30, MlsAdvancerElection.looksBeforeTakeover(9, Presence.ACTIVE, BASE));
    }

    // ---- the enclosing budget ----------------------------------------------------------------

    @Test public void theShippedConstantsPutTheTakeoverOutOfReachIfALookCostsAnAttempt() {
        // THE DEVICE DEFECT, pinned as arithmetic. Captured on deviceB at rank 3 of group 3256689f:
        // the takeover branch never fired, because each look was ALSO a self-heal attempt and the
        // enclosing budget escalated at attempt 5 while the takeover needed look 9.
        //
        // This is the test that would have caught it at desk time, and the reason it did not exist
        // is that every other test here exercises the election in ISOLATION — the collision is with
        // a different policy in the same ladder.
        final int retries = MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT;   // 5
        assertTrue("rank 1 was always fine, which is why this hid",
                MlsAdvancerElection.takeoverReachableWithin(1, Presence.UNKNOWN, BASE, retries));
        assertFalse("rank 2 needs 6 attempts out of 5",
                MlsAdvancerElection.takeoverReachableWithin(2, Presence.UNKNOWN, BASE, retries));
        assertFalse("rank 3 needs 9 out of 5 — the captured case",
                MlsAdvancerElection.takeoverReachableWithin(3, Presence.UNKNOWN, BASE, retries));
        assertFalse("and a LIVE advancer is out of reach even at rank 1",
                MlsAdvancerElection.takeoverReachableWithin(1, Presence.ACTIVE, BASE, retries));
    }

    @Test public void makingLooksFreeIsWhatMakesEveryRankReachable() {
        // The production fix: the host stopped charging a self-heal attempt for a look, because a
        // yield is not a failed repair — it is a decision not to repair yet, with its own
        // convergence test and its own bound.
        //
        // THE ARGUMENT IS READ FROM PRODUCTION, NOT TYPED HERE (invariant I5). This line
        // used to pass a literal 0 — a claim about MlsProviderTransport asserted by a constant in an
        // engine test — and the production fact behind that 0 was the order of two statements in
        // selfHealInner. Reordering them left all 1,154 host tests green, which is what made the
        // whole predicate decorative. MlsSelfHealPass now COMPUTES the argument from the charge a
        // look makes, and MlsSelfHealChargeGuardTest reads the statement order out of the transport
        // and derives it a second, independent way.
        //
        // Asserted across ranks and arms so that if a per-look cost is ever reintroduced (the
        // obvious one being a liveness probe on the takeover path) the numbers cannot go back into
        // collision silently.
        final int enclosing = MlsSelfHealPass.enclosingSelfHealAttempts(
                MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT);
        for (final Presence p : Presence.values()) {
            for (int rank = 1; rank <= 16; rank++) {
                assertTrue(p + " rank " + rank + " (enclosingAttempts=" + enclosing + ")",
                        MlsAdvancerElection.takeoverReachableWithin(rank, p, BASE, enclosing));
            }
        }
    }

    @Test public void cappingTheBudgetToFitWouldHaveReintroducedTheDuel() {
        // Why the obvious fix was rejected, kept as an executable argument rather than a comment.
        // Clamping the takeover budget into the enclosing one forces distinct ranks onto the same
        // value, and two members holding the same budget expire together and both advance.
        final int cap = MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT - 1;   // 4
        final int rank2 = Math.min(
                MlsAdvancerElection.looksBeforeTakeover(2, Presence.UNKNOWN, BASE), cap);
        final int rank3 = Math.min(
                MlsAdvancerElection.looksBeforeTakeover(3, Presence.UNKNOWN, BASE), cap);
        assertEquals("clamping collapses two ranks onto one budget — that is the duel", rank2, rank3);
        // Whereas the real budgets never tie, which noTwoRanksEverShareABudget asserts in general.
        assertTrue(MlsAdvancerElection.looksBeforeTakeover(3, Presence.UNKNOWN, BASE)
                > MlsAdvancerElection.looksBeforeTakeover(2, Presence.UNKNOWN, BASE));
    }

    @Test public void aYieldForeverConfigurationIsNotReachableAndSaysSo() {
        // baseLooks <= 0 means "never take over", so reachability must be false rather than
        // vacuously true — otherwise a caller asserting reachability would read a deliberate
        // never-take-over as a healthy configuration.
        assertFalse(MlsAdvancerElection.takeoverReachableWithin(1, Presence.NEVER_HEARD, 0, 0));
        assertTrue("the designated advancer does not wait at all",
                MlsAdvancerElection.takeoverReachableWithin(0, Presence.UNKNOWN, BASE, 5));
    }

    // ---- 1:1 ---------------------------------------------------------------------------------

    @Test public void aOneToOneIsUnchangedBecauseTheEvidenceCannotExistThere() {
        // Stated as a test because it is a real limit, not an oversight. A 1:1 has exactly one other
        // member, so the ledger is empty PRECISELY when we have never heard from that member — the
        // NEVER_HEARD arm is unreachable and the base budget stands. Telling "the peer is gone" from
        // "we have not started hearing yet" needs a third member to have heard from, and a 1:1 does
        // not have one.
        final List<String> pair = Arrays.asList(AU, D0286);
        final Decision d = MlsAdvancerElection.decide(AU, pair, heard(), 0L, BASE);
        assertFalse(d.weAdvanceNow());
        assertEquals(1, d.rank);
        assertEquals(Presence.UNKNOWN, d.ahead);
        assertEquals("a 1:1 keeps exactly the budget it had before this change", BASE, d.looks);
    }

    @Test public void aOneToOnePeerThatHasSpokenBeforeIsStillTreatedAsQuiet() {
        final List<String> pair = Arrays.asList(AU, D0286);
        final Decision d = MlsAdvancerElection.decide(AU, pair, heard(D0286, 3L), 7L, BASE);
        assertEquals(Presence.QUIET, d.ahead);
        assertEquals(BASE, d.looks);
    }

    // ---- the decision is loggable ------------------------------------------------------------

    @Test public void theDecisionSaysWhyInOneLine() {
        // The log line is the only thing anyone reads on device, and a takeover that cannot be
        // explained after the fact is a takeover nobody can review.
        final Decision d = MlsAdvancerElection.decide(
                AU, Arrays.asList(AU, D0286, T010T), heard(T010T, 4L), 0L, BASE);
        final String s = d.toString();
        assertTrue(s, s.contains(D0286));
        assertTrue(s, s.contains("NEVER_HEARD"));
        assertTrue(s, s.contains("rank 1"));
        assertEquals("advancer(self)", MlsAdvancerElection.decide(
                D0286, Arrays.asList(AU, D0286), heard(), 0L, BASE).toString());
    }
}
