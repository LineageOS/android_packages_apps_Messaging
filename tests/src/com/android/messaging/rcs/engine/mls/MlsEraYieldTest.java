/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * The era yield is decided by the group moment, not a stopwatch: the moment answers "did the peer
 * act?", the look count answers "have we waited long enough?", and they stay separate functions.
 * See docs/mls/health-and-recovery.md.
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
        // A device that has just gained the field must not read "unknown position" as "the peer
        // advanced".
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
        // 0 means yield forever; repetition never pushes such a device into advancing.
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, 0));
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(1_000_000, -1));
    }

    @Test public void sleepingCannotExhaustTheBound() {
        // A count moves only when we look, so elapsed time (a device off for a week) cannot spend
        // it.
        int looks = 0;
        assertFalse("no looks taken means nothing burned",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(looks, 3));
        // ... a week passes, still nobody looked ...
        assertFalse(MlsRecoveryPolicy.eraYieldObservationsExhausted(looks, 3));
    }

    @Test public void movementIsCheckedBeforeTheBound() {
        // In the caller, a group that moved on the final permitted look reports the yield as having
        // worked, not as exhausted. These are the two inputs at that moment.
        final Moment start = new Moment(3, 9L);
        final Moment now = new Moment(4, 0L);
        assertTrue(MlsRecoveryPolicy.eraYieldSatisfied(start, now));
        assertTrue("the bound would also fire here — success must win",
                MlsRecoveryPolicy.eraYieldObservationsExhausted(3, 3));
    }
}
