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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The MLS conversation health state machine — <b>which transitions are legal, and nothing else</b>.
 *
 * <p>This is the first piece of the state-machine rework, and it is deliberately the smallest one:
 * pure Java, no Android, no JNI, no I/O, so the whole legality question is answerable on the host.
 * The 18 in-memory maps this rework replaces did not fail because they held the wrong values — they
 * failed because nothing said which sequences of values were POSSIBLE, so every reader invented its
 * own answer. That question now has exactly one home.
 *
 * <p><b>Where this comes from.</b> 16 states and <b>127 legal pairs</b>, transcribed from Google
 * Messages' own adjacency table (design doc §5.2/§5.3).
 * Generated rather than hand-typed, and the extraction is self-checking: every FROM block in the
 * source declares its own out-degree, and all 16 declared counts match what was parsed. Anything not
 * listed here is ILLEGAL — that is the point of the table.
 *
 * <p><b>State numbering is sparse on purpose:</b> the values run 0..17 with <b>3 and 5 unused</b>.
 * They are gaps in the wire enum, not states we failed to transcribe, and a decoder must not
 * renumber them.
 */
public final class MlsHealthStates {

    private MlsHealthStates() {}

    // ---- states (wire values; 3 and 5 are unused) ----
    public static final int UNKNOWN = 0;
    public static final int EPOCHADVANCEMENTREQUESTED = 1;
    public static final int ONGOINGEPOCHADVANCEMENT = 2;
    public static final int ONGOINGERAADVANCEMENT = 4;
    public static final int SELFHEALFAILED = 6;
    public static final int ERAADVANCEMENTREQUESTED = 7;
    public static final int ENDMLSREQUESTED = 8;
    public static final int ONGOINGENDMLS = 9;
    public static final int DONEENDMLS = 10;
    public static final int ONGOINGREVIVEMLS = 11;
    public static final int ONGOINGERAADVANCEMENTFORREVIVE = 12;
    public static final int HEALTHY = 13;
    public static final int INITIALIZING = 14;
    public static final int CANNOTHEALDURINGENDMLS = 15;
    public static final int ONGOINGPHOENIXMODE = 16;
    public static final int PHOENIXMODEREQUESTED = 17;

    /** The number of wire slots, including the two unused ones. Loops run 0..STATE_SLOTS-1. */
    public static final int STATE_SLOTS = 18;

    /** True iff {@code v} is a state the wire enum actually defines (3 and 5 are not). */
    public static boolean isState(final int v) {
        return NAMES.containsKey(v);
    }

    /** The state's name, or null if {@code v} is not a defined state. */
    public static String name(final int v) {
        return NAMES.get(v);
    }

    /**
     * The edge that takes {@code from} to {@code to}, or null if that transition is ILLEGAL.
     *
     * <p>A null return is the whole contract: it means the engine has no named transition for that
     * pair, and a caller that performs it anyway has produced a state no Google Messages peer can have
     * reached. Callers should treat null as a bug in themselves, not as a condition to route around.
     */
    public static String edge(final int from, final int to) {
        return EDGES.get(key(from, to));
    }

    /** Whether {@code from -> to} is a legal transition at all. */
    public static boolean isLegal(final int from, final int to) {
        return EDGES.containsKey(key(from, to));
    }

    /**
     * Whether the transition exists in the table but is UNREACHABLE in practice.
     *
     * <p>There is exactly one: {@code OngoingEraAdvancement -> OngoingEraAdvancement}
     * ({@code StartedEraAdvancement}), a self-loop the engine can encode but never takes (§5.5).
     * Kept rather than dropped, because a transcription that silently omits a row cannot be checked
     * against the source's own out-degree counts.
     */
    public static boolean isUnreachable(final int from, final int to) {
        return UNREACHABLE.contains(key(from, to));
    }

    /** Every legal {@code (from, to)} pair, as {@code from * STATE_SLOTS + to}. */
    public static java.util.Set<Integer> legalPairs() {
        return Collections.unmodifiableSet(EDGES.keySet());
    }

    private static int key(final int from, final int to) { return from * STATE_SLOTS + to; }

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<Integer, String> EDGES = new HashMap<>();
    private static final java.util.Set<Integer> UNREACHABLE = new java.util.HashSet<>();

    private static void e(final int from, final int to, final String edge) { e(from, to, edge, false); }

    private static void e(final int from, final int to, final String edge, final boolean unreachable) {
        EDGES.put(key(from, to), edge);
        if (unreachable) UNREACHABLE.add(key(from, to));
    }

