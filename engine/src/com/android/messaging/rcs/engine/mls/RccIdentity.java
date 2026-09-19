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

/**
 * RCC.16 <b>A.4.1</b> participant identity — comparing an MSISDN against the one a certificate
 * asserts.
 *
 * <p><b>Why this is its own class.</b> A.4.1 requires a leaf certificate's SAN {@code tel:} URI to
 * <b>equal the queried MSISDN</b>: a certificate for the <i>wrong</i> number must fail, not merely
 * be a well-formed {@code tel:} URI. Only the caller knows the queried number — it is whichever
 * MSISDN it asked the KDS for — so the engine reports the certified identity and the comparison
 * happens at the claim site. That makes this comparison shared, pure, and worth pinning on the host:
 * it is the difference between talking to the peer we meant and talking to whoever the directory
 * handed back.
 *
 * <p>The Rust engine carries the same rule at {@code rcc16_validate.rs::normalize_e164} /
 * {@code msisdn_equals} for the leaf it parses. The two must agree, so both are host-tested against
 * the same spellings.
 */
public final class RccIdentity {

    private RccIdentity() {}

    /**
     * Do two MSISDN-ish strings denote the same number?
     *
     * <p>E.164-normalized, so {@code +1-555-111-0001}, {@code tel:+15551110001} and
     * {@code 15551110001} all compare equal. <b>Empty never matches</b>, including another empty: an
     * absent identity must not pass an equality check by looking like a wildcard — that is precisely
     * the failure this check exists to prevent.
     */
    public static boolean msisdnEquals(final String a, final String b) {
        final String x = normalizeE164(a);
        final String y = normalizeE164(b);
        return !x.isEmpty() && x.equals(y);
    }

    /**
     * Reduce a {@code tel:} URI or a raw MSISDN to bare E.164 digits.
     *
     * <p>Strips a leading {@code tel:} scheme, cuts any tel-URI parameters ({@code ;phone-context=…},
     * which are not part of the number), and drops visual separators — {@code +}, {@code -},
     * {@code .}, spaces and parentheses are non-significant. ASCII digits only; anything else,
     * including {@code null}, reduces to the empty string.
     */
    public static String normalizeE164(final String s) {
        if (s == null) return "";
        final String v = s.startsWith("tel:") ? s.substring(4) : s;
        final StringBuilder out = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (c == ';') break;                        // tel-URI parameters follow the number
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }
}
