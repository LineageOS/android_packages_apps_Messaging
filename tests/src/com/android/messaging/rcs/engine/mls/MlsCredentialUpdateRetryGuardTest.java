/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * The RCC.16 §9.5.3 credential update does not spend its one attempt per certificate on a request
 * the server never answered. {@code maybeUpdateGroupCredential} takes the marker before the Commit
 * and releases it when no verdict about the bytes came back: connectivity loss, never asked, a
 * withheld commit, or a throw. A refusal of our position scopes the seal to that position instead.
 *
 * <p>The provider's half (reporting "never authenticated" rather than "rejected") is guarded in the
 * provider's own suite; this class pins the app's half. Reachability of the release arm through
 * {@code commitAndSend}'s return is asserted in {@code MlsCommitApplicationTest}. See
 * docs/mls/credentials.md.
 */
public final class MlsCredentialUpdateRetryGuardTest {

    /** The RCC.16 §9.5.3 arm. */
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
    /** How the arm sends its Commit; every release below must be reachable from it. */
    private static final String SEND = "rekey(rcsGroupId, peerE164)";
    /** The tier that asks whether the server judged the bytes; it stays inside the failed arm. */
    private static final String ABOUT_THE_BYTES = "MlsCredentialUpdateSeal.isAboutTheBytes(";
    /**
     * What separates a withheld commit from one whose artefact was never built: both reach the
     * release with no verdict recorded, and only the era tells them apart.
     */
    private static final String WITHHELD_SELECTOR = "era >= 0";

