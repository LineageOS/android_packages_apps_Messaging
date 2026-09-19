/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * Whether the server applied our commit, and what to do when the outcome cannot say
 * ({@link #SILENT}). A silent commit is kept: keeping one the server refused leaves us ahead, which
 * is rebuilt, while discarding one it applied leaves us behind an epoch we signed, which nothing
 * repairs. See docs/mls/health-and-recovery.md.
 */
public enum MlsCommitApplication {

    APPLIED,

    /** The server spoke and did not take it; roll back. */
    NOT_APPLIED,

    /** The outcome does not say; the caller must not roll back. */
    SILENT;

    public boolean isSilent() {
        return this == SILENT;
    }

    /** Unknown verdicts, and an unauthenticated one, are {@link #SILENT}: the safe direction. */
    public static MlsCommitApplication ofVerdict(final int verdict) {
        switch (verdict) {
            case MlsTransportDisposition.VERDICT_OK:
                return APPLIED;
            case MlsTransportDisposition.VERDICT_ERA_GAP:
            case MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED:
            case MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED:
            case MlsTransportDisposition.VERDICT_REJECTED:
                return NOT_APPLIED;
            case MlsTransportDisposition.VERDICT_NOT_REGISTERED:
            case MlsTransportDisposition.VERDICT_TRANSPORT_FAILED:
            default:
                return SILENT;
        }
    }

    /** What a server era/epoch read says about a commit whose own outcome was {@link #SILENT}. */
    public enum Reconciliation {
        /** A position, not an identity: the epoch authenticator decides. */
        SERVER_AT_OUR_EPOCH,
        /** No read can settle whether our commit is in that chain. */
        SERVER_PAST_OUR_EPOCH,
        /** Behind our post-commit epoch, or in another era. */
        SERVER_LACKS_IT,
        UNREADABLE,
        /** The commit did not move our epoch (a self-leave), so no comparison can answer. */
        INDISTINGUISHABLE
    }

    /** @param serverEraEpoch null when the read failed or the server holds no group */
    public static Reconciliation reconcile(final long[] serverEraEpoch, final long ourEra,
            final long preEpoch, final long postEpoch) {
        if (ourEra < 0L || preEpoch < 0L || postEpoch < 0L) return Reconciliation.UNREADABLE;
        if (postEpoch <= preEpoch) return Reconciliation.INDISTINGUISHABLE;
        if (serverEraEpoch == null || serverEraEpoch.length < 2) return Reconciliation.UNREADABLE;
        // era <= 0 is the provider's "no live era" sentinel.
        if (serverEraEpoch[0] <= 0L) return Reconciliation.UNREADABLE;
        // Eras are crossed by a Welcome, never a commit.
        if (serverEraEpoch[0] != ourEra) return Reconciliation.SERVER_LACKS_IT;
        if (serverEraEpoch[1] < postEpoch) return Reconciliation.SERVER_LACKS_IT;
        return serverEraEpoch[1] == postEpoch
                ? Reconciliation.SERVER_AT_OUR_EPOCH : Reconciliation.SERVER_PAST_OUR_EPOCH;
    }

    public enum Disposition {
        KEEP_AND_REPORT_SUCCESS,
        /** Keep the state but report failure, so callers' conservative arms (RCC.16 §9.5.3) run. */
        KEEP_BUT_REPORT_UNRESOLVED,
        ROLL_BACK
    }

    /** @param identity consulted only for {@link Reconciliation#SERVER_AT_OUR_EPOCH} */
    public static Disposition disposition(final MlsCommitApplication application,
            final Reconciliation reconciliation, final boolean alreadyHoldingOne,
            final MlsWelcomeAdmission.ServerState identity) {
        if (application == APPLIED) return Disposition.KEEP_AND_REPORT_SUCCESS;
        if (application != SILENT) return Disposition.ROLL_BACK;
        switch (reconciliation) {
            case SERVER_PAST_OUR_EPOCH:
                // Unresolved, not success: nothing shows our commit is in the server's chain.
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_AT_OUR_EPOCH:
                // On a mismatch we still keep: below a forked server there is no repair, while
                // keeping reads as diverged and is rebuilt.
                if (identity == MlsWelcomeAdmission.ServerState.MATCHES) {
                    return Disposition.KEEP_AND_REPORT_SUCCESS;
                }
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_LACKS_IT:
                return Disposition.ROLL_BACK;
            default:
                break;
        }
        // Hold only the first unacknowledged commit: an outage leaves us one epoch ahead at most.
        return alreadyHoldingOne
                ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
    }
}
