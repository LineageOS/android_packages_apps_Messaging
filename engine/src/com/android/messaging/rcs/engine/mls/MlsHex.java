/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Hex rendering and parsing for log lines and debug inputs. {@code hex}, {@code groupIdHex} and
 * {@code hexDump} agree on non-null input and differ only in how null renders.
 */
public final class MlsHex {
    private MlsHex() {}

    public static String hex(final byte[] b) {
        if (b == null) return "";
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Parses hex, ignoring any non-hex characters; null or empty yields an empty array. */
    public static byte[] hexToBytes(final String s) {
        if (s == null) return new byte[0];
        final String h = s.replaceAll("[^0-9a-fA-F]", "");
        if (h.length() < 2) return new byte[0];
        final int n = h.length() / 2;
        final byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** The first 8 bytes as hex plus the length; tells digests apart without dumping keys. */
    public static String hexPrefix(final byte[] b) {
        if (b == null) return "null";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, b.length); i++) sb.append(String.format("%02x", b[i]));
        return sb + "/" + b.length + "B";
    }

    /** Lower-case hex for the AAD dump; "null" for null. */
    public static String hexDump(final byte[] b) {
        if (b == null) return "null";
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** A group id in the lower-case hex form {@link MlsConversationRecord#key} uses. */
    public static String groupIdHex(final byte[] b) {
        if (b == null) return "";
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(
                    Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
