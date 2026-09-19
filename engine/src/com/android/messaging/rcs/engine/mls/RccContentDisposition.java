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

import java.util.Locale;

/**
 * What to DO with a decrypted RCS payload, decided from its content type.
 *
 * <p>Pure string logic with no Android and no MLS state, so the dispatch every inbound message
 * depends on can be pinned by host tests instead of by shipping a build and watching a thread.
 *
 * <p><b>Why this is its own class.</b> Once a message is MLS-encrypted, the transport can no longer
 * see what is inside it — the outer type is just {@code message/mls}. The receiver decrypts, unframes,
 * and is then holding a content type that has never been routed. Every type we can carry but do not
 * route is the same bug: it falls through to "render as a chat bubble", and the user sees XML,
 * protobuf, or base64 in their conversation. That has already happened once for RCC.16 §7.8.1
 * {@code FileInfo}, on a path we had started sending before we could receive it.
 *
 * <p><b>The default is the important part.</b> An unrecognised <em>non-text</em> type is
 * {@link #DROP_UNKNOWN}, not text. Rendering an unknown MIME type as a bubble is how binary reaches
 * the UI; refusing to renders nothing, which is recoverable and visible in the log. Anything
 * {@code text/*} — including subtypes we have never seen — still renders, because text is text.
 */
public final class RccContentDisposition {

    private RccContentDisposition() { }

    /** Render as a message bubble. */
    public static final int TEXT = 0;
    /** Inline media bytes (image/video/audio) — stage and attach. */
    public static final int MEDIA = 1;
    /** GSMA FT-HTTP descriptor — resolve the transfer rather than showing the XML. */
    public static final int FT = 2;
    /** PIDF-LO location push — render as a place, not as XML. */
    public static final int LOCATION = 3;
    /** Signalling that is never a bubble: typing, delivery/read receipts. */
    public static final int DROP_CONTROL = 4;
    /** RCC.16 §7.8.1 key delivery — a KEY, never a message. */
    public static final int DROP_KEY = 5;
    /** A non-text type we do not route. Deliberately not rendered. */
    public static final int DROP_UNKNOWN = 6;

    /**
     * INTERNAL marker — a §10.3 resend addressed to ANOTHER member of the group.
     *
     * <p>Never on the wire. The decrypt path substitutes it so the message takes the ordinary DROP
     * route: not inserted, not receipted, and — the part that matters — <b>not routed into §10
     * recovery</b>. Returning {@code null} from the decrypt instead would look like a failure and
     * emit a §7.7.2.2 negative receipt for a message that is perfectly healthy and simply not ours,
     * once per non-target member per resend.
     */
    public static final String RESEND_NOT_FOR_ME = "application/vnd.lineageos.mls-resend-not-for-me";

    /**
     * INTERNAL marker — a message that DECRYPTED and was REFUSED for a §7.5.3.1 message-id
     * mis-binding. See {@link MlsInboundRefusal}.
     *
     * <p>Never on the wire, and the reason it exists is the same as {@link #RESEND_NOT_FOR_ME}'s:
     * {@code null} out of the decrypt path means "did not decrypt" and takes the §10 road — a
     * self-heal, a §7.7.2.2 report that is FALSE because we could read the message, and eventually
     * a conversation rebuild.
     */
    public static final String REFUSED_AAD_MISBINDING =
            "application/vnd.lineageos.mls-refused-aad-misbinding";

    /**
     * INTERNAL marker — a message that DECRYPTED and was REFUSED because the leaf that signed it is
     * certified as someone other than the envelope's sender (disposition
     * silently). See {@link MlsInboundRefusal}.
     *
     * <p>Deliberately NOT the same marker as {@link #REFUSED_AAD_MISBINDING}. The disposition is
     * identical but the event is not: one is a framing fault, this one is an authenticated member of
     * the group lying about who it is, and collapsing them would make the second unsearchable.
     */
    public static final String REFUSED_IMPERSONATION =
            "application/vnd.lineageos.mls-refused-impersonation";

    /**
     * INTERNAL marker — a FRAMELESS payload whose 8-byte RCC.16 header declares a payload type we do
     * not handle (option C).
     *
     * <p>Never on the wire. {@link RccMlsBody#parse} substitutes it for the {@code text/plain}
     * fallback it used to return for ANY payload with no MIME frame. That fallback exists for our
     * own legacy raw-text bodies and is right for them; it was also catching structured binary
     * payloads and rendering them as chat bubbles — and, because the TEXT arm is the only arrival
     * shape that receipts, ACKNOWLEDGING them.
     *
     * <p><b>It deliberately has no arm in {@link #classify} and relies on the {@link #DROP_UNKNOWN}
     * default</b>, which is the honest classification: we know the payload is not text and we do not
     * know what it is. Do not promote it to {@link #DROP_KEY} on a guess — that would assert we had
     * identified it. Pinned by test so the default cannot quietly become something else.
     */
    public static final String UNKNOWN_SECRET_PAYLOAD =
            "application/vnd.lineageos.mls-unknown-secret-payload";

