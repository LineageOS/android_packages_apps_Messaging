/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * RCC.16 §7.13 encrypted group metadata over CPM. Outbound is a Group Session Data Management
 * request ({@code encrypted-subject} / {@code encrypted-icon} targets, §7.13.1); inbound is the
 * conference-info notification's {@code encrypted-subject-description},
 * {@code encrypted-icon-description} (§7.13.2) and {@code mls-group-info} (§7.13.3). A subject
 * value is Base64 ciphertext and an icon value is a Content Server URL; an empty value means
 * deleted. The parser rejects DTDs and external entities.
 */
public final class RccGroupData {

    private RccGroupData() { }

    /** RCC.16 §7.13.1 Group Session Data Management content type. */
    public static final String CONTENT_TYPE_GROUPDATA = "application/vnd.oma.cpm-groupdata+xml";
    /** RCC.16 §7.13.2 conference-info notification content type. */
    public static final String CONTENT_TYPE_CONFERENCE_INFO = "application/conference-info+xml";

    /** RCC.16 §7.13.1 {@code target-type} values (v4.0). */
    public static final String TARGET_ENCRYPTED_SUBJECT = "encrypted-subject";
    public static final String TARGET_ENCRYPTED_ICON = "encrypted-icon";

    /** RCC.16 §7.13.1 {@code action-type} values. */
    public static final String ACTION_SET = "set";
    public static final String ACTION_DELETE = "delete";

    // ---- Outbound: the groupdata request.

    /**
     * Builds an RCC.16 §7.13.1 Group Session Data Management request.
     *
     * @param targetType {@link #TARGET_ENCRYPTED_SUBJECT} or {@link #TARGET_ENCRYPTED_ICON}
     * @param data the Base64 ciphertext (subject) or the Content Server URL (icon); ignored for
     *     {@link #ACTION_DELETE}
     * @param action {@link #ACTION_SET} or {@link #ACTION_DELETE}
     */
    public static String buildGroupData(final String targetType, final String data,
            final String action) {
        if (targetType == null || action == null) return null;
        final StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<groupdata xmlns=\"urn:oma:xml:cpm:groupdata\">\n")
          .append("  <target-type>").append(escape(targetType)).append("</target-type>\n")
          .append("  <action-type>").append(escape(action)).append("</action-type>\n");
        if (ACTION_SET.equals(action)) {
            // A delete has no <data>; a set always has one, even if the value is empty.
            final String elem = TARGET_ENCRYPTED_ICON.equals(targetType)
                    ? "encrypted-icon" : "encrypted-subject";
            sb.append("  <data><").append(elem).append('>')
              .append(escape(data == null ? "" : data))
              .append("</").append(elem).append("></data>\n");
        }
        sb.append("</groupdata>\n");
        return sb.toString();
    }

    // ---- Inbound: the conference-info notification.

    /** One RCC.16 §7.13.2 description element. */
    public static final class Description {
        /** Base64 ciphertext (subject) or Content Server URL (icon); empty means deleted. */
        public final String value;
        /** Optional {@code <participant>} anyURI, or empty. */
        public final String participant;
        /** Optional {@code <timestamp>} dateTime, verbatim, or empty. */
        public final String timestamp;

        Description(final String value, final String participant, final String timestamp) {
            this.value = value == null ? "" : value;
            this.participant = participant == null ? "" : participant;
            this.timestamp = timestamp == null ? "" : timestamp;
        }

        /**
         * Deletion is an empty value. An absent element is a {@code null} {@link Description},
         * which means no change.
         */
        public boolean isDeletion() { return value.isEmpty(); }
    }

    /** The notification's metadata; a {@code null} field was not present. */
    public static final class Parsed {
        public final Description encryptedSubject;
        public final Description encryptedIcon;
        /** RCC.16 §7.13.3 {@code <mls-group-info>}: Base64 of the server's latest GroupInfo. */
        public final String mlsGroupInfoBase64;

        Parsed(final Description s, final Description i, final String gi) {
            this.encryptedSubject = s;
            this.encryptedIcon = i;
            this.mlsGroupInfoBase64 = gi;
        }
    }

    /**
     * Parses a {@code conference-info+xml} body for the §7.13 extensions.
     *
     * @return {@code null} if unparseable, so it is not mistaken for "nothing changed"
     */
    public static Parsed parseConferenceInfo(final String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            final Document doc = safeParse(xml);
            if (doc == null) return null;
            return new Parsed(
                    description(doc, "encrypted-subject-description"),
                    description(doc, "encrypted-icon-description"),
                    firstText(doc, "mls-group-info"));
        } catch (final Throwable t) {
            return null;
        }
    }

    private static Description description(final Document doc, final String localName) {
        final Element e = firstElement(doc, localName);
        if (e == null) return null;  // absent, distinct from present-and-empty
        // The element's own text, excluding the optional child elements.
        final StringBuilder value = new StringBuilder();
        final NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            final Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                value.append(n.getNodeValue());
            }
        }
        return new Description(value.toString().trim(),
                childText(e, "participant"), childText(e, "timestamp"));
    }

    private static Element firstElement(final Document doc, final String localName) {
        // By local name: namespaces vary between deployments.
        final NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            final Node n = all.item(i);
            if (n instanceof Element && localName.equals(localName(n))) return (Element) n;
        }
        return null;
    }

    private static String firstText(final Document doc, final String localName) {
        final Element e = firstElement(doc, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    private static String childText(final Element parent, final String localName) {
        final NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            final Node n = kids.item(i);
            if (n instanceof Element && localName.equals(localName(n))) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static String localName(final Node n) {
        final String ln = n.getLocalName();
        if (ln != null) return ln;
        final String name = n.getNodeName();
        final int c = name.indexOf(':');
        return c < 0 ? name : name.substring(c + 1);
    }

    /** Parses with DTDs and external entities disabled; the body comes from the network. */
    private static Document safeParse(final String xml) {
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            // XMLConstants.ACCESS_EXTERNAL_* is not available on Android; the features above and
            // the no-op EntityResolver below block resolution.
            f.setNamespaceAware(true);
            final DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(
                    new java.io.StringReader("")));
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (final Throwable t) {
            return null;
        }
    }

    private static String escape(final String s) {
        if (s == null) return "";
        final StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
