/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * MLS trace lines, formatted byte-for-byte as other clients format them so traces can be compared
 * with {@code diff}. Methods return the line and do not log; each call site picks the severity
 * noted on the method. Emit a line only where the event it names happens, since a misplaced line
 * makes a diff falsely match. Pinned by {@code MlsTraceFormatTest}.
 *
 * <p>Formatters with no call site: {@link #allowingIllegalTransition()} (our machine never takes
 * the illegal-transition escape), {@link #droppingObsoleteHealthRequest} (we refuse a second health
 * request rather than replacing the first), the {@code …NeedsToBeSent} family (no single outgoing
 * decision point yet), {@link #executingRequest} and {@link #receivedResult} (the RPC boundary is
 * in the provider), {@link #waitingForImdns} and {@link #receivedImdn} (sends are not gated on
 * receipts), and {@link #unsupportedReceiptType} and {@link #unknownContentType} (the inbound
 * router classifies differently).
 */
public final class MlsTrace {

    private MlsTrace() {}

    /** Engine call entry, every op. Severity I. */
    public static String opEntry(final String opName, final int mlsOperation,
            final String operationName) {
        return opName + "(mlsOperation=" + mlsOperation + ", " + operationName + ")";
    }

    /** The engine call threw. Severity W. */
    public static String opFailed(final int mlsOperation, final String operationName,
            final String engineOperation, final String rcsMessageId) {
        return "MLS operation " + mlsOperation + ", " + operationName
                + " failed for the Google MLS engine operation " + engineOperation
                + " and RCS message ID " + MlsMessageId.forLog(rcsMessageId);
    }

    /** The state machine moved. Severity I. */
    public static String transitioning(final String groupId, final String from, final String to,
            final String edge) {
        return "Transitioning group GroupId: \"" + groupId + "\" from MlsHealthStatus::" + from
                + " to MlsHealthStatus::" + to + " on edge " + edge;
    }

    /** The pair is not in the transition table. Severity E. */
    public static String cannotTransition(final String groupId, final String from,
            final String to) {
        return "Cannot transition group GroupId: \"" + groupId + "\" from MlsHealthStatus::" + from
                + " to MlsHealthStatus::" + to;
    }

    /** The illegal-transition escape was taken. Severity E. A fixed string with no parameters. */
    public static String allowingIllegalTransition() {
        return "Allowing the illegal state transition to occur.";
    }

    /** A transition to the state we are already in. Severity I. */
    public static String alreadyInState(final String state, final String groupId) {
        return "Requested a state transition to MlsHealthStatus::" + state
                + " for group GroupId: \""
                + groupId + "\". That is already the group's state, no-op.";
    }

    /** The storage write was skipped because nothing changed. Severity I. */
    public static String skippingHealthWrite(final String state) {
        return "Skipping write to storage because the MLS health status is already " + state;
    }

    /** A queued health-status request superseded by a newer one. Severity I. */
    public static String droppingObsoleteHealthRequest(final String dropped, final String updatedTo,
            final String groupId) {
        return "Dropping obsolete health status request: " + dropped + ", updating to " + updatedTo
                + " for group: GroupId: \"" + groupId + "\"";
    }

    /** A health status was handled. Severity I. Takes the proto number, not the ordinal. */
    public static String handleMlsHealthStatus(final int selfHealStatusProtoNumber) {
        return "handleMlsHealthStatus, go/selfHealStatus=" + selfHealStatusProtoNumber;
    }

    /** The health status needed no action. Severity W. */
    public static String noWorkNeeded(final String healthStatus, final String resultStatus) {
        return "No work needed for MLS health status " + healthStatus
                + ", returning result_status: " + resultStatus;
    }

    /** The concurrency guard refused a second in-flight operation. Severity I. */
    public static String alreadyPending(final String what, final String groupId) {
        return what + " already pending for group: GroupId: \"" + groupId + "\"";
    }

