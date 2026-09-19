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
package com.android.messaging.rcs.carrier;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Minimal RFC 2617 digest auth. Carrier IMS uses AKAv1/AKAv2 (RFC 3310/4169);
 * those land with M4. For OTT mode (Kamailio default auth_db) plain MD5 digest
 * is the path of least resistance.
 */
public final class SipDigestAuth {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                out[i * 2] = HEX[(digest[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[digest[i] & 0xF];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static String newCnonce() {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        char[] out = new char[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * Compute the response value for HTTP Digest, MD5 algorithm.
     *
     * If qop=="auth", caller must also pass nonceCount and cnonce; otherwise
     * pass null for both (legacy path).
     */
    public static String response(String user, String realm, String password,
            String method, String uri, String nonce,
            String qop, String nonceCount, String cnonce) {
        final String ha1 = md5Hex(user + ":" + realm + ":" + password);
        return responseWithHa1(ha1, method, uri, nonce, qop, nonceCount, cnonce);
    }

    /**
     * Compute the Digest response from a PRECOMPUTED HA1 — {@code HA1 =
     * MD5(username:realm:password)} — instead of a cleartext password. Used when
     * the credential provisioned by the ACS (or HSS) is the HA1 itself
     * (AAuthType=Digest-HA1), so no password/Ki is ever stored client-side. The
     * caller-supplied {@code ha1} must have been computed with the SAME username
     * (the full IMPI we put in the Authorization header) and realm we register on,
     * or the server's HA1 won't match.
     */
    public static String responseWithHa1(String ha1, String method, String uri,
            String nonce, String qop, String nonceCount, String cnonce) {
        final String ha2 = md5Hex(method + ":" + uri);
        final String middle = (qop != null)
                ? nonce + ":" + nonceCount + ":" + cnonce + ":" + qop
                : nonce;
        return md5Hex(ha1 + ":" + middle + ":" + ha2);
    }
}
