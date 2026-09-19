/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * One row of the resend register. A resend is a new message with a new id (RCC.16 §11.1), linked to
 * the original by {@link #originalRcsMessageId} (the chain root) and
 * {@link #manualResendOfRcsMessage} (the immediate parent). {@link #ftdResendCount} is derived at
 * insert from the rows already in the chain, so it survives process death. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsResendRecord {

    /** The new id minted for this resend, in the app's {@code rcs_message_id} namespace. */
    public final String rcsMessageId;
    /** The chain root: the message the user sent. Never a resend's id. */
    public final String originalRcsMessageId;
    /** The id this attempt replaces: the root for the first resend, the previous resend after. */
    public final String manualResendOfRcsMessage;
    /**
     * The member this resend is for. Bookkeeping only: the resend still goes to the whole group, so
     * this is not a transport address.
     */
    public final String resendRecipientAddress;
    /** The target's client id, or empty when unknown or the peer has a single client. */
    public final String resendRecipientClientId;
    /** {@code max(siblings) + 1}, computed at insert; the first resend is 1. */
    public final int ftdResendCount;
    /**
     * When it was recorded, ms since epoch; diagnostic only. Minus the retirement time once the
     * chain was delivered.
     */
    public final long resendTimestampMs;

    public MlsResendRecord(final String rcsMessageId, final String originalRcsMessageId,
            final String manualResendOfRcsMessage, final String resendRecipientAddress,
            final String resendRecipientClientId, final int ftdResendCount,
            final long resendTimestampMs) {
        this.rcsMessageId = nonNull(rcsMessageId);
        this.originalRcsMessageId = nonNull(originalRcsMessageId);
        this.manualResendOfRcsMessage = nonNull(manualResendOfRcsMessage);
        this.resendRecipientAddress = nonNull(resendRecipientAddress);
        this.resendRecipientClientId = nonNull(resendRecipientClientId);
        this.ftdResendCount = Math.max(0, ftdResendCount);
        this.resendTimestampMs = resendTimestampMs;
    }

    /**
     * The count a new resend carries: one past the highest in the chain, not {@code size + 1}, so
     * an evicted row cannot drop a long-failing conversation back to the bottom rung.
     *
     * @param siblings every record sharing this chain's root; null or empty for the first resend
     */
    public static int nextFtdResendCount(final Iterable<MlsResendRecord> siblings) {
        int max = 0;
        if (siblings != null) {
            for (final MlsResendRecord r : siblings) {
                if (r != null && r.ftdResendCount > max) max = r.ftdResendCount;
            }
        }
        return max + 1;
    }

    /**
     * Which attempts' send material a terminal event may release. A delivery releases the whole
     * chain. A permanent failure releases only the failed attempt, since the rest of the chain is
     * the recovery material; a failure naming the root of a chain that has resends releases
     * nothing, because a peer's report shows the root was received.
     *
     * @param root      {@code rootOf(terminalId)}; equals {@code terminalId} when it is not a
     *     resend
     * @param siblings  every ledger row sharing {@code root}; null or empty means no chain
     * @param delivered true for a positive receipt, false for a permanent failure
     * @return the ids to release in a stable order; never null
     */
    public static java.util.Set<String> materialToRelease(final String terminalId,
            final String root, final Iterable<MlsResendRecord> siblings, final boolean delivered) {
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (terminalId == null || terminalId.isEmpty()) return out;
        if (delivered) {
            out.add(terminalId);
            if (root != null && !root.isEmpty()) out.add(root);
            if (siblings != null) {
                for (final MlsResendRecord r : siblings) {
                    if (r != null && r.rcsMessageId != null && !r.rcsMessageId.isEmpty()) {
                        out.add(r.rcsMessageId);
                    }
                }
            }
            return out;
        }
        if (rootOfALiveChain(terminalId, root, siblings)) return out;
        out.add(terminalId);
        return out;
    }

    /** Whether {@code terminalId} is this chain's root and the chain has at least one resend. */
    private static boolean rootOfALiveChain(final String terminalId, final String root,
            final Iterable<MlsResendRecord> siblings) {
        if (root != null && !root.isEmpty() && !root.equals(terminalId)) return false;
        if (siblings == null) return false;
        for (final MlsResendRecord r : siblings) {
            if (r != null && r.rcsMessageId != null && !r.rcsMessageId.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether this record targets {@code address}. An empty client id on either side matches on
     * address alone, so a peer whose client id we never learned still accumulates a count.
     */
    public boolean targets(final String address, final String clientId) {
        if (address == null || !address.equals(resendRecipientAddress)) return false;
        if (clientId == null || clientId.isEmpty()
                || resendRecipientClientId.isEmpty()) return true;
        return clientId.equals(resendRecipientClientId);
    }

    @Override public String toString() {
        return "resend{" + MlsMessageId.forLog(rcsMessageId) + " of="
                + MlsMessageId.forLog(originalRcsMessageId)
                + " replaces=" + manualResendOfRcsMessage
                + " to=" + resendRecipientAddress
                + (resendRecipientClientId.isEmpty() ? "" : "/" + resendRecipientClientId)
                + " n=" + ftdResendCount + "}";
    }

    private static String nonNull(final String s) { return s == null ? "" : s; }
}
