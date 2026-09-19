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

/**
 * RCC.16 <b>§10.5</b> — Recovering Group Metadata Keys, as pure decisions.
 *
 * <h2>This is NOT a v4.0-only feature, and that is the first thing to know about it</h2>
 *
 * §10.5 exists in RCC.16 <b>v3.0</b>, where it is titled "Recovering Group Subject and Icon". v4.0
 * renamed it and added the four sub-procedures. So there are two obligations here:
 *
 * <ul>
 *   <li><b>v3.0 (~20 lines, and we were failing it):</b> on receiving an External Commit, take the
 *       keys out of the local {@code icon_key}/{@code subject_key} extensions and send them in a
 *       PrivateMessage. This fires on the path we actually run — our whole recovery story IS
 *       external-commit resync — so every peer that healed into one of our groups was left without
 *       the icon/subject keys.</li>
 *   <li><b>v4.0:</b> a request/response protocol — detect out-of-sync (§10.5.1), request via the
 *       0xF007 GroupContext extension (§10.5.2), respond and remove the extension (§10.5.3), consume
 *       (§10.5.4).</li>
 * </ul>
 *
 * <p>The v3.0 rule is a strict special case of the v4.0 one: "someone external-committed in, so
 * assume they need keys" is §10.5.3 without the request. Both are expressed here, selected by
 * {@link Rcc16Version}, so the v3.0 path is not a separate code path that rots.
 *
 * <h2>§10.5.1 detection</h2>
 *
 * After any of: (1) initiating self-healing and consuming the server GroupInfo, (2) processing a
 * Commit that changes {@code icon_commitment}/{@code subject_commitment}, or (3) joining — check
 * whether the commitment in the GroupContext matches one recomputed from the key material we hold.
 * Any mismatch, or any commitment present with no local key, means out of sync.
 */
public final class MlsMetadataKeysPolicy {

    private MlsMetadataKeysPolicy() { }

    // ---- retention: how much §10.5 material we keep ---------------------------------------------
    //
    // The two bounds below are about the same material this class's procedures deliver — the icon
    // and subject keys and the ciphertexts they open — which is why they are here rather than in a
    // retention class of their own. They were `private static final` in MlsProviderTransport, where
    // no host test could reach either.

    /**
     * How many content keys we keep per (conversation, slot).
     *
     * <p>Filing keys BY COMMITMENT fixed a real bug (one slot per conversation meant a replayed
     * delivery overwrote the current key), and it created this one: every icon/subject key a device
     * has ever received was retained forever, one preference entry each. MLS gives forward secrecy
     * by rotating epoch secrets, and keeping every content key indefinitely partially undoes that
     * for exactly the payloads a group treats as long-lived.
     *
     * <p><b>Only the CURRENT icon/subject needs to be openable, and it needs to be openable
     * indefinitely.</b> It sits on the server and any member may re-render it at any time, so it can
     * never be evicted on age. Historical ones are a different question and nothing renders them:
     * our UI shows the current subject and icon, not what they were at some past moment.
     *
     * <p>The cap is therefore small but NOT one, and the reason is the race this scheme exists to
     * survive: a key may legitimately arrive BEFORE the commit that makes it current. An entry that
     * is not the current commitment may simply be one whose commit has not landed yet, and evicting
     * down to exactly the committed entry would drop precisely the key the next commit needs. So we
     * keep a few, evict oldest-first, and never evict on age alone.
     */
    public static final int RETAINED_CONTENT_KEYS_PER_SLOT = 4;

    /**
     * How many conversations may hold an unopened subject ciphertext at once.
     *
     * <p><b>Not the bound rework 6.7 deleted</b>, and the two look alike enough that the transport
     * kept a comment saying so at both ends. That one capped pending MLS MESSAGES, which §10.8
     * proves are unbounded in Google Messages — and combined with the sweeping drain it manufactured the
     * failures that declared conversations stranded. This caps held SUBJECTS: app-level metadata
     * whose key may never arrive, with no protocol requirement to retain it and no escalation
     * attached to overflowing. Dropping the oldest costs a subject line; dropping a pending message
     * costs a conversation.
     */
    public static final int MAX_PENDING_SUBJECT = 8;

