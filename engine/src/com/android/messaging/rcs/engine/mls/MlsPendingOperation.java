/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * The single in-flight operation for one conversation, held as a nullable field of the persisted
 * {@link MlsConversationRecord}: the "already pending" guard is the field itself, it survives a
 * restart, and its attempt count cannot be reset by crashing. Staleness is by group moment, never
 * by clock. Immutable, because the record is written whole. See docs/mls/health-and-recovery.md.
 */
public final class MlsPendingOperation {

    /**
     * A pending operation is abandoned after this many attempts. Unrelated to
     * {@link MlsRetryPolicy#MAX_ATTEMPTS_PER_ITEM}, which bounds a retry-queue item.
     */
    public static final int PENDING_OP_MAX_ATTEMPTS = 5;

    /** ...or this long, whichever comes first, so a stuck operation cannot wedge a conversation. */
    public static final long PENDING_OP_MAX_AGE_MS = 10 * 60_000L;

    public static int maxAttempts() {
        return PENDING_OP_MAX_ATTEMPTS;
    }

    public static long maxAgeMs() {
        return PENDING_OP_MAX_AGE_MS;
    }

    /** The typed request arms. Numbers are persisted; never renumber. */
    public enum Kind {
        UNKNOWN(0),
        /** In-place self-heal: epoch++, same era. */
        EPOCH_ADVANCEMENT(1),
        /** New era = new MLS group under the same RCS group id. */
        ERA_ADVANCEMENT(2),
        /** Downgrade out of MLS. */
        END_MLS(3),
        /** Revive out of {@code DoneEndMls}, in place. */
        REVIVE_MLS(4),
        /** Revive by creating a new era. */
        ERA_ADVANCEMENT_FOR_REVIVE(5),
        /** Era advancement used as a downgrade mechanism. */
        PHOENIX_MODE(6),
        /** A commit of proposals cached by reference (e.g. a peer's self_remove). */
        COMMIT_PENDING_PROPOSALS(7),
        /** A subject/icon metadata commit. */
        GROUP_METADATA(8);

        public final int wire;

        Kind(final int wire) { this.wire = wire; }

        /**
         * Whether this operation is a self-heal, so holding the slot makes a repeat heal request a
         * no-op. {@link #END_MLS} is not: a downgrade in flight is refused by the end-MLS guard,
         * which records why.
         */
        public boolean isSelfHeal() {
            return this == EPOCH_ADVANCEMENT
                    || this == ERA_ADVANCEMENT
                    || this == ERA_ADVANCEMENT_FOR_REVIVE
                    || this == REVIVE_MLS
                    || this == PHOENIX_MODE;
        }

        public static Kind fromWire(final int wire) {
            for (final Kind k : values()) {
                if (k.wire == wire) return k;
            }
            return UNKNOWN;
        }
    }

    /**
     * Where the operation came from, which decides the remedy when it dies: only
     * {@link #PROCESS_MESSAGE_API} escalates to recovery. Numbers are persisted.
     */
    public enum Origin {
        /** Provenance not recorded (a record from before this field); does not escalate. */
        UNKNOWN(0, false),
        /** A direct API call from the host with a caller waiting. The caller gets the failure. */
        EXPLICIT_API(1, false),
        /**
         * Raised while processing an inbound message; the only category that escalates, since
         * nobody is waiting on it and nothing else retries it.
         */
        PROCESS_MESSAGE_API(2, true),
        /** A user-visible action (send, add member, leave). The UI reports the failure. */
        USER_ACTION(3, false),
        /** Background or housekeeping work. Its failure is not itself a symptom. */
        OTHER(4, false);

        /** Persisted; never renumber. */
        public final int wire;
        /** Whether a dead operation from this origin escalates to real recovery. */
        public final boolean escalatesOnDeath;

        Origin(final int wire, final boolean escalatesOnDeath) {
            this.wire = wire;
            this.escalatesOnDeath = escalatesOnDeath;
        }

        public static Origin fromWire(final int wire) {
            for (final Origin o : values()) {
                if (o.wire == wire) return o;
            }
            return UNKNOWN;
        }
    }

    /** Which operation is in flight. */
    public final Kind kind;

    /** Where it came from; decides the remedy if it dies. */
    public final Origin origin;

    /**
     * How many times it has been attempted. Not cleared by the state machine's hooks, which clear
     * the self-heal budget instead.
     */
    public final int attemptCount;

