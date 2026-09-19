/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Send statuses that arrived before the row they describe was inserted, keyed by rcs message id.
 * The insert path takes the entry once the row exists. Bounded and in memory: an entry that is
 * never claimed is evicted by size or age, which costs only that status. Thread-safe.
 */
public final class RcsEarlyStatusPark {
    /**
     * One parked status. {@code sent} and {@code scheme} survive a later status for the same id,
     * so a send followed by its delivery still reports the applied scheme. {@code source} is an
     * {@code E2eeObservation.Source} name.
     */
    public static final class Entry {
        public final int status;
        public final boolean sent;
        public final String scheme;
        public final String source;
        final long parkedAtMs;

        Entry(final int status, final boolean sent, final String scheme, final String source,
                final long parkedAtMs) {
            this.status = status;
            this.sent = sent;
            this.scheme = scheme;
            this.source = source;
            this.parkedAtMs = parkedAtMs;
        }
    }

    public static final int MAX_ENTRIES = 256;
    public static final long MAX_AGE_MS = 10L * 60L * 1000L;

    private static final RcsEarlyStatusPark INSTANCE = new RcsEarlyStatusPark();

    public static RcsEarlyStatusPark get() {
        return INSTANCE;
    }

    private final LinkedHashMap<String, Entry> mEntries =
            new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, RcsEarlyStatusPark.Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Parks a status. A later status for the same id replaces the status but keeps the scheme of
     * an earlier sent status.
     *
     * @param sent the status is STATUS_SENT, the only one whose scheme means anything
     */
    public synchronized void park(final String rcsMessageId, final int status, final boolean sent,
            final String scheme, final String source, final long nowMs) {
        if (rcsMessageId == null) return;
        expire(nowMs);
        final Entry prior = mEntries.get(rcsMessageId);
        final boolean keepPrior = !sent && prior != null && prior.sent;
        mEntries.put(rcsMessageId, new Entry(status, sent || keepPrior,
                keepPrior ? prior.scheme : scheme, source, nowMs));
    }

    /** Removes and returns the entry for {@code rcsMessageId}, or null if none is live. */
    public synchronized Entry take(final String rcsMessageId, final long nowMs) {
        if (rcsMessageId == null) return null;
        expire(nowMs);
        return mEntries.remove(rcsMessageId);
    }

    public synchronized int size() {
        return mEntries.size();
    }

    private void expire(final long nowMs) {
        final Iterator<Entry> it = mEntries.values().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().parkedAtMs > MAX_AGE_MS) it.remove();
        }
    }
}
