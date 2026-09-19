/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;


import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Host tests for the carrier MLS outbound framing hop; {@code CarrierInboundBinaryContentTest}
 * covers the receive half. The outcome is decided by two pure functions composed in order:
 * {@link RccMlsBody#frame} at the caller, then {@link MlsRecoveryPolicy#stampBodyGeneration} at
 * encrypt time, because the frame header's counter is a placeholder only the sender ratchet can
 * fill. See docs/rcs/carrier-transport.md.
 */
public class CarrierOutboundFramingTest {

    /**
     * Bytes that a String round trip destroys: an embedded NUL and sequences that are not UTF-8.
     */
    private static byte[] binaryPayload() {
        return new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,   // JPEG SOI + APP0
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,                 // NULs inside the header
            (byte) 0xC3, (byte) 0x28,                             // truncated 2-byte sequence
            (byte) 0xFF, (byte) 0xFE,                             // impossible in UTF-8
        };
    }

    /** The frame header's per-message counter: bytes 4..7, big-endian. */
    private static long counterOf(final byte[] framed) {
        return ((long) (framed[4] & 0xFF) << 24) | ((framed[5] & 0xFF) << 16)
                | ((framed[6] & 0xFF) << 8) | (framed[7] & 0xFF);
    }

    @Test
    public void framedTextRoundTripsThroughTheEntityTheCarrierLegNowSends() {
        final String text = "padlock hi";
        final RccMlsBody.Parsed p = RccMlsBody.parse(RccMlsBody.frameText(text));
        assertEquals("text/plain", p.contentType);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), p.body);
    }

    /** The body is carried as bytes, so non-text payloads survive byte-exact. */
    @Test
    public void aNonTextContentTypeSurvivesTheFrame_whichTheStringSeamCouldNotCarry() {
        final byte[] jpeg = binaryPayload();
        final RccMlsBody.Parsed p = RccMlsBody.parse(
                RccMlsBody.frame(jpeg, "image/jpeg", /*inline=*/ true));
        assertEquals("image/jpeg", p.contentType);
        assertArrayEquals(jpeg, p.body);
    }

    @Test
    public void binaryIsNotCorruptedTheWayAUtf8StringHopWouldCorruptIt() {
        final byte[] jpeg = binaryPayload();
        // A String round trip, for contrast: bytes -> String -> bytes.
        final byte[] viaString = new String(jpeg, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
        assertNotEquals("the premise: a UTF-8 String hop is lossy for these bytes",
                jpeg.length, viaString.length);

        final RccMlsBody.Parsed p = RccMlsBody.parse(
                RccMlsBody.frame(jpeg, "image/jpeg", /*inline=*/ true));
        assertArrayEquals("the framed path must be byte-exact", jpeg, p.body);
    }

    /**
     * A framed binary type carries no {@code ;charset=UTF-8}. This asserts the bytes on the wire,
     * not a round trip: {@link RccMlsBody#parse} cuts the type at {@code ';'}, so only a peer sees
     * the header.
     */
    @Test
    public void aBinaryTypeDoesNotCarryACharsetParameterOnTheWire() {
        final String wire = new String(
                RccMlsBody.frame(binaryPayload(), "image/jpeg", /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue("the emitted header must name the media type",
                wire.contains("Content-Type: image/jpeg\r\n"));
        assertFalse("a charset on binary is meaningless and wrong to a strict peer",
                wire.contains("image/jpeg;charset"));
    }

    /**
     * Non-text types that already ship with the charset ({@code message/mls-rcs-file-info},
     * {@code message/mls-rcs-group-metadata-keys}) keep it, so a working wire shape does not
     * change.
     */
    @Test
    public void theShippingNonTextTypesKeepTheirCharset_becauseTheyAreAlreadyOnTheWire() {
        for (final String ct : new String[] {
                "message/mls-rcs-file-info", "message/mls-rcs-group-metadata-keys"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue(ct + " must keep the charset it ships with today",
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /** text/* keeps it, which is correct and what peers send. */
    @Test
    public void textKeepsItsCharset() {
        final String wire = new String(RccMlsBody.frameText("hi"), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: text/plain;charset=UTF-8\r\n"));
    }

    /**
     * Framing and routing are separate questions: {@link RccContentDisposition#takesCharset} and
     * {@code classify} can disagree (e.g. {@code application/pdf}). The invariant is containment:
     * anything routed as inline media is never framed with a charset.
     */
    @Test
    public void everyTypeRoutedAsInlineMediaIsFramedWithoutACharset() {
        for (final String ct : new String[] {
                "image/jpeg", "image/png", "image/gif",
                "video/mp4", "video/3gpp",
                "audio/ogg", "audio/mpeg"}) {
            assertEquals(ct + " must classify as MEDIA for this test to mean anything",
                    RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertFalse("a type routed as inline media must not carry a charset: " + ct,
                    wire.contains(ct + ";charset"));
        }
    }

    /** Near-misses on both sides, so a predicate that answers true for everything cannot pass. */
    @Test
    public void aTypeThatMerelyLooksLikeMediaStillGetsItsCharset() {
        for (final String ct : new String[] {
                "text/image",                // starts with text/, not image/ — textual
                "text/imagex",               // near-miss on the subtype, still text/
                "message/mls-ft",            // a transfer descriptor; message/ ships with a charset
                "application/imagex+xml"}) { // +xml is textual under application/
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertTrue("textual, so the charset stays: " + ct,
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /**
     * Binary {@code application/*} carries no charset either, but {@code application/} is not a
     * binary prefix: {@code +xml}, {@code +json}, {@code application/json} and
     * {@code application/xml} are text and keep it.
     */
    @Test
    public void binaryApplicationTypesCarryNoCharset() {
        for (final String ct : new String[] {
                "application/pdf", "application/zip", "application/octet-stream",
                "application/imagex", "application/vnd.lineageos.mls-resend-not-for-me"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue("the type still goes out verbatim: " + ct,
                    wire.contains("Content-Type: " + ct + "\r\n"));
            assertEquals("a charset on binary is meaningless: " + ct, 0,
                    countOccurrences(wire, "charset"));
        }
        for (final String ct : new String[] {
                "application/json", "application/xml",
                "application/vnd.gsma.rcs-ft-http+xml", "application/ld+json"}) {
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue("text carried under application/ keeps its charset: " + ct,
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    /**
     * Containment holds for input that is not already normalised: {@code classify} trims and
     * lower-cases, so {@code frame} must too when deciding on the charset.
     */
    @Test
    public void containmentHoldsForInputThatIsNotAlreadyNormalised() {
        for (final String ct : new String[] {
                "IMAGE/JPEG", "Image/Jpeg", " image/jpeg ", "\tVIDEO/MP4", "AUDIO/OGG "}) {
            assertEquals(ct + " must classify as MEDIA for this case to be about framing",
                    RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
            final String wire = new String(
                    RccMlsBody.frame(new byte[] {1, 2, 3}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertFalse("routed as inline media, so no charset — whatever the case/spacing: " + ct,
                    wire.contains(";charset"));
        }
    }

    /**
     * The emitted value keeps its case and loses its whitespace: trimming changes no shipping
     * shape, while lower-casing would change the wire for a caller that passed mixed case.
     */
    @Test
    public void theEmittedValueKeepsItsCaseButNotItsWhitespace() {
        final String wire = new String(
                RccMlsBody.frame(new byte[] {1}, "  Message/MLS-RCS-File-Info  ",
                        /*inline=*/ false),
                StandardCharsets.ISO_8859_1);
        assertTrue("case preserved, whitespace gone",
                wire.contains("Content-Type: Message/MLS-RCS-File-Info;charset=UTF-8\r\n"));
    }

    /** A whitespace-only type is not a type; it must fall back rather than emit a blank header. */
    @Test
    public void aWhitespaceOnlyContentTypeFallsBackToTextPlain() {
        final String wire = new String(RccMlsBody.frame(new byte[] {1}, "   ", /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: text/plain;charset=UTF-8\r\n"));
    }

    /**
     * A charset parameter already present is not duplicated. The input shapes follow RFC 2045 §5.1:
     * case-insensitive type and parameter names, whitespace around {@code ;} and {@code =}, and
     * quoted parameter values.
     */
    @Test
    public void aTypeThatAlreadyCarriesParametersDoesNotGetASecondCharset() {
        for (final String ct : new String[] {
                "text/plain;charset=utf-8",
                "text/plain; charset=UTF-8",
                "application/vnd.gsma.rcs-ft-http+xml;charset=utf-8"}) {
            final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ true),
                    StandardCharsets.ISO_8859_1);
            assertTrue("the caller's explicit parameters are kept verbatim: " + ct,
                    wire.contains("Content-Type: " + ct + "\r\n"));
            assertEquals("exactly one charset parameter", 1, countOccurrences(wire, "charset"));
        }
    }

    /** A quoted parameter value may contain a ';' — it must not be mistaken for a separator. */
    @Test
    public void aQuotedParameterValueContainingASemicolonIsNotSplit() {
        final String ct = "image/jpeg; name=\"a;b\"";
        final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ true),
                StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("Content-Type: " + ct + "\r\n"));
        assertEquals("binary, and explicit — no charset either way", 0,
                countOccurrences(wire, "charset"));
    }

    /** The three bare production types still get their charset — no shipping shape changed. */
    @Test
    public void theBareProductionTypesAreUnaffected() {
        for (final String ct : new String[] {
                "text/plain", "message/mls-rcs-file-info", "message/mls-rcs-group-metadata-keys"}) {
            final String wire = new String(RccMlsBody.frame(new byte[] {1}, ct, /*inline=*/ false),
                    StandardCharsets.ISO_8859_1);
            assertTrue(ct + " keeps the charset it ships with",
                    wire.contains("Content-Type: " + ct + ";charset=UTF-8\r\n"));
        }
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void theStampLandsInTheFrameHeaderAndLeavesTheEntityReadable() {
        final byte[] framed = RccMlsBody.frameText("three");
        final byte[] stamped = MlsRecoveryPolicy.stampBodyGeneration(framed, 3);

        assertEquals("the counter must equal the generation the message is encrypted at",
                3L, counterOf(stamped));
        final RccMlsBody.Parsed p = RccMlsBody.parse(stamped);
        assertEquals("stamping must not disturb the entity", "text/plain", p.contentType);
        assertArrayEquals("three".getBytes(StandardCharsets.UTF_8), p.body);
    }

    /**
     * A leg that frames but does not stamp emits generation 0 for every message in an epoch, which
     * a peer that cross-checks {@code sender_data.generation} rejects after the first message.
     */
    @Test
    public void framingWithoutStampingSendsGenerationZeroForever() {
        for (final String message : new String[] {"first", "second", "third"}) {
            assertEquals(
                    "an unstamped frame always carries 0 — this is the regression, not the fix",
                    0L, counterOf(RccMlsBody.frameText(message)));
        }
        // With the stamp the same three messages walk, as a peer's sender does.
        assertEquals(0L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("first"), 0)));
        assertEquals(1L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("second"), 1)));
        assertEquals(2L, counterOf(MlsRecoveryPolicy.stampBodyGeneration(
                RccMlsBody.frameText("third"), 2)));
    }

    /**
     * On an unframed body the stamp is the identity, so stamping and framing can be added in either
     * order.
     */
    @Test
    public void stampingAnUnframedBodyIsTheIdentity() {
        final byte[] raw = "not an RCC.16 entity".getBytes(StandardCharsets.UTF_8);
        assertSame("no copy, no change — the magic check fails and it returns its input",
                raw, MlsRecoveryPolicy.stampBodyGeneration(raw, 7));
    }

    /**
     * {@code nextAppGen} answers -1 when it cannot say; the body is then left alone, since a wrong
     * counter is worse than the placeholder.
     */
    @Test
    public void anUnavailableGenerationLeavesTheFrameUntouchedRatherThanWritingGarbage() {
        final byte[] framed = RccMlsBody.frameText("no generation available");
        assertSame(framed, MlsRecoveryPolicy.stampBodyGeneration(framed, -1));
        assertEquals(0L, counterOf(framed));
    }
}
