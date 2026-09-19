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

import org.junit.Test;

import java.util.Set;
import java.util.TreeSet;

/**
 * The four §9.7b membership tests, checked EXHAUSTIVELY over all 18 wire slots.
 *
 * <p>Exhaustive rather than sampled, for the same reason {@code MlsHealthStatesTest} is: these are
 * bitmasks in Google Messages, so the interesting content is which states are NOT in each set, and a
 * spot-check cannot tell "absent because the mask excludes it" from "absent because I forgot it".
 * Each test therefore states the whole set and compares it as a set.
 *
 * <p><b>The masks below are transcribed from Google Messages, not from our implementation.</b> That
 * is the only thing that makes them a test rather than a restatement: {@code 0x00019C00} and
 * {@code 0x35096} are the literals as they appear there, and computing the
 * expected set by shifting those constants means an error in the Java would have to be matched by an
 * identical misreading of Google Messages to go unnoticed.
 */
public class MlsHealthPredicatesTest {

    /** Every wire slot, including the two the enum does not define (3 and 5). */
    private static Set<Integer> slotsWhere(final java.util.function.IntPredicate p) {
        final Set<Integer> s = new TreeSet<>();
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) {
            if (p.test(i)) s.add(i);
        }
        return s;
    }

    /** The set a real bitmask denotes: bit i set ⇒ state i is in the set. */
    private static Set<Integer> fromMask(final int mask) {
        final Set<Integer> s = new TreeSet<>();
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) {
            if (((mask >> i) & 1) != 0) s.add(i);
        }
        return s;
    }

    @Test
    public void hasEndMls_isExactlyTheReferenceClientsMask() {
        // 0x00019C00 -> {10,11,12,15,16}
        assertEquals(fromMask(0x00019C00), slotsWhere(MlsHealthPredicates::hasEndMls));
    }

    /**
     * The two exclusions that make {@code has_end_mls} counter-intuitive, called out separately
     * because they are the whole reason item 11.1a is a CONFLICT and not a gap.
     */
    @Test
    public void hasEndMls_excludesRequestedAndOngoing() {
        assertFalse("EndMlsRequested(8) reports has_end_mls FALSE — it trips guard 2, not guard 1",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.ENDMLSREQUESTED));
        assertFalse("OngoingEndMls(9) reports has_end_mls FALSE — the commit is still in flight",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGENDMLS));
    }

    /** And the inclusion that is equally surprising: a group coming BACK still reports true. */
    @Test
    public void hasEndMls_includesBothReviveStates() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGREVIVEMLS));
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
        assertFalse("...but not once it lands in Healthy",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.HEALTHY));
    }

    @Test
    public void isDowngraded_isHasEndMlsUnionedWithTheFourRequestedOngoingStates() {
        // (status-8) & ~9 == 0  ->  {8,9,16,17},  unioned with 0x19C00
        final Set<Integer> expected = fromMask(0x00019C00);
        expected.add(8);
        expected.add(9);
        expected.add(16);
        expected.add(17);
        assertEquals(expected, slotsWhere(MlsHealthPredicates::isDowngraded));
    }

    @Test
    public void isDowngraded_isASupersetOfHasEndMls() {
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) {
            if (MlsHealthPredicates.hasEndMls(i)) {
                assertTrue("has_end_mls(" + i + ") must imply is_downgraded(" + i + ")",
                        MlsHealthPredicates.isDowngraded(i));
            }
        }
    }

    @Test
    public void isPhoenixOngoing_coversRequestedAsWellAsOngoing() {
        final Set<Integer> expected = new TreeSet<>();
        expected.add(MlsHealthStates.ONGOINGPHOENIXMODE);     // 16
        expected.add(MlsHealthStates.PHOENIXMODEREQUESTED);   // 17
        assertEquals(expected, slotsWhere(MlsHealthPredicates::isPhoenixOngoing));
    }

    @Test
    public void isHealing_isExactlyTheReferenceClientsMask() {
        // status < 13 && bit(status) in 0x10D6 -> {1,2,4,6,7,12}
        final Set<Integer> expected = new TreeSet<>();
        for (int i = 0; i < 13; i++) {
            if (((0x10D6 >> i) & 1) != 0) expected.add(i);
        }
        assertEquals(expected, slotsWhere(MlsHealthPredicates::isHealing));
    }

    /**
     * §9.7b calls this out explicitly, so it is asserted explicitly: state 12 is in BOTH sets, and a
     * decoder that "tidies" the overlap away breaks one of the two.
     */
    @Test
    public void state12_isBothDowngradedAndHealing() {
        final int s = MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE;
        assertTrue(MlsHealthPredicates.isDowngraded(s));
        assertTrue(MlsHealthPredicates.isHealing(s));
    }

    @Test
    public void buffersInbound_isExactlyG1sMask() {
        // 0x35096 -> {1,2,4,7,12,14,16,17}
        assertEquals(fromMask(0x35096), slotsWhere(MlsHealthPredicates::buffersInbound));
    }

    /**
     * The trap §10.8 sets, asserted so nobody "simplifies" G1 into {@code !isDowngraded}: the two
     * predicates disagree on FIVE states, in both directions.
     */
    @Test
    public void buffersInbound_isNotTheComplementOfIsDowngraded() {
        final Set<Integer> downgradedAndBuffers = new TreeSet<>();
        final Set<Integer> downgradedAndDoesNot = new TreeSet<>();
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) {
            if (!MlsHealthStates.isState(i) || !MlsHealthPredicates.isDowngraded(i)) continue;
            (MlsHealthPredicates.buffersInbound(i) ? downgradedAndBuffers : downgradedAndDoesNot)
                    .add(i);
        }
        // 12, 16 and 17 are downgraded AND buffer.
        final Set<Integer> expectBuffers = new TreeSet<>();
        expectBuffers.add(12);
        expectBuffers.add(16);
        expectBuffers.add(17);
        assertEquals(expectBuffers, downgradedAndBuffers);
        // 8, 9, 10, 11 and 15 are downgraded and do NOT buffer.
        final Set<Integer> expectNot = new TreeSet<>();
        expectNot.add(8);
        expectNot.add(9);
        expectNot.add(10);
        expectNot.add(11);
        expectNot.add(15);
        assertEquals(expectNot, downgradedAndDoesNot);
    }

    /** {@code SelfHealFailed(6)} is the state that is neither downgraded nor buffering. */
    @Test
    public void buffersInbound_excludesHealthyAndSelfHealFailed() {
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.HEALTHY));
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.SELFHEALFAILED));
    }

    // ---- the revive precondition ladder (§9.7i, invariant 104) --------------------------------

    @Test
    public void canRevive_requiresBothClausesInOrder() {
        // Clause 1 satisfied by the EXTENSION, clause 2 by is_downgraded.
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.DONEENDMLS, true));
        // Clause 1 satisfied by status 15 ALONE, with no extension — that is the point of the
        // disjunct: CannotHealDuringEndMls is reachable when end-mls is unstable, i.e. exactly when
        // the extension may be absent.
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.CANNOTHEALDURINGENDMLS, false));
    }

    @Test
    public void canRevive_refusesAHealthyGroupWithNoExtension() {
        assertFalse(MlsHealthPredicates.canRevive(MlsHealthStates.HEALTHY, false));
    }

    /**
     * Clause 2's second disjunct is statically redundant — 11 is already inside
     * {@code is_downgraded} — but it is in the shipped binary, so it is checkable and checked.
     */
    @Test
    public void canRevive_ongoingReviveMlsSatisfiesClause2Twice() {
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.ONGOINGREVIVEMLS));
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.ONGOINGREVIVEMLS, true));
    }

    /**
     * The diagnostic sits on the FIRST-clause failure, not the second. An earlier draft of the design
     * doc had it on the second; this test is what stops that reading coming back.
     */
    @Test
    public void zinniaBugDiagnostic_isOnTheFirstClauseFailure() {
        // Unhealthy (is_downgraded true) but NO extension and not status 15 -> clause 1 fails, and
        // the re-test of is_downgraded succeeds. That is Google Messages' "This is a bug in the Google MLS engine." case.
        assertTrue(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.ENDMLSREQUESTED, /*extensionPresent=*/ false));
        // A perfectly healthy group failing clause 1 is just "already active" — no diagnostic.
        assertFalse(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.HEALTHY, /*extensionPresent=*/ false));
        // And a group that PASSES clause 1 never reaches the diagnostic at all.
        assertFalse(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.DONEENDMLS, /*extensionPresent=*/ true));
    }

    /**
     * The landing state for a KILLED self-heal must not buffer inbound.
     *
     * <p>This is the invariant behind a device-observed permanent stall: a failed self-heal era
     * advance left the status at {@code OngoingEraAdvancement}, which IS in G1's mask, so every
     * inbound message from the peer parked as {@code GROUP_LOCKED} and never came back. The queue
     * has no TTL, the park is only retried when the group reaches that moment, and an era gap is not
     * self-serviceable — so "buffering" meant "receive nothing, forever".
     *
     * <p>The three assertions are the three candidate landing states, and only one is correct.
     */
    @Test
    public void selfHealFailed_isTheOneLandingStateThatStopsBufferingInbound() {
        // The state we were STUCK in. In the mask — this is the bug.
        assertTrue(MlsHealthPredicates.buffersInbound(MlsHealthStates.ONGOINGERAADVANCEMENT));
        // The tempting "just downgrade it to requested" fix. ALSO in the mask, so it fixes nothing.
        assertTrue(MlsHealthPredicates.buffersInbound(MlsHealthStates.ERAADVANCEMENTREQUESTED));
        // The correct landing state: a self-heal that has already failed does not buffer, so inbound
        // is attempted inline, fails honestly, and reaches the §6.2 FTD report that makes the peer
        // re-Welcome us.
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.SELFHEALFAILED));
        // It is still "healing" for the purposes of every other predicate — killed, not healthy.
        assertTrue(MlsHealthPredicates.isHealing(MlsHealthStates.SELFHEALFAILED));
    }

    /**
     * {@code SelfHealKilled} must be a legal §5.3 edge from the state the era advance sets on entry,
     * or that fix would be silently REFUSED by {@code moveHealth} and the group would
     * stay stuck with only a log line to show for it.
     */
    @Test
    public void selfHealKilled_isALegalEdgeOutOfEveryEraAdvanceState() {
        assertEquals("SelfHealKilled", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGERAADVANCEMENT, MlsHealthStates.SELFHEALFAILED));
        assertEquals("SelfHealKilled", MlsHealthStates.edge(
                MlsHealthStates.ERAADVANCEMENTREQUESTED, MlsHealthStates.SELFHEALFAILED));
        // And it is not a dead end — there is a way back to Healthy once the peer commits.
        assertEquals("HealedAfterRemoteCommit", MlsHealthStates.edge(
                MlsHealthStates.SELFHEALFAILED, MlsHealthStates.HEALTHY));
    }
}