    static {
        NAMES.put(0, "Unknown");
        NAMES.put(1, "EpochAdvancementRequested");
        NAMES.put(2, "OngoingEpochAdvancement");
        NAMES.put(4, "OngoingEraAdvancement");
        NAMES.put(6, "SelfHealFailed");
        NAMES.put(7, "EraAdvancementRequested");
        NAMES.put(8, "EndMlsRequested");
        NAMES.put(9, "OngoingEndMls");
        NAMES.put(10, "DoneEndMls");
        NAMES.put(11, "OngoingReviveMls");
        NAMES.put(12, "OngoingEraAdvancementForRevive");
        NAMES.put(13, "Healthy");
        NAMES.put(14, "Initializing");
        NAMES.put(15, "CannotHealDuringEndMls");
        NAMES.put(16, "OngoingPhoenixMode");
        NAMES.put(17, "PhoenixModeRequested");

        e(UNKNOWN, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(UNKNOWN, ONGOINGEPOCHADVANCEMENT, "StartedEpochAdvancement");
        e(UNKNOWN, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(UNKNOWN, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(UNKNOWN, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(UNKNOWN, HEALTHY, "GroupCreated");
        e(UNKNOWN, INITIALIZING, "GroupInitializing");
        e(UNKNOWN, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(UNKNOWN, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(EPOCHADVANCEMENTREQUESTED, ONGOINGEPOCHADVANCEMENT, "StartedEpochAdvancement");
        e(EPOCHADVANCEMENTREQUESTED, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(EPOCHADVANCEMENTREQUESTED, SELFHEALFAILED, "SelfHealKilled");
        e(EPOCHADVANCEMENTREQUESTED, ERAADVANCEMENTREQUESTED, "EpochAdvancementFailed");
        e(EPOCHADVANCEMENTREQUESTED, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(EPOCHADVANCEMENTREQUESTED, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(EPOCHADVANCEMENTREQUESTED, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(EPOCHADVANCEMENTREQUESTED, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(EPOCHADVANCEMENTREQUESTED, HEALTHY, "HealedAfterRemoteCommit");
        e(EPOCHADVANCEMENTREQUESTED, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(EPOCHADVANCEMENTREQUESTED, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(ONGOINGEPOCHADVANCEMENT, EPOCHADVANCEMENTREQUESTED, "SelfHealRestartedToEpochAdvancement");
        e(ONGOINGEPOCHADVANCEMENT, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(ONGOINGEPOCHADVANCEMENT, SELFHEALFAILED, "SelfHealKilled");
        e(ONGOINGEPOCHADVANCEMENT, ERAADVANCEMENTREQUESTED, "EpochAdvancementFailed");
        e(ONGOINGEPOCHADVANCEMENT, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(ONGOINGEPOCHADVANCEMENT, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(ONGOINGEPOCHADVANCEMENT, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(ONGOINGEPOCHADVANCEMENT, HEALTHY, "EpochAdvancementSuccessful");
        e(ONGOINGEPOCHADVANCEMENT, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(ONGOINGEPOCHADVANCEMENT, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(ONGOINGERAADVANCEMENT, EPOCHADVANCEMENTREQUESTED, "SelfHealRestartedToEpochAdvancement");
        e(ONGOINGERAADVANCEMENT, ONGOINGERAADVANCEMENT, "StartedEraAdvancement", true);
        e(ONGOINGERAADVANCEMENT, SELFHEALFAILED, "SelfHealKilled");
        e(ONGOINGERAADVANCEMENT, ERAADVANCEMENTREQUESTED, "StartedEraAdvancement");
        e(ONGOINGERAADVANCEMENT, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(ONGOINGERAADVANCEMENT, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(ONGOINGERAADVANCEMENT, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(ONGOINGERAADVANCEMENT, HEALTHY, "EraAdvancementSuccessful");
        e(ONGOINGERAADVANCEMENT, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(SELFHEALFAILED, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(SELFHEALFAILED, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(SELFHEALFAILED, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(SELFHEALFAILED, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(SELFHEALFAILED, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(SELFHEALFAILED, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(SELFHEALFAILED, HEALTHY, "HealedAfterRemoteCommit");
        e(SELFHEALFAILED, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(SELFHEALFAILED, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(ERAADVANCEMENTREQUESTED, EPOCHADVANCEMENTREQUESTED, "SelfHealRestartedToEpochAdvancement");
        e(ERAADVANCEMENTREQUESTED, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(ERAADVANCEMENTREQUESTED, SELFHEALFAILED, "SelfHealKilled");
        e(ERAADVANCEMENTREQUESTED, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(ERAADVANCEMENTREQUESTED, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(ERAADVANCEMENTREQUESTED, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(ERAADVANCEMENTREQUESTED, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(ERAADVANCEMENTREQUESTED, HEALTHY, "HealedAfterRemoteCommit");
        e(ERAADVANCEMENTREQUESTED, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(ENDMLSREQUESTED, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(ENDMLSREQUESTED, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(ENDMLSREQUESTED, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(ENDMLSREQUESTED, HEALTHY, "EndMlsCancelledByAnotherCommit");
        e(ENDMLSREQUESTED, CANNOTHEALDURINGENDMLS, "EndMlsFailedToSendOutRetryExceeded");
        e(ENDMLSREQUESTED, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(ONGOINGENDMLS, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(ONGOINGENDMLS, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(ONGOINGENDMLS, DONEENDMLS, "EndMlsSucceeded");
        e(ONGOINGENDMLS, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(ONGOINGENDMLS, HEALTHY, "EndMlsCancelledByAnotherCommit");
        e(ONGOINGENDMLS, CANNOTHEALDURINGENDMLS, "EndMlsFailedToSendOutRetryExceeded");
        e(ONGOINGENDMLS, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(DONEENDMLS, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(DONEENDMLS, ONGOINGEPOCHADVANCEMENT, "RevivingThroughSelfHealToRevivedServer");
        e(DONEENDMLS, ONGOINGREVIVEMLS, "RevivingFromEndMlsWithEpochAdvancement");
        e(DONEENDMLS, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(DONEENDMLS, HEALTHY, "HealedAfterRemoteCommit");
        e(DONEENDMLS, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(ONGOINGREVIVEMLS, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(ONGOINGREVIVEMLS, ONGOINGEPOCHADVANCEMENT, "ReviveRejectedAndServerAlreadyRevived");
        e(ONGOINGREVIVEMLS, ERAADVANCEMENTREQUESTED, "RequestedEraAdvancementForRevival");
        e(ONGOINGREVIVEMLS, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(ONGOINGREVIVEMLS, HEALTHY, "RevivedFromEndMls");
        e(ONGOINGREVIVEMLS, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(ONGOINGERAADVANCEMENTFORREVIVE, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(ONGOINGERAADVANCEMENTFORREVIVE, ONGOINGEPOCHADVANCEMENT, "RevivingThroughSelfHealToRevivedServer");
        e(ONGOINGERAADVANCEMENTFORREVIVE, ERAADVANCEMENTREQUESTED, "StartedEraAdvancement");
        e(ONGOINGERAADVANCEMENTFORREVIVE, DONEENDMLS, "RevivalFailed");
        e(ONGOINGERAADVANCEMENTFORREVIVE, HEALTHY, "RevivedInNewEra");
        e(ONGOINGERAADVANCEMENTFORREVIVE, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(HEALTHY, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(HEALTHY, ONGOINGEPOCHADVANCEMENT, "StartedEpochAdvancement");
        e(HEALTHY, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(HEALTHY, ERAADVANCEMENTREQUESTED, "StartedEraAdvancement");
        e(HEALTHY, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(HEALTHY, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(HEALTHY, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(HEALTHY, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(HEALTHY, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(HEALTHY, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(INITIALIZING, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(INITIALIZING, ONGOINGEPOCHADVANCEMENT, "StartedEpochAdvancement");
        e(INITIALIZING, ONGOINGERAADVANCEMENT, "StartedEraAdvancement");
        e(INITIALIZING, ERAADVANCEMENTREQUESTED, "StartedEraAdvancement");
        e(INITIALIZING, ENDMLSREQUESTED, "RequestedDowngradeToEndMls");
        e(INITIALIZING, ONGOINGENDMLS, "StartedDowngradeToEndMls");
        e(INITIALIZING, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(INITIALIZING, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(INITIALIZING, HEALTHY, "GroupCreated");
        e(INITIALIZING, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(INITIALIZING, PHOENIXMODEREQUESTED, "RequestedPhoenixMode");
        e(CANNOTHEALDURINGENDMLS, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(CANNOTHEALDURINGENDMLS, ONGOINGEPOCHADVANCEMENT, "RevivingThroughSelfHealToRevivedServer");
        e(CANNOTHEALDURINGENDMLS, SELFHEALFAILED, "SelfHealKilled");
        e(CANNOTHEALDURINGENDMLS, ERAADVANCEMENTREQUESTED, "StartedEraAdvancement");
        e(CANNOTHEALDURINGENDMLS, DONEENDMLS, "EndMlsSucceeded");
        e(CANNOTHEALDURINGENDMLS, ONGOINGERAADVANCEMENTFORREVIVE, "StartedEraAdvancementForRevival");
        e(CANNOTHEALDURINGENDMLS, HEALTHY, "HealedAfterRemoteCommit");
        e(ONGOINGPHOENIXMODE, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(ONGOINGPHOENIXMODE, ONGOINGEPOCHADVANCEMENT, "RevivingThroughSelfHealToRevivedServer");
        e(ONGOINGPHOENIXMODE, DONEENDMLS, "PhoenixModeSucceeded");
        e(ONGOINGPHOENIXMODE, HEALTHY, "RevivedInNewEra");
        e(ONGOINGPHOENIXMODE, CANNOTHEALDURINGENDMLS, "PhoenixModeFailedSoDowngradingLocally");
        e(ONGOINGPHOENIXMODE, PHOENIXMODEREQUESTED, "RestartingPhoenixMode");
        e(PHOENIXMODEREQUESTED, EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal");
        e(PHOENIXMODEREQUESTED, DONEENDMLS, "EndMlsAppliedByRemoteClient");
        e(PHOENIXMODEREQUESTED, HEALTHY, "HealedAfterRemoteCommit");
        e(PHOENIXMODEREQUESTED, CANNOTHEALDURINGENDMLS, "EndMlsOnServerUnstableEndMlsLocally");
        e(PHOENIXMODEREQUESTED, ONGOINGPHOENIXMODE, "StartedPhoenixMode");
    }
}
