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

/**
 * The wire details of an MLS <b>application message</b> that BOTH owners must agree on, byte for byte.
 *
 * <p><b>This class exists because splitting these two things across two processes cost this project
 * weeks.</b> Both bugs were the same shape — one wire contract, two implementations, only one tested:
 *
 * <ul>
 *   <li>The RCC.16 body counter was hardcoded to {@code 2} in the app while the sender ratchet lived in
 *       the provider. It must equal {@code sender_data.generation}, which peers cross-check. Neither
 *       side could be correct alone.</li>
 *   <li>The {@code AuthenticatedData} AAD was bound on one send path and not the other, so messages
 *       that looked identical decrypted on one route and silently failed on the other.</li>
 * </ul>
 *
 * <p>So the framing and the AAD now live in the shared engine module, next to the ratchet that
 * produces the generation they must match. Whoever owns the engine owns these — there is no second
 * copy to drift.
 *
 * <p>Pure and dependency-free: no Android, no engine handle, no I/O. Host-testable.
 */
public final class MlsAppMessage {

    private MlsAppMessage() {}

    /**
     * The Google Messages {@code AuthenticatedData{version, message_id, era, 00}} fed into the
     * application-message AEAD.
     *
     * <p>Layout: {@code [00 01][mls_varint len][message_id ASCII][uint32 era BE][00]}. The
     * {@code message_id} MUST equal the transport envelope's message id — a Google Messages peer cross-checks
     * them. The {@code uint32} is the group's current ERA (captured era-N → N), NOT a content type.
     *
     * <p>Stock mls-rs seals with an EMPTY aad; a Google Messages peer then fails AEAD authentication and the
     * symptom surfaces as a generation mismatch far from the cause. Always bind this.
     */
    /**
     * CONFIRMED FROM THE WIRE. A Google Messages peer's inbound AAD,
     * captured on our receive side (the AAD is authenticated but not encrypted, so it is readable
     * without touching the peer):
     *
     * <pre>
     *   0001 18 4d78..4c41 00000004 00
     *   ver  |  "Mxg7aPBHhvSLSqseYQHUenLA" (24 ASCII)
     *        |                              era = 4 (uint32 BE)
     *        len=24 (mls_varint, form 0)             trailing byte
     * </pre>
     *
     * <p>Byte-for-byte what this method builds. Two things that settles:
     *
     * <ul>
     *   <li><b>The era IS in the AAD</b>, as a {@code uint32} big-endian immediately after the
     *       message id — and it tracks the real era rather than being a constant. RCC.16's
     *       {@code {version, message_id, optional resent}} omits it; the wire has it. <b>Do not
     *       "correct" this layout to match the spec text</b> — that change would break interop with
     *       every Google Messages peer, and it would surface as {@code KEY_GENERATION_MISMATCH}, which looks
     *       nothing like the cause.</li>
     *   <li>An ordinary message carries exactly ONE trailing {@code 0x00}, and everything before it
     *       is variable-length, so it cannot be padding — see
     *       {@link #TRAILING_IS_RESENT_COMPONENT}.</li>
     * </ul>
     *
     * <p><b>Corroborated on three further Google Messages messages</b>
     * (same peer, same
     * group), which matters for the "tracks the real era" claim above: that was
     * a single-sample inference from one era-4 capture, and a constant would have fitted it equally
     * well. It now rests on four distinct era values.
     *
     * <pre>
     *   era 6  0001 18 "MxaDOv6I6QTwGK2Xj6V-Ubhw" 00000006 00   (Era-ID header: 6)
     *   era 7  0001 18 "MxHjYubZBiQYuA9DqODpyQiw" 00000007 00   (Era-ID header: 7)
     *   era 8  0001 18 "MxCACYGxqfQa-CnQZ=5XctuQ" 00000008 00   (Era-ID header: 8)
     * </pre>
     *
     * <p>All three consume exactly 32/32 bytes, and in every case the {@code uint32} equals that
     * message's {@code Era-ID} envelope header. All three also carry the resent component ABSENT
     * (trailing {@code 0x00}) — so the PRESENT form is still uncaptured and a Google Messages RESEND is still
     * required for it. Read these off the wire without decrypting: the artifacts'
     * eras are long gone, but {@code authenticated_data} is authenticated-not-encrypted.
     *
     * <h3>DO NOT CONFORM THIS TO RCC.16 v4.0 — the one place where being wrong silently breaks
     * ALL encrypted traffic</h3>
     *
     * <p>v4.0 restructures {@code AuthenticatedData} relative to this layout: a different field
     * order, and a union where v3.0 has the trailing resent-message byte. An unflagged reading of
     * the v4.0 assessment makes that look like an ordinary conformance gap. <b>It is not.</b> The
     * layout above is device-proven against Tachyon — every encrypted message we have ever had
     * accepted, and every one Google Messages has decrypted, uses it. Tachyon is v3.0.
     *
     * <p><b>The default is do nothing.</b> Changing this requires BOTH of:
     * <ol>
     *   <li>a capture of a real v4.0 peer's {@code AuthenticatedData} — not the specification text,
     *       which is what tempts the change in the first place; and</li>
     *   <li>the version enabler, so the new shape applies only to a transport that has actually
     *       announced v4.0 — the same gating {@code rcc16::end_mls_payload} already uses for the
     *       0xF002 payload, which is the worked example to copy.</li>
     * </ol>
     *
     * <p>The failure mode if you change it early is not a rejected message with a useful error. It
     * is every peer failing to decrypt, surfacing as {@code KEY_GENERATION_MISMATCH}, which looks
     * nothing like the cause — the same trap the {@code uint32} era note above describes, one level
     * more expensive.
     */
    /**
     * <b>DEAD SINCE {@code 0a01b096} — THE AAD IS BUILT IN RUST NOW, AND THIS IS NOT THE WIRE
     * FORMAT'S AUTHORITY.</b>
     *
     * <p>Neither overload has a production caller: the only reference to the 3-arg form is this
     * 2-arg one delegating to it, and nothing calls either. {@code 0a01b096} ("remove the
     * host-supplied AAD seam — the engine builds it now") moved construction to
     * {@code rcc16.rs::build_authenticated_data}, and that function — not this one — is what every
     * outbound message is actually sealed against.
     *
     * <p><b>Why that matters enough to say here.</b> This is the obvious
     * place to look when asking "what does our AuthenticatedData contain?", and a reader who answers
     * from this method is reading code that has not run since August. The two happen to agree today
     * ({@code [00 01][mls_varint len][message_id][era u32 BE][trailing]}); nothing enforces that they
     * keep agreeing, and a divergence would be invisible from here.
     *
     * <p><b>The same refactor orphaned {@code MlsSession.setRequestMessageId}</b>, which is why the
     * engine's RCC.16 §7.5.3.1 message-id check has never fired on any message on any leg; that is
     * guarded by {@code MlsEngineIdCheckWiringGuardTest}. Two orphans from one
     * commit; if you are about to delete this, read that guard first, because its analysis reads
     * this file.
     */
    public static byte[] buildAuthenticatedData(final String messageId, final int era) {
        return buildAuthenticatedData(messageId, era, null);
    }

