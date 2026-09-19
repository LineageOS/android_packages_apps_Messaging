/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * The Android-coupled sites that must consult {@link RcsSendStatus}, which {@link
 * RcsSendStatusTest} tests as a pure decision. See docs/rcs/architecture.md.
 */
public class RcsSendStatusGuardTest {

    private static final String INSERT =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";
    private static final String FIXUP =
            "src/com/android/messaging/datamodel/action/FixupMessageStatusOnStartupAction.java";
    private static final String RESEND =
            "src/com/android/messaging/datamodel/action/ResendMessageAction.java";
    private static final String PENDING =
            "src/com/android/messaging/datamodel/action/ProcessPendingMessagesAction.java";
    private static final String LOCATION =
            "src/com/android/messaging/datamodel/action/SendRcsLocationAction.java";

    /**
     * Every writer of an outgoing RCS row records its outcome unless the provider reports that
     * verb's status by callback; otherwise the row stays at {@code OUTGOING_YET_TO_SEND}, which
     * nothing moves. The callback-fed sites are an explicit list so a new writer fails until
     * classified; add to it only for a verb the provider answers with {@code onMessageStatus}.
     */
    @Test
    public void everyOutgoingRcsRowWriterRecordsOrIsFedByACallback() throws IOException {
        // File sends: the provider reports their status through onMessageStatus.
        final List<String> sinkFed = java.util.Arrays.asList(
                "tryInsertSendingRcsMessage", "tryInsertSendingRcsFile",
                "tryInsertSendingRcsGroupMessage", "tryInsertSendingRcsGroupFile");

        final String insert = SourceScan.codeOnly(SourceScan.read(INSERT));
        final String location = SourceScan.codeOnly(SourceScan.read(LOCATION));

        final String[][] writers = {
            {INSERT, "tryInsertSendingRcsMessage"},
            {INSERT, "tryInsertSendingRcsFile"},
            {INSERT, "tryInsertSendingRcsGroupMessage"},
            {INSERT, "tryInsertSendingRcsGroupFile"},
            {LOCATION, "executeAction"},
        };
        int checked = 0;
        for (final String[] w : writers) {
            final String src = w[0].equals(INSERT) ? insert : location;
            final String body = SourceScan.bodyOf(src, w[1]);
            assertTrue("ZERO HITS MUST FAIL: " + w[1] + " not found in " + w[0]
                    + " — a guard that cannot find its subject passes on nothing",
                    body.length() > 200);
            // Only bodies that store a wire id are row-writers in this sense.
            if (SourceScan.count(body, "rcsMetaValues(") < 1) continue;
            checked++;
            final boolean records =
                    SourceScan.count(body, "bugleStatusForMeasuredHandoff(") >= 1
                            && SourceScan.count(body, "rcsStatusForMeasuredHandoff(") >= 1;
            if (records || sinkFed.contains(w[1])) continue;
            org.junit.Assert.fail(w[1] + " in " + w[0] + " inserts an outgoing RCS row and neither "
                    + "records an observed outcome nor is fed by a status callback. Left at "
                    + "BUGLE_STATUS_OUTGOING_YET_TO_SEND the row reads \"Sending…\" for ever: the "
                    + "SMS queue excludes TRANSPORT_RCS by name, ResendMessageAction refuses it, "
                    + "and the startup fixup rewrites only SENDING/RESENDING. If this "
                    + "verb's provider entry point goes through TachyonTransport it fires a sink "
                    + "and belongs on the exempt list — CHECK RcsProviderService before adding it, "
                    + "because a verb that reaches TachyonRegistrar directly fires nothing and the "
                    + "exemption would be a lie. Otherwise stamp "
                    + "RcsSendStatus.bugleStatusForMeasuredHandoff / rcsStatusForMeasuredHandoff "
                    + "in the same rcsMetaValues write.");
        }
        assertTrue("no row-writer was actually checked, so this test passed on an empty set — the "
                + "method names or the rcsMetaValues token have gone stale", checked >= 4);
    }

