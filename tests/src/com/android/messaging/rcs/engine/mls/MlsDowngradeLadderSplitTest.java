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

public final class MlsDowngradeLadderSplitTest {


    @Test
    public void downgradeFromEngineStatusFunnelsOnlyStatusesThatNameAReason() {
        for (int st = 0; st <= 40; st++) {
            final MlsDowngradeReason r = MlsDowngradeReason.forEngineHealthStatus(st);
            if (r != null && st != MlsHealthStates.CANNOTHEALDURINGENDMLS) continue;
            final FakeShellPort f = new FakeShellPort().on("downgradeLocally", a -> null);
            final FakeShellPort.Log log = new FakeShellPort.Log();
            MlsDowngradeLadder.downgradeFromEngineStatus(f.port(), log, "g:x", st);
            assertEquals("status " + st, r != null, f.calls.stream()
                    .anyMatch(c -> c.startsWith("downgradeLocally(g:x, " + r)));
            if (st == MlsHealthStates.CANNOTHEALDURINGENDMLS) assertTrue(
                    log.said("W", "WEDGED in end-mls"));
        }
    }
}