    /** A pending operation belongs to a superseded moment. Severity W. */
    public static String stalePendingOperation(final String pending, final String currentMoment,
            final String groupId) {
        return "Pending operation: " + pending + ", is not for the current group moment: "
                + currentMoment + ". Requesting self-heal on group: GroupId: \"" + groupId + "\"";
    }

    /** A plain commit must go out. Severity I. */
    public static String commitNeedsToBeSent() {
        return "A commit needs to be sent";
    }

    /** An addMembers commit must go out, with the WelcomeAction's raw value. */
    public static String addMembersCommitNeedsToBeSent(final int welcomeActionWire) {
        return "An addMembers commit needs to be sent. Welcome action: " + welcomeActionWire;
    }

    /** A client MLS RCS message must go out. */
    public static String clientMessageNeedsToBeSent() {
        return "A client MLS RCS message needs to be sent";
    }

    /** A signed negative receipt must go out. {@code "An signed"} is reproduced verbatim. */
    public static String signedNegativeReceiptNeedsToBeSent(final String messageId,
            final int ftdStatus) {
        return "An signed negative receipt needs to be sent for message "
                + MlsMessageId.forLog(messageId)
                + ". FTD Status: " + ftdStatus;
    }

    /** An RPC is going out. Severity I. */
    public static String executingRequest(final String requestName, final String requestId) {
        return "Executing " + requestName + "Request: requestId=" + requestId;
    }

    /** An RPC came back. Severity I. */
    public static String receivedResult(final String requestName, final String requestId,
            final long responseId) {
        return "Received result for " + requestName + "Request: requestId=" + requestId
                + ", responseId=" + responseId;
    }

    /** The send is blocked on receipts. Severity I. */
    public static String waitingForImdns(final String messageId) {
        return "Waiting for IMDNs from message " + MlsMessageId.forLog(messageId);
    }

    /** A receipt arrived for a message we were waiting on. Severity I. */
    public static String receivedImdn(final String messageId) {
        return "Received IMDN for message " + MlsMessageId.forLog(messageId);
    }

    /**
     * No MLS headers at all, from the CPIM header splitter. Severity W. The splitter reports that
     * it cannot convert; dropping is a separate decision, so the line has no drop clause.
     */
    public static String noMlsHeaders() {
        return "No MLS headers present, this CPIM cannot be converted.";
    }

    /** The splitter's continuation line listing the namespaces found. */
    public static String noMlsHeadersNamespaces(final String namespacesFound) {
        return "    Header namespaces found: " + namespacesFound;
    }

    /** Headers present but incomplete (splitter). Severity W. No drop clause. */
    public static String incompleteMlsHeaders() {
        return "CPIM headers should contain all required MLS headers.";
    }

    /** The splitter's continuation line for the incomplete case. */
    public static String incompleteMlsHeadersFound(final String headersFound) {
        return "    Headers found: " + headersFound;
    }

    /** A receipt type the inbound router does not accept. Severity W. */
    public static String unsupportedReceiptType(final String receiptType) {
        return "Processing an incoming MLS IMDN message with unsupported receipt type "
                + receiptType + ". Drops it.";
    }

    /** An inbound content type the router does not route. Severity W. */
    public static String unknownContentType(final String contentType) {
        return "Received a message with unknown content type " + contentType + ". Drops it.";
    }

    /** One iteration of the post-processing drive loop. Severity D. */
    public static String postProcessInternal(final String contextId, final int iteration) {
        return "postProcessInternal for contextId " + contextId + ", iteration " + iteration;
    }

    /** The drive loop hit its bound. Severity W. */
    public static String maxIterationReached(final int max) {
        return "Max iteration " + max + " reached for the contextId";
    }

    /** About to process, with the moment we are at. Severity I. */
    public static String processingAtMoment(final String groupId, final String moment) {
        return "Processing a message on group GroupId: \"" + groupId + "\", at moment " + moment;
    }

