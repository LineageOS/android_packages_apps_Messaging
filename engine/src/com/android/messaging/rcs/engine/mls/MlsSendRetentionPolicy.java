/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
import java.util.List;
/**
 * How long a sent message's replay material (sealed ciphertext and pending body) is kept for an
 * RCC.16 §10.3 resend. A positive receipt is terminal for a 1:1 but not for a group, where one
 * member's success says nothing about the others: a group message is released once every current
 * member has confirmed, on permanent failure, or when it ages out. Keeping bytes too long costs a
 * slot; dropping them early loses a message for good.
 */
public final class MlsSendRetentionPolicy {

    private MlsSendRetentionPolicy() {}

    /**
     * The retention window for send-side replay material: 24 h, the app's own choice. Long enough
     * for a peer offline overnight to report a failure; short enough not to fill the store's cap.
     * It also backs the "Not sent" retry the app shows for rows failed at startup, so do not
     * shorten it, and do not reuse it as a delivery deadline.
     */
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /** Whether a positive delivery receipt may release replay material: only for a 1:1. */
    public static boolean releaseOnPositiveReceipt(final boolean isGroupMessage) {
        return !isGroupMessage;
    }

    /**
     * Whether a permanent send failure may release the failed attempt's material: always. Says
     * nothing about the rest of its resend chain; see {@link #retireChainOnTerminal}.
     */
    public static boolean releaseOnPermanentFailure() {
        return true;
    }

    /**
     * Whether a terminal event retires the resend chain's ledger rows: only a delivery. After a
     * delivery they are stale ladder evidence, so they stop counting; after a failure they are the
     * only link from a resend's id back to the root that holds the body, and they still count.
     * Retiring keeps the rows, because a receipt naming the resend (the delivery receipt itself,
     * which the status update resolves after this release, and a displayed receipt later) reaches
     * the original row only through them. They are deleted {@link #RETIRED_CHAIN_MAX_AGE_MS} later.
     *
     * @param delivered true for a positive delivery receipt, false for a permanent send failure
     */
    public static boolean retireChainOnTerminal(final boolean delivered) {
        return delivered;
    }

    /**
     * How long a delivered chain's rows keep mapping a resend's receipts to the original row: 7
     * days, for a displayed receipt from a peer who reads the message days later. The rows are a
     * few short strings each; the replay bytes are gone at delivery.
     */
    public static final long RETIRED_CHAIN_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * "No elapsed stamp". Negative, since 0 is a legal {@code elapsedRealtime()} reading just after
     * boot.
     */
    public static final long UNSTAMPED = -1L;

    /**
     * Whether material stamped {@code storedElapsedMs} has aged out. Ages by
     * {@link MlsMonotonicAge}, never the wall clock, whose forward jumps would delete in-flight
     * material: an entry may be retained past its window after a reboot but never dropped early.
     * {@link #UNSTAMPED} is expired, since nothing else could ever age it; an older-format row is
     * adopted by its caller instead.
     */
    public static boolean expiredMonotonic(final long nowElapsedMs, final long storedElapsedMs,
            final long maxAgeMs) {
        if (storedElapsedMs < 0L) return true;
        return MlsMonotonicAge.ageMs(storedElapsedMs, nowElapsedMs) >= maxAgeMs;
    }

    /** Ages out send-side replay material past the retention window. */
    public static void sweepExpiredSendMaterial(final MlsShellPort shell, final MlsLogSink log) {
        try {
            for (final String id
                    : shell.pendingBodies().sweepExpired(
                            MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS)) {
                shell.sealedCache().release(id);
            }
            // Sweep the ciphertext cache on its own elapsed clock too: entries whose body is
            // already gone are unreachable from the loop above and would fill the cache, which
            // declines rather than evicts.
            shell.sealedCache().sweepExpired(MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS,
                    shell.elapsedRealtime());
        } catch (final Throwable t) {
            log.w("MlsSendRetentionPolicy: retention sweep failed", t);
        }
        try {
            final int gone = shell.resendLedger().forgetRetiredChains(
                    MlsSendRetentionPolicy.RETIRED_CHAIN_MAX_AGE_MS);
            if (gone > 0) {
                log.i("MlsSendRetentionPolicy: deleted " + gone + " resend row(s) of chains "
                        + "delivered over " + (RETIRED_CHAIN_MAX_AGE_MS / 86400000L) + " days ago; "
                        + "a receipt naming one of those resends no longer reaches its row.");
            }
        } catch (final Throwable t) {
            log.w("MlsSendRetentionPolicy: retired resend chain sweep failed", t);
        }
    }

