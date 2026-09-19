/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import java.util.List;

/**
 * Picks one E2EE scheme per conversation from local MLS provisioning and the peers' MLS
 * capabilities, and persists the result as latching {@link EncryptionProtocolBits}:
 *
 * <pre>
 *   mlsBit      = mlsProvisioned(subId) AND mlsEligible(peers) AND hasGroupId(conversation)
 *                 AND NOT mlsDowngraded(conversation)
 *   bits        = load(conv).accumulate(false, mlsBit); store(conv, bits)
 *   return bits.resolve()                                  // MLS &gt; provider &gt; none
 * </pre>
 *
 * <p>The gate never sets the provider-plane bit: that bit records encryption the provider reported
 * applying ({@link E2eeObservation}), and our own availability says nothing about the peer. The
 * inputs are injected so the gate is host-testable. See docs/mls/downgrade.md.
 */
public final class E2eeSchemeGate {

    /** Local MLS provisioning state. */
    public interface MlsProvisioning {
        /** True iff this device holds a current certificate and has uploaded KeyPackages. */
        boolean isMlsProvisioned(int subId);

        /**
         * The MLS group id for this conversation, or {@code null}. A set MLS bit requires a group
         * id, so a conversation without one is never MLS-eligible.
         */
        String mlsGroupId(String conversationId);

        /** The launch iteration this build claims. */
        String ourLaunchIteration();

        /**
         * Whether the MLS downgrade flow took this conversation out of MLS and nothing has brought
         * it back. The peer still advertises MLS afterwards, so without this the next send would
         * set the bit again and go to an MLS seal that refuses a downgraded conversation.
         */
        boolean mlsDowngraded(String conversationId);
    }

    /** Durable per-conversation {@link EncryptionProtocolBits}. */
    public interface BitsStore {
        EncryptionProtocolBits load(String conversationId);

        void store(String conversationId, EncryptionProtocolBits bits);
    }

    private final MlsProvisioning mMls;
    private final BitsStore mStore;

    public E2eeSchemeGate(final MlsProvisioning mls, final BitsStore store) {
        mMls = mls;
        mStore = store;
    }

    /**
     * Resolve the conversation's scheme id, persisting the accumulated bits; {@code null} is
     * plaintext.
     *
     * @param isGroup enables the supports-groups eligibility path
     */
    public String selectScheme(final String conversationId, final int subId,
            final List<MlsCapabilities.PeerCaps> peers, final boolean isGroup) {
        final boolean mlsEligible =
                mMls != null
                        && mMls.isMlsProvisioned(subId)
                        && MlsCapabilities.mlsEligible(peers, mMls.ourLaunchIteration(), isGroup)
                        && !TextUtils.isEmpty(mMls.mlsGroupId(conversationId))
                        && !mMls.mlsDowngraded(conversationId);

        // Latch onto the stored bits, so a transient miss does not drop a plane.
        final EncryptionProtocolBits prior =
                (mStore != null) ? safeLoad(conversationId) : EncryptionProtocolBits.NONE;
        final EncryptionProtocolBits next = prior.accumulate(false, mlsEligible);
        if (mStore != null && !next.equals(prior)) {
            mStore.store(conversationId, next);
        }

        return next.resolvedSchemeId();
    }

    private EncryptionProtocolBits safeLoad(final String conversationId) {
        final EncryptionProtocolBits loaded = mStore.load(conversationId);
        return (loaded != null) ? loaded : EncryptionProtocolBits.NONE;
    }

    /**
     * Deliberate MLS downgrade (a real provisioning loss or an IMDN downgrade reason), as opposed
     * to a transient recompute miss. The provider plane's bit is left intact.
     */
    public void downgradeMls(final String conversationId) {
        if (mStore == null) {
            return;
        }
        final EncryptionProtocolBits cleared = safeLoad(conversationId).withMlsCleared();
        mStore.store(conversationId, cleared);
    }
}
