/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static org.junit.Assert.assertNotNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsPayloadCorruptorSplitTest {


    private static FakeShellPort prefsPort(final FakePrefs p) {
        return new FakeShellPort().returns("prefs", p);
    }

    private static FakeShellPort noPrefs() {
        return new FakeShellPort()
                .on("prefs", a -> { throw new IllegalStateException("no file"); });
    }

    @Test
    public void theForcedDecryptFailureFiresOncePerArmingAndThenIsClearedDurably() {
        final FakePrefs p = new FakePrefs();
        assertFalse(MlsPayloadCorruptor.consumeFailNextDecrypt(prefsPort(p).port()));
        p.values.put(MlsPayloadCorruptor.PREF_FAIL_NEXT_DECRYPT, true);
        assertTrue(MlsPayloadCorruptor.consumeFailNextDecrypt(prefsPort(p).port()));
        assertEquals(false, p.values.get(MlsPayloadCorruptor.PREF_FAIL_NEXT_DECRYPT));
        assertFalse(MlsPayloadCorruptor.consumeFailNextDecrypt(prefsPort(p).port()));
        assertFalse("an unreadable file is not an armed instrument",
                MlsPayloadCorruptor.consumeFailNextDecrypt(noPrefs().port()));
    }


    @Test
    public void theResendProbeArmsTheEngineOnlyWhenATagIsSet() {
        final Object[] armed = {"unset"};
        final FakeShellPort off = new FakeShellPort().returns("sysprops", new FakeSysProps());
        off.returns("session", off.stub(MlsSession.class,
                "setNextResentComponent",
                (Function<Object[], Object>) a -> { armed[0] = a[0]; return null; }));
        assertArrayEquals("m1".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                MlsPayloadCorruptor.buildAadWithProbe(off.port(), MlsLogSink.NONE, "m1", 1));
        assertNull("no tag: the engine is disarmed", armed[0]);
        final FakeShellPort user = new FakeShellPort().returns("sysprops",
                new FakeSysProps().set("debug.rcs.mls_resend_tag", 3));
        user.returns("session", user.stub(MlsSession.class,
                "setNextResentComponent",
                (Function<Object[], Object>) a -> { armed[0] = a[0]; return null; }));
        armed[0] = "unset";
        MlsPayloadCorruptor.buildAadWithProbe(user.port(), MlsLogSink.NONE, "m1", 1);
        assertNull("a user build ignores the tag, since adb can set it there", armed[0]);
        final FakeShellPort on = new FakeShellPort().returns("sysprops",
                new FakeSysProps().set("debug.rcs.mls_resend_tag", 3).set("ro.debuggable", 1));
        on.returns("session", on.stub(MlsSession.class,
                "setNextResentComponent",
                (Function<Object[], Object>) a -> { armed[0] = a[0]; return null; }));
        MlsPayloadCorruptor.buildAadWithProbe(on.port(), MlsLogSink.NONE, "m1", 1);
        final byte[] filler = new byte[MlsResentMessage.HMAC_FIELD_LEN];
        java.util.Arrays.fill(filler, (byte) 0x5A);
        assertArrayEquals(MlsResentMessage.encode(3, filler, MlsResentMessage.PrefixWidth.U8),
                (byte[]) armed[0]);
    }


    @Test
    public void aMalformedPayloadIsSentThroughTheOrdinaryGroupSend() {
        final FakeShellPort f = new FakeShellPort().returns("sendFramedToGroup", true);
        assertTrue(MlsPayloadCorruptor.sendMalformedPayloadToGroup(f.port(), MlsLogSink.NONE, "grp",
                "hi", MlsPayloadCorruptor.Mode.values()[0]));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("sendFramedToGroup(grp")));
    }


    @Test
    public void theReasonOverrideIsTakenOnceThenTheDefaultReturns() {
        final int ftd = RccNegativeDeliveryImdn.Reason.FAILED_TO_DECRYPT.code();
        assertEquals(ftd, MlsPayloadCorruptor.takeFtdReasonOverride(MlsLogSink.NONE));
        MlsPayloadCorruptor.armFtdReasonOverride(13);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(13, MlsPayloadCorruptor.takeFtdReasonOverride(log));
        assertTrue(log.said("W", "FAILURE-REASON OVERRIDE ACTIVE"));
        assertEquals(ftd, MlsPayloadCorruptor.takeFtdReasonOverride(MlsLogSink.NONE));
    }
}
