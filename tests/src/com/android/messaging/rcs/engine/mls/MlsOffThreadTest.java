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
import java.util.concurrent.atomic.AtomicReference;

/** {@link MlsOffThread}, the seam tests use to run background work inline. */
public class MlsOffThreadTest {

    /**
     * DIRECT runs inline, so an assertion after the call sees the work done without a sleep or
     * latch.
     */
    @Test
    public void directRunsBeforeRunReturns() {
        final AtomicBoolean ran = new AtomicBoolean(false);
        MlsOffThread.DIRECT.run("t", new Runnable() {
            @Override public void run() { ran.set(true); }
        });
        assertTrue("DIRECT must run the body before returning", ran.get());
    }

    /** On the caller's thread; running at once on another thread would still race the assertion. */
    @Test
    public void directRunsOnTheCallersThread() {
        final AtomicReference<Thread> where = new AtomicReference<Thread>();
        MlsOffThread.DIRECT.run("t", new Runnable() {
            @Override public void run() { where.set(Thread.currentThread()); }
        });
        assertSame(Thread.currentThread(), where.get());
    }

    /** A throwing body propagates under DIRECT; swallowing it would turn a failing test green. */
    @Test
    public void directLetsAThrowingBodyReachTheTest() {
        try {
            MlsOffThread.DIRECT.run("t", new Runnable() {
                @Override public void run() { throw new IllegalStateException("boom"); }
            });
            fail("DIRECT must not swallow the body's exception");
        } catch (final IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }

    /**
     * REAL_THREADS goes off-thread under the given name, which identifies a stalled thread in a bug
     * report.
     */
    @Test
    public void realThreadsRunsElsewhereAndKeepsTheName() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Thread> where = new AtomicReference<Thread>();
        MlsOffThread.REAL_THREADS.run("mls-unit-probe", new Runnable() {
            @Override public void run() {
                where.set(Thread.currentThread());
                done.countDown();
            }
        });
        assertTrue("the body must run", done.await(5, TimeUnit.SECONDS));
        assertNotSame("REAL_THREADS must not run inline", Thread.currentThread(), where.get());
        assertEquals("mls-unit-probe", where.get().getName());
    }

    /** A null body is a caller bug; all three implementations ignore it. */
    @Test
    public void aNullBodyIsIgnoredEverywhere() {
        MlsOffThread.DIRECT.run("t", null);
        MlsOffThread.REAL_THREADS.run("t", null);
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        rec.run("t", null);
        rec.drain();                       // must not NPE on the recorded null
        assertEquals(0, rec.count());      // drain clears
    }

    /** Recording answers whether a path scheduled work without running it. */
    @Test
    public void recordingCapturesTheScheduleWithoutRunningIt() {
        final AtomicBoolean ran = new AtomicBoolean(false);
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        rec.run("mls-gate-flush", new Runnable() {
            @Override public void run() { ran.set(true); }
        });
        assertEquals(1, rec.count());
        assertEquals("mls-gate-flush", rec.names().get(0));
        assertFalse("Recording must NOT run the body", ran.get());
    }

    /** drain runs in scheduling order, then forgets. */
    @Test
    public void drainRunsInOrderThenClears() {
        final StringBuilder order = new StringBuilder();
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        for (final String tag : new String[] { "a", "b", "c" }) {
            final String t = tag;
            rec.run(t, new Runnable() {
                @Override public void run() { order.append(t); }
            });
        }
        assertEquals(3, rec.count());
        rec.drain();
        assertEquals("abc", order.toString());
        assertEquals("drain clears what it ran", 0, rec.count());
        rec.drain();
        assertEquals("a second drain is a no-op", "abc", order.toString());
    }

    /** names() is a copy. */
    @Test
    public void namesIsACopy() {
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        rec.run("x", new Runnable() { @Override public void run() { } });
        rec.names().clear();
        assertEquals(1, rec.count());
    }
}
