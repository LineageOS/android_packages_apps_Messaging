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
package com.android.messaging.rcs.carrier.loopback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal SIP-message model used inside {@link LoopbackSipMsrpServer}. Test
 * scaffolding.
 *
 * <p>Not a real RFC 3261 implementation — just enough text parsing to:
 * <ul>
 *   <li>Tokenize the start-line into {@code method requestUri sipVersion} (for
 *       requests) or {@code sipVersion code reason} (for responses).</li>
 *   <li>Collect header lines (preserving insertion order, allowing duplicate
 *       Via headers).</li>
 *   <li>Read a {@code Content-Length}-bounded body off a byte stream.</li>
 *   <li>Serialize back to wire bytes when crafting our responses.</li>
 * </ul>
 *
 * <p>The harness is deliberately TCP-only (no UDP message-boundary parsing,
 * no SCTP, no maddr handling). RCS carriers default to TLS for SIP signaling
 * but our loopback strips down to plaintext TCP because we don't need TLS at
 * the SIP layer to exercise REGISTER/INVITE/MESSAGE wire shapes — the
 * carrier-RCS test code points its registrar at the harness's port and uses
 * cleartext TCP transport.
 */
final class SimpleSipMessage {

    static final Charset UTF_8 = Charset.forName("UTF-8");
    static final String CRLF = "\r\n";

    enum Kind { REQUEST, RESPONSE }

    final Kind kind;
    /** Request: method (e.g. "REGISTER"); Response: sipVersion (e.g. "SIP/2.0"). */
    final String startA;
    /** Request: requestUri (e.g. "sip:loopback.test"); Response: status code as string. */
    final String startB;
    /** Request: sipVersion (e.g. "SIP/2.0"); Response: reason phrase. */
    final String startC;
    /** Header order preserved. Same name may appear multiple times (Via). */
    final List<String[]> headers;
    final byte[] body;

    private SimpleSipMessage(Kind kind, String a, String b, String c,
            List<String[]> headers, byte[] body) {
        this.kind = kind;
        this.startA = a;
        this.startB = b;
        this.startC = c;
        this.headers = headers;
        this.body = body;
    }

    String getMethod() {
        return kind == Kind.REQUEST ? startA : null;
    }

    String getRequestUri() {
        return kind == Kind.REQUEST ? startB : null;
    }

    int getStatusCode() {
        if (kind != Kind.RESPONSE) return -1;
        try { return Integer.parseInt(startB); } catch (NumberFormatException nfe) { return -1; }
    }

    /** Returns the first matching header value, case-insensitive on name, or null. */
    String getHeader(String name) {
        String want = name.toLowerCase(Locale.ROOT);
        for (String[] h : headers) {
            if (h[0].toLowerCase(Locale.ROOT).equals(want)) return h[1];
        }
        return null;
    }

