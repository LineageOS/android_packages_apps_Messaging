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
package com.android.messaging.rcs.carrier.msrp;

import java.security.SecureRandom;

/**
 * MSRP transaction-id generator + validator per RFC 4975 §9 ABNF:
 *
 * <pre>
 *   transact-id = ident
 *   ident       = ALPHANUM 3*31ident-char
 *   ident-char  = ALPHANUM / "." / "-" / "+" / "%" / "="
 * </pre>
 *
 * <p>RFC 4975 also requires the transaction-id to be opaque and globally
 * unique within the lifetime of any same-time transactions (§7.1).
 *
 * <p>Google Messages' encoder uses a 16-byte SecureRandom
 * source rendered as lowercase hex (32 chars). That comfortably satisfies
 * the spec's collision bound while staying in {@code ident-char}. We follow
 * the same approach but expose it via a clean static API so tests can plug
 * in deterministic IDs.
 *
 * <p>The {@link #isValid(String)} predicate accepts the same character set
 * RFC 4975 §9 defines, so it correctly admits both our generator's output
 * and arbitrary spec-compliant peer IDs.
 */
public final class MsrpTransactionId {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** Default chunk-id length: 32 hex chars (16 random bytes). */
    public static final int DEFAULT_LEN = 32;

    private MsrpTransactionId() {}

    /** Generate a fresh 32-character lowercase-hex transaction-id. */
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

    /**
     * Validate per RFC 4975 §9 ABNF:
     * <pre>ident = ALPHANUM 3*31ident-char</pre>
     * i.e. 4-32 characters, first must be alphanum, the rest may also include
     * {@code . - + % =}.
     */
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