    /** {@link #RETAINED_CONTENT_KEYS_PER_SLOT}, for the eviction line that prints the cap. */
    public static int retainedContentKeysPerSlot() {
        return RETAINED_CONTENT_KEYS_PER_SLOT;
    }

    /**
     * Is this (conversation, slot) over its retention cap?
     *
     * @param retained how many keys the slot's insertion-order list holds, the newest included
     */
    public static boolean overRetentionCap(final int retained) {
        return retained > RETAINED_CONTENT_KEYS_PER_SLOT;
    }

    /**
     * May another conversation hold an unopened subject?
     *
     * @param conversationsHoldingASubject the count NOT including the caller's own conversation,
     *     whose slot the caller has already cleared — the question is "would this make an eighth
     *     holder?", which is the comparison the old map's {@code size()} made
     */
    public static boolean mayHoldAnotherSubject(final int conversationsHoldingASubject) {
        return conversationsHoldingASubject < MAX_PENDING_SUBJECT;
    }

    /**
     * How many BYTES of unopened ICON ciphertext may be held across all conversations at once
     *.
     *
     * <p><b>Bounded by bytes, not by conversations, and the asymmetry with
     * {@link #MAX_PENDING_SUBJECT} is the point.</b> A held subject is a few dozen bytes, so
     * counting holders bounds the memory implicitly. An icon is four or five orders of magnitude
     * larger, so the same count would bound nothing that matters: eight conversations each holding
     * a 256 KB icon is 2 MB of retained heap on a device, reached by peers we do not control simply
     * by changing their group icons while we are behind on keys.
     *
     * <p>One megabyte total, which is four icons at the provider's per-icon cap and many more at a
     * realistic avatar size. Overflow drops the candidate rather than evicting an older hold: an
     * icon that has been waiting is more likely to have its key arrive imminently than one that
     * just showed up, and a drop costs a re-render nobody is waiting on — the same trade
     * {@link #MAX_PENDING_SUBJECT} makes, priced in the unit that actually binds.
     */
    public static final int MAX_PENDING_ICON_BYTES = 1024 * 1024;

    /**
     * May we hold another unopened icon ciphertext?
     *
     * @param bytesAlreadyHeld total held across all conversations, NOT including the caller's own
     *     slot, which the caller has already cleared — the same "would this make one too many?"
     *     framing as {@link #mayHoldAnotherSubject}
     * @param candidateBytes the length of the ciphertext being considered
     */
    public static boolean mayHoldAnotherIcon(final long bytesAlreadyHeld,
            final int candidateBytes) {
        if (candidateBytes <= 0) return false;
        return bytesAlreadyHeld + candidateBytes <= MAX_PENDING_ICON_BYTES;
    }

    /** What triggered a §10.5.1 evaluation. Kept explicit because the spec enumerates them. */
    public enum Trigger {
        /** (1) We initiated self-healing and consumed the server's GroupInfo. */
        SELF_HEAL,
        /** (2) A Commit changed icon_commitment and/or subject_commitment. */
        COMMITMENT_CHANGED,
        /** (3) We joined the group. */
        JOINED
    }

    /** The §10.5.1 verdict. */
    public enum Sync {
        /** Commitments and local key material agree — nothing to do. */
        IN_SYNC,
        /** A commitment is present that our local key material does not reproduce. Request keys. */
        OUT_OF_SYNC,
        /** No commitment at all: the group has no encrypted metadata. Not a failure. */
        NO_ENCRYPTED_METADATA
    }