    /** Done processing, with the moment we reached. Severity I. */
    public static String finishedAtMoment(final String groupId, final String moment) {
        return "Finished processing a message on group: GroupId: \"" + groupId
                + "\", now at moment " + moment;
    }

    /** A {@code GroupMoment}, including the verbatim {@code ", and Era"}. */
    public static String moment(final long epoch, final long era) {
        return "GroupMoment { Epoch: " + epoch + ", and Era: " + era + " }";
    }

    /** A local downgrade is being applied, with the {@link MlsDowngradeReason} name. Severity W. */
    public static String downgradingLocally(final String reason) {
        return "Downgrading locally for downgrade reason: " + reason + ".";
    }

    /** The group's stored state was deleted. Severity I. */
    public static String deletedGroupState() {
        return "Deleted group state from MlsGroupStates table";
    }

    /**
     * A failed message was classified by health status. Severity I.
     *
     * @param arm {@code "zinnia"} or {@code "mls"}, the {@code %s_failure_reason} prefix
     */
    public static String failedMessageHandled(final String arm, final String failureReason,
            final String healthStatus, final String resultStatus) {
        return "Failed message handled using health status. " + arm + "_failure_reason: "
                + failureReason + ", mls_health_status: " + healthStatus
                + ", result_status: " + resultStatus;
    }

    // Self-heal recovery sequence lines outside the numbered list.

    /** The group-state write landed. Severity I. */
    public static String wroteGroupState() {
        return "Wrote group state to MlsGroupStates table";
    }

    /**
     * The fetched GroupInfo bundle is being processed. Severity I. The group id may be empty on
     * this path (the bundle names the group); pass what you have rather than a resolved id.
     */
    public static String processingGroupInfoBundle(final String groupId) {
        return "Processing MLS group info bundle for group: GroupId: \"" + groupId + "\"";
    }

    /** The no-op self-heal arm: the fetched moment equals ours. Severity I. */
    public static String groupInfoMatches(final String groupId) {
        return "Group info matches current group state: GroupId: \"" + groupId + "\"";
    }

    /** The no-op arm's decision. Severity I. The {@code ".: "} before the id is verbatim. */
    public static String settingHealthyForNoOpSelfHeal(final String groupId) {
        return "Setting group to healthy for no-op self-heal.: GroupId: \"" + groupId + "\"";
    }

    /** The full engine-result renderer. Severity D. */
    public static String processMessageResult(final String groupId, final long eraId,
            final long epochId, final String epochAuthenticatorHex, final int requestContextBytes,
            final String pendingOperationId, final String inner) {
        return "ProcessMessageResult(groupId=" + groupId + ", eraId=" + eraId
                + ", epochId=" + epochId + ", epochAuthenticator=" + epochAuthenticatorHex
                + ", zinniaRequestContextSize=(" + requestContextBytes + " bytes)"
                + ", pendingOperationId=" + pendingOperationId + ", " + inner + ")";
    }

