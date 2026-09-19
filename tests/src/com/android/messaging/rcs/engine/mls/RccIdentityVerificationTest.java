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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.RccIdentityVerification.PairOrder;
import com.android.messaging.rcs.engine.mls.RccIdentityVerification.User;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * RCC.16 Annex C.5 — the Identity Verification Code construction.
 *
 * <p>Like {@link RccCommitmentTest}, the shape assertions recompute C.5 from the spec text rather
 * than pinning a digest this implementation produced: a golden value taken from our own output
 * passes just as happily when the construction is wrong.
 *
 * <p>There is <b>no known-answer vector</b> — RCC.16 publishes none for C.5, and no peer we can ask.
 * {@link #pinnedAgainstAnIndependentImplementation} is the nearest available thing: a SHA-512
 * computed OUTSIDE the JVM (Python, from the spec text) over the framing we chose. It pins our
 * encoding against a second implementation; it does not prove the encoding is the one GSMA meant.
 */
public final class RccIdentityVerificationTest {

    private static final String M_A = "15551110001";
    private static final String M_B = "15551110002";

    /** 91 bytes — the length of a real P-256 SPKI, and past the 1-byte varint boundary at 64. */
    private static byte[] keyA() { return pattern(91, 7, 3); }
    private static byte[] keyB() { return pattern(91, 11, 5); }

    private static byte[] pattern(final int n, final int mul, final int add) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * mul + add);
        return b;
    }

    private static User a() { return new User(M_A, keyA()); }
    private static User b() { return new User(M_B, keyB()); }

    /** Independent restatement of C.5, written from the spec text rather than from the source. */
    private static byte[] expectedHash(final String m1, final byte[] k1,
            final String m2, final byte[] k2) throws Exception {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeUser(o, m1, k1);
        writeUser(o, m2, k2);
        return MessageDigest.getInstance("SHA-512").digest(o.toByteArray());
    }

    private static void writeUser(final ByteArrayOutputStream o, final String m, final byte[] k)
            throws Exception {
        final byte[] mb = m.getBytes(StandardCharsets.US_ASCII);
        o.write(MlsAppMessage.mlsVarint(mb.length));
        o.write(mb);
        o.write(MlsAppMessage.mlsVarint(k.length));
        o.write(k);
    }

    // ---- the construction -------------------------------------------------------------------

    @Test
    public void matchesTheSpecConstruction() throws Exception {
        assertArrayEquals(expectedHash(M_A, keyA(), M_B, keyB()),
                RccIdentityVerification.identityHash(a(), b()));
    }

    @Test
    public void hashIsSha512Sized() {
        assertEquals(64, RccIdentityVerification.identityHash(a(), b()).length);
    }

    /**
     * A second implementation's answer, computed outside the JVM. Not a spec KAT — see the class
     * javadoc — but it catches a same-file, same-idea bug that {@link #matchesTheSpecConstruction}
     * would reproduce in both the code and the expectation.
     */
    @Test
    public void pinnedAgainstAnIndependentImplementation() {
        assertEquals(
                "b9dcd204409a914e0f950427cee2ba9592aa1111dc803dba7ff5fb8677a0633b"
                + "a1f34bac55619ae2c014415e671e03a4e0fb5aa91af2ae9743b08373cbf1418c",
                hex(RccIdentityVerification.identityHash(a(), b(), PairOrder.ASCENDING)));
        assertEquals(
                "bfe324d9440737ce6b8562e457b4996938deb79e9b2f8c23271ff98f2fb3837b"
                + "978d77dc0365e20897e2b49cf053ae79d30a964e6700c3c53e94774399d9b7a3",
                hex(RccIdentityVerification.identityHash(a(), b(), PairOrder.DESCENDING)));
    }

    @Test
    public void isDeterministic() {
        assertArrayEquals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(a(), b()));
    }

    // ---- symmetry: the property the sort exists for ------------------------------------------

    /** Each party passes itself first; the code they read to each other must be the same one. */
    @Test
    public void argumentOrderDoesNotChangeTheCode() {
        assertArrayEquals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(b(), a()));
    }

    @Test
    public void symmetryHoldsUnderBothSortDirections() {
        for (final PairOrder o : PairOrder.values()) {
            assertArrayEquals("order " + o,
                    RccIdentityVerification.identityHash(a(), b(), o),
                    RccIdentityVerification.identityHash(b(), a(), o));
        }
    }

    /**
     * The MSISDNs are equal only when one user has two devices. C.5's sort is then not a total
     * order; the participant-key tie-break (spec-silent, see the class javadoc) keeps symmetry.
     */
    @Test
    public void symmetryHoldsWhenBothMsisdnsAreEqual() {
        final User x = new User(M_A, keyA());
        final User y = new User(M_A, keyB());
        assertArrayEquals(RccIdentityVerification.identityHash(x, y),
                RccIdentityVerification.identityHash(y, x));
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(x, y),
                RccIdentityVerification.identityHash(x, x)));
    }

    // ---- the sort ----------------------------------------------------------------------------

    /** ASCENDING puts the SMALLER MSISDN in {@code first_user} — what C.5's struct comment says. */
    @Test
    public void ascendingPutsTheSmallerMsisdnFirst() throws Exception {
        assertArrayEquals(expectedHash(M_A, keyA(), M_B, keyB()),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.ASCENDING));
    }

    /** DESCENDING is what C.5's swap actually implements. Both are reachable; neither is proven. */
    @Test
    public void descendingPutsTheLargerMsisdnFirst() throws Exception {
        assertArrayEquals(expectedHash(M_B, keyB(), M_A, keyA()),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.DESCENDING));
    }

    /** If the two directions agreed, the contradiction in C.5 would not matter. They do not. */
    @Test
    public void theTwoSortDirectionsGiveDifferentCodes() {
        assertFalse(Arrays.equals(
                RccIdentityVerification.identityHash(a(), b(), PairOrder.ASCENDING),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.DESCENDING)));
    }

    /**
     * The comparison is lexicographic over the canonical bytes, NOT numeric: a shorter number can
     * sort after a longer one. Pinned because "sorted by MSISDN" reads like numeric order and a
     * later reader might helpfully "fix" it into one, silently changing every code.
     */
    @Test
    public void comparisonIsLexicographicNotNumeric() throws Exception {
        final User big = new User("1999", keyA());          // numerically the larger
        final User small = new User("12345678901", keyB()); // numerically the smaller
        assertArrayEquals(expectedHash("12345678901", keyB(), "1999", keyA()),
                RccIdentityVerification.identityHash(big, small, PairOrder.ASCENDING));
    }

    // ---- MSISDN canonicalisation --------------------------------------------------------------

    /** Every spelling of one number must give one code, or the feature reports false alarms. */
    @Test
    public void msisdnSpellingDoesNotChangeTheCode() {
        final byte[] want = RccIdentityVerification.identityHash(a(), b());
        for (final String spelling : new String[] {
                "+15551110001", "+1-555-111-0001", "tel:+15551110001",
                "tel:+15551110001;phone-context=example.com", "(1) 555 111 0001" }) {
            assertArrayEquals(spelling,
                    want, RccIdentityVerification.identityHash(new User(spelling, keyA()), b()));
        }
    }

    /** Different numbers must not collide once separators are stripped. */
    @Test
    public void differentNumbersStayDifferent() {
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(new User("15551110003", keyA()), b())));
    }

    // ---- sensitivity ---------------------------------------------------------------------------

    /** One flipped bit in a participant key is a different peer. */
    @Test
    public void oneBitOfKeyChangesTheCode() {
        final byte[] k = keyA();
        k[42] ^= 0x01;
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(new User(M_A, k), b())));
    }

    /** Including a bit in the LAST byte — a truncated hash input would miss it. */
    @Test
    public void oneBitOfTheLastKeyByteChangesTheCode() {
        final byte[] k = keyB();
        k[k.length - 1] ^= 0x80;
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(a(), new User(M_B, k))));
    }

    /** Swapping which key belongs to which number must change the code. */
    @Test
    public void keysAreBoundToTheirOwnMsisdn() {
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(
                        new User(M_A, keyB()), new User(M_B, keyA()))));
    }

    // ---- opaque<V> framing ---------------------------------------------------------------------

    /**
     * The length prefixes are what stop the four fields from being ambiguous. Without them
     * {@code "12"+"3"} and {@code "1"+"23"} would hash identically.
     */
    @Test
    public void lengthPrefixesDisambiguateTheFields() {
        final byte[] one = RccIdentityVerification.identityHash(
                new User("12", new byte[] {3}), b());
        final byte[] two = RccIdentityVerification.identityHash(
                new User("1", new byte[] {2, 3}), b());
        assertNotNull(one);
        assertFalse(Arrays.equals(one, two));
    }

    /**
     * {@code <V>} is the MLS varint, not a raw byte: a 91-byte key takes the TWO-byte form
     * {@code 40 5B}. A raw-byte prefix would emit {@code 5B} and the peer would read a different
     * length — the exact latent bug {@link MlsAppMessage#mlsVarint} documents.
     */
    @Test
    public void keyLengthUsesTheTwoByteVarintFormPast63() {
        final byte[] s = RccIdentityVerification.userPairKeys(a(), b(), PairOrder.ASCENDING);
        // varint(11) | "15551110001" | varint(91) ...
        assertEquals(0x0B, s[0] & 0xFF);
        assertEquals("15551110001", new String(s, 1, 11, StandardCharsets.US_ASCII));
        assertEquals(0x40, s[12] & 0xFF);
        assertEquals(0x5B, s[13] & 0xFF);
        // 2 users x (1 + 11 + 2 + 91)
        assertEquals(2 * 105, s.length);
    }

    /**
     * 63 stays one byte, 64 becomes two — the boundary the varint exists for. 64 encodes as
     * {@code 40 40}, whose second byte is the length and whose first is the FORM: a raw-byte
     * prefix would emit the single byte {@code 40} for the same length, so the two encodings
     * differ by a byte that is invisible in the value.
     */
    @Test
    public void varintBoundaryAtSixtyFour() {
        final byte[] at63 = RccIdentityVerification.userPairKeys(
                new User(M_A, new byte[63]), b(), PairOrder.ASCENDING);
        final byte[] at64 = RccIdentityVerification.userPairKeys(
                new User(M_A, new byte[64]), b(), PairOrder.ASCENDING);
        assertEquals(0x3F, at63[12] & 0xFF);
        assertEquals(0x40, at64[12] & 0xFF);
        assertEquals(0x40, at64[13] & 0xFF);
        assertEquals(at63.length + 2, at64.length);
    }

    // ---- refusals ------------------------------------------------------------------------------

    /**
     * An empty participant key means the leaf did not parse ({@code memberParticipantKeys}'s "we
     * did not look" convention). A code over a key we could not read binds nothing while looking
     * identical to one that did.
     */
    @Test
    public void anUnreadableKeyProducesNoCode() {
        final User blank = new User(M_A, new byte[0]);
        assertFalse(RccIdentityVerification.canVerify(blank, b()));
        assertNull(RccIdentityVerification.identityHash(blank, b()));
        assertNull(RccIdentityVerification.userPairKeys(b(), blank));
    }

    @Test
    public void aMissingMsisdnProducesNoCode() {
        for (final String m : new String[] {null, "", "not a number"}) {
            final User u = new User(m, keyA());
            assertFalse(String.valueOf(m), RccIdentityVerification.canVerify(u, b()));
            assertNull(String.valueOf(m), RccIdentityVerification.identityHash(u, b()));
        }
    }

    @Test
    public void aNullUserProducesNoCode() {
        assertFalse(RccIdentityVerification.canVerify(null, b()));
        assertNull(RccIdentityVerification.identityHash(null, b()));
        assertNull(RccIdentityVerification.identityHash(a(), null));
    }

    @Test
    public void aGoodPairCanVerify() {
        assertTrue(RccIdentityVerification.canVerify(a(), b()));
    }

    // ---- the deliberate gap ---------------------------------------------------------------------

    /**
     * RCC.16 never defines {@code HASH_TO_DIGITS}, so there are no digits to display. Pinned as a
     * test so that implementing it is a deliberate act with a failing test attached, not a quiet
     * one-liner somebody adds because a UI needed a string.
     */
    @Test
    public void theDigitDerivationIsNotImplementable() {
        try {
            RccIdentityVerification.identityVerificationCode(a(), b());
            fail("identityVerificationCode must not invent a digit derivation");
        } catch (final UnsupportedOperationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HASH_TO_DIGITS"));
        }
    }

    /** §5.5 fixes the digit count even though it does not fix how to get them. */
    @Test
    public void theSpecDigitCountIsEighty() {
        assertEquals(80, RccIdentityVerification.DIGIT_COUNT);
    }

    // ---- the caller's actual input shape ---------------------------------------------------------

    /** {@code MlsParticipantKeyResync.Leaf.signedByParticipantKey} is hex; that must round-trip. */
    @Test
    public void hexKeysMatchByteKeys() {
        assertArrayEquals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(
                        User.fromHexKey(M_A, hex(keyA())), User.fromHexKey(M_B, hex(keyB()))));
    }

    /** Malformed hex must land in the refusal path, not in a code over half a key. */
    @Test
    public void malformedHexIsTreatedAsUnreadable() {
        for (final String bad : new String[] {null, "", "abc", "zz", "00zz"}) {
            assertFalse(String.valueOf(bad),
                    RccIdentityVerification.canVerify(User.fromHexKey(M_A, bad), b()));
        }
    }

    private static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
