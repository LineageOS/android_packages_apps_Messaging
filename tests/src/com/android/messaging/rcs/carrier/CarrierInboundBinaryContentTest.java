/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.RccMlsBody;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The carrier inbound content hop is binary-safe: the decrypted RCC.16 payload is unframed with
 * {@link RccMlsBody} where it is produced, and bytes plus content type cross
 * {@link CarrierTransportBridge#wrap} through {@link Transport.Listener#onIncomingContent}. A UTF-8
 * String hop anywhere on the chain replaces invalid sequences with U+FFFD.
 */
public class CarrierInboundBinaryContentTest {

    /** Embedded NULs and invalid UTF-8, as in any JPEG. */
    private static byte[] binaryPayload() {
        return new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,   // JPEG SOI + APP0
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,                 // NUL bytes inside the header
            (byte) 0xC3, (byte) 0x28,                             // invalid UTF-8 sequence
            (byte) 0xFF, (byte) 0xFE,                             // impossible in UTF-8
            0x00, 0x00, (byte) 0x80, (byte) 0x9F,
        };
    }

    // ---------------------------------------------------------------- the mechanism

    @Test
    public void utf8RoundTripDestroysBinary() {
        final byte[] payload = binaryPayload();
        final byte[] roundTripped =
                new String(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        assertFalse("a UTF-8 String round trip must be assumed lossy for binary",
                java.util.Arrays.equals(payload, roundTripped));
    }

    /**
     * The frame's QUIC var-int length can carry a byte above 0x7F even for ASCII text, so unframing
     * must precede any String hop for text as well as media.
     */
    @Test
    public void someOrdinaryTextLengthsPutANonAsciiByteInTheFrameItself() {
        String culprit = null;
        byte[] framed = null;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000 && culprit == null; i++) {
            sb.append('x');   // ASCII, so any high byte found is the frame's
            final byte[] f = RccMlsBody.frameText(sb.toString());
            if (prefixHasHighByte(f, sb.length())) {
                culprit = sb.toString();
                framed = f;
            }
        }
        assertTrue("expected some body length under 2000 whose var-int carries a high byte",
                culprit != null);

        // Unframed first, the text is intact.
        final RccMlsBody.Parsed parsed = RccMlsBody.parse(framed);
        assertEquals("text/plain", parsed.contentType);
        assertEquals(culprit, new String(parsed.body, StandardCharsets.UTF_8));

        // Stringified while still framed, the frame's own bytes change.
        final byte[] stringified =
                new String(framed, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        assertFalse("stringifying a still-framed all-ASCII payload of " + culprit.length()
                        + " chars still mangles the frame itself",
                java.util.Arrays.equals(framed, stringified));
    }

    /** True if any byte before the trailing {@code bodyLen} body bytes is above 0x7F. */
    private static boolean prefixHasHighByte(final byte[] framed, final int bodyLen) {
        for (int i = 0; i < framed.length - bodyLen; i++) {
            if ((framed[i] & 0xFF) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the seam

    @Test
    public void onIncomingContent_carriesBytesAndTypeThroughTheBridge() {
        final byte[] payload = binaryPayload();
        final RecordingListener sink = new RecordingListener();
        final CarrierTransportBridge bridge = new CarrierTransportBridge();

        bridge.wrap(sink).onIncomingContent("tel:+15551234567", payload, "image/jpeg",
                "msg-1", "gsma.rcs-e2ee.mls");

        assertEquals(1, sink.content.size());
        final Content got = sink.content.get(0);
        assertEquals("tel:+15551234567", got.fromUri);
        assertEquals("image/jpeg", got.contentType);
        assertEquals("msg-1", got.messageId);
        assertEquals("gsma.rcs-e2ee.mls", got.e2eeSchemeId);
        assertArrayEquals("the bridge must forward the bytes verbatim", payload, got.body);
        assertTrue("nothing should have reached the String overload", sink.text.isEmpty());
    }

    /** Frame as a peer would, unframe as {@code handleInboundMls} does, and cross the seam. */
    @Test
    public void framedImage_arrivesAtTheSeamWithItsRealTypeAndByteIdenticalBody() {
        final byte[] image = binaryPayload();
        final byte[] wire = RccMlsBody.frame(image, "image/jpeg", /*inline=*/ true);

        final RccMlsBody.Parsed parsed = RccMlsBody.parse(wire);
        assertEquals("image/jpeg", parsed.contentType);

        final RecordingListener sink = new RecordingListener();
        new CarrierTransportBridge().wrap(sink).onIncomingContent(
                "tel:+15551234567", parsed.body, parsed.contentType, "msg-2",
                "gsma.rcs-e2ee.mls");

        assertEquals(1, sink.content.size());
        assertEquals("image/jpeg", sink.content.get(0).contentType);
        assertArrayEquals(image, sink.content.get(0).body);
    }

    /**
     * {@code onIncomingContent} defaults to the String form, so a new hop that forgets to override
     * it compiles and corrupts binary.
     */
    @Test
    public void aListenerThatOnlyOverridesTheStringForm_corruptsBinary() {
        final byte[] payload = binaryPayload();
        final StringOnlyListener stringOnly = new StringOnlyListener();

        new CarrierTransportBridge().wrap(stringOnly).onIncomingContent(
                "tel:+15551234567", payload, "image/jpeg", "msg-3", "gsma.rcs-e2ee.mls");

        assertEquals(1, stringOnly.bodies.size());
        final byte[] whatItGot =
                stringOnly.bodies.get(0).getBytes(StandardCharsets.UTF_8);
        assertFalse("a hop that inherits the String default silently corrupts binary —"
                        + " override onIncomingContent instead",
                java.util.Arrays.equals(payload, whatItGot));
        // The content type is lost as well.
    }

    /** Valid UTF-8 survives a String hop, so the text-only overloads remain correct. */
    @Test
    public void plainTextIsUnaffectedByEitherForm() {
        final String text = "hello — plain UTF-8 text, accents and all: café 🔒";
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        final RecordingListener sink = new RecordingListener();
        new CarrierTransportBridge().wrap(sink).onIncomingContent(
                "tel:+15551234567", bytes, "text/plain", "msg-4", "gsma.rcs-e2ee.mls");

        assertEquals(1, sink.content.size());
        assertArrayEquals(bytes, sink.content.get(0).body);
        assertEquals(text, new String(sink.content.get(0).body, StandardCharsets.UTF_8));
        assertArrayEquals("valid UTF-8 survives a String round trip — this is why the text-only"
                        + " overloads are still correct for plaintext RCS",
                bytes, new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- fixtures

    private static final class Content {
        final String fromUri;
        final byte[] body;
        final String contentType;
        final String messageId;
        final String e2eeSchemeId;
        Content(final String f, final byte[] b, final String ct, final String id, final String s) {
            fromUri = f; body = b; contentType = ct; messageId = id; e2eeSchemeId = s;
        }
    }

    /** Records the binary-safe hop; anything landing on the String hop is a failure signal. */
    private static final class RecordingListener implements Transport.Listener {
        final List<Content> content = new ArrayList<>();
        final List<String> text = new ArrayList<>();

        @Override public void onIncomingMessage(final String from, final String body,
                final String id) {
            text.add(body);
        }
        @Override public void onIncomingContent(final String from, final byte[] body,
                final String contentType, final String id, final String e2eeSchemeId) {
            content.add(new Content(from, body, contentType, id, e2eeSchemeId));
        }
        @Override public void onMessageStatus(final String id, final Message.Status s,
                final String err) { }
        @Override public void onRegistrationStateChanged(
                final Transport.RegistrationState s, final String r) { }
    }

    /** A hop that implements only the mandatory String method. */
    private static final class StringOnlyListener implements Transport.Listener {
        final List<String> bodies = new ArrayList<>();

        @Override public void onIncomingMessage(final String from, final String body,
                final String id) {
            bodies.add(body);
        }
        @Override public void onMessageStatus(final String id, final Message.Status s,
                final String err) { }
        @Override public void onRegistrationStateChanged(
                final Transport.RegistrationState s, final String r) { }
    }
}
