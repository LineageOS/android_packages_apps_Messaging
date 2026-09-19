/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * What the host should do next: the closed action set MLS flows return, each arm carrying a
 * {@link MlsResultStatus}. Unhandled arms are rejected by name. Each {@link Kind} lists the paths
 * that may produce it; when you add a producer, add it to the list, so a re-drive arm cannot become
 * silently unreachable from its failure path.
 *
 * @see MlsDriveLoop for the bounded fixed point that consumes these
 */
public final class MlsHostAction {

    /** The closed set; adding an arm means visiting every match ({@link #rejectUnhandled}). */
    public enum Kind {

        /**
         * Nothing to do; terminates a drive loop, and is how convergence is detected.
         * Producers: an inbound control that changed nothing; a reconcile that found the group
         * healthy; a re-drive pass with no remaining work.
         */
        NONE(MlsResultStatus.NO_OP),

        /**
         * A decrypted application payload is ready to hand up. Producers: app-message decrypt; the
         * control-plane key delivery that accompanies a metadata commit.
         */
        DELIVER_MESSAGE(MlsResultStatus.SUCCESS),

        /**
         * A commit applied and the epoch moved; buffered work may be drainable. Producers: inbound
         * control the engine applied as a commit; our own commit after {@code commitAndSend}.
         */
        EPOCH_ADVANCED(MlsResultStatus.SUCCESS),

        /**
         * We joined from a Welcome. Producers: a plain Welcome; a treeless Welcome spliced with a
         * fetched tree; the re-join half of an era advance.
         */
        JOINED_GROUP(MlsResultStatus.SUCCESS),

        /**
         * A proposal is cached and someone must commit it. Pending, not success: a cached proposal
         * blocks application messages until committed. Producer: inbound control with engine
         * status PROPOSAL.
         */
        PROPOSAL_CACHED(MlsResultStatus.PENDING),

        /**
         * A valid control we cannot apply yet; buffer and retry after the next commit. Producer:
         * inbound control with engine status APPLY_FAILED (future epoch).
         */
        BUFFER_AND_RETRY(MlsResultStatus.PENDING),

