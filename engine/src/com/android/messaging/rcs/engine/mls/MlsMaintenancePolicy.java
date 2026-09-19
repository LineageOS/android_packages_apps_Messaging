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
 * When a PROACTIVE era advance is warranted — the counterpart of Google Messages' {@code maybeRefresh}.
 *
 * <h2>An era advance is a membership-refresh mechanism, not a counter you bump</h2>
 *
 * <p>Google Messages' own dispatcher and its diagnostic strings say it issues an advance for
 * exactly two reasons and no others:
 *
 * <pre>
 *   "Group metadata keys request extension present in the server group info for group {:?};
 *    and new members added; will be requesting a new era."
 *   "Generating new request to refresh others as the list of expired members has changed.
 *    For group: {:?}"
 *   "Expired members count: {:?}, for group: {:?}"
 *   "Member with client ID: {:?} needs key rotation, for group: {:?}"
 * </pre>
 *
 * <p><b>There is no path that advances for its own sake.</b> That single fact explains our first
 * silent no-move without needing any server rule to be invented: a blind 2→3 with no new member and
 * a 70-second-old era had ZERO membership delta, so Google Messages' own code would never have generated
 * the request. We were off-path before the server ever saw it.
 *
 * <h2>What this class must NEVER be asked about</h2>
 *
 * <p><b>Recovery is exempt, structurally.</b> There is no shared dispatcher:
 * Google Messages exposes four INDEPENDENT leaf ops, and its {@code maybeRefresh} only builds the
 * membership-delta payload and calls the native refresh entry point. It never calls revive or
 * create, so it is impossible to reach an era advance "through" the refresh path. The delta gate
 * lives inside the native {@code maybe_refresh} op and therefore applies ONLY to callers of that op.
 *
 * <p>Recovery issues through different classes entirely — its revive operation and its message
 * processor, both outside the maintenance synclet. So self-heal and
 * the §10 FTD escalation must NOT consult this policy: gating them on a membership delta would
 * disable the repair path, because a diverged conversation typically has a perfectly fine roster and
 * a broken STATE. Asking this class about a recovery is the specific mistake to avoid.
 *
 * <h2>No timer, and that is a measured negative</h2>
 *
 * <p>A search for Java timestamps/intervals and native rate/too-soon strings found none:
 * the client's throttle is STATE-based, not time-based. So this takes no clock and returns no delay.
 * §8.7 closes with two words — <i>Build no timer.</i>
 */
public final class MlsMaintenancePolicy {

    private MlsMaintenancePolicy() {}

    /**
     * How many groups ONE sweep pass may take to the server.
     *
     * <p>Small on purpose, and it is <b>not a throughput number</b>: the sweep's cursor makes
     * throughput a non-issue, because the walk always advances. It is the size of the BURST a single
     * triggering event may produce. Four fetches behind a session bring-up is invisible; forty would
     * be a visible stall on whatever the user was doing when the process came up.
     *
     * <p>It bounds NETWORK work only. An entry the sweep declines locally — no record, a downgraded
     * group, no routable peer — is walked past for free and does not spend the page. Otherwise a
     * device carrying a hundred dead aliases would need twenty-five passes to reach the live group
     * sitting behind them.
     *
     * <p><b>Not a timer, and this class still has none.</b> §8.7 closes with <i>Build no timer</i>;
     * a page size is a burst bound on the pass that consults {@link #evaluate}, and the POSITION
     * that must survive a restart is the cursor, which is durable. Nothing here schedules anything.
     */
    public static final int SWEEP_PAGE = 4;

    /**
     * Has this pass taken its page to the server?
     *
     * @param maintained groups this pass has actually FETCHED — a locally declined entry is walked
     *     past for free and must not be counted here
     */
    public static boolean sweepPageExhausted(final int maintained) {
        return maintained >= SWEEP_PAGE;
    }

