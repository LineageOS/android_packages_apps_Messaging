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
package com.android.messaging.rcs.e2ee;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;

/**
 * GSMA RCC.16 MLS <b>application-message body</b> framing — the transport-independent "generic MLS
 * handling" layer. An MLS application message is a self-describing MIME entity so the E2EE peer
 * (which the network cannot inspect) can tell text vs image vs a receipt vs a file. This wrapper is
 * REQUIRED for cross-platform interop (Apple &lt;-&gt; Google &lt;-&gt; us); raw text only works when
 * both ends are ours. Framing/parsing is done HERE, above any transport (carrier CPM/MSRP or the
 * Tachyon provider AIDL), so an MLS message hits the transport already framed and leaves it still
 * framed — the transport only ever carries opaque encrypted bytes.
 *
 * <p>Wire format (observed between Google Messages and Apple, and spec-standard):
 * <pre>
 *   00 01 00 01 00 00 00 02   fixed header: version=1, format=1, content-type-enum (2 = text)
 *   &lt;MLS varint&gt;              QUIC/RFC-9000 var-int = byte length of the content section below
 *   \r\n                       (content starts with CRLF)
 *   Content-Type: &lt;mime&gt;\r\n
 *   Content-Disposition: &lt;inline|attachment&gt;\r\n
 *   Content-Length: &lt;n&gt;\r\n
 *   \r\n                       blank line
 *   &lt;body&gt;                     n bytes
 * </pre>
 * The Content-Type is app-routing metadata: {@code text/plain} -&gt; bubble, {@code image/*} -&gt;
 * media, {@code message/imdn+xml} -&gt; a delivery/read RECEIPT (processed, never shown), etc.
 */
public final class RccMlsBody {
    private RccMlsBody() {}

    /**
     * The 8-byte header: {@code [00 01][00 01][uint32 counter]}.
     *
     * <p>CORRECTION: that trailing uint32 is NOT a
     * content-type enum. It is a PER-MESSAGE COUNTER that must equal the MLS
     * {@code sender_data.generation} of the message. Captured from Google Messages sending five
     * consecutive TEXT messages in one epoch, the field walked {@code 00000000, 00000001, 00000002,
     * 00000003, 00000004} — a content-type enum would have stayed constant. The peer cross-checks the two
     * and rejects a mismatch, which is what made every message after the first fail to decrypt.
     * (Content type is carried by the CPIM/MIME headers below, and the receiver routes on those.)
     *
     * <p>The value here is therefore only a PLACEHOLDER: this layer cannot know the generation, which
     * lives in the MLS sender ratchet and is only final at encrypt time. Do not "fix" this constant
     * here — it is intentionally inert.
     *
     * <p><b>WHO STAMPS IT.</b> This said the provider overwrites bytes 4..7 in
     * {@code MlsMessageTransport.stampBodyGeneration}. <b>No such method exists</b>, and it names the
     * wrong class in the wrong repo. The stamper is
     * {@link com.android.messaging.rcs.engine.mls.MlsRecoveryPolicy#stampBodyGeneration}, and its only
     * production caller is {@code MlsProviderTransport.encryptForSend} — the <b>Tachyon</b> leg.
     *
     * <p><b>STAMPING IS PER-TRANSPORT, AND FRAMING WITHOUT IT IS A REGRESSION.</b> The old wording
     * read as "somebody downstream handles this", which is true on Tachyon and was <em>false on the
     * carrier leg</em>: {@code MlsCarrierTransport.encryptForSend} had no generation handling at all.
     * Framing there without stamping would have put a constant {@code 0} in bytes 4..7 while
     * {@code sender_data.generation} walked 0,1,2,… — so the first message of an epoch decrypts and
     * every one after it fails, on a peer that cross-checks the two. That is worse than
     * sending unframed, because {@link #parse} returns a frameless payload verbatim and unframed
     * therefore still works between two of our own clients.
     *
     * <p>So: <b>a transport that frames MUST stamp.</b> If you add a third one, wire
     * {@code MlsSession.nextAppGen(groupId)} into its encrypt path before you call {@link #frame}.
     */
    private static final byte[] HEADER_TEXT = {0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};

    /** Bytes 0..1 of the 8-byte header. Every payload of this shape, ours or a peer's, carries it. */
    private static final int HEADER_VERSION_1 = 0x0001;

    /**
     * Bytes 2..3 of the 8-byte header, as we emit them.
     *
     * <p><b>TWO NAMES FOR ONE FIELD, and neither is ours to settle.</b> This class's own wire-format
     * doc calls it {@code format}; the RCC.16 SecretPayload shape calls
     * it the payload {@code type} and reports Google Messages' group-metadata-keys body as type 4. Both
     * descriptions agree on the OFFSET and on the value we send, which is all {@link #parse} acts on:
     * it only ever asks "is this the value we know", never "what does 4 mean". The day someone
     * captures a real one, the name follows the capture.
     */
    private static final int SECRET_PAYLOAD_TYPE_TEXT = 0x0001;

