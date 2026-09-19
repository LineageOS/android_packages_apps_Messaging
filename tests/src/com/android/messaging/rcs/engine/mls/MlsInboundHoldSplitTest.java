/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.AnchorProbe;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsInboundHoldSplitTest {


    @Test
    public void parksCountOnlyWhileTheGroupStaysAtTheSameMoment() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("conv", cs);
        final MlsAppMessage.Moment a = new MlsAppMessage.Moment(1, 5);
        assertEquals(1, MlsInboundHold.noteParkAtUnchangedMoment(f.port(), "g:x", a));
        assertEquals(2, MlsInboundHold.noteParkAtUnchangedMoment(f.port(), "g:x",
                new MlsAppMessage.Moment(1, 5)));
        assertEquals("the group moved", 1, MlsInboundHold.noteParkAtUnchangedMoment(f.port(), "g:x",
                new MlsAppMessage.Moment(1, 6)));
        assertEquals("an unknown moment never counts as unchanged", 1,
                MlsInboundHold.noteParkAtUnchangedMoment(f.port(), "g:x", null));
    }


    @Test
    public void clearParksResetsTheCount() {
        final ConvState cs = new ConvState();
        cs.consecutiveParksAtSameMoment = 4;
        MlsInboundHold.clearParksAtUnchangedMoment(new FakeShellPort().returns("conv", cs).port(),
                "g:x");
        assertEquals(0, cs.consecutiveParksAtSameMoment);
    }


    @Test
    public void aFailedControlWithNoGroupOrNotFromTheFutureIsNotParked() {
        final FakeShellPort none = new FakeShellPort().returns("getGroup", null);
        MlsInboundHold.bufferFromFuture(none.port(), MlsLogSink.NONE, "g:grp", "m1", new byte[] {1},
                "+2");
        assertFalse(none.calls.stream().anyMatch(c -> c.startsWith("park(")));
        final FakeShellPort f = SplitFixtures.port(SplitFixtures.storeWith(b -> b));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsInboundHold.bufferFromFuture(f.port(), log, "g:grp", "m1", new byte[] {1}, "+2");
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("park(")));
        assertTrue(log.said("I", "is NOT strictly from the future"));
    }


    /** A PrivateMessage for the fixture group at {@code epoch}: all an epoch read looks at. */
    private static byte[] ciphertextAt(final long epoch) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x00); o.write(0x01);
        o.write(0x00); o.write(MlsWireScan.WF_PRIVATE_MESSAGE);
        o.write(GID.length); o.write(GID, 0, GID.length);
        for (int i = 7; i >= 0; i--) o.write((int) ((epoch >>> (i * 8)) & 0xFF));
        o.write(1); o.write(0xCC);
        return o.toByteArray();
    }

    private static byte[] ee(final int era, final long epoch) {
        final byte[] b = new byte[12];
        b[0] = (byte) (era >>> 24); b[1] = (byte) (era >>> 16); b[2] = (byte) (era >>> 8); b[3] =
                (byte) era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (epoch >>> (56 - 8 * i));
        return b;
    }

    /** The fixture group at era 0 epoch 3, in the given health, with a park that records. */
    private static FakeShellPort parkPort(final int health, final ConvState cs) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b.healthStatus(health)))
                .returns("conv", cs).on("park", a -> null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(0, 3)));
        return f;
    }

    private static boolean parked(final FakeShellPort f) {
        return f.calls.stream().anyMatch(c -> c.startsWith("park("));
    }

    @Test
    public void onlyACiphertextFromOurFutureIsParkedAndAnUnreachableEraSaysSo() {
        final FakeShellPort f = parkPort(MlsHealthStates.UNKNOWN, new ConvState());
        assertTrue(MlsInboundHold.parkFutureCiphertext(f.port(), MlsLogSink.NONE, KEY, "m1",
                ciphertextAt(5), -1L, "grp", "+2"));
        assertTrue(parked(f));
        final FakeShellPort now = parkPort(MlsHealthStates.UNKNOWN, new ConvState());
        assertFalse(MlsInboundHold.parkFutureCiphertext(now.port(), MlsLogSink.NONE, KEY, "m2",
                ciphertextAt(3), -1L, "grp", "+2"));
        assertFalse("our own epoch is not the future", parked(now));
        final FakeShellPort nextEra = parkPort(MlsHealthStates.UNKNOWN, new ConvState());
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsInboundHold.parkFutureCiphertext(nextEra.port(), log, KEY, "m3",
                ciphertextAt(5), 1L, "grp", "+2"));
        assertTrue("held, but a later era is not reached by waiting", parked(nextEra));
    }


    @Test
    public void aLockedGroupParksInboundButNotAMessageAtItsOwnEpoch() {
        final FakeShellPort open = parkPort(MlsHealthStates.UNKNOWN, new ConvState());
        assertFalse(MlsInboundHold.bufferInboundIfGroupLocked(open.port(), MlsLogSink.NONE, KEY,
                "m1", ciphertextAt(5), "+2"));
        assertFalse(parked(open));
        final FakeShellPort locked =
                parkPort(MlsHealthStates.ERAADVANCEMENTREQUESTED, new ConvState());
        assertTrue(MlsInboundHold.bufferInboundIfGroupLocked(locked.port(), MlsLogSink.NONE, KEY,
                "m2", ciphertextAt(5), "+2"));
        assertTrue(locked.calls.stream()
                .anyMatch(c -> c.startsWith("park(") && c.contains("GROUP_LOCKED")));
        final FakeShellPort same =
                parkPort(MlsHealthStates.ERAADVANCEMENTREQUESTED, new ConvState());
        assertFalse("a message at our own epoch means the group is not waiting on anything",
                MlsInboundHold.bufferInboundIfGroupLocked(same.port(), MlsLogSink.NONE, KEY, "m3",
                        ciphertextAt(3), "+2"));
        assertFalse(parked(same));
    }


    /**
     * A port whose RCC.16 §10.8 queue for {@code grp} holds {@code entries}, all at the group's
     * moment.
     */
    static FakeShellPort drainPort(final MlsPendingQueue.Entry... entries) {
        final MlsPendingQueue q = new MlsPendingQueue();
        for (final MlsPendingQueue.Entry e : entries) assertTrue(q.store(e, GID).stored());
        final ConvState cs = new ConvState();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("convIfAny", cs)
                .returns("applyInboundControl", true).on("replayParkedApplication", a -> null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class, "load", q, "store", "ok"));
        return f;
    }

    private static MlsPendingQueue.Entry parked(final String id,
            final MlsPendingQueue.Plane plane) {
        return new MlsPendingQueue.Entry(id, new MlsAppMessage.Moment(0, 0L), 1, GID,
                new byte[] {1}, plane);
    }

    @Test
    public void theDrainRoutesEachParkedMessageByItsPlaneInsideARetryFlow() {
        final FakeShellPort f = drainPort(parked("c1", MlsPendingQueue.Plane.CONTROL),
                parked("a1", MlsPendingQueue.Plane.APPLICATION));
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("applyInboundControl(+2, c1,")));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("replayParkedApplication(g:grp, +2, grp,")));
        assertTrue(f.calls.contains("MlsPendingQueueAccess.store"));
        assertEquals(MlsSchedulingType.NORMAL, MlsDriveLoop.schedulingFor(f.port(), KEY));
    }

    @Test
    public void anEmptyQueueWritesNothing() {
        final FakeShellPort f = drainPort();
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        assertFalse(f.calls.contains("MlsPendingQueueAccess.store"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyInboundControl(")));
    }


    /**
     * A port at era 2 epoch 0 whose queue holds {@code q}, recording the RCC.16 §7.7.2.2 reports
     * queued in {@code cs} and the flushes.
     */
    private static FakeShellPort eraTwoPort(final MlsPendingQueue q, final ConvState cs) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("convIfAny", cs).on("flushFtdReports", a -> null)
                .returns("applyInboundControl", true).on("replayParkedApplication", a -> null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(2, 0)));
        f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class, "load", q, "store", "ok"));
        return f;
    }

    private static MlsPendingQueue.Entry at(final String id, final int era, final long epoch,
            final MlsPendingQueue.Plane plane, final String sender) {
        return new MlsPendingQueue.Entry(id, new MlsAppMessage.Moment(era, epoch), 2, GID,
                new byte[] {1}, plane, sender);
    }

    @Test
    public void theDrainDropsEarlierErasAndReportsTheirApplicationMessagesToTheirSenders() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(at("old-app", 1, 7, MlsPendingQueue.Plane.APPLICATION, "+3"), GID)
                .stored());
        assertTrue(q.store(at("old-ctl", 1, 7, MlsPendingQueue.Plane.CONTROL, "+2"), GID).stored());
        assertTrue(q.store(at("now", 2, 0, MlsPendingQueue.Plane.CONTROL, "+2"), GID).stored());
        assertTrue(q.store(at("later", 2, 4, MlsPendingQueue.Plane.APPLICATION, "+2"), GID)
                .stored());
        final ConvState cs = new ConvState();
        final FakeShellPort f = eraTwoPort(q, cs);
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        assertEquals("only the application message from era 1 is reported, to the member who "
                + "sent it rather than the one whose commit triggered the drain",
                java.util.Collections.singletonMap("old-app", "+3"), cs.ftdPending);
        assertTrue(f.calls.contains("flushFtdReports(grp, +3)"));
        assertTrue("the current bucket is still replayed",
                f.calls.stream().anyMatch(c -> c.startsWith("applyInboundControl(+2, now,")));
        assertEquals("era 2's later bucket stays parked", java.util.Collections.singleton(2),
                q.eras());
        assertEquals(1, q.size());
        assertTrue("the prune is written back before anything is reported",
                f.calls.indexOf("MlsPendingQueueAccess.store")
                        < f.calls.indexOf("flushFtdReports(grp, +3)"));
    }

    @Test
    public void aSupersededEraAloneIsStillWrittenBackAndReported() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(at("old", 1, 3, MlsPendingQueue.Plane.APPLICATION, "+2"), GID)
                .stored());
        final ConvState cs = new ConvState();
        final FakeShellPort f = eraTwoPort(q, cs);
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        assertTrue(q.isEmpty());
        assertTrue(f.calls.contains("MlsPendingQueueAccess.store"));
        assertEquals("+2", cs.ftdPending.get("old"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyInboundControl(")));
    }

    @Test
    public void pruneAfterOurOwnAdvanceDropsOnlyEarlierErasUnderTheLock() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(at("old", 1, 3, MlsPendingQueue.Plane.APPLICATION, "+3"), GID)
                .stored());
        assertTrue(q.store(at("cur", 2, 5, MlsPendingQueue.Plane.APPLICATION, "+3"), GID)
                .stored());
        final ConvState cs = new ConvState();
        final FakeShellPort f = eraTwoPort(q, cs);
        assertEquals(1, MlsInboundHold.pruneSupersededEras(f.port(), MlsLogSink.NONE, KEY, "grp"));
        assertEquals(java.util.Collections.singletonMap("old", "+3"), cs.ftdPending);
        assertEquals("nothing at the current era is replayed or dropped", 1, q.size());
        final int lock = f.calls.indexOf("lock(" + KEY + ")");
        final int store = f.calls.indexOf("MlsPendingQueueAccess.store");
        final int unlock = f.calls.indexOf("unlock(" + KEY + ")");
        assertTrue("load and store happen inside the conversation lock",
                lock >= 0 && lock < store && store < unlock);
        assertTrue("the report goes out after the lock is released",
                unlock < f.calls.indexOf("flushFtdReports(grp, +3)"));

        final FakeShellPort none = eraTwoPort(new MlsPendingQueue(), new ConvState());
        assertEquals(0, MlsInboundHold.pruneSupersededEras(none.port(), MlsLogSink.NONE, KEY,
                "grp"));
        assertFalse("nothing to prune writes nothing",
                none.calls.contains("MlsPendingQueueAccess.store"));
        assertFalse(none.calls.stream().anyMatch(c -> c.startsWith("flushFtdReports(")));
    }

    @Test
    public void aDroppedEntryWithNoRecordedSenderIsReportedOnlyInAOneToOne() {
        final java.util.List<MlsPendingQueue.Entry> legacy = java.util.Arrays.asList(
                at("a", 1, 1, MlsPendingQueue.Plane.APPLICATION, null),
                at("c", 1, 1, MlsPendingQueue.Plane.CONTROL, null));
        final ConvState dm = new ConvState();
        final FakeShellPort one = new FakeShellPort().returns("conv", dm)
                .on("flushFtdReports", a -> null);
        assertEquals(1, MlsInboundHold.reportDropped(one.port(), MlsLogSink.NONE, "p:+2", null,
                legacy, "test"));
        assertEquals("in a 1:1 the peer is the sender",
                java.util.Collections.singletonMap("a", "+2"), dm.ftdPending);
        assertTrue(one.calls.contains("flushFtdReports(null, +2)"));

        final ConvState group = new ConvState();
        final FakeShellPort g = new FakeShellPort().returns("conv", group);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(0, MlsInboundHold.reportDropped(g.port(), log, KEY, "grp", legacy, "test"));
        assertTrue("a group report to a guessed member is not sent", group.ftdPending.isEmpty());
        assertFalse(g.calls.stream().anyMatch(c -> c.startsWith("flushFtdReports(")));
        assertTrue(log.said("I", "1 with no recorded sender"));
    }

    @Test
    public void aParkedEntryRecordsItsSender() {
        final MlsPendingQueue q = new MlsPendingQueue();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class, "load", q, "store", "ok"));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(0, 3)));
        MlsInboundHold.park(f.port(), MlsLogSink.NONE, KEY, "a1", ciphertextAt(4),
                new MlsAppMessage.Moment(0, 4L), MlsPendingQueue.Admission.FROM_FUTURE, "+3",
                MlsPendingQueue.Plane.APPLICATION);
        assertEquals("+3", q.peekAll().get(0).sender);
    }

    @Test
    public void aParkedMessageIsStoredOnceAtItsMoment() {
        final MlsPendingQueue q = new MlsPendingQueue();
        final FakeShellPort f = SplitFixtures.port(
                storeWith(b -> b.healthStatus(MlsHealthStates.ERAADVANCEMENTREQUESTED)));
        f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class, "load", q, "store", "ok"));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(0, 3)));
        final byte[] publicMessage = {0x00, 0x01, 0x00, 0x01, 9};
        final MlsAppMessage.Moment at = new MlsAppMessage.Moment(0, 3L);
        final MlsPendingQueue.Admission why = MlsPendingQueue.Admission.values()[0];
        MlsInboundHold.park(f.port(), MlsLogSink.NONE, KEY, "c1", publicMessage, at, why, "+2",
                MlsPendingQueue.Plane.CONTROL);
        assertEquals(1, q.size());
        assertTrue(f.calls.contains("MlsPendingQueueAccess.store"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsInboundHold.park(f.port(), log, KEY, "c1", publicMessage, at, why, "+2",
                MlsPendingQueue.Plane.CONTROL);
        assertEquals(1, q.size());
        assertTrue(log.said("I", MlsPendingQueue.duplicateLine("c1")));
    }

    /**
     * A port over a real conversation lock, a group moment the test moves, and a pending queue
     * that round-trips through its codec as the durable store does. Records each replay as
     * {@code id<-sender}.
     */
    private static final class Live {
        final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();
        final java.util.concurrent.atomic.AtomicReference<byte[]> moment;
        final java.util.concurrent.atomic.AtomicReference<byte[]> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List<String> replayed =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final FakeShellPort f;
        final MlsShellPort port;

        Live(final int health, final int era, final long epoch) {
            moment = new java.util.concurrent.atomic.AtomicReference<>(ee(era, epoch));
            final ConvState cs = new ConvState();
            f = SplitFixtures.port(storeWith(b -> b.healthStatus(health))).returns("conv", cs)
                    .returns("convIfAny", cs).returns("log", MlsLogSink.NONE).real("park")
                    .on("lock", a -> { lock.lock(); return null; })
                    .on("unlock", a -> { lock.unlock(); return null; })
                    .returns("applyInboundControl", true)
                    .on("replayParkedApplication", a -> {
                        replayed.add(((MlsPendingQueue.Entry) a[3]).messageId + "<-" + a[1]);
                        return null;
                    });
            f.returns("session", f.stub(MlsSession.class, "eraEpoch",
                    (Function<Object[], Object>) a -> moment.get()));
            f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class,
                    "load", (Function<Object[], Object>) a -> MlsPendingQueue.fromBytes(
                            stored.get()),
                    "store", (Function<Object[], Object>) a -> {
                        stored.set(((MlsPendingQueue) a[2]).toBytes());
                        return null;
                    }));
            port = f.port();
        }

        MlsPendingQueue queue() { return MlsPendingQueue.fromBytes(stored.get()); }
    }

    /**
     * Seen on a device: the application thread decides to park a message for epoch 4 while the
     * group is at 3, then waits for the conversation lock, which the commit thread holds from
     * applying 3 -> 4 through its exact-key drain of 4. The drain finds nothing; the park then
     * stores the message at 4, where no drain will ever look. It must be delivered, once.
     */
    @Test
    public void aParkThatWaitsOutTheCommitsDrainIsReplayedInsteadOfStranded() throws Exception {
        final Live live = new Live(MlsHealthStates.UNKNOWN, 0, 3);
        final Thread[] parker = {null};
        final java.util.concurrent.CountDownLatch atTheLock =
                new java.util.concurrent.CountDownLatch(1);
        live.f.on("lock", a -> {
            if (Thread.currentThread() == parker[0]) atTheLock.countDown();
            live.lock.lock();
            return null;
        });
        final boolean[] handled = {false};
        final Throwable[] thrown = {null};
        // The commit's thread: it holds the conversation lock from processing to its drain.
        live.lock.lock();
        try {
            parker[0] = new Thread(() -> {
                try {
                    handled[0] = MlsInboundHold.parkFutureCiphertext(live.port, MlsLogSink.NONE,
                            KEY, "m4", ciphertextAt(4), -1L, "grp", "+2");
                } catch (final Throwable t) {
                    thrown[0] = t;
                }
            });
            parker[0].start();
            assertTrue("the park never reached the conversation lock",
                    atTheLock.await(10, java.util.concurrent.TimeUnit.SECONDS));
            live.moment.set(ee(0, 4));
            MlsInboundHold.drainDeferredControl(live.port, MlsLogSink.NONE, KEY, "+2", "grp");
            assertTrue("the drain ran before the park stored, so it has nothing to take",
                    live.replayed.isEmpty());
        } finally {
            live.lock.unlock();
        }
        parker[0].join(10_000L);
        assertFalse("the park is still blocked", parker[0].isAlive());
        assertNull(thrown[0]);
        assertTrue("an application message the group can now read must not take the FTD path",
                handled[0]);
        assertEquals("the message was stranded at a moment the group has already drained",
                java.util.Collections.singletonList("m4<-+2"), live.replayed);
        assertTrue("an entry was left parked where no drain will look: " + live.queue(),
                live.queue().isEmpty());
    }

    /**
     * The other half of the same window: the commit lands, and drains, between the failed decrypt
     * and the park decision, so the decision reads a group that is already at the message's
     * moment. The decrypt's own moment decides.
     */
    @Test
    public void aCommitLandingBeforeTheParkDecisionStillDeliversTheMessage() {
        final Live live = new Live(MlsHealthStates.UNKNOWN, 0, 4);
        MlsInboundHold.noteDecryptFailure(live.port, KEY, "m4", new MlsAppMessage.Moment(0, 3));
        assertTrue("a message that failed at epoch 3 and is readable at 4 was sent to the FTD path",
                MlsInboundHold.parkFutureCiphertext(live.port, MlsLogSink.NONE, KEY, "m4",
                        ciphertextAt(4), -1L, "grp", "+2"));
        assertEquals(java.util.Collections.singletonList("m4<-+2"), live.replayed);
        assertTrue(live.queue().isEmpty());
        assertFalse("the recorded moment is taken once: a second failure at 4 is not the future",
                MlsInboundHold.parkFutureCiphertext(live.port, MlsLogSink.NONE, KEY, "m4",
                        ciphertextAt(4), -1L, "grp", "+2"));
        assertEquals(1, live.replayed.size());
    }

    /**
     * The moment {@link #aCommitLandingBeforeTheParkDecisionStillDeliversTheMessage} relies on is
     * recorded by the decrypt's failure branch, before it returns and releases the lock.
     */
    @Test
    public void theFailedDecryptRecordsTheMomentItSaw() throws java.io.IOException {
        final String body = com.android.messaging.rcs.SourceScan.bodyOf(
                com.android.messaging.rcs.SourceScan.codeOnly(com.android.messaging.rcs.SourceScan
                        .read("engine/src/com/android/messaging/rcs/engine/mls/"
                                + "MlsInboundDecrypt.java")), "decryptInbound");
        final int failed = body.indexOf("if (plain == null)");
        assertTrue("decryptInbound's failure branch is gone", failed > 0);
        final int note = body.indexOf("MlsInboundHold.noteDecryptFailure(", failed);
        assertTrue("the failed decrypt no longer records its moment before returning, so a commit "
                + "landing before the park decision sends a readable message to the FTD path",
                note > failed && note < body.indexOf("return null;", failed));
    }

    /** A message still ahead of the group when the park takes the lock is stored as before. */
    @Test
    public void aParkStillAheadOfTheGroupIsStoredAndDrainedOnce() {
        final Live live = new Live(MlsHealthStates.UNKNOWN, 0, 3);
        assertTrue(MlsInboundHold.parkFutureCiphertext(live.port, MlsLogSink.NONE, KEY, "m4",
                ciphertextAt(4), -1L, "grp", "+2"));
        assertEquals(1, live.queue().size());
        assertTrue(live.replayed.isEmpty());
        live.moment.set(ee(0, 4));
        MlsInboundHold.drainDeferredControl(live.port, MlsLogSink.NONE, KEY, "+3", "grp");
        MlsInboundHold.drainDeferredControl(live.port, MlsLogSink.NONE, KEY, "+3", "grp");
        assertEquals("replayed once, as the member who sent it",
                java.util.Collections.singletonList("m4<-+2"), live.replayed);
        assertTrue(live.queue().isEmpty());
    }

    /**
     * The G1 twin: a busy group's control is filed at the moment read before the lock. If a commit
     * moved the group while the park waited, that moment is behind the drain for good.
     */
    @Test
    public void aBusyGroupParkOvertakenByACommitIsProcessedInline() {
        final Live live = new Live(MlsHealthStates.ERAADVANCEMENTREQUESTED, 0, 3);
        live.f.on("lock", a -> {
            live.moment.set(ee(0, 4));
            live.lock.lock();
            return null;
        });
        assertFalse("a control filed at a moment the group left would never be replayed",
                MlsInboundHold.bufferInboundIfGroupLocked(live.port, MlsLogSink.NONE, KEY, "c5",
                        ciphertextAt(5), "+2"));
        assertTrue(live.queue().isEmpty());

        final Live still = new Live(MlsHealthStates.ERAADVANCEMENTREQUESTED, 0, 3);
        assertTrue("an unmoved busy group still parks",
                MlsInboundHold.bufferInboundIfGroupLocked(still.port, MlsLogSink.NONE, KEY, "c5",
                        ciphertextAt(5), "+2"));
        assertEquals(1, still.queue().size());
    }

    /**
     * The drain runs on whichever member's commit arrived; each parked message is replayed as its
     * recorded sender, since the application door refuses a signer that is not the named sender.
     */
    @Test
    public void theDrainReplaysEachEntryAsItsRecordedSender() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(at("a", 2, 0, MlsPendingQueue.Plane.APPLICATION, "+3"), GID).stored());
        assertTrue(q.store(at("c", 2, 0, MlsPendingQueue.Plane.CONTROL, "+3"), GID).stored());
        assertTrue(q.store(at("legacy", 2, 0, MlsPendingQueue.Plane.APPLICATION, null), GID)
                .stored());
        final FakeShellPort f = eraTwoPort(q, new ConvState());
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("replayParkedApplication(g:grp, +3, grp,")));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("applyInboundControl(+3, c,")));
        assertEquals("only an entry with no recorded sender falls back to the drainer", 1,
                f.calls.stream()
                        .filter(c -> c.startsWith("replayParkedApplication(g:grp, +2, grp,"))
                        .count());
    }

    /**
     * The drain's load-take-store is under the conversation lock, as park()'s is: the Welcome and
     * downgrade callers do not hold it, and an unguarded write-back would erase a concurrent park.
     */
    @Test
    public void theDrainTakesTheQueueUnderTheLockAndReplaysAfterReleasingIt() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(at("a", 2, 0, MlsPendingQueue.Plane.APPLICATION, "+3"), GID).stored());
        final FakeShellPort f = eraTwoPort(q, new ConvState());
        MlsInboundHold.drainDeferredControl(f.port(), MlsLogSink.NONE, KEY, "+2", "grp");
        final int lock = f.calls.indexOf("lock(" + KEY + ")");
        final int load = f.calls.indexOf("MlsPendingQueueAccess.load");
        final int store = f.calls.indexOf("MlsPendingQueueAccess.store");
        final int unlock = f.calls.indexOf("unlock(" + KEY + ")");
        int replay = -1;
        for (int i = 0; i < f.calls.size(); i++) {
            if (f.calls.get(i).startsWith("replayParkedApplication(")) { replay = i; break; }
        }
        assertTrue("load and store are not inside the conversation lock: " + f.calls,
                lock >= 0 && lock < load && load < store && store < unlock);
        assertTrue("the replay runs after the lock is released", unlock < replay);
    }
}
