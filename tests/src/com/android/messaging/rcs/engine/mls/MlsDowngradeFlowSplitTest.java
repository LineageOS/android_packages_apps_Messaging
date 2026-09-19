/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupMembershipRouting;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsDowngradeFlowSplitTest {


    /**
     * A port over {@code s} whose health moves succeed and whose era advance yields {@code era}.
     */
    static FakeShellPort flowPort(final FakeRecords s, final int era) {
        final FakeShellPort f = SplitFixtures.port(s)
                .on("moveHealth", a -> MlsHealthEdge.values()[0])
                .returns("eraAdvance", era).returns("conversationIdFor", "c1")
                .returns("resolveInbound", KEY).on("downgradeLocally", a -> null);
        f.returns("reupgradeStore", f.stub(MlsReupgradeAccess.class, "clear", null,
                "load", new MlsReupgradeState(0L, 0, true)));
        return f;
    }

    private static boolean moved(final FakeShellPort f, final int to) {
        return f.calls.stream().anyMatch(c -> c.startsWith("moveHealth(" + KEY + ", " + to + ","));
    }

    @Test
    public void aNewEraReviveLandsHealthyAndClearsTheReupgradeBookkeeping() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS));
        final FakeShellPort f = flowPort(s, 5);
        assertEquals(5, MlsDowngradeFlow.reviveInNewEra(f.port(), MlsLogSink.NONE, KEY, "grp", "+2",
                MlsHealthStates.DONEENDMLS));
        assertTrue(moved(f, MlsHealthStates.HEALTHY));
        assertTrue(f.calls.contains("MlsReupgradeAccess.clear"));
        assertNull(rec(s).pendingOperation);
    }

    @Test
    public void aFailedNewEraReviveFallsBackToDoneEndMlsAndKeepsTheSlotForARetry() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS));
        final FakeShellPort f = flowPort(s, -1);
        assertEquals(-1, MlsDowngradeFlow.reviveInNewEra(f.port(), MlsLogSink.NONE, KEY, "grp",
                "+2", MlsHealthStates.DONEENDMLS));
        assertTrue(moved(f, MlsHealthStates.DONEENDMLS));
        assertFalse(f.calls.contains("MlsReupgradeAccess.clear"));
        assertNotNull(rec(s).pendingOperation);
    }


    @Test
    public void theWedgeRevivesByNewEraAndAHealthyGroupIsNotRevived() {
        final FakeRecords wedged =
                storeWith(b -> b.healthStatus(MlsHealthStates.CANNOTHEALDURINGENDMLS));
        final FakeShellPort f = flowPort(wedged, 6);
        f.returns("session", f.stub(MlsSession.class, "endMlsPresent", true));
        assertEquals(6, MlsDowngradeFlow.reviveInner(f.port(), MlsLogSink.NONE,
                KEY, grp(), "grp", "+2"));
        assertTrue(moved(f, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
        final FakeShellPort healthy =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)), 6);
        healthy.returns("session", healthy.stub(MlsSession.class, "endMlsPresent", false));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsDowngradeFlow.reviveInner(healthy.port(), log, KEY,
                grp(), "grp", "+2"));
        assertTrue(log.said("I", "MLS is already active, no need to revive"));
        assertFalse(healthy.calls.stream().anyMatch(c -> c.startsWith("eraAdvance(")));
    }


    @Test
    public void anEagerlyDowngradedConversationReupgradesOnceHealthy() {
        final FakeShellPort f =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)), 0);
        MlsDowngradeFlow.maybeFastReupgrade(f.port(), MlsLogSink.NONE, KEY);
        assertTrue(f.calls.contains("MlsReupgradeAccess.clear"));
        final FakeShellPort g =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)), 0);
        g.returns("reupgradeStore",
                g.stub(MlsReupgradeAccess.class, "load", MlsReupgradeState.NONE));
        MlsDowngradeFlow.maybeFastReupgrade(g.port(), MlsLogSink.NONE, KEY);
        assertFalse(g.calls.contains("MlsReupgradeAccess.clear"));
    }


    @Test
    public void phoenixDowngradesLocallyFirstAndLandsInDoneEndMls() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS));
        final FakeShellPort f = flowPort(s, 9);
        assertEquals(9, MlsDowngradeFlow.initiatePhoenixMode(f.port(),
                MlsLogSink.NONE, "grp", "+2", "test"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("downgradeLocally(")));
        assertTrue(moved(f, MlsHealthStates.DONEENDMLS));
        assertNull(rec(s).pendingOperation);
    }

    @Test
    public void phoenixAlreadyUnderwayIsNotStartedAgain() {
        final FakeShellPort f =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.ONGOINGPHOENIXMODE)), 9);
        assertEquals(-1, MlsDowngradeFlow.initiatePhoenixMode(f.port(),
                MlsLogSink.NONE, "grp", "+2", "test"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("downgradeLocally(")));
    }


    @Test
    public void anEndMlsTheEngineAlreadyAppliedDowngradesLocallyWithoutACommit() {
        final MlsDowngradeReason why = MlsDowngradeReason.RECEIVED_END_MLS_COMMIT;
        assertTrue("precondition: this reason reports MLS already ended", why.zinniaAlreadyEnded);
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY));
        final FakeShellPort f = flowPort(s, 0);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        f.returns("pendingQueue",
                f.stub(MlsPendingQueueAccess.class, "load", new MlsPendingQueue()));
        f.returns("conv", new ConvState()).returns("convIfAny", null);
        assertEquals(0,
                MlsDowngradeFlow.endMls(f.port(), MlsLogSink.NONE, "grp", "+2", false, why));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("downgradeLocally(")));
        assertTrue(moved(f, MlsHealthStates.DONEENDMLS));
        assertFalse(f.calls.contains("MlsSession.commitEndMls"));
        assertNull(rec(s).pendingOperation);
    }

    @Test
    public void anEndMlsTheLadderRefusesIsANoOp() {
        final FakeShellPort f =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)), 0);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsDowngradeFlow.endMls(f.port(), log, "grp", "+2", false, MlsDowngradeReason.DEBUG_MENU);
        assertTrue(log.said("I", "end_mls REFUSED"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("downgradeLocally(")));
    }


    @Test
    public void theThreeArgumentEndMlsIsADebugMenuDowngradeUnlessResuming() {
        final FakeShellPort f =
                flowPort(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)), 0);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsDowngradeFlow.endMls(f.port(), log, "grp", "+2", false);
        assertTrue(log.said("I", "reason " + MlsDowngradeReason.DEBUG_MENU));
    }


    private static FakeShellPort bitsPort(final Boolean mls) {
        final FakeShellPort f = flowPort(storeWith(b -> b), 0).returns("conversationMlsBit", mls)
                .on("downgradeMlsScheme", a -> null);
        f.returns("reupgradeStore", f.stub(MlsReupgradeAccess.class, "markDowngrade",
                MlsReupgradeState.NONE, "setEagerlyDowngraded", null));
        return f;
    }

    @Test
    public void anEagerDowngradeClearsTheBitAndRecordsItAndARepeatIsAbsorbed() {
        final FakeShellPort f = bitsPort(Boolean.TRUE);
        MlsDowngradeFlow.downgradeLocally(f.port(), MlsLogSink.NONE, KEY,
                MlsDowngradeReason.DEBUG_MENU, true);
        assertTrue(f.calls.contains("MlsReupgradeAccess.markDowngrade"));
        assertTrue(f.calls.contains("downgradeMlsScheme(c1)"));
        final FakeShellPort lazy = bitsPort(null);
        MlsDowngradeFlow.downgradeLocally(lazy.port(), MlsLogSink.NONE, KEY,
                MlsDowngradeReason.DEBUG_MENU, false);
        assertTrue("an unreadable bit is not plaintext: the downgrade is still recorded",
                lazy.calls.contains("MlsReupgradeAccess.markDowngrade"));
        assertFalse(lazy.calls.contains("downgradeMlsScheme(c1)"));
        final FakeShellPort plain = bitsPort(Boolean.FALSE);
        MlsDowngradeFlow.downgradeLocally(plain.port(), MlsLogSink.NONE, KEY,
                MlsDowngradeReason.DEBUG_MENU, true);
        assertFalse(plain.calls.contains("MlsReupgradeAccess.markDowngrade"));
    }

    /**
     * Column z defers the clear only until the end_mls commit lands: for every reason, the bit
     * goes exactly when the reason is eager or the status already refuses an MLS seal.
     */
    @Test
    public void columnZKeepsTheBitOnlyUntilTheStatusRecordsTheCommit() {
        final int[] statuses = {MlsHealthStates.ENDMLSREQUESTED, MlsHealthStates.ONGOINGENDMLS,
                MlsHealthStates.DONEENDMLS, MlsHealthStates.CANNOTHEALDURINGENDMLS};
        int deferred = 0;
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            for (final int st : statuses) {
                final FakeShellPort f = flowPort(storeWith(b -> b.healthStatus(st)), 0)
                        .returns("conversationMlsBit", Boolean.TRUE)
                        .on("downgradeMlsScheme", a -> null);
                f.returns("reupgradeStore", f.stub(MlsReupgradeAccess.class, "markDowngrade",
                        MlsReupgradeState.NONE, "setEagerlyDowngraded", null));
                MlsDowngradeFlow.downgradeLocally(f.port(), MlsLogSink.NONE, KEY, r,
                        MlsDowngradeReason.eagerFor(r));
                final boolean landed = MlsHealthPredicates.hasEndMls(st);
                final boolean cleared = f.calls.contains("downgradeMlsScheme(c1)");
                assertEquals(r + " at " + MlsHealthStates.name(st),
                        MlsDowngradeReason.eagerFor(r) || landed, cleared);
                assertEquals(r + " at " + MlsHealthStates.name(st) + ": the mark goes with the bit",
                        cleared, f.calls.contains("MlsReupgradeAccess.setEagerlyDowngraded"));
                if (!cleared) deferred++;
            }
        }
        assertTrue("precondition: some reason defers before the commit lands", deferred > 0);
    }
}
