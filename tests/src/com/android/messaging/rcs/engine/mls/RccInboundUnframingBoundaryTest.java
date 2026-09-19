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

import com.android.messaging.rcs.e2ee.RccMlsBody;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WHO UNFRAMES AN INBOUND MLS PAYLOAD, AND HOW OFTEN.
 *
 * <p>The rule: <b>whoever builds an {@code RcsIncomingMessage} unframes, exactly once</b> — the
 * Tachyon leg in {@code RcsCallbackRouter} (which gets an already-parsed body from
 * {@code MlsProviderTransport.decryptInbound}, because it routes receipts, keys and reactions on the
 * inner type before it knows there is a message at all), and the carrier leg in
 * {@code CarrierImsService.emitIncoming}. {@code ReceiveRcsMessageAction} takes the content-type and
 * body verbatim and does not parse.
 *
 * <p>It used to parse a second time, on the first parse's output. These tests pin the two halves of
 * why that was both invisible and wrong, and the reason the tempting workaround — "make parse safe
 * to run twice" — is not available.
 *
 * <p>The seam itself is not host-testable: {@code RcsIncomingMessage} is a Parcelable and the Action
 * writes into a Bundle. What IS testable is the pair of pure functions the whole decision rests on —
 * {@link RccMlsBody#parse} and {@link RccContentDisposition#classify} — and between them they decide
 * the content type that reaches the UI on both legs.
 */
public final class RccInboundUnframingBoundaryTest {

    /** Bytes that are a plausible JPEG and are NOT valid UTF-8 — a body a String hop would eat. */
    private static final byte[] JPEG = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F',
        0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, (byte) 0x80, (byte) 0x90, (byte) 0xA0,
        (byte) 0xFF, (byte) 0xD9,
    };

    // ------------------------------------------------------------------
    // THE CASE THIS EXISTS FOR: inline media over MLS.
    // ------------------------------------------------------------------

    /**
     * One unframe — what a producer does — yields the real type and the exact bytes, and the shared
     * classifier routes it to MEDIA. This is what {@code ReceiveRcsMessageAction} now receives.
     */
    @Test
    public void inlineMediaSurvivesOneUnframeIntact() {
        final byte[] framed = RccMlsBody.frame(JPEG, "image/jpeg", /*inline=*/ true);

        final RccMlsBody.Parsed once = RccMlsBody.parse(framed);

        assertEquals("image/jpeg", once.contentType);
        assertArrayEquals("the image bytes must survive byte-for-byte", JPEG, once.body);
        assertEquals("image/* routes to a media part, not a bubble",
                RccContentDisposition.MEDIA, RccContentDisposition.classify(once.contentType));
    }

    /**
     * THE DEFECT, pinned. Unframing the already-unframed body — what the Action's second parse did —
     * discards {@code image/jpeg} and hands back {@code text/plain} over the raw image bytes, which
     * classifies as TEXT. The picture renders as a bubble of binary.
     */
    @Test
    public void secondUnframeDemotesMediaToATextBubble() {
        final RccMlsBody.Parsed once = RccMlsBody.parse(
                RccMlsBody.frame(JPEG, "image/jpeg", /*inline=*/ true));

        final RccMlsBody.Parsed twice = RccMlsBody.parse(once.body);

        assertEquals("text/plain", twice.contentType);
        assertFalse("the producer's type is gone", "image/jpeg".equals(twice.contentType));
        assertEquals("which is how raw bytes reach the conversation",
                RccContentDisposition.TEXT, RccContentDisposition.classify(twice.contentType));
    }

    /**
     * WHY IT WAS INVISIBLE. An unframed TEXT body has no frame either, and parse returns a frameless
     * payload verbatim as {@code text/plain} — so the second parse was a no-op for every message we
     * have ever sent, and the bug shipped unnoticed.
     */
    @Test
    public void secondUnframeIsANoOpForText() {
        final RccMlsBody.Parsed once = RccMlsBody.parse(RccMlsBody.frameText("hello"));
        assertEquals("text/plain", once.contentType);

        final RccMlsBody.Parsed twice = RccMlsBody.parse(once.body);

        assertEquals("text/plain", twice.contentType);
        assertArrayEquals(once.body, twice.body);
    }

    // ------------------------------------------------------------------
    // WHY "MAKE PARSE IDEMPOTENT" IS NOT AN OPTION.
    // ------------------------------------------------------------------

    /**
     * A legitimate {@code text/plain} message whose TEXT is a pasted header block. One parse returns
     * the user's message verbatim. A second parse reads the user's own words as framing: it changes
     * the type and truncates the body to whatever followed the blank line.
     *
     * <p>So a guard of the form "only parse if it still looks framed" cannot be written — this
     * payload looks framed and is not.
     */
    @Test
    public void aTextBodyThatLooksFramedIsEatenByASecondParse() {
        final String pasted = "Content-Type: text/html\r\n\r\n<b>look at this</b>";
        final byte[] framed = RccMlsBody.frame(
                pasted.getBytes(StandardCharsets.UTF_8), "text/plain", /*inline=*/ true);

        final RccMlsBody.Parsed once = RccMlsBody.parse(framed);
        assertEquals("text/plain", once.contentType);
        assertEquals(pasted, new String(once.body, StandardCharsets.UTF_8));

        final RccMlsBody.Parsed twice = RccMlsBody.parse(once.body);
        assertEquals("the user's own text was read as a header", "text/html", twice.contentType);
        assertEquals("and everything above the blank line was thrown away",
                "<b>look at this</b>", new String(twice.body, StandardCharsets.UTF_8));
    }

    /**
     * The same trap on the media path, and worse. Nothing stops a real image from carrying a CRLF
     * followed by a {@code Content-Type:} line inside a metadata segment (an XMP packet is XML with
     * CRLFs in it). A second parse of such an image does not merely mislabel it — the type it
     * invents is unrouted, so the message is DROPPED and the user is shown nothing at all.
     *
     * <p>This is not a claim that ordinary JPEGs look like this. It is the claim that the framing
     * check has no way to tell, which is the whole reason the parse belongs at one layer only.
     */
    @Test
    public void anImageCarryingAHeaderLikeSegmentIsDroppedEntirelyBySecondParse() throws Exception {
        final ByteArrayOutputStream img = new ByteArrayOutputStream();
        img.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xFE});  // SOI + COM
        img.write("\r\nContent-Type: application/xml\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        img.write(JPEG);
        final byte[] bytes = img.toByteArray();

        final RccMlsBody.Parsed once = RccMlsBody.parse(
                RccMlsBody.frame(bytes, "image/jpeg", /*inline=*/ true));
        assertEquals("image/jpeg", once.contentType);
        assertArrayEquals(bytes, once.body);
        assertEquals(RccContentDisposition.MEDIA, RccContentDisposition.classify(once.contentType));

        final RccMlsBody.Parsed twice = RccMlsBody.parse(once.body);
        assertEquals("application/xml", twice.contentType);
        assertEquals("an unrouted type — the image does not even reach the thread",
                RccContentDisposition.DROP_UNKNOWN,
                RccContentDisposition.classify(twice.contentType));
    }

    // ------------------------------------------------------------------
    // The single parse still routes everything else the way it did.
    // ------------------------------------------------------------------

    /** One unframe keeps the non-message dispositions intact: receipts, keys, location, FT. */
    @Test
    public void oneUnframePreservesEveryOtherDisposition() {
        assertEquals(RccContentDisposition.DROP_CONTROL, dispositionAfterOneUnframe(
                "message/imdn+xml", "<imdn/>"));
        assertEquals(RccContentDisposition.DROP_KEY, dispositionAfterOneUnframe(
                RccFileInfo.CONTENT_TYPE, "\0key"));
        assertEquals(RccContentDisposition.DROP_KEY, dispositionAfterOneUnframe(
                RccGroupMetadataKeys.CONTENT_TYPE, "\0keys"));
        assertEquals(RccContentDisposition.LOCATION, dispositionAfterOneUnframe(
                "application/vnd.gsma.rcspushlocation+xml", "<presence/>"));
        assertEquals(RccContentDisposition.FT, dispositionAfterOneUnframe(
                "application/vnd.gsma.rcs-ft-http+xml", "<file/>"));
        assertEquals(RccContentDisposition.MEDIA, dispositionAfterOneUnframe(
                "video/mp4", "moov"));
        assertEquals(RccContentDisposition.TEXT, dispositionAfterOneUnframe(
                "text/plain", "hi"));
    }

    private static int dispositionAfterOneUnframe(final String contentType, final String body) {
        final RccMlsBody.Parsed p = RccMlsBody.parse(RccMlsBody.frame(
                body.getBytes(StandardCharsets.UTF_8), contentType, /*inline=*/ true));
        assertEquals(contentType, p.contentType);
        return RccContentDisposition.classify(p.contentType);
    }
}
