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

public final class MlsUpgradePolicySplitTest {


    @Test
    public void rollBackDiscardedCreateKeepsWhatWasBuiltWhenThereIsNothingToRollBackTo() {
        final FakeShellPort f = new FakeShellPort();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsUpgradePolicy.rollBackDiscardedCreate(null, log, new byte[] {1}, null));
        assertTrue(log.said("I", "KEEPING what it built"));
        assertTrue(MlsUpgradePolicy.rollBackDiscardedCreate(
                f.stub(MlsSession.class, "restoreGroupSnapshot", true), MlsLogSink.NONE,
                new byte[] {1}, new byte[] {2}));
        assertFalse(MlsUpgradePolicy.rollBackDiscardedCreate(f.stub(MlsSession.class,
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { throw new IllegalStateException(); }),
                MlsLogSink.NONE, new byte[] {1}, new byte[] {2}));
        assertFalse(MlsUpgradePolicy.rollBackDiscardedCreate(null, MlsLogSink.NONE, null,
                new byte[] {2}));
    }
}
