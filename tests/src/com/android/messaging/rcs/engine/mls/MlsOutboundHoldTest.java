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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The AHEAD fixture's decision and its MEASUREMENT.
 *
 * <p>A fixture that is wrong produces experiments that are wrong and nothing reports an error, so
 * the parts that can be checked without a device are checked here: what is withheld, what happens at
 * capacity, whether the derived group epoch survives the traffic it has to survive, and — the whole
 * point — whether a restore that did not land can be told from one that did.
 */
public final class MlsOutboundHoldTest {

    // ---- the decision -------------------------------------------------------------------------

    @Test
    public void nothingIsWithheldUnlessArmedInScopeAndARekey() {
        assertEquals(MlsOutboundHold.Verdict.PUBLISH,
                MlsOutboundHold.decide(false, true, true, 0));
        assertEquals(MlsOutboundHold.Verdict.PUBLISH,
                MlsOutboundHold.decide(true, false, true, 0));
        assertEquals("an add/remove/leave must never be withheld — suppressing one leaves the RCS "
                        + "roster and the MLS roster disagreeing, which is a different experiment",
                MlsOutboundHold.Verdict.PUBLISH, MlsOutboundHold.decide(true, true, false, 0));
        assertEquals(MlsOutboundHold.Verdict.SUPPRESS,
                MlsOutboundHold.decide(true, true, true, 0));
    }

    /**
     * At capacity the OUTBOUND lever REFUSES where the inbound one passes. Nothing here belongs to
     * anyone else — a rekey is our own housekeeping — and publishing the Nth commit while the server
     * has not seen 1..N-1 would be refused anyway.
     */
    @Test
    public void atCapacityTheCommitIsRefusedRatherThanPublished() {
        assertEquals(MlsOutboundHold.Verdict.SUPPRESS,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY - 1));
        assertEquals(MlsOutboundHold.Verdict.REFUSE,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY));
        assertEquals(MlsOutboundHold.Verdict.REFUSE,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY + 5));
        // and an out-of-scope commit is still published even when the store is full
        assertEquals(MlsOutboundHold.Verdict.PUBLISH,
                MlsOutboundHold.decide(true, false, true, MlsOutboundHold.CAPACITY));
    }

    // ---- the restore measurement, which is the whole job ---------------------------------------

    @Test
    public void aRestoreThatDidNotLandIsNotReportedAsSuccess() {
        assertTrue("the only clean case: the engine said so AND both numbers went back",
                MlsOutboundHold.restoredCleanly(true, 3, 7L, 3, 7L));

        assertFalse("the engine said the call failed",
                MlsOutboundHold.restoredCleanly(false, 3, 7L, 3, 7L));
        assertFalse("the epoch did NOT go back — this is the failure the whole fixture is about: "
                        + "restoreGroupSnapshot returning true says the call did not fail, not that "
                        + "the state moved",
                MlsOutboundHold.restoredCleanly(true, 3, 7L, 3, 8L));
        assertFalse("the era did not go back", MlsOutboundHold.restoredCleanly(true, 3, 7L, 4, 7L));
    }

    /** "I could not tell" must not be spelled the same way as "it went back". */
    @Test
    public void anUnreadableReadingIsAFailureNotAPass() {
        assertFalse(MlsOutboundHold.restoredCleanly(true, -1, 7L, -1, 7L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, -1L, 3, -1L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, 7L, -1, 7L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, 7L, 3, -1L));
    }

    // ---- the gap arithmetic --------------------------------------------------------------------

    @Test
    public void theGroupEpochIsOursMinusWhatItNeverSaw() {
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, 8L, 3);
        assertEquals(8L, g.ourEpoch);
        assertEquals(3, g.suppressed);
        assertEquals(5L, g.groupEpoch);
        assertEquals(3L, g.epochsAhead);
        assertFalse(g.eraMoved);
        assertTrue(g.consistent);
    }

    /**
     * A peer's commit advances BOTH sides by one, so the derived group epoch survives inbound
     * traffic — but only because the difference is what is measured, not the absolute numbers.
     */
    @Test
    public void inboundTrafficDoesNotBreakTheDerivation() {
        // armed at epoch 5, we withheld 2 of our own AND applied 4 of the peer's: we are at 11,
        // the group is at 9, and we are 2 ahead.
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, 11L, 2);
        assertEquals(9L, g.groupEpoch);
        assertEquals(2L, g.epochsAhead);
        // ...and it is NOT consistent, because our epoch moved by more than the commits we withheld.
        // That is the honest answer: the lever did not make all of this difference, so a release
        // that restores the stashed snapshot will not land where the arm-time reading says.
        assertFalse("the consistency check must notice that something else moved our epoch",
                g.consistent);
    }

    /** An era change resets the epoch counter, so the subtraction must refuse rather than lie. */
    @Test
    public void anEraChangeInvalidatesTheArithmeticRatherThanSilentlyLying() {
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 3, 1L, 2);
        assertTrue(g.eraMoved);
        assertEquals(-1L, g.groupEpoch);
        assertEquals(-1L, g.epochsAhead);
        assertFalse(g.consistent);
    }

    @Test
    public void anUnreadableEpochIsNotAZeroGap() {
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, -1L, 2);
        assertEquals(-1L, g.groupEpoch);
        assertEquals(-1L, g.epochsAhead);
        assertFalse(g.consistent);
    }

    /** Nothing is withheld yet: the group is where we are, and that is consistent. */
    @Test
    public void anArmedButIdleFixtureIsAZeroGap() {
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, 5L, 0);
        assertEquals(5L, g.groupEpoch);
        assertEquals(0L, g.epochsAhead);
        assertTrue(g.consistent);
    }

    // ---- the verb parser -----------------------------------------------------------------------

    @Test
    public void onlyTheSixVerbsAreAccepted() {
        for (final String v : new String[] {"arm", "status", "disarm", "release", "publish", "drop"}) {
            assertEquals(v, MlsOutboundHold.verbOf(v));
            assertEquals(v, MlsOutboundHold.verbOf("  " + v.toUpperCase(java.util.Locale.US) + " "));
        }
        // An unrecognised verb answers null rather than defaulting to anything — a fixture that
        // silently does something other than what was typed is worse than one that refuses.
        assertNull(MlsOutboundHold.verbOf("armed"));
        assertNull(MlsOutboundHold.verbOf(""));
        assertNull(MlsOutboundHold.verbOf(null));
        assertNull(MlsOutboundHold.verbOf("restore"));
    }
}
