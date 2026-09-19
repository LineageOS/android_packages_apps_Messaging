/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/**
 * The RCC.16 §10.3 resent-message component and the recipient-selector MAC. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsResentMessageTest {

    private static byte[] key32(final byte b) {
        final byte[] k = new byte[32];
        Arrays.fill(k, b);
        return k;
    }

    /** Builds the 64-byte field a sender would emit for a given target's bytes. */
    private static byte[] field(final byte[] key, final byte[] targetBytes) {
        final byte[] tag = MlsResentMessage.mac(key, targetBytes);
        final byte[] f = new byte[64];
        System.arraycopy(key, 0, f, 0, 32);
        System.arraycopy(tag, 0, f, 32, 32);
        return f;
    }

    /**
     * A MAC mismatch is not an error: verify-and-fail would fault instead of dropping, and a fault
     * that emits a negative receipt reports a healthy message as failed. Asserts only that our
     * {@code classify} answers {@code NOT_FOR_ME} for a field built against a different input.
     */
    @Test
    public void aMismatchIsNotForMe_neverAnError() {
        final byte[] f = field(key32((byte) 0xAB), "alice-bytes".getBytes());
        // We are Bob: we compute with our bytes, so the tag does not match.
        assertEquals(MlsResentMessage.MacVerdict.NOT_FOR_ME,
                MlsResentMessage.classify(f, "bob-bytes".getBytes()));
    }

    /**
     * The input the field was built against matches: {@code classify} is a function of its input.
     * It does not select a recipient; the MAC'd datum is recipient-invariant and the key is in the
     * clear.
     */
    @Test
    public void theIntendedTargetMatches() {
        final byte[] f = field(key32((byte) 0xAB), "alice-bytes".getBytes());
        assertEquals(MlsResentMessage.MacVerdict.FOR_ME,
                MlsResentMessage.classify(f, "alice-bytes".getBytes()));
    }

    /** Given three distinct candidate inputs, our {@code classify} matches exactly one. */
    @Test
    public void exactlyOneOfThreeMembersIsTheTarget() {
        final byte[] f = field(key32((byte) 0x11), "member-2".getBytes());
        int forMe = 0;
        for (final String who : new String[] { "member-1", "member-2", "member-3" }) {
            if (MlsResentMessage.classify(f, who.getBytes())
                    == MlsResentMessage.MacVerdict.FOR_ME) {
                forMe++;
            }
        }
        assertEquals("exactly one member is the target", 1, forMe);
    }

    /** The key is the first half of the field, in the clear, not a derived secret. */
    @Test
    public void theKeyIsTheFirstHalfOfTheFieldItself() {
        final byte[] key = key32((byte) 0x5A);
        final byte[] data = "recipient bytes".getBytes();
        final byte[] f = field(key, data);
        assertArrayEquals("the first 32 bytes ARE the key",
                key, Arrays.copyOfRange(f, 0, 32));
        assertArrayEquals("the second 32 bytes are HMAC-SHA256(key, data)",
                MlsResentMessage.mac(key, data), Arrays.copyOfRange(f, 32, 64));
    }

    @Test
    public void anEmptyFieldIsItsOwnMode() {
        assertEquals(MlsResentMessage.MacVerdict.EMPTY,
                MlsResentMessage.classify(new byte[0], "x".getBytes()));
        assertEquals(MlsResentMessage.MacVerdict.EMPTY,
                MlsResentMessage.classify(null, "x".getBytes()));
    }

    @Test
    public void aWrongLengthFieldIsItsOwnMode() {
        assertEquals(MlsResentMessage.MacVerdict.INVALID_LENGTH,
                MlsResentMessage.classify(new byte[63], "x".getBytes()));
        assertEquals(MlsResentMessage.MacVerdict.INVALID_LENGTH,
                MlsResentMessage.classify(new byte[65], "x".getBytes()));
    }

    @Test
    public void absentIsABareZeroTag() {
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(new byte[] { 0x00 });
        assertNotNull(p);
        assertFalse(p.present());
        assertEquals(MlsResentMessage.TAG_ABSENT, p.tag);
    }

    /**
     * The decoder reports which prefix width matched (u8, u16 or mls_varint) rather than assuming
     * one; the width is not established.
     */
    @Test
    public void theDecoderReportsWhichPrefixWidthMatched() {
        // 200 > 0x3F, so a 1-byte varint cannot hold it: separates u8 from varint.
        final byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 0x7E);
        final byte[] u8 = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, payload, MlsResentMessage.PrefixWidth.U8);
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(u8);
        assertNotNull(p);
        assertTrue(p.present());
        assertEquals(MlsResentMessage.PrefixWidth.U8, p.width);
        assertArrayEquals(payload, p.payload);
    }

    @Test
    public void aU16PrefixIsDistinguishedFromU8() {
        final byte[] payload = new byte[300];          // > 255, so u8 cannot express it
        final byte[] u16 = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, payload, MlsResentMessage.PrefixWidth.U16);
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(u16);
        assertNotNull(p);
        assertEquals(MlsResentMessage.PrefixWidth.U16, p.width);
        assertEquals(300, p.payload.length);
    }

    /** For a short payload u8 and a 1-byte mls_varint are the same byte; the decoder says so. */
    @Test
    public void aShortPayloadIsReportedAmbiguousRatherThanGuessed() {
        final byte[] short8 = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, new byte[] { 1, 2, 3 },
                MlsResentMessage.PrefixWidth.U8);
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(short8);
        assertNotNull(p);
        assertTrue("u8 and a 1-byte varint coincide here — say so", p.ambiguous);
    }

    /** Encoding without stating the width is refused rather than defaulted. */
    @Test
    public void encodingRefusesToDefaultThePrefixWidth() {
        try {
            MlsResentMessage.encode(MlsResentMessage.TAG_RESENT, new byte[] { 9 },
                    MlsResentMessage.PrefixWidth.UNKNOWN);
            fail("encode must refuse an unstated prefix width");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not established"));
        }
    }

    /**
     * The matching candidate names itself, so a captured resend identifies the recipient-specific
     * input.
     */
    @Test
    public void selectReportsWhichCandidateMatched() {
        final byte[] f = field(key32((byte) 0x33), "+15715550107".getBytes());
        final java.util.LinkedHashMap<String, byte[]> cands = new java.util.LinkedHashMap<>();
        cands.put("leaf-index", new byte[] { 0, 0, 0, 0 });
        cands.put("msisdn-e164", "+15715550107".getBytes());
        cands.put("msisdn-digits", "15715550107".getBytes());
        final MlsResentMessage.Selection s = MlsResentMessage.select(f, cands);
        assertEquals(MlsResentMessage.MacVerdict.FOR_ME, s.verdict);
        assertEquals("msisdn-e164", s.candidate);
    }

    /** No candidate matching is NOT_FOR_ME, with no candidate named. */
    @Test
    public void noCandidateMatchingIsBenignNotAnError() {
        final byte[] f = field(key32((byte) 0x44), "someone-else".getBytes());
        final java.util.LinkedHashMap<String, byte[]> cands = new java.util.LinkedHashMap<>();
        cands.put("msisdn-e164", "+15715550107".getBytes());
        final MlsResentMessage.Selection s = MlsResentMessage.select(f, cands);
        assertEquals(MlsResentMessage.MacVerdict.NOT_FOR_ME, s.verdict);
        assertNull(s.candidate);
    }

    /** Structural failures survive the candidate path unchanged. */
    @Test
    public void selectKeepsTheStructuralVerdicts() {
        assertEquals(MlsResentMessage.MacVerdict.EMPTY,
                MlsResentMessage.select(new byte[0], null).verdict);
        assertEquals(MlsResentMessage.MacVerdict.INVALID_LENGTH,
                MlsResentMessage.select(new byte[10], null).verdict);
    }

    /** An ordinary message's AAD ends in the absent component, one 0x00. */
    @Test
    public void anOrdinaryAadTrailingSlotIsAbsent() {
        final byte[] aad = MlsAppMessage.buildAuthenticatedData("MxSa=szpxmSJWBK9-TTcZaPA", 6);
        final byte[] tail = MlsAppMessage.aadTrailing(aad);
        assertNotNull(tail);
        assertArrayEquals(new byte[] { 0x00 }, tail);
        assertFalse(MlsResentMessage.parse(tail).present());
    }

    /** Pinned against a peer's AAD: the trailing slot is where this class says it is. */
    @Test
    public void theTrailingSlotIsCorrectOnACapturedAad() {
        final String hex = "0001184d7853613d737a70786d534a57424b392d5454635a6150410000000600";
        final byte[] aad = new byte[hex.length() / 2];
        for (int i = 0; i < aad.length; i++) {
            aad[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        assertEquals("MxSa=szpxmSJWBK9-TTcZaPA", MlsAppMessage.aadMessageId(aad));
        assertArrayEquals("a peer's ordinary message carries the ABSENT component",
                new byte[] { 0x00 }, MlsAppMessage.aadTrailing(aad));
    }

    @Test
    public void malformedTailsDoNotParse() {
        assertNull(MlsResentMessage.parse(null));
        assertNull(MlsResentMessage.parse(new byte[0]));
        // Tag present, but the length prefix overruns the buffer under every candidate width.
        assertNull(MlsResentMessage.parse(new byte[] { 0x02, (byte) 0xFF, 0x01 }));
    }
}
