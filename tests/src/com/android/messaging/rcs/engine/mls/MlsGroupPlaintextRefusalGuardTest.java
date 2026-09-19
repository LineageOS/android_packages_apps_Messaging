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

import com.android.messaging.rcs.e2ee.MlsSendRouting;

import java.io.IOException;

import org.junit.Test;

/**
 * <b>A UI-composed GROUP text must not reach {@code sendGroupMessage} without the MLS gate having
 * answered first</b>.
 *
 * <p>{@link MlsSendRoutingTest} pins the DECISION; this pins that the decision is CONSULTED,
 * and by the one call site that had the defect. The two are separate for the reason
 * {@code MlsResendReceive} was extracted: a correct policy nothing calls is the shape the defect
 * already had — {@code MlsProviderTransport.sendToGroup} has existed and sealed correctly the whole
 * time, and its only caller in the tree is a debug broadcast.
 *
 * <h2>Why a source scan</h2>
 *
 * <p>The standing caution on {@link SourceScan} applies and is paid for the usual reason:
 * {@code InsertNewMessageAction} and {@code MlsProviderTransport} both need a {@code Context}, a
 * bound provider and a {@code messaging.db}, so neither has a host test and the property is
 * otherwise unreachable. Every assertion here keys on an INVOKED METHOD NAME, and every one counts
 * its hits so a pattern that has gone stale fails instead of passing on nothing.
 *
 * <h2>The falsifier</h2>
 *
 * <p>Run this against the revision before the gate landed, for either file, and it goes red:
 * {@code tryInsertSendingRcsGroupMessage} called {@code sendGroupMessage} with the raw string and
 * nothing else, and {@code groupSendVerdict} did not exist. Measured before landing, both
 * directions.
 *
 * <p><b>The gate was called {@code groupTextSendVerdict} until the media paths joined it.</b> It never read
 * a body, the MEDIA paths had the identical defect in a worse place, and the media call sites now
 * share this one evaluation — so the word "Text" went. The media half of the property is pinned by
 * {@code MlsMediaPlaintextRefusalGuardTest}; neither guard sees the other's call sites, so finding
 * one is not finding the whole rule.
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

    // ------------------------------------------------------------------ the call site that leaked

    /**
     * THE DEFECT, stated as an ordering: the gate must be consulted BEFORE the plaintext send, not
     * merely somewhere in the method. A verdict read after the message has gone is a log line.
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
     * <b>A refusal must not be spelled {@code return false}.</b> The caller falls through to the
     * unchanged group-MMS path on {@code false}, so that spelling would put the same cleartext on a
     * different transport — a downgrade wearing the shape of a rejection. The refusal goes through
     * {@code insertRefusedRcsGroupMessage}, which returns {@code true} on every path.
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
     * The refused row lands TERMINAL, not as a spinner. An RCS row left in
     * {@code OUTGOING_YET_TO_SEND} is picked up by nothing —
     * {@code ProcessPendingMessagesAction.findNextMessageToSend} excludes {@code TRANSPORT_RCS} from
     * the SMS/MMS queue by design — so it shows "Sending…" forever, which is the stuck-send
     * defect arriving from the other direction.
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

    // ------------------------------------------------------------------ the gate's own two inputs

    /**
     * The latch is read BEFORE the engine is asked, and that ordering is load-bearing: the engine
     * call is wrapped in a catch, and a catch that had to invent a latch value would be the place a
     * padlocked conversation quietly became a plaintext send.
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
     * <b>The Etouffee trap.</b> {@code encryption_protocol} is a bitset and the padlock is drawn on
     * {@code != 0}, so {@code isE2eeEncrypted()} is true for an Etouffee conversation — which the
     * PROVIDER encrypts, from the very plaintext string the group path hands down. Reading the
     * padlock's own predicate here would refuse a working encrypted conversation in the name of
     * encryption. {@code mlsBit()} alone, exactly as the padlock work reads it.
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
     * <b>The gate must ask the engine the same two questions the seal path asks, against the same
     * key.</b> A gate that says SEALABLE where {@code sendFramedToGroup} says "no MLS group for RCS
     * group" is worse than no gate: it would route a message into a send that refuses it.
     *
     * <p>So this pins BOTH sides — the gate's derivation and the send's — and goes red if either
     * moves. {@code conversationIsMls} is deliberately not reused: it resolves through
     * {@code resolveInbound} while the send resolves through {@code canonicalKey}.
     */
    @Test
    public void theGateAsksTheEngineTheSameQuestionsTheSealPathAsks() throws IOException {
        final String src = transport();
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
     * <b>The 1:1 half of the property above, which that method stopped covering without its name
     * changing.</b>
     *
     * <p>{@code sealCapability} was group-only when this class was written. It was later given
     * a {@code peerE164} leg so the media, location and reaction
     * gates could ask it about a 1:1 — and from that moment
     * {@link #theGateAsksTheEngineTheSameQuestionsTheSealPathAsks} pinned the parity of ONE of the
     * two legs while reading as though it pinned the predicate. A guard that silently stops covering
     * half its subject is worse than one that never claimed to, so the second half is asserted here
     * rather than folded in: the two legs have different seal paths and want different failure
     * messages.
     *
     * <h2>The measurement this rests on, because the obvious reading of it is wrong</h2>
     *
     * <p>It is natural to conclude from {@code sendAppOwned}'s ARGUMENTS that the 1:1 seal keys on
     * the app's {@code conversationId} — it passes one — and therefore that {@code "p:" + peerE164}
     * is a different, weaker question. <b>A signature is not a key.</b> {@code ensureReady}, which is
     * that path's first step, derives {@code canonicalKey(null, peer)} and stores the conversation id
     * only as an in-memory ALIAS to it ({@code mConvAlias}), which {@code getGroup} then follows. So
     * {@code "p:" + peer} IS where a live 1:1 group is stored, the gate's key is the seal's key, and
     * the 1:1 answer is real parity rather than an approximation.
     *
     * <p><b>What is still NOT symmetric, and must not be inherited by whoever wires 1:1 SEALING:</b>
     * {@code ensureReady} is not a predicate. It can CREATE a group, charge the era budget and invoke
     * {@code MlsPeerGuard}. So a read-only {@code SEALABLE} is a strict SUBSET of "the seal would
     * succeed", and on the 1:1 leg {@code NO_MLS_STATE} does not mean "cannot seal" — it means "not
     * yet". That is exactly what a refuse-rather-than-degrade gate needs, since it can only ever add
     * refusals to conversations that demonstrably hold state, and it is NOT enough to decide whether
     * a 1:1 may be sealed. {@code E2eeSendGate.resolveForSend} is that predicate.
     *
     * <h2>The falsifier, and it covers ONE of the two assertions rather than both</h2>
     *
     * <p>Against {@code git show 886236cf^:MlsProviderTransport.java} — the last revision before the
     * 1:1 verdict existed — this method goes RED, measured in a shadow tree. That exercises the
     * FIRST assertion: {@code oneToOneSendVerdict} is absent, so the body read is empty.
     *
     * <p><b>The {@code ensureReady} half is NOT exercised by that rollback and is not claimed to
     * be</b>, because {@code canonicalKey} has been its keying since long before either change —
     * rolling back cannot make it fail. Its failure state is forward-looking and reachable: someone
     * re-keys the 1:1 seal on the conversation id, which is exactly the change we
     * warn against by name and exactly the change that would make every padlocked 1:1 read as
     * {@code NO_MLS_STATE} through a gate that still looked correct. That is what it is here to
     * catch, and saying so is the difference between a guard and a green tick.
     *
     * <p><b>THIS GUARD PASSING FOR BOTH LEGS DOES NOT MAKE THE LEGS EQUIVALENT, and its green is
     * exactly what will suggest otherwise.</b> Key parity is all it checks. The two seal paths
     * differ in kind underneath it: the group leg's {@code sendFramedToGroup} is a pure state read,
     * so {@code SEALABLE} genuinely predicts what the send will do; the 1:1 leg's
     * {@code ensureReady} can ESTABLISH a group, charge the era budget and invoke
     * {@code MlsPeerGuard}, so a read-only {@code SEALABLE} is a strict SUBSET of what that send
     * would do and {@code NO_MLS_STATE} does <b>not</b> mean "cannot seal" there.
     *
     * <p>Each leg is correct against its own send, which is what this asserts and all it asserts.
     * Whoever wires 1:1 sealing must decide that row deliberately rather than inherit it from the
     * group table. It is stated here rather than only in
     * {@code oneToOneSendVerdict}'s javadoc because the misleading signal is produced HERE: a
     * reader arrives at a passing parity guard, not at the method it is about.
     */
    @Test
    public void theOneToOneGateDerivesTheKeyTheOneToOneSealPathDoes() throws IOException {
        final String src = transport();

        final String verdict = body(src, "oneToOneSendVerdict");
        assertTrue("the 1:1 verdict must ask the SAME engine predicate the group one asks — a "
                + "second predicate for the second leg is how two answers drift apart, which is the "
                + "whole reason the composition is in one place",
                verdict.contains("sealCapability("));

        // The 5-arg ensureReady, selected by an argument name in its DECLARATION. bodyOf on the
        // name alone finds the 3-arg public overload first, whose body is a single delegating
        // return — it would report canonicalKey missing from a method that never had it.
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
     * "We hold no state" and "this device has no MLS at all" are different facts and are kept apart,
     * the separation {@code changeGroupMembership} had to make after a cold process read an MLS
     * group as plaintext. Only the log differs today — both refuse under a latched bit — but the
     * distinction is what the log is FOR, and collapsing it is how the cold-process bug returns.
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
     * Every {@code SealCapability} the enum declares must be produced somewhere in the transport. A
     * state nothing can return is a row of {@link MlsSendRoutingTest}'s table that tests
     * nothing — the "a check that cannot fail is not evidence" rule applied to the inputs rather
     * than to the check.
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
     * <b>The call site gates on an ALLOW-LIST, not a deny-list.</b> Only {@code SEAL} and
     * {@code PLAINTEXT} may send at all, and only {@code PLAINTEXT} may reach
     * {@code sendGroupMessage} — so a verdict added to the enum later cannot inherit "send it in
     * the clear" by omission, which is precisely how this defect existed at all. Naming
     * {@code REFUSE} instead would invert that.
     */
    @Test
    public void onlyThePlaintextVerdictReachesThePlaintextSend() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsGroupMessage");
        assertTrue("the call site must branch on the verdict type",
                caller.contains("MlsSendRouting.Verdict"));
        assertTrue("the allow-list must name PLAINTEXT", caller.contains("Verdict.PLAINTEXT"));
        assertTrue("and SEAL, which is the other verdict permitted to send",
                caller.contains("Verdict.SEAL"));
        assertFalse("naming REFUSE here would make the gate a deny-list, and the next verdict added "
                + "would default to sending in the clear", caller.contains("Verdict.REFUSE"));
    }

    /**
     * <b>THE LANDMINE IN {@code sendToGroup}, pinned as an argument count.</b> The 2-arg overload
     * synthesises {@code mls-grp-<gid>-<millis>}, which matches no chat row — so no IMDN can ever
     * correlate to it — and is deliberately not cached, which turns invariant 62's replay
     * protection off for it. Correct for the debug broadcast it was written for; for a production
     * send it ships a message that can never reach a terminal state AND can be re-encrypted at a
     * second generation.
     *
     * <p>Keyed on the ARGUMENT the row supplies rather than on the arity alone, because a future
     * 3-arg overload taking something else would satisfy a comma count and not the property.
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
     * A failed SEAL must not retry the same text unsealed. By the time a seal fails the engine may
     * already have consumed a generation, so the plaintext arm would both put cleartext on a
     * conversation the user was told is encrypted and leave a hole in the sender ratchet — the rule
     * the 1:1 app-owned arm states in the same file.
     *
     * <p>Counted rather than located: ONE plaintext send in the method, and at least TWO handoffs to
     * the refusal (the verdict's, and the failed seal's). A fallback would need a second
     * {@code sendGroupMessage}.
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
     * Both arms are synchronous, so reaching the row write IS the measurement — and it must be
     * recorded. Left at {@code OUTGOING_YET_TO_SEND} the row reads "Sending…"
     * forever, because {@code ProcessPendingMessagesAction} excludes {@code TRANSPORT_RCS} from the
     * send queue by name and nothing on either group route fires a status callback.
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
