/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

/**
 * A send with a known outcome never leaves an RCS row in a status nothing moves. A mapping that
 * answers {@code BUGLE_STATUS_OUTGOING_YET_TO_SEND} fails four of these. That production consults
 * it is {@code RcsSendStatusGuardTest}.
 */
public class RcsSendStatusTest {

    @Test
    public void measuredAcceptIsSentNotSending() {
        assertEquals("an accepted handoff must be recorded as SENT",
                RcsSendStatus.BUGLE_STATUS_OUTGOING_COMPLETE,
                RcsSendStatus.bugleStatusForMeasuredHandoff(true));
        assertEquals(RcsSendStatus.RCS_STATUS_SENT,
                RcsSendStatus.rcsStatusForMeasuredHandoff(true));
    }

    @Test
    public void measuredRefusalIsFailedNotSending() {
        assertEquals("a refused handoff must be recorded as FAILED — it is the ONLY status from "
                        + "which the UI offers any escape affordance at all",
                RcsSendStatus.BUGLE_STATUS_OUTGOING_FAILED,
                RcsSendStatus.bugleStatusForMeasuredHandoff(false));
        assertEquals(RcsSendStatus.RCS_STATUS_FAILED,
                RcsSendStatus.rcsStatusForMeasuredHandoff(false));
    }

    @Test
    public void noMeasuredOutcomeEverStrands() {
        for (final boolean accepted : new boolean[] {true, false}) {
            final int status = RcsSendStatus.bugleStatusForMeasuredHandoff(accepted);
            assertFalse("bugleStatusForMeasuredHandoff(" + accepted + ") returned " + status
                            + ", which nothing can move on a TRANSPORT_RCS row — that is the "
                            + "\"Sending… forever\" bubble this exists to remove",
                    RcsSendStatus.strandedOnRcsTransport(status));
        }
    }

    @Test
    public void theTwoOutcomesDoNotCollapse() {
        assertTrue(RcsSendStatus.bugleStatusForMeasuredHandoff(true)
                != RcsSendStatus.bugleStatusForMeasuredHandoff(false));
        assertTrue(RcsSendStatus.rcsStatusForMeasuredHandoff(true)
                != RcsSendStatus.rcsStatusForMeasuredHandoff(false));
    }

    /**
     * Exactly the pair the SMS send queue selects on while excluding {@code TRANSPORT_RCS}, which
     * {@code RcsSendStatusGuardTest.pendingSendQueueStillExcludesRcs} checks.
     */
    @Test
    public void strandedIsExactlyTheSmsQueuesTwoStatuses() {
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_YET_TO_SEND));
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_AWAITING_RETRY));
        assertFalse(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_COMPLETE));
        assertFalse(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_FAILED));
        // Delivered, draft, and the sending statuses the startup fixup already handles.
        for (final int other : new int[] {0, 2, 3, 5, 6, 9, 100}) {
            assertFalse("status " + other + " must not be reported as stranded",
                    RcsSendStatus.strandedOnRcsTransport(other));
        }
    }
}
