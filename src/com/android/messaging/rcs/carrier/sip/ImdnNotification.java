/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * RFC 5438 IMDN XML ({@code message/imdn+xml}): a {@code message-id}, a {@code datetime}, and a
 * notification-class element holding {@code <status><token/></status>}.
 */
public final class ImdnNotification {

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    public static final String NS_IMDN = "urn:ietf:params:xml:ns:imdn";

    /** RFC 5438 §6. */
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

    /** RFC 5438 §6 status tokens, written as {@code <status><token/></status>}. */
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

    /** RFC 5438 §6 XML with an XML declaration, in UTF-8. Built by hand: the shape is fixed. */
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

    public byte[] toXmlBytes() {
        return toXml().getBytes(UTF_8);
    }

    /**
     * Parses an IMDN body. Also accepts a status token directly under the notification element,
     * without the {@code <status>} wrapper, as older implementations send it.
     *
     * @throws ImdnParseException if the message id, datetime, class or status is missing
     */
    public static ImdnNotification parse(byte[] xmlBytes) throws ImdnParseException {
        if (xmlBytes == null) throw new ImdnParseException("null xml");
        return parse(new String(xmlBytes, UTF_8));
    }

    public static ImdnNotification parse(String xml) throws ImdnParseException {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            // Payloads are untrusted: no DTDs or external entities.
            try {
                spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // not supported by every SAX implementation
            }
            try {
                spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) { }

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

            NotificationClass nc = NotificationClass.fromElementName(name);
            if (nc != null) {
                this.cls = nc;
                return;
            }
            // RFC 5438 §6 puts status tokens inside <status>; accept them directly under the class
            // too.
            Status s = Status.fromToken(name);
            if (s != null) {
                this.status = s;
                return;
            }
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
