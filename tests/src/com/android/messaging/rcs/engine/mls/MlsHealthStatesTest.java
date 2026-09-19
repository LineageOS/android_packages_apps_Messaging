/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link MlsHealthStates}, checked exhaustively over all 18x18 slots: a spot-check cannot tell an
 * illegal pair from one dropped in transcription. See docs/mls/health-and-recovery.md.
 */
public final class MlsHealthStatesTest {

    /** 16 defined states, 127 legal pairs, and the two wire gaps intact. */
    @Test
    public void theTableMatchesTheEngineExactly() {
        int defined = 0;
        for (int v = 0; v < MlsHealthStates.STATE_SLOTS; v++) {
            if (MlsHealthStates.isState(v)) defined++;
        }
        assertEquals("16 defined states", 16, defined);
        assertEquals("127 legal transitions", 127, MlsHealthStates.legalPairs().size());

        // 3 and 5 are gaps in the wire enum; a decoder that renumbers disagrees with every peer.
        assertTrue(!MlsHealthStates.isState(3));
        assertTrue(!MlsHealthStates.isState(5));
        assertNull(MlsHealthStates.name(3));
        assertEquals("Healthy", MlsHealthStates.name(MlsHealthStates.HEALTHY));
        assertEquals("Unknown", MlsHealthStates.name(MlsHealthStates.UNKNOWN));
    }

