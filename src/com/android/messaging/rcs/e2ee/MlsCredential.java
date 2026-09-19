/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.Rcc16Der;
import com.android.messaging.rcs.log.LogMask;

import android.util.Log;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;


/**
 * RCC.16 credential construction, shared with the self-test APK as source. Two roles:
 * <ul>
 *   <li>Client: {@link #buildCsr(String, KeyPair)} over the durable participant key produces the
 *       fields posted to {@code POST /mls/v1/certificate}; the returned leaf and chain feed
 *       {@link MlsIdentity}.</li>
 *   <li>Local PKI: {@link #mintLocalPki()} and {@link #issueIdentity} mint a P-256 root,
 *       intermediate and leaf on the device, so the engine round trip runs with no network.</li>
 * </ul>
 * The leaf profile, and the {@code .4} proof-of-possession TBS that the Rust validator
 * reconstructs, are described in docs/mls/credentials.md. DER is built by the same Rust code that
 * validates it ({@link Rcc16Der}); every private-key operation stays in JCE on this side.
 */
public final class MlsCredential {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsCredential: ";
    private MlsCredential() {}

    // Everything this class mints is P-256 with SHA-256.
    private static final String SIG_ALG = "SHA256withECDSA";
    /**
     * Organisation and common-name prefixes on every certificate minted here. Keep them in step
     * with {@code testdata/regen-chain.py}, which mints the Rust suite's fixtures; the short hex id
     * appended to each CN tells two test chains apart.
     */
    private static final String LOCAL_PKI_ORG = "LineageOS";
    private static final String LOCAL_PKI_ROOT_CN_PREFIX = "MLS Test Root ";
    private static final String LOCAL_PKI_ICA_CN_PREFIX = "MLS Test ICA ";
    private static final long LEAF_LIFETIME_S = 60L * 24 * 3600;   // 60 days: within (30 d, 76 d]
    /** Root lifetime; RCC.16 A.1.5 caps a root at 3652 days. */
    private static final long ROOT_LIFETIME_S = 3650L * 24 * 3600;
    /**
     * Intermediate lifetime; RCC.16 A.2.5 caps an intermediate at 1827 days. 1825 leaves room for
     * the one-hour backdate applied at issue, which would otherwise push 1827 over the cap.
     */
    private static final long ICA_LIFETIME_S = 1825L * 24 * 3600;
    private static final SecureRandom RNG = new SecureRandom();

    private static final int GOOGLE_VENDOR_ID = 2;

    /**
     * A fresh P-256 identity keypair (suite 0x0002). Every call mints a different key, so it suits
     * only a key kept for one in-process object (the local PKI, {@link #buildCsr(String)}); an
     * enrolment uses the durable {@code MlsParticipantIdentityKey}.
     */
    public static KeyPair generateIdentityKey() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /** 65-byte uncompressed SEC1 point (0x04 || X || Y): the RFC 9420 SignaturePublicKey. */
    public static byte[] sec1Point(final PublicKey pub) {
        final ECPoint w = ((ECPublicKey) pub).getW();
        final byte[] x = i2osp(w.getAffineX(), 32);
        final byte[] y = i2osp(w.getAffineY(), 32);
        final byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    /** 32-byte big-endian P-256 private scalar. */
    public static byte[] scalar32(final PrivateKey priv) {
        return i2osp(((ECPrivateKey) priv).getS(), 32);
    }

    private static byte[] i2osp(final BigInteger v, final int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) return b;
        final byte[] out = new byte[len];
        if (b.length > len) {                    // strip leading sign byte(s)
            System.arraycopy(b, b.length - len, out, 0, len);
        } else {                                 // left-pad
            System.arraycopy(b, 0, out, len - b.length, b.length);
        }
        return out;
    }

    /** A minted CA: keypair, certificate DER, and SKI for its children's AKI. */
    public static final class Ca {
        public final KeyPair keyPair;
        public final byte[] der;
        /** The CA's subject Name DER, copied verbatim into a child's issuer field. */
        final byte[] subjectDer;
        final byte[] ski;
        Ca(final KeyPair kp, final byte[] der, final byte[] subjectDer, final byte[] ski) {
            this.keyPair = kp; this.der = der; this.subjectDer = subjectDer; this.ski = ski;
        }
    }

