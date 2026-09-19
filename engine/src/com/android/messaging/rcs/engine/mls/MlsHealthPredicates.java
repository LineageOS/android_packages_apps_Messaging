/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The health-status membership tests every operational guard uses. They read the persisted status,
 * not the {@code 0xF002} extension: the two disagree in several states, and the status is what
 * suppresses re-encryption while a downgrade is requested but not yet committed. Pure; these are
 * questions about a number. See docs/mls/health-and-recovery.md.
 */
public final class MlsHealthPredicates {

    private MlsHealthPredicates() {}

    /**
     * States {10, 11, 12, 15, 16}. Excludes a requested or in-flight downgrade (8, 9) and includes
     * the two revive states. Behind downgrade entry guard 1 and the mismatched-rcs-group-state
     * absorber.
     */
    public static boolean hasEndMls(final int status) {
        return status == MlsHealthStates.DONEENDMLS
                || status == MlsHealthStates.ONGOINGREVIVEMLS
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE
                || status == MlsHealthStates.CANNOTHEALDURINGENDMLS
                || status == MlsHealthStates.ONGOINGPHOENIXMODE;
    }

    /**
     * States {8, 9, 10, 11, 12, 15, 16, 17}: the hard stops (no new pending operation, no key
     * refresh, no self-heal).
     */
    public static boolean isDowngraded(final int status) {
        return hasEndMls(status)
                || status == MlsHealthStates.ENDMLSREQUESTED
                || status == MlsHealthStates.ONGOINGENDMLS
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;
    }

    /** States {16, 17}: a requested Phoenix blocks a new downgrade too (entry guard 3). */
    public static boolean isPhoenixOngoing(final int status) {
        return status == MlsHealthStates.ONGOINGPHOENIXMODE
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;
    }

    /** States {1, 2, 4, 6, 7, 12}. State 12 is in both this set and {@link #isDowngraded}. */
    public static boolean isHealing(final int status) {
        return status == MlsHealthStates.EPOCHADVANCEMENTREQUESTED
                || status == MlsHealthStates.ONGOINGEPOCHADVANCEMENT
                || status == MlsHealthStates.ONGOINGERAADVANCEMENT
                || status == MlsHealthStates.SELFHEALFAILED
                || status == MlsHealthStates.ERAADVANCEMENTREQUESTED
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE;
    }

    /**
     * States {1, 2, 4, 7, 12, 14, 16, 17}: park inbound before decrypt (RCC.16 §10.8 guard G1).
     * Not {@code !isDowngraded}: 12, 16 and 17 buffer while 8, 9, 10, 11 and 15 do not.
     */
    public static boolean buffersInbound(final int status) {
        return status == MlsHealthStates.EPOCHADVANCEMENTREQUESTED
                || status == MlsHealthStates.ONGOINGEPOCHADVANCEMENT
                || status == MlsHealthStates.ONGOINGERAADVANCEMENT
                || status == MlsHealthStates.ERAADVANCEMENTREQUESTED
                || status == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE
                || status == MlsHealthStates.INITIALIZING
                || status == MlsHealthStates.ONGOINGPHOENIXMODE
                || status == MlsHealthStates.PHOENIXMODEREQUESTED;
    }

    /**
     * The revive precondition, in evaluation order: {@code (extension || status == 15)} then
     * {@code (isDowngraded || status == 11)}. The second disjunct is redundant but kept to match
     * the evaluation order. The only decision that reads the extension, because revive repairs a
     * status and context that disagree. See docs/mls/downgrade.md.
     */
    public static boolean canRevive(final int status, final boolean extensionPresent) {
        final boolean clause1 = extensionPresent || status
                == MlsHealthStates.CANNOTHEALDURINGENDMLS;
        if (!clause1) return false;
        return isDowngraded(status) || status == MlsHealthStates.ONGOINGREVIVEMLS;
    }

    /**
     * Whether a {@link #canRevive} refusal on its first clause also logs the engine-bug diagnostic:
     * true when the status is nevertheless downgraded. A second-clause refusal logs nothing.
     */
    public static boolean reviveRefusalIsZinniaBug(final int status,
            final boolean extensionPresent) {
        final boolean clause1 = extensionPresent || status
                == MlsHealthStates.CANNOTHEALDURINGENDMLS;
        return !clause1 && isDowngraded(status);
    }
}
