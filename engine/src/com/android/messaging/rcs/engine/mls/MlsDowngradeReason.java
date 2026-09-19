/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Why the app is leaving MLS: 21 reasons with seven behavioural columns. Distinct from the engine's
 * {@code EndMlsRequested} health state. Every number is explicit; none is derived from declaration
 * order. The two table invariants ({@code y && x} forbidden; {@code B != 0} implies
 * {@code !y && !z}) are checked over every value at class load. See docs/mls/downgrade.md.
 */
public enum MlsDowngradeReason {

    // name                                        A   v     w      x      y      z      B
    /** This client cannot do MLS. */
    SELF_NOT_MLS_CAPABLE(                           2,  0,  true,  true, false, false, 11),
    /** This client lost MLS. */
    CLIENT_LOST_MLS(                                3,  1,  true,  true, false, false,  5),
    /** {@code "A new user is not MLS capable. Downgrading the group."} */
    NON_MLS_CLIENT_ADDED(                           4,  2,  true,  true, false, false, 15),
    /** The {@code era-advancement-quota-reached} receipt token; the server does not say why. */
    ERA_ADVANCEMENT_QUOTA_REACHED(                  5,  3, false,  true, false, false,  0),
    /** A terminal server failure reason. */
    UNRECOVERABLE_SERVER_FAILURE(                   6,  4, false,  true, false, false,  3),
    /** A receipt arrived unencrypted. */
    PLAINTEXT_DELIVERY_RECEIPT(                     7,  5, false,  true, false, false,  4),
    /**
     * An inbound commit carried {@code end_mls}. Recorded as unexpected ({@code w == false}), so it
     * is eligible for re-upgrade; generates no commit of our own ({@code y == true}).
     */
    RECEIVED_END_MLS_COMMIT(                        8,  6, false, false,  true,  true,  0),
    /** The default. Emits no metric ({@code v == null}). */
    UNKNOWN_DOWNGRADE_REASON(                       1, null, false,  true, false, false,  0),
    /** Manual, from a debug surface. */
    DEBUG_MENU(                                     9,  7, false,  true, false, false, 17),
    /** The maintenance path when an expired participant yields zero key packages. */
    FAILED_TO_GET_KEYPACKAGES_FOR_EXPIRED_MEMBERS( 10,  8,  true,  true, false, false, 10),
    /**
     * Engine-driven: health status {@code DoneEndMls(10)}, and the {@code END_MLS_ONGOING} handler.
     */
    ZINNIA_END_MLS_ONGOING(                        11,  9, false,  true, false,  true,  0),
    /** Engine-driven: the group-info result was null ({@code mls-group-not-found}). */
    SERVER_GROUP_STATE_NOT_FOUND(                  12, 10,  true, false, false,  true,  0),
    /** Encryption terminally refused. */
    COULD_NOT_ENCRYPT_MESSAGE(                     13, 11, false,  true, false, false, 11),
    /** The messaging service asked. */
    TACHYGRAM_REQUESTED_DOWNGRADE(                 14, 12, false,  true, false,  true,  0),
    /** Roster / tree divergence. */
    RCS_GROUP_HAS_MEMBER_NOT_SYNCED_WITH_MLS(      15, 13,  true,  true, false, false,  0),
    /**
     * Phoenix's own local downgrade, raised first by {@code initiatePhoenixMode}: expected
     * ({@code w}, we chose it) and no end_mls commit ({@code y}, the era advance carries it).
     */
    EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE( 16, 14,  true, false,  true, false,  0),
    /** Own leaf certificate expired. */
    SELF_CERTIFICATE_EXPIRED(                      17, 15,  true,  true, false, false, 14),
    /** Engine-driven: health status {@code CannotHealDuringEndMls(15)}, the wedge. */
    CANNOT_HEAL_DURING_END_MLS(                    18, 16, false, false,  true, false,  0),
    /**
     * Engine-driven: health status {@code EndMlsRequested(8)}. {@link #ZINNIA_END_MLS_ONGOING} is
     * the one that answers {@code DoneEndMls}.
     */
    ZINNIA_REQUESTED_END_MLS(                      19, 17,  true, false, false, false,  0),
    /** Capabilities no longer advertise MLS. */
    MLS_NOT_ADVERTISED(                            20, 18,  true,  true, false, false, 14),
    /** The engine asked for group deletion. */
    ZINNIA_DELETING_GROUP(                         21, 19,  true, false, false, false,  0);

    /** {@code A}: the reason number in the MLS context's trace record; not an RCS wire field. */
    public final int wire;
    /** {@code v}: the telemetry bucket, or {@code null} for no metric. */
    public final Integer metric;
    /**
     * {@code w}: expected; {@code false} drives the unexpected-downgrade (re-upgrade) bookkeeping.
     */
    public final boolean expected;
    /** {@code x}: there is an end-MLS commit result worth post-processing. */
    public final boolean postprocessResult;
    /** {@code y}: the engine or a peer already ended MLS, so generate no commit. */
    public final boolean zinniaAlreadyEnded;
    /** {@code z}: keep the app's MLS bit until the commit lands. */
    public final boolean suppressEagerLocalDowngrade;
    /** {@code B}: the reason code for the engine request; 0 means none. */
    public final int zinniaCode;

