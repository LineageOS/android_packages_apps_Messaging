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

/**
 * Inbound content-type dispatch.
 *
 * <p>The load-bearing case is the DEFAULT: an unrecognised non-text type must not become a chat
 * bubble. Every "raw bytes in the thread" bug this project has had was that default being TEXT.
 */
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
            assertEquals(ct, RccContentDisposition.DROP_UNKNOWN, RccContentDisposition.classify(ct));
            assertTrue(ct, RccContentDisposition.isDrop(RccContentDisposition.classify(ct)));
        }
    }

    /** Text of ANY subtype still renders — refusing to show text would be its own bug. */
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

    /** §7.8.1 FileInfo is a KEY. It is binary protobuf; rendering it shows raw bytes. */
    @Test
    public void fileInfoIsAKeyNotAMessage() {
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify(RccFileInfo.CONTENT_TYPE));
        // …and with parameters appended, as a real header would carry.
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

    /** Case and surrounding whitespace must not change routing — headers carry both. */
    @Test
    public void classificationIsCaseAndWhitespaceInsensitive() {
        assertEquals(RccContentDisposition.DROP_CONTROL,
                RccContentDisposition.classify("  Message/IMDN+XML  "));
        assertEquals(RccContentDisposition.MEDIA, RccContentDisposition.classify("IMAGE/PNG"));
        assertEquals(RccContentDisposition.DROP_KEY,
                RccContentDisposition.classify("  MESSAGE/MLS-RCS-FILE-INFO "));
    }

    /** Only the drop dispositions are drops — a rendering one must never be swallowed. */
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

    // ---- canonicalType --------------------------------------------------------------------------

    /**
     * The type and subtype fold; everything after the first {@code ;} does not.
     *
     * <p>RFC 2045 §5.1 makes the type, the subtype and parameter NAMES case-insensitive and says
     * nothing of the kind about parameter VALUES — {@code name="Photo.JPG"} means what it says, and
     * a normaliser that lower-cased the whole header value would rename the peer's file.
     */
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

    /**
     * Nothing to normalise comes back unchanged — never a substituted default.
     *
     * <p>{@code frame} has a {@code text/plain} fallback for a blank type and that is ITS decision;
     * a normaliser that made the same substitution would hide an empty content type from every
     * caller that needs to see one.
     */
    @Test
    public void canonicalTypeInventsNothing() {
        assertEquals(null, RccContentDisposition.canonicalType(null));
        assertEquals("", RccContentDisposition.canonicalType(""));
        assertEquals("", RccContentDisposition.canonicalType("   "));
    }

    /**
     * <b>The interaction this is about</b>: routing was always right and the value
     * handed downstream was not.
     */
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

    // ---- takesCharset ---------------------------------------------------------------------------

    /** Textual types take a charset. The rule, stated positively. */
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

    /**
     * Nothing else does — including the binary {@code application/*} class that fell through both
     * nets before this fix.
     */
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
     * <b>{@code message/*} is the EXCEPTION, and it is marked as one.</b>
     *
     * <p>Both shipping {@code message/} types carry BINARY protobuf bodies, so the rule above would
     * drop their charset — and they have been on the wire in live groups since 2026-07-29. Dropping
     * it would change a working shape to satisfy a reading of RFC 2045, and this project's rule runs
     * the other way: capture trumps docs. The binary classes have never shipped, so they have no
     * shape to preserve and were fixed on their merits.
     *
     * <p>What would settle it is a Google Messages peer's own §7.8.1 header block, which
     * {@code RccMlsBody.Parsed.headers} retains verbatim for exactly this. Until one arrives, this
     * test is the record that the exception is evidence-backed rather than an oversight.
     */
    @Test
    public void theShippingMessageTypesKeepTheirCharset() {
        assertTrue("§7.8.1 key delivery, on the wire since 2026-07-29",
                RccContentDisposition.takesCharset("message/mls-rcs-file-info"));
        assertTrue("§7.13.4 group metadata keys",
                RccContentDisposition.takesCharset("message/mls-rcs-group-metadata-keys"));
        assertTrue("the exception is message/, not those two literals",
                RccContentDisposition.takesCharset("message/mls-ft"));
    }

    /**
     * {@code takesCharset} is NOT {@code classify}, and this is the case that proves it.
     *
     * <p>{@code application/pdf} classifies {@code DROP_UNKNOWN} — it is not media — and takes no
     * charset. A reader who assumed the framing question was the routing question would get it
     * wrong in exactly this cell, which is why the two are separate functions rather than one.
     */
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
