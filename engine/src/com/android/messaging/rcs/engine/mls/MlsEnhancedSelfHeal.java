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
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 <b>§10.1.2</b> — the Enhanced Self-Heal client procedure, as a pure state machine.
 *
 * <h2>Why this matters more than "one more conformance item"</h2>
 *
 * Our entire recovery story is external-commit resync. §10.1.2 makes that the <b>fallback</b> and
 * puts something cheaper in front of it: ask the Conversation Focus for the control messages we
 * missed and replay them. That avoids consuming an External Commit against the quota, avoids
 * minting a new leaf, and avoids the roster churn that has repeatedly cost us
 * {@code mismatched-rcs-group-state} refusals. On Tachyon, era-advance IS our self-heal and it is
 * rate-limited, so a repair that needs neither is worth real effort.
 *
 * <h2>The procedure, verbatim, and the two traps in it</h2>
 *
 * <ol>
 *   <li>Request the Enhanced GroupInfo (§6.3.2).</li>
 *   <li>If {@code latest_epoch_identifier} equals the local epoch identifier, <b>done</b>.</li>
 *   <li>If {@code paginated_epoch_identifier} is provided:
 *     <ul>
 *       <li>Apply every {@code ServerMlsRcsMessage} from {@code committed_control_messages}
 *           <b>in sequential order</b>, skipping any from current or past epochs.</li>
 *       <li><b>If any message fails, ABORT the enhanced self-heal</b> and fall back to §6.3.1 plus
 *           an External Commit.</li>
 *       <li>If {@code paginated_epoch_identifier} ≠ {@code latest_epoch_identifier}, request
 *           another page with a newer epoch identifier.</li>
 *     </ul>
 *   </li>
 *   <li>If {@code paginated_epoch_identifier} is <b>not</b> provided, fall back.</li>
 * </ol>
 *
 * <p><b>Trap one:</b> absence of {@code paginated_epoch_identifier} is a control signal, not a
 * missing optional. Defaulting it to "empty" and proceeding would replay messages with no idea where
 * they end.
 *
 * <p><b>Trap two — and it is a correctness trap, not a tidiness one:</b> a single failed message
 * aborts the WHOLE enhanced path. Partial application is the dangerous outcome, because the group
 * state has advanced but not to a point the server named, so the next fetch compares against an
 * epoch nobody agrees on. The spec says abort and fall back, and "fall back" means the External
 * Commit resync we already have.
 *
 * <h2>The IMDN rule</h2>
 *
 * §10.1.2 says the client <b>shall not</b> send IMDNs for anything applied via this path <b>if the
 * message was never received on MSRP</b> — and that if the same message later arrives on MSRP, it is
 * to be treated as idempotent, successful, and acknowledged. Both halves matter: the first stops us
 * acknowledging messages the sender never sent to us directly, the second stops a duplicate looking
 * like a failure. See {@link #shouldSendImdn}.
 */
public final class MlsEnhancedSelfHeal {

    private MlsEnhancedSelfHeal() { }

    /** What the caller should do next. */
    public enum Step {
        /** {@code latest_epoch_identifier} equals ours. Nothing to do. */
        COMPLETE,
        /** Apply {@link Plan#toApply} in order, then re-evaluate. */
        APPLY_CONTROL_MESSAGES,
        /** Applied a page and there is more; request another with {@link Plan#nextRequest}. */
        REQUEST_NEXT_PAGE,
        /**
         * Abandon the enhanced path: §6.3.1 ordinary GroupInfo pull plus an External Commit. Reached
         * when the response carries no {@code paginated_epoch_identifier}, when the response is
         * unusable, or when a control message failed to apply.
         */
        FALL_BACK_TO_EXTERNAL_COMMIT
    }

    /** The decision plus whatever the caller needs to act on it. */
    public static final class Plan {
        public final Step step;
        /** Control messages to apply, in wire order, already filtered to future epochs. */
        public final List<RccEnhancedGroupInfo.ControlMessage> toApply;
        /** For {@link Step#REQUEST_NEXT_PAGE}: the identifier to put in the next request. */
        public final RccEpochIdentifier nextRequest;
        /** Human-readable reason, for the log line that explains a fallback. */
        public final String reason;

        Plan(final Step step, final List<RccEnhancedGroupInfo.ControlMessage> toApply,
                final RccEpochIdentifier nextRequest, final String reason) {
            this.step = step;
            this.toApply = Collections.unmodifiableList(
                    toApply == null ? new ArrayList<>() : new ArrayList<>(toApply));
            this.nextRequest = nextRequest;
            this.reason = reason;
        }
    }

    /**
     * Evaluate one round of §10.1.2 against a response.
     *
     * @param response the parsed §7.10.5 body, or {@code null} if the pull failed
     * @param localEpoch where we currently are
     */
    public static Plan evaluate(final RccEnhancedGroupInfo response,
            final RccEpochIdentifier localEpoch) {
        if (response == null) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "no Enhanced GroupInfo response (pull failed or unparseable)");
        }
        // Step 2 — already there. Checked BEFORE the pagination test, because a client that is up
        // to date must not be dragged down the fallback merely because the server sent no page.
        if (response.latestEpoch != null && localEpoch != null
                && response.latestEpoch.sameEpochAs(localEpoch)) {
            return new Plan(Step.COMPLETE, null, null, "already at latest_epoch_identifier");
        }
        // Step 4 — absence is the signal.
        if (response.paginatedEpoch == null) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "no paginated_epoch_identifier — the CF cannot back-fill this client "
                    + "(§7.10.3 gates the list on the requested epoch being known and on the "
                    + "requester having been a participant throughout)");
        }
        if (response.committedControlMessages.isEmpty()) {
            // A page that names an end but carries nothing cannot advance us. Falling back is the
            // honest move: looping would re-request the same empty page forever.
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "paginated_epoch_identifier present but committed_control_messages is empty");
        }
        return new Plan(Step.APPLY_CONTROL_MESSAGES,
                response.committedControlMessages, response.paginatedEpoch,
                "applying " + response.committedControlMessages.size()
                        + " control message(s) in sequential order");
    }

    /**
     * What to do after applying a page.
     *
     * @param allApplied false if ANY message failed — the whole enhanced attempt is then abandoned
     */
    public static Plan afterPage(final RccEnhancedGroupInfo response, final boolean allApplied) {
        if (!allApplied) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "a control message failed to process — §10.1.2 aborts the enhanced self-heal "
                    + "rather than leaving the group partially advanced");
        }
        if (response == null || response.paginatedEpoch == null) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null, "no page boundary");
        }
        if (response.latestEpoch != null
                && response.paginatedEpoch.sameEpochAs(response.latestEpoch)) {
            return new Plan(Step.COMPLETE, null, null, "reached latest_epoch_identifier");
        }
        return new Plan(Step.REQUEST_NEXT_PAGE, null, response.paginatedEpoch,
                "page applied; requesting the next from " + response.paginatedEpoch);
    }

    /**
     * §10.1.2's IMDN rule.
     *
     * @param appliedViaEnhancedSelfHeal whether this message reached us through the replay path
     * @param alsoReceivedOnMsrp         whether the same message arrived (or later arrives) on MSRP
     * @return whether to send an IMDN — and when applied-via-replay-and-also-on-MSRP, the IMDN is a
     *         SUCCESS one, because the spec makes the duplicate idempotent rather than a failure
     */
    public static boolean shouldSendImdn(final boolean appliedViaEnhancedSelfHeal,
            final boolean alsoReceivedOnMsrp) {
        if (!appliedViaEnhancedSelfHeal) return true;
        return alsoReceivedOnMsrp;
    }
}
