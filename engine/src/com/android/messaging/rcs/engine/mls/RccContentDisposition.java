/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Locale;

/**
 * What to do with a decrypted payload, decided from its inner content type. An unrecognised
 * non-text type is {@link #DROP_UNKNOWN}, never rendered, so binary bodies cannot reach the
 * conversation as text; any {@code text/*} still renders.
 */
public final class RccContentDisposition {

    private RccContentDisposition() { }

    /** Render as a message bubble. */
    public static final int TEXT = 0;
    /** Inline image, video or audio bytes: stage and attach. */
    public static final int MEDIA = 1;
    /** File-transfer descriptor: resolve the transfer rather than showing the XML. */
    public static final int FT = 2;
    /** PIDF-LO location push, shown as a place. */
    public static final int LOCATION = 3;
    /** Signalling that is never shown: typing, delivery and read receipts. */
    public static final int DROP_CONTROL = 4;
    /** RCC.16 §7.8.1 or §7.13.4 key delivery. */
    public static final int DROP_KEY = 5;
    /** A non-text type with no route; not rendered. */
    public static final int DROP_UNKNOWN = 6;

    /**
     * Local marker, never on the wire: an RCC.16 §10.3 resend addressed to another member. It takes
     * the drop route, so it is not inserted, receipted or reported as a decryption failure.
     */
    public static final String RESEND_NOT_FOR_ME =
            "application/vnd.lineageos.mls-resend-not-for-me";

    /**
     * Local marker: decrypted but refused for an RCC.16 §7.5.3.1 message-id mis-binding, so it is
     * not treated as a decrypt failure. See {@link MlsInboundRefusal}.
     */
    public static final String REFUSED_AAD_MISBINDING =
            "application/vnd.lineageos.mls-refused-aad-misbinding";

    /**
     * Local marker: decrypted but refused because the signing leaf is certified as someone other
     * than the sender; kept distinct from {@link #REFUSED_AAD_MISBINDING} in logs.
     */
    public static final String REFUSED_IMPERSONATION =
            "application/vnd.lineageos.mls-refused-impersonation";

    /**
     * Local marker from {@link RccMlsBody#parse} for a frameless payload of an unhandled type; it
     * falls to {@link #DROP_UNKNOWN}.
     */
    public static final String UNKNOWN_SECRET_PAYLOAD =
            "application/vnd.lineageos.mls-unknown-secret-payload";

    /** True for every disposition that must not reach the conversation. */
    public static boolean isDrop(final int disposition) {
        return disposition == DROP_CONTROL || disposition == DROP_KEY
                || disposition == DROP_UNKNOWN;
    }

    /** For the log line explaining why something did not appear. */
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
     * Classifies as the provider treats the same type in the clear. An empty or null type is an
     * unframed body, i.e. text.
     */
    public static int classify(final String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return TEXT;
        }
        final String ct = contentType.trim().toLowerCase(Locale.US);

        // Signalling, handled on its own path.
        if (ct.startsWith("application/im-iscomposing+xml")          // typing
                || ct.startsWith("message/imdn+xml")                 // delivery/read receipt
                || ct.equals(RESEND_NOT_FOR_ME)  // RCC.16 §10.3 resend for another member
                || ct.equals(REFUSED_AAD_MISBINDING)  // decrypted, refused (RCC.16 §7.5.3.1)
                || ct.equals(REFUSED_IMPERSONATION)                  // decrypted, refused
                || ct.equals("application/vnd.google.rcs.success")) { // proto IMDN success
            return DROP_CONTROL;
        }
        // RCC.16 §7.8.1 content key: a binary protobuf body.
        if (ct.startsWith(RccFileInfo.CONTENT_TYPE)) {
            return DROP_KEY;
        }
        // RCC.16 §7.13.4 GroupMetadataKeys. A separate arm from FileInfo: different protos and
        // consumers, so a rename of one must not reclassify the other.
        if (ct.startsWith(RccGroupMetadataKeys.CONTENT_TYPE)) {
            return DROP_KEY;
        }
        // RCC.16 §7.8.2 file descriptor type; as a message type it is a transfer, not text.
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
        if (ct.startsWith("text/")) {
            return TEXT;
        }
        return DROP_UNKNOWN;
    }

    /**
     * Trimmed, with type/subtype lower-cased (RFC 2045 §5.1) and parameters as sent; null or blank
     * is returned unchanged.
     */
    public static String canonicalType(final String contentType) {
        if (contentType == null) return null;
        final String trimmed = contentType.trim();
        if (trimmed.isEmpty()) return trimmed;
        final int semi = trimmed.indexOf(';');
        if (semi < 0) return trimmed.toLowerCase(Locale.US);
        return trimmed.substring(0, semi).toLowerCase(Locale.US) + trimmed.substring(semi);
    }

    /** {@code image/}, {@code video/} or {@code audio/}. */
    public static boolean isBinaryMedia(final String contentType) {
        final String ct = canonicalType(contentType);
        if (ct == null || ct.isEmpty()) return false;
        return ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/");
    }

    /**
     * Whether an RCC.16 frame appends {@code ;charset=UTF-8}: textual types ({@code text/*}, XML
     * and JSON) do, others do not. {@code message/*} also does: the key-delivery bodies are binary
     * but have always been sent with a charset, and that wire form is kept.
     */
    public static boolean takesCharset(final String contentType) {
        final String ct = canonicalType(contentType);
        if (ct == null || ct.isEmpty()) return false;
        final int semi = ct.indexOf(';');
        final String bare = (semi < 0) ? ct : ct.substring(0, semi).trim();
        if (bare.startsWith("text/")) return true;
        if (bare.startsWith("message/")) return true;
        if (bare.equals("application/xml") || bare.equals("application/json")) return true;
        if (bare.startsWith("application/") && (bare.endsWith("+xml") || bare.endsWith("+json"))) {
            return true;
        }
        return false;
    }
}
