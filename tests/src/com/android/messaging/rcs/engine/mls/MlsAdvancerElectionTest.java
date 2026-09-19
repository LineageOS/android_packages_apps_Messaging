/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
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
import com.android.messaging.rcs.log.LogMask;

/**
 * The designated-advancer yield ends when the advancer is absent without reintroducing the duel the
 * yield prevents: when several members' budgets are close to expiring, the stagger orders them and
 * the first takeover moves the group, which stands the rest down through
 * {@link MlsRecoveryPolicy#eraYieldSatisfied}. See docs/mls/health-and-recovery.md.
 */
public class MlsAdvancerElectionTest {

    // One member yields to a designated advancer that may never be connected.
    private static final String YIELDER = "+15715550104";     // the yielding member
    private static final String DESIGNATED = "+15715550103";  // designated, absent
    private static final String THIRD = "+15715550109";  // third member, live
    private static final int BASE = MlsConfig.DEF_ERA_YIELD_LOOKS;   // 3

    private static Map<String, Long> heard(final Object... pairs) {
        final Map<String, Long> m = new HashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], Long.valueOf(((Number) pairs[i + 1]).longValue()));
        }
        return m;
    }

    @Test public void theDesignatedAdvancerIsTheLowestE164AndNothingElseMovesIt() {
        final List<String> group = Arrays.asList(YIELDER, DESIGNATED, THIRD);
        assertEquals(DESIGNATED, MlsAdvancerElection.designated(group));
        // Every member derives the same answer from the same roster, which is what stops the duel.
        for (final String self : group) {
            assertEquals(DESIGNATED, MlsAdvancerElection.decide(
                    self, group, heard(), 0L, BASE).designated);
        }
        // Presence never moves the designation: liveness is local and asymmetric, so a
        // presence-weighted designation would let two members disagree about who is designated, and
        // both advance.
        final Map<String, Long> yielderHeardOnlyFromThird = heard(THIRD, 5L);
        assertEquals(DESIGNATED, MlsAdvancerElection.decide(
                YIELDER, group, yielderHeardOnlyFromThird, 0L, BASE).designated);
    }

    @Test public void theRosterIsSortedRatherThanTakenInPackOrder() {
        // The order is by E.164, not by the order the server's pack arrived in; two members handed
        // different pack orders must elect the same advancer.
        assertEquals(Arrays.asList(DESIGNATED, YIELDER, THIRD),
                MlsAdvancerElection.order(Arrays.asList(THIRD, YIELDER, DESIGNATED)));
        assertEquals(Arrays.asList(DESIGNATED, YIELDER, THIRD),
                MlsAdvancerElection.order(Arrays.asList(YIELDER, DESIGNATED, THIRD)));
    }

    @Test public void anUnusableRosterFailsOpen() {
        // Same contract as MlsRecoveryPolicy.weAreEraAdvancer: a conversation that never recovers
        // is worse than a possible duel, and a duel is self-limiting.
        assertTrue(
                MlsAdvancerElection.decide(null, Arrays.asList(YIELDER, DESIGNATED), heard(), 0L,
                        BASE)
                .weAdvanceNow());
        assertTrue(
                MlsAdvancerElection.decide("", Arrays.asList(YIELDER, DESIGNATED), heard(), 0L,
                        BASE)
                .weAdvanceNow());
        assertTrue(MlsAdvancerElection.decide(YIELDER, new ArrayList<String>(), heard(), 0L, BASE)
                .weAdvanceNow());
        assertNull(MlsAdvancerElection.designated(null));
        // Not in the roster we were handed: acting beats waiting for a group we may not be in.
        assertTrue(
                MlsAdvancerElection.decide(YIELDER, Arrays.asList(DESIGNATED, THIRD), heard(), 0L,
                        BASE)
                .weAdvanceNow());
    }

    @Test public void theLowestMemberNeverYields() {
        final Decision d = MlsAdvancerElection.decide(
                DESIGNATED, Arrays.asList(YIELDER, DESIGNATED, THIRD), heard(YIELDER, 9L), 0L,
                BASE);
        assertTrue(d.weAdvanceNow());
        assertEquals(0, d.rank);
    }

    @Test public void anEmptyLedgerIsUnknownNotAbsent() {
        // Evidence of absence requires evidence of presence: a cold start that read an empty ledger
        // as "everyone is absent" would be maximally impatient, the direction that can fork a
        // group.
        assertEquals(Presence.UNKNOWN, MlsAdvancerElection.classify(DESIGNATED, heard(), 0L));
        assertEquals(Presence.UNKNOWN, MlsAdvancerElection.classify(DESIGNATED, null, 0L));
    }

    @Test public void neverHeardIsOnlyClaimedWhenWeHaveHeardFromSomeoneElse() {
        // The conversation carries traffic from the third member and none from the designated one.
        assertEquals(Presence.NEVER_HEARD,
                MlsAdvancerElection.classify(DESIGNATED, heard(THIRD, 4L), 0L));
    }

    @Test public void quietAndActiveAreSeparatedByTheYieldsOwnStartingSequence() {
        // Heard from at 4, yield began at 7: quiet since we started waiting, not proven gone.
        assertEquals(Presence.QUIET,
                MlsAdvancerElection.classify(DESIGNATED, heard(DESIGNATED, 4L), 7L));
        // Heard from at 9, yield began at 7: alive now, possibly mid-advance.
        assertEquals(Presence.ACTIVE,
                MlsAdvancerElection.classify(DESIGNATED, heard(DESIGNATED, 9L), 7L));
        // The boundary is strict: heard at the instant the yield began is not "since".
        assertEquals(Presence.QUIET,
                MlsAdvancerElection.classify(DESIGNATED, heard(DESIGNATED, 7L), 7L));
    }

    @Test public void theStrongestEvidenceAheadWins() {
        // Taking over claims the advance from every member ahead of us, so one live member ahead is
        // a reason to keep waiting.
        final List<String> order =
                MlsAdvancerElection.order(Arrays.asList(YIELDER, DESIGNATED, THIRD));
        // We are THIRD (rank 2): DESIGNATED never heard from, YIELDER heard from during the yield.
        assertEquals(Presence.ACTIVE,
                MlsAdvancerElection.strongestAhead(order, 2, heard(YIELDER, 9L), 7L));
        // Both ahead of us silent -> nothing ahead is alive.
        assertEquals(Presence.NEVER_HEARD,
                MlsAdvancerElection.strongestAhead(order, 2, heard(THIRD, 9L), 7L));
        // Nobody ahead of us (rank 0) is UNKNOWN: there is no member the claim could be about.
        assertEquals(Presence.UNKNOWN,
                MlsAdvancerElection.strongestAhead(order, 0, heard(YIELDER, 9L), 7L));
    }

    @Test public void anAbsentAdvancerIsAbandonedAfterOneLookRatherThanTheFullBudget() {
        assertEquals(1, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, BASE));
        assertTrue("a never-heard advancer must cost strictly less than the fixed budget did",
                MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, BASE)
                        < MlsAdvancerElection.looksBeforeTakeover(1, Presence.UNKNOWN, BASE));
    }

    @Test public void aLiveAdvancerBuysMorePatienceThanNoEvidenceDoes() {
        // A duel with a live advancer forks the group, and a member heard from during this yield is
        // the most likely to be mid-advance.
        assertEquals(2 * BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.ACTIVE, BASE));
        assertEquals(BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.QUIET, BASE));
        assertEquals(BASE, MlsAdvancerElection.looksBeforeTakeover(1, Presence.UNKNOWN, BASE));
        assertEquals("no evidence at all keeps today's behaviour, unchanged",
                BASE, MlsAdvancerElection.looksBeforeTakeover(1, null, BASE));
    }

    @Test public void aYieldForeverConfigurationIsNeverOverriddenByPresence() {
        // MlsConfig documents 0 as "yield indefinitely", and absence evidence must not override it.
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, 0));
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(2, Presence.NEVER_HEARD, 0));
        assertEquals(0, MlsAdvancerElection.looksBeforeTakeover(1, Presence.NEVER_HEARD, -1));
        assertFalse("and the primitive must agree",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, 0));
    }

    @Test public void theFloorIsOneLookAndNeverZero() {
        // Zero would mean "yield forever" to eraYieldObservationsExhausted, so the never-heard arm
        // must never produce it for a real rank.
        for (int rank = 1; rank <= 8; rank++) {
            assertTrue("rank " + rank,
                    MlsAdvancerElection.looksBeforeTakeover(rank, Presence.NEVER_HEARD, BASE) >= 1);
        }
    }

    @Test public void severalMembersWithExpiringBudgetsDoNotAllTakeOver() {
        // Group of five, all yielding to a designated advancer none has heard from: every
        // non-designated member has the strongest reason to take over.
        final List<String> group = Arrays.asList(
                "+15710000001", "+15710000002", "+15710000003", "+15710000004", "+15710000005");
        final String absent = "+15710000001";
        // The ledger is non-empty, so NEVER_HEARD is claimable, but holds only the top of the
        // roster, so nobody ahead of each member under test has been heard from. That isolates the
        // stagger.
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
        // Rank 1 acts first; its advance moves the group, and every remaining yielder's next look
        // stands down through eraYieldSatisfied before reaching its own budget.
        final List<String> group = Arrays.asList(YIELDER, DESIGNATED, THIRD);
        // never heard from DESIGNATED
        final Map<String, Long> ledger = heard(YIELDER, 1L, THIRD, 2L);

        final Decision rank1 = MlsAdvancerElection.decide(YIELDER, group, ledger, 9L, BASE);
        final Decision rank2 = MlsAdvancerElection.decide(THIRD, group, ledger, 9L, BASE);
        assertEquals(1, rank1.rank);
        assertEquals(2, rank2.rank);
        assertTrue("the next-lowest E.164 must reach its takeover first",
                rank1.looks < rank2.looks);

        // Rank 1 takes over at its budget...
        assertTrue(MlsRecoveryPolicy.eraYieldObservationsExhausted(rank1.looks, rank1.looks));
        // ...and rank 2 has not reached its own at that point...
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(rank1.looks, rank2.looks));
        // ...and rank 1's advance moves the group: the movement check runs before the bound in
        // eraYieldExhausted, so even a rank 2 at its budget reports the yield as having worked.
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(
                new MlsAppMessage.Moment(1, 1L), new MlsAppMessage.Moment(2, 0L)));
    }

    @Test public void aMemberBehindALiveMemberWaitsLongerThanTheLiveMemberItself() {
        // Rank 2 must not overtake a live rank 1, however absent the designated advancer looks.
        final List<String> group = Arrays.asList(YIELDER, DESIGNATED, THIRD);
        // THIRD (rank 2) has heard from YIELDER (rank 1) during the yield, never from DESIGNATED
        // (rank 0).
        final Decision rank2 =
                MlsAdvancerElection.decide(THIRD, group, heard(YIELDER, 12L), 9L, BASE);
        assertEquals(Presence.ACTIVE, rank2.ahead);
        assertEquals("2*BASE for the live member, plus one BASE-length turn for the member ahead",
                3 * BASE, rank2.looks);
        // YIELDER's budget against the absent DESIGNATED is far shorter, so YIELDER acts long
        // before THIRD.
        final Decision rank1 =
                MlsAdvancerElection.decide(YIELDER, group, heard(THIRD, 4L), 9L, BASE);
        assertEquals(Presence.NEVER_HEARD, rank1.ahead);
        assertTrue(rank1.looks < rank2.looks);
    }

    @Test public void aLoudlyWedgedAdvancerGetsTheMostPatientArmNotTheLeast() {
        // A designated advancer whose only traffic is §7.7.2.2 failure reports is present:
        // if those reports did not reach the ledger it would classify NEVER_HEARD and draw the
        // floor budget, and each era advance forces every member to rebuild by Welcome.
        final List<String> group = Arrays.asList(YIELDER, DESIGNATED, THIRD);
        final Map<String, Long> ledgerWithoutTheReport = heard(THIRD, 4L);
        final Map<String, Long> ledgerWithTheReport = heard(THIRD, 4L, DESIGNATED, 12L);

        assertEquals("without the report the wedged advancer is invisible",
                Presence.NEVER_HEARD,
                MlsAdvancerElection.decide(YIELDER, group, ledgerWithoutTheReport, 9L, BASE).ahead);
        final Decision withReport =
                MlsAdvancerElection.decide(YIELDER, group, ledgerWithTheReport, 9L, BASE);
        assertEquals(Presence.ACTIVE, withReport.ahead);
        assertTrue("a peer that is talking to us must buy MORE patience, not less",
                withReport.looks
                        > MlsAdvancerElection.decide(YIELDER, group, ledgerWithoutTheReport, 9L,
                                BASE).looks);
    }

    @Test public void activeIsStrictlyTheMostPatientArmThereIs() {
        // Whatever the arms are tuned to, a member heard from during this yield is never overtaken
        // sooner than one we have not heard from.
        for (final Presence p : Presence.values()) {
            if (p == Presence.ACTIVE) continue;
            assertTrue(p + " must not be more patient than ACTIVE",
                    MlsAdvancerElection.looksBeforeTakeover(1, Presence.ACTIVE, BASE)
                            > MlsAdvancerElection.looksBeforeTakeover(1, p, BASE));
        }
    }

    @Test public void anyLedgerEntryAtAllRulesOutNeverHeard() {
        // Which inbound path recorded the member does not matter: a failure report and a decrypted
        // message are the same evidence here.
        assertEquals(Presence.QUIET,
                MlsAdvancerElection.classify(DESIGNATED, heard(DESIGNATED, 1L), 9L));
        assertEquals(Presence.ACTIVE,
                MlsAdvancerElection.classify(DESIGNATED, heard(DESIGNATED, 12L), 9L));
    }

    @Test public void noTwoRanksEverShareABudget() {
        // The anti-duel stagger rests on this: two members holding the same budget expire together
        // and both advance. Asserted for every presence arm.
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
        // One look is one GetMlsGroupInfo fetch (selfHeal fetches and evaluates the yield in the
        // same pass), so the budget is additive: exactly baseLooks per member ahead, never
        // multiplied by group size. A loop that cannot converge otherwise fetches into rate
        // limiting.
        for (final Presence p : Presence.values()) {
            for (int rank = 2; rank <= 32; rank++) {
                assertEquals(p + " rank " + rank
                        + ": each additional member ahead must cost exactly "
                        + "one base-length turn, never a multiple of the whole budget",
                        BASE,
                        MlsAdvancerElection.looksBeforeTakeover(rank, p, BASE)
                                - MlsAdvancerElection.looksBeforeTakeover(rank - 1, p, BASE));
            }
        }
        // The 10th-lowest member behind a live member, the most patient arm, spends 30 fetches.
        assertEquals(30, MlsAdvancerElection.looksBeforeTakeover(9, Presence.ACTIVE, BASE));
    }

    @Test public void theShippedConstantsPutTheTakeoverOutOfReachIfALookCostsAnAttempt() {
        // Each look is not also a self-heal attempt: if it were, the enclosing retry budget would
        // escalate before a rank 3 takeover ever fired.
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
        // A yield is not a failed repair, so the host does not charge a self-heal attempt per look.
        // MlsSelfHealPass computes the argument from the charge a look makes, and
        // MlsSelfHealChargeGuardTest derives it independently from the transport. Asserted across
        // ranks and arms so a reintroduced per-look cost fails here.
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
        // Clamping the takeover budget into the enclosing one would force distinct ranks onto the
        // same value, and two members holding the same budget expire together and both advance.
        final int cap = MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT - 1;   // 4
        final int rank2 = Math.min(
                MlsAdvancerElection.looksBeforeTakeover(2, Presence.UNKNOWN, BASE), cap);
        final int rank3 = Math.min(
                MlsAdvancerElection.looksBeforeTakeover(3, Presence.UNKNOWN, BASE), cap);
        assertEquals("clamping collapses two ranks onto one budget — that is the duel", rank2,
                rank3);
        // The real budgets never tie; noTwoRanksEverShareABudget asserts that in general.
        assertTrue(MlsAdvancerElection.looksBeforeTakeover(3, Presence.UNKNOWN, BASE)
                > MlsAdvancerElection.looksBeforeTakeover(2, Presence.UNKNOWN, BASE));
    }

    @Test public void aYieldForeverConfigurationIsNotReachableAndSaysSo() {
        // baseLooks <= 0 means "never take over", so reachability is false rather than vacuously
        // true.
        assertFalse(MlsAdvancerElection.takeoverReachableWithin(1, Presence.NEVER_HEARD, 0, 0));
        assertTrue("the designated advancer does not wait at all",
                MlsAdvancerElection.takeoverReachableWithin(0, Presence.UNKNOWN, BASE, 5));
    }

    @Test public void aOneToOneIsUnchangedBecauseTheEvidenceCannotExistThere() {
        // A 1:1 has one other member, so the ledger is empty exactly when we have never heard from
        // it: NEVER_HEARD is unreachable and the base budget stands.
        final List<String> pair = Arrays.asList(YIELDER, DESIGNATED);
        final Decision d = MlsAdvancerElection.decide(YIELDER, pair, heard(), 0L, BASE);
        assertFalse(d.weAdvanceNow());
        assertEquals(1, d.rank);
        assertEquals(Presence.UNKNOWN, d.ahead);
        assertEquals("a 1:1 keeps exactly the budget it had before this change", BASE, d.looks);
    }

    @Test public void aOneToOnePeerThatHasSpokenBeforeIsStillTreatedAsQuiet() {
        final List<String> pair = Arrays.asList(YIELDER, DESIGNATED);
        final Decision d =
                MlsAdvancerElection.decide(YIELDER, pair, heard(DESIGNATED, 3L), 7L, BASE);
        assertEquals(Presence.QUIET, d.ahead);
        assertEquals(BASE, d.looks);
    }

    @Test public void theDecisionSaysWhyInOneLine() {
        // A takeover must be explainable after the fact from its single log line.
        final Decision d = MlsAdvancerElection.decide(
                YIELDER, Arrays.asList(YIELDER, DESIGNATED, THIRD), heard(THIRD, 4L), 0L, BASE);
        final String s = d.toString();
        assertTrue(s, s.contains(LogMask.number(DESIGNATED)));
        assertTrue(s, s.contains("NEVER_HEARD"));
        assertTrue(s, s.contains("rank 1"));
        assertEquals("advancer(self)", MlsAdvancerElection.decide(
                DESIGNATED, Arrays.asList(YIELDER, DESIGNATED), heard(), 0L, BASE).toString());
    }


    @Test
    public void eraExtOfNamesAbsentAndMalformedRatherThanReadingThemAsZero() {
        assertEquals("ABSENT", MlsAdvancerElection.eraExtOf(null));
        assertEquals("UNEXPECTED-LENGTH(2B)", MlsAdvancerElection.eraExtOf(new byte[2]));
        assertEquals("7 (0x00000007)", MlsAdvancerElection.eraExtOf(new byte[] {0, 0, 0, 7}));
    }
}
