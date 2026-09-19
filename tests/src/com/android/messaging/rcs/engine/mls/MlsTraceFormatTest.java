/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MlsTrace} lines, pinned character for character so our traces can be diffed against
 * another client's. Every expectation is a literal: a reworded line, or a fixed typo, voids the
 * diff. Lines seen in real traces are copied from them.
 */
public class MlsTraceFormatTest {

    /** The one deliberate addition over other clients' traces: which clock answered. */
    @Test
    public void deadlineArm_namesTheClockThatAnswered() {
        assertEquals("deadline[self-heal window] clock=SYSTEM now=1000 deadline=4000 (+3000ms)",
                MlsTrace.deadlineArm("self-heal window", MlsPorts.ARM_SYSTEM, 1000L, 4000L));
        assertEquals("deadline[gate] clock=TRUSTED now=5 deadline=10 (+5ms)",
                MlsTrace.deadlineArm("gate", MlsPorts.ARM_TRUSTED, 5L, 10L));
    }

    /** Only the system clock ships, and it is labelled as such. */
    @Test
    public void theSystemClockReportsTheSystemArm() {
        assertEquals(MlsPorts.ARM_SYSTEM, MlsPorts.SYSTEM_CLOCK.arm());
        assertFalse(MlsPorts.SYSTEM_CLOCK.isTrusted());
        // A trusted implementation would be attributed with no other change.
        final MlsPorts.MlsClock trusted = new MlsPorts.MlsClock() {
            @Override public long nowMs() { return 42L; }
            @Override public boolean isTrusted() { return true; }
        };
        assertEquals(MlsPorts.ARM_TRUSTED, trusted.arm());
    }

    // The verbatim oddities: each fails when someone fixes the typo.

    @Test
    public void oddity_anSignedNotASigned() {
        final String s = MlsTrace.signedNegativeReceiptNeedsToBeSent("m1", 3);
        assertEquals("An signed negative receipt needs to be sent for message m1. FTD Status: 3",
                s);
        assertTrue("the typo is 'An signed', not 'A signed'", s.startsWith("An signed"));
    }

    @Test
    public void oddity_trailingSpaceAfterEraId() {
        final String s = MlsTrace.receivedIncomingServerMlsControlMessage(7);
        assertEquals("Received an incoming server mls control message. Era ID: 7 ", s);
        assertTrue("the line ENDS in a space", s.endsWith(" "));
    }

    @Test
    public void oddity_doubledSpaceBeforeKeyPackageCount() {
        final String s = MlsTrace.addMembersToGroupSync(5, "ADD_MEMBERS", 2);
        assertEquals("addMembersToGroupSync(mlsOperation=5, ADD_MEMBERS,  keyPackageCount=2)", s);
        assertTrue("two spaces, not one", s.contains(",  keyPackageCount="));
    }

    @Test
    public void oddity_negativeReceiptMismatchHasNoComma() {
        final String s = MlsTrace.groupIdMismatchInNegativeDeliveryReceipt("g1");
        assertEquals("Group ID mismatch in negative delivery receipt for group: g1", s);
        assertFalse("the NEGATIVE variant has no comma — the positive one does",
                s.contains("group, "));
    }

    @Test
    public void oddity_thisIsIs() {
        assertTrue(MlsTrace.mlsGroupNotFoundInDatabase().contains("because this is is an"));
    }

    @Test
    public void oddity_fourDotEllipsis() {
        final String s = MlsTrace.receivedIconKeysWithNoIconYet();
        assertTrue("FOUR dots, not three", s.endsWith("Waiting for encrypted bytes...."));
        assertFalse(s.endsWith("bytes..."));
    }

    /** The revive diagnostic: an embedded newline, a 20-space indent, 215 chars in all. */
    @Test
    public void oddity_reviveBugIsTwoHundredAndFifteenCharsWithATwentySpaceIndent() {
        assertEquals("a 20-space indent plus the {:?} token gives 215",
                215, MlsTrace.REVIVE_BUG_FORMAT.length());
        assertTrue(MlsTrace.REVIVE_BUG_FORMAT.contains("\n                    The health status"));
        assertTrue(MlsTrace.REVIVE_BUG_FORMAT.endsWith("{:?}"));
        // The 16-space indent used elsewhere would give 211.
        assertEquals(211, MlsTrace.REVIVE_BUG_FORMAT.replace(
                "\n                    ", "\n                ").length());
        final String rendered = MlsTrace.tryingToReviveAnUnhealthyGroupBug("DoneEndMls");
        assertTrue(rendered.endsWith("The health status we are trying to revive is: DoneEndMls"));
        assertTrue(
                rendered.contains("This is a bug in the Google MLS engine.\n                    "));
    }

