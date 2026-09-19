/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsTransportDisposition.ConnectivityLost;

/** Rework item 5.7 — retryability semantics for transport verdicts, and the typed loss exception. */
public class MlsTransportDispositionTest {

    @Test public void connectivityVerdictsAreRetryable() {
        // The source is Tachyon BIND-STREAM liveness, not IP reachability: a device with working
        // data and a dead bind stream is exactly the case a ping-style check calls healthy.
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        assertEquals(MlsTransportDisposition.RETRYABLE, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_NOT_REGISTERED));
    }

    @Test public void divergenceVerdictsAreNotTransportFailures() {
        // The distinction that matters most here. ERA_GAP and GROUP_ID_CHANGED mean the call
        // REACHED the server and the server disagreed about where we are — recovery's business,
        // not the retry queue's. Classifying them retryable would have the queue re-sending a
        // control the server has already said is at the wrong era.
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
        // The safe direction: one wasted retry beats treating a NEW failure mode as permanent and
        // abandoning the conversation.
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
        // RETRYABLE is exactly what FAIL_RETRY was built to carry.
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
        // The two call sites must stay distinguishable in logcat or the §20.3 trace diff stops
        // working — so the site name has to reach the message.
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
        // Deliberate: it travels out of a drive-loop pass without every intermediate signature
        // declaring it, which is the point of using an exception rather than another return value.
        assertTrue(RuntimeException.class.isAssignableFrom(ConnectivityLost.class));
    }

    @Test public void anAbandonedDriveIsRetryableAndSaysSo() {
        // Nothing was decided, so the work is still owed — FAIL_RETRY, not FAIL_NO_RETRY.
        final MlsDriveLoop.Result r = MlsDriveLoop.abandoned("reconcile:x", "connectivity lost");
        assertEquals(MlsResultStatus.FAIL_RETRY, r.status());
        assertEquals(0, r.iterations);
        assertFalse(r.cappedOut);
        assertTrue(r.logLine(), r.logLine().contains("ABANDONED"));
    }
    // ---- VERDICT_NOT_IN_GROUP, and the hole that let it in unclassified -----------

    /**
     * NOT_IN_GROUP is PERMANENT, and it is the one verdict where {@code default: RETRYABLE} was
     * actively wrong rather than merely uninformed.
     *
     * <p>The default is a fail-safe: an unknown mode is better retried than abandoned. Here the
     * reason is known — the server does not count this line as a member — so the identical request
     * cannot succeed however often it is sent. Retrying is not caution, it is a loop.
     */
    @Test public void notInGroupIsPermanentNotRetryable() {
        assertEquals(MlsTransportDisposition.PERMANENT, MlsTransportDisposition.ofVerdict(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
    }

    /**
     * It is NOT connectivity loss — that predicate raises {@code ConnectivityLost}, and the request
     * arrived, was read, and was declined. Widening {@code isConnectivityLoss} to fix the seal
     * would have made the seal correct by making the exception a lie.
     */
    @Test public void notInGroupIsNotConnectivityLoss() {
        assertFalse(MlsTransportDisposition.isConnectivityLoss(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
    }

    /**
     * {@code refusedOurPosition} is the seal's actual question, and the overlap with
     * {@code isConnectivityLoss} on NOT_REGISTERED is deliberate: that verdict genuinely is both.
     */
    @Test public void refusedOurPositionNamesTheVerdictsThatSayNothingAboutWhatWeSent() {
        assertTrue(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_NOT_IN_GROUP));
        assertTrue("NOT_REGISTERED is both a connectivity condition and a position refusal",
                MlsTransportDisposition.refusedOurPosition(
                        MlsTransportDisposition.VERDICT_NOT_REGISTERED));
        // And the verdicts that ARE about what we sent must not be in it, or the seal would
        // re-offer a certificate the server has already judged, on every maintenance pass.
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_REJECTED));
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED));
        assertFalse(MlsTransportDisposition.refusedOurPosition(
                MlsTransportDisposition.VERDICT_OK));
    }

    /**
     * EVERY VERDICT IN THE SHARED VOCABULARY IS CLASSIFIED HERE — the guard that would have caught
     * an unclassified verdict on the day it was introduced.
     *
     * <p>This test named its verdicts INDIVIDUALLY, which is why it could not notice an eighth:
     * {@code a505ca24} added {@code VERDICT_NOT_IN_GROUP} and every assertion above still passed
     * while {@code ofVerdict} was silently answering {@code default: RETRYABLE} for it. A count
     * ratchet in a different suite caught it, from a different question entirely.
     *
     * <p>So this enumerates the AIDL source both sides share — the same technique
     * {@code MlsCredentialUpdateRetryGuardTest} uses — and requires each value to appear in this
     * class's own switch. A ninth verdict now fails HERE, next to the code that has to classify it,
     * rather than being noticed sideways by a test about negatives.
     */
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
