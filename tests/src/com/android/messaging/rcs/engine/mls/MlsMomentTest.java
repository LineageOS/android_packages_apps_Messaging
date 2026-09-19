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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

/**
 * The {@code (era, epoch)} moment — decoding and ordering.
 *
 * <p>These exist because the decoder was wrong in a way no test would have caught by round-tripping
 * small values: {@code epochFrom} read bytes <b>8..11</b> of the 12-byte {@code [era u32][epoch u64]}
 * blob and returned a signed {@code int}, so it took the LOW HALF of the epoch and signed it. Every
 * value we had ever tested with was small enough to survive that unharmed.
 */
public final class MlsMomentTest {

    /** {@code [era u32 BE][epoch u64 BE]}, matching {@code ffi.rs::era_epoch}. */
    private static byte[] blob(final long era, final long epoch) {
        final byte[] b = new byte[12];
        for (int i = 0; i < 4; i++) b[3 - i] = (byte) ((era >>> (8 * i)) & 0xff);
        for (int i = 0; i < 8; i++) b[11 - i] = (byte) ((epoch >>> (8 * i)) & 0xff);
        return b;
    }

    /**
     * THE REGRESSION. The epoch occupies bytes 4..11, not 8..11 — reading the low half discards the
     * top four bytes entirely, so any epoch ≥ 2^32 decoded to an unrelated small number.
     */
    @Test
    public void epochIsBytesFourToEleven() {
        assertEquals(0x0102030405060708L, MlsAppMessage.epochFrom(blob(7, 0x0102030405060708L)));
        assertEquals(7, MlsAppMessage.eraFrom(blob(7, 0x0102030405060708L)));
        // The precise old failure: only the low 4 bytes survived.
        assertTrue("an epoch above 2^32 must not truncate to its low half",
                MlsAppMessage.epochFrom(blob(0, 0x00000001_00000000L)) != 0L);
    }

    /** A high epoch must not read as negative — it is unsigned on the wire. */
    @Test
    public void largeEpochDoesNotGoNegative() {
        final long e = MlsAppMessage.epochFrom(blob(0, 0x8000000000000000L));
        assertEquals("bit pattern must survive", 0x8000000000000000L, e);
        assertEquals("and must render unsigned", "9223372036854775808", Long.toUnsignedString(e));
    }

    /**
     * THE SPEC'S OWN WORKED EXAMPLE, and the case a signed comparison gets backwards: a new era
     * restarts the epoch at 0, so {@code (era=1, epoch=0)} is strictly NEWER than
     * {@code (era=0, epoch=2^63)}. Era dominates absolutely.
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

    /** Both fields are unsigned; a top-half era must not sort below a small one. */
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
     * The era ceiling. Era is an UNSIGNED u32 that advances by exactly 1 and never wraps; Google Messages
     * panics rather than wrapping and refuses a backwards move outright. Wrapping to 0 would read as
     * a catastrophic move backwards to every peer, and unlike a failed advance there is no way back —
     * so the advance must fail instead.
     */
    @Test
    public void eraAdvanceRefusesToWrapAtTheU32Ceiling() {
        assertEquals(2L, MlsAppMessage.nextEra(1L));
        assertEquals(0x80000000L, MlsAppMessage.nextEra(0x7FFFFFFFL));
        // The top of the unsigned range is reachable — it must advance, not wrap.
        assertEquals(0xFFFFFFFFL, MlsAppMessage.nextEra(0xFFFFFFFEL));
        assertEquals("the ceiling must refuse, not wrap to 0", -1L,
                MlsAppMessage.nextEra(0xFFFFFFFFL));
        // A negative input is a signed-int era that already overflowed upstream; it must not advance.
        assertEquals(-1L, MlsAppMessage.nextEra(-1L));
    }

    /**
     * RCC.16 A.4.1 identity equality is an E.164 comparison, not a string comparison — the same
     * number arrives spelled several ways, and an ABSENT identity must never pass as a wildcard.
     */
    @Test
    public void msisdnEqualityIsE164NotStringEquality() {
        assertTrue(RccIdentity.msisdnEquals("tel:+1-555-111-0001", "+15551110001"));
        assertTrue(RccIdentity.msisdnEquals("+1 (555) 111-0001", "15551110001"));
        assertTrue("tel-URI parameters are not part of the number",
                RccIdentity.msisdnEquals("tel:+15551110001;phone-context=example.com", "+15551110001"));
        assertTrue(!RccIdentity.msisdnEquals("+15551110001", "+15559999999"));
        // Empty must not match anything, including another empty — an absent identity is not proof.
        assertTrue(!RccIdentity.msisdnEquals("", "+15551110001"));
        assertTrue(!RccIdentity.msisdnEquals("+15551110001", ""));
        assertTrue(!RccIdentity.msisdnEquals("", ""));
        assertTrue(!RccIdentity.msisdnEquals(null, "+15551110001"));
        assertEquals("15551110001", RccIdentity.normalizeE164("tel:+1-555-111-0001"));
    }

    /**
     * RCC.16 §7.5.3.1 — the AAD's message_id must EQUAL the transport's.
     *
     * <p>mls-rs decrypts happily when they disagree, because RFC 9420 authenticates the AAD without
     * assigning it meaning. Without this check a ciphertext replayed under a new transport id is
     * accepted and attributed to the new id.
     */
    @Test
    public void aadMessageIdMustMatchTheTransportId() {
        final byte[] aad = MlsAppMessage.buildAuthenticatedData("mid-abc", 3);
        assertEquals("mid-abc", MlsAppMessage.aadMessageId(aad));
        assertTrue(MlsAppMessage.aadMessageIdMatches(aad, "mid-abc"));
        assertTrue("a replay under a different id must be refused",
                !MlsAppMessage.aadMessageIdMatches(aad, "mid-xyz"));
        assertTrue(!MlsAppMessage.aadMessageIdMatches(aad, null));
        // A peer that binds NO AAD has asserted nothing to contradict — refusing it would reject
        // every implementation that does not bind one. Only a CONTRADICTING AAD may fail.
        assertTrue(MlsAppMessage.aadMessageIdMatches(new byte[0], "mid-abc"));
        assertTrue(MlsAppMessage.aadMessageIdMatches(null, "mid-abc"));
        // Garbage must not read as a match either way.
        assertNull(MlsAppMessage.aadMessageId(new byte[] {0x09, 0x09, 0x01}));
        assertTrue(MlsAppMessage.aadMessageIdMatches(new byte[] {0x09, 0x09, 0x01}, "mid-abc"));
    }

    /** The varint length form must survive an id long enough to widen it (>63 chars). */
    @Test
    public void aadMessageIdSurvivesAWideVarint() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('a' + (i % 26));
        final String longId = sb.toString();
        final byte[] aad = MlsAppMessage.buildAuthenticatedData(longId, 1);
        assertEquals(longId, MlsAppMessage.aadMessageId(aad));
        assertTrue(MlsAppMessage.aadMessageIdMatches(aad, longId));
    }

    /** toString must not print a large era or epoch as negative — it is read by humans debugging. */
    @Test
    public void rendersUnsigned() {
        assertEquals("(era=2147483648 epoch=9223372036854775808)",
                new Moment(0x80000000, 0x8000000000000000L).toString());
    }
}