    /**
     * As above, but with an EXPLICIT trailing component instead of the absent {@code 0x00}.
     *
     * <p>The §10.3 resent-message slot. Pass null for the ordinary absent form; pass
     * {@link MlsResentMessage#encode} output to emit a present component.
     *
     * <p>This exists so the tag/prefix question can be settled by EXPERIMENT rather than by
     * argument: Google Messages' rejection distinguishes the two failure modes for us — a
     * "does not contain a resent message" means the TAG is wrong, while a PARSE error means the tag
     * was right and the PREFIX WIDTH is wrong. That distinction is the measurement, which is why the
     * caller supplies the exact bytes rather than this method choosing them.
     */
    public static byte[] buildAuthenticatedData(final String messageId, final int era,
            final byte[] trailing) {
        final byte[] mid = (messageId == null ? "" : messageId).getBytes(StandardCharsets.US_ASCII);
        final byte[] len = mlsVarint(mid.length);
        final byte[] tail = (trailing == null || trailing.length == 0)
                ? new byte[] { 0x00 } : trailing;
        final byte[] aad = new byte[2 + len.length + mid.length + 4 + tail.length];
        int i = 0;
        aad[i++] = 0x00; aad[i++] = 0x01;                 // version = 1
        System.arraycopy(len, 0, aad, i, len.length); i += len.length;
        System.arraycopy(mid, 0, aad, i, mid.length); i += mid.length;
        aad[i++] = (byte) (era >>> 24); aad[i++] = (byte) (era >>> 16);
        aad[i++] = (byte) (era >>> 8);  aad[i++] = (byte) era;
        System.arraycopy(tail, 0, aad, i, tail.length);   // trailing component
        return aad;
    }

