/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * The key every per-conversation map, lock and record in the MLS layer is indexed by:
 * {@code "g:" + rcsGroupId} for a group, {@code "p:" + peerE164} for a 1:1.
 */
public final class MlsConversationKey {
    private MlsConversationKey() {}

    /** A group is never keyed by peer: one person can share a 1:1 and a group with us. */
    public static String canonicalKey(final String rcsGroupId, final String peerE164) {
        if (rcsGroupId != null && !rcsGroupId.isEmpty()) return "g:" + rcsGroupId;
        return (peerE164 == null || peerE164.isEmpty()) ? null : "p:" + peerE164;
    }

    /** Returns {@code {rcsGroupId, peerE164}} (one null), or null for a key of neither shape. */
    public static String[] splitCanonicalKey(final String key) {
        if (key == null) return null;
        if (key.startsWith("g:")) return new String[] { key.substring(2), null };
        if (key.startsWith("p:")) return new String[] { null, key.substring(2) };
        return null;
    }

    /**
     * {@code key} for a log line: each phone number in it through {@link LogMask#number}, so
     * {@code p:+15550100123} is written {@code p:***0123}. A number is a run of digits after
     * {@code +} or right after {@code p:}; a group key, a database id or a timestamp passes
     * unchanged. Also takes any text that embeds a key. The key itself is never rewritten: it is
     * persisted and used as a map key.
     */
    public static String forLog(final String key) {
        if (key == null) return "null";
        final StringBuilder out = new StringBuilder(key.length());
        int i = 0;
        while (i < key.length()) {
            final int end = numberEnd(key, i);
            if (end > i) {
                out.append(LogMask.number(key.substring(i, end)));
                i = end;
            } else {
                out.append(key.charAt(i++));
            }
        }
        return out.toString();
    }

    /** The end of the number that starts at {@code i}, or {@code i} when none does. */
    private static int numberEnd(final String s, final int i) {
        int digits = i;
        if (s.charAt(i) == '+') {
            digits++;
        } else if (i < 2 || !s.startsWith("p:", i - 2)) {
            return i;
        }
        int end = digits;
        while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') end++;
        return end > digits ? end : i;
    }
}