    /**
     * §10.5.1 — recompute and compare.
     *
     * @param commitmentInGroupContext the {@code icon_commitment}/{@code subject_commitment}
     *                                 extension_data currently in the GroupContext, or {@code null}
     * @param localHmacTag             the {@code hmac_tag} of the FileEncryption metadata we hold
     *                                 for that artefact, or {@code null} if we hold no key
     * @param version                  selects the commitment label — see
     *                                 {@link RccCommitment#labelForIcon}
     * @param icon                     true for the icon, false for the subject
     */
    public static Sync detect(final byte[] commitmentInGroupContext, final byte[] localHmacTag,
            final Rcc16Version version, final boolean icon) {
        if (commitmentInGroupContext == null || commitmentInGroupContext.length == 0) {
            return Sync.NO_ENCRYPTED_METADATA;
        }
        // A commitment with no local key is the clearest out-of-sync there is: the group has an
        // encrypted artefact and we cannot open it.
        if (localHmacTag == null || localHmacTag.length == 0) return Sync.OUT_OF_SYNC;

        final String label = icon ? RccCommitment.labelForIcon(version)
                                  : RccCommitment.labelForSubject(version);
        final byte[] recomputed = RccCommitment.commit(label, localHmacTag);
        if (recomputed == null) return Sync.OUT_OF_SYNC;
        return MlsContinuityToken.commitmentMatches(recomputed, commitmentInGroupContext)
                ? Sync.IN_SYNC : Sync.OUT_OF_SYNC;
    }

    /**
     * §10.5.2 — should we ASK for keys?
     *
     * <p>Only under v4.0. The request mechanism is the 0xF007 GroupContext extension, and 0xF007
     * does not exist in v3.0 — so under v3.0 a client that is out of sync has no way to ask, and the
     * recovery is the sender-side rule below firing on its own. Requesting anyway would put an
     * extension a v3.0 peer has no handler for into the GroupContext.
     */
    public static boolean shouldRequestKeys(final Sync sync, final Rcc16Version version) {
        return sync == Sync.OUT_OF_SYNC && version == Rcc16Version.V4_0;
    }

    /**
     * §10.5.3 (v4.0) and the v3.0 one-shot rule — should we SEND our keys?
     *
     * <p>The two versions ask different questions and this is the whole reason the method takes a
     * version:
     *
     * <ul>
     *   <li><b>v4.0:</b> on every {@code ServerMlsMessage} containing a Commit or Welcome, send iff
     *       a peer is ASKING — i.e. the {@code group_metadata_keys_requested} extension is present —
     *       and we are ourselves in sync (there is no point forwarding keys we cannot verify).</li>
     *   <li><b>v3.0:</b> there is no request extension, so the trigger is structural: an
     *       <b>External Commit</b> means somebody joined without a Welcome and therefore without
     *       keys. Send unconditionally.</li>
     * </ul>
     *
     * @param keysRequestedPresent whether 0xF007 is in the GroupContext (v4.0 only; ignored on v3.0)
     * @param wasExternalCommit    whether the message that triggered this was an External Commit
     * @param weAreInSync          whether OUR OWN key material verifies (§10.5.1)
     * @param haveKeys             whether we hold any key material to send at all
     */
    public static boolean shouldSendKeys(final boolean keysRequestedPresent,
            final boolean wasExternalCommit, final boolean weAreInSync, final boolean haveKeys,
            final Rcc16Version version) {
        if (!haveKeys) return false;
        // Never forward key material we cannot verify against the group's own commitment: doing so
        // propagates a wrong key to everyone who asks, and the receiver's only symptom is a tag
        // mismatch with no way to tell who introduced it.
        if (!weAreInSync) return false;
        if (version == Rcc16Version.V4_0) return keysRequestedPresent;
        return wasExternalCommit;
    }

    /**
     * §10.5.3 — a responder must ALSO remove the request extension in the same Commit.
     *
     * <p>Separate from {@link #shouldSendKeys} because it is a separate obligation with a separate
     * failure mode: leaving 0xF007 in the GroupContext means every subsequent Commit or Welcome
     * re-triggers the send, so the group broadcasts its metadata keys forever. Only meaningful when
     * the extension is actually present.
     */
    public static boolean shouldRemoveRequestExtension(final boolean keysRequestedPresent,
            final boolean sending, final Rcc16Version version) {
        return version == Rcc16Version.V4_0 && keysRequestedPresent && sending;
    }
}
