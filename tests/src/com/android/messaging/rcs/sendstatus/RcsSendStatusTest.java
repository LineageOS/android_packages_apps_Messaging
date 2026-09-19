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
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

/**
 * The decision itself: a send whose outcome we measured must never leave the
 * row in a status nothing can move.
 *
 * <p><b>The state that would make these fail, and that is reachable:</b> a mapping that answers
 * {@code BUGLE_STATUS_OUTGOING_YET_TO_SEND} — which is not a hypothetical, it is what the tree
 * shipped. {@code InsertNewMessageAction} had the answer in hand
 * ({@code MlsProviderTransport.sendAppOwned} returns {@code SENT} or {@code FAILED} synchronously,
 * before the row is inserted), dropped it, and inserted at 4 either way. Verified by mutation on
 * 2026-09-13: replacing {@link RcsSendStatus#bugleStatusForMeasuredHandoff}'s body with
 * {@code return BUGLE_STATUS_OUTGOING_YET_TO_SEND;} fails four of the five cases below. So these
 * are not assertions that a function returns what it was written to return — each names a value the
 * shipped code actually used. The companion half, that production still CONSULTS this class, is
 * {@code RcsSendStatusGuardTest}; neither test can see what the other checks.
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

    /** The property, stated once over BOTH outcomes rather than asserted twice by coincidence. */
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

    /** The two outcomes must be DISTINGUISHABLE; one status for both is no report at all. */
    @Test
    public void theTwoOutcomesDoNotCollapse() {
        assertTrue(RcsSendStatus.bugleStatusForMeasuredHandoff(true)
                != RcsSendStatus.bugleStatusForMeasuredHandoff(false));
        assertTrue(RcsSendStatus.rcsStatusForMeasuredHandoff(true)
                != RcsSendStatus.rcsStatusForMeasuredHandoff(false));
    }

    /**
     * Exactly the pair {@code ProcessPendingMessagesAction.findNextMessageToSend} selects on while
     * excluding {@code TRANSPORT_RCS} from the same query — no more and no less.
     * {@code RcsSendStatusGuardTest.pendingSendQueueStillExcludesRcs} is what keeps that true.
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
        // 2 == BUGLE_STATUS_OUTGOING_DELIVERED, 3 == OUTGOING_DRAFT, 5/6 == SENDING/RESENDING
        // (which the startup fixup already owns). None of them is this predicate's business.
        for (final int other : new int[] {0, 2, 3, 5, 6, 9, 100}) {
            assertFalse("status " + other + " must not be reported as stranded",
                    RcsSendStatus.strandedOnRcsTransport(other));
        }
    }
}