    /**
     * The {@code message_id} an inbound AAD asserts, or null if it does not parse.
     *
     * <p>Layout is the mirror of {@link #buildAuthenticatedData}: {@code 00 01 | varint(len) |
     * message_id | era u32 | trailing}. Only the first two fields are needed here, so a trailing
     * component we do not model yet (see {@link #TRAILING_IS_RESENT_COMPONENT}) does not stop the
     * check working.
     */
    public static String aadMessageId(final byte[] aad) {
        if (aad == null || aad.length < 3) return null;
        if (aad[0] != 0x00 || aad[1] != 0x01) return null;          // version must be 1
        int i = 2;
        final int form = (aad[i] & 0xC0) >>> 6;
        final int nBytes = (form == 0) ? 1 : (form == 1) ? 2 : (form == 2) ? 4 : -1;
        if (nBytes < 0 || i + nBytes > aad.length) return null;
        int len = aad[i] & 0x3F;
        for (int k = 1; k < nBytes; k++) len = (len << 8) | (aad[i + k] & 0xFF);
        i += nBytes;
        if (len < 0 || i + len > aad.length) return null;
        return new String(aad, i, len, StandardCharsets.US_ASCII);
    }

    /**
     * The TRAILING SLOT of an inbound AAD — the §10.3 resent-message component, absent or present.
     *
     * <p>Layout is {@code 00 01 | varint(len) | message_id | era u32 | <trailing>}; this returns
     * everything from the trailing byte onward, for {@link MlsResentMessage#parse} to decode. On an
     * ordinary message that is the single byte {@code 0x00}.
     *
     * @return the trailing bytes, or null if the AAD does not parse
     */
    public static byte[] aadTrailing(final byte[] aad) {
        if (aad == null || aad.length < 3) return null;
        if (aad[0] != 0x00 || aad[1] != 0x01) return null;
        int i = 2;
        final int form = (aad[i] & 0xC0) >>> 6;
        final int nBytes = (form == 0) ? 1 : (form == 1) ? 2 : (form == 2) ? 4 : -1;
        if (nBytes < 0 || i + nBytes > aad.length) return null;
        int len = aad[i] & 0x3F;
        for (int k = 1; k < nBytes; k++) len = (len << 8) | (aad[i + k] & 0xFF);
        i += nBytes;
        if (len < 0 || i + len > aad.length) return null;
        i += len;
        i += 4;                                        // the era u32
        if (i > aad.length) return null;
        final byte[] tail = new byte[aad.length - i];
        System.arraycopy(aad, i, tail, 0, tail.length);
        return tail;
    }

