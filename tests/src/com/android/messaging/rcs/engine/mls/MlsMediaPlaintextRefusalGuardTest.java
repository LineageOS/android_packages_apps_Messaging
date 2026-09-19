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
import java.util.List;

import org.junit.Test;

/**
 * A UI-composed media send, 1:1 or group, does not reach {@code sendFile} before the MLS gate has
 * answered. The media half of {@code MlsGroupPlaintextRefusalGuardTest}. An unsealed attachment is
 * uploaded to a content server this app does not control, and there is no encrypted media send yet,
 * so the media sites allow {@code PLAINTEXT} alone. {@code InsertNewMessageAction} and the
 * transport have no host test, so the scan keys on invoked names and counts its hits. See
 * docs/testing.md.
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

    /**
     * The gate is consulted before the file is handed to the transport; an upload cannot be taken
     * back.
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

            assertTrue("the verdict must be taken BEFORE the send (gate at " + verdict
                    + ", send at " + send + ") in " + method, verdict < send);
        }
    }

    /**
     * The gate is ahead of every {@code return false} in the method: each falls through to the MMS
     * path, so a refusal decided later could not stop the same file leaving over MMS.
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
                // Permitted ahead of the gate: no resolved attachment, and (group) no rcs_group_id;
                // refusing those would land an empty failed bubble. Keyed on the guarding
                // condition, not a text window.
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
     * A refusal is not spelled {@code return false}, which would fall through to MMS. It goes
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
     * The refused row lands terminal: nothing moves an RCS row left in
     * {@code OUTGOING_YET_TO_SEND}, and no {@code onMessageStatus} follows a send that never
     * reached the wire.
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
     * The refusal describes the part the caller was about to upload, handed down rather than
     * re-derived from a multi-attachment draft.
     */
    @Test
    public void theRefusalDescribesThePartTheCallerWasAboutToSend() throws IOException {
        final String refusal = body(action(), "insertRefusedRcsMediaMessage");
        assertEquals("the refusal must not re-derive the attachment", 0,
                SourceScan.count(refusal, "firstMediaAttachment("));
        assertTrue("it must read the part it was handed",
                refusal.contains("media.getContentUri()"));
        assertTrue("and its type", refusal.contains("media.getContentType()"));
    }

    /**
     * The media sites allow {@code PLAINTEXT} alone: naming {@code REFUSE} would make the gate a
     * deny-list that new verdicts pass by omission, and naming {@code SEAL} would upload in the
     * clear for a conversation the engine could seal.
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
     * A media method may name {@code Verdict.SEAL} only if it also calls something that seals: with
     * the gate shaped {@code if (verdict != PLAINTEXT) return refuse(…);}, allowing SEAL at the
     * condition alone would let it fall through to the plaintext upload. Passes unchanged once a
     * seal arm exists.
     */
    @Test
    public void namingSealInAMediaMethodRequiresASealCallInIt() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            final String body = body(src, method);
            if (!body.contains("Verdict.SEAL")) {
                continue;   // the allow-list is PLAINTEXT alone
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
     * Exactly one plaintext file send per media method; a second would be a fall-back after a
     * refusal.
     */
    @Test
    public void thereIsOnePlaintextUploadPerMediaPath() throws IOException {
        final String src = action();
        for (final String method : MEDIA_PATHS) {
            assertEquals("exactly one plaintext file send in " + method, 1,
                    SourceScan.count(body(src, method), "sendFile("));
        }
    }

    /**
     * The 1:1 verdict reads the latch before it asks the engine and composes both with the
     * host-tested table, as {@code groupSendVerdict} does: the engine call is wrapped in a catch
     * that must not invent a latch value.
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
     * One table: every {@code Verdict.} the transport mentions is inside a method that calls
     * {@code MlsSendRouting.decide} or returns its result.
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
     * The 1:1 gate keys on the canonical E.164: a national form keys nothing and answers
     * {@code NO_MLS_STATE}, which fails open.
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
     * The engine input comes from the peer for a 1:1 and from the RCS group for a group. A wrong
     * shape gives {@code canonicalKey(null, null)}, which reads as {@code NO_MLS_STATE}.
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
                + "one method serve both shapes",
                gate.contains("canonicalKey(rcsGroupId, peerE164)"));
        assertTrue("a null key means we could not ask, and must not read as 'sealable'",
                gate.contains("NO_MLS_STATE"));
    }
}
