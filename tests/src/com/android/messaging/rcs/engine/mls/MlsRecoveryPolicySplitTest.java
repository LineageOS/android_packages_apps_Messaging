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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.RosterClaim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsRecoveryPolicySplitTest {


    @Test
    public void killSelfHealMovesOnlyAHealingStateToSelfHealFailed() {
        for (int st = 0; st <= 20; st++) {
            final int s = st;
            final FakeShellPort f = port(storeWith(b -> b.healthStatus(s)));
            MlsRecoveryPolicy.killSelfHeal(f.port(), MlsLogSink.NONE, KEY, "test");
            final boolean moved = f.calls.stream().anyMatch(c -> c.startsWith("moveHealth(g:grp, "
                    + MlsHealthStates.SELFHEALFAILED));
            assertEquals("status " + st, MlsHealthStates.edge(st, MlsHealthStates.SELFHEALFAILED)
                    != null, moved);
        }
    }


    @Test
    public void aUserRetryResetsTheBudgetAndChangesNothingElse() {
        final FakeRecords s = storeWith(b -> b.selfHealBudget(
                new MlsConversationRecord.SelfHealBudget(3, 1L, false))
                .healthStatus(MlsHealthStates.SELFHEALFAILED));
        MlsRecoveryPolicy.resetSelfHealBudget(port(s).port(), MlsLogSink.NONE, KEY);
        assertEquals(0, rec(s).selfHealBudget.retryCount);
        assertEquals(MlsHealthStates.SELFHEALFAILED, rec(s).healthStatus);
    }


    @Test
    public void forwardProgressResetsASpentBudgetAndWritesNothingWhenThereIsNone() {
        final FakeRecords s = storeWith(
                b -> b.selfHealBudget(new MlsConversationRecord.SelfHealBudget(2, 1L, false)));
        MlsRecoveryPolicy.noteForwardProgress(port(s).port(), MlsLogSink.NONE, KEY,
                "a commit applied");
        assertEquals(0, rec(s).selfHealBudget.retryCount);
        final FakeRecords clean = storeWith(b -> b);
        clean.failWrites = "must not be called";
        MlsRecoveryPolicy.noteForwardProgress(port(clean).port(), MlsLogSink.NONE, KEY, "x");
    }


    @Test
    public void markCannotHealDuringEndMlsPersistsTheWedgeOrSaysWhyNot() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.ONGOINGENDMLS));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecoveryPolicy.markCannotHealDuringEndMls(port(s).port(), log, KEY, "end-mls in flight");
        if (log.said("W", "CannotHealDuringEndMls")) {
            assertEquals(MlsHealthStates.CANNOTHEALDURINGENDMLS, rec(s).healthStatus);
        } else {
            assertTrue("the undocumented case is refused without a status write",
                    log.said("I", "the documented escape does not apply"));
            assertEquals(MlsHealthStates.ONGOINGENDMLS, rec(s).healthStatus);
        }
        final FakeRecords already =
                storeWith(b -> b.healthStatus(MlsHealthStates.CANNOTHEALDURINGENDMLS));
        already.failWrites = "must not be called";
        MlsRecoveryPolicy.markCannotHealDuringEndMls(port(already).port(), MlsLogSink.NONE, KEY,
                "x");
    }


    @Test
    public void aForkFoundByMaintenanceIsHealedAndReportedDivergedWhateverTheHealSays() {
        for (final int healed : new int[] {MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE, -1, 3}) {
            final FakeShellPort.Log log = new FakeShellPort.Log();
            assertSame(MlsMaintenancePolicy.Refresh.DIVERGED,
                    MlsRecoveryPolicy.maintenanceFoundAFork(
                    new FakeShellPort().returns("selfHeal", healed).port(), log, KEY, "grp", "+2",
                    "open"));
            assertTrue(log.said("E", "DIFFERENT GROUP AT THE SAME POSITION"));
            assertTrue(healed == 3 ? log.said("I", "self-heal returned 3")
                    : log.said("W", healed == -1 ? "could not repair the fork"
                            : "could not READ the server"));
        }
    }


    @Test
    public void theReestablishCooldownIsResetForAPeerAndNeverForAGroupAlone() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "resetReestablishCooldown", null));
        MlsRecoveryPolicy.resetReestablishCooldown(f.port(), MlsLogSink.NONE, "grp", "+2");
        assertTrue(f.calls.contains("MlsPeerGuards.resetReestablishCooldown"));
        final FakeShellPort none = new FakeShellPort();
        MlsRecoveryPolicy.resetReestablishCooldown(none.port(), MlsLogSink.NONE, "grp", null);
        assertTrue("no peer, nothing to reset", none.calls.isEmpty());
    }


    @Test
    public void aReWelcomeRequestCarriesTheServersEraAndAuthenticatorWhenItCouldReadThem() {
        final Object[] sent = new Object[1];
        final FakeShellPort f = new FakeShellPort()
                .returns("lookServerEraEpoch", Look.asked(new long[] {3, 0}))
                .returns("lookServerEpochAuthenticator", Look.asked(new byte[] {1, 2}));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "sendMlsNegativeDeliveryImdn",
                (Function<Object[], Object>) a -> { sent[0] = a; return true; }));
        MlsRecoveryPolicy.requestReWelcome(f.port(), MlsLogSink.NONE, "grp", "+2", "test");
        final Object[] a = (Object[]) sent[0];
        assertEquals(3L, a[4]);
        assertEquals(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2}), a[5]);
        final Object[] blind = new Object[1];
        final FakeShellPort g = new FakeShellPort()
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"))
                .returns("lookServerEpochAuthenticator", Look.refusedByLedger("no"));
        g.returns("rpc", g.stub(MlsProviderRpc.class, "sendMlsNegativeDeliveryImdn",
                (Function<Object[], Object>) x -> { blind[0] = x; return true; }));
        MlsRecoveryPolicy.requestReWelcome(g.port(), MlsLogSink.NONE, "grp", "+2", "test");
        assertEquals("the request still goes, without an era", -1L, ((Object[]) blind[0])[4]);
        assertNull(((Object[]) blind[0])[5]);
    }


    @Test
    public void peerHealthIsResetForEveryoneInTheRebuildRoster() {
        final FakeShellPort f = SplitFixtures.port(new FakeRecords())
                .returns("bugleRoster", java.util.Arrays.asList("+3", "+4"));
        final java.util.List<String> reset = new java.util.ArrayList<>();
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "resetPeerHealth",
                (java.util.function.Function<Object[], Object>) a ->
                { reset.add((String) a[0]); return null; }));
        MlsRecoveryPolicy.resetPeerHealth(f.port(), MlsLogSink.NONE, "grp", "+2");
        assertEquals(java.util.Arrays.asList("+3", "+4"), reset);
    }


    private static FakeShellPort reestablishPort(final boolean allowed, final Look<long[]> server) {
        final FakeShellPort f = new FakeShellPort()
                .returns("keyToConvId", new java.util.HashMap<String, String>())
                .returns("subId", 1).returns("offThread", MlsOffThread.DIRECT)
                .returns("lookServerEraEpoch", server).returns("ensureReady", false);
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "claimReestablishAttempt", allowed));
        return f;
    }

    @Test
    public void aPeerWithNoServerGroupIsReestablishedOffThreadOncePerCooldown() {
        final FakeShellPort f = reestablishPort(true, Look.asked(null));
        MlsRecoveryPolicy.reestablishOutbound(f.port(), MlsLogSink.NONE, "+2");
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("ensureReady(p:+2, 1, [+2]")));
        final FakeShellPort cooling = reestablishPort(false, Look.asked(null));
        MlsRecoveryPolicy.reestablishOutbound(cooling.port(), MlsLogSink.NONE, "+2");
        assertFalse(cooling.calls.stream()
                .anyMatch(c -> c.startsWith("offThread") || c.startsWith("ensureReady")));
        final FakeShellPort held = reestablishPort(true, Look.asked(new long[] {3, 0}));
        MlsRecoveryPolicy.reestablishOutbound(held.port(), MlsLogSink.NONE, "+2");
        assertFalse("the server holds a group: nothing to re-establish",
                held.calls.stream().anyMatch(c -> c.startsWith("ensureReady")));
    }
}
