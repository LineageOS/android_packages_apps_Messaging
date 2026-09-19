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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per {@code (identity, group)}, and an enforced rule about where it may be held.
 *
 * <p>The rule, in full:
 *
 * <blockquote><b>One lock per {@code (identity, group)}. Held across the engine call AND its
 * storage write. NEVER held across transport I/O.</b></blockquote>
 *
 * <p>What this replaces: {@code MlsProviderTransport} was a process singleton with ~25 entry points
 * {@code synchronized} on it, so ONE monitor covered every group and every identity in the process —
 * and that monitor was held across binder hops into the provider that issue gRPC. The code already
 * showed the symptom: the automatic self-heal detached onto its own thread with the comment "we are
 * inside it holding the lock", i.e. we escaped our own global lock by running away from it, which
 * loses exactly the serialisation the lock existed to provide.
 *
 * <h2>Why the third clause is enforced and not documented</h2>
 *
 * <p>"Never hold the lock across transport I/O" is the kind of rule that is true on the day it is
 * written and quietly false four refactors later, because the violation is invisible: the code still
 * works, it just serialises every inbound commit for a conversation behind that conversation's own
 * outbound RPC — and the recovery paths, which do the most I/O, are precisely the ones where inbound
 * progress matters most. So {@link #assertNoLockHeld} makes it checkable, and the transport call
 * sites assert. A held lock at an I/O boundary is a programming error, and it says which key.
 *
 * <p>Locks are {@link ReentrantLock}s so that a helper called from inside a critical section can
 * re-enter without deadlocking, and so {@link #isHeldByCurrentThread} can answer the assertion
 * precisely rather than approximately.
 *
 * <p>Entries are never evicted. A lock is ~48 bytes and the key set is bounded by the number of
 * conversations the device has ever had an MLS group for; evicting one is how you hand two threads
 * different monitors for the same key.
 */
public final class MlsGroupLocks {

    private final Map<String, ReentrantLock> mLocks = new ConcurrentHashMap<>();

    /**
     * The lock for one {@code (identity, group)}.
     *
     * <p>The key must include the identity and not just the group: the engine is per-identity, so
     * the same RCS group id under two identities is two different MLS groups with two different
     * storage directories.
     */
    public ReentrantLock lockFor(final String identity, final String group) {
        return mLocks.computeIfAbsent(key(identity, group), k -> new ReentrantLock());
    }

    /** The canonical key. Separator chosen so neither an E.164 nor a group id can contain it. */
    public static String key(final String identity, final String group) {
        return (identity == null ? "" : identity) + "\0" + (group == null ? "" : group);
    }

    /** True iff the calling thread holds the lock for this {@code (identity, group)}. */
    public boolean isHeldByCurrentThread(final String identity, final String group) {
        final ReentrantLock l = mLocks.get(key(identity, group));
        return l != null && l.isHeldByCurrentThread();
    }

    /** True iff the calling thread holds ANY group lock. The transport-boundary question. */
    public boolean anyHeldByCurrentThread() {
        for (final ReentrantLock l : mLocks.values()) {
            if (l.isHeldByCurrentThread()) return true;
        }
        return false;
    }

    /** Every key the calling thread currently holds, for an assertion message worth reading. */
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
     * Fail loudly if the calling thread is about to do transport I/O while holding a group lock.
     *
     * <p>Call at every binder/RPC boundary. This is the third clause of the model made checkable —
     * see the class doc for why a comment is not enough.
     *
     * @param what the operation about to be attempted, for the message
     * @throws IllegalStateException if any group lock is held by this thread
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
