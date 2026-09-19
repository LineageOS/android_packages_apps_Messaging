/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsSelfHealSplitTest {


    /** A heal port over {@code s}: the stall prompt and the terminator are recorded, not run. */
    private static FakeShellPort healPort(final FakeRecords s) {
        return SplitFixtures.port(s).returns("conv", new ConvState()).returns("convIfAny", null)
                .on("offerTheStallChoice", a -> null).on("terminateRepairIfQuotaBound", a -> null);
    }

    @Test
    public void anExhaustedHealEscalatesToAnEraAdvanceAndTerminatesTheRepair() {
        final FakeRecords s =
                storeWith(b -> b.healthStatus(MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        final FakeShellPort f = healPort(s);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSelfHeal.escalateExhaustedSelfHeal(MlsConfig.defaults(), f.port(), log, KEY, rec(s),
                1000L);
        assertEquals(MlsHealthStates.ERAADVANCEMENTREQUESTED, rec(s).healthStatus);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("offerTheStallChoice(g:grp")));
        assertTrue(
                f.calls.stream().anyMatch(c -> c.startsWith("terminateRepairIfQuotaBound(g:grp")));
        final FakeRecords unknown = storeWith(b -> b.healthStatus(MlsHealthStates.UNKNOWN));
        final FakeShellPort quiet = healPort(unknown);
        MlsSelfHeal.escalateExhaustedSelfHeal(MlsConfig.defaults(), quiet.port(), MlsLogSink.NONE,
                KEY, rec(unknown), 1000L);
        assertFalse("no heal to escalate from UNKNOWN",
                quiet.calls.stream().anyMatch(c -> c.startsWith("terminateRepairIfQuotaBound")));
    }


    @Test
    public void theHealBudgetChargesUntilExhaustedAndThenEscalates() {
        final FakeRecords s =
                storeWith(b -> b.healthStatus(MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        final FakeShellPort f = healPort(s);
        int allowed = 0;
        while (MlsSelfHeal.chargeSelfHealBudget(MlsConfig.defaults(), f.port(), MlsLogSink.NONE,
                KEY)) {
            assertTrue("the budget is bounded", ++allowed
                    <= MlsConfig.defaults().selfHealRetryLimit);
        }
        assertEquals(MlsConfig.defaults().selfHealRetryLimit, allowed);
        assertEquals(MlsHealthStates.ERAADVANCEMENTREQUESTED, rec(s).healthStatus);
        assertTrue("no record is not a reason to stop healing", MlsSelfHeal.chargeSelfHealBudget(
                MlsConfig.defaults(), healPort(s).returns("getGroup", null).port(), MlsLogSink.NONE,
                KEY));
    }


    /** {@code [len][part]...}, the self-heal look's framing. */
    private static byte[] packOf(final byte[]... parts) {
        return MlsArtifactBundle.joinLenPrefixed(java.util.Arrays.asList(parts));
    }

    /**
     * A heal over the fixture group: the look returns {@code look}; the server's authenticator is
     * {@code server}.
     */
    private static FakeShellPort innerPort(final FakeRecords s, final Look<byte[]> look,
            final Look<byte[]> server, final int processStatus) {
        final FakeShellPort f = healPort(s).returns("lookMissedCommits", look)
                .returns("lookServerEpochAuthenticator", server).on("putGroup", a -> null);
        f.returns("session", f.stub(MlsSession.class, "endMlsPresent", false, "eraEpoch",
                new byte[12], "epochAuth", new byte[] {1},
                "processEx", new MlsSession.ProcResult(processStatus, new byte[0], -1)));
        return f;
    }

    private static int heal(final FakeShellPort f) {
        return MlsSelfHeal.selfHealInner(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2", KEY);
    }

    @Test
    public void theHealReplaysTheServersCommitsWhenOurAuthenticatorDiffers() {
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f = innerPort(s, Look.asked(packOf(new byte[] {7}, new byte[] {8})),
                Look.asked(new byte[] {2}), 1);
        assertEquals("both commits applied", 2, heal(f));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("moveHealth(g:grp, " + MlsHealthStates.HEALTHY)));
    }

    @Test
    public void theHealStopsWithoutReplayingWhenTheServerAlreadyMatchesOrCannotBeRead() {
        assertEquals("our authenticator is the server's: nothing to replay", 0,
                heal(innerPort(storeWith(b -> b), Look.asked(packOf(new byte[] {7})),
                        Look.asked(new byte[] {1}), 1)));
        assertEquals(MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE,
                heal(innerPort(storeWith(b -> b), Look.refusedByLedger("no"),
                        Look.asked(new byte[] {2}), 1)));
        assertEquals(MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE,
                heal(innerPort(storeWith(b -> b), Look.asked(null), Look.asked(new byte[] {2}),
                        1)));
        assertEquals(MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE, heal(innerPort(storeWith(b -> b),
                Look.asked(packOf(new byte[] {7})), Look.refusedByLedger("no"), 1)));
        assertEquals(-1,
                heal(innerPort(storeWith(b -> b), null, null, 1).returns("getGroup", null)));
    }


    private static MlsProviderRpc.ControlResult refused(final int verdict) {
        return new MlsProviderRpc.ControlResult(verdict, null, "test");
    }

    private static FakeShellPort drivePort(final FakeRecords s) {
        return SplitFixtures.port(s).returns("resolveInbound", "g:grp").returns("convIfAny", null)
                .returns("conv", new ConvState()).on("scheduleRetry", a -> null)
                .returns("driveLoop", new MlsDriveLoop(MlsTelemetry.NONE))
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"));
    }

    @Test
    public void onlyADivergenceVerdictStartsARepair() {
        for (final MlsProviderRpc.ControlResult r : new MlsProviderRpc.ControlResult[] {null,
                refused(MlsProviderRpc.ControlResult.VERDICT_REJECTED),
                refused(MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED)}) {
            final FakeRecords s = storeWith(b -> b);
            final FakeShellPort f = drivePort(s);
            MlsSelfHeal.onControlRefused(f.port(), MlsLogSink.NONE, "grp", "+2", r, "commit");
            assertNull(rec(s).pendingOperation);
            assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("lookServerEraEpoch(")));
        }
    }

    @Test
    public void aRepairAlreadyInFlightIsNotStartedTwice() {
        final FakeShellPort f = drivePort(storeWith(
                b -> b.pendingOperation(op(MlsPendingOperation.Kind.EPOCH_ADVANCEMENT))));
        // Held by this process: a slot no live process claimed is an orphan and is reclaimed.
        f.returns("pendingOpsClaimedThisProcess",
                new java.util.HashSet<>(java.util.Collections.singleton("g:grp")));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSelfHeal.onControlRefused(f.port(), log, "grp", "+2",
                refused(MlsProviderRpc.ControlResult.VERDICT_ERA_GAP), "commit");
        assertTrue(log.said("I", "a repair is already in flight"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("lookServerEraEpoch(")));
    }

    @Test
    public void aDivergenceDrivesTheBoundedReconcileAndReportsItsOutcome() {
        final FakeShellPort f = drivePort(storeWith(b -> b));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSelfHeal.onControlRefused(f.port(), log, "grp", "+2",
                refused(MlsProviderRpc.ControlResult.VERDICT_GROUP_ID_CHANGED), "commit");
        assertTrue(log.said("W", "DIVERGENCE verdict"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("lookServerEraEpoch(")));
        assertTrue(log.said("I", "self-heal for g:grp"));
    }


    @Test
    public void anExhaustedRepairWithoutAnEstablishedQuotaStaysEncrypted() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("eraQuotaReported", new java.util.HashSet<String>())
                .returns("eraAsked", new java.util.HashMap<String, Integer>());
        f.returns("sealedCache", f.stub(MlsSealedCacheAccess.class, "releaseConversation", 2));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsSelfHeal.terminateRepairIfQuotaBound(MlsConfig.defaults(), f.port(), log, KEY, null);
        assertTrue(f.calls.contains("MlsSealedCacheAccess.releaseConversation"));
        assertTrue(log.said("W", "is NOT established"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }


    @Test
    public void aHealAlreadyInFlightOrNoSessionStartsNothing() {
        final FakeShellPort pending = SplitFixtures.port(storeWith(
                b -> b.pendingOperation(op(MlsPendingOperation.Kind.EPOCH_ADVANCEMENT))));
        assertEquals(0, MlsSelfHeal.selfHeal(MlsConfig.defaults(), pending.port(), MlsLogSink.NONE,
                "grp", "+2"));
        assertFalse(pending.calls.stream().anyMatch(c -> c.startsWith("lookServerEraEpoch(")));
        final FakeShellPort closed =
                SplitFixtures.port(storeWith(b -> b)).returns("ensureSession", false);
        assertEquals(-1, MlsSelfHeal.selfHeal(MlsConfig.defaults(), closed.port(), MlsLogSink.NONE,
                "grp", "+2"));
    }
}
