/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
/**
 * Seals one message to an RCS group and sends it: refuses a downgraded conversation, replays the
 * cached ciphertext for an id already sealed, and otherwise encrypts, stores the body and the
 * sealed bytes for a resend, and sends against the live epoch authenticator. The group twin of
 * {@link MlsSealSend}.
 */
public final class MlsGroupSend {
    private MlsGroupSend() {}

    /**
     * Sealed-cache header (not a wire header) holding the epoch authenticator current at seal
     * time; a replay under a freshly read one would look like a decrypt failure at the peer.
     */
    public static final String HDR_CACHED_GROUP_EPOCH_AUTH = "x-cached-epoch-authenticator";

    /** The epoch authenticator a cached group entry was sealed with, or null. */
    public static byte[] decodeEpochAuthHeader(final MlsShellPort shell,
            final MlsSealedMessage cached) {
        if (cached == null) return null;
        final String b64 = cached.headers().get(HDR_CACHED_GROUP_EPOCH_AUTH);
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return shell.base64Decode(b64);
        } catch (final IllegalArgumentException notBase64) {
            return null;
        }
    }

    public static boolean sendFramedToGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final byte[] framedBody,
            final String idPrefix, final String rcsMessageId) {
        if (!shell.ensureSession() || rcsGroupId == null || rcsGroupId.isEmpty()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, /*peerE164=*/ null);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsGroupSend: no MLS group for RCS group " + rcsGroupId
                    + " — join it first");
            return false;
        }
        // Gated on the persisted status, not the extension alone.
        if (MlsRecordState.hasEndMlsStatus(shell, log, key)) {
            log.i("MlsGroupSend: " + rcsGroupId + " is DOWNGRADED — this "
                    + "conversation is UNENCRYPTED; not sealing (§9.1.1)");
            return false;
        }
        final byte[] ee = shell.session().eraEpoch(g.groupId);
        final int era = MlsAppMessage.eraFrom(ee);
        if (era < 0) {
            log.e("MlsGroupSend: engine cannot state the era for " + rcsGroupId);
            return false;
        }
        final int gen = shell.session().nextAppGen(g.groupId);
        // A timestamp suffix, not the generation: the generation restarts every epoch and a reused
        // id is deduplicated away. The numeric tail keeps the group id recoverable by
        // MlsMessageId.groupIdFromLegacyWireId.
        final String messageId = (rcsMessageId == null || rcsMessageId.isEmpty())
                ? idPrefix + "-" + rcsGroupId + "-" + System.currentTimeMillis()
                : rcsMessageId;
        // A repeat send of one id replays the sealed bytes rather than re-encrypting. Only a
        // caller-supplied id is cached; a synthesised one is unique per call.
        final boolean cacheable = rcsMessageId != null && !rcsMessageId.isEmpty();
        if (cacheable) {
            final MlsSealedMessage cachedGroup = shell.sealedCache().get(messageId);
            if (cachedGroup != null) {
                log.i("MlsGroupSend: REPLAYING the sealed group ciphertext for "
                        + MlsMessageId.forLog(messageId) + " (" + cachedGroup
                        + ") — not re-encrypting, which would burn "
                        + "a generation and put a second ciphertext for one id on the wire");
                final byte[] replayAuth = MlsGroupSend.decodeEpochAuthHeader(shell, cachedGroup);
                final MlsProviderRpc.SendResult rr = shell.rpc(
                                "sendGroupMlsCiphertext").sendGroupMlsCiphertext(rcsGroupId,
                                cachedGroup.ciphertext(), messageId, cachedGroup.era, replayAuth);
                final boolean replayed = rr != null && rr.accepted;
                log.i("MlsGroupSend: group REPLAY → "
                        + (replayed ? "SENT" : "FAILED")
                        + (rr == null ? "" : " (" + rr.reason + ")"));
                return replayed;
            }
        }
        final byte[] aad = MlsPayloadCorruptor.buildAadWithProbe(shell, log, messageId, era);
        final byte[] body = framedBody;
        final byte[] ct = shell.session().encryptWithAad(g.groupId, body, aad);
        if (ct == null || ct.length == 0) {
            log.e("MlsGroupSend: group seal failed for " + rcsGroupId);
            return false;
        }
        // Debug instrument: corrupt the sealed bytes once so the peer's AEAD fails while the stored
        // body stays intact, making the resend readable. Unlike MlsPayloadCorruptor's body
        // mangling.
        if (MlsPayloadCorruptor.takeCorruptNextCt()) {
            // Inside the AEAD-protected payload, so it is a decrypt failure rather than a parse
            // error.
            final int idx = Math.min(ct.length - 1, ct.length / 2);
            ct[idx] ^= 0xFF;
            log.w("MlsGroupSend: POST-SEAL CT CORRUPTED byte " + idx + "/" + ct.length
                    + " for " + MlsMessageId.forLog(messageId)
                    + " — the peer's AEAD will FAIL (a real decrypt-miss). "
                    + "The stored framed body is INTACT, so our RESEND will be readable. Instrument "
                    + "for the reverse-resend capstone; one-shot.");
        }
        final byte[] epochAuth = shell.session().epochAuth(g.groupId);
        if (epochAuth == null) {
            log.e("MlsGroupSend: no live epoch-authenticator for " + rcsGroupId
                    + " — refusing to send with a cached one");
            return false;
        }
        // Store the body for a RCC.16 §10.3 resend before the send: the chat row is inserted after
        // dispatch, so a fast FTD can arrive before it exists.
        shell.pendingBodies().put(messageId, framedBody);
        // Cache before the send, so a retry that races it replays rather than re-encrypts.
        if (cacheable) {
            final java.util.Map<String, String> groupHeaders = new java.util.LinkedHashMap<>();
            groupHeaders.put(MlsGroupSend.HDR_CACHED_GROUP_EPOCH_AUTH,
                    java.util.Base64.getEncoder().encodeToString(epochAuth));
            // Wall clock and elapsed realtime both, as on the 1:1 path.
            shell.sealedCache().put(new MlsSealedMessage(messageId, ct, era, gen, groupHeaders,
                    System.currentTimeMillis(), key, shell.elapsedRealtime()));
        }
        log.i("MlsGroupSend: group sealed gen=" + gen + " era=" + era
                + " ct=" + ct.length + "B (rcsGroupId=" + rcsGroupId + ")");
        final MlsProviderRpc.SendResult r =
                shell.rpc("sendGroupMlsCiphertext").sendGroupMlsCiphertext(rcsGroupId, ct,
                        messageId, era, epochAuth);
        final boolean ok = r != null && r.accepted;
        log.i("MlsGroupSend: group sendSealed → " + (ok ? "SENT" : "FAILED")
                + (r == null ? "" : " (" + r.reason + ")"));
        if (ok) MlsRekeyPolicy.noteSendAndMaybeRekey(shell, log, key, /*peerE164=*/ null,
                rcsGroupId);
        return ok;
    }
}
