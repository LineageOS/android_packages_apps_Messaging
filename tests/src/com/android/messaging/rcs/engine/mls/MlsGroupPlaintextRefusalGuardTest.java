/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.MlsSendRouting;

import java.io.IOException;

import org.junit.Test;

/**
 * A UI-composed group text does not reach {@code sendGroupMessage} before the MLS gate answers.
 * {@link MlsSendRoutingTest} pins the decision; {@code MlsMediaPlaintextRefusalGuardTest} the media
 * paths. See docs/rcs/groups.md.
 */
public class MlsGroupPlaintextRefusalGuardTest {

    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";

    private static String action() throws IOException {
        return SourceScan.codeOnly(SourceScan.read(ACTION));
    }

    private static String transport() throws IOException {
        return SourceScan.transport();
    }

    private static String body(final String src, final String method) {
        final String b = SourceScan.bodyOf(src, method);
        assertTrue("could not find the body of " + method + " — this guard reads nothing and would "
                + "otherwise pass on an empty string", b.length() > 0);
        return b;
    }

    /**
     * The gate is consulted before the plaintext send; a verdict read after the send is a log line.
     */
    @Test
    public void theGroupTextPathAsksTheMlsGateBeforeItSendsPlaintext() throws IOException {
        final String b = body(action(), "tryInsertSendingRcsGroupMessage");

        final int verdict = b.indexOf("groupSendVerdict(");
        assertTrue("tryInsertSendingRcsGroupMessage must consult MlsProviderTransport."
                + "groupSendVerdict — without it every group text goes out as text/plain, on "
                + "MLS groups too, with the padlock lit", verdict >= 0);

        final int send = b.indexOf("sendGroupMessage(");
        assertTrue("the plaintext group send must still be here — a guard that passes because the "
                + "method was deleted is not a guard", send >= 0);

        assertTrue("the verdict must be taken BEFORE the send (gate at " + verdict + ", send at "
                + send + ")", verdict < send);
    }

    /**
     * A refusal is not spelled {@code return false}: the caller falls through to group MMS on
     * {@code false}, which would put the same cleartext on another transport.
     */
    @Test
    public void theRefusalDoesNotFallThroughToMms() throws IOException {
        final String src = action();
        final String caller = body(src, "tryInsertSendingRcsGroupMessage");
        assertTrue("the refusal branch must hand off to insertRefusedRcsGroupMessage",
                caller.contains("insertRefusedRcsGroupMessage("));

        final String refusal = body(src, "insertRefusedRcsGroupMessage");
        assertEquals("insertRefusedRcsGroupMessage must never return false: false is the caller's "
                + "signal to send the same text over MMS", 0,
                SourceScan.count(refusal, "return false"));
        assertTrue("it must return true so the caller neither sends nor falls through",
                SourceScan.count(refusal, "return true") >= 1);
    }

    /**
     * The refused row lands failed: {@code ProcessPendingMessagesAction} excludes RCS rows from the
     * SMS/MMS queue, so one left in {@code OUTGOING_YET_TO_SEND} shows "Sending…" forever.
     */
    @Test
    public void theRefusedMessageIsMarkedFailedRatherThanLeftSending() throws IOException {
        final String refusal = body(action(), "insertRefusedRcsGroupMessage");
        final int sending = refusal.indexOf("updateSendingMessage(");
        final int failed = refusal.indexOf("markMessageFailed(");
        assertTrue("the row must be inserted", sending >= 0);
        assertTrue("the row must be marked FAILED — OUTGOING_YET_TO_SEND on an RCS row is a "
                + "permanent 'Sending…'", failed >= 0);
        assertTrue("markMessageFailed must come after updateSendingMessage, which sets "
                + "OUTGOING_YET_TO_SEND and would otherwise overwrite it", sending < failed);
        assertTrue("the row must carry the RCS transport type, which is what keeps the SMS/MMS "
                + "queue from touching it", refusal.contains("rcsMetaValues("));
    }

    /**
     * The latch is read before the engine is asked: the engine call is wrapped in a catch, and a
     * catch that had to invent a latch value could turn a padlocked conversation into a plaintext
     * send.
     */
    @Test
    public void theVerdictReadsTheAppLatchBeforeItAsksTheEngine() throws IOException {
        final String b = body(transport(), "groupSendVerdict");
        final int latch = b.indexOf("readMlsLatch(");
        final int engine = b.indexOf("sealCapability(");
        assertTrue("groupSendVerdict must read the app's MLS latch", latch >= 0);
        assertTrue("groupSendVerdict must ask the engine what it can seal", engine >= 0);
        assertTrue("the latch read must not depend on the engine call surviving", latch < engine);
        assertTrue("the two must be composed by the host-tested table, not re-derived here",
                b.contains("MlsSendRouting.decide("));
    }

