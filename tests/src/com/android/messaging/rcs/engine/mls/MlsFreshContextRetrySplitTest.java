/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static org.junit.Assert.assertSame;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsFreshContextRetrySplitTest {


    private static final MlsProviderRpc.ControlResult OK =
            new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null, null);

    private static MlsGroupArtifacts art() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID);
    }

    @Test
    public void aSilentNonAdvanceIsRetriedWithAFreshContextIdAndVerified() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("sysprops", new FakeSysProps())
                .returns("conv", cs)
                .returns("lookServerEraEpoch", Look.asked(new long[] {5, 0}));
        final MlsProviderRpc rpc = f.stub(MlsProviderRpc.class, "createMlsConversation", OK);
        final Look<long[]> got = MlsFreshContextRetry.freshContextIdRetry(f.port(), MlsLogSink.NONE,
                rpc, KEY, "grp", "+2", art(), 5, OK, true, new long[] {4, 0});
        assertArrayEquals(new long[] {5, 0}, got.orNull());
        assertTrue(f.calls.contains("MlsProviderRpc.createMlsConversation"));
        assertEquals("the re-dial's verdict is recorded for the drive loop", OK.verdict,
                cs.lastControlVerdict);
    }

    @Test
    public void nothingIsRetriedUnlessTheCreateWasAcceptedAndTheEraProvablyDidNotMove() {
        final FakeShellPort f = new FakeShellPort().returns("sysprops", new FakeSysProps());
        final MlsProviderRpc rpc = f.stub(MlsProviderRpc.class);
        assertNull(MlsFreshContextRetry.freshContextIdRetry(f.port(), MlsLogSink.NONE, rpc, KEY,
                "grp", "+2", art(), 5,
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, null), true,
                new long[] {4, 0}));
        assertNull("the era moved: the create took", MlsFreshContextRetry.freshContextIdRetry(
                f.port(),
                MlsLogSink.NONE, rpc, KEY, "grp", "+2", art(), 5, OK, true, new long[] {5, 0}));
        assertNull(MlsFreshContextRetry.freshContextIdRetry(f.port(), MlsLogSink.NONE, rpc, KEY,
                "grp", "+2", art(), 5, OK, false, null));
        assertFalse(f.calls.contains("MlsProviderRpc.createMlsConversation"));
    }


    @Test
    public void aGroupReCreateReusesTheServersContextIdUnlessTheOperatorTurnsItOff() {
        assertTrue(MlsFreshContextRetry.groupReCreateReusesTheServersContextId(
                new FakeShellPort().returns("sysprops", new FakeSysProps()).port()));
        assertFalse(MlsFreshContextRetry.groupReCreateReusesTheServersContextId(new FakeShellPort()
                .returns("sysprops",
                        new FakeSysProps().set("debug.rcs.mls_ctxid_group_engine_id", 0)).port()));
    }
}
