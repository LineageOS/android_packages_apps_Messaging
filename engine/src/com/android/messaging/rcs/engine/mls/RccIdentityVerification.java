/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 Annex C.5 identity verification (the RCC.16 §5.5 safety number): the serialised
 * {@code UserPairKeys} and its SHA-512. C.5 never defines {@code HASH_TO_DIGITS}, so the digits
 * themselves are not produced. C.5 contradicts itself on sort direction; {@link PairOrder} offers
 * both, defaulting to the stated ascending order. MSISDNs are hashed as normalised E.164 digits and
 * compared as unsigned bytes, with equal MSISDNs tie-broken on the key (not in C.5) so both sides
 * serialise identically. The text is the same in v3.0 and v4.0.
 */
public final class RccIdentityVerification {

    private RccIdentityVerification() { }

    /** Digits shown to the user (RCC.16 §5.5, C.5). */
    public static final int DIGIT_COUNT = 80;

    /** Which end of the sort {@code first_user} is; C.5 states one and implements the other. */
    public enum PairOrder {
        /** {@code first_user} has the smaller MSISDN, as C.5's struct comment states. */
        ASCENDING,
        /** {@code first_user} has the larger MSISDN, as C.5's swap implements. */
        DESCENDING
    }

    /**
     * One side of the pair: an MSISDN and the participant's whole RCC.16 A.3.8 SubjectPublicKeyInfo
     * (not just the key bits, so algorithm parameters are bound too).
     */
    public static final class User {
        /** As supplied; normalised only when hashed. */
        public final String msisdn;
        /** The participant identity public key, as SPKI DER. */
        public final byte[] participantKeySpki;

        public User(final String msisdn, final byte[] participantKeySpki) {
            this.msisdn = msisdn == null ? "" : msisdn;
            this.participantKeySpki = participantKeySpki == null
                    ? new byte[0] : participantKeySpki.clone();
        }

        /**
         * From the hex SPKI that {@code OpenMlsSession.memberParticipantKeys} returns. Malformed
         * hex gives an empty key, which {@link #canVerify} refuses.
         */
        public static User fromHexKey(final String msisdn, final String participantKeySpkiHex) {
            return new User(msisdn, unhex(participantKeySpkiHex));
        }

        /** The bytes hashed for {@code opaque msisdn<V>}: normalised E.164, ASCII. */
        byte[] canonicalMsisdn() {
            return RccIdentity.normalizeE164(msisdn).getBytes(StandardCharsets.US_ASCII);
        }
    }

    /**
     * Whether a code can be shown at all. An empty key means the leaf did not parse, and a code
     * over it would bind nothing.
     */
    public static boolean canVerify(final User a, final User b) {
        return usable(a) && usable(b);
    }

    private static boolean usable(final User u) {
        return u != null && u.participantKeySpki.length > 0 && u.canonicalMsisdn().length > 0;
    }

    /**
     * The serialised {@code UserPairKeys}, sorted per {@code order}:
     * {@code varint(|m1|) ‖ m1 ‖ varint(|k1|) ‖ k1 ‖ varint(|m2|) ‖ m2 ‖ varint(|k2|) ‖ k2}, with
     * the RFC 9420 §6.2.2 varint.
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

    /** Canonical MSISDN bytes, unsigned lexicographic, tie-broken on the key. */
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
     * SHA-512 of {@code user_pair_keys}, the last step C.5 specifies. The sort makes it the same
     * whichever order the two users are passed in.
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
     * The 80 displayed digits. Always throws: RCC.16 does not define {@code HASH_TO_DIGITS},
     * several plausible derivations disagree, and a wrong one would show honest users mismatching
     * codes.
     *
     * @throws UnsupportedOperationException always
     */
    public static String identityVerificationCode(final User a, final User b) {
        throw new UnsupportedOperationException(
                "RCC.16 C.5 HASH_TO_DIGITS is undefined in v3.0 and v4.0 (one occurrence, the call "
                + "site; no cross-reference defines it). Use identityHash() and resolve "
                + "the digit derivation from a peer implementation before displaying anything.");
    }

    /** Hex to bytes; empty on anything malformed. */
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
