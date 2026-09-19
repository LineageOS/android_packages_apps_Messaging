/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

/**
 * The downgrade entry ladder and its order: a ladder with every guard present but evaluated in the
 * wrong sequence reports the wrong thing on states that trip two guards, such as a downgraded group
 * with a pending operation. See docs/mls/downgrade.md.
 */
public class MlsDowngradeLadderTest {

    private static final Moment M = new Moment(1, 1);

    private static MlsPendingOperation op(final MlsPendingOperation.Kind k) {
        return MlsPendingOperation.start(k, 1_000L, M, "");
    }

    @Test
    public void guard1_firesOnEveryHasEndMlsState() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthPredicates.hasEndMls(s)) continue;
            assertEquals("state " + s + " must report ALREADY_DONE",
                    MlsDowngradeLadder.Verdict.ALREADY_DONE,
                    MlsDowngradeLadder.evaluate(s, null));
        }
    }

    @Test
    public void guard2_firesOnAPendingEndMlsOrPhoenixOperation() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY,
                        op(MlsPendingOperation.Kind.END_MLS)));
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY,
                        op(MlsPendingOperation.Kind.PHOENIX_MODE)));
    }

    /**
     * Only a pending downgrade counts: a subject commit or epoch advance in flight is ordinary
     * work, and refusing on it would block the downgrade while anything is pending.
     */
    @Test
    public void guard2_ignoresEveryOtherPendingKind() {
        for (final MlsPendingOperation.Kind k : MlsPendingOperation.Kind.values()) {
            if (k == MlsPendingOperation.Kind.END_MLS
                    || k == MlsPendingOperation.Kind.PHOENIX_MODE) {
                continue;
            }
            assertEquals(k + " must not block a downgrade",
                    MlsDowngradeLadder.Verdict.PROCEED,
                    MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY, op(k)));
        }
    }

    @Test
    public void guard3_firesOnPhoenixRequestedAsWellAsOngoing() {
        // 16 is also has_end_mls, so guard 1 catches it first; 17 is the state that reaches guard
        // 3, which is why the predicate covers the requested state.
        assertEquals(MlsDowngradeLadder.Verdict.PHOENIX_ONGOING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.PHOENIXMODEREQUESTED, null));
    }

    @Test
    public void aHealthyGroupWithNothingPendingProceeds() {
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY, null));
        assertTrue(MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY, null) == null
                || !MlsDowngradeLadder.evaluate(MlsHealthStates.HEALTHY, null).refuses());
    }

    /**
     * A group that is both {@code DoneEndMls} and has a pending op reports "MLS is already done",
     * not "already pending".
     */
    @Test
    public void guard1_beatsGuard2() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.DONEENDMLS,
                        op(MlsPendingOperation.Kind.END_MLS)));
    }

    /**
     * {@code OngoingPhoenixMode(16)} is in both {@code has_end_mls} and {@code is_phoenix_ongoing};
     * guard 1 wins.
     */
    @Test
    public void guard1_beatsGuard3() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGPHOENIXMODE));
        assertTrue(MlsHealthPredicates.isPhoenixOngoing(MlsHealthStates.ONGOINGPHOENIXMODE));
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGPHOENIXMODE, null));
    }

    /** {@code PhoenixModeRequested(17)} with a pending phoenix op trips 2 and 3; guard 2 wins. */
    @Test
    public void guard2_beatsGuard3() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.PHOENIXMODEREQUESTED,
                        op(MlsPendingOperation.Kind.PHOENIX_MODE)));
    }

    /**
     * A requested or in-flight downgrade does not trip guard 1; it falls through to guard 2, the
     * "one at a time" guard.
     */
    @Test
    public void requestedAndOngoingEndMlsFallThroughToGuard2() {
        // With nothing pending they proceed: has_end_mls is false for 8 and 9.
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ENDMLSREQUESTED, null));
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, null));
        // With the operation registered, guard 2 catches them.
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS,
                        op(MlsPendingOperation.Kind.END_MLS)));
    }

    /**
     * {@code is_downgraded} is not a fourth entry guard: it is true from {@code EndMlsRequested(8)}
     * onward, so a downgrade would refuse itself.
     */
    @Test
    public void isDowngradedIsNotAFourthGuard() {
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.ENDMLSREQUESTED));
        assertEquals("EndMlsRequested is downgraded but must still PROCEED at the entry ladder",
                MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ENDMLSREQUESTED, null));
    }

    @Test
    public void guard1LineIsGenuinesThirtySevenBytes() {
        assertEquals("MLS is already done. Returning no-op.", MlsDowngradeLadder.ALREADY_DONE_LINE);
        assertEquals("some peers' own line is 0x25 = 37 bytes",
                37, MlsDowngradeLadder.ALREADY_DONE_LINE.length());
    }

    @Test
    public void guard3LineMatchesGenuinesFormat() {
        assertEquals("Phoenix mode is ongoing for group: abc. No need to start a new downgrade.",
                MlsDowngradeLadder.phoenixOngoingLine("abc"));
    }

    @Test
    public void guard2LineMatchesGenuinesFormat() {
        assertEquals("End-mls commit or phoenix downgrade already pending for group: abc",
                MlsDowngradeLadder.alreadyPendingLine("abc"));
    }

    @Test
    public void refusalLineMatchesTheVerdict() {
        assertEquals(MlsDowngradeLadder.ALREADY_DONE_LINE,
                MlsDowngradeLadder.refusalLine(MlsDowngradeLadder.Verdict.ALREADY_DONE, "g"));
        assertEquals(MlsDowngradeLadder.alreadyPendingLine("g"),
                MlsDowngradeLadder.refusalLine(MlsDowngradeLadder.Verdict.ALREADY_PENDING, "g"));
        assertEquals(MlsDowngradeLadder.phoenixOngoingLine("g"),
                MlsDowngradeLadder.refusalLine(MlsDowngradeLadder.Verdict.PHOENIX_ONGOING, "g"));
        assertNull(MlsDowngradeLadder.refusalLine(MlsDowngradeLadder.Verdict.PROCEED, "g"));
    }

    @Test
    public void onlyProceedDoesNotRefuse() {
        for (final MlsDowngradeLadder.Verdict v : MlsDowngradeLadder.Verdict.values()) {
            assertEquals(v != MlsDowngradeLadder.Verdict.PROCEED, v.refuses());
        }
    }

    private static MlsPendingOperation opWithCtx(final MlsPendingOperation.Kind k,
            final String ctx) {
        return MlsPendingOperation.start(k, 1_000L, M, ctx);
    }

    /**
     * An operation retrying into its own slot is not refused by it: a failed end_mls keeps its slot
     * for another attempt, and refusing that attempt would make the conversation permanently
     * un-downgradable.
     */
    @Test
    public void sameOperationMayResumeItsOwnSlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED, MlsDowngradeLadder.evaluate(
                MlsHealthStates.ONGOINGENDMLS, held, "UNRECOVERABLE_SERVER_FAILURE"));
    }

    /** A different downgrade is a second operation and still waits. */
    @Test
    public void aDifferentReasonMayNotAdoptAnotherOperationsSlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING, MlsDowngradeLadder.evaluate(
                MlsHealthStates.ONGOINGENDMLS, held, "NON_MLS_CLIENT_ADDED"));
    }

    /** A caller that names no context cannot resume anything; no owner is guessed. */
    @Test
    public void anUnnamedCallerNeverResumesASlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, held, null));
        // The overload without a context behaves the same.
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, held));
    }

    /** A phoenix never adopts an end_mls slot, even with a matching context string. */
    @Test
    public void aPhoenixMayNotResumeAnEndMlsSlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.PHOENIX_MODE, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING, MlsDowngradeLadder.evaluate(
                MlsHealthStates.ONGOINGENDMLS, held, "UNRECOVERABLE_SERVER_FAILURE"));
    }

    /** Guard 1 still wins: an already-downgraded group is ALREADY_DONE regardless of any slot. */
    @Test
    public void guardOneStillOutranksAResume() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE, MlsDowngradeLadder.evaluate(
                MlsHealthStates.DONEENDMLS, held, "UNRECOVERABLE_SERVER_FAILURE"));
    }
}
