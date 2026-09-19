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

import android.text.TextUtils;

import java.util.List;

/**
 * The per-conversation E2EE scheme gate — the app-layer analogue of the resolver
 * Google Messages runs over its own two-bit encryption-protocol column and its
 * capability eligibility tree.
 *
 * <p>Google Messages places this whole negotiation box <b>in-app</b> (pure Java-side
 * functions, no native code and no server round-trip), so OpenRCSChat's
 * split puts it app-layer in messaging2 too: it queries the <i>provider</i>'s
 * Etouffee availability through the opaque seam and the <i>peers'</i> MLS caps,
 * then picks <b>one</b> scheme per conversation.
 *
 * <p>Algorithm:
 * <pre>
 *   scytaleBit = providerEtouffeeAvailable(subId)        // seam → google.etouffee
 *   mlsBit     = mlsProvisioned(subId)                   // local KDS cert + KPs
 *                AND mlsEligible(peers)                  // standard-first caps tree
 *                AND hasGroupId(conversation)            // MLS-bit-implies-group rule
 *   bits       = load(conv).accumulate(scytaleBit, mlsBit)   // durable, latching
 *   store(conv, bits)
 *   return bits.resolve()                                // MLS &gt; Scytale &gt; none
 * </pre>
 *
 * <p>The three external dependencies are injected as seams so the gate is
 * unit-testable with synthetic inputs and so the heavy pieces (the provider AIDL
 * call, the KDS provisioning state, the SQLite-backed bits) plug in independently.
 */
public final class E2eeSchemeGate {

    /** Provider-plane (Etouffee) availability, read through the opaque AIDL seam. */
    public interface EtouffeeAvailability {
        /**
         * True iff the provider app reports {@code google.etouffee} provisionable
         * for this subId ({@code RcsE2eeInfo.available && schemeId == "google.etouffee"}).
         */
        boolean isEtouffeeAvailable(int subId);
    }

    /** Local MLS provisioning state (the KDS cert + uploaded key-packages, §2). */
    public interface MlsProvisioning {
        /**
         * True iff this device holds a non-stale KDS certificate AND has uploaded
         * key-packages for this subId.
         * Until the KDS leg is built this is always false, so the
         * gate never selects MLS prematurely.
         */
        boolean isMlsProvisioned(int subId);

        /**
         * The minted/joined MLS group-id for this conversation, or {@code null} if
         * none. Enforces the invariant that a set MLS bit MUST have a
         * group-id (Google Messages throws "Missing encryption Id for MLS protocol").
         */
        String mlsGroupId(String conversationId);

        /** This build's Google-wave launch-iteration (for the same-build fallback). */
        String ourLaunchIteration();
    }

    /** Durable per-conversation {@link EncryptionProtocolBits} (the encryption-protocol column). */
    public interface BitsStore {
        EncryptionProtocolBits load(String conversationId);

        void store(String conversationId, EncryptionProtocolBits bits);
    }

    private final EtouffeeAvailability mEtouffee;
    private final MlsProvisioning mMls;
    private final BitsStore mStore;

    public E2eeSchemeGate(final EtouffeeAvailability etouffee, final MlsProvisioning mls,
            final BitsStore store) {
        mEtouffee = etouffee;
        mMls = mls;
        mStore = store;
    }

    /**
     * Resolve the active E2EE scheme for a conversation, persisting the accumulated
     * bits. Returns one opaque schemeId: {@link RcsE2eeScheme#MLS},
     * {@link RcsE2eeScheme#ETOUFFEE}, or {@code null} (plaintext).
     *
     * @param conversationId durable conversation key
     * @param subId          the SIM subscription
     * @param peers          parsed MLS caps per participant
     * @param isGroup        group conversation (enables the supports-groups path)
     */
    public String selectScheme(final String conversationId, final int subId,
            final List<MlsCapabilities.PeerCaps> peers, final boolean isGroup) {
        // --- eligibility: which bits MAY be set this round ---
        final boolean scytaleEligible = mEtouffee != null && mEtouffee.isEtouffeeAvailable(subId);

        final boolean mlsEligible =
                mMls != null
                        && mMls.isMlsProvisioned(subId)
                        && MlsCapabilities.mlsEligible(peers, mMls.ourLaunchIteration(), isGroup)
                        // Invariant: never set the MLS bit without a group-id.
                        && !TextUtils.isEmpty(mMls.mlsGroupId(conversationId));

        // --- accumulate onto the durable bitset (transient-downgrade fallback) ---
        final EncryptionProtocolBits prior =
                (mStore != null) ? safeLoad(conversationId) : EncryptionProtocolBits.NONE;
        final EncryptionProtocolBits next = prior.accumulate(scytaleEligible, mlsEligible);
        if (mStore != null && !next.equals(prior)) {
            mStore.store(conversationId, next);
        }

        // --- resolve (MLS > Scytale > none) ---
        return next.resolvedSchemeId();
    }

    private EncryptionProtocolBits safeLoad(final String conversationId) {
        final EncryptionProtocolBits loaded = mStore.load(conversationId);
        return (loaded != null) ? loaded : EncryptionProtocolBits.NONE;
    }

    /**
     * Deliberate downgrade of a conversation's MLS bit (a real provisioning loss or
     * an IMDN downgrade reason), distinct
     * from a transient recompute miss. Leaves the Scytale bit intact for fallback.
     */
    public void downgradeMls(final String conversationId) {
        if (mStore == null) {
            return;
        }
        final EncryptionProtocolBits cleared = safeLoad(conversationId).withMlsCleared();
        mStore.store(conversationId, cleared);
    }
}
