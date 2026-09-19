/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * One MSRP frame (RFC 4975 §9), request or response, immutable. A request starts
 * {@code MSRP <tid> <method>}; a response starts {@code MSRP <tid> <code> [comment]} and has no
 * body. Both share the header table and the end-line {@code -------<tid><flag>}.
 */
public final class MsrpMessage {

    /** RFC 4975 §3.1. */
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final byte[] CRLF = {'\r', '\n'};
    /** RFC 4975 §9: the end-line starts with seven dashes. */
    public static final String DASHES = "-------";
    public static final int DASH_COUNT = 7;
    private static final String START_TAG = "MSRP";

    public enum Kind { REQUEST, RESPONSE }

    private final Kind kind;
    private final String transactionId;
    private final MsrpMethod method;       // non-null iff REQUEST
    private final int statusCode;          // iff RESPONSE
    private final String statusComment;    // RESPONSE only, optional
    private final LinkedHashMap<String, String> headers; // keys lower-cased
    private final byte[] body;             // null when there is no body
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

    public Kind getKind()                  { return kind; }
    public String getTransactionId()       { return transactionId; }
    public MsrpMethod getMethod()          { return method; }
    public int getStatusCode()             { return statusCode; }
    public String getStatusComment()       { return statusComment; }
    public MsrpEndFlag getEndFlag()        { return endFlag; }

    public boolean isRequest()  { return kind == Kind.REQUEST;  }
    public boolean isResponse() { return kind == Kind.RESPONSE; }

    /** Case-insensitive; null if absent. */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Unmodifiable, in insertion order, with lower-cased names. */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    /** Null when absent. */
    public String getToPath()        { return getHeader(MsrpHeaders.TO_PATH); }
    public String getFromPath()      { return getHeader(MsrpHeaders.FROM_PATH); }
    public String getMessageId()     { return getHeader(MsrpHeaders.MESSAGE_ID); }
    public String getContentType()   { return getHeader(MsrpHeaders.CONTENT_TYPE); }
    public String getByteRange()     { return getHeader(MsrpHeaders.BYTE_RANGE); }
    public String getSuccessReport() { return getHeader(MsrpHeaders.SUCCESS_REPORT); }
    public String getFailureReport() { return getHeader(MsrpHeaders.FAILURE_REPORT); }
    public String getStatus()        { return getHeader(MsrpHeaders.STATUS); }

    /** A copy, or null if there is no body. */
    public byte[] getBody() {
        return body == null ? null : body.clone();
    }

    public int getBodyLength() {
        return body == null ? 0 : body.length;
    }

