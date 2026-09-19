/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.port;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsPendingOperationSplitTest {


    @Test
    public void pendingOpIsTheRecordsOperationReadUnderTheLock() {
        final MlsPendingOperation held = op(MlsPendingOperation.Kind.END_MLS);
        final FakeShellPort f = port(storeWith(b -> b.pendingOperation(held)));
        assertEquals(held.kind, MlsPendingOperation.pendingOp(f.port(), MlsLogSink.NONE, KEY).kind);
        assertTrue(f.calls.indexOf("lock(g:grp)") < f.calls.indexOf("unlock(g:grp)"));
        assertNull(MlsPendingOperation.pendingOp(port(storeWith(b -> b)).port(), MlsLogSink.NONE,
                null));
    }


    @Test
    public void clearPendingOpDropsTheOperationAndItsInProcessClaim() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS)));
        final FakeShellPort f = port(s);
        @SuppressWarnings("unchecked")
        final java.util.Set<String> claimed =
                (java.util.Set<String>) f.port().pendingOpsClaimedThisProcess();
        claimed.add(KEY);
        MlsPendingOperation.clearPendingOp(f.port(), MlsLogSink.NONE, KEY);
        assertNull(rec(s).pendingOperation);
        assertFalse("released is not owned", claimed.contains(KEY));
    }


    @Test
    public void failPendingOpClearsItAndEscalatesOnlyAnOperationThatNobodyElseWouldRetry() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS)));
        final FakeShellPort f = port(s);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsPendingOperation.failPendingOp(f.port(), log, KEY, "server refused");
        assertNull(rec(s).pendingOperation);
        assertTrue(log.said("W", "FAILED — server refused"));
        assertEquals("an UNKNOWN-origin operation does not escalate",
                op(MlsPendingOperation.Kind.END_MLS).escalatesOnDeath(),
                f.calls.stream().anyMatch(c -> c.startsWith("moveHealth(")));
    }


    @Test
    public void healInFlightIsExactlyTheThreeOngoingAdvancementStates() {
        for (int st = 0; st <= 20; st++) {
            final int s = st;
            final boolean expected = st == MlsHealthStates.ONGOINGEPOCHADVANCEMENT
                    || st == MlsHealthStates.ONGOINGERAADVANCEMENT
                    || st == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE;
            assertEquals("status " + st, expected, MlsPendingOperation.healInFlight(
                    port(storeWith(b -> b.healthStatus(s))).port(), MlsLogSink.NONE, KEY));
        }
    }


    @Test
    public void aLiveSelfHealIsAlreadyPendingButAnotherKindOfOperationIsNot() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsPendingOperation.healAlreadyPending(port(storeWith(b -> b.pendingOperation(
                op(MlsPendingOperation.Kind.EPOCH_ADVANCEMENT)))).port(), log, KEY));
        assertTrue("a dropped concurrent trigger is a no-op, not a failure",
                log.said("I", "DROPPED"));
        assertFalse(MlsPendingOperation.healAlreadyPending(port(storeWith(b -> b.pendingOperation(
                op(MlsPendingOperation.Kind.END_MLS)))).port(), MlsLogSink.NONE, KEY));
        assertFalse(MlsPendingOperation.healAlreadyPending(port(storeWith(b -> b)).port(),
                MlsLogSink.NONE, KEY));
    }


    @Test
    public void escalationReleasesOnlyItsOwnEpochAdvancement() {
        final FakeRecords own =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.EPOCH_ADVANCEMENT)));
        MlsPendingOperation.releaseOwnHealSlotForEscalation(port(own).port(), MlsLogSink.NONE, KEY);
        assertNull(rec(own).pendingOperation);
        final FakeRecords other =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS)));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsPendingOperation.releaseOwnHealSlotForEscalation(port(other).port(), log, KEY);
        assertNotNull("somebody else's operation is not dropped", rec(other).pendingOperation);
        assertTrue(log.said("I", "is NOT releasing"));
    }


    @Test
    public void aFreeSlotIsClaimedAndMarkedOwnedByThisProcess() {
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f = SplitFixtures.port(s);
        assertTrue(MlsPendingOperation.claimPendingOp(f.port(), MlsLogSink.NONE, KEY,
                MlsPendingOperation.Kind.END_MLS, MlsPendingOperation.Origin.values()[0], "ctx"));
        assertEquals(MlsPendingOperation.Kind.END_MLS, rec(s).pendingOperation.kind);
        assertTrue(f.port().pendingOpsClaimedThisProcess().contains(KEY));
        assertFalse("no record is not a held slot", MlsPendingOperation.claimPendingOp(
                SplitFixtures.port(new FakeRecords()).returns("getGroup", null).port(),
                MlsLogSink.NONE, KEY, MlsPendingOperation.Kind.END_MLS,
                MlsPendingOperation.Origin.values()[0], "ctx"));
    }

    @Test
    public void aLiveSlotOwnedByUsRefusesAnotherOperationButLetsItsOwnContextResume() {
        final FakeRecords s = storeWith(b -> b.pendingOperation(MlsPendingOperation.start(
                MlsPendingOperation.Kind.END_MLS, System.currentTimeMillis(), null, "mine")));
        final FakeShellPort f = SplitFixtures.port(s);
        f.port().pendingOpsClaimedThisProcess().add(KEY);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsPendingOperation.claimPendingOp(f.port(), log, KEY,
                MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS,
                MlsPendingOperation.Origin.values()[0], "other"));
        assertTrue(log.said("I", "is already pending"));
        final FakeShellPort.Log resume = new FakeShellPort.Log();
        assertTrue(MlsPendingOperation.claimPendingOp(f.port(), resume, KEY,
                MlsPendingOperation.Kind.END_MLS, MlsPendingOperation.Origin.values()[0], "mine"));
        assertTrue(resume.said("I", "is RESUMING its own slot"));
    }

    @Test
    public void aSlotNoLivingProcessHoldsIsReclaimedRatherThanWaitedOut() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS)));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsPendingOperation.claimPendingOp(SplitFixtures.port(s).port(), log, KEY,
                MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS,
                MlsPendingOperation.Origin.values()[0], "x"));
        assertTrue(log.said("W", "claimed by a process that no longer exists"));
        assertEquals(MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS,
                rec(s).pendingOperation.kind);
    }

    @Test
    public void supersedingAnInFlightSelfHealRecordsTheKillAndFlushesReports() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.ONGOINGEPOCHADVANCEMENT)
                .pendingOperation(MlsPendingOperation.start(
                        MlsPendingOperation.Kind.EPOCH_ADVANCEMENT,
                        1L, null, "old")));            // started at 1 ms: timed out, so superseded
        final FakeShellPort f = SplitFixtures.port(s).on("flushFtdReports", a -> null);
        f.port().pendingOpsClaimedThisProcess().add(KEY);
        assertTrue(MlsPendingOperation.claimPendingOp(f.port(), MlsLogSink.NONE, KEY,
                MlsPendingOperation.Kind.END_MLS, MlsPendingOperation.Origin.values()[0], "new"));
        assertTrue(f.calls.stream().anyMatch(
                c -> c.startsWith("moveHealth(g:grp, " + MlsHealthStates.SELFHEALFAILED)));
        assertTrue(f.calls.contains("flushFtdReports(grp, null)"));
    }


    @Test
    public void retryKeepsTheSlotUntilTheAttemptsRunOutThenFailsIt() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.END_MLS)));
        final FakeShellPort f = SplitFixtures.port(s);
        int guard = 0;
        while (rec(s).pendingOperation != null && guard++ < 50) {
            MlsPendingOperation.retryPendingOp(f.port(), MlsLogSink.NONE, KEY, "refused");
        }
        assertNull("the slot is released once the attempts are exhausted", rec(s).pendingOperation);
        assertTrue(guard <= MlsPendingOperation.maxAttempts() + 1);
    }


    @Test
    public void claimAdvanceClaimsAnEraAdvancementNamedForItsReason() {
        final FakeRecords s = storeWith(b -> b);
        assertTrue(MlsPendingOperation.claimAdvance(SplitFixtures.port(s).port(), MlsLogSink.NONE,
                KEY, "stall"));
        assertEquals(MlsPendingOperation.Kind.ERA_ADVANCEMENT, rec(s).pendingOperation.kind);
        assertEquals("eraAdvance:stall", rec(s).pendingOperation.context);
    }


    @Test
    public void releaseAdvanceClearsTheSlot() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.ERA_ADVANCEMENT)));
        MlsPendingOperation.releaseAdvance(SplitFixtures.port(s).port(), MlsLogSink.NONE, KEY);
        assertNull(rec(s).pendingOperation);
    }
}
