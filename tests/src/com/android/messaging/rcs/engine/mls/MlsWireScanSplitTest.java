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

public final class MlsWireScanSplitTest {

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
    public void aServerPackCarriesAGroupInfoOnlyIfItsFirstRecordIsNonEmpty() {
        assertTrue(MlsWireScan.hasCarry(ServerPack.of(packed("gi"))));
        assertFalse(MlsWireScan.hasCarry(ServerPack.of(packed(""))));
        assertFalse(MlsWireScan.hasCarry(ServerPack.none(MlsServerPackOutcome.LOOK_FAILED)));
        assertFalse(MlsWireScan.hasCarry(null));
    }
}
