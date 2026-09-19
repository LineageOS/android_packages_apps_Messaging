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
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Which conversation a message WE SENT belongs to.
 *
 * <p>The answer decides whether ONE delivery receipt is terminal. "1:1" means the sole recipient
 * confirming ends the message, so its send material is released and its resend chain forgotten;
 * "group" means one member's success says nothing about the others and the bytes must survive
 *. Answering "1:1" about a group message is therefore not a mis-label, it is a
 * data-loss bug — and a §10.3 RESEND, a bare UUID with no chat row, was answered exactly that way
 * because it fits neither of the two routes the resolver knew about.
 */
public class MlsSentMessageOriginTest {

    private static final String GID = "5B8905CD-DEF5-414C-BC3F-5343069C256D";
    /** What MlsResendLedger.recordResend mints: a bare UUID, with no chat row anywhere. */
    private static final String RESEND = "7e6e374e-197f-4848-9feb-b004fd86d46b";

    /** THE BUG: a group resend has no group in its id and no chat row, so both old routes miss. */
    @Test public void aGroupResendIsAGroupMessage() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, "g:" + GID,
                "mls-grp-" + GID + "-1786224470377", /*chatRowGroupId=*/ null));
    }

    /** The ledger answers even when the ROOT is an app id whose chat row we could not read. */
    @Test public void theLedgerKeyAloneIsEnough() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, "g:" + GID,
                "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    /** …and the root's own wire id answers when the ledger row carries no conversation key. */
    @Test public void theRootsWireIdIsTheFallback() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                "mls-grp-" + GID + "-1786224470377", /*chatRowGroupId=*/ null));
    }

    /** …and the root's chat row after that. */
    @Test public void theRootsChatRowIsTheLastResort() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                "0b1e5f22-0000-4000-8000-000000000001", GID));
    }

    /**
     * A resend we cannot classify is NOT a 1:1.
     *
     * <p>{@code null} is the answer that makes one receipt terminal, so guessing it for an id we
     * could not resolve is how the chain gets released. Unknown must keep the material; the
     * retention window is what bounds it.
     */
    @Test public void anUnclassifiableResendIsUnknownRatherThanOneToOne() {
        assertEquals(MlsMessageId.UNKNOWN_CONVERSATION,
                MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                        "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    /** A resend of a 1:1 message still resolves to a 1:1 — the fix must not pin every chain. */
    @Test public void aOneToOneResendIsStillAOneToOne() {
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "p:+15550001",
                "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    /** The two pre-existing routes are byte-unchanged. */
    @Test public void theOriginalTwoRoutesStillAnswer() {
        // The group is in the id itself.
        assertEquals(GID, MlsMessageId.groupOfSentMessage("mls-grp-" + GID + "-1786224470377",
                /*ledgerKey=*/ null, "mls-grp-" + GID + "-1786224470377", /*chatRowGroupId=*/ null));
        // A UI group send: an app id with a chat row naming the group.
        assertEquals(GID, MlsMessageId.groupOfSentMessage("0b1e5f22-0000-4000-8000-000000000001",
                /*ledgerKey=*/ null, "0b1e5f22-0000-4000-8000-000000000001", GID));
        // A UI 1:1 send: an app id, a row, no group. Not a resend, so it stays a proven 1:1.
        assertNull(MlsMessageId.groupOfSentMessage("0b1e5f22-0000-4000-8000-000000000001",
                /*ledgerKey=*/ null, "0b1e5f22-0000-4000-8000-000000000001",
                /*chatRowGroupId=*/ null));
    }

    /** The id's own shape wins over a stale ledger key: the wire id cannot be wrong about itself. */
    @Test public void theIdsOwnShapeIsConsultedFirst() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage("mls-grp-" + GID + "-7",
                "p:+15550001", "mls-grp-" + GID + "-7", /*chatRowGroupId=*/ null));
    }

    /** Degenerate inputs must not throw on a callback thread. */
    @Test public void degenerateInputsAreSurvivable() {
        assertNull(MlsMessageId.groupOfSentMessage(null, null, null, null));
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "", RESEND, ""));
        // A key of an unrecognised shape is not evidence of anything, so it falls through.
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "who-knows", RESEND, null));
        // An empty group id inside a well-formed key is not a group id.
        assertEquals(MlsMessageId.UNKNOWN_CONVERSATION,
                MlsMessageId.groupOfSentMessage(RESEND, "g:", "root-id", null));
    }
}
