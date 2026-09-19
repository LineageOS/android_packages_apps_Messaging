/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.HashMap;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
/**
 * Seals one 1:1 message and sends it. Sealing refuses a downgraded or departed conversation or a
 * shut convergence gate, commits an outstanding proposal first, replays the cached ciphertext for
 * an id already sealed, and otherwise encrypts with the generation stamped into the body. Sending
 * reads the live epoch authenticator and retries a refusal once with identical bytes.
 */
public final class MlsSealSend {
    private MlsSealSend() {}

    /**
     * Pseudo-header carrying the id bound into the AAD, so the envelope goes out under the same id.
     * Stripped by the send path.
     */
    public static final String HDR_SEALED_MESSAGE_ID = "x-sealed-message-id";

    /**
     * Encrypts for send, binding the app's {@code rcs_message_id} as the wire and AAD id, so a
     * peer's report names a row id and the ciphertext cache can be keyed by it. The server
     * deduplicates on message id and the generation restarts every epoch, so a synthesised id must
     * carry era and epoch.
     *
     * @param rcsMessageId the app's row id; when null an id is synthesised from era, epoch and
     *     generation
     */
    public static MlsSendPayload encryptForSend(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final byte[] framedBody, final String rcsMessageId) {
        if (!shell.ensureSession()) return null;
        final Group g = shell.getGroup(conversationId);
        if (g == null) {
            log.w("MlsSealSend: no group for " + MlsConversationKey.forLog(conversationId));
            return null;
        }
        // RCC.16 §9.1.1: no encrypted sends once end_mls applies; null drops to the unencrypted
        // path. Tested on the status, not the extension: OngoingPhoenixMode must not send, and the
        // extension only appears in the new era.
        if (MlsRecordState.hasEndMlsStatus(shell, log, conversationId)) {
            log.i("MlsSealSend: " + MlsConversationKey.forLog(conversationId)
                    + " is DOWNGRADED (status "
                    + MlsHealthStates.name(
                            MlsRecordState.recordFor(shell, log, conversationId).healthStatus)
                    + ") — this conversation is UNENCRYPTED; not sealing");
            return null;
        }
        // Terminal. Without this our own SelfRemove proposal makes commitRequired() true and every
        // send would attempt a commit MLS forbids.
        if (MlsRecordState.weLeft(shell, log, conversationId)) {
            log.i("MlsSealSend: " + MlsConversationKey.forLog(conversationId)
                    + " is a group WE LEFT "
                    + "(RCC.16 §9.4 self_remove) — not sealing. This is terminal: MLS forbids "
                    + "committing your own removal, so nothing local can undo it, and the server "
                    + "has already taken us off the RCS roster.");
            return null;
        }
        if (MlsResendBudget.sendBlockedByGate(shell, log, conversationId)) {
            log.i("MlsSealSend: send HELD — awaiting peer convergence for "
                    + MlsConversationKey.forLog(conversationId));
            return null;
        }
        // mls-rs commit_required(): a cached by-reference proposal is committed before another
        // application message is encrypted.
        if (shell.session().commitRequired(g.groupId)) {
            log.i("MlsSealSend: cached proposal outstanding on "
                    + MlsConversationKey.forLog(conversationId)
                    + " — committing before encrypting");
            MlsStateChangeGate.commitPendingProposals(shell, log, g.rcsGroupId, g.peerE164);
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era < 0) {
            // Refuse rather than guess: a wrong era is a known-bad AAD.
            log.e("MlsSealSend: era unresolvable — refusing to send a guessed AAD");
            return null;
        }
        // Serialise from here: the cache lookup, nextAppGen and the encrypt are check-then-act on
        // ratchet state, and two concurrent sends would share a generation. No transport I/O
        // inside; the key-update round trip is collected here and fired after the unlock.
        final PendingKeyUpdate[] pendingKeyUpdate = new PendingKeyUpdate[1];
        shell.lock(conversationId);
        try {
        // Never re-encrypt an id that already has a ciphertext: each encrypt consumes a generation.
        // Replay the headers with the bytes; they must be the values current when it was sealed.
        if (rcsMessageId != null && !rcsMessageId.isEmpty()) {
            final MlsSealedMessage cached = shell.sealedCache().get(rcsMessageId);
            if (cached != null) {
                log.i("MlsSealSend: REPLAYING the sealed ciphertext for "
                        + MlsMessageId.forLog(rcsMessageId) + " (" + cached
                        + ") — not re-encrypting, which would burn a "
                        + "generation and put a second ciphertext for one id on the wire");
                return new MlsSendPayload("message/mls", cached.ciphertext(), cached.headers());
            }
        }
        final int nextGen = shell.session().nextAppGen(g.groupId);
        final byte[] stamped = MlsRecoveryPolicy.stampBodyGeneration(framedBody, nextGen);
        // The synthesised id carries era, epoch and generation; the generation restarts every
        // epoch, and a reused id is dropped by the server's dedupe. A row UUID cannot collide.
        final String messageId = (rcsMessageId == null || rcsMessageId.isEmpty())
                ? "mls-" + conversationId + "-e" + era
                        + "p" + MlsAppMessage.epochFrom(shell.session().eraEpoch(g.groupId)) + "-"
                        + nextGen
                : rcsMessageId;
        final byte[] aad = MlsPayloadCorruptor.buildAadWithProbe(shell, log, messageId, era);
        // The engine returns the ciphertext and any piggybacked key-update commit in one call; both
        // are dispatched before anything branches on status.
        final byte[] ct = MlsAppMessage.encryptDispatching(shell, log, g, conversationId, stamped,
                aad, messageId, pendingKeyUpdate);
        if (ct == null || ct.length == 0) {
            log.e("MlsSealSend: encrypt returned empty");
            return null;
        }
        // Test instrument: one-shot corruption so the peer's decrypt fails while the stored body
        // stays intact for the resend.
        if (MlsPayloadCorruptor.takeCorruptNextCt()) {
            final int idx = Math.min(ct.length - 1, ct.length / 2);
            ct[idx] ^= 0xFF;
            log.w("MlsSealSend: POST-SEAL CT CORRUPTED byte " + idx + "/" + ct.length
                    + " for " + MlsMessageId.forLog(messageId)
                    + " (1:1) — peer AEAD will fail; the stored body is intact "
                    + "so our resend will be readable. One-shot capstone instrument.");
        }
        g.era = era;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Era-ID", Long.toString(era));
        if (g.epochAuth != null) {
            headers.put("Epoch-Authenticator",
                    java.util.Base64.getEncoder().encodeToString(g.epochAuth));
        }
        // The envelope id must equal the id bound into the AAD; peers drop a mismatch.
        headers.put(HDR_SEALED_MESSAGE_ID, messageId);
        // Cache before returning so a racing retry replays. Only for an app id; a synthesised id is
        // not retry-addressable.
        if (rcsMessageId != null && !rcsMessageId.isEmpty()) {
            // Wall time is diagnostic; the elapsed stamp is what the retention sweep ages on.
            shell.sealedCache().put(new MlsSealedMessage(messageId, ct, era, nextGen, headers,
                    System.currentTimeMillis(), conversationId, shell.elapsedRealtime()));
        }
        // The framed body, for an FTD resend, which re-encrypts at the repaired epoch. Stored under
        // the wire id unconditionally and before the send, so a fast report still finds it.
        shell.pendingBodies().put(messageId, framedBody);
        log.i("MlsSealSend: sealed gen=" + nextGen + " era=" + era
                + " ct=" + ct.length + "B (msgId=" + MlsMessageId.forLog(messageId) + ")");
        return new MlsSendPayload("message/mls", ct, headers);
        } finally {
            shell.unlock(conversationId);
            // After the unlock, on every exit: the engine already applied the commit locally, so it
            // must reach the server, and not while we hold the lock.
            MlsRekeyPolicy.dispatchPendingKeyUpdate(shell, log, pendingKeyUpdate[0]);
        }
    }

