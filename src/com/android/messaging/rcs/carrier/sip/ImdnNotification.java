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

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Objects;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * RFC 5438 IMDN ("Message Disposition Notification") XML builder and parser.
 * Used as the payload of a CPIM body whose inner {@code Content-Type} is
 * {@code message/imdn+xml}.
 *
 * <p>Carrier-RCS uses IMDN reports for two user-visible signals: <b>delivered</b>
 * (the recipient's terminal accepted the message) and <b>displayed</b> (the
 * recipient's user actually saw the message — "read receipt"). RFC 5438 also
 * defines a <b>processed</b> status and a <b>failed</b> status for completeness.
 *
 * <p>XML shape per RFC 5438 §6:
 *
 * <pre>
 *   &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 *   &lt;imdn xmlns="urn:ietf:params:xml:ns:imdn"&gt;
 *     &lt;message-id&gt;{originalId}&lt;/message-id&gt;
 *     &lt;datetime&gt;{RFC3339-ts}&lt;/datetime&gt;
 *     &lt;delivery-notification&gt;
 *       &lt;status&gt;&lt;delivered/&gt;&lt;/status&gt;
 *     &lt;/delivery-notification&gt;
 *   &lt;/imdn&gt;
 * </pre>
 *
 * <p>The {@code delivery-notification} vs {@code display-notification} vs
 * {@code processing-notification} wrapper picks the notification class.
 * Status elements are self-closing tokens:
 * {@code <delivered/>}, {@code <displayed/>}, {@code <processed/>},
 * {@code <failed/>}, {@code <error/>}.
 *
 * <p>Pure Java (uses {@code javax.xml.parsers.SAXParser} which is available
 * on both the Android runtime and the host JVM) — no Android deps.
 */
public final class ImdnNotification {

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /** RFC 5438 §6 XML namespace. */
    public static final String NS_IMDN = "urn:ietf:params:xml:ns:imdn";

    /** RFC 5438 §6 notification classes. */
    public enum NotificationClass {
        DELIVERY("delivery-notification"),
        DISPLAY("display-notification"),
        PROCESSING("processing-notification");

        public final String elementName;
        NotificationClass(String n) { this.elementName = n; }

        public static NotificationClass fromElementName(String name) {
            for (NotificationClass c : values()) {
                if (c.elementName.equalsIgnoreCase(name)) return c;
            }
            return null;
        }
    }

    /**
     * RFC 5438 §6 status tokens. Note these are the *element* tokens — the
     * actual XML wraps them in {@code <status><{token}/></status>}.
     */
    public enum Status {
        DELIVERED("delivered"),
        DISPLAYED("displayed"),
        PROCESSED("processed"),
        FAILED("failed"),
        ERROR("error"),
        FORBIDDEN("forbidden");

        public final String token;
        Status(String t) { this.token = t; }

        public static Status fromToken(String token) {
            if (token == null) return null;
            for (Status s : values()) {
                if (s.token.equalsIgnoreCase(token)) return s;
            }
            return null;
        }
    }

    private final String messageId;
    private final String dateTime;
    private final NotificationClass cls;
    private final Status status;

    private ImdnNotification(String messageId, String dateTime,
            NotificationClass cls, Status status) {
        this.messageId = messageId;
        this.dateTime = dateTime;
        this.cls = cls;
        this.status = status;
    }

    public String getMessageId()       { return messageId; }
    public String getDateTime()        { return dateTime; }
    public NotificationClass getNotificationClass() { return cls; }
    public Status getStatus()          { return status; }

    public static ImdnNotification newDelivered(String messageId, String dateTime) {
        return new ImdnNotification(messageId, dateTime,
                NotificationClass.DELIVERY, Status.DELIVERED);
    }

    public static ImdnNotification newDisplayed(String messageId, String dateTime) {
        return new ImdnNotification(messageId, dateTime,
                NotificationClass.DISPLAY, Status.DISPLAYED);
    }

    public static ImdnNotification newProcessed(String messageId, String dateTime) {
        return new ImdnNotification(messageId, dateTime,
                NotificationClass.PROCESSING, Status.PROCESSED);
    }

    public static ImdnNotification newFailed(String messageId, String dateTime,
            NotificationClass cls) {
        return new ImdnNotification(messageId, dateTime, cls, Status.FAILED);
    }

    public static ImdnNotification of(String messageId, String dateTime,
            NotificationClass cls, Status status) {
        if (messageId == null) throw new IllegalArgumentException("null messageId");
        if (dateTime == null)  throw new IllegalArgumentException("null dateTime");
        if (cls == null)       throw new IllegalArgumentException("null notificationClass");
        if (status == null)    throw new IllegalArgumentException("null status");
        return new ImdnNotification(messageId, dateTime, cls, status);
    }

    // ---------- encode ----------

    /**
     * Serialize as RFC 5438 §6 XML. Output begins with the standard
     * {@code <?xml ...?>} declaration so the receiving stack can route on
     * MIME alone. Always UTF-8.
     *
     * <p>The XML is built by hand rather than via DOM because the structure
     * is fully static (four element types, all simple character content)
     * and the host build target {@code java_library_host} should avoid
     * pulling javax.xml.transform.* with its Xerces fallback noise.
     */
    public String toXml() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sb.append("<imdn xmlns=\"").append(NS_IMDN).append("\">\r\n");
        sb.append("  <message-id>").append(escape(messageId)).append("</message-id>\r\n");
        sb.append("  <datetime>").append(escape(dateTime)).append("</datetime>\r\n");
        sb.append("  <").append(cls.elementName).append(">\r\n");
        sb.append("    <status><").append(status.token).append("/></status>\r\n");
        sb.append("  </").append(cls.elementName).append(">\r\n");
        sb.append("</imdn>\r\n");
        return sb.toString();
    }

    /** Convenience: UTF-8 bytes ready to drop into a CPIM payload. */
    public byte[] toXmlBytes() {
        return toXml().getBytes(UTF_8);
    }

    /**
     * Parse an IMDN XML body. Expects the wrapping {@code <imdn>} element
     * with one of the three notification-class children and a
     * {@code <status>} grandchild. Returns {@code null} fields if elements
     * are missing — caller decides whether to reject.
     *
     * <p>Tolerant of: missing XML declaration, leading whitespace, mixed
     * indentation, and the legacy short tag for status (e.g.
     * {@code <delivered/>} as a direct child of the notification element,
     * which some GSMA UP 1.0 implementations emitted without the
     * {@code <status>} wrapper).
     */
    public static ImdnNotification parse(byte[] xmlBytes) throws ImdnParseException {
        if (xmlBytes == null) throw new ImdnParseException("null xml");
        return parse(new String(xmlBytes, UTF_8));
    }

    public static ImdnNotification parse(String xml) throws ImdnParseException {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            // Disable external entities; CPIM payloads are untrusted input.
            try {
                spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // not all SAX impls support this; ignore on the host build
            }
            try {
                spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) { /* tolerant */ }

            SAXParser parser = spf.newSAXParser();
            Handler h = new Handler();
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(UTF_8))), h);
            if (h.messageId == null) throw new ImdnParseException("missing <message-id>");
            if (h.dateTime == null)  throw new ImdnParseException("missing <datetime>");
            if (h.cls == null)       throw new ImdnParseException("missing notification element");
            if (h.status == null)    throw new ImdnParseException("missing status token");
            return new ImdnNotification(h.messageId, h.dateTime, h.cls, h.status);
        } catch (ImdnParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ImdnParseException("XML parse failure: " + e.getMessage(), e);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '&':  sb.append("&amp;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- equality ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImdnNotification)) return false;
        ImdnNotification i = (ImdnNotification) o;
        return Objects.equals(messageId, i.messageId)
                && Objects.equals(dateTime, i.dateTime)
                && cls == i.cls && status == i.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, dateTime, cls, status);
    }

    @Override
    public String toString() {
        return "ImdnNotification{mid=" + messageId + " dt=" + dateTime
                + " cls=" + cls + " status=" + status + "}";
    }

    // ---------- SAX handler ----------

    private static final class Handler extends DefaultHandler {
        String messageId;
        String dateTime;
        NotificationClass cls;
        Status status;

        private final StringBuilder chars = new StringBuilder();
        private String capturing;

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes attrs) {
            String name = localName != null && !localName.isEmpty() ? localName : qName;
            chars.setLength(0);

            // Notification-class elements are direct children of <imdn>.
            NotificationClass nc = NotificationClass.fromElementName(name);
            if (nc != null) {
                this.cls = nc;
                return;
            }
            // Status-token elements: <delivered/>, <displayed/>, etc. Per RFC
            // 5438 §6 these appear inside <status>, but we accept them as
            // direct children of the notification element too (UP 1.0 quirk).
            Status s = Status.fromToken(name);
            if (s != null) {
                this.status = s;
                return;
            }
            // Otherwise capture text content for known string-bearing elements.
            if ("message-id".equalsIgnoreCase(name) || "datetime".equalsIgnoreCase(name)) {
                capturing = name.toLowerCase();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturing != null) {
                chars.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName != null && !localName.isEmpty() ? localName : qName;
            if ("message-id".equalsIgnoreCase(name)) {
                this.messageId = chars.toString().trim();
                capturing = null;
            } else if ("datetime".equalsIgnoreCase(name)) {
                this.dateTime = chars.toString().trim();
                capturing = null;
            }
        }
    }
}