    @Test
    public void oddity_resyncCommitHasATwentySpaceIndent() {
        final String s = MlsTrace.cannotCreateResyncCommitOlderCertificate("t1", "t2", "g1");
        assertEquals("Cannot create a resync commit. The new certificate's issuance time (t1) is\n"
                + "                    older than the one being removed (t2) for group g1.", s);
    }

    /** Sixteen spaces here, twenty above: two unrelated source sites. */
    @Test
    public void oddity_stalePendingOperationHasASixteenSpaceIndent() {
        final String s = MlsTrace.pendingOperationNotForCurrentMoment("op", "(1,2)", "g1");
        assertEquals("Pending operation: op, is not for the current group moment: (1,2).\n"
                + "                Requesting self-heal on group: g1", s);
        assertTrue(s.contains("\n                Requesting"));
        assertFalse("not the 20-space indent", s.contains("\n                    Requesting"));
    }

    /** The doubled punctuation {@code ".: "}. */
    @Test
    public void oddity_noOpSelfHealHasDoubledPunctuation() {
        final String s = MlsTrace.settingHealthyForNoOpSelfHeal("g1");
        assertTrue("'.: ' — the doubled punctuation is IN the literal",
                s.startsWith("Setting group to healthy for no-op self-heal.: "));
    }

    private static final String G = "65cb80d62ebb4cc7b77646449c10264b";

    // Observed verbatim in a self-heal recovery trace.

    @Test public void transitioning_matchesTheCapture() {
        assertEquals("Transitioning group GroupId: \"" + G + "\" from MlsHealthStatus::Healthy"
                        + " to MlsHealthStatus::EpochAdvancementRequested on edge RequestedSelfHeal",
                MlsTrace.transitioning(G, "Healthy", "EpochAdvancementRequested",
                        "RequestedSelfHeal"));
    }

    @Test public void transitioning_theHealingEdgeToo() {
        // The observed no-op ending; the edge name is passed through untouched.
        assertEquals("Transitioning group GroupId: \"" + G + "\" from"
                        + " MlsHealthStatus::EpochAdvancementRequested to MlsHealthStatus::Healthy"
                        + " on edge HealedAfterRemoteCommit",
                MlsTrace.transitioning(G, "EpochAdvancementRequested", "Healthy",
                        "HealedAfterRemoteCommit"));
    }

    @Test public void alreadyInState_matchesTheCapture() {
        assertEquals(
                        "Requested a state transition to MlsHealthStatus::Healthy for group GroupId: \""
                        + G + "\". That is already the group's state, no-op.",
                MlsTrace.alreadyInState("Healthy", G));
    }

    @Test public void handleMlsHealthStatus_matchesTheCapture() {
        assertEquals("handleMlsHealthStatus, go/selfHealStatus=1",
                MlsTrace.handleMlsHealthStatus(1));
        assertEquals("handleMlsHealthStatus, go/selfHealStatus=0",
                MlsTrace.handleMlsHealthStatus(0));
    }

    @Test public void theMomentPair_matchesTheCapture() {
        assertEquals("Processing a message on group GroupId: \"" + G + "\", at moment"
                        + " GroupMoment { Epoch: 1, and Era: 1 }",
                MlsTrace.processingAtMoment(G, MlsTrace.moment(1, 1)));
        assertEquals("Finished processing a message on group: GroupId: \"" + G + "\", now at moment"
                        + " GroupMoment { Epoch: 2, and Era: 1 }",
                MlsTrace.finishedAtMoment(G, MlsTrace.moment(2, 1)));
    }

    @Test public void theMomentRendersEpochBeforeEraWithTheCommaAnd() {
        // Epoch before era, with ", and", unlike how we name the pair elsewhere.
        assertEquals("GroupMoment { Epoch: 2, and Era: 1 }", MlsTrace.moment(2, 1));
    }

    @Test public void deletedGroupState_matchesTheCapture() {
        assertEquals("Deleted group state from MlsGroupStates table", MlsTrace.deletedGroupState());
    }

