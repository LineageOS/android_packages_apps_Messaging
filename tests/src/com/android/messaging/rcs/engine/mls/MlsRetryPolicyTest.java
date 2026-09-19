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

import com.android.messaging.rcs.engine.mls.MlsRetryPolicy.Outcome;
import com.android.messaging.rcs.engine.mls.MlsRetryPolicy.WorkItem;

/**
 * Rework item 5.6 — the retry schedule, and specifically the parts a reimplementer gets wrong.
 *
 * <p>Every assertion here is pinning a VERIFIED NEGATIVE or a bound. The positives (that a retry
 * eventually runs) are uninteresting by comparison; what costs months is linear silently becoming
 * exponential, or duplicate runs silently becoming deduplicated.
 */
public class MlsRetryPolicyTest {

    // -- the backoff is LINEAR, not exponential (§19.3-32) -----------------------------------------

    @Test public void theImmediateLadderIsThreeFlatSteps() {
        assertEquals(30_000L, MlsRetryPolicy.retryDelayMs(1));
        assertEquals(30_000L, MlsRetryPolicy.retryDelayMs(2));
        assertEquals(30_000L, MlsRetryPolicy.retryDelayMs(3));
        assertTrue(MlsRetryPolicy.isImmediateRetry(3));
        assertFalse(MlsRetryPolicy.isImmediateRetry(4));
    }

    @Test public void afterTheLadderTheGrowthIsLinear() {
        // attempt<=3 ? 30s : defaultDelay*(attempt-3)
        assertEquals(300_000L, MlsRetryPolicy.retryDelayMs(4));
        assertEquals(600_000L, MlsRetryPolicy.retryDelayMs(5));
        assertEquals(900_000L, MlsRetryPolicy.retryDelayMs(6));
    }

    @Test public void theGrowthIsNotExponential() {
        // The regression that matters. Exponential is what every other backoff in this tree does,
        // so it is the shape someone will reach for; a transient failure that clears in a minute
        // must not be waited out for an hour because it happened to fail four times.
        final long d4 = MlsRetryPolicy.retryDelayMs(4);
        for (int a = 5; a < 20; a++) {
            final long step = MlsRetryPolicy.retryDelayMs(a) - MlsRetryPolicy.retryDelayMs(a - 1);
            assertEquals("step must be CONSTANT, not growing, at attempt " + a, d4, step);
        }
    }

    @Test public void aNonsenseAttemptIsTreatedAsTheFirst() {
        for (final int a : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertEquals(30_000L, MlsRetryPolicy.retryDelayMs(a));
        }
    }

    // -- the three nested bounds -------------------------------------------------------------------

    @Test public void theBoundsAreNestedAndDistinct() {
        assertTrue(MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM > MlsRetryPolicy.MAX_DRAIN_ITERATIONS);
        assertTrue(MlsRetryPolicy.MAX_DRAIN_ITERATIONS > MlsRetryPolicy.MAX_POSTPROCESS_ITERATIONS);
        assertEquals(1000, MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM);
        assertEquals(100, MlsRetryPolicy.MAX_DRAIN_ITERATIONS);
        assertEquals(10, MlsRetryPolicy.MAX_POSTPROCESS_ITERATIONS);
    }

    @Test public void theInnermostBoundIsTheDriveLoopsCap() {
        // The same bound seen from two sides. If these drift, one of the two becomes unreachable.
        assertEquals(MlsDriveLoop.DEFAULT_MAX_ITERATIONS,
                MlsRetryPolicy.MAX_POSTPROCESS_ITERATIONS);
    }

    @Test public void theOutermostBoundTerminates() {
        assertFalse(MlsRetryPolicy.attemptsExhausted(999));
        assertTrue(MlsRetryPolicy.attemptsExhausted(1000));
        assertTrue(MlsRetryPolicy.attemptsExhausted(5000));
    }

    @Test public void theBatchIsOneRow() {
        // Not a batch. A later "optimisation" that widens this would quietly serialise unrelated
        // conversations behind each other.
        assertEquals(1, MlsRetryPolicy.MAX_ROWS_PER_BATCH);
    }

    // -- the scheduling asymmetry (§19.3-33) -------------------------------------------------------

