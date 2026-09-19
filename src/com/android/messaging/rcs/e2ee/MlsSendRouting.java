/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * Whether a send may go out in the clear, for groups and 1:1 alike (through
 * {@code MlsProviderTransport.groupSendVerdict} and {@code oneToOneSendVerdict}). It composes two
 * inputs that must stay separate: what the engine can seal ({@link SealCapability}) and what the
 * app shows the user ({@link MlsLatch}). An unreadable latch counts as clear, so a read failure
 * never refuses ordinary groups. Media callers have no seal path and treat {@link Verdict#SEAL} as
 * a refusal.
 */
public final class MlsSendRouting {

    private MlsSendRouting() {}

    /** What the engine can do for this conversation now. */
    public enum SealCapability {
        /** We hold MLS group state and the conversation is not downgraded. */
        SEALABLE,
        /** No MLS group state: never joined, dropped from, or simply not encrypted. */
        NO_MLS_STATE,
        /** Downgraded; RCC.16 §9.1.1 forbids sending encrypted. */
        DOWNGRADED,
        /**
         * An identity exists but the engine would not open a session: unknown, not "nothing to
         * know".
         */
        ENGINE_UNAVAILABLE,
        /** No MLS identity was ever adopted, so nothing on this device can be MLS. */
        NO_MLS_IDENTITY,
    }

    /** Whether the app is presenting this conversation as MLS-encrypted. */
    public enum MlsLatch {
        /** {@code encryption_protocol}'s MLS bit is set. */
        LATCHED,
        /** The bit is clear. */
        CLEAR,
        /** The bit could not be read; not evidence that it is clear. */
        UNREADABLE,
    }

    /** What the caller must do with the message. */
    public enum Verdict {
        /** Seal it and send the ciphertext; never {@code text/plain}. */
        SEAL,
        /** Send it as an ordinary plaintext RCS message. */
        PLAINTEXT,
        /**
         * Do not send it in any form; leave it visibly failed. Falling back to group MMS is a
         * downgrade too, so the caller must not express the refusal that way.
         */
        REFUSE,
    }

    /**
     * The whole decision; the table is the specification.
     *
     * <pre>
     *   engine             latch        verdict
     *   -----------------------------------------
     *   SEALABLE           any          SEAL
     *   NO_MLS_STATE       LATCHED      REFUSE
     *   NO_MLS_STATE       CLEAR        PLAINTEXT   &lt;- an ordinary RCS conversation
     *   NO_MLS_STATE       UNREADABLE   PLAINTEXT
     *   DOWNGRADED         LATCHED      REFUSE      &lt;- RCC.16 &sect;9.1.1 forbids the seal
     *   DOWNGRADED         CLEAR        PLAINTEXT   &lt;- a completed downgrade
     *   DOWNGRADED         UNREADABLE   PLAINTEXT
     *   ENGINE_UNAVAILABLE LATCHED      REFUSE
     *   ENGINE_UNAVAILABLE CLEAR        PLAINTEXT
     *   ENGINE_UNAVAILABLE UNREADABLE   PLAINTEXT
     *   NO_MLS_IDENTITY    LATCHED      REFUSE      &lt;- differs from changeGroupMembership
     *   NO_MLS_IDENTITY    CLEAR        PLAINTEXT
     *   NO_MLS_IDENTITY    UNREADABLE   PLAINTEXT
     * </pre>
     *
     * <p>{@code changeGroupMembership} answers plaintext for no identity, since a roster change
     * carries no content. Here the latched bit means the user sees a padlock on this content, so
     * the send is refused; a downgrade clears the bit.
     *
     * @param engine never null
     * @param latch never null
     */
    public static Verdict decide(final SealCapability engine, final MlsLatch latch) {
        if (engine == null || latch == null) {
            // Refuse when the inputs are missing: the only answer that cannot leak cleartext.
            return Verdict.REFUSE;
        }
        if (engine == SealCapability.SEALABLE) {
            return Verdict.SEAL;
        }
        return latch == MlsLatch.LATCHED ? Verdict.REFUSE : Verdict.PLAINTEXT;
    }

    /**
     * The {@link MlsLatch} for a read MLS bit, or {@link MlsLatch#UNREADABLE} when the read failed.
     * Takes the MLS bit alone, not {@link EncryptionProtocolBits}: the padlock is drawn on either
     * bit, and folding in the provider plane would refuse conversations the provider encrypts.
     *
     * @param mlsBit the MLS bit, or {@code null} when the read failed
     */
    public static MlsLatch latchOf(final Boolean mlsBit) {
        if (mlsBit == null) return MlsLatch.UNREADABLE;
        return mlsBit ? MlsLatch.LATCHED : MlsLatch.CLEAR;
    }
}
