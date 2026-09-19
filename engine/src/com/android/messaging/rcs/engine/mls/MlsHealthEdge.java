/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The 32 health-transition edges, with the names other clients use. {@link #ordinalValue} and
 * {@link #telemetryValue} are independent numbers; telemetry must report the latter, never
 * {@link Enum#ordinal()}. There is no {@code EraAdvancementFailed} edge. See
 * docs/mls/health-and-recovery.md.
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
    /** Never produced by the table; only an allowed illegal transition, which is compiled off. */
    INVALID_TRANSITION(31, 32, "InvalidTransition");

    /** Position in the edge name table; not the metric value. */
    public final int ordinalValue;
    /** The value reported to telemetry; not the ordinal. */
    public final int telemetryValue;
    /** The edge's name, and the string {@link MlsHealthStates#edge} returns. */
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
     * The edge for a {@code (from, to)} pair, or {@code null} if illegal. Look edges up by pair:
     * {@code StartedEraAdvancement} leads into two different states.
     */
    public static MlsHealthEdge forPair(final int from, final int to) {
        return byWireName(MlsHealthStates.edge(from, to));
    }
}
