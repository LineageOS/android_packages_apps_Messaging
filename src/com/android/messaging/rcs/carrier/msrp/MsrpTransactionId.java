/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

import java.security.SecureRandom;

/**
 * MSRP transaction ids (RFC 4975 §9): generated as 16 random bytes in lowercase hex, validated
 * against the full {@code ident} grammar so any conforming peer id is accepted.
 */
public final class MsrpTransactionId {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    public static final int DEFAULT_LEN = 32;

    private MsrpTransactionId() {}

    public static String next() {
        byte[] b = new byte[DEFAULT_LEN / 2];
        RNG.nextBytes(b);
        char[] out = new char[DEFAULT_LEN];
        for (int i = 0; i < b.length; i++) {
            out[i * 2]     = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    /** 4 to 32 characters, the first alphanumeric, the rest alphanumeric or {@code . - + % =}. */
    public static boolean isValid(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 4 || n > 32) return false;
        if (!isAlphaNum(s.charAt(0))) return false;
        for (int i = 1; i < n; i++) {
            char c = s.charAt(i);
            if (!isIdentChar(c)) return false;
        }
        return true;
    }

    private static boolean isAlphaNum(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z');
    }

    private static boolean isIdentChar(char c) {
        return isAlphaNum(c)
            || c == '.' || c == '-' || c == '+' || c == '%' || c == '=';
    }
}
