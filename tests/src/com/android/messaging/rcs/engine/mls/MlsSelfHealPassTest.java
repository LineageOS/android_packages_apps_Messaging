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
 * <b>What one self-heal pass costs, and how many looks it records</b> — Stage 4.
 *
 * <p>Every argument below is read from production: the charge from {@link Kind}, the enclosing
 * allowance from {@code MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT}, the look budget from
 * {@code MlsConfig.DEF_ERA_YIELD_LOOKS}. That is invariant I5 and it is the whole reason this file
 * exists — the test it replaces asserted the same property with a literal {@code 0}, which is a
 * claim about {@code MlsProviderTransport} typed into an engine test.
 *
 * <p>The half this file CANNOT reach is the transport's control flow, and it is not smuggled in
 * here: {@code MlsSelfHealChargeGuardTest} reads {@code MlsProviderTransport.java} and derives the
 * per-look charge from the order of the two invoked methods.
 */
public final class MlsSelfHealPassTest {

    private static final int BASE = MlsConfig.DEF_ERA_YIELD_LOOKS;              // 3
    private static final int RETRY_LIMIT = MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT; // 5

    // ---- what a pass costs -------------------------------------------------------------------

    @Test public void aLiveYieldRelookIsFreeAndAnythingElseIsCharged() {
        assertEquals(Kind.FREE_LOOK, MlsSelfHealPass.classify(/*liveYieldStillHolds=*/ true));
        assertEquals(Kind.CHARGED_ATTEMPT, MlsSelfHealPass.classify(false));
        assertEquals("a look that costs an attempt is the defect, not its fix",
                0, Kind.FREE_LOOK.selfHealAttemptsCharged);
        assertEquals("a pass that ran the ladder and charged nothing is a repair loop with no bound",
                1, Kind.CHARGED_ATTEMPT.selfHealAttemptsCharged);
    }

    /**
     * The argument {@code takeoverReachableWithin} used to be handed as a literal.
     *
     * <p>Asserted as an IDENTITY against the charge table rather than against the number 0: the
     * point is that the answer is DERIVED, so writing {@code assertEquals(0, ...)} here would
     * reintroduce exactly the literal this exists to remove.
     */
    @Test public void theEnclosingArgumentIsComputedFromTheChargeAndNotWrittenDown() {
        final int expected =
                Kind.FREE_LOOK.selfHealAttemptsCharged <= 0 ? 0 : RETRY_LIMIT;
        assertEquals(expected, MlsSelfHealPass.enclosingSelfHealAttempts(RETRY_LIMIT));
        // And it tracks the shipped limit rather than a remembered one: any allowance the config
        // can produce must come back unchanged while a look is charged, and 0 while it is not.
        for (final int limit : new int[] {0, 1, 5, 7, 100}) {
            final int got = MlsSelfHealPass.enclosingSelfHealAttempts(limit);
            assertEquals("limit " + limit,
                    Kind.FREE_LOOK.selfHealAttemptsCharged <= 0 ? 0 : limit, got);
        }
    }

    /**
     * <b>The mutation gate, engine half.</b> This is the assertion that fails if a per-look cost
     * comes back through the charge table — the obvious return being a liveness probe added to the
     * takeover path, which the class javadoc of {@link MlsAdvancerElection} anticipates in as many
     * words.
     *
     * <p>Every rank, every presence arm, with the enclosing argument taken from production.
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

    /**
     * The same property through the PRODUCTION ENTRY POINT rather than the raw predicate, so the
     * call site the transport uses is the one under test.
     */
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

