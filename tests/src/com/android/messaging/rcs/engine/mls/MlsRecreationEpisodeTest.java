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
 * Which doors of one recovery episode charge the era budget again. The rule is one predicate,
 * {@link MlsRecreationEpisode}, fed from production, rather than four call sites each believing it
 * is the only one counting. See docs/mls/budgets.md.
 */
public final class MlsRecreationEpisodeTest {

    /** Nothing spent yet: this door pays. */
    @Test
    public void aDoorReachedWithNothingChargedPaysForItself() {
        assertEquals(MlsRecreationEpisode.PriorCharge.NONE,
                MlsRecreationEpisode.priorCharge(/*spent=*/ false, /*consumed=*/ false));
        assertTrue(MlsRecreationEpisode.needsItsOwnCharge(MlsRecreationEpisode.PriorCharge.NONE));
    }

    /**
     * An unconsumed charge covers the next door: both such doors run before the server has taken
     * any create, so no member can have re-joined.
     */
    @Test
    public void aChargeTheServerHasNotTakenCoversTheNextDoor() {
        final MlsRecreationEpisode.PriorCharge p =
                MlsRecreationEpisode.priorCharge(/*spent=*/ true, /*consumed=*/ false);
        assertEquals(MlsRecreationEpisode.PriorCharge.SPENT_AND_UNCONSUMED, p);
        assertFalse(
                "a charge that has not bought a re-creation the server took must cover the next "
                + "door — otherwise one re-creation reached by two methods is billed twice, and G2 "
                + "refuses the NEXT repair earlier than the attempt record justifies",
                MlsRecreationEpisode.needsItsOwnCharge(p));
    }

    /** Once the server accepted the re-creation the charge is used up; the next rebuild pays. */
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

    /** "Accepted" is ignored when nothing was spent. */
    @Test
    public void anAcceptanceWithNoChargeBehindItIsNotAPriorCharge() {
        assertEquals(
                "nothing was charged, so there is no prior charge for an acceptance to consume",
                MlsRecreationEpisode.PriorCharge.NONE,
                MlsRecreationEpisode.priorCharge(/*spent=*/ false, /*consumed=*/ true));
    }

    /** Every state answers; none falls through to a default. */
    @Test
    public void everyPriorChargeStateAnswersAndSaysWhy() {
        for (final MlsRecreationEpisode.PriorCharge p
                : MlsRecreationEpisode.PriorCharge.values()) {
            final String line = MlsRecreationEpisode.line(p);
            assertTrue(p + " has no log line, so a reader of a doubled charge has nothing to read",
                    line != null && line.length() > 40);
            // The two directions are distinguishable in a log line.
            assertEquals(p + "'s line must agree with its verdict",
                    MlsRecreationEpisode.needsItsOwnCharge(p),
                    !line.contains("not charged again"));
        }
    }

    /**
     * The verdicts that feed {@code spent} are read from {@link MlsReestablishPolicy}, so a new
     * charging verdict arrives here as an argument the rule must handle.
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

    /** The invoked name the source-bound tests key on. */
    private static final String PRIOR_CHARGE = "MlsRecreationEpisode.priorCharge(";

    /** The predicate is called, by invoked name, inside the method that owns the edge. */
    @Test
    public void theM0azFallbackDeclaresWhatTheEpisodeHasAlreadyCharged() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "eraAdvanceLocked");
        assertTrue(
                "eraAdvanceLocked not found — the rebuild fallback lives in it and this guard has "
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
     * That edge's second argument is the advance's own RPC result, not a literal: a constant would
     * be a claim about what the server did.
     */
    @Test
    public void theM0azEdgeReadsTheServersAnswerRatherThanAssumingIt() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "eraAdvanceLocked");
        final int at = body.indexOf(PRIOR_CHARGE);
        assertTrue("no priorCharge call in eraAdvanceLocked", at >= 0);
        final String args = argumentsAt(body, at + PRIOR_CHARGE.length() - 1);
        assertTrue("could not read the arguments to " + PRIOR_CHARGE + " — the scan has gone stale",
                args.length() > 0);
        // Code only: the argument-name hints are blanked, leaving the expression passed.
        assertFalse("eraAdvanceLocked passes LITERALS to priorCharge: (" + squash(args) + "). "
                + "Whether the server accepted the create this charge paid for is a fact about the "
                + "server, and asserting it with a constant typed at the call site is that failure one "
                + "level down. Read it from the create's own RcsMlsControlResult.",
                args.matches("\\s*true\\s*,\\s*(true|false)\\s*"));
        assertTrue(
                "the acceptance argument must be derived from the create's own result — expected "
                + "an ok() read, got (" + squash(args) + ")", args.contains("ok()"));
    }

    /** The three reconciled neighbours ask the same predicate. Zero hits fail. */
    @Test
    public void everyEdgeInTheEventAsksTheSameRule() throws IOException {
        // Unsplit view: a charging method that moved is still one of the doors.
        final String src = SourceScan.transportUnsplitCode();
        // Name, and the declaration fragment that picks the real overload.
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

    /** The text between the parenthesis at {@code openParen} and its match; "" if unbalanced. */
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
