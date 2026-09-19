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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
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

public final class MlsFtdEscalationSplitTest {


    @Test
    public void reportFtdWithoutHealingQueuesTheReportThenFlushes() {
        final ConvState cs = new ConvState();
        final FakeShellPort f =
                new FakeShellPort().returns("conv", cs).on("flushFtdReports", a -> null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFtdEscalation.reportFtdWithoutHealing(f.port(), log, "grp", "+2", "m1");
        assertEquals("+2", cs.ftdPending.get("m1"));
        assertTrue(f.calls.contains("flushFtdReports(grp, +2)"));
        assertTrue(log.said("W", "This is an instrument"));
    }


    @Test
    public void theFtdResendCountIsPerMessageAndPersisted() {
        final FakeRecords s = storeWith(b -> b);
        assertEquals(1,
                MlsFtdEscalation.bumpFtdResendCount(port(s).port(), MlsLogSink.NONE, KEY, "m1"));
        assertEquals(2,
                MlsFtdEscalation.bumpFtdResendCount(port(s).port(), MlsLogSink.NONE, KEY, "m1"));
        assertEquals(1,
                MlsFtdEscalation.bumpFtdResendCount(port(s).port(), MlsLogSink.NONE, KEY, "m2"));
        assertEquals(Integer.valueOf(2), rec(s).ftdResendCounts.get("m1"));
        assertEquals(1,
                MlsFtdEscalation.bumpFtdResendCount(port(s).port(), MlsLogSink.NONE, null, "m1"));
    }


    private static byte[] ee(final int era, final long epoch) {
        final byte[] b = new byte[12];
        b[0] = (byte) (era >>> 24); b[1] = (byte) (era >>> 16); b[2] = (byte) (era >>> 8); b[3] =
                (byte) era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (epoch >>> (56 - 8 * i));
        return b;
    }

    private static byte[] ciphertextAt(final long epoch) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x00); o.write(0x01); o.write(0x00); o.write(MlsWireScan.WF_PRIVATE_MESSAGE);
        o.write(GID.length); o.write(GID, 0, GID.length);
        for (int i = 7; i >= 0; i--) o.write((int) ((epoch >>> (i * 8)) & 0xFF));
        o.write(1); o.write(0xCC);
        return o.toByteArray();
    }

    private static FakeShellPort failurePort(final ConvState cs, final int healed) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("convIfAny", cs)
                .on("park", a -> null).returns("selfHeal", healed);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", ee(0, 3)));
        return f;
    }

    @Test
    public void aFutureCiphertextIsParkedRatherThanTreatedAsAFailure() {
        final FakeShellPort f = failurePort(new ConvState(), 0);
        MlsFtdEscalation.onDecryptFailure(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2", "m1", ciphertextAt(5), -1L);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("park(")));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("selfHeal")));
    }

    @Test
    public void aFailureIsRecordedAndHealedAndAnUnreadableServerStillReportsTheFailure() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = failurePort(cs, MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE)
                .on("flushFtdReports", a -> null);
        MlsFtdEscalation.onDecryptFailure(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2", "m1", null, -1L);
        assertEquals("+2", cs.ftdPending.get("m1"));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("selfHeal(grp, +2")));
        assertTrue("the sender is still told, so it can resend",
                f.calls.contains("flushFtdReports(grp, +2)"));
        assertFalse("and the server we could not reach is not asked again",
                f.calls.stream().anyMatch(c -> c.startsWith("lookServerEpochAuthenticator")));
        final FakeShellPort none = new FakeShellPort();
        MlsFtdEscalation.onDecryptFailure(MlsConfig.defaults(), none.port(), MlsLogSink.NONE, "grp",
                null, "m1", null, -1L);
        assertTrue(none.calls.isEmpty());
    }


    @Test
    public void theResendBudgetCountsThisReportAgainstTheWindow() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "resendsToPeerSince", 0));
        assertFalse(MlsFtdEscalation.resendBudgetSpent(f.port(), MlsLogSink.NONE, "g:grp", "+2",
                "m1", "t"));
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "resendsToPeerSince",
                MlsResendBudget.MAX_PER_WINDOW - 1));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsFtdEscalation.resendBudgetSpent(f.port(), log, "g:grp", "+2", "m1", "t"));
        assertTrue(log.said("W", "NOT resending m1"));
    }


    @Test
    public void forgetResendHistoryDropsTheConversationsRowsFromTheLedger() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "forgetConversation", 3));
        assertEquals(3,
                MlsFtdEscalation.forgetResendHistory(f.port(), MlsLogSink.NONE, "grp", "+2"));
        assertTrue(f.calls.contains("MlsResendLedgerAccess.forgetConversation"));
    }


    /** Our epoch authenticator is {1}; the server answers {@code server} to every look. */
    private static FakeShellPort escalationPort(final Look<byte[]> server, final int era) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("lookServerEpochAuthenticator", server).returns("eraAdvance", era)
                .on("selfHeal", a -> 0);
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}));
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "forgetConversation", 4));
        return f;
    }

    @Test
    public void inStateWithTheServerTheEscalationAdvancesTheEraAndResetsTheLadder() {
        final FakeShellPort f = escalationPort(Look.asked(new byte[] {1}), 3);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFtdEscalation.escalateForDivergedPeer(f.port(), log, "grp", "+2", "test");
        assertTrue(f.calls.contains("MlsResendLedgerAccess.forgetConversation"));
        assertTrue(log.said("I", "Released 4 resend row(s)"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("selfHeal(")));
    }

    @Test
    public void aRefusedLookDefersAndADivergedSelfThatCannotHealAbandons() {
        final FakeShellPort refused = escalationPort(Look.refusedByLedger("no"), 3);
        final FakeShellPort.Log deferred = new FakeShellPort.Log();
        MlsFtdEscalation.escalateForDivergedPeer(refused.port(), deferred, "grp", "+2", "test");
        assertTrue(deferred.said("W", "DEFERRED"));
        assertFalse(refused.calls.stream().anyMatch(c -> c.startsWith("eraAdvance(")));
        final FakeShellPort diverged = escalationPort(Look.asked(new byte[] {2}), 3);
        final FakeShellPort.Log abandoned = new FakeShellPort.Log();
        MlsFtdEscalation.escalateForDivergedPeer(diverged.port(), abandoned, "grp", "+2", "test");
        assertTrue(diverged.calls.stream().anyMatch(c -> c.startsWith("selfHeal(")));
        assertTrue(abandoned.said("E", "escalation ABANDONED"));
        assertFalse(diverged.calls.contains("MlsResendLedgerAccess.forgetConversation"));
    }

    @Test
    public void anEraAdvanceThatCouldNotLookIsNotReadAsAFailure() {
        final FakeShellPort f = escalationPort(Look.asked(new byte[] {1}),
                MlsEraAdvance.ERA_ADVANCE_LOOK_UNAVAILABLE);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFtdEscalation.escalateForDivergedPeer(f.port(), log, "grp", "+2", "test");
        assertTrue(log.said("W", "era advance NOT ATTEMPTED"));
        assertFalse(f.calls.contains("MlsResendLedgerAccess.forgetConversation"));
    }


    private static FakeShellPort reportPort() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("findGroupIdByRcsMessageId", null)
                .returns("convIfAny", null).returns("conv", new MlsTransportTypes.ConvState());
        f.returns("sealedCache",
                f.stub(MlsSealedCacheAccess.class, "invalidateForReEncrypt", null));
        f.returns("peerGuard",
                f.stub(MlsPeerGuards.class, "notePeerFailure", null, "notePeerRecovered", null));
        return f;
    }

    private static int serverCode(final RccNegativeDeliveryImdn.Disposition d) {
        for (final RccNegativeDeliveryImdn.ServerReason r :
                RccNegativeDeliveryImdn.ServerReason.values()) {
            if (r.disposition() == d) return r.code();
        }
        throw new AssertionError("no server reason with disposition " + d);
    }

    @Test
    public void aTransientServerRejectionNeedsNoRepair() {
        final FakeShellPort f = reportPort();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFtdEscalation.onPeerReportedFailure(MlsConfig.defaults(), f.port(), log, null, "+2",
                "m1", serverCode(RccNegativeDeliveryImdn.Disposition.RETRYABLE));
        assertTrue(log.said("W", "TRANSIENT error for m1"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("selfHeal(")));
    }

    @Test
    public void anUnmappedPeerReasonTakesNoActionAndAMissingIdIsDropped() {
        final FakeShellPort f = reportPort();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFtdEscalation.onPeerReportedFailure(MlsConfig.defaults(), f.port(), log, null, "+2",
                "m1", 99);
        assertTrue(log.said("W", "unmapped negative-delivery reason 99"));
        final FakeShellPort none = reportPort();
        MlsFtdEscalation.onPeerReportedFailure(MlsConfig.defaults(), none.port(), MlsLogSink.NONE,
                null, "+2", null, 4);
        assertTrue(none.calls.isEmpty());
    }


    @Test
    public void aPendingReportIsSentOnceWithItsReasonAndAHealInFlightHoldsIt() {
        final ConvState cs = new ConvState();
        cs.ftdPending.put("m1", "+2");
        final Object[][] sent = {null};
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("convIfAny", cs)
                .returns("conv", cs);
        f.returns("session", f.stub(MlsSession.class));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "sendMlsNegativeDeliveryImdn",
                (Function<Object[], Object>) a -> { sent[0] = a; return true; }));
        MlsFtdEscalation.flushFtdReports(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2");
        assertEquals("m1", sent[0][0]);
        assertEquals(RccNegativeDeliveryImdn.Reason.FAILED_TO_DECRYPT.code(), sent[0][3]);
        assertTrue(cs.ftdPending.isEmpty());
        sent[0] = null;
        MlsFtdEscalation.flushFtdReports(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2");
        assertEquals("nothing pending, nothing sent", null, sent[0]);
    }
}
