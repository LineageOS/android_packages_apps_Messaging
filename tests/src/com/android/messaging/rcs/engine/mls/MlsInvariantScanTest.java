/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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

/**
 * {@link MlsInvariantScan}: reports log lines that violate runtime invariants. See docs/testing.md.
 */
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
        // "No output" and "the scan never ran" must not look the same.
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
        // The metric and the log line are independent witnesses; either alone can be missing.
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
        // A peer may genuinely bind no AAD, so this is flagged but does not fail CI.
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: inbound Mx1 from +1555 carries NO AuthenticatedData"));
        assertEquals(1, v.size());
        assertEquals(Severity.SUSPICIOUS, v.get(0).marker.severity);
        assertFalse("a peer-controlled condition must not fail CI",
                MlsInvariantScan.hasFatal(v));
    }

    @Test public void aCapReachedOnEitherStoreIsFatal() {
        // Either cap means a release path stopped running: duplicate decrypts, or re-encrypts that
        // burn generations.
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
        // A line matching two markers is reported twice: the two may differ in severity.
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "E RcsMls: era advance FAILED and the advance did NOT take"));
        assertEquals(2, v.size());
    }

    @Test public void nullsAndEmptyInputAreSafe() {
        // It runs over arbitrary device logs and must never throw.
        assertTrue(MlsInvariantScan.scan(null).isEmpty());
        assertTrue(MlsInvariantScan.scan(new ArrayList<String>()).isEmpty());
        assertTrue(MlsInvariantScan.scan(Arrays.asList((String) null, "clean")).isEmpty());
        assertFalse(MlsInvariantScan.hasFatal(null));
    }

    @Test public void everyMarkerDocumentsWhatItGuardsAndWhoEmitsIt() {
        // A marker must name what it guards and its emitter, so it moves with the log line it
        // tracks.
        for (final MlsInvariantScan.Marker m : MlsInvariantScan.markers()) {
            assertFalse(m.needle.isEmpty());
            assertTrue("needle " + m.needle + " must say what it guards", m.guards.length() > 20);
            assertFalse("needle " + m.needle + " must name its emitter", m.emittedBy.isEmpty());
        }
    }

    /**
     * A dropped continuity token is fatal and the ordinary absence is not a marker at all; a marker
     * firing on both would make them indistinguishable again.
     */
    @Test public void aDroppedContinuityTokenIsFatalButAnAbsentOneIsNotEvenAMarker() {
        final List<Violation> v = MlsInvariantScan.scan(Arrays.asList(
                "W RcsMls: MlsProviderTransport: continuity DROPPED — a 32B token from "
                        + "§7.11.12.1 Welcome for key=g:abc could not be keyed (no self identity)"));
        assertEquals(1, v.size());
        assertEquals(Severity.NEVER, v.get(0).marker.severity);
        assertTrue(MlsInvariantScan.hasFatal(v));

        // Lines a healthy device emits routinely; none may trip anything.
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
