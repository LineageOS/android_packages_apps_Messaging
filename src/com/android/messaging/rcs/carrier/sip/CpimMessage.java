/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * RFC 3862 CPIM builder and parser, with the RFC 5438 imdn namespace: CPIM
 * headers, a blank line, inner MIME headers, a blank line, the payload. Carried in pager-mode SIP
 * messages and MSRP SENDs. Instances are immutable; the builder is not thread-safe. Headers are
 * UTF-8 (RFC 3862 §3).
 */
public final class CpimMessage {

    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final byte[] CRLF = {'\r', '\n'};

    /** RFC 5438 §2. */
    public static final String IMDN_NAMESPACE_URI = "urn:ietf:params:imdn";
    /** RFC 5438 §2, declared with {@code NS: imdn <...>}. */
    public static final String IMDN_NAMESPACE_PREFIX = "imdn";

    public static final String HDR_FROM            = "From";
    public static final String HDR_TO              = "To";
    public static final String HDR_CC              = "cc";
    public static final String HDR_DATETIME        = "DateTime";
    public static final String HDR_SUBJECT         = "Subject";
    public static final String HDR_NS              = "NS";
    public static final String HDR_IMDN_MESSAGE_ID = "imdn.Message-ID";
    public static final String HDR_IMDN_DISPO_NOTIF = "imdn.Disposition-Notification";

    public static final String MIME_CONTENT_TYPE        = "Content-Type";
    public static final String MIME_CONTENT_DISPOSITION = "Content-Disposition";
    public static final String MIME_CONTENT_LENGTH      = "Content-Length";

    public static final String CT_TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";
    public static final String CT_IMDN_XML        = "message/imdn+xml";
    public static final String CT_ISCOMPOSING_XML = "application/im-iscomposing+xml";
    public static final String CT_FT_HTTP_XML     = "application/vnd.gsma.rcs-ft-http+xml";
    public static final String CT_VND_GOOGLE_RCS_ENCRYPTED =
            "application/vnd.google.rcs.encrypted";

    /** RFC 5438 §3.1. */
    public static final String DISPO_POSITIVE_DELIVERY = "positive-delivery";
    public static final String DISPO_NEGATIVE_DELIVERY = "negative-delivery";
    public static final String DISPO_DISPLAY           = "display";
    public static final String DISPO_PROCESSING        = "processing";

    /** RFC 3323 §4.1. */
    public static final String ANONYMOUS_URI = "<sip:anonymous@anonymous.invalid>";

    private final LinkedHashMap<String, String> cpimHeaders;
    private final LinkedHashMap<String, String> mimeHeaders;
    /** Declared namespaces, in order; NS repeats, so it is kept out of the name-keyed map. */
    private final java.util.List<String> namespaces;
    private final byte[] payload;

    private CpimMessage(Builder b) {
        this.cpimHeaders = new LinkedHashMap<>(b.cpimHeaders);
        this.mimeHeaders = new LinkedHashMap<>(b.mimeHeaders);
        this.namespaces = new java.util.ArrayList<>(b.namespaces);
        this.payload = b.payload == null ? new byte[0] : b.payload.clone();
    }

    /** Case-insensitive. */
    public String getCpimHeader(String name) {
        return findCi(cpimHeaders, name);
    }

    /** Case-insensitive. */
    public String getMimeHeader(String name) {
        return findCi(mimeHeaders, name);
    }

    public String getFrom()       { return getCpimHeader(HDR_FROM); }
    public String getTo()         { return getCpimHeader(HDR_TO); }
    public String getDateTime()   { return getCpimHeader(HDR_DATETIME); }
    public String getMessageId()  { return getCpimHeader(HDR_IMDN_MESSAGE_ID); }
    public String getContentType(){ return getMimeHeader(MIME_CONTENT_TYPE); }

    /** A copy, never null. */
    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() { return payload.length; }