    /**
     * The payload type declared by the 8-byte header, or {@code -1} when the payload does not carry
     * one.
     *
     * <p><b>Why the version check is what makes this safe.</b> The frameless fallback has to serve
     * two populations: our own legacy RAW-TEXT bodies, which must keep rendering, and structured
     * binary payloads, which must not. UTF-8 text cannot begin {@code 00 01} — those are NUL and SOH
     * — so requiring bytes 0..1 to be the header version separates the two without guessing, and a
     * payload that is neither still takes the historical {@code text/plain} road unchanged.
     *
     * <p>A payload that merely happens to start {@code 00 01} and is not this header is not a new
     * hazard: it was already being rendered as a bubble, and the worst this can do is route it to
     * {@link RccContentDisposition#DROP_UNKNOWN} instead — quieter, logged, and not receipted.
     */
    static int secretPayloadType(final byte[] p) {
        if (p == null || p.length < 8) return -1;
        if ((((p[0] & 0xff) << 8) | (p[1] & 0xff)) != HEADER_VERSION_1) return -1;
        return ((p[2] & 0xff) << 8) | (p[3] & 0xff);
    }

    /**
     * The disposition for a payload with NO usable MIME frame.
     *
     * <p>This used to be an unconditional {@code new Parsed("text/plain", payload)}, and that single
     * line was the live defect: {@link #parse} never read the header's type field at
     * all, so a peer's structured binary body became a chat bubble containing its own framing bytes
     * AND was acknowledged — {@code RcsCallbackRouter} hardcodes {@code wantsDeliveredImdn=true} on
     * the insert path, and the drop arms return before reaching it, so the TEXT arm is the only
     * arrival shape that receipts. Answering "delivered" to a message we displayed as garbage is
     * worse than dropping it, because it tells the sender it worked.
     *
     * <p><b>WHAT THIS DOES NOT ESTABLISH, and it must not be reported as more.</b> It is NOT
     * established that Google Messages' group-metadata-keys plaintext is a type-4 payload, or that it
     * carries this 8-byte header at all — the input there is the spec shape, not
     * Google Messages' bytes, and no Google Messages peer has ever sent us one. If Google Messages sends a bare proto with
     * no header this returns {@code -1} and the fix does not fire. It is worth doing for the CLASS
     * of defect; the Google Messages case closes when a real one is captured.
     */
    private static Parsed frameless(final byte[] payload) {
        final int type = secretPayloadType(payload);
        if (type >= 0 && type != SECRET_PAYLOAD_TYPE_TEXT) {
            return new Parsed(RccContentDisposition.UNKNOWN_SECRET_PAYLOAD, payload);
        }
        return new Parsed("text/plain", payload);
    }

