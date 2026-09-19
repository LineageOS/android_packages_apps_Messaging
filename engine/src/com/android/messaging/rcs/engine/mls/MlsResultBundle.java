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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One engine call returns MANY results, each stamped with the context it belongs to — the
 * {@code Map<contextId, List<Action>>} demux (rework item 6.6, §10.5, invariant 57).
 *
 * <h2>What was missing, and why one result is not enough</h2>
 *
 * <p>Our engine returned ONE result for ONE message, with no context echo. The host then read that
 * single status in a hand-written switch. Three things are unrepresentable in that shape, and each
 * is a real behaviour rather than a nicety:
 *
 * <ol>
 *   <li><b>The piggybacked commit.</b> An encrypt can return the application ciphertext AND a
 *       self-key-update commit the engine decided to fold in. Interpreting the first element and
 *       dropping the rest loses the commit silently — the send succeeds, the key update never
 *       happens, and nothing says so. (§11.1a, rework 7.3.)</li>
 *   <li><b>Work for a DIFFERENT context.</b> A result can belong to an operation other than the one
 *       the caller asked about. It must still be post-processed for its effects, and must NOT appear
 *       in what this call returns — invariant 57. Filtering the wrong one of those two produces
 *       either lost work or a caller that acts on someone else's result.</li>
 *   <li><b>The re-drive.</b> {@link MlsDriveLoop} consumes an action per pass; without a list there
 *       is nothing for §10.5's carry function to fold a pending-operation id into.</li>
 * </ol>
 *
 * <h2>Three guards, and they THROW</h2>
 *
 * <p>§10.5 states them as hard invariants with exact messages, and they are hard for a reason: each
 * marks a state where continuing means acting on a result that is not the one we asked for. The
 * messages are reproduced verbatim so a log diff against Google Messages lines up.
 *
 * <p>Deliberately throws rather than returning empty. An empty result set and a result set that was
 * silently discarded are indistinguishable downstream, and this project has already paid for that
 * confusion twice — a check that fail-opened for its whole life (§7.5.3.1) and a release path that
 * was never called. A throw is the one outcome that cannot be mistaken for "nothing to do".
 */
public final class MlsResultBundle {

    /** One engine result: what to do, whose it is, and which group it concerns. */
    public static final class Result {
        /** The context this result belongs to. May differ from the caller's — see invariant 57. */
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
     * Group the results by context, preserving order within each context.
     *
     * @throws IllegalStateException if there are no results at all
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
            // Every element was null. Same condition as "none at all" from the caller's side, and it
            // must not read as an empty-but-valid demux.
            throw new IllegalStateException(
                    "No results returned from handleResults. This should not happen.");
        }
        return byContext;
    }

    /**
     * The actions for the caller's OWN context — what the call returns.
     *
     * <p>Invariant 57's second half. Results for other contexts stay in the map so the caller can
     * post-process them for their effects; only what comes back HERE is filtered.
     *
     * @throws IllegalStateException if the caller's context is absent, which means the engine did
     *         work and attributed none of it to the thing we asked about
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
     * The contexts that are NOT the caller's — post-process these for effects, then drop them.
     *
     * <p>The first half of invariant 57, and the half that is easy to skip because skipping it looks
     * like it works: the call returns the right thing either way, and what is lost is the side work
     * — a commit that never gets sent, a pending operation that never gets failed.
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
     * The single group every result in a list agrees on.
     *
     * <p>§10.5 makes disagreement fatal, and the reason is that the caller is about to apply a
     * remedy to "the group" — so two groups in one result list means the remedy would land on
     * whichever one happened to be read first. Results naming no group at all are ignored rather
     * than counted as a third answer; several arms legitimately have none ({@code NONE},
     * {@code DROP}).
     *
     * @throws IllegalStateException when no result names a group, or when two name different ones
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
     * §10.5's {@code PendingOperationFailure} rule: if any action in a pass failed a pending
     * operation, every {@code NO_OP} sibling in that same pass becomes {@code FAIL_NO_RETRY}.
     *
     * <p>Without it a failed pending operation coexisting with an idle group reads as "converged" —
     * the drive loop sees a NO_OP, stops, and the failure is lost. Google Messages logs
     * {@code "Pending operation %s failed with a no-op result. Treating as FAIL_NO_RETRY."} for each
     * one it re-maps.
     *
     * <p>Applied to the WHOLE pass, not just the caller's context: a pending operation that failed
     * is a fact about our state, and a NO_OP under a different context is no more true for it.
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
     * The action a drive-loop pass should report for a whole list — the WORST outcome present.
     *
     * <p>A pass returns one status, but a list can carry several. Taking the first would let a
     * SUCCESS at the head hide a FAIL_RETRY behind it and stop the loop on a group that still needs
     * work; taking the best has the same effect. So the pass reports the one that most needs
     * another look, ordered {@code FAIL_NO_RETRY > FAIL_RETRY > PENDING > SUCCESS > NO_OP}.
     *
     * <p>FAIL_NO_RETRY outranks FAIL_RETRY deliberately: it is terminal, and a loop that kept
     * retrying because a retryable sibling was also present would retry something already known to
     * be unretryable.
     *
     * @return the governing action, or {@code null} for an empty list
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
