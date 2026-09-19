/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How an inbound RCS file is kept in an attachment part, and when it is drawn as a file row (name,
 * size and type) rather than by a media view. Plain Java, so the host tests run it.
 *
 * <p>The parts table has no column for a file name or size, so both ride on the part's content
 * URI as query parameters, which the scratch-file provider ignores when it opens the file. A file
 * the provider has not downloaded yet has no bytes here at all: its part points at a
 * {@linkplain #placeholder placeholder} URI, the row is stored pending, and a tap asks the provider
 * for the file. See "Files" in docs/rcs/provider-contract.md.
 */
public final class RcsFileAttachment {

    static final String PARAM_NAME = "rcs_name";
    static final String PARAM_SIZE = "rcs_size";
    static final String PARAM_PENDING = "rcs_pending";

    /** Longest file name kept; longer ones keep their start and their extension. */
    static final int MAX_NAME_CHARS = 120;

    /** {@code rcs_status} of a received file that awaits the user's accept. */
    public static final int STATUS_PENDING = 100;
    /**
     * {@code rcs_status} of a pending file the provider reported it can no longer download
     * ({@code onIncomingFileUnavailable}).
     */
    public static final int STATUS_UNAVAILABLE = 101;

    private RcsFileAttachment() {}

    /** What the receive action stores for one delivery. */
    public enum Stored {
        /** The file itself. */
        FILE,
        /** Its thumbnail; the file awaits the user's accept. */
        THUMBNAIL,
        /** Neither: a pending row with a placeholder part; the file awaits the user's accept. */
        PLACEHOLDER,
    }

    /** What a delivery with these resolved handles is stored as. Never "dropped". */
    public static Stored storedAs(final String fileUri, final String thumbnailUri) {
        if (!isEmpty(fileUri)) return Stored.FILE;
        if (!isEmpty(thumbnailUri)) return Stored.THUMBNAIL;
        return Stored.PLACEHOLDER;
    }

    /**
     * Whether a row in {@code rcsStatus} has no file yet: pending, or unavailable. A pending copy
     * offered again may update such a row; it never replaces a file.
     */
    public static boolean lacksFile(final int rcsStatus) {
        return rcsStatus == STATUS_PENDING || rcsStatus == STATUS_UNAVAILABLE;
    }

    /**
     * The {@code rcs_status} a row moves to when the provider reports its file unavailable: a
     * pending row becomes unavailable, and any other row keeps its status. A report that crosses
     * the file's own delivery must not hide the file.
     */
    public static int statusWhenUnavailable(final int rcsStatus) {
        return rcsStatus == STATUS_PENDING ? STATUS_UNAVAILABLE : rcsStatus;
    }

    /**
     * {@code uri} with the file's name and size added. A null or empty name, or a negative size,
     * is left out.
     */
    public static String describe(final String uri, final String fileName, final long size) {
        final StringBuilder sb = new StringBuilder(uri);
        final String name = shorten(fileName);
        if (!isEmpty(name)) append(sb, PARAM_NAME, name);
        if (size >= 0) append(sb, PARAM_SIZE, Long.toString(size));
        return sb.toString();
    }

    /**
     * The part URI for a file not downloaded yet: {@code base} (a URI with a path and no query, in
     * the scratch-file provider's space) marked pending, with the name and size.
     */
    public static String placeholder(final String base, final String fileName, final long size) {
        final StringBuilder sb = new StringBuilder(base);
        append(sb, PARAM_PENDING, "1");
        return describe(sb.toString(), fileName, size);
    }

    /** Whether {@code uri} is a {@link #placeholder}: no bytes behind it. */
    public static boolean isPlaceholder(final String uri) {
        return "1".equals(param(uri, PARAM_PENDING));
    }

    /** The file name {@link #describe} added, or null. */
    public static String nameOf(final String uri) {
        final String n = param(uri, PARAM_NAME);
        return isEmpty(n) ? null : n;
    }

    /** The size {@link #describe} added, or -1. */
    public static long sizeOf(final String uri) {
        final String s = param(uri, PARAM_SIZE);
        if (s == null) return -1;
        try {
            final long v = Long.parseLong(s);
            return v >= 0 ? v : -1;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether an RCS attachment part is drawn as a file row: any type no media view draws
     * ({@code mediaType} false: not image, video, audio or vCard), and any placeholder, whose bytes
     * a media view could not load.
     */
    public static boolean rendersAsFile(final boolean mediaType, final String uri) {
        return !mediaType || isPlaceholder(uri);
    }

    /**
     * The text of a row's bubble: {@code text}, then each non-empty caption on its own line. An
     * RCS caption rides on its media part, and only a file row draws it; the image, video, audio
     * and vCard views do not, so their captions are shown here. {@code text} is returned as is
     * when no caption has text.
     */
    public static String withCaptions(final String text, final List<String> captions) {
        final StringBuilder sb = new StringBuilder(text == null ? "" : text);
        boolean added = false;
        for (final String caption : captions) {
            if (isEmpty(caption)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(caption);
            added = true;
        }
        return added ? sb.toString() : text;
    }

    /**
     * The caption an outgoing file carries: the text typed with it, which the composer keeps in a
     * text part of its own, and the attachment's own caption. The send button copies attachment
     * captions to the front of the typed text ({@code MessageData.consolidateText}), so typed text
     * that already starts with the caption is used as it is. Null when neither has text.
     */
    public static String outgoingCaption(final String mediaText, final String typedText) {
        if (isEmpty(typedText)) return isEmpty(mediaText) ? null : mediaText;
        if (isEmpty(mediaText) || typedText.startsWith(mediaText)) return typedText;
        return mediaText + "\n" + typedText;
    }

    /** The upper-case extension of {@code fileName}, for the row's type label, or null. */
    public static String extensionLabel(final String fileName) {
        if (isEmpty(fileName)) return null;
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return null;
        final String ext = fileName.substring(dot + 1);
        if (ext.length() > 8) return null;
        for (int i = 0; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) return null;
        }
        return ext.toUpperCase(java.util.Locale.ROOT);
    }

    private static String shorten(final String fileName) {
        if (fileName == null) return null;
        final String n = fileName.trim();
        if (n.length() <= MAX_NAME_CHARS) return n;
        final int dot = n.lastIndexOf('.');
        final String ext = dot > 0 && n.length() - dot <= 9 ? n.substring(dot) : "";
        return n.substring(0, MAX_NAME_CHARS - ext.length() - 1) + "…" + ext;
    }

    private static void append(final StringBuilder sb, final String key, final String value) {
        sb.append(sb.indexOf("?") < 0 ? '?' : '&').append(key).append('=').append(encode(value));
    }

    /** The decoded value of query parameter {@code key}, or null. */
    private static String param(final String uri, final String key) {
        if (uri == null) return null;
        final int q = uri.indexOf('?');
        if (q < 0) return null;
        int end = uri.indexOf('#', q);
        if (end < 0) end = uri.length();
        for (final String pair : uri.substring(q + 1, end).split("&")) {
            final int eq = pair.indexOf('=');
            final String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(key)) return eq < 0 ? "" : decode(pair.substring(eq + 1));
        }
        return null;
    }

    /** Percent-encodes all but RFC 3986 unreserved characters, as Uri.encode does. */
    static String encode(final String s) {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : s.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static String decode(final String s) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                final int hi = Character.digit(s.charAt(i + 1), 16);
                final int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            final int cp = s.codePointAt(i);
            final byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            i += Character.charCount(cp);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isEmpty(final String s) {
        return s == null || s.isEmpty();
    }
}
