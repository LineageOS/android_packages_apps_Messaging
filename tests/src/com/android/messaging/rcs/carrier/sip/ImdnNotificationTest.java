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
package com.android.messaging.rcs.carrier.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;

import org.junit.Test;

/**
 * Unit tests for {@link ImdnNotification} — RFC 5438 IMDN XML
 * builder/parser.
 *
 * <p>Test grouping:
 * <ul>
 *   <li>delivered / displayed / processed / failed round-trips</li>
 *   <li>RFC 5438 §6 verbatim XML parse</li>
 *   <li>UP 1.0 short-form status (no {@code <status>} wrapper) accept</li>
 *   <li>special chars in message-id escape correctly</li>
 *   <li>missing element rejection</li>
 *   <li>equality contract</li>
 * </ul>
 */
public class ImdnNotificationTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    // ============================================================
    // Round-trips
    // ============================================================

    @Test
    public void roundTrip_delivered() throws Exception {
        ImdnNotification sent = ImdnNotification.newDelivered(
                "msg-001", "2026-05-13T10:00:01.000Z");
        ImdnNotification parsed = ImdnNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(ImdnNotification.NotificationClass.DELIVERY,
                parsed.getNotificationClass());
        assertEquals(ImdnNotification.Status.DELIVERED, parsed.getStatus());
        assertEquals("msg-001", parsed.getMessageId());
        assertEquals("2026-05-13T10:00:01.000Z", parsed.getDateTime());
    }

    @Test
    public void roundTrip_displayed() throws Exception {
        ImdnNotification sent = ImdnNotification.newDisplayed(
                "msg-002", "2026-05-13T10:00:02.000Z");
        ImdnNotification parsed = ImdnNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(ImdnNotification.NotificationClass.DISPLAY,
                parsed.getNotificationClass());
        assertEquals(ImdnNotification.Status.DISPLAYED, parsed.getStatus());
    }

    @Test
    public void roundTrip_processed() throws Exception {
        ImdnNotification sent = ImdnNotification.newProcessed(
                "msg-003", "2026-05-13T10:00:03.000Z");
        ImdnNotification parsed = ImdnNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(ImdnNotification.NotificationClass.PROCESSING,
                parsed.getNotificationClass());
        assertEquals(ImdnNotification.Status.PROCESSED, parsed.getStatus());
    }

    @Test
    public void roundTrip_failedDelivery() throws Exception {
        ImdnNotification sent = ImdnNotification.newFailed(
                "msg-004", "2026-05-13T10:00:04.000Z",
                ImdnNotification.NotificationClass.DELIVERY);
        ImdnNotification parsed = ImdnNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(ImdnNotification.NotificationClass.DELIVERY,
                parsed.getNotificationClass());
        assertEquals(ImdnNotification.Status.FAILED, parsed.getStatus());
    }

    // ============================================================
    // RFC 5438 §6 verbatim example
    // ============================================================

    /**
     * RFC 5438 §6 Figure 4: delivery notification ("delivered" status).
     * Reproduced verbatim from the RFC for parser-correctness assurance.
     */
    @Test
    public void parses_rfc5438_section6_deliveredExample() throws Exception {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>34jk324j</message-id>\r\n"
                + "  <datetime>2008-04-04T12:16:49-05:00</datetime>\r\n"
                + "  <recipient-uri>im:bob@example.com</recipient-uri>\r\n"
                + "  <original-recipient-uri>im:bob@example.com</original-recipient-uri>\r\n"
                + "  <subject>Project Tomorrow</subject>\r\n"
                + "  <delivery-notification>\r\n"
                + "    <status>\r\n"
                + "      <delivered/>\r\n"
                + "    </status>\r\n"
                + "  </delivery-notification>\r\n"
                + "</imdn>\r\n";
        ImdnNotification n = ImdnNotification.parse(xml);
        assertEquals("34jk324j", n.getMessageId());
        assertEquals("2008-04-04T12:16:49-05:00", n.getDateTime());
        assertEquals(ImdnNotification.NotificationClass.DELIVERY,
                n.getNotificationClass());
        assertEquals(ImdnNotification.Status.DELIVERED, n.getStatus());
    }

    /** RFC 5438 §6 Figure 5: display notification ("displayed" status). */
    @Test
    public void parses_rfc5438_section6_displayedExample() throws Exception {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>34jk324j</message-id>\r\n"
                + "  <datetime>2008-04-04T12:16:49-05:00</datetime>\r\n"
                + "  <display-notification>\r\n"
                + "    <status>\r\n"
                + "      <displayed/>\r\n"
                + "    </status>\r\n"
                + "  </display-notification>\r\n"
                + "</imdn>\r\n";
        ImdnNotification n = ImdnNotification.parse(xml);
        assertEquals(ImdnNotification.NotificationClass.DISPLAY,
                n.getNotificationClass());
        assertEquals(ImdnNotification.Status.DISPLAYED, n.getStatus());
    }

    // ============================================================
    // UP 1.0 short-form tolerance
    // ============================================================

    /**
     * Some GSMA UP 1.0 implementations skipped the {@code <status>} wrapper
     * and put the status element directly under the notification element.
     * Our parser accepts both shapes.
     */
    @Test
    public void parses_up10_shortFormStatus_noStatusWrapper() throws Exception {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>m1</message-id>\r\n"
                + "  <datetime>2026-05-13T10:00:00.000Z</datetime>\r\n"
                + "  <delivery-notification>\r\n"
                + "    <delivered/>\r\n"
                + "  </delivery-notification>\r\n"
                + "</imdn>\r\n";
        ImdnNotification n = ImdnNotification.parse(xml);
        assertEquals(ImdnNotification.NotificationClass.DELIVERY,
                n.getNotificationClass());
        assertEquals(ImdnNotification.Status.DELIVERED, n.getStatus());
    }

    // ============================================================
    // XML escaping
    // ============================================================

    @Test
    public void escapes_specialCharsInMessageId() throws Exception {
        // Message ID containing characters that need XML escape.
        String mid = "weird<&>\"'id";
        ImdnNotification sent = ImdnNotification.newDelivered(
                mid, "2026-05-13T10:00:00.000Z");
        String xml = sent.toXml();
        // Raw special chars must be escaped on emit.
        assertTrue("less-than escaped", xml.contains("&lt;"));
        assertTrue("ampersand escaped", xml.contains("&amp;"));
        // Round-trip through parse restores the original value.
        ImdnNotification parsed = ImdnNotification.parse(xml);
        assertEquals(mid, parsed.getMessageId());
    }

    // ============================================================
    // Missing-element rejection
    // ============================================================

    @Test
    public void rejects_missingMessageId() {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <datetime>2026-05-13T10:00:00.000Z</datetime>\r\n"
                + "  <delivery-notification><status><delivered/></status></delivery-notification>\r\n"
                + "</imdn>\r\n";
        try {
            ImdnNotification.parse(xml);
            fail("missing <message-id> must throw");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    @Test
    public void rejects_missingDateTime() {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>m1</message-id>\r\n"
                + "  <delivery-notification><status><delivered/></status></delivery-notification>\r\n"
                + "</imdn>\r\n";
        try {
            ImdnNotification.parse(xml);
            fail("missing <datetime> must throw");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    @Test
    public void rejects_malformedXml() {
        try {
            ImdnNotification.parse("not xml at all");
            fail("malformed XML must throw");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    @Test
    public void rejects_doctype_xxeSafety() {
        // External-entity DOCTYPE should be rejected by our SAX parser config
        // (disallow-doctype-decl=true). This protects against XXE attacks.
        String xml =
                "<?xml version=\"1.0\"?>\r\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "  <message-id>&xxe;</message-id>\r\n"
                + "</imdn>\r\n";
        try {
            ImdnNotification.parse(xml);
            fail("DOCTYPE must be rejected");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    // ============================================================
    // Equality
    // ============================================================

    @Test
    public void equality_distinguishesClassAndStatus() {
        ImdnNotification a = ImdnNotification.newDelivered("m1", "2026-05-13T10:00:00.000Z");
        ImdnNotification b = ImdnNotification.newDelivered("m1", "2026-05-13T10:00:00.000Z");
        ImdnNotification c = ImdnNotification.newDisplayed("m1", "2026-05-13T10:00:00.000Z");
        ImdnNotification d = ImdnNotification.newDelivered("m2", "2026-05-13T10:00:00.000Z");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
    }

    @Test
    public void toXmlBytes_isUtf8() {
        ImdnNotification n = ImdnNotification.newDelivered("m1", "2026-05-13T10:00:00.000Z");
        byte[] bytes = n.toXmlBytes();
        // Compare against String.getBytes(UTF-8).
        byte[] expected = n.toXml().getBytes(UTF_8);
        assertEquals(expected.length, bytes.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte " + i, expected[i], bytes[i]);
        }
    }
}
