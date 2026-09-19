/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
/**
 * When our own leaf key rotates, and what a refused rotation costs. Rotation resets the application
 * ratchet to generation 0 before a long in-place run passes the receiver's {@code max_skip} window.
 * The threshold is staggered by {@link MlsRecoveryPolicy#weAreEraAdvancer} so two members under
 * mutual traffic do not commit-duel, and a refusal backs the counter off rather than restoring it.
 * The threshold is a constant, not a sysprop, so the behaviour needs no operator to arm it.
 */
public final class MlsRekeyPolicy {

    private MlsRekeyPolicy() {}

    /** Rotate our leaf after this many app sends; the app's own choice. */
    public static final int REKEY_AFTER_SENDS = 256;

    /**
     * How far below {@link #REKEY_AFTER_SENDS} a refused rotation drops the counter, in sends, so
     * the loser of a commit race lets the winner's commit land first.
     */
    public static final int REKEY_REFUSED_BACKOFF_SENDS = 64;

    /** @param weGoFirst {@link MlsRecoveryPolicy#weAreEraAdvancer} for (self, peer) */
    public static int rotateAt(final boolean weGoFirst) {
        return weGoFirst ? REKEY_AFTER_SENDS : REKEY_AFTER_SENDS + (REKEY_AFTER_SENDS / 2);
    }

    /**
     * Whether the next send carries a piggybacked key update, hence the {@code + 1}. Reads the
     * rotation counter, not the per-epoch one, which any peer or Add-only commit resets.
     */
    public static boolean rotationDueOnNextSend(final int sendsSinceLeafRotation,
            final boolean weGoFirst) {
        return sendsSinceLeafRotation + 1 >= rotateAt(weGoFirst);
    }

    /**
     * Whether the out-of-band fallback rekey is due. Unstaggered: it runs only when the piggybacked
     * rotation did not happen at all, so there is no duel to avoid.
     */
    public static boolean outOfBandRekeyDue(final int sendsSinceLeafRotation) {
        return sendsSinceLeafRotation >= REKEY_AFTER_SENDS;
    }

    /** Where both send counters land after a refused rotation; never below zero. */
    public static int counterAfterRefusal() {
        return Math.max(0, REKEY_AFTER_SENDS - REKEY_REFUSED_BACKOFF_SENDS);
    }

    /** A counter that puts the next send one short of the base threshold; for the test seam. */
    public static int seedForImminentRotation() {
        return REKEY_AFTER_SENDS - 1;
    }

    public static void noteSendAndMaybeRekey(final MlsShellPort shell, final MlsLogSink log,
            final String conversationKey, final String peerE164, final String rcsGroupId) {
        final Group g = shell.getGroup(conversationKey);
        if (g == null) return;
        g.sendsThisEpoch++;
        g.sendsSinceLeafRotation++;
        shell.putGroup(conversationKey, g);
        // Latch the conversation's MLS encryption bit on send, as the inbound path does on
        // receive; otherwise a thread we only sent into shows as not encrypted.
        shell.noteMlsPlaneInUse(conversationKey);
        if (!MlsRekeyPolicy.outOfBandRekeyDue(g.sendsSinceLeafRotation)) return;
        // Fallback only: the piggybacked rotation zeroes this counter when it lands.
        log.i("MlsRekeyPolicy: " + g.sendsSinceLeafRotation
                + " sends since our leaf last rotated on "
                + MlsConversationKey.forLog(conversationKey)
                + " → OUT-OF-BAND usage-limit rekey (the piggybacked one did not "
                + "land; this is the fallback and it can fail after the send has already gone)");
        final int era = (rcsGroupId != null && !rcsGroupId.isEmpty())
                ? shell.commitAndSend(rcsGroupId, peerE164, null, null, Op.REKEY, "usage-rekey")
                : shell.rekey(peerE164);
        if (era < 0) {
            // Leave the counter high so the next send retries; failing to rotate becomes fatal
            // only once the receiver's max_skip window is passed.
            log.w("MlsRekeyPolicy: usage-limit rekey FAILED — will retry on next send");
        }
    }

    /**
     * Sends a {@link PendingKeyUpdate}. The caller must not hold the conversation lock. On refusal
     * it undoes the engine snapshot and the cached era/epoch together, so the cache never disagrees
     * with the engine.
     */
    public static void dispatchPendingKeyUpdate(final MlsShellPort shell, final MlsLogSink log,
            final PendingKeyUpdate p) {
        if (p == null || p.commit == null || p.commit.length == 0) return;
        final MlsProviderRpc.ControlResult sent = shell.rpc("applyMlsControl").applyMlsControl(
                        p.peerE164, "mls-keyupdate-" + p.conversationKey + "-"
                        + System.currentTimeMillis(),
                /*groupInfo=*/ null, p.commit, /*tag=*/ null, /*ratchetTree=*/ null,
                /*baseEpochAuth=*/ p.baseEpochAuth, p.rcsGroupId);
        final boolean ok = sent != null && sent.verdict == MlsProviderRpc.ControlResult.VERDICT_OK;
        log.i("MlsRekeyPolicy: piggybacked key update for "
                + MlsConversationKey.forLog(p.conversationKey)
                + " (" + p.commit.length + "B) → " + (ok ? "SENT" : "REFUSED") + " [off-lock]");
        if (ok) return;   // the cache already matches the engine; nothing to undo
        if (p.rollback == null) {
            log.e("MlsRekeyPolicy: the piggybacked key update for "
                    + MlsConversationKey.forLog(p.conversationKey)
                    + " was REFUSED and the engine gave NO snapshot — we are "
                    + "epoch-AHEAD of the server and later sends will draw INVALID_ARGUMENT.");
            return;
        }
        shell.lock(p.conversationKey);
        try {
            final boolean restored = shell.session().restoreGroupSnapshot(p.groupId, p.rollback);
            final Group g2 = shell.getGroup(p.conversationKey);
            if (g2 != null) {
                // Undo the cache in the same critical section as the engine restore.
                g2.era = p.prevEra;
                g2.epochAuth = p.prevEpochAuth;
                // Back off rather than restore: the pre-commit counter is at the threshold, so
                // restoring it would retry on the next send and duel a peer that is also rotating.
                final int backedOff = MlsRekeyPolicy.counterAfterRefusal();
                g2.sendsThisEpoch = Math.min(p.prevSendsThisEpoch, backedOff);
                // Our leaf did not rotate, so the rotation counter backs off too, not to zero.
                g2.sendsSinceLeafRotation = Math.min(p.prevSendsSinceLeafRotation, backedOff);
                shell.putGroup(p.conversationKey, g2);
            }
            log.w("MlsRekeyPolicy: the piggybacked key update for "
                    + MlsConversationKey.forLog(p.conversationKey)
                    + " was REFUSED — rolled back engine=" + restored
                    + " and cache to era=" + p.prevEra + ". The message itself still went out; "
                    + "only the rotation is undone.");
            if (!restored) {
                log.e("MlsRekeyPolicy: could NOT roll back the refused key update "
                        + "for " + MlsConversationKey.forLog(p.conversationKey)
                        + " — this group may now be epoch-AHEAD of the "
                        + "server, and later sends will fail until it resyncs.");
            }
        } finally {
            shell.unlock(p.conversationKey);
        }
    }
}
