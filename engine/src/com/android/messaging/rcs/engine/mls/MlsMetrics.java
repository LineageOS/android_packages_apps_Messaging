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

import java.util.List;

/**
 * The 16-metric catalogue (§20.6) and the pure logic behind the two we can record today.
 *
 * <p>Rework item 13.3. Names are Google Messages' verbatim — see {@link MlsTelemetry} for why we do not
 * rename them.
 *
 * <p><b>Only two of the sixteen are recordable before the state machine exists</b>, and they are
 * also the two the spec calls out as cheapest and highest value:
 *
 * <ul>
 *   <li>{@link #ZINNIA_STATE_SIZE} — a log2 bucket on every state write. The cheapest state-bloat
 *       regression detector there is.</li>
 *   <li>{@link #ZINNIA_CLIENT_FAILURE_REASON} — the five {@code create_client} preconditions. This
 *       one would have told us provisioning was broken in several past sessions without a logcat
 *       dig, because today every one of those five failures returns the same undifferentiated
 *       zero handle.</li>
 * </ul>
 *
 * <p>The other fourteen are declared here but not yet recorded: {@code MaxLoopReached} needs the
 * drive loop (Phase 4), the Revive/Upgrade/Downgrade families need Phases 8 and 11. They are listed
 * rather than omitted so the call sites can be found by grepping the constant when those phases
 * land, instead of re-deriving the catalogue from the spec.
 */
public final class MlsMetrics {

    // -- recordable today ------------------------------------------------------------------------

    /** log2 bucket of the serialised state blob, on every write. */
    public static final String ZINNIA_STATE_SIZE = "Bugle.Mls.ZinniaStateSize";
    /**
     * {@code StateTransition{from, to, edge, cause}} — the machine-readable transition trace
     * (§20.6). Recorded with the edge's TELEMETRY value, which is not its ordinal.
     */
    public static final String STATE_TRANSITION = "Bugle.Mls.StateTransition";
    /** The five {@code create_client} precondition failures (§3.3). */
    public static final String ZINNIA_CLIENT_FAILURE_REASON = "Bugle.Mls.ZinniaClient.Failure.Reason";

    // -- declared, recorded when the phase that produces them lands ------------------------------

    /**
     * A drive pass reported more work without making progress. Should be FLAT AT ZERO; any increment
     * is a bug. (Phase 4)
     *
     * <p>Counted on BOTH of {@link MlsDriveLoop}'s non-progress exits — the iteration cap, and the
     * earlier "two consecutive passes were indistinguishable" stop. The condition is the same one;
     * the second catches it eight {@code GetMlsGroupInfo} calls sooner. Here is why that
     * distinction matters: the first time this counter moved, the ten passes it counted had also
     * exhausted the server quota that recovery depends on, so making the loop stop earlier would
     * have silenced the alarm along with the fault if it were only counted at the cap.
     */
    public static final String MAX_LOOP_REACHED = "Bugle.Mls.ZinniaMessageProcessor.MaxLoopReached";
    /**
     * An AUTOMATIC conversation rebuild ran.
     *
     * <p>Ours, not Google Messages': Google Messages has no equivalent because it does not do this. Every rebuild
     * used to be a person running the deep-forget lever by hand, so the operation had a witness by
     * construction. Now that it happens by itself it needs a counter, or the next regression in this
     * area is invisible until someone notices their conversations have gone quiet — which is exactly
     * how the P0 this metric belongs to was found.
     */
    public static final String CONVERSATION_REBUILD = "Bugle.Mls.ConversationRebuild";
    /**
     * We stopped yielding to the designated era advancer and took the advance over ourselves.
     *
     * <p>A takeover is legitimate and is the whole point of {@link MlsAdvancerElection}, but it is
     * also the one operation in the recovery ladder that can FORK a group if the election is wrong,
     * so it must be countable. A fleet where this is flat at zero is one where advancers are always
     * present; a fleet where it climbs is one where the anti-duel stagger is being leaned on, and
     * that is worth knowing before a group diverges rather than after.
     */
    public static final String ADVANCER_TAKEOVER = "Bugle.Mls.AdvancerTakeover";
    /** Membership-sync outcome. (Phase 9) */
    public static final String GROUP_SYNC_RESULT = "Bugle.Mls.GroupSync.Result";
    /** Revive sub-machine outcome — assert on recovery without parsing text. (Phase 8) */
    public static final String REVIVE_SUCCESS = "Bugle.Mls.Revive.Success";
    public static final String REVIVE_FAILURE_REASON = "Bugle.Mls.Revive.Failure.Reason";
    public static final String REVIVE_SKIPPED = "Bugle.Mls.Revive.Skipped";
    /** Conversation upgrade to MLS. (Phase 11) */
    public static final String UPGRADE_SUCCESS = "Bugle.Mls.Upgrade.Success";
    public static final String UPGRADE_FAILURE_REASON = "Bugle.Mls.Upgrade.Failure.Reason";
    /** The single best signal that recovery gave up. (Phase 11) */
    public static final String CONVERSATION_DOWNGRADE_REASON = "Bugle.Mls.ConversationDowngrade.Reason";
    public static final String CONVERSATION_DOWNGRADE_TARGET = "Bugle.Mls.ConversationDowngrade.Target";
    /** Header loss on the CPIM leg. */
    public static final String CPIM_HEADERS_DROPPED = "Bugle.Mls.CpimHeadersDropped.Count";
    /** Transport-level errors. The name fits us unchanged — we are on Tachyon too. */
    public static final String TACHYON_ERROR = "Bugle.Mls.Tachyon.Error";
    /** The subject/icon downgrade path (§13.7a). */
    public static final String PROFILE_DOWNGRADE_START = "Bugle.Mls.ProfileDowngrade.Start";
    public static final String PROFILE_DOWNGRADE_SUCCESS = "Bugle.Mls.ProfileDowngrade.Success";
    /** Encryption-data sync churn. */
    public static final String SYNC_ENCRYPTION_DATA_UPDATED =
            "Bugle.Mls.ConversationMlsUpdater.SyncEncryptionData.UpdatedCounter";

