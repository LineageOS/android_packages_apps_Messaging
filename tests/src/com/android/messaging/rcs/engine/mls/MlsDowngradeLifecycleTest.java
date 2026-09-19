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

import org.junit.Test;

/**
 * The §9.7 lifecycle as a set of REACHABILITY claims — the ones {@code MlsProviderTransport}'s
 * Stage M code branches on.
 *
 * <h2>Why this test exists separately from the table test</h2>
 *
 * <p>{@code MlsHealthStatesTest} checks the table is transcribed correctly. This checks that the
 * SHAPES Stage M relies on are actually in it. Those are different questions, and the gap between
 * them is exactly what {@code MlsRecoveryLadderTest} was written for: <b>"the pair is legal" was true
 * the entire time recovery was broken</b>, because nothing took the pair.
 *
 * <p>So every claim below is one the host code would silently get wrong if the table disagreed —
 * most sharply the revive dispatch, which chooses between two shapes by ASKING the table, and would
 * strand every wedged conversation if the wedge's edges were not what §9.7i says.
 */
public class MlsDowngradeLifecycleTest {

    // ---- the downgrade path ------------------------------------------------------------------

    /** {@code RequestedDowngradeToEndMls} must be reachable from a healthy conversation. */
    @Test
    public void aHealthyGroupCanBeDowngraded() {
        assertEquals("RequestedDowngradeToEndMls",
                MlsHealthStates.edge(MlsHealthStates.HEALTHY, MlsHealthStates.ENDMLSREQUESTED));
        assertEquals("StartedDowngradeToEndMls", MlsHealthStates.edge(
                MlsHealthStates.ENDMLSREQUESTED, MlsHealthStates.ONGOINGENDMLS));
        assertEquals("EndMlsSucceeded", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGENDMLS, MlsHealthStates.DONEENDMLS));
    }

    /**
     * The failure arm the host takes once send retries are exhausted, and the one that must NOT be
     * taken on the first refusal.
     */
    @Test
    public void aFailedDowngradeReachesTheWedgeFromBothInFlightStates() {
        assertEquals("EndMlsFailedToSendOutRetryExceeded", MlsHealthStates.edge(
                MlsHealthStates.ENDMLSREQUESTED, MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertEquals("EndMlsFailedToSendOutRetryExceeded", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGENDMLS, MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    /**
     * §9.7h paths A and B land on {@code EndMlsAppliedByRemoteClient} — but NOT from every state,
     * which is why the host tries the clean landing and falls back to the wedge.
     */
    @Test
    public void aRemoteDowngradeLandsOnDoneEndMlsFromSomeStatesAndNotOthers() {
        assertEquals("EndMlsAppliedByRemoteClient", MlsHealthStates.edge(
                MlsHealthStates.HEALTHY, MlsHealthStates.DONEENDMLS));
        assertEquals("EndMlsAppliedByRemoteClient", MlsHealthStates.edge(
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED, MlsHealthStates.DONEENDMLS));
        // ...but an end_mls arriving mid-epoch-advance is the WEDGED-IN-DOWNGRADE case, and the
        // table deliberately has no edge for it. The host must fall back rather than force it.
        assertNull("OngoingEpochAdvancement -> DoneEndMls must stay ILLEGAL; the fallback to "
                + "CannotHealDuringEndMls is what makes that safe",
                MlsHealthStates.edge(MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.DONEENDMLS));
        assertEquals("EndMlsOnServerUnstableEndMlsLocally", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGEPOCHADVANCEMENT, MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    // ---- the wedge (§9.7j) --------------------------------------------------------------------

    /**
     * <b>Once wedged, you can neither re-downgrade nor start a Phoenix run.</b> Both absences are
     * deliberate, and together they are why the wedge needs its own way out.
     */
    @Test
    public void theWedgeCannotReDowngradeOrStartPhoenix() {
        assertNull("15 -> 8 must not exist", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS, MlsHealthStates.ENDMLSREQUESTED));
        assertNull("15 -> 17 must not exist", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS, MlsHealthStates.PHOENIXMODEREQUESTED));
    }

    /** And it reports BOTH predicates true, which is what trips every hard stop at once. */
    @Test
    public void theWedgeIsBothHasEndMlsAndDowngraded() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    // ---- the revive dispatch (§9.7i) — the claim the host branches on ------------------------

    /**
     * <b>The reachability fact the whole revive dispatch rests on.</b>
     *
     * <p>{@code DoneEndMls(10)} can revive IN PLACE. {@code CannotHealDuringEndMls(15)} <b>cannot</b>
     * — it has no edge to {@code OngoingReviveMls} at all — and its only way back is the NEW ERA
     * shape. A revive that only knew the in-place shape would strand every wedged conversation, and
     * a dispatch that hardcoded state numbers instead of asking the table would drift from it.
     */
    @Test
    public void theWedgeRevivesOnlyByNewEra() {
        // In place: available from DoneEndMls, absent from the wedge.
        assertEquals("RevivingFromEndMlsWithEpochAdvancement", MlsHealthStates.edge(
                MlsHealthStates.DONEENDMLS, MlsHealthStates.ONGOINGREVIVEMLS));
        assertFalse("the wedge has NO in-place revive edge — this is the whole reason the new-era "
                + "shape has to exist",
                MlsHealthStates.isLegal(MlsHealthStates.CANNOTHEALDURINGENDMLS,
                        MlsHealthStates.ONGOINGREVIVEMLS));
        // New era: available from BOTH.
        assertEquals("StartedEraAdvancementForRevival", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS,
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
        assertEquals("StartedEraAdvancementForRevival", MlsHealthStates.edge(
                MlsHealthStates.DONEENDMLS, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
    }

    /** Both revive shapes have exactly the outcomes §9.7i gives them — no third, no partial. */
    @Test
    public void bothReviveShapesHaveTheirTwoOutcomes() {
        // In place: 11 -> {13 Healthy, 2 OngoingEpochAdvancement, 7 EraAdvancementRequested}.
        assertEquals("RevivedFromEndMls", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGREVIVEMLS, MlsHealthStates.HEALTHY));
        assertEquals("ReviveRejectedAndServerAlreadyRevived", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGREVIVEMLS, MlsHealthStates.ONGOINGEPOCHADVANCEMENT));
        // New era: 12 -> {13 Healthy, 10 DoneEndMls}. Either the new era is encrypted, or the
        // conversation is still downgraded and nothing was lost.
        assertEquals("RevivedInNewEra", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE, MlsHealthStates.HEALTHY));
        assertEquals("RevivalFailed", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE, MlsHealthStates.DONEENDMLS));
    }

    /**
     * {@code StartedEraAdvancementForRevival} reaches 12 from TWELVE states — the breadth that made
     * the old blanket era-advance refusal so costly, since it made every one of them unreachable.
     */
    @Test
    public void twelveStatesCanStartAnEraAdvanceForRevival() {
        int sources = 0;
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (MlsHealthStates.isLegal(s, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE)) {
                sources++;
            }
        }
        assertEquals(12, sources);
    }

    // ---- Phoenix (§9.7g) ----------------------------------------------------------------------

    /** {@code StartedPhoenixMode} (17 → 16) is the ONLY inbound edge of 16. */
    @Test
    public void ongoingPhoenixModeHasExactlyOneInboundEdge() {
        int inbound = 0;
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (MlsHealthStates.isLegal(s, MlsHealthStates.ONGOINGPHOENIXMODE)) inbound++;
        }
        assertEquals(1, inbound);
        assertEquals("StartedPhoenixMode", MlsHealthStates.edge(
                MlsHealthStates.PHOENIXMODEREQUESTED, MlsHealthStates.ONGOINGPHOENIXMODE));
    }

    /**
     * <b>Phoenix can succeed into {@code Healthy}.</b> Not a mistake and not a state to design away:
     * mode-2 era advance is "era+1 with end_mls", but the same in-flight state is also the landing
     * pad for an era advance that ends up healthy. <b>Do not assume Phoenix always ends
     * downgraded.</b>
     */
    @Test
    public void phoenixCanEndEitherDowngradedOrHealthy() {
        assertEquals("PhoenixModeSucceeded", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGPHOENIXMODE, MlsHealthStates.DONEENDMLS));
        assertEquals("RevivedInNewEra", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGPHOENIXMODE, MlsHealthStates.HEALTHY));
        assertEquals("PhoenixModeFailedSoDowngradingLocally", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGPHOENIXMODE, MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertEquals("RestartingPhoenixMode", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGPHOENIXMODE, MlsHealthStates.PHOENIXMODEREQUESTED));
    }

    /**
     * The coarse-status split §9.7l loop 2 depends on: {@code PhoenixModeRequested(17)} reports
     * HEALTHY coarsely while being thoroughly downgraded finely.
     *
     * <p>That is why the fast re-upgrade tests {@code status == HEALTHY} exactly rather than
     * {@code !isDowngraded}: the loose test would let a requested Phoenix re-upgrade itself out of
     * its own downgrade before it had even started.
     */
    @Test
    public void phoenixRequestedIsDowngradedButNotHasEndMls() {
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.PHOENIXMODEREQUESTED));
        assertFalse("17 has not installed the extension yet — Phoenix installs it in the NEW era",
                MlsHealthPredicates.hasEndMls(MlsHealthStates.PHOENIXMODEREQUESTED));
        assertTrue(MlsHealthPredicates.isPhoenixOngoing(MlsHealthStates.PHOENIXMODEREQUESTED));
        assertFalse("...and it is NOT Healthy, so loop 2 must not fire for it",
                MlsHealthStates.PHOENIXMODEREQUESTED == MlsHealthStates.HEALTHY);
    }

    // ---- the mode byte pairing (invariant 103) -----------------------------------------------

    /**
     * The two era-advance modes pair with the two states that use them, and neither is the ordinary
     * recovery mode. If a recovery path ever passed anything but NORMAL, a downgrade would be lost
     * or invented.
     */
    @Test
    public void theTwoNonNormalModesPairWithTheirStates() {
        assertTrue(MlsAdvanceEraKind.REVIVAL.mayRemoveEndMls());
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.installsEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.installsEndMls());
        // And the reason each one carries is the one whose columns match: Phoenix's is EXPECTED
        // (we chose it, do not plan to come back), which is what stops it stamping the re-upgrade
        // timestamp its own success would then have to clear.
        assertTrue(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE.expected);
        assertTrue(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE
                .zinniaAlreadyEnded);
    }

    // ---- idempotence (invariant 109) ----------------------------------------------------------

    /**
     * Invariant 109's state-machine rung: a downgrade delivered twice is a no-op at the machine,
     * because {@code cur == to} is answered before the table is consulted.
     */
    @Test
    public void aRepeatedDowngradeIsANoOpAtTheMachine() {
        assertEquals(MlsHealthMachine.Outcome.NO_OP, MlsHealthMachine.decide(
                MlsHealthStates.DONEENDMLS, MlsHealthStates.DONEENDMLS, false).outcome);
        assertNull(MlsHealthMachine.decide(MlsHealthStates.DONEENDMLS,
                MlsHealthStates.DONEENDMLS, false).edge);
    }

    /** And the ladder's rung: guard 1 answers it as a no-op rather than an error. */
    @Test
    public void aRepeatedDowngradeIsANoOpAtTheLadder() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.DONEENDMLS, null));
        assertNotNull(MlsDowngradeLadder.refusalLine(
                MlsDowngradeLadder.Verdict.ALREADY_DONE, "g"));
    }
}
