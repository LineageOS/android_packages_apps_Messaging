/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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

/**
 * {@link MlsHealthMachine}: the transition table, guard order and hooks. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsHealthMachineTest {

    private static final String ME = "+15715550104";
    private static final byte[] GID = new byte[] {1, 2, 3};
    private static final Moment NOW = new Moment(4, 11L);

    private static MlsConversationRecord at(final int status) {
        return MlsConversationRecord.initial(ME, GID, "rcs", "+1555")
                .toBuilder().healthStatus(status).build();
    }

    /**
     * All 324 cells: legal pairs decide LEGAL with an edge, self-pairs NO_OP, the rest
     * ILLEGAL_REJECTED. A mis-transcribed cell is invisible without this.
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
        // 127 legal pairs, minus the self-loop the guard order intercepts.
        assertEquals("the table's legal pairs, less the self-loop", 126, legal);
    }

    /** Every self-pair is a no-op that carries no edge and emits no telemetry. */
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

    /** Healthy is reachable in one hop from every other state, so no state is a trap. */
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

    /** The one self-loop in the table is unreachable because of guard order. */
    @Test
    public void theOneSelfLoopIsUnreachable() {
        final int s = MlsHealthStates.ONGOINGERAADVANCEMENT;
        assertTrue("the table still contains the self-loop", MlsHealthStates.isLegal(s, s));
        assertEquals("...and the guard order makes it unreachable",
                MlsHealthMachine.Outcome.NO_OP, MlsHealthMachine.decide(s, s, false).outcome);
    }

    /** There is no EraAdvancementFailed edge, unlike the epoch one. */
    @Test
    public void eraAdvancementFailedIsNotAnEdge() {
        assertNull(MlsHealthEdge.byWireName("EraAdvancementFailed"));
        assertNotNull("...while the epoch one IS",
                MlsHealthEdge.byWireName("EpochAdvancementFailed"));
    }

    /** INVALID_TRANSITION is never produced by the table. */
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

    /**
     * {@code ordinalValue} and {@code telemetryValue} are independent numberings; reporting
     * {@code ordinal()} as the metric would give plausible wrong numbers.
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

    /** The edge is determined by (from, to): one edge name lands in two destinations. */
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

    /** An illegal transition returns the same record instance. */
    @Test
    public void anIllegalTransitionMutatesNothing() {
        final MlsConversationRecord cur = at(MlsHealthStates.HEALTHY);
        // Healthy -> Initializing is not in the table.
        final MlsHealthMachine.Applied a =
                MlsHealthMachine.transition(cur, MlsHealthStates.INITIALIZING, NOW, false);
        assertEquals(MlsHealthMachine.Outcome.ILLEGAL_REJECTED, a.decision.outcome);
        assertSame("a rejected transition must not produce a new record", cur, a.record);
        assertEquals(MlsHealthStates.HEALTHY, a.record.healthStatus);
    }

    /**
     * The no-op still runs on_enter: Healthy -> Healthy restamps lastHealthyMoment without
     * telemetry or on_transition.
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

    /** A no-op to a non-Healthy state changes nothing: on_enter is gated on the destination. */
    @Test
    public void aNoOpToANonHealthyStateChangesNothing() {
        final MlsConversationRecord cur = at(MlsHealthStates.DONEENDMLS);
        final MlsHealthMachine.Applied a =
                MlsHealthMachine.transition(cur, MlsHealthStates.DONEENDMLS, NOW, false);
        assertEquals(MlsHealthMachine.Outcome.NO_OP, a.decision.outcome);
        assertNull(a.record.lastHealthyMoment);
        assertNull(a.record.recoveredAt);
    }

    /** on_enter stamps lastHealthyMoment on every entry to Healthy. */
    @Test
    public void everyEntryToHealthyStampsLastHealthyMoment() {
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (!MlsHealthStates.isState(from) || from == MlsHealthStates.HEALTHY) continue;
            final MlsHealthMachine.Applied a =
                    MlsHealthMachine.transition(at(from), MlsHealthStates.HEALTHY, NOW, false);
            assertEquals(MlsHealthStates.name(from), MlsHealthMachine.Outcome.LEGAL,
                    a.decision.outcome);
            assertEquals(MlsHealthStates.name(from) + " -> Healthy", NOW,
                    a.record.lastHealthyMoment);
        }
    }

    /**
     * Block A stamps recoveredAt only on a recovery landing, the six states of mask {@code 0x10d6}.
     * DoneEndMls (10) is excluded although DoneEndMls -> Healthy is legal: recoveredAt gates resend
     * admission, and a spurious stamp would admit fewer resends.
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
     * Block B fires on the four even ongoing states and not on 9 (OngoingEndMls) or 11
     * (OngoingReviveMls).
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
                // a group cannot be seeded into the middle of the ladder
            }
        }
    }

    /** The escape works from exactly its two sources and throws from anywhere else. */
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
                // the escape is not a general-purpose setter
            }
        }
    }

    /** The escape pairs are not in the table, so the bypass stays distinguishable. */
    @Test
    public void theEscapePairsAreNotInTheTable() {
        assertFalse(MlsHealthStates.isLegal(MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertFalse(MlsHealthStates.isLegal(MlsHealthStates.ERAADVANCEMENTREQUESTED,
                MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    /**
     * The allow-illegal parameter exists but nothing passes true: the hatch keeps the illegal move,
     * so it must not be reachable from an operator flag.
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

    /**
     * The wire numbering has holes at 3 and 5; the constants are the wire numbers, not ordinals.
     */
    @Test
    public void stateConstantsAreWireNumbersWithTheirHoles() {
        assertFalse("3 is unassigned", MlsHealthStates.isState(3));
        assertFalse("5 is unassigned", MlsHealthStates.isState(5));
        assertEquals(7, MlsHealthStates.ERAADVANCEMENTREQUESTED);
        assertEquals(17, MlsHealthStates.PHOENIXMODEREQUESTED);
        int states = 0;
        for (int i = 0; i < MlsHealthStates.STATE_SLOTS; i++) if (
                MlsHealthStates.isState(i)) states++;
        assertEquals("16 states across 18 slots", 16, states);
    }
}
