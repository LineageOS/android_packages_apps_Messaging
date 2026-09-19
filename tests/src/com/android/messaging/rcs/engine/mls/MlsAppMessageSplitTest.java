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
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsAppMessageSplitTest {


    private static byte[] eraEpoch(final int era) {
        final byte[] b = new byte[12];
        b[3] = (byte) era;
        return b;
    }

    @Test
    public void aPiggybackedCommitResetsCountersAdoptsTheNewEraPersistsAndIsHandedBackForDispatch() {
        final Group g = new Group();
        g.groupId = new byte[] {1};
        g.peerE164 = "+2";
        g.era = 3;
        g.sendsThisEpoch = 7;
        g.sendsSinceLeafRotation = 9;
        g.epochAuth = new byte[] {0x0a};
        final FakeShellPort f =
                new FakeShellPort().returns("selfE164", "+1").on("putGroup", a -> null);
        f.returns("session", f.stub(MlsSession.class,
                "encryptResults", java.util.Arrays.asList(
                        new MlsEngineResult("c", MlsProcStatus.OTHER, 0, new byte[0],
                                new byte[] {5}),
                        new MlsEngineResult("c", MlsProcStatus.COMMIT, 0, new byte[0],
                                new byte[] {6}),
                        new MlsEngineResult("c", MlsProcStatus.APP, 0, new byte[0],
                                new byte[] {7})),
                "eraEpoch", eraEpoch(4), "epochAuth", new byte[] {0x0b}));
        final PendingKeyUpdate[] out = new PendingKeyUpdate[1];
        assertArrayEquals(new byte[] {7}, MlsAppMessage.encryptDispatching(f.port(),
                MlsLogSink.NONE, g, "g:x", new byte[] {1}, new byte[0], "m1", out));
        assertEquals(0, g.sendsThisEpoch);
        assertEquals(0, g.sendsSinceLeafRotation);
        assertEquals(4, g.era);
        assertArrayEquals(new byte[] {0x0b}, g.epochAuth);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(g:x")));
        assertNotNull(out[0]);
        assertArrayEquals(new byte[] {6}, out[0].commit);
        assertArrayEquals("the OTHER result is the rollback snapshot", new byte[] {5},
                out[0].rollback);
        assertEquals(3, out[0].prevEra);
        assertArrayEquals(new byte[] {0x0a}, out[0].prevEpochAuth);
        assertEquals(7, out[0].prevSendsThisEpoch);
        assertEquals(9, out[0].prevSendsSinceLeafRotation);
    }

    @Test
    public void anEmptyResultListFallsBackToTheSingleEncryptAndNoCiphertextGetsExactlyOneRetry() {
        final Group g = new Group();
        g.groupId = new byte[] {1};
        g.peerE164 = "+2";
        final int[] singles = {0};
        final FakeShellPort f = new FakeShellPort().returns("selfE164", "+1");
        f.returns("session", f.stub(MlsSession.class, "encryptResults",
                java.util.Collections.emptyList(), "encryptWithAad",
                (Function<Object[], Object>) a -> { singles[0]++; return new byte[] {8}; }));
        assertArrayEquals(new byte[] {8}, MlsAppMessage.encryptDispatching(f.port(),
                MlsLogSink.NONE, g, "g:x", new byte[] {1}, new byte[0], "m1", null));
        assertEquals(1, singles[0]);

        singles[0] = 0;
        final FakeShellPort none = new FakeShellPort().returns("selfE164", "+1");
        none.returns("session",
                none.stub(MlsSession.class, "encryptResults", java.util.Collections.singletonList(
                new MlsEngineResult("c", MlsProcStatus.OTHER, 0, new byte[0], new byte[0])),
                "encryptWithAad",
                (Function<Object[], Object>) a -> { singles[0]++; return null; }));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsAppMessage.encryptDispatching(none.port(), log, g, "g:x", new byte[] {1},
                new byte[0], "m1", null));
        assertEquals("the ONE re-encrypt RCC.16 §11.3a allows, not two", 1, singles[0]);
        assertTrue(log.said("E", "NOT trying again"));
    }
}
