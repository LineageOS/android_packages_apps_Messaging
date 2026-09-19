/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnStamps;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnCheck;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * Signs and verifies IMDNs on MLS conversations (RCC.16 §7.6.2): the verifiable derived content,
 * signed with our leaf key, as the Base64 the receipt carries. The negative-receipt debug levers
 * ({@code debug.rcs.mls_ftd_*}) shape the delivery inner for decoder experiments.
 */
public final class MlsImdnSigner {
    private MlsImdnSigner() {}

    /**
     * Signs an IMDN (RCC.16 §7.6.2): builds the RCC.16 §7.6.3 derived content, signs it into an
     * {@code rcs_signature} PublicMessage, and returns the Base64 for the
     * {@code MLS-Derived-Content-Signature} header.
     *
     * @param receiptMessageId this receipt's own id, carried in the signed prefix
     * @param displayed true for a display IMDN, false for delivery
     * @param failureReason delivery only; {@link VerifiableDerivedContent#FAILURE_UNSET} if
     *     delivered
     * @return the Base64 header value, or null if we cannot sign (no group, engine refused)
     */
    public static String signImdn(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String receiptMessageId, final String messageId,
            final boolean displayed, final int status, final int failureReason) {
        // This is the positive/display signature producer. A negative receipt is signed with it too
        // by default, because peers reject an unsigned negative and accept this one; setting
        // debug.rcs.mls_sign_negative=false restores the refusal. A dedicated negative producer
        // does not exist yet.
        final boolean signNegative = shell.sysprops().getBoolean(
                "debug.rcs.mls_sign_negative", true);
        if (!displayed && failureReason != VerifiableDerivedContent.FAILURE_UNSET
                && !signNegative) {
            log.e("MlsImdnSigner: REFUSING to sign a NEGATIVE delivery receipt for "
                    + MlsMessageId.forLog(messageId) + " (failureReason=" + failureReason
                    + ") — invariant 80: that "
                    + "signature comes from OutgoingFtd field 1 of an earlier engine result, not "
                    + "from the generate verb. Set debug.rcs.mls_sign_negative to sign it with the "
                    + "positive producer as an EXPERIMENT.");
            return null;
        }
        if (!displayed && status != VerifiableDerivedContent.DELIVERY_DELIVERED && !signNegative) {
            log.e("MlsImdnSigner: REFUSING to sign delivery status " + status
                    + " for " + MlsMessageId.forLog(messageId)
                    + " — only DELIVERED(1) is the positive arm; anything "
                    + "else is a negative receipt and belongs to the other producer (invariant 80).");
            return null;
        }
        if (signNegative && !displayed && failureReason != VerifiableDerivedContent.FAILURE_UNSET) {
            log.i("MlsImdnSigner: signing a negative receipt for " + MlsMessageId.forLog(messageId)
                    + " with the positive producer. This is the DEFAULT: on the wire, "
                    + "signed clears CANNOT_PARSE_MESSAGE(16) and unsigned never does, so the "
                    + "producer distinction does not reach the peer. Set "
                    + "debug.rcs.mls_sign_negative=false to reproduce the unsigned rejection.");
        }
        if (!shell.ensureSession()) return null;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return null;
        // Debug levers for the RCC.16 §7.6.3.2 delivery inner of a negative receipt, which we then
        // sign; debug builds only:
        //   debug.rcs.mls_ftd_inner2      hex inserted raw into the inner (unset: off)
        //   debug.rcs.mls_ftd_inner2_pos  before|after failure_reason (default before)
        final boolean levers = shell.sysprops().debuggableBuild();
        final byte[] inner;
        if (displayed) {
            inner = VerifiableDerivedContent.displayImdn(status, messageId);
        } else {
            final boolean isNeg = failureReason != VerifiableDerivedContent.FAILURE_UNSET;
            final boolean probe = isNeg && levers;
            // Version and status levers (debug.rcs.mls_ftd_iver, _status) probe the peer's decoder
            // only: the peer rebuilds the compared receipt metadata from our IMDN XML.
            final int iVer = probe
                    ? shell.sysprops().getInt("debug.rcs.mls_ftd_iver", -1) : -1;
            final int effStatus = probe
                    ? shell.sysprops().getInt("debug.rcs.mls_ftd_status", status) : status;
            final byte[] insert = probe
                    ? MlsHex.hexToBytes(shell.sysprops().get("debug.rcs.mls_ftd_inner2", ""))
                    : null;
            final boolean before = !probe || !"after".equalsIgnoreCase(shell.sysprops().get(
                    "debug.rcs.mls_ftd_inner2_pos", "before"));
            // debug.rcs.mls_ftd_reportid (hex) overrides the reported_id bytes; empty uses the id's
            // UTF-8.
            byte[] reportOverride = probe
                    ? MlsHex.hexToBytes(shell.sysprops().get("debug.rcs.mls_ftd_reportid", ""))
                    : null;
            // Experiment, off by default: put the receipt's own id in the metadata id instead of
            // the reported id. Peers write the reported id there, so the default is the standard
            // form.
            if (probe && receiptMessageId != null && !receiptMessageId.isEmpty()
                    && (reportOverride == null || reportOverride.length == 0)
                    && shell.sysprops().getBoolean("debug.rcs.mls_ftd_selfid", false)) {
                reportOverride = receiptMessageId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                log.i("MlsImdnSigner: GATE1 self-consistency — receipt-metadata "
                        + "message_id = the receipt's OWN id "
                        + MlsMessageId.forLog(receiptMessageId) + " (== envelope "
                        + "clientMessageId == signed aad.message_id), NOT the reported "
                        + MlsMessageId.forLog(messageId)
                        + " (which stays in Original-Message-ID). reason-9 GATE1.");
            }
            inner = (reportOverride != null && reportOverride.length > 0)
                    ? VerifiableDerivedContent.deliveryImdn(
                            iVer, effStatus, reportOverride, failureReason, insert, before)
                    : VerifiableDerivedContent.deliveryImdn(
                            iVer, effStatus, messageId, failureReason, insert, before);
            if (probe && (iVer >= 0 || effStatus != status || (insert != null && insert.length > 0)
                    || (reportOverride != null && reportOverride.length > 0))) {
                log.w("MlsImdnSigner: FTD inner overrides — version="
                        + (iVer < 0 ? "def(1)" : iVer) + " status=" + effStatus + " insert="
                        + (insert == null ? 0 : insert.length) + "B [" + shell.sysprops().get(
                        "debug.rcs.mls_ftd_inner2", "")
                        + "] — DECODER probe, not a reason-9 sweep: "
                        + "some peers write those two fields themselves from our IMDN XML, "
                        + "so iver/status cannot move the compared values and a reason-9 "
                        + "null is expected. A DECODE error (51) is the informative outcome.");
            }
        }
        if (inner == null) return null;
        // The signed prefix carries this receipt's own id and the era; the inner struct carries the
        // reported id. The order matters.
        final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        // Any resend component belongs inside the delivery inner (above), not after the derived
        // content, so the trailing stays absent. debug.rcs.mls_ftd_moment adds the epoch after the
        // era, a decoder probe only; -1 keeps the production era-only form.
        final long momentEpoch = (!displayed && levers
                && failureReason != VerifiableDerivedContent.FAILURE_UNSET
                && shell.sysprops().getBoolean("debug.rcs.mls_ftd_moment", false))
                ? MlsAppMessage.epochFrom(shell.session().eraEpoch(g.groupId)) : -1L;
        if (momentEpoch >= 0L) {
            log.w("MlsImdnSigner: FTD MOMENT experiment — carrying epoch=" + momentEpoch
                    + " (full moment era||epoch) in the reason-4 derived content. THIS IS A DECODER "
                    + "probe, NOT a reason-9 sweep: the receipt struct has no era/epoch/moment "
                    + "field, so a reason-9 null here is EXPECTED and says nothing. What it CAN "
                    + "show: decode-error = the peer will not take an epoch in the derived content. "
                    + "Clear debug.rcs.mls_ftd_moment to restore era-only (the production form).");
        }
        final byte[] derived = VerifiableDerivedContent.signedImdnContent(
                receiptMessageId, eraNow > 0 ? eraNow : (int) MlsTransportTypes.ERA_INITIAL,
                momentEpoch, displayed ? VerifiableDerivedContent.TYPE_DISPLAY
                          : VerifiableDerivedContent.TYPE_DELIVERY,
                inner, /*trailing=*/ null);
        if (derived == null) return null;
        final byte[] signed = shell.session().rcsSign(g.groupId, derived);
        if (signed == null) {
            log.w("MlsImdnSigner: could not sign the "
                    + (displayed ? "display" : "delivery") + " IMDN for "
                    + MlsMessageId.forLog(messageId));
            return null;
        }
        log.i("MlsImdnSigner: signed " + (displayed ? "display" : "delivery")
                + " IMDN " + MlsMessageId.forLog(messageId) + " derived=" + derived.length
                + "B sig=" + signed.length
                + "B");
        // debug.rcs.mls_dump_sig dumps the derived content and the signature in hex.
        if (shell.sysprops().debuggableBuild()
                && shell.sysprops().getBoolean("debug.rcs.mls_dump_sig", false)) {
            log.i("MLS-SIG-DUMP " + (displayed ? "DISPLAY" : "DELIVERY")
                    + " status=" + status + " failureReason=" + failureReason
                    + " mid=" + messageId
                    + "\n  derived(" + derived.length + "B)=" + MlsHex.hexDump(derived)
                    + "\n  sig(" + signed.length + "B)=" + MlsHex.hexDump(signed));
        }
        return java.util.Base64.getEncoder().encodeToString(signed);
    }

