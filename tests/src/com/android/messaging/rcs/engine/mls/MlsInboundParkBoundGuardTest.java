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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>BOTH §10.8 G1 park sites are bounded, and the release is CHECKED.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code MlsHealthStates.ERAADVANCEMENTREQUESTED(7)} is written at two sites in the transport —
 * the server's EPOCH-quota escalation and self-heal budget exhaustion — and no site names it. It is
 * also inside §10.8's G1 inbound-buffering mask, so a record that lands on it <b>stops receiving</b>.
 * An earlier change had already bounded that exact closed loop (park because healing, healing never
 * progresses because nothing looks, nothing looks because everything parks) by counting parks at an
 * unchanged group moment and killing the heal — <b>but the counter went into the APPLICATION plane
 * only</b>. {@code bufferInboundIfGroupLocked}, which is the CONTROL plane's G1 gate, counted
 * nothing, so a conversation whose inbound is all control parked every message for ever.
 *
 * <h2>What is pinned here, and why each half is needed</h2>
 *
 * <ol>
 *   <li><b>Both planes feed the counter and both consult the bound.</b> Delete either call and this
 *       fails by name. The CONTROL half was added later; the APPLICATION half is the
 *       original, and it is pinned here too because the shared counter makes them one
 *       mechanism — removing either leaves the other looking correct.</li>
 *   <li><b>The control plane's release is READ BACK, not assumed.</b> §5.3 offers a
 *       {@code SelfHealKilled} edge into {@code SelfHealFailed(6)} from only five states, and four
 *       of the eight G1 states are not among them. {@code killSelfHeal} declines rather than forcing
 *       an illegal transition, so "we called it, therefore we are out of the mask" is false for half
 *       the mask.</li>
 *   <li><b>That read-back can actually fail</b> — asserted against the engine's own tables rather
 *       than stated. A guard whose negative arm is unreachable is not evidence (the rule this
 *       project keeps re-learning), so the test names the G1 states that have no edge out and
 *       requires the set to be non-empty.</li>
 *   <li><b>Both writers of state 7 say what it costs.</b> The escalation lines used to report only
 *       that they had escalated; the pairing "…and this conversation has now stopped receiving" was
 *       nowhere. An operator saw parked messages and a health string with no stated connection
 *       between them.</li>
 * </ol>
 *
 * <h2>On being a source scan</h2>
 *
 * <p>Keyed on INVOKED METHOD NAMES over {@link SourceScan#transport()}, which blanks comments and
 * string contents — so a comment describing the bound cannot satisfy it, and this file can document
 * what it forbids. Zero hits FAIL: every name below is required to appear an exact number of times,
 * so a pattern that has gone stale reports zero and fails here rather than passing quietly.
 */
public final class MlsInboundParkBoundGuardTest {

    /** The CONTROL plane's §10.8 G1 gate — parks before any decrypt is attempted. */
    private static final String CONTROL_GATE = "bufferInboundIfGroupLocked";
    /** The APPLICATION plane's park, reached from {@code onDecryptFailure}. */
    private static final String APPLICATION_GATE = "parkFutureCiphertext";

    /** The shared counter's two doors. */
    private static final String NOTE = "noteParkAtUnchangedMoment(";
    private static final String CLEAR = "clearParksAtUnchangedMoment(";
    /** The engine-owned bound. The transport must reach it through the predicate, never the raw
     *  constant — {@code MlsTransportPolicyConstantGuardTest} enforces the second half. */
    private static final String EXHAUSTED = "MlsSelfHealPass.parksExhausted(";
    /** The release. */
    private static final String KILL = "killSelfHeal(";
    /** The G1 mask, as production spells it. */
    private static final String LOCKED = "MlsPendingQueue.groupLocked(";
    /** The escalation suffix that states the cost of landing on a G1 state. */
    private static final String SAY_PARKED = "inboundNowParked(";

    /**
     * <b>The mutation gate.</b> Remove the control plane's park bound — the state this was
     * written against — and this fails.
     */
    @Test public void bothG1ParkSitesCountParksAndConsultTheSameBound() throws IOException {
        final String src = SourceScan.transport();
        for (final String gate : new String[] {CONTROL_GATE, APPLICATION_GATE}) {
            final String body = SourceScan.bodyOf(src, gate);
            assertFalse(gate + " no longer exists in the transport — this guard's subject is gone, "
                    + "and a park gate that cannot be found is not a park gate that is bounded",
                    body.isEmpty());
            assertEquals(gate + " must feed the shared park counter exactly once (" + NOTE + ")",
                    1, SourceScan.count(body, NOTE));
            assertEquals(gate + " must consult the engine's bound exactly once (" + EXHAUSTED + ")",
                    1, SourceScan.count(body, EXHAUSTED));
            // AT LEAST ONE, not exactly one, and the difference is production rather than slack:
            // CONTROL_GATE kills a heal on a SECOND path too (a commit at our current
            // epoch pre-empts the heal that is blocking it). Which of them belongs to THIS bound is
            // pinned by order, in theControlPlaneChecksThatTheKillActuallyLeftTheMask below.
            assertTrue(gate + " must release the heal when the bound is reached (" + KILL + ")",
                    SourceScan.count(body, KILL) >= 1);
            assertEquals(gate + " must reset the run it has just acted on (" + CLEAR + ")",
                    1, SourceScan.count(body, CLEAR));
        }
    }

    /**
     * The counter is ONE counter. A second, per-plane one would let each half stay under the
     * threshold for ever on a conversation receiving a mix of traffic — which is precisely the
     * conversation the bound exists for.
     */
    @Test public void theParkCounterHasExactlyTheTwoDoorsThisGuardKnowsAbout() throws IOException {
        final String src = SourceScan.transport();
        final List<int[]> decls = SourceScan.declarations(src);
        final Set<String> callers = new LinkedHashSet<>();
        for (final Integer at : SourceScan.invocationsOf(src, decls, NOTE)) {
            callers.add(SourceScan.enclosingMethod(src, decls, at.intValue()));
        }
        assertEquals("the park counter is fed from " + callers + "; it must be fed from exactly the "
                + "two G1 park gates, because a third door is a third opinion about when a "
                + "conversation has stopped receiving", 2, callers.size());
        assertTrue(CONTROL_GATE + " must be one of them (it is the half added later; " + callers
                + ")", callers.contains(CONTROL_GATE));
        assertTrue(APPLICATION_GATE + " must be one of them (it is the original; " + callers
                + ")", callers.contains(APPLICATION_GATE));
    }

    /**
     * <b>The control plane must READ THE RECORD BACK after killing the heal</b>, because the kill is
     * allowed to decline. Processing a control message inline while still inside the mask hands the
     * engine a message it buffers on the same status.
     */
    @Test public void theControlPlaneChecksThatTheKillActuallyLeftTheMask() throws IOException {
        final String src = SourceScan.transport();
        final String body = SourceScan.bodyOf(src, CONTROL_GATE);
        // ANCHORED ON THE BOUND, not on the first kill in the method. CONTROL_GATE has an earlier,
        // unrelated killSelfHeal, and anchoring on that one would let this test pass
        // with the park bound deleted entirely.
        final int bound = body.indexOf(EXHAUSTED);
        assertTrue(CONTROL_GATE + " does not consult " + EXHAUSTED, bound >= 0);
        final int kill = body.indexOf(KILL, bound);
        assertTrue(CONTROL_GATE + " consults the bound and never calls " + KILL + " after it",
                kill > bound);
        final int checkAfter = body.indexOf(LOCKED, kill);
        assertTrue(CONTROL_GATE + " calls " + KILL + " and never re-reads the record's health "
                + "through " + LOCKED + " afterwards. killSelfHeal declines for four of the eight "
                + "G1 states, so this would be asserting a release that did not happen.",
                checkAfter > kill);
    }

    /**
     * The read-back above CAN fail — measured off the engine's own tables, not asserted as prose.
     *
     * <p>If every G1 state had a {@code SelfHealKilled} edge the check would be decoration, and a
     * guard that cannot fail is not evidence. It names the states that do not, so the next reader
     * can see which conversations still cannot be unstuck from the inbound path.
     */
    @Test public void notEveryBufferingStateCanBeKilledOutOfTheMask() {
        final List<String> releasable = new ArrayList<>();
        final List<String> stuck = new ArrayList<>();
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthStates.isState(s) || !MlsHealthPredicates.buffersInbound(s)) continue;
            final String edge = MlsHealthStates.edge(s, MlsHealthStates.SELFHEALFAILED);
            (edge == null ? stuck : releasable).add(MlsHealthStates.name(s));
        }
        if (releasable.isEmpty()) {
            fail("no G1 state has a SelfHealKilled edge out — the control plane's release could "
                    + "never work for anything, so the bound it guards is dead code");
        }
        if (stuck.isEmpty()) {
            fail("every G1 state can be killed out of the mask, so the control plane's read-back "
                    + "of MlsPendingQueue.groupLocked can never be false. Delete the check or the "
                    + "assertion that it is load-bearing — a branch that cannot be taken is not a "
                    + "safety property. (Releasable: " + releasable + ")");
        }
        assertFalse("SelfHealFailed(6) must itself be OUTSIDE the mask, or the release releases "
                + "nothing", MlsHealthPredicates.buffersInbound(MlsHealthStates.SELFHEALFAILED));
    }

    /**
     * The state this is named for: {@code EraAdvancementRequested(7)} buffers inbound AND is
     * one of the states the kill can release. Both halves matter — the first is why a record parks
     * on it, the second is why the bound above is its automatic driver.
     */
    @Test public void eraAdvancementRequestedBuffersInboundAndIsReleasable() {
        assertTrue("EraAdvancementRequested(7) must be inside §10.8's G1 mask — that is the whole "
                + "reason a record landing on it stops receiving",
                MlsHealthPredicates.buffersInbound(MlsHealthStates.ERAADVANCEMENTREQUESTED));
        assertNotNull("§5.3 must keep EraAdvancementRequested → SelfHealFailed (SelfHealKilled); "
                + "without it the park bound cannot unstick the one state it was added for",
                MlsHealthStates.edge(MlsHealthStates.ERAADVANCEMENTREQUESTED,
                        MlsHealthStates.SELFHEALFAILED));
    }

    /**
     * Every writer of a G1 health status in the transport states the consequence.
     *
     * <p>Scoped to the two ERA-advancement escalations rather than to all ~40 {@code moveHealth}
     * sites: these are the two that write a buffering state as an <em>escalation</em>, i.e. as the
     * last thing that happens to the conversation, with nothing downstream to report it.
     */
    @Test public void bothEscalationsIntoTheMaskSayThatReceivingHasStopped() throws IOException {
        final String src = SourceScan.transport();
        final List<int[]> decls = SourceScan.declarations(src);
        final Set<String> writers = new LinkedHashSet<>();
        for (final Integer at : SourceScan.usesOf(src, "ERAADVANCEMENTREQUESTED")) {
            writers.add(SourceScan.enclosingMethod(src, decls, at.intValue()));
        }
        assertEquals("EraAdvancementRequested is written from " + writers + " — this guard knows "
                + "about two escalation sites. A third writer needs its own answer to 'and then "
                + "what moves it?' before it is added to this list.", 2, writers.size());
        for (final String w : writers) {
            final String body = SourceScan.bodyOf(src, w);
            assertTrue(w + " moves a record into §10.8's G1 mask and does not say so (" + SAY_PARKED
                    + " is absent). A conversation that silently stops receiving is the worst "
                    + "failure shape this system has.", body.contains(SAY_PARKED));
        }
    }
}
