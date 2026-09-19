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
import java.util.EnumSet;
import java.util.Set;

/**
 * The host-side downgrade-reason vocabulary — Google Messages' own, 21 values with <b>seven</b>
 * payload columns. Rework items {@code 11.2} and {@code 11.2a}.
 *
 * <h2>Two vocabularies that must not be merged</h2>
 *
 * <p>§9.7a opens by separating these, and collapsing them is the first mistake available here:
 *
 * <ul>
 *   <li><b>The engine-side end-MLS request</b> — {@code EndMlsRequested(8)}, raised from exactly two
 *       sites, both meaning <i>a pending operation has permanently failed and the remedy is to leave
 *       MLS</i>. That is a STATE, and it lives in {@link MlsHealthStates}.</li>
 *   <li><b>The host-side downgrade reason</b> — <i>this</i> enum. It answers "why is the app leaving
 *       MLS", carries seven behavioural columns, and is a <b>private vocabulary</b>: a whole-tree grep
 *       in Google Messages for reads of these fields returns only three classes, so <b>no other component
 *       branches on it</b>. Consumers see only {@link #wire} in the trace record and {@link #metric}
 *       in the telemetry bucket.</li>
 * </ul>
 *
 * <p><b>Mirror the enum's NUMBERS, not its ordinals.</b> Our declaration order matches Google Messages'
 * ordinals for readability, but every number below is explicit and none is derived from position —
 * that is the same trap that produced eight wrong codes in {@link RccNegativeDeliveryImdn} (§12.3,
 * rework {@code 10.2}), and it is not repeated here.
 *
 * <h2>The seven columns</h2>
 *
 * <table>
 *   <tr><th>field</th><th>meaning</th></tr>
 *   <tr><td>{@link #wire} ({@code A})</td>
 *       <td>the wire/GCE reason number written into the MLS context's trace record.
 *           <b>It does not go onto the RCS wire</b> — do not confuse it with
 *           {@code EndMlsRequest.reason}, which is {@link #zinniaCode} minus two.</td></tr>
 *   <tr><td>{@link #metric} ({@code v})</td>
 *       <td>telemetry bucket. <b>{@code null} ⇒ no metric is emitted at all</b>, which is a real
 *           behaviour of exactly one reason and not a modelling convenience.</td></tr>
 *   <tr><td>{@link #expected} ({@code w})</td>
 *       <td><b>"expected downgrade".</b> {@code false} ⇒ run the <i>unexpected-downgrade</i>
 *           bookkeeping: stamp the timestamp, conditionally reset the re-upgrade attempt counter.
 *           {@code true} ⇒ <b>do not plan to come back</b>. This column is what drives
 *           {@link MlsReupgradeState}'s loop 1.</td></tr>
 *   <tr><td>{@link #postprocessResult} ({@code x})</td>
 *       <td>"there is an end-mls commit result worth postprocessing" — gates {@code logResult}.</td></tr>
 *   <tr><td>{@link #zinniaAlreadyEnded} ({@code y})</td>
 *       <td>"the engine already ended MLS" ⇒ <b>skip generating the end_mls commit entirely</b>.</td></tr>
 *   <tr><td>{@link #suppressEagerLocalDowngrade} ({@code z})</td>
 *       <td>"do NOT eagerly downgrade the app's own state" — inverts the eager flag for this
 *           reason.</td></tr>
 *   <tr><td>{@link #zinniaCode} ({@code B})</td>
 *       <td>the reason code handed to the engine. See {@link #endMlsRequestWireReason()}.</td></tr>
 * </table>
 *
 * <p><b>{@code B} is not a string id.</b> An earlier assembly described the last five columns as
 * "four policy booleans and a UI string id"; each boolean has a precise, separate meaning and
 * {@code B} is an ordinal in an inlined {@code EndMlsReason} enum.
 *
 * <h2>The two constructor invariants</h2>
 *
 * <p>Both throw in Google Messages, and both throw here — checked in a static block over every value, so a
 * future edit to the table cannot introduce a violation that only shows up on the one device that
 * hits that reason:
 *
 * <ol>
 *   <li><b>{@code y && x} is forbidden</b> — you cannot both skip generating the commit and
 *       postprocess its result.</li>
 *   <li><b>{@code B != 0 ⇒ !y && !z}</b> — a reason carrying an engine reason code may neither skip
 *       the commit <b>nor</b> suppress the eager local downgrade.</li>
 * </ol>
 */
