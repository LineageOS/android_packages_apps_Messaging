/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link RccMlsBody#parse} answers {@code text/plain} for a frameless payload only when it is our
 * legacy raw text or declares the text type in its RCC.16 header. Any other typed payload is
 * {@code UNKNOWN_SECRET_PAYLOAD}, which classifies as a drop: the text arm is the only one that is
 * rendered and receipted as delivered. The type-4 inputs follow the spec shape; no peer's payload
 * of that kind has been seen.
 */
public final class RccFramelessPayloadTypeTest {

    /** An 8-byte RCC.16 header with the given type in bytes 2..3, then an opaque body. */
    private static byte[] headerAnd(final int type, final byte[] body) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); out.write(0x01);                       // version
        out.write((type >> 8) & 0xff); out.write(type & 0xff);  // payload type
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x00);   // generation
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** A protobuf-ish body with no blank-line pair anywhere in it. */
    private static final byte[] PROTO = {
        0x0a, 0x20, (byte) 0x9f, 0x11, 0x00, 0x42, (byte) 0xc3, 0x07,
        0x12, 0x04, 0x01, 0x02, 0x03, 0x04, 0x18, (byte) 0xd2, 0x09,
    };

    @Test
    public void framelessUnknownTypeIsNotText() {
        final byte[] payload = headerAnd(4, PROTO);

        final RccMlsBody.Parsed p = RccMlsBody.parse(payload);

        assertEquals("a typed binary payload must not be answered as text",
                RccContentDisposition.UNKNOWN_SECRET_PAYLOAD, p.contentType);
        assertArrayEquals("the bytes are still returned verbatim — we drop the ROUTING, not the "
                + "payload, so a future handler has something to read",
                payload, p.body);
    }

    /** {@code RcsCallbackRouter}'s drop arm tests {@code isDrop} and returns before the receipt. */
    @Test
    public void theMarkerRoutesToADropAndThereforeIsNotReceipted() {
        final int disp = RccContentDisposition.classify(
                RccContentDisposition.UNKNOWN_SECRET_PAYLOAD);

        assertEquals("DROP_UNKNOWN is the honest classification: not text, and not identified",
                RccContentDisposition.DROP_UNKNOWN, disp);
        assertTrue("the router's drop arm is keyed on isDrop(), and it returns before the insert "
                + "that hardcodes wantsDeliveredImdn=true",
                RccContentDisposition.isDrop(disp));
        assertFalse("it must never be TEXT — that arm is the one that bubbles AND receipts",
                RccContentDisposition.TEXT == disp);
    }

    @Test
    public void everyTypeOtherThanTextIsRefused() {
        for (final int type : new int[] {0, 2, 3, 4, 5, 0x00ff, 0xfffe}) {
            assertEquals("type " + type + " is not the text type and must not render",
                    RccContentDisposition.UNKNOWN_SECRET_PAYLOAD,
                    RccMlsBody.parse(headerAnd(type, PROTO)).contentType);
        }
    }

    // The payloads that must keep rendering.

    /** Our own legacy raw-text body, with no header and no frame. */
    @Test
    public void legacyRawTextStillRenders() {
        final byte[] raw = "hello from an older build".getBytes(StandardCharsets.UTF_8);

        final RccMlsBody.Parsed p = RccMlsBody.parse(raw);

        assertEquals("text/plain", p.contentType);
        assertArrayEquals(raw, p.body);
        assertEquals(RccContentDisposition.TEXT,
                RccContentDisposition.classify(p.contentType));
    }

    /** The header starts with NUL and SOH, which no text body begins with. */
    @Test
    public void textBeginningWithControlLookingCharactersStillRenders() {
        for (final String s : new String[] {"hello", " x", "", " ", "0001"}) {
            final byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            assertEquals("[" + s + "] must still render", "text/plain",
                    RccMlsBody.parse(raw).contentType);
        }
    }

    @Test
    public void framelessTextTypeIsStillText() {
        final byte[] payload = headerAnd(1, "hi".getBytes(StandardCharsets.UTF_8));

        assertEquals("text/plain", RccMlsBody.parse(payload).contentType);
    }

    /** Too short to carry a header: text. */
    @Test
    public void aPayloadTooShortForAHeaderIsUnchanged() {
        assertEquals("text/plain",
                RccMlsBody.parse(new byte[] {0x00, 0x01, 0x00, 0x04}).contentType);
        assertEquals("text/plain", RccMlsBody.parse(new byte[0]).contentType);
        assertEquals("text/plain", RccMlsBody.parse(null).contentType);
    }

    /** Our own framed messages never reach the frameless path. */
    @Test
    public void ourOwnFramedMessagesAreUnaffected() {
        assertEquals("text/plain", RccMlsBody.parse(RccMlsBody.frameText("hi")).contentType);
        assertEquals("image/jpeg", RccMlsBody.parse(RccMlsBody.frame(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9},
                "image/jpeg", /*inline=*/ true)).contentType);
    }

    /** A blank-line pair in binary reaches parse()'s no-Content-Type arm, which refuses too. */
    @Test
    public void aCoincidentalBlankLinePairDoesNotMakeItText() {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x0a); body.write(0x20);
        for (final byte b : "\r\n\r\n".getBytes(StandardCharsets.UTF_8)) {
            body.write(b);
        }
        body.write(0x42); body.write((byte) 0xc3);

        assertEquals(RccContentDisposition.UNKNOWN_SECRET_PAYLOAD,
                RccMlsBody.parse(headerAnd(4, body.toByteArray())).contentType);
    }
}
