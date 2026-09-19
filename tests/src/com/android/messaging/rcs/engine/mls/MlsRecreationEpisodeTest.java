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
 * <b>Which doors of one recovery episode charge G2 again</b> — event E1 of the
 * Stage 0 interaction catalogue.
 *
 * <p>Three of the four edges in that event were reconciled by a boolean parameter, a local variable
 * and an ordering comment; the fourth — {@code eraAdvance} → the rebuild fallback — had
 * nothing at all. This pins the rule that covers all four, so the answers stop being properties of
 * four separate call sites that each believe they are the only one counting.
 *
 * <p><b>No behaviour changes.</b> Every edge keeps the charge it had. What is new is that the answer
 * is now expressed once, in one predicate, with arguments read from production.
 */
public final class MlsRecreationEpisodeTest {

    // ---- the rule ------------------------------------------------------------------------------

    /** Nothing spent yet: this door pays. */
    @Test
    public void aDoorReachedWithNothingChargedPaysForItself() {
        assertEquals(MlsRecreationEpisode.PriorCharge.NONE,
                MlsRecreationEpisode.priorCharge(/*spent=*/ false, /*consumed=*/ false));
        assertTrue(MlsRecreationEpisode.needsItsOwnCharge(MlsRecreationEpisode.PriorCharge.NONE));
    }

    /**
     * A charge the server has not taken a re-creation on still covers the next door.
     *
     * <p>This is the {@code recreateAlreadyCharged} parameter and the {@code recreateCharged} local,
     * said once. The rebuild charges on the CLASSIFICATION before any create is put to the server;
     * {@code ensureReady}'s reclaim arm is reachable only through the bump's create being REFUSED.
     * In neither case can a member have re-joined, so charging again would bill one re-creation
     * twice.
     */
    @Test
    public void aChargeTheServerHasNotTakenCoversTheNextDoor() {
        final MlsRecreationEpisode.PriorCharge p =
                MlsRecreationEpisode.priorCharge(/*spent=*/ true, /*consumed=*/ false);
        assertEquals(MlsRecreationEpisode.PriorCharge.SPENT_AND_UNCONSUMED, p);
        assertFalse("a charge that has not bought a re-creation the server took must cover the next "
                + "door — otherwise one re-creation reached by two methods is billed twice, and G2 "
                + "refuses the NEXT repair earlier than the incident record justifies",
                MlsRecreationEpisode.needsItsOwnCharge(p));
    }

    /**
     * <b>The edge, and the decision.</b> Once the server has ACCEPTED the re-creation a charge
     * paid for, that charge is used up.
     *
     * <p>The rebuild fallback runs only after {@code createMlsConversation} returned OK carrying
     * a Welcome minted per member from freshly claimed KeyPackages. What was then measured is that
     * the server's era did not move — <b>not</b> that the peer was left untouched, which is not
     * observable from here. An outcome we cannot measure must not be the cheap one, so the rebuild
     * that follows is a second peer-facing re-creation and pays again.
     */
    @Test
    public void aChargeTheServerAcceptedIsUsedUpAndTheNextDoorPaysAgain() {
        final MlsRecreationEpisode.PriorCharge p =
                MlsRecreationEpisode.priorCharge(/*spent=*/ true, /*consumed=*/ true);
        assertEquals(MlsRecreationEpisode.PriorCharge.SPENT_AND_CONSUMED, p);
        assertTrue("the rebuild follows a create the SERVER ACCEPTED — a Welcome per member and "
                + "a KeyPackage claimed for each. Treating the rebuild as the same event would let "
                + "two re-creations through on one charge, which is the hole closed from "
                + "the other side.", MlsRecreationEpisode.needsItsOwnCharge(p));
    }

    /**
     * "Accepted" is meaningless when nothing was spent, and is ignored — the same shape as
     * {@link MlsReestablishPolicy#classify(boolean, long)}'s {@code serverEra}.
     */
    @Test
    public void anAcceptanceWithNoChargeBehindItIsNotAPriorCharge() {
        assertEquals("nothing was charged, so there is no prior charge for an acceptance to consume",
                MlsRecreationEpisode.PriorCharge.NONE,
                MlsRecreationEpisode.priorCharge(/*spent=*/ false, /*consumed=*/ true));
    }

    /** Every state answers, and no state falls through to an accidental default. */
    @Test
    public void everyPriorChargeStateAnswersAndSaysWhy() {
        for (final MlsRecreationEpisode.PriorCharge p
                : MlsRecreationEpisode.PriorCharge.values()) {
            final String line = MlsRecreationEpisode.line(p);
            assertTrue(p + " has no log line, so a reader of a doubled charge has nothing to read",
                    line != null && line.length() > 40);
            // Not an assertion about the wording — an assertion that the two directions are
            // distinguishable in a log, which is what a reader of "budget spent (2/2 this hour)"
            // needs and did not have.
            assertEquals(p + "'s line must agree with its verdict",
                    MlsRecreationEpisode.needsItsOwnCharge(p),
                    !line.contains("not charged again"));
        }
    }