    public static void releaseSealed(final MlsShellPort shell, final MlsLogSink log,
            final String rcsMessageId, final boolean delivered) {
        // On delivery, release the whole resend chain, since the receipt may name the resend that
        // got through; on failure, only the failed attempt (MlsResendRecord.materialToRelease).
        // Resolve the root first; siblings() is keyed by it.
        final String root = shell.resendLedger().rootOf(rcsMessageId);
        java.util.List<MlsResendRecord> siblings = java.util.Collections.emptyList();
        try {
            siblings = shell.resendLedger().siblings(root);
        } catch (final Throwable t) {
            // A ledger read must never stop the release of the id we were handed.
            log.w("MlsSendRetentionPolicy: could not enumerate the resend chain for " + root
                    + " — releasing only " + MlsMessageId.forLog(rcsMessageId), t);
        }
        final java.util.Set<String> chain =
                MlsResendRecord.materialToRelease(rcsMessageId, root, siblings, delivered);
        for (final String id : chain) {
            if (id == null || id.isEmpty()) continue;
            shell.sealedCache().release(id);
            // Same key for both stores, or one grows to its ceiling.
            shell.pendingBodies().release(id);
        }
        if (delivered && chain.size() > 1) {
            log.i("MlsSendRetentionPolicy: " + MlsMessageId.forLog(rcsMessageId)
                    + " reached a terminal state — "
                    + "released the send material for its WHOLE chain " + chain + " (root " + root
                    + "). The receipt names the attempt that got through; the bytes are held under "
                    + "the attempts that did not.");
        } else if (!delivered && !siblings.isEmpty()) {
            // Say what was kept, not just what went.
            log.i("MlsSendRetentionPolicy: " + MlsMessageId.forLog(rcsMessageId)
                    + " FAILED permanently — released "
                    + chain + " and KEPT the rest of resend chain " + root + " (" + siblings.size()
                    + " row(s)). A failure is not the end of a chain: the message did not arrive, so "
                    + "the root's body and the rows that resolve a resend id back to it are still "
                    + "what §10.3 needs.");
        }
        // A delivery ends the chain's ladder evidence; a failure ends nothing. The rows stay, so
        // this receipt's own status update, which runs after this, and a later displayed receipt
        // still resolve the resend's id to the original row.
        if (!MlsSendRetentionPolicy.retireChainOnTerminal(delivered)) return;
        final int retired = shell.resendLedger().retireChain(root);
        if (retired > 0) {
            log.i("MlsSendRetentionPolicy: " + MlsMessageId.forLog(rcsMessageId)
                    + " reached a terminal state — "
                    + "retired " + retired + " resend row(s) of chain " + root + ": no longer "
                    + "escalation evidence, kept so receipts naming a resend still reach the "
                    + "original row.");
        }
    }

    /**
     * Releases after a permanent send failure: the failed attempt's material only. The rest of its
     * chain, including the ledger rows, stays.
     */
    public static void releaseSealedOnPermanentFailure(final MlsShellPort shell,
            final MlsLogSink log, final String rcsMessageId) {
        if (!MlsSendRetentionPolicy.releaseOnPermanentFailure()) return;
        MlsSendRetentionPolicy.releaseSealed(shell, log, rcsMessageId, /*delivered=*/ false);
    }

