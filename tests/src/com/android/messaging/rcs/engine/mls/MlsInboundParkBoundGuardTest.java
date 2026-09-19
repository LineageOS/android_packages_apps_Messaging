/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Both RCC.16 §10.8 G1 park sites count parks at an unchanged group moment against one bound, and
 * the control plane checks that killing the heal really left the buffering mask:
 * {@code killSelfHeal} declines where the table has no {@code SelfHealKilled} edge. Both writers of
 * {@code EraAdvancementRequested(7)}, a buffering state, say that receiving has stopped. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsInboundParkBoundGuardTest {

    /** The control plane's G1 gate; it parks before any decrypt is attempted. */
    private static final String CONTROL_GATE = "bufferInboundIfGroupLocked";
    /** The application plane's park, reached from {@code onDecryptFailure}. */
    private static final String APPLICATION_GATE = "parkFutureCiphertext";

    private static final String NOTE = "noteParkAtUnchangedMoment(";
    private static final String CLEAR = "clearParksAtUnchangedMoment(";
    /**
     * The engine-owned bound, reached through the predicate and never the raw constant
     * ({@code MlsTransportPolicyConstantGuardTest} enforces the second half).
     */
    private static final String EXHAUSTED = "MlsSelfHealPass.parksExhausted(";
    private static final String KILL = "killSelfHeal(";
    private static final String LOCKED = "MlsPendingQueue.groupLocked(";
    private static final String SAY_PARKED = "inboundNowParked(";

    /** Remove either plane's park bound and this fails. */
    @Test public void bothG1ParkSitesCountParksAndConsultTheSameBound() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
        for (final String gate : new String[] {CONTROL_GATE, APPLICATION_GATE}) {
            final String body = SourceScan.bodyOf(src, gate);
            assertFalse(gate + " no longer exists in the transport — this guard's subject is gone, "
                    + "and a park gate that cannot be found is not a park gate that is bounded",
                    body.isEmpty());
            assertEquals(gate + " must feed the shared park counter exactly once (" + NOTE + ")",
                    1, SourceScan.count(body, NOTE));
            assertEquals(gate + " must consult the engine's bound exactly once (" + EXHAUSTED + ")",
                    1, SourceScan.count(body, EXHAUSTED));
            // At least one: the control gate also kills a heal when a commit at our current epoch
            // pre-empts it. Which kill belongs to this bound is pinned by order below.
            assertTrue(gate + " must release the heal when the bound is reached (" + KILL + ")",
                    SourceScan.count(body, KILL) >= 1);
            assertEquals(gate + " must reset the run it has just acted on (" + CLEAR + ")",
                    1, SourceScan.count(body, CLEAR));
        }
    }

    /**
     * One counter: a per-plane counter would let each half stay under the threshold on a
     * conversation receiving mixed traffic.
     */
    @Test public void theParkCounterHasExactlyTheTwoDoorsThisGuardKnowsAbout() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
        final List<int[]> decls = SourceScan.declarations(src);
        final Set<String> callers = new LinkedHashSet<>();
        for (final Integer at : SourceScan.invocationsOf(src, decls, NOTE)) {
            callers.add(SourceScan.enclosingMethod(src, decls, at.intValue()));
        }
        assertEquals("the park counter is fed from " + callers
                + "; it must be fed from exactly the "
                + "two G1 park gates, because a third door is a third opinion about when a "
                + "conversation has stopped receiving", 2, callers.size());
        assertTrue(CONTROL_GATE + " must be one of them (it is the half added later; " + callers
                + ")", callers.contains(CONTROL_GATE));
        assertTrue(APPLICATION_GATE + " must be one of them (it is the original; " + callers
                + ")", callers.contains(APPLICATION_GATE));
    }

    /**
     * The control plane re-reads the record after killing the heal, because the kill may decline;
     * processing inline while still inside the mask hands the engine a message it buffers again.
     */
    @Test public void theControlPlaneChecksThatTheKillActuallyLeftTheMask() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
        final String body = SourceScan.bodyOf(src, CONTROL_GATE);
        // Anchored on the bound: the control gate has an earlier, unrelated killSelfHeal.
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
     * The read-back can fail: some G1 states have no {@code SelfHealKilled} edge. If none lacked
     * one, the check would be decoration.
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

    /** {@code EraAdvancementRequested(7)} buffers inbound and the kill can release it. */
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
     * The two era escalations write a buffering state as the last thing that happens to the
     * conversation, so each must say that receiving has stopped.
     */
    @Test public void bothEscalationsIntoTheMaskSayThatReceivingHasStopped() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
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
