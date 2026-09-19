/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsResultBundleSplitTest {


    @Test
    public void theEnginesResultsAreHandedBackPerMessageAndASkewIsNotABadMessage() {
        final FakeShellPort f = new FakeShellPort();
        final MlsSession ok = f.stub(MlsSession.class, "processResults",
                java.util.Collections.singletonList(
                        new MlsEngineResult("ctx", 0, -1, GID, new byte[] {5})));
        final java.util.List<MlsSession.ProcResult> out =
                MlsResultBundle.inboundResults(ok, MlsLogSink.NONE, GID, new byte[] {1}, "ctx",
                        "+2");
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {5}, out.get(0).payload);
        final MlsSession skewed = f.stub(MlsSession.class, "processResults",
                (java.util.function.Function<Object[], Object>) a ->
                { throw new IllegalStateException("abi"); });
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsResultBundle.inboundResults(skewed, log, GID, new byte[] {1}, "ctx", "+2"));
        assertTrue(log.said("E", "this is a .so/Java build skew, not a bad message"));
    }
}
