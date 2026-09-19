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
 * The 32 health-transition edges — §5.2, decoded byte-exactly from Google Messages' {@code Debug} name table.
 *
 * <p>Rework item 3.1. <b>The ordinal and the telemetry value are two different numbers and neither is
 * derivable from the other.</b> That is the entire reason this is an enum with an explicit field
 * rather than a list of names: {@code RequestedPhoenixMode} is ordinal 15 and telemetry 26,
 * {@code RevivedFromEndMls} is ordinal 19 and telemetry 16. Anything that reports
 * {@link Enum#ordinal()} as the metric value produces numbers that look plausible and mean something
 * else — and the only way anyone would find out is by diffing against a Google Messages trace.
 *
 * <p>{@link #INVALID_TRANSITION} is ordinal 31 / telemetry 32 and is <b>never produced by the
 * table</b> (§5.3 trap 3). It exists for exactly one purpose: the escape hatch substitutes it when
 * an illegal transition is allowed through. Since we do not ship the hatch (item 3.6), it should
 * never appear in a log — if it does, something set {@code allowIllegal}.
 *
 * <p>{@code EraAdvancementFailed} is <b>not</b> an edge, despite the symmetry with
 * {@link #EPOCH_ADVANCEMENT_FAILED} making it look like one. §5.2's decoded table has no such
 * variant, and item 3.7 asks for that specifically as a pinned regression assertion.
 */
public enum MlsHealthEdge {
    REQUESTED_SELF_HEAL(0, 1, "RequestedSelfHeal"),
    STARTED_EPOCH_ADVANCEMENT(1, 2, "StartedEpochAdvancement"),
    EPOCH_ADVANCEMENT_SUCCESSFUL(2, 3, "EpochAdvancementSuccessful"),
    EPOCH_ADVANCEMENT_FAILED(3, 4, "EpochAdvancementFailed"),
    STARTED_ERA_ADVANCEMENT(4, 5, "StartedEraAdvancement"),
    ERA_ADVANCEMENT_SUCCESSFUL(5, 6, "EraAdvancementSuccessful"),
    SELF_HEAL_RESTARTED_TO_EPOCH_ADVANCEMENT(6, 7, "SelfHealRestartedToEpochAdvancement"),
    SELF_HEAL_KILLED(7, 8, "SelfHealKilled"),
    HEALED_AFTER_REMOTE_COMMIT(8, 9, "HealedAfterRemoteCommit"),
    REQUESTED_DOWNGRADE_TO_END_MLS(9, 10, "RequestedDowngradeToEndMls"),
    STARTED_DOWNGRADE_TO_END_MLS(10, 11, "StartedDowngradeToEndMls"),
    END_MLS_SUCCEEDED(11, 12, "EndMlsSucceeded"),
    END_MLS_APPLIED_BY_REMOTE_CLIENT(12, 13, "EndMlsAppliedByRemoteClient"),
    END_MLS_CANCELLED_BY_ANOTHER_COMMIT(13, 14, "EndMlsCancelledByAnotherCommit"),
    REVIVING_FROM_END_MLS_WITH_EPOCH_ADVANCEMENT(14, 15,
            "RevivingFromEndMlsWithEpochAdvancement"),
    REQUESTED_PHOENIX_MODE(15, 26, "RequestedPhoenixMode"),
    STARTED_PHOENIX_MODE(16, 27, "StartedPhoenixMode"),
    PHOENIX_MODE_SUCCEEDED(17, 28, "PhoenixModeSucceeded"),
    PHOENIX_MODE_FAILED_SO_DOWNGRADING_LOCALLY(18, 29,
            "PhoenixModeFailedSoDowngradingLocally"),
    REVIVED_FROM_END_MLS(19, 16, "RevivedFromEndMls"),
    STARTED_ERA_ADVANCEMENT_FOR_REVIVAL(20, 17, "StartedEraAdvancementForRevival"),
    REQUESTED_ERA_ADVANCEMENT_FOR_REVIVAL(21, 31, "RequestedEraAdvancementForRevival"),
    REVIVED_IN_NEW_ERA(22, 18, "RevivedInNewEra"),
    REVIVAL_FAILED(23, 19, "RevivalFailed"),
    GROUP_CREATED(24, 20, "GroupCreated"),
    GROUP_INITIALIZING(25, 21, "GroupInitializing"),
    REVIVING_THROUGH_SELF_HEAL_TO_REVIVED_SERVER(26, 22,
            "RevivingThroughSelfHealToRevivedServer"),
    REVIVE_REJECTED_AND_SERVER_ALREADY_REVIVED(27, 23,
            "ReviveRejectedAndServerAlreadyRevived"),
    END_MLS_ON_SERVER_UNSTABLE_END_MLS_LOCALLY(28, 24,
            "EndMlsOnServerUnstableEndMlsLocally"),
    END_MLS_FAILED_TO_SEND_OUT_RETRY_EXCEEDED(29, 25,
            "EndMlsFailedToSendOutRetryExceeded"),
    RESTARTING_PHOENIX_MODE(30, 30, "RestartingPhoenixMode"),
    /** Never produced by the table. Only the escape hatch substitutes it — and we do not ship one. */
    INVALID_TRANSITION(31, 32, "InvalidTransition");

    /** Position in Google Messages' decoded name table. NOT the metric value. */
    public final int ordinalValue;
    /** The value reported to telemetry. NOT the ordinal. */
    public final int telemetryValue;
    /** Google Messages' spelling, and the string {@link MlsHealthStates#edge} returns. */
    public final String wireName;

    MlsHealthEdge(final int ordinalValue, final int telemetryValue, final String wireName) {
        this.ordinalValue = ordinalValue;
        this.telemetryValue = telemetryValue;
        this.wireName = wireName;
    }

    /** The edge with this {@link #wireName}, or {@code null}. */
    public static MlsHealthEdge byWireName(final String name) {
        if (name == null) return null;
        for (final MlsHealthEdge e : values()) {
            if (e.wireName.equals(name)) return e;
        }
        return null;
    }

    /**
     * The edge for a {@code (from, to)} pair, or {@code null} if the pair is illegal.
     *
     * <p>Only {@code (from, to)} determines the edge, which matters for the one ambiguous name:
     * {@code StartedEraAdvancement} is the edge into <em>both</em> {@code OngoingEraAdvancement} and
     * {@code EraAdvancementRequested}, so an implementation that looked up by name would have to
     * guess.
     */
    public static MlsHealthEdge forPair(final int from, final int to) {
        return byWireName(MlsHealthStates.edge(from, to));
    }
}
