/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
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

public final class MlsResendBudgetSplitTest {

    private static Group group(final byte[] id) {
        final Group g = new Group();
        g.groupId = id;
        return g;
    }

    private static byte[] packed(final String... records) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (final String r : records) {
            final byte[] b = r.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(0); o.write(0); o.write(0); o.write(b.length);
            o.write(b, 0, b.length);
        }
        return o.toByteArray();
    }

    @Test
    public void resendMacCandidatesNamesEveryShapeAndConcatenatesGroupIdThenMsisdn() {
        final java.util.Map<String, byte[]> c = MlsResendBudget.resendMacCandidates(
                new FakeShellPort()
                .returns("selfE164", "+15").port(), group(new byte[] {9}));
        assertEquals(java.util.Arrays.asList("msisdn-e164", "msisdn-digits", "msisdn-tel-uri",
                "group-id", "group-id||msisdn"), new java.util.ArrayList<>(c.keySet()));
        assertArrayEquals("15".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                c.get("msisdn-digits"));
        assertArrayEquals(new byte[] {9, '+', '1', '5'}, c.get("group-id||msisdn"));
        assertEquals(java.util.Collections.singletonList("group-id"), new java.util.ArrayList<>(
                MlsResendBudget.resendMacCandidates(
                        new FakeShellPort().returns("selfE164", "").port(),
                        group(new byte[] {9})).keySet()));
    }


    @Test
    public void deferGatedResendParksUntilTheQueueIsFull() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("conv", cs);
        int parked = 0;
        while (!MlsRecoveryPolicy.gatedResendQueueFull(parked)) {
            assertTrue(MlsResendBudget.deferGatedResend(f.port(), MlsLogSink.NONE, "g:x",
                    new DeferredResend("grp", "+1", "m" + parked, "r", new byte[0])));
            parked++;
        }
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsResendBudget.deferGatedResend(f.port(), log, "g:x",
                new DeferredResend("grp", "+1", "late", "r", new byte[0])));
        assertEquals(parked, cs.gatedResends.size());
        assertTrue(log.lines.get(0).startsWith("W "));
    }


    @Test
    public void convergenceReleasesTheSendGateAndFlushesWhatItHeld() {
        final ConvState cs = new ConvState();
        cs.gateOpenedAt = 5L;
        final FakeShellPort f =
                new FakeShellPort().returns("convIfAny", cs).on("flushGatedResends", a -> null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsResendBudget.onPeerConverged(f.port(), log, "g:grp");
        assertEquals(0L, cs.gateOpenedAt);
        assertTrue(log.said("I", "releasing send-gate for g:grp"));
        assertTrue(f.calls.contains("flushGatedResends(g:grp)"));
        final FakeShellPort none =
                new FakeShellPort().returns("convIfAny", null).on("flushGatedResends", a -> null);
        MlsResendBudget.onPeerConverged(none.port(), MlsLogSink.NONE, "g:grp");
        assertTrue("no state is still a flush", none.calls.contains("flushGatedResends(g:grp)"));
    }


    private static final MlsProviderRpc.TransportProfile ACKS =
            new MlsProviderRpc.TransportProfile(false, true, false, false);

    @Test
    public void theSendGateOpensOnlyWhereTheTransportRequiresAConvergenceAck() {
        final ConvState cs = new ConvState();
        MlsResendBudget.openGate(
                new FakeShellPort().returns("transportProfile", ACKS).returns("conv", cs)
                .returns("elapsedRealtime", 5000L).port(), MlsLogSink.NONE, "g:grp");
        assertEquals(5000L, cs.gateOpenedAt);
        final ConvState untouched = new ConvState();
        MlsResendBudget.openGate(new FakeShellPort().returns("transportProfile",
                MlsProviderRpc.TransportProfile.conservativeDefault()).returns("conv", untouched)
                .port(), MlsLogSink.NONE, "g:grp");
        assertEquals(0L, untouched.gateOpenedAt);
    }


    @Test
    public void anOpenGateBlocksSendsUntilItExpiresAndThenFlushesWhatItHeld() {
        final ConvState cs = new ConvState();
        cs.gateOpenedAt = 5000L;
        final FakeShellPort open = new FakeShellPort().returns("transportProfile", ACKS)
                .returns("convIfAny", cs)
                .returns("elapsedRealtime", 5001L);
        assertTrue(MlsResendBudget.sendBlockedByGate(open.port(), MlsLogSink.NONE, "g:grp"));
        final FakeShellPort late = new FakeShellPort().returns("transportProfile", ACKS)
                .returns("convIfAny", cs)
                .returns("elapsedRealtime", 5000L + 365L * 86400_000L)
                .on("flushGatedResends", a -> null);
        assertFalse(MlsResendBudget.sendBlockedByGate(late.port(), MlsLogSink.NONE, "g:grp"));
        assertEquals("an expired gate is closed", 0L, cs.gateOpenedAt);
        assertTrue(late.calls.contains("flushGatedResends(g:grp)"));
        assertFalse("no ack required: never blocked", MlsResendBudget.sendBlockedByGate(
                new FakeShellPort()
                .returns("transportProfile", MlsProviderRpc.TransportProfile.conservativeDefault())
                .port(), MlsLogSink.NONE, "g:grp"));
    }
}
