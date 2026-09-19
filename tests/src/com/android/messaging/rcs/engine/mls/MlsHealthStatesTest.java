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
 * The health state machine, checked EXHAUSTIVELY over all 18×18 slots.
 *
 * <p>Exhaustive rather than sampled on purpose. The table is the one artefact that says which
 * sequences of states are possible at all, and a spot-check cannot distinguish "this pair is absent
 * because it is illegal" from "this pair is absent because it was dropped in transcription".
 */
public final class MlsHealthStatesTest {

    /** THE TABLE ITSELF: 16 defined states, 127 legal pairs, and the two wire gaps intact. */
    @Test
    public void theTableMatchesTheEngineExactly() {
        int defined = 0;
        for (int v = 0; v < MlsHealthStates.STATE_SLOTS; v++) {
            if (MlsHealthStates.isState(v)) defined++;
        }
        assertEquals("16 defined states", 16, defined);
        assertEquals("127 legal transitions", 127, MlsHealthStates.legalPairs().size());

        // 3 and 5 are GAPS IN THE WIRE ENUM, not states we failed to transcribe. A decoder that
        // renumbers to close them disagrees with every peer.
        assertTrue(!MlsHealthStates.isState(3));
        assertTrue(!MlsHealthStates.isState(5));
        assertNull(MlsHealthStates.name(3));
        assertEquals("Healthy", MlsHealthStates.name(MlsHealthStates.HEALTHY));
        assertEquals("Unknown", MlsHealthStates.name(MlsHealthStates.UNKNOWN));
    }

