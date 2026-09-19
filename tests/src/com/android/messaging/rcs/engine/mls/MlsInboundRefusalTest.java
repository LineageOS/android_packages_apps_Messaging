/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.log.LogMask;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link MlsInboundRefusal}: a message that decrypted but is refused (an AAD message-id
 * mis-binding, an impersonation) is not a decrypt failure. The properties are pinned over {@code
 * Reason.values()}, so a reason added later inherits nothing.
 */
public class MlsInboundRefusalTest {

    /**
     * No refusal sends an RCC.16 §7.7.2.2 negative delivery report: the message decrypted, and on
     * the impersonation arm the report would go to the peer being impersonated.
     */
    @Test public void noRefusalReportsToTheSender() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r + " must not emit an FTD — the message DECRYPTED, so the report would be "
                    + "false, and on the impersonation arm it is addressed to the peer being "
                    + "impersonated", MlsInboundRefusal.reportsToSender(r));
        }
    }

    /** No refusal runs an RCC.16 §10.1 self-heal: the outer message decrypted. */
    @Test public void noRefusalHealsTheGroup() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r + " must not self-heal — a successful decrypt is the strongest evidence "
                    + "the group is in step, and the era-advance quota it would spend is not "
                    + "available for a real desync afterwards", MlsInboundRefusal.healsGroup(r));
        }
    }

    /**
     * No refusal advances the non-convergence counter: with {@code UNCONVERGED_BEFORE_REBUILD} at
     * 3, any member could force a rebuild with three envelopes in someone else's name.
     */
    @Test public void noRefusalCountsTowardARebuild() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r
                    + " must not count toward a rebuild — it is remote-triggerable, and three "
                    + "of them would rebuild the conversation",
                    MlsInboundRefusal.countsTowardRebuild(r));
        }
    }

    /** Every reason is classified; one added without deciding its effects fails here. */
    @Test public void everyReasonIsClassified() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            MlsInboundRefusal.reportsToSender(r);
            MlsInboundRefusal.healsGroup(r);
            MlsInboundRefusal.countsTowardRebuild(r);
            MlsInboundRefusal.replayable(r);
            assertNotNull(r + " has no marker", MlsInboundRefusal.marker(r));
        }
    }

    /**
     * The router drops every marker as control: a marker classified as text would show an empty
     * bubble, and one classified as unknown would log the wrong story.
     */
    @Test public void everyMarkerClassifiesAsDropControl() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            final String marker = MlsInboundRefusal.marker(r);
            assertEquals(marker + " must be DROP_CONTROL", RccContentDisposition.DROP_CONTROL,
                    RccContentDisposition.classify(marker));
            assertTrue(marker + " must never reach the conversation",
                    RccContentDisposition.isDrop(RccContentDisposition.classify(marker)));
        }
    }

    /** A framing fault and an impersonation stay separately greppable. */
    @Test public void markersAreDistinct() {
        final Set<String> seen = new HashSet<>();
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertTrue("two reasons share the marker " + MlsInboundRefusal.marker(r),
                    seen.add(MlsInboundRefusal.marker(r)));
        }
        assertEquals(MlsInboundRefusal.Reason.values().length, seen.size());
    }

    /** A marker resolves back to its reason, and nothing else does. */
    @Test public void markersRoundTripToTheirReason() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertEquals(r, MlsInboundRefusal.forMarker(MlsInboundRefusal.marker(r)));
            assertTrue(MlsInboundRefusal.isRefusal(MlsInboundRefusal.marker(r)));
        }
        assertNull(MlsInboundRefusal.forMarker(null));
        assertNull(MlsInboundRefusal.forMarker(""));
        assertNull(MlsInboundRefusal.forMarker("text/plain"));
        // The RCC.16 §11.3a resend marker has the same disposition but is not a refusal.
        assertFalse(MlsInboundRefusal.isRefusal(RccContentDisposition.RESEND_NOT_FOR_ME));
    }

    /**
     * A redelivery replays {@code RccMlsBody.parse} of the stored payload, and {@code frame}
     * appends {@code ;charset=UTF-8}; the stored refusal must come back as the same marker.
     */
    @Test public void aStoredRefusalReplaysAsTheSameRefusal() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            final byte[] stored = RccMlsBody.frame(new byte[0], MlsInboundRefusal.marker(r),
                    /*inline=*/ true);
            final RccMlsBody.Parsed back = RccMlsBody.parse(stored);
            assertEquals("the replayed content type must be byte-equal to the marker",
                    MlsInboundRefusal.marker(r), back.contentType);
            assertEquals(r, MlsInboundRefusal.forMarker(back.contentType));
            assertEquals(RccContentDisposition.DROP_CONTROL,
                    RccContentDisposition.classify(back.contentType));
            assertEquals("a refused body carries nothing", 0, back.body.length);
        }
    }

    /**
     * Every refusal is stored for replay: a redelivery that re-entered the decrypt would hit a
     * ratchet step already consumed and fall into the self-heal and report this removes.
     */
    @Test public void everyRefusalIsStoredForReplay() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertTrue(r
                    + " must be stored: an unstored refusal re-enters the decrypt on a ratchet "
                    + "step the first delivery already consumed, and a failure there lands on "
                    + "onDecryptFailure — the exact self-heal plus FTD this removes",
                    MlsInboundRefusal.replayable(r));
        }
    }

    /**
     * The mis-binding line carries exactly one of the two scan needles, so the event is counted
     * once.
     */
    @Test public void theMisbindingLineCarriesExactlyOneNeedle() {
        final String plain = MlsInboundRefusal.misbindingLine("m1", "+15551110000", "m0",
                /*provenReplay=*/ false);
        assertTrue(plain.contains(MlsInboundRefusal.MARKER_MISBINDING));
        assertFalse(plain.contains(MlsInboundRefusal.MARKER_REPLAY));

        final String replay = MlsInboundRefusal.misbindingLine("m1", "+15551110000", "m0",
                /*provenReplay=*/ true);
        assertTrue(replay.contains(MlsInboundRefusal.MARKER_REPLAY));
        assertFalse(replay.contains(MlsInboundRefusal.MARKER_MISBINDING));
    }

    /** Both forms name the transport's id and the one the AAD bound. */
    @Test public void theMisbindingLineNamesBothIds() {
        for (final boolean replay : new boolean[] { false, true }) {
            final String line = MlsInboundRefusal.misbindingLine("wire-id", "+15551110000",
                    "aad-id", replay);
            assertTrue(line.contains("wire-id"));
            assertTrue(line.contains("aad-id"));
            assertTrue(line.contains(LogMask.number("+15551110000")));
            assertFalse(line.contains("+15551110000"));
            assertTrue("the line must say the message decrypted, or the next reader repeats the "
                    + "mistake that was fixed", line.contains("DECRYPTED"));
        }
    }

    /**
     * The impersonation line names the certified signer (the offender) and the envelope identity.
     */
    @Test public void theImpersonationLineNamesBothIdentities() {
        final String line = MlsInboundRefusal.impersonationLine("m9", "+15551110000",
                "+15552223333");
        assertTrue(line.contains(LogMask.number("+15551110000")));
        assertTrue(line.contains(LogMask.number("+15552223333")));
        assertFalse(line.contains("+15551110000") || line.contains("+15552223333"));
        assertTrue(line.contains(MlsInboundRefusal.MARKER_IMPERSONATION));
        assertTrue(line.contains("DECRYPTED"));
    }

    /** The scan's needles are the emitter's constants, and the scan fires on every refusal line. */
    @Test public void theInvariantScanFiresOnEveryRefusalLine() {
        assertHit(MlsInboundRefusal.misbindingLine("m1", "+1555", "m0", false),
                MlsInboundRefusal.MARKER_MISBINDING, MlsInvariantScan.Severity.SUSPICIOUS);
        assertHit(MlsInboundRefusal.misbindingLine("m1", "+1555", "m0", true),
                MlsInboundRefusal.MARKER_REPLAY, MlsInvariantScan.Severity.NEVER);
        assertHit(MlsInboundRefusal.impersonationLine("m9", "+1555", "+1666"),
                MlsInboundRefusal.MARKER_IMPERSONATION, MlsInvariantScan.Severity.NEVER);
    }

    private static void assertHit(final String line, final String needle,
            final MlsInvariantScan.Severity severity) {
        boolean found = false;
        for (final MlsInvariantScan.Violation v : MlsInvariantScan.scan(
                java.util.Collections.singletonList(line))) {
            if (needle.equals(v.marker.needle)) {
                assertEquals("wrong severity for " + needle, severity, v.marker.severity);
                found = true;
            }
        }
        assertTrue("MlsInvariantScan does not fire on: " + line, found);
    }

    /** An ordinary line trips nothing, including the broad catch-all refusal needle. */
    @Test public void anOrdinaryLineTripsNothing() {
        assertTrue(MlsInvariantScan.scan(java.util.Collections.singletonList(
                "MlsProviderTransport: m1 from +1555 decrypted (312B)")).isEmpty());
    }
}
