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

public final class MlsCredentialFloorSplitTest {

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
    public void expiredMemberCountSkipsUnreadableLeavesAndIsMinusOneWhenItCannotLook() {
        final long now = System.currentTimeMillis() / 1000L;
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(0, new long[] {now - 86400L, now - 1});
        v.put(1, new long[] {0L, 0L});
        v.put(2, new long[] {now - 86400L, now + 86400L});
        final FakeShellPort f = new FakeShellPort();
        assertEquals(1, MlsCredentialFloor.expiredMemberCount(
                f.stub(MlsSession.class, "memberValidity", v), group(new byte[] {1})));
        assertEquals(-1, MlsCredentialFloor.expiredMemberCount(null, group(new byte[] {1})));
        assertEquals(-1, MlsCredentialFloor.expiredMemberCount(
                f.stub(MlsSession.class, "memberValidity", new java.util.HashMap<>()),
                group(new byte[] {1})));
    }
}
