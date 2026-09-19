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

import org.junit.Test;

/**
 * The downgrade lifecycle as reachability claims the transport branches on.
 * {@code MlsHealthStatesTest} checks the table's transcription; this checks that the shapes the
 * host relies on are in it, in particular the revive dispatch, which chooses its shape by asking
 * the table. See docs/mls/downgrade.md.
 */
public class MlsDowngradeLifecycleTest {

    /** {@code RequestedDowngradeToEndMls} is reachable from a healthy conversation. */
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
     * The failure arm the host takes once send retries are exhausted, and not on the first refusal.
     */
    @Test
    public void aFailedDowngradeReachesTheWedgeFromBothInFlightStates() {
        assertEquals("EndMlsFailedToSendOutRetryExceeded", MlsHealthStates.edge(
                MlsHealthStates.ENDMLSREQUESTED, MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertEquals("EndMlsFailedToSendOutRetryExceeded", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGENDMLS, MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    /**
     * A remote end_mls lands on {@code EndMlsAppliedByRemoteClient}, but not from every state, so
     * the host tries the clean landing and falls back to the wedge.
     */
    @Test
    public void aRemoteDowngradeLandsOnDoneEndMlsFromSomeStatesAndNotOthers() {
        assertEquals("EndMlsAppliedByRemoteClient", MlsHealthStates.edge(
                MlsHealthStates.HEALTHY, MlsHealthStates.DONEENDMLS));
        assertEquals("EndMlsAppliedByRemoteClient", MlsHealthStates.edge(
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED, MlsHealthStates.DONEENDMLS));
        // An end_mls arriving mid-epoch-advance is the wedged-in-downgrade case, with no edge by
        // design.
        assertNull("OngoingEpochAdvancement -> DoneEndMls must stay ILLEGAL; the fallback to "
                + "CannotHealDuringEndMls is what makes that safe",
                MlsHealthStates.edge(MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.DONEENDMLS));
        assertEquals("EndMlsOnServerUnstableEndMlsLocally", MlsHealthStates.edge(
                MlsHealthStates.ONGOINGEPOCHADVANCEMENT, MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    /**
     * Once wedged, neither a re-downgrade nor a Phoenix run is possible, which is why the wedge
     * needs its own way out.
     */
    @Test
    public void theWedgeCannotReDowngradeOrStartPhoenix() {
        assertNull("15 -> 8 must not exist", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS, MlsHealthStates.ENDMLSREQUESTED));
        assertNull("15 -> 17 must not exist", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS, MlsHealthStates.PHOENIXMODEREQUESTED));
    }

    /** It reports both predicates true, which trips every hard stop at once. */
    @Test
    public void theWedgeIsBothHasEndMlsAndDowngraded() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.CANNOTHEALDURINGENDMLS));
    }

    /**
     * {@code DoneEndMls(10)} can revive in place; {@code CannotHealDuringEndMls(15)} has no edge to
     * {@code OngoingReviveMls} and revives only by a new era. The dispatch asks the table rather
     * than hardcoding state numbers.
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
        // New era: available from both.
        assertEquals("StartedEraAdvancementForRevival", MlsHealthStates.edge(
                MlsHealthStates.CANNOTHEALDURINGENDMLS,
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
        assertEquals("StartedEraAdvancementForRevival", MlsHealthStates.edge(
                MlsHealthStates.DONEENDMLS, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE));
    }

    /** Both revive shapes have exactly their table outcomes. */
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
     * {@code StartedEraAdvancementForRevival} reaches 12 from twelve states, so a blanket
     * era-advance refusal would strand all of them.
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

    /** {@code StartedPhoenixMode} (17 → 16) is the only inbound edge of 16. */
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
     * Phoenix can succeed into {@code Healthy}: the in-flight state is also the landing pad for an
     * era advance that ends healthy, so Phoenix does not always end downgraded.
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
     * {@code PhoenixModeRequested(17)} reports healthy coarsely while downgraded finely, so the
     * fast re-upgrade tests {@code status == HEALTHY} exactly rather than {@code !isDowngraded}.
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

    /**
     * The two era-advance modes pair with the two states that use them, and neither is the ordinary
     * recovery mode; a recovery path passing anything but NORMAL would lose or invent a downgrade.
     */
    @Test
    public void theTwoNonNormalModesPairWithTheirStates() {
        assertTrue(MlsAdvanceEraKind.REVIVAL.mayRemoveEndMls());
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.installsEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.installsEndMls());
        // Phoenix's reason is expected (we chose it), so it does not stamp the re-upgrade timestamp
        // its own success would have to clear.
        assertTrue(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE.expected);
        assertTrue(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE
                .zinniaAlreadyEnded);
    }

    /**
     * A downgrade delivered twice is a no-op at the state machine: {@code cur == to} is answered
     * before the table is consulted.
     */
    @Test
    public void aRepeatedDowngradeIsANoOpAtTheMachine() {
        assertEquals(MlsHealthMachine.Outcome.NO_OP, MlsHealthMachine.decide(
                MlsHealthStates.DONEENDMLS, MlsHealthStates.DONEENDMLS, false).outcome);
        assertNull(MlsHealthMachine.decide(MlsHealthStates.DONEENDMLS,
                MlsHealthStates.DONEENDMLS, false).edge);
    }

    /** The ladder answers it as a no-op rather than an error. */
    @Test
    public void aRepeatedDowngradeIsANoOpAtTheLadder() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.DONEENDMLS, null));
        assertNotNull(MlsDowngradeLadder.refusalLine(
                MlsDowngradeLadder.Verdict.ALREADY_DONE, "g"));
    }
}
