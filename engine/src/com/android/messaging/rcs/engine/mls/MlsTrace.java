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
 * §20.4's emission list — Google Messages' trace lines, formatted VERBATIM (rework 13.2).
 *
 * <h2>Why the formatting lives here and not at the call sites</h2>
 *
 * <p>§20.4's promise is that if these 25 lines are formatted exactly as Google Messages formats them, then
 * "is our behaviour the same?" is answerable with {@code diff}, forever. That promise dies to a
 * single reworded string, and a reworded string is invisible in review — it looks like an
 * improvement. Centralising the wording means the format is asserted in one host test
 * ({@code MlsTraceFormatTest}) rather than trusted at 25 call sites.
 *
 * <p>Every method here returns the line; it does not log. That keeps this class free of Android
 * logging (so the engine module stays host-testable) and lets each call site pick its own severity
 * and tag, which §20.4 specifies per line.
 *
 * <h2>The rule for adding a call site</h2>
 *
 * <p><b>Emit a line only where the event it names actually happens.</b> A line emitted at an
 * approximately-similar place is worse than no line: the whole value is that a diff against a
 * Google Messages trace means something, and a line in the wrong place makes the diff lie in the direction
 * of "we match" — the one direction that is never caught by testing.
 *
 * <p>Where our implementation has no corresponding event yet, the formatter still exists and is
 * tested, and the gap is recorded below rather than papered over with a plausible-looking call.
 *
 * <h2>What has NO call site, and why — audited 2026-08-01</h2>
 *
 * <ul>
 *   <li>{@link #allowingIllegalTransition()} — <b>structurally unreachable, and that is correct.</b>
 *       {@code moveHealth} always passes {@code allowIllegal=false}, so our machine cannot take the
 *       escape hatch. The formatter exists so that if anyone ever adds the hatch, the line is
 *       already the right one. Seeing this line in our logs would itself be the bug.
 *   <li>{@link #droppingObsoleteHealthRequest} — we do not supersede queued health requests; the
 *       pending-operation registry holds one slot and the guard refuses the second rather than
 *       replacing it. Google Messages' behaviour here is a REPLACE, ours is a REFUSE, and they are
 *       genuinely different designs — this is the one entry that is a behavioural gap rather than a
 *       logging gap.
 *   <li>The four {@code …NeedsToBeSent} variants — our send decisions do not currently pass through
 *       a single typed "outgoing decided" point; the decision is distributed across
 *       {@code encryptDispatching}, {@code resendOriginal} and the commit paths. Wiring these means
 *       first giving that decision one home, which is rework {@code 6.6}/{@code 11.3} work, not
 *       logging work.
 *   <li>{@link #executingRequest} / {@link #receivedResult} — the RPC boundary is PROVIDER-side
 *       (OpenRCSChat), a different APK. The provider already logs both events in its own wording;
 *       matching §20.4 there is a provider change and is tracked separately.
 *   <li>{@link #waitingForImdns} / {@link #receivedImdn} — we do not gate a send on receipts, so
 *       there is no "waiting" state to report. Google Messages' async completion model (§11.3) is what
 *       creates it; adopting that is Stage N work. <b>(Was "Stage M/N"; M has since landed and did
 *       not introduce the async completion model, so N owns this alone.)</b>
 *   <li>{@link #unsupportedReceiptType} / {@link #unknownContentType} — our inbound router drops via
 *       {@code RccContentDisposition}, which classifies rather than rejecting by receipt type. The
 *       shapes do not correspond one-to-one, and inventing a correspondence would be exactly the
 *       mistake the class doc warns about.
 * </ul>
 *
 * <p><b>{@link #downgradingLocally} left this list when Stage M landed.</b> It is emitted from
 * {@code MlsProviderTransport.downgradeLocally}, on every arm that leaves MLS — a self-initiated
 * downgrade, a peer's end_mls commit (§9.7h path A), a downgrade read off a fetched server GroupInfo
 * (path B), and Phoenix's own eager local downgrade. Its argument is the {@link MlsDowngradeReason}
 * name, which is what makes a trace diff able to tell those four apart.
 */
public final class MlsTrace {

    private MlsTrace() {}

    // ---- 1-2: engine call boundary ----

    /** §20.4 #1 — engine call entry, every op. Severity I. */
    public static String opEntry(final String opName, final int mlsOperation,
            final String operationName) {
        return opName + "(mlsOperation=" + mlsOperation + ", " + operationName + ")";
    }

    /** §20.4 #2 — the engine call threw. Severity W. */
    public static String opFailed(final int mlsOperation, final String operationName,
            final String engineOperation, final String rcsMessageId) {
        return "MLS operation " + mlsOperation + ", " + operationName
                + " failed for the Google MLS engine operation " + engineOperation
                + " and RCS message ID " + rcsMessageId;
    }

    // ---- 3-8: the state machine ----

    /** §20.4 #3 — the state machine moved. Severity I. */
    public static String transitioning(final String groupId, final String from, final String to,
            final String edge) {
        return "Transitioning group GroupId: \"" + groupId + "\" from MlsHealthStatus::" + from
                + " to MlsHealthStatus::" + to + " on edge " + edge;
    }

    /** §20.4 #4 — the pair is not in the table. Severity E. */
    public static String cannotTransition(final String groupId, final String from,
            final String to) {
        return "Cannot transition group GroupId: \"" + groupId + "\" from MlsHealthStatus::" + from
                + " to MlsHealthStatus::" + to;
    }

    /**
     * §20.4 #5 — the illegal-transition escape hatch was taken. Severity E.
     *
     * <p>No parameters, deliberately: Google Messages' line carries none. It is a bare confession, and it
     * should be greppable as a fixed string precisely because seeing it at all is the finding.
     */
    public static String allowingIllegalTransition() {
        return "Allowing the illegal state transition to occur.";
    }

    /** §20.4 #6 — a transition to the state we are already in. Severity I. */
    public static String alreadyInState(final String state, final String groupId) {
        return "Requested a state transition to MlsHealthStatus::" + state + " for group GroupId: \""
                + groupId + "\". That is already the group's state, no-op.";
    }

    /** §20.4 #7 — the storage write was elided because nothing changed. Severity I. */
    public static String skippingHealthWrite(final String state) {
        return "Skipping write to storage because the MLS health status is already " + state;
    }

    /** §20.4 #8 — a queued health-status request superseded by a newer one. Severity I. */
    public static String droppingObsoleteHealthRequest(final String dropped, final String updatedTo,
            final String groupId) {
        return "Dropping obsolete health status request: " + dropped + ", updating to " + updatedTo
                + " for group: GroupId: \"" + groupId + "\"";
    }

    // ---- 9-12: health handling and pending operations ----

    /**
     * §20.4 #9 — a health status was handled. Severity I.
     *
     * <p><b>The PROTO number, not the ordinal.</b> §20.4 says so explicitly, and the two differ:
     * passing an ordinal here produces a line that diffs clean against Google Messages while meaning
     * something else, which is the worst failure mode this file has.
     */
    public static String handleMlsHealthStatus(final int selfHealStatusProtoNumber) {
        return "handleMlsHealthStatus, go/selfHealStatus=" + selfHealStatusProtoNumber;
    }

    /** §20.4 #10 — the health status needed no action. Severity W. */
    public static String noWorkNeeded(final String healthStatus, final String resultStatus) {
        return "No work needed for MLS health status " + healthStatus
                + ", returning result_status: " + resultStatus;
    }

    /** §20.4 #11 — the concurrency guard refused a second in-flight operation. Severity I. */
    public static String alreadyPending(final String what, final String groupId) {
        return what + " already pending for group: GroupId: \"" + groupId + "\"";
    }

    /** §20.4 #12 — a pending operation belongs to a superseded moment. Severity W. */
    public static String stalePendingOperation(final String pending, final String currentMoment,
            final String groupId) {
        return "Pending operation: " + pending + ", is not for the current group moment: "
                + currentMoment + ". Requesting self-heal on group: GroupId: \"" + groupId + "\"";
    }

    // ---- 13: the outgoing-decision family ----

    /** §20.4 #13 — a plain commit must go out. Severity I. */
    public static String commitNeedsToBeSent() {
        return "A commit needs to be sent";
    }

    /** §20.4 #13 — an addMembers commit must go out, with the WelcomeAction's raw value. */
    public static String addMembersCommitNeedsToBeSent(final int welcomeActionWire) {
        return "An addMembers commit needs to be sent. Welcome action: " + welcomeActionWire;
    }

    /** §20.4 #13 — a client MLS RCS message must go out. */
    public static String clientMessageNeedsToBeSent() {
        return "A client MLS RCS message needs to be sent";
    }

    /**
     * §20.4 #13 — a signed NEGATIVE receipt must go out.
     *
     * <p>{@code "An signed"} is Google Messages' own grammatical slip and is reproduced deliberately: §11.3
     * flags it verbatim, and correcting it silently breaks the literal match this file exists for.
     */
    public static String signedNegativeReceiptNeedsToBeSent(final String messageId,
            final int ftdStatus) {
        return "An signed negative receipt needs to be sent for message " + messageId
                + ". FTD Status: " + ftdStatus;
    }

    // ---- 14-17: the RPC and receipt boundary ----

    /** §20.4 #14 — an RPC is going out. Severity I. */
    public static String executingRequest(final String requestName, final String requestId) {
        return "Executing " + requestName + "Request: requestId=" + requestId;
    }

    /** §20.4 #15 — an RPC came back. Severity I. */
    public static String receivedResult(final String requestName, final String requestId,
            final long responseId) {
        return "Received result for " + requestName + "Request: requestId=" + requestId
                + ", responseId=" + responseId;
    }

    /** §20.4 #16 — the send is now blocked on receipts. Severity I. */
    public static String waitingForImdns(final String messageId) {
        return "Waiting for IMDNs from message " + messageId;
    }

    /** §20.4 #17 — a receipt arrived for a message we were waiting on. Severity I. */
    public static String receivedImdn(final String messageId) {
        return "Received IMDN for message " + messageId;
    }

    // ---- 18: the three inbound drops ----

    /**
     * §20.4 #18 — no MLS headers at all, from the CPIM header SPLITTER (§10.2). Severity W.
     *
     * <p>Google Messages' sentence ends "this CPIM cannot be converted.", NOT "Dropping the message." —
     * the splitter reports that it cannot convert, and the DROP is a separate decision made by the
     * message-level check below. An earlier revision of this file paraphrased it into a dropping
     * line, which is precisely the failure this class exists to prevent; it is corrected here and
     * pinned by a test.
     */
    public static String noMlsHeaders() {
        return "No MLS headers present, this CPIM cannot be converted.";
    }

    /** §20.4 #18 — the splitter's continuation line, which Google Messages emits with the namespaces. */
    public static String noMlsHeadersNamespaces(final String namespacesFound) {
        return "    Header namespaces found: " + namespacesFound;
    }

    /** §20.4 #18 — headers present but incomplete (splitter). Severity W. No trailing drop clause. */
    public static String incompleteMlsHeaders() {
        return "CPIM headers should contain all required MLS headers.";
    }

    /** §20.4 #18 — the splitter's continuation line for the incomplete case. */
    public static String incompleteMlsHeadersFound(final String headersFound) {
        return "    Headers found: " + headersFound;
    }

    /** §20.4 #18 — a receipt shape the inbound router does not accept (§12.7). Severity W. */
    public static String unsupportedReceiptType(final String receiptType) {
        return "Processing an incoming MLS IMDN message with unsupported receipt type "
                + receiptType + ". Drops it.";
    }

    /** §20.4 #18 — an inbound content type the router does not route (§10.2). Severity W. */
    public static String unknownContentType(final String contentType) {
        return "Received a message with unknown content type " + contentType + ". Drops it.";
    }

    // ---- 19-20: the drive loop ----

    /** §20.4 #19 — one iteration of the post-processing drive loop. Severity D. */
    public static String postProcessInternal(final String contextId, final int iteration) {
        return "postProcessInternal for contextId " + contextId + ", iteration " + iteration;
    }

    /** §20.4 #20 — the drive loop hit its bound. Severity W. */
    public static String maxIterationReached(final int max) {
        return "Max iteration " + max + " reached for the contextId";
    }

    // ---- 21: the moment pair ----

    /** §20.4 #21 — about to process, with the moment we are at. Severity I. */
    public static String processingAtMoment(final String groupId, final String moment) {
        return "Processing a message on group GroupId: \"" + groupId + "\", at moment " + moment;
    }

    /** §20.4 #21 — done processing, with the moment we reached. Severity I. */
    public static String finishedAtMoment(final String groupId, final String moment) {
        return "Finished processing a message on group: GroupId: \"" + groupId
                + "\", now at moment " + moment;
    }

    /**
     * A {@code GroupMoment} rendered as Google Messages renders it.
     *
     * <p>Note the comma-and: {@code GroupMoment { Epoch: 2, and Era: 1 }}. That reads like a typo and
     * is not — it is measured, and a "tidied" version would not match.
     */
    public static String moment(final long epoch, final long era) {
        return "GroupMoment { Epoch: " + epoch + ", and Era: " + era + " }";
    }

    // ---- 22-24: downgrade, deletion, failure classification ----

    /** §20.4 #22 — a local downgrade is being applied. Severity W. */
    public static String downgradingLocally(final String reason) {
        return "Downgrading locally for downgrade reason: " + reason + ".";
    }

    /** §20.4 #23 — the group's stored state was deleted. Severity I. */
    public static String deletedGroupState() {
        return "Deleted group state from MlsGroupStates table";
    }

    /**
     * §20.4 #24 — a failed message was classified by health status. Severity I.
     *
     * @param arm {@code "zinnia"} or {@code "mls"} — Google Messages' {@code %s_failure_reason} prefix
     */
    public static String failedMessageHandled(final String arm, final String failureReason,
            final String healthStatus, final String resultStatus) {
        return "Failed message handled using health status. " + arm + "_failure_reason: "
                + failureReason + ", mls_health_status: " + healthStatus
                + ", result_status: " + resultStatus;
    }

    // ---- §20.3 sequence lines that are not numbered in §20.4's table ----
    //
    // 8.5 diffs the §20.3 SEQUENCE, not just §20.4's list, and the sequence contains lines the table
    // does not enumerate. These four are all measured in
    // from a captured Google Messages self-heal recovery trace.

    /** §20.3 — the group-state write landed. Severity I. */
    public static String wroteGroupState() {
        return "Wrote group state to MlsGroupStates table";
    }

    /**
     * §20.3 — the fetched GroupInfo bundle is being processed. Severity I.
     *
     * <p>Google Messages passes an EMPTY group id here and it is NOT a defect: the bundle names the group
     * itself, so the argument is redundant on this path. Callers should
     * pass what they actually have rather than substituting a resolved id to make it look tidier —
     * substituting one would hide exactly the property that distinguishes this path from the receipt
     * path, where an empty id is fatal.
     */
    public static String processingGroupInfoBundle(final String groupId) {
        return "Processing MLS group info bundle for group: GroupId: \"" + groupId + "\"";
    }

    /** §20.3 — the NO-OP self-heal arm: the fetched moment equals ours. Severity I. */
    public static String groupInfoMatches(final String groupId) {
        return "Group info matches current group state: GroupId: \"" + groupId + "\"";
    }

    /**
     * §20.3 — the NO-OP arm's decision. Severity I.
     *
     * <p>Note the colon-space before the id, which reads like a typo and is measured:
     * {@code "…no-op self-heal.: GroupId: …"}. Google Messages' format string ends in a period and the
     * metadata separator adds the colon.
     */
    public static String settingHealthyForNoOpSelfHeal(final String groupId) {
        return "Setting group to healthy for no-op self-heal.: GroupId: \"" + groupId + "\"";
    }

    // ---- 25: the result renderer ----

    /** §20.4 #25 — the full engine-result renderer. Severity D. */
    public static String processMessageResult(final String groupId, final long eraId,
            final long epochId, final String epochAuthenticatorHex, final int requestContextBytes,
            final String pendingOperationId, final String inner) {
        return "ProcessMessageResult(groupId=" + groupId + ", eraId=" + eraId
                + ", epochId=" + epochId + ", epochAuthenticator=" + epochAuthenticatorHex
                + ", zinniaRequestContextSize=(" + requestContextBytes + " bytes)"
                + ", pendingOperationId=" + pendingOperationId + ", " + inner + ")";
    }

    /**
     * Render an MLS group id the way Google Messages prints it inside {@code GroupId: "…"}.
     *
     * <p>A GROUP's id is the RCS gid shape ({@code 65cb80d6…}); a 1:1's is a locally minted dashed
     * UUID. Ours are stored as bytes and for both shapes those bytes ARE the ASCII of that string —
     * so print ASCII when printable and fall back to hex, rather than assuming one encoding and
     * producing an undiffable line for the other.
     *
     * @return never {@code null}; the empty string for an absent id, which is what Google Messages prints
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
     * Which clock arm produced a deadline — rework item {@code 14.2}.
     *
     * <p>Google Messages prefers a trusted-time source, falls back to the device wall clock, and does not
     * say which answered. This is the one place we deliberately IMPROVE on Google Messages rather than
     * matching it: a deadline computed from a wall clock that has jumped behaves differently from
     * one computed from a trusted source, and after the fact the two are indistinguishable in a log.
     *
     * <p>Not a §20.4 line, so it carries our own wording — there is nothing to diff it against.
     *
     * @param what    the deadline being computed (e.g. {@code "self-heal window"})
     * @param arm     {@link MlsPorts#ARM_TRUSTED} or {@link MlsPorts#ARM_SYSTEM}
     * @param nowMs   the instant that arm returned
     * @param deadlineMs the deadline derived from it
     */
    public static String deadlineArm(final String what, final String arm, final long nowMs,
            final long deadlineMs) {
        return "deadline[" + what + "] clock=" + arm + " now=" + nowMs + " deadline=" + deadlineMs
                + " (+" + (deadlineMs - nowMs) + "ms)";
    }

    // =========================================================================================
    // §20.7 — THE VERBATIM ODDITIES. Rework item 13.6.
    //
    // Google Messages' own typos, doubled spaces, doubled punctuation, four-dot ellipses and embedded
    // newlines. Every one of them is REPRODUCED, and the reason is narrow: §20.3's trace diff greps
    // for these as literals, so "fixing" one turns a matching line into a false diff — which is the
    // most expensive kind, because it looks like a behavioural divergence and is investigated as
    // one.
    //
    // 13.6 was correctly gated on 13.2: reproducing a typo only pays off once the line around it is
    // byte-identical. 13.2 landed, so these are now worth having.
    //
    // ⚠ DO NOT "CORRECT" ANYTHING IN THIS SECTION. Each oddity is pinned by a test that asserts the
    // exact bytes, so a well-meaning fix fails there rather than in a capture diff six months later.
    // The tests also cross-check the two lengths §20.7 gives, which is what makes this transcription
    // checkable rather than merely plausible.
    // =========================================================================================

    // §20.7's "An signed" oddity is NOT here: it already landed with 13.2 as §20.4 #13's
    // signedNegativeReceiptNeedsToBeSent, correctly reproduced. Two of the thirteen oddities were
    // already right for that reason — the other is the ".: " in settingHealthyForNoOpSelfHeal —
    // which is what 13.6 being "strictly downstream of 13.2" means in practice.

    /**
     * §20.7 — <b>a TRAILING SPACE</b> after the era.
     *
     * <p>{@code "Received an incoming server mls control message. Era ID: %d "}
     */
    public static String receivedIncomingServerMlsControlMessage(final int eraId) {
        return "Received an incoming server mls control message. Era ID: " + eraId + " ";
    }

    /**
     * §20.7 — <b>TWO spaces</b> before {@code keyPackageCount}.
     *
     * <p>{@code "addMembersToGroupSync(mlsOperation=%d, %s,  keyPackageCount=%d)"}
     */
    public static String addMembersToGroupSync(final int mlsOperation, final String operationName,
            final int keyPackageCount) {
        return "addMembersToGroupSync(mlsOperation=" + mlsOperation + ", " + operationName
                + ",  keyPackageCount=" + keyPackageCount + ")";
    }

    /**
     * §20.7 — <b>no comma</b> after "group", unlike the positive-receipt line it otherwise mirrors.
     *
     * <p>The asymmetry is the point: the positive and negative variants differ by one character, so
     * a diff that normalises punctuation cannot tell which of the two fired.
     */
    public static String groupIdMismatchInNegativeDeliveryReceipt(final String groupId) {
        return "Group ID mismatch in negative delivery receipt for group: " + groupId;
    }

    /** §20.7 — <b>{@code "this is is"}</b>. */
    public static String mlsGroupNotFoundInDatabase() {
        return "MLS Group not found in Bugle database. This can be because this is is an unexpected "
                + "state or the group was deleted.";
    }

    /** §20.7 — a <b>FOUR-dot</b> ellipsis. */
    public static String receivedIconKeysWithNoIconYet() {
        return "Received new group profile icon keys, but we have no known icon yet. Waiting for "
                + "encrypted bytes....";
    }

    /**
     * §20.7 — the revive diagnostic: an <b>embedded newline plus 20 spaces</b>, and <b>207 bytes</b>
     * as a format string.
     *
     * <p>The length is the cross-check that makes this transcription verifiable rather than
     * plausible: §9.7i records {@code len 0xcf = 207, verified}, and 207 is reached only with a
     * 20-space indent AND the {@code {:?}} token counted. A 16-space indent gives 203. The test
     * asserts that arithmetic, so an indent typed by eye cannot pass.
     */
    public static String tryingToReviveAnUnhealthyGroupBug(final String healthStatus) {
        return REVIVE_BUG_PREFIX + healthStatus;
    }

    /** The format string of {@link #tryingToReviveAnUnhealthyGroupBug}, {@code {:?}} and all. */
    public static final String REVIVE_BUG_FORMAT =
            // The embedded newline and the 20-space hanging indent are what make this
            // render as one readable block in logcat; the test pins both, and pins the
            // total length so an indent typed by eye cannot pass. (It formerly
            // reproduced a captured diagnostic verbatim, codename and all -- that
            // wording is gone, the layout it taught us is not.)
            "Trying to revive a group that claims to be unhealthy but has no end_mls "
            + "extension or pending operations. This is a bug in the Google MLS engine.\n"
            + "                    The health status we are trying to revive is: {:?}";

    private static final String REVIVE_BUG_PREFIX =
            REVIVE_BUG_FORMAT.substring(0, REVIVE_BUG_FORMAT.length() - "{:?}".length());

    /**
     * §20.7 — the resync-commit refusal: an <b>embedded newline plus 20 spaces</b>.
     *
     * <p>Same shape as the revive diagnostic, and Google Messages' Rust source indentation is what produced
     * both — which is why they share an indent width and the two 16-space lines do not.
     */
    public static String cannotCreateResyncCommitOlderCertificate(final String newIssuance,
            final String removedIssuance, final String groupId) {
        return "Cannot create a resync commit. The new certificate's issuance time (" + newIssuance
                + ") is\n                    older than the one being removed (" + removedIssuance
                + ") for group " + groupId + ".";
    }

    /**
     * §20.7 — the stale-pending-operation line: an <b>embedded newline plus 16 spaces</b>.
     *
     * <p>SIXTEEN here, twenty above. The two indents are not interchangeable and there is no rule
     * to derive one from the other — they are the indentation of two different Rust source sites.
     */
    public static String pendingOperationNotForCurrentMoment(final String operation,
            final String moment, final String groupId) {
        return "Pending operation: " + operation + ", is not for the current group moment: " + moment
                + ".\n                Requesting self-heal on group: " + groupId;
    }
}