    /**
     * Sends a sealed payload with the stamps from the seal that produced it, so id, era and epoch
     * authenticator cannot come from different moments.
     */
    public static boolean sendSealed(final MlsShellPort shell, final MlsLogSink log,
            final String peerE164, final MlsSendPayload p) {
        if (p == null || p.cpimHeaders == null) return false;
        final String sealedId = p.cpimHeaders.get(MlsSealSend.HDR_SEALED_MESSAGE_ID);
        final String eraStr = p.cpimHeaders.get("Era-ID");
        int era = -1;
        try { era = Integer.parseInt(eraStr); } catch (final Throwable ignore) { }
        if (sealedId == null || era < 0) {
            log.e("MlsSealSend: sealed payload missing id/era — refusing to send");
            return false;
        }
        final String convId = MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peerE164);
        final Group g = (convId == null) ? null : shell.getGroup(convId);
        // The live epoch authenticator; the cached copy predates any later commit.
        final byte[] epochAuth = (g == null || g.groupId == null) ? null
                : shell.session().epochAuth(g.groupId);
        // No fallback to the cached value: sending a stale authenticator hides a broken store.
        if (epochAuth == null) {
            log.e("MlsSealSend: engine has no live epoch-authenticator for "
                    + MlsConversationKey.forLog(convId)
                    + " — refusing to send with a cached one (run the health check; "
                    + "see the recovery notes on this class)");
            return false;
        }
        log.i("MlsSealSend: sendSealed conv=" + MlsConversationKey.forLog(convId)
                + " era=" + era + " epochAuth="
                + (epochAuth == null ? "NULL" : epochAuth.length + "B")
                + " gid=" + (g == null || g.groupId == null ? "?" : g.groupId.length + "B"));
        MlsProviderRpc.SendResult r = shell.rpc("sendMlsCiphertext").sendMlsCiphertext(peerE164,
                p.body, sealedId, era, epochAuth);
        boolean ok = r != null && r.accepted;
        if (!ok) {
            // Retry once with the same bytes and headers: a refusal while a peer's commit lands is
            // transient. Not a re-encrypt, and the server deduplicates on message id.
            log.w("MlsSealSend: sendSealed refused for " + sealedId
                    + (r == null ? "" : " (" + r.reason + ")") + " — retrying once with identical "
                    + "bytes/headers (transient concurrent-commit refusal).");
            r = shell.rpc("sendMlsCiphertext").sendMlsCiphertext(peerE164, p.body, sealedId, era,
                    epochAuth);
            ok = r != null && r.accepted;
        }
        // On success the reason carries the server timestamp; a repeated one means the enqueue was
        // deduplicated.
        log.i("MlsSealSend: sendSealed → " + (ok ? "SENT" : "FAILED")
                + (r == null ? "" : " (" + r.reason + ")"));
        if (ok) MlsRekeyPolicy.noteSendAndMaybeRekey(shell, log, convId, peerE164,
                /*rcsGroupId=*/ null);
        return ok;
    }
}
