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
import java.util.List;

import org.junit.Test;

/**
 * <b>A UI-composed MEDIA send must not reach {@code sendFile} without the MLS gate having answered
 * first — 1:1 AND group</b>.
 *
 * <p>The media half of what {@code MlsGroupPlaintextRefusalGuardTest} pins for group text. They are
 * two files because they pin two different sets of call sites, and <b>finding one of them is not
 * finding the whole rule</b>: the media paths were missed for exactly as long as the text path was
 * treated as the precedent, and a guard that covered only the text sites was part of why.
 *
 * <h2>Why this is worse than the text defect, and therefore stricter</h2>
 *
 * <p>An unsealed text message is readable by the Tachyon relay as it passes. An unsealed attachment
 * is <b>uploaded to a content server</b>, where it has a pre-signed URL and a retention policy this
 * app does not control — and the compose box was drawing a padlock over it. So the media call sites
 * gate on an allow-list of {@code PLAINTEXT} ALONE, where the text site also permits {@code SEAL}:
 * there is no encrypted media send yet, so {@code SEAL} is an answer about the conversation that
 * this caller cannot carry out, and treating it as permission to send would be the defect wearing a
 * gate.
 *
 * <h2>Why a source scan</h2>
 *
 * <p>The standing caution on {@link SourceScan} applies, and is paid for the same reason as the
 * group guard: {@code InsertNewMessageAction} and {@code MlsProviderTransport} each need a
 * {@code Context}, a bound provider and a {@code messaging.db}, so neither has a host test. Every
 * assertion here keys on an INVOKED METHOD NAME and counts its hits, so a pattern that has gone
 * stale fails rather than passing on nothing.
 *
 * <h2>The falsifier — measured, 10 of the 11 tests here</h2>
 *
 * <p>Materialise the pre-fix revision of both files into a shadow tree and run this class with that
 * as the working directory ({@link SourceScan#read} resolves relative to it):
 *
 * <pre>
 *   git show 886236cf:src/com/android/messaging/datamodel/action/InsertNewMessageAction.java
 *   git show a237cdde:src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java
 * </pre>
 *
 * <p>Ten go red, each on the defect rather than on a missing file: the two media methods referenced
 * {@code MlsProviderTransport} zero times, {@code insertRefusedRcsMediaMessage} did not exist, and
 * the transport held one verdict entry point instead of two.
 *
 * <p><b>The eleventh passes there, and that is correct.</b>
 * {@link #thereIsOnePlaintextUploadPerMediaPath} asserts the plaintext send is still present and
 * still singular — an invariant the defect satisfied too. It is here so that deleting the send
 * cannot make the other tests pass vacuously, and it is named rather than quietly counted as part
 * of the falsification.
 */
public class MlsMediaPlaintextRefusalGuardTest {

    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";

    /** The two media send paths this is about. */
    private static final String[] MEDIA_PATHS =
            {"tryInsertSendingRcsFile", "tryInsertSendingRcsGroupFile"};

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

    // ---------------------------------------------------------------- the two call sites that leak

    /**
     * THE DEFECT, stated as an ordering: the gate must be consulted BEFORE the file is handed to the
     * transport. A verdict read after the upload has started is a log line, and the upload is the
     * part that cannot be taken back.
     */
    @Test
    public void bothMediaPathsAskTheMlsGateBeforeTheyUpload() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            final String b = body(src, method);

            final int verdict = b.indexOf("SendVerdict(");
            assertTrue(method + " must consult MlsProviderTransport before it uploads — without it "
                    + "every attachment goes to the File Transfer Server in the clear, on MLS "
                    + "conversations too, with the padlock lit", verdict >= 0);

            final int send = b.indexOf("sendFile(");
            assertTrue("the plaintext file send must still be here — a guard that passes because "
                    + "the method was deleted is not a guard", send >= 0);

