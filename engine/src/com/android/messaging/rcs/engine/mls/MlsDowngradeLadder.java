/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The downgrade entry guard ladder: three guards in a fixed order, where a state tripping several
 * reports the earliest ("already done" means stop, "already pending" means retry later). The
 * pending-registry rule "downgraded, not starting a new pending operation" is not a fourth guard: a
 * downgrade is itself a new pending operation. See docs/mls/downgrade.md.
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

    /** Guard 1's text, byte for byte as other clients log it. */
    public static final String ALREADY_DONE_LINE = "MLS is already done. Returning no-op.";

    /** Guard 2's format; takes the group id. */
    public static String alreadyPendingLine(final String groupId) {
        return "End-mls commit or phoenix downgrade already pending for group: " + groupId;
    }

    /** Guard 3's format; takes the group id. */
    public static String phoenixOngoingLine(final String groupId) {
        return "Phoenix mode is ongoing for group: " + groupId
                + ". No need to start a new downgrade.";
    }

    /**
     * Is the pending operation the same one the caller is now retrying? Matched on kind and
     * context, the only things caller and persisted slot share across a restart. A null context
     * never matches.
     */
    static boolean isSameOperationRetrying(final MlsPendingOperation pending,
            final String requestContext) {
        if (pending == null || requestContext == null || pending.context == null) return false;
        // Only the operation's own kind can resume it; a phoenix must not adopt an end_mls slot.
        if (pending.kind != MlsPendingOperation.Kind.END_MLS) return false;
        if (!requestContext.equals(pending.context)) return false;
        // Bounded by the retry policy's per-item cap.
        return !MlsRetryPolicy.attemptsExhausted(pending.attemptCount);
    }

    /** A caller that names no context can never resume a slot. */
    public static Verdict evaluate(final int status, final MlsPendingOperation pending) {
        return evaluate(status, pending, /*requestContext=*/ null);
    }

    /**
     * Run the ladder. Pure: decides, does not act or log.
     *
     * @param status  the persisted health status (a wire number)
     * @param pending the single in-flight operation, or {@code null}
     * @param requestContext the caller's operation context, or {@code null}
     */
    public static Verdict evaluate(final int status, final MlsPendingOperation pending,
            final String requestContext) {
        // Guard 1 tests the status, not the extension: 8 and 9 fall through to guard 2.
        if (MlsHealthPredicates.hasEndMls(status)) {
            return Verdict.ALREADY_DONE;
        }
        // Guard 2. END_MLS and PHOENIX_MODE stand for the two operations the guard's message names.
        if (pending != null
                && (pending.kind == MlsPendingOperation.Kind.END_MLS
                    || pending.kind == MlsPendingOperation.Kind.PHOENIX_MODE)) {
            // Except the same operation retrying: a failed end_mls keeps its slot for another
            // attempt, and refusing that attempt would make the conversation permanently
            // un-downgradable.
            if (!isSameOperationRetrying(pending, requestContext)) {
                return Verdict.ALREADY_PENDING;
            }
        }
        // Guard 3 covers requested as well as ongoing (16 and 17).
        if (MlsHealthPredicates.isPhoenixOngoing(status)) {
            return Verdict.PHOENIX_ONGOING;
        }
        return Verdict.PROCEED;
    }

    /** The refusal line for a verdict, or {@code null} for {@link Verdict#PROCEED}. */
    public static String refusalLine(final Verdict v, final String groupId) {
        switch (v) {
            case ALREADY_DONE:    return ALREADY_DONE_LINE;
            case ALREADY_PENDING: return alreadyPendingLine(groupId);
            case PHOENIX_ONGOING: return phoenixOngoingLine(groupId);
            default:              return null;
        }
    }

    /**
     * The engine-to-host downgrade funnel, run after every real health move. Only four statuses map
     * to a reason ({@link MlsDowngradeReason#forEngineHealthStatus}); a reason outside the engine
     * whitelist throws before anything else, because it is a routing bug.
     */
    public static void downgradeFromEngineStatus(final MlsShellPort shell, final MlsLogSink log,
            final String key, final int status) {
        final MlsDowngradeReason reason = MlsDowngradeReason.forEngineHealthStatus(status);
        if (reason == null) return;              // this status calls for no host downgrade
        // First, and throwing.
        MlsDowngradeReason.requireEngineFunnelReason(reason);
        if (status == MlsHealthStates.CANNOTHEALDURINGENDMLS) {
            // For the wedge the app always downgrades locally: leaving it offering encryption the
            // engine has given up on would be a split brain.
            log.w("MlsDowngradeLadder: " + MlsConversationKey.forLog(key) + " is WEDGED in end-mls "
                    + "(CannotHealDuringEndMls) — downgrading locally. Some peers gate this behind "
                    + "a server flag and do nothing "
                    + "when it is off; we always downgrade, because the alternative is a split-brain "
                    + "where the app offers encryption the engine has given up on.");
        }
        shell.downgradeLocally(key, reason, MlsDowngradeReason.eagerFor(reason));
    }
}