    @Test public void postProcessInternal_matchesTheCapture() {
        assertEquals("postProcessInternal for contextId ctx-1, iteration 0",
                MlsTrace.postProcessInternal("ctx-1", 0));
    }

    @Test public void failedMessageHandled_matchesTheCapture() {
        assertEquals("Failed message handled using health status. zinnia_failure_reason: "
                        + "ZINNIA_FAILURE_GROUP_ID_MISMATCH, mls_health_status: UNKNOWN, "
                        + "result_status: FAIL_NO_RETRY",
                MlsTrace.failedMessageHandled("zinnia", "ZINNIA_FAILURE_GROUP_ID_MISMATCH",
                        "UNKNOWN", "FAIL_NO_RETRY"));
    }

    @Test public void executingAndReceiving_matchTheCapture() {
        assertEquals("Executing GetMlsGroupInfoRequest: requestId=r-1",
                MlsTrace.executingRequest("GetMlsGroupInfo", "r-1"));
        assertEquals("Received result for GetMlsGroupInfoRequest: requestId=r-1, responseId=-1333",
                MlsTrace.receivedResult("GetMlsGroupInfo", "r-1", -1333L));
    }

    @Test public void processMessageResult_matchesTheCapture() {
        assertEquals("ProcessMessageResult(groupId=" + G + ", eraId=2, epochId=2, "
                        + "epochAuthenticator=58f3, zinniaRequestContextSize=(1181 bytes), "
                        + "pendingOperationId=0a18, FailedMessage(zinniaFailureReason=8))",
                MlsTrace.processMessageResult(G, 2, 2, "58f3", 1181, "0a18",
                        "FailedMessage(zinniaFailureReason=8)"));
    }

    // Specified but not yet observed in a trace.

    @Test public void opEntryAndFailure() {
        assertEquals("processMessageSync(mlsOperation=8, SENDING_MESSAGE)",
                MlsTrace.opEntry("processMessageSync", 8, "SENDING_MESSAGE"));
        assertEquals(
                        "MLS operation 8, SENDING_MESSAGE failed for the Google MLS engine operation 25 "
                        + "and RCS message ID m-1",
                MlsTrace.opFailed(8, "SENDING_MESSAGE", "25", "m-1"));
    }

    @Test public void cannotTransitionAndTheOverride() {
        assertEquals("Cannot transition group GroupId: \"" + G + "\" from MlsHealthStatus::Healthy"
                        + " to MlsHealthStatus::DoneEndMls",
                MlsTrace.cannotTransition(G, "Healthy", "DoneEndMls"));
        // No parameters, so it greps as a fixed string.
        assertEquals("Allowing the illegal state transition to occur.",
                MlsTrace.allowingIllegalTransition());
    }

    @Test public void theHealthWriteElisionAndObsoleteRequest() {
        assertEquals("Skipping write to storage because the MLS health status is already Healthy",
                MlsTrace.skippingHealthWrite("Healthy"));
        assertEquals("Dropping obsolete health status request: EPOCH, updating to ERA "
                        + "for group: GroupId: \"" + G + "\"",
                MlsTrace.droppingObsoleteHealthRequest("EPOCH", "ERA", G));
    }

    @Test public void noWorkNeededAndTheGuards() {
        assertEquals(
                "No work needed for MLS health status Healthy, returning result_status: SUCCESS",
                MlsTrace.noWorkNeeded("Healthy", "SUCCESS"));
        assertEquals("Self-heal already pending for group: GroupId: \"" + G + "\"",
                MlsTrace.alreadyPending("Self-heal", G));
        assertEquals("Pending operation: EPOCH_ADVANCEMENT, is not for the current group moment: "
                        + "GroupMoment { Epoch: 3, and Era: 2 }. Requesting self-heal on group: "
                        + "GroupId: \"" + G + "\"",
                MlsTrace.stalePendingOperation("EPOCH_ADVANCEMENT", MlsTrace.moment(3, 2), G));
    }

    @Test public void theFourOutgoingVariants() {
        assertEquals("A commit needs to be sent", MlsTrace.commitNeedsToBeSent());
        assertEquals("An addMembers commit needs to be sent. Welcome action: 3",
                MlsTrace.addMembersCommitNeedsToBeSent(3));
        assertEquals("A client MLS RCS message needs to be sent",
                MlsTrace.clientMessageNeedsToBeSent());
    }

