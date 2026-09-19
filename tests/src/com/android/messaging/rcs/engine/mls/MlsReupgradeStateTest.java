/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Re-upgrade bookkeeping: the backoff arithmetic, the reset rule and the two loops. Expected values
 * are literals, since recomputing the formula is not an independent check of it. See
 * docs/mls/downgrade.md.
 */
public class MlsReupgradeStateTest {

    private static final long BASE = MlsReupgradeState.DEF_BACKOFF_BASE_S;          // 60
    private static final int CAP = MlsReupgradeState.DEF_BACKOFF_MAX_SHIFT;         // 8
    private static final long WINDOW = MlsReupgradeState.DEF_STABILITY_WINDOW_S;    // 86400

    @Test
    public void backoffDoublesUntilItReachesTheCap() {
        assertEquals(60L, MlsReupgradeState.backoffSeconds(0, CAP, BASE));    // 2^0 * 60
        assertEquals(120L, MlsReupgradeState.backoffSeconds(1, CAP, BASE));
        assertEquals(240L, MlsReupgradeState.backoffSeconds(2, CAP, BASE));
        assertEquals(3840L, MlsReupgradeState.backoffSeconds(6, CAP, BASE));
        assertEquals(15360L, MlsReupgradeState.backoffSeconds(8, CAP, BASE));  // 2^8 * 60
    }

    @Test
    public void backoffPlateausAtTheCapAndNeverGrowsAgain() {
        final long atCap = MlsReupgradeState.backoffSeconds(CAP, CAP, BASE);
        assertEquals(atCap, MlsReupgradeState.backoffSeconds(CAP + 1, CAP, BASE));
        assertEquals(atCap, MlsReupgradeState.backoffSeconds(1_000, CAP, BASE));
        assertEquals(atCap, MlsReupgradeState.backoffSeconds(Integer.MAX_VALUE, CAP, BASE));
    }

    /**
     * The 32-bit shift: {@code (long)(int)(1 << shift) * base}, not {@code 1L << shift}. At shift
     * 31 the product is negative and at 32 the shift wraps to 0. Reproduced deliberately to match
     * other clients, since the cap is server-delivered.
     */
    @Test
    public void theShiftIsThirtyTwoBitAndOverflowsExactlyAsTheReferenceClientDoes() {
        // shift 30: still positive.
        assertEquals(1L << 30, MlsReupgradeState.backoffSeconds(30, 30, 1L));
        // shift 31: (int)(1 << 31) == Integer.MIN_VALUE -> negative product.
        assertEquals((long) Integer.MIN_VALUE, MlsReupgradeState.backoffSeconds(31, 31, 1L));
        assertTrue(MlsReupgradeState.backoffSeconds(31, 31, 1L) < 0);
        // shift 32: the JLS masks the shift distance to 5 bits, so 1 << 32 == 1.
        assertEquals(1L, MlsReupgradeState.backoffSeconds(32, 32, 1L));
        // The 64-bit reading would give a different answer at 31.
        assertNotEquals(1L << 31, MlsReupgradeState.backoffSeconds(31, 31, 1L));
    }

    @Test
    public void negativeAttemptsAndCapsAreClampedRatherThanShiftingByANegative() {
        assertEquals(BASE, MlsReupgradeState.backoffSeconds(-5, CAP, BASE));
        assertEquals(BASE, MlsReupgradeState.backoffSeconds(3, -1, BASE));
    }

    @Test
    public void aConversationNeverDowngradedIsNotInBackoff() {
        final MlsReupgradeState s = MlsReupgradeState.NONE;
        assertFalse(s.hasUnexpectedDowngrade());
        assertFalse("loop 1 has no opinion about a conversation it has never seen",
                s.withinBackoff(1_000_000L, CAP, BASE));
    }

    @Test
    public void backoffIsMeasuredFromTheLastDowngradeNotTheLastAttempt() {
        final long downgradedAt = 1_000_000L;
        MlsReupgradeState s = new MlsReupgradeState(downgradedAt, 0, false);
        assertEquals(downgradedAt + 60_000L, s.nextAttemptAtMs(CAP, BASE));
        // Attempting moves only the exponent, not the anchor.
        s = s.attempted();
        assertEquals(1, s.attemptCount);
        assertEquals("the anchor is the downgrade, not the attempt",
                downgradedAt + 120_000L, s.nextAttemptAtMs(CAP, BASE));
        assertEquals(downgradedAt, s.lastUnexpectedDowngradeMs);
    }

    @Test
    public void withinBackoffIsTrueBeforeTheDeadlineAndFalseAtIt() {
        final MlsReupgradeState s = new MlsReupgradeState(1_000_000L, 0, false);
        final long next = s.nextAttemptAtMs(CAP, BASE);
        assertTrue(s.withinBackoff(next - 1, CAP, BASE));
        assertFalse("at the deadline the attempt is allowed", s.withinBackoff(next, CAP, BASE));
        assertFalse(s.withinBackoff(next + 1, CAP, BASE));
    }

