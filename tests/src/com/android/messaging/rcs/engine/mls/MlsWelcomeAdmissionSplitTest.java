/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.SourceScan;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
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

public final class MlsWelcomeAdmissionSplitTest {


    @Test
    public void serverStateCheckForComparesEpochAuthenticatorsAndSeparatesARefusalFromUnknown() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .on("lock", a -> null).on("unlock", a -> null);
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1, 2}));
        f.returns("lookServerEpochAuthenticator", Look.asked(new byte[] {1, 2}));
        assertEquals(MlsWelcomeAdmission.ServerState.MATCHES,
                MlsWelcomeAdmission.serverStateCheckFor(
                f.port(), MlsFetchLedger.Caller.values()[0], "grp", null, new byte[] {9}));
        f.returns("lookServerEpochAuthenticator", Look.asked(new byte[] {3}));
        assertEquals(MlsWelcomeAdmission.ServerState.DIFFERS,
                MlsWelcomeAdmission.serverStateCheckFor(
                f.port(), MlsFetchLedger.Caller.values()[0], "grp", null, new byte[] {9}));
        f.returns("lookServerEpochAuthenticator", Look.refusedByLedger("no"));
        assertEquals(MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER,
                MlsWelcomeAdmission.serverStateCheckFor(f.port(), MlsFetchLedger.Caller.values()[0],
                        "grp", null, new byte[] {9}));
        assertTrue("the lock is taken around the engine read and released",
                f.calls.indexOf("lock(g:grp)") < f.calls.indexOf("unlock(g:grp)"));
        assertEquals(MlsWelcomeAdmission.ServerState.UNKNOWN,
                MlsWelcomeAdmission.serverStateCheckFor(
                new FakeShellPort().returns("ensureSession", false).port(),
                MlsFetchLedger.Caller.values()[0], "grp", null, new byte[] {9}));
    }


    @Test
    public void serverStateCheckReadsTheGroupIdUnderTheLockThenAsksWithoutIt() {
        final Group g = new Group();
        g.groupId = new byte[] {4};
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .on("lock", a -> null).on("unlock", a -> null).returns("getGroup", g)
                .returns("lookServerEpochAuthenticator", Look.asked(new byte[] {1}));
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}));
        assertEquals(MlsWelcomeAdmission.ServerState.MATCHES, MlsWelcomeAdmission.serverStateCheck(
                f.port(), MlsFetchLedger.Caller.values()[0], "grp", null));
        assertEquals("the server is asked AFTER the group lock is released — the first unlock "
                + "precedes the look", true, f.calls.indexOf("unlock(g:grp)") < f.calls.indexOf(
                f.calls.stream().filter(c -> c.startsWith("lookServerEpochAuthenticator"))
                .findFirst().get()));
        assertEquals(MlsWelcomeAdmission.ServerState.UNKNOWN, MlsWelcomeAdmission.serverStateCheck(
                new FakeShellPort().returns("ensureSession", true).on("lock", a -> null)
                        .on("unlock", a -> null).returns("getGroup", null).port(),
                MlsFetchLedger.Caller.values()[0], "grp", null));
    }


    /** A blob whose only MLS message is a (content-free) Welcome: mls10, wire format 3. */
    private static final byte[] WELCOME_BLOB = {0x00, 0x01, 0x00, 0x03, 9, 9, 9, 9};

    private static FakeShellPort joinPort() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("prefs", new FakePrefs())
                .returns("publishKeyPackages", true);
        f.returns("session", f.stub(MlsSession.class, "joinTreelessWelcome", null, "join", null));
        return f;
    }

    @Test
    public void aBlobWithNoWelcomeIsNotAJoin() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsWelcomeAdmission.joinFromWelcome(MlsConfig.defaults(), joinPort().port(), log,
                "grp", "+2", new byte[] {1, 2, 3}));
        assertTrue(log.said("I", "not a join"));
    }

    @Test
    public void anUnopenableWelcomeTriesBothJoinsThenRepublishesThePool() {
        final FakeShellPort f = joinPort();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsWelcomeAdmission.joinFromWelcome(MlsConfig.defaults(), f.port(), log, "grp",
                "+2", WELCOME_BLOB));
        assertTrue(f.calls.indexOf("MlsSession.joinTreelessWelcome") < f.calls.indexOf(
                "MlsSession.join"));
        assertTrue(log.said("W", "could not join from the"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages(")));
    }


    @Test
    public void joiningByWelcomeDropsEveryParkedMessageAndReportsTheApplicationOnes() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertTrue(q.store(new MlsPendingQueue.Entry("app", new MlsAppMessage.Moment(1, 4), 2,
                SplitFixtures.GID, new byte[] {1}, MlsPendingQueue.Plane.APPLICATION, "+3"),
                SplitFixtures.GID).stored());
        assertTrue(q.store(new MlsPendingQueue.Entry("ctl", new MlsAppMessage.Moment(2, 9), 2,
                SplitFixtures.GID, new byte[] {1}, MlsPendingQueue.Plane.CONTROL, "+2"),
                SplitFixtures.GID).stored());
        final ConvState cs = new ConvState();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .on("flushFtdReports", a -> null);
        f.returns("pendingQueue", f.stub(MlsPendingQueueAccess.class, "load", q, "store", "ok"));
        assertEquals(2, MlsWelcomeAdmission.dropParkedOnJoin(f.port(), MlsLogSink.NONE, KEY, "grp",
                SplitFixtures.GID));
        assertTrue("every era is dropped, the current one included", q.isEmpty());
        assertEquals("the entries are read before the clear, so the report names them",
                java.util.Collections.singletonMap("app", "+3"), cs.ftdPending);
        assertTrue(f.calls.indexOf("MlsPendingQueueAccess.store")
                < f.calls.indexOf("flushFtdReports(grp, +3)"));

        final FakeShellPort empty = SplitFixtures.port(storeWith(b -> b));
        empty.returns("pendingQueue", empty.stub(MlsPendingQueueAccess.class, "load",
                new MlsPendingQueue(), "store", "ok"));
        assertEquals(0, MlsWelcomeAdmission.dropParkedOnJoin(empty.port(), MlsLogSink.NONE, KEY,
                "grp", SplitFixtures.GID));
        assertFalse(empty.calls.contains("MlsPendingQueueAccess.store"));
    }

    /**
     * {@code joinFromWelcome} drops the queue once, after adopting the joined group and before it
     * crosses off the KeyPackage. Positional, since the join itself needs a real engine.
     */
    @Test
    public void theJoinDropsTheQueueOnceAfterAdoptingTheGroup() throws java.io.IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsWelcomeAdmission.java"));
        final String join = SourceScan.bodyOf(src, "joinFromWelcome");
        assertEquals(1, SourceScan.count(join, "dropParkedOnJoin("));
        final int adopt = join.indexOf("MlsRecordState.adoptGroup(");
        final int drop = join.indexOf("dropParkedOnJoin(");
        final int crossOff = join.indexOf("crossOffConsumedKeyPackage(");
        assertTrue("adopt " + adopt + " < drop " + drop + " < cross-off " + crossOff,
                adopt >= 0 && adopt < drop && drop < crossOff);
        assertEquals("no other clear() in the join: the drop is the one that reports", 0,
                SourceScan.count(join, ".clear()"));
    }

    @Test
    public void aReWelcomeThatDoesNotJoinRollsTheGroupBack() {
        final FakeShellPort f = joinPort().on("putGroup", a -> null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12],
                "exportGroupSnapshot", new byte[] {5}, "restoreGroupSnapshot", true));
        assertFalse(MlsWelcomeAdmission.rejoinOnEraAdvance(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, KEY, "grp", "+2", new byte[] {1, 2, 3}));
        assertTrue(f.calls.contains("MlsSession.restoreGroupSnapshot"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(" + KEY + ",")));
    }
}