    @Test public void theNegativeReceiptLineKeepsTheReferenceClientsGrammaticalSLIP() {
        // "An signed", not "A signed", as other clients print it.
        assertEquals("An signed negative receipt needs to be sent for message m-1. FTD Status: 4",
                MlsTrace.signedNegativeReceiptNeedsToBeSent("m-1", 4));
    }

    @Test public void theImdnWaitPair() {
        assertEquals("Waiting for IMDNs from message m-1", MlsTrace.waitingForImdns("m-1"));
        assertEquals("Received IMDN for message m-1", MlsTrace.receivedImdn("m-1"));
    }

    @Test public void theSplitterDropsEndTheWayGENUINEEndsThem() {
        // The splitter reports that it cannot convert; the drop is the message-level check's.
        assertEquals("No MLS headers present, this CPIM cannot be converted.",
                MlsTrace.noMlsHeaders());
        assertEquals("CPIM headers should contain all required MLS headers.",
                MlsTrace.incompleteMlsHeaders());
    }

    @Test public void theSplitterContinuationLinesKeepTheirIndent() {
        // Indented four spaces under the parent line; the indent is part of the literal.
        assertEquals("    Header namespaces found: urn:ietf:params:imdn",
                MlsTrace.noMlsHeadersNamespaces("urn:ietf:params:imdn"));
        assertEquals("    Headers found: Era-ID", MlsTrace.incompleteMlsHeadersFound("Era-ID"));
    }

    @Test public void theRouterDrops() {
        assertEquals("Processing an incoming MLS IMDN message with unsupported receipt type "
                        + "DISPLAY. Drops it.",
                MlsTrace.unsupportedReceiptType("DISPLAY"));
        assertEquals("Received a message with unknown content type application/zip. Drops it.",
                MlsTrace.unknownContentType("application/zip"));
    }

    @Test public void theDriveLoopBound() {
        assertEquals("Max iteration 10 reached for the contextId",
                MlsTrace.maxIterationReached(10));
    }

    @Test public void downgradingLocallyKeepsTheTrailingPeriod() {
        // The trailing "." follows the %s in the format string.
        assertEquals("Downgrading locally for downgrade reason: USER_ACTION.",
                MlsTrace.downgradingLocally("USER_ACTION"));
    }

    // The group-id renderer.

    @Test public void aGroupIdIsPrintedAsASCIIWhenItIsPrintable() {
        assertEquals(G, MlsTrace.groupId(G.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test public void aDashedUuidGroupIdSurvivesToo() {
        final String uuid = "7a41c509-95bf-450c-8bbe-95c89cdcf8ba";
        assertEquals(uuid,
                MlsTrace.groupId(uuid.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test public void aNonPrintableGroupIdFallsBackToHex() {
        assertEquals("00ff10", MlsTrace.groupId(new byte[] { 0x00, (byte) 0xff, 0x10 }));
    }

    @Test public void anAbsentGroupIdIsTheEmptyStringNotNull() {
        // An empty id is a real value on the group-info path and renders as "".
        assertEquals("", MlsTrace.groupId(null));
        assertEquals("", MlsTrace.groupId(new byte[0]));
    }
    // Observed verbatim in a no-op self-heal sequence.

    @Test public void theNoOpArmSignature_matchesTheCapture() {
        assertEquals("Group info matches current group state: GroupId: \"" + G + "\"",
                MlsTrace.groupInfoMatches(G));
        // The format string ends in a period and the metadata separator adds the colon.
        assertEquals("Setting group to healthy for no-op self-heal.: GroupId: \"" + G + "\"",
                MlsTrace.settingHealthyForNoOpSelfHeal(G));
    }

    @Test public void wroteGroupState_matchesTheCapture() {
        assertEquals("Wrote group state to MlsGroupStates table", MlsTrace.wroteGroupState());
    }

    @Test public void theGroupInfoBundleLineKeepsAnEmptyIdWhenThatIsWhatItHas() {
        // An empty id is correct here (the bundle names the group); on the receipt path, fatal.
        assertEquals("Processing MLS group info bundle for group: GroupId: \"\"",
                MlsTrace.processingGroupInfoBundle(""));
        assertEquals("Processing MLS group info bundle for group: GroupId: \"" + G + "\"",
                MlsTrace.processingGroupInfoBundle(G));
    }
}
