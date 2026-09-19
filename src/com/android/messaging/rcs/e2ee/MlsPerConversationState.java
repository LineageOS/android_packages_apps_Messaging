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
package com.android.messaging.rcs.e2ee;

/**
 * A container that holds state for ONE conversation and can therefore be told to forget it —
 * the durable half.
 *
 * <h2>Why an interface rather than four more lines in the teardown</h2>
 *
 * <p>The teardown already had a single home ({@code MlsProviderTransport.clearConversationState})
 * and a source guard over it, and the durable stores were still missed — because the guard could
 * only see fields declared in that one file, and a store is a separate class. So the guard passed
 * while {@code forget()} left behind the persisted {@link MlsConversationRecord} (health status,
 * pending operation, self-heal budget, FTD resend counts, epoch authenticators, membership
 * history), its conversation→group alias, the parked inbound queue, the re-upgrade markers and the
 * resend ladder. Those survive a reboot, so the inherited state is <em>worse</em> than the maps'
 * was, not better.
 *
 * <p>An interface makes the set enumerable. {@code MlsPerConversationStateGuardTest} reads the
 * package source and fails when a class implements this and is not in the transport's registry, so
 * the next store cannot be added without joining the teardown — the same property the map guard
 * gives, extended to the containers that actually persist.
 *
 * <h2>The three key spaces, and why the scope carries all of them</h2>
 *
 * <p>There is no single conversation identifier in this stack, and pretending otherwise is how the
 * alias bug survived: {@code mConvAlias} is keyed by conversation id and was being removed by
 * canonical key, so the removal had always been a no-op. The containers key by:
 *
 * <ul>
 *   <li><b>canonical key</b> ({@code "g:"+rcsGroupId} / {@code "p:"+peerE164}) — the transport's
 *       own maps and {@link MlsResendLedger};</li>
 *   <li><b>(identity, groupId)</b> — {@link MlsRecordStore}, {@link MlsPendingQueueStore}; the
 *       record's own doc explains why the canonical key cannot express one RCS group under two
 *       identities;</li>
 *   <li><b>conversation id</b> — {@link MlsReupgradeStore}, and the record store's alias row.</li>
 * </ul>
 *
 * <p>{@link Scope} carries every one of them so an implementation reads the field it actually keys
 * by instead of deriving it, and so a null field is visibly a missing input rather than a silently
 * wrong lookup.
 *
 * <h2>What does NOT belong here</h2>
 *
 * <p>Per-MESSAGE stores are not per-conversation state and must not implement this:
 * {@link MlsCiphertextCache}, {@link MlsPendingBodyStore} and {@link MlsRendezvousStore} are keyed
 * by {@code rcsMessageId} and have a message-scoped lifecycle (released at send completion or at
 * chat-row insert). Their exemptions and the reason for each are recorded in the guard test, not
 * here, so that the claim is checked rather than merely asserted.
 */
public interface MlsPerConversationState {

    /**
     * Drop everything this container holds for one conversation.
     *
     * <p>Must be idempotent and must not throw: it runs inside {@code forget()}, and a container
     * that fails to clear must not prevent the others from clearing. Implementations that cannot
     * act on the fields the scope provides return {@code 0} rather than guessing.
     *
     * @return how many rows/entries were dropped — diagnostic only, never a control signal
     */
    int forgetConversation(Scope scope);

    /**
     * Every identifier a per-conversation container might key by.
     *
     * <p>Immutable, and fields may be null: {@code groupId} is absent when the engine never had a
     * group, {@code conversationId} when the app never opened a thread. An implementation reads
     * only the ones it needs and returns {@code 0} if they are absent.
     */
    final class Scope {
        /** {@code "g:"+rcsGroupId} or {@code "p:"+peerE164} — the transport's map key. */
        public final String canonicalKey;
        /** Our own MSISDN. The MLS storage directory is per-identity. */
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

        /** For logs. Deliberately does NOT print the peer's number in full. */
        @Override
        public String toString() {
            return "Scope{key=" + canonicalKey
                    + " gid=" + (groupId == null ? "-" : groupId.length + "B")
                    + " conv=" + (conversationId == null ? "-" : conversationId)
                    + "}";
        }
    }
}
