/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * Decrypts one inbound MLS application message: replays a stored result for an id already
 * decrypted, refuses a message whose AAD binds another id or whose signer is not its sender,
 * handles an RCC.16 §10.3 resend, and otherwise returns the unframed body with the group's cached
 * state advanced. See docs/mls/health-and-recovery.md.
 */
public final class MlsInboundDecrypt {
    private MlsInboundDecrypt() {}

    /**
     * Decrypts an inbound application message. {@code originalMessageId} is the outer CPIM
     * {@code Original-Message-ID} header, which peers set only on a resend and which is readable
     * without the key; it is a cross-check on the authenticated AAD component, never the decision.
     *
     * @param originalMessageId the resend's original message id, or null on an ordinary message
     * @return the parsed body, a refusal or resend marker, or null if it did not decrypt
     */
    public static RccMlsBody.Parsed decryptInbound(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String fromE164, final String messageId,
            final byte[] ciphertext, final String rcsGroupId, final String originalMessageId) {
        if (!shell.ensureSession() || ciphertext == null || ciphertext.length == 0) return null;
        final String conversationId = shell.resolveInbound(rcsGroupId, fromE164);
        if (conversationId == null) {
            log.w("MlsInboundDecrypt: inbound message from " + LogMask.number(fromE164)
                    + " with no group at all — dropped (buffering these is not implemented)");
            return null;
        }
        // Liveness evidence is that bytes arrived, readable or not, so record it before decrypting.
        MlsAdvancerElection.noteHeardFrom(shell, conversationId, fromE164);
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return null;
        // Decrypt advances the ratchet, so a duplicate must replay the stored result, not decrypt
        // again.
        final String selfId = shell.selfE164();
        // The read and the decrypt are atomic under the conversation lock: inbound messages arrive
        // on separate threads, and two concurrent deliveries of one id would both decrypt and burn
        // a generation. No transport I/O happens inside this window.
        shell.lock(conversationId);
        try {
        final MlsRendezvous.Stored already = shell.rendezvous().get(
                selfId, fromE164, messageId, MlsRendezvous.Stage.DECRYPT);
        if (already != null) {
            log.i("MlsInboundDecrypt: " + MlsMessageId.forLog(messageId) + " from "
                    + LogMask.number(fromE164)
                    + " was already decrypted (" + already + ") — REPLAYING the stored result "
                    + "instead of advancing the ratchet a second time");
            return RccMlsBody.parse(already.payload());
        }
        try {
            // Debug instrument: fail the next inbound decrypt once, so a peer-originated message
            // draws a real FTD and the peer performs a resend (peers only resend their own).
            if (MlsPayloadCorruptor.consumeFailNextDecrypt(shell)) {
                log.w("MlsInboundDecrypt: FORCING a decrypt failure for inbound "
                        + MlsMessageId.forLog(messageId) + " from " + LogMask.number(fromE164)
                        + " (one-shot receive-side instrument). "
                        + "The message is INTACT; we are pretending we could not read it so the "
                        + "sender performs a §10.3 resend we can capture.");
                return null;
            }
            // The engine decides RCC.16 §7.5.3.1; its verdict is acted on below.
            final MlsEngineIdCheck.Processed processed = MlsEngineIdCheck.process(
                    shell.session(), g.groupId, ciphertext, messageId);
            // Not final: a resend addressed to us is replaced by its unwrapped original below.
            byte[] plain = processed.plain;
            if (plain == null) {
                log.w("MlsInboundDecrypt: inbound " + MlsMessageId.forLog(messageId)
                        + " did not decrypt");
                // Read under the lock, so the park decision compares against what this decrypt saw.
                MlsInboundHold.noteDecryptFailure(shell, conversationId, messageId,
                        MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId)));
                return null;
            }
            final byte[] inboundAad = shell.session().lastInboundAad();
            // The id check passes on an empty AAD, and peers always bind one, so log its absence
            // loudly.
            if (inboundAad == null || inboundAad.length == 0) {
                log.e("MlsInboundDecrypt: inbound " + MlsMessageId.forLog(messageId) + " from "
                        + LogMask.number(fromE164)
                        + " carries NO AuthenticatedData. The §7.5.3.1 message-id binding check "
                        + "cannot assert anything about it and will pass by default. Most peers "
                        + "always bind one, so this is either a non-binding peer or our own AAD "
                        + "plumbing regressed — do not treat it as routine.");
            }
            if (cfg.dumpAadLive()) {
                // The AAD is authenticated but not encrypted, so a peer's is readable here.
                log.i("MLS-AAD-DUMP from=" + LogMask.number(fromE164) + " msgId="
                        + MlsMessageId.forLog(messageId)
                        + " len=" + (inboundAad == null ? -1 : inboundAad.length)
                        + " hex=" + MlsHex.hexDump(inboundAad));
            }
            // Diagnostic only: log a present resent-message component. Its disposition is decided
            // by MlsResendReceive below; returning null here would read as a decrypt failure.
            final byte[] aadTail = MlsAppMessage.aadTrailing(inboundAad);
            if (aadTail != null && !(aadTail.length == 1 && aadTail[0] == 0x00)) {
                log.w("MlsInboundDecrypt: " + MlsMessageId.forLog(messageId) + " from "
                        + LogMask.number(fromE164)
                        + " carries a PRESENT §10.3 resent-message component in its AAD (trailing="
                        + MlsHex.hexDump(aadTail)
                        + "). CAPTURE THESE BYTES — they are the present form "
                        + "the resend work is parked for want of (tag byte, length-prefix width, inner "
                        + "layout). Disposition is decided below by MlsResendReceive; this line is "
                        + "an instrument, not a decision.");
            }
            // RCC.16 §7.5.3.1: the AAD's message id must equal the transport's. mls-rs does not
            // check it, and every ledger keyed on message id depends on it.
            if (processed.idMismatch) {
                // A refusal, not a decrypt failure: null would trigger self-heal and an FTD. A
                // stored decrypt of the AAD's id from this peer proves a replay; a miss proves
                // nothing.
                final String aadId = processed.aadMessageId();
                final boolean provenReplay = aadId != null && !aadId.isEmpty()
                        && shell.rendezvous().get(selfId, fromE164, aadId,
                                MlsRendezvous.Stage.DECRYPT)
                                != null;
                log.e("MlsInboundDecrypt: " + MlsInboundRefusal.misbindingLine(
                        messageId, fromE164, aadId, provenReplay));
                return MlsInboundRefusal.refuse(shell, selfId, fromE164, messageId,
                        MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING);
            }
            // Sender binding: MLS proves only that some member signed; the envelope sender is
            // chosen by the sender. Compare it with the certified MSISDN of the signing leaf. An
            // unreadable credential is our gap, so it is logged loudly and passes.
            final String signer = shell.session().lastInboundSenderMsisdn();
            if (signer == null || signer.isEmpty()) {
                log.e("MlsInboundDecrypt: inbound " + MlsMessageId.forLog(messageId) + " from "
                        + LogMask.number(fromE164)
                        + " — the engine could not state WHO SIGNED it (no certified MSISDN on the "
                        + "sender's leaf). The sender binding cannot assert anything and "
                        + "passes by default; do not treat it as routine.");
            } else if (!RccIdentity.msisdnEquals(signer, fromE164)) {
                // Nothing goes out: an FTD here would be addressed to the member being
                // impersonated.
                log.e("MlsInboundDecrypt: " + MlsInboundRefusal.impersonationLine(
                        messageId, fromE164, signer));
                return MlsInboundRefusal.refuse(shell, selfId, fromE164, messageId,
                        MlsInboundRefusal.Reason.SENDER_IMPERSONATION);
            }
            // Every member decrypts a resend. The order of checks and the wire consequence of each
            // disposition live in MlsResendReceive, where host tests hold them; this method only
            // drives its predicates.
            final MlsResendReceive.Outcome resend = MlsResendReceive.evaluate(
                    MlsAppMessage.aadTrailing(inboundAad), plain,
                    MlsResendReceive.resentSelectorField(inboundAad),
                    MlsResendBudget.resendMacCandidates(shell, g),
                    MlsResendReceive.resentInnerUnwrap());
            final boolean headerSaysResend =
                    originalMessageId != null && !originalMessageId.isEmpty();
            if (headerSaysResend != resend.isResend()) {
                // Header without component suggests our inferred tag byte is wrong; component
                // without header, that the transport dropped the header.
                log.w("MlsInboundDecrypt: " + MlsResendReceive.MARKER_DISAGREEMENT
                        + " on " + MlsMessageId.forLog(messageId)
                        + " from " + LogMask.number(fromE164)
                        + " — the Original-Message-ID header says "
                        + (headerSaysResend
                                ? "RESEND of " + MlsMessageId.forLog(originalMessageId)
                                : "ORDINARY")
                        + " but the AAD component says " + (resend.isResend() ? "RESEND" : "ABSENT")
                        + " (" + resend + "). These are independent statements about one fact; if "
                        + "the header is right, our resent-component TAG expectation (0x"
                        + Integer.toHexString(MlsResentMessage.TAG_RESENT) + ", which is inferred) "
                        + "is what is wrong.");
            }
            // Check the inference that the component's opaque is the original message id.
            MlsTransportDiagnostics.logIdCandidates(log, resend, originalMessageId, inboundAad);
            final MlsResendReceive.IdCrossCheck idCheck =
                    MlsResendReceive.crossCheckOriginalMessageId(resend, originalMessageId);
            switch (idCheck) {
                case DISAGREE:
                case PAYLOAD_NOT_TEXT:
                    log.w("MlsInboundDecrypt: " + MlsResendReceive.MARKER_DISAGREEMENT
                            + " (VALUE) on " + MlsMessageId.forLog(messageId) + " from "
                            + LogMask.number(fromE164)
                            + " — "
                            + "Original-Message-ID header = '"
                            + MlsMessageId.forLog(originalMessageId)
                            + "' but the AAD "
                            + "component's opaque = "
                            + MlsResendReceive.componentPayloadForLog(resend) + " (" + idCheck
                            + "). Our inference that the opaque IS the original message id is "
                            + "WRONG, and that is the answer we wanted. RECORD THE BYTES.");
                    break;
                case AGREE:
                    log.i("MlsInboundDecrypt: the §10.3 component's opaque on "
                            + MlsMessageId.forLog(messageId)
                            + " EQUALS the Original-Message-ID header ("
                            + MlsMessageId.forLog(originalMessageId)
                            + "). That inference SURVIVES its first real "
                            + "test; the opaque is the authenticated copy of the id.");
                    break;
                default:
                    // Nothing was compared; no line, or every ordinary message would log one.
                    break;
            }
            if (resend.isResend()) {
                log.i("MlsInboundDecrypt: " + MlsMessageId.forLog(messageId) + " from "
                        + LogMask.number(fromE164)
                        + " is a §10.3 RESEND of "
                        + (headerSaysResend ? MlsMessageId.forLog(originalMessageId)
                                : "<no Original-Message-ID header>") + " → " + resend);
            }
            if (MlsResendReceive.silentDrop(resend.disposition)) {
                // Silent drop: no chat row, receipt, FTD, recovery or health transition. The
                // ratchet advanced, so the decision is stored for replay, and the marker (not null,
                // which means "did not decrypt") is returned.
                if (resend.disposition == MlsResendReceive.Disposition.NOT_FOR_ME) {
                    log.i("MlsInboundDecrypt: " + MlsResendReceive.notForMeLine(
                            headerSaysResend ? originalMessageId : messageId, rcsGroupId));
                } else if (resend.disposition
                        == MlsResendReceive.Disposition.FOR_ME_UNWRAP_UNAVAILABLE) {
                    // A named MAC candidate matched: log it, since the send side needs it.
                    log.e("MlsInboundDecrypt: *** " + MlsResendReceive.MARKER_FOR_ME
                            + " *** " + MlsMessageId.forLog(messageId)
                            + " — MAC input identified as '"
                            + resend.matchedCandidate
                            + "'. RECORD THAT CANDIDATE: it is the MAC "
                            + "input we could not determine statically, and it unblocks the SEND "
                            + "side. NOT 'recipient-specific' — that description fell with the "
                            + "selector model: the MAC'd datum is recipient-INVARIANT. "
                            + "Dropping the message SILENTLY because we "
                            + "hold no inner unwrapper — reporting would blame the sender "
                            + "for our own gap. Component bytes: " + resend.component);
                } else if (resend.disposition
                        == MlsResendReceive.Disposition.SELECTOR_UNAVAILABLE) {
                    // Our gap (where the selector field lives), not the sender's fault.
                    log.e("MlsInboundDecrypt: a §10.3 RESEND arrived on "
                            + MlsMessageId.forLog(messageId)
                            + " from " + LogMask.number(fromE164) + " and we "
                            + MlsResendReceive.MARKER_SELECTOR_UNAVAILABLE + " — we do not "
                            + "know where the 64-byte recipient-selector field lives in a resend "
                            + "(SEAM 1, resentSelectorField). Dropped SILENTLY; this says "
                            + "nothing about the sender. Component opaque = "
                            + MlsResendReceive.componentPayloadForLog(resend) + " " + resend
                            + " — THESE BYTES ARE THE ARTIFACT. Record them: they are "
                            + "the first present-form component we have ever seen.");
                } else {
                    log.w("MlsInboundDecrypt: " + MlsResendReceive.MARKER_MALFORMED
                            + " — the resend " + MlsMessageId.forLog(messageId) + " is "
                            + resend.disposition + " (" + resend + "), structurally broken "
                            + "rather than simply not-for-us. Dropped silently: some peers error "
                            + "here (104/105) but whether that reaches the wire is unestablished, "
                            + "and a per-member receipt for one resend is the storm we are "
                            + "avoiding. This is a capture worth keeping.");
                }
                // Store the framed marker, not the wrapper, or a redelivery would render the
                // wrapper.
                shell.rendezvous().put(selfId, fromE164, messageId, MlsRendezvous.Stage.DECRYPT,
                        new MlsRendezvous.Stored(MlsProcStatus.APP,
                                RccMlsBody.frame(new byte[0],
                                        RccContentDisposition.RESEND_NOT_FOR_ME, /*inline=*/ true),
                                System.currentTimeMillis()));
                return new RccMlsBody.Parsed(RccContentDisposition.RESEND_NOT_FOR_ME, new byte[0]);
            }
            if (MlsResendReceive.reports(resend.disposition)) {
                // The only arm that reports: the resend is ours but its inner unwrap failed. Report
                // without self-healing, since the outer message decrypted. Reason 6 is sent as
                // failed-to-decrypt (4), because peers drop the whole receipt on 6
                // (Reason.forEmit).
                log.e("MlsInboundDecrypt: " + MlsResendReceive.MARKER_FOR_ME + " — "
                        + MlsMessageId.forLog(messageId) + " (candidate '" + resend.matchedCandidate
                        + "') and its INNER unwrap "
                        + "FAILED. Reporting "
                        + RccNegativeDeliveryImdn.Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT
                                .forEmit().xmlElement()
                        + " to " + LogMask.number(fromE164)
                        + " WITHOUT self-healing — the outer message decrypted, "
                        + "so the group is in step and the fault is in how the resend was BUILT.");
                // Stored like the silent arm, so a redelivery does not report twice.
                shell.rendezvous().put(selfId, fromE164, messageId, MlsRendezvous.Stage.DECRYPT,
                        new MlsRendezvous.Stored(MlsProcStatus.APP,
                                RccMlsBody.frame(new byte[0],
                                        RccContentDisposition.RESEND_NOT_FOR_ME, /*inline=*/ true),
                                System.currentTimeMillis()));
                // Off-thread: the conversation lock is held and must not span transport I/O.
                final String ftdGroup = rcsGroupId;
                final String ftdPeer = fromE164;
                final String ftdMid = messageId;
                shell.offThread().run("mls-resend-ftd", new Runnable() {
                    @Override public void run() {
                        MlsFtdEscalation.reportFtdWithoutHealing(shell, log, ftdGroup, ftdPeer,
                                ftdMid);
                    }
                });
                return new RccMlsBody.Parsed(RccContentDisposition.RESEND_NOT_FOR_ME, new byte[0]);
            }
            if (resend.disposition == MlsResendReceive.Disposition.FOR_ME) {
                // Deliver the unwrapped original: peers nest the original ciphertext in the resend.
                log.i("MlsInboundDecrypt: " + MlsResendReceive.MARKER_FOR_ME + " — "
                        + MlsMessageId.forLog(messageId) + " (candidate '" + resend.matchedCandidate
                        + "') and UNWRAPPED to "
                        + resend.inner.length + "B — delivering the ORIGINAL "
                        + MlsMessageId.forLog(headerSaysResend ? originalMessageId : messageId)
                        + ", not the wrapper.");
                plain = resend.inner;
            }
            // A commit can ride an application message; keep our cached stamps in step with the
            // engine or the next send goes out at a stale era.
            final byte[] ee = shell.session().eraEpoch(g.groupId);
            final int afterEra = MlsAppMessage.eraFrom(ee);
            if (afterEra >= 0) {
                g.era = afterEra;
                g.epochAuth = shell.session().epochAuth(g.groupId);
                shell.putGroup(conversationId, g);
            }
            // Store before handing on, so a process death before the chat row is replayed.
            shell.rendezvous().put(selfId, fromE164, messageId, MlsRendezvous.Stage.DECRYPT,
                    new MlsRendezvous.Stored(MlsProcStatus.APP, plain, System.currentTimeMillis()));
            // A successful decrypt proves we hold the sender's epoch secrets, so move to Healthy.
            // moveHealth refuses pairs the table lacks and is a no-op when already Healthy.
            shell.moveHealth(conversationId, MlsHealthStates.HEALTHY,
                    "an inbound message decrypted — the group is demonstrably in step");
            final RccMlsBody.Parsed parsed = RccMlsBody.parse(plain);
            // debug.rcs.mls_log_plaintext: dump the decrypted headers and bytes. The dump is the
            // message itself, so it also needs a debuggable build: adb can set a debug.* prop.
            if (shell.sysprops().debuggableBuild() && (cfg.dumpAad || shell.sysprops().getBoolean(
                    "debug.rcs.mls_log_plaintext", false))) {
                final StringBuilder hx = new StringBuilder();
                for (int i = 0; plain != null && i < Math.min(1024, plain.length); i++) {
                    hx.append(String.format("%02x", plain[i]));
                }
                log.i("MLS-PLAINTEXT " + messageId + " from " + fromE164 + " "
                        + (plain == null ? 0 : plain.length) + "B ct="
                        + (parsed == null ? "?" : parsed.contentType)
                        + " bodyLen=" + (parsed == null || parsed.body == null
                                ? 0 : parsed.body.length)
                        + "\n  HEADERS<<" + (parsed == null ? "" : parsed.headers) + ">>"
                        + "\n  HEX " + hx);
            }
            log.i("MlsInboundDecrypt: decrypted inbound " + MlsMessageId.forLog(messageId)
                    + " from "
                    + LogMask.number(fromE164) + " " + ciphertext.length + "B → "
                    + (parsed == null ? 0
                            : parsed.body == null ? 0 : parsed.body.length)
                    + "B ct=" + (parsed == null ? "?" : parsed.contentType) + " era=" + afterEra);
            // Check MlsWireScan.epochOf, which decides park-versus-report on the failure path,
            // against the engine's epoch for a frame that just decrypted. Logged, never enforced.
            try {
                final Group gWire = shell.getGroup(conversationId);
                if (gWire != null && gWire.groupId != null) {
                    final MlsAppMessage.Moment now = MlsAppMessage.Moment.from(
                            shell.session().eraEpoch(gWire.groupId));
                    final long scanned = MlsWireScan.epochOf(ciphertext);
                    if (now != null) {
                        final boolean agree = scanned == now.epoch;
                        log.i("MlsInboundDecrypt: WIRESCAN-ORACLE " + MlsMessageId.forLog(messageId)
                                + " MlsWireScan.epochOf=" + scanned + " engine epoch=" + now.epoch
                                + (agree ? " AGREE" : " ⚠ DISAGREE — epochOf is misreading a REAL "
                                        + "frame; it decides park-vs-report on the failure path"));
                    }
                }
            } catch (final Throwable ignored) {
                // A diagnostic must never break a message that decrypted.
            }
            return parsed;
        } catch (final Throwable t) {
            log.w("MlsInboundDecrypt: inbound decrypt failed", t);
            return null;
        }
        } finally {
            shell.unlock(conversationId);
        }
    }

    /**
     * After a failed decrypt with no group, waits up to {@code windowMs} for a concurrent join and
     * retries once; returns null if no group appeared.
     */
    public static RccMlsBody.Parsed awaitJoinAndRetryDecrypt(final MlsConfig cfg,
            final MlsShellPort shell, final MlsLogSink log, final String fromE164,
            final String messageId,
            final byte[] ciphertext, final String rcsGroupId, final long windowMs,
            final String originalMessageId) {
        if (ciphertext == null || fromE164 == null) return null;
        // Only the no-group case is a race; with a group, a retry would fail again.
        if (shell.resolveInbound(rcsGroupId, fromE164) != null) return null;
        final long deadline = shell.elapsedRealtime() + Math.max(0L, windowMs);
        String conv = null;
        while (shell.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            conv = shell.resolveInbound(rcsGroupId, fromE164);
            if (conv != null) break;
        }
        if (conv == null) {
            log.i("MlsInboundDecrypt: " + MlsMessageId.forLog(messageId) + " from "
                    + LogMask.number(fromE164)
                    + " failed to "
                    + "decrypt and we hold NO group for it; waited " + windowMs + "ms and no join "
                    + "arrived — taking the ordinary §10/FTD path. Telling the sender is correct "
                    + "here: without a Welcome we cannot read this message by any action of our own.");
            return null;
        }
        final RccMlsBody.Parsed retry = MlsInboundDecrypt.decryptInbound(cfg, shell, log, fromE164,
                messageId, ciphertext, rcsGroupId, originalMessageId);
        // A refusal returns a non-null marker, which must not be logged as success.
        final boolean retryRefused = retry != null
                && MlsInboundRefusal.isRefusal(retry.contentType);
        log.i("MlsInboundDecrypt: JOIN RACE on " + MlsMessageId.forLog(messageId) + " from "
                + LogMask.number(fromE164)
                + " — the join landed while we were failing the decrypt; retry "
                + (retryRefused
                        ? "decrypted and was then REFUSED ("
                                + MlsInboundRefusal.forMarker(retry.contentType)
                                + ") — still no FTD and no §10 recovery, but this message is NOT "
                                + "delivered and the refusal above is the reason"
                        : retry != null && retry.body != null
                                ? "SUCCEEDED (no FTD, no §10 recovery, no spurious resend)"
                                : "still failed, taking the §10 path"));
        return retry;
    }
}