    /** Every slot is either a named edge or illegal. */
    @Test
    public void everySlotIsDecidedOneWayOrTheOther() {
        int legal = 0;
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                final boolean ok = MlsHealthStates.isLegal(from, to);
                final String edge = MlsHealthStates.edge(from, to);
                assertEquals("isLegal and edge must agree at " + from + "->" + to, ok, edge
                        != null);
                if (ok) {
                    legal++;
                    assertTrue("a legal pair must name a non-empty edge", !edge.isEmpty());
                    // An edge only connects defined states.
                    assertTrue("edge from an undefined state " + from,
                            MlsHealthStates.isState(from));
                    assertTrue("edge to an undefined state " + to, MlsHealthStates.isState(to));
                }
            }
        }
        assertEquals(127, legal);
    }

    /**
     * No state is a dead end. A self-pair and an unreachable edge do not count as exits: the
     * machine answers {@code cur == to} as a no-op before it consults the table.
     */
    @Test
    public void everyStateHasAnExitEdgeThatIsNotItself() {
        final List<String> deadEnds = new ArrayList<>();
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (!MlsHealthStates.isState(from)) continue;          // wire gap
            int usableExits = 0;
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                // a self-pair is a no-op, not an exit
                if (to == from) continue;
                if (!MlsHealthStates.isState(to)) continue;
                if (!MlsHealthStates.isLegal(from, to)) continue;
                if (MlsHealthStates.isUnreachable(from, to)) continue;
                usableExits++;
            }
            if (usableExits == 0) deadEnds.add(MlsHealthStates.name(from) + "(" + from + ")");
        }
        assertTrue("these states cannot be left — a conversation reaching one stops sending and "
                + "receiving with nothing surfaced: " + deadEnds,
                deadEnds.isEmpty());
    }

    /**
     * Exactly one self-pair is in the table and it is never taken; "already in the target state"
     * must be a no-op, or a loop re-applying it would make progress forever without changing
     * anything.
     */
    @Test
    public void theOnlySelfPairIsTheDocumentedUnreachableOne() {
        final List<Integer> selfLegal = new ArrayList<>();
        for (int v = 0; v < MlsHealthStates.STATE_SLOTS; v++) {
            if (MlsHealthStates.isLegal(v, v)) selfLegal.add(v);
        }
        assertEquals("exactly one self-pair in the table", 1, selfLegal.size());
        final int v = selfLegal.get(0);
        assertEquals("OngoingEraAdvancement", MlsHealthStates.name(v));
        assertTrue("and it is marked UNREACHABLE — the engine encodes it but never takes it",
                MlsHealthStates.isUnreachable(v, v));

        // No other pair is marked unreachable.
        int unreachable = 0;
        for (int a = 0; a < MlsHealthStates.STATE_SLOTS; a++) {
            for (int b = 0; b < MlsHealthStates.STATE_SLOTS; b++) {
                if (MlsHealthStates.isUnreachable(a, b)) unreachable++;
            }
        }
        assertEquals(1, unreachable);
    }

    /** Every state can reach Healthy, in at most one hop. */
    @Test
    public void everyStateReachesHealthyAndIsAtMostOneHopAway() {
        for (int v = 0; v < MlsHealthStates.STATE_SLOTS; v++) {
            if (!MlsHealthStates.isState(v) || v == MlsHealthStates.HEALTHY) continue;
            final int hops = hopsToHealthy(v);
            assertTrue(MlsHealthStates.name(v) + " cannot reach Healthy at all", hops > 0);
            assertTrue(MlsHealthStates.name(v) + " is " + hops
                    + " hops from Healthy, expected <= 1", hops <= 1);
        }
    }

    /** Shortest path length to Healthy over legal, reachable edges; -1 if unreachable. */
    private static int hopsToHealthy(final int start) {
        final Set<Integer> seen = new HashSet<>();
        final Deque<int[]> q = new ArrayDeque<>();   // {state, depth}
        q.add(new int[] {start, 0});
        seen.add(start);
        while (!q.isEmpty()) {
            final int[] cur = q.poll();
            if (cur[0] == MlsHealthStates.HEALTHY) return cur[1];
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                if (!MlsHealthStates.isLegal(cur[0], to)) continue;
                if (MlsHealthStates.isUnreachable(cur[0], to)) continue;   // encodable, never taken
                if (seen.add(to)) q.add(new int[] {to, cur[1] + 1});
            }
        }
        return -1;
    }

    /**
     * {@code Unknown} is source-only (9 out, 0 in): it is the default for a record never written,
     * and transitioning into it would discard what the previous state said.
     */
    @Test
    public void unknownIsSourceOnly() {
        int out = 0;
        for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
            if (MlsHealthStates.isLegal(MlsHealthStates.UNKNOWN, to)) out++;
        }
        assertEquals("Unknown has 9 outbound edges", 9, out);
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            assertTrue("nothing may transition INTO Unknown (from " + from + ")",
                    !MlsHealthStates.isLegal(from, MlsHealthStates.UNKNOWN));
        }
    }

    /** A few edges checked by name, so a renumbering cannot pass silently. */
    @Test
    public void namedEdgesSurviveTranscription() {
        assertEquals("GroupCreated",
                MlsHealthStates.edge(MlsHealthStates.UNKNOWN, MlsHealthStates.HEALTHY));
        assertEquals("EpochAdvancementSuccessful",
                MlsHealthStates.edge(MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.HEALTHY));
        assertEquals("SelfHealKilled",
                MlsHealthStates.edge(MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.SELFHEALFAILED));
        assertNotNull(MlsHealthStates.edge(MlsHealthStates.HEALTHY,
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        // An invented pair does not resolve.
        assertNull(MlsHealthStates.edge(MlsHealthStates.DONEENDMLS, MlsHealthStates.INITIALIZING));
    }

    /**
     * The self-heal exhaustion escalation is reachable: {@code selfHealInner} records {@code
     * EpochAdvancementRequested} before it charges the budget, so an exhausted heal is not at
     * Unknown when {@code escalateExhaustedSelfHeal} runs. Only the table half is pinned here; the
     * ordering in {@code selfHealInner} is not asserted.
     */
    @Test
    public void theSelfHealExhaustionEscalationIsReachableFromWhereTheLadderRecordsIt() {
        assertNull(
                "Unknown must still have NO edge to EraAdvancementRequested — that is what makes "
                + "escalateExhaustedSelfHeal's Unknown arm a real arm rather than dead code",
                MlsHealthStates.edge(
                        MlsHealthStates.UNKNOWN, MlsHealthStates.ERAADVANCEMENTREQUESTED));
        assertEquals(
                "selfHealInner's entry rung must stay legal from Unknown, or the ladder cannot "
                + "record that a heal was asked for at all", "RequestedSelfHeal",
                MlsHealthStates.edge(
                        MlsHealthStates.UNKNOWN, MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        assertEquals("the escalation itself: an exhausted heal sits at EpochAdvancementRequested, "
                + "and THIS is the edge escalateExhaustedSelfHeal takes. If it stops being legal, "
                + "the ladder's last rung falls to the ILLEGAL branch and the conversation is stuck "
                + "with no escalation path — which that branch's own log line already says.",
                "EpochAdvancementFailed",
                MlsHealthStates.edge(MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ERAADVANCEMENTREQUESTED));
    }
}
