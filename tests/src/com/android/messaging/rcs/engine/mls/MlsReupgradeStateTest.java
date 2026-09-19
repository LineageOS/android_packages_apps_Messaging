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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Re-upgrade bookkeeping — the backoff arithmetic, the reset rule, and the two loops (§9.7l,
 * invariants 105/106/107).
 *
 * <p>The arithmetic tests carry their own expected values as literals rather than recomputing the
 * formula, because "the formula, applied twice" is not an independent check of the formula.
 */
public class MlsReupgradeStateTest {

    private static final long BASE = MlsReupgradeState.DEF_BACKOFF_BASE_S;          // 60
    private static final int CAP = MlsReupgradeState.DEF_BACKOFF_MAX_SHIFT;         // 8
    private static final long WINDOW = MlsReupgradeState.DEF_STABILITY_WINDOW_S;    // 86400

    // ---- the backoff -------------------------------------------------------------------------

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
     * <b>The 32-bit shift.</b> Google Messages computes {@code (long)(int)(1 << shift) * base} and only then
     * widens — <i>not</i> {@code 1L << shift}. At shift 31 the {@code int} is
     * {@link Integer#MIN_VALUE}, so the product is NEGATIVE, and at shift 32 the shift wraps to 0.
     *
     * <p>We reproduce that rather than "fixing" it. The cap is server-delivered, so we do not control
     * whether it is ever pushed above 30, and a backoff that silently disagrees with Google Messages' is
     * worse than one that is faithfully odd. This test exists so the oddity is documented as
     * deliberate and a later "cleanup" fails here rather than shipping a divergence.
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
        // And the 64-bit reading would have given a different answer at 31 — which is the point.
        assertNotEquals(1L << 31, MlsReupgradeState.backoffSeconds(31, 31, 1L));
    }

    @Test
    public void negativeAttemptsAndCapsAreClampedRatherThanShiftingByANegative() {
        assertEquals(BASE, MlsReupgradeState.backoffSeconds(-5, CAP, BASE));
        assertEquals(BASE, MlsReupgradeState.backoffSeconds(3, -1, BASE));
    }

    // ---- loop 1: the drive, the skip, and the anchor ------------------------------------------

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
        // Attempting does NOT move the anchor — only the exponent.
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

    /**
     * Invariant 107 — INCREMENT BEFORE ATTEMPT. Modelled as a method that returns the incremented
     * state, so a caller that attempts without incrementing has to go out of its way to do so.
     */
    @Test
    public void attemptedIncrementsAndIsUnbounded() {
        MlsReupgradeState s = MlsReupgradeState.NONE;
        for (int i = 0; i < 100; i++) s = s.attempted();
        assertEquals("there is NO absolute attempt cap — Google Messages backs off forever, never gives up",
                100, s.attemptCount);
    }

    // ---- the reset rule (invariant 106: RESET-THEN-STAMP) -------------------------------------

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
     * Invariant 106's failure mode, stated as a test. The window is consulted against the PREVIOUS
     * timestamp; if the new one were written first the window would always read as zero, the counter
     * would reset on every downgrade, and the backoff would never grow past one base interval.
     */
    @Test
    public void theWindowIsConsultedAgainstThePreviousTimestampNotTheNewOne() {
        final long first = 1_000_000L;
        // Two downgrades a second apart. Under a stamp-then-reset implementation the elapsed time
        // would be 0, which is NOT >= the window, so it would not reset either — the observable
        // difference appears on the far side.
        MlsReupgradeState s = new MlsReupgradeState(first, 7, false)
                .markUnexpectedDowngrade(first + 1_000L, WINDOW);
        assertEquals(7, s.attemptCount);
        // Now one a full window later, measured from the SECOND downgrade.
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

    // ---- loop 2: fast, counterless -----------------------------------------------------------

    @Test
    public void loop2NeedsBothTheEagerFlagAndACoarseHealthyEngine() {
        final MlsReupgradeState eager = MlsReupgradeState.NONE.withEagerlyDowngraded(true);
        assertTrue(eager.eligibleForFastReupgrade(/*engineReportsCoarseHealthy=*/ true));
        assertFalse(eager.eligibleForFastReupgrade(false));
        assertFalse(MlsReupgradeState.NONE.eligibleForFastReupgrade(true));
    }

    /**
     * Loop 2 is independent of loop 1's bookkeeping — no timestamp, no counter, no backoff. Both
     * loops can fire for the same conversation, which is Google Messages' behaviour (§22.1-60) and not a
     * defect to design out.
     */
    @Test
    public void loop2IgnoresTheBackoffThatLoop1IsInsideOf() {
        final MlsReupgradeState s =
                new MlsReupgradeState(1_000_000L, 3, /*eagerlyDowngraded=*/ true);
        assertTrue("loop 1 is holding this one back", s.withinBackoff(1_000_001L, CAP, BASE));
        assertTrue("loop 2 does not care", s.eligibleForFastReupgrade(true));
    }

    // ---- clearing -----------------------------------------------------------------------------

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
