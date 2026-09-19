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

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * The single in-flight operation for one conversation — §4.8 rows 3–7.
 *
 * <p>Rework item 2.2, and it is a CONFLICT rather than a gap: we built exactly the shape the item
 * forbids, four times over. {@code mAdvanceInFlight}, {@code mPendingCommitFor},
 * {@code mPendingEndMlsFor} and {@code mPendingSubject} are four disjoint in-memory collections,
 * <b>keyed inconsistently</b> — the first by canonical key, the next two by the app's conversation
 * id — so "one in-flight operation per group" is not even expressible today, let alone enforced.
 *
 * <p>This type replaces all four with a <b>nullable field of the conversation record</b>. That
 * choice is the entire mechanism:
 *
 * <ul>
 *   <li><b>The "already pending" guards come for free.</b> Not as three separate checks that can
 *       disagree, but because a field either holds an operation or does not.</li>
 *   <li><b>It survives a restart.</b> A crash mid-advance used to lose both the guard AND the
 *       operation.</li>
 *   <li><b>The retry limit cannot be bypassed by crashing.</b> {@link #attemptCount} is persisted,
 *       so a crash loop no longer resets it to zero on every pass.</li>
 *   <li><b>A stale operation cannot be applied after the world moved on.</b> {@link #momentBinding}
 *       is the {@code (era, epoch)} at creation, and the staleness check is
 *       <b>moment-based, never timestamp-based</b> (§7.4).</li>
 * </ul>
 *
 * <p>Immutable: {@link #retried()} returns a new instance rather than mutating, because this is a
 * field of a record that is <b>written whole</b>, and an in-place bump would be a partial update by
 * another name.
 */
public final class MlsPendingOperation {

    /**
     * A pending operation is abandoned after this many attempts.
     *
     * <p>Declared here, next to {@link #attemptsExhausted} rather
     * than in {@code MlsProviderTransport}: the counter is a field of THIS class, it is persisted
     * on THIS class's record, and the bound on a counter belongs with the counter. It is not
     * {@link MlsRetryPolicy#MAX_ATTEMPTS_PER_ITEM}, which bounds a retry QUEUE ITEM and is three
     * orders of magnitude larger — the two were never the same number and deriving one from the
     * other is exactly the tie we refuse to make elsewhere.
     */
    public static final int PENDING_OP_MAX_ATTEMPTS = 5;

    /**
     * ...or this long, whichever comes first. A stuck op must never wedge a conversation.
     *
     * <p>Ten minutes. It is the ONLY thing that frees a slot whose owner died before the
     * process-ownership check existed, which is why it is short: every APK reinstall kills the
     * process, and a longer cap is an outage measured in that cap.
     */
    public static final long PENDING_OP_MAX_AGE_MS = 10 * 60_000L;

    /** {@link #PENDING_OP_MAX_ATTEMPTS}, for a log line that prints the ceiling it is against. */
    public static int maxAttempts() {
        return PENDING_OP_MAX_ATTEMPTS;
    }

    /** {@link #PENDING_OP_MAX_AGE_MS}, for the same reason. */
    public static long maxAgeMs() {
        return PENDING_OP_MAX_AGE_MS;
    }

    /** The eight typed request arms (§4.4). Numbers are persisted — never renumber. */
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
         * Whether this operation IS a self-heal — i.e. whether holding the slot should make a fresh
         * heal request a no-op (invariant 86, rework 8.4).
         *
         * <p>The distinction is not cosmetic. The pending guard drops a repeat heal only when the
         * held operation is one, because the two other kinds of occupant mean opposite things: a
         * {@link #GROUP_METADATA} or {@link #COMMIT_PENDING_PROPOSALS} in the slot is ordinary work
         * that a genuinely diverged conversation must still be allowed to heal past, whereas
         * dropping a heal because a subject commit is in flight would leave it broken until someone
         * happened to trigger recovery again.
         *
         * <p>{@link #END_MLS} is deliberately NOT one: a downgrade in flight is answered by the
         * end-MLS guard, which records {@code CannotHealDuringEndMls} — a refusal that says WHY,
         * where a silent drop here would say nothing.
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
     * WHERE the operation came from (§4.4 oneof-0) — four categories, and the category decides the
     * remedy when the operation DIES.
     *
     * <p>Rework item 5.1, and the half most likely to be dismissed as cosmetic. It is not. Without
     * the category there are only two available behaviours and both are wrong: escalate every dead
     * operation to real recovery, and a device that fails one user-initiated action starts churning
     * era advances; escalate none, and a conversation that dies mid-inbound-processing stays wedged
     * with nothing watching it.
     *
     * <p>Only {@link #PROCESS_MESSAGE_API} escalates. The other three are deliberate no-ops — the
     * caller is present to be told, or the work was never load-bearing to begin with.
     */
    public enum Origin {
        /**
         * Provenance not recorded — an operation persisted before this field existed.
         *
         * <p>Treated as non-escalating, deliberately. An unknown origin on a device that has just
         * upgraded is far more likely to be an ordinary old record than a wedged conversation, and
         * escalating every one of them on first boot after an update is exactly the churn the
         * categories exist to prevent.
         */
        UNKNOWN(0, false),
        /** A direct API call from the host with a caller waiting. The caller gets the failure. */
        EXPLICIT_API(1, false),
        /**
         * Raised while processing an inbound message. <b>The only category that escalates.</b>
         *
         * <p>Nobody is waiting on it and nobody will retry it, so if this dies quietly the
         * conversation simply stops working — which is precisely the failure recovery exists for.
         */
        PROCESS_MESSAGE_API(2, true),
        /** A user-visible action (send, add member, leave). The UI reports the failure. */
        USER_ACTION(3, false),
        /** Background or housekeeping work. Its failure is not itself a symptom. */
        OTHER(4, false);

        /** Persisted. Never renumber. */
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

    /** Which operation is in flight (§4.8 row 4). */
    public final Kind kind;

    /** Where it came from (§4.4) — decides the remedy if it dies. See {@link Origin}. */
    public final Origin origin;

    /**
     * How many times it has been attempted (§4.8 row 5).
     *
     * <p><b>Must NOT be cleared by the state machine's hooks</b> (§5.10). It is the self-heal
     * budget that those hooks clear — a different field, deliberately.
     */
    public final int attemptCount;

    /** When the FIRST attempt started, ms since epoch (§4.8 row 6) — the retry TIME limit. */
    public final long startedAtMs;

    /**
     * The {@code (era, epoch)} the operation was created at (§4.8 row 7).
     *
     * <p>Consumed by {@code PendingOperationNotForCurrentGroupMoment}. Moment-based and never
     * timestamp-based, because the question is "has the group moved since?" and a clock cannot
     * answer that: two devices' clocks disagree, and an operation created a millisecond ago against
     * an era that has since advanced is stale while one created an hour ago against the current
     * moment is not.
     */
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

    /**
     * Whether this operation dying should escalate to real recovery (§4.4).
     *
     * <p>Delegates to {@link Origin#escalatesOnDeath} rather than testing the {@link Kind}: what
     * makes a death worth recovering from is whether anyone is left to notice it, which is a
     * property of where the work came from, not of what the work was.
     */
    public boolean escalatesOnDeath() { return origin.escalatesOnDeath; }

    /**
     * True if the group has moved since this operation was created — §7.4's staleness rule.
     *
     * <p>An operation with no binding is NOT stale: it predates the field, and treating an unknown
     * binding as stale would drop every in-flight operation the first time a device upgrades.
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
     * True once ONE MORE attempt would exhaust the bound.
     *
     * <p>Its own predicate rather than {@code attemptCount + 1 >= …} at the call site, because the
     * one caller that asks it is deciding whether to WEDGE a conversation (§9.7j
     * {@code CannotHealDuringEndMls}) — a terminal state that forbids both a fresh downgrade and a
     * Phoenix run. Arriving there one attempt early strands a conversation a retry would have fixed.
     */
    public boolean lastAttemptExhaustsTheBound() {
        return attemptCount + 1 >= PENDING_OP_MAX_ATTEMPTS;
    }

    /**
     * Is this slot SPENT — out of attempts, or out of time?
     *
     * <p>The two arms were written out together at three call sites and had to agree at all three;
     * they are one question and this is it. {@code stale} is deliberately NOT folded in: §20.4 #12
     * emits a line for the stale arm and Google Messages emits none for the spent one, so a caller that
     * cannot tell them apart would make a diff show a match we do not have.
     */
    public boolean spent(final long nowMs) {
        return attemptsExhausted() || timedOut(nowMs, PENDING_OP_MAX_AGE_MS);
    }

    @Override public String toString() {
        return "pending{" + kind + " from=" + origin + " attempt=" + attemptCount
                + " at=" + (momentBinding == null ? "?" : momentBinding)
                + (context.isEmpty() ? "" : " ctx=" + context) + "}";
    }

    /**
     * May a {@code claiming} operation SUPERSEDE a held one purely because it escalates it?
     *
     * <p>Normally a held slot blocks a different operation until it goes stale or spends its budget
     * (5 attempts / 10 minutes). That is right for competitors and wrong for escalations, and there
     * is exactly one escalation pair:
     *
     * <p><b>{@link Kind#PHOENIX_MODE} may supersede {@link Kind#END_MLS}.</b> Phoenix mode exists
     * for one situation — the outgoing {@code end_mls} Commit failed — and RCC.16 v4.0 §7.11.2.2
     * even names the reason {@code END_MLS_REASON_OUTGOING_COMMIT_FAILED(10)}. Making Phoenix wait
     * out the budget of the very commit it is rescuing blocks the rescue with the wreck.
     *
     * <p>This is the CROSS-KIND instance of a bug already fixed for the same-kind case: an
     * operation refused by its own bookkeeping, logged as an ordinary "already
     * pending", so the refusal reads like correctness. Device-observed:
     * an {@code end_mls} that failed {@code InvalidEpoch} held the slot and
     * Phoenix was refused, leaving the conversation in {@code PhoenixModeRequested} with the dead
     * {@code END_MLS} still pending.
     *
     * <p>Deliberately ONE DIRECTED PAIR. The reverse does not hold — an {@code end_mls} must not
     * barge past a running Phoenix, which is already further along the same ladder — and no other
     * combination escalates. A general "urgent operations may supersede" rule here would re-open
     * the races the single slot exists to prevent.
     */
    public static boolean supersedesAsEscalation(final Kind held, final Kind claiming) {
        return held == Kind.END_MLS && claiming == Kind.PHOENIX_MODE;
    }
}
