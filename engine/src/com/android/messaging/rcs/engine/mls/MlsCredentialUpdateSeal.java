/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * When a refused RCC.16 §9.5.3 certificate update may be offered again: a refusal of the
 * certificate seals the update until a new certificate exists; a refusal of our position seals it
 * only until we move. Pure functions of a moment and a verdict; nothing here is a timer.
 * See docs/mls/credentials.md.
 */
public final class MlsCredentialUpdateSeal {

    private MlsCredentialUpdateSeal() {}

    /**
     * Does a seal taken at {@code sealedAt} still suppress a re-offer at {@code positionNow}? Says
     * nothing about whether anything is sealed; read it together with the certificate marker.
     *
     * @param sealedAt the moment the refusal applies to, or {@code null} for a seal no move
     *     lifts
     * @param positionNow where the conversation is now, or {@code null} when the engine could not
     *     say
     */
    public static boolean stillStands(final MlsAppMessage.Moment sealedAt,
            final MlsAppMessage.Moment positionNow) {
        // A seal about the bytes does not lift on a move.
        if (sealedAt == null) return true;
        // An unreadable position keeps the seal: re-offering on a guess that we moved costs an
        // epoch and a self-heal per pass, a suppressed repair costs one maintenance cycle.
        if (positionNow == null) return true;
        return sealedAt.equals(positionNow);
    }

    /**
     * Was the server asked at all? Every transport verdict is non-negative, and {@code
     * commitAndSend} writes the {@code -1} sentinel at the top of each attempt, so a negative
     * verdict is the absence of an answer. Such an attempt is released, not sealed. Ask this before
     * {@link #isAboutTheBytes}.
     */
    public static boolean serverWasNeverAsked(final int verdict) {
        return verdict < 0;
    }

    /**
     * Did the server evaluate the commit we sent, as opposed to refusing where we stood or never
     * seeing it? Defined over {@link MlsTransportDisposition#ofVerdict}; an unrecognised verdict
     * answers false, the fail-safe direction (too narrow a seal costs one extra Commit, too wide
     * abandons the repair).
     */
    public static boolean isAboutTheBytes(final int verdict) {
        return MlsTransportDisposition.ofVerdict(verdict) == MlsTransportDisposition.PERMANENT;
    }

    /**
     * For a credential-validity refusal, was the credential it named ours? True when the detail is
     * not such a refusal or names us; false when it names a peer or nobody readable. RCC.16 A.4.3.2
     * §3 exempts only the committer's own leaf, so the same refusal means opposite things by
     * subject.
     */
    public static boolean judgedOurOwnCredential(final String detail, final String ourE164) {
        final MlsTimeValidationRefusal ref = MlsTimeValidationRefusal.parse(detail);
        return ref == null || ref.namesUs(ourE164);
    }
}