    /**
     * An MLS group id as printed inside {@code GroupId: "…"}: ASCII when printable (both group and
     * 1:1 ids are stored as ASCII bytes), else hex.
     *
     * @return never {@code null}; empty for an absent id
     */
    public static String groupId(final byte[] mlsGroupId) {
        if (mlsGroupId == null || mlsGroupId.length == 0) return "";
        boolean printable = true;
        for (final byte b : mlsGroupId) {
            if (b < 0x20 || b > 0x7e) { printable = false; break; }
        }
        if (printable) {
            return new String(mlsGroupId, java.nio.charset.StandardCharsets.US_ASCII);
        }
        final StringBuilder sb = new StringBuilder(mlsGroupId.length * 2);
        for (final byte b : mlsGroupId) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Which clock produced a deadline. Our own line, not a verbatim one: a deadline from a jumped
     * wall clock behaves differently from a trusted one, and the log should say which.
     *
     * @param what       the deadline being computed (e.g. {@code "self-heal window"})
     * @param arm        {@link MlsPorts#ARM_TRUSTED} or {@link MlsPorts#ARM_SYSTEM}
     * @param nowMs      the instant that arm returned
     * @param deadlineMs the deadline derived from it
     */
    public static String deadlineArm(final String what, final String arm, final long nowMs,
            final long deadlineMs) {
        return "deadline[" + what + "] clock=" + arm + " now=" + nowMs + " deadline=" + deadlineMs
                + " (+" + (deadlineMs - nowMs) + "ms)";
    }

    // Verbatim oddities: typos, doubled spaces and punctuation, four-dot ellipses and embedded
    // newlines are reproduced exactly because trace diffs match them as literals. Each is pinned by
    // a test on its exact bytes; do not correct them.

    /** A trailing space after the era: {@code "… Era ID: %d "}. */
    public static String receivedIncomingServerMlsControlMessage(final int eraId) {
        return "Received an incoming server mls control message. Era ID: " + eraId + " ";
    }

    /** Two spaces before {@code keyPackageCount}. */
    public static String addMembersToGroupSync(final int mlsOperation, final String operationName,
            final int keyPackageCount) {
        return "addMembersToGroupSync(mlsOperation=" + mlsOperation + ", " + operationName
                + ",  keyPackageCount=" + keyPackageCount + ")";
    }

    /**
     * No comma after "group", unlike the positive-receipt line, so the two stay distinguishable.
     */
    public static String groupIdMismatchInNegativeDeliveryReceipt(final String groupId) {
        return "Group ID mismatch in negative delivery receipt for group: " + groupId;
    }

    /** Contains {@code "this is is"}. */
    public static String mlsGroupNotFoundInDatabase() {
        return "MLS Group not found in Bugle database. This can be because this is is an unexpected "
                + "state or the group was deleted.";
    }

    /** A four-dot ellipsis. */
    public static String receivedIconKeysWithNoIconYet() {
        return "Received new group profile icon keys, but we have no known icon yet. Waiting for "
                + "encrypted bytes....";
    }

    /**
     * The revive diagnostic: an embedded newline plus 20 spaces; the format string is 207 bytes,
     * {@code {:?}} included, which the test asserts.
     */
    public static String tryingToReviveAnUnhealthyGroupBug(final String healthStatus) {
        return REVIVE_BUG_PREFIX + healthStatus;
    }

    /** The format string of {@link #tryingToReviveAnUnhealthyGroupBug}, {@code {:?}} and all. */
    public static final String REVIVE_BUG_FORMAT =
            // The newline and 20-space hanging indent render as one block in logcat; the test pins
            // both and the total length.
            "Trying to revive a group that claims to be unhealthy but has no end_mls "
            + "extension or pending operations. This is a bug in the Google MLS engine.\n"
            + "                    The health status we are trying to revive is: {:?}";

    private static final String REVIVE_BUG_PREFIX =
            REVIVE_BUG_FORMAT.substring(0, REVIVE_BUG_FORMAT.length() - "{:?}".length());

    /** The resync-commit refusal: an embedded newline plus 20 spaces. */
    public static String cannotCreateResyncCommitOlderCertificate(final String newIssuance,
            final String removedIssuance, final String groupId) {
        return "Cannot create a resync commit. The new certificate's issuance time (" + newIssuance
                + ") is\n                    older than the one being removed (" + removedIssuance
                + ") for group " + groupId + ".";
    }

    /** The stale-pending-operation line: an embedded newline plus 16 spaces (not 20). */
    public static String pendingOperationNotForCurrentMoment(final String operation,
            final String moment, final String groupId) {
        return "Pending operation: " + operation + ", is not for the current group moment: "
                + moment + ".\n                Requesting self-heal on group: " + groupId;
    }
}
