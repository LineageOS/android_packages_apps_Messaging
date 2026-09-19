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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every way a rebuild can fail to repair is a DIFFERENT answer, and exactly the deliberate stops
 * reach a person.
 *
 * <p>The properties are asserted over the whole enum rather than constant by constant, so a value
 * added later with its flags out of step fails here instead of becoming a stop nobody is told about.
 * That is {@code MlsReestablishPolicy.Verdict}'s discipline, and it is the one that caught the
 * uncharged rebuild door.
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

    /** A success is never something to interrupt a person about. */
    @Test
    public void successNeverNeedsAPerson() {
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            if (o.repaired()) {
                assertFalse(o + " both repaired the conversation and asked for a person",
                        o.needsAPerson());
            }
        }
    }

    /**
     * THE WHOLE POINT. A DELIBERATE STOP must reach a person; a transient or unactionable
     * failure must not.
     */
    @Test
    public void onlyTheDeliberateStopsReachAPerson() {
        assertTrue("a guard refusal is G4's 'STOP and surface it', and the surfacing is this",
                MlsRebuildOutcome.REFUSED_BY_GUARD.needsAPerson());
        assertTrue("the rate bound outlasts any drive and Try again clears it outright",
                MlsRebuildOutcome.RATE_LIMITED.needsAPerson());

        assertFalse("the 60-second episode guard clears itself; alerting on it trains a person to "
                        + "dismiss the alert that matters",
                MlsRebuildOutcome.SUPPRESSED_AS_ONE_EPISODE.needsAPerson());
        assertFalse("no budget a person can reset is what stopped us, so Try again would do nothing",
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
     * A refusal by OUR OWN ledger must not wear a peer-protecting guard's value.
     *
     * <p>This is the regression that shipped. {@code rebuildConversation}'s opening look decides
     * whether the rebuild is charged to the era budget, and a ledger refusal there returned
     * {@link MlsRebuildOutcome#REFUSED_BY_GUARD}, so {@link MlsRebuildOutcome#line()} asserted that
     * the joining allowlist, the era budget, the peer-health streak or the kill switch had refused
     * when none of them had run — and {@code needsAPerson()} then raised the stalled-conversation
     * notification carrying that text, offering a Try again that resets six bounds, none of which
     * is the fetch ledger.
     *
     * <p>Asserted on the VALUE and on what the value SAYS, because the log line at the call site was
     * already correct ("Deferred, not abandoned") and it was the value that was wrong. A test that
     * only read the log would have passed throughout.
     */
    @Test
    public void aRefusalByOurOwnLedgerIsNotAGuardRefusal() {
        final MlsRebuildOutcome deferred = MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER;
        assertNotEquals("the fetch ledger is OURS; REFUSED_BY_GUARD means a peer-protecting guard "
                + "said no, and its line() names four of them. Sharing the value makes the alert "
                + "assert something nothing measured, one layer up.",
                MlsRebuildOutcome.REFUSED_BY_GUARD, deferred);
        assertNotEquals("and not COULD_NOT_ATTEMPT: that line enumerates four causes and this is a "
                + "fifth. Folding it in re-creates the disjunction-nobody-measured that this enum "
                + "replaced.",
                MlsRebuildOutcome.COULD_NOT_ATTEMPT, deferred);
        assertFalse("nothing was attempted", deferred.repaired());
        assertTrue("its line must say the rebuild was NOT started",
                deferred.line().contains("NOT started"));
        assertTrue("and must say explicitly that no guard refused it, because that is the claim the "
                + "value it replaced was making",
                deferred.line().contains("No peer-protecting guard refused this"));
        assertFalse("REFUSED_BY_GUARD's line must NOT be reachable for a ledger refusal",
                deferred.line().equals(MlsRebuildOutcome.REFUSED_BY_GUARD.line()));
    }

    /**
     * A line must SAY something. The string it replaces — "the rebuild did not converge or was
     * rate-limited" — named two possibilities and distinguished neither, which is the house rule
     * this was filed under.
     */
    @Test
    public void everyOutcomeHasItsOwnLine() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            final String line = o.line();
            assertTrue(o + " has no line", line != null && line.length() > 20);
            assertTrue("two outcomes share a line: " + line, seen.add(line));
        }
    }

    /** Nothing here is allowed to be a shrug: every constant answers both questions. */
    @Test
    public void thereIsNoUnknownOutcome() {
        for (final MlsRebuildOutcome o : MlsRebuildOutcome.values()) {
            // Both accessors are total; this is a compile-and-run assertion that no constant
            // throws or returns something the switch in line() forgot.
            o.repaired();
            o.needsAPerson();
            assertFalse(o.line().isEmpty());
        }
        assertEquals("MlsRebuildOutcome has gained or lost a constant — check its flags against that "
                + "class's javadoc before updating this number. The TYPE is named so the failure "
                + "attributes itself: a bare count says something moved, not what.",
                10, MlsRebuildOutcome.values().length);
    }

    /**
     * A rebuild refused because the server WILL NOT APPLY its re-create is its own answer,
     * and it is deliberately NOT the neighbouring fork value.
     *
     * <p>The two describe opposite outcomes of the same arm. {@code WOULD_FORK_AT_ERA_INITIAL} is a
     * build that HAPPENS and lands beside the server's group, permanently. This one is a build the
     * server does not take at all, at any era — nothing to adopt, and both local halves would simply
     * be gone. A reader deciding whether local state still exists needs them apart, and a reader
     * deciding whether to change the ERA needs to be told the era was never the problem.
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
        assertTrue("its line must say the rebuild was NOT started", no.line().contains("NOT started"));
        assertTrue("and must say nothing was destroyed AND nothing was charged — the fork value "
                + "only promises the first, and the era-budget charge is the cost "
                + "measured being spent per ATTEMPT",
                no.line().contains("nothing was destroyed and nothing was charged"));
        assertTrue("and must name the exit, or the refusal is just a quieter dead end",
                no.line().contains("re-admitting us"));
    }

    /**
     * A rebuild refused because it WOULD HAVE FORKED is its own answer.
     *
     * <p>Every other value here is wrong about something a reader acts on. {@code COULD_NOT_ATTEMPT}
     * enumerates four causes and this is a fifth; {@code REFUSED_BY_GUARD} asserts a peer-protecting
     * guard ran and none did; {@code NOT_CONVERGED} says the rebuild RAN, and the whole point is
     * that it did not — nothing was dropped and nothing was charged, which is precisely what a
     * reader deciding whether local state still exists needs to know.
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
     * A rebuild we could not VERIFY must not reach a person, and must not read as one that failed.
     *
     * <p>Both halves matter and they fail differently. {@code needsAPerson()} true would put a stall
     * alert on a conversation that may be perfectly repaired, offering a Try again that cannot
     * shorten our own window. {@code repaired()} true would claim a repair on an unasked question,
     * which is the failure this whole method exists to avoid.
     */
    @Test
    public void anUnverifiedRebuildIsNeitherAFailureNorAPersonsProblem() {
        assertFalse("RAN_BUT_UNVERIFIED must not raise the stalled-conversation notification — the "
                + "exit is our own fetch-ledger window passing, which no Try again shortens",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.needsAPerson());
        assertFalse("RAN_BUT_UNVERIFIED must not claim a repair it did not verify",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.repaired());
        assertNotEquals("it must stay distinct from NOT_CONVERGED, which means the rebuild ran and "
                + "was measured not to have worked. Collapsing them puts a stall alert on a "
                + "conversation nobody measured.",
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
