/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;

import org.junit.Test;

/** {@link CpimMessage}, the RFC 3862 CPIM body used by the pager and session paths. */
public class CpimMessageTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    // ---- Round-trip

    @Test
    public void roundTrip_simpleText() throws Exception {
        CpimMessage sent = CpimMessage.newText(
                "<tel:+15551234567>",
                "<tel:+15557654321>",
                "msg-001",
                "2026-05-13T10:00:00.000Z",
                "Hello, carrier!");

        byte[] wire = sent.encode();
        CpimMessage parsed = CpimMessage.parse(wire);

        assertEquals("round-trip equality", sent, parsed);
        assertEquals("<tel:+15551234567>", parsed.getFrom());
        assertEquals("<tel:+15557654321>", parsed.getTo());
        assertEquals("msg-001", parsed.getMessageId());
        assertEquals("2026-05-13T10:00:00.000Z", parsed.getDateTime());
        assertEquals(CpimMessage.CT_TEXT_PLAIN_UTF8, parsed.getContentType());
        assertArrayEquals("Hello, carrier!".getBytes(UTF_8), parsed.getPayload());
    }

    @Test
    public void roundTrip_imdnReport() throws Exception {
        String imdnXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>msg-001</message-id>\r\n"
                + "  <datetime>2026-05-13T10:00:01.000Z</datetime>\r\n"
                + "  <delivery-notification>\r\n"
                + "    <status><delivered/></status>\r\n"
                + "  </delivery-notification>\r\n"
                + "</imdn>\r\n";
        CpimMessage sent = CpimMessage.newImdnReport(
                "<tel:+15557654321>",
                "<tel:+15551234567>",
                "imdn-001",
                "2026-05-13T10:00:01.000Z",
                imdnXml);
        byte[] wire = sent.encode();
        CpimMessage parsed = CpimMessage.parse(wire);
        assertEquals(sent, parsed);
        assertEquals(CpimMessage.CT_IMDN_XML, parsed.getContentType());
        assertEquals("notification", parsed.getMimeHeader("Content-Disposition"));
        assertArrayEquals(imdnXml.getBytes(UTF_8), parsed.getPayload());
    }

    // ---- Header order, as peers emit it

    @Test
    public void headerOrder_matchesBugleEmitOrder() throws Exception {
        CpimMessage cpim = CpimMessage.newText(
                "<tel:+15551234567>",
                "<tel:+15557654321>",
                "msg-002",
                "2026-05-13T10:01:00.000Z",
                "x");
        String wire = new String(cpim.encode(), UTF_8);
        int from = wire.indexOf("From:");
        int to = wire.indexOf("To:");
        int ns = wire.indexOf("NS:");
        int mid = wire.indexOf("imdn.Message-ID:");
        int dt = wire.indexOf("DateTime:");
        int disp = wire.indexOf("imdn.Disposition-Notification:");
        assertTrue("From precedes To", from >= 0 && from < to);
        assertTrue("To precedes NS",   to < ns);
        assertTrue("NS precedes imdn.Message-ID", ns < mid);
        assertTrue("imdn.Message-ID precedes DateTime", mid < dt);
        assertTrue("DateTime precedes imdn.Disposition-Notification", dt < disp);
    }

    // ---- RFC 5438 §A.1 example verbatim

    /** The RFC 5438 §A.1 message with a disposition request, parsed as written. */
    @Test
    public void parses_rfc5438_dispositionRequestExample() throws Exception {
        String wire =
                "From: Alice <im:alice@example.com>\r\n"
                + "To: Bob <im:bob@example.com>\r\n"
                + "NS: imdn <urn:ietf:params:imdn>\r\n"
                + "imdn.Message-ID: 34jk324j\r\n"
                + "DateTime: 2026-05-13T10:00:00.000Z\r\n"
                + "imdn.Disposition-Notification: positive-delivery, display\r\n"
                + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Hello Bob!";
        CpimMessage cpim = CpimMessage.parse(wire.getBytes(UTF_8));
        assertEquals("Alice <im:alice@example.com>", cpim.getFrom());
        assertEquals("Bob <im:bob@example.com>", cpim.getTo());
        assertEquals("34jk324j", cpim.getMessageId());
        assertEquals("2026-05-13T10:00:00.000Z", cpim.getDateTime());
        String[] dispos = cpim.getDispositionNotifications();
        assertEquals(2, dispos.length);
        assertEquals("positive-delivery", dispos[0]);
        assertEquals("display", dispos[1]);
        assertArrayEquals("Hello Bob!".getBytes(UTF_8), cpim.getPayload());
    }

    // ---- UTF-8 round-trip

    @Test
    public void utf8_specialCharsInPayload() throws Exception {
        String payload = "Hello 世界! Emoji: 😀 Accents: éèê";
        CpimMessage sent = CpimMessage.newText(
                "<tel:+15551234567>",
                "<tel:+15557654321>",
                "msg-utf8",
                "2026-05-13T10:00:00.000Z",
                payload);
        byte[] wire = sent.encode();
        CpimMessage parsed = CpimMessage.parse(wire);
        assertEquals(payload, new String(parsed.getPayload(), UTF_8));
        assertEquals(sent, parsed);
    }

    @Test
    public void utf8_payloadByteOffsetCorrectWithMultibyteHeaders() throws Exception {
        // A non-ASCII header value must not shift the parser's byte offsets.
        CpimMessage sent = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .addImdnNamespace()
                .messageId("msg-mb-1")
                .dateTime("2026-05-13T10:00:00.000Z")
                .subject("中文")
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("Body!".getBytes(UTF_8))
                .build();
        byte[] wire = sent.encode();
        CpimMessage parsed = CpimMessage.parse(wire);
        assertEquals("中文", parsed.getCpimHeader("Subject"));
        assertArrayEquals("Body!".getBytes(UTF_8), parsed.getPayload());
    }

    // ---- CRLF injection guard

    @Test
    public void rejectsCrlfInHeaderValue() {
        try {
            CpimMessage.newBuilder()
                    .from("<tel:+15551234567>")
                    .to("<tel:+15557654321>")
                    .cpimHeader("X-Malicious", "value\r\nInjected: foo");
            fail("CR/LF in header value must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            CpimMessage.newBuilder()
                    .from("<tel:+15551234567>")
                    .to("<tel:+15557654321>")
                    .mimeHeader("X-Malicious", "value\nInjected: foo");
            fail("Bare LF in header value must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- Case-insensitive header lookup

    @Test
    public void caseInsensitiveHeaderLookup_onParse() throws Exception {
        String wire =
                "FROM: <tel:+15551234567>\r\n"
                + "to: <tel:+15557654321>\r\n"
                + "NS: imdn <urn:ietf:params:imdn>\r\n"
                + "imdn.Message-ID: m1\r\n"
                + "DateTime: 2026-05-13T10:00:00.000Z\r\n"
                + "\r\n"
                + "content-type: text/plain;charset=UTF-8\r\n"
                + "\r\n"
                + "hi";
        CpimMessage cpim = CpimMessage.parse(wire.getBytes(UTF_8));
        assertEquals("<tel:+15551234567>", cpim.getFrom());
        assertEquals("<tel:+15557654321>", cpim.getTo());
        assertEquals("text/plain;charset=UTF-8", cpim.getContentType());
    }

    // ---- Bare LF line endings are accepted

    @Test
    public void parses_bareLfLineEndings() throws Exception {
        String wire =
                "From: <tel:+15551234567>\n"
                + "To: <tel:+15557654321>\n"
                + "NS: imdn <urn:ietf:params:imdn>\n"
                + "imdn.Message-ID: m1\n"
                + "DateTime: 2026-05-13T10:00:00.000Z\n"
                + "\n"
                + "Content-Type: text/plain;charset=UTF-8\n"
                + "\n"
                + "lf-only body";
        CpimMessage cpim = CpimMessage.parse(wire.getBytes(UTF_8));
        assertEquals("m1", cpim.getMessageId());
        assertArrayEquals("lf-only body".getBytes(UTF_8), cpim.getPayload());
    }

    // ---- Content-Length truncation

    @Test
    public void respectsContentLengthWhenSmallerThanRemaining() throws Exception {
        // Bytes past the declared Content-Length are ignored.
        String wire =
                "From: <tel:+15551234567>\r\n"
                + "To: <tel:+15557654321>\r\n"
                + "NS: imdn <urn:ietf:params:imdn>\r\n"
                + "imdn.Message-ID: m1\r\n"
                + "DateTime: 2026-05-13T10:00:00.000Z\r\n"
                + "\r\n"
                + "Content-Type: text/plain;charset=UTF-8\r\n"
                + "Content-Length: 5\r\n"
                + "\r\n"
                + "abcdeXX";
        CpimMessage cpim = CpimMessage.parse(wire.getBytes(UTF_8));
        assertArrayEquals("abcde".getBytes(UTF_8), cpim.getPayload());
    }

    // ---- Required header rejection

    @Test
    public void rejects_missingFromOnBuild() {
        try {
            CpimMessage.newBuilder()
                    .to("<tel:+15557654321>")
                    .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                    .payload("x".getBytes(UTF_8))
                    .build();
            fail("missing From must reject");
        } catch (IllegalStateException expected) { /* ok */ }
    }

    @Test
    public void rejects_missingToOnBuild() {
        try {
            CpimMessage.newBuilder()
                    .from("<tel:+15551234567>")
                    .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                    .payload("x".getBytes(UTF_8))
                    .build();
            fail("missing To must reject");
        } catch (IllegalStateException expected) { /* ok */ }
    }

    @Test
    public void rejects_missingHeaderSeparator() {
        // No blank line between the CPIM and MIME headers.
        String bad = "From: <tel:+15551234567>\r\nTo: <tel:+15557654321>\r\n";
        try {
            CpimMessage.parse(bad.getBytes(UTF_8));
            fail("missing CPIM/MIME separator must throw");
        } catch (CpimParseException expected) { /* ok */ }
    }

    @Test
    public void rejects_headerWithoutColon() {
        String bad =
                "ThisIsNotAHeader\r\n"
                + "To: <tel:+15557654321>\r\n"
                + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "x";
        try {
            CpimMessage.parse(bad.getBytes(UTF_8));
            fail("header without colon must throw");
        } catch (CpimParseException expected) { /* ok */ }
    }

    // ---- Auto-angled URIs

    @Test
    public void from_and_to_autoWrappedInAngleBrackets() {
        CpimMessage cpim = CpimMessage.newBuilder()
                .from("tel:+15551234567")
                .to("tel:+15557654321")
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("x".getBytes(UTF_8))
                .build();
        assertEquals("<tel:+15551234567>", cpim.getFrom());
        assertEquals("<tel:+15557654321>", cpim.getTo());
    }

    // ---- equals / hashCode contract

    @Test
    public void equalsAndHashCode_caseInsensitiveHeaderNames() {
        CpimMessage a = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .cpimHeader("DateTime", "2026-05-13T10:00:00.000Z")
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("x".getBytes(UTF_8))
                .build();
        CpimMessage b = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .cpimHeader("datetime", "2026-05-13T10:00:00.000Z")  // lower case
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("x".getBytes(UTF_8))
                .build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        CpimMessage c = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .cpimHeader("DateTime", "2026-05-13T10:00:00.000Z")
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("y".getBytes(UTF_8))
                .build();
        assertNotEquals(a, c);
    }

    // ---- Disposition-Notification multi-token

    @Test
    public void dispositionNotification_emitsCommaSeparated() {
        CpimMessage cpim = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .addImdnNamespace()
                .messageId("m1")
                .dateTime("2026-05-13T10:00:00.000Z")
                .dispositionNotification(
                        CpimMessage.DISPO_POSITIVE_DELIVERY,
                        CpimMessage.DISPO_DISPLAY)
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("x".getBytes(UTF_8))
                .build();
        assertEquals("positive-delivery, display",
                cpim.getCpimHeader("imdn.Disposition-Notification"));
        assertArrayEquals(
                new String[] {"positive-delivery", "display"},
                cpim.getDispositionNotifications());
    }

    @Test
    public void dispositionNotification_emptyArrayWhenAbsent() {
        CpimMessage cpim = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .messageId("m1")
                .contentType(CpimMessage.CT_TEXT_PLAIN_UTF8)
                .payload("x".getBytes(UTF_8))
                .build();
        assertEquals(0, cpim.getDispositionNotifications().length);
    }

    // ---- Wire bytes structural shape

    @Test
    public void encode_separatesCpimAndMimeWithBlankLine() {
        CpimMessage cpim = CpimMessage.newText(
                "<tel:+15551234567>",
                "<tel:+15557654321>",
                "msg-x",
                "2026-05-13T10:00:00.000Z",
                "Test");
        String wire = new String(cpim.encode(), UTF_8);
        // A blank line after the CPIM headers and another after the MIME headers.
        int firstGap = wire.indexOf("\r\n\r\n");
        int secondGap = wire.indexOf("\r\n\r\n", firstGap + 1);
        assertTrue("first CRLF CRLF present", firstGap > 0);
        assertTrue("second CRLF CRLF present", secondGap > firstGap);
        int ct = wire.indexOf("Content-Type:");
        assertTrue("Content-Type lives between first and second gap",
                ct > firstGap && ct < secondGap);
        int payloadOff = secondGap + 4;
        assertEquals("Test", wire.substring(payloadOff));
    }

    // ---- Empty payload

    @Test
    public void emptyPayloadRoundTrips() throws Exception {
        CpimMessage sent = CpimMessage.newBuilder()
                .from("<tel:+15551234567>")
                .to("<tel:+15557654321>")
                .messageId("empty")
                .contentType("text/plain")
                .payload(new byte[0])
                .build();
        byte[] wire = sent.encode();
        CpimMessage parsed = CpimMessage.parse(wire);
        assertEquals(0, parsed.getPayloadLength());
        assertNotNull(parsed.getPayload());
        assertArrayEquals(new byte[0], parsed.getPayload());
    }
}
