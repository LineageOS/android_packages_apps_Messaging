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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/** §10.3 resent-message component + the recipient-selector MAC. */
public class MlsResentMessageTest {

    private static byte[] key32(final byte b) {
        final byte[] k = new byte[32];
        Arrays.fill(k, b);
        return k;
    }

    /** Build the 64-byte field a sender would emit for a given target's bytes. */
    private static byte[] field(final byte[] key, final byte[] targetBytes) {
        final byte[] tag = MlsResentMessage.mac(key, targetBytes);
        final byte[] f = new byte[64];
        System.arraycopy(key, 0, f, 0, 32);
        System.arraycopy(tag, 0, f, 32, 32);
        return f;
    }

    // ==================== THE ONE THAT MATTERS ====================

    /**
     * <b>A MAC MISMATCH IS NOT AN ERROR.</b> This is the test that stops the natural implementation
     * from coming back: verify-and-fail would fault rather than drop, and if that fault emits a
     * negative receipt it FTDs a healthy message.
     *
     * <p><b>This javadoc used to end "…on every resend in a group not addressed to us, which is N-1
     * of every N", and that count is WITHDRAWN</b> (this site
     * survived the sweep). It was never measured by anybody and the
     * falsifier is unrun. Nothing below depends on it: what is asserted is that
     * OUR {@code classify} answers {@code NOT_FOR_ME} for a field built against a different input,
     * which is a property of our own function and needs no claim about how Google Messages partitions a
     * group.
     */
    @Test
    public void aMismatchIsNotForMe_neverAnError() {
        final byte[] f = field(key32((byte) 0xAB), "alice-bytes".getBytes());
        // We are Bob: we compute with OUR bytes, so the tag will not match.
        assertEquals(MlsResentMessage.MacVerdict.NOT_FOR_ME,
                MlsResentMessage.classify(f, "bob-bytes".getBytes()));
    }

    /**
     * …and the input the field was BUILT AGAINST does match.
     *
     * <p>Not "so the selector actually selects": nothing in this verifier selects
     * — the MAC'd datum is recipient-invariant and the key is in the clear, so on
     * Google Messages every member's MAC passes). What this pins is that our own {@code classify} is a
     * function of the input it is given, which is what the disposition table is driven by.
     */
    @Test
    public void theIntendedTargetMatches() {
        final byte[] f = field(key32((byte) 0xAB), "alice-bytes".getBytes());
        assertEquals(MlsResentMessage.MacVerdict.FOR_ME,
                MlsResentMessage.classify(f, "alice-bytes".getBytes()));
    }

    /**
     * Given three DISTINCT candidate inputs, our {@code classify} matches exactly one of them.
     *
     * <p><b>Scoped deliberately, because this javadoc used to claim more</b>.
     * It said this was "the host-side twin of a logcat check … and the reason that check works
     * at all is this branch". That corroboration was withdrawn with the selector model:
     * on Google Messages the MAC passes for every member, so the logcat check —
     * <em>"one resend in a 3-member group produces exactly two not-for-me lines"</em> — is not a
     * twin of this at all. It is the UNRUN FALSIFIER for the withdrawn count.
     * What is asserted here is a property of OUR function over inputs WE chose.
     */
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

    /** The key is the FIRST half of the field, in the clear — not a derived secret. */
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

    // ==================== structural failures, which ARE errors ====================

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

    // ==================== the component framing ====================

    @Test
    public void absentIsABareZeroTag() {
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(new byte[] { 0x00 });
        assertNotNull(p);
        assertFalse(p.present());
        assertEquals(MlsResentMessage.TAG_ABSENT, p.tag);
    }

    /**
     * The decoder REPORTS which prefix width matched rather than assuming one.
     *
     * <p>The width is unestablished (u8 / u16 / mls_varint all live), so a decoder that picked one
     * would answer an open question by fiat and then be believed. Reporting it makes this an
     * instrument for settling the question off a captured Google Messages resend.
     */
    @Test
    public void theDecoderReportsWhichPrefixWidthMatched() {
        // A 200-byte payload separates u8 from varint: 200 > 0x3F so a 1-byte varint cannot hold it.
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

    /**
     * A SHORT payload is genuinely ambiguous, and saying so is the honest answer.
     *
     * <p>For a small length, {@code u8} and a 1-byte {@code mls_varint} are the same byte. A decoder
     * that reported a single width here would be manufacturing a determination the bytes do not
     * support — which is exactly the failure mode the {@code .4} fixture had, where one sample could
     * not distinguish two readings that coincide on it.
     */
    @Test
    public void aShortPayloadIsReportedAmbiguousRatherThanGuessed() {
        final byte[] short8 = MlsResentMessage.encode(
                MlsResentMessage.TAG_RESENT, new byte[] { 1, 2, 3 },
                MlsResentMessage.PrefixWidth.U8);
        final MlsResentMessage.Parsed p = MlsResentMessage.parse(short8);
        assertNotNull(p);
        assertTrue("u8 and a 1-byte varint coincide here — say so", p.ambiguous);
    }

    /** Encoding without stating the width is refused: a default would be a guess wearing an API. */
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

    // ==================== targeting: the candidate set identifies itself ====================

    /**
     * The matching candidate NAMES ITSELF, which is what makes this an instrument rather than a
     * guess: on a captured Google Messages resend addressed to us, whichever candidate matches IS the
     * recipient-specific input we have been unable to identify.
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

    /** No candidate matching is NOT_FOR_ME — benign, and with no candidate named. */
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

    // ==================== the AAD trailing slot ====================

    /** An ordinary message's AAD ends in the ABSENT component — one 0x00. */
    @Test
    public void anOrdinaryAadTrailingSlotIsAbsent() {
        final byte[] aad = MlsAppMessage.buildAuthenticatedData("MxSa=szpxmSJWBK9-TTcZaPA", 6);
        final byte[] tail = MlsAppMessage.aadTrailing(aad);
        assertNotNull(tail);
        assertArrayEquals(new byte[] { 0x00 }, tail);
        assertFalse(MlsResentMessage.parse(tail).present());
    }

    /**
     * Pinned against a REAL Google Messages AAD (00RU, era 6, 2026-08-08) — the trailing slot is where this
     * class says it is, on Google Messages' own bytes rather than only on ours.
     */
    @Test
    public void theTrailingSlotIsCorrectOnACapturedAad() {
        final String hex = "0001184d7853613d737a70786d534a57424b392d5454635a6150410000000600";
        final byte[] aad = new byte[hex.length() / 2];
        for (int i = 0; i < aad.length; i++) {
            aad[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        assertEquals("MxSa=szpxmSJWBK9-TTcZaPA", MlsAppMessage.aadMessageId(aad));
        assertArrayEquals("Google Messages' ordinary message carries the ABSENT component",
                new byte[] { 0x00 }, MlsAppMessage.aadTrailing(aad));
    }

    @Test
    public void malformedTailsDoNotParse() {
        assertNull(MlsResentMessage.parse(null));
        assertNull(MlsResentMessage.parse(new byte[0]));
        // tag present, but the length prefix overruns the buffer under every candidate width
        assertNull(MlsResentMessage.parse(new byte[] { 0x02, (byte) 0xFF, 0x01 }));
    }
}
