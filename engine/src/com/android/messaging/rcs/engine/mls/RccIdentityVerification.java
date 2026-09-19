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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 <b>Annex C.5</b> — the Identity Verification Code (the "safety number") that §5.5 requires
 * a client to let users compare.
 *
 * <pre>
 *   struct { opaque msisdn&lt;V&gt;; opaque participant_identity_public_key&lt;V&gt;; } User;
 *   // Users are sorted by MSISDN ascending
 *   struct { User first_user; User second_user; } UserPairKeys;
 *
 *   string generate_code(UserPairKeys user_pair_keys) {
 *     if (second_user.msisdn &gt; first_user.msisdn) { swap(&amp;first_user, &amp;second_user); }
 *     hash = SHA512(user_pair_keys)
 *     return HASH_TO_DIGITS(&#47;*digit_count=*&#47;80, hash);
 *   }
 * </pre>
 *
 * <p>C.5 is <b>byte-identical in v3.0 and v4.0</b> — only a comment changed ("~265 bit" became
 * "~256-bit") — so this takes no {@link Rcc16Version}. That is worth stating: almost every other
 * Annex C construction we implement diverged between the two revisions.
 *
 * <h2>THE CODE ITSELF IS NOT IMPLEMENTABLE FROM THE SPEC — {@code HASH_TO_DIGITS} is undefined</h2>
 *
 * {@code HASH_TO_DIGITS} occurs <b>exactly once</b> in RCC.16 v3.0 and exactly once in v4.0: at the
 * call site above. It is never defined, never given a reference, and none of the 28 documents in
 * §1.5 Document Cross-References defines it either (RFC 4226 and the Signal/WhatsApp fingerprint
 * constructions are not among them). So {@link #identityVerificationCode} <b>throws</b> rather than
 * returning digits — see its javadoc for why a guess would be worse than nothing.
 *
 * <p>Everything BEFORE that call is fully specified, and is what this class provides:
 * {@link #userPairKeys} (the serialisation) and {@link #identityHash} (the SHA-512 over it). When
 * the derivation is settled, it is one method plus its tests, on top of a hash that is already
 * pinned here.
 *
 * <h2>The two spec ambiguities we had to resolve, and how</h2>
 *
 * <ol>
 *   <li><b>The sort direction contradicts itself.</b> The struct comment says "sorted by MSISDN
 *       <i>ascending</i>", but the swap implements the opposite: {@code if (second &gt; first) swap}
 *       leaves {@code first_user} holding the LARGER MSISDN in every case, which is descending. We
 *       follow the comment ({@link PairOrder#ASCENDING}) — it is the natural-language statement of
 *       intent, and the swap is an inverted comparison, a transcription error of the kind a
 *       reviewer does not catch. {@link PairOrder#DESCENDING} is implemented and tested alongside it
 *       so flipping is one argument, not a rewrite, if evidence from a real peer ever says
 *       otherwise. <b>This is a coin flip and it is not resolved.</b></li>
 *   <li><b>What "the MSISDN" is, exactly.</b> The spec never fixes a spelling. We put the
 *       {@link RccIdentity#normalizeE164 E.164-normalised bare digits} in — {@code "15551110001"},
 *       no {@code +}, no {@code tel:}, no separators — because that is the ONE form both sides of
 *       the pair can produce identically from what they hold: a peer's number arrives as
 *       {@code leaf_san_msisdn}, which is already {@code normalize_e164} of a SAN {@code tel:} URI,
 *       and our own arrives from configuration in whatever spelling the carrier used. Hashing the
 *       raw spelling would make {@code +1-555-111-0001} and {@code tel:+15551110001} produce
 *       different codes for one number, which is the failure this whole feature exists to rule
 *       out.</li>
 * </ol>
 *
 * <p>The comparison itself is <b>lexicographic over those bytes</b>, unsigned — what comparing two
 * {@code opaque} vectors means in the presentation language, and the only comparison that is
 * well-defined without parsing a number. Note it is not numeric order: {@code "1999"} sorts after
 * {@code "12345678901"}. Both sides agree because both sort the same canonical bytes.
 *
 * <p><b>Assumption, spec-silent:</b> when the two MSISDNs are EQUAL (one user, two devices — C.5
 * says "a pair of Participants" and does not exclude it) the sort is not a total order and the two
 * sides could serialise in opposite orders. We tie-break on the participant key bytes so symmetry
 * survives. Nothing in C.5 sanctions this; it is here because the alternative is a code that
 * disagrees with itself.
 *
 * <p>Pure Java, no Android, so the construction is host-testable — the same reason
 * {@link RccCommitment} and {@link RccIdentity} are. There is no peer we can ask to check a code
 * against, so unit tests over the spec text are the only honest coverage.
 */
public final class RccIdentityVerification {

    private RccIdentityVerification() { }

    /** RCC.16 §5.5 / C.5 — the number of digits shown to the user. */
    public static final int DIGIT_COUNT = 80;

    /** Which end of the sort {@code first_user} is. See the class javadoc: the spec says both. */
    public enum PairOrder {
        /** {@code first_user} holds the SMALLER MSISDN — what C.5's struct comment states. */
        ASCENDING,
        /** {@code first_user} holds the LARGER MSISDN — what C.5's swap actually implements. */
        DESCENDING
    }

    /**
     * One side of the pair: an MSISDN and that participant's identity public key.
     *
     * <p>The key is the RCC.16 A.3.8 ParticipantInformation <b>SubjectPublicKeyInfo, whole</b> —
     * the SPKI TLV re-encoded in full, not the public-key bit string alone. That is deliberate
     * upstream ({@code leaf_participant_key_spki}) and matters here for the same
     * reason: two keys differing only in algorithm parameters would otherwise hash identically and
     * verify as the same participant.
     */
    public static final class User {
        /** The MSISDN as supplied; normalised on the way into the hash, never stored normalised. */
        public final String msisdn;
        /** The participant identity public key, as SPKI DER. */
        public final byte[] participantKeySpki;

        public User(final String msisdn, final byte[] participantKeySpki) {
            this.msisdn = msisdn == null ? "" : msisdn;
            this.participantKeySpki = participantKeySpki == null
                    ? new byte[0] : participantKeySpki.clone();
        }

        /**
         * From the hex spelling {@code MlsParticipantKeyResync.Leaf.signedByParticipantKey} carries.
         *
         * <p>{@code OpenMlsSession.memberParticipantKeys} hands the roster back with the SPKI
         * hex-encoded, and that is the shape a caller actually has. Odd-length or non-hex input
         * yields an EMPTY key, which {@link #canVerify} then refuses — the same "we did not look"
         * convention {@code memberParticipantKeys} uses for a leaf it could not parse.
         */
        public static User fromHexKey(final String msisdn, final String participantKeySpkiHex) {
            return new User(msisdn, unhex(participantKeySpkiHex));
        }

        /** The bytes actually hashed for {@code opaque msisdn<V>}: normalised E.164, ASCII. */
        byte[] canonicalMsisdn() {
            return RccIdentity.normalizeE164(msisdn).getBytes(StandardCharsets.US_ASCII);
        }
    }

    /**
     * Is there enough to show the user a code at all?
     *
     * <p>A first-class question rather than a null test, because the answer gates a UI affordance:
     * an empty participant key means the leaf did not parse, and a code computed over a key we could
     * not read would bind nothing while looking exactly like one that did. That is the one outcome
     * a verification feature must never produce.
     */
    public static boolean canVerify(final User a, final User b) {
        return usable(a) && usable(b);
    }

    private static boolean usable(final User u) {
        return u != null && u.participantKeySpki.length > 0 && u.canonicalMsisdn().length > 0;
    }

    /**
     * The serialised {@code UserPairKeys} — the exact bytes C.5 hashes, sorted per {@code order}.
     *
     * <p>{@code varint(|m1|) ‖ m1 ‖ varint(|k1|) ‖ k1 ‖ varint(|m2|) ‖ m2 ‖ varint(|k2|) ‖ k2}.
     * The structs add no framing of their own; {@code opaque x<V>} is the RFC 9420 §6.2.2 varint
     * length prefix, taken from {@link MlsAppMessage#mlsVarint} rather than copied — a raw
     * length byte is correct only below 64 and then silently diverges from the peer.
     *
     * @return the bytes, or {@code null} if {@link #canVerify} would be false
     */
    public static byte[] userPairKeys(final User a, final User b, final PairOrder order) {
        if (!canVerify(a, b)) return null;
        final boolean aFirst = (compare(a, b) <= 0) == (order == PairOrder.ASCENDING);
        final User first = aFirst ? a : b;
        final User second = aFirst ? b : a;
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            writeUser(out, first);
            writeUser(out, second);
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    private static void writeUser(final ByteArrayOutputStream out, final User u) throws Exception {
        final byte[] m = u.canonicalMsisdn();
        out.write(MlsAppMessage.mlsVarint(m.length));
        out.write(m);
        out.write(MlsAppMessage.mlsVarint(u.participantKeySpki.length));
        out.write(u.participantKeySpki);
    }

    /**
     * The sort key: canonical MSISDN bytes, unsigned lexicographic, tie-broken on the key.
     *
     * <p>The tie-break is not in C.5 — see the class javadoc. Without it, two clients of the same
     * number would serialise the pair in opposite orders and show different codes.
     */
    static int compare(final User a, final User b) {
        final int m = compareUnsigned(a.canonicalMsisdn(), b.canonicalMsisdn());
        return m != 0 ? m : compareUnsigned(a.participantKeySpki, b.participantKeySpki);
    }

    private static int compareUnsigned(final byte[] x, final byte[] y) {
        final int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            final int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) return d;
        }
        return x.length - y.length;
    }

    /**
     * {@code SHA512(user_pair_keys)} — 64 bytes, and the last step C.5 fully specifies.
     *
     * <p>Symmetric by construction: both parties pass their own user and their peer's in whatever
     * order is convenient, and the sort makes the input identical on both sides. That symmetry is
     * the entire point of the sort, and it is the property a user relies on when they read a code
     * aloud.
     *
     * @return 64 bytes, or {@code null} if {@link #canVerify} would be false
     */
    public static byte[] identityHash(final User a, final User b, final PairOrder order) {
        final byte[] serialized = userPairKeys(a, b, order);
        if (serialized == null) return null;
        try {
            return MessageDigest.getInstance("SHA-512").digest(serialized);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** {@link #identityHash} under {@link PairOrder#ASCENDING}, C.5's stated order. */
    public static byte[] identityHash(final User a, final User b) {
        return identityHash(a, b, PairOrder.ASCENDING);
    }

    /** {@link #userPairKeys} under {@link PairOrder#ASCENDING}, C.5's stated order. */
    public static byte[] userPairKeys(final User a, final User b) {
        return userPairKeys(a, b, PairOrder.ASCENDING);
    }

    /**
     * The 80 digits §5.5 says to display. <b>Always throws</b> — RCC.16 does not define
     * {@code HASH_TO_DIGITS}, so we cannot compute this, and a guess must not reach a user.
     *
     * <p><b>Why not just pick something plausible.</b> At least three constructions turn a 64-byte
     * SHA-512 into exactly 80 digits, they disagree completely, and each is defensible from the
     * spec's own text:
     *
     * <ol>
     *   <li>{@code BigInteger(1, hash) mod 10^80}, zero-padded — supported by v3.0's comment
     *       "returns ~265 bit representation", since log2(10^80) = 265.75;</li>
     *   <li>16 big-endian 32-bit words, each {@code mod 100000} → 5 digits — uses all 64 bytes
     *       exactly, and 16 × 5 = 80 falls out with nothing left over;</li>
     *   <li>20 big-endian 24-bit groups, each {@code mod 10000} → 4 digits — 20 × 4 = 80, the
     *       Signal-style chunked-truncation shape, which for a 64-byte hash and 5-byte chunks
     *       would give 60 digits and so has to be re-chunked to reach 80.</li>
     * </ol>
     *
     * <p>Pick wrong and every pair of users sees two different 80-digit codes for a channel that is
     * perfectly secure. They would compare, disagree, and conclude they were being attacked. An
     * unimplemented verification feature is a missing feature; an interoperable-LOOKING one that
     * does not interoperate manufactures exactly the alarm it exists to prevent — so this fails
     * loudly at the seam instead, the same reason {@link MlsAppMessage#eraFrom} returns a sentinel
     * rather than guessing an era.
     *
     * <p>Resolving it needs evidence, not reasoning: a Google Messages implementation's output for a known
     * pair, or a GSMA erratum. {@link #identityHash} is the input it plugs into.
     *
     * @throws UnsupportedOperationException always
     */
    public static String identityVerificationCode(final User a, final User b) {
        throw new UnsupportedOperationException(
                "RCC.16 C.5 HASH_TO_DIGITS is undefined in v3.0 and v4.0 (one occurrence, the call "
                + "site; no cross-reference defines it). Use identityHash() and resolve "
                + "the digit derivation from a peer implementation before displaying anything.");
    }

    /** Lenient hex → bytes; empty on anything malformed. */
    private static byte[] unhex(final String s) {
        if (s == null || s.isEmpty() || (s.length() & 1) != 0) return new byte[0];
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(i * 2), 16);
            final int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