public enum MlsDowngradeReason {

    // name                                        A   v     w      x      y      z      B
    /** This client cannot do MLS. */
    SELF_NOT_MLS_CAPABLE(                           2,  0,  true,  true, false, false, 11),
    /** This client lost MLS. */
    CLIENT_LOST_MLS(                                3,  1,  true,  true, false, false,  5),
    /** {@code "A new user is not MLS capable. Downgrading the group."} */
    NON_MLS_CLIENT_ADDED(                           4,  2,  true,  true, false, false, 15),
    /** The {@code era-advancement-quota-reached} IMDN token. SERVER-OPAQUE as to <i>why</i>. */
    ERA_ADVANCEMENT_QUOTA_REACHED(                  5,  3, false,  true, false, false,  0),
    /** A terminal server failure reason. */
    UNRECOVERABLE_SERVER_FAILURE(                   6,  4, false,  true, false, false,  3),
    /** A receipt arrived unencrypted. */
    PLAINTEXT_DELIVERY_RECEIPT(                     7,  5, false,  true, false, false,  4),
    /**
     * An inbound commit carried {@code end_mls} — §9.7h path A.
     *
     * <p>Note the column pair: {@code w == false} ⇒ it <b>is</b> recorded as an unexpected downgrade
     * and so becomes eligible for automatic re-upgrade; {@code y == true} ⇒ <b>no end_mls commit of
     * our own is generated</b>, which is correct, since the peer already committed one.
     */
    RECEIVED_END_MLS_COMMIT(                        8,  6, false, false,  true,  true,  0),
    /** The default. <b>{@code v == null} ⇒ no metric is emitted at all.</b> */
    UNKNOWN_DOWNGRADE_REASON(                       1, null, false,  true, false, false,  0),
    /** Manual, from a debug surface. */
    DEBUG_MENU(                                     9,  7, false,  true, false, false, 17),
    /** The maintenance path when an expired participant yields zero key packages. */
    FAILED_TO_GET_KEYPACKAGES_FOR_EXPIRED_MEMBERS( 10,  8,  true,  true, false, false, 10),
    /** Engine-driven: health status {@code DoneEndMls(10)}, and the {@code END_MLS_ONGOING} handler. */
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
     * Phoenix's own local downgrade — raised <b>unconditionally and FIRST</b> by
     * {@code initiatePhoenixMode} (§9.7g, invariant 95).
     *
     * <p>Its columns are the meaning of its name: {@code w == true} (do not mark this as an
     * <i>unexpected</i> downgrade — we chose it) and {@code y == true} (do not emit an end_mls commit
     * — the era advance carries it).
     */
    EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE( 16, 14,  true, false,  true, false,  0),
    /** Own leaf certificate expired. */
    SELF_CERTIFICATE_EXPIRED(                      17, 15,  true,  true, false, false, 14),
    /** Engine-driven: health status {@code CannotHealDuringEndMls(15)} — §9.7j. */
    CANNOT_HEAL_DURING_END_MLS(                    18, 16, false, false,  true, false,  0),
    /**
     * Engine-driven: health status {@code EndMlsRequested(8)}.
     *
     * <p>⚠ <b>CORRECTED.</b> The source arm is {@code END_MLS_REQUESTED}, <i>not</i>
     * {@code DONE_END_MLS} — the reason↔status mapping was inverted in an earlier assembly
     * (§9.7a row 18). {@link #ZINNIA_END_MLS_ONGOING} is the one that answers {@code DoneEndMls}.
     */
    ZINNIA_REQUESTED_END_MLS(                      19, 17,  true, false, false, false,  0),
    /** Capabilities no longer advertise MLS. */
    MLS_NOT_ADVERTISED(                            20, 18,  true,  true, false, false, 14),
    /** The engine asked for group deletion. */
    ZINNIA_DELETING_GROUP(                         21, 19,  true, false, false, false,  0);

    /** {@code A} — the wire/GCE reason number. Never the ordinal. */
    public final int wire;
    /** {@code v} — the telemetry bucket, or {@code null} for "emit no metric at all". */
    public final Integer metric;
    /** {@code w} — "expected downgrade"; {@code false} drives the unexpected-downgrade bookkeeping. */
    public final boolean expected;
    /** {@code x} — there is an end-mls commit result worth postprocessing. */
    public final boolean postprocessResult;
    /** {@code y} — the engine already ended MLS, so skip generating the commit entirely. */
    public final boolean zinniaAlreadyEnded;
    /** {@code z} — do NOT eagerly downgrade the app's own conversation state. */
    public final boolean suppressEagerLocalDowngrade;
    /** {@code B} — the reason code handed to the engine; 0 means "no reason". */
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

