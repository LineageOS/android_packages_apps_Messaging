/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * The transport message-id generator behind {@link MlsPorts.MessageAccessor#generateMessageId}.
 *
 * <p>Read off Google Messages' own generator rather than inferred, under trace span 804
 * {@code RcsMessageIdGenerator::generate}:
 *
 * <pre>
 *   UUID r = UUID.randomUUID();
 *   ByteBuffer b = ByteBuffer.wrap(new byte[16]);
 *   b.putLong(r.getMostSignificantBits()); b.putLong(r.getLeastSignificantBits());
 *   return "Mx" + Base64.encodeToString(b.array(), 11).replace('_', '=');
 * </pre>
 *
 * <p>Validated elsewhere against {@code ^Mx(.){22,26}}. Reproduced exactly rather than
 * approximately, because a peer or the server may well validate the shape — and an id we mint that
 * they reject is a message that silently never arrives, which is the most expensive class of bug
 * this project has.
 *
 * <p>The {@code '_' → '='} substitution is Google Messages' and looks like a mistake until you notice what
 * it is doing: flag 11 is {@code NO_PADDING | NO_WRAP | URL_SAFE}, so the alphabet is already
 * URL-safe (using {@code -} and {@code _}) and there is no padding to restore. Mapping {@code _}
 * back to {@code =} yields an alphabet that is neither standard nor URL-safe. We copy it because
 * matching the wire beats being tidy — the ids have to interoperate, not to round-trip through a
 * decoder.
 */
public final class MlsMessageId {

    /** Google Messages' Base64 flag 11 = NO_PADDING(1) | NO_WRAP(2) | URL_SAFE(8). */
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

    /** True iff {@code id} matches the shape Google Messages validates against, {@code ^Mx(.){22,26}}. */
    public static boolean isWellFormed(final String id) {
        if (id == null || !id.startsWith("Mx")) return false;
        final int n = id.length() - 2;
        return n >= 22 && n <= 26;
    }

    /**
     * Base64 with the URL-safe alphabet and no padding — {@code android.util.Base64} flag 11.
     *
     * <p>Hand-rolled so this class stays Android-free and host-testable. Sixteen bytes always
     * produce 22 characters, so the tail case here only ever takes the two-byte branch; it is
     * written in full anyway rather than specialised to 16, because a generator that silently
     * depends on its input length is one refactor from being wrong.
     */
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
     * The legacy GROUP wire-id prefix: {@code mls-grp-<rcsGroupId>-<generation>}.
     *
     * <p>Synthesised by the group send path for callers that have no app {@code rcs_message_id} to
     * bind. The 1:1 leg's ids were unified with the app's; the group leg still mints
     * these, and this class is where the shape is written down so a reader is not left inferring it
     * from a string concatenation.
     */
    public static final String LEGACY_GROUP_PREFIX = "mls-grp-";

    /**
     * Recover the RCS group id from a legacy group wire id, or {@code null} if it is not one.
     *
     * <p><b>Why this is worth having rather than "just fix the id".</b> An inbound MLS IMDN carries
     * no group (§12.9), so a negative receipt is correlated message-id → conversation through the
     * message store. A legacy group id was never written to {@code rcs_message_id}, so that lookup
     * MISSES — and the remedy (a rekey, an era advance) then falls back to the 1:1 conversation with
     * the reporting peer and repairs the wrong group while the broken one stays broken.
     *
     * <p>Device-observed: one device's group message failed to decrypt on a second, the second
     * reported it correctly, and the first's rekey landed on the 1:1 — advancing an unrelated healthy
     * conversation's epoch while the group could never recover.
     *
     * <p>The id CONTAINS the group id, so no lookup is needed. This is a fallback for ids already on
     * the wire, not a licence to keep minting them.
     */
    public static String groupIdFromLegacyWireId(final String id) {
        if (id == null || !id.startsWith(LEGACY_GROUP_PREFIX)) return null;
        final int lastDash = id.lastIndexOf('-');
        // The generation suffix must exist AND be a number. Without the digit check, an id whose
        // group component merely contains a dash — every UUID does — would be truncated at the wrong
        // place and yield a group id that matches nothing, which is worse than returning null
        // because it looks like a successful resolution.
        if (lastDash <= LEGACY_GROUP_PREFIX.length()) return null;
        final String gen = id.substring(lastDash + 1);
        if (gen.isEmpty()) return null;
        for (int i = 0; i < gen.length(); i++) {
            if (gen.charAt(i) < '0' || gen.charAt(i) > '9') return null;
        }
        final String gid = id.substring(LEGACY_GROUP_PREFIX.length(), lastDash);
        return gid.isEmpty() ? null : gid;
    }

    /** Whether {@code id} is a legacy GROUP wire id — i.e. names a group we should be able to find. */
    public static boolean isLegacyGroupWireId(final String id) {
        return groupIdFromLegacyWireId(id) != null;
    }

    /** Returned by {@link #groupOfSentMessage} when nothing could classify the id. */
    public static final String UNKNOWN_CONVERSATION = "";

    /**
     * Which conversation a message WE SENT belongs to.
     *
     * <h2>A §10.3 RESEND is a THIRD population, and it was classified as a 1:1</h2>
     *
     * <p>The caller of this used to know two routes: a UI send binds the app's
     * {@code rcs_message_id} and is resolvable from the message store, and a group send with no chat
     * row to bind mints {@link #LEGACY_GROUP_PREFIX}{@code <gid>-<stamp>} and carries the group in
     * the id itself. A resend is neither. It is a bare UUID minted by the resend ledger, and no chat
     * row is ever written for it — so BOTH routes miss and the id was reported as "not a group".
     *
     * <p>That is not a cosmetic mis-label. "Not a group" is what makes a single delivery receipt
     * TERMINAL: one member confirming releases the send material and forgets the chain. That was
     * fixed for ordinary group sends after watching a group message's
     * bytes live 1.2s and die on an unrelated member's success; a group RESEND still took the 1:1
     * arm, so the fix had a hole precisely on the path §10.3 depends on — the second failure report
     * then arrived at a correct remedy with nothing to resend, which is the rung-1 stop.
     *
     * <p><b>The ledger's conversation key is the authority</b>, because it is the only place a
     * resend's conversation is recorded at all: {@code "g:<rcsGroupId>"} for a group,
     * {@code "p:<e164>"} for a 1:1.
     *
     * @param rcsMessageId          the id the terminal event named
     * @param ledgerConversationKey the resend row's conversation key, or {@code null}/empty if this
     *                              id is not in the ledger
     * @param rootRcsMessageId      {@code rootOf(rcsMessageId)} — equal to it when not a resend
     * @param chatRowGroupId        the group id from the chat row for the ROOT, or {@code null}
     * @return the {@code rcsGroupId}; {@code null} for a message proven to be a 1:1;
     *         {@link #UNKNOWN_CONVERSATION} when it cannot be told — which callers must treat as a
     *         group, since keeping bytes we did not need costs a slot and dropping bytes we did
     *         costs a message that cannot be recovered
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
                // Proven 1:1 — the sole recipient confirming IS the terminal state.
                return null;
            }
        }
        final boolean knownResend = rootRcsMessageId != null && !rootRcsMessageId.isEmpty()
                && !rootRcsMessageId.equals(rcsMessageId);
        if (knownResend) {
            // The ROOT is the attempt that carries a shape we can read: it is either an
            // "mls-grp-…" wire id or an app id with a chat row.
            final String fromRoot = groupIdFromLegacyWireId(rootRcsMessageId);
            if (fromRoot != null && !fromRoot.isEmpty()) return fromRoot;
        }
        if (chatRowGroupId != null && !chatRowGroupId.isEmpty()) return chatRowGroupId;
        // A resend we could not classify must NOT default to the 1:1 arm — that is the whole of the
        // bug above. An id that was never a resend and has no row is an ordinary 1:1 send, which is
        // the pre-existing behaviour and stays.
        return knownResend ? UNKNOWN_CONVERSATION : null;
    }
}
