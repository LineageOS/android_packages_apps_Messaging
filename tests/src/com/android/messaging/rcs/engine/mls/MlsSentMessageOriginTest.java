/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Which conversation a message we sent belongs to. A 1:1 answer makes one delivery receipt terminal
 * and releases the send material, so answering 1:1 for a group message, including an RCC.16 §10.3
 * resend (a bare UUID with no chat row), loses data.
 */
public class MlsSentMessageOriginTest {

    private static final String GID = "5B8905CD-DEF5-414C-BC3F-5343069C256D";
    /** What MlsResendLedger.recordResend mints: a bare UUID with no chat row. */
    private static final String RESEND = "7e6e374e-197f-4848-9feb-b004fd86d46b";

    @Test public void aGroupResendIsAGroupMessage() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, "g:" + GID,
                "mls-grp-" + GID + "-1786224470377", /*chatRowGroupId=*/ null));
    }

    @Test public void theLedgerKeyAloneIsEnough() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, "g:" + GID,
                "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    @Test public void theRootsWireIdIsTheFallback() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                "mls-grp-" + GID + "-1786224470377", /*chatRowGroupId=*/ null));
    }

    @Test public void theRootsChatRowIsTheLastResort() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                "0b1e5f22-0000-4000-8000-000000000001", GID));
    }

    /** Unknown keeps the material (the retention window bounds it); null would release it. */
    @Test public void anUnclassifiableResendIsUnknownRatherThanOneToOne() {
        assertEquals(MlsMessageId.UNKNOWN_CONVERSATION,
                MlsMessageId.groupOfSentMessage(RESEND, /*ledgerKey=*/ null,
                        "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    @Test public void aOneToOneResendIsStillAOneToOne() {
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "p:+15550001",
                "0b1e5f22-0000-4000-8000-000000000001", /*chatRowGroupId=*/ null));
    }

    @Test public void theOriginalTwoRoutesStillAnswer() {
        // The group is in the id itself.
        assertEquals(GID, MlsMessageId.groupOfSentMessage("mls-grp-" + GID + "-1786224470377",
                /*ledgerKey=*/ null, "mls-grp-" + GID + "-1786224470377",
                /*chatRowGroupId=*/ null));
        // An app id with a chat row naming the group.
        assertEquals(GID, MlsMessageId.groupOfSentMessage("0b1e5f22-0000-4000-8000-000000000001",
                /*ledgerKey=*/ null, "0b1e5f22-0000-4000-8000-000000000001", GID));
        // An app id with a row and no group, not a resend: a 1:1.
        assertNull(MlsMessageId.groupOfSentMessage("0b1e5f22-0000-4000-8000-000000000001",
                /*ledgerKey=*/ null, "0b1e5f22-0000-4000-8000-000000000001",
                /*chatRowGroupId=*/ null));
    }

    /** The wire id's own shape wins over a stale ledger key. */
    @Test public void theIdsOwnShapeIsConsultedFirst() {
        assertEquals(GID, MlsMessageId.groupOfSentMessage("mls-grp-" + GID + "-7",
                "p:+15550001", "mls-grp-" + GID + "-7", /*chatRowGroupId=*/ null));
    }

    @Test public void degenerateInputsAreSurvivable() {
        assertNull(MlsMessageId.groupOfSentMessage(null, null, null, null));
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "", RESEND, ""));
        // A key of an unrecognised shape falls through.
        assertNull(MlsMessageId.groupOfSentMessage(RESEND, "who-knows", RESEND, null));
        // An empty group id inside a well-formed key is not a group id.
        assertEquals(MlsMessageId.UNKNOWN_CONVERSATION,
                MlsMessageId.groupOfSentMessage(RESEND, "g:", "root-id", null));
    }
}
