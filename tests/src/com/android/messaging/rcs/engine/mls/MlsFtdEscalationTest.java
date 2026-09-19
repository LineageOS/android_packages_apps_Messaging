/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MlsFtdEscalation}: the escalation rung is not derived from the resend allowance, so
 * raising one cannot silently move the threshold at which repeated failures become an era advance,
 * and each report class declares its counting rule and terminal action. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsFtdEscalationTest {

    /**
     * Asserted over the source rather than the value: the two are equal today and must be free to
     * differ, so what must hold is that the declaration does not read the other policy.
     */
    @Test
    public void theEscalationRungIsNotDerivedFromTheResendAllowance() throws IOException {
        // Code only, so neither a commented-out declaration nor the class's own javadoc counts.
        final String src = SourceScan.codeOnly(SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsFtdEscalation.java"));
        final Matcher m = Pattern.compile(
                "(?m)^\\s*public static final int ESCALATE_AT\\s*=\\s*([^;]+);").matcher(src);
        assertTrue("MlsFtdEscalation.ESCALATE_AT is gone — the escalation rung has moved somewhere "
                + "this guard cannot see, and the whole point is that the rung must be "
                + "somewhere a reader can find it with its own justification. Zero hits is not a "
                + "pass.", m.find());
        final String initialiser = m.group(1).trim();
        assertTrue("MlsFtdEscalation.ESCALATE_AT is initialised to '" + initialiser + "', which is "
                + "not a plain number. An escalation rung DERIVED from another policy's constant is "
                + "the defect exactly: raising that constant silently moves the threshold at which a "
                + "repeated failure becomes an era advance, and no log line or test would say so. "
                + "Give the rung its own value and its own argument.",
                initialiser.matches("\\d+"));

        final String rungWindow = SourceScan.bodyOf(src, "windowStart");
        assertTrue("MlsFtdEscalation.windowStart is gone", rungWindow.length() > 0);
        assertFalse("MlsFtdEscalation.windowStart reads MlsResendBudget. The WINDOW is half the "
                + "rule — '2 repeats in an hour' — so sharing it leaves half the coupling in place: "
                + "widening the storm window for storm reasons would also widen how far back a "
                + "repeat carries the rung towards an era advance.",
                rungWindow.contains("MlsResendBudget"));
    }

    /** The transport asks the rung for the escalation and the allowance for the resend. */
    @Test
    public void theEscalationDecisionAndTheResendAllowanceAreAskedOfDifferentClasses()
            throws IOException {
        final String src = SourceScan.transportUnsplitCode();

        final String escalating = SourceScan.bodyOf(src, "onPeerReportedFailure");
        assertTrue(
                "onPeerReportedFailure not found — it owns the reason-4 escalation rung and this "
                + "guard has stopped watching it", escalating.length() > 0);
        assertTrue("onPeerReportedFailure does not ask MlsFtdEscalation.escalates. The rung it "
                + "applies is then somebody else's number again.",
                escalating.contains("MlsFtdEscalation.escalates("));
        assertFalse("onPeerReportedFailure asks MlsResendBudget.spent for its ESCALATION decision. "
                + "That is the derivation this forbids, restated as a call: the threshold at "
                + "which a repeated failure becomes an era advance would move whenever the resend "
                + "allowance moves.", escalating.contains("MlsResendBudget.spent("));
        assertTrue("the reason-4 rung must count REPEAT resends — resendsToPeerSince counts every "
                + "row, which conflates a loop with N distinct messages each needing their first "
                + "resend (~2% permanent loss at burst rate)",
                escalating.contains("MlsResendLedger.repeatResendsToPeerSince("));
        assertTrue("the reason-4 rung must take its window from MlsFtdEscalation, not from the "
                + "resend allowance", escalating.contains("MlsFtdEscalation.windowStart("));
        assertTrue("onPeerReportedFailure no longer escalates at all — the terminal action "
                + "MlsFtdEscalation.ReportClass.DECRYPT_FAILURE declares is gone",
                escalating.contains("escalateForDivergedPeer("));

        final String stopping = SourceScan.bodyOf(src, "resendBudgetSpent");
        assertTrue(
                "resendBudgetSpent not found — it is the reason-less arm's cap and the other half "
                + "of this pair", stopping.length() > 0);
        assertTrue("resendBudgetSpent must count TOTAL resends",
                stopping.contains("MlsResendLedger.resendsToPeerSince("));
        assertTrue("resendBudgetSpent must ask the resend ALLOWANCE",
                stopping.contains("MlsResendBudget.spent("));
        assertFalse(
                "resendBudgetSpent escalates. Its whole documented difference from the reason-4 "
                + "path is that at the cap it STOPS: a report with no reason is not evidence of "
                + "anything in particular, and advancing an era on one would mutate group state on a "
                + "conversation that may be perfectly healthy.",
                stopping.contains("escalateForDivergedPeer(")
                        || stopping.contains("MlsFtdEscalation.escalates("));
    }

    @Test
    public void theTransportNoLongerDeclaresARungDerivedFromTheAllowance() throws IOException {
        // The transport plus the classes the split moved code into, so an absence check cannot pass
        // on a constant that only moved.
        final String src = SourceScan.transportAndMoved();
        assertTrue(
                        "ZERO HITS MUST FAIL: the transport's class-level declarations is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                SourceScan.declarations(src).size() > 50);
        final Matcher m = Pattern.compile(
                "(?m)^\\s*(?:private|public|protected)?\\s*static final int (\\w+)\\s*=\\s*"
                        + "MlsResendBudget\\.\\w+\\s*;").matcher(src);
        final boolean derived = m.find();
        assertFalse("MlsProviderTransport still declares a constant derived from MlsResendBudget"
                + (derived ? " (" + m.group(1) + ")" : "") + ". That is the finding "
                + "verbatim — an escalation rung expressed as a resend allowance.", derived);
    }

    @Test
    public void theRungFiresAtItsThresholdAndNotBefore() {
        assertFalse("a first failure report must not escalate — one FTD is plausibly a single lost "
                + "message", MlsFtdEscalation.escalates(1));
        assertFalse(MlsFtdEscalation.escalates(MlsFtdEscalation.ESCALATE_AT - 1));
        assertTrue(MlsFtdEscalation.escalates(MlsFtdEscalation.ESCALATE_AT));
        assertTrue("and stays escalating past it",
                MlsFtdEscalation.escalates(MlsFtdEscalation.ESCALATE_AT + 40));
    }

    @Test
    public void theRungIsPositiveSoTheLadderHasARungAtAll() {
        assertTrue("an ESCALATE_AT of " + MlsFtdEscalation.ESCALATE_AT + " escalates on the FIRST "
                + "report — every decrypt failure would become an era advance, which is the loop "
                + "the G2 budget exists to bound", MlsFtdEscalation.ESCALATE_AT >= 2);
    }

    @Test
    public void theRungWindowMovesForwardWithTime() {
        final long t0 = 1_800_000_000_000L;
        assertEquals(t0 - MlsFtdEscalation.WINDOW_MS, MlsFtdEscalation.windowStart(t0));
        assertTrue("a repeat at t0 must fall outside the window one window later",
                MlsFtdEscalation.windowStart(t0 + MlsFtdEscalation.WINDOW_MS) >= t0);
    }

    /** Every report class declares both halves, so a class added later cannot inherit an action. */
    @Test
    public void everyReportClassDeclaresItsCountingRuleAndItsTerminalAction() {
        final EnumSet<MlsFtdEscalation.Counting> countings =
                EnumSet.noneOf(MlsFtdEscalation.Counting.class);
        final EnumSet<MlsFtdEscalation.AtTheCap> actions =
                EnumSet.noneOf(MlsFtdEscalation.AtTheCap.class);
        for (final MlsFtdEscalation.ReportClass c : MlsFtdEscalation.ReportClass.values()) {
            assertTrue(c + " declares no counting rule", c.counting() != null);
            assertTrue(c + " declares no terminal action", c.atTheCap() != null);
            assertEquals(c + "'s mayEscalate() must agree with its declared terminal action",
                    c.atTheCap() == MlsFtdEscalation.AtTheCap.ESCALATE_TO_A_GROUP_REPAIR,
                    c.mayEscalate());
            countings.add(c.counting());
            actions.add(c.atTheCap());
        }
        assertEquals("both counting rules must be in use — one rule for both classes means the "
                + "distinction between a loop and N distinct messages has quietly gone",
                MlsFtdEscalation.Counting.values().length, countings.size());
        assertEquals("both terminal actions must be in use — the whole finding of event E4 is that "
                + "one allowance drove OPPOSITE terminal actions with nothing naming the split",
                MlsFtdEscalation.AtTheCap.values().length, actions.size());
    }

    /** The two classes are judged differently in both dimensions. */
    @Test
    public void aReasonlessReportStopsWhereADecryptFailureEscalates() {
        final MlsFtdEscalation.ReportClass four = MlsFtdEscalation.ReportClass.DECRYPT_FAILURE;
        final MlsFtdEscalation.ReportClass none = MlsFtdEscalation.ReportClass.NO_REASON_GIVEN;
        assertNotEquals("the two classes must not share a counting rule", four.counting(),
                none.counting());
        assertNotEquals("the two classes must not share a terminal action", four.atTheCap(),
                none.atTheCap());
        assertEquals(MlsFtdEscalation.Counting.REPEAT_RESENDS, four.counting());
        assertEquals(MlsFtdEscalation.AtTheCap.ESCALATE_TO_A_GROUP_REPAIR, four.atTheCap());
        assertEquals(MlsFtdEscalation.Counting.TOTAL_RESENDS, none.counting());
        assertEquals(
                "a report with no reason tells us nothing about the cause, so repeated ones are "
                + "not evidence of anything in particular and stopping is the action that cannot be "
                + "wrong", MlsFtdEscalation.AtTheCap.STOP, none.atTheCap());
        assertFalse("a reason-less report must never reach the rung",
                none.mayEscalate());
    }


    /**
     * The RCC.16 §10.3 chain cap, the escalation rung and their order: the group is repaired long
     * before the spec says to stop reporting.
     */
    @Test
    public void theChainCapIsTheSpecsFiveAndIsNotTheEscalationRung() {
        assertEquals(5, MlsFtdEscalation.MAX_FTD_ATTEMPTS);
        assertNotEquals("the §10.3 chain cap and the escalation rung are different bounds over the "
                + "same event; equal values would be the tie this class exists to have broken",
                MlsFtdEscalation.MAX_FTD_ATTEMPTS, MlsFtdEscalation.ESCALATE_AT);
        assertTrue("we must repair the GROUP before the spec tells us to stop reporting at all",
                MlsFtdEscalation.ESCALATE_AT < MlsFtdEscalation.MAX_FTD_ATTEMPTS);
    }

    /**
     * The chain is exhausted past the cap, not at it. The cap is an argument because
     * {@code debug.rcs.mls_ftd_max_attempts} raises it.
     */
    @Test
    public void theChainIsExhaustedPastTheCapNotAtIt() {
        final int cap = MlsFtdEscalation.MAX_FTD_ATTEMPTS;
        assertFalse(MlsFtdEscalation.chainExhausted(cap - 1, cap));
        assertFalse(MlsFtdEscalation.chainExhausted(cap, cap));
        assertTrue(MlsFtdEscalation.chainExhausted(cap + 1, cap));
        // A raised cap really raises it.
        assertFalse(MlsFtdEscalation.chainExhausted(cap + 1, 13));
        assertTrue(MlsFtdEscalation.chainExhausted(14, 13));
    }

}