    /**
     * RCC.16 §7.5.3.1 — the AAD's {@code message_id} must EQUAL the transport's.
     *
     * <p><b>Why the host has to do this.</b> RFC 9420 authenticates the AAD but assigns it no
     * meaning, so mls-rs will happily decrypt a message whose AAD names a DIFFERENT message than the
     * envelope that carried it. Without the check, an attacker who can replay a ciphertext under a
     * new transport message-id gets it accepted and attributed to the new id — and every ledger we
     * key on message-id (receipts, FTD correlation, resend) then points at the wrong thing.
     *
     * <p>Google Messages enforces it: two comparison sites against the request context's message_id, failing
     * {@code MessageIdMismatch}.
     *
     * <p>An ABSENT AAD returns true. That is deliberate rather than lax — a peer that binds no AAD
     * has asserted nothing to contradict, and treating "said nothing" as "said something wrong"
     * would reject every message from an implementation that does not bind one. What must never pass
     * is an AAD that names a different id, which is the case this rejects.
     */
    public static boolean aadMessageIdMatches(final byte[] aad, final String transportMessageId) {
        if (aad == null || aad.length == 0) return true;            // nothing asserted
        final String claimed = aadMessageId(aad);
        if (claimed == null || claimed.isEmpty()) return true;      // unparseable / empty: nothing asserted
        return claimed.equals(transportMessageId == null ? "" : transportMessageId);
    }

    /**
     * The trailing AAD byte is the ABSENT §10.3 resent-message component — not padding, not reserved.
     *
     * <p>A strong inference from Google Messages' engine: the AAD carries exactly one optional trailing
     * component, its absence on the resend path is a first-class checked failure ("Authenticated data
     * struct for resent message does not contain a resent message"), and a single zero byte is the
     * natural encoding of "not present".
     *
     * <p>Recorded as a constant because it changes what a future implementer may do with that byte:
     * when the §10.3 resend leg is built, the resent-message struct goes in THAT slot. Continuing to
     * emit {@code 0x00} on a resend would draw exactly the failure above.
     *
     * <h2>IT IS AN ENUM DISCRIMINANT, NOT A PRESENCE FLAG — and the natural reading is wrong</h2>
     *
     * <p><b>Do not implement this slot as {@code optional&lt;T&gt;}.</b> Google Messages' own
     * receive path reads the slot as a single byte and tests it against <b>2</b>; anything else
     * takes the branch that reports <i>"does not contain a resent message"</i>. Only on a match
     * does it call the 64-byte-HMAC resend verifier.
     *
     * <p><b>Why this is a trap rather than a detail.</b> The proven wire ends in {@code 00}, which
     * reads exactly like a TLS-presentation {@code optional<T>} absence octet — under which "present"
     * would be {@code 01} followed by the struct. That is the natural implementation, it is wrong,
     * and the rejection reports <i>absence</i> rather than a bad tag. You would be debugging a
     * serialiser that was emitting the struct correctly.
     *
     * <p><b>THE WIRE BYTE IS {@code 0x02} — INFERRED, NOT OBSERVED.</b> The split
     * is worth keeping, because exactly one step is
     * observed and it is the step that makes the question answerable at all:
     *
     * <ul>
     *   <li><b>OBSERVED:</b> the tag is read as a single byte — a <b>plain byte tag</b>. That rules
     *       out the niche-optimised layouts in which no tag byte exists and the discriminant rides
     *       inside a pointer or an integer range. Had it been one of those, "what byte goes on the
     *       wire" would have been an <i>ill-posed</i> question rather than an open one.</li>
     *   <li><b>INFERRED</b> (three steps, none of them observed in the binary): rustc tags data-carrying
     *       enums with a plain byte in <i>declaration order</i> absent an explicit {@code repr(u8)};
     *       {@code mls_rs_codec}'s derived codec writes that declaration index; and our proven wire
     *       carries {@code 00} for the non-resend case, consistent with variant 0 being first.</li>
     * </ul>
     *
     * <p><b>What would break it:</b> an explicit {@code #[repr(u8)]} with custom values, or a codec
     * discriminant attribute. Neither is visible from the binary and neither has been excluded.
     * <b>Falsifier: one replay of {@code 02} where the capture has {@code 00}.</b>
     *
     * <h2>AND THE FRAMING IS NOT COMPLETE — the length-prefix width is UNKNOWN</h2>
     *
     * <p>Said explicitly so {@code 02 || opaque} does not read as a finished answer. The decoded
     * struct holds the tag and then a {@code (ptr,len)} payload, and a {@code (ptr,len)} payload
     * means a variable-length byte
     * string, so the present form is
     *
     * <pre>&lt;tag = 0x02&gt; || &lt;length-prefixed opaque&gt;</pre>
     *
     * <p>…and <b>{@code u8}, {@code u16} and {@code mls_varint} are all live candidates for that
     * prefix.</b> Nobody has guessed it and nobody should.
     *
     * <p><b>RUN THE REPLAY AS TWO OUTCOMES, NOT PASS/FAIL</b> — the distinction is the measurement:
     * {@code :1838} again means the TAG was wrong; a <i>parse</i> error instead means the tag was
     * right and the PREFIX was wrong.
     *
     * <p><b>Two halves, and only one of them is reachable from a capture: the wire framing — tag
     * and prefix — and the MAC derivation.</b> A Google Messages-emitted resend captured on our
     * receive side hands us tag and prefix together, in the clear, for free: the AAD is authenticated
     * but not encrypted, which is how this layout was read in the first place. It can <i>never</i>
     * reach the derivation label, which is a key-derivation input and never appears on the wire —
     * that is the one thing only static analysis can supply. A capture supersedes
     * the {@code 0x02} inference outright; treat that inference as a prior, not an answer.
     */
    public static final boolean TRAILING_IS_RESENT_COMPONENT = true;

