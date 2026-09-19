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
package com.android.messaging.rcs.carrier.msrp.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;
import java.util.Arrays;

import org.junit.Test;

/**
 * Host-side unit tests for {@link SdpOffer} — SDP body builder + parser for
 * MSRP chat sessions per RFC 4566 + RFC 4975 §8 + RFC 4572 + GSMA UP 2.4
 * §A.1.3.5.
 */
public class SdpOfferTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String CRLF = "\r\n";

    // ============================================================
    // Encode
    // ============================================================

    @Test
    public void encode_minimalTlsOffer_hasAllUpRequiredLines() {
        SdpOffer o = SdpOffer.builder()
                .sessionId("abcd1234")
                .sessionVersion(1L)
                .host("203.0.113.5")
                .port(2855)
                .msrpPath("msrps://203.0.113.5:2855/abcd1234;tcp")
                .setup(SdpOffer.SetupRole.ACTIVE)
                .fingerprint("SHA-256",
                        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99")
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .build();

        String wire = new String(o.encode(), UTF_8);
        assertTrue("v= line", wire.contains("v=0" + CRLF));
        // RFC 4566 §5.2: <sess-id> MUST be a numeric string. Our MSRP session-id is a hex token
        // (correct in a=path, NOT in o=) — emitting hex made PJMEDIA reject the descriptor with
        // PJMEDIA_SDP_EINSDP (open5gs, 2026-07-19: 400 on the INVITE offer, cause=406 BYE on the
        // 200-OK answer). SdpOffer therefore derives a stable positive-numeric sess-id for o=.
        // Assert THAT RULE rather than a literal, so the test cannot go stale against a
        // deliberately-changed derivation. (Was: expected the hex "abcd1234" verbatim.)
        final java.util.regex.Matcher oLine = java.util.regex.Pattern
                .compile("o=- (\\S+) 1 IN IP4 203\\.0\\.113\\.5" + CRLF)
                .matcher(wire);
        assertTrue("o= line present", oLine.find());
        assertTrue("o= sess-id must be numeric per RFC 4566 §5.2, was: " + oLine.group(1),
                oLine.group(1).matches("[0-9]+"));
        assertTrue("c= line", wire.contains("c=IN IP4 203.0.113.5" + CRLF));
        assertTrue("t= line", wire.contains("t=0 0" + CRLF));
        assertTrue("m=message TCP/TLS/MSRP", wire.contains(
                "m=message 2855 TCP/TLS/MSRP *" + CRLF));
        assertTrue("a=path:", wire.contains("a=path:msrps://203.0.113.5:2855/abcd1234;tcp" + CRLF));
        assertTrue("a=setup:active", wire.contains("a=setup:active" + CRLF));
        assertTrue("a=fingerprint:", wire.contains("a=fingerprint:SHA-256 AA:BB:"));
        assertTrue("a=accept-types contains message/cpim",
                wire.contains("a=accept-types:message/cpim"));
        assertTrue("a=accept-wrapped-types contains text/plain",
                wire.contains("a=accept-wrapped-types:text/plain"));
        // GSMA UP / Etouffee marker
        assertTrue("accept-wrapped-types must include vnd.google.rcs.encrypted",
                wire.contains("application/vnd.google.rcs.encrypted"));
        assertTrue("sendrecv", wire.contains("a=sendrecv" + CRLF));
    }

    @Test
    public void encode_includesMsrpCemaAndConnectionWhenSet() {
        SdpOffer o = SdpOffer.builder()
                .sessionId("s1")
                .host("10.0.0.1")
                .port(2000)
                .msrpPath("msrps://10.0.0.1:2000/s1;tcp")
                .setup(SdpOffer.SetupRole.ACTPASS)
                .msrpCema(true)
                .connectionNew(true)
                .build();
        String wire = new String(o.encode(), UTF_8);
        assertTrue(wire.contains("a=connection:new" + CRLF));
        assertTrue(wire.contains("a=msrp-cema" + CRLF));
        assertTrue(wire.contains("a=setup:actpass" + CRLF));
    }

    @Test
    public void encode_cleartextMsrpHasNoFingerprint() {
        SdpOffer o = SdpOffer.builder()
                .sessionId("s1")
                .host("10.0.0.1")
                .port(2000)
                .mediaProto(SdpOffer.MSRP_PROTO_TCP)
                .msrpPath("msrp://10.0.0.1:2000/s1;tcp")
                .setup(SdpOffer.SetupRole.PASSIVE)
                .build();
        String wire = new String(o.encode(), UTF_8);
        assertTrue(wire.contains("m=message 2000 TCP/MSRP *"));
        assertFalse("no fingerprint", wire.contains("a=fingerprint"));
    }

    @Test
    public void build_rejectsBadInputs() {
        try {
            SdpOffer.builder().host("h").port(2000).build();
            fail("missing sessionId");
        } catch (IllegalStateException expected) {}

        try {
            SdpOffer.builder().sessionId("s").port(2000).build();
            fail("missing host");
        } catch (IllegalStateException expected) {}

        try {
            SdpOffer.builder().sessionId("s").host("h").port(0).build();
            fail("zero port");
        } catch (IllegalStateException expected) {}

        try {
            SdpOffer.builder().sessionId("s").host("h").port(70000).build();
            fail("port out of range");
        } catch (IllegalStateException expected) {}
    }

    // ============================================================
    // Parse
    // ============================================================

    @Test
    public void parse_rfc4566ExampleStyleOffer() throws Exception {
        // Constructed per RFC 4566 §6.1 example shape (audio swapped for
        // MSRP message), with the MSRP a-lines from RFC 4975 §A.
        String sdp = ""
                + "v=0" + CRLF
                + "o=alice 2890844526 2890844527 IN IP4 192.0.2.10" + CRLF
                + "s=-" + CRLF
                + "c=IN IP4 192.0.2.10" + CRLF
                + "t=0 0" + CRLF
                + "m=message 7654 TCP/TLS/MSRP *" + CRLF
                + "a=path:msrps://atlanta.example.com:7654/jshA7we;tcp" + CRLF
                + "a=accept-types:message/cpim" + CRLF
                + "a=accept-wrapped-types:text/plain application/vnd.google.rcs.encrypted" + CRLF
                + "a=setup:actpass" + CRLF
                + "a=fingerprint:SHA-256 12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:"
                        + "12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0" + CRLF
                + "a=sendrecv" + CRLF;

        SdpOffer o = SdpOffer.parse(sdp);
        assertEquals("alice", o.getOrigUser());
        assertEquals("2890844526", o.getSessionId());
        assertEquals(2890844527L, o.getSessionVersion());
        assertEquals("IP4", o.getAddrType());
        assertEquals("192.0.2.10", o.getHost());
        assertEquals(7654, o.getPort());
        assertEquals("TCP/TLS/MSRP", o.getMediaProto());
        assertTrue(o.isMsrpTls());
        assertEquals("msrps://atlanta.example.com:7654/jshA7we;tcp", o.getMsrpPath());
        assertEquals(SdpOffer.SetupRole.ACTPASS, o.getSetup());
        assertTrue(o.hasFingerprint());
        assertEquals("SHA-256", o.getFingerprintAlg());
        assertEquals(SdpOffer.Direction.SENDRECV, o.getDirection());
        assertEquals(Arrays.asList("message/cpim"), o.getAcceptTypes());
        assertEquals(Arrays.asList("text/plain", "application/vnd.google.rcs.encrypted"),
                o.getAcceptWrappedTypes());
    }

    @Test
    public void parse_tolerantOfBareLF() throws Exception {
        String sdp = "v=0\n"
                + "o=- s1 1 IN IP4 10.0.0.1\n"
                + "s=-\n"
                + "c=IN IP4 10.0.0.1\n"
                + "t=0 0\n"
                + "m=message 2000 TCP/MSRP *\n"
                + "a=path:msrp://10.0.0.1:2000/s1;tcp\n"
                + "a=accept-types:message/cpim\n";
        SdpOffer o = SdpOffer.parse(sdp);
        assertEquals(2000, o.getPort());
        assertEquals("10.0.0.1", o.getHost());
    }

    @Test
    public void parse_preservesUnknownAttributes() throws Exception {
        String sdp = ""
                + "v=0" + CRLF
                + "o=- s 1 IN IP4 h" + CRLF
                + "c=IN IP4 h" + CRLF
                + "t=0 0" + CRLF
                + "m=message 1000 TCP/TLS/MSRP *" + CRLF
                + "a=path:msrps://h:1000/s;tcp" + CRLF
                + "a=carrier-vendor-X:something" + CRLF
                + "a=sendrecv" + CRLF;
        SdpOffer o = SdpOffer.parse(sdp);
        assertEquals("something", o.getExtraAttributes().get("carrier-vendor-x"));
    }

    @Test
    public void parse_rejectsMissingVersion() {
        String sdp = "o=- s 1 IN IP4 h" + CRLF
                + "m=message 1000 TCP/TLS/MSRP *" + CRLF;
        try {
            SdpOffer.parse(sdp);
            fail("expected SdpParseException");
        } catch (SdpParseException expected) {}
    }

    @Test
    public void parse_rejectsMissingMediaLine() {
        String sdp = "v=0" + CRLF
                + "o=- s 1 IN IP4 h" + CRLF
                + "s=-" + CRLF;
        try {
            SdpOffer.parse(sdp);
            fail("expected SdpParseException");
        } catch (SdpParseException expected) {}
    }

    @Test
    public void parse_rejectsMultiMediaLine() {
        String sdp = "v=0" + CRLF
                + "o=- s 1 IN IP4 h" + CRLF
                + "c=IN IP4 h" + CRLF
                + "t=0 0" + CRLF
                + "m=message 1000 TCP/TLS/MSRP *" + CRLF
                + "m=message 1002 TCP/TLS/MSRP *" + CRLF;
        try {
            SdpOffer.parse(sdp);
            fail("expected SdpParseException");
        } catch (SdpParseException expected) {}
    }

    @Test
    public void parse_rejectsNonMessageMedia() {
        String sdp = "v=0" + CRLF
                + "o=- s 1 IN IP4 h" + CRLF
                + "c=IN IP4 h" + CRLF
                + "t=0 0" + CRLF
                + "m=audio 1000 RTP/AVP 0" + CRLF;
        try {
            SdpOffer.parse(sdp);
            fail("expected SdpParseException");
        } catch (SdpParseException expected) {}
    }

    // ============================================================
    // Round-trip
    // ============================================================

    @Test
    public void roundTrip_byteForByteIdentical() throws Exception {
        SdpOffer original = SdpOffer.builder()
                // NUMERIC on purpose: RFC 4566 §5.2 requires it, and SdpOffer normalises a
                // non-numeric sess-id on encode, so a hex/alpha id here would make a "byte for byte
                // identical" round trip untestable by construction. Hex ids are covered by the
                // normalisation assertion in encode_minimalTlsOffer_hasAllUpRequiredLines.
                .sessionId("12345")
                .sessionVersion(42L)
                .host("198.51.100.10")
                .port(4567)
                .msrpPath("msrps://198.51.100.10:4567/s12345;tcp")   // hex/opaque is CORRECT here
                .setup(SdpOffer.SetupRole.ACTPASS)
                .msrpCema(true)
                .connectionNew(true)
                .fingerprint("SHA-256",
                        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:"
                                + "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF")
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .build();

        SdpOffer parsed = SdpOffer.parse(original.encode());

        assertEquals(original.getSessionId(), parsed.getSessionId());
        assertEquals(original.getSessionVersion(), parsed.getSessionVersion());
        assertEquals(original.getHost(), parsed.getHost());
        assertEquals(original.getPort(), parsed.getPort());
        assertEquals(original.getMediaProto(), parsed.getMediaProto());
        assertEquals(original.getMsrpPath(), parsed.getMsrpPath());
        assertEquals(original.getSetup(), parsed.getSetup());
        assertEquals(original.hasMsrpCema(), parsed.hasMsrpCema());
        assertEquals(original.hasConnectionNew(), parsed.hasConnectionNew());
        assertEquals(original.getFingerprintAlg(), parsed.getFingerprintAlg());
        // Fingerprint hex normalized through; case may differ.
        assertEquals(original.getFingerprintHex().toLowerCase(),
                parsed.getFingerprintHex().toLowerCase());
        assertEquals(original.getAcceptTypes(), parsed.getAcceptTypes());
        assertEquals(original.getAcceptWrappedTypes(), parsed.getAcceptWrappedTypes());
        assertEquals(original.getDirection(), parsed.getDirection());

        // And re-encoding the parsed value yields identical bytes.
        byte[] e1 = original.encode();
        byte[] e2 = parsed.encode();
        assertEquals("re-encoded bytes equal", new String(e1, UTF_8), new String(e2, UTF_8));
    }

    // ============================================================
    // Fingerprint
    // ============================================================

    @Test
    public void fingerprintMatches_caseInsensitiveAndColonTolerant() {
        SdpOffer o = SdpOffer.builder()
                .sessionId("s").host("h").port(1).msrpPath("msrps://h:1/s;tcp")
                .fingerprint("SHA-256", "AB:CD:EF")
                .build();
        assertTrue(o.fingerprintMatches("ab:cd:ef"));
        assertTrue(o.fingerprintMatches("AB:CD:EF"));
        assertTrue(o.fingerprintMatches("abcdef"));
        assertTrue(o.fingerprintMatches("ABCDEF"));
        assertFalse(o.fingerprintMatches("01:23:45"));
        assertFalse(o.fingerprintMatches(null));
    }

    @Test
    public void fingerprintMatches_returnsFalseWhenSdpHasNoFingerprint() {
        SdpOffer o = SdpOffer.builder()
                .sessionId("s").host("h").port(1).msrpPath("msrps://h:1/s;tcp").build();
        assertFalse(o.fingerprintMatches("AB:CD:EF"));
    }

    @Test
    public void formatFingerprint_colonSeparatedLowercaseHex() {
        byte[] bytes = new byte[] {(byte) 0xAB, (byte) 0xCD, 0x01, 0x23};
        String f = SdpOffer.formatFingerprint(bytes);
        // RFC 4572 §5 requires lowercase hex colon-separated.
        assertEquals("ab:cd:01:23", f);
    }

    @Test
    public void formatFingerprint_nullSafe() {
        assertNull(SdpOffer.formatFingerprint(null));
    }

    // ============================================================
    // SetupRole / Direction enums
    // ============================================================

    @Test
    public void setupRole_parseAllVariants() {
        assertEquals(SdpOffer.SetupRole.ACTIVE,   SdpOffer.SetupRole.parse("active"));
        assertEquals(SdpOffer.SetupRole.PASSIVE,  SdpOffer.SetupRole.parse("PASSIVE"));
        assertEquals(SdpOffer.SetupRole.ACTPASS,  SdpOffer.SetupRole.parse(" actpass "));
        assertEquals(SdpOffer.SetupRole.HOLDCONN, SdpOffer.SetupRole.parse("holdconn"));
        assertNull(SdpOffer.SetupRole.parse("garbage"));
        assertNull(SdpOffer.SetupRole.parse(null));
    }

    @Test
    public void direction_parseAllVariants() {
        assertEquals(SdpOffer.Direction.SENDRECV, SdpOffer.Direction.parse("sendrecv"));
        assertEquals(SdpOffer.Direction.SENDONLY, SdpOffer.Direction.parse("SENDONLY"));
        assertEquals(SdpOffer.Direction.RECVONLY, SdpOffer.Direction.parse("recvonly"));
        assertEquals(SdpOffer.Direction.INACTIVE, SdpOffer.Direction.parse("inactive"));
        assertNull(SdpOffer.Direction.parse(null));
    }
}
