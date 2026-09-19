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
package com.android.messaging.rcs.carrier.loopback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link SimpleSipMessage} — the SIP-over-TCP message parser
 * used by {@link LoopbackSipServer}. Test scaffolding.
 */
public class SimpleSipMessageTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void parseRequestWithBody() throws Exception {
        String wire =
            "REGISTER sip:loopback.test SIP/2.0\r\n"
            + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-1\r\n"
            + "From: <tel:+15715551234>;tag=fromtag\r\n"
            + "To: <tel:+15715551234>\r\n"
            + "Call-ID: my-call-id\r\n"
            + "CSeq: 1 REGISTER\r\n"
            + "Content-Length: 11\r\n"
            + "\r\n"
            + "hello world";
        SimpleSipMessage msg = SimpleSipMessage.read(
                new ByteArrayInputStream(wire.getBytes(UTF_8)));
        assertNotNull(msg);
        assertEquals(SimpleSipMessage.Kind.REQUEST, msg.kind);
        assertEquals("REGISTER", msg.getMethod());
        assertEquals("sip:loopback.test", msg.getRequestUri());
        assertEquals("SIP/2.0", msg.startC);
        assertEquals("my-call-id", msg.getHeader("Call-ID"));
        assertEquals("1 REGISTER", msg.getHeader("CSeq"));
        assertArrayEquals("hello world".getBytes(UTF_8), msg.body);
    }

    @Test
    public void parseResponse() throws Exception {
        String wire =
            "SIP/2.0 200 OK\r\n"
            + "Via: SIP/2.0/TCP 127.0.0.1:1234\r\n"
            + "From: <tel:+15715551234>;tag=fromtag\r\n"
            + "To: <tel:+15715551234>;tag=totag\r\n"
            + "Call-ID: cid\r\n"
            + "CSeq: 2 REGISTER\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";
        SimpleSipMessage msg = SimpleSipMessage.read(
                new ByteArrayInputStream(wire.getBytes(UTF_8)));
        assertEquals(SimpleSipMessage.Kind.RESPONSE, msg.kind);
        assertEquals(200, msg.getStatusCode());
        assertEquals("OK", msg.startC);
        assertNull(msg.body);
    }

    @Test
    public void parseHandlesMultipleViaHeaders() throws Exception {
        String wire =
            "REGISTER sip:x SIP/2.0\r\n"
            + "Via: SIP/2.0/TCP 1.1.1.1:1\r\n"
            + "Via: SIP/2.0/TCP 2.2.2.2:2\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";
        SimpleSipMessage msg = SimpleSipMessage.read(
                new ByteArrayInputStream(wire.getBytes(UTF_8)));
        assertEquals(2, msg.getHeaders("Via").size());
        assertEquals("SIP/2.0/TCP 1.1.1.1:1", msg.getHeaders("Via").get(0));
        assertEquals("SIP/2.0/TCP 2.2.2.2:2", msg.getHeaders("Via").get(1));
    }

    @Test
    public void parseHeaderContinuationLines() throws Exception {
        // RFC 3261 §7.3.1 allows LWS-prefixed continuation lines.
        String wire =
            "REGISTER sip:x SIP/2.0\r\n"
            + "WWW-Authenticate: Digest realm=\"loopback.test\",\r\n"
            + " nonce=\"abc\",\r\n"
            + "\tqop=\"auth\"\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";
        SimpleSipMessage msg = SimpleSipMessage.read(
                new ByteArrayInputStream(wire.getBytes(UTF_8)));
        String h = msg.getHeader("WWW-Authenticate");
        assertNotNull(h);
        assertTrue("continuation joined: " + h, h.contains("nonce=\"abc\""));
        assertTrue("continuation joined: " + h, h.contains("qop=\"auth\""));
    }

    @Test
    public void parseRejectsMalformedStartLine() {
        String wire = "BLAH\r\nContent-Length: 0\r\n\r\n";
        try {
            SimpleSipMessage.read(new ByteArrayInputStream(wire.getBytes(UTF_8)));
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void parseReturnsNullOnEofBeforeAnyBytes() throws Exception {
        SimpleSipMessage msg = SimpleSipMessage.read(new ByteArrayInputStream(new byte[0]));
        assertNull(msg);
    }

    @Test
    public void extractTagPullsTagFromFromOrToHeader() {
        assertEquals("abc123",
                SimpleSipMessage.extractTag("<tel:+15551234567>;tag=abc123"));
        assertEquals("xyz",
                SimpleSipMessage.extractTag("<sip:user@x>;tag=xyz;other=y"));
        assertNull(SimpleSipMessage.extractTag("<sip:user@x>"));
        assertNull(SimpleSipMessage.extractTag(null));
    }

    @Test
    public void parseAuthHeaderFlattensQuotedAndTokenValues() {
        String h = "Digest realm=\"loopback.test\","
                + " nonce=\"abc-def\","
                + " uri=\"sip:loopback.test\","
                + " response=\"deadbeef\","
                + " algorithm=MD5,"
                + " qop=auth, nc=00000001, cnonce=\"01020304\"";
        Map<String, String> p = SimpleSipMessage.parseAuthHeader(h);
        assertEquals("loopback.test", p.get("realm"));
        assertEquals("abc-def", p.get("nonce"));
        assertEquals("sip:loopback.test", p.get("uri"));
        assertEquals("deadbeef", p.get("response"));
        assertEquals("MD5", p.get("algorithm"));
        assertEquals("auth", p.get("qop"));
        assertEquals("00000001", p.get("nc"));
        assertEquals("01020304", p.get("cnonce"));
    }

    @Test
    public void encodeRoundTripsThroughRead() throws Exception {
        SimpleSipMessage built = SimpleSipMessage.Builder.response(202, "Accepted")
                .addHeader("Via", "SIP/2.0/TCP 127.0.0.1:1234")
                .addHeader("From", "<tel:+15715551234>;tag=t1")
                .addHeader("To", "<tel:+15715559999>;tag=t2")
                .addHeader("Call-ID", "x")
                .addHeader("CSeq", "1 MESSAGE")
                .body("ok", "text/plain")
                .build();
        byte[] wire = built.encode();
        SimpleSipMessage round = SimpleSipMessage.read(new ByteArrayInputStream(wire));
        assertEquals(SimpleSipMessage.Kind.RESPONSE, round.kind);
        assertEquals(202, round.getStatusCode());
        assertArrayEquals("ok".getBytes(UTF_8), round.body);
        assertEquals("text/plain", round.getHeader("Content-Type"));
    }
}