    /** The {@code imdn.Disposition-Notification} tokens (RFC 5438 §5.1), or an empty array. */
    public String[] getDispositionNotifications() {
        String raw = getCpimHeader(HDR_IMDN_DISPO_NOTIF);
        if (raw == null) return new String[0];
        String[] parts = raw.split(",");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].trim();
        return out;
    }

    /**
     * The wire form, with no CRLF after the payload; the enclosing framing adds any. Values are not
     * checked for CR/LF here; the {@link Builder} rejects them.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                64 + cpimHeaders.size() * 32 + mimeHeaders.size() * 32 + payload.length);
        // NS goes just before the first prefixed header: the prefix is bound before use, and the
        // order (From, To, NS, imdn.Message-ID, DateTime, imdn.Disposition-Notification) matches
        // what peers send. Header order is visible on the wire.
        boolean nsWritten = false;
        for (Map.Entry<String, String> e : cpimHeaders.entrySet()) {
            if (!nsWritten && e.getKey().indexOf('.') >= 0) {
                for (String ns : namespaces) {
                    writeHeader(out, HDR_NS, ns);
                }
                nsWritten = true;
            }
            writeHeader(out, e.getKey(), e.getValue());
        }
        if (!nsWritten) {
            // No prefixed header: declare anyway, so a namespace is never dropped.
            for (String ns : namespaces) {
                writeHeader(out, HDR_NS, ns);
            }
        }
        out.write(CRLF, 0, CRLF.length);
        for (Map.Entry<String, String> e : mimeHeaders.entrySet()) {
            writeHeader(out, e.getKey(), e.getValue());
        }
        out.write(CRLF, 0, CRLF.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, String name, String value) {
        byte[] nb = name.getBytes(UTF_8);
        byte[] vb = value.getBytes(UTF_8);
        out.write(nb, 0, nb.length);
        out.write(':');
        out.write(' ');
        out.write(vb, 0, vb.length);
        out.write(CRLF, 0, CRLF.length);
    }

    /**
     * Parses a CPIM frame. Both blank-line separators are required; header-name case and bare-LF
     * line endings are tolerated.
     *
     * @throws CpimParseException if the input is not a CPIM frame
     */
    public static CpimMessage parse(byte[] wire) throws CpimParseException {
        if (wire == null) throw new CpimParseException("null wire");
        // Decoded as UTF-8 to find the blocks; the payload is copied from the original bytes.
        String text = new String(wire, UTF_8);

        int cpimEnd = findHeaderBlockEnd(text, 0);
        if (cpimEnd < 0) throw new CpimParseException("missing CPIM/MIME separator");
        int mimeStart = nextLine(text, cpimEnd);
        int mimeEnd = findHeaderBlockEnd(text, mimeStart);
        if (mimeEnd < 0) throw new CpimParseException("missing MIME/payload separator");
        int payloadStart = nextLine(text, mimeEnd);

        Builder b = new Builder();
        parseHeaderLines(text.substring(0, cpimEnd), b.cpimHeaders);
        // NS repeats (RFC 5438 §2) and must not stay in the last-value-wins map; move every
        // declaration, in wire order, to the namespace list.
        {
            final String ns = b.cpimHeaders.remove(HDR_NS);
            if (ns != null) {
                b.addNamespace(ns);
            }
            for (final String line : text.substring(0, cpimEnd).split("\r?\n")) {
                final int c = line.indexOf(':');
                if (c > 0 && HDR_NS.equalsIgnoreCase(line.substring(0, c).trim())) {
                    b.addNamespace(line.substring(c + 1).trim());   // idempotent
                }
            }
        }
        parseHeaderLines(text.substring(mimeStart, mimeEnd), b.mimeHeaders);

        // The payload's byte offset is the UTF-8 length of the text before it.
        int payloadByteOffset = text.substring(0, payloadStart).getBytes(UTF_8).length;
        int len = wire.length - payloadByteOffset;
        // A Content-Length shorter than the remaining bytes trims transport padding; it never grows
        // the payload.
        String cl = findCi(b.mimeHeaders, MIME_CONTENT_LENGTH);
        if (cl != null) {
            try {
                int n = Integer.parseInt(cl.trim());
                if (n >= 0 && n < len) len = n;
            } catch (NumberFormatException ignored) {
                // tolerate a malformed Content-Length
            }
        }
        byte[] payload = new byte[len];
        System.arraycopy(wire, payloadByteOffset, payload, 0, len);
        b.payload = payload;
        return new CpimMessage(b);
    }

    /** The index of the blank line (CRLF CRLF or LF LF) ending a header block, or -1. */
    private static int findHeaderBlockEnd(String s, int from) {
        int crlfCrlf = s.indexOf("\r\n\r\n", from);
        int lfLf     = s.indexOf("\n\n", from);
        if (crlfCrlf < 0)      return lfLf;
        if (lfLf < 0)          return crlfCrlf;
        return Math.min(crlfCrlf, lfLf);
    }

    /** The start of the block after the blank line at {@code idx}. */
    private static int nextLine(String s, int idx) {
        if (idx + 4 <= s.length() && s.charAt(idx) == '\r' && s.charAt(idx + 1) == '\n'
                && s.charAt(idx + 2) == '\r' && s.charAt(idx + 3) == '\n') {
            return idx + 4;
        }
        if (idx + 2 <= s.length() && s.charAt(idx) == '\n' && s.charAt(idx + 1) == '\n') {
            return idx + 2;
        }
        return idx;
    }

    private static void parseHeaderLines(String block, LinkedHashMap<String, String> out)
            throws CpimParseException {
        int p = 0;
        while (p < block.length()) {
            int eol = findEol(block, p);
            String line = block.substring(p, eol);
            if (!line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    throw new CpimParseException("header without colon: " + line);
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.isEmpty()) {
                    throw new CpimParseException("empty header name");
                }
                // Last value wins for a repeated name.
                out.put(name, value);
            }
            p = eol;
            if (p < block.length() && block.charAt(p) == '\r') p++;
            if (p < block.length() && block.charAt(p) == '\n') p++;
        }
    }

    private static int findEol(String s, int from) {
        int cr = s.indexOf('\r', from);
        int lf = s.indexOf('\n', from);
        if (cr < 0 && lf < 0) return s.length();
        if (cr < 0) return lf;
        if (lf < 0) return cr;
        return Math.min(cr, lf);
    }

    private static String findCi(LinkedHashMap<String, String> map, String name) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CpimMessage)) return false;
        CpimMessage c = (CpimMessage) o;
        return mapEqualsCi(cpimHeaders, c.cpimHeaders)
                && mapEqualsCi(mimeHeaders, c.mimeHeaders)
                // The namespaces bind the other headers' prefixes, so they are part of equality.
                && namespaces.equals(c.namespaces)
                && java.util.Arrays.equals(payload, c.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toLowerKeys(cpimHeaders), toLowerKeys(mimeHeaders),
                namespaces,   // must track equals()
                java.util.Arrays.hashCode(payload));
    }

    private static boolean mapEqualsCi(Map<String, String> a, Map<String, String> b) {
        if (a.size() != b.size()) return false;
        return toLowerKeys(a).equals(toLowerKeys(b));
    }

    private static LinkedHashMap<String, String> toLowerKeys(Map<String, String> m) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(m.size() * 2);
        for (Map.Entry<String, String> e : m.entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    @Override
    public String toString() {
        return "CpimMessage{from=" + getFrom() + " to=" + getTo()
                + " mid=" + getMessageId()
                + " ct=" + getContentType()
                + " payload=" + payload.length + "B}";
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * A 1:1 chat text body requesting delivery and display receipts. Angle brackets are added to
     * the URIs if missing.
     *
     * @param messageId the id receipts will reference
     * @param dateTime RFC 3339, as from {@link CpimDateTime#now()}
     */
    public static CpimMessage newText(String fromUri, String toUri,
            String messageId, String dateTime, String text) {
        return newBuilder()
                .from(fromUri)
                .to(toUri)
                .addImdnNamespace()
                .messageId(messageId)
                .dateTime(dateTime)
                .dispositionNotification(
                        DISPO_POSITIVE_DELIVERY, DISPO_DISPLAY)
                .contentType(CT_TEXT_PLAIN_UTF8)
                .payload(text.getBytes(UTF_8))
                .build();
    }

    /**
     * An IMDN body with {@code Content-Disposition: notification} (RFC 5438 §3). It requests no
     * receipt of its own.
     *
     * @param toUri the sender of the message being acknowledged
     * @param messageId a new id for the receipt itself
     */
    public static CpimMessage newImdnReport(String fromUri, String toUri,
            String messageId, String dateTime, String imdnXml) {
        return newBuilder()
                .from(fromUri)
                .to(toUri)
                .addImdnNamespace()
                .messageId(messageId)
                .dateTime(dateTime)
                .contentType(CT_IMDN_XML)
                .contentDisposition("notification")
                .payload(imdnXml.getBytes(UTF_8))
                .build();
    }

    public static final class Builder {
        // Insertion order is wire order.
        private final LinkedHashMap<String, String> cpimHeaders = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> mimeHeaders = new LinkedHashMap<>();
        /** See {@link #addNamespace}. */
        private final java.util.List<String> namespaces = new java.util.ArrayList<>();
        private byte[] payload;

        public Builder cpimHeader(String name, String value) {
            putHeader(cpimHeaders, name, value);
            return this;
        }

        public Builder mimeHeader(String name, String value) {
            putHeader(mimeHeaders, name, value);
            return this;
        }

        public Builder from(String uri)         { return ensureAngled(HDR_FROM, uri); }
        public Builder to(String uri)           { return ensureAngled(HDR_TO, uri); }
        public Builder dateTime(String rfc3339) { return cpimHeader(HDR_DATETIME, rfc3339); }
        public Builder messageId(String id)     { return cpimHeader(HDR_IMDN_MESSAGE_ID, id); }
        public Builder subject(String s)        { return cpimHeader(HDR_SUBJECT, s); }

        /** {@code NS: imdn <urn:ietf:params:imdn>}. */
        public Builder addImdnNamespace() {
            return addNamespace(IMDN_NAMESPACE_PREFIX + " <" + IMDN_NAMESPACE_URI + ">");
        }

        /**
         * Declares a namespace. NS is the one repeatable header (RFC 3862 §3.1), so it bypasses the
         * name-keyed map. Adding the same declaration twice emits it once.
         */
        public Builder addNamespace(String declaration) {
            if (declaration == null) throw new IllegalArgumentException("null NS declaration");
            for (int i = 0; i < declaration.length(); i++) {
                char c = declaration.charAt(i);
                if (c == '\r' || c == '\n') {
                    throw new IllegalArgumentException("CR/LF in CPIM header value: " + HDR_NS);
                }
            }
            if (!namespaces.contains(declaration)) namespaces.add(declaration);
            return this;
        }

        /** The comma-joined RFC 5438 §3.1 tokens; none removes the header. */
        public Builder dispositionNotification(String... tokens) {
            if (tokens == null || tokens.length == 0) {
                cpimHeaders.remove(HDR_IMDN_DISPO_NOTIF);
                return this;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(tokens[i]);
            }
            return cpimHeader(HDR_IMDN_DISPO_NOTIF, sb.toString());
        }

        public Builder contentType(String ct) { return mimeHeader(MIME_CONTENT_TYPE, ct); }
        public Builder contentDisposition(String cd) {
            return mimeHeader(MIME_CONTENT_DISPOSITION, cd);
        }

        public Builder payload(byte[] bytes) {
            this.payload = bytes == null ? null : bytes.clone();
            // Content-Length lets the parser stop before any transport padding.
            if (bytes != null) {
                mimeHeaders.put(MIME_CONTENT_LENGTH, Integer.toString(bytes.length));
            }
            return this;
        }

        public CpimMessage build() {
            if (!cpimHeaders.containsKey(HDR_FROM)) {
                throw new IllegalStateException("CPIM From header is required");
            }
            if (!cpimHeaders.containsKey(HDR_TO)) {
                throw new IllegalStateException("CPIM To header is required");
            }
            return new CpimMessage(this);
        }

        private Builder ensureAngled(String name, String uri) {
            String v = uri == null ? "" : uri.trim();
            if (!v.isEmpty() && v.charAt(0) != '<') {
                v = "<" + v + ">";
            }
            return cpimHeader(name, v);
        }

        private static void putHeader(LinkedHashMap<String, String> map,
                String name, String value) {
            if (name == null) throw new IllegalArgumentException("null header name");
            if (value == null) throw new IllegalArgumentException("null header value");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n') {
                    throw new IllegalArgumentException(
                            "CR/LF in CPIM header value: " + name);
                }
            }
            map.put(name, value);
        }
    }
}
