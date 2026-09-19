/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.os;

/** Host stand-in for {@code android.os.SystemProperties}: every property is unset. */
public final class SystemProperties {
    private SystemProperties() {}

    public static String get(final String key, final String def) {
        return def;
    }

    public static boolean getBoolean(final String key, final boolean def) {
        return def;
    }

    public static int getInt(final String key, final int def) {
        return def;
    }

    public static long getLong(final String key, final long def) {
        return def;
    }
}
