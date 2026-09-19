/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Exhaustive decoder from an engine {@code processEx} status ({@code MlsSession.ProcResult}) to a
 * host action. The values are sparse (4-6 do not exist) and an unknown one throws, so a status
 * added on the Rust side cannot fall silently into a default. The throw happens before any state is
 * touched; the caller fails that one control and must not continue as though it applied.
 */
public final class MlsProcStatus {

    /** Application payload; on a control frame, the RCC.16 §7.8.1 key delivery. */
    public static final int APP = 0;
    /** A commit that applied; the epoch advanced. */
    public static final int COMMIT = 1;
    /** A proposal, cached by reference. Someone must commit it. */
    public static final int PROPOSAL = 2;
    /** Parsed, but none of the above. No state change. */
    public static final int OTHER = 3;
    /** {@code from_bytes} failed — not a valid MLSMessage at all. */
    public static final int MALFORMED = 7;
    /** Valid, but for an epoch we have not reached yet. Buffer and retry. */
    public static final int APPLY_FAILED = 8;
    /** A commit behind our epoch, already superseded. */
    public static final int PAST_EPOCH = 9;

    /**
     * Map an engine status onto the action the host should take. {@link #APP} delivers even on the
     * control plane: a metadata commit carries the key its {@code 0xF006} commitment names as an
     * APP payload.
     *
     * @throws IllegalStateException if {@code status} is not one of the seven known values
     */
    public static MlsHostAction.Kind toActionKind(final int status) {
        switch (status) {
            case APP:          return MlsHostAction.Kind.DELIVER_MESSAGE;
            case COMMIT:       return MlsHostAction.Kind.EPOCH_ADVANCED;
            case PROPOSAL:     return MlsHostAction.Kind.PROPOSAL_CACHED;
            case OTHER:        return MlsHostAction.Kind.NONE;
            case MALFORMED:    return MlsHostAction.Kind.DROP;
            case APPLY_FAILED: return MlsHostAction.Kind.BUFFER_AND_RETRY;
            case PAST_EPOCH:   return MlsHostAction.Kind.DROP;
            default:
                throw new IllegalStateException("MLS engine returned an UNKNOWN processEx status "
                        + status + " — the host has no arm for it. Known: " + APP + " APP, "
                        + COMMIT + " COMMIT, " + PROPOSAL + " PROPOSAL, " + OTHER + " OTHER, "
                        + MALFORMED + " MALFORMED, " + APPLY_FAILED + " APPLY_FAILED, "
                        + PAST_EPOCH + " PAST_EPOCH. Add the arm rather than widening a default.");
        }
    }

    /** Whether {@code status} is one this host knows how to act on. */
    public static boolean isKnown(final int status) {
        switch (status) {
            case APP: case COMMIT: case PROPOSAL: case OTHER:
            case MALFORMED: case APPLY_FAILED: case PAST_EPOCH:
                return true;
            default:
                return false;
        }
    }

    /** The status name, for logs. Never throws. */
    public static String nameOf(final int status) {
        switch (status) {
            case APP:          return "APP";
            case COMMIT:       return "COMMIT";
            case PROPOSAL:     return "PROPOSAL";
            case OTHER:        return "OTHER";
            case MALFORMED:    return "MALFORMED";
            case APPLY_FAILED: return "APPLY_FAILED";
            case PAST_EPOCH:   return "PAST_EPOCH";
            default:           return "UNKNOWN(" + status + ")";
        }
    }

    private MlsProcStatus() {}
}
