/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
/**
 * The bounded fixed point: re-drive until a pass's result says stop, or the cap does. It terminates
 * on what a pass returned, never on a predicate re-read from the world; a {@link Pass} is handed no
 * way to ask about shared state. A pass that declares it changed nothing (rule 1) or two identical
 * passes in a row (rule 2) stop the drive, because every reconcile pass costs a server look-up. The
 * cap is a bug detector. See docs/mls/health-and-recovery.md.
 */
public final class MlsDriveLoop {

    /** Reaching this is a bug, not a workload: real flows converge in two or three passes. */
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
        // A cap below 1 would run zero passes and fail a conversation that was never asked
        // anything.
        mMaxIterations = Math.max(1, maxIterations);
    }

    /**
     * The outcome of a whole drive. Carries its log lines rather than emitting them, because the
     * engine module is Android-free.
     */
    public static final class Result {
        /** The last action a pass returned, or the synthesised cap-reached action. */
        public final MlsHostAction action;
        public final int iterations;
        /** The trace lines for this drive, in order; the caller emits them. */
        public final java.util.List<String> traceLines;
        /** Whether the cap stopped it. Should always be {@code false}. */
        public final boolean cappedOut;
        /**
         * A pass declared it changed nothing (rule 1). Not a fault; the status is the pass's own,
         * so a {@code FAIL_RETRY} is still scheduled.
         */
        public final boolean stoppedDeclaredInert;
        /**
         * Two consecutive passes were indistinguishable (rule 2). A fault, counted as
         * {@link MlsMetrics#MAX_LOOP_REACHED}.
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

        /** The line to log: at error level when {@link #cappedOut}, info otherwise. */
        public String logLine() {
            if (cappedOut) {
                return "MLS drive '" + mWhat + "' hit the iteration cap (" + mCap + ") without "
                        + "settling — last action " + action + ". "
                        + MlsMetrics.MAX_LOOP_REACHED + " is expected to be FLAT AT ZERO; an "
                        + "increment means a pass reported more work without making progress.";
            }
            if (stoppedWithoutProgress) {
                // Shares the cap line's "reported work without progressing" phrase: same fault, and
                // MlsInvariantScan greps for it.
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
     * Run {@code pass} until its result says stop: {@code NO_OP} (how convergence is detected),
     * success or {@code FAIL_NO_RETRY}. A pass that throws propagates; converting it to a
     * retry would report the cap instead of the cause.
     *
     * @param what a short label for logs: the flow being driven
     */
    public Result drive(final String what, final Pass pass) {
        if (pass == null) throw new IllegalArgumentException("pass");
        MlsHostAction previous = null;
        boolean poisoned = false;
        final java.util.List<String> trace = new java.util.ArrayList<>();
        for (int i = 0; i < mMaxIterations; i++) {
            trace.add(MlsTrace.postProcessInternal(what, i));
            MlsHostAction action = pass.run(previous);
            if (action == null) {
                // A null is not a NO_OP: silence is not convergence.
                throw new IllegalStateException("MLS drive '" + what + "' pass " + (i + 1)
                        + " returned null — return MlsHostAction.none(reason) to report no work");
            }
            // Once a pending operation has failed in this drive, a later "nothing to do" is the
            // failure being forgotten, not convergence.
            if (poisoned) action = action.poison();
            if (action.poisonsNoOp()) poisoned = true;
            if (action.status.stopsLoop()) {
                return new Result(action, i + 1, false, trace, what, mMaxIterations);
            }
            // Rule 1, declared: stop, keeping the pass's own status so owed work is still
            // scheduled.
            if (action.redrive != MlsHostAction.Redrive.IMMEDIATE) {
                return new Result(action, i + 1, false, /*stoppedDeclaredInert=*/ true,
                        /*stoppedWithoutProgress=*/ false, trace, what, mMaxIterations);
            }
            // Rule 2, observed: two indistinguishable results in a row. The snapshot is part of the
            // comparison, so a pass that progresses must report a different snapshot.
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
        trace.add(MlsTrace.maxIterationReached(mMaxIterations));
        final MlsGroupSnapshot snap = previous == null ? MlsGroupSnapshot.NONE : previous.snapshot;
        return new Result(MlsHostAction.withStatus(MlsHostAction.Kind.NONE,
                MlsResultStatus.FAIL_NO_RETRY, snap,
                "drive '" + what + "' reached the " + mMaxIterations + "-iteration cap"),
                mMaxIterations, true, trace, what, mMaxIterations);
    }

    public int maxIterations() { return mMaxIterations; }

    /**
     * A drive abandoned before it could settle (connectivity lost): {@code FAIL_RETRY} with zero
     * passes, so the work is still owed and no caller handles {@code null}.
     *
     * @param what the flow that was abandoned
     */
    public static Result abandoned(final String what, final String reason) {
        return new Result(MlsHostAction.withStatus(MlsHostAction.Kind.NONE,
                MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE, reason),
                0, false, java.util.Collections.<String>emptyList(), what, 0);
    }

    /** The re-entrancy marker for {@code key}; never null, and absent means {@code NORMAL}. */
    public static MlsSchedulingType schedulingFor(final MlsShellPort shell, final String key) {
        final ConvState s = shell.convIfAny(key);
        if (s == null) return MlsSchedulingType.NORMAL;
        synchronized (s) {
            return s.scheduling == null ? MlsSchedulingType.NORMAL : s.scheduling;
        }
    }

    /**
     * Stamp the marker. Must happen before the results of this work reach anything that could
     * schedule more.
     */
    public static void stampScheduling(final MlsShellPort shell, final String key,
            final MlsSchedulingType type) {
        if (key == null) return;
        // NORMAL clears rather than stores, so "no state" and "stamped NORMAL" stay one thing.
        if (type == null || type == MlsSchedulingType.NORMAL) {
            final ConvState s = shell.convIfAny(key);
            if (s != null) synchronized (s) { s.scheduling = null; }
            return;
        }
        final ConvState s = shell.conv(key);
        synchronized (s) { s.scheduling = type; }
    }

    /**
     * Whether this conversation may schedule follow-up work right now. Declines rather than
     * throwing; {@link MlsSchedulingType#requireTransportAllowed} is the guard that throws.
     */
    public static boolean maySchedule(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String what) {
        final MlsSchedulingType t = MlsDriveLoop.schedulingFor(shell, key);
        if (t.allowsScheduling()) return true;
        log.i("MlsDriveLoop: not scheduling " + what + " for " + MlsConversationKey.forLog(key)
                + " because it is already part of a " + t + " flow");
        return false;
    }

    /**
     * Records a provider verdict for {@code key}, as {@code MlsCommitSend} does for a commit, so
     * {@link #livePass} can tell a pass that failed on connectivity from one that failed on the
     * merits. Called after each create RPC a rebuild or era advance makes.
     */
    public static void noteControlVerdict(final MlsShellPort shell, final String key,
            final MlsProviderRpc.ControlResult r) {
        if (key == null) return;
        final ConvState cs = shell.conv(key);
        synchronized (cs) {
            cs.lastControlVerdict = (r == null)
                    ? MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED : r.verdict;
            cs.lastControlDetail = (r == null) ? null : r.detail;
        }
    }

    /**
     * Runs one drive pass with the conversation's last control verdict cleared first. A pass that
     * ends {@code FAIL_RETRY} after a provider call reported lost connectivity throws
     * {@link MlsTransportDisposition.ConnectivityLost}, which the drive's caller turns into
     * {@link #abandoned}; any other outcome is returned unchanged.
     *
     * @param where the call site, for {@link MlsTransportDisposition#requireLive}
     */
    static MlsHostAction livePass(final MlsShellPort shell, final String key, final String where,
            final java.util.function.Supplier<MlsHostAction> pass) {
        final ConvState cs = key == null ? null : shell.conv(key);
        if (cs != null) {
            synchronized (cs) {
                cs.lastControlVerdict = -1;
                cs.lastControlDetail = null;
            }
        }
        final MlsHostAction a = pass.get();
        if (cs != null && a != null && a.status == MlsResultStatus.FAIL_RETRY) {
            final int verdict;
            synchronized (cs) { verdict = cs.lastControlVerdict; }
            MlsTransportDisposition.requireLive(where, verdict);
        }
        return a;
    }

    public static MlsDriveLoop.Result driveReconcileInner(final MlsShellPort shell,
            final MlsLogSink log, final String key, final String rcsGroupId,
            final String peerE164) {
        final String what = "reconcile:" + key;
        // The fetch budget lives at the call site: the loop bounds iterations and cannot see a
        // fetch. Every pass costs at least one server look-up (reconcileAction starts with
        // detectHealth).
        final int[] looks = new int[1];
        final MlsDriveLoop.Result drive = shell.driveLoop().drive(what, previous -> {
            if (MlsFetchBudget.mayLook(looks[0], MlsFetchBudget.RECOVERY_LOOKS)
                    != MlsFetchBudget.Verdict.SPEND) {
                final String why = MlsFetchBudget.describeDenial(
                        looks[0], MlsFetchBudget.RECOVERY_LOOKS, what);
                log.w("MlsDriveLoop: " + why);
                // FAIL_RETRY: running out of our own allowance says nothing about repairability.
                return MlsHostAction.withRedrive(MlsHostAction.Kind.NONE,
                        MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                        MlsHostAction.Redrive.NOT_IN_THIS_DRIVE, why);
            }
            final int spentBefore = looks[0];
            looks[0]++;
            final MlsHostAction a = MlsDriveLoop.livePass(shell, key, "reconcile pass",
                    () -> MlsConversationRebuild.reconcileAction(shell, log, rcsGroupId,
                            peerE164));
            // The look count spent just before an unreadable look exists only here, and decides how
            // to read it.
            if (a.redrive == MlsHostAction.Redrive.AFTER_A_COOLDOWN) {
                log.w("MlsDriveLoop: " + MlsFetchBudget.describeUnreadableLook(
                        spentBefore, what));
            }
            return a;
        });
        // The no-progress stop is a fault and logs at the cap's severity; the declared-inert stop
        // does not.
        if (drive.cappedOut || drive.stoppedWithoutProgress) {
            log.e(drive.logLine());
        } else {
            log.i(drive.logLine());
        }
        log.i("MlsDriveLoop: " + MlsFetchBudget.describeSpend(
                looks[0], MlsFetchBudget.RECOVERY_LOOKS, what));
        return drive;
    }

    /**
     * Run {@link MlsConversationRebuild#reconcileAction} through the drive loop. Public so a debug
     * trigger exercises the real path. Does not claim the pending operation or record forward
     * progress; those belong to the caller.
     */
    public static MlsDriveLoop.Result driveReconcile(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final MlsDriveLoop.Result drive;
        try {
            drive = MlsDriveLoop.driveReconcileInner(shell, log, key, rcsGroupId, peerE164);
        } catch (final MlsTransportDisposition.ConnectivityLost lost) {
            // Call site 1 of 2: keep this wording distinct from site 2's.
            log.w("MlsDriveLoop: connectivity lost during RECONCILE for "
                    + MlsConversationKey.forLog(key)
                    + ", requesting backoff — " + lost.getMessage());
            return MlsDriveLoop.abandoned("reconcile:" + key,
                    "connectivity lost mid-reconcile (verdict " + lost.verdict + ")");
        }
        return drive;
    }
}
