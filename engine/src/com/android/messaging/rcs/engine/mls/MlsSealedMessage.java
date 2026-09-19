/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One sealed outbound message, kept so a repeat send replays it instead of re-encrypting: each
 * encrypt consumes a generation and would put a second ciphertext for one id on the wire. The
 * headers sent with it are kept too, since a replay must carry the era and epoch authenticator
 * current when it was sealed.
 */
public final class MlsSealedMessage {

    /** The wire and AAD id: the app's {@code rcs_message_id}. */
    public final String messageId;
    private final byte[] mCiphertext;
    /** The era at seal time. */
    public final int era;
    /** The generation this consumed; diagnostic. */
    public final int generation;
    private final Map<String, String> mHeaders;
    /**
     * When it was sealed, ms since epoch. Diagnostic only; the wall clock can jump, so retention
     * uses {@link #sealedAtElapsedMs}.
     */
    public final long sealedAtMs;

    /**
     * {@code SystemClock.elapsedRealtime()} at seal time; the retention input.
     * {@link MlsSendRetentionPolicy#UNSTAMPED} for an older row, which the sweep adopts at the
     * current reading rather than expires.
     */
    public final long sealedAtElapsedMs;

    /**
     * The canonical conversation this was sealed for, or {@code ""} when unknown. Consumers treat
     * {@code ""} as matching nothing, so one terminal event cannot wipe the store.
     */
    public final String conversationKey;

    public MlsSealedMessage(final String messageId, final byte[] ciphertext, final int era,
            final int generation, final Map<String, String> headers, final long sealedAtMs) {
        this(messageId, ciphertext, era, generation, headers, sealedAtMs, "");
    }

    public MlsSealedMessage(final String messageId, final byte[] ciphertext, final int era,
            final int generation, final Map<String, String> headers, final long sealedAtMs,
            final String conversationKey) {
        this(messageId, ciphertext, era, generation, headers, sealedAtMs, conversationKey,
                MlsSendRetentionPolicy.UNSTAMPED);
    }

    public MlsSealedMessage(final String messageId, final byte[] ciphertext, final int era,
            final int generation, final Map<String, String> headers, final long sealedAtMs,
            final String conversationKey, final long sealedAtElapsedMs) {
        this.messageId = messageId == null ? "" : messageId;
        mCiphertext = ciphertext == null ? new byte[0] : copy(ciphertext);
        this.era = era;
        this.generation = generation;
        mHeaders = new LinkedHashMap<>();
        if (headers != null) mHeaders.putAll(headers);
        this.sealedAtMs = sealedAtMs;
        this.conversationKey = conversationKey == null ? "" : conversationKey;
        this.sealedAtElapsedMs = sealedAtElapsedMs < 0L
                ? MlsSendRetentionPolicy.UNSTAMPED : sealedAtElapsedMs;
    }

    /**
     * This entry re-stamped at {@code nowElapsedMs}, for a row with no usable elapsed stamp: one
     * full window from a trusted clock. {@link #sealedAtMs} is kept unchanged.
     */
    public MlsSealedMessage adoptedAt(final long nowElapsedMs) {
        return new MlsSealedMessage(messageId, mCiphertext, era, generation, mHeaders, sealedAtMs,
                conversationKey, nowElapsedMs);
    }

    public byte[] ciphertext() { return copy(mCiphertext); }

    /** The headers sent with this ciphertext; never null. */
    public Map<String, String> headers() { return new LinkedHashMap<>(mHeaders); }

    /** Whether this entry is replayable: an empty ciphertext is not a cache hit. */
    public boolean isReplayable() { return mCiphertext.length > 0; }

    /**
     * Encodes as {@code era " " generation " " sealedAtMs " " base64(ct) " " k:v;k:v " "
     * base64(conversationKey) " " sealedAtElapsedMs}. Header keys and values are base64'd
     * separately, and the separator is {@code ':'}, since {@code '='} is base64 padding.
     */
    public String encode() {
        final StringBuilder sb = new StringBuilder();
        sb.append(era).append(' ').append(generation).append(' ').append(sealedAtMs)
          .append(' ').append(MlsRendezvous.base64(mCiphertext)).append(' ');
        boolean first = true;
        for (final Map.Entry<String, String> e : mHeaders.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(MlsRendezvous.base64(bytes(e.getKey())))
              .append(':')
              .append(MlsRendezvous.base64(bytes(e.getValue())));
        }
        // Field 6, appended so five-field rows still decode; base64 because a group key is
        // server-supplied.
        sb.append(' ').append(MlsRendezvous.base64(bytes(conversationKey)));
        // Field 7, appended: the elapsed stamp. Field 3 keeps its meaning.
        sb.append(' ').append(sealedAtElapsedMs);
        return sb.toString();
    }

    /** @return the decoded entry, or null if {@code s} is not one */
    public static MlsSealedMessage decode(final String messageId, final String s) {
        if (s == null) return null;
        final String[] p = s.split(" ", -1);
        if (p.length < 5) return null;
        try {
            final int era = Integer.parseInt(p[0]);
            final int gen = Integer.parseInt(p[1]);
            final long at = Long.parseLong(p[2]);
            final byte[] ct = MlsRendezvous.unbase64(p[3]);
            final Map<String, String> headers = new LinkedHashMap<>();
            if (!p[4].isEmpty()) {
                for (final String pair : p[4].split(";", -1)) {
                    final int sep = pair.indexOf(':');
                    if (sep <= 0) continue;
                    headers.put(str(MlsRendezvous.unbase64(pair.substring(0, sep))),
                            str(MlsRendezvous.unbase64(pair.substring(sep + 1))));
                }
            }
            // Field 6 is optional; absent reads as "", not a wildcard.
            final String conv = p.length >= 6 ? str(MlsRendezvous.unbase64(p[5])) : "";
            // Field 7 is optional; absent reads as UNSTAMPED, which the sweep adopts.
            final long atElapsed = p.length >= 7
                    ? Long.parseLong(p[6]) : MlsSendRetentionPolicy.UNSTAMPED;
            return new MlsSealedMessage(messageId, ct, era, gen, headers, at, conv, atElapsed);
        } catch (final RuntimeException notAnEntry) {
            // A corrupt row reads as absent; re-encrypting beats replaying half-decoded bytes.
            return null;
        }
    }

    @Override public String toString() {
        return "sealed{" + MlsMessageId.forLog(messageId) + " era=" + era + " gen=" + generation
                + " ct=" + mCiphertext.length + "B headers=" + mHeaders.size() + "}";
    }

    private static byte[] bytes(final String s) {
        return (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(final byte[] b) {
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] copy(final byte[] b) {
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }
}