    /**
     * The group a message we sent went to: {@code null} for a proven 1:1,
     * {@link MlsMessageId#UNKNOWN_CONVERSATION} when it cannot be told. Three routes: the message
     * store for a UI send, the id itself for {@code mls-grp-<id>-<stamp>}, and the resend ledger
     * for a resend, whose bare UUID has neither.
     */
    public static String groupIdForSentMessage(final MlsShellPort shell, final MlsLogSink log,
            final String rcsMessageId) {
        // The ledger's conversation key is the only record of a resend's conversation.
        String ledgerKey = null;
        String root = rcsMessageId;
        try {
            ledgerKey = shell.resendLedger().conversationKeyOf(rcsMessageId);
            root = shell.resendLedger().rootOf(rcsMessageId);
        } catch (final Throwable t) {
            log.w("MlsSendRetentionPolicy: could not read the resend ledger for "
                    + MlsMessageId.forLog(rcsMessageId) + " while resolving its conversation", t);
        }
        String fromStore = null;
        try {
            fromStore = shell.findGroupIdByRcsMessageId(root);
        } catch (final Throwable t) {
            // Unknown is treated as a group, keeping the bytes.
            log.w("MlsSendRetentionPolicy: could not resolve a group for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " — keeping its resend material on the safe side", t);
            return MlsMessageId.UNKNOWN_CONVERSATION;
        }
        return MlsMessageId.groupOfSentMessage(rcsMessageId, ledgerKey, root, fromStore);
    }

    /**
     * Releases after a positive delivery, the terminal that ends a resend chain. A group receipt
     * goes through {@link #releaseSealedOnPositiveReceipt} or {@link #releaseSealedOnGroupReceipt};
     * a permanent failure through {@link #releaseSealedOnPermanentFailure}.
     */
    public static void releaseSealed(final MlsShellPort shell, final MlsLogSink log,
            final String rcsMessageId) {
        MlsSendRetentionPolicy.releaseSealed(shell, log, rcsMessageId, /*delivered=*/ true);
    }

    /**
     * Releases on a positive receipt with no sender: terminal for a 1:1, not for a group, whose
     * material then lives until every member confirms, it fails permanently, or it ages out.
     */
    public static void releaseSealedOnPositiveReceipt(final MlsShellPort shell,
            final MlsLogSink log, final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        final String groupId =
                MlsSendRetentionPolicy.groupIdForSentMessage(shell, log, rcsMessageId);
        if (!MlsSendRetentionPolicy.releaseOnPositiveReceipt(groupId != null)) {
            log.i("MlsSendRetentionPolicy: delivery receipt for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " is for GROUP " + groupId + " — keeping its resend material. One member "
                    + "confirming says nothing about the others, and a later failure report needs "
                    + "these bytes. Released on permanent failure or after "
                    + (MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS / 3600000L) + "h.");
            MlsSendRetentionPolicy.sweepExpiredSendMaterial(shell, log);
            return;
        }
        MlsSendRetentionPolicy.releaseSealed(shell, log, rcsMessageId);
        // Sweep on the 1:1 path too, or a 1:1-only device never enforces the window.
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(shell, log);
    }

    /**
     * Releases a group message's replay material once every member of today's roster, ourselves
     * excluded, has confirmed it. The roster is re-read per receipt, since a member removed while
     * the message was in flight would otherwise be waited on forever.
     */
    public static void releaseSealedOnGroupReceipt(final MlsShellPort shell, final MlsLogSink log,
            final String rcsMessageId, final String fromE164) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (fromE164 == null || fromE164.isEmpty()) return;
        final String rcsGroupId =
                MlsSendRetentionPolicy.groupIdForSentMessage(shell, log, rcsMessageId);
        // Not a group message we sent; the 1:1 path owns it.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return;
        if (!shell.ensureSession()) return;

        final java.util.List<String> roster;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, null);
        if (key == null) return;
        // An IMDN is liveness evidence for the advancer election.
        MlsAdvancerElection.noteHeardFrom(shell, key, fromE164);
        // A positive IMDN also ends the peer's G4 failure streak, a stronger statement than the
        // liveness note above.
        shell.peerGuard().notePeerRecovered(fromE164);
        shell.lock(key);
        try {
            final Group g = shell.getGroup(key);
            if (g == null || g.groupId == null) return;
            roster = MlsServerBundle.ourRoster(shell, log, key, g);
        } finally { shell.unlock(key); }
        if (roster == null || roster.isEmpty()) {
            // Unreadable and empty look the same here, and neither is coverage.
            log.i("MlsSendRetentionPolicy: group receipt for " + MlsMessageId.forLog(rcsMessageId)
                    + " from "
                    + LogMask.number(fromE164)
                    + " but the roster is unreadable — keeping the resend material and "
                    + "letting the retention window own it.");
            return;
        }
        final String self = shell.selfE164();
        final java.util.List<String> others = new java.util.ArrayList<>(roster.size());
        for (final String m : roster) {
            if (m != null && !m.isEmpty() && !m.equals(self)) others.add(m);
        }
        final boolean covered;
        synchronized (shell.groupDelivery()) {
            shell.groupDelivery().record(rcsMessageId, fromE164);
            for (final String gone : shell.groupDelivery().takeEvicted()) {
                log.w("MlsSendRetentionPolicy: dropped group delivery tracking for " + gone
                        + " at the ledger cap — it can no longer reach coverage, so its resend "
                        + "material now waits the full retention window.");
            }
            covered = shell.groupDelivery().isCovered(rcsMessageId, others);
            if (covered) shell.groupDelivery().forget(rcsMessageId);
        }
        if (!covered) {
            log.i("MlsSendRetentionPolicy: group receipt for " + MlsMessageId.forLog(rcsMessageId)
                    + " from "
                    + LogMask.number(fromE164) + " — "
                    + shell.groupDelivery().confirmedCount(rcsMessageId) + " of "
                    + others.size() + " member(s) have confirmed; keeping the resend material.");
            return;
        }
        log.i("MlsSendRetentionPolicy: ALL " + others.size() + " member(s) have confirmed "
                + MlsMessageId.forLog(rcsMessageId)
                + " — releasing its resend material now rather than after "
                + (MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS / 3600000L) + "h.");
        MlsSendRetentionPolicy.releaseSealed(shell, log, rcsMessageId);
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(shell, log);
    }
}
