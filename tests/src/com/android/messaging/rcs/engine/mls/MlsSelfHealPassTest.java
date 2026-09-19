/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAdvancerElection.Decision;
import com.android.messaging.rcs.engine.mls.MlsAdvancerElection.Presence;
import com.android.messaging.rcs.engine.mls.MlsSelfHealPass.Kind;
import com.android.messaging.rcs.engine.mls.MlsSelfHealPass.Look;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one self-heal pass costs and how many looks it records. Every argument is read from
 * production ({@link Kind}, {@code MlsConfig} defaults). The transport's control flow is
 * {@code MlsSelfHealChargeGuardTest}'s half. See docs/mls/health-and-recovery.md.
 */
public final class MlsSelfHealPassTest {

    private static final int BASE = MlsConfig.DEF_ERA_YIELD_LOOKS;              // 3
    private static final int RETRY_LIMIT = MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT; // 5

    @Test public void aLiveYieldRelookIsFreeAndAnythingElseIsCharged() {
        assertEquals(Kind.FREE_LOOK, MlsSelfHealPass.classify(/*liveYieldStillHolds=*/ true));
        assertEquals(Kind.CHARGED_ATTEMPT, MlsSelfHealPass.classify(false));
        assertEquals("a look that costs an attempt is the defect, not its fix",
                0, Kind.FREE_LOOK.selfHealAttemptsCharged);
        assertEquals(
                "a pass that ran the ladder and charged nothing is a repair loop with no bound",
                1, Kind.CHARGED_ATTEMPT.selfHealAttemptsCharged);
    }

    /** Asserted as an identity against the charge table, not against 0: it must be derived. */
    @Test public void theEnclosingArgumentIsComputedFromTheChargeAndNotWrittenDown() {
        final int expected =
                Kind.FREE_LOOK.selfHealAttemptsCharged <= 0 ? 0 : RETRY_LIMIT;
        assertEquals(expected, MlsSelfHealPass.enclosingSelfHealAttempts(RETRY_LIMIT));
        // Tracks the configured limit: returned unchanged while a look is charged, 0 while it is
        // not.
        for (final int limit : new int[] {0, 1, 5, 7, 100}) {
            final int got = MlsSelfHealPass.enclosingSelfHealAttempts(limit);
            assertEquals("limit " + limit,
                    Kind.FREE_LOOK.selfHealAttemptsCharged <= 0 ? 0 : limit, got);
        }
    }

    /**
     * Every rank and presence arm reaches its takeover inside the shipped self-heal budget; fails
     * if a per-look cost returns through the charge table.
     */
    @Test public void everyRankCanReachItsTakeoverInsideTheShippedSelfHealBudget() {
        final int enclosing = MlsSelfHealPass.enclosingSelfHealAttempts(RETRY_LIMIT);
        for (final Presence p : Presence.values()) {
            for (int rank = 1; rank <= 16; rank++) {
                assertTrue("rank " + rank + " ahead=" + p + " cannot reach its takeover inside "
                        + enclosing + " self-heal attempt(s) — a look has started costing an "
                        + "attempt again and the defect is back",
                        MlsAdvancerElection.takeoverReachableWithin(rank, p, BASE, enclosing));
            }
        }
    }

    /** The same property through the entry point the transport uses. */
    @Test public void theProductionEntryPointAgreesWithThePredicateForEveryRank() {
        final List<String> roster = Arrays.asList(
                "+15710000001", "+15710000002", "+15710000003", "+15710000004");
        final Map<String, Long> heard = new HashMap<>();
        for (final String self : roster) {
            final Decision d = MlsAdvancerElection.decide(self, roster, heard, 0L, BASE);
            assertEquals(MlsAdvancerElection.takeoverReachableWithin(d.rank, d.ahead, BASE,
                            MlsSelfHealPass.enclosingSelfHealAttempts(RETRY_LIMIT)),
                    MlsSelfHealPass.takeoverReachable(d, BASE, RETRY_LIMIT));
            assertTrue("no member of an ordinary roster may be left yielding forever: " + d,
                    MlsSelfHealPass.takeoverReachable(d, BASE, RETRY_LIMIT));
        }
    }

