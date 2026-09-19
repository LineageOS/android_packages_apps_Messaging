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
}
