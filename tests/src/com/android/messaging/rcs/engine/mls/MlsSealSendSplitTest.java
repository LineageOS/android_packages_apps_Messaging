/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import org.junit.Test;

public final class MlsSealSendSplitTest {


    /** A port for a seal on {@code grp}, whose sealed cache answers {@code cached} for every id. */
    private static FakeShellPort sealPort(final FakeRecords s, final MlsSealedMessage cached) {
        final FakeShellPort f = SplitFixtures.port(s).returns("convIfAny", null)
                .returns("transportProfile", MlsProviderRpc.TransportProfile.conservativeDefault());
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false, "eraEpoch",
                new byte[12], "nextAppGen", 1));
        f.returns("sealedCache", f.stub(MlsSealedCacheAccess.class, "get", cached));
        return f;
    }

    @Test
    public void anIdAlreadySealedReplaysItsCiphertextInsteadOfBurningAGeneration() {
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Era-ID", "0");
        final MlsSealedMessage cached =
                new MlsSealedMessage("m1", new byte[] {7, 7}, 0, 1, headers, 1L);
        final FakeShellPort f = sealPort(storeWith(b -> b), cached);
        final MlsSendPayload p =
                MlsSealSend.encryptForSend(f.port(), MlsLogSink.NONE, KEY, new byte[] {1}, "m1");
        assertEquals("message/mls", p.contentType);
        assertArrayEquals(new byte[] {7, 7}, p.body);
        assertEquals(headers, p.cpimHeaders);
        assertFalse(f.calls.contains("MlsSession.nextAppGen"));
        assertTrue(f.calls.indexOf("lock(" + KEY + ")") < f.calls.indexOf("unlock(" + KEY + ")"));
    }

    @Test
    public void noGroupOrADowngradedConversationIsNotSealed() {
        final FakeShellPort none = sealPort(storeWith(b -> b), null).returns("getGroup", null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsSealSend.encryptForSend(none.port(), log, KEY, new byte[] {1}, "m1"));
        assertTrue(log.said("W", "no group for"));
        final FakeShellPort down =
                sealPort(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)), null);
        final FakeShellPort.Log said = new FakeShellPort.Log();
        assertNull(MlsSealSend.encryptForSend(down.port(), said, KEY, new byte[] {1}, "m1"));
        assertTrue(said.said("I", "UNENCRYPTED; not sealing"));
        assertFalse(down.calls.contains("MlsSealedCacheAccess.get"));
    }


    private static MlsSendPayload sealed(final String id, final String era) {
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        if (id != null) h.put("Message-ID", id);
        if (era != null) h.put("Era-ID", era);
        return new MlsSendPayload("message/mls", new byte[] {7}, h);
    }

    @Test
    public void aPayloadWithoutItsSealedIdOrEraIsRefusedBeforeTheWire() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsSealSend.sendSealed(f.port(), log, "+2", sealed(null, "0")));
        assertFalse(MlsSealSend.sendSealed(f.port(), log, "+2", null));
        assertTrue(log.said("E", "missing id/era"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }

    @Test
    public void aRefusalIsRetriedOnceWithIdenticalBytes() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}));
        final MlsProviderRpc rpc = f.stub(MlsProviderRpc.class, "sendMlsCiphertext",
                new MlsProviderRpc.SendResult(false, 1, "busy"));
        f.returns("rpc", rpc);
        final String id = MlsSealSend.HDR_SEALED_MESSAGE_ID;
        final MlsSendPayload p = new MlsSendPayload("message/mls", new byte[] {7},
                new java.util.HashMap<>(java.util.Map.of(id, "m1", "Era-ID", "0")));
        assertFalse(MlsSealSend.sendSealed(f.port(), MlsLogSink.NONE, "+2", p));
        assertEquals(2,
                f.calls.stream().filter(c -> c.equals("MlsProviderRpc.sendMlsCiphertext")).count());
    }
}
