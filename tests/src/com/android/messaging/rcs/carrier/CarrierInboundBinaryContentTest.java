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
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.RccMlsBody;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Host-side tests for the BINARY-SAFETY of the carrier inbound content hop.
 *
 * <p>The carrier MLS receive path used to hand the decrypted RCC.16 application payload down the
 * listener chain as a Java String — {@code new String(plaintext, UTF_8)} in
 * {@code CarrierMessageReceiver.handleInboundMls}, turned back into bytes five hops later by
 * {@code CarrierImsService.emitIncoming}. That round trip is LOSSY: every byte sequence that is not
 * valid UTF-8 is replaced with U+FFFD on the way in and never comes back. Inline media over carrier
 * MLS could not work at all, and a text message just past 64 bytes was already at the mercy of its
 * frame's QUIC var-int, whose second byte is an arbitrary 0x00-0xFF.
 *
 * <p>The chain's Android-coupled hops (JAIN-SIP receiver, the {@code :ims} Messenger seam) are not
 * host-testable. What IS host-testable is the two halves that actually decide the outcome, and both
 * are exercised here against real production code:
 *
 * <ul>
 *   <li>{@link RccMlsBody} — the unframe that now happens at the hop that produced the plaintext,
 *       so a real content type reaches the seam instead of a hardcoded {@code text/plain};</li>
 *   <li>{@link Transport.Listener#onIncomingContent} forwarded through the real
 *       {@link CarrierTransportBridge#wrap} — the seam that carries bytes plus that content type.</li>
 * </ul>
 *
 * <p>{@link #aListenerThatOnlyOverridesTheStringForm_corruptsBinary} is the one that would have
 * caught the original defect and is the one that keeps catching it: it pins that the inherited
 * String fallback destroys binary, so a hop added later that forgets to override
 * {@code onIncomingContent} is a hop that silently corrupts again.
 */
public class CarrierInboundBinaryContentTest {

    /**
     * A payload with the two shapes that break a String round trip: an embedded NUL, and bytes that
     * are not valid UTF-8 at all. {@code 0xC3 0x28} is a truncated 2-byte sequence (0xC3 expects a
     * continuation byte, 0x28 is not one); {@code 0xFF 0xFE} can never appear in UTF-8. These are
     * ordinary bytes in a JPEG.
     */
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

    /** Why a String hop cannot carry this at all — the fact the whole change rests on. */
    @Test
    public void utf8RoundTripDestroysBinary() {
        final byte[] payload = binaryPayload();
        final byte[] roundTripped =
                new String(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        assertFalse("a UTF-8 String round trip must be assumed lossy for binary",
                java.util.Arrays.equals(payload, roundTripped));
    }

    /**
     * And why it was never only a media problem. {@link RccMlsBody#frame} writes an 8-byte header
     * and then a QUIC var-int of the content-section length; for a content section of 64..16383
     * bytes that var-int is {@code 0x40|(n>>8)} followed by {@code n&0xff}, so its SECOND byte takes
     * any value 0x00..0xFF and for a whole band of ordinary message lengths it is >= 0x80. That byte
     * is not text, and it sits BEFORE the text — so a PLAIN TEXT message of an unlucky length was
     * already being mangled by the String hop. Unframing has to happen before any String hop, not
     * after; this test finds a real length where it matters rather than asserting it of all of them.
     */
    @Test
    public void someOrdinaryTextLengthsPutANonAsciiByteInTheFrameItself() {
        String culprit = null;
        byte[] framed = null;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000 && culprit == null; i++) {
            sb.append('x');   // ASCII, so any high byte found below is the FRAME's, not the body's
            final byte[] f = RccMlsBody.frameText(sb.toString());
            if (prefixHasHighByte(f, sb.length())) {
                culprit = sb.toString();
                framed = f;
            }
        }
        assertTrue("expected some body length under 2000 whose var-int carries a high byte",
                culprit != null);

        // Unframe FIRST (what the fixed code does) -> the text is intact.
        final RccMlsBody.Parsed parsed = RccMlsBody.parse(framed);
        assertEquals("text/plain", parsed.contentType);
        assertEquals(culprit, new String(parsed.body, StandardCharsets.UTF_8));

        // Stringify the FRAME first (what the broken chain did) -> the frame's own bytes change,
        // for a message that is nothing but ASCII letters.
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

    /** The fixed hop: bytes plus a real content type cross {@code CarrierTransportBridge} intact. */
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

    /**
     * The whole inbound MLS shape, end to end over the two real pieces: frame an image the way a
     * peer would, unframe it the way {@code handleInboundMls} now does, and push the result across
     * the seam. The content type that reaches the producer is {@code image/jpeg} and the bytes are
     * the ones the peer sent — which is exactly what a still-String chain could not deliver.
     */
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
     * THE REGRESSION GUARD. {@code Transport.Listener.onIncomingContent} has a default that degrades
     * to the String form, so every existing implementer keeps compiling — which means a hop added to
     * the inbound chain later can inherit it without a compile error and put the corruption straight
     * back. This pins what that costs, so the failure is a red test rather than a silently mangled
     * image on a device.
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
        // And it loses the content type entirely, which is the other half of the same defect.
    }

    /** Plain text is the case where a String hop is genuinely lossless — do not "fix" those. */
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

    /** A hop written the old way: it implements only the mandatory String method. */
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
