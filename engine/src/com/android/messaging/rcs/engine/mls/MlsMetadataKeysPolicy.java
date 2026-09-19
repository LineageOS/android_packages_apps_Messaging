/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * RCC.16 §10.5 metadata-key recovery and retention bounds, as pure decisions. Both the v3.0 rule
 * (send keys on an External Commit) and the v4.0 request/response protocol are expressed here,
 * selected by {@link Rcc16Version}. See docs/mls/metadata.md.
 */
public final class MlsMetadataKeysPolicy {

    private MlsMetadataKeysPolicy() { }

    // ---- retention ---------------------------------------------------------------------------

    /**
     * Content keys kept per (conversation, slot), evicted oldest first and never on age. More than
     * one because a key may arrive before the commit that makes it current.
     */
    public static final int RETAINED_CONTENT_KEYS_PER_SLOT = 4;

    /** How many conversations may hold an unopened subject ciphertext at once. */
    public static final int MAX_PENDING_SUBJECT = 8;

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
     * @param conversationsHoldingASubject the count excluding the caller's own conversation, whose
     *     slot the caller has already cleared
     */
    public static boolean mayHoldAnotherSubject(final int conversationsHoldingASubject) {
        return conversationsHoldingASubject < MAX_PENDING_SUBJECT;
    }

    /**
     * Bytes of unopened icon ciphertext held across all conversations. Bounded by bytes because
     * icons are orders of magnitude larger than subjects; overflow drops the newcomer.
     */
    public static final int MAX_PENDING_ICON_BYTES = 1024 * 1024;

    /**
     * May we hold another unopened icon ciphertext?
     *
     * @param bytesAlreadyHeld total held across all conversations, excluding the caller's own
     *     slot, which the caller has already cleared
     * @param candidateBytes the length of the ciphertext being considered
     */
    public static boolean mayHoldAnotherIcon(final long bytesAlreadyHeld,
            final int candidateBytes) {
        if (candidateBytes <= 0) return false;
        return bytesAlreadyHeld + candidateBytes <= MAX_PENDING_ICON_BYTES;
    }

    /** What triggered an RCC.16 §10.5.1 evaluation. */
    public enum Trigger {
        /** (1) We initiated self-healing and consumed the server's GroupInfo. */
        SELF_HEAL,
        /** (2) A Commit changed icon_commitment and/or subject_commitment. */
        COMMITMENT_CHANGED,
        /** (3) We joined the group. */
        JOINED
    }

    /** The RCC.16 §10.5.1 verdict. */
    public enum Sync {
        /** Commitments and local key material agree. */
        IN_SYNC,
        /** A commitment is present that our local key material does not reproduce. Request keys. */
        OUT_OF_SYNC,
        /** No commitment at all: the group has no encrypted metadata. Not a failure. */
        NO_ENCRYPTED_METADATA
    }

    /**
     * RCC.16 §10.5.1: recompute the commitment from local key material and compare.
     *
     * @param commitmentInGroupContext the {@code icon_commitment}/{@code subject_commitment}
     *                                 extension_data currently in the GroupContext, or {@code null}
     * @param localHmacTag             the {@code hmac_tag} of the FileEncryption metadata we hold
     *                                 for that artefact, or {@code null} if we hold no key
     * @param version                  selects the commitment label
     * @param icon                     true for the icon, false for the subject
     */
    public static Sync detect(final byte[] commitmentInGroupContext, final byte[] localHmacTag,
            final Rcc16Version version, final boolean icon) {
        if (commitmentInGroupContext == null || commitmentInGroupContext.length == 0) {
            return Sync.NO_ENCRYPTED_METADATA;
        }
        if (localHmacTag == null || localHmacTag.length == 0) return Sync.OUT_OF_SYNC;

        final String label = icon ? RccCommitment.labelForIcon(version)
                                  : RccCommitment.labelForSubject(version);
        final byte[] recomputed = RccCommitment.commit(label, localHmacTag);
        if (recomputed == null) return Sync.OUT_OF_SYNC;
        return MlsContinuityToken.commitmentMatches(recomputed, commitmentInGroupContext)
                ? Sync.IN_SYNC : Sync.OUT_OF_SYNC;
    }

    /**
     * RCC.16 §10.5.2: should we request keys? Only under v4.0; the 0xF007 request extension does
     * not exist in v3.0.
     */
    public static boolean shouldRequestKeys(final Sync sync, final Rcc16Version version) {
        return sync == Sync.OUT_OF_SYNC && version == Rcc16Version.V4_0;
    }

    /**
     * RCC.16 §10.5.3: should we send our keys? v4.0 sends when 0xF007 is present; v3.0 sends on
     * an External Commit. Never when our own keys do not verify.
     *
     * @param keysRequestedPresent whether 0xF007 is in the GroupContext (v4.0 only)
     * @param wasExternalCommit    whether the triggering message was an External Commit
     * @param weAreInSync          whether our own key material verifies (RCC.16 §10.5.1)
     * @param haveKeys             whether we hold any key material to send
     */
    public static boolean shouldSendKeys(final boolean keysRequestedPresent,
            final boolean wasExternalCommit, final boolean weAreInSync, final boolean haveKeys,
            final Rcc16Version version) {
        if (!haveKeys) return false;
        // A wrong key forwarded reaches everyone who asks, with no way to tell who introduced it.
        if (!weAreInSync) return false;
        if (version == Rcc16Version.V4_0) return keysRequestedPresent;
        return wasExternalCommit;
    }

    /**
     * RCC.16 §10.5.3: a responder also removes the request extension in the same commit, or every
     * later commit re-triggers the send.
     */
    public static boolean shouldRemoveRequestExtension(final boolean keysRequestedPresent,
            final boolean sending, final Rcc16Version version) {
        return version == Rcc16Version.V4_0 && keysRequestedPresent && sending;
    }
}
