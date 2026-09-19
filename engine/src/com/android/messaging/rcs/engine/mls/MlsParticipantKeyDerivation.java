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
 * The ONE definition of the <b>lab (open5gs) MLS Public Participant Key</b> derivation — the key
 * whose SPKI DER goes to the lab ACS as {@code participant_key} and whose 65-byte SEC1 point goes
 * to the lab KDS as {@code identity_pub}.
 *
 * <h2>Why this is in the shared engine and not in either app</h2>
 *
 * <p>RCC.16 §4.1 has the client hand its Public Participant Key to the ACS, the ACS RETAIN it, and
 * the ACS sign a {@code SignedEncryptionIdentityProof} over {@code (msisdn, participant_key,
 * home_kds, expiry)}; the KDS then enforces {@code ext4.participantKey == SPKI(identity_pub)}
 * before embedding that proof as the {@code .5} extension. THREE artefacts — the ACS's stored key,
 * the proof's signed body, and the certificate the KDS mints — refer to one key.
 *
 * <p>On our fleet those three are reached from TWO processes: {@code the RCS provider app} drives the
 * ACS leg ({@code transport.lab.LabAcsClient}) and {@code messaging2} drives the KDS enrolment
 * ({@code RcsKdsClient}). They must arrive at the same key, and the cheapest way to guarantee that
 * is for both to compute it from the same bytes with <b>the same code</b> — which is only possible
 * in {@code messaging-mls-engine}, the one java_library both APKs static-link.
 *
 * <p>The alternative — each app deriving it from its own copy of the preimage — is the bug class
 * the derivation exists to avoid: two copies of a hash preimage that are required to agree.
 *
 * <h2>Why the SAME bytes are available in both processes</h2>
 *
 * <p>The anchor is {@code Settings.Secure.ANDROID_ID}, which is <b>scoped to the app-signing key</b>
 * — and both APKs are {@code certificate: "platform"}. That was previously asserted from the AOSP
 * rule; it is now READ OFF THE PLATFORM SOURCE THIS TREE BUILDS:
 * {@code frameworks/base/packages/SettingsProvider/.../SettingsProvider.java},
 * {@code generateSsaidLocked} computes
 * {@code HMAC-SHA256(perUserKey, for each sig: len32(sig) || sig)} and truncates to 16 hex chars.
 * <b>The package name is not in that preimage</b> — only the signatures are — so two same-signed
 * packages in one user get byte-identical values. Two conditions bound that:
 * <ul>
 *   <li>{@code isNewSsaidSetting} routes to the per-app table only when the caller's app id is
 *       {@code >= Process.FIRST_APPLICATION_UID}; a package running as a system UID gets the
 *       GLOBAL {@code android_id} instead. Neither APK declares a {@code sharedUserId}, so both
 *       take the per-app path — <b>if one ever gains {@code android.uid.system}, this breaks</b>;
 *       and</li>
 *   <li>the value is per-USER, so the two apps must be the same Android user (they are).</li>
 * </ul>
 *
 * <h2>Rotation: never, except a factory reset — deliberately</h2>
 *
 * <p>The derived key is a pure function of {@code (ANDROID_ID, label)}. It therefore rotates on a
 * FACTORY RESET and on nothing else: not on {@code pm clear}, not on reinstall, not on time.
 * That is the point rather than an omission. RCC.16's rotation mechanism is the key-roll chain
 * ({@code participantKeyRolls [0] IMPLICIT} inside the {@code .4} extension, A.3.8.9) plus the
 * §10.1.1 resync commit ({@link MlsParticipantKeyResync}); we emit no rolls, so a key that changed
 * on its own would orphan every proof the ACS holds and every leaf the KDS minted against it, with
 * an error at both ends naming neither.
 *
 * <p>The one explicit rotation path is operator-driven and lives in the per-app holders: set
 * {@code debug.rcs.lab_participant_key_mode=random} and clear the stored key. A rotation done
 * that way is a NEW IDENTITY, not a roll — the ACS must be re-registered and peers re-Welcomed.
 *
 * <h2>The P-256 arithmetic here duplicates {@code EcP256}, and that is pinned, not hoped</h2>
 *
 * <p>{@code KeyPairGenerator("EC")} draws its scalar from the native RNG and ignores any supplied
 * {@link java.security.SecureRandom}, so there is no JCE path from a derived scalar to a keypair;
 * {@code Q = s·G} has to be computed. The provider already has that code in
 * its own equivalent, but that copy is welded to the provider's exception type and cannot move
 * without breaking the provider's own tests. The duplication is therefore deliberate, and the
 * provider's suite cross-checks the two implementations against the same inputs, asserting
 * byte-identical SPKI/PKCS#8. Drift fails a test rather than a device.
 *
 * <p>Pure JDK by design — no {@code android.*} — so it is host-testable and so the engine's "no
 * Context, no SharedPreferences, no SystemProperties" boundary holds. Reading {@code ANDROID_ID}
 * and persisting the result belong to the per-app holders
 * ({@code transport.lab.LabParticipantKey}, {@code rcs.e2ee.MlsParticipantIdentityKey}).
 */