    MlsDowngradeReason(final int wire, final Integer metric, final boolean expected,
            final boolean postprocessResult, final boolean zinniaAlreadyEnded,
            final boolean suppressEagerLocalDowngrade, final int zinniaCode) {
        this.wire = wire;
        this.metric = metric;
        this.expected = expected;
        this.postprocessResult = postprocessResult;
        this.zinniaAlreadyEnded = zinniaAlreadyEnded;
        this.suppressEagerLocalDowngrade = suppressEagerLocalDowngrade;
        this.zinniaCode = zinniaCode;
    }

    /** Whether a metric is emitted for this reason at all ({@code v != null}). */
    public boolean emitsMetric() { return metric != null; }

    /** The reasons the engine-to-host downgrade funnel accepts; all have {@code B == 0}. */
    public static final Set<MlsDowngradeReason> ENGINE_FUNNEL_WHITELIST =
            Collections.unmodifiableSet(EnumSet.of(
                    SERVER_GROUP_STATE_NOT_FOUND,
                    EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                    CANNOT_HEAL_DURING_END_MLS,
                    ZINNIA_END_MLS_ONGOING,
                    ZINNIA_REQUESTED_END_MLS));

    /**
     * Throws for a reason outside {@link #ENGINE_FUNNEL_WHITELIST}: such a reason on the engine
     * channel is a routing bug, never a downgrade.
     *
     * @throws IllegalStateException for a reason outside the whitelist
     */
    public static void requireEngineFunnelReason(final MlsDowngradeReason reason) {
        if (!ENGINE_FUNNEL_WHITELIST.contains(reason)) {
            throw new IllegalStateException("Unexpected failure reason: " + reason);
        }
    }

    /**
     * The host downgrade an engine health status calls for, or {@code null} for none. Only four
     * statuses map; {@code OngoingPhoenixMode(16)} maps to nothing because Phoenix downgraded the
     * app at request time. {@link #SERVER_GROUP_STATE_NOT_FOUND} is raised elsewhere, with no
     * status.
     */
    public static MlsDowngradeReason forEngineHealthStatus(final int status) {
        switch (status) {
            case MlsHealthStates.ENDMLSREQUESTED:            // 8 
                return ZINNIA_REQUESTED_END_MLS;
            case MlsHealthStates.DONEENDMLS:                 // 10
                return ZINNIA_END_MLS_ONGOING;
            case MlsHealthStates.CANNOTHEALDURINGENDMLS:     // 15
                return CANNOT_HEAL_DURING_END_MLS;
            case MlsHealthStates.PHOENIXMODEREQUESTED:       // 17
                return EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE;
            default:
                return null;
        }
    }

    /**
     * The engine-request reason gate: {@code B} only for an app-originated downgrade, i.e. when the
     * request context has not round-tripped an engine health status ("do not tell the engine what
     * it just told you"); otherwise 0.
     *
     * @param contextCarriesEngineHealthStatus whether an engine health status was copied into the
     *        MLS request context
     * @return the code to place on the request, or 0 for "set no reason"
     */
    public int zinniaReasonFor(final boolean contextCarriesEngineHealthStatus) {
        return (zinniaCode != 0 && !contextCarriesEngineHealthStatus) ? zinniaCode : 0;
    }

    /**
     * The {@code EndMlsRequest.reason} wire value, {@code B - 2}: {@code B} is an enum ordinal
     * where 0 is "no reason" and 1 is the unrecognised value. No reason ships {@code B == 1}.
     *
     * @throws IllegalArgumentException if {@code B == 1}
     * @throws IllegalStateException if {@code B == 0}; consult {@link #zinniaReasonFor} first
     */
    public static int endMlsRequestWireReason(final int zinniaCode) {
        if (zinniaCode == 0) {
            throw new IllegalStateException(
                    "endMlsRequestWireReason called with code 0 — the reason field is not set at all "
                            + "in that case; gate on zinniaReasonFor() first");
        }
        if (zinniaCode == 1) {
            throw new IllegalArgumentException("Can't get the number of an unknown enum value.");
        }
        return zinniaCode - 2;
    }

    /** {@link #endMlsRequestWireReason(int)} for this reason's own code. */
    public int endMlsRequestWireReason() { return endMlsRequestWireReason(zinniaCode); }

    /** Lookup by {@link #wire}, or {@link #UNKNOWN_DOWNGRADE_REASON} if no value carries it. */
    public static MlsDowngradeReason fromWire(final int wire) {
        for (final MlsDowngradeReason r : values()) {
            if (r.wire == wire) return r;
        }
        return UNKNOWN_DOWNGRADE_REASON;
    }

    static {
        // Checked over the whole table at class init: a bad edit fails the first test to load it.
        for (final MlsDowngradeReason r : values()) {
            if (r.zinniaAlreadyEnded && r.postprocessResult) {
                throw new IllegalArgumentException("Failed requirement.");            }
            if (r.zinniaCode != 0 && (r.zinniaAlreadyEnded || r.suppressEagerLocalDowngrade)) {
                throw new IllegalArgumentException("Failed requirement.");
            }
        }
    }
}
