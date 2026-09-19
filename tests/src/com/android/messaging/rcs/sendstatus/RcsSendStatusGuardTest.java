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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * The four production sites this turns on, asserted against the source.
 *
 * <p><b>Why a source scan here.</b> Every one of these four lives in an {@code Action} or a
 * {@code Fragment}: they need a {@code DatabaseWrapper}, a bound provider, a {@code Factory} and a
 * {@code Context}, so none of them has a host test and none can be given one without standing up
 * most of the app. The decision itself was extracted into {@link RcsSendStatus} precisely so the part
 * that CAN be tested is (see {@link RcsSendStatusTest}); these guards are the other half — they
 * check that production still consults it, which is the thing a pure-function test structurally
 * cannot see. Two rules apply and are met: key on the INVOKED METHOD NAME rather
 * than on a receiver, a variable or a log label, and <b>make zero hits FAIL</b> — every assertion
 * below counts.
 *
 * <p><b>The state that would make each one fail, and that is reachable:</b> the tree as it stood
 * before the fix. The first three guards had zero hits then; the fourth passed then and is here
 * for the opposite reason — it pins the premise the other three are built on, so a future change
 * that invalidates the premise cannot land quietly.
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
     * <b>EVERY outgoing-RCS row-writer must either RECORD its measured outcome or be fed by a verb
     * that fires a status callback</b>, and this is the one assertion here
     * that predicts the NEXT verb instead of listing the current ones.
     *
     * <h2>The rule, and why it is a LAYER rule</h2>
     *
     * <p>{@code updateSendingMessage} leaves a row at {@code BUGLE_STATUS_OUTGOING_YET_TO_SEND},
     * which on a {@code TRANSPORT_RCS} row is terminal by neglect: the SMS queue excludes
     * TRANSPORT_RCS by name, {@code ResendMessageAction} refuses it, and the startup fixup rewrites
     * only 5/6. So a row left there reads "Sending…" for ever.
     *
     * <p>Whether that is a defect depends on ONE thing — which layer
     * {@code RcsProviderService} routes the verb through. A verb it sends via
     * {@code TachyonTransport} fires a {@code ProviderSink} and an async
     * {@code onMessageStatus} will move the row, so YET_TO_SEND is correct there. A verb it routes
     * STATICALLY to {@code TachyonRegistrar} fires nothing, there is no async half, and the
     * synchronous answer the caller already holds is the only one there will ever be.
     *
     * <p>Measured 2026-09-13, over all nine sites that insert an outgoing RCS row: eight were
     * correct and {@code SendRcsLocationAction} was not — every SUCCESSFUL location share read
     * "Sending…" for ever. Earlier fixes covered the 1:1 text arm and the group
     * text arms, and neither reached this verb.
     *
     * <h2>Why an allow-list, which is normally the wrong shape</h2>
     *
     * <p>Because it FAILS CLOSED on the thing that actually goes wrong. The two exempt sites are
     * exempt for a reason a scanner cannot see from this repo — their verb fires a sink, which is a
     * fact about {@code RcsProviderService} in the sibling repo. Naming them here means a NEW
     * row-writer is caught by default and its author has to state which side of the layer rule it
     * falls on, rather than inheriting silence. That is the whole property: this guard is about the
     * verb nobody has written yet.
     *
     * <p><b>Do not add to the exempt list to make a red go away.</b> Check
     * {@code RcsProviderService} first: if the verb reaches {@code TachyonRegistrar} without going
     * through {@code transport()}, the row-writer is wrong and the exemption would be a lie.
     */
    @Test
    public void everyOutgoingRcsRowWriterRecordsOrIsFedByACallback() throws IOException {
        // Sites whose verb DOES fire a ProviderSink, so an async onMessageStatus moves the row.
        // Both go out through RcsProviderService.sendFile -> TachyonTransport.sendFile.
        final List<String> sinkFed = java.util.Arrays.asList(
                "tryInsertSendingRcsMessage", "tryInsertSendingRcsFile",
                "tryInsertSendingRcsGroupMessage", "tryInsertSendingRcsGroupFile");

        final String insert = SourceScan.codeOnly(SourceScan.read(INSERT));
        final String location = SourceScan.codeOnly(SourceScan.read(LOCATION));

        // (file, method) pairs that write an outgoing RCS row with a REAL rcs id. The refusal
        // writers are excluded by construction, not by name: they pass a null id and call
        // markMessageFailed, so they never reach YET_TO_SEND terminally.
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
            // Only sites that actually mint and store a wire id are in scope; a body that does not
            // is not a row-writer of the kind this rule is about, and asserting over it would be
            // asserting over nothing.
            if (SourceScan.count(body, "rcsMetaValues(") < 1) continue;
            checked++;
            final boolean records =
                    SourceScan.count(body, "bugleStatusForMeasuredHandoff(") >= 1
                            && SourceScan.count(body, "rcsStatusForMeasuredHandoff(") >= 1;
            if (records || sinkFed.contains(w[1])) continue;
            org.junit.Assert.fail(w[1] + " in " + w[0] + " inserts an outgoing RCS row and neither "
                    + "records a measured outcome nor is fed by a status callback. Left at "
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
     * The cold-start backstop for every OTHER RCS path — including a provider status callback lost
     * to a process death, and every row already stranded on a device that has been running the
     * broken build.
     *
     * <p>Keyed on the two stranding statuses AND on the transport-type narrowing: a sweep of 4/7
     * that forgot the {@code TRANSPORT_RCS} clause would fail SMS rows the send queue is still
     * legitimately holding, which is a worse defect than the one being fixed.
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
     * <b>An RCS row must be routed to the RCS resend and never into the SMS send queue.</b>
     * The first fix put a REFUSAL here, and a later one replaced it with a real resend. This
     * guard was rewritten rather than deleted, because the property it protects did not change:
     * <b>the row must never reach the write that parks it at {@code OUTGOING_YET_TO_SEND}.</b>
     * Only the arm taken instead changed, from "return" to "resend over RCS".
     *
     * <p>Order still matters and is still asserted: the transport-type read has to happen BEFORE
     * that write, or the branch arrives after the damage.
     *
     * <p>The second half is the one the real resend makes necessary. A refusal could not
     * accidentally strand a row; a resend can, if it is ever written to go through
     * {@code ProcessPendingMessagesAction} "like the SMS path does". So the RCS branch's own body
     * is asserted to contain NEITHER the stranding status NOR that queue — which is the
     * constraint, stated as a property of the code rather than as a hope about the author.
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
        // The RCS arm must RETURN rather than fall through. There is no over-RCS resend on this
        // route, so the honest outcome is to leave the row FAILED with "Send as SMS" still
        // offered -- what must never happen is reaching the SMS queue below, which excludes
        // TRANSPORT_RCS and therefore parks the row at "Sending..." and never sends it.
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

        // No assertion here about ProcessPendingMessagesAction. The SMS path BELOW this branch
        // uses it legitimately, and a scan from the branch onwards reaches that path -- it
        // measured 2 and would have been a guard that fails on correct code. What actually
        // protects the row is the ordering asserted above: the transport-type check and the
        // return both precede the YET_TO_SEND write, so an RCS row never reaches the queue.
    }

    /**
     * THE PREMISE, pinned. Everything above rests on one fact: the SMS/MMS send queue refuses to
     * carry {@code TRANSPORT_RCS} rows, so a status only that queue services is terminal-by-neglect
     * on an RCS row.
     *
     * <p>This guard passed before the fix too, deliberately. If someone ever teaches
     * {@code findNextMessageToSend} to take RCS rows, the refusal in {@code ResendMessageAction} and
     * the sweep in {@code FixupMessageStatusOnStartupAction} both become WRONG — they would be
     * refusing and failing rows that a queue is now willing to send. Read on the RAW source rather
     * than {@code codeOnly}: the exclusion lives in the SQL string {@code " !=? "}, and
     * {@code codeOnly} blanks string contents, so the operator is invisible there.
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
        // The two statuses that query selects on are exactly RcsSendStatus.strandedOnRcsTransport's
        // domain. If a third is ever added there, this predicate is incomplete.
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_YET_TO_SEND));
        assertTrue(SourceScan.count(body, "BUGLE_STATUS_OUTGOING_YET_TO_SEND") >= 1);
        assertTrue(RcsSendStatus.strandedOnRcsTransport(
                RcsSendStatus.BUGLE_STATUS_OUTGOING_AWAITING_RETRY));
        assertTrue(SourceScan.count(body, "BUGLE_STATUS_OUTGOING_AWAITING_RETRY") >= 1);
    }

    /**
     * <b>The UI half: a tap must not fire a resend that cannot work</b> — and the set of rows for
     * which that is true SHRANK when the real resend landed, so this guard was rewritten rather
     * than deleted.
     *
     * <p>The first fix withheld the one-click resend from EVERY RCS row, because
     * {@code ResendMessageAction} refused them all. With a real over-RCS resend in place that
     * blanket gate would have been a control withheld for a reason that no longer existed — and a
     * gate whose stated reason has evaporated becomes a fact by attrition. So the predicate is now
     * a positive capability test, and this guard asserts the capability rather than the refusal.
     *
     * <p>The three conditions are each asserted by NAME, because each excludes a row for which the
     * button would do nothing: not encrypted (the resend seals and has no plaintext arm), no wire
     * id (a refused row that never reached the wire has no body to
     * root-resolve), and not RCS at all. Dropping any one of them silently re-offers a button that
     * always fails, which is the defect that was removed.
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
        assertEquals("the capability must require RCS transport — the SMS path owns everything else",
                1, SourceScan.count(cap, "getIsRcs()"));
        assertTrue("the capability must require a wire id: the resend root-resolves it to recover "
                        + "the body, and the REFUSED rows are landed FAILED with a "
                        + "null rcs_message_id on purpose — they never reached the wire",
                SourceScan.count(cap, "mRcsMessageId") >= 1);
    }
}
