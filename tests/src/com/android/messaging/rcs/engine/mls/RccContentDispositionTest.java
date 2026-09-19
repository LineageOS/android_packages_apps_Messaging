/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Inbound content-type dispatch. An unrecognised non-text type must not become a chat bubble. */
public final class RccContentDispositionTest {

    @Test
    public void unknownNonTextTypesAreDroppedNotRendered() {
        for (final String ct : new String[] {
                "application/octet-stream",
                "application/x-protobuf",
                "application/vnd.gsma.bot.v1.0+json",
                "message/mls-rcs-server",
                "message/mls-rcs-client",
                "message/mls",
                "application/something-invented-next-year",
        }) {
            assertEquals(ct, RccContentDisposition.DROP_UNKNOWN,
                    RccContentDisposition.classify(ct));
            assertTrue(ct, RccContentDisposition.isDrop(RccContentDisposition.classify(ct)));
        }
    }

    /** Text of any subtype still renders. */
    @Test
    public void anyTextSubtypeStillRenders() {
        for (final String ct : new String[] {
                "text/plain", "text/plain;charset=UTF-8", "text/html", "text/markdown",
        }) {
            assertEquals(ct, RccContentDisposition.TEXT, RccContentDisposition.classify(ct));
        }
    }

    /** An absent/empty type is an unframed legacy body, which is text. */
    @Test
    public void absentTypeIsTreatedAsText() {
        assertEquals(RccContentDisposition.TEXT, RccContentDisposition.classify(null));
        assertEquals(RccContentDisposition.TEXT, RccContentDisposition.classify(""));
        assertEquals(RccContentDisposition.TEXT, RccContentDisposition.classify("   "));
    }

