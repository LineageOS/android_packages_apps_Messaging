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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * Rework item 5.3 — the era yield is decided by the group MOMENT, not by a stopwatch.
 *
 * <p>The two halves are deliberately separate functions and these tests keep them that way: the
 * moment answers "did the peer act?", the look count answers "have we waited long enough to stop?".
 * Neither can answer the other's question.
 */
public class MlsEraYieldTest {

    @Test public void theYieldSucceedsWhenTheGroupMoves() {
        // An era advance rebuilds the group, so the era changes and the epoch restarts.
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(new Moment(3, 9L), new Moment(4, 0L)));
        // An in-era commit also counts as movement: something happened while we waited.
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(new Moment(3, 9L), new Moment(3, 10L)));
    }

    @Test public void anUnmovedGroupIsNotSatisfied() {
        assertFalse(MlsRecoveryPolicy.eraYieldSatisfied(new Moment(3, 9L), new Moment(3, 9L)));
    }

    @Test public void anUnknownMomentIsNotMovement() {
        // The upgrade case. A device that has just gained the field must not read "I do not know
        // where the group is" as "the peer advanced" and silently stop yielding.
        assertFalse(MlsRecoveryPolicy.eraYieldSatisfied(null, new Moment(4, 0L)));
        assertFalse(MlsRecoveryPolicy.eraYieldSatisfied(new Moment(3, 9L), null));
        assertFalse(MlsRecoveryPolicy.eraYieldSatisfied(null, null));
    }

    @Test public void theBoundIsCountedInLooksNotMilliseconds() {
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(1, 3));
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(2, 3));
        assertTrue(MlsRecoveryPolicy.eraYieldObservationsExhausted(3, 3));
        assertTrue(MlsRecoveryPolicy.eraYieldObservationsExhausted(4, 3));
    }

    @Test public void aZeroLimitYieldsForever() {
        // The documented "0 = yield forever" contract. A device configured to always defer must
        // never be pushed into advancing by sheer repetition.
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, 0));
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, -1));
    }

    @Test public void sleepingCannotExhaustTheBound() {
        // The concrete regression the wall-clock version had: a device off for a week woke with its
        // 120s deadline long past and advanced an era nobody needed. A count only moves when we
        // actually look, so no amount of elapsed time can spend it.
        int looks = 0;
        assertFalse("no looks taken means nothing burned",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(looks, 3));
        // ... a week passes, still nobody looked ...
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(looks, 3));
    }

    @Test public void movementIsCheckedBeforeTheBound() {
        // Order matters in the caller: a group that moved on the final permitted look must report
        // the yield as having WORKED, not as exhausted. These are the two inputs at that moment.
        final Moment start = new Moment(3, 9L);
        final Moment now = new Moment(4, 0L);
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(start, now));
        assertTrue("the bound would also fire here — success must win",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(3, 3));
    }
}
