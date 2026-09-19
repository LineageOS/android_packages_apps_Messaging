/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MlsOutboundHold}, the test fixture that withholds our own rekey commits to put us ahead of
 * the group: what is withheld, what happens at capacity, whether the derived group epoch survives
 * traffic, and whether a restore that did not land can be told from one that did.
 */
public final class MlsOutboundHoldTest {

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
     * At capacity the outbound hold refuses rather than publishes: a rekey is our own housekeeping,
     * and the Nth commit would be refused while the server has not seen the earlier ones.
     */
    @Test
    public void atCapacityTheCommitIsRefusedRatherThanPublished() {
        assertEquals(MlsOutboundHold.Verdict.SUPPRESS,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY - 1));
        assertEquals(MlsOutboundHold.Verdict.REFUSE,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY));
        assertEquals(MlsOutboundHold.Verdict.REFUSE,
                MlsOutboundHold.decide(true, true, true, MlsOutboundHold.CAPACITY + 5));
        // An out-of-scope commit is still published when the store is full.
        assertEquals(MlsOutboundHold.Verdict.PUBLISH,
                MlsOutboundHold.decide(true, false, true, MlsOutboundHold.CAPACITY));
    }

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

    /** "Could not tell" is not spelled the same as "it went back". */
    @Test
    public void anUnreadableReadingIsAFailureNotAPass() {
        assertFalse(MlsOutboundHold.restoredCleanly(true, -1, 7L, -1, 7L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, -1L, 3, -1L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, 7L, -1, 7L));
        assertFalse(MlsOutboundHold.restoredCleanly(true, 3, 7L, 3, -1L));
    }

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

    /** A peer's commit advances both sides by one, so the difference survives inbound traffic. */
    @Test
    public void inboundTrafficDoesNotBreakTheDerivation() {
        // Armed at 5, withheld 2 of ours and applied 4 of the peer's: we are at 11, the group at 9.
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, 11L, 2);
        assertEquals(9L, g.groupEpoch);
        assertEquals(2L, g.epochsAhead);
        // Not consistent: our epoch moved by more than the withheld commits, so a release that
        // restores the stashed snapshot will not land where the arm-time reading says.
        assertFalse("the consistency check must notice that something else moved our epoch",
                g.consistent);
    }

    /** An era change resets the epoch counter, so the subtraction refuses. */
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

    /** Nothing withheld yet: a zero gap, consistent. */
    @Test
    public void anArmedButIdleFixtureIsAZeroGap() {
        final MlsOutboundHold.Gap g = MlsOutboundHold.measure(2, 5L, 2, 5L, 0);
        assertEquals(5L, g.groupEpoch);
        assertEquals(0L, g.epochsAhead);
        assertTrue(g.consistent);
    }

    @Test
    public void onlyTheSixVerbsAreAccepted() {
        for (final String v : new String[] {"arm", "status", "disarm", "release", "publish",
                "drop"}) {
            assertEquals(v, MlsOutboundHold.verbOf(v));
            assertEquals(v,
                    MlsOutboundHold.verbOf("  " + v.toUpperCase(java.util.Locale.US) + " "));
        }
        // An unrecognised verb answers null rather than a default.
        assertNull(MlsOutboundHold.verbOf("armed"));
        assertNull(MlsOutboundHold.verbOf(""));
        assertNull(MlsOutboundHold.verbOf(null));
        assertNull(MlsOutboundHold.verbOf("restore"));
    }
}
