/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsExternalCommitResyncSplitTest {


    /** A resync of group {@code grp}: the charged look is free, the provider answers as given. */
    private static FakeShellPort resyncPort(final byte[] externalPub, final boolean budget,
            final MlsProviderRpc.ControlResult applied) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "g:grp")
                .returns("prefs", new FakePrefs()).returns("sysprops", new FakeSysProps())
                .returns("elapsedRealtime", 1000L)
                .returns("fetchServerPack", Look.asked(MlsArtifactBundle.joinLenPrefixed(
                        java.util.Arrays.asList(new byte[] {1, 2}, new byte[] {6}))))
                .returns("lookServerEpochAuthenticator", Look.asked(new byte[] {3}))
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .returns("transportProfile", MlsProviderRpc.TransportProfile.conservativeDefault());
        f.returns("xcBudget", f.stub(MlsExternalCommitLimits.class, "claim", budget));
        f.returns("session", f.stub(MlsSession.class, "groupInfoExtTypes", new byte[0],
                "exportGroupSnapshot", new byte[] {5}, "groupInfoContinuity", externalPub,
                "externalCommitResync", new MlsSession.ExternalCommit(GID, new byte[] {0, 1, 0, 1},
                        new byte[] {8}, new byte[] {7}, new byte[] {6}),
                "restoreGroupSnapshot", true));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "getMlsGroupInfoForGroup",
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK,
                        new byte[] {1, 2}, null),
                "applyMlsControl", applied));
        return f;
    }

    @Test
    public void theGroupInfoLookIsChargedToTheFetchLedger() {
        final FakeShellPort f = resyncPort(null, true, null);
        final Look<MlsProviderRpc.ControlResult> l = MlsExternalCommitResync.lookGroupInfoForGroup(
                f.port(),
                MlsLogSink.NONE, MlsFetchLedger.Caller.EXTERNAL_COMMIT_RESYNC, "g:grp", "+2",
                "grp");
        assertArrayEquals(new byte[] {1, 2}, l.orNull().response);
        assertTrue(f.calls.contains("MlsProviderRpc.getMlsGroupInfoForGroup"));
    }


    @Test
    public void aResyncAppliesOnlyWhereTheProfileAllowsAMembersExternalCommit() {
        final FakeShellPort no = new FakeShellPort().returns("transportProfile",
                MlsProviderRpc.TransportProfile.conservativeDefault());
        assertEquals(MlsRecoveryPolicy.externalCommitResyncApplies(false, true),
                MlsExternalCommitResync.resyncApplies(no.port(), true));
        final FakeShellPort yes = new FakeShellPort().returns("transportProfile",
                new MlsProviderRpc.TransportProfile(false, false, true, false));
        assertEquals(MlsRecoveryPolicy.externalCommitResyncApplies(true, true),
                MlsExternalCommitResync.resyncApplies(yes.port(), true));
    }


    private static int resync(final FakeShellPort f, final boolean dryRun) {
        return MlsExternalCommitResync.resyncViaExternalCommit(f.port(), MlsLogSink.NONE, "grp",
                "+2", "test", true, -1L, dryRun);
    }

    @Test
    public void theDryRunSaysWhetherAResyncCouldWorkWithoutSpendingTheBudget() {
        final FakeShellPort viable = resyncPort(new byte[] {9}, false, null);
        assertEquals(MlsExternalCommitResync.RESYNC_DRY_RUN_VIABLE, resync(viable, true));
        assertFalse(viable.calls.contains("MlsExternalCommitLimits.claim"));
        assertEquals(MlsExternalCommitResync.RESYNC_DRY_RUN_NOT_VIABLE,
                resync(resyncPort(null, false, null), true));
    }

    @Test
    public void aRealResyncSpendsTheBudgetFirstAndAppliesTheExternalCommit() {
        final FakeShellPort spent = resyncPort(new byte[] {9}, false, null);
        assertEquals(-1, resync(spent, false));
        assertFalse("no budget, no fetch",
                spent.calls.contains("MlsProviderRpc.getMlsGroupInfoForGroup"));
        final FakeShellPort ok = resyncPort(new byte[] {9}, true,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null,
                        null));
        assertEquals(MlsProviderRpc.ControlResult.VERDICT_OK, resync(ok, false));
        assertTrue(ok.calls.contains("MlsProviderRpc.applyMlsControl"));
        final FakeShellPort refused = resyncPort(new byte[] {9}, true,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"));
        assertEquals(MlsProviderRpc.ControlResult.VERDICT_REJECTED, resync(refused, false));
        assertTrue("rolled back", refused.calls.contains("MlsSession.restoreGroupSnapshot"));
    }


    /**
     * A resync whose rollback failed: {@code onResyncFailure} decides by whether the group still
     * loads. Refused by the server and still loading, only an ahead-of-server group is dropped; no
     * loadable group left, the conversation is forgotten.
     */
    private static FakeShellPort failedRollbackPort(final MlsSession.ExternalCommit built,
            final byte[] eraEpochAfter) {
        final FakeShellPort f = resyncPort(new byte[] {9}, true,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"))
                .returns("forget", true)
                .returns("quarantineIfAheadOfServer",
                        MlsTransportTypes.EraReconcile.unknown());
        f.returns("session", f.stub(MlsSession.class, "groupInfoExtTypes", new byte[0],
                "exportGroupSnapshot", new byte[] {5}, "groupInfoContinuity", new byte[] {9},
                "externalCommitResync", built, "restoreGroupSnapshot", false,
                "eraEpoch", eraEpochAfter));
        return f;
    }

    private static final MlsSession.ExternalCommit BUILT = new MlsSession.ExternalCommit(GID,
            new byte[] {0, 1, 0, 1}, new byte[] {8}, new byte[] {7}, new byte[] {6});

    @Test
    public void aRefusedResyncWhoseRollbackFailedQuarantinesAGroupThatStillLoads() {
        final FakeShellPort f = failedRollbackPort(BUILT, new byte[12]);
        assertEquals(MlsProviderRpc.ControlResult.VERDICT_REJECTED, resync(f, false));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("quarantineIfAheadOfServer(")));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("forget(")));
    }

    @Test
    public void aFailedResyncThatLeavesNoLoadableGroupForgetsTheConversation() {
        final FakeShellPort refused = failedRollbackPort(BUILT, null);
        assertEquals(MlsProviderRpc.ControlResult.VERDICT_REJECTED, resync(refused, false));
        assertTrue(refused.calls.contains("forget(grp, +2)"));
        assertFalse("nothing loads, so there is no era to compare",
                refused.calls.stream().anyMatch(c -> c.startsWith("quarantineIfAheadOfServer(")));

        final FakeShellPort unbuilt = failedRollbackPort(null, null);
        assertEquals(-1, resync(unbuilt, false));
        assertTrue(unbuilt.calls.contains("forget(grp, +2)"));
        assertFalse("nothing was sent", unbuilt.calls.contains("MlsProviderRpc.applyMlsControl"));
    }

    @Test
    public void aFailedBuildWhoseGroupStillLoadsTouchesNothing() {
        final FakeShellPort f = failedRollbackPort(null, new byte[12]);
        assertEquals(-1, resync(f, false));
        assertFalse(f.calls.stream().anyMatch(
                c -> c.startsWith("forget(") || c.startsWith("quarantineIfAheadOfServer(")));
    }

    @Test
    public void aSuccessfulRollbackConsultsNoFailurePolicy() {
        final FakeShellPort refused = resyncPort(new byte[] {9}, true,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"));
        assertEquals(MlsProviderRpc.ControlResult.VERDICT_REJECTED, resync(refused, false));
        assertFalse(refused.calls.contains("MlsSession.eraEpoch"));
    }

    @Test
    public void theFiveArgumentResyncIsNotADryRun() {
        assertEquals(-1, MlsExternalCommitResync.resyncViaExternalCommit(
                resyncPort(new byte[] {9}, false, null).port(),
                MlsLogSink.NONE, "grp", "+2", "test", true, -1L));
    }


    @Test
    public void theFourArgumentResyncRemovesNoLeaf() {
        assertEquals(-1, MlsExternalCommitResync.resyncViaExternalCommit(
                resyncPort(new byte[] {9}, false, null).port(),
                MlsLogSink.NONE, "grp", "+2", "test", true));
    }


    @Test
    public void theThreeArgumentResyncRespectsTheProfile() {
        assertEquals(-1, MlsExternalCommitResync.resyncViaExternalCommit(
                resyncPort(new byte[] {9}, false, null).port(),
                MlsLogSink.NONE, "grp", "+2", "test"));
    }


    @Test
    public void theTryAgainLeverResetsTheExternalCommitBudget() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("xcBudget", f.stub(MlsExternalCommitLimits.class, "reset", null));
        MlsExternalCommitResync.resetExternalCommitBudget(f.port(), "g:grp");
        assertTrue(f.calls.contains("MlsExternalCommitLimits.reset"));
        final FakeShellPort none = new FakeShellPort();
        MlsExternalCommitResync.resetExternalCommitBudget(none.port(), null);
        assertTrue(none.calls.isEmpty());
    }
}
