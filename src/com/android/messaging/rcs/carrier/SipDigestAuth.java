/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * RFC 2617 Digest with MD5, for SIP registration and the HTTP content server. AKA (RFC 3310) is not
 * implemented. See docs/rcs/carrier-transport.md.
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

    /**
     * Whether a challenge came from a proxy: a 407, which carries Proxy-Authenticate and is
     * answered with Proxy-Authorization. A 401 carries WWW-Authenticate and is answered with
     * Authorization (RFC 3261 §22.3).
     */
    public static boolean isProxyChallenge(int status) {
        return status == 407;
    }

    /** The header a challenge with this status carries its Digest parameters in. */
    public static String challengeHeaderName(int status) {
        return isProxyChallenge(status) ? "Proxy-Authenticate" : "WWW-Authenticate";
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
     * Digest response. {@code nonceCount} and {@code cnonce} are required with a qop, else null.
     */
    public static String response(String user, String realm, String password,
            String method, String uri, String nonce,
            String qop, String nonceCount, String cnonce) {
        final String ha1 = md5Hex(user + ":" + realm + ":" + password);
        return responseWithHa1(ha1, method, uri, nonce, qop, nonceCount, cnonce);
    }

    /**
     * Digest response from a provisioned HA1 instead of a password. The HA1 must have been computed
     * with the same user name and realm the caller sends.
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
