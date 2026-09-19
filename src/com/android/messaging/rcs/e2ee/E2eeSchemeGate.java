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
 * The per-conversation E2EE scheme gate — the app-layer analogue of the resolver
 * Google Messages runs over its own encryption-protocol column.
 *
 * <p>Google Messages places this whole negotiation box <b>in-app</b> (pure Java-side
 * functions, no native code and no server round-trip), so OpenRCSChat's split puts it
 * app-layer here too: it queries the <i>provider</i>'s Etouffee availability through the
 * opaque seam and resolves the scheme for the conversation.
 *
 * <p>Algorithm:
 * <pre>
 *   scytaleBit = providerEtouffeeAvailable(subId)         // seam &rarr; google.etouffee
 *   bits       = load(conv).accumulate(scytaleBit)        // durable, latching
 *   store(conv, bits)
 *   return bits.resolvedSchemeId()                        // Scytale &gt; none
 * </pre>
 *
 * <p><b>This build carries one E2EE plane: the provider's.</b> {@link EncryptionProtocolBits}
 * is the shared durable value object and carries a second bit that nothing here sets, so
 * {@link #selectScheme} accumulates a constant {@code false} into it. That is deliberately
 * not the same as clearing it — see the note on {@link #selectScheme}, which is what keeps
 * the persisted column byte-compatible.
 *
 * <p>The two external dependencies are injected as seams so the gate is unit-testable with
 * synthetic inputs and so the heavy pieces (the provider AIDL call, the SQLite-backed bits)
 * plug in independently.
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

    /** Durable per-conversation {@link EncryptionProtocolBits} (the encryption-protocol column). */
    public interface BitsStore {
        EncryptionProtocolBits load(String conversationId);

        void store(String conversationId, EncryptionProtocolBits bits);
    }

    private final EtouffeeAvailability mEtouffee;
    private final BitsStore mStore;

    public E2eeSchemeGate(final EtouffeeAvailability etouffee, final BitsStore store) {
        mEtouffee = etouffee;
        mStore = store;
    }

    /**
     * Resolve the active E2EE scheme for a conversation, persisting the accumulated
     * bits. Returns one opaque schemeId: {@link RcsE2eeScheme#ETOUFFEE}, or {@code null}
     * (plaintext).
     *
     * <p><b>Why the second accumulate argument is a constant {@code false} rather than
     * absent.</b> {@link EncryptionProtocolBits#accumulate} <i>latches</i>: a {@code false}
     * leaves a bit that is already set in the durable column exactly as it was, whereas
     * rewriting the column without it would be a downgrade this gate has no authority to
     * perform. So this build never <i>sets</i> the second bit and never <i>clears</i> one it
     * finds, and the stored column value is unchanged for every conversation either way.
     *
     * @param conversationId durable conversation key
     * @param subId          the SIM subscription
     */
    public String selectScheme(final String conversationId, final int subId) {
        // --- eligibility: which bits MAY be set this round ---
        final boolean scytaleEligible = mEtouffee != null && mEtouffee.isEtouffeeAvailable(subId);

        // --- accumulate onto the durable bitset (transient-downgrade fallback) ---
        final EncryptionProtocolBits prior =
                (mStore != null) ? safeLoad(conversationId) : EncryptionProtocolBits.NONE;
        final EncryptionProtocolBits next =
                prior.accumulate(scytaleEligible, /* secondPlaneEligible= */ false);
        if (mStore != null && !next.equals(prior)) {
            mStore.store(conversationId, next);
        }

        // --- resolve (Scytale > none) ---
        return next.resolvedSchemeId();
    }

    private EncryptionProtocolBits safeLoad(final String conversationId) {
        final EncryptionProtocolBits loaded = mStore.load(conversationId);
        return (loaded != null) ? loaded : EncryptionProtocolBits.NONE;
    }
}
