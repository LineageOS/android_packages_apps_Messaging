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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * <b>The RCC.16 §9.5.3 credential update must not spend its one attempt on a request the server
 * never answered</b>, in both halves.
 *
 * <h2>The defect, device-measured 2026-09-11 on {@code deviceA}</h2>
 *
 * <p>Two groups held a copy of our credential 32 days older than the one the device held, with no
 * peer inside the floor and eleven days to the 30-day crossing. The five candidate refusals
 * were all wrong: the arm FIRED, built a 3606 B Commit, and the engine carried the new credential.
 * The refusal was at the wire — {@code grpcStatus=16 UNAUTHENTICATED}, because that line's Tachyon
 * registration was refused ({@code tachyonerror=32}) and its register auth token
 * could not be renewed.
 *
 * <p>The damage was what happened NEXT. {@code credentialUpdateAttemptedFor} was taken before the
 * Commit and never released, so 93 seconds later the arm declined — <i>"already attempted for this
 * certificate … the next MINT re-arms this"</i> — on a device whose mint needs the same dead token.
 * A transient transport state became a PERMANENT refusal to re-offer a perfectly good 73-day
 * certificate, while the group aged toward the floor.
 *
 * <h2>Why this is a guard and not a device note</h2>
 *
 * <p>The device state that proves the fix DISAPPEARS the moment that line's registration recovers,
 * and it is not reproducible on a healthy one. So the evidence has to move into something that
 * survives — which is this file. The mechanism is one thing split across the AIDL boundary: the
 * provider must SAY "we were never authenticated" rather than "the server refused you", and the
 * app must ACT on the difference. This file pins the APP half; the provider half is guarded in the
 * provider's own suite, for the reasons set out at the end of this comment. Either half alone
 * restores the wedge.
 *
 * <h2>What each assertion would catch</h2>
 *
 * <ol>
 *   <li>Delete the release branch in {@code maybeUpdateGroupCredential} and
 *       {@link #theCredentialUpdateReleasesItsMarkerWhenNothingWasDecided} fails by name.</li>
 *   <li>Delete either the clear or the record in {@code commitAndSend} and
 *       {@link #commitAndSendBothClearsAndRecordsTheVerdict} fails on the count.</li>
 *   <li>{@link #theReleasePredicateSeparatesBothDirections} is the one that is NOT a source scan:
 *       it asserts against {@link MlsTransportDisposition} itself that the predicate the release
 *       depends on actually separates the two cases, which a scan could not tell from a constant
 *       {@code false}.
 *       <p><b>IT WAS CALLED {@code theReleaseArmIsReachable} AND THAT NAME WAS A LIE</b> — renamed
 *       2026-09-12 after a later change made the release arm genuinely UNREACHABLE on one path
 *       and this guard stayed green throughout. The arm sits under {@code if (era < 0)}, so
 *       reaching it needs {@code commitAndSend} to RETURN NEGATIVE; part 2 began returning
 *       {@code era >= 0} for a commit it kept without measuring the identity, which skips the gate
 *       entirely. Nothing here looks at {@code rekey}'s return, so nothing here could have caught
 *       it. Fixed in Messaging {@code c3d99947} by reporting UNRESOLVED rather than SUCCESS where
 *       the identity was never measured, and the reachability property now lives where it can
 *       actually be asserted — {@code MlsCommitApplicationTest}, "a SILENT outcome reaches
 *       KEEP_AND_REPORT_SUCCESS only via MATCHES".
 *       <p>A TEST NAME IS AN ASSERTION WITH NO CODE BEHIND IT. This one claimed the strongest
 *       property in the file and checked the weakest, and a reader auditing the guard would have
 *       ticked reachability off the list on the strength of the name alone.</li>
 * </ol>
 *
 * <h2>The PROVIDER half is not asserted here, and used to be</h2>
 *
 * <p>Two checks in this class used to read the out-of-tree RCS provider's source across a
 * repository boundary, against the method that turns a server reply into the verdict this arm
 * acts on. One required an unauthenticated request to be classified "not registered" rather than
 * falling through to "rejected" — with the count of status reads in that method pinned EXACTLY, so
 * that a fourth classification rule could not arrive undescribed — and the other required the
 * "rejected" verdict to need positive evidence that the server spoke at all.
 *
 * <p>Both are gone: the provider is not part of this repository, and a scan that cannot find its
 * subject certifies nothing. The evidence requirement is independently guarded in the provider's
 * own suite, which pins the same condition by its COMPOSITION where this class pinned its exact
 * spelling. <b>The status-read count has no counterpart and is now unguarded</b> — it was
 * deliberately placed here, on the reasoning that the provider's suite pins the ARM while this one
 * noticed a read appearing with nobody describing it. That reasoning does not survive the two
 * suites no longer sharing a tree, so if the count is still wanted it has to be re-made in the
 * provider's suite, where the method it counts actually lives.
 *
 * <p>What remains here is the APP half, and it is the half this repository can be held to: the
 * marker must be released when nothing was decided, and the app must ACT on the difference between
 * "the server refused you" and "we were never authenticated". Either half alone restores the wedge
 * described above, so the provider half being someone else's to keep is a statement about
 * ownership, not about it mattering less.
 */
public final class MlsCredentialUpdateRetryGuardTest {

    /** The §9.5.3 arm. */
    private static final String ARM = "maybeUpdateGroupCredential";
    /** The one-attempt-per-certificate marker. */
    private static final String MARKER = "cs.credentialUpdateAttemptedFor";
    /** The moment that marker was refused at, or null for a seal no move lifts. */
    private static final String SCOPE = "cs.credentialUpdateRefusedAt";
    /** Where the transport's answer is parked for the arm to read. */
    private static final String VERDICT_FIELD = "lastControlVerdict";
    /** The predicate that separates "refused" from "never asked". */
    private static final String PREDICATE = "MlsTransportDisposition.isConnectivityLoss(";
    /** The predicate that separates "the server answered" from "the server was never asked". */
    private static final String NEVER_ASKED = "MlsCredentialUpdateSeal.serverWasNeverAsked(";
    /** How the arm SENDS its Commit — the point every release below must be reachable from. */
    private static final String SEND = "rekey(rcsGroupId, peerE164)";
    /** The tier that asks whether the server judged the BYTES; it stays inside the failed arm. */
    private static final String ABOUT_THE_BYTES = "MlsCredentialUpdateSeal.isAboutTheBytes(";
    /**
     * What separates a WITHHELD commit from one whose artefact was never built: both
     * reach the release with no verdict recorded, and only the era tells them apart.
     */
    private static final String WITHHELD_SELECTOR = "era >= 0";

    /**
     * <b>The mutation gate.</b> The arm must consult the transport's verdict and put the marker back
     * when nothing was decided. Remove that branch — the state the device was measured in — and the
     * predicate count drops to zero and this fails.
     */
    @Test public void theCredentialUpdateReleasesItsMarkerWhenNothingWasDecided() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), ARM);
        assertFalse(ARM + " no longer exists in the transport — this guard's subject is gone, and an "
                + "arm that cannot be found is not an arm whose retry is preserved", body.isEmpty());
        assertEquals(ARM + " must decide on the TRANSPORT's verdict, not on the bare -1 that "
                + "commitAndSend returns for every failure alike (" + PREDICATE + ")",
                1, SourceScan.count(body, PREDICATE));
        assertTrue(ARM + " must read the parked verdict (" + VERDICT_FIELD + ")",
                SourceScan.count(body, VERDICT_FIELD) >= 1);
        // Three touches, and each is a different job: the dedup read that declines a re-offer, the
        // take before the Commit that keeps a concurrent pass off the same bytes, and the release.
        // Two would mean the release is gone while the take and the dedup still look correct —
        // which is exactly the shape the device was in.
        assertTrue(MARKER + " must be read, taken AND restored in " + ARM + " — a take without a "
                + "matching release is the measured defect",
                SourceScan.count(body, MARKER) >= 3);
    }

    /**
     * The verdict has to be CLEARED before the attempt as well as written after it. Without the
     * clear, every early return in {@code commitAndSend} — the floor gate, a null engine artefact,
     * the AHEAD fixture — leaves a PREVIOUS operation's answer standing, and the arm above reads it
     * as this one's.
     */
    @Test public void commitAndSendBothClearsAndRecordsTheVerdict() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "commitAndSend");
        assertFalse("commitAndSend no longer exists — this guard's subject is gone", body.isEmpty());
        assertEquals("commitAndSend must touch " + VERDICT_FIELD + " exactly twice: once to CLEAR it "
                + "ahead of the attempt and once to RECORD what the transport said. One touch means "
                + "a stale verdict survives an early return",
                2, SourceScan.count(body, VERDICT_FIELD));
    }

    /**
     * <b>The assertion that makes the rest evidence.</b> Everything above is a source scan, and a
     * source scan cannot tell a live predicate from one that has been reduced to a constant. This
     * runs the predicate.
     *
     * <p><b>What it does NOT establish, stated because its old name claimed it:</b> that the release
     * arm is REACHED. That needs {@code commitAndSend} to return negative, which is not a fact about
     * this predicate and is asserted in {@code MlsCommitApplicationTest} instead. See the class
     * javadoc for the regression that exploited the gap.
     */
    @Test public void theReleasePredicateSeparatesBothDirections() {
        assertTrue("an UNAUTHENTICATED request arrives as NOT_REGISTERED and MUST take the release "
                + "arm — otherwise the wedge returns with the provider fix still in place",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_NOT_REGISTERED));
        assertTrue("a request that never completed carries no verdict about the commit either",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        // AND THE POSITIVE ARM MUST STILL EXIST. If every verdict took the release, the marker would
        // never be spent and a genuinely refused certificate would be re-offered for ever — the
        // opposite defect, and a predicate that answered true for everything would pass the two
        // assertions above while producing it.
        assertFalse("a REJECTED commit IS a verdict about these bytes and must still spend the "
                + "marker — a predicate that is true for everything is not a predicate",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_REJECTED));
        // AN ERA GAP REACHED THE SERVER, so it does not take THIS release arm — but it is a
        // verdict about our POSITION and not about the bytes, which is a distinction this predicate
        // does not draw and was never meant to. A second tier was added that does; see
        // aPositionRefusalIsNotAVerdictAboutTheBytes below. Do not widen isConnectivityLoss to cover
        // it: the two arms do different things (this one puts the marker back, that one keeps it and
        // scopes it to a moment), and merging them would re-send a Commit per maintenance pass at a
        // position the server has already refused.
        assertFalse("an era gap reached the server, so it is not a connectivity loss",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_ERA_GAP));
    }

    /**
     * <b>A refusal of WHERE WE ARE is not a refusal of WHAT WE OFFERED</b> — the half of the
     * mechanism the original fix did not reach.
     *
     * <p>The original fix released the marker only when nothing reached the server at all. Two
     * verdicts fall outside that and are the common ones: {@code VERDICT_ERA_GAP} and
     * {@code VERDICT_GROUP_ID_CHANGED} both mean the server looked at our POSITION and refused it
     * before the certificate was examined. Device-measured on {@code deviceA} 2026-09-11 —
     * {@code grpcStatus=3 "Commit was from era Some(Era: 1) epoch Some(1), expected era Era: 1 epoch
     * 3" mlsError=&#123;1=2&#125;} — and the attempt was spent on it.
     *
     * <p><b>The negative assertions are what make the positive ones mean anything.</b> A predicate
     * reduced to a constant {@code false} would pass every "not about the bytes" case here while
     * producing the OPPOSITE defect: a certificate the server genuinely refused, re-offered on every
     * maintenance pass for the life of the certificate.
     */
    @Test public void aPositionRefusalIsNotAVerdictAboutTheBytes() {
        assertFalse("an era gap refuses our POSITION — the server did not reach the certificate, "
                + "and the position clears without a new mint (catch-up, an era advance, a peer's "
                + "commit). Sealing the update on it is the same wedge one layer further in",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_ERA_GAP));
        assertFalse("a group-id change says we addressed the wrong conversation, which is not a "
                + "statement about this certificate either",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED));
        assertFalse("an unauthenticated request was never evaluated",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_NOT_REGISTERED));
        assertFalse("a request that never completed carries no verdict about the commit",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        // THE POSITIVE HALF. Without these the predicate could be a constant false and the four
        // above would all still pass.
        assertTrue("VERDICT_REJECTED is the ONE verdict asserting the server EVALUATED this commit "
                + "and said no — the marker must still be spent on it, or a genuinely refused "
                + "certificate is re-offered for ever",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_REJECTED));
        assertTrue("an external commit refused on its proposal shape is a refusal on the merits too",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED));
        // AND AN UNRECOGNISED VERDICT MUST FAIL SAFE. ofVerdict answers RETRYABLE for one, so this
        // answers false: a seal that is too narrow costs one extra Commit after a move; a seal that
        // is too wide silently abandons the repair.
        assertFalse("a verdict nobody has classified yet must not seal the repair",
                MlsCredentialUpdateSeal.isAboutTheBytes(9999));
    }

    /**
     * <b>The seal lifts when the conversation moves, and only then.</b> Both directions, because
     * each failure is a different defect: a seal that never lifts is the wedge again, and
     * a seal that lifts at the same position is one wasted Commit, one rollback and one self-heal
     * spend per maintenance pass on a forked group.
     */
    @Test public void theSealLiftsWhenThePositionMovesAndNotBefore() {
        final MlsAppMessage.Moment here = new MlsAppMessage.Moment(1, 1L);
        assertTrue("a second pass at the SAME moment must not re-send — the server has already "
                + "refused this position and nothing has changed",
                MlsCredentialUpdateSeal.stillStands(here, new MlsAppMessage.Moment(1, 1L)));
        assertFalse("the EPOCH moved, so the refusal is stale evidence: the update is now possible "
                + "and still needed, and before the position tier nothing re-offered it until the next mint",
                MlsCredentialUpdateSeal.stillStands(here, new MlsAppMessage.Moment(1, 2L)));
        assertFalse("the ERA moved, likewise",
                MlsCredentialUpdateSeal.stillStands(here, new MlsAppMessage.Moment(2, 1L)));
        // A SEAL ABOUT THE BYTES IS NOT LIFTED BY A MOVE. Without this the two halves collapse into
        // one and a certificate the server refused on its merits is re-offered at every new epoch.
        assertTrue("a null scope means the server judged the bytes — no move lifts that",
                MlsCredentialUpdateSeal.stillStands(null, new MlsAppMessage.Moment(9, 9L)));
        // AND AN UNREADABLE POSITION KEEPS THE SEAL, rather than re-offering on a guess.
        assertTrue("an unreadable position is not evidence that we moved",
                MlsCredentialUpdateSeal.stillStands(here, null));
    }

    /**
     * <b>The mutation gate for the position tier.</b> Everything above runs the
     * rule; this asserts the arm still asks it, in both places, and still puts the SCOPE back
     * alongside the marker when nothing was decided.
     */
    @Test public void theCredentialUpdateScopesItsSealToThePosition() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        assertEquals(ARM + " must ask whether the seal still stands before declining a re-offer",
                1, SourceScan.count(body, "MlsCredentialUpdateSeal.stillStands("));
        assertEquals(ARM + " must ask whether the refusal was about the bytes before widening the "
                + "seal", 1, SourceScan.count(body, "MlsCredentialUpdateSeal.isAboutTheBytes("));
        assertEquals("the seal must be TAKEN scoped to the position the Commit is offered from",
                1, SourceScan.count(body, SCOPE + " = positionNow;"));
        assertEquals("the scope must be RESTORED with the marker when nothing reached the server — "
                + "restoring the marker alone leaves the next attempt sealed to a position it was "
                + "never refused at", 1, SourceScan.count(body, SCOPE + " = refusedAtBefore;"));
        assertEquals("the seal must be WIDENED in exactly two places: when the server refused these "
                + "bytes on the merits, and when a Commit was ACCEPTED and left the credential "
                + "behind anyway — a defect which moves the position by definition and would "
                + "otherwise re-offer once per epoch for ever)",
                2, SourceScan.count(body, SCOPE + " = null;"));
        // THE ORDER IS A PROPERTY, NOT A STYLE. isConnectivityLoss is a STRICT SUBSET of
        // !isAboutTheBytes, so a position arm placed first would swallow NOT_REGISTERED and
        // TRANSPORT_FAILED, never put the marker back, and restore the original defect with
        // the later fix sitting on top of it looking correct.
        final int release = body.indexOf(PREDICATE);
        final int position = body.indexOf("MlsCredentialUpdateSeal.isAboutTheBytes(");
        assertTrue("the connectivity-loss release must be tested BEFORE the position tier",
                release >= 0 && position > release);
        // AND BOTH RESTORES MUST BE INSIDE THAT RELEASE BLOCK.
        final String releaseBlock = body.substring(release, position);
        assertTrue("the marker restore left the connectivity-loss block",
                releaseBlock.contains(MARKER + " = markerBefore;"));
        assertTrue("the scope restore left the connectivity-loss block",
                releaseBlock.contains(SCOPE + " = refusedAtBefore;"));
    }

    /**
     * <b>A verdict says the server SPOKE; it does not say what it spoke ABOUT</b> — the second
     * residual, and the one Google Messages' own classifier disagrees with us on.
     *
     * <p>Wire {@code mlsError} 4 is {@code EXPIRED_CREDENTIAL} and Google Messages' own
     * interceptor calls it RECOVERABLE. Our provider maps 1/2 and 11/12 and lets 4 fall through to
     * {@code VERDICT_REJECTED} — {@code PERMANENT}, the widest seal there is. We measured
     * that refusal on this arm and it named the PEER's MSISDN while the certificate we were
     * installing had 73 days left.
     *
     * <p><b>Both directions, and the "named US" case is the one that makes the rest mean
     * something.</b> A predicate that answered {@code false} for every credential refusal would pass
     * the peer case while producing the opposite defect: a certificate the server really did refuse,
     * re-offered at every new epoch for the life of the certificate.
     */
    @Test public void aRefusalNamingSomeoneElsesCredentialIsNotAVerdictAboutOurs() {
        final String us = "+15715550104";
        final String peer = "grpcStatus=7 http=200 grpcMsg=Time-related validation error: client "
                + "\"326B6A76-064b-4678-8a6a-c24f65829b8b\" with MSISDN \"+12025550101\" error: "
                + "Validation error: Validity { not_before: 2026-07-23 04:25:14 (1784780714), "
                + "not_after: 2026-10-06 03:25:14 (1791257114) } is not valid at time: "
                + "LoggedMlsTime { epoch_seconds: 1791669648 } mlsError={1=4}";
        assertFalse("the measured refusal named the PEER, not us — the roster blocks this commit "
                + "and the roster clears without a new mint, exactly as the pre-check arm says",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(peer, us));
        assertTrue("the SAME sentence naming OUR number IS a verdict about our certificate: "
                + "re-offering the same bytes gets the same answer and only a mint changes it",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        peer.replace("+12025550101", us), us));
        assertFalse("a credential refusal we cannot attribute is not evidence that OURS was judged; "
                + "MlsTimeValidationRefusal.namesUs answers false for an unparsed MSISDN by design "
                + "and the same direction is the safe one here",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        peer.replace("with MSISDN \"+12025550101\"", "with MSISDN"), us));
        // AND EVERY OTHER REFUSAL IS UNTOUCHED. Without these the predicate could answer false for
        // everything and quietly scope every REJECTED to a position.
        assertTrue("an era gap is not a credential-validity refusal, so this must not speak to it",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        "grpcStatus=3 grpcMsg=Commit was from era Some(Era: 1) epoch Some(1), "
                                + "expected era Era: 1 epoch 3 mlsError={1=2}", us));
        assertTrue("a refusal with no detail at all leaves the verdict standing on its own",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(null, us));
    }

    /**
     * <b>The arm asks the second question, and the detail reaches it.</b>
     *
     * <p>Separate from the rule above because the rule is reachable on host and the wiring is not:
     * {@code lastControlDetail} has to be written where the verdict is written, cleared where the
     * verdict is cleared, and read where the verdict is read, or the predicate is evaluated against
     * a previous operation's sentence — the same defect {@code lastControlVerdict}'s own "cleared at
     * the top of every commit" comment exists to prevent.
     */
    @Test public void theArmReadsWhatTheServerSaidAndNotOnlyItsVerdict() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(SourceScan.TRANSPORT));
        assertEquals("the detail must be CLEARED wherever the verdict is cleared, or a commit that "
                + "never reached the transport is judged on an older operation's sentence",
                1, SourceScan.count(src, "controlState.lastControlDetail = null;"));
        assertEquals("...and RECORDED wherever the verdict is recorded",
                1, SourceScan.count(src, "controlState.lastControlDetail = (r == null)"));
        final String body = SourceScan.bodyOf(src, ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        assertEquals("the arm must read the detail beside the verdict, under the same lock",
                1, SourceScan.count(body, "verdictDetail = cs.lastControlDetail;"));
        assertEquals("the arm must ask whose credential the server meant",
                1, SourceScan.count(body, "MlsCredentialUpdateSeal.judgedOurOwnCredential("));
        assertEquals("and the answer must gate the SAME branch that scopes the seal to a position "
                + "— a second question whose answer changes nothing is not a fix",
                1, SourceScan.count(body,
                        "if (!MlsCredentialUpdateSeal.isAboutTheBytes(verdict) || !judgedOurs) {"));
    }

    /**
     * <b>A THROW IS NOT A VERDICT EITHER</b> — the route that reaches the marker
     * without going past any of the arms that classify one.
     *
     * <p>{@code commitAndSend} has no {@code catch} of its own — zero in 13 KB — so a
     * {@code DeadObjectException} out of the Binder call, or any {@code NullPointerException} in the
     * commit path, unwinds into {@code maybeUpdateGroupCredential}'s own {@code catch} with the
     * marker ALREADY TAKEN. Before this the attempt was spent there: position-scoped after the rest
     * of the position work, so a group that moves recovers, but a quiet group stays sealed until the
     * next mint or a process restart — the same wedge with an exception in front of it.
     *
     * <p><b>Why the guard checks the TAKE as well as the restore.</b> The restore is conditional on
     * {@code takenOn} having been recorded at the take. Asserting only the restore would pass
     * against a version that never records it, where the condition is false for ever and the release
     * can never run — a check that cannot fail.
     */
    @Test public void aThrowDoesNotSpendTheCertificatesOneAttempt() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        final int catchAt = body.indexOf("catch (final Throwable");
        assertTrue(ARM + " no longer has the catch-all this guard is about", catchAt > 0);
        final String take = body.substring(0, catchAt);
        final String release = body.substring(catchAt);
        // THE TAKE MUST RECORD ITSELF, or the release below is unreachable.
        assertEquals("the take must record WHICH conversation state it took the attempt on",
                1, SourceScan.count(take, "takenOn = cs;"));
        assertEquals("...and for WHICH certificate, so a concurrent pass that took it for another "
                + "one is not undone by this catch",
                1, SourceScan.count(take, "takenFor = st.clientNotAfter;"));
        // AND THE CATCH MUST PUT BOTH HALVES BACK, under that guard.
        assertEquals("the catch must re-check that THIS invocation still holds the marker before "
                + "restoring it", 1,
                SourceScan.count(release, "takenOn.credentialUpdateAttemptedFor == takenFor"));
        assertEquals("the catch must put the marker back — a throw carries no verdict about the "
                + "bytes, and an attempt spent on no evidence is the original defect", 1,
                SourceScan.count(release, "takenOn.credentialUpdateAttemptedFor = markerBefore;"));
        assertEquals("...and the SCOPE with it: restoring the marker alone would leave the next "
                + "attempt sealed to a position it was never refused at", 1,
                SourceScan.count(release, "takenOn.credentialUpdateRefusedAt = refusedAtBefore;"));
        // AND THE RECORD MUST BE DECLARED OUTSIDE THE TRY, which is the only scope both halves see.
        // A declaration inside would not compile; this pins the SHAPE so a later refactor that moves
        // it back does not quietly reintroduce a catch that can see nothing.
        final String method = SourceScan.codeOnly(SourceScan.read(SourceScan.TRANSPORT));
        final int decl = method.indexOf("ConvState takenOn = null;");
        final int tryAt = method.indexOf("takenOn = cs;");
        assertTrue("the undo record must be declared before the take", decl > 0 && decl < tryAt);
    }

    /**
     * <b>A request the server was never ASKED is not a verdict either</b> — the third
     * residual, and the last of the four silent outcomes to be covered.
     *
     * <p>The verdict field holds two different things: what the server said, and the sentinel
     * meaning nothing has been recorded. {@code commitAndSend} returns {@code -1} without ever
     * calling {@code applyMlsControl} when the engine produces no commit artefact — the reachable
     * case for a {@code REKEY}, which is what a §9.5.3 Self-Update is — and the marker is already
     * taken by then.
     *
     * <p>A {@code -1} used to fall to {@code ofVerdict}'s {@code RETRYABLE} default, which is not
     * {@code PERMANENT}, so {@link #aPositionRefusalIsNotAVerdictAboutTheBytes}'s tier kept the
     * attempt under a POSITION-scoped seal. That records "the server refused where we stood" about a
     * request the server never received, and a position does not move on a quiet group — so the
     * repair sat sealed until the next mint or the next process restart, which is the same
     * wedge reached by a third door.
     *
     * <p><b>The asymmetry is the argument.</b> The {@code catch} asserted in
     * {@link #aThrowDoesNotSpendTheCertificatesOneAttempt} already releases outright when the commit
     * path THROWS, because "a throw carries no verdict about the bytes". An engine that returns null
     * instead of throwing produced the same absence of evidence and got a stricter answer. Two
     * routes to one fact; one of them was covered.
     *
     * <p><b>Both directions, and the negative half is the load-bearing one.</b> A predicate reduced
     * to a constant {@code true} would release on a real {@code VERDICT_REJECTED} and re-offer a
     * certificate the server refused on its merits for ever — the opposite defect, and the one the
     * whole marker exists to prevent.
     */
    @Test public void aRequestTheServerNeverSawIsNotAVerdictEither() throws IOException {
        assertTrue("the sentinel commitAndSend writes at the top of every attempt must read as "
                + "'no answer was recorded', or the arm treats a Commit that never left the device "
                + "as a refusal of our POSITION",
                MlsCredentialUpdateSeal.serverWasNeverAsked(-1));
        // EVERY REAL VERDICT IS AN ANSWER. Without these the predicate could be a constant true.
        for (int v = MlsTransportDisposition.VERDICT_OK;
                v <= MlsTransportDisposition.VERDICT_REJECTED; v++) {
            assertFalse("verdict " + v + " is something the transport reported — releasing the "
                    + "marker on it would re-offer a certificate the server has already judged, on "
                    + "every maintenance pass, for the life of that certificate",
                    MlsCredentialUpdateSeal.serverWasNeverAsked(v));
        }
        assertFalse("an unrecognised but POSITIVE verdict is still an answer; the seal tiers below "
                + "are what classify it, not this one",
                MlsCredentialUpdateSeal.serverWasNeverAsked(9999));
        // AND THE PREMISE THE PREDICATE RESTS ON, RATCHETED RATHER THAN ASSUMED. "Negative means no
        // answer" is only true while no verdict in the vocabulary is negative. Adding a
        // VERDICT_UNKNOWN = -1 tomorrow would silently reclassify a real server answer as "never
        // asked" and release the marker on it — which is why this is asserted over the SOURCE of the
        // AIDL vocabulary both sides share, not over the seven constants this test could list.
        final String aidl = SourceScan.codeOnly(
                SourceScan.read("aidl/src/java/org/lineageos/rcs/provider/RcsMlsControlResult.java"));
        assertFalse("the AIDL result class this vocabulary lives in is gone — the premise behind "
                + "serverWasNeverAsked cannot be checked", aidl.isEmpty());
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("VERDICT_(\\w+)\\s*=\\s*(-?\\d+)\\s*;").matcher(aidl);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue("VERDICT_" + m.group(1) + " = " + m.group(2) + " is NEGATIVE, so "
                    + "serverWasNeverAsked would read a real server answer as 'never asked' and "
                    + "release the §9.5.3 marker on it. Give it a non-negative value, or stop "
                    + "expressing 'no answer' as a negative.",
                    Integer.parseInt(m.group(2)) >= 0);
        }
        // BUMPED 7 -> 8 ON 2026-09-14, AND THE RE-CHECK IS THE POINT OF THE BUMP.
        //
        // `a505ca24` added VERDICT_NOT_IN_GROUP = 7. This assertion fired, which is the whole
        // reason it counts rather than listing the members it cares about — and the re-check it
        // forced found something the negative question would have missed entirely.
        //
        // THE PREMISE HOLDS: all eight are non-negative, so serverWasNeverAsked (verdict < 0)
        // still cannot read a real answer as "never asked". That is what this assertion guards and
        // it is intact.
        //
        // WHAT THE RE-CHECK FOUND, recorded here because the next bump should not have to
        // rediscover it: MlsTransportDisposition does not mention NOT_IN_GROUP at all. ofVerdict
        // falls to `default: RETRYABLE` and isConnectivityLoss names only NOT_REGISTERED and
        // TRANSPORT_FAILED, so the §9.5.3 release arm spends the once-per-certificate marker on a
        // verdict that says nothing about the certificate — while the seal's own javadoc frames
        // its question as "evaluated the commit, as opposed to REFUSING WHERE WE STOOD", which is
        // what NOT_IN_GROUP is. Tracked separately; NOT fixed here, because classifying someone
        // else's verdict is a semantic decision and not a ratchet bump.
        //
        // AND NOTHING ELSE IN THE TREE NOTICED. MlsTransportDispositionTest names the seven
        // pre-existing verdicts INDIVIDUALLY, so it cannot see an eighth. Before anyone calls this
        // count brittle: it was the only thing that caught a new constant entering a shared
        // vocabulary, and it caught it from another repo's AIDL file.
        assertEquals("the transport verdict vocabulary must still be the EIGHT constants this "
                + "predicate was derived against — a different count means the vocabulary moved and "
                + "nobody re-checked what a negative means in it, NOR whether "
                + "MlsTransportDisposition classifies the newcomer, which is what "
                + "the 7 -> 8 bump turned up)", 8, seen);
    }

    /**
     * <b>The mutation gate for the never-asked arm, and for the sentinel it depends on.</b>
     *
     * <p>Two properties, and the second is the one that is easy to miss.
     *
     * <ol>
     *   <li><b>The release is ONE arm.</b> The never-asked question must be answered on the release
     *       side, sharing the connectivity arm's restore of the marker AND the scope. A separate
     *       exit between the two questions is an exit that can be written without a restore, which
     *       is the defect rather than the fix.</li>
     *   <li><b>The sentinel has to MEAN something.</b>
     *       {@link #commitAndSendBothClearsAndRecordsTheVerdict} pins that {@code commitAndSend}
     *       clears the verdict before the attempt — and its own javadoc says that is what stops an
     *       early return leaving a previous operation's answer standing. <b>It is not sufficient,
     *       and the count cannot see why:</b> THREE of {@code commitAndSend}'s returns sit ABOVE its
     *       own clear ({@code !ensureSession()}, a null {@code resolveInbound}, a group that
     *       vanished), so on those routes the arm reads a verdict from some earlier operation. A
     *       stale {@code VERDICT_REJECTED} there takes the WIDEST seal we have on a Commit that
     *       never left the device. So the arm clears it itself, under the same lock as the take.</li>
     * </ol>
     *
     * <p><b>What this does NOT establish</b>, stated because a neighbouring guard in this file once
     * claimed a property its body never tested: that the never-asked arm is REACHED. That needs
     * {@code commitAndSend} to return {@code -1} from above {@code applyMlsControl}, which is a fact
     * about {@code commitAndSend} and not about this arm. What is asserted here is that if it is
     * reached, the marker comes back.
     */
    @Test public void theArmReleasesWhenTheServerWasNeverAskedAndClearsTheSentinelItself()
            throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        final int asked = body.indexOf(NEVER_ASKED);
        final int release = body.indexOf(PREDICATE);
        final int position = body.indexOf("MlsCredentialUpdateSeal.isAboutTheBytes(");
        assertEquals(ARM + " must ask whether the server was asked at all, exactly once",
                1, SourceScan.count(body, NEVER_ASKED));
        assertTrue("the never-asked question must be settled BEFORE the position tier, or a request "
                + "the server never saw is recorded as a refusal of where we stood — which is "
                + "exactly the seal this guard exists to stop taking",
                asked >= 0 && release >= 0 && position > asked && position > release);
        // ONE ARM, NOT TWO. A `return` between the two questions means never-asked has its own exit,
        // and an exit of its own is one that can be written — or left — without the restore. This is
        // the assertion that would fail if the arm were split, which a count of either predicate
        // would not.
        //
        // ASSERTED OVER THE WINDOW IN WHICHEVER ORDER THEY ARE WRITTEN, deliberately. Which disjunct
        // comes first is style: the two predicates are DISJOINT (a verdict cannot be both negative
        // and one of NOT_REGISTERED/TRANSPORT_FAILED), so neither can swallow the other and no
        // correctness property distinguishes the orders. Pinning the order anyway would be a guard
        // that fails on a rewrite which changes nothing — the opposite failure to the one above it,
        // and just as misleading to whoever reads the name.
        assertFalse("the never-asked question and the connectivity-loss release must share ONE arm "
                + "and ONE restore — a return between them is a second exit, and the defect here is "
                + "exactly an exit that spends the attempt without putting it back",
                body.substring(Math.min(asked, release), Math.max(asked, release))
                        .contains("return"));
        // AND THE SENTINEL MUST BE SET BY THIS ARM, BEFORE THE COMMIT. commitAndSend's own clear is
        // below three of its returns; this one is not below any of them.
        assertEquals(ARM + " must clear the parked verdict itself — commitAndSend's clear sits "
                + "BELOW three of its own returns, so without this the arm can read a PREVIOUS "
                + "operation's answer as this attempt's and take the widest seal on it",
                1, SourceScan.count(body, VERDICT_FIELD + " = -1;"));
        final int cleared = body.indexOf(VERDICT_FIELD + " = -1;");
        final int sent = body.indexOf("rekey(rcsGroupId, peerE164)");
        assertTrue("the clear must happen BEFORE the Commit is sent, or the sentinel names some "
                + "other attempt", cleared >= 0 && sent > cleared);
        // AND IT MUST BE INSIDE THE TAKE, which is the only place the marker and the verdict are
        // written under one lock. Outside it, a concurrent pass can take the marker between the
        // clear and the send.
        final int took = body.indexOf(SCOPE + " = positionNow;");
        assertTrue("the clear must sit with the TAKE, under the same lock", took >= 0
                && cleared > took && cleared < sent);
    }

    /**
     * <b>The release must be asked on EVERY outcome of the Commit, not on one branch of it</b> —
     * This is the REACHABILITY property the two
     * guards above explicitly decline to establish, and the one case it was false in.
     *
     * <h2>The defect</h2>
     *
     * <p>{@link #aRequestTheServerNeverSawIsNotAVerdictEither} and
     * {@link #theArmReleasesWhenTheServerWasNeverAskedAndClearsTheSentinelItself} both end on the
     * same disclaimer: neither shows that the never-asked arm is REACHED. It was not. The release
     * sat inside {@code if (era < 0)}, which encodes "only a failed Commit can have gone
     * unanswered" — and the AHEAD fixture breaks exactly that. {@code suppressCommit}
     * withholds the publish of a commit the engine has ALREADY applied and returns
     * the POST-commit era, the same value the accepted path returns, with {@code applyMlsControl}
     * never called. So a withheld Commit read as ACCEPTED and spent this certificate's one
     * attempt: a fixture that does not merely fail to measure the state under test, but CONSUMES
     * it. The next pass then declined with <i>"already attempted for this certificate"</i>.
     *
     * <h2>Asserted as a DEPTH, not as a spelling</h2>
     *
     * <p>The property is structural — "this question is asked on every path out of the Commit" —
     * and brace depth is what states it. Keying on the text {@code if (era < 0)} would pin a local
     * variable's NAME, which {@link SourceScan}'s standing caution forbids, and would pass the
     * day somebody renamed the local while leaving the nesting exactly as it was.
     *
     * <p><b>The third assertion is the non-vacuity witness.</b> A depth reader that returned a
     * constant would satisfy the first two. The POSITION tier legitimately stays nested — a verdict
     * about where we stood can only exist when the server refused us — so it must read exactly one
     * deeper, which is what shows the instrument separates the two cases it is trusted to separate.
     *
     * <p><b>Mutation-proved, not asserted.</b> Run against the pre-fix revision of
     * {@code MlsProviderTransport.java} this test goes RED on the first assertion, with the release
     * arm at depth 3 and the {@code rekey} call at depth 2.
     */
    @Test public void theReleaseArmIsAskedOnEveryOutcomeOfTheCommit() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), ARM);
        assertFalse(ARM + " no longer exists in the transport — an arm that cannot be found is not "
                + "one whose release is reachable", body.isEmpty());
        final int sent = body.indexOf(SEND);
        final int asked = body.indexOf(NEVER_ASKED);
        final int release = body.indexOf(PREDICATE);
        final int position = body.indexOf(ABOUT_THE_BYTES);
        assertTrue(ARM + " no longer sends its Commit through " + SEND + " — this guard's subject "
                + "is gone, and a release whose Commit cannot be found is not one this can place",
                sent >= 0);
        assertTrue("the release arm must be reached AFTER the Commit it is judging",
                asked > sent && release > sent);
        assertTrue("the position tier must still follow both halves of the release",
                position > asked && position > release);
        assertEquals("the never-asked release is nested BELOW the Commit instead of sitting on "
                + "every path out of it: the AHEAD fixture withholds a "
                + "commit the engine has already applied and " + SEND + " returns the POST-commit "
                + "era, so a release gated on a FAILED Commit never runs and this certificate's "
                + "one attempt is spent on a request no server ever saw",
                braceDepthAt(body, sent), braceDepthAt(body, asked));
        assertEquals("...and the connectivity-loss half with it — the two share ONE arm and ONE "
                + "restore, so they must also share one reachability",
                braceDepthAt(body, sent), braceDepthAt(body, release));
        assertEquals("the POSITION tier must stay exactly one level deeper, inside the "
                + "failed-Commit branch: a verdict about where we stood only exists when the server "
                + "refused us. It is also what proves the depth readings above are MEASUREMENTS "
                + "rather than a constant — delete this and the two assertions before it can no "
                + "longer fail",
                braceDepthAt(body, sent) + 1, braceDepthAt(body, position));
        // AND THE RECORD MUST TELL THE WITHHELD CASE APART. Three causes now reach one release —
        // the artefact was never built, the fixture WITHHELD it, the transport lost the answer —
        // and a log that reads the same for the first two is the instrument defect this guard is
        // made of. The withheld one used to be reported as an ACCEPTED credential update.
        //
        // ASSERTED ON THE SELECTOR, NOT ON THE SENTENCE, and that is a stated limit rather than a
        // preference: SourceScan.codeOnly BLANKS the contents of string literals, so the three
        // sentences are not present in `body` at all and any assertion over them would be one that
        // cannot fail — the vacuity this file has already caught once, in
        // rejectedRequiresEvidenceThatTheServerAnswered. What IS visible is the era test that
        // chooses between them.
        assertEquals("the release arm must read the ERA to tell a WITHHELD commit from one whose "
                + "artefact was never built. Both arrive with no verdict recorded and only the era "
                + "separates them, so without this the record cannot name which one happened",
                1, SourceScan.count(body, WITHHELD_SELECTOR));
        final int withheld = body.indexOf(WITHHELD_SELECTOR);
        assertTrue("that era test must sit INSIDE the release arm — between the never-asked "
                + "question and the position tier — or it is deciding something else",
                withheld > asked && withheld < position);
    }

    /**
     * How many unclosed braces precede {@code at} — the NESTING DEPTH of that point in the body.
     *
     * <p>Correct on {@link SourceScan#codeOnly} output and on nothing else: comments and string
     * CONTENTS are blanked there, so no brace this counts can be inside one. Handed raw source it
     * would count the braces in a javadoc and report a depth nobody wrote.
     */
    private static int braceDepthAt(final String body, final int at) {
        int depth = 0;
        for (int i = 0; i < at; i++) {
            final char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }
}
