/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.log.LogMask;
/**
 * The send side of an RCC.16 §10.3 resend: recover the original's body, record the resend in the
 * ledger before sending, and send it under a new id. See docs/mls/health-and-recovery.md.
 */
public final class MlsResend {
    private MlsResend() {}

    /** Records the resend and sends it; the gate is known to be open. The one path to the wire. */
    public static boolean sendResendNow(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String originalMessageId, final String reportedMessageId,
            final byte[] framedBody, final String convKey, final String conversationId) {
        final String newMessageId = shell.resendLedger().recordResend(originalMessageId,
                /*replaces=*/ reportedMessageId, peerE164, /*recipientClientId=*/ null,
                convKey, System.currentTimeMillis());
        if (newMessageId == null) {
            // Never send under the old id: an unrecorded resend never advances the ladder, and
            // the server deduplicates on message id anyway.
            log.e("MlsResend: could not record a resend of "
                    + MlsMessageId.forLog(originalMessageId)
                    + " — NOT sending it. The escalation ladder needs the row.");
            return false;
        }

        if (rcsGroupId != null && !rcsGroupId.isEmpty()) {
            return shell.sendFramedToGroup(rcsGroupId, framedBody, "mls-grp", newMessageId);
        }
        // 1:1: seal at the epoch we hold now. The original id's cached ciphertext is left alone
        // (RCC.16 §12.8); this is a different message with its own cache entry.
        final MlsSendPayload p =
                MlsSealSend.encryptForSend(shell, log, conversationId, framedBody, newMessageId);
        if (p == null) return false;
        return MlsSealSend.sendSealed(shell, log, peerE164, p);
    }

    public static boolean resendFramed(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String originalMessageId, final String reportedMessageId,
            final byte[] framedBody, final boolean allowDefer) {
        if (framedBody == null || framedBody.length == 0) {
            log.w("MlsResend: cannot resend " + MlsMessageId.forLog(originalMessageId)
                    + " — the stored text recovered but re-framing produced an EMPTY body.");
            return false;
        }
        final String convKey = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);