    /** When the first attempt started, ms since epoch: the retry time limit. */
    public final long startedAtMs;

    /** The {@code (era, epoch)} the operation was created at; the staleness binding. */
    public final Moment momentBinding;

    /** Opaque per-arm context (a peer E.164, an rcs group id, a target era). May be empty. */
    public final String context;

    public MlsPendingOperation(final Kind kind, final int attemptCount, final long startedAtMs,
            final Moment momentBinding, final String context) {
        this(kind, Origin.UNKNOWN, attemptCount, startedAtMs, momentBinding, context);
    }

    public MlsPendingOperation(final Kind kind, final Origin origin, final int attemptCount,
            final long startedAtMs, final Moment momentBinding, final String context) {
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.origin = origin == null ? Origin.UNKNOWN : origin;
        this.attemptCount = Math.max(0, attemptCount);
        this.startedAtMs = startedAtMs;
        this.momentBinding = momentBinding;
        this.context = context == null ? "" : context;
    }

    /** A fresh operation at attempt 1, provenance unrecorded. */
    public static MlsPendingOperation start(final Kind kind, final long nowMs, final Moment at,
            final String context) {
        return new MlsPendingOperation(kind, Origin.UNKNOWN, 1, nowMs, at, context);
    }

    /** A fresh operation at attempt 1, with its provenance. */
    public static MlsPendingOperation start(final Kind kind, final Origin origin, final long nowMs,
            final Moment at, final String context) {
        return new MlsPendingOperation(kind, origin, 1, nowMs, at, context);
    }

    /** The same operation, one attempt later. {@link #startedAtMs} and the binding do not move. */
    public MlsPendingOperation retried() {
        return new MlsPendingOperation(kind, origin, attemptCount + 1, startedAtMs, momentBinding,
                context);
    }

    /** Whether this operation dying should escalate to recovery; a property of its origin. */
    public boolean escalatesOnDeath() { return origin.escalatesOnDeath; }

    /**
     * True if the group has moved since this operation was created. An operation with no binding
     * is not stale.
     */
    public boolean isStaleAt(final Moment current) {
        if (momentBinding == null || current == null) return false;
        return !momentBinding.equals(current);
    }

    /** True once {@code limit} attempts have been made. */
    public boolean attemptsExhausted(final int limit) {
        return limit > 0 && attemptCount >= limit;
    }

    /** True once {@code windowMs} has elapsed since the first attempt. */
    public boolean timedOut(final long nowMs, final long windowMs) {
        return windowMs > 0L && startedAtMs > 0L && (nowMs - startedAtMs) >= windowMs;
    }

    /** True once {@link #PENDING_OP_MAX_ATTEMPTS} attempts have been made. The shipping form. */
    public boolean attemptsExhausted() {
        return attemptsExhausted(PENDING_OP_MAX_ATTEMPTS);
    }

    /**
     * True once one more attempt would exhaust the bound. Used to decide whether to wedge a
     * conversation in {@code CannotHealDuringEndMls}, where one attempt early strands it.
     */
    public boolean lastAttemptExhaustsTheBound() {
        return attemptCount + 1 >= PENDING_OP_MAX_ATTEMPTS;
    }

    /**
     * Whether the slot is out of attempts or out of time. Staleness is deliberately separate: only
     * the stale arm emits the stale-pending trace line.
     */
    public boolean spent(final long nowMs) {
        return attemptsExhausted() || timedOut(nowMs, PENDING_OP_MAX_AGE_MS);
    }

    @Override public String toString() {
        return "pending{" + kind + " from=" + origin + " attempt=" + attemptCount
                + " at=" + (momentBinding == null ? "?" : momentBinding)
                + (context.isEmpty() ? "" : " ctx=" + MlsConversationKey.forLog(context)) + "}";
    }

    /**
     * Whether {@code claiming} may supersede {@code held} as its escalation, before the held slot
     * is stale or spent. Exactly one directed pair: {@link Kind#PHOENIX_MODE} over
     * {@link Kind#END_MLS}, since Phoenix mode exists for a failed outgoing {@code end_mls} commit
     * (RCC.16 §7.11.2.2).
     */
    public static boolean supersedesAsEscalation(final Kind held, final Kind claiming) {
        return held == Kind.END_MLS && claiming == Kind.PHOENIX_MODE;
    }

