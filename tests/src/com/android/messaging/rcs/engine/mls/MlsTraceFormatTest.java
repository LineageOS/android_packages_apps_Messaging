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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Rework 13.2 — §20.4's emission list, pinned character-for-character.
 *
 * <h2>Why every expectation here is a literal</h2>
 *
 * <p>§20.4's whole promise is that these lines make "is our behaviour the same?" answerable with
 * {@code diff}. A reworded string silently voids that, and rewording looks like an improvement in
 * review — nobody rejects "fix grammar in a log message". These assertions are the only thing that
 * makes the wording load-bearing.
 *
 * <p>Where a line was OBSERVED on the wire, the expectation below is copied from the capture rather
 * than from the spec table, and the capture is named. Those are the strongest ones: the spec table
 * is itself a transcription, and two of its entries have already turned out to be inference
 * (§20.3's no-op edge name, and the "dedicated metric" claim).
 */
public class MlsTraceFormatTest {

    // ===================== §14.2 — the clock-arm attribution =====================

    /**
     * The one place we deliberately IMPROVE on Google Messages rather than matching it: Google Messages falls back
     * from a trusted time source to the wall clock and does not record which answered.
     */
    @Test
    public void deadlineArm_namesTheClockThatAnswered() {
        assertEquals("deadline[self-heal window] clock=SYSTEM now=1000 deadline=4000 (+3000ms)",
                MlsTrace.deadlineArm("self-heal window", MlsPorts.ARM_SYSTEM, 1000L, 4000L));
        assertEquals("deadline[gate] clock=TRUSTED now=5 deadline=10 (+5ms)",
                MlsTrace.deadlineArm("gate", MlsPorts.ARM_TRUSTED, 5L, 10L));
    }

    /**
     * We ship exactly ONE arm and it is honestly labelled — a recorded decision, not an oversight.
     *
     * <p>Building a second arm we cannot feed would produce an attribution that always says the same
     * thing while implying a choice was made, which is worse than one arm labelled truthfully
     * because it reads as corroboration.
     */
    @Test
    public void theSystemClockReportsTheSystemArm() {
        assertEquals(MlsPorts.ARM_SYSTEM, MlsPorts.SYSTEM_CLOCK.arm());
        assertFalse(MlsPorts.SYSTEM_CLOCK.isTrusted());
        // ...and a trusted implementation would discriminate with no other change, which is the
        // point of having built the attribution before the second arm exists.
        final MlsPorts.MlsClock trusted = new MlsPorts.MlsClock() {
            @Override public long nowMs() { return 42L; }
            @Override public boolean isTrusted() { return true; }
        };
        assertEquals(MlsPorts.ARM_TRUSTED, trusted.arm());
    }

    // ===================== §20.7 — the verbatim oddities (rework 13.6) =====================
    //
    // Each asserts the EXACT bytes. These are the tests whose whole purpose is to fail when someone
    // fixes a typo, so each says which oddity it is guarding.

    @Test
    public void oddity_anSignedNotASigned() {
        final String s = MlsTrace.signedNegativeReceiptNeedsToBeSent("m1", 3);
        assertEquals("An signed negative receipt needs to be sent for message m1. FTD Status: 3", s);
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

    /**
     * The revive diagnostic — an embedded newline, a 20-space indent, and <b>215 chars</b>.
     *
     * <p>The length is the cross-check: 215 is reached only with a 20-space indent AND the
     * {@code {:?}} token counted; the 16-space indent used elsewhere in §20.7 gives 211. So this
     * test verifies the layout arithmetic rather than how the string looked when it was typed,
     * and the two indents cannot be derived from one another.
     */
    @Test
    public void oddity_reviveBugIsTwoHundredAndFifteenCharsWithATwentySpaceIndent() {
        assertEquals("a 20-space indent plus the {:?} token gives 215",
                215, MlsTrace.REVIVE_BUG_FORMAT.length());
        assertTrue(MlsTrace.REVIVE_BUG_FORMAT.contains("\n                    The health status"));
        assertTrue(MlsTrace.REVIVE_BUG_FORMAT.endsWith("{:?}"));
        // A 16-space indent — the OTHER indent in §20.7 — would give 211, so the two are not
        // interchangeable and cannot be derived from one another.
        assertEquals(211, MlsTrace.REVIVE_BUG_FORMAT.replace(
                "\n                    ", "\n                ").length());
        final String rendered = MlsTrace.tryingToReviveAnUnhealthyGroupBug("DoneEndMls");
        assertTrue(rendered.endsWith("The health status we are trying to revive is: DoneEndMls"));
        assertTrue(rendered.contains("This is a bug in the Google MLS engine.\n                    "));
    }

    @Test
    public void oddity_resyncCommitHasATwentySpaceIndent() {
        final String s = MlsTrace.cannotCreateResyncCommitOlderCertificate("t1", "t2", "g1");
        assertEquals("Cannot create a resync commit. The new certificate's issuance time (t1) is\n"
                + "                    older than the one being removed (t2) for group g1.", s);
    }

    /** SIXTEEN spaces here, TWENTY above — two different Rust source sites, no rule between them. */
    @Test
    public void oddity_stalePendingOperationHasASixteenSpaceIndent() {
        final String s = MlsTrace.pendingOperationNotForCurrentMoment("op", "(1,2)", "g1");
        assertEquals("Pending operation: op, is not for the current group moment: (1,2).\n"
                + "                Requesting self-heal on group: g1", s);
        assertTrue(s.contains("\n                Requesting"));
        assertFalse("not the 20-space indent", s.contains("\n                    Requesting"));
    }

    /** The one §20.7 oddity that already landed with 13.2 — the doubled punctuation {@code ".: "}. */
    @Test
    public void oddity_noOpSelfHealHasDoubledPunctuation() {
        final String s = MlsTrace.settingHealthyForNoOpSelfHeal("g1");
        assertTrue("'.: ' — the doubled punctuation is IN the literal",
                s.startsWith("Setting group to healthy for no-op self-heal.: "));
    }

    private static final String G = "65cb80d62ebb4cc7b77646449c10264b";

    // ---- observed verbatim in a self-heal recovery trace ---------------------------------------

    @Test public void transitioning_matchesTheCapture() {
        assertEquals("Transitioning group GroupId: \"" + G + "\" from MlsHealthStatus::Healthy"
                        + " to MlsHealthStatus::EpochAdvancementRequested on edge RequestedSelfHeal",
                MlsTrace.transitioning(G, "Healthy", "EpochAdvancementRequested",
                        "RequestedSelfHeal"));
    }

    @Test public void transitioning_theHealingEdgeToo() {
        // The measured no-op ending. §20.3 predicted EpochAdvancementSuccessful here and was wrong;
        // this asserts the edge NAME is passed through untouched, whatever it is.
        assertEquals("Transitioning group GroupId: \"" + G + "\" from"
                        + " MlsHealthStatus::EpochAdvancementRequested to MlsHealthStatus::Healthy"
                        + " on edge HealedAfterRemoteCommit",
                MlsTrace.transitioning(G, "EpochAdvancementRequested", "Healthy",
                        "HealedAfterRemoteCommit"));
    }

    @Test public void alreadyInState_matchesTheCapture() {
        assertEquals("Requested a state transition to MlsHealthStatus::Healthy for group GroupId: \""
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
        // "Epoch: 2, and Era: 1" reads like a typo and is measured. A tidied version would not match,
        // and epoch-before-era is the opposite of how we name the pair everywhere else.
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

    // ---- from §20.4's table (not yet observed on the wire) ----

    @Test public void opEntryAndFailure() {
        assertEquals("processMessageSync(mlsOperation=8, SENDING_MESSAGE)",
                MlsTrace.opEntry("processMessageSync", 8, "SENDING_MESSAGE"));
        assertEquals("MLS operation 8, SENDING_MESSAGE failed for the Google MLS engine operation 25 "
                        + "and RCS message ID m-1",
                MlsTrace.opFailed(8, "SENDING_MESSAGE", "25", "m-1"));
    }

    @Test public void cannotTransitionAndTheOverride() {
        assertEquals("Cannot transition group GroupId: \"" + G + "\" from MlsHealthStatus::Healthy"
                        + " to MlsHealthStatus::DoneEndMls",
                MlsTrace.cannotTransition(G, "Healthy", "DoneEndMls"));
        // No parameters, deliberately — seeing it at all is the finding, so it must grep as a fixed
        // string.
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
        assertEquals("No work needed for MLS health status Healthy, returning result_status: SUCCESS",
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
        // "An signed", not "A signed". Google Messages' own slip, flagged verbatim in §11.3. Correcting it
        // silently is the exact failure this whole file exists to prevent.
        assertEquals("An signed negative receipt needs to be sent for message m-1. FTD Status: 4",
                MlsTrace.signedNegativeReceiptNeedsToBeSent("m-1", 4));
    }

    @Test public void theImdnWaitPair() {
        assertEquals("Waiting for IMDNs from message m-1", MlsTrace.waitingForImdns("m-1"));
        assertEquals("Received IMDN for message m-1", MlsTrace.receivedImdn("m-1"));
    }

    @Test public void theSplitterDropsEndTheWayGENUINEEndsThem() {
        // NOT "Dropping the message." The splitter reports that it cannot CONVERT; the drop is a
        // separate decision made by the message-level check. An earlier revision of MlsTrace
        // paraphrased both of these into dropping lines — the exact failure the class exists to
        // prevent — so these two assertions are the ones that would have caught it.
        assertEquals("No MLS headers present, this CPIM cannot be converted.",
                MlsTrace.noMlsHeaders());
        assertEquals("CPIM headers should contain all required MLS headers.",
                MlsTrace.incompleteMlsHeaders());
    }

    @Test public void theSplitterContinuationLinesKeepTheirIndent() {
        // Google Messages indents these four spaces under their parent line. The indent is part of the
        // literal a diff matches.
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
        assertEquals("Max iteration 10 reached for the contextId", MlsTrace.maxIterationReached(10));
    }

    @Test public void downgradingLocallyKeepsTheTrailingPeriod() {
        // The trailing "." is inside Google Messages' format string, after the %s. Easy to drop.
        assertEquals("Downgrading locally for downgrade reason: USER_ACTION.",
                MlsTrace.downgradingLocally("USER_ACTION"));
    }

    // ---- the group-id renderer ----

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
        // Google Messages prints GroupId: "" — an empty id is a real, observed value on the group-info path,
        // not an error case, so it must render rather than blow up.
        assertEquals("", MlsTrace.groupId(null));
        assertEquals("", MlsTrace.groupId(new byte[0]));
    }
    // ---- §20.3 sequence lines, all measured in the 172523 capture ----

    @Test public void theNoOpArmSignature_matchesTheCapture() {
        assertEquals("Group info matches current group state: GroupId: \"" + G + "\"",
                MlsTrace.groupInfoMatches(G));
        // The colon-space before GroupId reads like a typo and is measured: Google Messages' format string
        // ends in a period and the metadata separator adds the colon.
        assertEquals("Setting group to healthy for no-op self-heal.: GroupId: \"" + G + "\"",
                MlsTrace.settingHealthyForNoOpSelfHeal(G));
    }

    @Test public void wroteGroupState_matchesTheCapture() {
        assertEquals("Wrote group state to MlsGroupStates table", MlsTrace.wroteGroupState());
    }

    @Test public void theGroupInfoBundleLineKeepsAnEmptyIdWhenThatIsWhatItHas() {
        // Google Messages passes an EMPTY group id here and it is not a defect — the bundle names the group
        // itself. Substituting a resolved id would hide the very property that distinguishes this
        // path from the receipt path, where an empty id is fatal.
        assertEquals("Processing MLS group info bundle for group: GroupId: \"\"",
                MlsTrace.processingGroupInfoBundle(""));
        assertEquals("Processing MLS group info bundle for group: GroupId: \"" + G + "\"",
                MlsTrace.processingGroupInfoBundle(G));
    }
}
