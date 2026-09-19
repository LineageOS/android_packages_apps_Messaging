/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.log;

import java.util.Collection;

/**
 * Masks a phone number for a log line, keeping its last four digits: {@code ***4567}. Logcat is
 * readable over adb and lands in bug reports, so a number reaches it unmasked only from a log
 * line that a debug build alone writes. Unlike {@code LogUtil.sanitizePII}, which a log-tag
 * property can switch off on a user build, this has no switch.
 *
 * <p>Pure Java, so the host tests compile it as {@code messaging-log-mask-host}.
 */
public final class LogMask {

    private LogMask() {}

    /**
     * {@code ***} and the last four digits of {@code number}, ignoring every other character, so a
     * {@code tel:} or SIP URI masks the same way. {@code ***} alone when there are four digits or
     * fewer; {@code null} and the empty string are shown as they are, since neither is a number.
     */
    public static String number(final String number) {
        if (number == null) return "null";
        if (number.isEmpty()) return "";
        final char[] last = new char[4];
        int digits = 0;
        for (int i = 0; i < number.length(); i++) {
            final char c = number.charAt(i);
            if (c >= '0' && c <= '9') last[digits++ % 4] = c;
        }
        if (digits <= 4) return "***";
        final StringBuilder b = new StringBuilder("***");
        for (int i = 0; i < 4; i++) b.append(last[(digits + i) % 4]);
        return b.toString();
    }

    /** {@link #number} over each element, as {@code [***4567, ***0123]}. */
    public static String numbers(final Collection<String> numbers) {
        if (numbers == null) return "null";
        final StringBuilder b = new StringBuilder("[");
        for (final String n : numbers) {
            if (b.length() > 1) b.append(", ");
            b.append(number(n));
        }
        return b.append(']').toString();
    }
}
