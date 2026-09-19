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

/**
 * The downgrade ENTRY guard ladder — three guards in a <b>fixed order</b>. Rework item {@code 11.1b},
 * §9.7c, invariant 101.
 *
 * <h2>The order is the specification, not an implementation detail</h2>
 *
 * <p>Invariant 101 states the consequence in one line: <i>a group that is both {@code DoneEndMls}
 * <b>and</b> has a pending op must produce "MLS is already done", not "already pending"</i>. Both
 * guards would fire; only the first one's message is correct, because it is the one that says the
 * work is unnecessary rather than merely deferred. A caller that reads "already pending" schedules a
 * retry; a caller that reads "already done" stops.
 *
 * <pre>
 * GUARD 1   has_end_mls(status)        -&gt; {10,11,12,15,16}   "MLS is already done. Returning no-op."
 * GUARD 2   a pending end-mls / phoenix op                    "End-mls commit or phoenix downgrade already pending for group: {:?}"
 * GUARD 3   is_phoenix_ongoing(status) -&gt; {16,17}            "Phoenix mode is ongoing for group: {:?}. No need to start a new downgrade."
 * </pre>
 *
 * <p>Google Messages' guard-1 and guard-3 literals are cross-checked by their own {@code strlen} immediates
 * ({@code mov w1,#0x25} = 37 and {@code mov w1,#0x4a} = 74), so the two texts below are pinned to the
 * byte rather than transcribed by eye.
 *
 * <h2>What must NOT be added as a fourth guard</h2>
 *
 * <p>⚠ <b>{@code "Group {:?} is downgraded. Not starting new pending operation."} is NOT a
 * downgrade-entry guard.</b> It lives in the pending-operation registry and guards STARTING ANY NEW
 * PENDING OPERATION (§9.7f) — one of three such hard stops, alongside "Not refreshing" and "Not
 * self-healing". Folding it in here would make a downgrade refuse itself: a downgrade IS a new
 * pending operation, and {@code is_downgraded} is true from {@code EndMlsRequested(8)} onward.
 *
 * <h2>Guard 2 and the honest limit of our fidelity</h2>
 *
 * <p>Google Messages' guard 2 refuses on {@code kind == 1} unconditionally and on {@code kind == 5} when a
 * further predicate on the operation holds. Those are {@code ZinniaMlsGroupPendingOperation} category
 * discriminants and <b>their names are not recoverable</b> — NEEDS-CAPTURE §22.1-50. The plan's
 * decision was to <b>model the kinds abstractly</b>, and that is what this does: our
 * {@link MlsPendingOperation.Kind#END_MLS} stands in for Google Messages' kind 1 (the explicit end-mls
 * operation) and {@link MlsPendingOperation.Kind#PHOENIX_MODE} for its kind 5 (an era-advance
 * operation whose purpose is Phoenix).
 *
 * <p><b>This is a semantic match, not a byte-faithful one, and the difference is worth stating.</b>
 * The mapping is inferred from the guard's own message — it names exactly two things, "end-mls
 * commit" and "phoenix downgrade" — so if a capture ever pins the discriminants and they disagree,
 * what changes is this mapping and nothing else. The behaviour under test does not depend on the
 * numbers.
 */
public final class MlsDowngradeLadder {

    private MlsDowngradeLadder() {}

    /** Which rung refused, or {@link #PROCEED}. */
    public enum Verdict {
        /** No guard fired. Build the commit. */
        PROCEED,
        /** Guard 1: the status already reports end-MLS. A no-op, not a failure. */
        ALREADY_DONE,
        /** Guard 2: an end-mls or phoenix operation is already in flight. */
        ALREADY_PENDING,
        /** Guard 3: Phoenix is ongoing or requested; it will carry the downgrade itself. */
        PHOENIX_ONGOING;

        /** Whether this verdict stops the downgrade. */
        public boolean refuses() { return this != PROCEED; }
    }

    /** Guard 1's verbatim text — 37 bytes, cross-checked against Google Messages' own {@code strlen}. */
    public static final String ALREADY_DONE_LINE = "MLS is already done. Returning no-op.";

    /** Guard 2's verbatim format. {@code {:?}} takes the group id. */
    public static String alreadyPendingLine(final String groupId) {
        return "End-mls commit or phoenix downgrade already pending for group: " + groupId;
    }

