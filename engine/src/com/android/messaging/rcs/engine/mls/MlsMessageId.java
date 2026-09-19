/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * The transport message-id generator behind {@link MlsPorts.MessageAccessor#generateMessageId}:
 * {@code "Mx"} plus the 16 UUID bytes in unpadded URL-safe Base64, with {@code '_'} then mapped to
 * {@code '='}. The odd alphabet is reproduced exactly because peers validate the shape
 * ({@code ^Mx(.){22,26}}), and an id they reject is a message that never arrives.
 */
public final class MlsMessageId {

    /** {@code android.util.Base64} flag 11 = NO_PADDING(1) | NO_WRAP(2) | URL_SAFE(8). */
    private static final char[] URL_SAFE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** A fresh message id. Never null, never empty. */
    public static String generate() {
        return of(UUID.randomUUID());
    }

    /** The generator as a pure function of its UUID, so the encoding can be pinned by a test. */
    public static String of(final UUID r) {
        final ByteBuffer b = ByteBuffer.wrap(new byte[16]);
        b.putLong(r.getMostSignificantBits());
        b.putLong(r.getLeastSignificantBits());
        return "Mx" + base64NoPadUrlSafe(b.array()).replace('_', '=');
    }

    /** True iff {@code id} matches the shape peers validate against, {@code ^Mx(.){22,26}}. */
    public static boolean isWellFormed(final String id) {
        if (id == null || !id.startsWith("Mx")) return false;
        final int n = id.length() - 2;
        return n >= 22 && n <= 26;
    }

    /** Base64 with the URL-safe alphabet and no padding, kept Android-free for host tests. */
    private static String base64NoPadUrlSafe(final byte[] in) {
        final StringBuilder out = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= in.length) {
            final int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8) | (in[i + 2] & 0xFF);
            out.append(URL_SAFE_ALPHABET[(v >>> 18) & 0x3F])
               .append(URL_SAFE_ALPHABET[(v >>> 12) & 0x3F])
               .append(URL_SAFE_ALPHABET[(v >>> 6) & 0x3F])
               .append(URL_SAFE_ALPHABET[v & 0x3F]);
            i += 3;
        }
        final int rem = in.length - i;
        if (rem == 1) {
            final int v = (in[i] & 0xFF) << 16;
            out.append(URL_SAFE_ALPHABET[(v >>> 18) & 0x3F])
               .append(URL_SAFE_ALPHABET[(v >>> 12) & 0x3F]);
        } else if (rem == 2) {
            final int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8);
            out.append(URL_SAFE_ALPHABET[(v >>> 18) & 0x3F])
               .append(URL_SAFE_ALPHABET[(v >>> 12) & 0x3F])
               .append(URL_SAFE_ALPHABET[(v >>> 6) & 0x3F]);
        }
        return out.toString();
    }

    private MlsMessageId() {}

    /**
     * The legacy group wire-id prefix, {@code mls-grp-<rcsGroupId>-<generation>}, minted by the
     * group send path when there is no app {@code rcs_message_id} to bind.
     */
    public static final String LEGACY_GROUP_PREFIX = "mls-grp-";

    /**
     * Recover the RCS group id from a legacy group wire id, or {@code null} if it is not one. Such
     * ids are never written to {@code rcs_message_id}, so without this a negative receipt (which
     * names no group) would be routed to the reporting peer's 1:1 and repair the wrong group.
     */
    public static String groupIdFromLegacyWireId(final String id) {
        if (id == null || !id.startsWith(LEGACY_GROUP_PREFIX)) return null;
        final int lastDash = id.lastIndexOf('-');
        // The suffix must be numeric: a UUID group id contains dashes, and a cut at the wrong one
        // would return a group id that matches nothing.
        if (lastDash <= LEGACY_GROUP_PREFIX.length()) return null;
        final String gen = id.substring(lastDash + 1);
        if (gen.isEmpty()) return null;
        for (int i = 0; i < gen.length(); i++) {
            if (gen.charAt(i) < '0' || gen.charAt(i) > '9') return null;
        }
        final String gid = id.substring(LEGACY_GROUP_PREFIX.length(), lastDash);
        return gid.isEmpty() ? null : gid;
    }

    /**
     * {@code id} for a log line. A 1:1 control id embeds the peer's number
     * ({@code mls-endmls-+15550100123-<ms>}, {@code mls-keyupdate-p:+15550100123-<ms>}), which is
     * written masked as in {@link MlsConversationKey#forLog}; any other id passes unchanged. The
     * id itself is never rewritten: it is the wire id and the AAD.
     */
    public static String forLog(final String id) {
        return MlsConversationKey.forLog(id);
    }

    /** Whether {@code id} is a legacy group wire id. */
    public static boolean isLegacyGroupWireId(final String id) {
        return groupIdFromLegacyWireId(id) != null;
    }

    /** Returned by {@link #groupOfSentMessage} when nothing could classify the id. */
    public static final String UNKNOWN_CONVERSATION = "";

    /**
     * Which conversation a message we sent belongs to. A resend is a bare UUID with no chat row,
     * so the resend ledger's key ({@code "g:<rcsGroupId>"} or {@code "p:<e164>"}) is the authority.
     *
     * @param rcsMessageId          the id the terminal event named
     * @param ledgerConversationKey the resend row's conversation key, or {@code null}/empty if this
     *                              id is not in the ledger
     * @param rootRcsMessageId      {@code rootOf(rcsMessageId)}; equal to it when not a resend
     * @param chatRowGroupId        the group id from the chat row for the root, or {@code null}
     * @return the {@code rcsGroupId}; {@code null} for a message proven to be a 1:1;
     *         {@link #UNKNOWN_CONVERSATION} when it cannot be told, which callers must treat as a
     *         group so one member's receipt does not release bytes a resend still needs
     */
    public static String groupOfSentMessage(final String rcsMessageId,
            final String ledgerConversationKey, final String rootRcsMessageId,
            final String chatRowGroupId) {
        final String fromId = groupIdFromLegacyWireId(rcsMessageId);
        if (fromId != null && !fromId.isEmpty()) return fromId;
        if (ledgerConversationKey != null && !ledgerConversationKey.isEmpty()) {
            if (ledgerConversationKey.startsWith("g:")) {
                final String gid = ledgerConversationKey.substring(2);
                if (!gid.isEmpty()) return gid;
            } else if (ledgerConversationKey.startsWith("p:")) {
                // Proven 1:1: the sole recipient confirming is the terminal state.
                return null;
            }
        }
        final boolean knownResend = rootRcsMessageId != null && !rootRcsMessageId.isEmpty()
                && !rootRcsMessageId.equals(rcsMessageId);
        if (knownResend) {
            // The root carries a readable shape: an "mls-grp-" id or an app id with a chat row.
            final String fromRoot = groupIdFromLegacyWireId(rootRcsMessageId);
            if (fromRoot != null && !fromRoot.isEmpty()) return fromRoot;
        }
        if (chatRowGroupId != null && !chatRowGroupId.isEmpty()) return chatRowGroupId;
        // An unclassified resend must not default to the 1:1 arm; a non-resend with no row is 1:1.
        return knownResend ? UNKNOWN_CONVERSATION : null;
    }
}