    @Test public void onlyRetryImmediatelySchedulesFollowUp() {
        assertTrue(MlsRetryPolicy.schedulesFollowUp(Outcome.RETRY_IMMEDIATELY));
        // Reads like a bug and is not: the inline path already runs on a trigger that will fire
        // again, so scheduling here stacks a second driver on the one already coming.
        assertFalse(MlsRetryPolicy.schedulesFollowUp(Outcome.NEED_MORE_RETRIES));
        assertFalse(MlsRetryPolicy.schedulesFollowUp(Outcome.DONE));
        assertFalse(MlsRetryPolicy.schedulesFollowUp(Outcome.FAILED));
    }

    @Test public void theSameStatusMeansDifferentThingsInlineAndQueued() {
        // The asymmetry in one assertion.
        assertEquals(Outcome.RETRY_IMMEDIATELY,
                MlsRetryPolicy.outcomeOf(MlsResultStatus.FAIL_RETRY, false));
        assertEquals(Outcome.NEED_MORE_RETRIES,
                MlsRetryPolicy.outcomeOf(MlsResultStatus.FAIL_RETRY, true));
    }

    @Test public void convergenceIsDetectedByNoOp() {
        assertEquals(Outcome.DONE, MlsRetryPolicy.outcomeOf(MlsResultStatus.NO_OP, false));
        assertEquals(Outcome.DONE, MlsRetryPolicy.outcomeOf(MlsResultStatus.NO_OP, true));
        assertEquals(Outcome.DONE, MlsRetryPolicy.outcomeOf(MlsResultStatus.SUCCESS, false));
    }

    @Test public void terminalFailureNeverSchedules() {
        assertEquals(Outcome.FAILED, MlsRetryPolicy.outcomeOf(MlsResultStatus.FAIL_NO_RETRY, false));
        assertEquals(Outcome.FAILED, MlsRetryPolicy.outcomeOf(MlsResultStatus.FAIL_NO_RETRY, true));
        assertFalse(MlsRetryPolicy.schedulesFollowUp(
                MlsRetryPolicy.outcomeOf(MlsResultStatus.FAIL_NO_RETRY, false)));
    }

    @Test public void pendingKeepsDriving() {
        // PENDING is in-flight, not finished; queued it re-runs, inline it waits for its trigger.
        assertEquals(Outcome.RETRY_IMMEDIATELY,
                MlsRetryPolicy.outcomeOf(MlsResultStatus.PENDING, false));
        assertEquals(Outcome.NEED_MORE_RETRIES,
                MlsRetryPolicy.outcomeOf(MlsResultStatus.PENDING, true));
    }

    @Test public void aNullStatusDoesNotScheduleAnything() {
        // The safe direction: an unknown outcome must not spin the scheduler.
        assertEquals(Outcome.NEED_MORE_RETRIES, MlsRetryPolicy.outcomeOf(null, false));
        assertFalse(MlsRetryPolicy.schedulesFollowUp(MlsRetryPolicy.outcomeOf(null, false)));
    }

    // -- the work item -----------------------------------------------------------------------------

    @Test public void theUnitOfWorkIsOnePair() {
        final WorkItem w = new WorkItem("gid-1", "+15551234567", 1);
        assertEquals("gid-1", w.groupId);
        assertEquals("+15551234567", w.msisdn);
        assertEquals(1, w.immediateRetryAttempt);
        assertEquals(30_000L, w.delayMs());
    }

    @Test public void nextCarriesTheAttemptForward() {
        // The attempt rides the PAYLOAD so it survives the process — a counter held in memory would
        // reset on every restart and the ladder would never advance.
        final WorkItem w = new WorkItem("g", "+1", 3).next();
        assertEquals(4, w.immediateRetryAttempt);
        assertEquals(300_000L, w.delayMs());
        assertEquals("g", w.groupId);
    }

    @Test public void twoItemsForTheSamePairAreTwoItems() {
        // §19.3-29's no-dedup rule, expressed as the absence of any collapsing behaviour. There is
        // deliberately no dedup key on WorkItem: convergence comes from extra runs finding NO_OP,
        // so collapsing duplicates would REMOVE the idempotence mechanism, not improve it.
        final WorkItem a = new WorkItem("g", "+1", 1);
        final WorkItem b = new WorkItem("g", "+1", 1);
        assertFalse("no value-equality that a set could collapse on", a.equals(b));
    }

    @Test public void nullsInAWorkItemAreEmptyNotCrashes() {
        final WorkItem w = new WorkItem(null, null, 0);
        assertEquals("", w.groupId);
        assertEquals("", w.msisdn);
        assertEquals(1, w.immediateRetryAttempt);
    }
}