            assertTrue("the verdict must be taken BEFORE the send (gate at " + verdict + ", send at "
                    + send + ") in " + method, verdict < send);
        }
    }

    /**
     * <b>The gate must be ahead of EVERY {@code return false} in the method, not merely ahead of the
     * send.</b> Each of those is a fall-through to the unchanged MMS path, so a refusal decided
     * after one of them would have already lost the chance to stop the same file leaving over a
     * different transport. This is the one property the group-text guard does not have an analogue
     * of, because the text gate genuinely sits downstream of several of them.
     */
    @Test
    public void theGateRunsAheadOfEveryFallThroughToMms() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            final String b = body(src, method);
            final int verdict = b.indexOf("SendVerdict(");
            assertTrue(method + " must consult the gate", verdict >= 0);

            final List<Integer> falls = SourceScan.indicesOf(b, "return false");
            assertFalse("there must still be fall-throughs to MMS in " + method + " — a guard that "
                    + "passes because they were all removed is not a guard", falls.isEmpty());

            for (final Integer idx : falls) {
                final int at = idx.intValue();
                if (at > verdict) continue;
                // The only ones permitted ahead of the gate are the two the gate cannot answer
                // about: no resolved attachment, and (group) no rcs_group_id. Both would land an
                // empty failed bubble if they were refused instead.
                //
                // Keyed on the CONDITION that guards the return rather than on a window of preceding
                // text: a window has to be sized, and a comment growing past it would turn this into
                // a check that fails for a reason unrelated to the property.
                final int guard = b.lastIndexOf("if (", at);
                final String cond = (guard < 0) ? "" : b.substring(guard, at);
                assertTrue("a `return false` at " + at + " in " + method + " sits AHEAD of the MLS "
                        + "gate at " + verdict + " and is not guarded by one of the two conditions "
                        + "the gate cannot answer about (no media part / no rcs_group_id). Every "
                        + "`return false` is a fall-through to MMS, so one ahead of the gate can "
                        + "carry the file out in the clear. Guarded by: " + cond,
                        cond.contains("media") || cond.contains("groupId"));
            }
        }
    }

    /**
     * <b>A refusal must not be spelled {@code return false}.</b> Both media forks fall through to
     * the unchanged MMS path on {@code false}, so that spelling would upload nothing and then send
     * the same picture over MMS — a downgrade wearing the shape of a rejection. The refusal goes
     * through {@code insertRefusedRcsMediaMessage}, which returns {@code true} on every path.
     */
    @Test
    public void theRefusalDoesNotFallThroughToMms() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            assertTrue(method + "'s refusal branch must hand off to insertRefusedRcsMediaMessage",
                    body(src, method).contains("insertRefusedRcsMediaMessage("));
        }

        final String refusal = body(src, "insertRefusedRcsMediaMessage");
        assertEquals("insertRefusedRcsMediaMessage must never return false: false is the caller's "
                + "signal to send the same file over MMS", 0,
                SourceScan.count(refusal, "return false"));
        assertTrue("it must return true so the caller neither sends nor falls through",
                SourceScan.count(refusal, "return true") >= 1);
    }

    /**
     * The refused row lands TERMINAL, not as a spinner. An RCS row left in
     * {@code OUTGOING_YET_TO_SEND} is picked up by nothing — {@code ProcessPendingMessagesAction}
     * excludes {@code TRANSPORT_RCS} from the SMS/MMS queue by design — so it reads "Sending…"
     * forever, which is the stuck-send defect arriving from the other direction. Nothing was handed to
     * the wire here, so unlike the successful media path there is no {@code onMessageStatus} coming
     * to rescue it.
     */
    @Test
    public void theRefusedMediaMessageIsMarkedFailedRatherThanLeftSending() throws IOException {
        final String refusal = body(action(), "insertRefusedRcsMediaMessage");
        final int sending = refusal.indexOf("updateSendingMessage(");
        final int failed = refusal.indexOf("markMessageFailed(");
        assertTrue("the row must be inserted", sending >= 0);
        assertTrue("the row must be marked FAILED — OUTGOING_YET_TO_SEND on an RCS row is a "
                + "permanent 'Sending…'", failed >= 0);
        assertTrue("markMessageFailed must come after updateSendingMessage, which sets "
                + "OUTGOING_YET_TO_SEND and would otherwise overwrite it", sending < failed);
        assertTrue("the row must carry the RCS transport type, which is what keeps the SMS/MMS "
                + "queue from touching it", refusal.contains("rcsMetaValues("));
        assertTrue("it must be a MEDIA row — a text row would drop the attachment the user is "
                + "looking at and offer nothing to resend as SMS",
                refusal.contains("createOutgoingRcsMediaMessage("));
    }

    /**
     * <b>The refusal describes the part the caller was about to upload</b>, handed down rather than
     * re-derived. A second {@code firstMediaAttachment} walk inside the refusal could pick a
     * different part on a multi-attachment draft, and the failed row would then describe a file
     * other than the one that was refused.
     */
    @Test
    public void theRefusalDescribesThePartTheCallerWasAboutToSend() throws IOException {
        final String refusal = body(action(), "insertRefusedRcsMediaMessage");
        assertEquals("the refusal must not re-derive the attachment", 0,
                SourceScan.count(refusal, "firstMediaAttachment("));
        assertTrue("it must read the part it was handed", refusal.contains("media.getContentUri()"));
        assertTrue("and its type", refusal.contains("media.getContentType()"));
    }

    // ------------------------------------------------------------------ the allow-list, and its bar

    /**
     * <b>The media call sites gate on an ALLOW-LIST of {@code PLAINTEXT} alone.</b>
     *
     * <p>Two properties in one, and they fail for different reasons. Naming {@code REFUSE} would
     * make the gate a deny-list, so a verdict added to the enum later would inherit "send it in the
     * clear" by omission — precisely how this defect existed at all. Naming {@code SEAL} would be
     * worse than that: it would let a conversation the engine says it COULD seal send its
     * attachment unencrypted, on the strength of a capability nothing here exercises.
     */
    @Test
    public void onlyThePlaintextVerdictReachesTheUpload() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            final String caller = body(src, method);
            assertTrue(method + " must branch on the verdict type",
                    caller.contains("MlsSendRouting.Verdict"));
            assertTrue(method + "'s allow-list must name PLAINTEXT",
                    caller.contains("Verdict.PLAINTEXT"));
            assertFalse("naming REFUSE in " + method + " would make the gate a deny-list, and the "
                    + "next verdict added would default to uploading in the clear",
                    caller.contains("Verdict.REFUSE"));
            assertFalse("naming SEAL in " + method + " would let a conversation the engine says it "
                    + "could seal upload its attachment unencrypted — there is no media seal path "
                    + "yet (phase 2), so SEAL is not an instruction this caller can obey",
                    caller.contains("Verdict.SEAL"));
        }
    }

    /**
     * <b>WIDENING THE ALLOW-LIST TO {@code SEAL} REQUIRES A SEAL ARM IN THE SAME METHOD, AND THIS
     * ASSERTION IS WHY THE WIDENING CANNOT BE DONE ALONE.</b>
     *
     * <p>It has been proposed twice as "four lines", and the arithmetic is right while the ordering
     * is not. The gate's shape is {@code if (verdict != PLAINTEXT) return refuse(…);} and everything
     * after it is the PLAINTEXT upload. So permitting {@code SEAL} at the condition does not enable
     * sealing — <b>it lets a SEAL verdict fall through to {@code sendFile}</b>, which puts the
     * attachment of a padlocked conversation on a content server in the clear. That is the leak
     * re-opened, by the change intended to complete it.
     *
     * <p>The widening is correct the moment there is something to widen INTO. So this pins the
     * PRECONDITION rather than forbidding the change: a media method may name {@code Verdict.SEAL}
     * only if it also calls something that seals. Prose did not stop the proposal twice; this will.
     *
     * <p>When the seal arm lands, this assertion passes on its own and needs no edit — it is not a
     * ratchet and it is not a veto.
     */
    @Test
    public void namingSealInAMediaMethodRequiresASealCallInIt() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            final String body = body(src, method);
            if (!body.contains("Verdict.SEAL")) {
                continue;   // today's state: the allow-list is PLAINTEXT alone
            }
            final boolean seals = body.contains("sendMlsFile(") || body.contains("RccMediaSeal.")
                    || body.contains("sealMedia(") || body.contains("sendSealedFile(");
            assertTrue(method + " names Verdict.SEAL but calls nothing that seals. The gate is "
                    + "`if (verdict != PLAINTEXT) return refuse(...)` and everything below it is the "
                    + "PLAINTEXT upload — so permitting SEAL here does not seal, it lets a SEAL "
                    + "verdict FALL THROUGH to sendFile and put a padlocked conversation's "
                    + "attachment on a content server in the clear. That is the leak re-opened. Wire "
                    + "the seal arm in the same commit as the widening.", seals);
        }
    }

    /**
     * Exactly ONE plaintext file send per media method. A second one is the fall-back this
     * exists to remove: it would be reachable only by deciding, after the gate said no, to try
     * anyway.
     */
    @Test
    public void thereIsOnePlaintextUploadPerMediaPath() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            assertEquals("exactly one plaintext file send in " + method, 1,
                    SourceScan.count(body(src, method), "sendFile("));
        }
    }

    // --------------------------------------------------------------- the 1:1 gate's own two inputs

    /**
     * The 1:1 verdict reads the latch BEFORE it asks the engine, and composes them with the
     * host-tested table rather than re-deriving the rule. Same ordering and same reason as
     * {@code groupSendVerdict}: the engine call is wrapped in a catch, and a catch that had to
     * invent a latch value would be the place a padlocked conversation quietly became a plaintext
     * upload.
     */
    @Test
    public void theOneToOneVerdictReadsTheAppLatchBeforeItAsksTheEngine() throws IOException {
        final String b = body(transport(), "oneToOneSendVerdict");
        final int latch = b.indexOf("readMlsLatch(");
        final int engine = b.indexOf("sealCapability(");
        assertTrue("oneToOneSendVerdict must read the app's MLS latch", latch >= 0);
        assertTrue("oneToOneSendVerdict must ask the engine what it can seal", engine >= 0);
        assertTrue("the latch read must not depend on the engine call surviving", latch < engine);
        assertTrue("the two must be composed by the host-tested table, not re-derived here",
                b.contains("MlsSendRouting.decide("));
    }

    /**
     * <b>ONE table, not a second copy of the rule.</b> The group half of this defect existed because
     * the decision lived at the call site; a 1:1 copy of {@code decide}'s three lines would rebuild
     * that, and the two would agree only until one of them was edited.
     *
     * <p>Counted over the whole transport rather than located: {@code MlsSendRouting.decide} is
     * the only thing permitted to turn a {@code SealCapability} and an {@code MlsLatch} into a
     * verdict, so every {@code Verdict.} the transport MENTIONS must be inside a method that calls
     * it or returns its result.
     */
    @Test
    public void bothVerdictsComposeTheSameTable() throws IOException {
        final String src = transport();
        assertEquals("both verdict entry points must compose the table, and nothing else may", 2,
                SourceScan.count(src, "MlsSendRouting.decide("));
        for (final String method : new String[] {"groupSendVerdict", "oneToOneSendVerdict"}) {
            assertTrue(method + " must exist and compose the table",
                    body(src, method).contains("MlsSendRouting.decide("));
        }
    }

    /**
     * <b>The 1:1 gate must key on the CANONICAL E.164</b>, the same string the send is addressed to.
     * {@code canonicalKey} builds {@code "p:" + peerE164}, so the national/dialable form keys
     * nothing — it would answer {@code NO_MLS_STATE} for a conversation we hold state for, and that
     * is the direction that fails OPEN: a padlocked thread would upload in the clear because the
     * gate was asked about a peer that does not exist.
     */
    @Test
    public void theOneToOneCallSitePassesTheCanonicalDestination() throws IOException {
        final String caller = body(action(), "tryInsertSendingRcsFile");
        final int at = caller.indexOf("oneToOneSendVerdict(");
        assertTrue("the 1:1 media path must consult oneToOneSendVerdict", at >= 0);

        final int open = caller.indexOf('(', at);
        int depth = 0, close = -1;
        for (int i = open; i < caller.length(); i++) {
            if (caller.charAt(i) == '(') depth++;
            else if (caller.charAt(i) == ')' && --depth == 0) { close = i; break; }
        }
        assertTrue("unbalanced call", close > open);
        final String args = caller.substring(open + 1, close);
        assertTrue("the gate must be handed `dest` — the canonicalized E.164 the send uses — and "
                + "not the raw `recipient`, which keys nothing: " + args, args.contains("dest"));
        assertFalse("`recipient` is the national/dialable form; keying on it answers about a peer "
                + "that does not exist and fails OPEN: " + args, args.contains("recipient"));

        final int canon = caller.indexOf("getCanonicalBySimLocale(");
        assertTrue("dest must be canonicalized before the gate reads it (canonicalize at " + canon
                + ", gate at " + at + ")", canon >= 0 && canon < at);
    }

    /**
     * The engine input is derived from the PEER for a 1:1 and from the RCS GROUP for a group, over
     * one {@code sealCapability}. Pinned as the two argument shapes, because the failure mode of
     * getting it wrong is silent: {@code canonicalKey(null, null)} is null, which reads as
     * {@code NO_MLS_STATE} — a clean answer that means "we did not ask".
     */
    @Test
    public void theTwoVerdictsAskTheEngineAboutDifferentKeys() throws IOException {
        final String src = transport();
        assertTrue("the group verdict must key on the rcs_group_id",
                body(src, "groupSendVerdict").contains("sealCapability(rcsGroupId,"));
        assertTrue("the 1:1 verdict must key on the peer",
                body(src, "oneToOneSendVerdict").contains(", peerE164)"));

        final String gate = body(src, "sealCapability");
        assertTrue("sealCapability must build the key through canonicalKey, which is what makes "
                + "one method serve both shapes", gate.contains("canonicalKey(rcsGroupId, peerE164)"));
        assertTrue("a null key means we could not ask, and must not read as 'sealable'",
                gate.contains("NO_MLS_STATE"));
    }
}
