/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Compares an MSISDN against the one a certificate asserts (RCC.16 A.4.1: the leaf's SAN
 * {@code tel:} URI must equal the queried number). Only the caller knows the queried number, so
 * the comparison happens at the claim site. The Rust core's {@code msisdn_equals} must agree.
 */
public final class RccIdentity {

    private RccIdentity() {}

    /**
     * E.164-normalized equality. Empty never matches, not even another empty, so an absent identity
     * cannot pass as a wildcard.
     */
    public static boolean msisdnEquals(final String a, final String b) {
        final String x = normalizeE164(a);
        final String y = normalizeE164(b);
        return !x.isEmpty() && x.equals(y);
    }

    /**
     * Reduces a {@code tel:} URI or raw MSISDN to its ASCII digits, stopping at tel-URI parameters;
     * {@code null} gives the empty string.
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
