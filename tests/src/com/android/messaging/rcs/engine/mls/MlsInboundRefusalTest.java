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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.RccMlsBody;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * A REFUSAL IS NOT A DECRYPT FAILURE.
 *
 * <p>The assertions here are almost entirely about what a refusal must NOT do, for the same reason
 * as {@link MlsResendReceiveTest}: the defect was not a wrong value anywhere, it was two arms whose
 * comments correctly said the message had decrypted and which then took the crypto-failure exit
 * ({@code return null}), where the router turns it into a self-heal, a false §7.7.2.2 report and
 * eventually a conversation rebuild. Nothing about the return value looked wrong at the call site;
 * only following it to its caller refuted it. So the properties are pinned over
 * {@code Reason.values()} rather than over a list, and the round trip through the rendezvous framing
 * is exercised end to end.
 */
public class MlsInboundRefusalTest {

    /**
     * THE HEADLINE. No refusal may put a §7.7.2.2 negative delivery report on the wire.
     *
     * <p>Walks the enum on purpose: a reason added later inherits nothing, because
     * {@code reportsToSender} throws on an unclassified constant and this test names it.
     */
    @Test public void noRefusalReportsToTheSender() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r + " must not emit an FTD — the message DECRYPTED, so the report would be "
                    + "false, and on the impersonation arm it is addressed to the peer being "
                    + "impersonated", MlsInboundRefusal.reportsToSender(r));
        }
    }

    /** No refusal may run a §10.1 self-heal. The outer message decrypted; nothing is broken. */
    @Test public void noRefusalHealsTheGroup() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r + " must not self-heal — a successful decrypt is the strongest evidence "
                    + "the group is in step, and the era-advance quota it would spend is not "
                    + "available for a real desync afterwards", MlsInboundRefusal.healsGroup(r));
        }
    }

    /**
     * No refusal may advance the consecutive-non-convergence counter.
     *
     * <p>The security-relevant one: {@code UNCONVERGED_BEFORE_REBUILD} is 3, so a refusal that
     * counted would let any member of a group force a conversation rebuild with three envelopes
     * addressed in someone else's name.
     */
    @Test public void noRefusalCountsTowardARebuild() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertFalse(r + " must not count toward a rebuild — it is remote-triggerable, and three "
                    + "of them would rebuild the conversation",
                    MlsInboundRefusal.countsTowardRebuild(r));
        }
    }

    /**
     * Every reason is CLASSIFIED — no constant falls through to the {@code default: throw}.
     *
     * <p>This is what makes the three tests above hold for reasons that do not exist yet: adding one
     * without deciding its wire effects fails here rather than acquiring a default.
     */
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
     * THE ROUTER MUST DROP EVERY MARKER, and drop it as CONTROL rather than as unknown.
     *
     * <p>This is the half that makes the return value safe. A marker that classified TEXT would put
     * an empty bubble in the conversation; one that classified DROP_UNKNOWN would still work but
     * would log the refusal as an unrouted content type, which is the wrong story.
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

    /**
     * The two reasons do NOT share a marker.
     *
     * <p>Same disposition, different events. A framing fault and an authenticated member lying about
     * its identity must stay separately greppable — collapsing them is how the second becomes
     * invisible in a log full of the first.
     */
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
        // The §11.3a resend marker is a DIFFERENT internal marker with the same disposition. It must
        // not answer as a refusal: it is a healthy message that is simply not ours.
        assertFalse(MlsInboundRefusal.isRefusal(RccContentDisposition.RESEND_NOT_FOR_ME));
    }

    /**
     * THE REDELIVERY REPLAY, end to end through the framing the rendezvous row actually stores.
     *
     * <p>Not a formality. {@code MlsProviderTransport}'s replay path answers a redelivery with
     * {@code RccMlsBody.parse(stored payload)} and consults nothing else, and {@code frame} appends
     * {@code ;charset=UTF-8} to the content type — so if {@code parse} did not strip the parameter,
     * the replayed refusal would come back as an unrecognised type and classify DROP_UNKNOWN. That
     * exact round trip is the reason the marker is stored framed rather than as a flag.
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

    /** Both refusals are replayable — the redelivery must not re-enter the destructive decrypt. */
    @Test public void everyRefusalIsStoredForReplay() {
        for (final MlsInboundRefusal.Reason r : MlsInboundRefusal.Reason.values()) {
            assertTrue(r + " must be stored: an unstored refusal re-enters the decrypt on a ratchet "
                    + "step the first delivery already consumed, and a failure there lands on "
                    + "onDecryptFailure — the exact self-heal plus FTD this removes",
                    MlsInboundRefusal.replayable(r));
        }
    }

    // ---- the log lines, which are the ONLY thing either arm puts anywhere ----------------------

    /**
     * The mis-binding line carries EXACTLY ONE of the two scan needles.
     *
     * <p>Both would double-count one event; neither would make it invisible to a scan that is the
     * only reader of a log nobody greps.
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

    /** Both forms name both ids — the transport's and the one the AAD bound. */
    @Test public void theMisbindingLineNamesBothIds() {
        for (final boolean replay : new boolean[] { false, true }) {
            final String line = MlsInboundRefusal.misbindingLine("wire-id", "+15551110000",
                    "aad-id", replay);
            assertTrue(line.contains("wire-id"));
            assertTrue(line.contains("aad-id"));
            assertTrue(line.contains("+15551110000"));
            assertTrue("the line must say the message decrypted, or the next reader repeats the "
                    + "mistake that was fixed", line.contains("DECRYPTED"));
        }
    }

    /**
     * The impersonation line names BOTH identities and carries the historical needle.
     *
     * <p>The certified signer is the only thing that identifies the offending member; the envelope
     * identity is the victim. A line with only one of them cannot be acted on.
     */
    @Test public void theImpersonationLineNamesBothIdentities() {
        final String line = MlsInboundRefusal.impersonationLine("m9", "+15551110000",
                "+15552220000");
        assertTrue(line.contains("+15551110000"));
        assertTrue(line.contains("+15552220000"));
        assertTrue(line.contains(MlsInboundRefusal.MARKER_IMPERSONATION));
        assertTrue(line.contains("DECRYPTED"));
    }

    /**
     * THE SCAN AND THE EMITTER MOVE TOGETHER.
     *
     * <p>{@link MlsInvariantScan}'s own most expensive bug was a hand-typed needle that differed
     * from the emitted string by three characters, so the scan built to catch a discovery could
     * never fire on it. These three needles are now emitter constants; this test proves the
     * catalogue actually fires on the lines this class produces.
     */
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

    /**
     * A clean line trips nothing. Guards the catch-all "REFUSING" needle, which is broad enough that
     * a careless reword elsewhere could make ordinary traffic match it.
     */
    @Test public void anOrdinaryLineTripsNothing() {
        assertTrue(MlsInvariantScan.scan(java.util.Collections.singletonList(
                "MlsProviderTransport: m1 from +1555 decrypted (312B)")).isEmpty());
    }
}
