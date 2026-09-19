/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * The per-conversation encryption bits stored in {@code conversations.encryption_protocol}: one bit
 * for the provider-owned plane, which dominates plaintext.
 *
 * <p>The bit follows observed traffic ({@link E2eeObservation}): set when the provider reports
 * encrypting, cleared when it reports sending plaintext. The compose-box padlock reads it.
 * Immutable.
 */
public final class EncryptionProtocolBits {
    public static final EncryptionProtocolBits NONE =
            new EncryptionProtocolBits(false);

    private final boolean mScytaleBit;

    public EncryptionProtocolBits(final boolean scytaleBit) {
        mScytaleBit = scytaleBit;
    }

    public boolean scytaleBit() {
        return mScytaleBit;
    }

    /**
     * Accumulate fresh eligibility onto the stored bits: a newly eligible bit latches on and a set
     * bit stays set. For a deliberate downgrade use {@link #withScytaleCleared()}.
     */
    public EncryptionProtocolBits accumulate(final boolean scytaleEligible) {
        return new EncryptionProtocolBits(mScytaleBit || scytaleEligible);
    }

    public EncryptionProtocolBits withScytaleCleared() {
        return new EncryptionProtocolBits(false);
    }

    /** Column form: bit 0 = provider plane; bit 1 is reserved. Persisted; do not renumber. */
    public int toColumnValue() {
        return mScytaleBit ? 0x1 : 0;
    }

    public static EncryptionProtocolBits fromColumnValue(final int v) {
        return new EncryptionProtocolBits((v & 0x1) != 0);
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
        return mScytaleBit == other.mScytaleBit;
    }

    @Override
    public int hashCode() {
        return toColumnValue();
    }

    @Override
    public String toString() {
        return "EncryptionProtocolBits{scytale=" + mScytaleBit + "}";
    }
}
