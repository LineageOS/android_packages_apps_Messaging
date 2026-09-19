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
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsIdentity;

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

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * RCC.16-conformant credential minting for the OpenMLS ("openmls") engine, shared by the RCS provider app
 * and messaging2. Two roles:
 *
 * <ol>
 *   <li><b>Client (production/lab):</b> {@link #buildCsr(String, KeyPair)} over the DURABLE lab
 *       participant key ({@code MlsParticipantIdentityKey}) produces the fields POSTed to the lab CA service
 *       ({@code POST /mls/v1/certificate}); the returned
 *       leaf+chain feed {@link MlsIdentity}. The key is supplied rather than minted here because the
 *       ACS signs its proof over that one key and the KDS checks the leaf against it — one key,
 *       not one per call.</li>
 *   <li><b>Local PKI (self-test / no-service fallback):</b> {@link #mintLocalPki()} +
 *       {@link #issueIdentity} mint a full P-256 root&rarr;ICA&rarr;leaf chain on-device so the engine
 *       round-trip (create/join/encrypt/decrypt) can be proven with no network and no second device.</li>
 * </ol>
 *
 * <p><b>Profile</b> (mirrors the passing {@code rcc16_validate.rs} testdata chain — all P-256 /
 * ecdsa-with-SHA256, NOT {@code MlsSyntheticCa}'s the Google MLS engine-tuned P-384): leaf carries EKU
 * {@code id-kp-rcsMlsClient} (2.23.146.2.1.3), {@code KeyUsage=digitalSignature}, {@code SAN=tel:+E164},
 * a {@code subjectKeyIdentifier}, exactly one non-critical {@code certificatePolicies} naming the
 * E2EE policy 2.23.146.2.1.2, a CRITICAL {@code .4} ParticipantInformation PoP, {@code .5} key-roll,
 * {@code .6} vendor-id, and a &le;76-day / &ge;30-day-remaining lifetime. These are exactly the checks {@link Rcc16Validator}
 * enforces on a peer cert at add/join. The {@code .4} PoP TBS is
 * {@code SEQ{ leafSubject, INT 2, validity, participantSPKI, leafSAN }} signed ECDSA-SHA256 by the
 * identity key — byte-for-byte what {@code verify_participant_pop} reconstructs.
 */
public final class MlsCredential {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    /** Origin prefix: this class used to BE the tag (rework 14.3 collapsed the tag, not the origin). */
    private static final String P = "MlsCredential: ";
    private MlsCredential() {}

    // All-P-256 / SHA-256 (matches the validator's accepted testdata chain).
    private static final String SIG_ALG = "SHA256withECDSA";
    /**
     * Organisation on every certificate this class mints, and the common-name prefixes that go
     * with it.
     *
     * <p><b>These three must agree with {@code testdata/regen-chain.py}</b>, which mints the
     * checked-in fixtures the Rust suite validates against. They are not cosmetic: a chain minted
     * at runtime and a chain minted by the script are compared by readers (and by
     * {@code openssl x509 -subject}) to tell whether a leaf came from the lab PKI or from
     * somewhere else, and the short hex id appended below is how two lab chains are told apart.
     */
    private static final String LAB_ORG = "LineageOS";
    private static final String LAB_ROOT_CN_PREFIX = "MLS Test Root ";
    private static final String LAB_ICA_CN_PREFIX = "MLS Test ICA ";
    private static final long LEAF_LIFETIME_S = 60L * 24 * 3600;   // 60d: within (30d, 76d]
    /**
     * ROOT CA lifetime — RCC.16 <b>A.1.5</b> caps a root at 3652 days.
     *
     * <p>The live Google and Apple roots we fetch all sit at ~3650 days, which is what
     * made the limit worth getting right rather than guessing.
     */
    private static final long ROOT_LIFETIME_S = 3650L * 24 * 3600;
    /**
     * INTERMEDIATE CA lifetime — RCC.16 <b>A.2.5</b> caps an ICA at 1827 days, which is a different
     * and much shorter limit than the root's.
     *
     * <p>This used to be one CA_LIFETIME_S of 3650 days for both, with a comment claiming CA
     * lifetime is "unbounded by A.4.1" — true, because A.4.1 is the LEAF profile and says nothing
     * about CAs. A.1.5 and A.2.5 do. Our own lab ICA was therefore twice the permitted length, and
     * once the A.2 chain validation landed the self-test could no longer form a group at all:
     * "chain[1]: A.2.5: intermediate CA lifetime 3650 d > 1827 d". Device-found 2026-07-30 — the
     * validator was right and the mint was wrong.
     *
     * <p>1825 rather than the permitted 1827, matching what Google's real ICA actually ships. Two
     * days of margin also keeps the 1-hour backdate below from spending the whole allowance: a
     * first fix used 1827 exactly and the certificate came out at 1827 d + 1 h, which the validator
     * correctly refused — and reported as "1827 d > 1827 d", because the excess was inside the day
     * it was truncating away.
     */
    private static final long ICA_LIFETIME_S = 1825L * 24 * 3600;
    private static final SecureRandom RNG = new SecureRandom();

    // GSMA arc 2.23.146.2.1.x
    private static final ASN1ObjectIdentifier OID_EKU_RCSMLS = new ASN1ObjectIdentifier("2.23.146.2.1.3");
    private static final ASN1ObjectIdentifier OID_PARTICIPANT_INFO = new ASN1ObjectIdentifier("2.23.146.2.1.4");
    /**
     * {@code 2.23.146.2.1.5} = <b>id-acsParticipantInformation</b>, NOT keyroll.
     *
     * <p>RCC.16 A.3.8.10: "This extension shall be present. The value is an OCTET STRING of the
     * encoded SignedEncryptionIdentityProof (section 7.12)." The key-roll chain is a FIELD INSIDE
     * .4 ParticipantInformation ({@code participantKeyRolls [0] IMPLICIT}), per A.3.8.9.
     *
     * <p>We had this backwards: this constant was named {@code OID_KEYROLL} and we wrote a key-roll
     * stub here, so our lab leaves carried a key-roll blob in the ACS-proof slot AND omitted the
     * mandatory proof, and that was verified against the spec.
     * Our own Rust validator already named the OID correctly, which is what made it certain rather
     * than arguable — the minter and the validator disagreed inside one tree.
     *
     * <p><b>We no longer emit this extension at all.</b> The KDS fills it with the ACS-signed proof
     * the device passes through at enrolment; a client that minted its own would be asserting a
     * proof it cannot sign.
     */
    private static final ASN1ObjectIdentifier OID_ACS_PARTICIPANT_INFO =
            new ASN1ObjectIdentifier("2.23.146.2.1.5");
    private static final ASN1ObjectIdentifier OID_VENDOR = new ASN1ObjectIdentifier("2.23.146.2.1.6");
    private static final ASN1ObjectIdentifier OID_ECDSA_SHA256 = new ASN1ObjectIdentifier("1.2.840.10045.4.3.2");
    /**
     * The E2EE {@code certificatePolicies} policy — <b>2.23.146.2.1.2</b>, read off a KDS-minted
     * leaf's own extension. §14.2.3 requires <b>exactly
     * one</b> of these on a leaf, non-critical and carrying no policy qualifiers.
     *
     * <p>We did not emit it until 2026-08-08, which is why {@code Rcc16Validator} carried a
     * conditional relaxation skipping the rule on the lab profile — our own fixture could not
     * satisfy a rule we hold every real peer to. Emitting it here is what let that relaxation be
     * deleted.
     */
    private static final ASN1ObjectIdentifier OID_E2EE_POLICY = new ASN1ObjectIdentifier("2.23.146.2.1.2");
    private static final int GOOGLE_VENDOR_ID = 2;

    // ---- key helpers (engine-independent; no dependency on the provider's MlsIdentityKey) ----

    /**
     * A fresh P-256 identity keypair (the MLS signing / certified subject key, suite 0x0002).
     *
     * <p><b>Throwaway.</b> Every call mints a different key, so the only correct callers are the ones
     * that keep it for the life of one in-process object: the local-PKI CAs and
     * {@link #buildCsr(String)}. The enrolment key is DURABLE and lives in {@code MlsParticipantIdentityKey}
     * — "a key per call" is not merely wasteful there, it makes the
     * ACS proof structurally unsatisfiable.
     */
    public static KeyPair generateIdentityKey() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    /** 65-byte uncompressed SEC1 point (0x04 || X32 || Y32) — RFC 9420 SignaturePublicKey. */
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

    // ---- CA / issuance ----

    /** A minted CA (its keypair + self/parent-signed cert DER + SKI for child AKIs). */
    public static final class Ca {
        public final KeyPair keyPair;
        public final byte[] der;
        final X500Name subject;
        final byte[] ski;
        Ca(final KeyPair kp, final byte[] der, final X500Name subject, final byte[] ski) {
            this.keyPair = kp; this.der = der; this.subject = subject; this.ski = ski;
        }
    }

    /** A local (self-hosted) PKI: root + intermediate. Both devices in a loopback share ONE instance. */
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

    private static X500Name dn(final String org, final String cn) {
        final X500NameBuilder b = new X500NameBuilder(BCStyle.INSTANCE);
        b.addRDN(BCStyle.O, new DERPrintableString(org));
        b.addRDN(BCStyle.CN, new DERPrintableString(cn));
        return b.build();
    }

    private static X500Name cnDn(final String cn) {
        final X500NameBuilder b = new X500NameBuilder(BCStyle.INSTANCE);
        // UTF8String CN — openrcs-kds's KeyPackage-credential x509 parser rejects a PrintableString
        // CN ("unexpected ASN.1 DER tag: got PrintableString"). Our Rcc16Validator is encoding-
        // agnostic (uses tbs.subject.to_der()), and subject_der is sent to the CA verbatim, so the
        // .4 PoP stays byte-consistent.
        b.addRDN(BCStyle.CN, new DERUTF8String(cn));
        return b.build();
    }

    private static BigInteger serial() {
        final BigInteger s = new BigInteger(159, RNG);
        return s.signum() == 0 ? BigInteger.ONE : s;
    }

    private static byte[] keyId(final PublicKey pub) throws Exception {
        final SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(pub.getEncoded());
        return MessageDigest.getInstance("SHA-1").digest(spki.getPublicKeyData().getBytes());
    }

    /** Mint a fresh local PKI: self-signed P-256 root + a P-256 intermediate (chain length 3). */
    public static LocalPki mintLocalPki() throws Exception {
        final Ca root = generateCa(LAB_ROOT_CN_PREFIX + shortId());
        final Ca ica = issueIntermediate(root, LAB_ICA_CN_PREFIX + shortId());
        return new LocalPki(root, ica);
    }

    private static Ca generateCa(final String cn) throws Exception {
        final KeyPair kp = generateP256();
        final X500Name subject = dn(LAB_ORG, cn);
        final long now = System.currentTimeMillis();
        final byte[] ski = keyId(kp.getPublic());
        final X509v3CertificateBuilder b = new X509v3CertificateBuilder(subject, serial(),
                new Date(now - 3600_000L), new Date(now + ROOT_LIFETIME_S * 1000L), subject,
                SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        b.addExtension(Extension.subjectKeyIdentifier, false, new SubjectKeyIdentifier(ski));
        b.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(ski));
        b.addExtension(OID_VENDOR, false, new ASN1Integer(GOOGLE_VENDOR_ID));
        final ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        return new Ca(kp, b.build(signer).getEncoded(), subject, ski);
    }

    private static Ca issueIntermediate(final Ca root, final String cn) throws Exception {
        final KeyPair kp = generateP256();
        final X500Name subject = dn(LAB_ORG, cn);
        final long now = System.currentTimeMillis();
        final byte[] ski = keyId(kp.getPublic());
        final X509v3CertificateBuilder b = new X509v3CertificateBuilder(root.subject, serial(),
                new Date(now - 3600_000L), new Date(now + ICA_LIFETIME_S * 1000L), subject,
                SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        b.addExtension(Extension.subjectKeyIdentifier, false, new SubjectKeyIdentifier(ski));
        b.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(root.ski));
        b.addExtension(OID_VENDOR, false, new ASN1Integer(GOOGLE_VENDOR_ID));
        final ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(root.keyPair.getPrivate());
        return new Ca(kp, b.build(signer).getEncoded(), subject, ski);
    }

    /**
     * The client half of {@code POST /mls/v1/certificate}: everything the device signs/chooses before
     * the CA issues the leaf. Elements 1 (subject) + 5 (SAN) of the {@code .4} PoP TBS are pulled from
     * the ISSUED leaf at verify time, so the CA MUST embed {@link #subjectDer}/{@link #sanDer} verbatim
     * — otherwise the PoP fails. {@link #identity} (private key) stays client-side.
     */
    public static final class Csr {
        public final String e164;
        public final KeyPair identity;      // client-only; not transmitted
        public final byte[] identityPub65;  // SEC1 P-256 point
        public final byte[] subjectDer;     // leaf Subject Name DER (CN=<uuid>)
        public final byte[] sanDer;         // SAN GeneralNames DER = SEQ{ [6] tel:+E164 }
        public final byte[] ext4Value;      // .4 ParticipantInformation VALUE (inner SEQUENCE DER)
        public final byte[] csrPop;         // SHA256withECDSA(idKey, msisdn_utf8||identityPub65), DER
        Csr(String e164, KeyPair id, byte[] pub, byte[] subj, byte[] san, byte[] e4, byte[] pop) {
            this.e164 = e164; this.identity = id; this.identityPub65 = pub; this.subjectDer = subj;
            this.sanDer = san; this.ext4Value = e4; this.csrPop = pop;
        }
    }

    /**
     * Build a CSR with a <b>THROWAWAY</b> identity key.
     *
     * <p><b>Only correct for the LOCAL PKI</b> ({@link #issueIdentity}, {@code MlsSelfTestReceiver}),
     * where the CA is minted in the same call and nothing outside the process ever refers to the key
     * again. For a REAL enrolment use {@link #buildCsr(String, KeyPair)} with the durable lab
     * participant key.
     *
     * <p>This overload used to be the only one, and {@code RcsKdsClient.enroll} called it — so the
     * device's {@code identity_pub}, and the {@code participantKey} inside the {@code .4} extension,
     * changed on every enrolment. RCC.16 §4.1 has the ACS RETAIN the participant key and sign a
     * {@code SignedEncryptionIdentityProof} over it, and the KDS enforces
     * {@code ext4.participantKey == SPKI(identity_pub)}; a key that changes between the ACS call and
     * the enrolment is refused at both ends with an error naming neither, so no ACS proof could ever
     * match.
     */
    public static Csr buildCsr(final String e164) throws Exception {
        return buildCsr(e164, generateIdentityKey());
    }

    /**
     * Build a CSR (client side) over a SUPPLIED identity key: subject CN, SAN tel:, the {@code .4}
     * PoP, and {@code csr_pop}.
     *
     * @param identity the P-256 key the leaf will certify. For a real enrolment this MUST be the
     *     durable lab participant key ({@code MlsParticipantIdentityKey}) — the same key whose SPKI DER the
     *     ACS was given as {@code participant_key}, because the KDS checks the two against each
     *     other. The private half never leaves the device.
     */
    public static Csr buildCsr(final String e164, final KeyPair identity) throws Exception {
        final KeyPair id = identity;
        final PublicKey idPub = id.getPublic();
        final byte[] pub65 = sec1Point(idPub);
        final X500Name subject = cnDn(UUID.randomUUID().toString());
        final byte[] subjectDer = subject.getEncoded("DER");
        final GeneralNames san = new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, "tel:" + e164));
        final byte[] sanDer = san.getEncoded("DER");
        final long now = System.currentTimeMillis();
        final Date nb = new Date(now - 3600_000L);
        final Date na = new Date(now + LEAF_LIFETIME_S * 1000L);
        final byte[] ext4 = buildParticipantInfoExt(id.getPrivate(), idPub, subject, san, nb, na);
        // csr_pop: holds-the-key proof. Standard SHA256withECDSA (single SHA-256 inside ECDSA)
        // over the raw pre-image (msisdn_utf8 || identity_pub_65B), DER-encoded signature.
        final byte[] popPreimage = concat(("tel:" + e164).getBytes("UTF-8"), pub65);
        final byte[] csrPop = ecdsaSha256(id.getPrivate(), popPreimage);
        return new Csr(e164, id, pub65, subjectDer, sanDer, ext4, csrPop);
    }

    /**
     * The CA half of {@code POST /mls/v1/certificate}: issue the RCC.16 leaf, embedding the client's
     * {@code subjectDer}/{@code sanDer}/{@code ext4} VERBATIM and adding the CA-controlled
     * profile (EKU, KeyUsage, SKI, AKI, vendor-id, validity), signed by {@code issuer}. Follows the
     * stock RCC.16 §A.3.8 client profile — BasicConstraints is OMITTED on the leaf (the lab
     * {@code openrcs-kds} does the same). A real server also verifies {@code csr_pop} and that
     * {@code sanDer} matches the authenticated msisdn before issuing.
     */
    public static byte[] issueLeafFromCsr(final Ca issuer, final Csr csr) throws Exception {
        final X500Name subject = X500Name.getInstance(
                org.bouncycastle.asn1.ASN1Primitive.fromByteArray(csr.subjectDer));
        final SubjectPublicKeyInfo spki = spkiFromSec1P256(csr.identityPub65);
        final long now = System.currentTimeMillis();
        final Date nb = new Date(now - 3600_000L);
        final Date na = new Date(now + LEAF_LIFETIME_S * 1000L);
        final X509v3CertificateBuilder b =
                new X509v3CertificateBuilder(issuer.subject, serial(), nb, na, subject, spki);
        // §A.3.8: no BasicConstraints on the leaf (Rcc16Validator ignores it either way).
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        b.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.getInstance(OID_EKU_RCSMLS) }));
        b.addExtension(Extension.subjectKeyIdentifier, false,
                new SubjectKeyIdentifier(sha1(csr.identityPub65)));
        b.addExtension(Extension.authorityKeyIdentifier, false, new AuthorityKeyIdentifier(issuer.ski));
        // Embed the client's SAN / .5 / .4 VALUE bytes verbatim (raw extension-value overload).
        b.addExtension(Extension.subjectAlternativeName, false, csr.sanDer);
        b.addExtension(OID_VENDOR, false, new ASN1Integer(GOOGLE_VENDOR_ID));
        // §14.2.3: EXACTLY ONE E2EE policy on a leaf, non-critical, NO qualifiers.
        //
        // PolicyInformation ::= SEQUENCE { policyIdentifier OID, policyQualifiers OPTIONAL }, so a
        // bare SEQUENCE containing only the OID is the qualifier-free form the profile demands —
        // the validator rejects anything following the identifier inside the PolicyInformation.
        b.addExtension(Extension.certificatePolicies, false,
                new DERSequence(new DERSequence(OID_E2EE_POLICY)));
        b.addExtension(OID_PARTICIPANT_INFO, true, csr.ext4Value);   // CRITICAL
        final ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(issuer.keyPair.getPrivate());
        return b.build(signer).getEncoded();
    }

    /** SubjectPublicKeyInfo for a raw 65-byte SEC1 P-256 point (id-ecPublicKey + prime256v1). */
    private static SubjectPublicKeyInfo spkiFromSec1P256(final byte[] point65) {
        final AlgorithmIdentifier alg = new AlgorithmIdentifier(
                new ASN1ObjectIdentifier("1.2.840.10045.2.1"),        // id-ecPublicKey
                new ASN1ObjectIdentifier("1.2.840.10045.3.1.7"));     // prime256v1
        return new SubjectPublicKeyInfo(alg, point65);
    }

    private static byte[] sha1(final byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(b);
    }

    /**
     * The {@code 2.23.146.2.1.4} ParticipantInformation PoP. TBS (self-signed by the identity key) =
     * {@code SEQ{ leafSubject, INT 2, validity, participantSPKI, leafSAN }} — byte-identical to what
     * {@code rcc16_validate.rs::verify_participant_pop} reconstructs. Ext value =
     * {@code SEQ{ INT 2, validity, SEQ{ecdsa-SHA256}, BITSTRING sig, participantSPKI }}.
     */
    private static byte[] buildParticipantInfoExt(final PrivateKey idPriv, final PublicKey idPub,
            final X500Name leafSubject, final GeneralNames leafSan, final Date nb, final Date na)
            throws Exception {
        final ASN1EncodableVector vv = new ASN1EncodableVector();
        vv.add(new Time(nb));
        vv.add(new Time(na));
        final DERSequence validity = new DERSequence(vv);
        final SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(idPub.getEncoded());

        final ASN1EncodableVector tbs = new ASN1EncodableVector();
        tbs.add(leafSubject);                          // (1) == leaf subject
        tbs.add(new ASN1Integer(GOOGLE_VENDOR_ID));    // (2) vendorId = 2
        tbs.add(validity);                             // (3) validity
        tbs.add(spki);                                 // (4) participant SPKI (== identity key)
        tbs.add(leafSan);                              // (5) == leaf SAN GeneralNames
        final byte[] sig = ecdsaSha256(idPriv, new DERSequence(tbs).getEncoded("DER"));

        final ASN1EncodableVector body = new ASN1EncodableVector();
        body.add(new ASN1Integer(GOOGLE_VENDOR_ID));
        body.add(validity);
        body.add(new AlgorithmIdentifier(OID_ECDSA_SHA256));
        body.add(new DERBitString(sig));
        body.add(spki);
        // participantKeyRolls [0] IMPLICIT SEQUENCE SIZE (1..5) OF ParticipantKeyRoll OPTIONAL
        //
        // The key-roll chain belongs HERE, inside .4 — not in a separate .5 extension, which is
        // where we used to put it and which is the ACS-proof slot (see the OID_ACS_PARTICIPANT_INFO
        // note above). We hold no rolls, and the field is SIZE(1..5), so a zero-length sequence
        // would be ILLEGAL: the faithful encoding of "no rolls" is to OMIT the optional field, which
        // is exactly what the old 4-byte {00 00 00 00} stub was standing in for.
        //
        // When rolls do exist, append:
        //   body.add(new DERTaggedObject(false, 0, new DERSequence(rolls)));
        // where each roll is ParticipantKeyRoll ::= SEQUENCE { alg, sigValue BIT STRING, oldKey SPKI }
        // and the chain runs newest-first per A.3.8.9.
        return new DERSequence(body).getEncoded("DER");
    }

    private static byte[] ecdsaSha256(final PrivateKey key, final byte[] tbs) throws Exception {
        final Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(tbs);
        return s.sign();
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    // ---- convenience: full local identity (self-test / no-service fallback) ----

    /**
     * Mint a complete {@link MlsIdentity} for {@code e164} under {@code pki} by driving BOTH halves of
     * the real lab flow locally ({@link #buildCsr} then {@link #issueLeafFromCsr}) — so the self-test
     * exercises the exact CSR contract the lab CA implements. chain = [ICA], roots = [root]. Ready for
     * OpenMlsEngine.startSession.
     */
    public static MlsIdentity issueIdentity(final LocalPki pki, final String e164) throws Exception {
        final Csr csr = buildCsr(e164);
        final byte[] leaf = issueLeafFromCsr(pki.ica, csr);
        final List<byte[]> chain = new ArrayList<>();
        chain.add(pki.ica.der);
        final List<byte[]> roots = Collections.singletonList(pki.root.der);
        Log.i(TAG, P + "issueIdentity " + e164 + ": leaf=" + leaf.length + "B chain=[ica] roots=[root]");
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
