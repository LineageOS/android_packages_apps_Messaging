/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * A location share and a reaction do not reach the wire before the MLS gate has answered, on both
 * the group and the 1:1 leg. {@code MlsSendRoutingTest} pins the decision and
 * {@code MlsGroupPlaintextRefusalGuardTest} the group text path. The two single-send checks cannot
 * detect a missing gate; they fail when a second send is added after it.
 */
public class MlsLocationReactionRefusalGuardTest {

    private static final String LOCATION =
            "src/com/android/messaging/datamodel/action/SendRcsLocationAction.java";
    private static final String FRAGMENT =
            "src/com/android/messaging/ui/conversation/ConversationFragment.java";

    private static String location() throws IOException {
        return SourceScan.codeOnly(SourceScan.read(LOCATION));
    }

    private static String fragment() throws IOException {
        return SourceScan.codeOnly(SourceScan.read(FRAGMENT));
    }

    private static String body(final String src, final String method) {
        final String b = SourceScan.bodyOf(src, method);
        assertTrue("could not find the body of " + method + " — this guard reads nothing and would "
                + "otherwise pass on an empty string", b.length() > 0);
        return b;
    }

    /**
     * The first call to either verdict entry point; both legs are separate methods, and both are
     * gated.
     */
    private static int firstVerdictCall(final String b) {
        final int group = b.indexOf("groupSendVerdict(");
        final int one = b.indexOf("oneToOneSendVerdict(");
        if (group < 0) return one;
        if (one < 0) return group;
        return Math.min(group, one);
    }

    /**
     * The gate is consulted before the send; a verdict read after the location has gone is a log
     * line.
     */
    @Test
    public void theLocationShareAsksTheMlsGateBeforeItSendsPlaintext() throws IOException {
        final String b = body(location(), "executeAction");

        final int verdict = firstVerdictCall(b);
        assertTrue("SendRcsLocationAction.executeAction must consult MlsProviderTransport's send "
                + "verdict — without it a location share goes out as rcspushlocation+xml in the "
                + "clear, on MLS conversations too, with the padlock lit", verdict >= 0);

        final int send = b.indexOf("sendLocation(");
        assertTrue("the plaintext send must still be here — a guard that passes because the call "
                + "was deleted is not a guard", send >= 0);
        assertTrue("the verdict must be taken BEFORE the send (gate at " + verdict + ", send at "
                + send + ")", verdict < send);
    }

    /** Both legs: {@code isGroup} selects the destination and nothing else. */
    @Test
    public void theLocationGateCoversTheOneToOneLegAsWellAsTheGroup() throws IOException {
        final String b = body(location(), "executeAction");
        assertTrue("the GROUP leg must be gated", b.contains("groupSendVerdict("));
        assertTrue("the 1:1 leg must be gated too — SendRcsLocationAction sets isGroup from "
                + "rcs_group_id and sends to a peer otherwise; both reach the same unsealed "
                + "transport path", b.contains("oneToOneSendVerdict("));
    }

    /**
     * An allow-list: only {@code PLAINTEXT} may send. There is no location seal path, so {@code
     * SEAL} must not be read as permission, and naming {@code REFUSE} would make a new verdict
     * default to sending in the clear.
     */
    @Test
    public void onlyThePlaintextVerdictReachesTheLocationSend() throws IOException {
        final String b = body(location(), "executeAction");
        assertTrue("the call site must branch on the verdict type",
                b.contains("MlsSendRouting.Verdict"));
        assertTrue("the allow-list must name PLAINTEXT", b.contains("Verdict.PLAINTEXT"));
        assertFalse("naming REFUSE here makes the gate a deny-list", b.contains("Verdict.REFUSE"));
        assertFalse("SEAL must NOT be an allowed outcome on this path: there is no location seal, "
                + "so treating it as permission to proceed sends the coordinates in the clear",
                b.contains("Verdict.SEAL"));
    }

    /**
     * The refusal lands a failed row: {@code executeAction} writes no row on any failure path, so a
     * bare {@code return null} would leave only the optimistic "Sharing location…" toast.
     */
    @Test
    public void theRefusedLocationLandsAVisibleRowRatherThanVanishing() throws IOException {
        final String src = location();
        assertTrue("the refusal branch must hand off to insertRefusedLocationRow",
                body(src, "executeAction").contains("insertRefusedLocationRow("));

        final String refusal = body(src, "insertRefusedLocationRow");
        final int sending = refusal.indexOf("updateSendingMessage(");
        final int failed = refusal.indexOf("markMessageFailed(");
        assertTrue("the row must be inserted", sending >= 0);
        assertTrue("the row must be marked FAILED — OUTGOING_YET_TO_SEND on an RCS row is a "
                + "permanent \"Sending…\", because ProcessPendingMessagesAction excludes "
                + "TRANSPORT_RCS from the send queue by name", failed >= 0);
        assertTrue("markMessageFailed must come after updateSendingMessage, which sets "
                + "OUTGOING_YET_TO_SEND and would otherwise overwrite it", sending < failed);
        assertTrue("the row must carry the RCS transport type, which is what keeps the SMS/MMS "
                + "queue from touching it", refusal.contains("rcsMetaValues("));
        assertTrue("and the thread must be told to redraw, or the row is terminal and invisible",
                refusal.contains("notifyMessagesChanged("));
    }