    /** Guard 3's verbatim format — 74 bytes at the format level. {@code {:?}} takes the group id. */
    public static String phoenixOngoingLine(final String groupId) {
        return "Phoenix mode is ongoing for group: " + groupId
                + ". No need to start a new downgrade.";
    }

    /**
     * Run the ladder. Pure: it decides, it does not act and it does not log.
     *
     * <p>The evaluation order below is load-bearing and is asserted by
     * {@code MlsDowngradeLadderTest}: a state that trips more than one guard must report the
     * EARLIEST.
     *
     * @param status  the conversation's persisted health status (a WIRE number)
     * @param pending the single in-flight operation, or {@code null}
     */
    /**
     * Is the pending operation the SAME one the caller is now retrying?
     *
     * <p>Matched on context rather than on identity because that is the only thing the caller and the
     * slot reliably share across a process restart: the slot is persisted, the caller is not.
     *
     * <p>A null request context means the caller did not say, and we do NOT guess — an unmatched
     * retry stays refused, which is the safe direction. Widening this to "any END_MLS may resume any
     * END_MLS slot" would let an unrelated downgrade adopt another's half-finished operation.
     */
    static boolean isSameOperationRetrying(final MlsPendingOperation pending,
            final String requestContext) {
        if (pending == null || requestContext == null || pending.context == null) return false;
        // Only the operation's OWN kind can resume it; a phoenix must not adopt an end_mls slot.
        if (pending.kind != MlsPendingOperation.Kind.END_MLS) return false;
        if (!requestContext.equals(pending.context)) return false;
        // Do not let a wedged operation retry without bound. The cap is the same one the retry
        // policy applies per item, so this cannot outlive the accounting that governs everything else.
        return !MlsRetryPolicy.attemptsExhausted(pending.attemptCount);
    }

    /** Back-compat overload — a caller that names no context can never resume a slot. */
    public static Verdict evaluate(final int status, final MlsPendingOperation pending) {
        return evaluate(status, pending, /*requestContext=*/ null);
    }

    public static Verdict evaluate(final int status, final MlsPendingOperation pending,
            final String requestContext) {
        // GUARD 1 — the status test, NOT the extension test. EndMlsRequested(8) and OngoingEndMls(9)
        // deliberately do NOT trip this: a downgrade that has been requested, or whose commit is in
        // flight, reports has_end_mls == false and falls through to guard 2, which is the guard that
        // is actually about "one at a time".
        if (MlsHealthPredicates.hasEndMls(status)) {
            return Verdict.ALREADY_DONE;
        }
        // GUARD 2 — see the class doc on the abstract kind mapping.
        if (pending != null
                && (pending.kind == MlsPendingOperation.Kind.END_MLS
                    || pending.kind == MlsPendingOperation.Kind.PHOENIX_MODE)) {
            // …EXCEPT when the pending operation IS this one, retrying.
            //
            // The slot exists to stop a SECOND operation, not to stop the FIRST one from finishing.
            // A failed end_mls deliberately KEEPS its slot "for another attempt" — and this guard
            // then refused that attempt, reporting a NO-OP rather than a failure. So one server
            // refusal made a conversation permanently un-downgradable, silently, with the user free
            // to press the button forever and nothing happening.
            //
            // Device-observed 2026-08-04: an end_mls refused with "Era changed from 6 to 5" kept
            // pending{END_MLS from=EXPLICIT_API attempt=2 ctx=UNRECOVERABLE_SERVER_FAILURE}, and
            // every later attempt died at this rung.
            //
            // Same kind AND same context means the caller is resuming its own operation, so let it
            // through. A DIFFERENT context is a genuinely different downgrade and still waits.
            if (!isSameOperationRetrying(pending, requestContext)) {
                return Verdict.ALREADY_PENDING;
            }
        }
        // GUARD 3 — covers REQUESTED as well as ONGOING (16 and 17).
        if (MlsHealthPredicates.isPhoenixOngoing(status)) {
            return Verdict.PHOENIX_ONGOING;
        }
        return Verdict.PROCEED;
    }

    /** The verbatim refusal line for a verdict, or {@code null} for {@link Verdict#PROCEED}. */
    public static String refusalLine(final Verdict v, final String groupId) {
        switch (v) {
            case ALREADY_DONE:    return ALREADY_DONE_LINE;
            case ALREADY_PENDING: return alreadyPendingLine(groupId);
            case PHOENIX_ONGOING: return phoenixOngoingLine(groupId);
            default:              return null;
        }
    }
}