    /**
     * The <b>hard 5-reason whitelist</b> on the engine→host downgrade funnel (§9.7d, invariant 94).
     *
     * <p>Google Messages' {@code downgradeMlsLocally} begins with this membership test — <b>before</b> the
     * identity and conversation lookups and before the log line — and <b>throws</b> rather than
     * degrading. Copy the whitelist, and put your own reasons through the ordinary downgrade entry
     * point rather than through the health-status handler.
     *
     * <p>Corroboration that this set is right and not merely plausible: <b>all five have
     * {@code B == 0}</b>, which is one of the three independent checks that pin §9.7e's polarity.
     */
    public static final Set<MlsDowngradeReason> ENGINE_FUNNEL_WHITELIST =
            Collections.unmodifiableSet(EnumSet.of(
                    SERVER_GROUP_STATE_NOT_FOUND,
                    EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                    CANNOT_HEAL_DURING_END_MLS,
                    ZINNIA_END_MLS_ONGOING,
                    ZINNIA_REQUESTED_END_MLS));

    /**
     * Invariant 94 — the whitelist check, in the position Google Messages puts it: FIRST, and throwing.
     *
     * <p><b>This is a hard rejection, not a fallback.</b> A reason that reaches the engine funnel
     * without being one of the five is a routing defect in the caller, and degrading to "downgrade
     * anyway" would hide it behind a conversation that quietly left MLS.
     *
     * @throws IllegalStateException with Google Messages' verbatim text
     */
    public static void requireEngineFunnelReason(final MlsDowngradeReason reason) {
        if (!ENGINE_FUNNEL_WHITELIST.contains(reason)) {
            throw new IllegalStateException("Unexpected failure reason: " + reason);
        }
    }

    /**
     * The engine→host funnel's dispatch: which downgrade reason an engine {@code MlsHealthStatus}
     * produces, or {@code null} if that status calls for no host downgrade at all (§9.7d).
     *
     * <p>Four of the five whitelisted reasons come from here; the fifth,
     * {@link #SERVER_GROUP_STATE_NOT_FOUND}, is raised when a group-info result comes back null and
     * so has no status to map from.
     *
     * <p><b>Most statuses map to nothing, and that is the point.</b> A conversation being
     * {@code EpochAdvancementRequested} or {@code OngoingEraAdvancement} is recovery working, not a
     * reason to leave MLS. Only four statuses mean "the engine has given up on encryption here":
     *
     * <table>
     *   <tr><th>status</th><th>reason</th><th>why</th></tr>
     *   <tr><td>{@code EndMlsRequested(8)}</td><td>{@link #ZINNIA_REQUESTED_END_MLS}</td>
     *       <td>⚠ arm 6, <b>not</b> {@code DoneEndMls} — the reason↔status mapping was inverted in an
     *           earlier assembly (§9.7a row 18)</td></tr>
     *   <tr><td>{@code DoneEndMls(10)}</td><td>{@link #ZINNIA_END_MLS_ONGOING}</td>
     *       <td>arm 8 — "Failed to encrypt MLS message because the conversation has end_mls
     *           applied."</td></tr>
     *   <tr><td>{@code CannotHealDuringEndMls(15)}</td><td>{@link #CANNOT_HEAL_DURING_END_MLS}</td>
     *       <td>arm 13, the wedge (§9.7j)</td></tr>
     *   <tr><td>{@code PhoenixModeRequested(17)}</td>
     *       <td>{@link #EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE}</td>
     *       <td>arm 15</td></tr>
     * </table>
     *
     * <p>Note {@code OngoingPhoenixMode(16)} maps to <b>nothing</b>: Phoenix's local downgrade has
     * already been performed at REQUEST time, before the era advance was asked for (invariant 95).
     * Downgrading again on arrival at 16 would double-count the bookkeeping.
     *
     * <p>Every reason this returns is in {@link #ENGINE_FUNNEL_WHITELIST} by construction, which is
     * asserted rather than assumed by {@code MlsDowngradeReasonTest}.
     */
    public static MlsDowngradeReason forEngineHealthStatus(final int status) {
        switch (status) {
            case MlsHealthStates.ENDMLSREQUESTED:            // 8  — arm 6
                return ZINNIA_REQUESTED_END_MLS;
            case MlsHealthStates.DONEENDMLS:                 // 10 — arm 8
                return ZINNIA_END_MLS_ONGOING;
            case MlsHealthStates.CANNOTHEALDURINGENDMLS:     // 15 — arm 13
                return CANNOT_HEAL_DURING_END_MLS;
            case MlsHealthStates.PHOENIXMODEREQUESTED:       // 17 — arm 15
                return EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE;
            default:
                return null;
        }
    }

