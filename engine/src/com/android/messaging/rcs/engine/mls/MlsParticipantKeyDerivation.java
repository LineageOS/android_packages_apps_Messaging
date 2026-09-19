/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;

/**
 * The single derivation of the MLS public participant key (RCC.16 §4.1), shared by the app and
 * the provider because both must reach the same key from {@code ANDROID_ID}. Deterministic, so the
 * key rotates only on a factory reset. Pure JDK; reading {@code ANDROID_ID} and persisting the key
 * belong to the per-app holders. See docs/mls/credentials.md.
 */
public final class MlsParticipantKeyDerivation {

    private MlsParticipantKeyDerivation() {}

    /**
     * Domain-separation label for the participant key. It is a derivation input: changing it
     * derives a different key on every device and invalidates every issued proof and certificate.
     * So the value is frozen exactly as it is, every word of it: here it is bytes, not a name, and
     * {@code TestNetworkWordGuardTest} allowlists exactly this value. The provider's ACS request
     * class declares the same label, and a provider test pins the two equal.
     */
    public static final String LABEL_PARTICIPANT_KEY = "OpenRCS Lab 1.0 Public_Participant_Key";

    /**
     * Domain tag prefixed to {@code ANDROID_ID} so the raw identifier never leaves the derivation;
     * {@code MlsKeyStore.stableDeviceSecret} delegates here.
     */
    public static final String DEVICE_SECRET_TAG =
            "org.lineageos.rcs.mls.participant.stable-secret.v1\0";

    /**
     * The stable per-device secret: {@code SHA-256( DEVICE_SECRET_TAG_utf8 || androidId_utf8 )}.
     *
     * @param androidId {@code Settings.Secure.ANDROID_ID} as read by the caller's process
     * @return 32 bytes, or {@code null} if {@code androidId} is null or empty (callers then mint a
     *     random key and must log it, since that key does not survive clearing app data)
     */
    public static byte[] stableSecret(final String androidId) throws GeneralSecurityException {
        if (androidId == null || androidId.isEmpty()) {
            return null;
        }
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(DEVICE_SECRET_TAG.getBytes(StandardCharsets.UTF_8));
        md.update(androidId.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    /**
     * Derive a deterministic P-256 keypair:
     * {@code material = SHA-256( lenpref(label_utf8) || lenpref(secret) )},
     * {@code s = (material mod (n-1)) + 1}, {@code Q = s·G}. Built through {@link KeyFactory} on
     * the named curve, so the encodings match a natively minted key for the same scalar.
     */
    public static KeyPair deriveP256(final byte[] secret, final String label)
            throws GeneralSecurityException {
        if (secret == null || secret.length == 0) {
            throw new GeneralSecurityException("deriveP256: empty stable secret");
        }
        if (label == null || label.isEmpty()) {
            throw new GeneralSecurityException("deriveP256: empty label");
        }
        return keyPairFromScalar(scalarInRange(kdfHash(label, secret)));
    }

    /**
     * {@code SHA-256( lenpref(label_utf8) || lenpref(secret) )} with a varint length prefix; must
     * match {@code MlsIdentityKey.crikHash} (cross-checked by a test).
     */
    static byte[] kdfHash(final String label, final byte[] secret) throws GeneralSecurityException {
        final ByteArrayOutputStream pre = new ByteArrayOutputStream();
        lenPrefix(pre, label.getBytes(StandardCharsets.UTF_8));
        lenPrefix(pre, secret);
        return MessageDigest.getInstance("SHA-256").digest(pre.toByteArray());
    }

    /** Write {@code varint(len(bytes)) || bytes}. */
    private static void lenPrefix(final ByteArrayOutputStream out, final byte[] bytes) {
        int v = bytes.length;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
        out.write(bytes, 0, bytes.length);
    }

    /** Map big-endian bytes into {@code [1, n-1]}. */
    static BigInteger scalarInRange(final byte[] beBytes) {
        return new BigInteger(1, beBytes).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
    }

    /**
     * Build a P-256 {@link KeyPair} whose private scalar is exactly {@code s} (in {@code [1,
     * n-1]}).
     */
    static KeyPair keyPairFromScalar(final BigInteger s) throws GeneralSecurityException {
        if (s == null || s.signum() <= 0 || s.compareTo(N) >= 0) {
            throw new GeneralSecurityException("P-256 scalar out of range [1, n-1]");
        }
        final AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        final ECParameterSpec params = ap.getParameterSpec(ECParameterSpec.class);
        final BigInteger[] q = scalarMul(s, new BigInteger[] {GX, GY});
        final KeyFactory kf = KeyFactory.getInstance("EC");
        return new KeyPair(
                kf.generatePublic(new ECPublicKeySpec(new ECPoint(q[0], q[1]), params)),
                kf.generatePrivate(new ECPrivateKeySpec(s, params)));
    }

    // --- secp256r1 / NIST P-256 domain parameters (FIPS 186-4 D.1.2.3) ---

    private static final BigInteger P = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger A = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16);
    private static final BigInteger B = new BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16);
    private static final BigInteger GX = new BigInteger(
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16);
    private static final BigInteger GY = new BigInteger(
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16);
    /** Group order n. */
    static final BigInteger N = new BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

