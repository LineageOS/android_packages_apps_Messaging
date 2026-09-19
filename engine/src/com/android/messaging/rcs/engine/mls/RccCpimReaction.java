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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The CPIM envelope of a decrypted MLS application message, and the REACTION it may carry
 *.
 *
 * <h2>Captured, not inferred</h2>
 *
 * <p>The rule was "do not invent a carriage", with two candidates: the reaction headers are
 * either inside the encrypted CPIM (a) or still on the outer envelope (b). This is (a), read off a
 * Google Messages reaction decrypted on our own device — the whole 232-byte payload:
 *
 * <pre>
 * [10-byte RCC.16 framing prefix]
 * NS: n1 &lt;http://www.gsma.com&gt;
 * NS: n2 &lt;urn:rcs:message:reactions:&gt;
 * n1.Reference-ID: mls-appmls-+15715550106-e6p3-2
 * n1.Reference-Type: +Reaction
 * n2.Origin-Surface-Type: 1
 *                                    &lt;- blank line ends the CPIM headers
 * Content-Type: text/plain; charset=UTF-8
 *                                    &lt;- blank line ends the MIME headers
 * 😂
 * </pre>
 *
 * <p>So option (b) is refuted: nothing outside the ciphertext marks this as a reaction, and the
 * metadata disclosure that was worried about does not happen.
 *
 * <h2>Why prefixes are resolved rather than hardcoded</h2>
 *
 * <p>{@code n1} and {@code n2} are labels the sender chose in its own {@code NS:} lines, not part of
 * the contract. A sender may legally emit {@code NS: r <urn:rcs:message:reactions:>} and then
 * {@code r.Origin-Surface-Type}. Matching on the literal string {@code "n1."} would work against
 * every capture we have and break on the first peer that numbers differently — the precise shape of
 * bug this codebase keeps paying for. So the URIs are authoritative and the prefixes are looked up.
 *
 * <h2>What identifies a reaction</h2>
 *
 * <p>{@code Reference-Type} in the GSMA namespace, whose value is {@code +Reaction} for an ADD.
 * The leading sign is the ADD/REMOVE discriminator, and a REMOVE is presumed {@code -Reaction} —
 * <b>presumed, not captured</b>: we have only ever seen ADDs. {@link #isRemove()} therefore reports
 * what it saw rather than asserting a negative, and a caller must treat an unrecognised sign as
 * "not a reaction I can act on" rather than guessing the opposite of ADD.
 */
public final class RccCpimReaction {

    /** The GSMA namespace that carries {@code Reference-ID} / {@code Reference-Type}. */
    public static final String NS_GSMA = "http://www.gsma.com";

    /** The reactions namespace. Presence alone does not make a message a reaction. */
    public static final String NS_REACTIONS = "urn:rcs:message:reactions:";

    private final Map<String, String> mHeaders;   // lower-cased resolved name -> value
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

    /** The inner body — for a reaction, the emoji itself. Never null. */
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

    /** ADD (a {@code +} sign). False for anything else, including a payload that is not a reaction. */
    public boolean isAdd() {
        final String t = header(NS_GSMA, "Reference-Type");
        return isReaction() && t.charAt(0) == '+';
    }

    /**
     * REMOVE (a {@code -} sign). <b>Never captured</b> — every real reaction we have observed is
     * an ADD, so this reports the sign we read rather than asserting the shape of a removal.
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
     * Parse a decrypted MLS application payload.
     *
     * <p>{@code payload} is what came out of the engine, INCLUDING any RCC.16 framing prefix — the
     * prefix is binary and is skipped by scanning forward to the first CPIM {@code NS:} or header
     * line rather than by assuming a fixed width. The observed prefix is ten bytes, but assuming
     * that would make the parser wrong for any other framing, and the scan costs nothing.
     *
     * @return the parsed envelope, or {@code null} if this is not a CPIM payload at all (in which
     *         case the caller should treat the payload exactly as it did before — this must not
     *         change the handling of an ordinary text message)
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

        // Pass 2: the headers themselves, keyed by RESOLVED namespace.
        final Map<String, String> headers = new HashMap<String, String>();
        for (final String line : headerBlock.split("\r\n|\n")) {
            final int c = line.indexOf(':');
            if (c <= 0) continue;
            final String rawName = line.substring(0, c).trim();
            if (rawName.equalsIgnoreCase("NS")) continue;
            final String value = line.substring(c + 1).trim();
            final int dot = rawName.indexOf('.');
            if (dot <= 0) continue;                       // unprefixed CPIM header — not ours to map
            final String uri = nsByPrefix.get(rawName.substring(0, dot));
            if (uri == null) continue;                    // prefix never declared; ignore rather than guess
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

    /** Skip a binary framing prefix by finding the first line that looks like a CPIM header. */
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
