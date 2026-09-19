/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * A health verdict reached without running the divergence test is
 * {@code Health.IN_SYNC_UNVERIFIED}, the second-stage sibling of {@code LOOK_REFUSED}, and is not
 * spent as a verified one: neither the user-requested retry nor the reconcile drive clears the
 * stall alert on it. Only a definite {@code DIFFERS} demotes to {@code DIVERGED}. Each consumer arm
 * is checked for order, not presence.
 */
public class MlsUnverifiedHealthGuardTest {

    private static String transport() throws IOException {
        return SourceScan.transportUnsplitCode();   // healthAgainstServer is in MlsAheadChainCheck
    }

    private static String body(final String src, final String method) {
        final String b = SourceScan.bodyOf(src, method);
        assertTrue("could not find the body of " + method + " — this guard would otherwise read "
                + "nothing and pass on an empty string", b.length() > 0);
        return b;
    }

    @Test
    public void theUnverifiedVerdictIsDeclared() throws IOException {
        final String src = transport();
        assertTrue("Health must declare IN_SYNC_UNVERIFIED — the whole point of the blindness "
                + "half is that 'we did not run the test' is a VERDICT rather than a log line",
                SourceScan.count(src, "IN_SYNC_UNVERIFIED") >= 4);
        assertTrue("and its first-stage sibling must still exist — if LOOK_REFUSED went away, the "
                + "symmetry this fix rests on is gone and these arms need rereading",
                SourceScan.count(src, "LOOK_REFUSED") >= 3);
    }

    /** Exactly one arm, the verified one, may answer {@code IN_SYNC}. */
    @Test
    public void theRefusedForkTestDoesNotReportAVerifiedInSync() throws IOException {
        final String b = body(transport(), "healthAgainstServer");

        assertTrue("the refused-authenticator arm must return IN_SYNC_UNVERIFIED",
                b.contains("ServerComparison.asked(Health.IN_SYNC_UNVERIFIED, anchor)"));
        assertEquals("exactly one arm may still answer a VERIFIED IN_SYNC — the one where the "
                + "authenticator look actually came back. A second is the refused path rejoined to "
                + "the verified one, which is the defect the blindness half exists to remove",
                1, SourceScan.count(b, "ServerComparison.asked(Health.IN_SYNC,"));
        assertTrue("a definite DIFFERS must still demote to DIVERGED — this fix must not have "
                + "weakened the one comparison that IS conclusive",
                b.contains("ServerComparison.asked(Health.DIVERGED, anchor)"));
    }

    /** A person who asked for a retry must not be told it worked on a test we did not run. */
    @Test
    public void theStallAlertIsNotClearedOnAnUnrunForkTest() throws IOException {
        // Declared in MlsStallAlert; the transport holds a delegate.
        final String b = body(SourceScan.codeOnly(SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsStallAlert.java")),
                "refreshStallNotification");

        final int unverified = b.indexOf("Health.IN_SYNC_UNVERIFIED");
        // clearStall is the transport's Context-bound effect; checked below to still clear.
        final int clear = b.indexOf("clearStall(");
        assertTrue("clearStall no longer withdraws the stalled-conversation notification",
                body(transport(), "clearStall").contains("MlsStalledNotifier.clear("));
        assertTrue("refreshStallNotification must branch on IN_SYNC_UNVERIFIED — without it a "
                + "matching era and epoch clears this person's alert on an unrun DIVERGED test",
                unverified >= 0);
        assertTrue("the clear must still be here — a guard that passes because the behaviour was "
                + "deleted is not a guard", clear >= 0);
        assertTrue("the unverified arm must be reached BEFORE the alert is cleared (arm at "
                + unverified + ", clear at " + clear + ")", unverified < clear);

        final int refused = b.indexOf("Health.LOOK_REFUSED");
        assertTrue("and the first-stage refusal must still be handled here too — the two say "
                + "different things and dropping either re-opens half the hazard", refused >= 0);
    }

    /** The same property on the self-driven path, which clears the alert on {@code IN_SYNC}. */
    @Test
    public void theReconcileDriveDoesNotClearOnAnUnrunForkTest() throws IOException {
        final String b = body(transport(), "reconcileAction");

        final int arm = b.indexOf("case IN_SYNC_UNVERIFIED:");
        final int inSync = b.indexOf("case IN_SYNC:");
        assertTrue("reconcileAction must carry an explicit IN_SYNC_UNVERIFIED arm. Letting a new "
                + "enum value fall out of the switch would be right by ACCIDENT — which is what "
                + "ERA_GAP, REJOIN, NOT_FOUND, UNKNOWN and LOOK_REFUSED already do there", arm
                >= 0);
        assertTrue("the verified arm must still exist", inSync >= 0);
        assertTrue("the unverified arm must come first, so it cannot fall through into the arm "
                + "that clears (unverified at " + arm + ", verified at " + inSync + ")",
                arm < inSync);

        final String unverifiedArm = b.substring(arm, inSync);
        assertEquals("the unverified arm must NOT call stallCleared — that is the claim it exists "
                + "to withhold", 0, SourceScan.count(unverifiedArm, "stallCleared("));
        assertTrue("and it must return rather than fall through into the clearing arm",
                unverifiedArm.contains("return "));
    }

    /** {@code detectHealth} keeps only {@code .health}: safe while each stage has its verdict. */
    @Test
    public void theFirstStageRefusalStillHasItsOwnVerdict() throws IOException {
        final String b = body(transport(), "detectHealth");
        assertTrue(
                "detectHealth must still answer LOOK_REFUSED when the era/epoch look was refused",
                b.contains("return Health.LOOK_REFUSED;"));
        assertTrue("and must still delegate the second stage to healthAgainstServer",
                b.contains("healthAgainstServer("));
    }
}
