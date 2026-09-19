/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per {@code (identity, group)}, held across the engine call and its storage write and
 * never across transport I/O; {@link #assertNoLockHeld} checks the last clause at every I/O
 * boundary. Entries are never evicted, since evicting one could hand two threads different
 * monitors for the same key. See docs/mls/transport-and-port.md.
 */
public final class MlsGroupLocks {

    private final Map<String, ReentrantLock> mLocks = new ConcurrentHashMap<>();

    /**
     * The lock for one {@code (identity, group)}. The identity is part of the key because the
     * engine is per identity: one RCS group id under two identities is two MLS groups.
     */
    public ReentrantLock lockFor(final String identity, final String group) {
        return mLocks.computeIfAbsent(key(identity, group), k -> new ReentrantLock());
    }

    /** The canonical key; neither an E.164 nor a group id can contain the separator. */
    public static String key(final String identity, final String group) {
        return (identity == null ? "" : identity) + "\0" + (group == null ? "" : group);
    }

    /** True iff the calling thread holds the lock for this {@code (identity, group)}. */
    public boolean isHeldByCurrentThread(final String identity, final String group) {
        final ReentrantLock l = mLocks.get(key(identity, group));
        return l != null && l.isHeldByCurrentThread();
    }

    /** True iff the calling thread holds any group lock. */
    public boolean anyHeldByCurrentThread() {
        for (final ReentrantLock l : mLocks.values()) {
            if (l.isHeldByCurrentThread()) return true;
        }
        return false;
    }

    /** Every key the calling thread holds, for the assertion message. */
    public String heldByCurrentThread() {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, ReentrantLock> e : mLocks.entrySet()) {
            if (e.getValue().isHeldByCurrentThread()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().replace('\0', '/'));
            }
        }
        return sb.toString();
    }

    /**
     * Throws if the calling thread holds a group lock. Call at every binder or RPC boundary.
     *
     * @param what the operation about to be attempted, for the message
     */
    public void assertNoLockHeld(final String what) {
        if (anyHeldByCurrentThread()) {
            throw new IllegalStateException("MLS group lock held across transport I/O (" + what
                    + ") — holding: " + heldByCurrentThread() + ". The lock covers the engine call "
                    + "and its storage write ONLY; see this class's javadoc.");
        }
    }

    /** Test/diagnostic: how many distinct keys have ever been locked. */
    public int size() { return mLocks.size(); }
}
