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
 * What the host should do next — the closed action set our MLS flows return (rework item 4.2, §3.9).
 *
 * <p>Nothing in our code returns a value describing what should happen next. Every branch performs
 * its effect inline and returns a {@code boolean} or an era {@code int}:
 * {@code applyInboundControl} returns {@code boolean}, {@code eraAdvance} returns {@code int},
 * {@code commitAndSend} returns {@code int}. Consequently there is no place to stand to write a
 * drive loop, no way to test a decision without performing it, and no way for a failure path to
 * hand a caller anything but "false".
 *
 * <h2>These are OUR arms, not Google Messages' eleven</h2>
 *
 * <p>Google Messages' set ({@code ApplicationMessageToSend}, {@code MlsGroupInfo},
 * {@code SignedMessageToSend}, …) is the shape of ITS class graph — nine Dagger-multibound
 * postprocessors and an FFI container for Google's MLS engine. Copying the names would mean inventing arms our engine
 * has no reason to emit: our engine returns facts and the orchestration those arms represent already
 * lives in our Java host. What transfers, and what the scoping review says to imitate, is the
 * DISCIPLINE — a closed sum, a {@link MlsResultStatus} on every arm, exhaustive matching, rejection
 * BY NAME, and a written-down producer set per arm.
 *
 * <h2>The producer set is load-bearing</h2>
 *
 * <p>Each {@link Kind} below names the code paths that may emit it. This is not documentation for
 * its own sake: §3.9 shows two of Google Messages' arms have non-obvious producer sets
 * ({@code KeyRefreshRequired} from four producers, {@code PendingOperationFailure} from two), and
 * the failure mode of not writing them down is a re-drive arm that is unreachable from the failure
 * path — a recovery that silently cannot happen. Ours already has that shape in miniature:
 * {@link Kind#KEY_REFRESH_REQUIRED} has three distinct producers and today NONE of them exists,
 * which is why §14.9 trigger 5 leaves us advertising {@code mls-kds} forever with an empty pool.
 *
 * <p><b>When you add a producer, add it to the list.</b> The list is the only thing that makes
 * "which paths can reach this arm?" answerable without re-reading every flow.
 *
 * @see MlsDriveLoop for the bounded fixed point that consumes these
 */
public final class MlsHostAction {

    /**
     * The closed set. Adding an arm here means visiting every exhaustive match — which is the point:
     * the compiler cannot force it in Java, so {@link #rejectUnhandled} does it at runtime, by name.
     */
    public enum Kind {

        /**
         * Genuinely nothing to do. Terminates a drive loop.
         *
         * <p><b>Producers:</b> an inbound control that parsed but changed nothing (engine status
         * OTHER); a reconcile that found the group already healthy; a re-drive pass that found no
         * remaining work — which is how convergence is DETECTED (§8.6).
         */
        NONE(MlsResultStatus.NO_OP),

        /**
         * A decrypted application payload is ready to hand up.
         *
         * <p><b>Producers:</b> the app-message decrypt path; the control-plane key-delivery path
         * (§7.8.1 — an APP payload riding a control frame, which is how a metadata commit ships the
         * key its 0xF006 commitment commits to).
         */
        DELIVER_MESSAGE(MlsResultStatus.SUCCESS),

        /**
         * A commit applied and the epoch moved. Buffered work may now be drainable.
         *
         * <p><b>Producers:</b> inbound control, engine status COMMIT; our own commit applying
         * locally after {@code commitAndSend}.
         */
        EPOCH_ADVANCED(MlsResultStatus.SUCCESS),

        /**
         * We joined a group from a Welcome.
         *
         * <p><b>Producers:</b> a plain Welcome; Apple's kind=5 tree-splice; the re-join half of an
         * era advance (a Welcome for a group we are already in).
         */
        JOINED_GROUP(MlsResultStatus.SUCCESS),

        /**
         * A proposal is cached by reference and someone must commit it.
         *
         * <p>PENDING, not SUCCESS: a cached proposal BLOCKS application messages until committed
         * (mls-rs's {@code commit_required()} contract), so treating it as done wedges the
         * conversation.
         *
         * <p><b>Producers:</b> inbound control, engine status PROPOSAL — both the honourable kinds
         * (self_remove) and the ones we merely advertise (end_mls, server_remove).
         */
        PROPOSAL_CACHED(MlsResultStatus.PENDING),

        /**
         * A valid control we cannot apply YET — buffer it and retry after the next commit.
         *
         * <p><b>Producers:</b> inbound control, engine status APPLY_FAILED (future epoch). The
         * common cause is ordinary concurrency: a metadata change ships as two messages dispatched
         * concurrently, so the key delivery routinely arrives before the commit it depends on.
         */
        BUFFER_AND_RETRY(MlsResultStatus.PENDING),

        /**
         * Discard this input. It is not actionable and never will be.
         *
         * <p><b>Producers:</b> engine status MALFORMED (not a valid MLSMessage); engine status
         * PAST_EPOCH (a commit already superseded — dropped so it cannot re-spam the flush cap).
         */
        DROP(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * Our KeyPackage pool needs replenishing or re-publishing before this can proceed.
         *
         * <p><b>Producers (three, none of which exists yet — §14.9):</b> pool exhausted after a
         * Welcome consumed the last claimable package; a server {@code MLS_KEYS_NOT_FOUND},
         * which must ALSO reset the key-package status (trigger 5 — its absence is why an empty
         * pool is currently permanent); a changed {@code kdsUrl} (trigger 2).
         */
        KEY_REFRESH_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * The conversation is stranded and needs recovery.
         *
         * <p><b>Producers:</b> the consecutive-defer bound tripping (we missed a commit, and MLS
         * cannot skip one); a divergence detected against a fetched GroupInfo.
         */
        SELF_HEAL_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * The divergence cannot be closed in-era; the group must be rebuilt at a new era.
         *
         * <p><b>Producers:</b> reconcile finding {@code local.era != groupinfo.era}; a self-heal
         * budget charge that still left the group diverged.
         *
         * <p>Costly by construction — an era advance burns a KeyPackage per member — so this arm
         * should stay rare, and §5.2's "no in-era heal" risk is exactly about how often it fires.
         */
        ERA_ADVANCE_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * Delete our local group state. ALWAYS terminal (§10.5).
         *
         * <p>Results from the delete are logged, never propagated: there is nothing left to
         * re-drive, so a retry could only re-delete.
         *
         * <p><b>Producers:</b> an unrecoverable local state (the group exists on the server and we
         * cannot rejoin it); the tail of a downgrade.
         */
        DELETE_LOCAL_GROUP_STATE(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * The in-flight pending operation failed. Terminal, and it POISONS the pass.
         *
         * <p>Per §10.5 this arm re-maps every {@link MlsResultStatus#NO_OP} in the same pass to
         * {@link MlsResultStatus#FAIL_NO_RETRY} — see {@link #poisonsNoOp()}. Without that, a failed
         * pending operation coexisting with an idle group reads as "converged" and the failure is
         * lost.
         *
         * <p><b>Producers (two):</b> the send path; the health handler.
         */
        PENDING_OPERATION_FAILURE(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * MLS is being ended on this conversation, so healing it is not permitted (§21.4-1).
         *
         * <p>The wedged-in-downgrade case. Our guards for this exist today as PURE REFUSALS that
         * record nothing — so the condition is invisible the moment the call returns and is
         * recomputed from the engine on every subsequent trigger. This arm is what carries it out
         * so a state write can be attached.
         *
         * <p><b>Producers:</b> self-heal with a local end_mls present; self-heal where the fetched
         * GroupInfo carries 0xF002; era advance under the same two conditions.
         */
        CANNOT_HEAL_DURING_END_MLS(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * The machine has exhausted what it can do alone, and the remaining choice belongs to a
         * PERSON.
         *
         * <p>Every other terminal arm here says "this cannot be repaired". This one says something
         * different and narrower: <b>it cannot be repaired by us, and the two ways forward differ in
         * what the user gives up.</b> They can wait and retry, keeping confidentiality and keeping a
         * conversation that currently receives nothing; or they can drop the conversation to
         * unencrypted and have it work. That is not a decision a state machine gets to make silently
         * in either direction.
         *
         * <p><b>Why this is not an automatic downgrade.</b> Google Messages' own answer to an uncrossable
         * era gap is to locally downgrade, and we could have copied it. What is established is
         * that Google Messages' kill path EXISTS and what it does — never what INVOKES it, and our own
         * 20-minute observation showed an undelivered message is NOT the trigger. So an automatic
         * downgrade here would be us inventing a condition and then silently spending the user's
         * confidentiality on it. Surfacing the choice costs nothing and assumes nothing.
         *
         * <p><b>Why it is not silence either.</b> Doing nothing is what the conversation already
         * does, and it is the worst outcome available: it receives nothing, permanently, while
         * showing a closed padlock — the appearance of confidentiality over a dead channel.
         *
         * <p><b>Producer:</b> the self-heal budget is exhausted AND the moment the pending queue is
         * waiting for is unreachable ({@link MlsPendingQueue#awaitedMomentIsReachable} false — an era
         * gap, which needs a Welcome only the peer or server can send). Either condition alone is
         * NOT enough: an exhausted budget on a reachable moment is ordinary backoff, and an
         * unreachable moment with budget left has repair attempts still owed to it.
         */
        USER_ACTION_REQUIRED(MlsResultStatus.FAIL_NO_RETRY);

        /** The status this arm carries unless a producer overrides it for a documented reason. */
        public final MlsResultStatus defaultStatus;

        Kind(final MlsResultStatus defaultStatus) { this.defaultStatus = defaultStatus; }
    }

    /**
     * WHEN this action may be attempted again — which is a different question from
     * {@link MlsResultStatus}, and conflating the two is a defect we have shipped before.
     *
     * <p>{@link MlsResultStatus#FAIL_RETRY} means <b>the work is still owed</b>. It does NOT mean
     * re-doing it this instant will answer differently, and {@link MlsDriveLoop} read it as if it
     * did: it re-drove immediately, up to the cap. Device-measured 2026-09-08, the reconcile drive
     * ran ten passes over an unchanged {@code local era=1 epoch=1 · server era=1 epoch=7} — because
     * the arm it kept selecting performs no work at all, so every pass re-read the same two numbers
     * and re-reported the same "retry". Each of those passes cost a {@code GetMlsGroupInfo}, and the
     * tenth arrived as {@code RESOURCE_EXHAUSTED}.
     *
     * <p>So a producer says the second thing here, and the loop stops on anything but
     * {@link #IMMEDIATE} while PRESERVING the status — the caller still sees {@code FAIL_RETRY},
     * still records the work as owed, and still schedules it. What it does not do is spend another
     * look-up on an answer it already has.
     */
    public enum Redrive {
        /**
         * The pass ACTED, or its result may genuinely differ if asked again now. The loop may
         * re-drive. This is the default, and it is the right default: a producer that has not
         * thought about it gets today's behaviour, bounded by the loop's own rules.
         */
        IMMEDIATE,
        /**
         * The pass DIAGNOSED but did not ACT — nothing it did changed anything a second look could
         * see, so asking again inside this drive re-reads identical state and spends a look-up to
         * learn nothing.
         *
         * <p>The canonical producer is a health verdict with no available remedy: reconcile finding
         * us BEHIND cannot replay commits it does not buffer, so it returns {@code FAIL_RETRY}
         * having done nothing. That is worth re-driving from a fresh trigger minutes later, when a
         * commit may have landed; it is worthless microseconds later.
         */
        NOT_IN_THIS_DRIVE,
        /**
         * The pass could not spend its server look-up, or spent it and got nothing readable back.
         * <b>Waiting is the remedy and asking again is the fault.</b>
         *
         * <p>This is the arm the recovery ladder did not have. A failed fetch used to be
         * indistinguishable from "nothing came back", so the ladder escalated — spending an era
         * advance or a rebuild on a fault that only needed a pause. A caller that can schedule
         * should schedule past {@link MlsFetchBudget#MEASURED_THROTTLE_COOLDOWN_MS}; a caller that
         * counts consecutive failures must NOT count this one, because nothing was learned.
         */
        AFTER_A_COOLDOWN
    }

    public final Kind kind;
    public final MlsResultStatus status;
    /** Where the group landed. Never {@code null} — {@link MlsGroupSnapshot#NONE} when unknown. */
    public final MlsGroupSnapshot snapshot;
    /** The decrypted payload for {@link Kind#DELIVER_MESSAGE}; {@code null} otherwise. */
    public final byte[] payload;
    /** Why, in one human-readable clause. Always present — this is what a failure log prints. */
    public final String reason;
    /** When this may be attempted again. Never {@code null}; {@link Redrive#IMMEDIATE} by default. */
    public final Redrive redrive;

    private MlsHostAction(final Kind kind, final MlsResultStatus status,
            final MlsGroupSnapshot snapshot, final byte[] payload, final String reason,
            final Redrive redrive) {
        if (kind == null) throw new IllegalArgumentException("kind");
        this.kind = kind;
        this.status = status == null ? kind.defaultStatus : status;
        this.snapshot = snapshot == null ? MlsGroupSnapshot.NONE : snapshot;
        this.payload = payload;
        this.reason = reason == null ? "" : reason;
        this.redrive = redrive == null ? Redrive.IMMEDIATE : redrive;
    }

    // The factories pass a null status and let the constructor resolve the arm's default AFTER it
    // has null-checked the kind. Reading kind.defaultStatus here instead throws NPE on a null kind,
    // which is a worse report of the same mistake.

    /** The arm at its default status, with no snapshot. */
    public static MlsHostAction of(final Kind kind, final String reason) {
        return new MlsHostAction(kind, null, MlsGroupSnapshot.NONE, null, reason, null);
    }

    /** The arm at its default status, carrying where the group landed. */
    public static MlsHostAction of(final Kind kind, final MlsGroupSnapshot snapshot,
            final String reason) {
        return new MlsHostAction(kind, null, snapshot, null, reason, null);
    }

    /** A decrypted payload to hand up. */
    public static MlsHostAction deliver(final byte[] payload, final MlsGroupSnapshot snapshot,
            final String reason) {
        return new MlsHostAction(Kind.DELIVER_MESSAGE, MlsResultStatus.SUCCESS, snapshot,
                payload, reason, null);
    }

    /**
     * The arm with an OVERRIDDEN status.
     *
     * <p>Deliberately verbose to call, because overriding is the exception. The legitimate uses are
     * §10.5's re-mapping rules and the drive-loop cap forcing
     * {@link MlsResultStatus#FAIL_NO_RETRY}; anything else is probably an arm that does not exist
     * yet.
     */
    public static MlsHostAction withStatus(final Kind kind, final MlsResultStatus status,
            final MlsGroupSnapshot snapshot, final String reason) {
        return new MlsHostAction(kind, status, snapshot, null, reason, null);
    }

    /**
     * The arm with an overridden status AND a {@link Redrive} other than {@link Redrive#IMMEDIATE}.
     *
     * <p>Use it wherever a pass returns a retryable status without having changed anything — see
     * {@link Redrive}. Saying so is the producer's job: the loop has a structural backstop for
     * producers that do not, but the backstop costs one wasted look-up to notice and this does not.
     */
    public static MlsHostAction withRedrive(final Kind kind, final MlsResultStatus status,
            final MlsGroupSnapshot snapshot, final Redrive redrive, final String reason) {
        return new MlsHostAction(kind, status, snapshot, null, reason, redrive);
    }

    /** Shorthand for the terminating arm. */
    public static MlsHostAction none(final String reason) { return of(Kind.NONE, reason); }

    /**
     * Whether this arm forces every {@link MlsResultStatus#NO_OP} in the same pass to
     * {@link MlsResultStatus#FAIL_NO_RETRY} (§10.5).
     */
    public boolean poisonsNoOp() { return kind == Kind.PENDING_OPERATION_FAILURE; }

    /** Apply {@link #poisonsNoOp()} to a sibling action from the same pass. */
    public MlsHostAction poison() {
        if (status != MlsResultStatus.NO_OP) return this;
        // The Redrive is carried through: poisoning re-maps the OUTCOME, and whether the pass
        // changed anything is a fact about what it did, which the re-mapping does not alter.
        return new MlsHostAction(kind, MlsResultStatus.FAIL_NO_RETRY, snapshot, payload,
                reason + " (re-mapped: a pending operation failed in the same pass)", redrive);
    }

    /**
     * Reject an arm a flow does not handle — BY NAME, loudly, per §3.9.
     *
     * <p>The naming matters. "unexpected status 4" sends a reader to the enum; "the inbound flow
     * cannot handle DELETE_LOCAL_GROUP_STATE" sends them to the producer. A silent default arm is
     * how a state machine drifts, and ours currently has one: it logs and returns {@code false}.
     *
     * @throws IllegalStateException always
     */
    public static void rejectUnhandled(final String flow, final MlsHostAction action) {
        throw new IllegalStateException("MLS flow '" + flow + "' cannot handle action "
                + (action == null ? "null" : action.kind.name() + " (status "
                        + action.status.name() + "): " + action.reason));
    }

    @Override public String toString() {
        return "MlsHostAction{" + kind.name() + " " + status.name()
                // Printed only when it is not the default, so an ordinary action reads unchanged and
                // the one that stopped a drive early says so on the line that stopped it.
                + (redrive == Redrive.IMMEDIATE ? "" : " " + redrive.name())
                + (payload == null ? "" : " payload=" + payload.length + "B")
                + (snapshot.hasGroup() || snapshot.isKnown() ? " " + snapshot.describe() : "")
                + (reason.isEmpty() ? "" : " — " + reason) + "}";
    }
}