        /**
         * Discard this input; it will never be actionable. Producers: an input that is not a
         * valid MLSMessage; PAST_EPOCH (a superseded commit).
         */
        DROP(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * Our KeyPackage pool needs replenishing or re-publishing. Intended producers, none wired
         * yet: pool exhausted by a Welcome; a server {@code MLS_KEYS_NOT_FOUND}, which must also
         * reset the key-package status; a changed KDS URL.
         */
        KEY_REFRESH_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * The conversation is stranded. Producers: the consecutive-defer bound tripping; a
         * divergence found against a fetched GroupInfo.
         */
        SELF_HEAL_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * The divergence cannot be closed within the era. Costly: an era advance spends a
         * KeyPackage per member. Producers: reconcile finding a different era; a self-heal charge
         * that left the group diverged.
         */
        ERA_ADVANCE_REQUIRED(MlsResultStatus.FAIL_RETRY),

        /**
         * Delete our local group state; always terminal, and the delete's results are logged, not
         * propagated. Producers: unrecoverable local state; the tail of a downgrade.
         */
        DELETE_LOCAL_GROUP_STATE(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * The in-flight pending operation failed. Terminal, and it poisons the pass
         * ({@link #poisonsNoOp()}), so a failure beside an idle group does not read as converged.
         * Producers: the send path; the health handler.
         */
        PENDING_OPERATION_FAILURE(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * MLS is being ended here, so healing is not permitted; carries the condition out so a
         * state write can be attached. Producers: self-heal or era advance with a local end_mls, or
         * with 0xF002 in the fetched GroupInfo.
         */
        CANNOT_HEAL_DURING_END_MLS(MlsResultStatus.FAIL_NO_RETRY),

        /**
         * Only the user can choose: keep waiting with confidentiality, or drop the conversation to
         * unencrypted. Neither an automatic downgrade nor silence is acceptable. Producer: the
         * self-heal budget is exhausted and the pending queue's awaited moment is unreachable
         * ({@link MlsPendingQueue#awaitedMomentIsReachable} false); either alone is not enough.
         */
        USER_ACTION_REQUIRED(MlsResultStatus.FAIL_NO_RETRY);

        /** The status this arm carries unless a producer overrides it. */
        public final MlsResultStatus defaultStatus;

        Kind(final MlsResultStatus defaultStatus) { this.defaultStatus = defaultStatus; }
    }

    /**
     * When this action may be attempted again, which {@link MlsResultStatus} does not say:
     * {@code FAIL_RETRY} means the work is owed, not that asking again now will answer differently.
     * The drive loop re-drives only on {@link #IMMEDIATE} and otherwise stops, keeping the status.
     */
    public enum Redrive {
        /** The pass acted, or its result may differ if asked again now. The default. */
        IMMEDIATE,
        /**
         * The pass diagnosed but did not act, so another look in this drive learns nothing; for
         * example reconcile finding us behind with no commits to replay.
         */
        NOT_IN_THIS_DRIVE,
        /**
         * The server look-up was refused or unreadable; waiting is the remedy. Schedule past
         * {@link MlsFetchBudget#MEASURED_THROTTLE_COOLDOWN_MS}, and do not count it as a
         * consecutive failure, since nothing was learned.
         */
        AFTER_A_COOLDOWN
    }

    public final Kind kind;
    public final MlsResultStatus status;
    /** Where the group landed; {@link MlsGroupSnapshot#NONE} when unknown, never {@code null}. */
    public final MlsGroupSnapshot snapshot;
    /** The decrypted payload for {@link Kind#DELIVER_MESSAGE}; {@code null} otherwise. */
    public final byte[] payload;
    /** Why, in one clause; never null. */
    public final String reason;
    /** When this may be attempted again; never {@code null}. */
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

    // Factories pass a null status so the constructor null-checks the kind first.

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
     * The arm with an overridden status: the exception, for the poison re-mapping and the
     * drive-loop cap forcing {@link MlsResultStatus#FAIL_NO_RETRY}.
     */
    public static MlsHostAction withStatus(final Kind kind, final MlsResultStatus status,
            final MlsGroupSnapshot snapshot, final String reason) {
        return new MlsHostAction(kind, status, snapshot, null, reason, null);
    }

    /**
     * The arm with an overridden status and a non-immediate {@link Redrive}; use it wherever a
     * pass returns a retryable status without having changed anything.
     */
    public static MlsHostAction withRedrive(final Kind kind, final MlsResultStatus status,
            final MlsGroupSnapshot snapshot, final Redrive redrive, final String reason) {
        return new MlsHostAction(kind, status, snapshot, null, reason, redrive);
    }

    /** Shorthand for the terminating arm. */
    public static MlsHostAction none(final String reason) { return of(Kind.NONE, reason); }

    /** Whether this arm forces every {@code NO_OP} in the same pass to {@code FAIL_NO_RETRY}. */
    public boolean poisonsNoOp() { return kind == Kind.PENDING_OPERATION_FAILURE; }

    /** Apply {@link #poisonsNoOp()} to a sibling action from the same pass. */
    public MlsHostAction poison() {
        if (status != MlsResultStatus.NO_OP) return this;
        // Redrive describes what the pass did, which re-mapping does not change.
        return new MlsHostAction(kind, MlsResultStatus.FAIL_NO_RETRY, snapshot, payload,
                reason + " (re-mapped: a pending operation failed in the same pass)", redrive);
    }

    /**
     * Rejects an arm a flow does not handle, naming both, so the reader is sent to the producer.
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
                // Only when not the default, so the action that stopped a drive early says so.
                + (redrive == Redrive.IMMEDIATE ? "" : " " + redrive.name())
                + (payload == null ? "" : " payload=" + payload.length + "B")
                + (snapshot.hasGroup() || snapshot.isKnown() ? " " + snapshot.describe() : "")
                + (reason.isEmpty() ? "" : " — " + reason) + "}";
    }
}