    /**
     * The two E1 verdicts that feed {@code spent} come from {@link MlsReestablishPolicy}, and only a
     * measured "nobody re-joins" is free.
     *
     * <p>Read from the production enum rather than restated, so a verdict added later that charges
     * arrives here as an argument this rule must handle.
     */
    @Test
    public void everyChargingVerdictFeedsThisRuleAsASpentCharge() {
        for (final MlsReestablishPolicy.Verdict v : MlsReestablishPolicy.Verdict.values()) {
            final MlsRecreationEpisode.PriorCharge p =
                    MlsRecreationEpisode.priorCharge(v.chargesEraBudget(), /*consumed=*/ false);
            assertEquals(v + " charges=" + v.chargesEraBudget() + " must map to the matching prior "
                    + "state — this is the argument rebuildConversation passes down to ensureReady",
                    v.chargesEraBudget() ? MlsRecreationEpisode.PriorCharge.SPENT_AND_UNCONSUMED
                            : MlsRecreationEpisode.PriorCharge.NONE, p);
        }
    }

    // ---- bound to production ------------------------------------------------------------------

    /** The invoked name both source-bound tests key on (never a label). */
    private static final String PRIOR_CHARGE = "MlsRecreationEpisode.priorCharge(";

    /**
     * <b>The predicate is wired, at the edge that had no reconciliation.</b>
     *
     * <p>The failure this avoids is a predicate that exists, is tested and is called by nothing. Asserted
     * on the INVOKED NAME and inside the method that owns the edge.
     */
    @Test
    public void theM0azFallbackDeclaresWhatTheEpisodeHasAlreadyCharged() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "eraAdvanceLocked");
        assertTrue("eraAdvanceLocked not found — the rebuild fallback lives in it and this guard has "
                + "stopped watching the edge", body.length() > 0);
        final int prior = body.indexOf(PRIOR_CHARGE);
        final int rebuild = body.indexOf("rebuildConversation(");
        assertTrue("eraAdvanceLocked does not declare what this episode has already charged before "
                + "calling rebuildConversation. That edge charges G2 a second time and was, until "
                + "this landed, the only edge in event E1 with no reconciliation at all — every "
                + "neighbour has one.", prior >= 0);
        assertTrue("eraAdvanceLocked no longer calls rebuildConversation — the rebuild fallback is "
                + "gone and this guard is watching nothing", rebuild >= 0);
        assertTrue("the prior-charge declaration must be made before the rebuild it describes",
                prior < rebuild);
    }

    /**
     * That edge's second argument is the advance's OWN RPC result, not a literal.
     *
     * <p>This is invariant I5 in the transport: {@code true} typed at the call site would be a claim
     * about what the server did, asserted by a constant. The arm is currently only reachable with
     * {@code r.ok()} true, so the value does not change today — but the value is not the point. A
     * later arm that reaches this fallback WITHOUT an accepted create would then be classified
     * correctly instead of inheriting an assumption.
     */
    @Test
    public void theM0azEdgeReadsTheServersAnswerRatherThanAssumingIt() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "eraAdvanceLocked");
        final int at = body.indexOf(PRIOR_CHARGE);
        assertTrue("no priorCharge call in eraAdvanceLocked", at >= 0);
        final String args = argumentsAt(body, at + PRIOR_CHARGE.length() - 1);
        assertTrue("could not read the arguments to " + PRIOR_CHARGE + " — the scan has gone stale",
                args.length() > 0);
        // Read over CODE ONLY, so the /*name=*/ hints are already blanked and what is left is the
        // expression actually passed.
        assertFalse("eraAdvanceLocked passes LITERALS to priorCharge: (" + squash(args) + "). "
                + "Whether the server accepted the create this charge paid for is a fact about the "
                + "server, and asserting it with a constant typed at the call site is that failure one "
                + "level down. Read it from the create's own RcsMlsControlResult.",
                args.matches("\\s*true\\s*,\\s*(true|false)\\s*"));
        assertTrue("the acceptance argument must be derived from the create's own result — expected "
                + "an ok() read, got (" + squash(args) + ")", args.contains("ok()"));
    }

    /**
     * The three reconciled neighbours ask the SAME predicate, so E1 has one rule rather than four.
     *
     * <p>Zero hits FAIL: a scan that finds none of its subjects has certified a
     * reconciliation it cannot see.
     */
    @Test
    public void everyEdgeInTheEventAsksTheSameRule() throws IOException {
        final String src = SourceScan.transport();
        // name, and the DECLARATION FRAGMENT that picks the real overload rather than its one-line
        // delegating sibling — the failure MlsPeerReJoinBudgetGuardTest hit on its own first run.
        final String[][] doors = {
            {"eraAdvanceLocked", ""},
            {"rebuildConversation", "final MlsRecreationEpisode.PriorCharge"},
            {"ensureReady", "final boolean recreateAlreadyCharged"},
        };
        int sites = 0;
        for (final String[] door : doors) {
            final String body = SourceScan.bodyOf(src, door[0], door[1]);
            assertTrue(door[0] + " not found — one of event E1's four doors has been renamed and "
                    + "this guard has stopped watching it", body.length() > 0);
            if (body.contains("MlsRecreationEpisode.")) sites++;
        }
        assertEquals("event E1's three charging methods must each consult MlsRecreationEpisode. "
                + "Fewer means an edge is back to reconciling itself with a boolean, a local or an "
                + "ordering comment — which is the state the event was found in.", 3, sites);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The text between the parenthesis at {@code openParen} and its match. "" if unbalanced. */
    private static String argumentsAt(final String src, final int openParen) {
        int depth = 0;
        for (int i = openParen; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return src.substring(openParen + 1, i);
        }
        return "";
    }

    private static String squash(final String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
