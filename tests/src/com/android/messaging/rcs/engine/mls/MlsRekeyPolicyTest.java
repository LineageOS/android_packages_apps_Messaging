/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MlsRekeyPolicy}, the leaf-rotation cadence. Both members count against the same bound, so
 * the stagger is what keeps them from reaching it together and duelling.
 */
public final class MlsRekeyPolicyTest {

    @Test
    public void theThresholdIsOurs_andTheBackoffIsBelowIt() {
        assertEquals(256, MlsRekeyPolicy.REKEY_AFTER_SENDS);
        assertEquals(64, MlsRekeyPolicy.REKEY_REFUSED_BACKOFF_SENDS);
        assertTrue("a backoff at or past the threshold would make a refused rotation retry on the "
                + "very next send, which is the storm this constant exists to stop",
                MlsRekeyPolicy.REKEY_REFUSED_BACKOFF_SENDS < MlsRekeyPolicy.REKEY_AFTER_SENDS);
    }

    /** The two sides rotate at different counts, or they duel. */
    @Test
    public void theTwoSidesRotateAtDifferentCounts() {
        assertNotEquals("the stagger is the whole fix for the bidirectional duel",
                MlsRekeyPolicy.rotateAt(true), MlsRekeyPolicy.rotateAt(false));
        assertEquals(MlsRekeyPolicy.REKEY_AFTER_SENDS, MlsRekeyPolicy.rotateAt(true));
        assertTrue(
                "the non-advancer must rotate LATER, never earlier — otherwise the tie-break has "
                + "swapped and both sides still race, just in the other order",
                MlsRekeyPolicy.rotateAt(false) > MlsRekeyPolicy.rotateAt(true));
    }

    /** Asked before the send, about that send; off by one, the rotation lands a message late. */
    @Test
    public void rotationIsDueOnTheSendThatReachesTheThreshold() {
        final int at = MlsRekeyPolicy.REKEY_AFTER_SENDS;
        assertFalse(MlsRekeyPolicy.rotationDueOnNextSend(at - 2, true));
        assertTrue(MlsRekeyPolicy.rotationDueOnNextSend(at - 1, true));
        // Same counter, other side of the tie-break: not yet.
        assertFalse(MlsRekeyPolicy.rotationDueOnNextSend(at - 1, false));
        assertTrue(MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.rotateAt(false) - 1, false));
    }

    /** The out-of-band fallback is unstaggered: it runs only when no piggybacked rotation ran. */
    @Test
    public void theFallbackFiresAtTheBaseThresholdForBothSides() {
        assertFalse(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.REKEY_AFTER_SENDS - 1));
        assertTrue(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.REKEY_AFTER_SENDS));
        assertTrue(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.rotateAt(false)));
    }

    /**
     * A refusal lands below the bound and stops asking; the seam lands one short and makes the next
     * send ask.
     */
    @Test
    public void refusalBacksOffBelowTheThreshold_andTheSeamSitsOneShortOfIt() {
        assertEquals(256 - 64, MlsRekeyPolicy.counterAfterRefusal());
        assertFalse("a refused rotation must not ask again on the very next send",
                MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.counterAfterRefusal(), true));
        assertTrue(MlsRekeyPolicy.counterAfterRefusal() >= 0);

        assertEquals(MlsRekeyPolicy.REKEY_AFTER_SENDS - 1,
                MlsRekeyPolicy.seedForImminentRotation());
        assertTrue("the test seam must make the NEXT send rotate, or it is silently inert",
                MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.seedForImminentRotation(),
                        true));
    }
}
