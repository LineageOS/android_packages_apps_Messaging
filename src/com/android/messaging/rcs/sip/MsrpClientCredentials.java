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
package com.android.messaging.rcs.sip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Ephemeral self-signed RSA cert + key for MSRP-over-TLS, plus the SHA-256
 * fingerprint we put in the SDP offer's {@code a=fingerprint:} (RFC 4572). GSMA
 * RCS MSRP-over-TLS authenticates BOTH endpoints by the SDP fingerprint, not
 * PKI — so as the TLS client (active setup) we present this self-signed cert,
 * and the relay binds it to the fingerprint it saw in our SDP. Reciprocally we
 * validate the relay's cert against the fingerprint in its SDP answer
 * ({@link com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection}).
 *
 * <p>The DER cert builder is ported from the provider tree's loopback
 * {@code LoopbackCertGen} (host-tested). This class additionally wires the
 * keypair into an {@link SSLSocketFactory} so the cert is actually presented
 * during the client handshake, and installs a trust-all manager (the
 * fingerprint check is the real gate).
 */
public final class MsrpClientCredentials {

    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final byte[] sha256Fingerprint;

    private MsrpClientCredentials(KeyPair kp, X509Certificate cert, byte[] fp) {
        this.keyPair = kp;
        this.certificate = cert;
        this.sha256Fingerprint = fp;
    }

    public static MsrpClientCredentials generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        byte[] der = makeCertDer(kp, "CN=rcs-msrp", 86400L);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate)
                cf.generateCertificate(new ByteArrayInputStream(der));
        byte[] fp = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        return new MsrpClientCredentials(kp, cert, fp);
    }

    /** RFC 4572 §5 lowercase colon-hex fingerprint form for {@code a=fingerprint:}. */
    public String fingerprintColonHex() {
        StringBuilder sb = new StringBuilder(sha256Fingerprint.length * 3);
        for (int i = 0; i < sha256Fingerprint.length; i++) {
            if (i > 0) sb.append(':');
            int v = sha256Fingerprint[i] & 0xFF;
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * An SSLSocketFactory that presents this cert as the client cert and trusts
     * any server cert (fingerprint validated separately post-handshake).
     */
    public SSLSocketFactory sslSocketFactory() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        char[] pw = new char[0];
        ks.setKeyEntry("msrp", keyPair.getPrivate(), pw,
                new Certificate[] { certificate });
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(kmf.getKeyManagers(), trustAll(), new SecureRandom());
        return ctx.getSocketFactory();
    }

    private static TrustManager[] trustAll() {
        return new TrustManager[] { new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
    }

    // ---------- DER cert generator (X.509 v3, ported from LoopbackCertGen) ----------

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
        byte[] sigAlgSeq = der(0x30, concat(oid("1.2.840.113549.1.1.11"), derEmpty(0x05)));
        byte[] sigBitStr = bitString(sigBytes);
        return der(0x30, concat(tbs, sigAlgSeq, sigBitStr));
    }

    private static byte[] encodeTbs(PublicKey pub, String dn, Date nb, Date na)
            throws Exception {
        byte[] version = explicit(0, integer(2));
        byte[] serial = integer(BigInteger.valueOf(System.currentTimeMillis() & 0x7fffffffffffL));
        byte[] sigAlg = der(0x30, concat(oid("1.2.840.113549.1.1.11"), derEmpty(0x05)));
        byte[] dnBytes = encodeName(dn);
        byte[] validity = der(0x30, concat(utcTime(nb), utcTime(na)));
        byte[] spki = pub.getEncoded();
        byte[] extensions = encodeExtensions();
        return der(0x30, concat(version, serial, sigAlg, dnBytes, validity, dnBytes, spki, extensions));
    }

    private static byte[] encodeExtensions() throws Exception {
        byte[] bc = encodeBasicConstraintsNotCa();
        return explicit(3, der(0x30, bc));
    }

    private static byte[] encodeBasicConstraintsNotCa() throws Exception {
        byte[] inner = der(0x30, new byte[0]);
        byte[] octet = der(0x04, inner);
        byte[] crit = der(0x01, new byte[]{(byte) 0xff});
        return der(0x30, concat(oid("2.5.29.19"), crit, octet));
    }

    private static byte[] der(int tag, byte[] content) {
        byte[] len = lenBytes(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

    private static byte[] derEmpty(int tag) { return new byte[]{(byte) tag, 0}; }

    private static byte[] lenBytes(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
        if (n < 0x10000) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static byte[] integer(int v) { return integer(BigInteger.valueOf(v)); }
    private static byte[] integer(BigInteger bi) { return der(0x02, bi.toByteArray()); }
    private static byte[] explicit(int tag, byte[] inner) { return der(0xA0 | tag, inner); }

    private static byte[] bitString(byte[] bytes) {
        byte[] withZero = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, withZero, 1, bytes.length);
        return der(0x03, withZero);
    }

    private static byte[] oid(String s) throws IOException {
        String[] parts = s.split("\\.");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            byte[] enc = base128(Long.parseLong(parts[i]));
            bo.write(enc, 0, enc.length);
        }
        return der(0x06, bo.toByteArray());
    }

    private static byte[] base128(long v) {
        if (v == 0) return new byte[]{0};
        int len = 0;
        long t = v;
        while (t > 0) { len++; t >>= 7; }
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
            byte[] atv = der(0x30, concat(oid(o), der(0x0c, v.getBytes("UTF-8"))));
            bo.write(der(0x31, atv), 0, der(0x31, atv).length);
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