    /**
     * The broken arithmetic, still pinned against the shipped constant — from the original device
     * capture, kept executable so "the fix was needed" does not become folklore.
     *
     * <p>deviceB, rank 3 of group {@code 3256689f}: five looks each charged an attempt, the budget
     * escalated at 5, and the takeover budget of 9 was never reached.
     */
    @Test public void hadALookKeptCostingAnAttemptRankTwoAndUpWouldStillBeDeadCode() {
        assertTrue("rank 1 was always reachable — that is why the defect hid for so long",
                MlsAdvancerElection.takeoverReachableWithin(1, Presence.UNKNOWN, BASE, RETRY_LIMIT));
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

    // ---- which reason answered false -----------------------------------------------------------

    /**
     * <b>The three situations that produce a {@code false} do not share a value.</b>
     *
     * <p>{@link MlsSelfHealPass#takeoverReachable} returns one boolean, and the production caller
     * LOGS on false. A caller that logs the boolean asserts the budget collision for a device an
     * operator has deliberately configured to yield forever, and for a call that was handed no
     * decision at all — an assertion about something nobody measured, which is the defect this work
     * spent itself on. {@link MlsSelfHealPass.Reach} is that split, and this is what stops it
     * collapsing back: each value is reached by its own construction, and no two are equal.
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

        // The collision itself, reached the only way it can be: a rank whose takeover budget does
        // not fit inside the enclosing one. This is the arithmetic measured on a device, and
        // enclosingSelfHealAttempts returns 0 while a look is free — so it is constructed here
        // through the raw predicate's contract rather than by pretending the charge table moved.
        final List<String> deep = Arrays.asList("+15710000001", "+15710000002", "+15710000003");
        final Decision rankTwo = MlsAdvancerElection.decide("+15710000003", deep, heard, 0L, BASE);
        assertTrue("rank " + rankTwo.rank + " must need more looks than the retry limit for this "
                + "case to be the collision at all",
                MlsAdvancerElection.looksBeforeTakeover(rankTwo.rank, rankTwo.ahead, BASE)
                        >= RETRY_LIMIT);
        assertFalse(MlsAdvancerElection.takeoverReachableWithin(
                rankTwo.rank, rankTwo.ahead, BASE, RETRY_LIMIT));

        // Every value distinct, asserted rather than assumed: a later edit that made two of them
        // the same enum constant would leave every assertion above passing.
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
        assertEquals("more than one value warning about the collision is the collapse this enum prevents",
                1, collisions);
        assertEquals("REACHABLE is the only value with nothing to say", all.length - 1, worthALine);
    }

    // ---- the look ledger ----------------------------------------------------------------------

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
     * <b>The second instance of this shape, inside the earlier fix.</b>
     *
     * <p>{@code relookLiveYield} and {@code eraYieldExhausted} both evaluate one pass. The rule that
     * kept them in step was a comment — "Do NOT record the look here … incrementing twice for one
     * pass would take over a look early and, worse, make the two paths disagree". This runs the pass
     * through both paths using only {@link MlsSelfHealPass#look} and asserts the comment.
     *
     * <p>The mutation this catches: make {@code recorded} true on the exhausted arm and the ladder
     * sees {@code held + 1}, computes {@code held + 2}, and takes over one look early.
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
                // The re-look fell through; the full ladder now re-runs the SAME pass.
                final Look ladder = MlsSelfHealPass.look(afterRelook, budget);
                passesThatReachedTheLadder++;
                assertEquals("the two paths of one pass disagree about WHICH look this is (budget "
                        + budget + ", held " + held + ")", relook.number, ladder.number);
                assertEquals("the two paths of one pass disagree about the VERDICT (budget " + budget
                        + ", held " + held + ")", relook.exhausted, ladder.exhausted);
                assertTrue("the only reason to fall through with the counter unmoved is exhaustion",
                        ladder.exhausted);
            }
        }
        assertTrue("no pass ever reached the ladder — this test proved nothing",
                passesThatReachedTheLadder > 0);
    }

    /**
     * The whole yield episode, at the shipped budget, counted: {@code baseLooks} passes and the
     * takeover lands on the last one, not one early and not one late.
     */
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
     * The park bound Stage 6 moved here, at its SHIPPING value.
     *
     * <p>The boundary is stated because the production counter is incremented BEFORE the ask, so
     * the third park is the one that unsticks — an off-by-one here either kills a heal that is
     * simply slow, or never kills one that is dead.
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
