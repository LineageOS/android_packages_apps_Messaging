/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * RCC.16 framing of an MLS application-message body as a self-describing MIME entity, applied
 * above any transport so the transport only carries opaque encrypted bytes:
 * <pre>
 *   00 01 00 01 &lt;uint32 counter&gt;   version 1, payload type 1, then the message's generation
 *   &lt;MLS varint&gt;                   byte length of the content section below
 *   \r\n Content-Type: &lt;mime&gt;\r\n Content-Disposition: &lt;inline|attachment&gt;\r\n
 *   Content-Length: &lt;n&gt;\r\n \r\n &lt;body&gt;
 * </pre>
 * Receivers route on the inner Content-Type ({@link RccContentDisposition}).
 */
public final class RccMlsBody {
    private RccMlsBody() {}

    /**
     * The 8-byte header. Bytes 4..7 must equal the message's MLS {@code sender_data.generation},
     * which peers cross-check; it is only known at encrypt time, so this is a zero placeholder. A
     * transport that frames must overwrite it ({@link MlsRecoveryPolicy#stampBodyGeneration} with
     * {@code MlsSession.nextAppGen}) before encrypting, or every message after an epoch's first
     * fails to decrypt at the peer.
     */
    private static final byte[] HEADER_TEXT = {0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};

    /** Bytes 0..1 of the header, on every payload of this shape. */
    private static final int HEADER_VERSION_1 = 0x0001;

    /**
     * Bytes 2..3 as emitted: the payload type (RCC.16 SecretPayload), 1 for text. {@link #parse}
     * only compares against this value.
     */
    private static final int SECRET_PAYLOAD_TYPE_TEXT = 0x0001;

    /**
     * The header's payload type, or {@code -1} if bytes 0..1 are not {@code 00 01}, which UTF-8
     * text cannot start with.
     */
    static int secretPayloadType(final byte[] p) {
        if (p == null || p.length < 8) return -1;
        if ((((p[0] & 0xff) << 8) | (p[1] & 0xff)) != HEADER_VERSION_1) return -1;
        return ((p[2] & 0xff) << 8) | (p[3] & 0xff);
    }

    /**
     * A payload with no usable MIME frame: a non-text header type becomes
     * {@link RccContentDisposition#UNKNOWN_SECRET_PAYLOAD} (dropped, not receipted); anything else
     * is a raw-text body.
     */
    private static Parsed frameless(final byte[] payload) {
        final int type = secretPayloadType(payload);
        if (type >= 0 && type != SECRET_PAYLOAD_TYPE_TEXT) {
            return new Parsed(RccContentDisposition.UNKNOWN_SECRET_PAYLOAD, payload);
        }
        return new Parsed("text/plain", payload);
    }

    /** {contentType, body} from a decrypted application payload. */
    public static final class Parsed {
        public final String contentType;
        public final byte[] body;
        /**
         * The raw header block, or {@code ""} when there was no MIME frame. Kept unparsed so
         * callers can read headers this class does not name.
         */
        public final String headers;
        public Parsed(final String contentType, final byte[] body) {
            this(contentType, body, "");
        }
        public Parsed(final String contentType, final byte[] body, final String headers) {
            this.contentType = contentType; this.body = body;
            this.headers = headers == null ? "" : headers;
        }
    }