    /**
     * MLS variable-length integer (RFC 9420 §6.2.2) — the length prefix for an {@code opaque<V>}.
     *
     * <p>Was a raw {@code (byte) length}, correct only below 64: at 64–255 the top bits collide with
     * the 2-byte prefix so the peer reads a different length than we wrote, and above 255 it silently
     * truncated. Latent, because every captured Google Messages message_id is well under 64 — but nothing
     * enforces that bound.
     *
     * <p>Encoding: {@code 00xxxxxx} 1 byte · {@code 01xxxxxx …} 2 bytes · {@code 10xxxxxx …} 4 bytes.
     * <b>Unverified above 63</b> — the multi-byte forms follow the spec rather than an observation.
     */
    public static byte[] mlsVarint(final int value) {
        if (value < 0) throw new IllegalArgumentException("negative length: " + value);
        if (value < 0x40) {
            return new byte[] {(byte) value};
        }
        if (value < 0x4000) {
            return new byte[] {(byte) (0x40 | (value >>> 8)), (byte) value};
        }
        if (value < 0x40000000) {
            return new byte[] {(byte) (0x80 | (value >>> 24)), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value};
        }
        throw new IllegalArgumentException("length exceeds MLS varint range: " + value);
    }

    /**
     * Extract the era from the engine's 12-byte {@code eraEpoch} blob, or {@code -1} if unreadable.
     *
     * <p>Returns a sentinel rather than defaulting to 1. A guessed era produces a <em>known-wrong</em>
     * AAD at any era &gt; 1: the peer's AEAD fails, the message is dropped, and the visible symptom
     * appears far from the cause. A send that never happens is trivially diagnosable.
     */
    public static int eraFrom(final byte[] eraEpoch) {
        if (eraEpoch == null || eraEpoch.length != 12) return -1;
        return ((eraEpoch[0] & 0xff) << 24) | ((eraEpoch[1] & 0xff) << 16)
                | ((eraEpoch[2] & 0xff) << 8) | (eraEpoch[3] & 0xff);
    }

