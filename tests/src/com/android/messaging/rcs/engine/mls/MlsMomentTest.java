/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

/**
 * The {@code (era, epoch)} moment: decoding and ordering. Both fields are unsigned, and the epoch
 * is a full u64; small test values survive a decoder that reads only its low half.
 */
public final class MlsMomentTest {

    /** {@code [era u32 BE][epoch u64 BE]}, matching the core's {@code era_epoch}. */
    private static byte[] blob(final long era, final long epoch) {
        final byte[] b = new byte[12];
        for (int i = 0; i < 4; i++) b[3 - i] = (byte) ((era >>> (8 * i)) & 0xff);
        for (int i = 0; i < 8; i++) b[11 - i] = (byte) ((epoch >>> (8 * i)) & 0xff);
        return b;
    }

    /**
     * The epoch occupies bytes 4..11; reading only 8..11 would decode any epoch ≥ 2^32 as a small
     * number.
     */
    @Test
    public void epochIsBytesFourToEleven() {
        assertEquals(0x0102030405060708L, MlsAppMessage.epochFrom(blob(7, 0x0102030405060708L)));
        assertEquals(7, MlsAppMessage.eraFrom(blob(7, 0x0102030405060708L)));
        // Only the low 4 bytes would survive a half-width read.
        assertTrue("an epoch above 2^32 must not truncate to its low half",
                MlsAppMessage.epochFrom(blob(0, 0x00000001_00000000L)) != 0L);
    }

    /** A high epoch does not read as negative. */
    @Test
    public void largeEpochDoesNotGoNegative() {
        final long e = MlsAppMessage.epochFrom(blob(0, 0x8000000000000000L));
        assertEquals("bit pattern must survive", 0x8000000000000000L, e);
        assertEquals("and must render unsigned", "9223372036854775808", Long.toUnsignedString(e));
    }

    /**
     * A new era restarts the epoch at 0, so {@code (era=1, epoch=0)} is newer than
     * {@code (era=0, epoch=2^63)}: era dominates, and a signed comparison gets this backwards.
     */
    @Test
    public void eraDominatesEvenAHugeEpoch() {
        final Moment newEra = new Moment(1, 0L);
        final Moment hugeEpochOldEra = new Moment(0, 0x8000000000000000L);
        assertTrue("(era=1,epoch=0) must be newer than (era=0,epoch=2^63)",
                newEra.isNewerThan(hugeEpochOldEra));
        assertTrue(hugeEpochOldEra.isOlderThan(newEra));
        assertTrue(newEra.compareTo(hugeEpochOldEra) > 0);
    }

    /** A top-half era does not sort below a small one. */
    @Test
    public void eraComparisonIsUnsigned() {
        final Moment big = new Moment(0x80000000, 0L);   // 2147483648 unsigned, negative signed
        final Moment small = new Moment(1, 0L);
        assertTrue("era 2^31 must be newer than era 1", big.isNewerThan(small));
        assertEquals("2147483648", Integer.toUnsignedString(big.era));
    }

    @Test
    public void epochComparisonIsUnsignedWithinAnEra() {
        final Moment hi = new Moment(3, 0x8000000000000000L);
        final Moment lo = new Moment(3, 1L);
        assertTrue(hi.isNewerThan(lo));
        assertTrue(lo.isOlderThan(hi));
    }

    @Test
    public void equalMomentsAreNeitherNewerNorOlder() {
        final Moment a = new Moment(2, 9L);
        final Moment b = new Moment(2, 9L);
        assertEquals(0, a.compareTo(b));
        assertTrue(!a.isNewerThan(b));
        assertTrue(!a.isOlderThan(b));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void decodesFromTheEngineBlobAndRejectsMalformed() {
        final Moment m = Moment.from(blob(4, 77L));
        assertNotNull(m);
        assertEquals(4, m.era);
        assertEquals(77L, m.epoch);
        assertNull(Moment.from(null));
        assertNull(Moment.from(new byte[11]));
        assertEquals(-1, MlsAppMessage.eraFrom(new byte[11]));
        assertEquals(-1L, MlsAppMessage.epochFrom(new byte[11]));
    }

    /**
     * The era is an unsigned u32 that advances by one and never wraps: a wrap to 0 would read as a
     * move backwards to every peer, with no way back, so the advance fails instead.
     */
    @Test
    public void eraAdvanceRefusesToWrapAtTheU32Ceiling() {
        assertEquals(2L, MlsAppMessage.nextEra(1L));
        assertEquals(0x80000000L, MlsAppMessage.nextEra(0x7FFFFFFFL));
        // The top of the unsigned range advances, not wraps.
        assertEquals(0xFFFFFFFFL, MlsAppMessage.nextEra(0xFFFFFFFEL));
        assertEquals("the ceiling must refuse, not wrap to 0", -1L,
                MlsAppMessage.nextEra(0xFFFFFFFFL));
        // A negative input is an era that already overflowed as a signed int; it does not advance.
        assertEquals(-1L, MlsAppMessage.nextEra(-1L));
    }

    /**
     * RCC.16 A.4.1 identity equality compares E.164 numbers, not strings, and an absent identity
     * never matches.
     */
    @Test
    public void msisdnEqualityIsE164NotStringEquality() {
        assertTrue(RccIdentity.msisdnEquals("tel:+1-555-111-0001", "+15551110001"));
        assertTrue(RccIdentity.msisdnEquals("+1 (555) 111-0001", "15551110001"));
        assertTrue("tel-URI parameters are not part of the number",
                RccIdentity.msisdnEquals("tel:+15551110001;phone-context=example.com",
                        "+15551110001"));
        assertTrue(!RccIdentity.msisdnEquals("+15551110001", "+15559999999"));
        // Empty matches nothing, including another empty.
        assertTrue(!RccIdentity.msisdnEquals("", "+15551110001"));
        assertTrue(!RccIdentity.msisdnEquals("+15551110001", ""));
        assertTrue(!RccIdentity.msisdnEquals("", ""));
        assertTrue(!RccIdentity.msisdnEquals(null, "+15551110001"));
        assertEquals("15551110001", RccIdentity.normalizeE164("tel:+1-555-111-0001"));
    }

    /**
     * The AAD carries the message id it was built with. The RCC.16 §7.5.3.1 comparison is the
     * engine's (ffi.rs {@code aad_id_check_tests}); this parse only feeds diagnostics.
     */
    @Test
    public void aadMessageIdReadsBackTheBuiltId() {
        final byte[] aad = MlsAppMessage.buildAuthenticatedData("mid-abc", 3);
        assertEquals("mid-abc", MlsAppMessage.aadMessageId(aad));
        // Garbage does not read as an id.
        assertNull(MlsAppMessage.aadMessageId(new byte[] {0x09, 0x09, 0x01}));
        assertNull(MlsAppMessage.aadMessageId(new byte[0]));
        assertNull(MlsAppMessage.aadMessageId(null));
    }

    /** The varint length form survives an id long enough to widen it (over 63 characters). */
    @Test
    public void aadMessageIdSurvivesAWideVarint() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('a' + (i % 26));
        final String longId = sb.toString();
        final byte[] aad = MlsAppMessage.buildAuthenticatedData(longId, 1);
        assertEquals(longId, MlsAppMessage.aadMessageId(aad));
    }

    /** toString does not print a large era or epoch as negative. */
    @Test
    public void rendersUnsigned() {
        assertEquals("(era=2147483648 epoch=9223372036854775808)",
                new Moment(0x80000000, 0x8000000000000000L).toString());
    }
}
