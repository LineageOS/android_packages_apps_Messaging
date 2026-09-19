/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * Known-answer tests for {@link MlsParticipantKeyDerivation}. The curve arithmetic has a second
 * copy in the provider and agreement between the two is asserted there; these are the absolute
 * checks: NIST's published {@code G} and {@code 2G}, on-curve products, and the scalar map at the
 * boundaries a modulo bug lands on.
 */
public class MlsParticipantKeyDerivationTest {

    @Test
    public void oneTimesGIsTheNistBasePoint() {
        final BigInteger[] g1 = MlsParticipantKeyDerivation.scalarMulG(BigInteger.ONE);
        assertEquals(new BigInteger(
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16), g1[0]);
        assertEquals(new BigInteger(
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16), g1[1]);
    }

    /** 2·G for NIST P-256, a published value, checks the doubling path. */
    @Test
    public void twoTimesGMatchesThePublishedValue() {
        final BigInteger[] g2 = MlsParticipantKeyDerivation.scalarMulG(BigInteger.valueOf(2));
        assertEquals(new BigInteger(
                "7CF27B188D034F7E8A52380304B51AC3C08969E277F21B35A60B48FC47669978", 16), g2[0]);
        assertEquals(new BigInteger(
                "07775510DB8ED040293D9AC69F7430DBBA7DADE63CE982299E04B79D227873D1", 16), g2[1]);
    }

    @Test
    public void everyProductIsOnTheCurve() {
        for (final long k : new long[] {1L, 2L, 3L, 7L, 123456789L, Integer.MAX_VALUE}) {
            final BigInteger[] q = MlsParticipantKeyDerivation.scalarMulG(BigInteger.valueOf(k));
            assertTrue(k + ".G must be on the curve",
                    MlsParticipantKeyDerivation.isOnCurve(q[0], q[1]));
        }
    }

    /**
     * The scalar map lands in {@code [1, n-1]}, including for inputs {@code n-1} and {@code n},
     * where a wrong modulus would produce a private key the JCE refuses.
     */
    @Test
    public void theScalarMapStaysInRange() {
        final byte[][] materials = {
            new byte[32],
            hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"),
            hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632550"),   // n-1
            hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),   // n
        };
        for (final byte[] m : materials) {
            final BigInteger s = MlsParticipantKeyDerivation.scalarInRange(m);
            assertTrue("scalar >= 1", s.signum() > 0);
            assertTrue("scalar <= n-1", s.compareTo(MlsParticipantKeyDerivation.N) < 0);
        }
    }

    /**
     * The KDF length-prefixes both inputs, so ("AB", "C") and ("A", "BC") derive different keys;
     * the label is what separates the trust domains.
     */
    @Test
    public void theKdfIsUnambiguousAcrossTheLabelBoundary() throws Exception {
        final byte[] a =
                MlsParticipantKeyDerivation.kdfHash("AB", "C".getBytes(StandardCharsets.UTF_8));
        final byte[] b =
                MlsParticipantKeyDerivation.kdfHash("A", "BC".getBytes(StandardCharsets.UTF_8));
        assertFalse("length-prefixing must keep these preimages apart", Arrays.equals(a, b));
    }

    /** A secret over 127 bytes needs a two-byte varint; the framing still holds. */
    @Test
    public void aLongSecretDerivesADistinctStableKey() throws Exception {
        final byte[] longSecret = new byte[200];
        Arrays.fill(longSecret, (byte) 0x5A);
        final KeyPair one = MlsParticipantKeyDerivation.deriveP256(
                longSecret, MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY);
        final KeyPair two = MlsParticipantKeyDerivation.deriveP256(
                longSecret, MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY);
        assertArrayEquals("derivation must be deterministic",
                one.getPublic().getEncoded(), two.getPublic().getEncoded());

        final byte[] shortSecret = new byte[100];
        Arrays.fill(shortSecret, (byte) 0x5A);
        assertFalse("a different-length secret of the same bytes must derive a different key",
                Arrays.equals(one.getPublic().getEncoded(),
                        MlsParticipantKeyDerivation.deriveP256(
                                shortSecret, MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY)
                                .getPublic().getEncoded()));
    }

    /**
     * The SPKI's BIT STRING content is the 65-byte SEC1 point, so {@code participant_key} and
     * {@code identity_pub} are one key in two encodings.
     */
    @Test
    public void spkiAndSec1PointAreTheSameKey() throws Exception {
        final KeyPair kp = MlsParticipantKeyDerivation.deriveP256(
                "spki-vs-point".getBytes(StandardCharsets.UTF_8),
                MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY);
        final byte[] spki = kp.getPublic().getEncoded();
        final byte[] point = MlsParticipantKeyDerivation.sec1Point65(kp.getPublic());

        assertEquals("P-256 SPKI DER is 91 bytes", 91, spki.length);
        assertEquals("SEC1 uncompressed point is 65 bytes", 65, point.length);
        assertEquals("uncompressed point marker", 0x04, point[0] & 0xFF);
        assertArrayEquals("the SPKI's BIT STRING content IS the SEC1 point",
                point, Arrays.copyOfRange(spki, spki.length - 65, spki.length));
    }

    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
