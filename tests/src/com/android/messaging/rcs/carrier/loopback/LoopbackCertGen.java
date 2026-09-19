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
package com.android.messaging.rcs.carrier.loopback;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Loopback-only X.509 self-signed certificate factory. Test scaffolding.
 *
 * <p>The {@link LoopbackSipMsrpServer} needs a TLS cert+key pair for its MSRP
 * SSLServerSocket. Production code uses platform trust anchors / cert pinning;
 * the loopback harness instead emits an ephemeral self-signed cert generated
 * at startup. The SHA-256 fingerprint is surfaced so the SDP answer can carry
 * an {@code a=fingerprint:} attribute that the client validates per RFC 4572
 * (the spec-mandated MSRP-over-TLS auth mechanism — see
 * {@code MsrpTlsConnection.computePeerFingerprint}).
 *
 * <p>Why hand-rolled DER instead of {@code sun.security.tools.keytool.CertAndKeyGen}?
 * Because the modular JDK locks {@code sun.security.tools.keytool} away from
 * unnamed modules even via reflection. Hand-rolling a v3 X.509 certificate
 * in DER is ~150 lines of bounded code and uses only standard
 * {@code java.security} / {@code java.security.cert} APIs at parse time.
 *
 * <p>The cert is NEVER chain-validated by anything in our pipeline — the
 * client's {@code MsrpTlsConnection} skips trust anchors entirely and binds
 * via the SDP fingerprint. We sign with SHA256withRSA so OpenJDK accepts it
 * during the handshake.
 */
final class LoopbackCertGen {

    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final byte[] sha256Fingerprint;

    private LoopbackCertGen(KeyPair kp, X509Certificate cert, byte[] fp) {
        this.keyPair = kp;
        this.certificate = cert;
        this.sha256Fingerprint = fp;
    }