    /** With a charged look, rank 2 and up would exhaust the self-heal budget before taking over. */
    @Test public void hadALookKeptCostingAnAttemptRankTwoAndUpWouldStillBeDeadCode() {
        assertTrue("rank 1 was always reachable — that is why the defect hid for so long",
                MlsAdvancerElection.takeoverReachableWithin(1, Presence.UNKNOWN, BASE,
                        RETRY_LIMIT));
        for (int rank = 2; rank <= 16; rank++) {
            assertFalse("rank " + rank, MlsAdvancerElection.takeoverReachableWithin(
                    rank, Presence.UNKNOWN, BASE, RETRY_LIMIT));
        }
    }

    @Test public void aYieldForeverConfigurationIsNotSilentlyReadAsReachable() {
        final Decision d = MlsAdvancerElection.decide("+15710000002",
                Arrays.asList("+15710000001", "+15710000002"), new HashMap<String, Long>(), 0L,
                /*baseLooks=*/ 0);
        assertFalse("baseLooks<=0 means 'never take over'; reporting that as reachable would let a "
                + "caller read a deliberate never-take-over as a healthy configuration",
                MlsSelfHealPass.takeoverReachable(d, 0, RETRY_LIMIT));
    }

    @Test public void aNullDecisionIsNotReachableRatherThanVacuouslyTrue() {
        assertFalse(MlsSelfHealPass.takeoverReachable(null, BASE, RETRY_LIMIT));
    }

