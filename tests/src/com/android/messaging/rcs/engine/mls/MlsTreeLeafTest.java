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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link MlsTreeLeaf}. The whole of this class's job is keeping "we could not
 * read this leaf" apart from "this leaf is fine", so that is what is pinned.
 */
@RunWith(JUnit4.class)
public class MlsTreeLeafTest {

    private static final long NOW = 1789165969L;
    private static final long DAY = 86400L;

    @Test
    public void aReadableWindowReportsWholeDaysRemaining() {
        final MlsTreeLeaf l = new MlsTreeLeaf(2, NOW - 10 * DAY, NOW + 41 * DAY, "+15715550104");
        assertFalse(l.unreadable());
        assertEquals(41L, l.remainingDays(NOW));
        assertEquals("+15715550104", l.msisdn);
        assertEquals(2, l.leafIndex);
    }

    /** Past the floor and past expiry are DIFFERENT, and both are readable. */
    @Test
    public void anExpiredWindowIsNegativeAndStillReadable() {
        final MlsTreeLeaf l = new MlsTreeLeaf(0, NOW - 100 * DAY, NOW - DAY, "+15715550103");
        assertFalse("expired is not unreadable", l.unreadable());
        assertEquals(-1L, l.remainingDays(NOW));
    }

    /**
     * THE ASSERTION THIS CLASS EXISTS FOR. An unreadable window must not come back as a small
     * number that a floor comparison silently accepts — {@code 0} would read as "expired today" and
     * a naive {@code < 30} test would treat it as inside the floor on no evidence. Long.MIN_VALUE
     * cannot be mistaken for a measurement.
     */
    @Test
    public void anUnreadableWindowCannotBeMistakenForAMeasurement() {
        final MlsTreeLeaf l = new MlsTreeLeaf(3, 0L, 0L, "");
        assertTrue(l.unreadable());
        assertEquals(Long.MIN_VALUE, l.remainingDays(NOW));
        assertTrue("the rendering must SAY so rather than printing a window",
                l.toString().contains("UNREADABLE"));
        assertFalse("and must not print a notAfter it does not have",
                l.toString().contains("notAfter"));
    }

    /**
     * A HALF-READ LEAF IS STILL UNREADABLE ONLY WHEN BOTH BOUNDS ARE ZERO. The engine reports
     * {@code (0,0)} for "could not parse"; a leaf with a real notAfter and a zero notBefore is a
     * window we DID read, and collapsing it into the unreadable bucket would discard a usable
     * measurement.
     */
    @Test
    public void onlyBothBoundsZeroMeansUnreadable() {
        assertFalse(new MlsTreeLeaf(1, 0L, NOW + DAY, "+1").unreadable());
        assertEquals(1L, new MlsTreeLeaf(1, 0L, NOW + DAY, "+1").remainingDays(NOW));
        assertFalse(new MlsTreeLeaf(1, NOW, 0L, "+1").unreadable());
    }

    /**
     * THE REGRESSION THIS METHOD EXISTS FOR, device-measured on 00AU 2026-09-11 before the fix.
     *
     * <p>The engine strips the {@code +} and the caller's identity keeps it, so the bare
     * {@code equals} the probe shipped with was false for EVERY leaf of EVERY group on EVERY
     * device: a comparison that CANNOT PASS. It printed "OUR LEAF: ABSENT FROM THE MLS TREE" while
     * printing our own leaf one line above, and that branch tells an operator to re-add a member
     * that is already there.
     */
    @Test
    public void theLeafMatchesAnIdentityThatCarriesThePlus() {
        final MlsTreeLeaf l = new MlsTreeLeaf(1, NOW, NOW + 41 * DAY, "15715550104");
        assertTrue("the engine's stripped form must match a caller's +E.164",
                l.isIdentity("+15715550104"));
        assertTrue("and the same form on both sides", l.isIdentity("15715550104"));
        assertFalse("a different number must not match", l.isIdentity("+15715550103"));
    }

    /** {@code tel:} URIs and visual separators are non-significant, as the engine treats them. */
    @Test
    public void telUrisAndSeparatorsAreNonSignificant() {
        final MlsTreeLeaf l = new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104");
        assertTrue(l.isIdentity("tel:+1-571-555-0104"));
        assertTrue(l.isIdentity("tel:+15715550104;phone-context=example.com"));
        assertTrue(l.isIdentity("+1 (571) 555-0104"));
    }

    /**
     * "WE COULD NOT TELL WHO THIS IS" IS NOT "THIS IS YOU", on either side. An unreadable SAN
     * renders as {@code (no readable SAN)}; if it compared equal to a caller with nothing, an
     * unidentifiable leaf would be reported as ours.
     */
    @Test
    public void anEmptyIdentityMatchesNothing() {
        assertFalse("empty leaf vs empty caller", new MlsTreeLeaf(0, NOW, NOW + DAY, "").isIdentity(""));
        assertFalse("empty leaf vs a real caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "").isIdentity("+15715550104"));
        assertFalse("real leaf vs empty caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity(""));
        assertFalse("real leaf vs null caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity(null));
        assertFalse("a caller of only separators is empty too, not a wildcard",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity("+-. ()"));
    }

    /** A null MSISDN is empty, never the string "null" printed into an operator's log line. */
    @Test
    public void aMissingMsisdnIsEmptyNotTheWordNull() {
        final MlsTreeLeaf l = new MlsTreeLeaf(4, NOW, NOW + DAY, null);
        assertEquals("", l.msisdn);
        assertFalse(l.toString().contains("null"));
    }
}