    /**
     * Validates a peer's MLS-signed IMDN (RCC.16 §7.6.2). Both checks are required: the signature
     * verifies against a member's leaf, and the signed content matches the IMDN received, so a
     * valid signature over a different statement is not accepted.
     */
    public static ImdnCheck verifyImdn(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String headerB64, final String messageId, final boolean displayed,
            final int status, final int failureReason) {
        if (!shell.ensureSession() || headerB64 == null) return new ImdnCheck(false, false, -1);
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return new ImdnCheck(false, false, -1);
        byte[] verified;
        try {
            verified = shell.session().rcsVerify(g.groupId, shell.base64Decode(headerB64));
        } catch (final Throwable t) {
            verified = null;
        }
        if (verified == null) {
            log.w("MlsImdnSigner: IMDN signature did NOT verify for "
                    + MlsMessageId.forLog(messageId));
            return new ImdnCheck(false, false, -1);
        }
        final int leaf = ((verified[0] & 0xFF) << 24) | ((verified[1] & 0xFF) << 16)
                | ((verified[2] & 0xFF) << 8) | (verified[3] & 0xFF);
        final byte[] signedContent = java.util.Arrays.copyOfRange(verified, 4, verified.length);
        final byte[] expected = displayed
                ? VerifiableDerivedContent.displayImdn(status, messageId)
                : VerifiableDerivedContent.deliveryImdn(status, messageId, failureReason);
        final boolean matches = expected != null
                && java.util.Arrays.equals(expected, signedContent);
        if (!matches) {
            log.w("MlsImdnSigner: IMDN signature is VALID but signs a DIFFERENT "
                    + "statement than the receipt we got (" + MlsMessageId.forLog(messageId)
                    + ") — rejecting");
        }
        final ImdnCheck r = new ImdnCheck(true, matches, leaf);
        log.i("MlsImdnSigner: verifyImdn(" + MlsMessageId.forLog(messageId) + ") → " + r);
        return r;
    }