    /** The operation in flight for this conversation, or null. */
    public static MlsPendingOperation pendingOp(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return null;
        shell.lock(key);
        try {
            final MlsConversationRecord r = MlsRecordState.recordFor(shell, log, key);
            return r == null ? null : r.pendingOperation;
        } finally { shell.unlock(key); }
    }

    /** Release the slot. Safe when nothing is held. */
    public static void clearPendingOp(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null || rec.pendingOperation == null) return;
            shell.records().put(rec.toBuilder().pendingOperation(null).build());
            synchronized (shell.pendingOpsClaimedThisProcess()) {
                shell.pendingOpsClaimedThisProcess().remove(key);   // released is not owned
            }
        } finally { shell.unlock(key); }
    }

    /**
     * The operation failed terminally: release the slot and, if its origin escalates, request a
     * self-heal.
     */
    public static void failPendingOp(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String why) {
        if (key == null) return;
        boolean escalate = false;
        MlsPendingOperation dead = null;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null || rec.pendingOperation == null) return;
            dead = rec.pendingOperation;
            escalate = dead.escalatesOnDeath();
            shell.records().put(rec.toBuilder().pendingOperation(null).build());
            synchronized (shell.pendingOpsClaimedThisProcess()) {
                shell.pendingOpsClaimedThisProcess().remove(key);   // released is not owned
            }
        } finally { shell.unlock(key); }
        log.w("MlsPendingOperation: " + MlsConversationKey.forLog(key) + " " + dead + " FAILED — "
                + why);
        if (escalate) {
            // Outside the lock: the health move may reach transport I/O.
            log.w("MlsPendingOperation: that operation came from inbound message "
                    + "processing and nothing else would have retried it — escalating to self-heal");
            shell.moveHealth(key, MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                    "a pending operation raised by inbound processing failed");
        }
    }

    /**
     * Whether a self-heal is in flight, read from the durable health status. Anything that emits a
     * negative receipt checks this first: RCC.16 §10.2 sends the report after recovery.
     */
    public static boolean healInFlight(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return false;
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null) return false;
        return rec.healthStatus == MlsHealthStates.ONGOINGEPOCHADVANCEMENT
                || rec.healthStatus == MlsHealthStates.ONGOINGERAADVANCEMENT
                || rec.healthStatus == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE;
    }

    /**
     * Whether a self-heal is already in flight: a held self-heal that is neither stale nor spent. A
     * drop is a no-op, not a failure, because callers escalate on a failed heal.
     *
     * @return true if the caller should quietly do nothing
     */
    public static boolean healAlreadyPending(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return false;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return false;
            final MlsPendingOperation held = rec.pendingOperation;
            if (held == null) return false;
            if (!held.kind.isSelfHeal()) return false;
            final boolean stale = held.isStaleAt(rec.moment);
            final boolean spent = held.spent(System.currentTimeMillis());
            if (stale || spent) {
                // A dead slot must not block recovery; the claim path supersedes it. The stale
                // trace line is emitted for the stale arm only.
                if (stale) {
                    log.w(MlsTrace.stalePendingOperation(String.valueOf(held),
                            String.valueOf(rec.moment), MlsGroupState.groupIdForTrace(shell, key)));
                }
                log.i("MlsPendingOperation: " + held + " on " + MlsConversationKey.forLog(key)
                        + " is "
                        + (stale ? "STALE (the group moved to " + rec.moment + ")" : "SPENT")
                        + " — not treating it as a heal in flight");
                return false;
            }
            log.i(MlsTrace.alreadyPending("Self-heal", MlsGroupState.groupIdForTrace(shell, key)));
            log.i("MlsPendingOperation: self-heal for " + MlsConversationKey.forLog(key)
                    + " DROPPED — " + held
                    + " is already in flight. This is a no-op, not a failure: the running heal will "
                    + "finish or be superseded, and reporting failure here would make callers "
                    + "escalate on an ordinary concurrent trigger.");
            return true;
        } finally { shell.unlock(key); }
    }

    /**
     * Release the slot only if it holds an {@link Kind#EPOCH_ADVANCEMENT}, the rung an escalating
     * self-heal claimed itself, so that heal may claim it for an era advance. Any other holder is
     * left alone. Logs either way.
     */
    public static void releaseOwnHealSlotForEscalation(final MlsShellPort shell,
            final MlsLogSink log, final String key) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            final MlsPendingOperation held = (rec == null) ? null : rec.pendingOperation;
            if (held == null) return;
            if (held.kind != MlsPendingOperation.Kind.EPOCH_ADVANCEMENT) {
                log.i("MlsPendingOperation: escalating self-heal for "
                        + MlsConversationKey.forLog(key) + " is NOT "
                        + "releasing " + held
                        + " — it is not this heal's own epoch advancement, and "
                        + "dropping somebody else's operation is the fork the guard prevents");
                return;
            }
            log.i("MlsPendingOperation: escalating self-heal for " + MlsConversationKey.forLog(key)
                    + " — releasing its own " + held + " so the era advance can claim the slot "
                    + "(one operation changing rung, not a second advance)");
            shell.records().put(rec.toBuilder().pendingOperation(null).build());
            synchronized (shell.pendingOpsClaimedThisProcess()) {
                shell.pendingOpsClaimedThisProcess().remove(key);   // released is not owned
            }
        } finally { shell.unlock(key); }
    }

    public static boolean claimPendingOp(final MlsShellPort shell, final MlsLogSink log,
            final String key, final MlsPendingOperation.Kind kind,
            final MlsPendingOperation.Origin origin, final String context) {
        if (key == null) return false;
        boolean escalate = false;
        boolean killedHeal = false;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) {
                // No record is not a held slot; the next Welcome re-establishes the group.
                log.i("MlsPendingOperation: " + kind + " NOT started for "
                        + MlsConversationKey.forLog(key)
                        + " — there is NO conversation record (not a held slot). Nothing to claim; "
                        + "the group re-establishes on the next Welcome.");
                return false;
            }
            MlsPendingOperation held = rec.pendingOperation;
            final MlsAppMessage.Moment now = rec.moment;
            if (held != null) {
                // A slot this process never claimed belongs to a dead process (MLS state lives in
                // one process, and the set is empty at start), so reclaim it now instead of waiting
                // out the age cap.
                final boolean ownedByUs;
                synchronized (shell.pendingOpsClaimedThisProcess()) {
                    ownedByUs = shell.pendingOpsClaimedThisProcess().contains(key);
                }
                if (!ownedByUs) {
                    log.w("MlsPendingOperation: reclaiming the pending "
                            + held.kind + " slot on " + MlsConversationKey.forLog(key)
                            + " — it was claimed by a process that no "
                            + "longer exists (this process has never held it). Waiting out the "
                            + (MlsPendingOperation.maxAgeMs() / 60000L)
                            + "-minute cap for an owner that "
                            + "cannot return is an outage with nobody on it.");
                    // Only the local view is cleared; the claim below overwrites the persisted
                    // slot.
                    held = null;
                }
            }
            if (held != null) {
                final boolean stale = held.isStaleAt(now);
                final boolean spent = held.spent(System.currentTimeMillis());
                // Same kind and context is the operation resuming its own slot, not a second one;
                // the attempt and age caps still bound it.
                final boolean resuming = held.kind == kind && context != null
                        && context.equals(held.context);
                // Phoenix mode is the escalation of a failed end_mls, not a competitor for its
                // slot.
                final boolean escalatingEndMls =
                        MlsPendingOperation.supersedesAsEscalation(held.kind, kind);
                if (escalatingEndMls) {
                    log.i("MlsPendingOperation: PHOENIX_MODE is SUPERSEDING the pending "
                            + held + " on " + MlsConversationKey.forLog(key)
                            + " rather than queueing behind it — phoenix is "
                            + "that operation's escalation (the end_mls commit failed), so waiting "
                            + "out its budget would block the rescue with the wreck");
                }
                if (!stale && !spent && !resuming && !escalatingEndMls) {
                    // Name the holder and how long it can keep blocking.
                    final long ageMs = Math.max(0L, System.currentTimeMillis() - held.startedAtMs);
                    log.i("MlsPendingOperation: " + kind + " NOT started for "
                            + MlsConversationKey.forLog(key)
                            + " — " + held + " is already pending (held for " + (ageMs / 1000)
                            + "s of a " + (MlsPendingOperation.maxAgeMs() / 1000)
                            + "s ceiling, attempt " + held.attemptCount + "/"
                            + MlsPendingOperation.maxAttempts()
                            + "). If this blocks a recovery it is that slot's fault, not the "
                            + "recovery's — the holder's ctx names the site that took it.");
                    return false;
                }
                if (resuming && !stale && !spent) {
                    log.i("MlsPendingOperation: " + kind + " on " + MlsConversationKey.forLog(key)
                            + " is RESUMING its "
                            + "own slot (" + held
                            + ") rather than being refused by it — same kind, "
                            + "same context, attempt " + (held.attemptCount + 1));
                }
                log.w("MlsPendingOperation: superseding " + held + " on "
                        + MlsConversationKey.forLog(key)
                        + " (" + (stale ? "the group moved to " + now : "attempts/time exhausted")
                        + ") — a slot that is never released wedges the conversation");
                // Superseding is the held operation dying; its origin decides whether that
                // escalates.
                escalate = held.escalatesOnDeath();
                // A superseded self-heal is killed: record SelfHealFailed (its only producer, and
                // not terminal) so the interruption is visible.
                killedHeal = held.kind.isSelfHeal()
                        && MlsPendingOperation.healInFlight(shell, log, key);
            }
            final String err = shell.records().put(rec.toBuilder()
                    .pendingOperation(MlsPendingOperation.start(
                            kind, origin, System.currentTimeMillis(), now, context))
                    .build());
            if (err != null) {
                log.w("MlsPendingOperation: could not persist the pending op — " + err);
                return false;
            }
            // Only after the persist succeeds, so the next claim can tell a live holder from an
            // orphan.
            synchronized (shell.pendingOpsClaimedThisProcess()) {
                shell.pendingOpsClaimedThisProcess().add(key);
            }
        } finally { shell.unlock(key); }
        if (killedHeal) {
            // Outside the lock, like the escalation below.
            shell.moveHealth(key, MlsHealthStates.SELFHEALFAILED,
                    kind + " took the pending slot while a self-heal was in flight");
            log.w("MlsPendingOperation: a self-heal on " + MlsConversationKey.forLog(key)
                    + " was KILLED by " + kind
                    + " taking the pending slot. Recorded as SelfHealFailed — not terminal, a later "
                    + "trigger can start a fresh one, but the interruption is now visible.");
            // With the heal gone, release the FTD reports that were held behind it.
            final String[] parts = MlsConversationKey.splitCanonicalKey(key);
            if (parts != null) shell.flushFtdReports(parts[0], parts[1]);
        }
        if (escalate) {
            // Outside the lock: the health move may reach transport I/O.
            log.w("MlsPendingOperation: the superseded operation on "
                    + MlsConversationKey.forLog(key)
                    + " came from inbound message processing and nothing else would have retried "
                    + "it — escalating to self-heal");
            shell.moveHealth(key, MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                    "a pending operation raised by inbound processing died unobserved");
        }
        return true;
    }

    /**
     * The operation failed but is worth another go: bump the attempt count and keep the slot;
     * exhausting the attempts converts to {@link #failPendingOp}. {@link #clearPendingOp} is for
     * success. There is deliberately no abort verb.
     */
    public static void retryPendingOp(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String why) {
        if (key == null) return;
        MlsPendingOperation next = null;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null || rec.pendingOperation == null) return;
            next = rec.pendingOperation.retried();
            shell.records().put(rec.toBuilder().pendingOperation(next).build());
        } finally { shell.unlock(key); }
        if (next == null) return;
        if (next.attemptsExhausted()) {
            MlsPendingOperation.failPendingOp(shell, log, key, why + " — and that was attempt "
                    + next.attemptCount + " of " + MlsPendingOperation.maxAttempts());
            return;
        }
        log.i("MlsPendingOperation: " + MlsConversationKey.forLog(key) + " " + next + " — " + why
                + "; keeping the slot for another attempt");
    }

    /**
     * Claim the slot for an era advance, origin {@link Origin#OTHER}. The context names the reason
     * so a held slot is attributable and a resume matches only the same advance.
     */
    public static boolean claimAdvance(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String why) {
        return MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.ERA_ADVANCEMENT, MlsPendingOperation.Origin.OTHER,
                (why == null || why.isEmpty()) ? "eraAdvance" : "eraAdvance:" + why);
    }

    public static void releaseAdvance(final MlsShellPort shell, final MlsLogSink log,
            final String key) { MlsPendingOperation.clearPendingOp(shell, log, key); }
}
