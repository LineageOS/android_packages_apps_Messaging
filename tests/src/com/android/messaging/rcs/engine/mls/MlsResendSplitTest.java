/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import java.util.function.Function;
import org.junit.Test;

public final class MlsResendSplitTest {


    /** A port whose ledger mints {@code newId} for a resend and whose group send answers true. */
    static FakeShellPort resendPort(final String newId, final byte[] stored, final String text) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("sendFramedToGroup", true)
                .returns("findTextByRcsMessageId", text);
        f.returns("resendLedger", f.stub(MlsResendLedgerAccess.class, "recordResend", newId,
                "rootOf", (Function<Object[], Object>) a -> a[0]));
        f.returns("pendingBodies", f.stub(MlsPendingBodyAccess.class, "get", stored));
        return f;
    }

    private static boolean sentToGroup(final FakeShellPort f, final String newId) {
        return f.calls.stream().anyMatch(c -> c.startsWith("sendFramedToGroup(grp, ")
                && c.endsWith(", mls-grp, " + newId + ")"));
    }

    @Test
    public void aResendIsRecordedBeforeItIsSentAndGoesOutUnderItsNewId() {
        final FakeShellPort f = resendPort("r2", null, null);
        assertTrue(MlsResend.sendResendNow(f.port(), MlsLogSink.NONE, "grp", "+2", "m1", "m1",
                new byte[] {1}, "g:grp", null));
        assertTrue(f.calls.indexOf("MlsResendLedgerAccess.recordResend") >= 0);
        assertTrue(sentToGroup(f, "r2"));
    }

    @Test
    public void aResendTheLedgerCannotRecordIsNotSent() {
        final FakeShellPort f = resendPort(null, null, null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsResend.sendResendNow(f.port(), log, "grp", "+2", "m1", "m1", new byte[] {1},
                "g:grp", null));
        assertTrue(log.said("E", "could not record a resend"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("sendFramedToGroup(")));
    }


    @Test
    public void anEmptyReframedBodyIsNotResentAndAGroupResendSkipsTheGate() {
        final FakeShellPort f = resendPort("r2", null, null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(
                MlsResend.resendFramed(f.port(), log, "grp", "+2", "m1", "m1", new byte[0], true));
        assertTrue(log.said("W", "EMPTY body"));
        assertTrue(MlsResend.resendFramed(f.port(), MlsLogSink.NONE, "grp", "+2", "m1", "m1",
                new byte[] {1}, false));
        assertTrue(sentToGroup(f, "r2"));
    }


    @Test
    public void theFiveArgumentResendFramedAllowsADeferral() {
        final FakeShellPort f = resendPort("r3", null, null);
        assertTrue(MlsResend.resendFramed(f.port(), MlsLogSink.NONE, "grp", "+2", "m1", "m1",
                new byte[] {1}));
        assertTrue(sentToGroup(f, "r3"));
    }


    @Test
    public void theStoredBodyIsPreferredAndTheChatTextIsTheFallback() {
        final FakeShellPort stored = resendPort("r4", new byte[] {5}, null);
        assertTrue(
                MlsResend.resendOriginal(stored.port(), MlsLogSink.NONE, "grp", "+2", "m1", true));
        assertFalse(stored.calls.stream().anyMatch(c -> c.startsWith("findTextByRcsMessageId(")));
        final FakeShellPort text = resendPort("r5", null, "hello");
        assertTrue(MlsResend.resendOriginal(text.port(), MlsLogSink.NONE, "grp", "+2", "m1", true));
        assertTrue(sentToGroup(text, "r5"));
        final FakeShellPort neither = resendPort("r6", null, null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsResend.resendOriginal(neither.port(), log, "grp", "+2", "m1", true));
        assertTrue(log.said("W", "no stored text for m1"));
    }


    @Test
    public void theThreeArgumentResendOriginalResendsFromTheStoredBody() {
        final FakeShellPort f = resendPort("r7", new byte[] {5}, null);
        assertTrue(MlsResend.resendOriginal(f.port(), MlsLogSink.NONE, "grp", "+2", "m1"));
        assertTrue(sentToGroup(f, "r7"));
    }


    @Test
    public void aUserResendNeedsAnIdAndRunsTheChain() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(
                MlsResend.resendByUser(resendPort("r8", null, null).port(), log, "grp", "+2", ""));
        assertTrue(log.said("W", "no rcs message id"));
        final FakeShellPort f = resendPort("r9", new byte[] {5}, null);
        assertTrue(MlsResend.resendByUser(f.port(), MlsLogSink.NONE, "grp", "+2", "m1"));
        assertTrue(sentToGroup(f, "r9"));
    }


    @Test
    public void openingTheGateFlushesEveryDeferredResendOffThread() {
        final ConvState cs = new ConvState();
        cs.gatedResends.add(new DeferredResend("grp", "+2", "m1", "m1", new byte[] {1}));
        final FakeShellPort f = resendPort("r2", null, null).returns("convIfAny", cs);
        f.returns("offThread", f.stub(MlsOffThread.class, "run", (Function<Object[], Object>) a -> {
            ((Runnable) a[1]).run();
            return null;
        }));
        MlsResend.flushGatedResends(f.port(), MlsLogSink.NONE, KEY);
        assertTrue(cs.gatedResends.isEmpty());
        assertTrue(sentToGroup(f, "r2"));
    }
}
