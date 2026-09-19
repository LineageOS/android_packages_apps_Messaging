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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * <b>A LOCATION SHARE and a REACTION must not reach the wire without the MLS gate having answered
 * first</b> — the fourth and fifth siblings of the group-text gate.
 *
 * <p>{@code MlsSendRoutingTest} pins the DECISION and
 * {@code MlsGroupPlaintextRefusalGuardTest} pins that the GROUP TEXT path consults it. This pins the
 * same for the two verbs that were found leaking afterwards, and it is a separate class rather than
 * four more methods there for one reason: that class's falsifier is "run it against the source as
 * it stood before the group gate". These have a different falsifier and a different pair of call
 * sites, and a guard whose failure message names the wrong change sends the next reader to the
 * wrong place.
 *
 * <h2>Why a source scan — the standing caution, paid again</h2>
 *
 * <p>{@link SourceScan}'s javadoc records the cost: a scan encodes a SPELLING, not the property.
 * It is worth paying here for the same reason as next door and the reason is checkable rather than
 * asserted — {@code SendRcsLocationAction} needs a {@code messaging.db}, a bound provider and a
 * {@code Context}; {@code ConversationFragment} needs all of that plus an {@code Activity}. Neither
 * has a host test and neither can have one. Every assertion below keys on an INVOKED METHOD NAME and
 * counts its hits, so a pattern that has gone stale FAILS rather than passing on nothing.
 *
 * <h2>The falsifier — measured, and it is EIGHT of ten, not ten</h2>
 *
 * <p>Both files were materialised from {@code git show HEAD:} into a shadow tree and this class was
 * run against them before landing. Result: <b>8 failures out of 10</b>. Pre-change, {@code
 * SendRcsLocationAction.executeAction} called {@code transport.sendLocation} with no verdict of any
 * kind on the path and returned {@code null} on every failure, and {@code
 * ConversationFragment.onReactionSelected} called {@code ProviderTransport.sendReaction} on a bare
 * thread with nothing between the optimistic chip and the binder call.
 *
 * <p><b>The two that stayed GREEN are named here rather than counted as evidence</b>, because a
 * check that could not have failed is not one — {@link #noSecondLocationSendCanBypassTheGate} and
 * {@link #noSecondReactionSendCanBypassTheGate}. Each file already had exactly one send call before
 * this change, so neither could ever have detected THIS defect. They are not falsifiers for it and
 * are not offered as such. What they detect is the NEXT one, and that state is reachable: a second
 * call added after the gate — a retry, a fallback, an "MMS instead" arm — is how a refusal becomes a
 * downgrade on a different route, which is the exact shape the group gate had to forbid at its own
 * call site.
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
     * Either verdict entry point counts. The two legs are separate methods on the transport
     * ({@code groupSendVerdict} / {@code oneToOneSendVerdict}) and both defects are on BOTH legs, so
     * a guard that named only one would go green on a fix that covered only one.
     */
    private static int firstVerdictCall(final String b) {
        final int group = b.indexOf("groupSendVerdict(");
        final int one = b.indexOf("oneToOneSendVerdict(");
        if (group < 0) return one;
        if (one < 0) return group;
        return Math.min(group, one);
    }

    // ------------------------------------------------------------------------ the location share

    /**
     * THE DEFECT, stated as an ordering: the gate is consulted BEFORE the send, not merely somewhere
     * in the method. A verdict read after the location has gone is a log line.
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

    /**
     * <b>BOTH LEGS.</b> A padlocked 1:1 leaks exactly as a padlocked group
     * does — {@code isGroup} selects the destination and nothing else — so a fix that gated only the
     * group leg would leave the 1:1 share in the clear and look finished.
     */
    @Test
    public void theLocationGateCoversTheOneToOneLegAsWellAsTheGroup() throws IOException {
        final String b = body(location(), "executeAction");
        assertTrue("the GROUP leg must be gated", b.contains("groupSendVerdict("));
        assertTrue("the 1:1 leg must be gated too — SendRcsLocationAction sets isGroup from "
                + "rcs_group_id and sends to a peer otherwise; both reach the same unsealed "
                + "transport path", b.contains("oneToOneSendVerdict("));
    }

    /**
     * <b>The allow-list, not a deny-list.</b> Only {@code PLAINTEXT} may send. There is no location
     * seal path, so {@code SEAL} is not an instruction this caller can carry out and must not be
     * named as one; naming {@code REFUSE} would invert the default and hand the next verdict added
     * to the enum "send it in the clear", which is how this family of defects existed at all.
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
     * <b>The refusal is not spelled as SILENCE.</b> That is this verb's trap and it is the opposite
     * of the media one: there the danger was falling through to MMS, here {@code executeAction}
     * writes no row on any failure path, so a bare {@code return null} would leave the user with the
     * caller's optimistic "Sharing location…" toast and no way to tell a refusal from a slow send.
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

    /**
     * A refusal must not leave a second way out: exactly ONE {@code sendLocation} in the method, so
     * no branch can reach the wire after the gate has said no.
     *
     * <p><b>This one is GREEN against pre-change source and is not evidence for the fix</b> — the
     * method only ever had one send. The state that makes it FAIL is a SECOND {@code sendLocation}
     * appearing after the gate: a retry arm, a fallback, an "unsealed after all" branch. That is the
     * shape a downgrade takes once a refusal exists, and it is why the assertion is worth keeping
     * even though it could not have caught the defect it ships with.
     */
    @Test
    public void noSecondLocationSendCanBypassTheGate() throws IOException {
        final String b = body(location(), "executeAction");
        assertEquals("exactly one location send in this method — a second one is the downgrade this "
                + "guard exists to remove", 1, SourceScan.count(b, "sendLocation("));
    }

    // ---------------------------------------------------------------------------- the reaction

    /**
     * Same ordering property on the reaction verb. The gate runs inside the send thread rather than
     * at the tap, because it reads {@code messaging.db} and the engine and {@code onReactionSelected}
     * is called on the main thread.
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

    /** Both legs again — {@code groupId} selects the destination and nothing else. */
    @Test
    public void theReactionGateCoversTheOneToOneLegAsWellAsTheGroup() throws IOException {
        final String b = body(fragment(), "onReactionSelected");
        assertTrue("the GROUP leg must be gated", b.contains("groupSendVerdict("));
        assertTrue("the 1:1 leg must be gated too", b.contains("oneToOneSendVerdict("));
    }

    /** The same allow-list. A reaction has no seal path either. */
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
     * <b>THE OPTIMISTIC CHIP MUST BE WITHDRAWN.</b> This is the reaction's own trap and the reason
     * its refusal could not reuse the location one: there is no message row to mark FAILED, only a
     * chip {@code UpdateRcsReactionAction.recordSelfReaction} has ALREADY written so it appears
     * before the binder call returns. Leaving it there tells the user their reaction landed when it
     * was withheld — the same lie as the plaintext send, one layer up.
     *
     * <p>Pinned as a COUNT, so the optimistic write alone cannot satisfy it: the method must call
     * {@code recordSelfReaction} on the tap, and the refusal must call it again to undo that.
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

    /**
     * One send, as on the location path: no branch may reach {@code sendReaction} after the gate has
     * said no.
     *
     * <p><b>Also GREEN against pre-change source, for the same reason and with the same caveat</b> —
     * see {@link #noSecondLocationSendCanBypassTheGate}. It fails when a second {@code sendReaction}
     * is added after the gate, not when the gate is missing.
     */
    @Test
    public void noSecondReactionSendCanBypassTheGate() throws IOException {
        final String b = body(fragment(), "onReactionSelected");
        assertEquals("exactly one reaction send in this method", 1,
                SourceScan.count(b, "sendReaction("));
    }
}
