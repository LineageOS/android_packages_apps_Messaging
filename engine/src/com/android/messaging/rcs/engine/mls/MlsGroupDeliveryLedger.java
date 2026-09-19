/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which members have confirmed delivery of a group message we sent, so its replay material can be
 * released once every current member has confirmed rather than after the full retention window.
 *
 * <p>No roster is stored: coverage is judged against the roster the caller passes each time, so
 * members added or removed after the send are handled without policy here. Bounded, with
 * oldest-first eviction reported through {@link #takeEvicted}. Not thread-safe; the caller holds
 * the conversation lock.
 */
public final class MlsGroupDeliveryLedger {

    /** Messages tracked at once; matches the send-material store's cap. */
    public static final int MAX_MESSAGES = 256;

    /** Message id to confirming members, insertion-ordered for oldest-first eviction. */
    private final LinkedHashMap<String, Set<String>> mConfirmed =
            new LinkedHashMap<String, Set<String>>();

    /** Message ids evicted by the cap, for the caller to report. Cleared when read. */
    private final List<String> mEvicted = new ArrayList<String>();

    /**
     * Records that {@code member} confirmed {@code messageId}. Idempotent, so a replayed IMDN
     * cannot advance coverage twice.
     *
     * @return true if this was new information
     */
    public boolean record(final String messageId, final String member) {
        if (isBlank(messageId) || isBlank(member)) return false;
        Set<String> set = mConfirmed.get(messageId);
        if (set == null) {
            evictIfFull();
            set = new LinkedHashSet<String>();
            mConfirmed.put(messageId, set);
        }
        return set.add(normalise(member));
    }

    /**
     * Whether every member of {@code roster} confirmed {@code messageId}. An empty or null roster
     * is not coverage: it cannot be told apart from an unreadable roster, and keeping the bytes is
     * the safe side.
     *
     * @param roster the members expected to confirm, excluding ourselves
     */
    public boolean isCovered(final String messageId, final Collection<String> roster) {
        if (isBlank(messageId) || roster == null || roster.isEmpty()) return false;
        final Set<String> set = mConfirmed.get(messageId);
        if (set == null) return false;
        for (final String m : roster) {
            if (isBlank(m)) continue;
            if (!set.contains(normalise(m))) return false;
        }
        return true;
    }

    /** How many distinct members have confirmed {@code messageId}. */
    public int confirmedCount(final String messageId) {
        final Set<String> set = isBlank(messageId) ? null : mConfirmed.get(messageId);
        return set == null ? 0 : set.size();
    }

    /** Stop tracking {@code messageId} — released, failed, or aged out. */
    public void forget(final String messageId) {
        if (!isBlank(messageId)) mConfirmed.remove(messageId);
    }

    /** Messages currently tracked. */
    public int size() { return mConfirmed.size(); }

    /**
     * Returns and clears the message ids dropped by the cap. An evicted message can no longer
     * reach coverage and waits the full retention window, so callers log these.
     */
    public List<String> takeEvicted() {
        final List<String> out = new ArrayList<String>(mEvicted);
        mEvicted.clear();
        return out;
    }

    private void evictIfFull() {
        while (mConfirmed.size() >= MAX_MESSAGES) {
            final Map.Entry<String, Set<String>> oldest = mConfirmed.entrySet().iterator().next();
            mEvicted.add(oldest.getKey());
            mConfirmed.remove(oldest.getKey());
        }
    }

    // Digits only: the roster and the IMDN sender may differ in +, tel: or sip: form.
    private static String normalise(final String e164) {
        final StringBuilder sb = new StringBuilder(e164.length());
        for (int i = 0; i < e164.length(); i++) {
            final char c = e164.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isEmpty();
    }
}
