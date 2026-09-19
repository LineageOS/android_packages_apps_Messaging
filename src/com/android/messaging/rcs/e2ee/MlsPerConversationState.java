/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;

/**
 * A durable store that holds state for one conversation and can be told to forget it. The
 * transport's teardown calls every implementation listed in
 * {@code MlsProviderTransport.perConversationStores}, and {@code MlsPerConversationStateGuardTest}
 * fails when an implementation is missing from that list. See docs/mls/transport-and-port.md.
 *
 * <p>Stores key by different identifiers: the canonical key (the transport's maps,
 * {@link MlsResendLedger}), {@code (identity, groupId)} ({@link MlsRecordStore},
 * {@link MlsPendingQueueStore}) or the conversation id ({@link MlsReupgradeStore}, the record
 * store's alias row). {@link Scope} carries all of them so each store reads the one it keys by.
 *
 * <p>{@link MlsPendingBodyStore} and {@link MlsRendezvousStore} are per-message with a
 * message-scoped lifecycle and do not implement this. {@link MlsCiphertextCache} is keyed per
 * message but does, because each entry records the conversation it was sealed in.
 */
public interface MlsPerConversationState {

    /**
     * Drop everything held for one conversation. Idempotent and must not throw, so one failing
     * store does not stop the others. Returns {@code 0} when the fields it keys by are absent.
     *
     * @return rows or entries dropped; diagnostic only
     */
    int forgetConversation(Scope scope);

    /**
     * Every identifier a per-conversation store might key by. Fields may be null: {@code groupId}
     * when no group was established, {@code conversationId} when no thread exists.
     */
    final class Scope {
        /** {@code "g:"+rcsGroupId} or {@code "p:"+peerE164}: the transport's map key. */
        public final String canonicalKey;
        /** Our own MSISDN; the MLS storage directory is per identity. */
        public final String identity;
        /** The MLS group id, or null if none was ever established. */
        public final byte[] groupId;
        /** The app's conversation id, or null if no thread exists. */
        public final String conversationId;
        /** The RCS group id for a group conversation, else null. */
        public final String rcsGroupId;
        /** The peer's E.164 for a 1:1, else null. */
        public final String peerE164;

        public Scope(final String canonicalKey, final String identity, final byte[] groupId,
                final String conversationId, final String rcsGroupId, final String peerE164) {
            this.canonicalKey = canonicalKey;
            this.identity = identity;
            this.groupId = groupId;
            this.conversationId = conversationId;
            this.rcsGroupId = rcsGroupId;
            this.peerE164 = peerE164;
        }

        /** For logs; omits the peer's number. */
        @Override
        public String toString() {
            return "Scope{key=" + MlsConversationKey.forLog(canonicalKey)
                    + " gid=" + (groupId == null ? "-" : groupId.length + "B")
                    + " conv=" + (conversationId == null ? "-" : conversationId)
                    + "}";
        }
    }
}
