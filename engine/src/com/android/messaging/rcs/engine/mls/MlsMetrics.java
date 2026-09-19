/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/**
 * The MLS metric catalogue and the pure logic behind it. Names are kept verbatim (see
 * {@link MlsTelemetry}); constants in the second group are declared but not yet recorded.
 */
public final class MlsMetrics {

    // -- recorded --------------------------------------------------------------------------------

    /** log2 bucket of the serialised state blob, on every write. */
    public static final String ZINNIA_STATE_SIZE = "Bugle.Mls.ZinniaStateSize";
    /**
     * {@code StateTransition{from, to, edge, cause}}, recorded with the edge's telemetry value,
     * which is not its ordinal.
     */
    public static final String STATE_TRANSITION = "Bugle.Mls.StateTransition";
    /** The five {@code create_client} precondition failures. */
    public static final String ZINNIA_CLIENT_FAILURE_REASON =
            "Bugle.Mls.ZinniaClient.Failure.Reason";

    // -- MAX_LOOP_REACHED, CONVERSATION_REBUILD and ADVANCER_TAKEOVER are recorded; the rest are
    // declared only.

    /**
     * A drive pass reported more work without making progress; any increment is a bug. Counted on
     * both of {@link MlsDriveLoop}'s non-progress exits, the iteration cap and the earlier
     * identical-passes stop.
     */
    public static final String MAX_LOOP_REACHED = "Bugle.Mls.ZinniaMessageProcessor.MaxLoopReached";
    public static final String CONVERSATION_REBUILD = "Bugle.Mls.ConversationRebuild";
    /**
     * We stopped yielding to the designated era advancer and advanced ourselves. Counted because a
     * wrong election is the one way the recovery ladder can fork a group.
     */
    public static final String ADVANCER_TAKEOVER = "Bugle.Mls.AdvancerTakeover";
    public static final String GROUP_SYNC_RESULT = "Bugle.Mls.GroupSync.Result";
    public static final String REVIVE_SUCCESS = "Bugle.Mls.Revive.Success";
    public static final String REVIVE_FAILURE_REASON = "Bugle.Mls.Revive.Failure.Reason";
    public static final String REVIVE_SKIPPED = "Bugle.Mls.Revive.Skipped";
    public static final String UPGRADE_SUCCESS = "Bugle.Mls.Upgrade.Success";
    public static final String UPGRADE_FAILURE_REASON = "Bugle.Mls.Upgrade.Failure.Reason";
    /** The single best signal that recovery gave up. */
    public static final String CONVERSATION_DOWNGRADE_REASON =
            "Bugle.Mls.ConversationDowngrade.Reason";
    public static final String CONVERSATION_DOWNGRADE_TARGET =
            "Bugle.Mls.ConversationDowngrade.Target";
    public static final String CPIM_HEADERS_DROPPED = "Bugle.Mls.CpimHeadersDropped.Count";
    public static final String TACHYON_ERROR = "Bugle.Mls.Tachyon.Error";
    public static final String PROFILE_DOWNGRADE_START = "Bugle.Mls.ProfileDowngrade.Start";
    public static final String PROFILE_DOWNGRADE_SUCCESS = "Bugle.Mls.ProfileDowngrade.Success";
    public static final String SYNC_ENCRYPTION_DATA_UPDATED =
            "Bugle.Mls.ConversationMlsUpdater.SyncEncryptionData.UpdatedCounter";

    // -- create_client precondition reasons, in evaluation order ---------------------------------

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
     * The five host-side {@code create_client} preconditions, checked in the peers' order so the
     * counter values stay comparable: the chain as a whole before its parts.
     *
     * @return {@link #CLIENT_OK}, or the reason code to record against
     *         {@link #ZINNIA_CLIENT_FAILURE_REASON}
     */
    public static int preflightCreateClient(final MlsIdentity id) {
        if (id == null) return CLIENT_FAIL_UNKNOWN;
        final List<byte[]> chain = id.chainDer;
        if (chain == null || chain.isEmpty()) return CLIENT_FAIL_CHAIN_NULL_OR_EMPTY;
        if (isEmpty(id.leafDer)) return CLIENT_FAIL_MISSING_LEAF;
        // chainDer excludes the leaf, so a chain of only empty entries has no intermediate.
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
        // Either half of the keypair missing is "missing device keypair".
        if (isEmpty(id.subjectPriv) || isEmpty(id.subjectPub)) return CLIENT_FAIL_MISSING_KEYPAIR;
        return CLIENT_OK;
    }

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
     * The log2 bucket of a serialised blob size: growth by orders of magnitude is what is watched.
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
