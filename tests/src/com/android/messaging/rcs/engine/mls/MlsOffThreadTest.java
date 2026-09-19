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

/** The off-thread seam — the properties a test harness relies on. */
public class MlsOffThreadTest {

    // ==================== THE ONE THAT MATTERS ====================

    /**
     * <b>DIRECT runs inline.</b> This is the entire reason the seam exists: an assertion written
     * after the call must see the work already done, with no sleep, no latch and no flake.
     */
    @Test
    public void directRunsBeforeRunReturns() {
        final AtomicBoolean ran = new AtomicBoolean(false);
        MlsOffThread.DIRECT.run("t", new Runnable() {
            @Override public void run() { ran.set(true); }
        });
        assertTrue("DIRECT must run the body before returning", ran.get());
    }

    /**
     * …and it runs on the CALLER's thread, which is what makes an inline assertion sound. Running
     * "immediately" on some other thread would still race with the assertion.
     */
    @Test
    public void directRunsOnTheCallersThread() {
        final AtomicReference<Thread> where = new AtomicReference<Thread>();
        MlsOffThread.DIRECT.run("t", new Runnable() {
            @Override public void run() { where.set(Thread.currentThread()); }
        });
        assertSame(Thread.currentThread(), where.get());
    }

    /**
     * A THROWING BODY MUST PROPAGATE under DIRECT.
     *
     * <p>Swallowing it would turn a failing test green — strictly worse than having no test — and
     * that is precisely the failure this seam is meant to prevent, since an exception on a real
     * background thread is exactly what a device-only check cannot see.
     */
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

    // ==================== production default ====================

    /**
     * REAL_THREADS actually goes off-thread, and carries the name it was given. The name is not
     * decoration: it is what identifies a stalled MLS thread in a bug report.
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

    // ==================== null safety ====================

    /** A null body is a caller bug, not a crash in background machinery. All three tolerate it. */
    @Test
    public void aNullBodyIsIgnoredEverywhere() {
        MlsOffThread.DIRECT.run("t", null);
        MlsOffThread.REAL_THREADS.run("t", null);
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        rec.run("t", null);
        rec.drain();                       // must not NPE on the recorded null
        assertEquals(0, rec.count());      // drain clears
    }

    // ==================== Recording ====================

    /**
     * Recording answers "did the trigger fire", WITHOUT running the work — the distinction that
     * matters when the question is whether a path scheduled recovery, not what recovery did.
     */
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

    /** …and drain runs them in scheduling order, then forgets them. */
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

    /** names() is a copy — a caller must not be able to mutate the recorder's state through it. */
    @Test
    public void namesIsACopy() {
        final MlsOffThread.Recording rec = new MlsOffThread.Recording();
        rec.run("x", new Runnable() { @Override public void run() { } });
        rec.names().clear();
        assertEquals(1, rec.count());
    }
}