    /** Increment before attempt: the method returns the incremented state. */
    @Test
    public void attemptedIncrementsAndIsUnbounded() {
        MlsReupgradeState s = MlsReupgradeState.NONE;
        for (int i = 0; i < 100; i++) s = s.attempted();
        assertEquals("there is NO absolute attempt cap — it backs off forever, never gives up",
                100, s.attemptCount);
    }

    @Test
    public void aDowngradeInsideTheStabilityWindowKeepsCountingUp() {
        final long first = 1_000_000L;
        MlsReupgradeState s = new MlsReupgradeState(first, 4, false);
        final long soonAfter = first + (WINDOW - 1) * 1000L;
        s = s.markUnexpectedDowngrade(soonAfter, WINDOW);
        assertEquals("still inside the window — this is the SAME continuing problem", 4,
                s.attemptCount);
        assertEquals(soonAfter, s.lastUnexpectedDowngradeMs);
    }

    @Test
    public void aDowngradeAfterTheStabilityWindowResetsTheCounter() {
        final long first = 1_000_000L;
        MlsReupgradeState s = new MlsReupgradeState(first, 4, false);
        final long muchLater = first + WINDOW * 1000L;
        s = s.markUnexpectedDowngrade(muchLater, WINDOW);
        assertEquals("stable for a while, so treat this as a fresh problem", 0, s.attemptCount);
        assertEquals(muchLater, s.lastUnexpectedDowngradeMs);
    }

    /**
     * Reset then stamp: the window is consulted against the previous timestamp. Stamping first
     * would make the window read zero and the backoff never grow.
     */
    @Test
    public void theWindowIsConsultedAgainstThePreviousTimestampNotTheNewOne() {
        final long first = 1_000_000L;
        // Two downgrades a second apart: both orders leave the counter; the difference shows on the
        // far side.
        MlsReupgradeState s = new MlsReupgradeState(first, 7, false)
                .markUnexpectedDowngrade(first + 1_000L, WINDOW);
        assertEquals(7, s.attemptCount);
        // One a full window after the second downgrade.
        s = s.markUnexpectedDowngrade(first + 1_000L + WINDOW * 1000L, WINDOW);
        assertEquals(0, s.attemptCount);
    }

    @Test
    public void theFirstEverDowngradeDoesNotResetAnything() {
        final MlsReupgradeState s = MlsReupgradeState.NONE.markUnexpectedDowngrade(5_000L, WINDOW);
        assertEquals(5_000L, s.lastUnexpectedDowngradeMs);
        assertEquals(0, s.attemptCount);
        assertTrue(s.hasUnexpectedDowngrade());
    }

    @Test
    public void loop2NeedsBothTheEagerFlagAndACoarseHealthyEngine() {
        final MlsReupgradeState eager = MlsReupgradeState.NONE.withEagerlyDowngraded(true);
        assertTrue(eager.eligibleForFastReupgrade(/*engineReportsCoarseHealthy=*/ true));
        assertFalse(eager.eligibleForFastReupgrade(false));
        assertFalse(MlsReupgradeState.NONE.eligibleForFastReupgrade(true));
    }

    /** Loop 2 is independent of loop 1's bookkeeping; both can fire for the same conversation. */
    @Test
    public void loop2IgnoresTheBackoffThatLoop1IsInsideOf() {
        final MlsReupgradeState s =
                new MlsReupgradeState(1_000_000L, 3, /*eagerlyDowngraded=*/ true);
        assertTrue("loop 1 is holding this one back", s.withinBackoff(1_000_001L, CAP, BASE));
        assertTrue("loop 2 does not care", s.eligibleForFastReupgrade(true));
    }

    @Test
    public void clearedZeroesAllThreeColumnsTogether() {
        final MlsReupgradeState s = new MlsReupgradeState(9_999L, 6, true).cleared();
        assertEquals(MlsReupgradeState.NEVER, s.lastUnexpectedDowngradeMs);
        assertEquals(0, s.attemptCount);
        assertFalse(s.eagerlyDowngraded);
        assertEquals(MlsReupgradeState.NONE, s);
    }

    @Test
    public void valueSemantics() {
        assertEquals(new MlsReupgradeState(5L, 2, true), new MlsReupgradeState(5L, 2, true));
        assertEquals(new MlsReupgradeState(5L, 2, true).hashCode(),
                new MlsReupgradeState(5L, 2, true).hashCode());
        assertNotEquals(new MlsReupgradeState(5L, 2, true), new MlsReupgradeState(5L, 2, false));
        assertNotEquals(new MlsReupgradeState(5L, 2, true), new MlsReupgradeState(6L, 2, true));
    }

    @Test
    public void mutatorsDoNotMutate() {
        final MlsReupgradeState s = new MlsReupgradeState(5L, 2, false);
        s.attempted();
        s.withEagerlyDowngraded(true);
        s.markUnexpectedDowngrade(99_999L, WINDOW);
        assertEquals("persisted state is written whole; an in-place bump is a partial update by "
                + "another name", new MlsReupgradeState(5L, 2, false), s);
    }
}
