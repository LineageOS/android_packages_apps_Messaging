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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * RFC 3862 CPIM ("Common Profile for Instant Messaging") body builder and
 * parser. CPIM is a MIME-like wrapper that carries a payload (text, IMDN XML,
 * iscomposing XML, encrypted bytes, ...) plus identity / disposition metadata
 * that survives transport hops. Carrier-RCS sends CPIM inside SIP MESSAGE
 * (RFC 3428 pager mode) and MSRP SEND (RFC 4975 session mode).
 *
 * <p>Wire shape (see RFC 3862 §3 and the RFC 5438 §2 extensions):
 *
 * <pre>
 *   CPIM-headers CRLF
 *   CRLF
 *   MIME-headers CRLF
 *   CRLF
 *   payload-bytes
 * </pre>
 *
 * <p>Required CPIM headers, as Google Messages emits them:
 * <ul>
 *   <li>{@code From: &lt;tel:+E164&gt;} (or {@code &lt;sip:anonymous@anonymous.invalid&gt;}
 *       for revealed-identity messages)</li>
 *   <li>{@code To: &lt;tel:+E164&gt;}</li>
 *   <li>{@code NS: imdn &lt;urn:ietf:params:imdn&gt;} — registers the imdn
 *       namespace so {@code imdn.Message-ID} and
 *       {@code imdn.Disposition-Notification} can appear below</li>
 *   <li>{@code imdn.Message-ID: &lt;id&gt;}</li>
 *   <li>{@code DateTime: &lt;RFC 3339 ts&gt;}</li>
 *   <li>{@code imdn.Disposition-Notification: positive-delivery, display}
 *       — comma-joined</li>
 * </ul>
 *
 * <p>Required inner-MIME headers:
 * <ul>
 *   <li>{@code Content-Type: text/plain;charset=UTF-8} for chat text</li>
 *   <li>{@code Content-Type: message/imdn+xml} for IMDN reports</li>
 *   <li>{@code Content-Type: application/im-iscomposing+xml} for typing</li>
 *   <li>{@code Content-Disposition: notification} for IMDN reports</li>
 *   <li>{@code Content-Length: &lt;n&gt;} — payload length in bytes</li>
 * </ul>
 *
 * <p><b>Threading</b>: instances are immutable; the builder is not
 * thread-safe but built instances may be shared.
 *
 * <p><b>Charset</b>: CPIM headers are UTF-8 per RFC 3862 §3 paragraph 1
 * ("CPIM messages are UTF-8 encoded throughout"). Inner payload may declare
 * its own charset on the {@code Content-Type} header.
 */
public final class CpimMessage {

    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final byte[] CRLF = {'\r', '\n'};

    /** RFC 5438 §2 default namespace registration prefix used by Google Messages. */
    public static final String IMDN_NAMESPACE_URI = "urn:ietf:params:imdn";
    /** RFC 5438 §2 prefix declared via {@code NS: imdn <…>}. */
    public static final String IMDN_NAMESPACE_PREFIX = "imdn";

    // ---- Canonical CPIM header names ----
    public static final String HDR_FROM            = "From";
    public static final String HDR_TO              = "To";
    public static final String HDR_CC              = "cc";
    public static final String HDR_DATETIME        = "DateTime";
    public static final String HDR_SUBJECT         = "Subject";
    public static final String HDR_NS              = "NS";
    public static final String HDR_IMDN_MESSAGE_ID = "imdn.Message-ID";
    public static final String HDR_IMDN_DISPO_NOTIF = "imdn.Disposition-Notification";

    // ---- Canonical inner MIME header names ----
    public static final String MIME_CONTENT_TYPE        = "Content-Type";
    public static final String MIME_CONTENT_DISPOSITION = "Content-Disposition";
    public static final String MIME_CONTENT_LENGTH      = "Content-Length";

    // ---- Common content types ----
    public static final String CT_TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";
    public static final String CT_IMDN_XML        = "message/imdn+xml";
    public static final String CT_ISCOMPOSING_XML = "application/im-iscomposing+xml";
    public static final String CT_FT_HTTP_XML     = "application/vnd.gsma.rcs-ft-http+xml";
    public static final String CT_VND_GOOGLE_RCS_ENCRYPTED =
            "application/vnd.google.rcs.encrypted";

