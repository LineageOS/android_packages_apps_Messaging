/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnCheck;
import org.junit.Test;

public final class MlsGroupSendSplitTest {


    private static MlsSealedMessage sealedWith(final String authHeader) {
        final java.util.Map<String, String> h = new java.util.HashMap<>();
        if (authHeader != null) h.put("x-cached-epoch-authenticator", authHeader);
        return new MlsSealedMessage("m1", new byte[] {7}, 0, 1, h, 1L);
    }

    @Test
    public void theCachedEpochAuthenticatorDecodesAndABadOneIsAbsent() {
        final FakeShellPort f = new FakeShellPort()
                .on("base64Decode", a -> java.util.Base64.getDecoder().decode((String) a[0]));
        assertArrayEquals(new byte[] {1, 2, 3},
                MlsGroupSend.decodeEpochAuthHeader(f.port(), sealedWith("AQID")));
        assertNull(MlsGroupSend.decodeEpochAuthHeader(f.port(), sealedWith(null)));
        assertNull(MlsGroupSend.decodeEpochAuthHeader(f.port(), null));
        final FakeShellPort bad = new FakeShellPort().on("base64Decode", a -> {
            throw new IllegalArgumentException("bad");
        });
        assertNull(MlsGroupSend.decodeEpochAuthHeader(bad.port(), sealedWith("!!")));
    }


    private static FakeShellPort groupSendPort(final FakeRecords s, final MlsSealedMessage cached) {
        final FakeShellPort f = SplitFixtures.port(s)
                .on("base64Decode", a -> java.util.Base64.getDecoder().decode((String) a[0]));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12], "nextAppGen", 1));
        f.returns("sealedCache", f.stub(MlsSealedCacheAccess.class, "get", cached));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "sendGroupMlsCiphertext",
                new MlsProviderRpc.SendResult(true, 0, "ok")));
        return f;
    }

    @Test
    public void anIdAlreadySealedToTheGroupIsReplayedNotReEncrypted() {
        final FakeShellPort f = groupSendPort(storeWith(b -> b), sealedWith("AQID"));
        assertTrue(MlsGroupSend.sendFramedToGroup(f.port(), MlsLogSink.NONE, "grp", new byte[] {1},
                "mls-grp", "m1"));
        assertTrue(f.calls.contains("MlsProviderRpc.sendGroupMlsCiphertext"));
        assertFalse(f.calls.contains("MlsSession.encryptWithAad"));
    }

    @Test
    public void aDowngradedGroupIsNotSealed() {
        final FakeShellPort f =
                groupSendPort(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)), null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsGroupSend.sendFramedToGroup(f.port(), log, "grp", new byte[] {1}, "mls-grp",
                "m1"));
        assertTrue(log.said("I", "is DOWNGRADED"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }
}