    /** {@code R = k·P} via left-to-right double-and-add; affine, identity is {@code null}. */
    private static BigInteger[] scalarMul(final BigInteger k, final BigInteger[] point) {
        BigInteger[] r = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = pointDouble(r);
            if (k.testBit(i)) {
                r = pointAdd(r, point);
            }
        }
        return r;
    }

    private static BigInteger[] pointAdd(final BigInteger[] p1, final BigInteger[] p2) {
        if (p1 == null) {
            return p2;
        }
        if (p2 == null) {
            return p1;
        }
        final BigInteger x1 = p1[0];
        final BigInteger y1 = p1[1];
        final BigInteger x2 = p2[0];
        final BigInteger y2 = p2[1];
        if (x1.equals(x2)) {
            return y1.equals(y2) ? pointDouble(p1) : null;   // P + (-P) = O
        }
        final BigInteger lambda = y2.subtract(y1)
                .multiply(x2.subtract(x1).modInverse(P)).mod(P);
        final BigInteger x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P);
        return new BigInteger[] {x3, lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)};
    }

    private static BigInteger[] pointDouble(final BigInteger[] p) {
        if (p == null) {
            return null;
        }
        final BigInteger x1 = p[0];
        final BigInteger y1 = p[1];
        if (y1.signum() == 0) {
            return null;                                     // vertical tangent -> O
        }
        final BigInteger num = BigInteger.valueOf(3).multiply(x1).multiply(x1).add(A).mod(P);
        final BigInteger lambda = num.multiply(y1.shiftLeft(1).modInverse(P)).mod(P);
        final BigInteger x3 = lambda.multiply(lambda).subtract(x1.shiftLeft(1)).mod(P);
        return new BigInteger[] {x3, lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)};
    }

    /** True iff {@code (x, y)} satisfies {@code y^2 = x^3 + ax + b (mod p)}. */
    static boolean isOnCurve(final BigInteger x, final BigInteger y) {
        return y.multiply(y).mod(P)
                .equals(x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P));
    }

    /** {@code s·G} as affine {@code {x, y}}, for known-answer tests. */
    static BigInteger[] scalarMulG(final BigInteger s) {
        return scalarMul(s, new BigInteger[] {GX, GY});
    }

    /**
     * 65-byte uncompressed SEC1 point {@code 0x04 || X(32) || Y(32)} — RFC 9420 SignaturePublicKey.
     */
    public static byte[] sec1Point65(final java.security.PublicKey pub) {
        final ECPoint w = ((java.security.interfaces.ECPublicKey) pub).getW();
        final byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(i2osp(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(i2osp(w.getAffineY()), 0, out, 33, 32);
        return out;
    }

    /**
     * A short, non-secret fingerprint of a participant public key: the first 8 bytes of
     * {@code SHA-256(SPKI DER)} in lower-case hex, so both apps can log a comparable string.
     */
    public static String spkiFingerprint(final java.security.PublicKey pub) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(pub.getEncoded());
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (final GeneralSecurityException e) {
            return "?";
        }
    }

    private static byte[] i2osp(final BigInteger v) {
        final byte[] b = v.toByteArray();
        if (b.length == 32) {
            return b;
        }
        final byte[] out = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }
}
