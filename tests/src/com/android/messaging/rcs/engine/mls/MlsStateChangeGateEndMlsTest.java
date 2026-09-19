/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsStateChangeGateEndMlsTest {


    private static byte[] ext(final int type, final int len) {
        return new byte[] {(byte) (type >> 8), (byte) type, (byte) (len >> 8), (byte) len};
    }

    @Test
    public void groupInfoHasEndMlsFindsF002AndSaysWhenItCouldNotLook() {
        final FakeShellPort f = new FakeShellPort();
        final byte[] gi = {1, 2, 3};
        final byte[] with = new byte[8];
        System.arraycopy(ext(0x000A, 4), 0, with, 0, 4);
        System.arraycopy(ext(0xF002, 0), 0, with, 4, 4);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsStateChangeGate.groupInfoHasEndMls(
                f.stub(MlsSession.class, "groupInfoExtTypes", with), log, gi));
        assertTrue(log.said("I", "0xF002=0B"));
        assertFalse(MlsStateChangeGate.groupInfoHasEndMls(
                f.stub(MlsSession.class, "groupInfoExtTypes", ext(0x000A, 4)), MlsLogSink.NONE,
                gi));

        final FakeShellPort.Log blind = new FakeShellPort.Log();
        assertFalse(MlsStateChangeGate.groupInfoHasEndMls(
                f.stub(MlsSession.class, "groupInfoExtTypes", null), blind, gi));
        assertTrue("an undecodable GroupInfo is answered 'no' OUT LOUD",
                blind.said("W", "could NOT decode"));
        assertFalse(MlsStateChangeGate.groupInfoHasEndMls(null, MlsLogSink.NONE, new byte[0]));
    }
}
