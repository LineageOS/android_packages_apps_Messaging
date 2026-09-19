/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The results of one engine call, each stamped with the context it belongs to. An encrypt can
 * return a piggybacked commit beside the ciphertext, and a result can belong to another operation;
 * those must still be post-processed but not returned to the caller. The guards throw rather than
 * return empty, because an empty result set and a discarded one look the same downstream; their
 * messages match other clients' so logs compare.
 */
public final class MlsResultBundle {

    /** One engine result: what to do, whose it is, and which group it concerns. */
    public static final class Result {
        /** The context this result belongs to; may differ from the caller's. */
        public final String contextId;
        public final MlsHostAction action;
        private final byte[] mGroupId;

        public Result(final String contextId, final MlsHostAction action, final byte[] groupId) {
            this.contextId = contextId == null ? "" : contextId;
            this.action = action;
            mGroupId = groupId == null ? new byte[0] : copy(groupId);
        }

        /** The group this result concerns; empty when the engine named none. */
        public byte[] groupId() { return copy(mGroupId); }

        boolean hasGroup() { return mGroupId.length > 0; }

        @Override public String toString() {
            return "result{ctx=" + contextId + " " + (action == null ? "null" : action.toString())
                    + " gid=" + mGroupId.length + "B}";
        }
    }

    /**
     * Groups the results by context, preserving order within each.
     *
     * @throws IllegalStateException if there are no results
     */
    public static Map<String, List<MlsHostAction>> demux(final List<Result> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException(
                    "No results returned from handleResults. This should not happen.");
        }
        final Map<String, List<MlsHostAction>> byContext = new LinkedHashMap<>();
        for (final Result r : results) {
            if (r == null) continue;
            List<MlsHostAction> l = byContext.get(r.contextId);
            if (l == null) {
                l = new ArrayList<>();
                byContext.put(r.contextId, l);
            }
            l.add(r.action);
        }
        if (byContext.isEmpty()) {
            // Every element was null; not an empty-but-valid demux.
            throw new IllegalStateException(
                    "No results returned from handleResults. This should not happen.");
        }
        return byContext;
    }

    /**
     * The actions for the caller's own context. Other contexts stay in the map for post-processing.
     *
     * @throws IllegalStateException if the caller's context is absent
     */
    public static List<MlsHostAction> forContext(final Map<String, List<MlsHostAction>> byContext,
            final String mlsContextId) {
        final String id = mlsContextId == null ? "" : mlsContextId;
        if (byContext == null || !byContext.containsKey(id)) {
            throw new IllegalStateException("Mls context id is not found in the result map. "
                    + "mlsContextId=" + id + ", resultMap.keys="
                    + (byContext == null ? "[]" : byContext.keySet().toString()));
        }
        return Collections.unmodifiableList(byContext.get(id));
    }

    /**
     * The contexts that are not the caller's, to post-process for their effects. Skipping them
     * loses side work such as a commit that is never sent.
     */
    public static Map<String, List<MlsHostAction>> otherContexts(
            final Map<String, List<MlsHostAction>> byContext, final String mlsContextId) {
        final Map<String, List<MlsHostAction>> others = new LinkedHashMap<>();
        if (byContext == null) return others;
        final String id = mlsContextId == null ? "" : mlsContextId;
        for (final Map.Entry<String, List<MlsHostAction>> e : byContext.entrySet()) {
            if (!id.equals(e.getKey())) others.put(e.getKey(), e.getValue());
        }
        return others;
    }

    /**
     * The single group every result agrees on, since the caller applies a remedy to "the group".
     * Results naming no group are ignored.
     *
     * @throws IllegalStateException when no result names a group, or two name different ones
     */
    public static byte[] soleGroup(final List<Result> results) {
        final Set<String> seen = new LinkedHashSet<>();
        byte[] found = null;
        if (results != null) {
            for (final Result r : results) {
                if (r == null || !r.hasGroup()) continue;
                final byte[] g = r.groupId();
                if (seen.add(hex(g))) found = g;
            }
        }
        if (seen.isEmpty()) {
            throw new IllegalStateException("No MLS group found in the results: "
                    + describe(results));
        }
        if (seen.size() > 1) {
            throw new IllegalStateException("Multiple MLS groups found in the results: " + seen);
        }
        return found;
    }

    /**
     * If any action in a pass failed a pending operation, every {@code NO_OP} in the pass becomes
     * {@code FAIL_NO_RETRY}, so the failure is not read as convergence. Applies to the whole pass,
     * not only the caller's context.
     *
     * @return the list with the rule applied; the input is not modified
     */
    public static List<MlsHostAction> applyPoisonRule(final List<MlsHostAction> pass) {
        if (pass == null || pass.isEmpty()) return Collections.emptyList();
        boolean poisons = false;
        for (final MlsHostAction a : pass) {
            if (a != null && a.poisonsNoOp()) { poisons = true; break; }
        }
        if (!poisons) return Collections.unmodifiableList(new ArrayList<>(pass));
        final List<MlsHostAction> out = new ArrayList<>(pass.size());
        for (final MlsHostAction a : pass) {
            out.add(a == null ? null : a.poison());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The worst outcome in a pass, ordered {@code FAIL_NO_RETRY > FAIL_RETRY > PENDING > SUCCESS >
     * NO_OP}, so a success at the head cannot hide work still owed.
     *
     * @return the governing action, or null for an empty list
     */
    public static MlsHostAction governing(final List<MlsHostAction> pass) {
        MlsHostAction worst = null;
        if (pass == null) return null;
        for (final MlsHostAction a : pass) {
            if (a == null) continue;
            if (worst == null || rank(a.status) > rank(worst.status)) worst = a;
        }
        return worst;
    }

    private static int rank(final MlsResultStatus s) {
        if (s == null) return -1;
        switch (s) {
            case FAIL_NO_RETRY: return 4;
            case FAIL_RETRY:    return 3;
            case PENDING:       return 2;
            case SUCCESS:       return 1;
            case NO_OP:         return 0;
            default:            return -1;
        }
    }

    private static String describe(final List<Result> results) {
        return results == null ? "[]" : Arrays.toString(results.toArray());
    }

    private static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] copy(final byte[] b) {
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    private MlsResultBundle() {}
}
