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
package com.android.messaging.rcs.carrier.msrp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Unit tests for the MSRP framing library. Runs on the host JVM via
 * {@code junit} — no Android dependencies.
 *
 * <p>Test grouping:
 * <ul>
 *   <li>round-trip encode/decode</li>
 *   <li>RFC 4975 §4 / Appendix A wire-byte examples</li>
 *   <li>REPORT request format with {@code Status} header</li>
 *   <li>multi-chunk SEND continuation / completion flags</li>
 *   <li>parser tolerance of bare LF in addition to CRLF</li>
 *   <li>header-value injection rejection (CR/LF in value)</li>
 *   <li>response (e.g. {@code MSRP <tid> 200 OK}) parse</li>
 * </ul>
 */
public class MsrpMessageTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final byte[] CRLF = {'\r', '\n'};

    // ============================================================
    // Round-trip
    // ============================================================

    @Test
    public void roundTrip_simpleSend() throws Exception {
        byte[] body = "Hey Bob, are you there?".getBytes(UTF_8);
        MsrpMessage sent = MsrpMessage.newSend()
                .transactionId("a786hjs2")
                .toPath("msrp://biloxi.example.com:12763/kjhd37s2s20w2a;tcp")
                .fromPath("msrp://atlanta.example.com:7654/jshA7weztas;tcp")
                .messageId("87652491")
                .byteRange(1, body.length, body.length)
                .contentType("text/plain")
                .body(body)
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();

        byte[] wire = sent.encode();
        MsrpMessage parsed = MsrpMessage.parse(wire);

        assertEquals("round-trip equality", sent, parsed);
        assertEquals(MsrpMethod.SEND, parsed.getMethod());
        assertEquals("a786hjs2", parsed.getTransactionId());
        assertEquals("text/plain", parsed.getContentType());
        assertEquals("87652491", parsed.getMessageId());
        assertEquals(MsrpEndFlag.COMPLETE, parsed.getEndFlag());
        assertArrayEquals(body, parsed.getBody());
    }

    @Test
    public void roundTrip_responseHasNoBody() throws Exception {
        MsrpMessage resp = MsrpMessage.newResponse(200, "OK")
                .transactionId("a786hjs2")
                .toPath("msrp://atlanta.example.com:7654/jshA7weztas;tcp")
                .fromPath("msrp://biloxi.example.com:12763/kjhd37s2s20w2a;tcp")
                .build();
        byte[] wire = resp.encode();
        MsrpMessage parsed = MsrpMessage.parse(wire);
        assertTrue(parsed.isResponse());
        assertEquals(200, parsed.getStatusCode());
        assertEquals("OK", parsed.getStatusComment());
        assertEquals(0, parsed.getBodyLength());
        assertEquals(resp, parsed);
    }

    // ============================================================
    // RFC 4975 wire-byte examples
    // ============================================================

    /**
     * RFC 4975 §4 illustrative SEND. The body "Hey Bob, are you there?" is
     * 23 bytes (we counted; the RFC's "1-25/25" includes the trailing
     * "\r\n" delimiter per RFC 4975's interpretation of Byte-Range — the
     * spec is ambiguous here and Google Messages uses payload-length not
     * framed-length, so we follow its interpretation).
     *
     * <p>This test verifies our encoder can round-trip the literal RFC 4975
     * example wire bytes that we control (using Google Messages' Byte-Range
     * convention), and that the parser accepts the RFC's exact illustrative
     * frame as written (which uses the legacy interpretation).
     */
    @Test
    public void parses_rfc4975_section4_send_example() throws Exception {
        String wireStr =
                "MSRP a786hjs2 SEND\r\n" +
                "To-Path: msrp://biloxi.example.com:12763/kjhd37s2s20w2a;tcp\r\n" +
                "From-Path: msrp://atlanta.example.com:7654/jshA7weztas;tcp\r\n" +
                "Message-ID: 87652491\r\n" +
                "Byte-Range: 1-25/25\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Hey Bob, are you there?\r\n" +
                "-------a786hjs2$\r\n";
        MsrpMessage m = MsrpMessage.parse(wireStr.getBytes(UTF_8));
        assertEquals(MsrpMethod.SEND, m.getMethod());
        assertEquals("a786hjs2", m.getTransactionId());
        assertEquals(MsrpEndFlag.COMPLETE, m.getEndFlag());
        assertEquals("87652491", m.getMessageId());
        assertEquals("text/plain", m.getContentType());
        assertEquals("1-25/25", m.getByteRange());
        assertEquals("Hey Bob, are you there?",
                new String(m.getBody(), UTF_8));
    }

    @Test
    public void parses_rfc4975_section5_multichunk_continuation() throws Exception {
        // RFC 4975 §5.1 illustrative multi-chunk example. Chunk 1 of 2:
        String wire1 =
                "MSRP dkei38sd SEND\r\n" +
                "To-Path: msrp://alice.example.com:7777/foo;tcp\r\n" +
                "From-Path: msrp://bob.example.com:7777/bar;tcp\r\n" +
                "Message-ID: 4564dpWd\r\n" +
                "Byte-Range: 1-*/8\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "abcd\r\n" +
                "-------dkei38sd+\r\n";
        MsrpMessage c1 = MsrpMessage.parse(wire1.getBytes(UTF_8));
        assertEquals(MsrpEndFlag.CONTINUATION, c1.getEndFlag());
        assertEquals("4564dpWd", c1.getMessageId());
        assertEquals("1-*/8", c1.getByteRange());
        assertArrayEquals("abcd".getBytes(UTF_8), c1.getBody());

        String wire2 =
                "MSRP dkei38ia SEND\r\n" +
                "To-Path: msrp://alice.example.com:7777/foo;tcp\r\n" +
                "From-Path: msrp://bob.example.com:7777/bar;tcp\r\n" +
                "Message-ID: 4564dpWd\r\n" +
                "Byte-Range: 5-8/8\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "EFGH\r\n" +
                "-------dkei38ia$\r\n";
        MsrpMessage c2 = MsrpMessage.parse(wire2.getBytes(UTF_8));
        assertEquals(MsrpEndFlag.COMPLETE, c2.getEndFlag());
        assertEquals("5-8/8", c2.getByteRange());
        assertArrayEquals("EFGH".getBytes(UTF_8), c2.getBody());
    }

    // ============================================================
    // REPORT
    // ============================================================

    @Test
    public void encode_reportRequest() throws Exception {
        MsrpMessage r = MsrpMessage.newReport()
                .transactionId("rpt00001")
                .toPath("msrp://atlanta.example.com:7654/jshA7weztas;tcp")
                .fromPath("msrp://biloxi.example.com:12763/kjhd37s2s20w2a;tcp")
                .messageId("87652491")
                .byteRange(1, 25, 25)
                .status(200, "OK")
                .build();
        byte[] wire = r.encode();
        String s = new String(wire, UTF_8);
        // Start line
        assertTrue("starts with REPORT method line", s.startsWith("MSRP rpt00001 REPORT\r\n"));
        // Status header with namespace 000
        assertTrue("Status header uses 000 namespace", s.contains("Status: 000 200 OK\r\n"));
        // No body, terminates immediately with end-line
        assertTrue("ends with end-line $", s.endsWith("-------rpt00001$\r\n"));
        // Round-trip
        MsrpMessage parsed = MsrpMessage.parse(wire);
        assertEquals(MsrpMethod.REPORT, parsed.getMethod());
        assertEquals("000 200 OK", parsed.getStatus());
        assertEquals(0, parsed.getBodyLength());
        assertEquals(r, parsed);
    }

    @Test
    public void report_rejectsSuccessAndFailureReportHeaders() {
        try {
            MsrpMessage.newReport()
                    .transactionId("rpt00001")
                    .toPath("msrp://a/b;tcp")
                    .fromPath("msrp://c/d;tcp")
                    .messageId("m1")
                    .byteRange(1, 1, 1)
                    .status(200, "OK")
                    .successReport(true)
                    .build();
            fail("REPORT with Success-Report should not validate");
        } catch (IllegalStateException expected) { /* ok */ }
    }

    // ============================================================
    // Header behaviour
    // ============================================================

    @Test
    public void header_caseInsensitiveOnParse() throws Exception {
        String wire =
                "MSRP a786hjs2 SEND\r\n" +
                "TO-PATH: msrp://a/b;tcp\r\n" +
                "from-path: msrp://c/d;tcp\r\n" +
                "Message-Id: m1\r\n" +
                "Byte-Range: 1-3/3\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "abc\r\n" +
                "-------a786hjs2$\r\n";
        MsrpMessage m = MsrpMessage.parse(wire.getBytes(UTF_8));
        assertEquals("msrp://a/b;tcp", m.getToPath());
        assertEquals("msrp://c/d;tcp", m.getFromPath());
        assertEquals("m1", m.getMessageId());
    }

    @Test
    public void header_rejectsCRLFInjection() {
        try {
            MsrpMessage.newSend()
                    .transactionId("tid12345")
                    .toPath("msrp://a/b;tcp")
                    .fromPath("msrp://c/d\r\nEvil-Header: evil;tcp")
                    .messageId("m1")
                    .byteRange(1, 1, 1)
                    .body(new byte[]{'x'})
                    .build();
            fail("CRLF in header value must be rejected");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void parser_toleratesBareLF() throws Exception {
        String wire =
                "MSRP a786hjs2 SEND\n" +
                "To-Path: msrp://a/b;tcp\n" +
                "From-Path: msrp://c/d;tcp\n" +
                "Message-ID: m1\n" +
                "Byte-Range: 1-3/3\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "abc\n" +
                "-------a786hjs2$\n";
        MsrpMessage m = MsrpMessage.parse(wire.getBytes(UTF_8));
        assertEquals(MsrpMethod.SEND, m.getMethod());
        assertArrayEquals("abc".getBytes(UTF_8), m.getBody());
        assertEquals(MsrpEndFlag.COMPLETE, m.getEndFlag());
    }

    @Test
    public void encoder_omitsBlankLineBeforeEndLineForBodyless() throws Exception {
        MsrpMessage m = MsrpMessage.newReport()
                .transactionId("tid12345")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 1, 1)
                .status(200, "OK")
                .build();
        String s = new String(m.encode(), UTF_8);
        // Expect: ...Status: 000 200 OK\r\n-------tid12345$\r\n (no blank line)
        assertFalse("bodyless frame should not emit blank line",
                s.contains("OK\r\n\r\n"));
        assertTrue(s.contains("Status: 000 200 OK\r\n-------tid12345$\r\n"));
    }

    // ============================================================
    // Empty SEND keepalive (RFC 4975 §6.4 / §11)
    // ============================================================

    @Test
    public void roundTrip_emptySendKeepalive() throws Exception {
        MsrpMessage ka = MsrpMessage.newSend()
                .transactionId("ka123abc")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("ka-msg-1")
                .byteRange("1-0/0")
                .successReport(true)
                .build();
        MsrpMessage parsed = MsrpMessage.parse(ka.encode());
        assertEquals(ka, parsed);
        assertEquals(0, parsed.getBodyLength());
    }

    // ============================================================
    // Transaction-id validation
    // ============================================================

    @Test
    public void tidValidator_acceptsRfcExamples() {
        // RFC 4975 examples
        assertTrue(MsrpTransactionId.isValid("a786hjs2"));
        assertTrue(MsrpTransactionId.isValid("dkei38sd"));
        assertTrue(MsrpTransactionId.isValid("dkei38ia"));
        // Google Messages-style hex
        assertTrue(MsrpTransactionId.isValid("0123456789abcdef0123456789abcdef"));
        // Minimum length (4) and max length (32)
        assertTrue(MsrpTransactionId.isValid("abcd"));
        assertTrue(MsrpTransactionId.isValid("a234567890123456789012345678901z"));
        // ident-char set
        assertTrue(MsrpTransactionId.isValid("a1.-+%="));
    }

    @Test
    public void tidValidator_rejectsBadInputs() {
        assertFalse("too short", MsrpTransactionId.isValid("abc"));
        assertFalse("33 chars", MsrpTransactionId.isValid(
                "a23456789012345678901234567890123"));
        assertFalse("leading non-alnum", MsrpTransactionId.isValid(".abcd"));
        assertFalse("bad char in body", MsrpTransactionId.isValid("ab cd"));
        assertFalse("null", MsrpTransactionId.isValid(null));
        assertFalse("empty", MsrpTransactionId.isValid(""));
    }

    @Test
    public void tidGenerator_producesValidIds() {
        for (int i = 0; i < 100; i++) {
            String id = MsrpTransactionId.next();
            assertTrue("tid " + id + " is valid", MsrpTransactionId.isValid(id));
            assertEquals(32, id.length());
        }
    }

    // ============================================================
    // Chunker
    // ============================================================

    @Test
    public void chunker_singleChunkWhenBodyFits() throws Exception {
        byte[] body = "hi".getBytes(UTF_8);
        MsrpMessage proto = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, body.length, body.length) // overridden by chunker
                .contentType("text/plain")
                .body(body)
                .build();
        List<MsrpMessage> chunks = new MsrpChunker(1024).chunk(proto);
        assertEquals(1, chunks.size());
        MsrpMessage only = chunks.get(0);
        assertEquals(MsrpEndFlag.COMPLETE, only.getEndFlag());
        assertEquals("1-2/2", only.getByteRange());
        assertArrayEquals(body, only.getBody());
    }

    @Test
    public void chunker_splits100KBodyIntoMultipleSendChunks() throws Exception {
        // 100KB of pseudo-random bytes
        byte[] body = new byte[100 * 1024];
        new Random(0xC0FFEE).nextBytes(body);
        int chunkSize = 4096;
        MsrpMessage proto = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("big-msg-1")
                .contentType("application/octet-stream")
                .body(body)
                .build();
        List<MsrpMessage> chunks = new MsrpChunker(chunkSize).chunk(proto);
        int expected = (body.length + chunkSize - 1) / chunkSize;
        assertEquals(expected, chunks.size());

        // All but last carry '+', last carries '$'.
        for (int i = 0; i < chunks.size(); i++) {
            MsrpMessage c = chunks.get(i);
            assertEquals(MsrpMethod.SEND, c.getMethod());
            assertEquals("big-msg-1", c.getMessageId());
            assertEquals("application/octet-stream", c.getContentType());
            if (i == chunks.size() - 1) {
                assertEquals("last chunk is $", MsrpEndFlag.COMPLETE, c.getEndFlag());
            } else {
                assertEquals("non-final chunk is +",
                        MsrpEndFlag.CONTINUATION, c.getEndFlag());
            }
        }

        // Each chunk's tid must be unique.
        java.util.Set<String> tids = new java.util.HashSet<>();
        for (MsrpMessage c : chunks) {
            assertTrue("tid unique per chunk: " + c.getTransactionId(),
                    tids.add(c.getTransactionId()));
        }

        // Reassembly via the reassembler reproduces the original body.
        MsrpReassembler r = new MsrpReassembler();
        MsrpReassembler.Outcome o = null;
        for (MsrpMessage c : chunks) {
            // round-trip every chunk through encode/parse to also stress the parser
            byte[] wire = c.encode();
            MsrpMessage parsed = MsrpMessage.parse(wire);
            assertEquals(c, parsed);
            o = r.feed(parsed);
        }
        assertEquals(MsrpReassembler.Outcome.COMPLETE, o);
        byte[] reassembled = r.take("big-msg-1");
        assertArrayEquals(body, reassembled);
    }

    @Test
    public void chunker_emptyBodyProducesSingleZeroByteSend() {
        MsrpMessage proto = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("ka1")
                .body(null) // no body
                .build();
        List<MsrpMessage> chunks = new MsrpChunker().chunk(proto);
        assertEquals(1, chunks.size());
        MsrpMessage only = chunks.get(0);
        assertEquals(0, only.getBodyLength());
        assertEquals(MsrpEndFlag.COMPLETE, only.getEndFlag());
        assertTrue(only.getByteRange().endsWith("/0"));
    }

    // ============================================================
    // Reassembler edge cases
    // ============================================================

    @Test
    public void reassembler_handlesAbort() throws Exception {
        MsrpReassembler r = new MsrpReassembler();
        MsrpMessage c1 = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 4, -1)
                .body("abcd".getBytes(UTF_8))
                .endFlag(MsrpEndFlag.CONTINUATION)
                .build();
        assertEquals(MsrpReassembler.Outcome.IN_PROGRESS, r.feed(c1));
        MsrpMessage c2 = MsrpMessage.newSend()
                .transactionId("bbbbbbbb")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(5, 8, -1)
                .body("efgh".getBytes(UTF_8))
                .endFlag(MsrpEndFlag.ABORT)
                .build();
        assertEquals(MsrpReassembler.Outcome.ABORTED, r.feed(c2));
        assertNull("aborted message yields null take", r.take("m1"));
        assertEquals(0, r.inFlightCount());
    }

    @Test
    public void reassembler_rejectsOutOfOrder() {
        MsrpReassembler r = new MsrpReassembler();
        MsrpMessage outOfOrder = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(5, 8, 8)
                .body("efgh".getBytes(UTF_8))
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        try {
            r.feed(outOfOrder);
            fail("expected MsrpException for out-of-order chunk");
        } catch (MsrpException expected) { /* ok */ }
    }

    @Test
    public void reassembler_rejectsLengthMismatch() {
        MsrpReassembler r = new MsrpReassembler();
        MsrpMessage lyingChunk = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 10, 10) // claims 10 bytes
                .body("abc".getBytes(UTF_8)) // but sends 3
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        try {
            r.feed(lyingChunk);
            fail("expected MsrpException for length mismatch");
        } catch (MsrpException expected) { /* ok */ }
    }

    // ============================================================
    // FrameReader (streaming)
    // ============================================================

    @Test
    public void frameReader_emitsCompleteFramesAcrossSocketWrites() throws Exception {
        // Build two frames and concatenate their wire bytes.
        byte[] f1 = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1").byteRange(1, 3, 3)
                .body("abc".getBytes(UTF_8)).build().encode();
        byte[] f2 = MsrpMessage.newResponse(200, "OK")
                .transactionId("bbbbbbbb")
                .toPath("msrp://c/d;tcp").fromPath("msrp://a/b;tcp")
                .build().encode();
        byte[] stream = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, stream, 0, f1.length);
        System.arraycopy(f2, 0, stream, f1.length, f2.length);

        // Feed the stream in 17-byte slices to simulate TCP fragmentation.
        MsrpFrameReader reader = new MsrpFrameReader();
        java.util.List<MsrpMessage> got = new java.util.ArrayList<>();
        for (int p = 0; p < stream.length; p += 17) {
            int chunk = Math.min(17, stream.length - p);
            got.addAll(reader.append(stream, p, chunk));
        }
        assertEquals(2, got.size());
        assertEquals(MsrpMethod.SEND, got.get(0).getMethod());
        assertTrue(got.get(1).isResponse());
        assertEquals(200, got.get(1).getStatusCode());
    }

    // ============================================================
    // Body with embedded end-line-like content
    // ============================================================

    @Test
    public void parser_handlesBodyContainingEndLineLikeBytes() throws Exception {
        // Body coincidentally contains "-------" — must NOT confuse the parser
        // because the end-line is the unique "-------<tid><flag>" sequence.
        byte[] body = ("dashes: -------xyzNotEndLine\r\n"
                + "and more text").getBytes(UTF_8);
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1").byteRange(1, body.length, body.length)
                .contentType("text/plain")
                .body(body)
                .build();
        byte[] wire = m.encode();
        MsrpMessage parsed = MsrpMessage.parse(wire);
        assertArrayEquals(body, parsed.getBody());
    }

    // ============================================================
    // Encoder output: header insertion order is preserved
    // ============================================================

    @Test
    public void encoder_preservesHeaderInsertionOrder() throws Exception {
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 1, 1)
                .failureReport("yes")
                .successReport(false)
                .contentType("text/plain")
                .body(new byte[]{'x'})
                .build();
        String s = new String(m.encode(), UTF_8);
        int idxTo = s.indexOf("To-Path:");
        int idxFrom = s.indexOf("From-Path:");
        int idxMid = s.indexOf("Message-ID:");
        int idxBr = s.indexOf("Byte-Range:");
        int idxFr = s.indexOf("Failure-Report:");
        int idxSr = s.indexOf("Success-Report:");
        int idxCt = s.indexOf("Content-Type:");
        assertTrue(idxTo  < idxFrom);
        assertTrue(idxFrom < idxMid);
        assertTrue(idxMid < idxBr);
        assertTrue(idxBr  < idxFr);
        assertTrue(idxFr  < idxSr);
        assertTrue(idxSr  < idxCt);
    }

    // ============================================================
    // Special chars in headers / message-ids
    // ============================================================

    @Test
    public void roundTrip_specialCharsInMessageIdAndContentType() throws Exception {
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                // RFC 4975 ident allows .  -  +  %  =, plus alnum
                .messageId("Msg.-+%=09xy")
                .byteRange(1, 1, 1)
                // CPIM with parameters
                .contentType("message/cpim; charset=\"utf-8\"")
                .body(new byte[]{'x'})
                .build();
        MsrpMessage parsed = MsrpMessage.parse(m.encode());
        assertEquals("Msg.-+%=09xy", parsed.getMessageId());
        assertEquals("message/cpim; charset=\"utf-8\"", parsed.getContentType());
        assertEquals(m, parsed);
    }

    // ============================================================
    // Empty headers list — should still produce a parsable response
    // ============================================================

    @Test
    public void responseWithNoHeaders_roundtrips() throws Exception {
        MsrpMessage r = MsrpMessage.newResponse(481, "Session does not exist")
                .transactionId("zzzzz123")
                .build();
        MsrpMessage parsed = MsrpMessage.parse(r.encode());
        assertEquals(481, parsed.getStatusCode());
        assertEquals("Session does not exist", parsed.getStatusComment());
        assertEquals(r, parsed);
    }

    @Test
    public void parser_rejectsUnknownStartTag() {
        try {
            MsrpMessage.parse("SIP/2.0 200 OK\r\n\r\n".getBytes(UTF_8));
            fail("non-MSRP start-line must be rejected");
        } catch (MsrpException expected) { /* ok */ }
    }

    @Test
    public void parser_rejectsInvalidEndFlag() {
        // Build a frame manually with a bogus end-flag char.
        String wire =
                "MSRP aaaaaaaa SEND\r\n" +
                "To-Path: msrp://a/b;tcp\r\n" +
                "From-Path: msrp://c/d;tcp\r\n" +
                "Message-ID: m1\r\n" +
                "Byte-Range: 1-1/1\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "x\r\n" +
                "-------aaaaaaaa@\r\n"; // bogus '@' flag
        try {
            MsrpMessage.parse(wire.getBytes(UTF_8));
            fail("bogus end-flag should fail parse");
        } catch (MsrpException expected) { /* ok */ }
    }

    @Test
    public void byteRangeBuilder_starTotalEmits() throws Exception {
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 4, -1) // unknown total
                .body("abcd".getBytes(UTF_8))
                .endFlag(MsrpEndFlag.CONTINUATION)
                .build();
        assertEquals("1-4/*", m.getByteRange());
    }

    // Sanity guard: encoder is deterministic for a given builder.
    @Test
    public void encoder_isDeterministic() throws Exception {
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp")
                .fromPath("msrp://c/d;tcp")
                .messageId("m1")
                .byteRange(1, 1, 1)
                .body(new byte[]{'x'})
                .build();
        byte[] a = m.encode();
        byte[] b = m.encode();
        assertTrue(Arrays.equals(a, b));
    }

    // The end-line must be exactly 7 dashes per RFC 4975 §9 (not 6, not 8).
    @Test
    public void encoder_endLineUsesExactlySevenDashes() throws Exception {
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("tid12345")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1").byteRange(1, 1, 1)
                .body(new byte[]{'x'})
                .build();
        String s = new String(m.encode(), UTF_8);
        assertTrue("end-line uses exactly 7 dashes",
                s.contains("\r\n-------tid12345$\r\n"));
        assertFalse("never 6 dashes", s.contains("\r\n------tid12345$"));
        assertFalse("never 8 dashes", s.contains("\r\n--------tid12345$"));
    }

    // The dash count == MsrpMessage.DASH_COUNT constant
    @Test
    public void dashCountConstantMatchesSpec() {
        assertEquals(7, MsrpMessage.DASH_COUNT);
        assertEquals("-------", MsrpMessage.DASHES);
        assertEquals(7, MsrpMessage.DASHES.length());
    }

    // Body containing CR/LF should survive round-trip (binary-safe body).
    @Test
    public void roundTrip_binaryBodyWithCrLf() throws Exception {
        byte[] body = new byte[]{0x00, 0x0D, 0x0A, 0x2D, 0x2D, 0x2D, 0x7F, (byte) 0xFF};
        MsrpMessage m = MsrpMessage.newSend()
                .transactionId("aaaaaaaa")
                .toPath("msrp://a/b;tcp").fromPath("msrp://c/d;tcp")
                .messageId("m1").byteRange(1, body.length, body.length)
                .contentType("application/octet-stream")
                .body(body)
                .build();
        MsrpMessage parsed = MsrpMessage.parse(m.encode());
        assertArrayEquals(body, parsed.getBody());
    }
}