    /** The CPIM header an MLS-signed IMDN travels in (RCC.16 §7.6.2). */
    public static final String HDR_DERIVED_SIGNATURE = "MLS-Derived-Content-Signature";

    /**
     * Validates a decrypted MLS-carried IMDN (RCC.16 §7.6.2); its CPIM header exists only after
     * decryption.
     *
     * @return null if this is not a signed IMDN we can evaluate; otherwise the verdict
     */
    public static ImdnCheck verifyDecryptedImdn(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final byte[] decryptedCpim) {
        if (decryptedCpim == null || decryptedCpim.length == 0) return null;
        final String doc = new String(decryptedCpim, java.nio.charset.StandardCharsets.UTF_8);
        final String sig = MlsCpimDoc.header(doc, HDR_DERIVED_SIGNATURE);
        if (sig == null) {
            // Unsigned: not an error, but not verified either.
            log.i("MlsImdnSigner: inbound IMDN carries no "
                    + HDR_DERIVED_SIGNATURE + " — unsigned, not verified");
            return null;
        }
        final String messageId = MlsCpimDoc.element(doc, "message-id");
        if (messageId == null) {
            log.w("MlsImdnSigner: signed IMDN with no <message-id> — rejecting");
            return new ImdnCheck(false, false, -1);
        }
        final boolean displayed = doc.contains("<display-notification");
        final int status;
        if (displayed) {
            status = doc.contains("<displayed")
                    ? VerifiableDerivedContent.DISPLAY_DISPLAYED
                    : VerifiableDerivedContent.DISPLAY_ERROR;
        } else {
            status = doc.contains("<delivered")
                    ? VerifiableDerivedContent.DELIVERY_DELIVERED
                    : VerifiableDerivedContent.DELIVERY_FAILED;
        }
        return MlsImdnSigner.verifyImdn(shell, log, rcsGroupId, peerE164, sig, messageId, displayed,
                status, VerifiableDerivedContent.FAILURE_UNSET);
    }

