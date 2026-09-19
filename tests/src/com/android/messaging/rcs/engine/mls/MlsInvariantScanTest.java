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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsInvariantScan.Severity;
import com.android.messaging.rcs.engine.mls.MlsInvariantScan.Violation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rework item 13.5 — the assert-by-absence harness. */
public class MlsInvariantScanTest {

    @Test public void aCleanLogProducesNothing() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "I RcsMls: MlsProviderTransport: decrypted inbound Mx123 from +1555 274B -> 8B",
                "I RcsMls: MLS drive 'reconcile:p:+1555' settled after 1 pass",
                "I MessagingApp: onMlsCiphertext: inserted Mx123 (8B text/plain)"));
        assertTrue(v.isEmpty());
        assertFalse(MlsInvariantScan.hasFatal(v));
    }

    @Test public void theCleanReportSaysHowMANYWereChecked() {
        // "no output" and "the scan never ran" look identical otherwise — which is the same
        // confusion this whole class exists to end.
        final String r = MlsInvariantScan.report(new ArrayList<Violation>());
        assertTrue(r, r.contains("CLEAN"));
        assertTrue("must state the number checked",
                r.contains(String.valueOf(MlsInvariantScan.markers().size())));
    }

    @Test public void theDriveLoopCapIsFatal() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: MLS drive 'x' hit the iteration cap (10) without settling"));
        assertEquals(1, v.size());
        assertEquals(Severity.NEVER, v.get(0).marker.severity);
        assertTrue(MlsInvariantScan.hasFatal(v));
    }

    @Test public void theCounterAndTheLogAreBothCaught() {
        // Two independent witnesses of the same condition: the metric name and the log line. Either
        // alone can be missing (telemetry off, or a log filtered), so both are in the catalogue.
        assertFalse(MlsInvariantScan.scan(Arrays.asList(
                "METRIC Bugle.Mls.ZinniaMessageProcessor.MaxLoopReached=1")).isEmpty());
        assertFalse(MlsInvariantScan.scan(Arrays.asList(
                "MLS drive 'x' hit the iteration cap (10)")).isEmpty());
    }

    @Test public void anUnhandledResultIsFatal() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: MlsProviderTransport: UNHANDLED MLS RESULT applying inbound control"));
        assertTrue(MlsInvariantScan.hasFatal(v));
    }

    @Test public void aMissingAadIsSuspiciousNotFatal() {
        // A peer that genuinely binds none is possible; our plumbing regressing is likelier. Worth
        // flagging, not worth failing CI on, because we cannot control what a peer sends.
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: inbound Mx1 from +1555 carries NO AuthenticatedData"));
        assertEquals(1, v.size());
        assertEquals(Severity.SUSPICIOUS, v.get(0).marker.severity);
        assertFalse("a peer-controlled condition must not fail CI",
                MlsInvariantScan.hasFatal(v));
    }

    @Test public void aCapReachedOnEitherStoreIsFatal() {
        // Both caps mean a release path stopped running, and both silently disable a correctness
        // guarantee: duplicate decrypts, and re-encrypts that burn generations.
        assertTrue(MlsInvariantScan.hasFatal(MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: 512 rows — the delete-on-chat-row-insert path has stopped running"))));
        assertTrue(MlsInvariantScan.hasFatal(MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: 256 entries — NOT caching x. A retry of it will re-encrypt"))));
    }

    @Test public void everyViolationCarriesItsLineNumberAndTheLine() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "clean", "clean", "E RcsMls: UNHANDLED MLS RESULT here", "clean"));
        assertEquals(1, v.size());
        assertEquals("1-based so a hit can be found again", 3, v.get(0).lineNumber);
        assertTrue(v.get(0).line.contains("UNHANDLED"));
    }

    @Test public void oneLineCanTripMoreThanOneMarker() {
        // Deliberate: a line matching two conditions is reported twice rather than the scan
        // stopping at the first, because the two may have different severities.
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: era advance FAILED and the advance did NOT take"));
        assertEquals(2, v.size());
    }

    @Test public void nullsAndEmptyInputAreSafe() {
        // This runs against arbitrary device logs; it must never be the thing that throws.
        assertTrue(MlsInvariantScan.scan(null).isEmpty());
        assertTrue(MlsInvariantScan.scan(new ArrayList<String>()).isEmpty());
        assertTrue(MlsInvariantScan.scan(Arrays.asList((String) null, "clean")).isEmpty());
        assertFalse(MlsInvariantScan.hasFatal(null));
    }

    @Test public void everyMarkerDocumentsWhatItGuardsAndWhoEmitsIt() {
        // The catalogue is the artefact. A marker without provenance cannot be maintained when the
        // log line it tracks is reworded — the message and its check have to move together.
        for (final MlsInvariantScan.Marker m : MlsInvariantScan.markers()) {
            assertFalse(m.needle.isEmpty());
            assertTrue("needle " + m.needle + " must say what it guards", m.guards.length() > 20);
            assertFalse("needle " + m.needle + " must name its emitter", m.emittedBy.isEmpty());
        }
    }

    /**
     * A dropped continuity token is fatal, and the ORDINARY absence is not.
     *
     * <p>The pair matters more than either half. The whole bug was that "we dropped a token" and
     * "there was no token to drop" were indistinguishable in a log, so a marker that fired on both
     * would re-create the confusion in the scanner instead of the transport.
     */
    @Test public void aDroppedContinuityTokenIsFatalButAnAbsentOneIsNotEvenAMarker() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "W RcsMls: MlsProviderTransport: continuity DROPPED — a 32B token from "
                        + "§7.11.12.1 Welcome for key=g:abc could not be keyed (no self identity)"));
        assertEquals(1, v.size());
        assertEquals(Severity.NEVER, v.get(0).marker.severity);
        assertTrue(MlsInvariantScan.hasFatal(v));

        // The three lines a HEALTHY device emits constantly. None may trip anything.
        assertTrue(MlsInvariantScan.scan(Arrays.asList(
                "I RcsMls: [ffi] WELCOME-CONTINUITY g:abc — no 0xF010 in the decrypted GroupInfo; "
                        + "nothing to persist.",
                "I RcsMls: [ffi] WELCOME-CONTINUITY g:abc CAPTURED 32B token from 0xF010 (33B on "
                        + "the wire, opaque<V>-framed) — held for the host to persist",
                "I RcsMls: MlsProviderTransport: continuity FIRST — stored a 32B token for g:abc"))
                .isEmpty());
    }

    @Test public void theReportNamesFatalityExplicitly() {
        final String r = MlsInvariantScan.report(MlsInvariantScan.scan(
                Arrays.asList("UNHANDLED MLS RESULT")));
        assertTrue(r, r.contains("FATAL"));
    }
}