    // -- create_client precondition reasons (§3.3, in Google Messages' evaluation order) ---------------

    /** Reached only if a precondition fails without matching any of the five below. */
    public static final int CLIENT_FAIL_UNKNOWN = 0;
    public static final int CLIENT_FAIL_CHAIN_NULL_OR_EMPTY = 1;
    public static final int CLIENT_FAIL_MISSING_LEAF = 2;
    public static final int CLIENT_FAIL_MISSING_INTERMEDIATE = 3;
    public static final int CLIENT_FAIL_MISSING_ROOT = 4;
    public static final int CLIENT_FAIL_MISSING_KEYPAIR = 5;
    /** Not a failure. Returned by {@link #preflightCreateClient} when every precondition holds. */
    public static final int CLIENT_OK = -1;

    /**
     * The five host-side {@code create_client} preconditions, in Google Messages' order (§3.3).
     *
     * <p>We had none. {@code nativeSessionStart} was handed whatever the identity contained and
     * returned {@code 0} for every kind of failure — a missing intermediate, an absent trust anchor
     * and a null device keypair were one undifferentiated value, at the exact point in the system
     * where "provisioning is broken" and "the engine is broken" have completely different remedies.
     *
     * <p>Order matters and is Google Messages', not ours: the chain is checked as a whole before its parts,
     * so a null chain reports as {@code 1} rather than as a missing leaf. Anything else would make
     * our counter values incomparable with a Google Messages trace, which is the reason to mirror them.
     *
     * @return {@link #CLIENT_OK}, or the reason code to record against
     *         {@link #ZINNIA_CLIENT_FAILURE_REASON}
     */
    public static int preflightCreateClient(final MlsIdentity id) {
        if (id == null) return CLIENT_FAIL_UNKNOWN;
        final List<byte[]> chain = id.chainDer;
        if (chain == null || chain.isEmpty()) return CLIENT_FAIL_CHAIN_NULL_OR_EMPTY;
        if (isEmpty(id.leafDer)) return CLIENT_FAIL_MISSING_LEAF;
        // "Intermediate" is the chain beyond the leaf. chainDer excludes the leaf by contract, so a
        // chain whose every entry is empty has no usable intermediate even though it is non-empty.
        boolean anyIntermediate = false;
        for (final byte[] c : chain) {
            if (!isEmpty(c)) { anyIntermediate = true; break; }
        }
        if (!anyIntermediate) return CLIENT_FAIL_MISSING_INTERMEDIATE;
        boolean anyRoot = false;
        if (id.roots != null) {
            for (final byte[] r : id.roots) {
                if (!isEmpty(r)) { anyRoot = true; break; }
            }
        }
        if (!anyRoot) return CLIENT_FAIL_MISSING_ROOT;
        // Both halves: a private scalar with no public point cannot produce a SigningIdentity, and a
        // public point with no scalar cannot sign. Either alone is "missing device keypair".
        if (isEmpty(id.subjectPriv) || isEmpty(id.subjectPub)) return CLIENT_FAIL_MISSING_KEYPAIR;
        return CLIENT_OK;
    }

    /** The human-readable half, so the logged line says what the counter value means. */
    public static String clientFailureReason(final int code) {
        switch (code) {
            case CLIENT_FAIL_CHAIN_NULL_OR_EMPTY: return "Certificate chain is null or empty";
            case CLIENT_FAIL_MISSING_LEAF: return "Missing leaf certificate";
            case CLIENT_FAIL_MISSING_INTERMEDIATE: return "Missing intermediate certificate";
            case CLIENT_FAIL_MISSING_ROOT: return "Missing trusted root certificate";
            case CLIENT_FAIL_MISSING_KEYPAIR: return "Missing device keypair";
            case CLIENT_OK: return "ok";
            default: return "Unknown failure";
        }
    }

    /**
     * The log2 bucket of a serialised blob size — Google Messages' {@link #ZINNIA_STATE_SIZE} shape.
     *
     * <p>A bucket rather than the size because the thing being watched is growth by orders of
     * magnitude: a group state that has gone from 8 KiB to 12 KiB is noise, and one that has gone
     * from 8 KiB to 800 KiB is the bug. Bucketing also means the metric carries no usable
     * information about the state itself.
     *
     * @return {@code floor(log2(bytes))}, and {@code 0} for a zero-length or negative size
     */
    public static int log2Bucket(final long bytes) {
        if (bytes <= 0L) return 0;
        return 63 - Long.numberOfLeadingZeros(bytes);
    }

    private static boolean isEmpty(final byte[] b) { return b == null || b.length == 0; }

    private MlsMetrics() {}
}