    /**
     * The frame's wire bytes (RFC 4975 §9). Headers are written in insertion order with canonical
     * case; a frame with a body gets a blank line before it and a CRLF after it, and a bodiless
     * frame goes straight from the last header to the end-line.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256
                + (body == null ? 0 : body.length));
        try {
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

            for (Map.Entry<String, String> e : headers.entrySet()) {
                String name = canonicalCase(e.getKey());
                out.write((name + ": " + e.getValue()).getBytes(UTF_8));
                out.write(CRLF);
            }

            if (body != null && body.length > 0) {
                out.write(CRLF);
                out.write(body);
                out.write(CRLF);
            }

            out.write((DASHES + transactionId).getBytes(UTF_8));
            out.write(endFlag.asChar());
            out.write(CRLF);
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        return out.toByteArray();
    }

    /** The preferred spelling of a known header; unknown names pass through lower-cased. */
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
            default:                return lowered;
        }
    }

    /**
     * Parses exactly one complete frame; {@link MsrpFrameReader} splits a stream. Accepts CRLF and
     * bare LF line ends.
     *
     * @throws MsrpException if the bytes are not a well-formed frame
     */
    public static MsrpMessage parse(byte[] bytes) throws MsrpException {
        return parse(bytes, 0, bytes.length);
    }

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
            MsrpMethod m = MsrpMethod.parse(tail);
            b = new Builder().asRequest(m).transactionId(tid);
        }

        // Folded continuation lines are accepted (RFC 4975 §9 imports the SIP grammar).
        String pendingName = null;
        StringBuilder pendingValue = new StringBuilder();
        while (true) {
            int lineEnd = findLineEnd(bytes, p, end);
            if (lineEnd < 0) {
                throw new MsrpException("MSRP parse: unterminated headers");
            }
            // An empty line separates the headers from a body; a bodiless frame goes straight to
            // its end-line instead.
            if (lineEnd == p) {
                if (pendingName != null) {
                    b.header(pendingName, pendingValue.toString().trim());
                }
                p = advancePastLineEnd(bytes, lineEnd, end);
                break;
            }

            if (looksLikeEndLine(bytes, p, lineEnd - p, tid)) {
                if (pendingName != null) {
                    b.header(pendingName, pendingValue.toString().trim());
                }
                parseEndLineAndSetFlag(bytes, p, lineEnd - p, tid, b);
                p = advancePastLineEnd(bytes, lineEnd, end);
                return b.buildUnchecked();
            }

            // A line starting with SP or HT continues the previous value.
            byte first = bytes[p];
            if ((first == ' ' || first == '\t') && pendingName != null) {
                pendingValue.append(' ')
                        .append(decode(bytes, p, lineEnd - p).trim());
                p = advancePastLineEnd(bytes, lineEnd, end);
                continue;
            }

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

        // The body runs to the line break before "-------<tid><flag>" (RFC 4975 §3.1).
        int sentinel = findEndLine(bytes, p, end, tid);
        if (sentinel < 0) {
            throw new MsrpException("MSRP parse: no end-line found for tid " + tid);
        }
        int bodyLen = sentinel - p;
        if (bodyLen > 0) {
            byte[] body = new byte[bodyLen];
            System.arraycopy(bytes, p, body, 0, bodyLen);
            b.body(body);
        }
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

    /** The index of the next CRLF or bare LF, or -1. */
    private static int findLineEnd(byte[] bytes, int off, int end) {
        for (int i = off; i < end; i++) {
            byte v = bytes[i];
            if (v == '\r' && i + 1 < end && bytes[i + 1] == '\n') return i;
            if (v == '\n') return i;
        }
        return -1;
    }

    /** Past a line end: 2 bytes for CRLF, 1 for LF. */
    private static int advancePastLineEnd(byte[] bytes, int lineEnd, int end) {
        if (lineEnd < end && bytes[lineEnd] == '\r'
                && lineEnd + 1 < end && bytes[lineEnd + 1] == '\n') {
            return lineEnd + 2;
        }
        return lineEnd + 1;
    }

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

    /** The index of the line break before this transaction's end-line, or -1. */
    private static int findEndLine(byte[] bytes, int off, int end, String tid) {
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
        // exactly three digits, then end or SP
        return s.length() == 3 || s.charAt(3) == ' ';
    }

    private static int parseInt(String s, String what) throws MsrpException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            throw new MsrpException("MSRP parse: " + what + " not numeric: " + s, nfe);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MsrpMessage)) return false;
        MsrpMessage other = (MsrpMessage) o;
        if (kind != other.kind) return false;
        if (!Objects.equals(transactionId, other.transactionId)) return false;
        if (kind == Kind.REQUEST  && method != other.method) return false;
        if (kind == Kind.RESPONSE && statusCode != other.statusCode) return false;
        if (kind == Kind.RESPONSE
                && !Objects.equals(statusComment, other.statusComment)) return false;
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

    /** A SEND with a fresh transaction id. */
    public static Builder newSend() {
        return new Builder().asRequest(MsrpMethod.SEND)
                .transactionId(MsrpTransactionId.next())
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /** A REPORT with a fresh transaction id. */
    public static Builder newReport() {
        return new Builder().asRequest(MsrpMethod.REPORT)
                .transactionId(MsrpTransactionId.next())
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /** A response; the caller sets the transaction id of the request it answers. */
    public static Builder newResponse(int statusCode, String comment) {
        return new Builder().asResponse(statusCode, comment)
                .endFlag(MsrpEndFlag.COMPLETE);
    }

    /** Header insertion order is wire order. */
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

        /** Sets or replaces a header; names are stored lower-cased. CR and LF are rejected. */
        public Builder header(String name, String value) {
            if (name == null || value == null) {
                throw new NullPointerException("MSRP header name/value");
            }
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
        public Builder contentType(String value)   { return header(MsrpHeaders.CONTENT_TYPE,
                value); }
        public Builder byteRange(String value)     { return header(MsrpHeaders.BYTE_RANGE, value); }

        /** A {@code total} of -1 is written as {@code *}. */
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

        /** The REPORT {@code Status} header in namespace 000. */
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
                // RFC 4975 §7.1.
                requireHeader(MsrpHeaders.TO_PATH);
                requireHeader(MsrpHeaders.FROM_PATH);
                requireHeader(MsrpHeaders.MESSAGE_ID);
            } else if (kind == Kind.REQUEST && method == MsrpMethod.REPORT) {
                // RFC 4975 §7.1.2: a REPORT carries no Success-Report or Failure-Report.
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

        /** Skips the method-specific header checks; for the parser. */
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

    /** A builder holding a copy of this message; for the chunker. */
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
