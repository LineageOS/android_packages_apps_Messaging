/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 §10.1.2 Enhanced Self-Heal as a pure planner: replay the control messages the Conversation
 * Focus pages back to us, falling back to an External Commit. A missing
 * {@code paginated_epoch_identifier} is a control signal, and one failed message abandons the whole
 * attempt so the group is never left partially advanced. See docs/mls/health-and-recovery.md.
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
         * Abandon the enhanced path for the RCC.16 §6.3.1 GroupInfo pull plus an External Commit:
         * no {@code paginated_epoch_identifier}, an unusable response, or a control message failed
         * to apply.
         */
        FALL_BACK_TO_EXTERNAL_COMMIT
    }

    /** The decision plus whatever the caller needs to act on it. */
    public static final class Plan {
        public final Step step;
        /** Control messages to apply, in wire order, as the page carried them. */
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
     * Evaluate one round against a response.
     *
     * @param response the parsed RCC.16 §7.10.5 body, or {@code null} if the pull failed
     * @param localEpoch where we currently are
     */
    public static Plan evaluate(final RccEnhancedGroupInfo response,
            final RccEpochIdentifier localEpoch) {
        if (response == null) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "no Enhanced GroupInfo response (pull failed or unparseable)");
        }
        // Up to date is checked before pagination: a current client must not fall back for want of
        // a page.
        if (response.latestEpoch != null && localEpoch != null
                && response.latestEpoch.sameEpochAs(localEpoch)) {
            return new Plan(Step.COMPLETE, null, null, "already at latest_epoch_identifier");
        }
        // Absence of a page boundary is the signal to fall back.
        if (response.paginatedEpoch == null) {
            return new Plan(Step.FALL_BACK_TO_EXTERNAL_COMMIT, null, null,
                    "no paginated_epoch_identifier — the CF cannot back-fill this client "
                    + "(§7.10.3 gates the list on the requested epoch being known and on the "
                    + "requester having been a participant throughout)");
        }
        if (response.committedControlMessages.isEmpty()) {
            // A page that names an end but carries nothing cannot advance us; looping would
            // re-request it.
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
     * @param allApplied false if any message failed; the whole enhanced attempt is then abandoned
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
     * RCC.16 §10.1.2's IMDN rule: no IMDN for a message applied only through this path; a later
     * MSRP copy of it is idempotent and acknowledged as a success.
     *
     * @param appliedViaEnhancedSelfHeal whether this message reached us through the replay path
     * @param alsoReceivedOnMsrp         whether the same message arrived (or later arrives) on MSRP
     */
    public static boolean shouldSendImdn(final boolean appliedViaEnhancedSelfHeal,
            final boolean alsoReceivedOnMsrp) {
        if (!appliedViaEnhancedSelfHeal) return true;
        return alsoReceivedOnMsrp;
    }
}
