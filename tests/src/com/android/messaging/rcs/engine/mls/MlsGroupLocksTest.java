/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** {@link MlsGroupLocks}: one reentrant lock per (identity, group). */
public class MlsGroupLocksTest {

    private static final String ME = "+15551230000";
    private static final String OTHER_IDENTITY = "+15559990000";
    private static final String G1 = "group-1";
    private static final String G2 = "group-2";

    @Test
    public void sameKey_isTheSameLock() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        assertSame(locks.lockFor(ME, G1), locks.lockFor(ME, G1));
    }

    @Test
    public void differentGroups_getDifferentLocks() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        assertNotSame(locks.lockFor(ME, G1), locks.lockFor(ME, G2));
    }

    /**
     * The key carries the identity: the engine is per-identity, so one RCS group id under two
     * identities is two MLS groups, and sharing a lock would serialise unrelated work.
     */
    @Test
    public void sameGroupUnderTwoIdentities_getsTwoLocks() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        assertNotSame(locks.lockFor(ME, G1), locks.lockFor(OTHER_IDENTITY, G1));
    }

    /** One wedged conversation must not block another. */
    @Test
    public void aHeldLock_doesNotBlockAnotherGroup() throws Exception {
        final MlsGroupLocks locks = new MlsGroupLocks();
        locks.lockFor(ME, G1).lock();
        try {
            final AtomicBoolean got = new AtomicBoolean(false);
            final CountDownLatch done = new CountDownLatch(1);
            final Thread t = new Thread(() -> {
                final ReentrantLock other = locks.lockFor(ME, G2);
                if (other.tryLock()) {
                    got.set(true);
                    other.unlock();
                }
                done.countDown();
            });
            t.start();
            assertTrue("the other group's lock was never attempted",
                    done.await(5, TimeUnit.SECONDS));
            assertTrue("a lock on " + G1 + " blocked " + G2, got.get());
        } finally {
            locks.lockFor(ME, G1).unlock();
        }
    }

    @Test
    public void theSameGroup_isMutuallyExclusive() throws Exception {
        final MlsGroupLocks locks = new MlsGroupLocks();
        locks.lockFor(ME, G1).lock();
        try {
            final AtomicBoolean got = new AtomicBoolean(true);
            final CountDownLatch done = new CountDownLatch(1);
            final Thread t = new Thread(() -> {
                final ReentrantLock same = locks.lockFor(ME, G1);
                got.set(same.tryLock());
                if (same.isHeldByCurrentThread()) same.unlock();
                done.countDown();
            });
            t.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertFalse("two threads held one group's lock at once", got.get());
        } finally {
            locks.lockFor(ME, G1).unlock();
        }
    }

    /** Reentrant, so a helper called from inside a critical section does not deadlock on itself. */
    @Test
    public void reentry_doesNotDeadlock() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        locks.lockFor(ME, G1).lock();
        locks.lockFor(ME, G1).lock();
        assertTrue(locks.isHeldByCurrentThread(ME, G1));
        locks.lockFor(ME, G1).unlock();
        assertTrue("one unlock of a doubly-held lock must not release it",
                locks.isHeldByCurrentThread(ME, G1));
        locks.lockFor(ME, G1).unlock();
        assertFalse(locks.isHeldByCurrentThread(ME, G1));
    }

    /**
     * A lock held across transport I/O is invisible otherwise: the code still works, it just
     * serialises inbound behind our own outbound RPC.
     */
    @Test
    public void assertNoLockHeld_firesOnlyWhenThisThreadHoldsOne() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        locks.assertNoLockHeld("fetchMissedCommits");     // nothing held: no throw

        locks.lockFor(ME, G1).lock();
        try {
            locks.assertNoLockHeld("fetchMissedCommits");
            fail("expected the held lock to be reported");
        } catch (final IllegalStateException expected) {
            assertTrue("the message must name the operation: " + expected.getMessage(),
                    expected.getMessage().contains("fetchMissedCommits"));
            assertTrue("the message must name the key held: " + expected.getMessage(),
                    expected.getMessage().contains(G1));
        } finally {
            locks.lockFor(ME, G1).unlock();
        }
        locks.assertNoLockHeld("fetchMissedCommits");     // released again
    }

    /** The check is per-thread; another thread's lock does not trip it. */
    @Test
    public void anotherThreadsLock_doesNotTripTheAssertion() throws Exception {
        final MlsGroupLocks locks = new MlsGroupLocks();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread t = new Thread(() -> {
            locks.lockFor(ME, G1).lock();
            held.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            locks.lockFor(ME, G1).unlock();
        });
        t.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            locks.assertNoLockHeld("sendMlsCiphertext");   // must not throw
            assertFalse(locks.anyHeldByCurrentThread());
        } finally {
            release.countDown();
            t.join(5000);
        }
    }

    @Test
    public void heldByCurrentThread_listsEveryKey() {
        final MlsGroupLocks locks = new MlsGroupLocks();
        assertEquals("", locks.heldByCurrentThread());
        locks.lockFor(ME, G1).lock();
        locks.lockFor(ME, G2).lock();
        try {
            final String held = locks.heldByCurrentThread();
            assertTrue(held, held.contains(G1) && held.contains(G2));
        } finally {
            locks.lockFor(ME, G2).unlock();
            locks.lockFor(ME, G1).unlock();
        }
    }

    /** Null components must not collapse two distinct keys onto one lock. */
    @Test
    public void nullComponents_stillProduceDistinctKeys() {
        assertNotSame(MlsGroupLocks.key(null, G1), MlsGroupLocks.key(ME, G1));
        assertFalse(MlsGroupLocks.key(ME, null).equals(MlsGroupLocks.key(null, ME)));
        final MlsGroupLocks locks = new MlsGroupLocks();
        assertNotSame(locks.lockFor(null, G1), locks.lockFor(ME, G1));
        assertEquals(2, locks.size());
    }
}