        // The rekey this remedy just performed shut the 1:1 send-gate, and the peer cannot read the
        // new epoch until it converges, so defer rather than bypass. Flushed on convergence and on
        // gate expiry. No ledger row until the send is attempted, or deferrals would climb the
        // ladder. The group path does not consult the gate.
        if (rcsGroupId != null && !rcsGroupId.isEmpty()) {
            return MlsResend.sendResendNow(shell, log, rcsGroupId, peerE164, originalMessageId,
                    reportedMessageId, framedBody, convKey, /*conversationId=*/ null);
        }
        final String conversationId = shell.resolveInbound(rcsGroupId, peerE164);
        if (conversationId == null) {
            log.w("MlsResend: cannot resend " + MlsMessageId.forLog(originalMessageId)
                    + " — no conversation id resolved for peer " + LogMask.number(peerE164)
                    + " (1:1 path; a group resend does not reach here).");
            return false;
        }
        if (MlsResendBudget.sendBlockedByGate(shell, log, conversationId)) {
            // A user resend refuses rather than defers: the caller writes the row's status from
            // this answer, and a deferral is not a send.
            if (!allowDefer) {
                log.i("MlsResend: REFUSING the user-initiated resend of "
                        + MlsMessageId.forLog(originalMessageId) + " — the send-gate is shut for "
                        + MlsConversationKey.forLog(conversationId)
                        + " (awaiting peer convergence). Not deferring it: the caller reports this "
                        + "answer to the user as the outcome, and a deferral is not an outcome.");
                return false;
            }
            return MlsResendBudget.deferGatedResend(shell, log, conversationId, new DeferredResend(
                    rcsGroupId, peerE164, originalMessageId, reportedMessageId, framedBody));
        }
        return MlsResend.sendResendNow(shell, log, rcsGroupId, peerE164, originalMessageId,
                reportedMessageId, framedBody, convKey, conversationId);
    }

    /** {@code resendFramed} with deferral allowed. */
    public static boolean resendFramed(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String originalMessageId, final String reportedMessageId,
            final byte[] framedBody) {
        return MlsResend.resendFramed(shell, log, rcsGroupId, peerE164, originalMessageId,
                reportedMessageId, framedBody, /*allowDefer=*/ true);
    }

    public static boolean resendOriginal(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String reportedMessageId, final boolean allowDefer) {
        // A peer names whichever id it saw, so a second failure names a resend's id, which has
        // no chat row; resolve root-ward first. Ids that are not resends come back unchanged.
        // TODO: recover the cached ciphertext instead of re-encrypting the plaintext.
        final String originalMessageId = shell.resendLedger().rootOf(reportedMessageId);
        final boolean groupWireId = MlsMessageId.isLegacyGroupWireId(originalMessageId);
        final boolean wireIdNamespace = originalMessageId.startsWith("mls-");
        // Stored body first: it is written at seal time, while the chat row is written after
        // dispatch, so a fast report can arrive before the row exists. The row is the fallback.
        final byte[] storedBody = shell.pendingBodies().get(originalMessageId);
        if (storedBody != null) {
            log.i("MlsResend: resending " + MlsMessageId.forLog(originalMessageId)
                    + " from the stored framed body (" + storedBody.length + "B) — no chat-row "
                    + "lookup needed");
            return MlsResend.resendFramed(shell, log, rcsGroupId, peerE164, originalMessageId,
                    reportedMessageId, storedBody, allowDefer);
        }
        final String text = shell.findTextByRcsMessageId(originalMessageId);
        if (text == null || text.isEmpty()) {
            if (groupWireId) {
                // A group wire id is minted by a group send with no chat row to bind, so its body
                // is unrecoverable by construction.
                log.w("MlsResend: cannot resend " + MlsMessageId.forLog(originalMessageId)
                        + " — it "
                        + "is a GROUP wire id (mls-grp-<rcsGroupId>-<stamp>), minted by a send that "
                        + "had no chat row to bind, so there is no stored body to recover. The group "
                        + "itself resolved fine; it is the TEXT that is unavailable. A UI group send "
                        + "writes a row and does not land here.");
            } else if (wireIdNamespace) {
                log.w("MlsResend: cannot resend " + MlsMessageId.forLog(originalMessageId)
                        + " — it "
                        + "is a LEGACY 1:1 WIRE id (mls-<conv>-e<era>-<gen>) from a message sent "
                        + "before the id namespaces were unified, so it was never a row "
                        + "id. New sends bind the app's rcs_message_id and do resolve here.");
            } else if (originalMessageId.equals(reportedMessageId)) {
                // The id resolved to itself; if it was a resend, its ledger row is gone.
                log.w("MlsResend: no stored text for " + MlsMessageId.forLog(originalMessageId)
                        + " — cannot resend it. It did NOT resolve root-ward, so it is not a link in "
                        + "any chain we still hold: if it was a RESEND, its ledger row is GONE and "
                        + "the chain has been orphaned.");
            } else {
                log.w("MlsResend: no stored text for " + MlsMessageId.forLog(originalMessageId)
                        + " — cannot resend it.");
            }
            return false;
        }

        // A resend is a new message with a new id (RCC.16 §11.1): the server deduplicates on
        // message id, and client receipts never clean the ciphertext cache (RCC.16 §12.8).
        return MlsResend.resendFramed(shell, log, rcsGroupId, peerE164, originalMessageId,
                reportedMessageId, RccMlsBody.frameText(text), allowDefer);
    }

    public static boolean resendOriginal(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String reportedMessageId) {
        return MlsResend.resendOriginal(shell, log, rcsGroupId, peerE164, reportedMessageId,
                /*allowDefer=*/ true);
    }

    /**
     * The user's Resend on a failed MLS chat row. Unlike the automatic path it applies no resend
     * budget and refuses rather than defers at a shut gate. The chain seals, so the caller offers
     * it only for an E2EE row. The row keeps its original {@code rcs_message_id}; receipts for the
     * new id resolve back through the ledger.
     *
     * @return true iff the resend reached the wire and was accepted
     */
    public static boolean resendByUser(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) {
            log.w("MlsResend: resendByUser called with no rcs message id — "
                    + "nothing to root-resolve, so there is no body to recover.");
            return false;
        }
        log.i("MlsResend: USER-initiated resend of " + MlsMessageId.forLog(rcsMessageId)
                + (rcsGroupId == null || rcsGroupId.isEmpty() ? " to " + LogMask.number(peerE164)
                        : " in group " + rcsGroupId)
                + " — no ladder budget applies and the send-gate will refuse rather than defer "
                + ".");
        return MlsResend.resendOriginal(shell, log, rcsGroupId, peerE164, rcsMessageId,
                /*allowDefer=*/ false);
    }

    /**
     * Sends everything parked at {@code key}'s gate; call after the gate is released. Runs
     * off-thread because both release points are provider callback threads.
     */
    public static void flushGatedResends(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final java.util.List<DeferredResend> due;
        final ConvState s = shell.convIfAny(key);
        if (s == null) return;
        synchronized (s) {
            if (s.gatedResends.isEmpty()) return;
            due = new java.util.ArrayList<DeferredResend>(s.gatedResends);
            // Drain, not remove: the rest of the state belongs to a live conversation.
            s.gatedResends.clear();
        }
        shell.offThread().run("mls-gate-flush", new Runnable() {
            @Override public void run() {
                log.i("MlsResend: send-gate open for " + MlsConversationKey.forLog(key)
                        + " — flushing " + due.size() + " deferred §6.2 resend(s)");
                for (final DeferredResend d : due) {
                    try {
                        final boolean ok = MlsResend.sendResendNow(shell, log, d.rcsGroupId,
                                d.peerE164, d.originalMessageId, d.reportedMessageId, d.framedBody,
                                MlsConversationKey.canonicalKey(d.rcsGroupId, d.peerE164), key);
                        log.i("MlsResend: deferred resend of "
                                + MlsMessageId.forLog(d.originalMessageId) + " → "
                                + (ok ? "SENT" : "FAILED"));
                    } catch (final Throwable t) {
                        log.e("MlsResend: deferred resend of "
                                + MlsMessageId.forLog(d.originalMessageId) + " threw", t);
                    }
                }
            }
        });
    }
}
