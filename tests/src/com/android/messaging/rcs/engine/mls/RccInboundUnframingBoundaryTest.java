/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Whoever builds an {@code RcsIncomingMessage} unframes an inbound MLS payload exactly once:
 * {@code RcsCallbackRouter} (from {@code decryptInbound}'s parsed body) or
 * {@code CarrierImsService.emitIncoming}. {@code ReceiveRcsMessageAction} takes type and body
 * verbatim. A second parse is not safe to make idempotent, because a body can look framed and not
 * be. Tested through {@link RccMlsBody#parse} and {@link RccContentDisposition#classify}.
 */
public final class RccInboundUnframingBoundaryTest {

    /** A plausible JPEG that is not valid UTF-8. */
    private static final byte[] JPEG = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F',
        0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, (byte) 0x80, (byte) 0x90, (byte) 0xA0,
        (byte) 0xFF, (byte) 0xD9,
    };

    /** One unframe yields the real type and exact bytes, routed to MEDIA. */
    @Test
    public void inlineMediaSurvivesOneUnframeIntact() {
        final byte[] framed = RccMlsBody.frame(JPEG, "image/jpeg", /*inline=*/ true);

        final RccMlsBody.Parsed once = RccMlsBody.parse(framed);

        assertEquals("image/jpeg", once.contentType);
        assertArrayEquals("the image bytes must survive byte-for-byte", JPEG, once.body);
        assertEquals("image/* routes to a media part, not a bubble",
                RccContentDisposition.MEDIA, RccContentDisposition.classify(once.contentType));
    }

    /** A second unframe turns {@code image/jpeg} into a text bubble of binary. */
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

    /** For text a second unframe is a no-op, which hides the media defect. */
    @Test
    public void secondUnframeIsANoOpForText() {
        final RccMlsBody.Parsed once = RccMlsBody.parse(RccMlsBody.frameText("hello"));
        assertEquals("text/plain", once.contentType);

        final RccMlsBody.Parsed twice = RccMlsBody.parse(once.body);

        assertEquals("text/plain", twice.contentType);
        assertArrayEquals(once.body, twice.body);
    }

    // Why parse cannot be made idempotent.

    /** Text that is a pasted header block looks framed, and a second parse eats it. */
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

    /** An image segment can hold a header-like line; a second parse then drops the image. */
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

    /** One unframe keeps the other dispositions: receipts, keys, location, FT. */
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