public final class MlsParticipantKeyDerivation {

    private MlsParticipantKeyDerivation() {}

    /**
     * Domain-separation label for the LAB participant key.
     *
     * <p><b>Changing this string rotates every device's lab participant key</b>, silently
     * invalidating every proof the ACS has issued and every certificate the KDS minted against it.
     * It is a wire-visible constant in everything but name.
     *
     * <p>Deliberately unlike {@code MLS 1.0 ACS_Participant_Key_*}: those name Google's ACS legs.
     * The lab and Tachyon keys come off the same device secret and are kept apart by THIS label
     * alone — never by a second secret. {@code transport.lab.LabAcsRequest.LABEL_PARTICIPANT_KEY}
     * carries the same literal on the provider side (it is in the Android-free half so the request
     * builder's host test can pin it without this library); {@code LabParticipantKeyTest} asserts
     * the two are equal.
     *
     * <p><b>THE VALUE DOES NOT FOLLOW THE NAME, AND MUST NOT.</b> This literal is an HKDF
     * domain separator: it is an input to the derivation, so changing it derives a DIFFERENT
     * key. Every already-enrolled identity would break, and the ACS has RETAINED the old
     * public participant key (RCC.16 §4.1 — retention is the point of that step), so the two
     * would no longer correspond. Moving it is a coordinated flag day across this app, the
     * provider and the CA, not a rename.
     */
    public static final String LABEL_PARTICIPANT_KEY = "OpenRCS Lab 1.0 Public_Participant_Key";

    /**
     * Domain tag mixed in front of {@code ANDROID_ID} so the raw identifier never leaves the
     * derivation and a future secret source can be swapped in without colliding. Byte-identical to
     * {@code MlsKeyStore.stableDeviceSecret}'s tag — that method delegates here, so there is one
     * copy of these bytes.
     */
    public static final String DEVICE_SECRET_TAG = "org.lineageos.rcs.mls.participant.stable-secret.v1\0";

    /**
     * The stable per-device secret: {@code SHA-256( DEVICE_SECRET_TAG_utf8 || androidId_utf8 )}.
     *
     * @param androidId {@code Settings.Secure.ANDROID_ID} as read by the caller's process
     * @return 32 bytes, or {@code null} if {@code androidId} is null/empty (callers fall back to a
     *     random mint and must say so in the log — a random mint does NOT survive a pm clear and an
     *     ACS proof bound to it dies with it)
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
     * Derive a DETERMINISTIC P-256 keypair from {@code secret} and {@code label}:
     * {@code material = SHA-256( lenpref(label_utf8) || lenpref(secret) )}, scalar
     * {@code s = (OS2IP(material) mod (n-1)) + 1} (never 0, always in {@code [1, n-1]}),
     * {@code Q = s·G}. The same {@code (secret, label)} ALWAYS yields the same key.
     *
     * <p>Keys are rebuilt through the JCE {@link KeyFactory} against the {@code secp256r1} NAMED
     * curve spec, so {@code getEncoded()} is byte-identical to a natively-minted key for the same
     * scalar — which is what lets the two apps compare SPKIs at all.
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
     * {@code SHA-256( lenpref(label_utf8) || lenpref(secret) )}, {@code lenpref} =
     * varint-length-prefixed. Mirrors {@code MlsIdentityKey.crikHash} exactly (the cross-check test
     * is what keeps that true).
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

    /** Map derived bytes into {@code [1, n-1]} as {@code (OS2IP(bytes) mod (n-1)) + 1}. */
    static BigInteger scalarInRange(final byte[] beBytes) {
        return new BigInteger(1, beBytes).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
    }

    /** Build a P-256 {@link KeyPair} whose private scalar is exactly {@code s} (in {@code [1, n-1]}). */
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

    /** {@code s·G} as affine {@code {x, y}} — exposed for the known-answer tests only. */
    static BigInteger[] scalarMulG(final BigInteger s) {
        return scalarMul(s, new BigInteger[] {GX, GY});
    }

    /** 65-byte uncompressed SEC1 point {@code 0x04 || X(32) || Y(32)} — RFC 9420 SignaturePublicKey. */
    public static byte[] sec1Point65(final java.security.PublicKey pub) {
        final ECPoint w = ((java.security.interfaces.ECPublicKey) pub).getW();
        final byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(i2osp(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(i2osp(w.getAffineY()), 0, out, 33, 32);
        return out;
    }

    /**
     * A short, non-secret label for a participant public key: the first 8 bytes of
     * {@code SHA-256(SPKI DER)}, lower-case hex.
     *
     * <p>Exists so BOTH apps can print the SAME string for the SAME key. That is the only way the
     * cross-process agreement this class is built for can be OBSERVED on a device: the provider logs
     * the key it hands the ACS, messaging2 logs the key it enrols with, and an operator compares two
     * 16-character strings instead of trusting the SSAID rule. A mismatch is otherwise invisible —
     * the ACS answers with a proofless 200 and the KDS with an opaque refusal.
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
