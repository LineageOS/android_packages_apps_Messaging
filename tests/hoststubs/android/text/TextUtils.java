/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.text;

/** Host stand-in for the {@code android.text.TextUtils} methods the pure e2ee classes call. */
public final class TextUtils {
    private TextUtils() {}

    public static boolean isEmpty(final CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean equals(final CharSequence a, final CharSequence b) {
        return a == b || (a != null && b != null && a.toString().equals(b.toString()));
    }
}