    /** Parsed {contentType, body} from a decrypted MLS application payload. */
    public static final class Parsed {
        public final String contentType;
        public final byte[] body;
        /**
         * The RAW header block, verbatim, or {@code ""} when the payload carried no MIME frame.
         *
         * <p>Retained because parsing headers only to find {@code Content-Type} and then dropping
         * them loses information we are already receiving. A real MLS REACTION is an ORDINARY
         * application message — outer {@code message/mls}, inner {@code text/plain}, body = the
         * literal emoji — and the thing that makes it a reaction (which message, ADD vs REMOVE)
         * rides in these headers (captured from Google Messages 2026-08-20). A ❤️ is four
         * UTF-8 bytes and the observed payload was 234, so the remainder is here.
         *
         * <p>Kept as the raw block rather than a parsed map on purpose: we do not yet know the exact
         * header names Google Messages uses, and inventing a schema before reading one is how this project
         * has most often gone wrong. Callers that need a field should parse what they can name.
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
     * Frame {@code body} (already the raw content bytes) with {@code contentType} as an RCC.16 MLS
     * application entity, ready to be MLS-encrypted by whatever transport carries it.
     */
    public static byte[] frame(final byte[] body, final String contentType,
            final boolean inline) {
        final byte[] b = body == null ? new byte[0] : body;
        // TRIM BOTH HALVES, LOWERCASE ONLY THE PREDICATE.
        //
        // frame() did neither while RccContentDisposition.classify does both, so the containment
        // invariant "anything routed as inline media is framed without a charset" was VIOLATED for
        // any type that was not already lower-cased and trimmed:
        //     classify("IMAGE/JPEG")  -> MEDIA
        //     frame(..,"IMAGE/JPEG")  -> Content-Type: IMAGE/JPEG;charset=UTF-8
        // and " image/jpeg " emitted `Content-Type:  image/jpeg ;charset=UTF-8`, whose trailing
        // space before the ';' is malformed as a header value quite apart from the charset.
        //
        // WHY THE TWO NORMALISATIONS ARE TREATED DIFFERENTLY, because it is not symmetry:
        //  - TRIM applies to the EMITTED value too. Surrounding whitespace in a header value is
        //    malformed however you read RFC 2045, and no shipping caller passes one (every existing
        //    content type here is a string literal), so trimming cannot change a shape that ships.
        //  - LOWERCASE applies to the PREDICATE ONLY. Emitting a lower-cased value would change what
        //    goes on the wire for any caller that passed mixed case, and this file's whole charset
        //    boundary is drawn on "do not change a shipping wire shape". In practice every shipping
        //    type is already lowercase so it would be a no-op — but predicate-only does not need
        //    that argument to be true.
        //
        // Reachability, rated honestly: Android MIME strings from ContentResolver.getType() and
        // MimeTypeMap are conventionally lowercase, so this is unlikely to fire from a gallery pick.
        // But RcsOutgoingMessage.contentType is a plain String crossing a Parcelable seam and the
        // caller feeds it straight in — the guarantee is a CONVENTION, not a contract.
        final String ct = (contentType == null || contentType.trim().isEmpty())
                ? "text/plain" : contentType.trim();
        // WHICH TYPES TAKE A CHARSET IS THE ENGINE'S ANSWER, NOT THIS FILE'S.
        //
        // It used to be a predicate here — image/ | video/ | audio/ get none, everything else gets
        // one — which was byte-identical to RccContentDisposition.classify's MEDIA arm with nothing
        // making the two agree. That is two copies of one fact in two modules, and this codebase has
        // a documented instance of it going wrong: MlsContentRoute exists because the two legs
        // answered "which content types are MLS?" differently.
        //
        // AND THE NEGATIVE FORM WAS WRONG ANYWAY, which is the part worth keeping in mind before
        // anyone moves it back. It enumerated the three binary types somebody had thought about, so
        // application/pdf — equally binary, equally charset-less — came out as
        // `application/pdf;charset=UTF-8`. RccContentDisposition.takesCharset states the rule
        // positively instead (textual types take a charset, nothing else does) and carries the ONE
        // exception with its evidence: message/* keeps its charset because two of them SHIP.
        //
        // The two questions are still NOT coupled. takesCharset is not classify: application/pdf
        // classifies DROP_UNKNOWN and takes no charset, and CarrierOutboundFramingTest pins
        // CONTAINMENT rather than equality for exactly that reason.
        final boolean charset = RccContentDisposition.takesCharset(ct);
        // A TYPE THAT ALREADY CARRIES PARAMETERS KEEPS THEM AND GETS NOTHING APPENDED.
        //
        // Without this, "text/plain;charset=utf-8" went out as
        // "text/plain;charset=utf-8;charset=UTF-8" — a DUPLICATE parameter, which RFC 2045 does not
        // permit and a strict peer may resolve either way or reject.
        //
        // FOUND BY DERIVING THE TEST DIMENSIONS FROM RFC 2045 §5.1 RATHER THAN FROM MEMORY. The
        // checklist this file was hardened against an hour earlier — case, whitespace, ordering,
        // encoding — did not contain "parameters already present", because a remembered list is
        // itself a chosen input set. The grammar has four dimensions and names this one.
        //
        // The rule is also the conservative one on its own merits: a caller that supplied
        // parameters was EXPLICIT, and appending to their parameter list overrides an explicit
        // choice — the same class of error as changing a shipping wire shape.
        //
        // No shipping shape changes: every production caller passes a bare type
        // (frameText's "text/plain", RccFileInfo.CONTENT_TYPE, RccGroupMetadataKeys.CONTENT_TYPE),
        // so all three still receive the charset exactly as before.
        final boolean hasParameters = ct.indexOf(';') >= 0;
        final String ctHeader = (!charset || hasParameters) ? ct : ct + ";charset=UTF-8";
        // Content section (what the length var-int measures): leading CRLF, headers, blank line, body.
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

    /** Convenience: frame a UTF-8 text body. */
    public static byte[] frameText(final String text) {
        return frame(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8),
                "text/plain", /*inline=*/ true);
    }

    /**
     * Parse a decrypted MLS application payload into {contentType, body}. Robust to the fixed header
     * / var-int prefix (it locates the header/body separator + honors Content-Length). If there is
     * no MIME frame at all the payload is returned verbatim, as {@code text/plain} when it is a
     * legacy raw-text message from our own older client and as
     * {@link RccContentDisposition#UNKNOWN_SECRET_PAYLOAD} when its 8-byte header declares a payload
     * type we do not handle — see {@link #frameless}. Frameless text still renders; frameless binary
     * no longer becomes a bubble, and is no longer receipted.
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
        if (sep < 0) return frameless(payload); // no MIME frame -> raw, or a typed binary body
        final String headers = new String(payload, 0, sep, StandardCharsets.UTF_8);
        if (!headers.toLowerCase(Locale.US).contains("content-type")) {
            // A blank-line pair with no Content-Type above it is not a frame. Binary can contain
            // CRLFCRLF by coincidence, so this arm is reachable by exactly the payloads the arm
            // above is for, and it gets the same treatment rather than a second, softer default.
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
                try { contentLength = Integer.parseInt(val); } catch (final NumberFormatException ignore) {}
            }
        }
        int start = sep + seplen;
        int len = payload.length - start;
        if (contentLength >= 0 && contentLength <= len) len = contentLength;
        final byte[] body = new byte[len];
        System.arraycopy(payload, start, body, 0, len);
        return new Parsed(contentType, body, headers);
    }

    /** Write an MLS/QUIC (RFC 9000 §16 / RFC 9420) variable-length integer. */
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