    /**
     * The cold-start sweep covers both stranding statuses and is narrowed to {@code TRANSPORT_RCS};
     * unnarrowed, it would fail SMS rows the send queue still holds.
     */
    @Test
    public void startupFixupSweepsStrandedRcsRows() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(FIXUP)), "executeAction");
        assertTrue("executeAction not found in " + FIXUP, body.length() > 200);
        assertEquals("the startup fixup must sweep RCS rows stranded at OUTGOING_YET_TO_SEND",
                1, SourceScan.count(body, "BUGLE_STATUS_OUTGOING_YET_TO_SEND"));
        assertEquals("…and at OUTGOING_AWAITING_RETRY, the queue's other status",
                1, SourceScan.count(body, "BUGLE_STATUS_OUTGOING_AWAITING_RETRY"));
        assertEquals("the sweep MUST be narrowed to TRANSPORT_RCS — an unnarrowed sweep of 4/7 "
                        + "would fail SMS rows ProcessPendingMessagesAction is still holding",
                1, SourceScan.count(body, "RcsConstants.TRANSPORT_RCS"));
        assertEquals("…by the transport_type column", 1,
                SourceScan.count(body, "MessageColumns.TRANSPORT_TYPE"));
    }

    /**
     * An RCS row branches away before the {@code OUTGOING_YET_TO_SEND} write, and that
     * branch neither writes the status nor uses the SMS queue.
     */
    @Test
    public void resendRoutesRcsRowsAwayFromTheSmsQueue() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(RESEND));
        final String body = SourceScan.bodyOf(src, "executeAction");
        assertTrue("executeAction not found in " + RESEND, body.length() > 200);
        final List<Integer> reads = SourceScan.indicesOf(body, "readByLocalId(");
        assertEquals("ResendMessageAction must consult the row's transport type — the SMS send "
                        + "queue below excludes TRANSPORT_RCS, so an RCS row that reaches it is "
                        + "parked at \"Sending…\" and never sent",
                1, reads.size());
        final List<Integer> branch = SourceScan.indicesOf(body, "return null;");
        assertTrue("the RCS arm must return before the SMS path, not fall into it",
                !branch.isEmpty());
        final List<Integer> writes =
                SourceScan.indicesOf(body, "BUGLE_STATUS_OUTGOING_YET_TO_SEND");
        assertEquals("the YET_TO_SEND write should still be here exactly once (SMS/MMS keep it)",
                1, writes.size());
        assertTrue("the transport-type check must come BEFORE the YET_TO_SEND write, or the branch "
                        + "is too late to prevent the strand",
                reads.get(0).intValue() < writes.get(0).intValue());
        assertTrue("the RCS branch must be taken BEFORE the YET_TO_SEND write too",
                branch.get(0).intValue() < writes.get(0).intValue());

    }

    /**
     * The premise of the guards above: the SMS send queue excludes {@code TRANSPORT_RCS}. If it
     * ever takes RCS rows, the resend branch and the startup sweep become wrong. Read raw, since
     * the exclusion is in a SQL string.
     */
    @Test
    public void pendingSendQueueStillExcludesRcs() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.read(PENDING), "findNextMessageToSend");
        assertTrue("findNextMessageToSend not found in " + PENDING, body.length() > 500);
        assertTrue("the send queue must still NARROW on transport_type",
                SourceScan.count(body, "MessageColumns.TRANSPORT_TYPE") >= 1);
        assertTrue("the send queue must still EXCLUDE (!=) rather than include it",
                SourceScan.count(body, "\" !=? \"") >= 1);
        assertTrue("…and the excluded value must still be TRANSPORT_RCS",
                SourceScan.count(body, "RcsConstants.TRANSPORT_RCS") >= 1);
        // The query's statuses are strandedOnRcsTransport's domain; a third would need adding
        // there.
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_YET_TO_SEND));
        assertTrue(SourceScan.count(body, "BUGLE_STATUS_OUTGOING_YET_TO_SEND") >= 1);
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_AWAITING_RETRY));
        assertTrue(SourceScan.count(body, "BUGLE_STATUS_OUTGOING_AWAITING_RETRY") >= 1);
    }

    /**
     * RCS rows are never resent over RCS: ResendMessageAction refuses them, so offering the tap
     * or the "Send" item would do nothing. "Send as SMS" is their resend.
     */
    @Test
    public void rcsRowsAreNotOfferedAnRcsResend() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/datamodel/data/ConversationMessageData.java"));
        final String body = SourceScan.bodyOf(src, "getOneClickResendMessage");
        assertTrue("getOneClickResendMessage not found", body.length() > 20);
        assertEquals("a tap must be withheld from RCS rows: ResendMessageAction refuses them",
                1, SourceScan.count(body, "!getIsRcs()"));

        final String menu = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/ui/conversation/ConversationFragment.java"));
        final int send = menu.indexOf("R.id.action_send)");
        assertTrue("the Send menu item was not found", send >= 0);
        final String gate = menu.substring(send, menu.indexOf(';', send));
        assertEquals("the Send item must be withheld from RCS rows for the same reason",
                1, SourceScan.count(gate, "!data.getIsRcs()"));

        final String resend = SourceScan.codeOnly(SourceScan.read(RESEND));
        assertTrue("ResendMessageAction must still refuse RCS rows, or this rule is stale",
                SourceScan.count(SourceScan.bodyOf(resend, "executeAction"), "isRcs()") >= 1);
    }
}
