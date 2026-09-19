/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * When a proactive era advance is warranted. An era advance is a membership refresh, issued for
 * exactly two reasons: new members while the server GroupInfo requests the metadata keys, or a
 * changed set of expired members; never for its own sake. Recovery (self-heal, FTD escalation)
 * must not consult this policy: a diverged conversation usually has a fine roster and broken state.
 * Pure, with no clock.
 */
public final class MlsMaintenancePolicy {

    private MlsMaintenancePolicy() {}

    /**
     * Groups one sweep page may take to the server: a burst bound per triggering event, not a
     * throughput number, since the cursor always advances. Locally declined entries do not count.
     */
    public static final int SWEEP_PAGE = 4;

    /**
     * Whether this page is spent.
     *
     * @param maintained groups this page actually fetched; locally declined entries excluded
     */
    public static boolean sweepPageExhausted(final int maintained) {
        return maintained >= SWEEP_PAGE;
    }

    /** Why a proactive refresh is (or is not) warranted. */
    public enum Refresh {
        /** No membership delta; no request is generated. */
        NOT_NEEDED,
        /**
         * Members were added and the server GroupInfo carries the metadata-keys-request extension.
         */
        NEW_MEMBERS,
        /** At least one member's certificate expired and needs key rotation. */
        EXPIRED_MEMBERS,
        /** Both reasons hold; one advance serves both. */
        BOTH,
        /**
         * The pass stopped: the group is not the server's (same era and epoch, different epoch
         * authenticator). {@link #evaluate} never returns it; the transport does, so a fork is not
         * logged as {@link #NOT_NEEDED}. Not {@link #warranted()}: an era advance would rebuild the
         * wrong group around the right roster.
         */
        DIVERGED;

        /**
         * Whether an era advance follows. Listed positively, so {@link #DIVERGED} cannot answer
         * true by falling outside a {@code != NOT_NEEDED} rule.
         */
        public boolean warranted() {
            return this == NEW_MEMBERS || this == EXPIRED_MEMBERS || this == BOTH;
        }
    }

    /**
     * Decides whether to issue a proactive era advance. The add arm also requires the server's
     * metadata-keys-request extension: without it the server has not asked, and an advance would be
     * unrequested. The expiry arm has no such precondition.
     *
     * @param newMemberCount members in the server roster that our group does not carry
     * @param expiredMemberCount members whose certificates expired and need key rotation
     * @param metadataKeysRequestPresent the server GroupInfo carries the metadata-keys request
     */
    public static Refresh evaluate(final int newMemberCount, final int expiredMemberCount,
            final boolean metadataKeysRequestPresent) {
        final boolean add = newMemberCount > 0 && metadataKeysRequestPresent;
        final boolean expiry = expiredMemberCount > 0;
        if (add && expiry) return Refresh.BOTH;
        if (add) return Refresh.NEW_MEMBERS;
        if (expiry) return Refresh.EXPIRED_MEMBERS;
        return Refresh.NOT_NEEDED;
    }

    /** The add-arm log line, worded as other clients log it so traces can be compared. */
    public static String newMembersLine(final String groupId) {
        return "Group metadata keys request extension present in the server group info for group "
                + q(groupId) + "; and new members added; will be requesting a new era.";
    }

    /** The expiry-arm log line, in the same shared wording. */
    public static String expiredMembersChangedLine(final String groupId) {
        return "Generating new request to refresh others as the list of expired members has changed. "
                + "For group: " + q(groupId);
    }

    /** The expired-members count line, emitted even when the count is zero. */
    public static String expiredCountLine(final int count, final String groupId) {
        return "Expired members count: " + count + ", for group: " + q(groupId);
    }

    /** The per-member rotation line. */
    public static String needsRotationLine(final String clientId, final String groupId) {
        return "Member with client ID: " + q(clientId) + " needs key rotation, for group: "
                + q(groupId);
    }

    /** Ids are printed quoted, in Rust {@code {:?}} form. */
    private static String q(final String s) {
        return s == null ? "\"\"" : "\"" + s + "\"";
    }
}
