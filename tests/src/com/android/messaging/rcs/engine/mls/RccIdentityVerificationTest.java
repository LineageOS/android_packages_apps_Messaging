/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The identity verification code (RCC.16 Annex C.5), recomputed from the spec text. The spec
 * publishes no known-answer vector; {@link #pinnedAgainstAnIndependentImplementation} pins our
 * framing against a second implementation outside the JVM, which does not prove the framing is the
 * intended one.
 */
public final class RccIdentityVerificationTest {

    private static final String M_A = "15551110001";
    private static final String M_B = "15551110002";

    /** 91 bytes: a P-256 SPKI's length, past the 1-byte varint boundary at 64. */
    private static byte[] keyA() { return pattern(91, 7, 3); }
    private static byte[] keyB() { return pattern(91, 11, 5); }

    private static byte[] pattern(final int n, final int mul, final int add) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * mul + add);
        return b;
    }

    private static User a() { return new User(M_A, keyA()); }
    private static User b() { return new User(M_B, keyB()); }

    /** Annex C.5, restated from the spec text. */
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

    @Test
    public void matchesTheSpecConstruction() throws Exception {
        assertArrayEquals(expectedHash(M_A, keyA(), M_B, keyB()),
                RccIdentityVerification.identityHash(a(), b()));
    }

    @Test
    public void hashIsSha512Sized() {
        assertEquals(64, RccIdentityVerification.identityHash(a(), b()).length);
    }

    /** Catches a bug {@link #matchesTheSpecConstruction} would share with the code. */
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

    /** Each party passes itself first; both must read out the same code. */
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

    /** Equal MSISDNs (one user, two devices): a participant-key tie-break, not in the spec. */
    @Test
    public void symmetryHoldsWhenBothMsisdnsAreEqual() {
        final User x = new User(M_A, keyA());
        final User y = new User(M_A, keyB());
        assertArrayEquals(RccIdentityVerification.identityHash(x, y),
                RccIdentityVerification.identityHash(y, x));
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(x, y),
                RccIdentityVerification.identityHash(x, x)));
    }

    /** ASCENDING puts the smaller MSISDN in {@code first_user}, as Annex C.5's struct says. */
    @Test
    public void ascendingPutsTheSmallerMsisdnFirst() throws Exception {
        assertArrayEquals(expectedHash(M_A, keyA(), M_B, keyB()),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.ASCENDING));
    }

    /** DESCENDING is what Annex C.5's swap implements; the text contradicts itself. */
    @Test
    public void descendingPutsTheLargerMsisdnFirst() throws Exception {
        assertArrayEquals(expectedHash(M_B, keyB(), M_A, keyA()),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.DESCENDING));
    }

    @Test
    public void theTwoSortDirectionsGiveDifferentCodes() {
        assertFalse(Arrays.equals(
                RccIdentityVerification.identityHash(a(), b(), PairOrder.ASCENDING),
                RccIdentityVerification.identityHash(a(), b(), PairOrder.DESCENDING)));
    }

    /** Lexicographic over the canonical bytes, not numeric: a shorter number can sort later. */
    @Test
    public void comparisonIsLexicographicNotNumeric() throws Exception {
        final User big = new User("1999", keyA());
        final User small = new User("12345678901", keyB());
        assertArrayEquals(expectedHash("12345678901", keyB(), "1999", keyA()),
                RccIdentityVerification.identityHash(big, small, PairOrder.ASCENDING));
    }

    /** Every spelling of one number gives one code. */
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

    @Test
    public void differentNumbersStayDifferent() {
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(new User("15551110003", keyA()), b())));
    }

    @Test
    public void oneBitOfKeyChangesTheCode() {
        final byte[] k = keyA();
        k[42] ^= 0x01;
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(new User(M_A, k), b())));
    }

    /** A truncated hash input would miss the last byte. */
    @Test
    public void oneBitOfTheLastKeyByteChangesTheCode() {
        final byte[] k = keyB();
        k[k.length - 1] ^= 0x80;
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(a(), new User(M_B, k))));
    }

    @Test
    public void keysAreBoundToTheirOwnMsisdn() {
        assertFalse(Arrays.equals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(
                        new User(M_A, keyB()), new User(M_B, keyA()))));
    }

    /** Without length prefixes {@code "12"+"3"} and {@code "1"+"23"} would hash identically. */
    @Test
    public void lengthPrefixesDisambiguateTheFields() {
        final byte[] one = RccIdentityVerification.identityHash(
                new User("12", new byte[] {3}), b());
        final byte[] two = RccIdentityVerification.identityHash(
                new User("1", new byte[] {2, 3}), b());
        assertNotNull(one);
        assertFalse(Arrays.equals(one, two));
    }

    /** {@code <V>} is the MLS varint: a 91-byte key takes the two-byte form {@code 40 5B}. */
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

    /** 63 stays one byte; 64 becomes {@code 40 40}, where a raw-byte prefix would be {@code 40}. */
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

    /** An empty key means the leaf did not parse; a code over it would bind nothing. */
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

    /** RCC.16 never defines {@code HASH_TO_DIGITS}, so no digit derivation is invented. */
    @Test
    public void theDigitDerivationIsNotImplementable() {
        try {
            RccIdentityVerification.identityVerificationCode(a(), b());
            fail("identityVerificationCode must not invent a digit derivation");
        } catch (final UnsupportedOperationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HASH_TO_DIGITS"));
        }
    }

    /** RCC.16 §5.5 fixes the digit count, though not how to derive the digits. */
    @Test
    public void theSpecDigitCountIsEighty() {
        assertEquals(80, RccIdentityVerification.DIGIT_COUNT);
    }

    /** {@code MlsParticipantKeyResync.Leaf.signedByParticipantKey} is hex. */
    @Test
    public void hexKeysMatchByteKeys() {
        assertArrayEquals(RccIdentityVerification.identityHash(a(), b()),
                RccIdentityVerification.identityHash(
                        User.fromHexKey(M_A, hex(keyA())), User.fromHexKey(M_B, hex(keyB()))));
    }

    /** Malformed hex is refused rather than hashed as half a key. */
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
