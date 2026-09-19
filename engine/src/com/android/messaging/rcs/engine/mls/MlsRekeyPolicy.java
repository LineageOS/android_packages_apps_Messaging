/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * When our own leaf key rotates, and what a refused rotation costs. Rotation resets the application
 * ratchet to generation 0 before a long in-place run passes the receiver's {@code max_skip} window.
 * The threshold is staggered by {@link MlsRecoveryPolicy#weAreEraAdvancer} so two members under
 * mutual traffic do not commit-duel, and a refusal backs the counter off rather than restoring it.
 * The threshold is a constant, not a sysprop, so the behaviour needs no operator to arm it.
 */
public final class MlsRekeyPolicy {

    private MlsRekeyPolicy() {}

    /** Rotate our leaf after this many app sends; the app's own choice. */
    public static final int REKEY_AFTER_SENDS = 256;

    /**
     * How far below {@link #REKEY_AFTER_SENDS} a refused rotation drops the counter, in sends, so
     * the loser of a commit race lets the winner's commit land first.
     */
    public static final int REKEY_REFUSED_BACKOFF_SENDS = 64;

    /** @param weGoFirst {@link MlsRecoveryPolicy#weAreEraAdvancer} for (self, peer) */
    public static int rotateAt(final boolean weGoFirst) {
        return weGoFirst ? REKEY_AFTER_SENDS : REKEY_AFTER_SENDS + (REKEY_AFTER_SENDS / 2);
    }

    /**
     * Whether the next send carries a piggybacked key update, hence the {@code + 1}. Reads the
     * rotation counter, not the per-epoch one, which any peer or Add-only commit resets.
     */
    public static boolean rotationDueOnNextSend(final int sendsSinceLeafRotation,
            final boolean weGoFirst) {
        return sendsSinceLeafRotation + 1 >= rotateAt(weGoFirst);
    }

    /**
     * Whether the out-of-band fallback rekey is due. Unstaggered: it runs only when the piggybacked
     * rotation did not happen at all, so there is no duel to avoid.
     */
    public static boolean outOfBandRekeyDue(final int sendsSinceLeafRotation) {
        return sendsSinceLeafRotation >= REKEY_AFTER_SENDS;
    }

    /** Where both send counters land after a refused rotation; never below zero. */
    public static int counterAfterRefusal() {
        return Math.max(0, REKEY_AFTER_SENDS - REKEY_REFUSED_BACKOFF_SENDS);
    }

    /** A counter that puts the next send one short of the base threshold; for the test seam. */
    public static int seedForImminentRotation() {
        return REKEY_AFTER_SENDS - 1;
    }
}