    /** Exactly one {@code sendLocation}, so no branch reaches the wire after the gate refuses. */
    @Test
    public void noSecondLocationSendCanBypassTheGate() throws IOException {
        final String b = body(location(), "executeAction");
        assertEquals(
                "exactly one location send in this method — a second one is the downgrade this "
                + "guard exists to remove", 1, SourceScan.count(b, "sendLocation("));
    }

    /**
     * The same ordering for reactions. The gate runs inside the send thread, because it reads the
     * database and the engine and {@code onReactionSelected} runs on the main thread.
     */
    @Test
    public void theReactionAsksTheMlsGateBeforeItSendsPlaintext() throws IOException {
        final String b = body(fragment(), "onReactionSelected");

        final int verdict = firstVerdictCall(b);
        assertTrue("onReactionSelected must consult MlsProviderTransport's send verdict — without "
                + "it the emoji goes out as text/plain and the reactions-ns header carries the "
                + "TARGET MESSAGE ID in the clear, correlating a sealed message",
                verdict >= 0);

        final int send = b.indexOf("sendReaction(");
        assertTrue("the plaintext send must still be here", send >= 0);
        assertTrue("the verdict must be taken BEFORE the send (gate at " + verdict + ", send at "
                + send + ")", verdict < send);
    }

    /** Both legs: {@code groupId} selects the destination and nothing else. */
    @Test
    public void theReactionGateCoversTheOneToOneLegAsWellAsTheGroup() throws IOException {
        final String b = body(fragment(), "onReactionSelected");
        assertTrue("the GROUP leg must be gated", b.contains("groupSendVerdict("));
        assertTrue("the 1:1 leg must be gated too", b.contains("oneToOneSendVerdict("));
    }

    /** The same allow-list; a reaction has no seal path either. */
    @Test
    public void onlyThePlaintextVerdictReachesTheReactionSend() throws IOException {
        final String b = body(fragment(), "onReactionSelected");
        assertTrue("the call site must branch on the verdict type",
                b.contains("MlsSendRouting.Verdict"));
        assertTrue("the allow-list must name PLAINTEXT", b.contains("Verdict.PLAINTEXT"));
        assertFalse("naming REFUSE here makes the gate a deny-list", b.contains("Verdict.REFUSE"));
        assertFalse("SEAL must NOT be an allowed outcome: there is no reaction seal",
                b.contains("Verdict.SEAL"));
    }

    /**
     * A refused reaction withdraws the optimistic chip {@code recordSelfReaction} already wrote, by
     * calling it again with the inverse flag, and tells the user. Counted, so the optimistic write
     * alone cannot satisfy it.
     */
    @Test
    public void aRefusedReactionWithdrawsTheOptimisticChip() throws IOException {
        final String src = fragment();
        final String b = body(src, "onReactionSelected");
        assertTrue("the optimistic chip must still be written on the tap — a guard that passes "
                + "because the instant-feedback write was deleted is not a guard",
                b.contains("recordSelfReaction("));
        assertTrue("the refusal branch must hand off to withdrawRefusedReaction",
                b.contains("withdrawRefusedReaction("));

        final String undo = body(src, "withdrawRefusedReaction");
        assertEquals("the withdrawal must rewrite the chip exactly once", 1,
                SourceScan.count(undo, "recordSelfReaction("));
        assertTrue("it must pass the INVERSE of the user's flag: UpdateRcsReactionAction branches "
                + "on that flag alone, so !add removes a refused ADD and restores a refused REMOVE "
                + "— and a refused REMOVE matters, because that retraction did not reach the peer "
                + "either", undo.contains("!add"));
        assertTrue("the user must be told, or a chip that silently vanishes is indistinguishable "
                + "from a UI glitch", undo.contains("showToast("));
        assertTrue("the toast must be posted to the main thread — this runs on a bare Thread with "
                + "no Looper and Toast.makeText throws there",
                undo.contains("getMainThreadHandler("));
    }

    /** Exactly one {@code sendReaction}, as on the location path. */
    @Test
    public void noSecondReactionSendCanBypassTheGate() throws IOException {
        final String b = body(fragment(), "onReactionSelected");
        assertEquals("exactly one reaction send in this method", 1,
                SourceScan.count(b, "sendReaction("));
    }
}