    // ---- MLS E2EE — the RCC.16 §7.9 content types plus the ones Google Messages uses.
    // MLS ciphertext/control is an inner CPIM Content-Type inside a message/cpim MSRP body
    // (never a top-level MSRP CT), parallel to CT_VND_GOOGLE_RCS_ENCRYPTED. Both families are
    // recognized so we interop with a Google Messages peer AND a strict RCC.16 peer.
    /** Application-message ciphertext (chat) → the MLS processor. */
    public static final String CT_MLS = "message/mls";
    /** RCC.16 §7.9 client control: Commit/Proposal (ClientMlsRcsMessage). */
    public static final String CT_MLS_RCS_CLIENT = "message/mls-rcs-client";
    /** Server control: Welcome/Commit bundle maintenance (ServerMlsRcsMessage, the server plane). */
    public static final String CT_MLS_RCS_SERVER = "message/mls-rcs-server";
    /** Google Messages: file-transfer ciphertext. */
    public static final String CT_MLS_FT = "message/mls-ft";
    /** Google Messages: file-transfer metadata XML (inside the MLS plane). */
    public static final String CT_MLS_RCS_FILE_INFO = "message/mls-rcs-file-info";
    /** Google Messages: server eviction (SERVER_KICK). */
    public static final String CT_MLS_RCS_SERVER_KICK = "message/mls-rcs-server-kick";

    /** RCC.16 §7.9 MLS CPIM namespace. */
    public static final String MLS_NAMESPACE_PREFIX = "mls";
    public static final String MLS_NAMESPACE_URI    = "http://www.gsma.com/rcs/mls";
    /** Namespaced MLS CPIM sub-headers. Both are REQUIRED on an MLS body; RX drops on missing. */
    public static final String HDR_MLS_ERA_ID       = MLS_NAMESPACE_PREFIX + ".Era-ID";
    public static final String HDR_MLS_EPOCH_AUTH   = MLS_NAMESPACE_PREFIX + ".Epoch-Authenticator";

    /** True for any MLS-plane inner content-type (case-insensitive). */
    public static boolean isMlsContentType(final String ct) {
        if (ct == null) {
            return false;
        }
        final String c = ct.trim().toLowerCase(java.util.Locale.ROOT);
        return c.equals(CT_MLS) || c.equals(CT_MLS_RCS_CLIENT) || c.equals(CT_MLS_RCS_SERVER)
                || c.equals(CT_MLS_FT) || c.equals(CT_MLS_RCS_FILE_INFO)
                || c.equals(CT_MLS_RCS_SERVER_KICK);
    }

    /** RFC 5438 §3.1 disposition-notification token values. */
    public static final String DISPO_POSITIVE_DELIVERY = "positive-delivery";
    public static final String DISPO_NEGATIVE_DELIVERY = "negative-delivery";
    public static final String DISPO_DISPLAY           = "display";
    public static final String DISPO_PROCESSING        = "processing";

    /** Anonymous URI per RFC 3323 §4.1, used for revealed-recipient bodies. */
    public static final String ANONYMOUS_URI = "<sip:anonymous@anonymous.invalid>";

    private final LinkedHashMap<String, String> cpimHeaders;
    private final LinkedHashMap<String, String> mimeHeaders;
    /** Declared CPIM namespaces, in order. Repeatable, so kept out of the name-keyed map. */
    private final java.util.List<String> namespaces;
    private final byte[] payload;

    private CpimMessage(Builder b) {
        this.cpimHeaders = new LinkedHashMap<>(b.cpimHeaders);
        this.mimeHeaders = new LinkedHashMap<>(b.mimeHeaders);
        this.namespaces = new java.util.ArrayList<>(b.namespaces);
        this.payload = b.payload == null ? new byte[0] : b.payload.clone();
    }

    // ---------- accessors ----------

    /** Case-insensitive CPIM-header lookup. */
    public String getCpimHeader(String name) {
        return findCi(cpimHeaders, name);
    }

    /** Case-insensitive inner-MIME-header lookup. */
    public String getMimeHeader(String name) {
        return findCi(mimeHeaders, name);
    }

    /** Returns an unmodifiable insertion-ordered view of CPIM headers. */
    /** The declared CPIM namespaces, in emission order. */
    public java.util.List<String> getNamespaces() {
        return Collections.unmodifiableList(namespaces);
    }

    public Map<String, String> getCpimHeaders() {
        return Collections.unmodifiableMap(cpimHeaders);
    }

    /** Returns an unmodifiable insertion-ordered view of inner-MIME headers. */
    public Map<String, String> getMimeHeaders() {
        return Collections.unmodifiableMap(mimeHeaders);
    }

    public String getFrom()       { return getCpimHeader(HDR_FROM); }
    public String getTo()         { return getCpimHeader(HDR_TO); }
    public String getDateTime()   { return getCpimHeader(HDR_DATETIME); }
    public String getMessageId()  { return getCpimHeader(HDR_IMDN_MESSAGE_ID); }
    public String getContentType(){ return getMimeHeader(MIME_CONTENT_TYPE); }

