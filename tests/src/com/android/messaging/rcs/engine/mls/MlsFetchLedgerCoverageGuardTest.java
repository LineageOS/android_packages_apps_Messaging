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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A coverage claim must be falsifiable.</b>
 *
 * <h2>What this guards, and why prose was not enough</h2>
 *
 * <p>The question this class answers: a refusal arm that is <i>structurally
 * unreachable from adb</i> is not the same as one that <i>cannot happen</i>, and we had no way to
 * tell those apart on a device. The answer went into a javadoc on
 * {@link MlsFetchLedger.Caller#ERA_ADVANCE}. Within a day that paragraph was over-general in one
 * direction (it was read as covering refusals INSIDE a rebuild, which a device run then
 * exercised on hardware) and resting on a wrong premise in another (it treated the ledger's ceiling
 * sysprop as a bound that only goes down). <b>Neither error could have gone red</b>, because the
 * claim was a comment.
 *
 * <p>So the claims are {@link MlsFetchLedgerCoverage} rows now, and every one of them is held to
 * three things a machine can check: the call sites it counts really exist, the host test it names
 * really exists, and the fact it rests on is still true.
 *
 * <h2>The four properties</h2>
 *
 * <ol>
 *   <li><b>Every caller has a row.</b> Enumerated from {@link MlsFetchLedger.Caller} at runtime, so
 *       a door added later lands as an unanswered question rather than as a blank.</li>
 *   <li><b>A claim counts every call site of its caller.</b> Scanned from the source and keyed on
 *       the constant's use, not on a convention. This is the check that stops one device-proven arm
 *       standing in for a caller's other two.</li>
 *   <li><b>A claim names host coverage that exists.</b> {@code Class#method}, resolved to a file and
 *       a method declaration. A named test that is not there reports green forever — we have had
 *       a gate list name an arm the dispatcher could never produce.</li>
 *   <li><b>A reachability claim has a falsifier that runs.</b> Not an assertion that the claim is
 *       true, which is not decidable here, but a check on the FACT the claim rests on, so that
 *       changing the fact turns the claim red instead of leaving it stale.</li>
 * </ol>
 *
 * <h2>The negative controls</h2>
 *
 * <p>Every source-scanning check below was injected against a SCRATCH COPY of the tree, observed
 * RED, and reverted — in comment-out form where the regression could plausibly leave the old text
 * behind, because a control that deletes proves less than one that comments out.
 *
 * <table>
 *   <caption>What was injected, and which check went red</caption>
 *   <tr><th>check</th><th>injected regression</th></tr>
 *   <tr><td>{@link #everyLedgerCallerHasACoverageRow}</td>
 *       <td>{@code REBUILD}'s row left claiming {@code DEVICE_REACHABLE_NOT_YET_RUN} with its
 *           falsifier set to null — a reachability claim with nothing behind it, which is the
 *           unfalsifiable comment this whole class replaces, retyped as data. RED. The same
 *           injection also tripped {@link #everyClaimNamesHostCoverageThatExists}'s zero-hits guard
 *           ("no coverage row named a test, so this guard checked nothing"), which is the arm that
 *           stops an empty table reporting green</td></tr>
 *   <tr><td>{@link #aCoverageClaimCountsEveryCallSiteOfItsCaller}</td>
 *       <td>a FOURTH {@code MlsFetchLedger.Caller.REBUILD} call site added to the transport (RED,
 *           "row says 3, source has 4"); and separately an existing site retyped to another
 *           {@code Caller} (RED, "source has 2") — a claim written against three sites must not
 *           silently cover four or two. <b>And the false-red direction</b>: the fourth site left
 *           ONLY IN A COMMENT stays GREEN, which is what {@link #codeOnly} buys and what a
 *           raw-source scan would have got wrong</td></tr>
 *   <tr><td>{@link #everyClaimNamesHostCoverageThatExists}</td>
 *       <td>the named host-test method renamed. RED, naming the row and the missing method</td></tr>
 *   <tr><td>{@link #theEraBudgetHasNoDebugLeverToday}</td>
 *       <td><b>the debug-lever option, implemented</b> — {@code MlsPeerGuard.resetEraBudget}
 *           added to the debug receiver's {@code rebuildreset} arm. RED, and the message says which
 *           claims to re-derive. This is the control that matters most: it is the check's whole
 *           purpose to notice that lever landing</td></tr>
 *   <tr><td>{@link #theAdbRecipeForTheRebuildArmStillHolds}</td>
 *       <td>{@code MlsPeerGuard.allowEraAdvance} moved ABOVE the opening look, which is the one
 *           reordering that kills the recipe (RED); and separately {@code REBUILD}'s ration raised
 *           from 4 to 8, which moves the recipe's drive count and so must red the row that states
 *           it (RED, and the message does the arithmetic and says to warn whoever is at the
 *           device)</td></tr>
 * </table>
 *
 * <p><b>Inject against a COPY, not the shared tree.</b> These scans read
 * {@code MlsProviderTransport.java} and {@code RcsDebugSendReceiver.java} as text while other agents
 * are editing them.
 */
public final class MlsFetchLedgerCoverageGuardTest {

    // ---- 1. every caller has a row --------------------------------------------------------------

    /**
     * A door with no coverage row is a door nobody has been asked about.
     *
     * <p>The row set is built by iterating {@link MlsFetchLedger.Caller#values()}, so this cannot
     * fail by construction today — which is the point of also checking the row's INTERNAL
     * consistency here. A row is only worth having if its fields agree with its status.
     */
    @Test
    public void everyLedgerCallerHasACoverageRow() {
        final List<String> bad = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            final MlsFetchLedgerCoverage.Row r = MlsFetchLedgerCoverage.of(c);
            if (r == null) {
                bad.add(c + ": no row");
                continue;
            }
            if (r.caller != c) bad.add(c + ": row is keyed to " + r.caller);
            if (r.evidence == null || r.evidence.length() < 20) {
                bad.add(c + ": a row with no evidence is a status with nothing behind it");
            }
            if (r.makesAClaim()) {
                if (r.callSites < 1) {
                    bad.add(c + ": claims " + r.status + " but declares no call sites. A claim "
                            + "about an arm has to say how many arms it covers.");
                }
                if (r.hostTest == null) {
                    bad.add(c + ": claims " + r.status + " and names no host test. The DoD-3 shape "
                            + "is 'device-exercised OR declares what covers it instead'.");
                }
                bad.addAll(statusAgreesWithTheSites(r));
            } else {
                if (!r.sites.isEmpty()) {
                    bad.add(c + ": NOT_EXAMINED but declares call sites — a site with a state is a "
                            + "claim and this row is not making one.");
                }
                if (r.hostTest != null || r.claimFalsifier != null) {
                    bad.add(c + ": NOT_EXAMINED but names a test. Say what was examined instead.");
                }
            }
            if (r.status == MlsFetchLedgerCoverage.Status.DEVICE_REACHABLE_NOT_YET_RUN
                    && r.recipeDrives < 1) {
                bad.add(c + ": says a device can reach it and does not say how many drives the "
                        + "recipe takes. That number is what somebody at a device budgets against, "
                        + "and it is the first thing to go stale when a ration moves.");
            }
            // A RECIPE IS FOR SOMETHING LEFT TO RUN, not for a particular label. This was
            // `!makesAReachabilityClaim()`, which is a status test, and PARTIALLY_EXERCISED — a row
            // with proven sites AND outstanding ones — is exactly the case that breaks it: REBUILD
            // has two sites reached, one untouched, and a live five-drive recipe for the rest.
            if (r.recipeDrives != MlsFetchLedgerCoverage.UNCOUNTED && r.sitesOutstanding() == 0) {
                bad.add(c + ": declares a recipe drive count with nothing outstanding to run it "
                        + "for. Every site is either settled or has no distinguishable refusal — "
                        + "delete the recipe or say which site it is still for.");
            }
            if (r.recipeDrives != MlsFetchLedgerCoverage.UNCOUNTED && r.claimFalsifier == null) {
                bad.add(c + ": records an adb recipe and nothing goes red when the fact it rests "
                        + "on stops being true. That is an unfalsifiable comment, retyped as a "
                        + "number somebody will take to a device.");
            }
            if (r.makesAReachabilityClaim() && r.claimFalsifier == null) {
                bad.add(c + ": claims " + r.status + " with no falsifier. That is exactly the "
                        + "unfalsifiable comment this class exists to eliminate, retyped as data.");
            }
            if (!r.makesAReachabilityClaim()
                    && r.blocker != MlsFetchLedgerCoverage.Blocker.NONE) {
                bad.add(c + ": names a blocker without claiming reachability is blocked.");
            }
            if (r.status == MlsFetchLedgerCoverage.Status.NO_ADB_FIXTURE_KNOWN
                    && r.blocker == MlsFetchLedgerCoverage.Blocker.NONE) {
                bad.add(c + ": says no fixture is known and names no bound. 'We could not think of "
                        + "one' and 'this bound prevents one' are different claims.");
            }
        }
        if (!bad.isEmpty()) fail("coverage rows are inconsistent with their own status: " + bad);
        assertEquals("every caller must have exactly one row",
                MlsFetchLedger.Caller.values().length, MlsFetchLedgerCoverage.rows().size());
    }

    /**
     * <b>The status and the site arithmetic must say the same thing.</b>
     *
     * <p>This is what stops {@link MlsFetchLedgerCoverage.Status#PARTIALLY_EXERCISED} becoming a
     * softer {@code DEVICE_EXERCISED}. The status is DECLARED and the sites are DECLARED, both by
     * hand, and this asserts the relation between them — the same reason
     * {@code MlsRebuildOutcome} declares its two flags independently instead of deriving one from
     * the other, and the same reason the recipe's drive count is a literal.
     *
     * <p>The two reachability statuses require a numerator of ZERO, deliberately: they are
     * PREDICTIONS about what a device could do, and a row with a site already proven is no longer
     * predicting. That is the direction {@code REBUILD} was wrong in — two sites device-proven
     * under a status that says nobody has run it.
     */
    private static List<String> statusAgreesWithTheSites(final MlsFetchLedgerCoverage.Row r) {
        final List<String> bad = new ArrayList<>();
        final int done = r.sitesExercised();
        final int of = r.exercisableSites();
        final String arithmetic = done + " of " + of + " exercisable site(s) (" + r.sites + ")";
        switch (r.status) {
            case DEVICE_EXERCISED:
                if (of < 1 || done != of) {
                    bad.add(r.caller + ": DEVICE_EXERCISED with " + arithmetic + ". Every "
                            + "exercisable site must have been refused on a device AND had its "
                            + "remedy seen; " + r.sitesIn(
                                    MlsFetchLedgerCoverage.SiteState.UNEXERCISED)
                            + " has not been reached and " + r.sitesIn(MlsFetchLedgerCoverage
                                    .SiteState.REFUSAL_OBSERVED_REMEDY_UNVERIFIED)
                            + " was reached on a build that predates the arm it is about.");
                }
                break;
            case PARTIALLY_EXERCISED:
                if (done < 1 || done >= of) {
                    bad.add(r.caller + ": PARTIALLY_EXERCISED with " + arithmetic + ". The value "
                            + "means SOME and not ALL — 0 is one of the reachability statuses or "
                            + "NOT_EXAMINED, and all of them is DEVICE_EXERCISED. This is the "
                            + "arithmetic that keeps it from drifting into the stronger value.");
                }
                break;
            case DEVICE_REACHABLE_NOT_YET_RUN:
            case NO_ADB_FIXTURE_KNOWN:
                if (done != 0) {
                    bad.add(r.caller + ": " + r.status + " with " + arithmetic + ". These two are "
                            + "PREDICTIONS about what a device could be made to do; a row with a "
                            + "site already proven has measured something and must say so. This is "
                            + "the exact state REBUILD sat in until it was re-pointed — two of three "
                            + "sites device-proven under a status reading 'nobody has run it'.");
                }
                break;
            default:
                break;
        }
        return bad;
    }

    // ---- 2. a claim counts every call site of its caller -----------------------------------------

    /**
     * A coverage claim covers the caller's WHOLE surface, not the one site somebody happened to
     * reach.
     *
     * <p>{@code REBUILD} is the case that forced this. It has three call sites — the pack look, the
     * opening look and the confirming look — and exactly one of them is device-proven. A per-caller
     * status with no site count would let that one stand for all three, which is how "the arm is
     * covered" gets said about an arm nothing has touched.
     *
     * <p>Keyed on the CONSTANT'S USE and read from {@link #codeOnly}, so a constant surviving only
     * in a comment does not count as a site. The needle carries a word boundary: a future
     * {@code REBUILD_SOMETHING} must not be counted as {@code REBUILD}.
     */
    @Test
    public void aCoverageClaimCountsEveryCallSiteOfItsCaller() throws IOException {
        final String src = codeOnly(readTransport()) + "\n" + codeOnly(readDebugReceiver());
        final List<String> wrong = new ArrayList<>();
        int claims = 0;
        int sitesSeen = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            final int found = occurrences(src,
                    Pattern.compile("MlsFetchLedger\\.Caller\\." + r.caller.name() + "\\b"));
            sitesSeen += found;
            if (!r.makesAClaim()) continue;
            claims++;
            if (found != r.callSites) {
                wrong.add(r.caller + ": row says " + r.callSites + " call site(s), source has "
                        + found);
            }
        }
        // ZERO HITS MUST FAIL. A renamed constant, a moved file or a broken locator all
        // produce a scan that finds nothing and reports every count as satisfied-by-absence.
        assertTrue("no MlsFetchLedger.Caller constant was found at any call site — the needle or the "
                + "source locator is broken, and this guard is passing on an empty scan",
                sitesSeen >= MlsFetchLedger.Caller.values().length);
        assertTrue("no coverage row makes a claim, so nothing above was checked. A table of "
                + "NOT_EXAMINED rows is a record, not a guard.", claims >= 1);
        if (!wrong.isEmpty()) {
            fail("a coverage claim no longer covers its caller's call sites: " + wrong
                    + ". A caller that grew a site grew an ARM, and a claim written before that site "
                    + "existed cannot be inherited by it — re-derive the row (which of the sites has "
                    + "been reached on a device, and what covers the rest) rather than editing the "
                    + "number to match.");
        }
    }

    // ---- 3. a claim names host coverage that exists ----------------------------------------------

    /**
     * Every {@code Class#method} a row names must resolve to a real test method.
     *
     * <p>The precedent is exact: {@code STATE_CHANGING} named an arm the
     * dispatcher could never produce, so the lab-peer gate never ran for it and <b>the entry's own
     * presence is what made the gate read as covered</b>. A coverage row naming a test that was
     * renamed or deleted fails in the identical way — it reads as covered, forever, and nothing
     * says otherwise.
     */
    @Test
    public void everyClaimNamesHostCoverageThatExists() throws IOException {
        final List<String> missing = new ArrayList<>();
        int checked = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            for (final String ref : new String[] {r.hostTest, r.claimFalsifier}) {
                if (ref == null) continue;
                checked++;
                final int hash = ref.indexOf('#');
                if (hash <= 0 || hash == ref.length() - 1) {
                    missing.add(r.caller + ": '" + ref + "' is not Class#method");
                    continue;
                }
                final String cls = ref.substring(0, hash);
                final String method = ref.substring(hash + 1);
                final String body;
                try {
                    body = readTest(cls);
                } catch (final IOException e) {
                    missing.add(r.caller + ": " + cls + " does not exist under tests/");
                    continue;
                }
                if (!Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(")
                        .matcher(codeOnly(body)).find()) {
                    missing.add(r.caller + ": " + cls + " has no method " + method + "()");
                }
            }
        }
        assertTrue("no coverage row named a test, so this guard checked nothing", checked >= 2);
        if (!missing.isEmpty()) {
            fail("coverage rows name host tests that are not there: " + missing + ". A row naming a "
                    + "test that does not exist reports the arm as covered and always will — the "
                    + "same shape. Point the row at the test that replaced it, or drop the claim.");
        }
    }

    // ---- 4. the falsifiers -----------------------------------------------------------------------

    /**
     * <b>The era budget cannot be cleared from {@code adb} today — and this test is how we find out
     * when that changes.</b>
     *
     * <p>One option considered was a debug lever that resets {@link MlsFetchLedger}'s outer
     * neighbour, {@code MlsPeerGuard}'s era budget, so a fixture could drive the rebuild rung past
     * its 2-per-rolling-hour bound. It was not taken, for a recorded reason: the era
     * budget is the only one of the seven bounds the stalled-conversation notification's
     * <b>Try again</b> clears that bounds the PEER's cost rather than ours, and it is the counter
     * that makes seventeen hand-driven re-creations impossible even on a peer the lab
     * allowlist permits.
     *
     * <p><b>This is not a ban.</b> A lever may well be right later, and if it is, it should be
     * written — gated on {@code Build.TYPE} and named in {@code RcsDebugSendReceiver}'s
     * {@code STATE_CHANGING} so the lab-peer allowlist runs for it, exactly as
     * {@code --ez rebuildreset} and {@code --ez cooldownsreset} are. What must not happen is the
     * lever landing while coverage rows elsewhere still rest on the budget being unclearable. So
     * this check fails loudly and tells the next person which claims to re-derive.
     */
    @Test
    public void theEraBudgetHasNoDebugLeverToday() throws IOException {
        final String needle =
                MlsFetchLedgerCoverage.Blocker.ERA_BUDGET_RATE.clearedBy;
        assertTrue("the blocker no longer names the method that would clear it, so this falsifier "
                + "has nothing to look for", needle != null && !needle.isEmpty());
        final String receiver = codeOnly(readDebugReceiver());
        // The scan is on the debug receiver alone and deliberately so: MlsStalledActionReceiver
        // calls resetEraBudget and MUST — that is a PERSON pressing Try again, which is the reset
        // this bound is designed to have. What must not exist is a path from an adb broadcast.
        assertTrue("the debug receiver source did not load, or is empty — this falsifier is passing "
                + "on nothing", receiver.length() > 10_000);
        if (receiver.contains(needle)) {
            fail("RcsDebugSendReceiver now reaches " + needle + "(), so MlsPeerGuard's era budget is "
                    + "clearable from adb. That may be the right call — but "
                    + "it invalidates every MlsFetchLedgerCoverage row whose reachability rests on "
                    + "the era budget, and the lever must be named in STATE_CHANGING so the "
                    + "lab-peer allowlist runs for it (a gate that never runs is the failure above). "
                    + "Re-derive the rows, then update this check to reflect the new reachability.");
        }
    }

    /**
     * <b>The recorded adb recipe for {@code REBUILD}'s refusal arm still works</b> — the falsifier
     * for the one row that claims {@link MlsFetchLedgerCoverage.Status#DEVICE_REACHABLE_NOT_YET_RUN}.
     *
     * <p>The arm was recorded as unreachable on two interlocks. The recipe escapes both,
     * and each escape rests on ONE fact that this checks:
     *
     * <ol>
     *   <li><b>The opening look is charged BEFORE the era budget is consulted.</b> That is why the
     *       ration is spent by ATTEMPTS rather than by completed rebuilds, and it is why the era
     *       budget — the second interlock — never applies. Reorder those two and the recipe dies
     *       silently, because the symptom is a fixture that produces no refusal, which reads exactly
     *       like a clean run.</li>
     *   <li><b>Exhausting {@code REBUILD} takes no more drives than {@code RECONCILE_DRIVE}
     *       survives.</b> That is the first interlock, stated as arithmetic: the health read gates
     *       the rung, so if it is refused first the ladder stops ABOVE the rung and the arm is never
     *       asked. A rebuild that runs spends TWO {@code REBUILD} looks (opening and confirming)
     *       against ONE {@code RECONCILE_DRIVE} look, which is what puts the rebuild look ahead.</li>
     * </ol>
     *
     * <p>The third fact the recipe needs — that {@code debug.rcs.mls_fetch_ceiling} is read live,
     * so the shared ceiling can be lifted clear of the sequence — is a device property and is not
     * checkable here. It is stated on the row and it is the part a device run must confirm.
     */
    @Test
    public void theAdbRecipeForTheRebuildArmStillHolds() throws IOException {
        final MlsFetchLedgerCoverage.Row row =
                MlsFetchLedgerCoverage.of(MlsFetchLedger.Caller.REBUILD);
        // KEYED ON WHAT IS LEFT TO RUN, NOT ON A LABEL. This asserted
        // `status == DEVICE_REACHABLE_NOT_YET_RUN` literally, and a later change re-pointed the row to
        // PARTIALLY_EXERCISED — two of three sites proven, one untouched — which would have broken
        // a check that was still true in substance. A recipe exists for a row with an OUTSTANDING
        // site; the status is a summary of that arithmetic, not the fact itself (key on
        // an engine-visible fact, never on a label).
        assertTrue("REBUILD has nothing outstanding, so there is no arm left for this recipe to "
                        + "reach and the recipe should be deleted rather than kept green: " + row,
                row.sitesOutstanding() >= 1);

        // (1) THE CHARGE ORDER, read out of rebuildConversation's own body.
        final String body = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        assertTrue("rebuildConversation's body did not resolve — the scan has nothing to order",
                body.length() > 2_000);
        final int look = body.indexOf("MlsFetchLedger.Caller.REBUILD");
        final int eraBudget = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        assertTrue("no MlsFetchLedger.Caller.REBUILD in rebuildConversation — the opening look has "
                + "moved or been renamed, and the recipe is written about a call that is not there",
                look >= 0);
        assertTrue("no MlsPeerGuard.allowEraAdvance( in rebuildConversation — the era budget is no "
                + "longer charged here, which changes the interlock this recipe navigates",
                eraBudget >= 0);
        assertTrue("the rebuild's opening look is now charged AFTER MlsPeerGuard.allowEraAdvance. "
                        + "That reverses the second interlock: the ledger would then be "
                        + "spent only by rebuilds the era budget ALLOWED, so REBUILD's ration could "
                        + "never be reached from adb and the recipe on the coverage row is dead. "
                        + "Re-derive the row before changing this order deliberately.",
                look < eraBudget);

        // (2) THE ARITHMETIC — REWRITTEN 2026-09-09 BY A DEVICE RUN, and the version it replaces
        // is worth recording because it was a right verdict resting on a wrong reason.
        //
        // IT SAID: a completed rebuild spends TWO REBUILD looks (opening + confirming) against ONE
        // RECONCILE_DRIVE look, so the recipe needs (ration/2 + 1) = 3 drives, and it asserted that
        // against RECONCILE_DRIVE's ration on the theory that the health read would otherwise be
        // refused first — interlock (1), restated as a check.
        //
        // Measured on a device (ceiling 20): the arm fired on
        // drive FOUR, not three, and RECONCILE_DRIVE was never what limited it. Two things were
        // wrong. A rebuild that does NOT CONVERGE never reaches the confirming look, so it spends
        // ONE REBUILD look; and the second rebuild of a run does not converge, because ENSURE_READY
        // spends 3 per rebuild against REBUILD's 2 and hits its OWN ration first. So the worst case
        // is one look per attempt, and the drive count is REBUILD.ration + 1.
        //
        // THE RECONCILE_DRIVE ASSERTION IS GONE RATHER THAN CORRECTED, deliberately. At one look
        // per attempt the recipe needs 5 drives against that ration of 4, so the old assertion
        // would now FAIL on a recipe that demonstrably WORKS — and it worked because the drive's
        // charge lands on a ledger keyed "<no conversation key>", a different record from the
        // conversation's, so the two rations were never competing on this path at all. Keeping a
        // check whose reasoning the measurement contradicted is exactly the shape this work keeps
        // finding (a refusal speaking for a bound it never checked). The relationship is
        // recorded on the row instead, where it is evidence rather than an assertion.
        // THE ROW'S DRIVE COUNT IS A LITERAL AND MUST STAY ONE. Deriving it from the ration here
        // (or there) makes this a comparison of a number with itself — which is what it was until a
        // negative control raised REBUILD's ration to 8 and this test stayed GREEN on a recipe that
        // had just become wrong. An assertion about nothing is the defect this work keeps finding
        // (MlsAdvancerElection.takeoverReachableWithin, finding 3 of the plan); it is easy to
        // write by accident and invisible to review, and only a mutation shows it.
        final int rebuildRation = MlsFetchLedger.Caller.REBUILD.ration;
        assertEquals("REBUILD's ration is " + rebuildRation + ", so the recorded adb recipe needs "
                        + (rebuildRation + 1) + " drives inside one WINDOW_MS in the WORST case — "
                        + "one attempt per look, where no rebuild converges and each spends only "
                        + "its opening look, plus the attempt that is refused. (The one device run "
                        + "so far took 4, because its first rebuild DID converge and earned the "
                        + "confirming look too.) The row says " + row.recipeDrives + ". Changing the "
                        + "ration changes a plan somebody is about to run on hardware, and it must "
                        + "not go stale silently: re-derive the row and warn whoever is at the "
                        + "device, rather than editing the number to match.",
                rebuildRation + 1, row.recipeDrives);

        // AND THE CONSTRAINT THAT ACTUALLY BOUND, so it cannot drift unnoticed: ENSURE_READY
        // outspends REBUILD inside a single rebuild, which is why the second one does not converge
        // and why the drive count is the worst case rather than the best. If that ever inverts, the
        // recipe gets CHEAPER and the row's drive count becomes an overestimate — not a broken
        // fixture, but a stale plan, and the person at the device should be told which.
        assertTrue("ENSURE_READY's ration (" + MlsFetchLedger.Caller.ENSURE_READY.ration + ") no "
                        + "longer sits at or below REBUILD's (" + rebuildRation + "). The device "
                        + "run's whole shape — a second rebuild that does not converge, so the "
                        + "confirming look is never charged — depends on ENSURE_READY running out "
                        + "first, because it spends 3 per rebuild against REBUILD's 2. Re-derive "
                        + "the row's recipe before anyone runs it again.",
                MlsFetchLedger.Caller.ENSURE_READY.ration <= rebuildRation);
    }

    /**
     * <b>A site exempted from the denominator really does discard its look</b> — axis 1,
     * second half.
     *
     * <p>{@link MlsFetchLedgerCoverage.SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM} is the one value
     * here that makes a caller's coverage LOOK better: it takes a site out of the denominator
     * {@link MlsFetchLedgerCoverage.Status#DEVICE_EXERCISED} is measured against. Left on trust it
     * is the hand-written exemption table this guard family has already replaced twice.
     *
     * <p>So it is not on trust. The claim is corroborated against the transport: a site whose look
     * is discarded is one where {@code .orNull()} is applied in the SAME STATEMENT as the
     * {@code MlsFetchLedger.Caller} constant, so a refusal and "the server had nothing" produce the
     * identical value and the identical silence. The counts must be EQUAL, which is what makes it
     * two-directional — a real refusal arm cannot be exempted, and an exempt site cannot quietly
     * grow one.
     *
     * <p><b>What it can and cannot see, stated rather than found later.</b> "Same statement" is a
     * scan to the next {@code ;}, so a site that discards its look two statements later reads as
     * distinguishable. That is the conservative direction: it counts a site as a coverage GAP,
     * which is the answer that keeps somebody looking. {@code ENSURE_READY}'s reclaim
     * is exactly that case and it is correctly counted as a gap, because it tests
     * {@code reclaim.refused()} before calling {@code .orNull()} on a later line.
     */
    @Test
    public void anUnexercisableSiteReallyDiscardsItsLook() throws IOException {
        final String src = codeOnly(readTransport()) + "\n" + codeOnly(readDebugReceiver());
        int discardsAnywhere = 0;
        final List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            final int discards = sitesDiscardingTheirLook(src, r.caller);
            discardsAnywhere += discards;
            if (r.sites.isEmpty()) continue;
            checked++;
            final List<String> exempt =
                    r.sitesIn(MlsFetchLedgerCoverage.SiteState.NO_DISTINGUISHABLE_REFUSAL_ARM);
            if (exempt.size() != discards) {
                wrong.add(r.caller + ": the row exempts " + exempt.size() + " site(s) from the "
                        + "denominator " + exempt + ", and " + discards + " of its call sites "
                        + "actually discard the look");
            }
        }
        // ZERO HITS MUST FAIL, and here the needle is a NEGATIVE one for most rows —
        // so it has to demonstrate it can find something before its zeroes mean anything.
        assertTrue("no call site anywhere applies .orNull() in the statement that names its Caller. "
                + "Either the needle or the statement scan is broken, and every 'this site has no "
                + "distinguishable refusal' claim below is being confirmed by an empty scan.",
                discardsAnywhere >= 1);
        assertTrue("no coverage row declares any sites, so this checked nothing", checked >= 1);
        if (!wrong.isEmpty()) {
            fail("A site's exemption from the coverage denominator does not match the source: "
                    + wrong + ". Exempting a site that HAS a distinguishable refusal arm hides a "
                    + "real gap; refusing to exempt one that does not leaves a caller permanently "
                    + "short of DEVICE_EXERCISED for a reason nobody can close. Re-derive the row "
                    + "against the call sites, rather than editing the count to agree.");
        }
    }

    /**
     * <b>{@code ENSURE_READY}'s two distinguishable arms still decline, and still fail open</b> —
     * the host coverage its row names.
     *
     * <p>This is the BEHAVIOUR a device run saw on the bump arm (H1, on {@code deviceA}): a
     * ledger refusal, then <i>"the 'Era changed from' re-create for … is PROCEEDING WITHOUT the
     * server's era"</i> with neither decline line firing, because a rebuild had already dropped
     * both halves of the state. Two arms per site and they are not interchangeable — the decline is
     * the second door held shut (do not re-create over a conversation the server holds
     * at an era we declined to read), and the fail-open is the case where declining leaves the
     * conversation with nothing at all.
     *
     * <p>Derived from the ROW rather than from a number typed here: the sites the row calls
     * distinguishable must each test {@code refused()} twice — once with
     * {@code stateAlreadyDestroyed} and once without — and the site the row exempts must test it
     * not at all. So the row lying in either direction fails here.
     */
    @Test
    public void bothDistinguishableEnsureReadyArmsStillDecline() throws IOException {
        final MlsFetchLedgerCoverage.Row row =
                MlsFetchLedgerCoverage.of(MlsFetchLedger.Caller.ENSURE_READY);
        final String body = bodyOf(codeOnly(readTransport()), "ensureReady");
        assertTrue("ensureReady's body did not resolve — this guard has nothing to read", 
                body.length() > 5_000);
        final List<Integer> at = new ArrayList<>();
        final Matcher m = Pattern.compile("MlsFetchLedger\\.Caller\\.ENSURE_READY\\b").matcher(body);
        while (m.find()) at.add(Integer.valueOf(m.start()));
        assertEquals("ensureReady no longer holds the call sites the coverage row is about — the "
                        + "sites moved and the row must move with them",
                row.sites.size(), at.size());

        final List<String> wrong = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) {
            final int from = at.get(i).intValue();
            final int to = (i + 1 < at.size()) ? at.get(i + 1).intValue() : body.length();
            final String arm = body.substring(from, to);
            final MlsFetchLedgerCoverage.Site site = row.sites.get(i);
            final int refused = occurrences(arm, Pattern.compile("\\.refused\\(\\)"));
            final int destroyed = occurrences(arm, Pattern.compile("\\bstateAlreadyDestroyed\\b"));
            if (site.state.exercisable()) {
                if (refused < 2) {
                    wrong.add(site.name + ": " + refused + " refused() test(s), 2 required — the "
                            + "decline and the fail-open are different arms and a site with one of "
                            + "them has lost the other");
                }
                if (destroyed < 1) {
                    wrong.add(site.name + ": no stateAlreadyDestroyed test. Without it the arm "
                            + "either always declines (leaving a rebuilt conversation with nothing "
                            + "at all) or always proceeds (re-creating over a conversation the "
                            + "server holds at an era we declined to read — the second door).");
                }
            } else if (refused != 0) {
                wrong.add(site.name + ": the row exempts this site as having no distinguishable "
                        + "refusal, and it tests refused() " + refused + " time(s)");
            }
        }
        if (!wrong.isEmpty()) {
            fail("ENSURE_READY's refusal arms no longer match what its coverage row says about "
                    + "them: " + wrong);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * How many of {@code caller}'s call sites DISCARD their look in the statement that names it.
     *
     * <p>"Statement" is the span from the {@code Caller} constant to the next {@code ;}. The source
     * is {@link #codeOnly}, so a {@code ;} inside a comment or a string cannot end it early.
     */
    private static int sitesDiscardingTheirLook(final String src, final MlsFetchLedger.Caller c) {
        final Matcher m =
                Pattern.compile("MlsFetchLedger\\.Caller\\." + c.name() + "\\b").matcher(src);
        int n = 0;
        while (m.find()) {
            final int end = src.indexOf(';', m.end());
            if (end < 0) continue;
            if (src.substring(m.start(), end).contains(".orNull()")) n++;
        }
        return n;
    }

    private static int occurrences(final String src, final Pattern p) {
        final Matcher m = p.matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * The brace-matched body of {@code name}, skipping any DELEGATING overload.
     *
     * <p>{@code rebuildConversation} has a two-argument overload whose whole body is
     * {@code return rebuildConversation(…);}. Taking the first match would order two calls that are
     * not there — the same trap {@code MlsGetGroupInfoLedgerGuardTest} records having been caught by
     * a negative control rather than by reading.
     */
    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            return body;
        }
        return "";
    }

    private static boolean isDelegateTo(final String body, final String name) {
        final String inner = body.trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) return false;
        final String stmt = inner.substring(1, inner.length() - 1).trim();
        return stmt.startsWith("return " + name + "(") && stmt.indexOf(';') == stmt.length() - 1;
    }

    private static String bracedBlock(final String src, final int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    /**
     * The source with comments AND string contents blanked, offsets preserved.
     *
     * <p>Blanking rather than deleting keeps every index comparable with the raw file, and blanking
     * string contents is what stops a constant surviving in a log message from counting as a call
     * site — the regression measured against the sibling guard.
     */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        final int n = out.length;
        while (i < n) {
            final char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                final char quote = c;
                i++;
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        i++;
                        if (out[i] != '\n') out[i] = ' ';
                        i++;
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String readTransport() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java");
    }

    private static String readDebugReceiver() throws IOException {
        return read("src/com/android/messaging/rcs/RcsDebugSendReceiver.java");
    }

    private static String readTest(final String simpleClassName) throws IOException {
        return read("tests/src/com/android/messaging/rcs/engine/mls/" + simpleClassName + ".java");
    }

    /** Same locator as the sibling guards: works from the module dir or the tree root. */
    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