    /** RCC.16 §7.8.1 FileInfo is a key, a binary protobuf; rendering it shows raw bytes. */
    @Test
    public void fileInfoIsAKeyNotAMessage() {
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccFileInfo.CONTENT_TYPE));
        // With parameters appended, as a real header carries.
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccFileInfo.CONTENT_TYPE + ";charset=UTF-8"));
    }

    /** Receipts and typing are signalling: handled elsewhere, never a bubble. */
    @Test
    public void signallingIsDropped() {
        for (final String ct : new String[] {
                "message/imdn+xml", "message/imdn+xml;charset=UTF-8",
                "application/im-iscomposing+xml", "application/vnd.google.rcs.success",
        }) {
            assertEquals(ct, RccContentDisposition.DROP_CONTROL,
                    RccContentDisposition.classify(ct));
        }
    }

    @Test
    public void richTypesRouteToTheirOwnHandlers() {
        assertEquals(RccContentDisposition.FT,
                RccContentDisposition.classify("application/vnd.gsma.rcs-ft-http+xml"));
        assertEquals(RccContentDisposition.FT, RccContentDisposition.classify("message/mls-ft"));
        assertEquals(RccContentDisposition.LOCATION,
                RccContentDisposition.classify("application/vnd.gsma.rcspushlocation+xml"));
        for (final String ct : new String[] {"image/png", "video/mp4", "audio/ogg"}) {
            assertEquals(ct, RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
        }
    }

    /** Case and surrounding whitespace do not change routing. */
    @Test
    public void classificationIsCaseAndWhitespaceInsensitive() {
        assertEquals(RccContentDisposition.DROP_CONTROL,
                RccContentDisposition.classify("  Message/IMDN+XML  "));
        assertEquals(RccContentDisposition.MEDIA, RccContentDisposition.classify("IMAGE/PNG"));
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify("  MESSAGE/MLS-RCS-FILE-INFO "));
    }

    @Test
    public void isDropCoversExactlyTheDropDispositions() {
        assertTrue(RccContentDisposition.isDrop(RccContentDisposition.DROP_CONTROL));
        assertTrue(RccContentDisposition.isDrop(RccContentDisposition.DROP_KEY));
        assertTrue(RccContentDisposition.isDrop(RccContentDisposition.DROP_UNKNOWN));
        assertFalse(RccContentDisposition.isDrop(RccContentDisposition.TEXT));
        assertFalse(RccContentDisposition.isDrop(RccContentDisposition.MEDIA));
        assertFalse(RccContentDisposition.isDrop(RccContentDisposition.FT));
        assertFalse(RccContentDisposition.isDrop(RccContentDisposition.LOCATION));
    }

    @Test
    public void everyDispositionHasAName() {
        for (final int d : new int[] {RccContentDisposition.TEXT, RccContentDisposition.MEDIA,
                RccContentDisposition.FT, RccContentDisposition.LOCATION,
                RccContentDisposition.DROP_CONTROL, RccContentDisposition.DROP_KEY,
                RccContentDisposition.DROP_UNKNOWN}) {
            assertFalse(RccContentDisposition.name(d).isEmpty());
        }
    }

    /** Type and subtype fold; parameter values keep their case (RFC 2045 §5.1). */
    @Test
    public void canonicalTypeFoldsTheTypeAndLeavesParameterValuesAlone() {
        assertEquals("image/jpeg", RccContentDisposition.canonicalType("IMAGE/JPEG"));
        assertEquals("image/jpeg", RccContentDisposition.canonicalType("  Image/Jpeg  "));
        assertEquals("image/jpeg", RccContentDisposition.canonicalType("image/jpeg"));
        assertEquals("image/jpeg; name=\"Photo.JPG\"",
                RccContentDisposition.canonicalType("IMAGE/JPEG; name=\"Photo.JPG\""));
        assertEquals("text/plain;charset=UTF-8",
                RccContentDisposition.canonicalType("TEXT/PLAIN;charset=UTF-8"));
    }

    /** A blank type comes back blank; substituting a default is {@code frame}'s decision alone. */
    @Test
    public void canonicalTypeInventsNothing() {
        assertEquals(null, RccContentDisposition.canonicalType(null));
        assertEquals("", RccContentDisposition.canonicalType(""));
        assertEquals("", RccContentDisposition.canonicalType("   "));
    }

    /** Routing and the value handed downstream must agree for upper-case media types. */
    @Test
    public void anUppercaseMediaTypeRoutesAsMediaAndNormalisesToItsLowercaseForm() {
        for (final String ct : new String[] {"IMAGE/JPEG", "Video/MP4", " AUDIO/OGG "}) {
            assertEquals(ct + " must route as MEDIA — that half was never broken",
                    RccContentDisposition.MEDIA, RccContentDisposition.classify(ct));
            assertEquals(ct + " must normalise to the form the renderers test against",
                    ct.trim().toLowerCase(java.util.Locale.US),
                    RccContentDisposition.canonicalType(ct));
        }
    }

    @Test
    public void textualTypesTakeACharset() {
        for (final String ct : new String[] {
                "text/plain", "text/html", "text/whatever-we-have-not-seen",
                "application/xml", "application/json",
                "application/vnd.gsma.rcs-ft-http+xml", "application/ld+json",
                "TEXT/PLAIN", "  Application/JSON  "}) {
            assertTrue(ct + " is textual and must take a charset",
                    RccContentDisposition.takesCharset(ct));
        }
    }

    /** Binary types, including binary {@code application/*}, take none. */
    @Test
    public void nonTextualTypesTakeNone() {
        for (final String ct : new String[] {
                "image/jpeg", "video/mp4", "audio/ogg",
                "application/pdf", "application/zip", "application/octet-stream",
                "application/imagex", "application/vnd.lineageos.mls-resend-not-for-me",
                "IMAGE/JPEG", "", "   "}) {
            assertFalse(ct + " is not textual and must take no charset",
                    RccContentDisposition.takesCharset(ct));
        }
        assertFalse("null is not a content type", RccContentDisposition.takesCharset(null));
    }

    /**
     * {@code message/*} keeps its charset although both shipping types carry binary protobuf: the
     * shape is already in use on the wire. {@code RccMlsBody.Parsed.headers} keeps a peer's header
     * block verbatim for comparison.
     */
    @Test
    public void theShippingMessageTypesKeepTheirCharset() {
        assertTrue("§7.8.1 key delivery, already on the wire",
                RccContentDisposition.takesCharset("message/mls-rcs-file-info"));
        assertTrue("§7.13.4 group metadata keys",
                RccContentDisposition.takesCharset("message/mls-rcs-group-metadata-keys"));
        assertTrue("the exception is message/, not those two literals",
                RccContentDisposition.takesCharset("message/mls-ft"));
    }

    /** Framing ({@code takesCharset}) and routing ({@code classify}) are separate questions. */
    @Test
    public void theFramingQuestionIsNotTheRoutingQuestion() {
        assertEquals(RccContentDisposition.DROP_UNKNOWN,
                RccContentDisposition.classify("application/pdf"));
        assertFalse(RccContentDisposition.takesCharset("application/pdf"));
        assertEquals(RccContentDisposition.MEDIA, RccContentDisposition.classify("image/jpeg"));
        assertFalse(RccContentDisposition.takesCharset("image/jpeg"));
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify("message/mls-rcs-file-info"));
        assertTrue(RccContentDisposition.takesCharset("message/mls-rcs-file-info"));
    }
}
