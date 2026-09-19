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
package com.android.messaging.rcs.carrier.msrp;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-memory representation of a single MSRP frame (request or
 * response) per RFC 4975. Pure I/O-free: encoders return {@code byte[]},
 * parsers consume {@code byte[]}.
 *
 * <p>Two flavours of frame coexist:
 * <ul>
 *   <li><b>Request</b> — start-line {@code "MSRP <tid> <method>"}. Method
 *       is one of {@link MsrpMethod}. Has body for SEND, no body for REPORT.</li>
 *   <li><b>Response</b> — start-line {@code "MSRP <tid> <code> [comment]"}.
 *       Always bodiless. Used by an MSRP peer to ACK/NACK a SEND/REPORT
 *       transaction (200 OK on success, 4xx/5xx on failure such as RFC 4975
 *       §13's 413 Request Entity Too Large for chunk-size pushback).</li>
 * </ul>
 *
 * <p>Both share the same header table and end-line; the type discriminator
 * lives on the start line. Use the named factory methods
 * ({@link Builder#asSend()}, {@link Builder#asReport()},
 * {@link Builder#asResponse(int, String)}) rather than passing flags.
 *
 * <p>RFC 4975 §9 ABNF anchors for the syntax below:
 * <pre>
 *   msrp-request   = req-start headers [content-stuff] end-line
 *   msrp-response  = resp-start headers end-line
 *   req-start      = pMSRP SP transact-id SP method CRLF
 *   resp-start     = pMSRP SP transact-id SP status-code [SP comment] CRLF
 *   end-line       = "-------" transact-id continuation-flag CRLF
 *   content-stuff  = *(header CRLF) CRLF data CRLF       ; body precedes end-line
 * </pre>
 *
 * <p>Cross-checked against Google Messages' own MSRP constants, encoder and
 * parser.
 */
public final class MsrpMessage {

    // -------- charset / constants --------

    /** RFC 4975 §3.1: "Headers are encoded using UTF-8". */
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    /** RFC 4975 §3.1: CRLF separator between header lines. */
    public static final byte[] CRLF = {'\r', '\n'};
    /** RFC 4975 §9 ABNF: end-line begins with exactly 7 minus signs. */
    public static final String DASHES = "-------";
    public static final int DASH_COUNT = 7;
    private static final String START_TAG = "MSRP";

    // -------- frame kind --------

    /** Discriminates the start-line shape. */
    public enum Kind { REQUEST, RESPONSE }

    // -------- immutable fields --------

    private final Kind kind;
    private final String transactionId;
    private final MsrpMethod method;       // non-null iff kind == REQUEST
    private final int statusCode;          // valid iff kind == RESPONSE
    private final String statusComment;    // optional, only for RESPONSE
    private final LinkedHashMap<String, String> headers; // case-insensitive view
    private final byte[] body;             // may be null (no body)
    private final MsrpEndFlag endFlag;

    private MsrpMessage(Builder b) {
        this.kind          = b.kind;
        this.transactionId = b.transactionId;
        this.method        = b.method;
        this.statusCode    = b.statusCode;
        this.statusComment = b.statusComment;
        this.headers       = new LinkedHashMap<>(b.headers);
        this.body          = b.body == null ? null : b.body.clone();
        this.endFlag       = b.endFlag;
    }

    // -------- accessors --------

    public Kind getKind()                  { return kind; }
    public String getTransactionId()       { return transactionId; }
    public MsrpMethod getMethod()          { return method; }
    public int getStatusCode()             { return statusCode; }
    public String getStatusComment()       { return statusComment; }
    public MsrpEndFlag getEndFlag()        { return endFlag; }

    public boolean isRequest()  { return kind == Kind.REQUEST;  }
    public boolean isResponse() { return kind == Kind.RESPONSE; }

    /** Returns a header value by name (case-insensitive), or null. */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Returns an immutable, insertion-ordered view of all header (name,value) pairs. */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    /** Convenience accessors for canonical headers; null when absent. */
    public String getToPath()        { return getHeader(MsrpHeaders.TO_PATH); }
    public String getFromPath()      { return getHeader(MsrpHeaders.FROM_PATH); }
    public String getMessageId()     { return getHeader(MsrpHeaders.MESSAGE_ID); }
    public String getContentType()   { return getHeader(MsrpHeaders.CONTENT_TYPE); }
    public String getByteRange()     { return getHeader(MsrpHeaders.BYTE_RANGE); }
    public String getSuccessReport() { return getHeader(MsrpHeaders.SUCCESS_REPORT); }
    public String getFailureReport() { return getHeader(MsrpHeaders.FAILURE_REPORT); }
    public String getStatus()        { return getHeader(MsrpHeaders.STATUS); }

    /** Returns a defensive copy of the body, or null if there is no body. */
    public byte[] getBody() {
        return body == null ? null : body.clone();
    }

    /** Returns the body length in bytes, or 0 if none. */
    public int getBodyLength() {
        return body == null ? 0 : body.length;
    }

    // -------- encoder --------

    /**
     * Serialize this frame to wire bytes per RFC 4975 §9. The output is the
     * exact stream a peer would receive on a TCP/TLS MSRP socket — caller
     * just writes it.
     *
     * <p>Frame layout (request with body):
     * <pre>
     *   MSRP {tid} {method}\r\n
     *   To-Path: ...\r\n
     *   From-Path: ...\r\n
     *   {other-headers}\r\n
     *   \r\n
     *   {body}\r\n
     *   -------{tid}{flag}\r\n
     * </pre>
     *
     * <p>For bodiless frames (REPORT, responses, empty-SEND keepalive) the
     * blank-line + body + trailing CRLF are omitted — the end-line follows
     * the last header directly, per RFC 4975 §9 ABNF where
     * {@code content-stuff} is optional.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256
                + (body == null ? 0 : body.length));
        try {
            // start-line
            if (kind == Kind.REQUEST) {
                out.write((START_TAG + " " + transactionId + " " + method.name())
                        .getBytes(UTF_8));
            } else {
                StringBuilder sl = new StringBuilder(START_TAG)
                        .append(' ').append(transactionId)
                        .append(' ').append(statusCode);
                if (statusComment != null && !statusComment.isEmpty()) {
                    sl.append(' ').append(statusComment);
                }
                out.write(sl.toString().getBytes(UTF_8));
            }
            out.write(CRLF);

            // headers (preserves insertion order, which mirrors Google Messages' emit order)
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String name = canonicalCase(e.getKey());
                out.write((name + ": " + e.getValue()).getBytes(UTF_8));
                out.write(CRLF);
            }

            // body, if any
            if (body != null && body.length > 0) {
                out.write(CRLF);          // blank line separates headers from body
                out.write(body);
                out.write(CRLF);          // body terminator before end-line
            }

            // end-line
            out.write((DASHES + transactionId).getBytes(UTF_8));
            out.write(endFlag.asChar());
            out.write(CRLF);
        } catch (java.io.IOException impossible) {
            // ByteArrayOutputStream.write never actually throws
            throw new AssertionError(impossible);
        }
        return out.toByteArray();
    }

    /** Resolve the canonical (preferred-case) spelling of a header name we know. */
    private static String canonicalCase(String lowered) {
        switch (lowered) {
            case "to-path":         return MsrpHeaders.TO_PATH;
            case "from-path":       return MsrpHeaders.FROM_PATH;
            case "message-id":      return MsrpHeaders.MESSAGE_ID;
            case "byte-range":      return MsrpHeaders.BYTE_RANGE;
            case "content-type":    return MsrpHeaders.CONTENT_TYPE;
            case "success-report":  return MsrpHeaders.SUCCESS_REPORT;
            case "failure-report":  return MsrpHeaders.FAILURE_REPORT;
            case "status":          return MsrpHeaders.STATUS;
            case "use-path":        return MsrpHeaders.USE_PATH;
            default:                return lowered; // unknown extension — pass through
        }
    }

    // -------- parser --------

    /**
     * Parse one complete MSRP frame from {@code bytes}. The input must contain
     * exactly one frame's worth of data — start-line through CRLF after the
     * end-line. (See {@link MsrpFrameReader} for a streaming reader that
     * peels one frame at a time off a TCP buffer.)
     *
     * <p>Per RFC 4975 §3.1 the wire is line-oriented; we accept CRLF (the
     * spec form) and bare LF (tolerant of buggy peers — Google Messages'
     * parser does the same).
     *
     * @throws MsrpException if the bytes do not parse as a well-formed frame
     */
    public static MsrpMessage parse(byte[] bytes) throws MsrpException {
        return parse(bytes, 0, bytes.length);
    }

    /**
     * Parse one MSRP frame from a slice of {@code bytes}.
     */
    public static MsrpMessage parse(byte[] bytes, int off, int len) throws MsrpException {
        if (bytes == null) {
            throw new MsrpException("MSRP parse: null buffer");
        }
        if (off < 0 || len < 0 || off + len > bytes.length) {
            throw new MsrpException("MSRP parse: bad slice " + off + "+" + len
                    + " into " + bytes.length);
        }
        int end = off + len;
        int p = off;

        // -- start line --
        int slEnd = findLineEnd(bytes, p, end);
        if (slEnd < 0) throw new MsrpException("MSRP parse: no CRLF after start-line");
        String startLine = decode(bytes, p, slEnd - p);
        p = advancePastLineEnd(bytes, slEnd, end);

        Builder b;
        if (!startLine.startsWith(START_TAG + " ")) {
            throw new MsrpException("MSRP parse: start-line missing \"MSRP \" prefix: "
                    + startLine);
        }
        int firstSp = startLine.indexOf(' ');
        int secondSp = startLine.indexOf(' ', firstSp + 1);
        if (secondSp < 0) {
            throw new MsrpException("MSRP parse: malformed start-line: " + startLine);
        }
        String tid = startLine.substring(firstSp + 1, secondSp);
        if (!MsrpTransactionId.isValid(tid)) {
            throw new MsrpException("MSRP parse: invalid transaction-id: " + tid);
        }
        String tail = startLine.substring(secondSp + 1);
        if (isDigit3(tail)) {
            // Response: code [SP comment]
            int sp = tail.indexOf(' ');
            int code;
            String comment;
            if (sp < 0) {
                code = parseInt(tail, "status-code");
                comment = null;
            } else {
                code = parseInt(tail.substring(0, sp), "status-code");
                comment = tail.substring(sp + 1);
            }
            b = new Builder().asResponse(code, comment).transactionId(tid);
        } else {
            // Request: method
            MsrpMethod m = MsrpMethod.parse(tail);
            b = new Builder().asRequest(m).transactionId(tid);
        }

        // -- headers --
        // We accumulate raw header lines; spec also allows continuation lines
        // (LWS-prefixed) per the SIP grammar that RFC 4975 §9 imports — Google
        // Messages does NOT emit those, but we tolerate them on parse.
        String pendingName = null;
        StringBuilder pendingValue = new StringBuilder();
        while (true) {
            int lineEnd = findLineEnd(bytes, p, end);
            if (lineEnd < 0) {
                throw new MsrpException("MSRP parse: unterminated headers");
            }
            // Empty line ends the header block — but we must check whether the
            // "empty line" is actually the end-line of a bodiless frame
            // (the case where there's no blank-line + body section).
            if (lineEnd == p) {
                // CRLF on its own: body separator.
                if (pendingName != null) {
                    b.header(pendingName, pendingValue.toString().trim());
                }
                p = advancePastLineEnd(bytes, lineEnd, end);
                break;
            }

            // Is this line the end-line? "-------<tid><flag>"
            if (looksLikeEndLine(bytes, p, lineEnd - p, tid)) {
                if (pendingName != null) {
                    b.header(pendingName, pendingValue.toString().trim());
                }
                // No body in this frame. Parse end-line and we're done.
                parseEndLineAndSetFlag(bytes, p, lineEnd - p, tid, b);
                p = advancePastLineEnd(bytes, lineEnd, end);
                return b.buildUnchecked();
            }

            // Folded continuation? RFC 4975 §3.1: a header line beginning with
            // SP or HT is a continuation of the previous header value.
            byte first = bytes[p];
            if ((first == ' ' || first == '\t') && pendingName != null) {
                pendingValue.append(' ')
                        .append(decode(bytes, p, lineEnd - p).trim());
                p = advancePastLineEnd(bytes, lineEnd, end);
                continue;
            }

            // Otherwise it's a fresh header. Flush the previous one.
            if (pendingName != null) {
                b.header(pendingName, pendingValue.toString().trim());
                pendingValue.setLength(0);
            }
            String headerLine = decode(bytes, p, lineEnd - p);
            int colon = headerLine.indexOf(':');
            if (colon < 0) {
                throw new MsrpException("MSRP parse: header missing colon: " + headerLine);
            }
            pendingName = headerLine.substring(0, colon).trim();
            pendingValue.append(headerLine.substring(colon + 1).trim());
            p = advancePastLineEnd(bytes, lineEnd, end);
        }

        // -- body, if present --
        // The body runs until "\r\n-------<tid><flag>\r\n" — RFC 4975 §3.1.
        // We search forward for that sentinel; everything before it (less the
        // trailing CRLF that separates body from end-line) is the body.
        int sentinel = findEndLine(bytes, p, end, tid);
        if (sentinel < 0) {
            throw new MsrpException("MSRP parse: no end-line found for tid " + tid);
        }
        // sentinel points at the CRLF immediately preceding "-------<tid>";
        // body is bytes[p .. sentinel).
        int bodyLen = sentinel - p;
        if (bodyLen > 0) {
            byte[] body = new byte[bodyLen];
            System.arraycopy(bytes, p, body, 0, bodyLen);
            b.body(body);
        }
        // Skip the CRLF separator (sentinel..sentinel+crlfLen)
        int crlfLen = (bytes[sentinel] == '\r' && sentinel + 1 < end
                && bytes[sentinel + 1] == '\n') ? 2 : 1;
        p = sentinel + crlfLen;
        int endLineEnd = findLineEnd(bytes, p, end);
        if (endLineEnd < 0) {
            throw new MsrpException("MSRP parse: end-line not CRLF-terminated");
        }
        parseEndLineAndSetFlag(bytes, p, endLineEnd - p, tid, b);
        return b.buildUnchecked();
    }

    /** Find the next line terminator (CRLF or bare LF). Returns the index of CR/LF, or -1. */
    private static int findLineEnd(byte[] bytes, int off, int end) {
        for (int i = off; i < end; i++) {
            byte v = bytes[i];
            if (v == '\r' && i + 1 < end && bytes[i + 1] == '\n') return i;
            if (v == '\n') return i;
        }
        return -1;
    }

    /** Advance past a line-end at index {@code lineEnd}: 2 bytes for CRLF, 1 for bare LF. */
    private static int advancePastLineEnd(byte[] bytes, int lineEnd, int end) {
        if (lineEnd < end && bytes[lineEnd] == '\r'
                && lineEnd + 1 < end && bytes[lineEnd + 1] == '\n') {
            return lineEnd + 2;
        }
        return lineEnd + 1;
    }

    /** Check whether the candidate line is the end-line {@code "-------<tid><flag>"}. */
    private static boolean looksLikeEndLine(byte[] bytes, int off, int len, String tid) {
        int minLen = DASH_COUNT + tid.length() + 1;
        if (len != minLen) return false;
        for (int i = 0; i < DASH_COUNT; i++) {
            if (bytes[off + i] != '-') return false;
        }
        for (int i = 0; i < tid.length(); i++) {
            if (bytes[off + DASH_COUNT + i] != (byte) tid.charAt(i)) return false;
        }
        byte flag = bytes[off + DASH_COUNT + tid.length()];
        return flag == '$' || flag == '+' || flag == '#';
    }

    /**
     * Scan {@code bytes[off..end)} for the CRLF (or LF) directly preceding the
     * MSRP end-line for this transaction. Returns the index of the CR (or LF)
     * of that separator, or -1 if not found.
     */
    private static int findEndLine(byte[] bytes, int off, int end, String tid) {
        // We look for "\r\n-------<tid><flag>" or "\n-------<tid><flag>".
        // First-match by walking the buffer linearly.
        byte[] needle = (DASHES + tid).getBytes(UTF_8);
        int nLen = needle.length;
        for (int i = off; i + nLen + 1 <= end; i++) {
            boolean isCRLF  = (i + 1 < end && bytes[i] == '\r' && bytes[i + 1] == '\n');
            boolean isLF    = (bytes[i] == '\n');
            if (!(isCRLF || isLF)) continue;
            int needleStart = i + (isCRLF ? 2 : 1);
            if (needleStart + nLen >= end) continue;
            boolean match = true;
            for (int k = 0; k < nLen; k++) {
                if (bytes[needleStart + k] != needle[k]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            byte flag = bytes[needleStart + nLen];
            if (flag == '$' || flag == '+' || flag == '#') {
                return i;
            }
        }
        return -1;
    }

    private static void parseEndLineAndSetFlag(byte[] bytes, int off, int len,
            String tid, Builder b) throws MsrpException {
        int minLen = DASH_COUNT + tid.length() + 1;
        if (len < minLen) {
            throw new MsrpException("MSRP parse: end-line too short");
        }
        for (int i = 0; i < DASH_COUNT; i++) {
            if (bytes[off + i] != '-') {
                throw new MsrpException("MSRP parse: end-line missing dashes");
            }
        }
        for (int i = 0; i < tid.length(); i++) {
            if (bytes[off + DASH_COUNT + i] != (byte) tid.charAt(i)) {
                throw new MsrpException("MSRP parse: end-line transaction-id mismatch");
            }
        }
        char flagCh = (char) bytes[off + DASH_COUNT + tid.length()];
        b.endFlag(MsrpEndFlag.fromChar(flagCh));
    }

    private static String decode(byte[] bytes, int off, int len) throws MsrpException {
        try {
            return new String(bytes, off, len, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new MsrpException("UTF-8 unavailable", impossible);
        }
    }

    private static boolean isDigit3(String s) {
        if (s.length() < 3) return false;
        for (int i = 0; i < 3; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        // status-code is exactly 3 digits, optionally followed by SP+comment
        return s.length() == 3 || s.charAt(3) == ' ';
    }

    private static int parseInt(String s, String what) throws MsrpException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            throw new MsrpException("MSRP parse: " + what + " not numeric: " + s, nfe);
        }
    }

    // -------- equality (value-equal for round-trip tests) --------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MsrpMessage)) return false;
        MsrpMessage other = (MsrpMessage) o;
        if (kind != other.kind) return false;
        if (!Objects.equals(transactionId, other.transactionId)) return false;
        if (kind == Kind.REQUEST  && method != other.method) return false;
        if (kind == Kind.RESPONSE && statusCode != other.statusCode) return false;
        if (kind == Kind.RESPONSE && !Objects.equals(statusComment, other.statusComment)) return false;
        if (!headers.equals(other.headers)) return false;
        if (endFlag != other.endFlag) return false;
        return java.util.Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(kind, transactionId, method, statusCode,
                statusComment, headers, endFlag);
        h = 31 * h + java.util.Arrays.hashCode(body);
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MsrpMessage{");
        if (kind == Kind.REQUEST) {
            sb.append(method).append(' ');
        } else {
            sb.append(statusCode).append(' ');
        }
        sb.append("tid=").append(transactionId);
        sb.append(", flag=").append(endFlag);
        sb.append(", headers=").append(headers);
        sb.append(", bodyLen=").append(getBodyLength());
        sb.append('}');
        return sb.toString();
    }

    // -------- Builder --------

    /** Returns a fresh builder for a SEND request with a new random tid. */
    public static Builder newSend() {
        return new Builder().asRequest(MsrpMethod.SEND)
                .transactionId(MsrpTransactionId.next())
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /** Returns a fresh builder for a REPORT request with a new random tid. */
    public static Builder newReport() {
        return new Builder().asRequest(MsrpMethod.REPORT)
                .transactionId(MsrpTransactionId.next())
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /** Returns a fresh builder for a 2xx/4xx/5xx response (no body, no tid yet). */
    public static Builder newResponse(int statusCode, String comment) {
        return new Builder().asResponse(statusCode, comment)
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /**
     * Mutable builder. Header insertion order is preserved (and matches the
     * order Google Messages' encoder uses).
     */
    public static final class Builder {
        private Kind kind;
        private String transactionId;
        private MsrpMethod method;
        private int statusCode;
        private String statusComment;
        private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private MsrpEndFlag endFlag = MsrpEndFlag.COMPLETE;

        public Builder asRequest(MsrpMethod m) {
            this.kind = Kind.REQUEST;
            this.method = m;
            return this;
        }

        public Builder asSend()   { return asRequest(MsrpMethod.SEND); }
        public Builder asReport() { return asRequest(MsrpMethod.REPORT); }
        public Builder asAuth()   { return asRequest(MsrpMethod.AUTH); }

        public Builder asResponse(int code, String comment) {
            this.kind = Kind.RESPONSE;
            this.statusCode = code;
            this.statusComment = comment;
            return this;
        }

        public Builder transactionId(String tid) {
            if (!MsrpTransactionId.isValid(tid)) {
                throw new IllegalArgumentException(
                        "MSRP transaction-id violates RFC 4975 §9 ABNF: " + tid);
            }
            this.transactionId = tid;
            return this;
        }

        /** Set or replace a header. Name is normalized to lowercase for storage. */
        public Builder header(String name, String value) {
            if (name == null || value == null) {
                throw new NullPointerException("MSRP header name/value");
            }
            // Disallow CRLF in header values to prevent injection.
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                        "MSRP header value contains CR/LF: " + name);
            }
            headers.put(name.toLowerCase(Locale.ROOT), value);
            return this;
        }

        public Builder toPath(String value)        { return header(MsrpHeaders.TO_PATH, value); }
        public Builder fromPath(String value)      { return header(MsrpHeaders.FROM_PATH, value); }
        public Builder messageId(String value)     { return header(MsrpHeaders.MESSAGE_ID, value); }
        public Builder contentType(String value)   { return header(MsrpHeaders.CONTENT_TYPE, value); }
        public Builder byteRange(String value)     { return header(MsrpHeaders.BYTE_RANGE, value); }

        /** Set {@code Byte-Range: start-end/total}. Pass {@code -1} for unknown total ('*'). */
        public Builder byteRange(long start, long end, long total) {
            StringBuilder sb = new StringBuilder()
                    .append(start).append('-').append(end).append('/');
            if (total < 0) sb.append('*'); else sb.append(total);
            return byteRange(sb.toString());
        }

        public Builder successReport(boolean yes) {
            return header(MsrpHeaders.SUCCESS_REPORT,
                    yes ? MsrpHeaders.SUCCESS_REPORT_YES : MsrpHeaders.SUCCESS_REPORT_NO);
        }

        public Builder failureReport(String value) {
            return header(MsrpHeaders.FAILURE_REPORT, value);
        }

        /** Set the REPORT-only {@code Status:} header with namespace 000. */
        public Builder status(int code, String comment) {
            String v = MsrpHeaders.STATUS_NAMESPACE_MSRP + " " + code
                    + (comment == null ? "" : " " + comment);
            return header(MsrpHeaders.STATUS, v);
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public Builder endFlag(MsrpEndFlag f) {
            this.endFlag = f;
            return this;
        }

        /** Validate required fields and produce the immutable message. */
        public MsrpMessage build() {
            if (kind == null) {
                throw new IllegalStateException("kind not set");
            }
            if (transactionId == null) {
                throw new IllegalStateException("transactionId not set");
            }
            if (endFlag == null) {
                throw new IllegalStateException("endFlag not set");
            }
            if (kind == Kind.REQUEST && method == MsrpMethod.SEND) {
                // RFC 4975 §7.1: SEND requires Message-ID, To-Path, From-Path.
                requireHeader(MsrpHeaders.TO_PATH);
                requireHeader(MsrpHeaders.FROM_PATH);
                requireHeader(MsrpHeaders.MESSAGE_ID);
            } else if (kind == Kind.REQUEST && method == MsrpMethod.REPORT) {
                // RFC 4975 §7.1.2: REPORT requires To-Path, From-Path,
                // Message-ID, Byte-Range, Status. Success-Report/Failure-Report
                // MUST NOT appear.
                requireHeader(MsrpHeaders.TO_PATH);
                requireHeader(MsrpHeaders.FROM_PATH);
                requireHeader(MsrpHeaders.MESSAGE_ID);
                requireHeader(MsrpHeaders.STATUS);
                if (headers.containsKey("success-report")
                        || headers.containsKey("failure-report")) {
                    throw new IllegalStateException(
                            "REPORT MUST NOT carry Success-Report/Failure-Report (RFC 4975 §7.1.2)");
                }
            }
            return new MsrpMessage(this);
        }

        /** Build without re-validating method-specific headers (parser use). */
        MsrpMessage buildUnchecked() {
            if (kind == null) throw new IllegalStateException("kind not set");
            if (transactionId == null) throw new IllegalStateException("tid not set");
            if (endFlag == null) throw new IllegalStateException("flag not set");
            return new MsrpMessage(this);
        }

        private void requireHeader(String name) {
            if (!headers.containsKey(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("MSRP "
                        + (method == null ? kind : method)
                        + " missing required header: " + name);
            }
        }
    }

    // -------- builder snapshot (for chunker reuse) --------

    /** Internal: copy this message's headers into a fresh builder. */
    Builder toBuilder() {
        Builder b = new Builder();
        b.kind = this.kind;
        b.method = this.method;
        b.statusCode = this.statusCode;
        b.statusComment = this.statusComment;
        b.transactionId = this.transactionId;
        b.endFlag = this.endFlag;
        for (Map.Entry<String, String> e : this.headers.entrySet()) {
            b.headers.put(e.getKey(), e.getValue());
        }
        if (this.body != null) {
            b.body = this.body.clone();
        }
        return b;
    }

}
