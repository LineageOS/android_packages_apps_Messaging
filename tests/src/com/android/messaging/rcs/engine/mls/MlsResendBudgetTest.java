/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The resend cap expires: a count-only cap would leave a conversation degraded until its rows were
 * deleted by hand.
 */
public class MlsResendBudgetTest {

    @Test
    public void theBudgetIsSpentAtTheLimitAndNotBefore() {
        assertFalse("a first resend must be allowed", MlsResendBudget.spent(0));
        assertFalse(MlsResendBudget.spent(MlsResendBudget.MAX_PER_WINDOW - 1));
        assertTrue(MlsResendBudget.spent(MlsResendBudget.MAX_PER_WINDOW));
        assertTrue("and stays spent past the limit",
                MlsResendBudget.spent(MlsResendBudget.MAX_PER_WINDOW + 97));
    }

    /** Rows older than the window do not count, so a burst ages out by itself. */
    @Test
    public void theWindowStartMovesForwardWithTime() {
        final long t0 = 1_800_000_000_000L;
        assertEquals(t0 - MlsResendBudget.WINDOW_MS, MlsResendBudget.windowStart(t0));
        final long later = t0 + MlsResendBudget.WINDOW_MS;
        assertTrue("a row at t0 must fall outside the window an hour later",
                MlsResendBudget.windowStart(later) >= t0);
    }

    /** The window must not let a runaway resume the moment it is noticed. */
    @Test
    public void aBurstInsideTheWindowIsStillRefused() {
        final long t0 = 1_800_000_000_000L;
        final long justInside = MlsResendBudget.windowStart(t0) + 1;
        assertTrue("a row 1ms inside the window still counts",
                justInside > MlsResendBudget.windowStart(t0));
        assertTrue(MlsResendBudget.spent(99));
    }

    /** The window is long enough that repeated budgets cannot themselves become a storm. */
    @Test
    public void theWindowIsLongEnoughToBoundTheWorstCaseRate() {
        final long perHour = MlsResendBudget.MAX_PER_WINDOW
                * (60L * 60L * 1000L / MlsResendBudget.WINDOW_MS);
        assertTrue("worst case must stay a handful of resends an hour, not a storm", perHour <= 10);
    }
}
