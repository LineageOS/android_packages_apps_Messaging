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
 * The exhaustive decoder from an engine {@code processEx} status to a host action (rework item
 * 4.1d).
 *
 * <p>These are the status numbers {@code MlsSession.ProcResult} carries. They are sparse and NOT
 * contiguous — 4, 5 and 6 do not exist — which is exactly why an unrecognised value must be loud
 * rather than absorbed: a new status added on the Rust side and not handled here would otherwise
 * take the {@code default:} arm and be silently dropped.
 *
 * <p>Our {@code default:} arm today logs and returns {@code false}, which is indistinguishable from
 * "this control did not apply". §3.13 is explicit that silently ignoring an unrecognised result is
 * how a state machine drifts, so this decoder THROWS.
 *
 * <h2>Throwing is safe here, and that is a deliberate placement decision</h2>
 *
 * <p>The throw happens in a pure decode step, before any state has been touched — so it cannot leave
 * a group half-mutated. The caller catches it at the operation boundary, logs it at SEVERE and fails
 * that one control. What it must never do is catch it and continue as though the control applied.
 */
public final class MlsProcStatus {

    /** Application payload — for a control frame this is the §7.8.1 key delivery. */
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
    /** A commit BEHIND our epoch — already superseded. */
    public static final int PAST_EPOCH = 9;

    /**
     * Map an engine status onto the action the host should take.
     *
     * <p>Note {@link #APP} maps to {@link MlsHostAction.Kind#DELIVER_MESSAGE} even on the control
     * plane: a metadata commit ships the key its {@code 0xF006} commitment commits to as an APP
     * payload riding a control frame. Dropping it — which this code did until it was found on
     * device — leaves a receiver that applied the epoch change but can never open the subject it was
     * just told about.
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

    /** The status name, for logs. Never throws — a log line must not be the thing that fails. */
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