    /** Every one of the 18×18 slots is either a named edge or illegal — never ambiguous. */
    @Test
    public void everySlotIsDecidedOneWayOrTheOther() {
        int legal = 0;
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                final boolean ok = MlsHealthStates.isLegal(from, to);
                final String edge = MlsHealthStates.edge(from, to);
                assertEquals("isLegal and edge must agree at " + from + "->" + to, ok, edge != null);
                if (ok) {
                    legal++;
                    assertTrue("a legal pair must name a non-empty edge", !edge.isEmpty());
                    // An edge may only connect states that exist.
                    assertTrue("edge from an undefined state " + from,
                            MlsHealthStates.isState(from));
                    assertTrue("edge to an undefined state " + to, MlsHealthStates.isState(to));
                }
            }
        }
        assertEquals(127, legal);
    }

    /**
     * <b>NO STATE IS A DEAD END</b> — the rework's headline acceptance property, asserted here
     * rather than assumed.
     *
     * <p>The plan named this as the thing to deliver: a "no state reachable with no exit
     * edge" check as a TABLE-WALKING HOST TEST that also catches UNREACHABLE states, which a compile
     * error would not. The table has been carrying the property since it was transcribed and
     * nothing has been guarding it, so a future edit could remove the last exit from a state and
     * every existing test would still pass.
     *
     * <p>A SELF-PAIR AND AN UNREACHABLE EDGE DO NOT COUNT as exits. That is the whole subtlety: a
     * state whose only outbound edge is the documented {@code OngoingEraAdvancement} self-loop would
     * satisfy a naive "out-degree > 0" check and still be a trap, because the machine answers
     * {@code cur == to} as a no-op before it ever consults the table.
     *
     * <p><b>Why it matters beyond tidiness</b>: a conversation that reaches a
     * state it cannot leave stops sending and receiving with nothing surfaced to the user. Every
     * real wedge found on device has been a state that was terminal IN PRACTICE — the ladder had an
     * exit and nothing requested it — so the structural property is necessary, not sufficient, and
     * it is worth knowing the day the structural half regresses too.
     */
    @Test
    public void everyStateHasAnExitEdgeThatIsNotItself() {
        final List<String> deadEnds = new ArrayList<>();
        for (int from = 0; from < MlsHealthStates.STATE_SLOTS; from++) {
            if (!MlsHealthStates.isState(from)) continue;          // 3 and 5 are wire gaps
            int usableExits = 0;
            for (int to = 0; to < MlsHealthStates.STATE_SLOTS; to++) {
                if (to == from) continue;                          // a self-pair is a no-op, not an exit
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
     * SELF-PAIRS ARE NOT TRANSITIONS. Exactly one exists in the table and the engine never takes it.
     *
     * <p>This is the property the rework's drive loop depends on: "already in the target state" must
     * be a NO-OP, not a transition to perform. If self-pairs were legal transitions, a loop that
     * re-applied one would make progress forever without changing anything.
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

        // No OTHER pair is marked unreachable; a growing unreachable set would mean transcription
        // noise rather than a faithful table.
        int unreachable = 0;
        for (int a = 0; a < MlsHealthStates.STATE_SLOTS; a++) {
            for (int b = 0; b < MlsHealthStates.STATE_SLOTS; b++) {
                if (MlsHealthStates.isUnreachable(a, b)) unreachable++;
            }
        }
        assertEquals(1, unreachable);
    }

    /**
     * EVERY STATE CAN GET BACK TO HEALTHY, and the recovery ladder is shallow.
     *
     * <p>A state with no path to {@code Healthy} is a conversation that can never recover — the
     * exact failure the rework exists to make impossible. The design doc asserts every state is at
     * most one hop away; this checks it rather than trusting it, and prints the offender if not.
     */
    @Test
    public void everyStateReachesHealthyAndIsAtMostOneHopAway() {
        for (int v = 0; v < MlsHealthStates.STATE_SLOTS; v++) {
            if (!MlsHealthStates.isState(v) || v == MlsHealthStates.HEALTHY) continue;
            final int hops = hopsToHealthy(v);
            assertTrue(MlsHealthStates.name(v) + " cannot reach Healthy at all", hops > 0);
            assertTrue(MlsHealthStates.name(v) + " is " + hops + " hops from Healthy, expected <= 1",
                    hops <= 1);
        }
    }

    /** Shortest path length to Healthy over legal, REACHABLE edges; -1 if unreachable. */
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
     * {@code Unknown} is SOURCE-ONLY — 9 out, 0 in.
     *
     * <p>It is the proto default for a group whose row exists but was never written. Nothing may
     * transition INTO it: doing so would turn a known conversation back into an unwritten one and
     * lose whatever the previous state was telling us.
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

    /** A few edges spot-checked by NAME, so a renumbering cannot pass silently. */
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
        // An invented pair must NOT resolve.
        assertNull(MlsHealthStates.edge(MlsHealthStates.DONEENDMLS, MlsHealthStates.INITIALIZING));
    }

    /**
     * The self-heal exhaustion escalation is reachable from the state the ladder actually records.
     *
     * <p>{@code MlsProviderTransport.escalateExhaustedSelfHeal} carried a comment saying the
     * escalation could not land because <i>"nothing writes a health status yet, so every record
     * still sits at Unknown"</i>. The Unknown half of it is true and stays checked below; the
     * premise was not — about twenty {@code moveHealth} sites write health, and
     * {@code selfHealInner} records {@code EpochAdvancementRequested} <b>before</b> it charges the
     * budget, so a heal that exhausts its budget is never at Unknown when the escalation is tried.
     * A reader taking that comment at face value would conclude the whole
     * {@code escalateExhaustedSelfHeal → offerTheStallChoiceOffThread} path is dead, which is
     * exactly the estimate Stage 0 §1.5c predicted 9-13 {@code GetMlsGroupInfo} looks
     * for and nobody has run.
     *
     * <p><b>What this test does NOT pin</b>, stated so it is not assumed: the ORDERING in
     * {@code selfHealInner} — {@code moveHealth} before {@code chargeSelfHealBudget} — is transport
     * source and nothing asserts it. Only the table half is here, which is the half that would
     * change silently if an edge were re-cut.
     */
    @Test
    public void theSelfHealExhaustionEscalationIsReachableFromWhereTheLadderRecordsIt() {
        assertNull("Unknown must still have NO edge to EraAdvancementRequested — that is what makes "
                + "escalateExhaustedSelfHeal's Unknown arm a real arm rather than dead code",
                MlsHealthStates.edge(
                        MlsHealthStates.UNKNOWN, MlsHealthStates.ERAADVANCEMENTREQUESTED));
        assertEquals("selfHealInner's entry rung must stay legal from Unknown, or the ladder cannot "
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
