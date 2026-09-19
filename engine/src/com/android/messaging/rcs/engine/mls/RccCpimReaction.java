/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The CPIM envelope inside a decrypted MLS application message, and the reaction it may carry. A
 * reaction's headers are inside the ciphertext: after a binary framing prefix come
 * {@code NS: n1 <http://www.gsma.com>}, {@code NS: n2 <urn:rcs:message:reactions:>},
 * {@code n1.Reference-ID}, {@code n1.Reference-Type: +Reaction}, {@code n2.Origin-Surface-Type},
 * then a MIME part whose body is the emoji. Prefixes are the sender's choice, so headers are keyed
 * by the resolved namespace URI.
 */
public final class RccCpimReaction {

    /** Namespace of {@code Reference-ID} and {@code Reference-Type}. */
    public static final String NS_GSMA = "http://www.gsma.com";

    /** The reactions namespace; its presence alone does not make a message a reaction. */
    public static final String NS_REACTIONS = "urn:rcs:message:reactions:";

    private final Map<String, String> mHeaders;  // lower-cased resolved name -> value
    private final String mContentType;
    private final byte[] mBody;

    private RccCpimReaction(final Map<String, String> headers, final String contentType,
            final byte[] body) {
        mHeaders = headers;
        mContentType = contentType;
        mBody = body;
    }

    /** The inner MIME content type, or {@code null} when the payload had no MIME part. */
    public String contentType() { return mContentType; }

    /** The inner body (for a reaction, the emoji). Never null. */
    public byte[] body() { return mBody == null ? new byte[0] : mBody; }

    /** A resolved CPIM header by {@code <namespaceUri> + "." + name}, case-insensitive, or null. */
    public String header(final String namespaceUri, final String name) {
        if (namespaceUri == null || name == null) return null;
        return mHeaders.get((namespaceUri + "." + name).toLowerCase(Locale.US));
    }

    /** Whether this payload is a reaction at all. */
    public boolean isReaction() {
        final String t = header(NS_GSMA, "Reference-Type");
        return t != null && t.regionMatches(true, 1, "Reaction", 0, "Reaction".length())
                && (t.charAt(0) == '+' || t.charAt(0) == '-');
    }

    /** A {@code +} sign; false for anything else, including a non-reaction. */
    public boolean isAdd() {
        final String t = header(NS_GSMA, "Reference-Type");
        return isReaction() && t.charAt(0) == '+';
    }

    /**
     * A {@code -} sign. Removal is assumed to be {@code -Reaction} but has not been seen from a
     * peer, so callers treat any other sign as not actionable rather than as the opposite of add.
     */
    public boolean isRemove() {
        final String t = header(NS_GSMA, "Reference-Type");
        return isReaction() && t.charAt(0) == '-';
    }

    /** The message id this reaction targets, or null. */
    public String reactedMessageId() { return header(NS_GSMA, "Reference-ID"); }

    /** The emoji, as text. Empty when there is no body. */
    public String emoji() { return new String(body(), StandardCharsets.UTF_8); }

    /**
     * Parses a decrypted application payload, including its binary framing prefix, which is skipped
     * by finding {@code NS:} within the first 64 bytes rather than assuming a width.
     *
     * @return the envelope, or {@code null} if no {@code NS:} line starts the CPIM headers; the
     *     caller then handles the payload as an ordinary message
     */
    public static RccCpimReaction parse(final byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        final int start = firstHeaderOffset(payload);
        if (start < 0) return null;
        final String text = new String(payload, start, payload.length - start,
                StandardCharsets.UTF_8);
        final int sep = endOfHeaderBlock(text);
        if (sep < 0) return null;
        final String headerBlock = text.substring(0, sep);

        // Pass 1: the NS declarations, so prefixes can be resolved to URIs.
        final Map<String, String> nsByPrefix = new HashMap<String, String>();
        for (final String line : headerBlock.split("\r\n|\n")) {
            final int c = line.indexOf(':');
            if (c <= 0) continue;
            if (!line.substring(0, c).trim().equalsIgnoreCase("NS")) continue;
            final String v = line.substring(c + 1).trim();
            final int lt = v.indexOf('<'), gt = v.lastIndexOf('>');
            if (lt < 0 || gt <= lt) continue;
            final String prefix = v.substring(0, lt).trim();
            if (!prefix.isEmpty()) nsByPrefix.put(prefix, v.substring(lt + 1, gt).trim());
        }

        // Pass 2: the headers, keyed by resolved namespace.
        final Map<String, String> headers = new HashMap<String, String>();
        for (final String line : headerBlock.split("\r\n|\n")) {
            final int c = line.indexOf(':');
            if (c <= 0) continue;
            final String rawName = line.substring(0, c).trim();
            if (rawName.equalsIgnoreCase("NS")) continue;
            final String value = line.substring(c + 1).trim();
            final int dot = rawName.indexOf('.');
            if (dot <= 0) continue;  // unprefixed CPIM header
            final String uri = nsByPrefix.get(rawName.substring(0, dot));
            if (uri == null) continue;  // undeclared prefix: ignore
            headers.put((uri + "." + rawName.substring(dot + 1)).toLowerCase(Locale.US), value);
        }

        // The MIME part after the blank line: its own headers, another blank line, then the body.
        String contentType = null;
        byte[] body = new byte[0];
        final String rest = text.substring(sep);
        final String trimmed = stripLeadingBlankLines(rest);
        final int mimeSep = endOfHeaderBlock(trimmed);
        if (mimeSep >= 0) {
            for (final String line : trimmed.substring(0, mimeSep).split("\r\n|\n")) {
                final int c = line.indexOf(':');
                if (c <= 0) continue;
                if (line.substring(0, c).trim().equalsIgnoreCase("Content-Type")) {
                    final String v = line.substring(c + 1).trim();
                    final int semi = v.indexOf(';');
                    contentType = (semi > 0 ? v.substring(0, semi) : v).trim();
                }
            }
            body = stripLeadingBlankLines(trimmed.substring(mimeSep))
                    .getBytes(StandardCharsets.UTF_8);
        }
        return new RccCpimReaction(headers, contentType, body);
    }

    /** Offset of the first {@code NS:} in the first 64 bytes, or -1. */
    private static int firstHeaderOffset(final byte[] p) {
        for (int i = 0; i < p.length && i < 64; i++) {
            if (p[i] == 'N' && i + 2 < p.length && p[i + 1] == 'S' && p[i + 2] == ':') return i;
        }
        return -1;
    }

    /** Index just past the blank line that ends a header block, or -1. */
    private static int endOfHeaderBlock(final String s) {
        final int a = s.indexOf("\r\n\r\n");
        if (a >= 0) return a + 4;
        final int b = s.indexOf("\n\n");
        return b >= 0 ? b + 2 : -1;
    }

    private static String stripLeadingBlankLines(final String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\r' || s.charAt(i) == '\n')) i++;
        return s.substring(i);
    }
}
