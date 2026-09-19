/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsSendReadiness;
import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A send that starts the process runs before the transport reports its state. Seen on a device:
 * prov=1 at +2 ms, the send decided at +150 ms, prov=3 reg=2 at +264 ms, and the message went out
 * as SMS; the same send in a warm process went RCS. The send waits, bounded, while the state is
 * settling, and not at all when RCS is known to be unavailable.
 */
public class RcsSendReadinessTest {

    private static final int AVAILABLE = RcsSendReadiness.AVAILABLE;
    private static final int UNAVAILABLE = RcsSendReadiness.UNAVAILABLE;
    private static final int SETTLING = RcsSendReadiness.SETTLING;

    @Test
    public void aStateStillArrivingIsSettlingAndAKnownOneIsNot() {
        // enabled, selecting, up, settling
        assertEquals("cold process, provisioning in progress", SETTLING,
                RcsSendReadiness.classify(true, true, false, true));
        assertEquals(AVAILABLE, RcsSendReadiness.classify(true, true, true, false));
        assertEquals("up wins even with nothing selecting", AVAILABLE,
                RcsSendReadiness.classify(true, false, true, false));
        assertEquals("the toggle is off", UNAVAILABLE,
                RcsSendReadiness.classify(false, true, true, false));
        assertEquals("the toggle is off, still settling", UNAVAILABLE,
                RcsSendReadiness.classify(false, true, false, true));
        assertEquals("no transport can be selected", UNAVAILABLE,
                RcsSendReadiness.classify(true, false, false, true));
        assertEquals("a reported failure, OTP or ToS wait", UNAVAILABLE,
                RcsSendReadiness.classify(true, true, false, false));
    }

    /** That timeline: settling at the send, up a little later. The send goes RCS. */
    @Test
    public void aSendThatStartedTheProcessWaitsForTheStateAndGoesRcs() throws Exception {
        final AtomicInteger state = new AtomicInteger(SETTLING);
        final Object monitor = new Object();
        final Thread provider = new Thread(() -> {
            sleep(150);
            state.set(AVAILABLE);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        });
        final long start = System.nanoTime();
        provider.start();
        final boolean rcs = RcsSendReadiness.await(state::get, monitor,
                RcsSendReadiness.MAX_WAIT_MS);
        final long ms = (System.nanoTime() - start) / 1_000_000L;
        provider.join();
        assertTrue("the send routed SMS although RCS came up " + ms + " ms later", rcs);
        assertTrue("it waited " + ms + " ms, longer than the state took", ms < 1_500L);
    }

    @Test
    public void aStateChangeWithoutANotifyIsStillSeen() throws Exception {
        final AtomicInteger state = new AtomicInteger(SETTLING);
        final Thread provider = new Thread(() -> {
            sleep(120);
            state.set(AVAILABLE);
        });
        provider.start();
        assertTrue(RcsSendReadiness.await(state::get, new Object(), RcsSendReadiness.MAX_WAIT_MS));
        provider.join();
    }

    @Test
    public void aKnownUnavailableStateDoesNotWait() {
        final long start = System.nanoTime();
        assertFalse(RcsSendReadiness.await(() -> UNAVAILABLE, new Object(),
                RcsSendReadiness.MAX_WAIT_MS));
        final long ms = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("an SMS send was held " + ms + " ms", ms < 500L);
    }

    @Test
    public void aStateThatNeverSettlesGivesUpAtTheBound() {
        final long start = System.nanoTime();
        assertFalse(RcsSendReadiness.await(() -> SETTLING, new Object(), 200L));
        final long ms = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("gave up after " + ms + " ms", ms >= 190L && ms < 1_000L);
        assertTrue("the bound is short: the send path holds an action thread",
                RcsSendReadiness.MAX_WAIT_MS <= 5_000L);
    }

    @Test
    public void aStateThatGoesDownWhileWaitingEndsTheWait() throws Exception {
        final AtomicInteger state = new AtomicInteger(SETTLING);
        final Thread provider = new Thread(() -> {
            sleep(100);
            state.set(UNAVAILABLE);
        });
        final long start = System.nanoTime();
        provider.start();
        assertFalse(RcsSendReadiness.await(state::get, new Object(),
                RcsSendReadiness.MAX_WAIT_MS));
        provider.join();
        assertTrue((System.nanoTime() - start) / 1_000_000L < 1_500L);
    }

    /**
     * The send actions wait; the non-waiting test is for the UI, which must not block. Every RCS
     * arm of InsertNewMessageAction and the location send go through the waiting form.
     */
    @Test
    public void theSendActionsDecideThroughTheWait() throws IOException {
        for (final String f : new String[] {"InsertNewMessageAction", "SendRcsLocationAction"}) {
            final String code = SourceScan.codeOnly(SourceScan.read(
                    "src/com/android/messaging/datamodel/action/" + f + ".java"));
            assertEquals(f + " decides the RCS route without waiting for the state", 0,
                    SourceScan.count(code, "isRcsAvailableForSub(")
                            + SourceScan.count(code, "isGroupRcsAvailableForSub("));
            assertTrue(f + ": no waiting decision found",
                    SourceScan.count(code, "RcsAvailableForSub(") > 0);
        }
        final String insert = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java"));
        assertEquals("InsertNewMessageAction: an RCS arm lost its waiting decision", 4,
                SourceScan.count(insert, ".awaitRcsAvailableForSub(")
                        + SourceScan.count(insert, ".awaitGroupRcsAvailableForSub("));
        // The 1:1 text arm reads the selected transport after the wait, which commits it.
        final String text = SourceScan.bodyOf(insert, "tryInsertSendingRcsMessage");
        final int wait = text.indexOf(".awaitRcsAvailableForSub(");
        final int active = text.indexOf(".getActiveTransport(");
        assertTrue("tryInsertSendingRcsMessage reads the active transport before the wait",
                wait >= 0 && active > wait);
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
