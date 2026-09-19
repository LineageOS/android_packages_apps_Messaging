/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsRekeyPolicySplitTest {


    @Test
    public void everySendIsCountedAndTheUsageLimitRekeysOutOfBand() {
        final Group g = grp();
        g.sendsSinceLeafRotation = MlsRekeyPolicy.REKEY_AFTER_SENDS - 2;
        final FakeShellPort f = new FakeShellPort().returns("getGroup", g).on("putGroup", a -> null)
                .on("noteMlsPlaneInUse", a -> null).returns("commitAndSend", 4);
        MlsRekeyPolicy.noteSendAndMaybeRekey(f.port(), MlsLogSink.NONE, "g:grp", "+2", "grp");
        assertEquals(1, g.sendsThisEpoch);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("commitAndSend")));
        MlsRekeyPolicy.noteSendAndMaybeRekey(f.port(), MlsLogSink.NONE, "g:grp", "+2", "grp");
        assertTrue(f.calls.stream().anyMatch(
                c -> c.startsWith("commitAndSend(grp, +2, null, null, REKEY, usage-rekey")));
        assertTrue(f.calls.contains("noteMlsPlaneInUse(g:grp)"));
    }


    private static PendingKeyUpdate pending(final byte[] rollback) {
        return new PendingKeyUpdate(new byte[] {1}, rollback, GID, KEY, "+2", "grp", new byte[] {3},
                4L, new byte[] {5}, 300, 300);
    }

    @Test
    public void aRefusedKeyUpdateRestoresTheEngineAndBacksTheCounterOff() {
        final Group g = grp();
        final FakeShellPort f = new FakeShellPort().returns("getGroup", g).on("putGroup", a -> null)
                .on("lock", a -> null).on("unlock", a -> null);
        f.returns("session", f.stub(MlsSession.class, "restoreGroupSnapshot", true));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "applyMlsControl",
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no")));
        MlsRekeyPolicy.dispatchPendingKeyUpdate(f.port(), MlsLogSink.NONE, pending(new byte[] {2}));
        assertTrue(f.calls.contains("MlsSession.restoreGroupSnapshot"));
        assertEquals(4, g.era);
        assertArrayEquals(new byte[] {5}, g.epochAuth);
        assertEquals(MlsRekeyPolicy.counterAfterRefusal(), g.sendsSinceLeafRotation);
    }

    @Test
    public void anAcceptedKeyUpdateTouchesNothingAndARefusalWithoutASnapshotSaysSo() {
        final FakeShellPort ok = new FakeShellPort();
        ok.returns("rpc", ok.stub(MlsProviderRpc.class, "applyMlsControl",
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null,
                        null)));
        MlsRekeyPolicy.dispatchPendingKeyUpdate(ok.port(), MlsLogSink.NONE,
                pending(new byte[] {2}));
        assertFalse(ok.calls.stream().anyMatch(c -> c.startsWith("lock(")));
        final FakeShellPort bare = new FakeShellPort();
        bare.returns("rpc", bare.stub(MlsProviderRpc.class, "applyMlsControl", null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRekeyPolicy.dispatchPendingKeyUpdate(bare.port(), log, pending(null));
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("E ")));
    }
}
