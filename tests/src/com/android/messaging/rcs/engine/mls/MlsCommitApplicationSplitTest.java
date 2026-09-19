/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsCommitApplicationSplitTest {


    private static byte[] ee(final int era, final long epoch) {
        final byte[] b = new byte[12];
        b[0] = (byte) (era >>> 24); b[1] = (byte) (era >>> 16); b[2] = (byte) (era >>> 8); b[3] =
                (byte) era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (epoch >>> (56 - 8 * i));
        return b;
    }

    /** Our group moved to era 1 epoch 5 from epoch 4; the server answers {@code server}. */
    private static FakeShellPort commitPort(final ConvState cs, final long[] server,
            final byte[] serverAuth) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("convIfAny", cs)
                .on("putGroup", a -> null).on("scheduleRetry", a -> null)
                .returns("resolveInbound", "g:grp")
                .returns("lookServerEraEpoch", Look.asked(server))
                .returns("lookServerEpochAuthenticator", Look.asked(serverAuth))
                .returns("transportProfile", MlsProviderRpc.TransportProfile.conservativeDefault());
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", ee(1, 5), "epochAuth", new byte[] {1}));
        return f;
    }

    private static int keep(final FakeShellPort f, final MlsProviderRpc.ControlResult r) {
        return MlsCommitApplication.keepUnacknowledgedCommit(f.port(), MlsLogSink.NONE, "g:grp",
                grp(), Op.REMOVE, "remove", "grp", "+2", r, 4L);
    }

    @Test
    public void aCommitTheProviderPlainlyAnsweredIsNotHeld() {
        final ConvState cs = new ConvState();
        cs.unacknowledgedCommitEpoch = 9L;
        final int verdict = MlsProviderRpc.ControlResult.VERDICT_OK;
        // then the other test covers it
        if (MlsCommitApplication.ofVerdict(verdict).isSilent()) return;
        assertEquals(Integer.MIN_VALUE, keep(commitPort(cs, new long[] {1, 5}, new byte[] {1}),
                new MlsProviderRpc.ControlResult(verdict, null, null)));
        assertEquals(-1L, cs.unacknowledgedCommitEpoch);
    }

    @Test
    public void aSilentCommitIsCarriedOutExactlyAsThePolicyDisposesOfIt() {
        for (final long[] server : new long[][] {{1, 5}, {1, 4}, null}) {
            final ConvState cs = new ConvState();
            final FakeShellPort f = commitPort(cs, server, new byte[] {1});
            final int got = keep(f, null);   // no answer at all: the silent case
            final MlsCommitApplication.Reconciliation rec =
                    MlsCommitApplication.reconcile(server, 1, 4L, 5L);
            final MlsWelcomeAdmission.ServerState id = rec
                    == MlsCommitApplication.Reconciliation.SERVER_AT_OUR_EPOCH
                    ? MlsWelcomeAdmission.ServerState.MATCHES
                    : MlsWelcomeAdmission.ServerState.UNKNOWN;
            final MlsCommitApplication.Disposition d = MlsCommitApplication.disposition(
                    MlsCommitApplication.ofVerdict(
                            MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED), rec, false, id);
            if (d == MlsCommitApplication.Disposition.ROLL_BACK) {
                assertEquals(Integer.MIN_VALUE, got);
            } else if (d == MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS) {
                assertEquals(1, got);
                assertEquals(MlsProviderRpc.ControlResult.VERDICT_OK, cs.lastControlVerdict);
            } else {
                assertEquals(-1, got);
                assertEquals("the unacknowledged epoch is held", 5L, cs.unacknowledgedCommitEpoch);
            }
        }
    }


    private static boolean applyOne(final FakeShellPort f, final int status) {
        return MlsCommitApplication.applyOneInboundResult(f.port(), MlsLogSink.NONE, KEY,
                new MlsSession.ProcResult(status, new byte[0], -1), "+2", "grp", new byte[] {0, 1,
                0, 1}, "c1");
    }

    @Test
    public void eachEngineStatusGetsItsDisposition() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("convIfAny", null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        f.returns("pendingQueue",
                f.stub(MlsPendingQueueAccess.class, "load", new MlsPendingQueue()));
        assertTrue("OTHER changes nothing and is handled", applyOne(f, 3));
        assertFalse("MALFORMED is dropped", applyOne(f, 7));
        assertFalse("PAST-epoch is superseded", applyOne(f, 9));
        assertTrue("a COMMIT drains what it may have unblocked", applyOne(f, 1));
        assertTrue(f.calls.contains("MlsPendingQueueAccess.load"));
    }


    @Test
    public void ourOwnEchoAndAnEmptyControlAreSettledBeforeTheEngine() {
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("resolveInbound", KEY);
        f.returns("session", f.stub(MlsSession.class));
        assertTrue("our own control echoed back is not applied twice",
                MlsCommitApplication.applyInboundControl(
                MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "+1", "c1", new byte[] {1}, false,
                "grp"));
        assertFalse(MlsCommitApplication.applyInboundControl(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, "+2", "c1", new byte[0], false, "grp"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("MlsSession.process")));
    }

    /**
     * A peer's end_mls commit, applied on the receiving side, leaves the conversation where the
     * MLS seal refuses it (status DoneEndMls), so the app's MLS bit must be clear and the mark set
     * by then. Its reason carries column z, which defers the clear only until the commit lands.
     */
    @Test
    public void aPeersEndMlsCommitClearsTheBitOnceTheCommitHasLanded() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY));
        final boolean[] bit = {true};
        final boolean[] marked = {false};
        final FakeShellPort f = SplitFixtures.port(s).returns("resolveInbound", KEY)
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .on("offerInboundHold", a -> false).on("putGroup", a -> null)
                .returns("groups", new java.util.HashMap<String, Group>())
                .returns("telemetry", MlsTelemetry.NONE).on("flushGatedResends", a -> null)
                .returns("conversationIdFor", "c1").on("conversationMlsBit", a -> bit[0])
                .on("downgradeMlsScheme", a -> { bit[0] = false; return null; });
        f.returns("pendingQueue",
                f.stub(MlsPendingQueueAccess.class, "load", new MlsPendingQueue()));
        f.on("downgradeLocally", a -> {
            MlsDowngradeFlow.downgradeLocally(f.port(), MlsLogSink.NONE, (String) a[0],
                    (MlsDowngradeReason) a[1], (Boolean) a[2]);
            return null;
        });
        f.returns("reupgradeStore", f.stub(MlsReupgradeAccess.class,
                "markDowngrade", MlsReupgradeState.NONE,
                "load", (Function<Object[], Object>) a -> new MlsReupgradeState(0L, 0, marked[0]),
                "setEagerlyDowngraded", (Function<Object[], Object>) a -> {
                    marked[0] = (Boolean) a[1];
                    return null;
                }));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(1, 5),
                "epochAuth", new byte[] {1}, "endMlsPresent", true, "commitRequired", false,
                "lastStatus", MlsSession.OpStatus.OK, "memberValidity", null,
                "processResults", java.util.Collections.singletonList(new MlsEngineResult(
                        "inbound:" + KEY + ":c1", MlsProcStatus.COMMIT, 0, GID, new byte[0]))));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final boolean applied = MlsCommitApplication.applyInboundControl(MlsConfig.defaults(),
                f.port(), log, "+2", "c1", new byte[] {0, 1, 0, 1}, false, "grp");
        assertTrue(String.valueOf(log.lines), applied);

        assertTrue("precondition: the seal now refuses this conversation",
                MlsRecordState.hasEndMlsStatus(f.port(), MlsLogSink.NONE, KEY));
        assertFalse("the MLS bit outlived the end_mls commit it was waiting for", bit[0]);
        assertTrue("the mark that keeps the next send off MLS", marked[0]);
        final int landed = f.calls.indexOf("MlsSession.endMlsPresent");
        final int cleared = f.calls.indexOf("downgradeMlsScheme(c1)");
        assertTrue("cleared after the commit landed, not ahead of it", landed >= 0
                && cleared > landed);
        assertEquals("cleared once", cleared, f.calls.lastIndexOf("downgradeMlsScheme(c1)"));
    }
}