    /** True for every disposition that must not reach the conversation. */
    public static boolean isDrop(final int disposition) {
        return disposition == DROP_CONTROL || disposition == DROP_KEY
                || disposition == DROP_UNKNOWN;
    }

    /** Human-readable, for the log line that explains why something did not appear. */
    public static String name(final int disposition) {
        switch (disposition) {
            case TEXT:         return "TEXT";
            case MEDIA:        return "MEDIA";
            case FT:           return "FT";
            case LOCATION:     return "LOCATION";
            case DROP_CONTROL: return "DROP_CONTROL";
            case DROP_KEY:     return "DROP_KEY";
            default:           return "DROP_UNKNOWN";
        }
    }

    /**
     * Classify a decoded content type.
     *
     * <p>Mirrors the provider's plaintext switch (its {@code TachyonTransport} dispatch) so a type
     * carried INSIDE MLS is treated exactly as the same type in the clear — a message must not
     * change meaning because it was encrypted.
     *
     * @param contentType the type from the RCC.16 frame; empty/absent is treated as text, which is
     *                    what an unframed legacy body is
     */
    public static int classify(final String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return TEXT;                                  // unframed legacy body
        }
        final String ct = contentType.trim().toLowerCase(Locale.US);

        // Signalling — handled by its own path, never a bubble.
        if (ct.startsWith("application/im-iscomposing+xml")          // typing
                || ct.startsWith("message/imdn+xml")                 // delivery/read receipt
                || ct.equals(RESEND_NOT_FOR_ME)                      // §10.3 resend for another member
                || ct.equals(REFUSED_AAD_MISBINDING)                 // decrypted, refused (§7.5.3.1)
                || ct.equals(REFUSED_IMPERSONATION)                  // decrypted, refused
                || ct.equals("application/vnd.google.rcs.success")) { // proto IMDN success
            return DROP_CONTROL;
        }
        // RCC.16 §7.8.1 — a content KEY. Its body is binary protobuf; rendering it shows raw bytes.
        if (ct.startsWith(RccFileInfo.CONTENT_TYPE)) {
            return DROP_KEY;
        }
        // §7.13.4 GroupMetadataKeys — the §10.5.3 response and the §8.3.1.3 continuity token. Also
        // a KEY, never a message. Ordered BEFORE the file-info check would be equivalent today, but
        // it is its own arm because the two bodies are different protos with different consumers,
        // and folding them into one prefix match is how a future rename silently reclassifies one.
        if (ct.startsWith(RccGroupMetadataKeys.CONTENT_TYPE)) {
            return DROP_KEY;
        }
        // §7.8.2 sets this inside the file-info XML for the file and its thumbnail. If one ever
        // arrives as a message content type it is a transfer descriptor, not something to render.
        if (ct.startsWith("message/mls-ft")) {
            return FT;
        }
        if (ct.startsWith("application/vnd.gsma.rcs-ft-http+xml")) {
            return FT;
        }
        if (ct.startsWith("application/vnd.gsma.rcspushlocation+xml")) {
            return LOCATION;
        }
        if (isBinaryMedia(ct)) {
            return MEDIA;
        }
        // Text of any subtype still renders — text is text, even a subtype we have not seen.
        if (ct.startsWith("text/")) {
            return TEXT;
        }
        // Everything else: NOT a bubble. See the class doc — this default is the point.
        return DROP_UNKNOWN;
    }

    /**
     * The content type <b>as we act on it</b>: trimmed, and the type/subtype lower-cased.
     *
     * <h2>Why the producer normalises and not each consumer</h2>
     *
     * <p>MIME types are case-insensitive (RFC 2045 §5.1), so a conforming peer may legitimately send
     * {@code IMAGE/JPEG}. {@link #classify} lower-cases, so ROUTING was already correct — but the
     * type it routed on was then handed downstream VERBATIM, and every renderer tested it with a
     * case-sensitive {@code startsWith("image/")}. The message routed as media, the bytes staged
     * correctly, and the attachment rendered as nothing.
     *
     * <p>Inbound casing is the PEER'S choice — the shape neither copy of the fact controls — which
     * is why the fix belongs where the value enters rather than at each place it is read. The
     * consumer list grows; the producer does not.
     *
     * <h2>Parameters keep their case</h2>
     *
     * <p>RFC 2045 §5.1 makes the type, the subtype and parameter NAMES case-insensitive and says
     * nothing of the kind about parameter VALUES — {@code name="Photo.JPG"} means what it says. So
     * only the part before the first {@code ;} is folded, and the remainder is carried through
     * character for character.
     *
     * @return the normalised type, or the input unchanged when it is null or blank — a caller with
     *     nothing to normalise gets back exactly what it had, never a substituted default
     */
    public static String canonicalType(final String contentType) {
        if (contentType == null) return null;
        final String trimmed = contentType.trim();
        if (trimmed.isEmpty()) return trimmed;
        final int semi = trimmed.indexOf(';');
        if (semi < 0) return trimmed.toLowerCase(Locale.US);
        return trimmed.substring(0, semi).toLowerCase(Locale.US) + trimmed.substring(semi);
    }

    /**
     * Is this a binary media type — {@code image/}, {@code video/}, {@code audio/}?
     *
     * <p>Extracted so {@link #classify}'s {@code MEDIA} arm and the framing question below read the
     * same three prefixes from one place. They are still DIFFERENT questions and are still not
     * coupled to each other (see {@link #takesCharset}); what is shared is only the prefix list.
     */
    public static boolean isBinaryMedia(final String contentType) {
        final String ct = canonicalType(contentType);
        if (ct == null || ct.isEmpty()) return false;
        return ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/");
    }

    /**
     * <b>Should an RCC.16 frame append {@code ;charset=UTF-8} to this type?</b>
     *
     * <h2>The rule is POSITIVE: textual types take a charset, and nothing else does</h2>
     *
     * <p>It used to be negative — <i>everything except {@code image/}, {@code video/},
     * {@code audio/}</i> — which is why {@code application/pdf} came out as
     * {@code application/pdf;charset=UTF-8}. A charset on a PDF is exactly as meaningless as one on
     * a JPEG; it fell through only because the predicate enumerated the three types anybody had
     * thought about. A positive rule has the opposite failure mode, and it is the safe one: a type
     * nobody has thought about gets NO charset, which is meaningless-but-absent rather than
     * meaningless-but-present.
     *
     * <p>Stated as a rule rather than a list because the list is what went wrong. {@code text/*} is
     * text. {@code application/*+xml}, {@code application/xml}, {@code application/json} and
     * {@code application/*+json} are text carried under {@code application/} — which is why
     * "{@code application/} is binary" would be wrong in the other direction, and why the bare
     * prefix is deliberately not used here.
     *
     * <h2>{@code message/*} is the exception, and it is an EXCEPTION rather than a case of the rule</h2>
     *
     * <p>Two {@code message/} types ship with the charset and have since 2026-07-29 —
     * {@code message/mls-rcs-file-info} (§7.8.1 key delivery) and
     * {@code message/mls-rcs-group-metadata-keys} (§7.13.4). Both carry BINARY protobuf bodies, so
     * the rule above would drop their charset, and dropping it would change a working wire shape to
     * satisfy a reading of RFC 2045. <b>This project's rule runs the other way: capture trumps
     * docs.</b> The binary classes below have never shipped, so they have no shape to preserve and
     * are fixed on their merits; these two have one and keep it.
     *
     * <p><b>What would settle it</b> is a Google Messages peer's own §7.8.1 FileInfo header block, read
     * verbatim out of {@code RccMlsBody.Parsed.headers} — which is retained for exactly this. The
     * instrument already exists ({@code MlsProviderTransport} logs {@code HEADERS<<…>>} for every
     * decrypted inbound MLS message under {@code debug.rcs.mls_log_plaintext}); what is missing is
     * the event, since only a Google Messages group icon or subject change delivers one. Until then this
     * exception is what we have evidence for and the rule is what we have argument for, and the two
     * are marked apart rather than blended.
     */
    public static boolean takesCharset(final String contentType) {
        final String ct = canonicalType(contentType);
        if (ct == null || ct.isEmpty()) return false;
        final int semi = ct.indexOf(';');
        final String bare = (semi < 0) ? ct : ct.substring(0, semi).trim();
        if (bare.startsWith("text/")) return true;
        // THE SHIPPING EXCEPTION — see the javadoc. Not a case of the rule.
        if (bare.startsWith("message/")) return true;
        if (bare.equals("application/xml") || bare.equals("application/json")) return true;
        if (bare.startsWith("application/") && (bare.endsWith("+xml") || bare.endsWith("+json"))) {
            return true;
        }
        return false;
    }
}
