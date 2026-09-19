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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
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

public final class MlsKeyPackageClaimsSplitTest {


    @Test
    public void logClaimBlockedWarnsWithItsClassPrefix() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsKeyPackageClaims.logClaimBlocked(log, "x");
        assertEquals(java.util.Collections.singletonList("W MlsKeyPackageClaims: x"), log.lines);
    }


    @Test
    public void claimForUpgradeIsAllOrNothingOnALedgerRefusal() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true).on("claimAll",
                a -> "+3".equals(a[1]) ? Claim.refusedByLedger("rationed")
                        : Claim.asked(java.util.Collections.singletonList(new byte[] {1})));
        final MlsUpgradeClaim ok = MlsKeyPackageClaims.claimForUpgrade(f.port(), MlsLogSink.NONE,
                java.util.Arrays.asList("+1", "+2"));
        assertFalse(ok.refused());
        assertTrue(ok.covers("+1") && ok.covers("+2"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final MlsUpgradeClaim no = MlsKeyPackageClaims.claimForUpgrade(f.port(), log,
                java.util.Arrays.asList("+1", "+3", "+2"));
        assertTrue("a partial count would read as 'not enough key packages'", no.refused());
        assertEquals("rationed", no.why());
        assertTrue(log.said("W", "ABANDONED after 1 of 3"));
        assertFalse(MlsKeyPackageClaims.claimForUpgrade(new FakeShellPort()
                .returns("ensureSession", false).port(), MlsLogSink.NONE,
                java.util.Arrays.asList("+1")).refused());
    }


    @Test
    public void gatherInitialKeyPackagesRefusesTheCreateOnAnyMissingOrUnusablePackage() {
        final FakeShellPort f = new FakeShellPort()
                .on("claimAll",
                        a -> Claim.asked(java.util.Arrays.asList(new byte[] {1}, new byte[] {2})))
                .on("keyPackageUsable", a -> ((byte[]) a[0])[0] != 9);
        assertEquals(4, MlsKeyPackageClaims.gatherInitialKeyPackages(f.port(), MlsLogSink.NONE,
                java.util.Arrays.asList("+2", "+3"), null).size());
        assertEquals("every claim charges GROUP_ESTABLISH", 2, f.calls.stream()
                .filter(c -> c.startsWith("claimAll(" + MlsClaimLedger.Caller.GROUP_ESTABLISH))
                .count());

        f.on("claimAll", a -> Claim.asked(java.util.Collections.singletonList(new byte[] {9})));
        assertNull("one unusable package refuses the whole create",
                MlsKeyPackageClaims.gatherInitialKeyPackages(f.port(), MlsLogSink.NONE,
                        java.util.Arrays.asList("+2"), null));
        f.on("claimAll", a -> Claim.refusedByLedger("rationed"));
        assertNull(MlsKeyPackageClaims.gatherInitialKeyPackages(f.port(), MlsLogSink.NONE,
                java.util.Arrays.asList("+2"), null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull("a refused pre-claim is not re-spent",
                MlsKeyPackageClaims.gatherInitialKeyPackages(
                new FakeShellPort().port(), log, java.util.Arrays.asList("+2"),
                MlsUpgradeClaim.refusedByLedger("no")));
        assertTrue(
                log.said("W", "claiming again here would spend exactly what the refusal withheld"));
    }
}
