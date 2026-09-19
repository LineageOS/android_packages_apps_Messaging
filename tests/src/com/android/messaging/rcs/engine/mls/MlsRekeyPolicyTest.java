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
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host tests for {@link MlsRekeyPolicy} — the leaf-rotation cadence Stage 6 moved out of
 * {@code MlsProviderTransport}.
 *
 * <p>The property worth asserting is not "256 is 256". It is the STAGGER: both members run the same
 * counter against the same bound, and before the tie-break existed they reached it together and
 * dueled — device-observed 2026-08-09, era 13 -> 17 in one bidirectional run, ending wedged with
 * every send INVALID_ARGUMENT. Verifying that on devices costs two devices and 256 real sends each;
 * here it is four assertions.
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

    /** The two sides must NOT rotate at the same count, or they duel. */
    @Test
    public void theTwoSidesRotateAtDifferentCounts() {
        assertNotEquals("the stagger is the whole fix for the bidirectional duel",
                MlsRekeyPolicy.rotateAt(true), MlsRekeyPolicy.rotateAt(false));
        assertEquals(MlsRekeyPolicy.REKEY_AFTER_SENDS, MlsRekeyPolicy.rotateAt(true));
        assertTrue("the non-advancer must rotate LATER, never earlier — otherwise the tie-break has "
                + "swapped and both sides still race, just in the other order",
                MlsRekeyPolicy.rotateAt(false) > MlsRekeyPolicy.rotateAt(true));
    }

    /**
     * The advancer rotates on the send that REACHES the threshold; the non-advancer does not.
     *
     * <p>The {@code + 1} is the part that can silently be wrong: the question is asked before the
     * send, about the send about to happen. Off by one and the rotation lands a message late, which
     * is only visible as a decrypt failure 256 sends later.
     */
    @Test
    public void rotationIsDueOnTheSendThatReachesTheThreshold() {
        final int at = MlsRekeyPolicy.REKEY_AFTER_SENDS;
        assertFalse(MlsRekeyPolicy.rotationDueOnNextSend(at - 2, true));
        assertTrue(MlsRekeyPolicy.rotationDueOnNextSend(at - 1, true));
        // Same counter, other side of the tie-break: not yet.
        assertFalse(MlsRekeyPolicy.rotationDueOnNextSend(at - 1, false));
        assertTrue(MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.rotateAt(false) - 1, false));
    }

    /**
     * The out-of-band fallback is UNSTAGGERED, and that is deliberate.
     *
     * <p>It runs only when the piggybacked rotation did not happen at all, so the duel the stagger
     * avoids is not the situation — and the alternative to a late rotation is none.
     */
    @Test
    public void theFallbackFiresAtTheBaseThresholdForBothSides() {
        assertFalse(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.REKEY_AFTER_SENDS - 1));
        assertTrue(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.REKEY_AFTER_SENDS));
        assertTrue(MlsRekeyPolicy.outOfBandRekeyDue(MlsRekeyPolicy.rotateAt(false)));
    }

    /**
     * A refused rotation lands BELOW the threshold, and the seam lands one short of it.
     *
     * <p>Asserted together because they are the two ways this counter is written from outside a
     * send, and they must land on opposite sides of the bound: the refusal must stop asking, and the
     * seam must make the next send ask.
     */
    @Test
    public void refusalBacksOffBelowTheThreshold_andTheSeamSitsOneShortOfIt() {
        assertEquals(256 - 64, MlsRekeyPolicy.counterAfterRefusal());
        assertFalse("a refused rotation must not ask again on the very next send",
                MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.counterAfterRefusal(), true));
        assertTrue(MlsRekeyPolicy.counterAfterRefusal() >= 0);

        assertEquals(MlsRekeyPolicy.REKEY_AFTER_SENDS - 1, MlsRekeyPolicy.seedForImminentRotation());
        assertTrue("the test seam must make the NEXT send rotate, or it is silently inert",
                MlsRekeyPolicy.rotationDueOnNextSend(MlsRekeyPolicy.seedForImminentRotation(), true));
    }
}
