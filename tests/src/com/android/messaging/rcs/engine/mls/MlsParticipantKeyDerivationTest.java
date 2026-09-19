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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * Known-answer coverage for the shared lab participant-key derivation.
 *
 * <p>The curve arithmetic in {@link MlsParticipantKeyDerivation} is a deliberate second copy of the
 * equivalent in the out-of-tree RCS provider, which cannot move — it is a listed source of the
 * provider's signer library, pinned there against captured KDS and ACS requests. That the two
 * copies agree is asserted in the provider's own suite, which is the one classpath carrying both;
 * it is not asserted here and cannot be, since only one of the two is on this classpath. That is
 * an agreement test in any case — it would pass if BOTH were wrong in the same way. These are the
 * absolute checks, and they need no second copy: NIST's published {@code G} and {@code 2G},
 * on-curve for every product, and the scalar map's range at the exact boundaries a modulo bug
 * lands on.
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

    /** 2·G for NIST P-256 — a published value, so the doubling path is checked against the spec. */
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
            assertTrue(k + ".G must be on the curve", MlsParticipantKeyDerivation.isOnCurve(q[0], q[1]));
        }
    }

    /**
     * The scalar map never yields 0 and always lands in {@code [1, n-1]}, INCLUDING at {@code n-1}
     * and {@code n} — the two inputs a wrong modulus lands on, and the ones that would produce an
     * invalid private key the JCE then refuses with an error about the key rather than the derivation.
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
     * The KDF frames BOTH inputs with a length prefix, so no two (label, secret) pairs can share a
     * preimage. Without the framing, label {@code "AB"} + secret {@code "C"} and label {@code "A"} +
     * secret {@code "BC"} would derive the SAME key — and the label is the only thing separating the
     * lab trust domain from the Tachyon one.
     */
    @Test
    public void theKdfIsUnambiguousAcrossTheLabelBoundary() throws Exception {
        final byte[] a = MlsParticipantKeyDerivation.kdfHash("AB", "C".getBytes(StandardCharsets.UTF_8));
        final byte[] b = MlsParticipantKeyDerivation.kdfHash("A", "BC".getBytes(StandardCharsets.UTF_8));
        assertFalse("length-prefixing must keep these preimages apart", Arrays.equals(a, b));
    }

    /** A secret longer than 127 bytes needs a two-byte varint; the framing must still round-trip. */
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
     * The SPKI's BIT STRING content IS the 65-byte SEC1 point. The lab KDS enforces
     * {@code ext4.participantKey == SPKI(identity_pub)} and the ACS signs over that same SPKI, so
     * {@code participant_key} and {@code identity_pub} are one key in two encodings.
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
