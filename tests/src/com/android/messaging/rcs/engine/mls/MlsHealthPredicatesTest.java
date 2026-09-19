/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@link MlsHealthPredicates}, checked exhaustively over all 18 wire slots and compared as sets.
 * The expected sets are derived from the bitmask literals interoperating clients use, not from our
 * implementation. See docs/mls/health-and-recovery.md.
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

    /** The set a bitmask denotes: bit i set means state i is in the set. */
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

    /** {@code hasEndMls} excludes a requested (8) and an in-flight (9) downgrade. */
    @Test
    public void hasEndMls_excludesRequestedAndOngoing() {
        assertFalse("EndMlsRequested(8) reports has_end_mls FALSE — it trips guard 2, not guard 1",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.ENDMLSREQUESTED));
        assertFalse("OngoingEndMls(9) reports has_end_mls FALSE — the commit is still in flight",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGENDMLS));
    }

    /** A group being revived still reports true. */
    @Test
    public void hasEndMls_includesBothReviveStates() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGREVIVEMLS));
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
        assertFalse("...but not once it lands in Healthy",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.HEALTHY));
    }

    @Test
    public void isDowngraded_isHasEndMlsUnionedWithTheFourRequestedOngoingStates() {
        // (status - 8) & ~9 == 0 gives {8, 9, 16, 17}, unioned with 0x19C00
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
        expected.add(MlsHealthStates.ONGOINGPHOENIXMODE);
        expected.add(MlsHealthStates.PHOENIXMODEREQUESTED);
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

    /** State 12 is in both sets; tidying the overlap away breaks one of them. */
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
     * RCC.16 §10.8 guard G1 is not {@code !isDowngraded}: the two predicates disagree on five
     * states, in both directions.
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
        // 12, 16 and 17 are downgraded and buffer.
        final Set<Integer> expectBuffers = new TreeSet<>();
        expectBuffers.add(12);
        expectBuffers.add(16);
        expectBuffers.add(17);
        assertEquals(expectBuffers, downgradedAndBuffers);
        // 8, 9, 10, 11 and 15 are downgraded and do not buffer.
        final Set<Integer> expectNot = new TreeSet<>();
        expectNot.add(8);
        expectNot.add(9);
        expectNot.add(10);
        expectNot.add(11);
        expectNot.add(15);
        assertEquals(expectNot, downgradedAndDoesNot);
    }

    /** {@code SelfHealFailed(6)} is neither downgraded nor buffering. */
    @Test
    public void buffersInbound_excludesHealthyAndSelfHealFailed() {
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.HEALTHY));
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.SELFHEALFAILED));
    }

    // The revive precondition. See docs/mls/downgrade.md.

    @Test
    public void canRevive_requiresBothClausesInOrder() {
        // Clause 1 satisfied by the extension, clause 2 by isDowngraded.
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.DONEENDMLS, true));
        // Clause 1 satisfied by status 15 alone: CannotHealDuringEndMls is reachable exactly when
        // the extension may be absent.
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.CANNOTHEALDURINGENDMLS, false));
    }

    @Test
    public void canRevive_refusesAHealthyGroupWithNoExtension() {
        assertFalse(MlsHealthPredicates.canRevive(MlsHealthStates.HEALTHY, false));
    }

    /** Clause 2's second disjunct is redundant (11 is already downgraded) but is checked. */
    @Test
    public void canRevive_ongoingReviveMlsSatisfiesClause2Twice() {
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.ONGOINGREVIVEMLS));
        assertTrue(MlsHealthPredicates.canRevive(MlsHealthStates.ONGOINGREVIVEMLS, true));
    }

    /** The engine-defect diagnostic sits on the first-clause failure, not the second. */
    @Test
    public void zinniaBugDiagnostic_isOnTheFirstClauseFailure() {
        // Downgraded but no extension and not status 15: clause 1 fails and the re-test of
        // isDowngraded succeeds, which indicates an engine defect.
        assertTrue(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.ENDMLSREQUESTED, /*extensionPresent=*/ false));
        // A healthy group failing clause 1 is "already active", no diagnostic.
        assertFalse(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.HEALTHY, /*extensionPresent=*/ false));
        // A group that passes clause 1 never reaches the diagnostic.
        assertFalse(MlsHealthPredicates.reviveRefusalIsZinniaBug(
                MlsHealthStates.DONEENDMLS, /*extensionPresent=*/ true));
    }

    /**
     * A killed self-heal must land in a state that does not buffer inbound. Left in {@code
     * OngoingEraAdvancement}, which G1 buffers, every inbound message parks and never returns,
     * since an era gap cannot be serviced by the parked queue.
     */
    @Test
    public void selfHealFailed_isTheOneLandingStateThatStopsBufferingInbound() {
        // The state a failed advance leaves behind, and EraAdvancementRequested, both buffer.
        assertTrue(MlsHealthPredicates.buffersInbound(MlsHealthStates.ONGOINGERAADVANCEMENT));
        assertTrue(MlsHealthPredicates.buffersInbound(MlsHealthStates.ERAADVANCEMENTREQUESTED));
        // SelfHealFailed does not buffer, so inbound fails inline and reaches the FTD report.
        assertFalse(MlsHealthPredicates.buffersInbound(MlsHealthStates.SELFHEALFAILED));
        assertTrue(MlsHealthPredicates.isHealing(MlsHealthStates.SELFHEALFAILED));
    }

    /**
     * {@code SelfHealKilled} is a legal edge from the era-advance states, or {@code moveHealth}
     * would refuse the move and the group would stay stuck.
     */
    @Test
    public void selfHealKilled_isALegalEdgeOutOfEveryEraAdvanceState() {
        assertEquals("SelfHealKilled", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGERAADVANCEMENT, MlsHealthStates.SELFHEALFAILED));
        assertEquals("SelfHealKilled", MlsHealthStates.edge(
                MlsHealthStates.ERAADVANCEMENTREQUESTED, MlsHealthStates.SELFHEALFAILED));
        assertEquals("HealedAfterRemoteCommit", MlsHealthStates.edge(
                MlsHealthStates.SELFHEALFAILED, MlsHealthStates.HEALTHY));
    }
}
