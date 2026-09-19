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

/**
 * The property that matters here is not the cap — it is that the cap <b>expires</b>.
 *
 * <p>A count-only cap was measured turning a forty-second bug into a permanently degraded
 * conversation, recoverable only by an operator deleting rows by hand.
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

    /** THE POINT: rows older than the window must not count, so a burst ages out by itself. */
    @Test
    public void theWindowStartMovesForwardWithTime() {
        final long t0 = 1_800_000_000_000L;
        assertEquals(t0 - MlsResendBudget.WINDOW_MS, MlsResendBudget.windowStart(t0));
        final long later = t0 + MlsResendBudget.WINDOW_MS;
        assertTrue("a row at t0 must fall outside the window an hour later",
                MlsResendBudget.windowStart(later) >= t0);
    }

    /**
     * A burst inside the window is still refused — the window must not become an escape hatch that
     * lets a runaway resume the moment it is noticed.
     */
    @Test
    public void aBurstInsideTheWindowIsStillRefused() {
        final long t0 = 1_800_000_000_000L;
        final long justInside = MlsResendBudget.windowStart(t0) + 1;
        assertTrue("a row 1ms inside the window still counts", justInside > MlsResendBudget.windowStart(t0));
        assertTrue(MlsResendBudget.spent(99));
    }

    /** The window has to be long enough that repeated budgets cannot themselves become a storm. */
    @Test
    public void theWindowIsLongEnoughToBoundTheWorstCaseRate() {
        final long perHour = MlsResendBudget.MAX_PER_WINDOW
                * (60L * 60L * 1000L / MlsResendBudget.WINDOW_MS);
        assertTrue("worst case must stay a handful of resends an hour, not a storm", perHour <= 10);
    }
}