    /** Returns every matching header value, in order. */
    List<String> getHeaders(String name) {
        String want = name.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String[] h : headers) {
            if (h[0].toLowerCase(Locale.ROOT).equals(want)) out.add(h[1]);
        }
        return out;
    }

    /** Extract the {@code tag=} parameter value from a From / To / etc. header, or null. */
    static String extractTag(String headerValue) {
        if (headerValue == null) return null;
        int p = headerValue.toLowerCase(Locale.ROOT).indexOf(";tag=");
        if (p < 0) return null;
        int start = p + 5;
        int end = start;
        while (end < headerValue.length() && headerValue.charAt(end) != ';'
                && headerValue.charAt(end) != ',' && !Character.isWhitespace(headerValue.charAt(end))) {
            end++;
        }
        return headerValue.substring(start, end);
    }

    /**
     * Read one complete SIP message off the stream. Returns null on EOF
     * before any bytes were read. Throws IOException on malformed wire.
     */
    static SimpleSipMessage read(InputStream in) throws IOException {
        // Read until \r\n\r\n (header terminator). We do this byte-at-a-time
        // because we don't know header length upfront.
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(512);
        int crlfState = 0; // 0=initial, 1=\r, 2=\r\n, 3=\r\n\r, 4=\r\n\r\n
        int firstByte = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (headerBuf.size() == 0) return null;
                throw new IOException("SIP: EOF mid-header");
            }
            if (headerBuf.size() == 0) firstByte = b;
            headerBuf.write(b);
            switch (crlfState) {
                case 0: crlfState = (b == '\r') ? 1 : 0; break;
                case 1: crlfState = (b == '\n') ? 2 : (b == '\r' ? 1 : 0); break;
                case 2: crlfState = (b == '\r') ? 3 : 0; break;
                case 3: crlfState = (b == '\n') ? 4 : (b == '\r' ? 1 : 0); break;
                default: break;
            }
            if (crlfState == 4) break;
        }

        String headerText = new String(headerBuf.toByteArray(), 0,
                headerBuf.size() - 4, UTF_8);
        String[] lines = headerText.split("\\r\\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IOException("SIP: empty start-line");
        }
        String startLine = lines[0];
        Kind kind;
        String a, b, c;
        if (startLine.startsWith("SIP/")) {
            kind = Kind.RESPONSE;
            // SIP/2.0 200 OK
            int sp1 = startLine.indexOf(' ');
            int sp2 = startLine.indexOf(' ', sp1 + 1);
            if (sp1 < 0) throw new IOException("SIP: malformed status line: " + startLine);
            a = startLine.substring(0, sp1);
            if (sp2 < 0) {
                b = startLine.substring(sp1 + 1);
                c = "";
            } else {
                b = startLine.substring(sp1 + 1, sp2);
                c = startLine.substring(sp2 + 1);
            }
        } else {
            kind = Kind.REQUEST;
            // METHOD request-uri SIP/2.0
            int sp1 = startLine.indexOf(' ');
            int sp2 = startLine.lastIndexOf(' ');
            if (sp1 < 0 || sp2 == sp1) {
                throw new IOException("SIP: malformed request line: " + startLine);
            }
            a = startLine.substring(0, sp1);
            b = startLine.substring(sp1 + 1, sp2);
            c = startLine.substring(sp2 + 1);
        }
        List<String[]> headers = new ArrayList<>();
        String pendingName = null;
        StringBuilder pendingValue = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                // continuation
                if (pendingName != null) {
                    pendingValue.append(' ').append(line.trim());
                }
                continue;
            }
            if (pendingName != null) {
                headers.add(new String[]{pendingName, pendingValue.toString().trim()});
                pendingValue.setLength(0);
                pendingName = null;
            }
            int colon = line.indexOf(':');
            if (colon < 0) throw new IOException("SIP: header missing colon: " + line);
            pendingName = line.substring(0, colon).trim();
            pendingValue.append(line.substring(colon + 1).trim());
        }
        if (pendingName != null) {
            headers.add(new String[]{pendingName, pendingValue.toString().trim()});
        }

        int contentLength = 0;
        for (String[] h : headers) {
            String n = h[0].toLowerCase(Locale.ROOT);
            if (n.equals("content-length") || n.equals("l")) {
                try { contentLength = Integer.parseInt(h[1].trim()); }
                catch (NumberFormatException nfe) {
                    throw new IOException("SIP: bad Content-Length: " + h[1]);
                }
                break;
            }
        }
        byte[] body = null;
        if (contentLength > 0) {
            body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) throw new IOException("SIP: EOF in body at " + off + "/" + contentLength);
                off += n;
            }
        }
        // Suppress unused warning for firstByte (used only to seed the first-byte trace).
        if (firstByte == -1) { /* unreachable */ }
        return new SimpleSipMessage(kind, a, b, c, headers, body);
    }

    /** Build a SIP response derived from this request. */
    Builder responseBuilder(int code, String reason) {
        if (kind != Kind.REQUEST) throw new IllegalStateException("not a request");
        Builder b = Builder.response(code, reason);
        // Echo headers per RFC 3261 §8.2.6.2.
        for (String v : getHeaders("Via")) b.addHeader("Via", v);
        if (getHeader("From") != null) b.addHeader("From", getHeader("From"));
        if (getHeader("To") != null)   b.addHeader("To", getHeader("To"));
        if (getHeader("Call-ID") != null) b.addHeader("Call-ID", getHeader("Call-ID"));
        if (getHeader("CSeq") != null) b.addHeader("CSeq", getHeader("CSeq"));
        return b;
    }

    byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try {
            if (kind == Kind.REQUEST) {
                out.write((startA + " " + startB + " " + startC + CRLF).getBytes(UTF_8));
            } else {
                out.write((startA + " " + startB + " " + startC + CRLF).getBytes(UTF_8));
            }
            for (String[] h : headers) {
                out.write((h[0] + ": " + h[1] + CRLF).getBytes(UTF_8));
            }
            int bodyLen = body == null ? 0 : body.length;
            out.write(("Content-Length: " + bodyLen + CRLF).getBytes(UTF_8));
            out.write(CRLF.getBytes(UTF_8));
            if (body != null) out.write(body);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return out.toByteArray();
    }

    static final class Builder {
        private final Kind kind;
        private final String startA, startB, startC;
        private final List<String[]> headers = new ArrayList<>();
        private byte[] body;

        private Builder(Kind kind, String a, String b, String c) {
            this.kind = kind;
            this.startA = a;
            this.startB = b;
            this.startC = c;
        }

        static Builder response(int code, String reason) {
            return new Builder(Kind.RESPONSE, "SIP/2.0", Integer.toString(code), reason);
        }

        static Builder request(String method, String uri) {
            return new Builder(Kind.REQUEST, method, uri, "SIP/2.0");
        }

        Builder addHeader(String name, String value) {
            headers.add(new String[]{name, value});
            return this;
        }

        Builder body(byte[] data) {
            this.body = data;
            return this;
        }

        Builder body(String data, String contentType) {
            this.body = data == null ? null : data.getBytes(UTF_8);
            addHeader("Content-Type", contentType);
            return this;
        }

        SimpleSipMessage build() {
            return new SimpleSipMessage(kind, startA, startB, startC,
                    new ArrayList<>(headers), body);
        }
    }

    // ---------- digest auth helpers ----------

    /**
     * Parse a SIP Authorization header (or WWW-Authenticate) into a flat
     * (name → value) map. Strips wrapping quotes from values.
     */
    static Map<String, String> parseAuthHeader(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null) return out;
        // Expected form: scheme name="value", name=token, ...
        int sp = value.indexOf(' ');
        if (sp > 0) value = value.substring(sp + 1);
        int i = 0;
        while (i < value.length()) {
            while (i < value.length() && (value.charAt(i) == ',' || Character.isWhitespace(value.charAt(i)))) i++;
            int eq = value.indexOf('=', i);
            if (eq < 0) break;
            String name = value.substring(i, eq).trim();
            i = eq + 1;
            if (i < value.length() && value.charAt(i) == '"') {
                int end = value.indexOf('"', i + 1);
                if (end < 0) break;
                out.put(name.toLowerCase(Locale.ROOT), value.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = i;
                while (end < value.length() && value.charAt(end) != ',') end++;
                out.put(name.toLowerCase(Locale.ROOT), value.substring(i, end).trim());
                i = end;
            }
        }
        return out;
    }
}