    /**
     * The arm consults the transport's verdict and puts the marker back when nothing was decided;
     * removing that branch drops the predicate count to zero.
     */
    @Test public void theCredentialUpdateReleasesItsMarkerWhenNothingWasDecided()
            throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), ARM);
        assertFalse(ARM
                + " no longer exists in the transport — this guard's subject is gone, and an "
                + "arm that cannot be found is not an arm whose retry is preserved",
                body.isEmpty());
        assertEquals(ARM + " must decide on the TRANSPORT's verdict, not on the bare -1 that "
                + "commitAndSend returns for every failure alike (" + PREDICATE + ")",
                1, SourceScan.count(body, PREDICATE));
        assertTrue(ARM + " must read the parked verdict (" + VERDICT_FIELD + ")",
                SourceScan.count(body, VERDICT_FIELD) >= 1);
        // Three touches, each a different job: the dedup read that declines a re-offer, the take
        // before the Commit that keeps a concurrent pass off the same bytes, and the release.
        assertTrue(MARKER + " must be read, taken AND restored in " + ARM + " — a take without a "
                + "matching release is the defect this guards",
                SourceScan.count(body, MARKER) >= 3);
    }

    /**
     * The verdict is cleared before the attempt as well as written after it, so an early return in
     * {@code commitAndSend} does not leave a previous operation's answer for the arm to read.
     */
    @Test public void commitAndSendBothClearsAndRecordsTheVerdict() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "commitAndSend");
        assertFalse("commitAndSend no longer exists — this guard's subject is gone",
                body.isEmpty());
        assertEquals("commitAndSend must touch " + VERDICT_FIELD
                + " exactly twice: once to CLEAR it "
                + "ahead of the attempt and once to RECORD what the transport said. One touch means "
                + "a stale verdict survives an early return",
                2, SourceScan.count(body, VERDICT_FIELD));
    }

    /**
     * Runs the release predicate itself, which a source scan cannot tell from a constant. It does
     * not establish that the release arm is reached; see {@code MlsCommitApplicationTest}.
     */
    @Test public void theReleasePredicateSeparatesBothDirections() {
        assertTrue("an UNAUTHENTICATED request arrives as NOT_REGISTERED and MUST take the release "
                + "arm — otherwise the wedge returns with the provider fix still in place",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_NOT_REGISTERED));
        assertTrue("a request that never completed carries no verdict about the commit either",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        // The positive arm still exists: a rejected commit spends the marker, or a refused
        // certificate would be re-offered for ever.
        assertFalse("a REJECTED commit IS a verdict about these bytes and must still spend the "
                + "marker — a predicate that is true for everything is not a predicate",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_REJECTED));
        // An era gap reached the server, so it does not take this release arm; it is a verdict
        // about our position, handled by the second tier
        // (aPositionRefusalIsNotAVerdictAboutTheBytes). isConnectivityLoss is not widened to cover
        // it: that tier keeps the marker and scopes it to a moment, rather than re-sending a Commit
        // per maintenance pass.
        assertFalse("an era gap reached the server, so it is not a connectivity loss",
                MlsTransportDisposition.isConnectivityLoss(
                        MlsTransportDisposition.VERDICT_ERA_GAP));
    }

    /**
     * {@code VERDICT_ERA_GAP} and {@code VERDICT_GROUP_ID_CHANGED} mean the server refused our
     * position before examining the certificate, so they are not a verdict about the bytes. The
     * negative cases keep a constant-false predicate from passing.
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
        // The positive half: without these a constant false would pass the cases above.
        assertTrue("VERDICT_REJECTED is the ONE verdict asserting the server EVALUATED this commit "
                + "and said no — the marker must still be spent on it, or a genuinely refused "
                + "certificate is re-offered for ever",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_REJECTED));
        assertTrue(
                "an external commit refused on its proposal shape is a refusal on the merits too",
                MlsCredentialUpdateSeal.isAboutTheBytes(
                        MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED));
        // An unrecognised verdict fails safe: too narrow a seal costs one extra Commit after a
        // move, too wide a seal silently abandons the repair.
        assertFalse("a verdict nobody has classified yet must not seal the repair",
                MlsCredentialUpdateSeal.isAboutTheBytes(9999));
    }

    /**
     * A position-scoped seal lifts when the conversation moves, and only then: never lifting wedges
     * the repair, and lifting at the same position wastes a Commit, a rollback and a self-heal per
     * pass.
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
        // A seal about the bytes is not lifted by a move.
        assertTrue("a null scope means the server judged the bytes — no move lifts that",
                MlsCredentialUpdateSeal.stillStands(null, new MlsAppMessage.Moment(9, 9L)));
        // An unreadable position keeps the seal rather than re-offering on a guess.
        assertTrue("an unreadable position is not evidence that we moved",
                MlsCredentialUpdateSeal.stillStands(here, null));
    }

    /**
     * The arm asks the position rule in both places, and restores the scope alongside the marker
     * when nothing was decided.
     */
    @Test public void theCredentialUpdateScopesItsSealToThePosition() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), ARM);
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
        assertEquals(
                "the seal must be WIDENED in exactly two places: when the server refused these "
                + "bytes on the merits, and when a Commit was ACCEPTED and left the credential "
                + "behind anyway — a defect which moves the position by definition and would "
                + "otherwise re-offer once per epoch for ever)",
                2, SourceScan.count(body, SCOPE + " = null;"));
        // Order matters: isConnectivityLoss is a strict subset of !isAboutTheBytes, so a position
        // arm placed first would swallow NOT_REGISTERED and TRANSPORT_FAILED and never restore the
        // marker.
        final int release = body.indexOf(PREDICATE);
        final int position = body.indexOf("MlsCredentialUpdateSeal.isAboutTheBytes(");
        assertTrue("the connectivity-loss release must be tested BEFORE the position tier",
                release >= 0 && position > release);
        // Both restores are inside that release block.
        final String releaseBlock = body.substring(release, position);
        assertTrue("the marker restore left the connectivity-loss block",
                releaseBlock.contains(MARKER + " = markerBefore;"));
        assertTrue("the scope restore left the connectivity-loss block",
                releaseBlock.contains(SCOPE + " = refusedAtBefore;"));
    }

    /**
     * A credential refusal ({@code mlsError} 4, expired credential) that names another member's
     * credential is not a verdict about ours; one that names us is. Both directions are asserted.
     */
    @Test public void aRefusalNamingSomeoneElsesCredentialIsNotAVerdictAboutOurs() {
        final String us = "+15715550104";
        final String peer = "grpcStatus=7 http=200 grpcMsg=Time-related validation error: client "
                + "\"326B6A76-064b-4678-8a6a-c24f65829b8b\" with MSISDN \"+12025550101\" error: "
                + "Validation error: Validity { not_before: 2026-07-23 04:25:14 (1784780714), "
                + "not_after: 2026-10-06 03:25:14 (1791257114) } is not valid at time: "
                + "LoggedMlsTime { epoch_seconds: 1791669648 } mlsError={1=4}";
        assertFalse("this refusal names the PEER, not us — the roster blocks this commit "
                + "and the roster clears without a new mint, exactly as the pre-check arm says",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(peer, us));
        assertTrue("the SAME sentence naming OUR number IS a verdict about our certificate: "
                + "re-offering the same bytes gets the same answer and only a mint changes it",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        peer.replace("+12025550101", us), us));
        assertFalse(
                "a credential refusal we cannot attribute is not evidence that OURS was judged; "
                + "MlsTimeValidationRefusal.namesUs answers false for an unparsed MSISDN by design "
                + "and the same direction is the safe one here",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        peer.replace("with MSISDN \"+12025550101\"", "with MSISDN"), us));
        // Every other refusal is untouched, so the predicate cannot scope every rejection to a
        // position.
        assertTrue("an era gap is not a credential-validity refusal, so this must not speak to it",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(
                        "grpcStatus=3 grpcMsg=Commit was from era Some(Era: 1) epoch Some(1), "
                                + "expected era Era: 1 epoch 3 mlsError={1=2}", us));
        assertTrue("a refusal with no detail at all leaves the verdict standing on its own",
                MlsCredentialUpdateSeal.judgedOurOwnCredential(null, us));
    }

    /**
     * {@code lastControlDetail} is written, cleared and read where the verdict is, so the predicate
     * is never evaluated against a previous operation's detail.
     */
    @Test public void theArmReadsWhatTheServerSaidAndNotOnlyItsVerdict() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
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
     * A throw is not a verdict either: {@code commitAndSend} has no catch, so an exception from the
     * commit path unwinds into the arm's catch with the marker taken, and the catch releases it.
     * The take records {@code takenOn}, which the release is conditional on, so the take is checked
     * too.
     */
    @Test public void aThrowDoesNotSpendTheCertificatesOneAttempt() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        final int catchAt = body.indexOf("catch (final Throwable");
        assertTrue(ARM + " no longer has the catch-all this guard is about", catchAt > 0);
        final String take = body.substring(0, catchAt);
        final String release = body.substring(catchAt);
        // The take records itself, or the release below is unreachable.
        assertEquals("the take must record WHICH conversation state it took the attempt on",
                1, SourceScan.count(take, "takenOn = cs;"));
        assertEquals("...and for WHICH certificate, so a concurrent pass that took it for another "
                + "one is not undone by this catch",
                1, SourceScan.count(take, "takenFor = st.clientNotAfter;"));
        // The catch restores both halves under that guard.
        assertEquals("the catch must re-check that THIS invocation still holds the marker before "
                + "restoring it", 1,
                SourceScan.count(release, "takenOn.credentialUpdateAttemptedFor == takenFor"));
        assertEquals("the catch must put the marker back — a throw carries no verdict about the "
                + "bytes, and an attempt spent on no evidence is the original defect", 1,
                SourceScan.count(release, "takenOn.credentialUpdateAttemptedFor = markerBefore;"));
        assertEquals("...and the SCOPE with it: restoring the marker alone would leave the next "
                + "attempt sealed to a position it was never refused at", 1,
                SourceScan.count(release, "takenOn.credentialUpdateRefusedAt = refusedAtBefore;"));
        // The record is declared outside the try, the only scope both halves see.
        final String method = SourceScan.transportUnsplitCode();
        final int decl = method.indexOf("ConvState takenOn = null;");
        final int tryAt = method.indexOf("takenOn = cs;");
        assertTrue("the undo record must be declared before the take", decl > 0 && decl < tryAt);
    }

    /**
     * A request the server was never asked is not a verdict: {@code commitAndSend} returns -1
     * without calling {@code applyMlsControl} when the engine produces no commit artefact, with the
     * marker already taken, and a position-scoped seal would never lift on a quiet group. A
     * constant-true predicate would release on a real rejection, so both directions are asserted.
     */
    @Test public void aRequestTheServerNeverSawIsNotAVerdictEither() throws IOException {
        assertTrue("the sentinel commitAndSend writes at the top of every attempt must read as "
                + "'no answer was recorded', or the arm treats a Commit that never left the device "
                + "as a refusal of our POSITION",
                MlsCredentialUpdateSeal.serverWasNeverAsked(-1));
        // Every real verdict is an answer; otherwise the predicate could be a constant true.
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
        // "Negative means no answer" holds only while no verdict is negative, so it is asserted
        // over the source of the shared AIDL vocabulary.
        final String aidl = SourceScan.codeOnly(
                SourceScan.read(
                        "aidl/src/java/org/lineageos/rcs/provider/RcsMlsControlResult.java"));
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
        // The verdict vocabulary is counted, so a new constant forces a re-check here. All eight
        // are non-negative, so serverWasNeverAsked (verdict < 0) cannot read a real answer as
        // "never asked". TODO: MlsTransportDisposition does not classify VERDICT_NOT_IN_GROUP, so
        // the release arm spends the marker on it though it says nothing about the certificate.
        assertEquals("the transport verdict vocabulary must still be the EIGHT constants this "
                + "predicate was derived against — a different count means the vocabulary moved and "
                + "nobody re-checked what a negative means in it, NOR whether "
                + "MlsTransportDisposition classifies the newcomer, which is what "
                + "the 7 -> 8 bump turned up)", 8, seen);
    }

    /**
     * The never-asked question shares the connectivity arm's release (one arm, restoring the marker
     * and the scope), and the arm clears the parked verdict itself under the take's lock, because
     * three of {@code commitAndSend}'s returns sit above its own clear. Reachability is not
     * asserted here.
     */
    @Test public void theArmReleasesWhenTheServerWasNeverAskedAndClearsTheSentinelItself()
            throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), ARM);
        assertFalse(ARM + " no longer exists in the transport", body.isEmpty());
        final int asked = body.indexOf(NEVER_ASKED);
        final int release = body.indexOf(PREDICATE);
        final int position = body.indexOf("MlsCredentialUpdateSeal.isAboutTheBytes(");
        assertEquals(ARM + " must ask whether the server was asked at all, exactly once",
                1, SourceScan.count(body, NEVER_ASKED));
        assertTrue(
                "the never-asked question must be settled BEFORE the position tier, or a request "
                + "the server never saw is recorded as a refusal of where we stood — which is "
                + "exactly the seal this guard exists to stop taking",
                asked >= 0 && release >= 0 && position > asked && position > release);
        // One arm, not two: a separate exit can be written without the restore. The two predicates
        // are disjoint, so their order is not asserted.
        assertFalse("the never-asked question and the connectivity-loss release must share ONE arm "
                + "and ONE restore — a return between them is a second exit, and the defect here is "
                + "exactly an exit that spends the attempt without putting it back",
                body.substring(Math.min(asked, release), Math.max(asked, release))
                        .contains("return"));
        // The arm sets the sentinel before the Commit; commitAndSend's clear is below three of its
        // returns.
        assertEquals(ARM + " must clear the parked verdict itself — commitAndSend's clear sits "
                + "BELOW three of its own returns, so without this the arm can read a PREVIOUS "
                + "operation's answer as this attempt's and take the widest seal on it",
                1, SourceScan.count(body, VERDICT_FIELD + " = -1;"));
        final int cleared = body.indexOf(VERDICT_FIELD + " = -1;");
        final int sent = body.indexOf("rekey(rcsGroupId, peerE164)");
        assertTrue("the clear must happen BEFORE the Commit is sent, or the sentinel names some "
                + "other attempt", cleared >= 0 && sent > cleared);
        // Inside the take, the only place the marker and the verdict are written under one lock.
        final int took = body.indexOf(SCOPE + " = positionNow;");
        assertTrue("the clear must sit with the TAKE, under the same lock", took >= 0
                && cleared > took && cleared < sent);
    }

    /**
     * The release is asked on every outcome of the Commit, not only when {@code era < 0}: a
     * withheld commit ({@code suppressCommit}) returns the post-commit era without calling
     * {@code applyMlsControl}. Asserted as brace depth rather than on a local's name; the position
     * tier legitimately sits one level deeper, which shows the depth reader separates the two
     * cases.
     */
    @Test public void theReleaseArmIsAskedOnEveryOutcomeOfTheCommit() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), ARM);
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
        // The log tells the withheld case apart from a never-built artefact. Asserted on the era
        // selector rather than the sentences, because codeOnly blanks string-literal contents.
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
     * The number of unclosed braces before {@code at}. Correct only on {@link SourceScan#codeOnly}
     * output, where comments and string contents are blanked.
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
