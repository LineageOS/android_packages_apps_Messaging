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
 * The five-value outcome axis every MLS host action carries (rework item 4.4, §3.10).
 *
 * <p>This is the axis our code did not have. Today a recovery path returns a bare {@code boolean}
 * ({@code reconcile()} → "healthy or repaired") and the send path returns a three-value
 * {@code SendOutcome} with no status axis at all, so a caller cannot distinguish four situations
 * that demand four different responses:
 *
 * <ul>
 *   <li>nothing to do — {@link #NO_OP}</li>
 *   <li>in progress, ask again — {@link #PENDING}</li>
 *   <li>done — {@link #SUCCESS}</li>
 *   <li>failed, try again — {@link #FAIL_RETRY}</li>
 *   <li>failed, stop — {@link #FAIL_NO_RETRY}</li>
 * </ul>
 *
 * <p>That distinction is not decoration: it is precisely what {@link MlsDriveLoop} needs in order to
 * terminate. A loop that cannot tell {@link #NO_OP} from {@link #PENDING} either stops while an
 * advancement is still in flight or spins forever on one that has finished.
 *
 * <h2>NO_OP is a VALUE, not an absence</h2>
 *
 * <p>The single most important rule here, and the one we currently break everywhere. Convergence
 * comes from a scheduled re-drive finding {@link #NO_OP} (§8.6, §19.3-29) — so if "nothing to do" is
 * spelled as an empty list, a {@code null}, or a {@code false}, the drain cannot tell <em>done</em>
 * from <em>broken</em>. Our FFI returns {@code NULL_BYTES} for both, and
 * {@code MlsProviderTransport}'s unknown-status arm returns {@code false} for both. Both are the
 * same bug.
 *
 * <p><b>Corollary, and the direction of the safe error:</b> anything unknown or not-yet-known must
 * report {@link #PENDING}, never {@link #NO_OP}. {@link #PENDING} costs one extra scheduled pass;
 * {@link #NO_OP} declares an in-flight operation finished and drops it. Only genuinely-nothing-to-do
 * earns {@link #NO_OP}.
 *
 * <h2>The wire numbers are not the ordinals</h2>
 *
 * <p>§3.10 flags an ordinal-vs-wire trap in the health-status enum this feeds (ordinals 3/4/5 are
 * wire 7/4/6). This enum's own numbering happens to be dense from zero, but {@link #wire} is
 * declared explicitly anyway so that no later reader assumes {@code ordinal()} is the transmitted
 * value — that assumption is what the trap punishes, and {@link MlsHealthEdge} already carries the
 * same separation for the same reason.
 */
public enum MlsResultStatus {

    /**
     * Genuinely nothing to do. The only status that legitimately STOPS a drive loop without an
     * effect having occurred.
     */
    NO_OP(0),

    /**
     * Work is in flight; the answer is not known yet. The loop must NOT conclude anything from this.
     *
     * <p>Also the mandated default for any status we do not recognise — see the class doc.
     */
    PENDING(1),

    /** The operation completed and its effect is applied. */
    SUCCESS(2),

    /** Failed, but the failure is transient — the loop may re-drive it. */
    FAIL_RETRY(3),

    /**
     * Failed terminally. The loop must stop and must NOT re-drive.
     *
     * <p>Three producers make this unconditional regardless of what the underlying call returned:
     * the iteration cap in {@link MlsDriveLoop}, a local-group-state delete, and a pending-operation
     * failure (§10.5).
     */
    FAIL_NO_RETRY(4);

    /** The transmitted number. Explicit — never assume it equals {@link #ordinal()}. */
    public final int wire;

    MlsResultStatus(final int wire) { this.wire = wire; }

    /**
     * Whether a drive loop should stop on this status.
     *
     * <p>{@link #PENDING} does NOT stop the loop: an in-flight operation is exactly the case a
     * re-drive exists to follow up on.
     */
    public boolean stopsLoop() {
        return this == NO_OP || this == SUCCESS || this == FAIL_NO_RETRY;
    }

    /** Whether a re-drive is permitted. */
    public boolean mayRetry() {
        return this == PENDING || this == FAIL_RETRY;
    }

    /** Whether this status reports a failure of any kind. */
    public boolean isFailure() {
        return this == FAIL_RETRY || this == FAIL_NO_RETRY;
    }

    /**
     * Decode a wire number.
     *
     * @return the matching status, or {@link #PENDING} for anything unrecognised — the safe
     *         direction per §3.10, and deliberately NOT {@code null} or {@link #NO_OP}
     */
    public static MlsResultStatus fromWire(final int wire) {
        for (final MlsResultStatus s : values()) {
            if (s.wire == wire) return s;
        }
        return PENDING;
    }
}
