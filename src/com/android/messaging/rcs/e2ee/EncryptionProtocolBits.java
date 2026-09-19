/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * The per-conversation encryption bits stored in {@code conversations.encryption_protocol}: one bit
 * for the provider-owned plane and one for MLS. MLS dominates the provider plane, which dominates
 * plaintext.
 *
 * <p>The MLS bit accumulates: once eligible it latches on, so one recompute that returns false
 * does not drop it. The provider-plane bit follows observed traffic ({@link E2eeObservation}): set
 * when the provider reports encrypting, cleared when it reports sending plaintext. Persist and
 * resolve from them; never recompute from scratch on each send. Immutable. See
 * docs/mls/downgrade.md.
 */
public final class EncryptionProtocolBits {
    /** The resolved active plane. */
    public enum Plane {
        /** No plane eligible. */ UNENCRYPTED,
        /** The provider-owned plane ({@link RcsE2eeScheme#ETOUFFEE}). */ SCYTALE,
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

    /** The single active plane: MLS, else the provider plane, else unencrypted. */
    public Plane resolve() {
        if (mMlsBit) {
            return Plane.MLS;
        }
        if (mScytaleBit) {
            return Plane.SCYTALE;
        }
        return Plane.UNENCRYPTED;
    }

    /** The scheme id for the resolved plane; {@code null} for plaintext. */
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
     * Accumulate fresh eligibility onto the stored bits: a newly eligible bit latches on and a set
     * bit stays set. For a deliberate downgrade use {@link #withScytaleCleared()} or {@link
     * #withMlsCleared()}.
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

    /** Column form: bit 0 = provider plane, bit 1 = MLS. Persisted; do not renumber. */
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
