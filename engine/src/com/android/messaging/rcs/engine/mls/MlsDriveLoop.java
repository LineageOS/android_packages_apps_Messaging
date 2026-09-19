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
 * The bounded fixed point — re-drive until the result says stop, or the cap says stop (rework item
 * 4.3, §10.5, invariant 55).
 *
 * <p>An MLS operation frequently produces more work: applying a commit unblocks buffered controls,
 * a key refresh unblocks a send, an era advance requires a re-join. Something has to keep going
 * until there is nothing left, and that something has to be guaranteed to stop.
 *
 * <h2>Invariant 55 — RESULT-driven, never STATE-driven</h2>
 *
 * <p>This is the whole design, and the reason the class exists rather than a {@code while} loop at
 * each call site. The loop terminates on what the last pass RETURNED, never on a predicate re-read
 * from the world. A state-predicate re-drive does not terminate the same way, because the predicate
 * is recomputed from a world other threads are still changing — so "has it converged?" can flip back
 * and forth indefinitely while every individual pass reports success.
 *
 * <p>We have that exact hazard in the tree today: {@code onControlRefused → reconcile → possible era
 * advance → new control send → possible refusal} is a state-predicate loop whose ONLY bound is a
 * 60-second in-memory debounce. That is a bound by wall clock, not by iteration — and it is lost
 * entirely on restart. {@code drainDeferredControl} has the same shape (it re-drives on "the epoch
 * advanced" and recurses through {@code applyInboundControl}); it is a different mechanism and must
 * not be grown into this one.
 *
 * <p>The API enforces the rule structurally: a {@link Pass} receives the previous action and returns
 * the next one. It is handed no way to ask the loop about the world, and the loop consults nothing
 * but {@link MlsResultStatus}.
 *
 * <h2>The cap is a bug detector, not a policy</h2>
 *
 * <p>{@link #DEFAULT_MAX_ITERATIONS} is 10, Google Messages' client default. Reaching it is not a busy
 * conversation — every real flow converges in two or three passes — it means a pass keeps reporting
 * "more work" without making progress. So hitting the cap logs at SEVERE, increments
 * {@link MlsMetrics#MAX_LOOP_REACHED}, and returns a terminal {@link MlsResultStatus#FAIL_NO_RETRY}
 * rather than looping or throwing.
 *
 * <p><b>That counter should be flat at zero forever.</b> Any increment is a bug, which is precisely
 * what makes it worth asserting in CI — it is a one-line check that replaces scraping logcat for the
 * absence of a symptom.
 *
 * <h2>The cap is not a bound on the thing that is scarce</h2>
 *
 * <p>It fired. Device-measured: ten passes over an unchanged {@code local era=1
 * epoch=1 · server era=1 epoch=7}, every pass reporting more work, none progressing. And a loop that
 * cannot converge <b>does not sit still</b> — every pass here is a {@code GetMlsGroupInfo}, so it
 * fetched once per pass until the server answered {@code grpcStatus=8 RESOURCE_EXHAUSTED} and the
 * self-heal that ran next could not fetch at all. Reaching the cap and exhausting the quota were the
 * same event: <b>the loop's failure mode was to destroy the resource its own recovery depends on</b>,
 * on precisely the conversation that most needed to heal.
 *
 * <p>Iterations are free; look-ups are not. So the loop now stops the moment a pass reports no
 * progress, by two rules that do not depend on each other:
 *
 * <ol>
 *   <li><b>DECLARED.</b> A pass returns {@link MlsHostAction.Redrive} other than
 *       {@link MlsHostAction.Redrive#IMMEDIATE} — "I diagnosed and changed nothing", or "I could not
 *       spend my look-up". The loop stops and PRESERVES the status, so the caller still sees
 *       {@code FAIL_RETRY}, still records the work as owed and still schedules it. This costs ONE
 *       pass, which is the honest floor: the state had to be read once to know.</li>
 *   <li><b>OBSERVED.</b> Two consecutive passes return an indistinguishable action — same arm, same
 *       status, same {@link MlsGroupSnapshot}. Nothing the loop can see has moved, so a third pass
 *       asks a question whose answer is already in hand. This is the backstop for producers that
 *       have not declared rule 1, and it costs the one wasted look-up rule 1 avoids.</li>
 * </ol>
 *
 * <p>Rule 2 is why {@link MlsMetrics#MAX_LOOP_REACHED} is counted on a no-progress stop and not only
 * at the cap. The counter's DOCUMENTED meaning is "a pass reported more work without making
 * progress", which is exactly what rule 2 detects — eight fetches earlier. Counting only at the cap
 * would have made the fix silence the alarm rather than the fault. Rule 1 is NOT counted: a producer
 * saying honestly that it has nothing more to do is the loop working, not a bug.
 *
 * <p>The number of look-ups a whole drive may spend is a separate bound at the call site, because
 * the loop cannot see a fetch — {@link MlsFetchBudget}.
 */
public final class MlsDriveLoop {

    /** Google Messages' client default. See the class doc on why reaching it is a bug, not a workload. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** One re-drive pass. Returns what happened; is told nothing about the loop's state. */
    public interface Pass {
        /**
         * @param previous the action the last pass returned, or {@code null} on the first pass
         * @return what this pass did — never {@code null}
         */
        MlsHostAction run(MlsHostAction previous);
    }

    private final int mMaxIterations;
    private final MlsTelemetry mTelemetry;

    public MlsDriveLoop(final MlsTelemetry telemetry) {
        this(telemetry, DEFAULT_MAX_ITERATIONS);
    }

    public MlsDriveLoop(final MlsTelemetry telemetry, final int maxIterations) {
        mTelemetry = telemetry == null ? MlsTelemetry.NONE : telemetry;
        // A cap below 1 would run zero passes and report FAIL_NO_RETRY on a conversation that was
        // never asked to do anything — a self-inflicted outage rather than a tight bound.
        mMaxIterations = Math.max(1, maxIterations);
    }

    /**
     * The outcome of a whole drive, as distinct from one pass.
     *
     * <p>Carries its own log line rather than emitting one. The engine module is Android-free by
     * construction — that is the entire argument for the state machine living here and being
     * host-testable — so this class cannot call {@code android.util.Log}, and inventing a logging
     * port for one message would be a worse trade than handing the caller the text.
     * {@link MlsHealthMachine} returns its {@code Outcome} for the same reason.
     */
    public static final class Result {
        /** The last action a pass returned, or the synthesised cap-reached action. */
        public final MlsHostAction action;
        /** How many passes ran. */
        public final int iterations;
        /**
         * §20.4 #19/#20's lines for this drive, in order (rework 13.2).
         *
         * <p>RETURNED rather than logged, because this class lives in the engine module and stays
         * free of Android logging so it can be host-tested. The caller emits them; the wording is
         * fixed here so it cannot drift per call site.
         */
        public final java.util.List<String> traceLines;
        /** Whether the cap stopped it. Should be {@code false} forever. */
        public final boolean cappedOut;
        /**
         * The loop stopped because a pass DECLARED it changed nothing — rule 1 in the class doc.
         *
         * <p>Not a fault. It is the drive doing the cheapest correct thing, and the status it
         * carries is the pass's own, so a caller reading {@code FAIL_RETRY} here should schedule the
         * retry exactly as it always did.
         */
        public final boolean stoppedDeclaredInert;
        /**
         * The loop stopped because two consecutive passes were indistinguishable — rule 2.
         *
         * <p><b>This one IS a fault</b>, and it is the fault {@link MlsMetrics#MAX_LOOP_REACHED}
         * names. A pass reported more work while nothing moved, and the producer did not say so, so
         * one look-up was spent finding out.
         */
        public final boolean stoppedWithoutProgress;
        private final String mWhat;
        private final int mCap;

        Result(final MlsHostAction action, final int iterations, final boolean cappedOut,
                final java.util.List<String> traceLines,
                final String what, final int cap) {
            this(action, iterations, cappedOut, false, false, traceLines, what, cap);
        }

        Result(final MlsHostAction action, final int iterations, final boolean cappedOut,
                final boolean stoppedDeclaredInert, final boolean stoppedWithoutProgress,
                final java.util.List<String> traceLines,
                final String what, final int cap) {
            this.action = action;
            this.iterations = iterations;
            this.cappedOut = cappedOut;
            this.stoppedDeclaredInert = stoppedDeclaredInert;
            this.stoppedWithoutProgress = stoppedWithoutProgress;
            this.traceLines = traceLines == null
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.unmodifiableList(traceLines);
            mWhat = what;
            mCap = cap;
        }

        public MlsResultStatus status() { return action.status; }

        /**
         * The line to log — at SEVERE when {@link #cappedOut}, at INFO otherwise.
         *
         * <p>The cap-reached wording states the expected-flat-at-zero property explicitly, because
         * the person reading it will be seeing this counter for the first time.
         */
        public String logLine() {
            if (cappedOut) {
                return "MLS drive '" + mWhat + "' hit the iteration cap (" + mCap + ") without "
                        + "settling — last action " + action + ". "
                        + MlsMetrics.MAX_LOOP_REACHED + " is expected to be FLAT AT ZERO; an "
                        + "increment means a pass reported more work without making progress.";
            }
            if (stoppedWithoutProgress) {
                // Carries the same "reported work without progressing" phrase as the cap line,
                // because it is the same fault caught earlier — and MlsInvariantScan greps for it.
                return "MLS drive '" + mWhat + "' STOPPED after " + iterations + " passes: two "
                        + "consecutive passes reported work without progressing (identical arm, "
                        + "status and snapshot) — " + action + ". Every pass here costs a server "
                        + "look-up, so re-driving an unchanged answer is how recovery exhausts the "
                        + "quota it needs. " + MlsMetrics.MAX_LOOP_REACHED
                        + " is counted here, not only at the cap.";
            }
            if (stoppedDeclaredInert) {
                return "MLS drive '" + mWhat + "' settled after " + iterations
                        + (iterations == 1 ? " pass" : " passes")
                        + ": the pass reported it changed nothing (" + action.redrive.name()
                        + "), so it is not re-driven here — the work is still owed and its status ("
                        + action.status.name() + ") is preserved for the caller. " + action;
            }
            if (iterations == 0) {
                return "MLS drive '" + mWhat + "' was ABANDONED before any pass completed: "
                        + action;
            }
            return "MLS drive '" + mWhat + "' settled after " + iterations
                    + (iterations == 1 ? " pass: " : " passes: ") + action;
        }

        @Override public String toString() {
            return "drive{" + action + " after " + iterations
                    + (iterations == 1 ? " pass" : " passes")
                    + (cappedOut ? ", CAPPED" : "")
                    + (stoppedWithoutProgress ? ", NO PROGRESS" : "")
                    + (stoppedDeclaredInert ? ", INERT" : "") + "}";
        }
    }

    /**
     * Run {@code pass} until its result says stop.
     *
     * <p>Stops on {@link MlsResultStatus#NO_OP} (converged — this is how convergence is DETECTED),
     * {@link MlsResultStatus#SUCCESS} (done) and {@link MlsResultStatus#FAIL_NO_RETRY} (terminal).
     * Re-drives on {@link MlsResultStatus#PENDING} and {@link MlsResultStatus#FAIL_RETRY}.
     *
     * <p>A pass that throws is NOT swallowed: an exception is a defect in the pass, and converting
     * it to a retry would re-drive a broken pass up to the cap and report the cap instead of the
     * cause.
     *
     * @param what a short label for logs — the flow being driven
     */
    public Result drive(final String what, final Pass pass) {
        if (pass == null) throw new IllegalArgumentException("pass");
        MlsHostAction previous = null;
        boolean poisoned = false;
        // §20.4 #19, verbatim (rework 13.2) — one per iteration, zero-based as Google Messages numbers them.
        final java.util.List<String> trace = new java.util.ArrayList<>();
        for (int i = 0; i < mMaxIterations; i++) {
            trace.add(MlsTrace.postProcessInternal(what, i));
            MlsHostAction action = pass.run(previous);
            if (action == null) {
                // Not a NO_OP. A pass returning null has not told us anything, and reading silence
                // as convergence is the exact conflation item 4.4 exists to end.
                throw new IllegalStateException("MLS drive '" + what + "' pass " + (i + 1)
                        + " returned null — return MlsHostAction.none(reason) to report no work");
            }
            // §10.5: once a pending operation has failed in this drive, a later "nothing to do" is
            // not convergence — it is the failure being forgotten.
            if (poisoned) action = action.poison();
            if (action.poisonsNoOp()) poisoned = true;
            if (action.status.stopsLoop()) {
                return new Result(action, i + 1, false, trace, what, mMaxIterations);
            }
            // RULE 1 — DECLARED. The pass says it changed nothing, or could not spend
            // its look-up. Re-driving re-reads identical state at the price of a GetMlsGroupInfo,
            // and the answer is already in hand. Stop, keeping the pass's OWN status: FAIL_RETRY
            // still means the work is owed, so the caller still records and schedules it.
            if (action.redrive != MlsHostAction.Redrive.IMMEDIATE) {
                return new Result(action, i + 1, false, /*stoppedDeclaredInert=*/ true,
                        /*stoppedWithoutProgress=*/ false, trace, what, mMaxIterations);
            }
            // RULE 2 — OBSERVED. The backstop for a pass that has not declared rule 1. Two
            // indistinguishable results in a row are not evidence of progress; they are evidence of
            // its absence, and a third pass would spend another look-up to be told the same thing a
            // third time. Counted as MAX_LOOP_REACHED because that counter's meaning is exactly this
            // condition — see the class doc on why it must fire here and not only eight fetches
            // later.
            //
            // The snapshot is part of the comparison and is load-bearing: a pass that legitimately
            // progresses while returning the same arm says so by returning a different snapshot,
            // which is what MlsGroupSnapshot exists for (rework 4.1b). A pass that progresses and
            // reports MlsGroupSnapshot.NONE both times has told the loop nothing, and the loop is
            // not entitled to assume movement it cannot see.
            if (previous != null
                    && previous.kind == action.kind
                    && previous.status == action.status
                    && previous.snapshot.identicalTo(action.snapshot)) {
                mTelemetry.count(MlsMetrics.MAX_LOOP_REACHED);
                return new Result(action, i + 1, false, /*stoppedDeclaredInert=*/ false,
                        /*stoppedWithoutProgress=*/ true, trace, what, mMaxIterations);
            }
            previous = action;
        }
        // The cap. Counted here; logged by the caller from Result.logLine().
        mTelemetry.count(MlsMetrics.MAX_LOOP_REACHED);
        // §20.4 #20, verbatim (13.2). Google Messages emits this on the cap; ours additionally carries the
        // richer Result.logLine() at the call site, which explains why reaching it is a bug.
        trace.add(MlsTrace.maxIterationReached(mMaxIterations));
        final MlsGroupSnapshot snap = previous == null ? MlsGroupSnapshot.NONE : previous.snapshot;
        return new Result(MlsHostAction.withStatus(MlsHostAction.Kind.NONE,
                MlsResultStatus.FAIL_NO_RETRY, snap,
                "drive '" + what + "' reached the " + mMaxIterations + "-iteration cap"),
                mMaxIterations, true, trace, what, mMaxIterations);
    }

    /** The configured cap. */
    public int maxIterations() { return mMaxIterations; }

    /**
     * A drive that was ABANDONED before it could settle — connectivity lost mid-call (rework 5.7).
     *
     * <p>{@link MlsResultStatus#FAIL_RETRY}, not {@code FAIL_NO_RETRY}: nothing was decided, so the
     * work is still owed. Returning this rather than {@code null} keeps every caller on the same
     * shape — a null would push the branch out to each of them, and the one that forgot it would
     * NPE on a transport blip, which is the least convenient moment to discover it.
     *
     * @param what the flow that was abandoned
     */
    public static Result abandoned(final String what, final String reason) {
        return new Result(MlsHostAction.withStatus(MlsHostAction.Kind.NONE,
                MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE, reason),
                0, false, java.util.Collections.<String>emptyList(), what, 0);
    }
}
