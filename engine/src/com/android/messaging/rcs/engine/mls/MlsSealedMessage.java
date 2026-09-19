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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One sealed outbound message, kept so a repeat send REPLAYS it instead of re-encrypting (rework
 * item 7.1, invariant 62).
 *
 * <h2>Why re-encrypting is not merely wasteful</h2>
 *
 * <p>Every encrypt consumes a generation from our sender ratchet. So encrypting the same message
 * twice puts <b>two different ciphertexts for one message id</b> on the wire, at two generations —
 * and a peer that received the first now sees a second it cannot reconcile with it. The user-visible
 * form is a duplicate, or a message that never arrives because the server deduped on id and dropped
 * the one the peer could actually open.
 *
 * <p>Today an ordinary UI retry does exactly that: a failed app-owned send is left "retryable" with
 * no cache, so retrying re-frames and re-encrypts at a fresh generation.
 *
 * <h2>The headers are part of the cached thing, not decoration</h2>
 *
 * <p>Replaying the ciphertext without its {@code Era-ID} and {@code Epoch-Authenticator} produces a
 * body the peer cannot select group state for — the values must be the ones that were current
 * <em>when it was sealed</em>, not whatever is current now. A resend after an era advance carrying
 * today's era and yesterday's ciphertext is precisely the mismatch that looks like a decrypt failure
 * and is not one.
 *
 * <p>So the entry stores what was sent, whole: bytes, era, epoch, epoch-authenticator, and the
 * header map that went with them.
 */
public final class MlsSealedMessage {

    /** The wire/AAD message id this was sealed under — the app's {@code rcs_message_id}. */
    public final String messageId;
    private final byte[] mCiphertext;
    /** The era current at seal time. */
    public final int era;
    /** The application generation this consumed. Diagnostic — a replay consumes none. */
    public final int generation;
    private final Map<String, String> mHeaders;
    /**
     * When it was sealed, ms since epoch. <b>DIAGNOSTIC ONLY</b> — read it
     * in a dump, never to decide anything.
     *
     * <p>It WAS a staleness input: {@code MlsCiphertextCache.sweepExpired} aged entries out on it
     *. That is the defect it records — the wall clock can jump
     * forward by an arbitrary amount (an NTP correction after a boot with a dead RTC), which makes
     * every entry look old and deletes the replay material for messages that are still in flight.
     * The staleness input is now {@link #sealedAtElapsedMs}.
     *
     * <p>Kept rather than removed, deliberately: it is the only human-readable "when" in a dump,
     * and an elapsed reading answers "how long ago" without ever answering "when". The project rule
     * is to keep persisting captured values and gate the CONSUMPTION, which is what moving the
     * sweep off it does.
     */
    public final long sealedAtMs;

    /**
     * {@code SystemClock.elapsedRealtime()} at seal time — the RETENTION input.
     *
     * <p>{@link MlsSendRetentionPolicy#UNSTAMPED} for a row written before this field existed. Such
     * a row is NOT expired on the strength of that: it has a wall stamp in {@link #sealedAtMs} that
     * simply cannot be trusted, so {@code MlsCiphertextCache.sweepExpired} ADOPTS it at the current
     * reading and lets it age from there. Treating "written by an older build" the same way as
     * "cannot be aged" would turn a format upgrade into a deletion of every message in flight
     * across it.
     */
    public final long sealedAtElapsedMs;

