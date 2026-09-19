/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import java.util.Locale;

/**
 * What to do with an inbound RCS payload, decided from its content type. An unrecognised non-text
 * type is {@link #DROP_UNKNOWN}, never rendered, so binary bodies cannot reach the conversation as
 * text; any {@code text/*} still renders.
 */
public final class RcsContentDisposition {

    private RcsContentDisposition() { }

    /** Render as a message bubble. */
    public static final int TEXT = 0;
    /** Inline image, video or audio bytes: stage and attach. */
    public static final int MEDIA = 1;
    /** GSMA FT-HTTP descriptor: resolve the transfer rather than showing the XML. */
    public static final int FT = 2;
    /** PIDF-LO location push, shown as a place. */
    public static final int LOCATION = 3;
    /** Signalling that is never a bubble: typing, delivery/read receipts. */
    public static final int DROP_CONTROL = 4;
    /** A key-delivery payload; never a message. */
    public static final int DROP_KEY = 5;
    /** A non-text type we do not route. Deliberately not rendered. */
    public static final int DROP_UNKNOWN = 6;





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
     * Classifies as the provider treats the same type in the clear. An empty or null type is an
     * unframed body, i.e. text.
     */
    public static int classify(final String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return TEXT;                                  // unframed legacy body
        }
        final String ct = contentType.trim().toLowerCase(Locale.US);

        // Signalling, handled on its own path.
        if (ct.startsWith("application/im-iscomposing+xml")          // typing
                || ct.startsWith("message/imdn+xml")                 // delivery/read receipt
                || ct.equals("application/vnd.google.rcs.success")) { // proto IMDN success
            return DROP_CONTROL;
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
        // Text of any subtype renders, including one not seen before.
        if (ct.startsWith("text/")) {
            return TEXT;
        }
        // Everything else is dropped; see the class doc.
        return DROP_UNKNOWN;
    }

    /**
     * Trimmed, with type/subtype lower-cased (RFC 2045 §5.1) and parameters as sent; null or blank
     * is returned unchanged. The producer normalises so every consumer can compare
     * case-sensitively.
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
     * Whether a frame appends {@code ;charset=UTF-8}: textual types ({@code text/*}, XML and JSON)
     * do, others do not, except that {@code message/*} also does.
     */
    public static boolean takesCharset(final String contentType) {
        final String ct = canonicalType(contentType);
        if (ct == null || ct.isEmpty()) return false;
        final int semi = ct.indexOf(';');
        final String bare = (semi < 0) ? ct : ct.substring(0, semi).trim();
        if (bare.startsWith("text/")) return true;
        // The message/* exception; see the javadoc.
        if (bare.startsWith("message/")) return true;
        if (bare.equals("application/xml") || bare.equals("application/json")) return true;
        if (bare.startsWith("application/") && (bare.endsWith("+xml") || bare.endsWith("+json"))) {
            return true;
        }
        return false;
    }
}
