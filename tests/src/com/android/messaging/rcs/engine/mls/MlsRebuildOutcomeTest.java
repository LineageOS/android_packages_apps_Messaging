/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every way a rebuild can fail to repair is a different {@link MlsRebuildOutcome}, and exactly the
 * deliberate stops reach a person. Asserted over the whole enum, so a new value with its flags out
 * of step fails here.
 */
public final class MlsRebuildOutcomeTest {

    @Test
    public void exactlyOneOutcomeIsSuccess() {
        int repaired = 0;
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            if (o.repaired()) repaired++;
        }
        assertEquals("more than one outcome claims the conversation is repaired", 1, repaired);
        assertTrue(MlsRebuildOutcome.REPAIRED.repaired());
    }

    /** A success never interrupts a person. */
    @Test
    public void successNeverNeedsAPerson() {
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            if (o.repaired()) {
                assertFalse(o + " both repaired the conversation and asked for a person",
                        o.needsAPerson());
            }
        }
    }

    /** A deliberate stop reaches a person; a transient or unactionable failure does not. */
    @Test
    public void onlyTheDeliberateStopsReachAPerson() {
        assertTrue("a guard refusal is G4's 'STOP and surface it', and the surfacing is this",
                MlsRebuildOutcome.REFUSED_BY_GUARD.needsAPerson());
        assertTrue("the rate bound outlasts any drive and Try again clears it outright",
                MlsRebuildOutcome.RATE_LIMITED.needsAPerson());

        assertFalse("the 60-second episode guard clears itself; alerting on it trains a person to "
                        + "dismiss the alert that matters",
                MlsRebuildOutcome.SUPPRESSED_AS_ONE_EPISODE.needsAPerson());
        assertFalse(
                "no budget a person can reset is what stopped us, so Try again would do nothing",
                MlsRebuildOutcome.COULD_NOT_ATTEMPT.needsAPerson());
        assertFalse("the rebuild RAN; escalating on the first non-convergence pre-empts the "
                        + "self-heal ladder, whose exhausted arm already raises the alert",
                MlsRebuildOutcome.NOT_CONVERGED.needsAPerson());
        assertFalse("our own fetch ledger is not a bound Try again can reach — it resets six "
                        + "bounds and the ledger is not one of them, so surfacing this hands a "
                        + "person a button that cannot change what stopped us",
                MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER.needsAPerson());
    }

    /**
     * A refusal by our own fetch ledger does not wear a peer-protecting guard's value: its line
     * would blame guards that never ran, and the stall notification would offer a Try again that
     * does not reset the ledger. Asserted on the value and its text, not on the call site's log.
     */
    @Test
    public void aRefusalByOurOwnLedgerIsNotAGuardRefusal() {
        final MlsRebuildOutcome deferred = MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER;
        assertNotEquals("the fetch ledger is OURS; REFUSED_BY_GUARD means a peer-protecting guard "
                + "said no, and its line() names four of them. Sharing the value makes the alert "
                + "assert something nothing verified, one layer up.",
                MlsRebuildOutcome.REFUSED_BY_GUARD, deferred);
        assertNotEquals("and not COULD_NOT_ATTEMPT: that line enumerates four causes and this is a "
                + "fifth. Folding it in re-creates the unverifiable disjunction that this enum "
                + "exists to avoid.",
                MlsRebuildOutcome.COULD_NOT_ATTEMPT, deferred);
        assertFalse("nothing was attempted", deferred.repaired());
        assertTrue("its line must say the rebuild was NOT started",
                deferred.line().contains("NOT started"));
        assertTrue(
                "and must say explicitly that no guard refused it, because that is the claim the "
                + "value it replaced was making",
                deferred.line().contains("No peer-protecting guard refused this"));
        assertFalse("REFUSED_BY_GUARD's line must NOT be reachable for a ledger refusal",
                deferred.line().equals(MlsRebuildOutcome.REFUSED_BY_GUARD.line()));
    }

    /** Each line distinguishes its cause; no line names two possibilities at once. */
    @Test
    public void everyOutcomeHasItsOwnLine() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            final String line = o.line();
            assertTrue(o + " has no line", line != null && line.length() > 20);
            assertTrue("two outcomes share a line: " + line, seen.add(line));
        }
    }

    /** Every constant answers both questions. */
    @Test
    public void thereIsNoUnknownOutcome() {
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            // Both accessors are total: no constant throws or falls through the switch in line().
            o.repaired();
            o.needsAPerson();
            assertFalse(o.line().isEmpty());
        }
        assertEquals(
                "MlsRebuildOutcome has gained or lost a constant — check its flags against that "
                + "class's javadoc before updating this number. The TYPE is named so the failure "
                + "attributes itself: a bare count says something moved, not what.",
                10, MlsRebuildOutcome.values().length);
    }

    /**
     * A re-create the server will not apply is distinct from the fork value: a fork is a build that
     * happens beside the server's group, this is a build the server takes at no era. Readers
     * deciding whether local state exists, or whether to change the era, need them apart.
     */
    @Test
    public void aReCreateTheServerWillNotApplyIsItsOwnAnswer() {
        final MlsRebuildOutcome no = MlsRebuildOutcome.RECREATE_WOULD_NOT_TAKE;
        assertFalse("nothing was attempted, so nothing was repaired", no.repaired());
        assertFalse("Try again resets six bounds and none of them is 'the server already holds "
                + "this contextId' — the exits are another member re-admitting us, which the "
                + "refusal asks for, or an operator lever", no.needsAPerson());
        assertNotEquals("NOT_CONVERGED means the rebuild RAN, after dropping both halves and "
                + "charging the era budget. That is precisely what this value exists to stop "
                + "happening, so sharing it would erase the change",
                MlsRebuildOutcome.NOT_CONVERGED, no);
        assertNotEquals("no peer-protecting guard ran",
                MlsRebuildOutcome.REFUSED_BY_GUARD, no);
        assertNotEquals("a fork is ADOPTED and permanent; this create is not applied at all",
                MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL, no);
        assertTrue("its line must say the rebuild was NOT started",
                no.line().contains("NOT started"));
        assertTrue("and must say nothing was destroyed AND nothing was charged — the fork value "
                + "only promises the first, and the era-budget charge is the cost "
                + "spent per ATTEMPT",
                no.line().contains("nothing was destroyed and nothing was charged"));
        assertTrue("and must name the exit, or the refusal is just a quieter dead end",
                no.line().contains("re-admitting us"));
    }

    /**
     * A rebuild refused because it would have forked is its own answer: nothing was dropped or
     * charged, which no neighbouring value says.
     */
    @Test
    public void aRefusedForkIsNeitherAnAttemptNorAGuardRefusal() {
        final MlsRebuildOutcome fork = MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL;
        assertFalse("nothing was attempted, so nothing was repaired", fork.repaired());
        assertFalse("Try again resets six bounds and none of them is 'the server pack could not be "
                + "fetched', so surfacing this hands a person a button that changes nothing",
                fork.needsAPerson());
        assertNotEquals("no peer-protecting guard ran — that substitution is the regression "
                + "DEFERRED_BY_OUR_OWN_LEDGER was split out to undo",
                MlsRebuildOutcome.REFUSED_BY_GUARD, fork);
        assertNotEquals("COULD_NOT_ATTEMPT's line enumerates four causes and this is a fifth",
                MlsRebuildOutcome.COULD_NOT_ATTEMPT, fork);
        assertNotEquals("NOT_CONVERGED means the rebuild RAN; this one did not start",
                MlsRebuildOutcome.NOT_CONVERGED, fork);
        assertTrue("its line must say the rebuild was NOT started",
                fork.line().contains("NOT started"));
        assertTrue("and must say nothing was destroyed, because that is the behaviour change",
                fork.line().contains("nothing was destroyed"));
    }

    /**
     * A rebuild we could not verify neither reaches a person (it may be repaired) nor claims a
     * repair on a question that was never asked.
     */
    @Test
    public void anUnverifiedRebuildIsNeitherAFailureNorAPersonsProblem() {
        assertFalse("RAN_BUT_UNVERIFIED must not raise the stalled-conversation notification — the "
                + "exit is our own fetch-ledger window passing, which no Try again shortens",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.needsAPerson());
        assertFalse("RAN_BUT_UNVERIFIED must not claim a repair it did not verify",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.repaired());
        assertNotEquals("it must stay distinct from NOT_CONVERGED, which means the rebuild ran and "
                + "was verified not to have worked. Collapsing them puts a stall alert on a "
                + "conversation nobody verified.",
                MlsRebuildOutcome.NOT_CONVERGED, MlsRebuildOutcome.RAN_BUT_UNVERIFIED);
        assertNotEquals("and distinct from COULD_NOT_ATTEMPT, whose javadoc says we never got as "
                + "far as trying — here we got all the way and only the confirmation is missing, "
                + "which is what a reader deciding whether local state exists needs to know",
                MlsRebuildOutcome.COULD_NOT_ATTEMPT, MlsRebuildOutcome.RAN_BUT_UNVERIFIED);
        assertTrue("its line must say the rebuild RAN",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.line().contains("RAN"));
        assertTrue("and must say it is not evidence of failure",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.line().contains("NOT evidence"));
    }
}
