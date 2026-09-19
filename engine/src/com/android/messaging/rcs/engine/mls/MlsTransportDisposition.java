/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Whether a transport failure is worth trying again. The provider returns a verdict value across
 * Binder; the first host-side wrapper classifies it here and, on connectivity loss, throws
 * {@link ConnectivityLost}, so a mid-call loss is reported and each call site logs distinctly.
 * {@link #RETRYABLE} maps to {@link MlsResultStatus#FAIL_RETRY}.
 */
public enum MlsTransportDisposition {

    /** No failure. */
    OK,

    /**
     * Transient: the provider's connection to the messaging server is down or we are not
     * registered. Not IP reachability; a working data connection with a dead server stream is still
     * retryable.
     */
    RETRYABLE,

    /** The server refused on the merits; retrying gets the same answer. */
    PERMANENT,

    /** The group is not there and must be re-established before anything else can succeed. */
    NOT_FOUND;

    /** Whether a drive loop may re-drive on this. */
    public boolean mayRetry() { return this == RETRYABLE; }

    /** The {@link MlsResultStatus} this disposition maps onto. */
    public MlsResultStatus toResultStatus() {
        switch (this) {
            case OK:        return MlsResultStatus.SUCCESS;
            case RETRYABLE: return MlsResultStatus.FAIL_RETRY;
            case NOT_FOUND:
            case PERMANENT:
            default:        return MlsResultStatus.FAIL_NO_RETRY;
        }
    }

    // The provider's VERDICT_* values on RcsMlsControlResult, duplicated as ints because this
    // module cannot depend on the AIDL parcelable. Part of the versioned provider contract.
    public static final int VERDICT_OK = 0;
    public static final int VERDICT_ERA_GAP = 1;
    public static final int VERDICT_EXTERNAL_COMMIT_REFUSED = 2;
    public static final int VERDICT_GROUP_ID_CHANGED = 3;
    public static final int VERDICT_NOT_REGISTERED = 4;
    public static final int VERDICT_TRANSPORT_FAILED = 5;
    public static final int VERDICT_REJECTED = 6;
    /** The server does not count this line as a member of the group. */
    public static final int VERDICT_NOT_IN_GROUP = 7;

    /**
     * Classify a provider verdict. The divergence verdicts ({@code ERA_GAP},
     * {@code GROUP_ID_CHANGED}) are {@link #OK}: the server answered, and recovery rather than the
     * retry queue handles them. An unrecognised verdict is {@link #RETRYABLE}, since abandoning a
     * conversation is the worse error.
     */
    public static MlsTransportDisposition ofVerdict(final int verdict) {
        switch (verdict) {
            case VERDICT_OK:
            case VERDICT_ERA_GAP:
            case VERDICT_GROUP_ID_CHANGED:
                return OK;
            case VERDICT_NOT_REGISTERED:
            case VERDICT_TRANSPORT_FAILED:
                return RETRYABLE;
            case VERDICT_REJECTED:
            case VERDICT_EXTERNAL_COMMIT_REFUSED:
                return PERMANENT;
            // Permanent for this request: the identical request cannot succeed while the server
            // does not count us as a member. The repair is retiring the stale local membership,
            // which is not a retry.
            case VERDICT_NOT_IN_GROUP:
                return PERMANENT;
            default:
                return RETRYABLE;
        }
    }

    /** Whether a verdict means connectivity was lost, i.e. should raise the typed exception. */
    public static boolean isConnectivityLoss(final int verdict) {
        return verdict == VERDICT_NOT_REGISTERED || verdict == VERDICT_TRANSPORT_FAILED;
    }

    /**
     * The server refused our position rather than evaluating what we sent, so a verdict here says
     * nothing about the artefact (used to release the RCC.16 §9.5.3 once-per-certificate marker).
     * Overlaps {@link #isConnectivityLoss} on {@code VERDICT_NOT_REGISTERED}; not-in-group is not a
     * connectivity loss.
     */
    public static boolean refusedOurPosition(final int verdict) {
        return verdict == VERDICT_NOT_REGISTERED || verdict == VERDICT_NOT_IN_GROUP;
    }

    /** Connectivity was lost during a call. Unchecked so it leaves the drive-loop pass directly. */
    public static final class ConnectivityLost extends RuntimeException {
        /** The provider verdict that produced it. */
        public final int verdict;

        public ConnectivityLost(final String where, final int verdict) {
            super("Connectivity lost during " + where + " (verdict " + verdict + ")");
            this.verdict = verdict;
        }
    }

    /**
     * Throw if {@code verdict} means connectivity was lost.
     *
     * @param where the call site; must be distinct per site so the log lines stay distinguishable
     */
    public static void requireLive(final String where, final int verdict) {
        if (isConnectivityLoss(verdict)) {
            throw new ConnectivityLost(where, verdict);
        }
    }
}
