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

public final class MlsFloorRebuildReportLogTest {


    @Test
    public void logFloorReportNeverReadsAnUnreadableRosterAsHealthy() {
        final MlsConfig cfg = MlsConfig.defaults();
        final FakeShellPort.Log none = new FakeShellPort.Log();
        MlsFloorRebuild.logFloorReport(cfg, none, "g:x", null, "probe");
        assertTrue(none.said("I", "NOT concluding that it is healthy"));

        final long now = System.currentTimeMillis() / 1000L;
        final java.util.Map<Integer, long[]> healthy = new java.util.HashMap<>();
        healthy.put(0, new long[] {now - 86400L, now + 70 * 86400L});
        final FakeShellPort.Log ok = new FakeShellPort.Log();
        MlsFloorRebuild.logFloorReport(cfg, ok, "g:x", MlsCredentialFloor.classify(healthy,
                java.util.Collections.emptyMap(), now, cfg.kpMinRemainingDays), "probe");
        assertTrue(ok.said("I", "all above the " + cfg.kpMinRemainingDays + "-day floor"));

        final java.util.Map<Integer, long[]> inside = new java.util.HashMap<>(healthy);
        inside.put(1, new long[] {now - 86400L, now + 3 * 86400L});
        final FakeShellPort.Log bad = new FakeShellPort.Log();
        MlsFloorRebuild.logFloorReport(cfg, bad, "g:x", MlsCredentialFloor.classify(inside,
                java.util.Collections.emptyMap(), now, cfg.kpMinRemainingDays), "probe");
        assertTrue(bad.said("W", "INSIDE RCC.16's"));
    }
}
