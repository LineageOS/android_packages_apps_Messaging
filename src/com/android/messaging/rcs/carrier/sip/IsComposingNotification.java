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
import org.xml.sax.helpers.DefaultHandler;

/**
 * RFC 3994 isComposing XML ({@code application/im-iscomposing+xml}), independent of whether it
 * travels in a pager-mode message or an MSRP session. Only {@code <state>} is required
 * (RFC 3994 §6.1); the other elements are written when set and optional on parse.
 */
public final class IsComposingNotification {

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    public static final String NS_ISCOMPOSING =
            "urn:ietf:params:xml:ns:im-iscomposing";
    public static final String NS_XSI =
            "http://www.w3.org/2001/XMLSchema-instance";
    public static final String SCHEMA_LOCATION =
            "urn:ietf:params:xml:ns:im-composing iscomposing.xsd";

    /** RFC 3994 §5.1. */
    public enum State {
        ACTIVE("active"),
        IDLE("idle");

        public final String token;
        State(String t) { this.token = t; }

        public static State fromToken(String t) {
            if (t == null) return null;
            for (State s : values()) {
                if (s.token.equalsIgnoreCase(t.trim())) return s;
            }
            return null;
        }
    }

    private final State state;
    private final String contentType;
    /** RFC 3339 time of the user's last input. */
    private final String lastActive;
    /** Seconds (RFC 3994 §5.3). */
    private final Integer refreshSec;

    private IsComposingNotification(State state, String contentType,
            String lastActive, Integer refreshSec) {
        this.state = state;
        this.contentType = contentType;
        this.lastActive = lastActive;
        this.refreshSec = refreshSec;
    }

    public State getState()         { return state; }
    public String getContentType()  { return contentType; }
    public String getLastActive()   { return lastActive; }
    public Integer getRefreshSec()  { return refreshSec; }

    public static IsComposingNotification active() {
        return new IsComposingNotification(State.ACTIVE, "text/plain", null, 60);
    }

    public static IsComposingNotification active(String contentType, String lastActive,
            Integer refreshSec) {
        return new IsComposingNotification(State.ACTIVE, contentType, lastActive, refreshSec);
    }

    public static IsComposingNotification idle() {
        return new IsComposingNotification(State.IDLE, null, null, null);
    }

    public static IsComposingNotification of(State state, String contentType,
            String lastActive, Integer refreshSec) {
        if (state == null) throw new IllegalArgumentException("null state");
        return new IsComposingNotification(state, contentType, lastActive, refreshSec);
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sb.append("<isComposing xmlns=\"").append(NS_ISCOMPOSING).append("\"");
        sb.append(" xmlns:xsi=\"").append(NS_XSI).append("\"");
        sb.append(" xsi:schemaLocation=\"").append(SCHEMA_LOCATION).append("\">\r\n");
        sb.append("  <state>").append(state.token).append("</state>\r\n");
        if (contentType != null) {
            sb.append("  <contenttype>").append(escape(contentType)).append("</contenttype>\r\n");
        }
        if (lastActive != null) {
            sb.append("  <lastactive>").append(escape(lastActive)).append("</lastactive>\r\n");
        }
        if (refreshSec != null) {
            sb.append("  <refresh>").append(refreshSec).append("</refresh>\r\n");
        }
        sb.append("</isComposing>\r\n");
        return sb.toString();
    }

    public byte[] toXmlBytes() {
        return toXml().getBytes(UTF_8);
    }

    public static IsComposingNotification parse(byte[] xmlBytes) throws ImdnParseException {
        if (xmlBytes == null) throw new ImdnParseException("null xml");
        return parse(new String(xmlBytes, UTF_8));
    }

    public static IsComposingNotification parse(String xml) throws ImdnParseException {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            try {
                spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) { /* tolerant */ }
            try {
                spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) { /* tolerant */ }

            SAXParser parser = spf.newSAXParser();
            Handler h = new Handler();
            parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(UTF_8))), h);
            if (h.state == null) throw new ImdnParseException("missing <state>");
            return new IsComposingNotification(h.state, h.contentType, h.lastActive, h.refreshSec);
        } catch (ImdnParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ImdnParseException("iscomposing XML parse failure: " + e.getMessage(), e);
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
        if (!(o instanceof IsComposingNotification)) return false;
        IsComposingNotification i = (IsComposingNotification) o;
        return state == i.state
                && Objects.equals(contentType, i.contentType)
                && Objects.equals(lastActive, i.lastActive)
                && Objects.equals(refreshSec, i.refreshSec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, contentType, lastActive, refreshSec);
    }

    @Override
    public String toString() {
        return "IsComposingNotification{state=" + state
                + " ct=" + contentType
                + " lastActive=" + lastActive
                + " refresh=" + refreshSec + "}";
    }

    private static final class Handler extends DefaultHandler {
        State state;
        String contentType;
        String lastActive;
        Integer refreshSec;

        private final StringBuilder chars = new StringBuilder();
        private String capturing;

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes attrs) {
            String name = localName != null && !localName.isEmpty() ? localName : qName;
            chars.setLength(0);
            String lower = name.toLowerCase();
            if ("state".equals(lower) || "contenttype".equals(lower)
                    || "lastactive".equals(lower) || "refresh".equals(lower)) {
                capturing = lower;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturing != null) chars.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (capturing == null) return;
            String value = chars.toString().trim();
            switch (capturing) {
                case "state":       state = State.fromToken(value); break;
                case "contenttype": contentType = value; break;
                case "lastactive":  lastActive = value; break;
                case "refresh":
                    try { refreshSec = Integer.parseInt(value); }
                    catch (NumberFormatException ignored) { /* drop */ }
                    break;
            }
            capturing = null;
        }
    }
}