    /**
     * The three reasons {@link MlsSelfHealPass#takeoverReachable} is false are distinct
     * {@link MlsSelfHealPass.Reach} values, so a caller logging the result does not report a budget
     * collision for a device configured to yield forever or for a call with no decision.
     */
    @Test public void everyReasonForAnUnreachableTakeoverIsItsOwnValue() {
        final List<String> roster = Arrays.asList("+15710000001", "+15710000002");
        final Map<String, Long> heard = new HashMap<>();
        final Decision ordinary =
                MlsAdvancerElection.decide("+15710000002", roster, heard, 0L, BASE);
        final Decision yieldForever =
                MlsAdvancerElection.decide("+15710000002", roster, heard, 0L, /*baseLooks=*/ 0);

        assertEquals(MlsSelfHealPass.Reach.REACHABLE,
                MlsSelfHealPass.reach(ordinary, BASE, RETRY_LIMIT));
        assertEquals(MlsSelfHealPass.Reach.NO_DECISION,
                MlsSelfHealPass.reach(null, BASE, RETRY_LIMIT));
        assertEquals("an operator configuring this device never to take over must not be reported "
                + "as the budget collision",
                MlsSelfHealPass.Reach.YIELD_FOREVER_BY_CONFIGURATION,
                MlsSelfHealPass.reach(yieldForever, /*baseLooks=*/ 0, RETRY_LIMIT));

        // The collision, constructed through the raw predicate's contract: a rank whose takeover
        // budget does not fit inside the enclosing one.
        final List<String> deep = Arrays.asList("+15710000001", "+15710000002", "+15710000003");
        final Decision rankTwo = MlsAdvancerElection.decide("+15710000003", deep, heard, 0L, BASE);
        assertTrue("rank " + rankTwo.rank + " must need more looks than the retry limit for this "
                + "case to be the collision at all",
                MlsAdvancerElection.looksBeforeTakeover(rankTwo.rank, rankTwo.ahead, BASE)
                        >= RETRY_LIMIT);
        assertFalse(MlsAdvancerElection.takeoverReachableWithin(
                rankTwo.rank, rankTwo.ahead, BASE, RETRY_LIMIT));

        // Every value distinct: merging two constants would leave the assertions above passing.
        final MlsSelfHealPass.Reach[] all = MlsSelfHealPass.Reach.values();
        assertEquals("MlsSelfHealPass.Reach has gained or lost a constant without this test being "
                + "re-answered. NAMED rather than left as 'a value': a bare 'expected:<4> but "
                + "was:<5>' collides with every other 4-valued ratchet in this suite, and a reader "
                + "meeting it cannot tell whose change it is.", 4, all.length);
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertFalse(all[i] + " and " + all[j] + " must stay distinct — the whole point of "
                        + "this enum is that a reader with partial knowledge does not share a value "
                        + "with one that has none", all[i] == all[j]);
            }
        }
        assertTrue("exactly one value deserves a warning, and it is the collision",
                MlsSelfHealPass.Reach.ENCLOSING_BUDGET_ESCALATES_FIRST.isACollision);
        int collisions = 0;
        int worthALine = 0;
        for (final MlsSelfHealPass.Reach r : all) {
            if (r.isACollision) collisions++;
            if (r.why != null) worthALine++;
        }
        assertEquals(
                "more than one value warning about the collision is the collapse this enum prevents",
                1, collisions);
        assertEquals("REACHABLE is the only value with nothing to say", all.length - 1, worthALine);
    }

    @Test public void aLookIsOneBasedAndExhaustsExactlyAtTheBudget() {
        for (int budget = 1; budget <= 9; budget++) {
            for (int held = 0; held < budget + 3; held++) {
                final Look l = MlsSelfHealPass.look(held, budget);
                assertEquals("budget " + budget + " held " + held, held + 1, l.number);
                assertEquals(l.number >= budget, l.exhausted);
                assertEquals("recorded is the complement of exhausted on BOTH paths — that is the "
                        + "reconciliation, not a coincidence", !l.exhausted, l.recorded);
            }
        }
    }

    @Test public void aYieldForeverBudgetIsNeverExhaustedByAnyLook() {
        for (final int budget : new int[] {0, -1, -100}) {
            for (int held = 0; held < 40; held++) {
                final Look l = MlsSelfHealPass.look(held, budget);
                assertFalse("budget " + budget + " held " + held, l.exhausted);
                assertTrue(l.recorded);
            }
        }
    }

    /**
     * One pass never records a look twice: both paths go through {@link MlsSelfHealPass#look}, and
     * a {@code recorded} exhausted arm would make the ladder take over a look early.
     */
    @Test public void onePassNeverAdvancesTheCounterTwiceAndBothPathsAgree() {
        int passesThatReachedTheLadder = 0;
        for (int budget = 1; budget <= 12; budget++) {
            for (int held = 0; held <= 20; held++) {
                final Look relook = MlsSelfHealPass.look(held, budget);
                final int afterRelook = relook.recorded ? relook.number : held;
                assertTrue("one pass moved the counter by " + (afterRelook - held),
                        afterRelook - held <= 1);
                if (relook.recorded) continue;      // the pass ended at the re-look
                // The re-look fell through; the full ladder re-runs the same pass.
                final Look ladder = MlsSelfHealPass.look(afterRelook, budget);
                passesThatReachedTheLadder++;
                assertEquals("the two paths of one pass disagree about WHICH look this is (budget "
                        + budget + ", held " + held + ")", relook.number, ladder.number);
                assertEquals("the two paths of one pass disagree about the VERDICT (budget "
                        + budget + ", held " + held + ")", relook.exhausted, ladder.exhausted);
                assertTrue("the only reason to fall through with the counter unmoved is exhaustion",
                        ladder.exhausted);
            }
        }
        assertTrue("no pass ever reached the ladder — this test proved nothing",
                passesThatReachedTheLadder > 0);
    }

    /** At the shipped budget the takeover lands on the last of {@code baseLooks} passes. */
    @Test public void aWholeYieldEpisodeAtTheShippedBudgetTakesOverOnTheLastLook() {
        int observations = 0;
        int passes = 0;
        Look last = null;
        while (passes < 100) {
            passes++;
            last = MlsSelfHealPass.look(observations, BASE);
            if (last.exhausted) break;
            observations = last.number;
        }
        assertTrue(last != null && last.exhausted);
        assertEquals("the episode ran " + passes + " passes at a budget of " + BASE, BASE, passes);
        assertEquals(BASE, last.number);
    }

    /**
     * The park bound. The counter is incremented before the ask, so the third park unsticks; off by
     * one kills a slow heal or never kills a dead one.
     */
    @Test
    public void parksExhausted_atThreeAtTheSameMoment() {
        assertEquals(3, MlsSelfHealPass.PARKS_BEFORE_UNSTICKING_A_HEAL);
        assertFalse(MlsSelfHealPass.parksExhausted(1));
        assertFalse(MlsSelfHealPass.parksExhausted(2));
        assertTrue(MlsSelfHealPass.parksExhausted(3));
        assertTrue(MlsSelfHealPass.parksExhausted(4));
    }

}
