/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsTransportDisposition.ConnectivityLost;

/** Retryability of the provider's transport verdicts, and the typed connectivity-loss exception. */
public class MlsTransportDispositionTest {

    @Test public void connectivityVerdictsAreRetryable() {
        // These report the provider's connection, not IP reachability.
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_NOT_REGISTERED));
    }

    @Test public void divergenceVerdictsAreNotTransportFailures() {
        // The call reached the server and it disagreed about where we are: recovery's business,
        // not the retry queue's.
        assertEquals(MlsTransportDisposition.OK, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_ERA_GAP));
        assertEquals(MlsTransportDisposition.OK, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED));
        assertFalse(MlsTransportDisposition.isConnectivityLoss(
                MlsTransportDisposition.VERDICT_ERA_GAP));
    }

    @Test public void refusalsArePermanent() {
        assertEquals(MlsTransportDisposition.PERMANENT, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_REJECTED));
        assertEquals(MlsTransportDisposition.PERMANENT, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED));
    }

    @Test public void anUnknownVerdictIsRetryableNotPermanent() {
        // One wasted retry beats abandoning the conversation on a new failure mode.
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(999));
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(-1));
    }

    @Test public void onlyRetryableMayRetry() {
        assertTrue(MlsTransportDisposition.RETRYABLE.mayRetry());
        assertFalse(MlsTransportDisposition.PERMANENT.mayRetry());
        assertFalse(MlsTransportDisposition.NOT_FOUND.mayRetry());
        assertFalse(MlsTransportDisposition.OK.mayRetry());
    }

    @Test public void dispositionMapsOntoTheStageFStatusAxis() {
        assertEquals(MlsResultStatus.FAIL_RETRY,
                MlsTransportDisposition.RETRYABLE.toResultStatus());
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsTransportDisposition.PERMANENT.toResultStatus());
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsTransportDisposition.NOT_FOUND.toResultStatus());
        assertEquals(MlsResultStatus.SUCCESS, MlsTransportDisposition.OK.toResultStatus());
    }

    @Test public void requireLiveThrowsOnlyOnALoss() {
        MlsTransportDisposition.requireLive("reconcile", MlsTransportDisposition.VERDICT_OK);
        MlsTransportDisposition.requireLive("reconcile", MlsTransportDisposition.VERDICT_ERA_GAP);
        MlsTransportDisposition.requireLive("reconcile", MlsTransportDisposition.VERDICT_REJECTED);
        try {
            MlsTransportDisposition.requireLive("reconcile",
                    MlsTransportDisposition.VERDICT_TRANSPORT_FAILED);
            fail("a transport failure must raise the typed loss");
        } catch (final ConnectivityLost expected) {
            assertEquals(MlsTransportDisposition.VERDICT_TRANSPORT_FAILED, expected.verdict);
        }
    }

    @Test public void theExceptionNamesTheCallSite() {
        // The call site reaches the message, so the two sites stay distinguishable in the log.
        try {
            MlsTransportDisposition.requireLive("SEND",
                    MlsTransportDisposition.VERDICT_NOT_REGISTERED);
            fail("expected the loss");
        } catch (final ConnectivityLost expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("SEND"));
            assertTrue(expected.getMessage().contains("Connectivity lost"));
        }
    }

    @Test public void theLossIsUnchecked() {
        // It leaves a drive-loop pass without every intermediate signature declaring it.
        assertTrue(RuntimeException.class.isAssignableFrom(ConnectivityLost.class));
    }

    @Test public void anAbandonedDriveIsRetryableAndSaysSo() {
        // Nothing was decided, so the work is still owed.
        final MlsDriveLoop.Result r = MlsDriveLoop.abandoned("reconcile:x", "connectivity lost");
        assertEquals(MlsResultStatus.FAIL_RETRY, r.status());
        assertEquals(0, r.iterations);
        assertFalse(r.cappedOut);
        assertTrue(r.logLine(), r.logLine().contains("ABANDONED"));
    }

    /** The server does not count this line as a member, so the same request cannot succeed. */
    @Test public void notInGroupIsPermanentNotRetryable() {
        assertEquals(MlsTransportDisposition.PERMANENT, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
    }

    /** The request arrived and was declined, so it must not raise {@code ConnectivityLost}. */
    @Test public void notInGroupIsNotConnectivityLoss() {
        assertFalse(MlsTransportDisposition.isConnectivityLoss(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
    }

    /** The seal's question; NOT_REGISTERED is both a connectivity loss and a position refusal. */
    @Test public void refusedOurPositionNamesTheVerdictsThatSayNothingAboutWhatWeSent() {
        assertTrue(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
        assertTrue("NOT_REGISTERED is both a connectivity condition and a position refusal",
                MlsTransportDisposition.refusedOurPosition(
                        MlsTransportDisposition.VERDICT_NOT_REGISTERED));
        // Verdicts about what we sent are excluded, or the seal re-offers a judged certificate.
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_REJECTED));
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED));
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_OK));
    }

    /** Reads the shared AIDL vocabulary: a new verdict fails until {@code ofVerdict} has a case. */
    @Test public void everyVerdictInTheAidlVocabularyIsClassified() throws java.io.IOException {
        final String aidl = SourceScan.codeOnly(SourceScan.read(
                "aidl/src/java/org/lineageos/rcs/provider/RcsMlsControlResult.java"));
        assertFalse("the AIDL result class is gone — this guard cannot check anything",
                aidl.isEmpty());
        final String disposition = SourceScan.codeOnly(SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsTransportDisposition.java"));

        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("VERDICT_(\\w+)\\s*=\\s*(-?\\d+)\\s*;").matcher(aidl);
        int seen = 0;
        while (m.find()) {
            seen++;
            final String name = "VERDICT_" + m.group(1);
            assertTrue(name + " is in the shared vocabulary but MlsTransportDisposition does not "
                            + "declare it, so ofVerdict answers `default: RETRYABLE` for it — the "
                            + "exact shape of that defect. Declare it and classify it.",
                    disposition.contains(name + " = " + m.group(2) + ";"));
            assertTrue(name + " is declared here but never appears in ofVerdict's switch, so it "
                            + "falls to the default. An unclassified verdict is not a safe "
                            + "default when its reason is known.",
                    disposition.contains("case " + name + ":"));
        }
        assertTrue("no VERDICT_* constants were found at all — the regex or the AIDL moved",
                seen >= 8);
    }

}