    /** Why a proactive refresh is (or is not) warranted. */
    public enum Refresh {
        /** No membership delta. Google Messages would not have generated the request; neither do we. */
        NOT_NEEDED,
        /** Members were added and the server GroupInfo carries the metadata-keys-request extension. */
        NEW_MEMBERS,
        /** At least one member's certificate expired and needs key rotation. */
        EXPIRED_MEMBERS,
        /** Both reasons hold at once — one advance serves both. */
        BOTH,
        /**
         * <b>The pass STOPPED: the group it was asked to maintain is not the server's group</b> —
         * i.e. a fork. Era and epoch match the server's and the epoch AUTHENTICATOR does not,
         * which is a different group at the same position rather than a conversation that is behind.
         *
         * <p><b>{@link #evaluate} never returns this, and that is correct rather than an omission.</b>
         * This policy decides whether a MEMBERSHIP DELTA warrants an era advance; whether the group
         * is ours at all is a question asked before the delta means anything, and answering it is
         * the transport's job. The value lives here because the transport's callers log the pass's
         * verdict and had no way to say this one — {@link #NOT_NEEDED} would have reported a fork as
         * a conversation with nothing to do, which is the exact collapse this value prevents.
         *
         * <p>{@link #warranted()} is FALSE, deliberately. Nothing about a refresh repairs a fork:
         * there is no commit chain from one group to another, so an era advance here would rebuild
         * the wrong group around the right roster.
         */
        DIVERGED;

        /**
         * Does an ERA ADVANCE follow from this verdict?
         *
         * <p>Spelled against the two that warrant one rather than against {@link #NOT_NEEDED},
         * because {@link #DIVERGED} is the second verdict that must answer false and a
         * {@code != NOT_NEEDED} rule would have silently made it answer true.
         */
        public boolean warranted() {
            return this == NEW_MEMBERS || this == EXPIRED_MEMBERS || this == BOTH;
        }
    }

    /**
     * Decide whether to issue a proactive era advance.
     *
     * <p>Pure: it decides, it does not act, and it reads no clock.
     *
     * <p><b>The add arm carries a second precondition</b> and it is not decoration. Google Messages' string
     * is one sentence with an "and": the metadata-keys-request extension present in the SERVER group
     * info, <i>and</i> new members added. That is another server-group-info-driven gate, like the
     * continuity-token one — a precondition we do not control and must read rather than assume. New
     * members WITHOUT the extension is not a refusal to be worked around; it means the server has not
     * asked for the keys yet, so an advance would be unrequested.
     *
     * <p>The expiry arm has no such precondition in any observed string, so it is not given one here.
     *
     * @param newMemberCount        members in the server roster that our group does not carry
     * @param expiredMemberCount    members whose certificates have expired and need key rotation
     * @param metadataKeysRequestPresent whether the SERVER GroupInfo carries the metadata-keys-request
     *                                   extension
     */
    public static Refresh evaluate(final int newMemberCount, final int expiredMemberCount,
            final boolean metadataKeysRequestPresent) {
        final boolean add = newMemberCount > 0 && metadataKeysRequestPresent;
        final boolean expiry = expiredMemberCount > 0;
        if (add && expiry) return Refresh.BOTH;
        if (add) return Refresh.NEW_MEMBERS;
        if (expiry) return Refresh.EXPIRED_MEMBERS;
        return Refresh.NOT_NEEDED;
    }

    /**
     * Google Messages' own line for the add arm, verbatim (§20.4 / rework 13.2).
     *
     * <p>Emitted as a literal so a trace diff matches it; a paraphrase breaks the comparison exactly
     * when a membership regression is what you are looking for.
     */
    public static String newMembersLine(final String groupId) {
        return "Group metadata keys request extension present in the server group info for group "
                + q(groupId) + "; and new members added; will be requesting a new era.";
    }

    /** Google Messages' own line for the expiry arm, verbatim. */
    public static String expiredMembersChangedLine(final String groupId) {
        return "Generating new request to refresh others as the list of expired members has changed. "
                + "For group: " + q(groupId);
    }

    /** Google Messages' own count line, emitted whether or not the count is zero. */
    public static String expiredCountLine(final int count, final String groupId) {
        return "Expired members count: " + count + ", for group: " + q(groupId);
    }

    /** Google Messages' per-member rotation line. */
    public static String needsRotationLine(final String clientId, final String groupId) {
        return "Member with client ID: " + q(clientId) + " needs key rotation, for group: "
                + q(groupId);
    }

    /** Google Messages prints ids in Rust {@code {:?}} form, i.e. quoted. */
    private static String q(final String s) {
        return s == null ? "\"\"" : "\"" + s + "\"";
    }
}
