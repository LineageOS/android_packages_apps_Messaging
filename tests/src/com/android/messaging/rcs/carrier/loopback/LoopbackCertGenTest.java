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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.junit.Test;

/**
 * Unit tests for {@link LoopbackCertGen} — the hand-rolled DER self-signed
 * certificate generator used by the MSRP-over-TLS loopback. Test scaffolding.
 *
 * <p>We deliberately use a smaller 1024-bit RSA key here to keep the test
 * suite fast; in real usage the harness defaults to 2048 bits. The cert is
 * still parseable by the standard {@link java.security.cert.CertificateFactory}.
 */
public class LoopbackCertGenTest {

    @Test
    public void generate_producesParseableSelfSignedCert() throws Exception {
        LoopbackCertGen g = LoopbackCertGen.generate(
                "CN=loopback.test,O=LineageOS", 1024, 3600);
        X509Certificate cert = g.getCertificate();
        assertNotNull(cert);
        cert.checkValidity(new Date());
        // Self-signed: issuer == subject.
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        // Verifies against its own public key.
        cert.verify(g.getPublicKey());
    }

    @Test
    public void fingerprint_matchesSha256OfEncodedCert() throws Exception {
        LoopbackCertGen g = LoopbackCertGen.generate(
                "CN=loopback.test", 1024, 3600);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] expected = md.digest(g.getCertificate().getEncoded());
        assertEquals(32, expected.length);
        assertEquals(32, g.getSha256Fingerprint().length);
        // Bytes match.
        for (int i = 0; i < 32; i++) {
            assertEquals(expected[i], g.getSha256Fingerprint()[i]);
        }
    }

    @Test
    public void fingerprintColonHex_matchesRfc4572FormatLowercase() throws Exception {
        LoopbackCertGen g = LoopbackCertGen.generate(
                "CN=loopback.test", 1024, 3600);
        String hex = g.getSha256FingerprintColonHex();
        // 32 bytes => 32*2 chars + 31 colons
        assertEquals(32 * 3 - 1, hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((i + 1) % 3 == 0) {
                assertEquals("colon at " + i, ':', c);
            } else {
                assertTrue("hex digit at " + i + ": " + c,
                        (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
            }
        }
    }

    @Test
    public void differentGenerationsProduceDifferentFingerprints() throws Exception {
        LoopbackCertGen a = LoopbackCertGen.generate("CN=a", 1024, 3600);
        LoopbackCertGen b = LoopbackCertGen.generate("CN=b", 1024, 3600);
        assertFalse(a.getSha256FingerprintColonHex().equals(
                b.getSha256FingerprintColonHex()));
    }

    @Test
    public void cert_carriesSubjectAlternativeNameFor127001() throws Exception {
        LoopbackCertGen g = LoopbackCertGen.generate(
                "CN=loopback.test", 1024, 3600);
        java.util.Collection<java.util.List<?>> sans =
                g.getCertificate().getSubjectAlternativeNames();
        assertNotNull(sans);
        // We added DNS=localhost (type 2) and IP=127.0.0.1 (type 7).
        boolean dns = false, ip = false;
        for (java.util.List<?> entry : sans) {
            int type = ((Integer) entry.get(0)).intValue();
            String value = (String) entry.get(1);
            if (type == 2 && "localhost".equals(value)) dns = true;
            if (type == 7 && "127.0.0.1".equals(value)) ip = true;
        }
        assertTrue("DNS=localhost", dns);
        assertTrue("IP=127.0.0.1", ip);
    }
}