    /**
     * Frames {@code body} as an RCC.16 application entity, ready for encryption. The generation
     * bytes are a placeholder; see {@link #HEADER_TEXT}.
     */
    public static byte[] frame(final byte[] body, final String contentType,
            final boolean inline) {
        final byte[] b = body == null ? new byte[0] : body;
        // Trimmed; case is kept as given (only the charset test lower-cases).
        final String ct = (contentType == null || contentType.trim().isEmpty())
                ? "text/plain" : contentType.trim();
        // Which types take a charset is RccContentDisposition's rule, not a copy here.
        final boolean charset = RccContentDisposition.takesCharset(ct);
        // A type that already has parameters gets nothing appended: a second charset is a duplicate
        // parameter (RFC 2045 §5.1).
        final boolean hasParameters = ct.indexOf(';') >= 0;
        final String ctHeader = (!charset || hasParameters) ? ct : ct + ";charset=UTF-8";
        // The content section the varint measures: leading CRLF, headers, blank line, body.
        final byte[] headers = ("\r\n"
                + "Content-Type: " + ctHeader + "\r\n"
                + "Content-Disposition: " + (inline ? "inline" : "attachment") + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(headers, 0, headers.length);
        content.write(b, 0, b.length);
        final byte[] c = content.toByteArray();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER_TEXT, 0, HEADER_TEXT.length);
        writeVarint(out, c.length);
        out.write(c, 0, c.length);
        return out.toByteArray();
    }

    /** Frames a UTF-8 text body. */
    public static byte[] frameText(final String text) {
        return frame(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8),
                "text/plain", /*inline=*/ true);
    }

    /**
     * Parses a decrypted application payload, honouring Content-Length. A payload with no MIME
     * frame is returned verbatim via {@link #frameless}.
     */
    public static Parsed parse(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new Parsed("text/plain", payload == null ? new byte[0] : payload);
        }
        int sep = -1, seplen = 0;
        for (int i = 0; i + 1 < payload.length; i++) {
            if (payload[i] == '\r' && i + 3 < payload.length
                    && payload[i + 1] == '\n' && payload[i + 2] == '\r' && payload[i + 3] == '\n') {
                sep = i; seplen = 4; break;
            }
            if (payload[i] == '\n' && payload[i + 1] == '\n') { sep = i; seplen = 2; break; }
        }
        if (sep < 0) return frameless(payload);
        final String headers = new String(payload, 0, sep, StandardCharsets.UTF_8);
        if (!headers.toLowerCase(Locale.US).contains("content-type")) {
            // No Content-Type above the blank line: not a frame (binary can contain one).
            return frameless(payload);
        }
        String contentType = "text/plain";
        int contentLength = -1;
        for (final String line : headers.split("\r\n|\n")) {
            final int c = line.indexOf(':');
            if (c <= 0) continue;
            final String name = line.substring(0, c).trim().toLowerCase(Locale.US);
            final String val = line.substring(c + 1).trim();
            if (name.equals("content-type")) {
                final int semi = val.indexOf(';');
                contentType = (semi > 0 ? val.substring(0, semi) : val).trim();
            } else if (name.equals("content-length")) {
                try { contentLength =
                        Integer.parseInt(val); } catch (final NumberFormatException ignore) {}
            }
        }
        int start = sep + seplen;
        int len = payload.length - start;
        if (contentLength >= 0 && contentLength <= len) len = contentLength;
        final byte[] body = new byte[len];
        System.arraycopy(payload, start, body, 0, len);
        return new Parsed(contentType, body, headers);
    }

    /** Writes an MLS (RFC 9000 §16 / RFC 9420) variable-length integer. */
    static void writeVarint(final ByteArrayOutputStream out, final long n) {
        if (n < 0x40L) {
            out.write((int) n);
        } else if (n < 0x4000L) {
            out.write((int) (0x40 | (n >> 8))); out.write((int) (n & 0xff));
        } else if (n < 0x40000000L) {
            out.write((int) (0x80 | (n >> 24))); out.write((int) ((n >> 16) & 0xff));
            out.write((int) ((n >> 8) & 0xff)); out.write((int) (n & 0xff));
        } else {
            out.write((int) (0xc0 | (n >> 56))); out.write((int) ((n >> 48) & 0xff));
            out.write((int) ((n >> 40) & 0xff)); out.write((int) ((n >> 32) & 0xff));
            out.write((int) ((n >> 24) & 0xff)); out.write((int) ((n >> 16) & 0xff));
            out.write((int) ((n >> 8) & 0xff)); out.write((int) (n & 0xff));
        }
    }
}