    /** A local root and intermediate. Both sides of a loopback test share one instance. */
    public static final class LocalPki {
        public final Ca root;
        public final Ca ica;
        LocalPki(final Ca root, final Ca ica) { this.root = root; this.ica = ica; }
    }

    private static KeyPair generateP256() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /**
     * A positive serial number as DER integer content. 159 bits keeps the encoding within the 20
     * octets RFC 5280 §4.1.2.2 allows.
     */
    private static byte[] serial() {
        final BigInteger s = new BigInteger(159, RNG);
        return (s.signum() == 0 ? BigInteger.ONE : s).toByteArray();
    }

    /** SHA-1 over the 65-byte SEC1 point: the SKI and AKI key identifier. */
    private static byte[] keyId(final PublicKey pub) throws Exception {
        return sha1(sec1Point(pub));
    }

    /** Rebuild a P-256 public key from its SEC1 point to get its SPKI DER from JCE. */
    private static byte[] spkiDerFromSec1P256(final byte[] point65) throws Exception {
        final AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        final ECParameterSpec ecSpec = ap.getParameterSpec(ECParameterSpec.class);
        final BigInteger x = new BigInteger(1, Arrays.copyOfRange(point65, 1, 33));
        final BigInteger y = new BigInteger(1, Arrays.copyOfRange(point65, 33, 65));
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), ecSpec)).getEncoded();
    }

    /**
     * Mint a CA: self-signed root when {@code parent == null} (issuer == subject, AKI == SKI),
     * otherwise an intermediate signed by {@code parent}.
     */
    private static Ca mintCa(final Ca parent, final String cn, final long lifetimeS)
            throws Exception {
        final KeyPair kp = generateP256();
        final byte[] subject = Rcc16Der.caName(LOCAL_PKI_ORG, cn);
        final byte[] ski = keyId(kp.getPublic());
        final boolean selfSigned = parent == null;
        final byte[] issuer = selfSigned ? subject : parent.subjectDer;
        final byte[] aki = selfSigned ? ski : parent.ski;
        final PrivateKey signingKey = selfSigned ? kp.getPrivate() : parent.keyPair.getPrivate();
        final long now = System.currentTimeMillis() / 1000L;
        final byte[] tbs = Rcc16Der.caTbs(issuer, subject, kp.getPublic().getEncoded(), serial(),
                now - 3600L, now + lifetimeS, ski, aki, GOOGLE_VENDOR_ID);
        return new Ca(kp, Rcc16Der.certificate(tbs, ecdsaSha256(signingKey, tbs)), subject, ski);
    }

    /**
     * The CA half of {@code POST /mls/v1/certificate}: issue the leaf, embedding the client's
     * {@code subjectDer}, {@code sanDer} and {@code ext4Value} verbatim and adding the
     * CA-controlled profile. BasicConstraints is omitted on a leaf (RCC.16 A.3.8). A real issuer
     * also verifies {@code csr_pop} and that the SAN matches the authenticated number; this one
     * does not.
     */
    public static byte[] issueLeafFromCsr(final Ca issuer, final Csr csr) throws Exception {
        final long now = System.currentTimeMillis() / 1000L;
        final byte[] tbs = Rcc16Der.leafTbs(issuer.subjectDer, csr.subjectDer,
                spkiDerFromSec1P256(csr.identityPub65), serial(),
                now - 3600L, now + LEAF_LIFETIME_S,
                sha1(csr.identityPub65), issuer.ski, csr.sanDer, csr.ext4Value,
                GOOGLE_VENDOR_ID);
        return Rcc16Der.certificate(tbs, ecdsaSha256(issuer.keyPair.getPrivate(), tbs));
    }

    /** Mint a local PKI: a self-signed P-256 root and a P-256 intermediate. */
    public static LocalPki mintLocalPki() throws Exception {
        final Ca root = mintCa(null, LOCAL_PKI_ROOT_CN_PREFIX + shortId(), ROOT_LIFETIME_S);
        final Ca ica = mintCa(root, LOCAL_PKI_ICA_CN_PREFIX + shortId(), ICA_LIFETIME_S);
        return new LocalPki(root, ica);
    }

    /**
     * The client half of {@code POST /mls/v1/certificate}. The {@code .4} proof's subject and SAN
     * are read from the issued leaf at verification, so the CA must embed {@link #subjectDer} and
     * {@link #sanDer} verbatim. {@link #identity} stays on the device.
     */
    public static final class Csr {
        public final String e164;
        public final KeyPair identity;      // not transmitted
        public final byte[] identityPub65;  // SEC1 P-256 point
        public final byte[] subjectDer;     // leaf subject Name DER (CN=<uuid>)
        public final byte[] sanDer;         // SAN GeneralNames DER: SEQ{ [6] tel:+E164 }
        public final byte[] ext4Value;      // .4 ParticipantInformation value, DER
        public final byte[] csrPop;         // SHA256withECDSA(msisdn || pub65), DER
        Csr(String e164, KeyPair id, byte[] pub, byte[] subj, byte[] san, byte[] e4, byte[] pop) {
            this.e164 = e164; this.identity = id; this.identityPub65 = pub; this.subjectDer = subj;
            this.sanDer = san; this.ext4Value = e4; this.csrPop = pop;
        }
    }

    /**
     * Build a CSR with a throwaway identity key. Only for the local PKI, where nothing outside the
     * process refers to the key again; an enrolment uses {@link #buildCsr(String, KeyPair)} with
     * the durable participant key, since the ACS proof is bound to that key.
     */
    public static Csr buildCsr(final String e164) throws Exception {
        return buildCsr(e164, generateIdentityKey());
    }

    /**
     * Build a CSR over a supplied identity key: subject CN, {@code tel:} SAN, the {@code .4} proof
     * and {@code csr_pop}.
     *
     * @param identity the key the leaf will certify; for an enrolment, the durable participant key
     *     whose SPKI the ACS holds, because the KDS checks the two against each other
     */
    public static Csr buildCsr(final String e164, final KeyPair identity) throws Exception {
        final KeyPair id = identity;
        final PublicKey idPub = id.getPublic();
        final byte[] pub65 = sec1Point(idPub);

        final byte[] subjectDer = Rcc16Der.subject(UUID.randomUUID().toString());
        final byte[] sanDer = Rcc16Der.san("tel:" + e164);

        // getEncoded() is already the SubjectPublicKeyInfo DER.
        final byte[] spkiDer = idPub.getEncoded();

        final long nowSec = System.currentTimeMillis() / 1000L;
        final byte[] validityDer = Rcc16Der.validity(nowSec - 3600L, nowSec + LEAF_LIFETIME_S);

        final byte[] tbs = Rcc16Der.tbsParticipantInfo(
                subjectDer, GOOGLE_VENDOR_ID, validityDer, spkiDer, sanDer);
        final byte[] popSig = ecdsaSha256(id.getPrivate(), tbs);
        final byte[] ext4 = Rcc16Der.participantInfoExtension(
                GOOGLE_VENDOR_ID, validityDer, popSig, spkiDer);

        // csr_pop: SHA256withECDSA over (msisdn_utf8 || identity_pub_65B), DER-encoded.
        final byte[] popPreimage = concat(("tel:" + e164).getBytes("UTF-8"), pub65);
        final byte[] csrPop = ecdsaSha256(id.getPrivate(), popPreimage);
        return new Csr(e164, id, pub65, subjectDer, sanDer, ext4, csrPop);
    }

    private static byte[] sha1(final byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(b);
    }
    private static byte[] ecdsaSha256(final PrivateKey key, final byte[] tbs) throws Exception {
        final Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(tbs);
        return s.sign();
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    /**
     * A complete {@link MlsIdentity} for {@code e164} under {@code pki}, produced through both
     * halves of the CSR contract. chain = [ICA], roots = [root].
     */
    public static MlsIdentity issueIdentity(final LocalPki pki,
            final String e164) throws Exception {
        final Csr csr = buildCsr(e164);
        final byte[] leaf = issueLeafFromCsr(pki.ica, csr);
        final List<byte[]> chain = new ArrayList<>();
        chain.add(pki.ica.der);
        final List<byte[]> roots = Collections.singletonList(pki.root.der);
        Log.i(TAG, P + "issueIdentity " + LogMask.number(e164) + ": leaf=" + leaf.length
                + "B chain=[ica] roots=[root]");
        return new MlsIdentity(e164, leaf, chain,
                scalar32(csr.identity.getPrivate()), csr.identityPub65, roots);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