    /**
     * {@code encryption_protocol} is a bitset and the padlock is drawn on {@code != 0}, so {@code
     * isE2eeEncrypted()} is also true for the provider-encrypted scheme, which encrypts the very
     * plaintext the group path hands down. Only {@code mlsBit()} is read.
     */
    @Test
    public void theLatchReadsTheMlsBitAloneAndNotThePadlocksOwnPredicate() throws IOException {
        final String b = body(transport(), "readMlsLatch");
        assertTrue("the MLS bit is the input", b.contains("mlsBit()"));
        assertEquals("isE2eeEncrypted() folds Etouffee in and would refuse a provider-encrypted "
                + "group", 0, SourceScan.count(b, "isE2eeEncrypted"));
        assertTrue("loadOrNull, not load: a failed read is not evidence of plaintext",
                b.contains("loadOrNull("));
        assertEquals("load() would flatten an unreadable bit to 'plaintext'", 0,
                SourceScan.count(b, ".load("));
    }

    /**
     * The gate asks the engine what {@code sendFramedToGroup} asks, against the same key, so it
     * never predicts "sealable" for a send that refuses. {@code conversationIsMls} keys through
     * {@code resolveInbound} and is not reused.
     */
    @Test
    public void theGateAsksTheEngineTheSameQuestionsTheSealPathAsks() throws IOException {
        // The unsplit view: sendFramedToGroup is in MlsGroupSend, sealCapability is not.
        final String src = SourceScan.transportUnsplitCode();
        final String gate = body(src, "sealCapability");
        final String seal = body(src, "sendFramedToGroup");

        for (final String call : new String[] {"canonicalKey(", "getGroup(", "hasEndMlsStatus("}) {
            assertTrue("sealCapability must ask " + call, gate.contains(call));
            assertTrue("sendFramedToGroup must still ask " + call + " — if it stops, the gate is "
                    + "predicting a refusal that no longer happens", seal.contains(call));
        }
        assertEquals("sealCapability must derive the key exactly as the send does, not through "
                + "resolveInbound", 0, SourceScan.count(gate, "resolveInbound("));
        assertEquals("the send derives it through canonicalKey too", 0,
                SourceScan.count(seal, "resolveInbound("));
    }

    /**
     * The 1:1 leg: {@code ensureReady} keys on {@code canonicalKey(null, peer)} and keeps the
     * conversation id only as an alias. Key parity is all this checks: {@code ensureReady} can also
     * create a group, so on this leg {@code NO_MLS_STATE} means "not yet", and
     * {@code E2eeSendGate.resolveForSend} decides whether a 1:1 may be sealed.
     */
    @Test
    public void theOneToOneGateDerivesTheKeyTheOneToOneSealPathDoes() throws IOException {
        // The unsplit view: the 5-argument ensureReady is in MlsOneToOneGroup.
        final String src = SourceScan.transportUnsplitCode();

        final String verdict = body(src, "oneToOneSendVerdict");
        assertTrue("the 1:1 verdict must ask the SAME engine predicate the group one asks — a "
                + "second predicate for the second leg is how two answers drift apart, which is the "
                + "whole reason the composition is in one place",
                verdict.contains("sealCapability("));

        // By an argument name: the 3-argument overload is a delegate.
        final String ready =
                SourceScan.bodyOf(src, "ensureReady", "recreateAlreadyCharged");
        assertTrue("could not find the 5-arg ensureReady — this guard would otherwise read nothing "
                + "and fail for the wrong reason", ready.length() > 0);
        assertTrue("ensureReady is the 1:1 seal path's first step and must derive its key through "
                + "canonicalKey — if it stops, sealCapability is answering about a key nothing "
                + "stores a group under, and every padlocked 1:1 reads as NO_MLS_STATE",
                ready.contains("canonicalKey("));
        assertTrue("and it must look the group up under that key, not under the conversation id",
                ready.contains("getGroup("));
        assertEquals("not through resolveInbound, for the same reason the group half does not",
                0, SourceScan.count(ready, "resolveInbound("));
    }

    /**
     * "We hold no state" and "this device has no MLS at all" are kept apart; the log distinguishes
     * them and a cold process once read an MLS group as plaintext when they were merged.
     */
    @Test
    public void theTwoReasonsASessionCanBeAbsentAreDistinguished() throws IOException {
        final String b = body(transport(), "sealCapability");
        assertTrue("it must try to open a session", b.contains("ensureSession()"));
        assertTrue("and separate 'no identity was ever adopted' from 'we could not open one'",
                b.contains("loadIdentity("));
        assertTrue("NO_MLS_IDENTITY must be reachable", b.contains("NO_MLS_IDENTITY"));
        assertTrue("ENGINE_UNAVAILABLE must be reachable", b.contains("ENGINE_UNAVAILABLE"));
    }