    static LoopbackCertGen generate(String dn, int rsaBits, long validitySeconds)
            throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(rsaBits);
        KeyPair kp = kpg.generateKeyPair();
        byte[] der = makeCertDer(kp, dn, validitySeconds);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate)
                cf.generateCertificate(new ByteArrayInputStream(der));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] fp = md.digest(cert.getEncoded());
        return new LoopbackCertGen(kp, cert, fp);
    }

    PrivateKey getPrivateKey()   { return keyPair.getPrivate(); }
    PublicKey  getPublicKey()    { return keyPair.getPublic(); }
    X509Certificate getCertificate() { return certificate; }
    byte[] getSha256Fingerprint() { return sha256Fingerprint.clone(); }

    /** Hex colon-separated, lowercase, matching the form RFC 4572 §5 mandates. */
    String getSha256FingerprintColonHex() {
        StringBuilder sb = new StringBuilder(sha256Fingerprint.length * 3);
        for (int i = 0; i < sha256Fingerprint.length; i++) {
            if (i > 0) sb.append(':');
            int v = sha256Fingerprint[i] & 0xFF;
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------- DER cert generator (X.509 v3) ----------

    /**
     * Build a DER-encoded X.509 v3 cert self-signed with SHA256withRSA. v3 so
     * we can include a Subject Alternative Name extension for 127.0.0.1 (no
     * production code in our app actually does hostname verification on MSRP
     * — see {@code MsrpTlsConnection} — but having a SAN keeps the cert
     * legible to {@code keytool} et al. if a test fails and we need to dump
     * it).
     */
    private static byte[] makeCertDer(KeyPair kp, String dn, long secondsValid)
            throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(now + secondsValid * 1000L);
        byte[] tbs = encodeTbs(kp.getPublic(), dn, notBefore, notAfter);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();
        // signatureAlgorithm: SEQ { OID 1.2.840.113549.1.1.11, NULL }
        byte[] sigAlgSeq = der(0x30, concat(oid("1.2.840.113549.1.1.11"),
                derEmpty(0x05)));
        byte[] sigBitStr = bitString(sigBytes);
        // Certificate: SEQ { tbsCert, sigAlg, sigBits }
        return der(0x30, concat(tbs, sigAlgSeq, sigBitStr));
    }

    private static byte[] encodeTbs(PublicKey pub, String dn,
            Date notBefore, Date notAfter) throws Exception {
        // version [0] EXPLICIT v3 (INTEGER 2)
        byte[] version = explicit(0, integer(2));
        // serialNumber (positive INTEGER, fits in long)
        byte[] serial = integer(BigInteger.valueOf(System.currentTimeMillis() & 0x7fffffffffffL));
        // signature alg id
        byte[] sigAlg = der(0x30, concat(oid("1.2.840.113549.1.1.11"), derEmpty(0x05)));
        // issuer / subject (same — self-signed)
        byte[] dnBytes = encodeName(dn);
        // validity
        byte[] validity = der(0x30, concat(utcTime(notBefore), utcTime(notAfter)));
        // SubjectPublicKeyInfo — already DER-encoded by the JCE
        byte[] spki = pub.getEncoded();
        // extensions (SAN: DNS=localhost + IP=127.0.0.1, basicConstraints CA:FALSE)
        byte[] extensions = encodeExtensions();
        return der(0x30, concat(version, serial, sigAlg, dnBytes,
                validity, dnBytes, spki, extensions));
    }

    private static byte[] encodeExtensions() throws Exception {
        // Extensions [3] EXPLICIT SEQUENCE OF Extension
        // Extension :: SEQ { extnID OID, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING }
        byte[] san = encodeSan();
        byte[] bc = encodeBasicConstraintsNotCa();
        byte[] extSeq = der(0x30, concat(bc, san));
        return explicit(3, extSeq);
    }

    private static byte[] encodeBasicConstraintsNotCa() throws Exception {
        // SEQ { CA BOOLEAN FALSE }  (default — emit empty SEQ)
        byte[] inner = der(0x30, new byte[0]);
        byte[] octet = der(0x04, inner);
        // Mark critical=true
        byte[] crit = der(0x01, new byte[]{(byte) 0xff});
        return der(0x30, concat(oid("2.5.29.19"), crit, octet));
    }

    private static byte[] encodeSan() throws Exception {
        // SubjectAltName OID = 2.5.29.17
        // Value: SEQ OF GeneralName
        //   GeneralName ::= CHOICE { dNSName [2] IA5String, iPAddress [7] OCTET STRING }
        byte[] dnsLocalhost = der(0x82, "localhost".getBytes("US-ASCII"));
        // 127.0.0.1 as 4 raw bytes
        byte[] ip127 = der(0x87, new byte[]{127, 0, 0, 1});
        byte[] sanSeq = der(0x30, concat(dnsLocalhost, ip127));
        byte[] octet = der(0x04, sanSeq);
        return der(0x30, concat(oid("2.5.29.17"), octet));
    }

    // ---------- DER primitives ----------

    private static byte[] der(int tag, byte[] content) {
        byte[] len = lenBytes(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

    private static byte[] derEmpty(int tag) {
        return new byte[]{(byte) tag, 0};
    }

    private static byte[] lenBytes(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
        if (n < 0x10000) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static byte[] integer(int v) {
        return integer(BigInteger.valueOf(v));
    }

    private static byte[] integer(BigInteger bi) {
        return der(0x02, bi.toByteArray());
    }

    private static byte[] explicit(int tag, byte[] inner) {
        return der(0xA0 | tag, inner);
    }

    private static byte[] bitString(byte[] bytes) {
        byte[] withZero = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, withZero, 1, bytes.length);
        return der(0x03, withZero);
    }

    private static byte[] oid(String s) throws IOException {
        String[] parts = s.split("\\.");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        bo.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            byte[] enc = base128(v);
            bo.write(enc, 0, enc.length);
        }
        return der(0x06, bo.toByteArray());
    }

    private static byte[] base128(long v) {
        if (v == 0) return new byte[]{0};
        int len = 0;
        long t = v;
        while (t > 0) {
            len++;
            t >>= 7;
        }
        byte[] out = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            out[i] = (byte) ((v & 0x7F) | (i == len - 1 ? 0 : 0x80));
            v >>= 7;
        }
        return out;
    }

    private static byte[] utcTime(Date d) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x17, sdf.format(d).getBytes("US-ASCII"));
    }

    private static byte[] encodeName(String dn) throws Exception {
        // X.500 Name :: SEQ OF RDN; RDN :: SET OF AttributeTypeAndValue
        String[] parts = dn.split(",");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (String p : parts) {
            p = p.trim();
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            String k = p.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String v = p.substring(eq + 1).trim();
            String o;
            switch (k) {
                case "CN": o = "2.5.4.3"; break;
                case "OU": o = "2.5.4.11"; break;
                case "O":  o = "2.5.4.10"; break;
                case "C":  o = "2.5.4.6"; break;
                default: continue;
            }
            byte[] atv = der(0x30, concat(oid(o),
                    der(0x0c, v.getBytes("UTF-8")))); // UTF8String
            byte[] rdn = der(0x31, atv); // SET
            bo.write(rdn, 0, rdn.length);
        }
        return der(0x30, bo.toByteArray());
    }

    private static byte[] concat(byte[]... arrs) {
        int n = 0;
        for (byte[] a : arrs) n += a.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] a : arrs) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }
}
