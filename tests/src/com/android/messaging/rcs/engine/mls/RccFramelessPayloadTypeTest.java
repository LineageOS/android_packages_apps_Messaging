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
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.RccMlsBody;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A FRAMELESS INBOUND PAYLOAD IS NOT AUTOMATICALLY TEXT (option C).
 *
 * <p>{@link RccMlsBody#parse} used to answer {@code text/plain} for ANY payload with no MIME frame.
 * That default exists for our own legacy raw-text bodies and is correct for them. It was also
 * catching STRUCTURED BINARY payloads — a peer's RCC.16 SecretPayload whose 8-byte header declares a
 * type we do not handle — and turning them into chat bubbles containing their own framing bytes.
 *
 * <p><b>And the bubble is the smaller half.</b> {@code RcsCallbackRouter} hardcodes
 * {@code wantsDeliveredImdn=true} on the insert path and its drop arms {@code return} before
 * reaching it, so <em>the TEXT arm is the only arrival shape that receipts</em>. Rendering a peer's
 * key material as garbage is recoverable; telling the peer we DELIVERED it is not — it is a lie
 * about our own state, and the sender acts on it.
 *
 * <p>These tests pin both directions, because a guard that cannot fail is not a guard: the
 * populations that must keep rendering still render, and the population that must not is refused
 * AND is refused at the disposition the router actually drops on.
 *
 * <p><b>WHAT THESE TESTS DO NOT ESTABLISH.</b> That Google Messages' group-metadata-keys plaintext is a
 * type-4 payload, or that it carries this header at all — the input is the spec shape,
 * not Google Messages' bytes, and no Google Messages peer has ever sent us one. The type-4 inputs below are
 * CONSTRUCTED. This closes the class of defect; the Google Messages case closes on a capture.
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

    // ------------------------------------------------------------------
    // THE DEFECT.
    // ------------------------------------------------------------------

    /** A frameless payload declaring a type we do not handle must not become a bubble. */
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

    /**
     * AND IT LANDS ON A DISPOSITION THE ROUTER ACTUALLY DROPS ON.
     *
     * <p>Naming a marker type is worth nothing if it classifies as something renderable. This is the
     * assertion that connects the parse change to the user-visible outcome: {@code isDrop} is the
     * predicate {@code RcsCallbackRouter} tests, and its arm returns before the insert that carries
     * {@code wantsDeliveredImdn=true}.
     */
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

    /** Every unhandled type, not just 4. The guard is on "not ours", not on one value. */
    @Test
    public void everyTypeOtherThanTextIsRefused() {
        for (final int type : new int[] {0, 2, 3, 4, 5, 0x00ff, 0xfffe}) {
            assertEquals("type " + type + " is not the text type and must not render",
                    RccContentDisposition.UNKNOWN_SECRET_PAYLOAD,
                    RccMlsBody.parse(headerAnd(type, PROTO)).contentType);
        }
    }

    // ------------------------------------------------------------------
    // THE POPULATIONS THAT MUST NOT REGRESS. Without these the change above is
    // indistinguishable from "stop delivering frameless messages".
    // ------------------------------------------------------------------

    /** Our own legacy raw-text body — no header, no frame — still renders. */
    @Test
    public void legacyRawTextStillRenders() {
        final byte[] raw = "hello from an older build".getBytes(StandardCharsets.UTF_8);

        final RccMlsBody.Parsed p = RccMlsBody.parse(raw);

        assertEquals("text/plain", p.contentType);
        assertArrayEquals(raw, p.body);
        assertEquals(RccContentDisposition.TEXT,
                RccContentDisposition.classify(p.contentType));
    }

    /**
     * UTF-8 TEXT CANNOT BE MISTAKEN FOR THE HEADER, which is what makes the version check the safe
     * discriminator rather than a guess: bytes 0x00 0x01 are NUL and SOH, and no text body begins
     * with them.
     */
    @Test
    public void textBeginningWithControlLookingCharactersStillRenders() {
        for (final String s : new String[] {"hello", " x", "", " ", "0001"}) {
            final byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            assertEquals("[" + s + "] must still render", "text/plain",
                    RccMlsBody.parse(raw).contentType);
        }
    }

    /** A frameless payload that declares the TEXT type is text, exactly as before. */
    @Test
    public void framelessTextTypeIsStillText() {
        final byte[] payload = headerAnd(1, "hi".getBytes(StandardCharsets.UTF_8));

        assertEquals("text/plain", RccMlsBody.parse(payload).contentType);
    }

    /** Too short to carry a header: the historical road, unchanged. */
    @Test
    public void aPayloadTooShortForAHeaderIsUnchanged() {
        assertEquals("text/plain",
                RccMlsBody.parse(new byte[] {0x00, 0x01, 0x00, 0x04}).contentType);
        assertEquals("text/plain", RccMlsBody.parse(new byte[0]).contentType);
        assertEquals("text/plain", RccMlsBody.parse(null).contentType);
    }

    /** Our own FRAMED messages never reach the frameless road at all. */
    @Test
    public void ourOwnFramedMessagesAreUnaffected() {
        assertEquals("text/plain", RccMlsBody.parse(RccMlsBody.frameText("hi")).contentType);
        assertEquals("image/jpeg", RccMlsBody.parse(RccMlsBody.frame(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9},
                "image/jpeg", /*inline=*/ true)).contentType);
    }

    /**
     * A BLANK-LINE PAIR IS NOT A FRAME. Binary can contain one by coincidence, which sends the
     * payload down parse()'s second fallback — the one that finds a separator and no Content-Type
     * above it. That arm gets the same treatment; a softer default there would be a hole in the
     * shape of the fix.
     */
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