    /**
     * Stamps for a receipt whose original message arrived in {@code rcsGroupId}, which selects the
     * conversation: keyed on the peer alone, a group message would be stamped from our 1:1 with
     * that peer, and the peer rejects it as a group id mismatch. The peer-scan fallback runs only
     * for a 1:1.
     */
    public static ImdnStamps imdnStampsFor(final MlsShellPort shell, final MlsLogSink log,
            final String peerE164, final String rcsGroupId,
            final String originalMessageId, final boolean displayed) {
        try {
            if (!shell.ensureSession()) return null;
            // Callers pass tel: URIs or formatted numbers; match on normalised digits.
            final String want = RccIdentity.normalizeE164(peerE164);
            final boolean inGroup = rcsGroupId != null && !rcsGroupId.isEmpty();
            Group g = null;
            String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
            if (key != null) g = shell.getGroup(key);
            if (g == null && !inGroup && !want.isEmpty()) {
                for (final Map.Entry<String, Group> e : shell.groups().entrySet()) {
                    final Group cand = e.getValue();
                    if (cand != null && cand.peerE164 != null
                            && RccIdentity.normalizeE164(cand.peerE164).equals(want)) {
                        g = cand;
                        key = e.getKey();
                        break;
                    }
                }
            }
            if (g == null || g.groupId == null) {
                if (inGroup) {
                    // Log it: the unsigned fallback would otherwise hide the missing group.
                    log.w("imdnStampsFor(" + MlsMessageId.forLog(originalMessageId)
                            + "): no MLS group for "
                            + LogMask.number(peerE164) + " in RCS group " + rcsGroupId
                            + " — NOT falling back to the "
                            + "1:1 with that peer; stamping it from the wrong conversation is what "
                            + "some peers reject as a GROUP_ID_MISMATCH");
                }
                return null;      // not an MLS conversation
            }
            final byte[] ea = shell.session().epochAuth(g.groupId);
            final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            // Minted here so the signature covers it; see ImdnStamps.receiptMessageId.
            final String receiptId = java.util.UUID.randomUUID().toString();
            final String sig = MlsImdnSigner.signImdn(shell, log, rcsGroupId, peerE164, receiptId,
                    originalMessageId, displayed, VerifiableDerivedContent.DELIVERY_DELIVERED,
                    VerifiableDerivedContent.FAILURE_UNSET);
            return new ImdnStamps(shell.subId(), era > 0 ? era : MlsTransportTypes.ERA_INITIAL,
                    ea == null ? null : java.util.Base64.getEncoder().encodeToString(ea),
                    sig, receiptId);
        } catch (final Throwable t) {
            log.w("imdnStampsFor(" + MlsMessageId.forLog(originalMessageId) + ") failed", t);
            return null;
        }
    }

    /**
     * Stamps for a positive delivery or display receipt, or null when this is not an MLS
     * conversation (send it the ordinary way). All values come from one group read, so the
     * signature and the epoch authenticator describe the same epoch.
     */
    public static ImdnStamps imdnStampsFor(final MlsShellPort shell, final MlsLogSink log,
            final String peerE164, final String originalMessageId, final boolean displayed) {
        return MlsImdnSigner.imdnStampsFor(shell, log, peerE164, /*rcsGroupId=*/ null,
                originalMessageId, displayed);
    }
}
