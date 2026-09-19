/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsCommitSendSplitTest {


    /**
     * A port whose engine builds {@code art} for a rekey and whose ahead-of-server check answers
     * {@code hold}.
     */
    private static FakeShellPort commitPort(final MlsGroupArtifacts art,
            final MlsOutboundHold.Verdict hold, final int verdict) {
        final ConvState cs = new ConvState();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("convIfAny", cs)
                .returns("outboundHoldVerdict", hold).on("noteOutboundHoldRefused", a -> null)
                .returns("transportProfile", MlsProviderRpc.TransportProfile.conservativeDefault())
                .returns("groups", new java.util.HashMap<String, MlsTransportTypes.Group>())
                .on("flushGatedResends", a -> null);
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false, "lastStatus",
                MlsSession.OpStatus.OK,
                "epochAuth", new byte[] {1}, "eraEpoch", new byte[12], "exportGroupSnapshot",
                new byte[] {5},
                "restoreGroupSnapshot", true, "selfUpdate", art, "selfUpdateExtPub", art));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "applyMlsControl",
                new MlsProviderRpc.ControlResult(verdict, null, "t")));
        return f;
    }

    private static final MlsGroupArtifacts ART = new MlsGroupArtifacts(null, new byte[] {9},
            new byte[] {8}, new byte[] {7}, GID);

    private static int rekey(final FakeShellPort f) {
        return MlsCommitSend.commitAndSend(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                null, null, null, Op.REKEY, "rekey");
    }

    @Test
    public void anAcceptedRekeyReturnsTheEra() {
        final FakeShellPort f = commitPort(ART, MlsOutboundHold.Verdict.PUBLISH,
                MlsProviderRpc.ControlResult.VERDICT_OK);
        assertEquals(0, rekey(f));
        assertTrue(f.calls.contains("MlsProviderRpc.applyMlsControl"));
    }

    @Test
    public void noCommitFromTheEngineIsRolledBackAndNeverSent() {
        final FakeShellPort f = commitPort(null, MlsOutboundHold.Verdict.PUBLISH,
                MlsProviderRpc.ControlResult.VERDICT_OK);
        assertEquals(-1, rekey(f));
        assertTrue(f.calls.contains("MlsSession.restoreGroupSnapshot"));
        assertFalse(f.calls.contains("MlsProviderRpc.applyMlsControl"));
    }

    @Test
    public void theAheadFixtureCanRefuseThePublish() {
        final FakeShellPort f = commitPort(ART, MlsOutboundHold.Verdict.REFUSE,
                MlsProviderRpc.ControlResult.VERDICT_OK);
        assertEquals(-1, rekey(f));
        assertFalse(f.calls.contains("MlsProviderRpc.applyMlsControl"));
    }
}