    /**
     * Extract the epoch from the engine's 12-byte {@code eraEpoch} blob, or {@code -1}.
     *
     * <p><b>Returns a long, and reads bytes 4..11.</b> An earlier version read bytes <b>8..11</b> and
     * returned an {@code int} — i.e. it took the LOW HALF of the u64 epoch and signed it. The blob is
     * {@code [era u32 BE][epoch u64 BE]} (see {@code ffi.rs} {@code era_epoch}: {@code era.to_be_bytes()}
     * then {@code epoch.to_be_bytes()}), so bytes 4..7 were being discarded entirely.
     *
     * <p>Two consequences, both silent: an epoch ≥ 2^31 read as negative, and an epoch ≥ 2^32 wrapped to
     * an unrelated small number. Any comparison built on it was wrong for those ranges, and the ordering
     * check the spec states as its own worked example —
     * {@code (era=1, epoch=0)} must sort <i>after</i> {@code (era=0, epoch=2^63)} — failed against us.
     *
     * <p>Compare epochs with {@link Long#compareUnsigned}, never with {@code <}. Better still, use
     * {@link Moment}, which encodes the era-major rule so the mistake cannot recur.
     */
    /**
     * The next era after {@code era}, or {@code -1} at the ceiling.
     *
     * <p>Era is an <b>unsigned</b> 32-bit counter that advances by exactly 1 and never wraps —
     * Google Messages panics at the ceiling rather than wrapping, and refuses a backwards move outright
     * ("{@code backwards in eras}"). Wrapping would be worse than failing: era 0 reads as a
     * catastrophic move backwards to every peer, and unlike a failed advance there is no way back.
     *
     * <p>The arithmetic happens here rather than in the engine because the engine never increments —
     * the new era comes FROM the server's, so a gap is impossible by construction. {@code long} in,
     * {@code long} out, but the value must still fit a {@code uint32} when it crosses the FFI, where
     * a silent truncation would land as era 0, i.e. a group with no Era extension at all.
     */
    public static long nextEra(final long era) {
        if (era < 0L || era >= 0xFFFFFFFFL) return -1L;
        return era + 1L;
    }

    public static long epochFrom(final byte[] eraEpoch) {
        if (eraEpoch == null || eraEpoch.length != 12) return -1L;
        long v = 0L;
        for (int i = 4; i < 12; i++) {
            v = (v << 8) | (eraEpoch[i] & 0xffL);
        }
        return v;
    }

    /**
     * A point in a conversation's MLS history: {@code (era, epoch)}, ordered <b>era-major</b>.
     *
     * <p>Exists because "which of these two states is newer" is asked in at least eight places —
     * out-of-order admission, staleness, self-heal comparisons, the send gate — and every one of them
     * got it wrong for large values while the raw accessors returned signed ints.
     *
     * <p><b>Both fields are unsigned on the wire</b> ({@code era} u32, {@code epoch} u64) and Java has
     * neither type, so comparison MUST go through {@link Integer#compareUnsigned} /
     * {@link Long#compareUnsigned}. Using {@code <} is the bug this type exists to prevent.
     *
     * <p>Era dominates absolutely: a new era restarts the epoch at 0, so {@code (1, 0)} is strictly
     * newer than {@code (0, anything)} — including {@code (0, 2^63)}, which is the spec's own worked
     * example and the case a signed comparison gets backwards.
     */
    public static final class Moment implements Comparable<Moment> {
        /** RCC.16 era (0xF001), unsigned 32-bit. */
        public final int era;
        /** RFC 9420 epoch, unsigned 64-bit. */
        public final long epoch;

        public Moment(final int era, final long epoch) {
            this.era = era;
            this.epoch = epoch;
        }

        /** Decode from the engine's 12-byte blob; null if it is not 12 bytes. */
        public static Moment from(final byte[] eraEpoch) {
            if (eraEpoch == null || eraEpoch.length != 12) return null;
            return new Moment(eraFrom(eraEpoch), epochFrom(eraEpoch));
        }

        /** Era-major, both fields unsigned. Negative if {@code this} is older than {@code o}. */
        @Override
        public int compareTo(final Moment o) {
            final int e = Integer.compareUnsigned(era, o.era);
            return e != 0 ? e : Long.compareUnsigned(epoch, o.epoch);
        }

        public boolean isNewerThan(final Moment o) { return o != null && compareTo(o) > 0; }
        public boolean isOlderThan(final Moment o) { return o != null && compareTo(o) < 0; }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Moment)) return false;
            final Moment m = (Moment) o;
            return era == m.era && epoch == m.epoch;
        }

        @Override
        public int hashCode() {
            return era * 31 + (int) (epoch ^ (epoch >>> 32));
        }

        /** Unsigned rendering — a large era or epoch must not print as negative. */
        @Override
        public String toString() {
            return "(era=" + Integer.toUnsignedString(era)
                    + " epoch=" + Long.toUnsignedString(epoch) + ")";
        }
    }
}