    /**
     * The canonical conversation this was sealed for, or {@code ""} when unknown.
     *
     * <p>Without it the cache is a flat message→bytes map that cannot answer "which entries belong
     * to this conversation", so the only releases possible are per-message and whole-store. That is
     * what made a stranded conversation's material unreleasable: nobody could name it. Empty for
     * entries written by an older build, which is why every consumer treats {@code ""} as "do not
     * match" rather than "matches everything" — a wildcard would make one terminal wipe the store.
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
     * The same entry, re-stamped at {@code nowElapsedMs} — the ADOPTION of a row whose elapsed
     * stamp cannot be used.
     *
     * <p>Called for a row written before {@link #sealedAtElapsedMs} existed. The alternative
     * readings were both wrong: expire it (a format upgrade deletes every in-flight message) or
     * retain it forever (nothing can ever age it, which wedges the cache at its ceiling).
     * Adopting gives it one full window measured from a clock we trust, which is the
     * over-retention direction we settled on and is bounded rather than permanent.
     *
     * <p>{@link #sealedAtMs} is carried through UNCHANGED: it is what it always was — the wall time
     * the bytes were sealed — and rewriting it would destroy the one diagnostic in the row for the
     * sake of a field that is not read.
     */
    public MlsSealedMessage adoptedAt(final long nowElapsedMs) {
        return new MlsSealedMessage(messageId, mCiphertext, era, generation, mHeaders, sealedAtMs,
                conversationKey, nowElapsedMs);
    }

    public byte[] ciphertext() { return copy(mCiphertext); }

    /** The headers that were sent WITH this ciphertext. Never null. */
    public Map<String, String> headers() { return new LinkedHashMap<>(mHeaders); }

    /**
     * Whether this entry is replayable.
     *
     * <p>Invariant 62's condition is "a non-empty ciphertext exists". An entry with empty bytes is
     * not a cache hit — it is a row that should never have been written, and treating it as a hit
     * would send nothing and report success.
     */
    public boolean isReplayable() { return mCiphertext.length > 0; }

    /**
     * Encode as {@code era " " generation " " sealedAtMs " " base64(ct) " " k:v;k:v " "
     * base64(conversationKey) " " sealedAtElapsedMs}.
     *
     * <p>Header keys and values are base64'd individually so one containing a separator cannot be
     * read back as a different set of headers — nothing in the type stops a caller adding a header
     * with a space in it.
     *
     * <p><b>The key/value separator is {@code ':'}, NOT {@code '='}.</b> Base64 pads with
     * {@code '='}, so an {@code '='} separator collides with the padding of any key whose length is
     * not a multiple of 3 — {@code indexOf('=')} then finds the padding inside the KEY and the pair
     * splits in the wrong place. That failed on the very first header set with a 19-character name.
     * {@code ':'} and {@code ';'} are both outside the base64 alphabet.
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
        // FIELD 6, APPENDED — base64'd so a conversation key containing a space cannot split the
        // record, which matters because a 1:1 key is an E.164 but a group key is server-supplied.
        // Appending rather than inserting keeps every existing 5-field row decodable (see decode).
        sb.append(' ').append(MlsRendezvous.base64(bytes(conversationKey)));
        // FIELD 7, APPENDED for the same reason: the elapsed stamp the retention sweep
        // reads. A decimal long cannot contain the separator, so it needs no base64. Field 3 stays
        // where it is and keeps meaning what it meant — changing the MEANING of an existing field
        // would leave every row on disk lying about which clock it was written with.
        sb.append(' ').append(sealedAtElapsedMs);
        return sb.toString();
    }

    /** @return the decoded entry, or {@code null} if {@code s} is not one */
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
            // FIELD 6 IS OPTIONAL, and must stay that way: rows written before it existed are
            // otherwise unreadable, and an unreadable row is a re-encrypt (invariant 62). Absent
            // reads as "" — deliberately NOT a wildcard; see conversationKey.
            final String conv = p.length >= 6 ? str(MlsRendezvous.unbase64(p[5])) : "";
            // FIELD 7 IS OPTIONAL for the same reason. Absent reads as UNSTAMPED, which the sweep
            // ADOPTS rather than expires — see sealedAtElapsedMs.
            final long atElapsed = p.length >= 7
                    ? Long.parseLong(p[6]) : MlsSendRetentionPolicy.UNSTAMPED;
            return new MlsSealedMessage(messageId, ct, era, gen, headers, at, conv, atElapsed);
        } catch (final RuntimeException notAnEntry) {
            // A corrupt row reads as ABSENT. Replaying a half-decoded ciphertext would put bytes on
            // the wire that no peer can open, which is worse than re-encrypting.
            return null;
        }
    }

    @Override public String toString() {
        return "sealed{" + messageId + " era=" + era + " gen=" + generation
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
