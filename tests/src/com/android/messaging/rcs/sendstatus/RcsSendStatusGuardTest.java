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
     * The app-sealed send writes the outcome it got back, in both columns, so the row matches one a
     * status callback would have written.
     */
    @Test
    public void appOwnedSendRecordsItsMeasuredOutcome() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(INSERT)), "tryInsertSendingRcsMessage");
        assertTrue("tryInsertSendingRcsMessage not found in " + INSERT
                + " — this guard has gone stale, not green", body.length() > 500);
        assertTrue("the app-owned arm must still be the one measuring the outcome",
                SourceScan.count(body, "sendAppOwned(") >= 1);
        assertEquals(
                        "tryInsertSendingRcsMessage must stamp the observed handoff's message_status via "
                        + "RcsSendStatus.bugleStatusForMeasuredHandoff; without it the row goes in "
                        + "at OUTGOING_YET_TO_SEND and reads \"Sending…\" forever",
                1, SourceScan.count(body, "bugleStatusForMeasuredHandoff("));
        assertEquals("tryInsertSendingRcsMessage must stamp the observed handoff's rcs_status via "
                        + "RcsSendStatus.rcsStatusForMeasuredHandoff",
                1, SourceScan.count(body, "rcsStatusForMeasuredHandoff("));
    }

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
                "tryInsertSendingRcsFile", "tryInsertSendingRcsGroupFile");

        final String insert = SourceScan.codeOnly(SourceScan.read(INSERT));
        final String location = SourceScan.codeOnly(SourceScan.read(LOCATION));

        // Refusal writers pass a null id and mark the row failed, so they are not listed.
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
     * An RCS row branches to the RCS resend before the {@code OUTGOING_YET_TO_SEND} write, and that
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
        final List<Integer> branch = SourceScan.indicesOf(body, "resendOverRcs(");
        assertEquals("the RCS arm must hand off to the over-RCS resend. If it was "
                        + "renamed, re-derive this guard; if it was REMOVED, a failed RCS row is "
                        + "back to having a plaintext SMS as its only recovery on a conversation "
                        + "the app called encrypted.",
                1, branch.size());
        final List<Integer> writes =
                SourceScan.indicesOf(body, "BUGLE_STATUS_OUTGOING_YET_TO_SEND");
        assertEquals("the YET_TO_SEND write should still be here exactly once (SMS/MMS keep it)",
                1, writes.size());
        assertTrue("the transport-type check must come BEFORE the YET_TO_SEND write, or the branch "
                        + "is too late to prevent the strand",
                reads.get(0).intValue() < writes.get(0).intValue());
        assertTrue("the RCS branch must be taken BEFORE the YET_TO_SEND write too",
                branch.get(0).intValue() < writes.get(0).intValue());

        // The RCS branch itself must not strand the row.
        final String rcsArm = SourceScan.bodyOf(src, "resendOverRcs");
        assertTrue("resendOverRcs not found in " + RESEND, rcsArm.length() > 200);
        assertEquals("the over-RCS resend must NEVER write BUGLE_STATUS_OUTGOING_YET_TO_SEND. That "
                        + "status is terminal by neglect on a TRANSPORT_RCS row — nothing moves it "
                        + "— so writing it here would restore the exact \"Sending…\" forever trip "
                        + "that was removed, through the control offered to escape it.",
                0, SourceScan.count(rcsArm, "BUGLE_STATUS_OUTGOING_YET_TO_SEND"));
        assertEquals("the over-RCS resend must NEVER hand the row to ProcessPendingMessagesAction. "
                        + "That queue excludes TRANSPORT_RCS by name, so it cannot send this row "
                        + "and scheduling it only looks like progress.",
                0, SourceScan.count(rcsArm, "ProcessPendingMessagesAction"));
        assertEquals("the over-RCS resend must reach MlsProviderTransport.resendByUser — the SAME "
                        + "machinery a peer-reported resend uses. A second implementation of "
                        + "\"resend this message\" is a maintenance cost and an interop risk, and "
                        + "it would have to re-derive invariant 62 and the §10.3 ledger.",
                1, SourceScan.count(rcsArm, "resendByUser("));
        assertTrue("the over-RCS resend must record its observed outcome: the send is synchronous, "
                        + "so reaching the row write IS the measurement and there is no callback "
                        + "coming to fix it up later",
                SourceScan.count(rcsArm, "bugleStatusForMeasuredHandoff(") >= 1
                        && SourceScan.count(rcsArm, "rcsStatusForMeasuredHandoff(") >= 1);
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
     * One-click resend is offered only on rows the RCS resend can handle: RCS, encrypted (the
     * resend seals and has no plaintext arm) and carrying a wire id (it resolves the body from it).
     */
    @Test
    public void oneClickResendIsWithheldFromRcsRowsThatCannotBeResent() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/datamodel/data/ConversationMessageData.java"));
        final String body = SourceScan.bodyOf(src, "getOneClickResendMessage");
        assertTrue("getOneClickResendMessage not found", body.length() > 20);
        assertEquals("a tap must still be gated on whether this RCS row can actually be resent — "
                        + "an ungated tap fires ResendMessageAction on rows whose over-RCS resend "
                        + "will fail, which looks like it did something and did not",
                1, SourceScan.count(body, "canResendOverRcs()"));

        final String cap = SourceScan.bodyOf(src, "canResendOverRcs");
        assertTrue("canResendOverRcs not found — the gate above now points at nothing, and a "
                        + "predicate that cannot be located is not one that gates",
                cap.length() > 20);
        assertEquals("the capability must require E2EE: MlsProviderTransport.resendByUser seals "
                        + "and has no plaintext arm, so a non-encrypted RCS row finds no MLS group "
                        + "and returns false every time",
                1, SourceScan.count(cap, "isE2eeEncrypted()"));
        assertEquals(
                "the capability must require RCS transport — the SMS path owns everything else",
                1, SourceScan.count(cap, "getIsRcs()"));
        assertTrue("the capability must require a wire id: the resend root-resolves it to recover "
                        + "the body, and the REFUSED rows are landed FAILED with a "
                        + "null rcs_message_id on purpose — they never reached the wire",
                SourceScan.count(cap, "mRcsMessageId") >= 1);
    }
}
