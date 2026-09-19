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
 * RCC.16 <b>§7.13</b> — Encrypted Group Metadata over CPM/SIP.
 *
 * <p>Two XML surfaces, in opposite directions:
 *
 * <ul>
 *   <li><b>Outbound</b> — {@code application/vnd.oma.cpm-groupdata+xml}, the CPM Group Session Data
 *       Management request. §7.13.1 extends {@code target-type} with {@code encrypted-subject} and
 *       {@code encrypted-icon}, and {@code data-type} with the matching payloads.</li>
 *   <li><b>Inbound</b> — {@code conference-info+xml} in a NOTIFY. §7.13.2 extends
 *       {@code conference-description} with {@code encrypted-subject-description} and
 *       {@code encrypted-icon-description}, each carrying a value plus optional
 *       {@code participant} and {@code timestamp}. §7.13.3 adds {@code mls-group-info}.</li>
 * </ul>
 *
 * <h2>Scope, stated honestly</h2>
 *
 * This is the <b>carrier</b> leg. Our device-proven path is Tachyon, where the equivalent is the
 * {@code ChangeGroupProfile} RPC — so nothing here runs against Google today. It is built because
 * the MLS half of the same feature (§9.7.1.4/.5, the key travelling in a PrivateMessage rather than
 * a GroupContext extension) IS transport-independent and does apply to us; see
 * {@link RccGroupMetadataKeys}. The XML is the part that only matters over a Conversation Focus.
 *
 * <h2>Two details that are easy to get wrong</h2>
 *
 * <ol>
 *   <li><b>Deletion is signalled by an EMPTY STRING</b>, not by omitting the element (§7.13.2). So a
 *       parser must distinguish "element absent" from "element present and empty", and collapsing
 *       them means a delete silently does nothing. {@link Description#isDeletion} is that
 *       distinction.</li>
 *   <li>The icon carries a <b>URL</b> to ciphertext on the uploader's HTTP Content Server, while the
 *       subject carries <b>Base64 ciphertext inline</b>. Same element shape, different meaning —
 *       feeding an icon URL to a Base64 decoder yields plausible-looking garbage.</li>
 * </ol>
 *
 * <p>The parser disables external entities and DTDs. This XML arrives from the network, and an
 * XXE in a NOTIFY handler would be a file-disclosure bug in a messaging app.
 */
public final class RccGroupData {

    private RccGroupData() { }

    /** §7.13.1 — the CPM Group Session Data Management content type. */
    public static final String CONTENT_TYPE_GROUPDATA = "application/vnd.oma.cpm-groupdata+xml";
    /** §7.13.2 — the NOTIFY body content type. */
    public static final String CONTENT_TYPE_CONFERENCE_INFO = "application/conference-info+xml";

    /** §7.13.1 {@code target-type} values added by v4.0. */
    public static final String TARGET_ENCRYPTED_SUBJECT = "encrypted-subject";
    public static final String TARGET_ENCRYPTED_ICON = "encrypted-icon";

    /** §7.13.1 {@code action-type} values. */
    public static final String ACTION_SET = "set";
    public static final String ACTION_DELETE = "delete";

    // ---- outbound: the groupdata request -------------------------------------------------------

    /**
     * Build a §7.13.1 Group Session Data Management request.
     *
     * @param targetType {@link #TARGET_ENCRYPTED_SUBJECT} or {@link #TARGET_ENCRYPTED_ICON}
     * @param data       for the subject, the Base64 ciphertext; for the icon, the CS <b>URL</b>.
     *                   Ignored for {@link #ACTION_DELETE}.
     * @param action     {@link #ACTION_SET} or {@link #ACTION_DELETE}
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
            // §9.7.1.6/.7 delete carries no <data>; a set always does, even when the value is empty
            // (an empty ciphertext is a legal, if odd, artefact — an ABSENT one is not).
            final String elem = TARGET_ENCRYPTED_ICON.equals(targetType)
                    ? "encrypted-icon" : "encrypted-subject";
            sb.append("  <data><").append(elem).append('>')
              .append(escape(data == null ? "" : data))
              .append("</").append(elem).append("></data>\n");
        }
        sb.append("</groupdata>\n");
        return sb.toString();
    }

    // ---- inbound: the conference-info NOTIFY ---------------------------------------------------

    /** One §7.13.2 description element. */
    public static final class Description {
        /** The Base64 ciphertext (subject) or the CS URL (icon). Empty means DELETED. */
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
         * §7.13.2 — <b>deletion is an empty string</b>, not an absent element.
         *
         * <p>The caller distinguishes the third case, "no such element at all", by getting
         * {@code null} back from {@link Parsed#encryptedSubject} rather than an empty
         * {@code Description}. Collapsing the two makes a delete do nothing.
         */
        public boolean isDeletion() { return value.isEmpty(); }
    }

    /** What a §7.13.2/.3 NOTIFY body yields. Any field may be {@code null} meaning "not present". */
    public static final class Parsed {
        public final Description encryptedSubject;
        public final Description encryptedIcon;
        /** §7.13.3 {@code <mls-group-info>} — Base64 of the server's latest GroupInfo. */
        public final String mlsGroupInfoBase64;

        Parsed(final Description s, final Description i, final String gi) {
            this.encryptedSubject = s;
            this.encryptedIcon = i;
            this.mlsGroupInfoBase64 = gi;
        }
    }

    /**
     * Parse a {@code conference-info+xml} NOTIFY body for the §7.13 extensions.
     *
     * @return {@code null} if the document is unparseable — a NOTIFY we cannot read must not be
     *         acted on, and returning an empty {@link Parsed} would read as "nothing changed"
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
        if (e == null) return null;   // absent — distinct from present-and-empty
        // The value is the element's own text, excluding the optional child elements.
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
        // Local-name lookup, because the CPM/conference-info namespaces vary between deployments and
        // a prefix-qualified lookup would silently miss a conforming body from a different carrier.
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

    /**
     * Parse with external entities and DTDs OFF.
     *
     * <p>This body arrives from the network. An XXE here would be arbitrary local-file disclosure in
     * a messaging app, triggered by a NOTIFY nobody asked for.
     */
    private static Document safeParse(final String xml) {
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            // NOT XMLConstants.ACCESS_EXTERNAL_{DTD,SCHEMA}: those are JAXP 1.5 and Android's
            // XMLConstants does not define them, so naming them fails to COMPILE against
            // system_current (it built on the host, where the JDK has them — which is exactly the
            // kind of divergence that makes a host-only check misleading). The three SAX features
            // above plus the no-op EntityResolver below are what actually block resolution, and the
            // disallow-doctype-decl feature rejects the DOCTYPE outright before any of it matters.
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
