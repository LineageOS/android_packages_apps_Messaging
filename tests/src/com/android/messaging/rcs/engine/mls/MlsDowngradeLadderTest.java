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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

/**
 * The downgrade entry ladder — and above all its ORDER (§9.7c, invariant 101).
 *
 * <p>The order tests are the ones that matter. A ladder whose three guards are all present but
 * evaluated in the wrong sequence passes every single-guard test and still reports the wrong thing on
 * the states that trip two — and those states are not exotic: a downgraded group with a pending
 * operation is the ordinary result of a downgrade that has already run once.
 */
public class MlsDowngradeLadderTest {

    private static final Moment M = new Moment(1, 1);

    private static MlsPendingOperation op(final MlsPendingOperation.Kind k) {
        return MlsPendingOperation.start(k, 1_000L, M, "");
    }

    // ---- each guard on its own ----------------------------------------------------------------

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
     * And on nothing else. A subject commit or an epoch advance in flight is ordinary work — it does
     * not mean a downgrade is already under way, and refusing on it would make the downgrade
     * unreachable for as long as any operation at all was pending.
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
        // 16 is also has_end_mls, so it is caught by guard 1 first — 17 is the state that reaches
        // guard 3, and it is the reason the predicate covers REQUESTED at all.
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

    // ---- THE ORDER (invariant 101) ------------------------------------------------------------

    /**
     * Invariant 101's own example, verbatim: <i>a group that is both {@code DoneEndMls} and has a
     * pending op must produce "MLS is already done", not "already pending"</i>.
     */
    @Test
    public void guard1_beatsGuard2() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.DONEENDMLS,
                        op(MlsPendingOperation.Kind.END_MLS)));
    }

    /**
     * {@code OngoingPhoenixMode(16)} trips guard 1 AND guard 3 — it is in both
     * {@code has_end_mls} and {@code is_phoenix_ongoing}. Guard 1 wins.
     */
    @Test
    public void guard1_beatsGuard3() {
        assertTrue(MlsHealthPredicates.hasEndMls(MlsHealthStates.ONGOINGPHOENIXMODE));
        assertTrue(MlsHealthPredicates.isPhoenixOngoing(MlsHealthStates.ONGOINGPHOENIXMODE));
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_DONE,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGPHOENIXMODE, null));
    }

    /** {@code PhoenixModeRequested(17)} with a pending phoenix op trips 2 and 3. Guard 2 wins. */
    @Test
    public void guard2_beatsGuard3() {
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.PHOENIXMODEREQUESTED,
                        op(MlsPendingOperation.Kind.PHOENIX_MODE)));
    }

    /**
     * The two states §9.7c calls out as NOT tripping guard 1: a requested or in-flight downgrade
     * falls through to guard 2, which is the guard that is actually about "one at a time".
     */
    @Test
    public void requestedAndOngoingEndMlsFallThroughToGuard2() {
        // With nothing pending they proceed — has_end_mls is false for 8 and 9.
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ENDMLSREQUESTED, null));
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, null));
        // With the operation actually registered, guard 2 catches them.
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS,
                        op(MlsPendingOperation.Kind.END_MLS)));
    }

    /**
     * <b>The negative that §9.7c flags in a warning box.</b> {@code is_downgraded} must NOT be a
     * fourth entry guard: a downgrade IS a new pending operation, and {@code is_downgraded} is true
     * from {@code EndMlsRequested(8)} onward — so folding the pending-registry stop in here would
     * make a downgrade refuse itself.
     */
    @Test
    public void isDowngradedIsNotAFourthGuard() {
        assertTrue(MlsHealthPredicates.isDowngraded(MlsHealthStates.ENDMLSREQUESTED));
        assertEquals("EndMlsRequested is downgraded but must still PROCEED at the entry ladder",
                MlsDowngradeLadder.Verdict.PROCEED,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ENDMLSREQUESTED, null));
    }

    // ---- the verbatim texts -------------------------------------------------------------------

    @Test
    public void guard1LineIsGenuinesThirtySevenBytes() {
        assertEquals("MLS is already done. Returning no-op.", MlsDowngradeLadder.ALREADY_DONE_LINE);
        assertEquals("Google Messages' own strlen immediate is 0x25 = 37",
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

    // ============== resuming your own operation ==============

    private static MlsPendingOperation opWithCtx(final MlsPendingOperation.Kind k, final String ctx) {
        return MlsPendingOperation.start(k, 1_000L, M, ctx);
    }

    /**
     * An operation retrying into its OWN slot must not be refused by it.
     *
     * <p>A failed end_mls keeps its slot "for another attempt", and guard 2 then refused that very
     * attempt — reporting a NO-OP, not a failure, so it read like correctness. One server refusal
     * made a conversation permanently un-downgradable and the user could press the button forever.
     */
    @Test
    public void sameOperationMayResumeItsOwnSlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.PROCEED, MlsDowngradeLadder.evaluate(
                MlsHealthStates.ONGOINGENDMLS, held, "UNRECOVERABLE_SERVER_FAILURE"));
    }

    /** A DIFFERENT downgrade is a second operation and still waits — the guard's actual job. */
    @Test
    public void aDifferentReasonMayNotAdoptAnotherOperationsSlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING, MlsDowngradeLadder.evaluate(
                MlsHealthStates.ONGOINGENDMLS, held, "NON_MLS_CLIENT_ADDED"));
    }

    /** A caller that names no context cannot resume anything — we do not guess an owner. */
    @Test
    public void anUnnamedCallerNeverResumesASlot() {
        final MlsPendingOperation held =
                opWithCtx(MlsPendingOperation.Kind.END_MLS, "UNRECOVERABLE_SERVER_FAILURE");
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, held, null));
        // And the back-compat overload behaves the same, so no existing caller silently changed.
        assertEquals(MlsDowngradeLadder.Verdict.ALREADY_PENDING,
                MlsDowngradeLadder.evaluate(MlsHealthStates.ONGOINGENDMLS, held));
    }

    /** A phoenix must never adopt an end_mls slot, even with a matching context string. */
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