    /**
     * §9.7e's reason gate, <b>with the corrected polarity</b> — rework item {@code 11.2a}.
     *
     * <pre>  r = (B != 0 &amp;&amp; ctx.hasEngineHealthStatus == false) ? B : 0</pre>
     *
     * <p>⚠ The polarity is {@code == 0}, not {@code != 0}, and this is the one correction in this
     * domain that changes what you build. It is a <b>"don't tell the engine what the engine just told
     * you"</b> gate, not an "only trust the engine" gate: the reason is shipped exactly on
     * <b>app-originated</b> downgrades, and suppressed the moment the MLS context has round-tripped an
     * engine health status.
     *
     * <p>Three independent corroborations (§9.7e): (a) all five whitelisted engine-driven reasons have
     * {@code B == 0}, so under the opposite polarity the field would be <b>permanently dead</b>;
     * (b) every engine-driven downgrade sets the hasbit immediately before the dispatch switch, i.e.
     * arrives with the reason suppressed; (c) the set with {@code B != 0} is exactly the
     * app-originated set.
     *
     * @param contextCarriesEngineHealthStatus whether the MLS request context has had an engine
     *        {@code MlsHealthStatus} copied into it (Google Messages' hasbit 8 / field 14)
     * @return the code to place on the request, or 0 for "set no reason"
     */
    public int zinniaReasonFor(final boolean contextCarriesEngineHealthStatus) {
        return (zinniaCode != 0 && !contextCarriesEngineHealthStatus) ? zinniaCode : 0;
    }

    /**
     * The {@code EndMlsRequest.reason} wire value — {@code B - 2}.
     *
     * <p>{@code B} is an ordinal in an inlined {@code EndMlsReason} Java enum where ordinal 0 =
     * "no reason", <b>ordinal 1 = {@code UNRECOGNIZED}</b>, and ordinals ≥ 2 are the real constants
     * with wire number {@code B - 2}. That is <i>why</i> the mapping is minus two rather than an
     * arbitrary offset.
     *
     * <p>Google Messages' {@code B == 1} arm is not a sentinel — it <b>throws</b>, on protobuf-lite's
     * {@code getNumber()} failure path. Nothing ships with {@code B == 1} so the arm is dead, but
     * modelling it as a sentinel would be a worse reading than modelling it as the throw it is, and a
     * future table edit that introduced a 1 should fail loudly rather than emit reason {@code -1}.
     *
     * <p>Observed {@code B ∈ {3,4,5,10,11,14,15,17}} ⇒ observed wire values
     * {@code {1,2,3,8,9,12,13,15}}. The enum's constant NAMES are not statically recoverable
     * (NEEDS-CAPTURE §22.1-57), which is why this returns a number and not a symbol.
     *
     * @throws IllegalArgumentException verbatim, if {@code B == 1}
     * @throws IllegalStateException if called when no reason is to be set ({@code B == 0}) — callers
     *         must consult {@link #zinniaReasonFor} first, exactly as Google Messages' {@code if (i2 != 0)}
     *         does
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
        // The two constructor invariants, enforced over the WHOLE table at class-init rather than
        // per-instance at construction. Google Messages checks them in the constructor, which catches the
        // same violations — but only for a value someone actually constructs, and every value here is
        // a compile-time constant. Checking the table means a bad edit fails the first test that
        // touches this class, not the first device that hits that one reason.
        for (final MlsDowngradeReason r : values()) {
            if (r.zinniaAlreadyEnded && r.postprocessResult) {
                throw new IllegalArgumentException("Failed requirement.");   // Google Messages' verbatim text
            }
            if (r.zinniaCode != 0 && (r.zinniaAlreadyEnded || r.suppressEagerLocalDowngrade)) {
                throw new IllegalArgumentException("Failed requirement.");
            }
        }
    }
}
