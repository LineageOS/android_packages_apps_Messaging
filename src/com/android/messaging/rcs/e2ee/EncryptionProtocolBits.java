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
 * The app-layer analogue of Google Messages' per-conversation
 * {@code EncryptionProtocol} 2-bit bitset.
 *
 * <p>Google Messages stores this in the {@code encryption_protocol} column of
 * {@code conversation_encryption} (default {@code NONE}) and resolves the active
 * plane from it:
 * <pre>  MLS-bit set    → MLS
 *   else Scytale-bit set → Scytale (Etouffee)
 *   else                 → UNENCRYPTED</pre>
 *
 * <p><b>Accumulation rule (load-bearing):</b> the two bits are
 * independent durable signals. Once a plane becomes eligible its bit latches so a
 * <i>transient</i> downgrade (one recompute returning false) does not drop the
 * other plane's fallback. messaging2 must persist this per conversation and
 * resolve from it, never recompute-from-scratch on every send. Google Messages adds the
 * MLS bit while keeping the Scytale bit set.
 *
 * <p>This value object is immutable; mutation produces a new instance. It carries
 * no Android dependency so the gate logic stays unit-testable.
 */
public final class EncryptionProtocolBits {
    /** Resolved active plane (mirrors the enum Google Messages stores). */
    public enum Plane {
        /** No E2EE plane eligible. */ UNENCRYPTED,
        /** The Scytale (Etouffee) plane. */ SCYTALE,
        /** The MLS plane. */ MLS,
    }

    public static final EncryptionProtocolBits NONE =
            new EncryptionProtocolBits(false, false);

    private final boolean mScytaleBit;
    private final boolean mMlsBit;

    public EncryptionProtocolBits(final boolean scytaleBit, final boolean mlsBit) {
        mScytaleBit = scytaleBit;
        mMlsBit = mlsBit;
    }

    public boolean scytaleBit() {
        return mScytaleBit;
    }

    public boolean mlsBit() {
        return mMlsBit;
    }

    /**
     * Resolve the single active plane. MLS dominates Scytale
     * dominates none (ev §0.2).
     */
    public Plane resolve() {
        if (mMlsBit) {
            return Plane.MLS;
        }
        if (mScytaleBit) {
            return Plane.SCYTALE;
        }
        return Plane.UNENCRYPTED;
    }

    /** The opaque seam schemeId for the resolved plane (null = plaintext). */
    public String resolvedSchemeId() {
        switch (resolve()) {
            case MLS:
                return RcsE2eeScheme.MLS;
            case SCYTALE:
                return RcsE2eeScheme.ETOUFFEE;
            case UNENCRYPTED:
            default:
                return RcsE2eeScheme.NONE;
        }
    }

    /**
     * Accumulate freshly-computed eligibility onto the stored bits. A bit that is
     * <i>newly</i> eligible latches on; a bit already set stays set even if this
     * recompute saw it false (the transient-downgrade fallback). Use
     * {@link #withScytaleCleared()} / {@link #withMlsCleared()} for a real,
     * deliberate downgrade (provisioning actually lost / IMDN downgrade reason).
     */
    public EncryptionProtocolBits accumulate(final boolean scytaleEligible,
            final boolean mlsEligible) {
        return new EncryptionProtocolBits(
                mScytaleBit || scytaleEligible,
                mMlsBit || mlsEligible);
    }

    public EncryptionProtocolBits withMlsCleared() {
        return new EncryptionProtocolBits(mScytaleBit, false);
    }

    public EncryptionProtocolBits withScytaleCleared() {
        return new EncryptionProtocolBits(false, mMlsBit);
    }

    /** Compact wire/DB form: bit0 = scytale, bit1 = mls (mirrors the stored int). */
    public int toColumnValue() {
        return (mScytaleBit ? 0x1 : 0) | (mMlsBit ? 0x2 : 0);
    }

    public static EncryptionProtocolBits fromColumnValue(final int v) {
        return new EncryptionProtocolBits((v & 0x1) != 0, (v & 0x2) != 0);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EncryptionProtocolBits)) {
            return false;
        }
        final EncryptionProtocolBits other = (EncryptionProtocolBits) o;
        return mScytaleBit == other.mScytaleBit && mMlsBit == other.mMlsBit;
    }

    @Override
    public int hashCode() {
        return toColumnValue();
    }

    @Override
    public String toString() {
        return "EncryptionProtocolBits{scytale=" + mScytaleBit + ",mls=" + mMlsBit
                + ",resolved=" + resolve() + "}";
    }
}
