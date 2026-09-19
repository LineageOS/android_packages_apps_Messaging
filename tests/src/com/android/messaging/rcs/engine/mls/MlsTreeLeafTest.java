/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link MlsTreeLeaf} keeps "we could not read this leaf" apart from "this leaf is fine". */
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

    /** Expired is readable, and distinct from unreadable. */
    @Test
    public void anExpiredWindowIsNegativeAndStillReadable() {
        final MlsTreeLeaf l = new MlsTreeLeaf(0, NOW - 100 * DAY, NOW - DAY, "+15715550103");
        assertFalse("expired is not unreadable", l.unreadable());
        assertEquals(-1L, l.remainingDays(NOW));
    }

    /** {@code Long.MIN_VALUE}, unlike 0, cannot pass a floor comparison as a measurement. */
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

    /** The engine reports {@code (0,0)} for "could not parse"; one zero bound is a reading. */
    @Test
    public void onlyBothBoundsZeroMeansUnreadable() {
        assertFalse(new MlsTreeLeaf(1, 0L, NOW + DAY, "+1").unreadable());
        assertEquals(1L, new MlsTreeLeaf(1, 0L, NOW + DAY, "+1").remainingDays(NOW));
        assertFalse(new MlsTreeLeaf(1, NOW, 0L, "+1").unreadable());
    }

    /** The engine strips the {@code +} and the caller's identity keeps it. */
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

    /** An unidentifiable leaf must never compare equal to an empty caller identity. */
    @Test
    public void anEmptyIdentityMatchesNothing() {
        assertFalse("empty leaf vs empty caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "").isIdentity(""));
        assertFalse("empty leaf vs a real caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "").isIdentity("+15715550104"));
        assertFalse("real leaf vs empty caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity(""));
        assertFalse("real leaf vs null caller",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity(null));
        assertFalse("a caller of only separators is empty too, not a wildcard",
                new MlsTreeLeaf(0, NOW, NOW + DAY, "15715550104").isIdentity("+-. ()"));
    }

    /** A null MSISDN is empty, never the string "null" in a log line. */
    @Test
    public void aMissingMsisdnIsEmptyNotTheWordNull() {
        final MlsTreeLeaf l = new MlsTreeLeaf(4, NOW, NOW + DAY, null);
        assertEquals("", l.msisdn);
        assertFalse(l.toString().contains("null"));
    }
}