    /** Returns a defensive copy of the inner payload (never null). */
    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() { return payload.length; }

    /**
     * Returns the {@code imdn.Disposition-Notification} header tokens, or an
     * empty array if not present. Tokens are comma-separated per RFC 5438
     * §5.1 ABNF (e.g. {@code positive-delivery, display}).
     */
    public String[] getDispositionNotifications() {
        String raw = getCpimHeader(HDR_IMDN_DISPO_NOTIF);
        if (raw == null) return new String[0];
        String[] parts = raw.split(",");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].trim();
        return out;
    }

    // ---------- encode / parse ----------

    /**
     * Serialize this CPIM message into wire-format bytes. Output structure:
     *
     * <pre>
     *   {@literal <}CPIM-header CRLF{@literal >}*
     *   CRLF
     *   {@literal <}MIME-header CRLF{@literal >}*
     *   CRLF
     *   {@literal <}payload bytes{@literal >}
     * </pre>
     *
     * <p>No trailing CRLF is added after the payload — the enclosing SIP /
     * MSRP framing is responsible for that. Header values are NOT validated
     * for CR/LF injection here; {@link Builder} enforces that on input.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                64 + cpimHeaders.size() * 32 + mimeHeaders.size() * 32 + payload.length);
        // NS goes immediately BEFORE THE FIRST PREFIXED HEADER — which both satisfies "a prefix
        // must be bound before it is used" and reproduces Google Messages' emit order
        // (From, To, NS, imdn.Message-ID, DateTime, imdn.Disposition-Notification).
        //
        // Emitting all NS declarations unconditionally first, as this briefly did, is prefix-safe
        // but does NOT match Google Messages, and header order is wire-visible — CpimMessageTest's
        // headerOrder_matchesBugleEmitOrder asserts Google Messages' order and was right to fail.
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
            // No prefixed header at all — still declare, so a namespace is never silently dropped.
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
     * Parse a CPIM wire-format byte array. Strict on the structural shape
     * (must contain a CRLF CRLF gap between CPIM and MIME header blocks, and
     * a CRLF CRLF gap between MIME headers and payload) but tolerant on
     * header-name case and on bare-LF line endings (RFC 822 §3.1 historical
     * forgiveness — Google Messages' parser is similarly forgiving).
     *
     * @throws CpimParseException if the input is not a valid CPIM frame
     */
    public static CpimMessage parse(byte[] wire) throws CpimParseException {
        if (wire == null) throw new CpimParseException("null wire");
        // Decode as UTF-8 — CPIM is UTF-8 by spec for both header and payload
        // labels; payload bytes are preserved exactly by indexing back into
        // the wire byte[] post-header.
        String text = new String(wire, UTF_8);

        int cpimEnd = findHeaderBlockEnd(text, 0);
        if (cpimEnd < 0) throw new CpimParseException("missing CPIM/MIME separator");
        int mimeStart = nextLine(text, cpimEnd);
        int mimeEnd = findHeaderBlockEnd(text, mimeStart);
        if (mimeEnd < 0) throw new CpimParseException("missing MIME/payload separator");
        int payloadStart = nextLine(text, mimeEnd);

        Builder b = new Builder();
        parseHeaderLines(text.substring(0, cpimEnd), b.cpimHeaders);
        // NS is REPEATABLE (RFC 5438 §2) and must NOT live in the name-keyed CPIM map, which is
        // last-value-wins for duplicates. parseHeaderLines is shared with the MIME block, so the
        // re-homing happens here, at the CPIM call site only.
        //
        // Before this, an inbound `NS:` landed in cpimHeaders and the namespaces list came back
        // EMPTY, which had two consequences: (a) a message declaring BOTH the imdn and the MLS
        // namespaces (RCC.16 §7.9 needs both) silently lost one to the last-value-wins map, and
        // (b) parse(encode(x)) never equalled x, because encode() writes NS from `namespaces` while
        // parse() put it in `cpimHeaders`. Found by CpimMessageTest's round-trip tests, which were
        // right to fail.
        {
            final String ns = b.cpimHeaders.remove(HDR_NS);
            if (ns != null) {
                // A single NS survives the map; multiples are recovered from the raw block below.
                b.addNamespace(ns);
            }
            // Recover EVERY declaration in wire order — the map could only ever hold the last.
            for (final String line : text.substring(0, cpimEnd).split("\r?\n")) {
                final int c = line.indexOf(':');
                if (c > 0 && HDR_NS.equalsIgnoreCase(line.substring(0, c).trim())) {
                    b.addNamespace(line.substring(c + 1).trim());   // addNamespace is idempotent
                }
            }
        }
        parseHeaderLines(text.substring(mimeStart, mimeEnd), b.mimeHeaders);

        // Payload byte index — recompute by re-encoding the prefix in UTF-8.
        // CPIM headers are ASCII / UTF-8 so the byte offset of the payload
        // is the byte length of the UTF-8 encoding of [0..payloadStart) in
        // the original char string.
        int payloadByteOffset = text.substring(0, payloadStart).getBytes(UTF_8).length;
        int len = wire.length - payloadByteOffset;
        // Apply Content-Length if present and shorter than remaining bytes
        // (some senders may include a transport-padding suffix; respect the
        // declared length — but never grow beyond the supplied wire bytes).
        String cl = findCi(b.mimeHeaders, MIME_CONTENT_LENGTH);
        if (cl != null) {
            try {
                int n = Integer.parseInt(cl.trim());
                if (n >= 0 && n < len) len = n;
            } catch (NumberFormatException ignored) {
                // tolerate broken Content-Length
            }
        }
        byte[] payload = new byte[len];
        System.arraycopy(wire, payloadByteOffset, payload, 0, len);
        b.payload = payload;
        return new CpimMessage(b);
    }

    /** Returns the index just before the trailing blank-line CRLF (or LF), or -1. */
    private static int findHeaderBlockEnd(String s, int from) {
        // Look for CRLF CRLF or LF LF separating a header block from the next block.
        int crlfCrlf = s.indexOf("\r\n\r\n", from);
        int lfLf     = s.indexOf("\n\n", from);
        if (crlfCrlf < 0)      return lfLf;
        if (lfLf < 0)          return crlfCrlf;
        return Math.min(crlfCrlf, lfLf);
    }

    /** Return the index of the start of the next block after a CRLF CRLF / LF LF at {@code idx}. */
    private static int nextLine(String s, int idx) {
        if (idx + 4 <= s.length() && s.charAt(idx) == '\r' && s.charAt(idx + 1) == '\n'
                && s.charAt(idx + 2) == '\r' && s.charAt(idx + 3) == '\n') {
            return idx + 4;
        }
        if (idx + 2 <= s.length() && s.charAt(idx) == '\n' && s.charAt(idx + 1) == '\n') {
            return idx + 2;
        }
        // We were called only after findHeaderBlockEnd succeeded — fall through.
        return idx;
    }

    private static void parseHeaderLines(String block, LinkedHashMap<String, String> out)
            throws CpimParseException {
        // Split on CRLF or LF.
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
                // Last value wins for duplicate names, matching Google Messages'
                // LinkedHashMap-like behaviour (and our LinkedHashMap impl).
                out.put(name, value);
            }
            p = eol;
            // skip CRLF or LF
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

    // ---------- equality / debug ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CpimMessage)) return false;
        CpimMessage c = (CpimMessage) o;
        return mapEqualsCi(cpimHeaders, c.cpimHeaders)
                && mapEqualsCi(mimeHeaders, c.mimeHeaders)
                // namespaces is part of the message's identity: it is what binds the prefixes the
                // other headers use, and it was omitted here while NS also sat in cpimHeaders, so
                // the omission was invisible. Both halves are fixed together.
                && namespaces.equals(c.namespaces)
                && java.util.Arrays.equals(payload, c.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toLowerKeys(cpimHeaders), toLowerKeys(mimeHeaders),
                namespaces,   // must track equals() — see the note there
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

    // ---------- builder ----------

    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Convenience factory for a 1:1 chat text body. Inserts the canonical
     * Google Messages header set: {@code From / To / NS imdn /
     * imdn.Message-ID / DateTime / imdn.Disposition-Notification}.
     *
     * @param fromUri    sender URI, e.g. {@code <tel:+15551234567>}; angle
     *                   brackets are added if not present
     * @param toUri      recipient URI, ditto
     * @param messageId  CPIM-stable id; reused on IMDN reports referring to
     *                   this message
     * @param dateTime   RFC 3339 timestamp (use {@link CpimDateTime#now()})
     * @param text       UTF-8 text body
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
     * Build an MLS-plane CPIM body: an inner {@code message/mls[-rcs-*]} content-type
     * carrying raw MLSMessage {@code wire} bytes, with the RCC.16 §7.9 namespaced {@code mls.Era-ID}
     * + {@code mls.Epoch-Authenticator} sub-headers (both required; the receiver drops a body missing
     * either before decrypt). {@code eraId} is the ASCII uint Era; {@code epochAuthB64} is the
     * standard-base64 32-byte epoch authenticator.
     */
    public static CpimMessage newMls(String fromUri, String toUri, String messageId, String dateTime,
            String contentType, byte[] wire, String eraId, String epochAuthB64) {
        final Builder b = newBuilder()
                .from(fromUri)
                .to(toUri)
                .addImdnNamespace()
                .addMlsNamespace()
                .messageId(messageId)
                .dateTime(dateTime)
                // NEGATIVE-DELIVERY IS NOT OPTIONAL ON THE MLS PLANE. RCC.16 §7.7.2.2 has a peer
                // that cannot DECRYPT report it as a negative-delivery IMDN, and that report is the
                // entry point to §10 recovery — it is how the sender learns the group has diverged
                // at all. RFC 5438 makes a disposition the sender did not REQUEST unsolicited, so
                // asking only for positive-delivery meant the one receipt that carries actionable
                // information was the one we never asked for.
                .dispositionNotification(DISPO_POSITIVE_DELIVERY, DISPO_NEGATIVE_DELIVERY,
                        DISPO_DISPLAY);
        if (eraId != null) {
            b.cpimHeader(HDR_MLS_ERA_ID, eraId);
        }
        if (epochAuthB64 != null) {
            b.cpimHeader(HDR_MLS_EPOCH_AUTH, epochAuthB64);
        }
        return b.contentType(contentType)
                .payload(wire == null ? new byte[0] : wire)
                .build();
    }

    /**
     * Convenience factory for an IMDN-disposition CPIM body. Adds the
     * required {@code Content-Disposition: notification} MIME header per
     * RFC 5438 §3. The {@code imdn.Disposition-Notification} header is NOT
     * added to outbound disposition messages (we don't ask for receipts of
     * our receipts); to override use the builder.
     *
     * @param fromUri    sender of the disposition message
     * @param toUri      original sender of the message being acked
     * @param messageId  a NEW message id for the disposition body itself
     * @param dateTime   RFC 3339 timestamp
     * @param imdnXml    serialized {@code <imdn>} XML body
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
        // LinkedHashMap preserves Google Messages' emit order for deterministic wire
        // shape (helpful for snapshot tests and digest correlators).
        private final LinkedHashMap<String, String> cpimHeaders = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> mimeHeaders = new LinkedHashMap<>();
        /** NS is repeatable, so it cannot live in a name-keyed map. See {@link #addNamespace}. */
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
        public Builder cc(String uri)           { return ensureAngled(HDR_CC, uri); }
        public Builder dateTime(String rfc3339) { return cpimHeader(HDR_DATETIME, rfc3339); }
        public Builder messageId(String id)     { return cpimHeader(HDR_IMDN_MESSAGE_ID, id); }
        public Builder subject(String s)        { return cpimHeader(HDR_SUBJECT, s); }

        /** Add the canonical {@code NS: imdn <urn:ietf:params:imdn>} header. */
        public Builder addImdnNamespace() {
            return addNamespace(IMDN_NAMESPACE_PREFIX + " <" + IMDN_NAMESPACE_URI + ">");
        }

        /** Add the {@code NS: mls <http://www.gsma.com/rcs/mls>} header (RCC.16 §7.9 MLS sub-headers). */
        public Builder addMlsNamespace() {
            return addNamespace(MLS_NAMESPACE_PREFIX + " <" + MLS_NAMESPACE_URI + ">");
        }

        /**
         * Declare a CPIM namespace. <b>NS is the one repeatable header</b> (RFC 3862 §3.1) and it
         * must not go through the name-keyed header map.
         *
         * <p>Both namespace helpers used to write {@code cpimHeader("NS", …)}, and the map is keyed
         * by header NAME — so a message declaring both namespaces silently kept only the one added
         * LAST. An RCC.16 §7.9 MLS message that also carries an IMDN request needs both, and the
         * loser's prefix then refers to a namespace the recipient was never told about: the header
         * is present, well-formed, and meaningless.
         *
         * <p>Idempotent per declaration, so adding the same namespace twice does not emit it twice.
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

        /**
         * Set the {@code imdn.Disposition-Notification} header to the
         * comma-joined list of tokens. RFC 5438 §3.1 valid tokens:
         * {@code positive-delivery}, {@code negative-delivery},
         * {@code display}, {@code processing}.
         */
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
            // Auto-set Content-Length so the parser side knows where to stop
            // when the wire frame has trailing transport padding.
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

        // ---- internals ----

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
            // CR/LF injection guard: header values must not span lines.
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
