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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** The health state machine — rework items 3.1–3.7. */
public class MlsHealthMachineTest {

    private static final String ME = "+15715550104";
    private static final byte[] GID = new byte[] {1, 2, 3};
    private static final Moment NOW = new Moment(4, 11L);

    private static MlsConversationRecord at(final int status) {
        return MlsConversationRecord.initial(ME, GID, "rcs", "+1555")
                .toBuilder().healthStatus(status).build();
    }

    // ---- 3.7: the exhaustive 18x18 matrix ------------------------------------------------------

    /**
     * All 324 cells. The 127 legal pairs must decide LEGAL with a real edge; every other cell must
     * decide ILLEGAL_REJECTED — except the self-pairs, which are NO_OP by the guard order.
     *
     * <p>This is the test the item asks for by name, and the reason is that a mis-transcribed cell
     * is invisible without it: nothing else in the system would notice a single wrong entry.
     */
    @Test
    public void everyOneOf324CellsDecidesCorrectly() {
        int legal = 0, noop = 0, illegal = 0;
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                final MlsHealthMachine.Decision d = MlsHealthMachine.decide(from, to, false);
                if (from == to) {
                    assertEquals("self-pair " + from, MlsHealthMachine.Outcome.NO_OP, d.outcome);
                    assertNull("a no-op must carry no edge", d.edge);
                    noop++;
                } else if (MlsHealthStates.isLegal(from, to)) {
                    assertEquals(from + "->" + to, MlsHealthMachine.Outcome.LEGAL, d.outcome);
                    assertNotNull(from + "->" + to + " is legal but has no edge", d.edge);
                    legal++;
                } else {
                    assertEquals(from + "->" + to, MlsHealthMachine.Outcome.ILLEGAL_REJECTED,
                            d.outcome);
                    assertNull(d.edge);
                    illegal++;
                }
            }
        }
        assertEquals("all 324 cells must be covered", 324, legal + noop + illegal);
        assertEquals("18 self-pairs", 18, noop);
        // 127 legal transitions, minus the one self-loop, which the guard order intercepts first.
        assertEquals("the table's legal pairs, less the self-loop", 126, legal);
    }

    /**
     * All 16 self-pairs return the NO-OP sentinel and NOT an edge. This is the executable form of
     * Invariant 10 and of 3.2's guard order.
     */
    @Test
    public void everySelfPairIsANoOpAndCarriesNoEdge() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthStates.isState(s)) continue;
            final MlsHealthMachine.Decision d = MlsHealthMachine.decide(s, s, false);
            assertEquals(MlsHealthStates.name(s), MlsHealthMachine.Outcome.NO_OP, d.outcome);
            assertNull(d.edge);
            assertFalse("a no-op emits NO telemetry", d.emitsTelemetry());
            assertFalse(d.changesState());
        }
    }

    /**
     * §5.11's safety property: Healthy is reachable in ONE hop from all 15 other states. A property,
     * not fifteen hand-written cases — the point is that no state is a trap.
     */
    @Test
    public void healthyIsOneHopFromEveryOtherState() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthStates.isState(s) || s == MlsHealthStates.HEALTHY) continue;
            final MlsHealthMachine.Decision d =
                    MlsHealthMachine.decide(s, MlsHealthStates.HEALTHY, false);
            assertEquals(MlsHealthStates.name(s) + " cannot reach Healthy in one hop",
                    MlsHealthMachine.Outcome.LEGAL, d.outcome);
        }
    }

    /** The self-loop in the table is DEAD DATA — present, but unreachable because of guard order. */
    @Test
    public void theOneSelfLoopIsUnreachable() {
        final int s = MlsHealthStates.ONGOINGERAADVANCEMENT;
        assertTrue("the table still contains the self-loop", MlsHealthStates.isLegal(s, s));
        assertEquals("...and the guard order makes it unreachable",
                MlsHealthMachine.Outcome.NO_OP, MlsHealthMachine.decide(s, s, false).outcome);
    }

    // ---- 3.1 / 3.7: the two pinned negative assertions ------------------------------------------

    /** §5.2: EraAdvancementFailed is NOT an edge, despite the symmetry with the epoch one. */
    @Test
    public void eraAdvancementFailedIsNotAnEdge() {
        assertNull(MlsHealthEdge.byWireName("EraAdvancementFailed"));
        assertNotNull("...while the epoch one IS",
                MlsHealthEdge.byWireName("EpochAdvancementFailed"));
    }

    /** §5.3 trap 3: InvalidTransition is never produced BY THE TABLE. */
    @Test
    public void invalidTransitionIsNeverProducedByTheTable() {
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                final MlsHealthEdge e = MlsHealthEdge.forPair(from, to);
                assertFalse(from + "->" + to,
                        e == MlsHealthEdge.INVALID_TRANSITION);
            }
        }
    }

    // ---- 3.1: ordinal is NOT the telemetry value ------------------------------------------------

    /**
     * The two numberings are independent, and reporting {@code ordinal()} as the metric would give
     * plausible-looking numbers that mean something else. These are the pairs where they diverge
     * most, taken straight from §5.2.
     */
    @Test
    public void ordinalAndTelemetryValueAreDifferentNumberings() {
        assertEquals(15, MlsHealthEdge.REQUESTED_PHOENIX_MODE.ordinalValue);
        assertEquals(26, MlsHealthEdge.REQUESTED_PHOENIX_MODE.telemetryValue);
        assertEquals(19, MlsHealthEdge.REVIVED_FROM_END_MLS.ordinalValue);
        assertEquals(16, MlsHealthEdge.REVIVED_FROM_END_MLS.telemetryValue);
        assertEquals(21, MlsHealthEdge.REQUESTED_ERA_ADVANCEMENT_FOR_REVIVAL.ordinalValue);
        assertEquals(31, MlsHealthEdge.REQUESTED_ERA_ADVANCEMENT_FOR_REVIVAL.telemetryValue);

        int differing = 0;
        final Set<Integer> telemetry = new HashSet<>();
        for (final MlsHealthEdge e : MlsHealthEdge.values()) {
            assertEquals("the java ordinal must track the decoded one",
                    e.ordinal(), e.ordinalValue);
            if (e.ordinalValue != e.telemetryValue) differing++;
            assertTrue("telemetry values must be unique: " + e, telemetry.add(e.telemetryValue));
        }
        assertEquals("all 32 edges", 32, MlsHealthEdge.values().length);
        assertTrue("the two numberings genuinely differ", differing > 10);
    }

    /** Only (from, to) determines the edge — the one ambiguous NAME lands in two destinations. */
    @Test
    public void startedEraAdvancementIsAmbiguousByNameAlone() {
        int destinations = 0;
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                if (MlsHealthEdge.forPair(from, to) == MlsHealthEdge.STARTED_ERA_ADVANCEMENT
                        && (to == MlsHealthStates.ONGOINGERAADVANCEMENT
                            || to == MlsHealthStates.ERAADVANCEMENTREQUESTED)) {
                    destinations |= (to == MlsHealthStates.ONGOINGERAADVANCEMENT) ? 1 : 2;
                }
            }
        }
        assertEquals("StartedEraAdvancement must land in BOTH destinations", 3, destinations);
    }

    // ---- 3.2: the guard order -------------------------------------------------------------------

    /** An illegal transition changes NOTHING — the record comes back as the same instance. */
    @Test
    public void anIllegalTransitionMutatesNothing() {
        final MlsConversationRecord cur = at(MlsHealthStates.HEALTHY);
        // Healthy -> Initializing is not in the table (Initializing has a single inbound edge).
        final MlsHealthMachine.Applied a =
                MlsHealthMachine.transition(cur, MlsHealthStates.INITIALIZING, NOW, false);
        assertEquals(MlsHealthMachine.Outcome.ILLEGAL_REJECTED, a.decision.outcome);
        assertSame("a rejected transition must not produce a new record", cur, a.record);
        assertEquals(MlsHealthStates.HEALTHY, a.record.healthStatus);
    }

    /**
     * The no-op is NOT an early return: it still runs on_enter. That is why
     * {@code transition(Healthy → Healthy)} restamps last_healthy_moment while emitting no
     * telemetry and running no on_transition.
     */
    @Test
    public void theNoOpStillRunsOnEnter() {
        final MlsConversationRecord cur = at(MlsHealthStates.HEALTHY).toBuilder()
                .lastHealthyMoment(new Moment(1, 1L)).build();
        final MlsHealthMachine.Applied a =
                MlsHealthMachine.transition(cur, MlsHealthStates.HEALTHY, NOW, false);
        assertEquals(MlsHealthMachine.Outcome.NO_OP, a.decision.outcome);
        assertFalse(a.decision.emitsTelemetry());
        assertEquals("on_enter ran on the no-op path", NOW, a.record.lastHealthyMoment);
        assertNull("...but on_transition did NOT", a.record.recoveredAt);
    }

    /** A no-op to a NON-Healthy state touches nothing at all: on_enter is gated on the destination. */
    @Test
    public void aNoOpToANonHealthyStateChangesNothing() {
        final MlsConversationRecord cur = at(MlsHealthStates.DONEENDMLS);
        final MlsHealthMachine.Applied a =
                MlsHealthMachine.transition(cur, MlsHealthStates.DONEENDMLS, NOW, false);
        assertEquals(MlsHealthMachine.Outcome.NO_OP, a.decision.outcome);
        assertNull(a.record.lastHealthyMoment);
        assertNull(a.record.recoveredAt);
    }

    // ---- 3.4: the hooks --------------------------------------------------------------------------

    /** on_enter stamps last_healthy_moment on EVERY entry to Healthy, recovery or not. */
    @Test
    public void everyEntryToHealthyStampsLastHealthyMoment() {
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (!MlsHealthStates.isState(from) || from == MlsHealthStates.HEALTHY) continue;
            final MlsHealthMachine.Applied a =
                    MlsHealthMachine.transition(at(from), MlsHealthStates.HEALTHY, NOW, false);
            assertEquals(MlsHealthStates.name(from), MlsHealthMachine.Outcome.LEGAL,
                    a.decision.outcome);
            assertEquals(MlsHealthStates.name(from) + " -> Healthy", NOW, a.record.lastHealthyMoment);
        }
    }

    /**
     * Block A stamps recoveredAt only on a RECOVERY landing — the <b>six</b> states of the mask
     * {@code 0x10d6}, not every arrival at Healthy. Collapsing the two loses "when did this
     * conversation last need repairing".
     *
     * <p><b>{@code DoneEndMls(10)} is the one to watch, and this test is what pins it.</b> Bit 10 is
     * CLEAR in {@code 0x10d6}; the seven-state reading we carried until 2026-09-11 was a
     * transcription, not a reading. Because {@code DoneEndMls → Healthy} IS a legal edge
     * ({@code HealedAfterRemoteCommit}), the loop below reaches it and asserts {@code recoveredAt}
     * stays NULL — which fails against the old seven-state set. That matters beyond tidiness since
     * §22.1-39: {@code recoveredAt} is G3's comparand, so a spurious stamp makes our resend gate
     * stricter than Google Messages'.
     */
    @Test
    public void recoveredAtIsStampedOnlyByARecoveryLanding() {
        final int[] recovery = {
            MlsHealthStates.EPOCHADVANCEMENTREQUESTED, MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
            MlsHealthStates.ONGOINGERAADVANCEMENT, MlsHealthStates.SELFHEALFAILED,
            MlsHealthStates.ERAADVANCEMENTREQUESTED,
            MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE,
        };
        assertEquals("the mask 0x10d6 has SIX bits; {1,2,4,6,7,10,12} would be 0x14d6",
                6, recovery.length);
        int mask = 0;
        for (final int r : recovery) mask |= 1 << r;
        assertEquals("the enumerated set must BE the mask, not resemble it", 0x10d6, mask);
        final Set<Integer> isRecovery = new HashSet<>();
        for (final int r : recovery) isRecovery.add(r);

        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (!MlsHealthStates.isState(from) || from == MlsHealthStates.HEALTHY) continue;
            final MlsHealthMachine.Applied a =
                    MlsHealthMachine.transition(at(from), MlsHealthStates.HEALTHY, NOW, false);
            if (isRecovery.contains(from)) {
                assertEquals(MlsHealthStates.name(from) + " is a recovery landing",
                        NOW, a.record.recoveredAt);
            } else {
                assertNull(MlsHealthStates.name(from) + " is NOT a recovery landing",
                        a.record.recoveredAt);
            }
        }
    }

    /**
     * Block B fires on the four EVEN ongoing states — and explicitly NOT on 9 (OngoingEndMls) or
     * 11 (OngoingReviveMls), which is the trap the symmetry sets.
     */
    @Test
    public void blockBFiresOnFourStatesAndNotOnTheTwoThatLookLikeThem() {
        final MlsConversationRecord.StatusRequest req =
                new MlsConversationRecord.StatusRequest(MlsHealthStates.ERAADVANCEMENTREQUESTED, 3);
        final int[] fires = {MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE,
                MlsHealthStates.ONGOINGPHOENIXMODE};
        for (final int to : fires) {
            final MlsConversationRecord cur = firstLegalSourceFor(to, req);
            if (cur == null) { fail("no legal source into " + MlsHealthStates.name(to)); return; }
            final MlsHealthMachine.Applied a = MlsHealthMachine.transition(cur, to, NOW, false);
            assertNull("Block B must clear the stored request on entering "
                    + MlsHealthStates.name(to), a.record.storedStatusRequest);
        }
        final int[] doesNotFire = {MlsHealthStates.ONGOINGENDMLS, MlsHealthStates.ONGOINGREVIVEMLS};
        for (final int to : doesNotFire) {
            final MlsConversationRecord cur = firstLegalSourceFor(to, req);
            if (cur == null) { fail("no legal source into " + MlsHealthStates.name(to)); return; }
            final MlsHealthMachine.Applied a = MlsHealthMachine.transition(cur, to, NOW, false);
            assertNotNull("Block B must NOT fire on " + MlsHealthStates.name(to),
                    a.record.storedStatusRequest);
        }
    }

    private static MlsConversationRecord firstLegalSourceFor(final int to,
            final MlsConversationRecord.StatusRequest req) {
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (from == to || !MlsHealthStates.isState(from)) continue;
            if (MlsHealthStates.isLegal(from, to)) {
                return at(from).toBuilder().storedStatusRequest(req).build();
            }
        }
        return null;
    }

    // ---- 3.5: the seed constructor and the ONE escape --------------------------------------------

    @Test
    public void onlyThreeSeedsAreAllowed() {
        for (final int seed : new int[] {MlsHealthStates.UNKNOWN, MlsHealthStates.INITIALIZING,
                MlsHealthStates.HEALTHY}) {
            assertEquals(seed,
                    MlsHealthMachine.newState(ME, GID, "r", "p", seed).healthStatus);
        }
        for (final int bad : new int[] {MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.DONEENDMLS, MlsHealthStates.CANNOTHEALDURINGENDMLS, 3, 5, 99}) {
            try {
                MlsHealthMachine.newState(ME, GID, "r", "p", bad);
                fail("seed " + bad + " must be refused");
            } catch (final IllegalArgumentException expected) {
                // the point: a group cannot be conjured into the middle of the ladder
            }
        }
    }

    /** The escape works from exactly two sources and throws from anywhere else. */
    @Test
    public void theEscapeHatchIsLimitedToItsTwoDocumentedSources() {
        for (final int from : new int[] {MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.ERAADVANCEMENTREQUESTED}) {
            final MlsConversationRecord r =
                    MlsHealthMachine.forceCannotHealDuringEndMls(at(from), "server end_mls");
            assertEquals(MlsHealthStates.CANNOTHEALDURINGENDMLS, r.healthStatus);
        }
        for (final int from : new int[] {MlsHealthStates.HEALTHY, MlsHealthStates.DONEENDMLS,
                MlsHealthStates.UNKNOWN}) {
            try {
                MlsHealthMachine.forceCannotHealDuringEndMls(at(from), "x");
                fail("the escape must be refused from " + MlsHealthStates.name(from));
            } catch (final IllegalArgumentException expected) {
                // exactly the point of a DOCUMENTED escape: it is not a general-purpose setter
            }
        }
    }

    /**
     * The two escape pairs must NOT be in the table. If they were, the bypass would be
     * indistinguishable from an ordinary transition and there would be nothing to document.
     */
    @Test
    public void theEscapePairsAreNotInTheTable() {
        assertFalse(MlsHealthStates.isLegal(MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertFalse(MlsHealthStates.isLegal(MlsHealthStates.ERAADVANCEMENTREQUESTED,
                MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    // ---- 3.6: the hatch is off ------------------------------------------------------------------

    /**
     * The parameter exists (3.1 mandates the signature) but nothing ships it on. Passing true is
     * expressible in a test and produces the substitution — which is exactly what must never be
     * reachable from an operator-flippable flag, because the hatch KEEPS the illegal move.
     */
    @Test
    public void theHatchSubstitutesInvalidTransitionWhenForcedOn() {
        final MlsHealthMachine.Decision off = MlsHealthMachine.decide(
                MlsHealthStates.HEALTHY, MlsHealthStates.INITIALIZING, false);
        assertEquals(MlsHealthMachine.Outcome.ILLEGAL_REJECTED, off.outcome);

        final MlsHealthMachine.Decision on = MlsHealthMachine.decide(
                MlsHealthStates.HEALTHY, MlsHealthStates.INITIALIZING, true);
        assertEquals(MlsHealthMachine.Outcome.ILLEGAL_ALLOWED, on.outcome);
        assertEquals(MlsHealthEdge.INVALID_TRANSITION, on.edge);
        assertTrue("an allowed-illegal move is still a state change", on.changesState());
    }

    // ---- 3.3: wire numbers, never ordinals -------------------------------------------------------

    /**
     * The wire numbering has holes at 3 and 5, so a Java {@code ordinal()} would diverge from the
     * wire number from EraAdvancementRequested(7) onward. These constants ARE the wire numbers.
     */
    @Test
    public void stateConstantsAreWireNumbersWithTheirHoles() {
        assertFalse("3 is unassigned", MlsHealthStates.isState(3));
        assertFalse("5 is unassigned", MlsHealthStates.isState(5));
        assertEquals(7, MlsHealthStates.ERAADVANCEMENTREQUESTED);
        assertEquals(17, MlsHealthStates.PHOENIXMODEREQUESTED);
        int states = 0;
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) if (MlsHealthStates.isState(i)) states++;
        assertEquals("16 states across 18 slots", 16, states);
    }
}