    /**
     * Every declared {@code SealCapability} is produced somewhere, or its table row tests nothing.
     */
    @Test
    public void everyDeclaredSealCapabilityIsActuallyReachable() throws IOException {
        final String src = transport();
        int produced = 0;
        for (final MlsSendRouting.SealCapability s
                : MlsSendRouting.SealCapability.values()) {
            assertTrue(s + " is declared but nothing in MlsProviderTransport returns it",
                    SourceScan.count(src, "SealCapability." + s.name()) > 0);
            produced++;
        }
        assertEquals(MlsSendRouting.SealCapability.values().length, produced);
    }

    /**
     * The call site gates on an allow-list: only {@code SEAL} and {@code PLAINTEXT} may send, and
     * only {@code PLAINTEXT} reaches {@code sendGroupMessage}, so a verdict added later cannot
     * default to sending in the clear.
     */
    @Test
    public void onlyThePlaintextVerdictReachesThePlaintextSend() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsGroupMessage");
        assertTrue("the call site must branch on the verdict type",
                caller.contains("MlsSendRouting.Verdict"));
        assertTrue("the allow-list must name PLAINTEXT", caller.contains("Verdict.PLAINTEXT"));
        assertTrue("and SEAL, which is the other verdict permitted to send",
                caller.contains("Verdict.SEAL"));
        assertFalse(
                "naming REFUSE here would make the gate a deny-list, and the next verdict added "
                + "would default to sending in the clear", caller.contains("Verdict.REFUSE"));
    }

    /**
     * The 2-argument {@code sendToGroup} synthesises an id that matches no chat row and is not
     * cached, so no IMDN correlates and replay protection is off; the seal arm must pass the row's
     * own id.
     */
    @Test
    public void theSealArmPassesTheRowsOwnMessageIdAndNotASynthesisedOne() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsGroupMessage");
        final int at = caller.indexOf("sendToGroup(");
        assertTrue("the SEAL arm must route to MlsProviderTransport.sendToGroup", at >= 0);

        final int open = caller.indexOf('(', at);
        int depth = 0, close = -1;
        for (int i = open; i < caller.length(); i++) {
            if (caller.charAt(i) == '(') depth++;
            else if (caller.charAt(i) == ')' && --depth == 0) { close = i; break; }
        }
        assertTrue("unbalanced call", close > open);
        final String args = caller.substring(open + 1, close);
        assertTrue("sendToGroup must be handed the row's own rcs_message_id — the 2-arg overload "
                + "synthesises an id that correlates to nothing and is not replay-protected: "
                + args, args.contains("rcsMessageId"));
    }

    /**
     * A failed seal does not retry the text unsealed: the engine may already have consumed a
     * generation. Counted: one plaintext send, at least two handoffs to the refusal.
     */
    @Test
    public void aFailedSealDoesNotFallBackToThePlaintextSend() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsGroupMessage");
        assertEquals("exactly one plaintext group send in this method — a second one is the "
                + "downgrade this guard exists to remove", 1,
                SourceScan.count(caller, "sendGroupMessage("));
        assertTrue("a failed seal must reach the refusal, not the plaintext arm",
                SourceScan.count(caller, "insertRefusedRcsGroupMessage(") >= 2);
    }

    /**
     * Both arms are synchronous and no status callback follows, so the outcome is recorded on the
     * row, and a sealed send stamps the scheme for the padlock.
     */
    @Test
    public void theMeasuredOutcomeIsRecordedOnTheRowRatherThanDiscarded() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsGroupMessage");
        assertTrue("message_status must carry the measurement",
                caller.contains("bugleStatusForMeasuredHandoff("));
        assertTrue("and rcs_status must carry it in the provider's own vocabulary",
                caller.contains("rcsStatusForMeasuredHandoff("));
        assertEquals("RCS_STATUS_NONE means 'no status yet, forever' on a route that will never "
                + "deliver one", 0, SourceScan.count(caller, "RCS_STATUS_NONE"));
        assertTrue("a SEALED send must stamp the scheme so the outgoing bubble carries the "
                + "per-message padlock — its absence was the only thing distinguishing an "
                + "encrypted group send from a plaintext one, and it was unlabelled",
                caller.contains("RCS_E2EE_SCHEME_ID"));
    }
}
