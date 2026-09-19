/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

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

public final class MlsServerMessageSplitTest {

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
    public void removeOnServerNotifyCommitsARemoveAndSaysSoWhenItFails() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:grp").returns("getGroup", group(new byte[] {1}))
                .returns("commitAndSend", -1);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsServerMessage.removeOnServerNotify(f.port(), log, "grp", null, null));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("commitAndSend(grp, null, null, , REMOVE")));
        assertTrue(log.said("W", "the spec says abandon"));
        assertEquals(-1, MlsServerMessage.removeOnServerNotify(new FakeShellPort()
                .returns("ensureSession", true).returns("resolveInbound", null).port(),
                MlsLogSink.NONE, "grp", null, null));
    }
}
