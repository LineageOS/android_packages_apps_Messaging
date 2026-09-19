/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MlsFetchBudget}: the rules that ration server look-ups. The invariant is asserted
 * directly: a drive spends at most {@link MlsFetchBudget#RECOVERY_LOOKS} look-ups whatever its
 * passes return. See docs/mls/budgets.md.
 */
public class MlsFetchBudgetTest {

    @Test public void aDriveSpendsAtMostItsBudgetHoweverManyPassesRun() {
        // A drive whose passes never converge: the look-ups permitted equal the budget, not the
        // iteration cap, and the two differ.
        int spent = 0;
        for (int pass = 0; pass < MlsDriveLoop.DEFAULT_MAX_ITERATIONS * 10; pass++) {
            if (MlsFetchBudget.mayLook(spent, MlsFetchBudget.RECOVERY_LOOKS)
                    == MlsFetchBudget.Verdict.SPEND) {
                spent++;
            }
        }
        assertEquals(MlsFetchBudget.RECOVERY_LOOKS, spent);
        assertTrue("a hundred passes must not buy a hundred look-ups",
                spent < MlsDriveLoop.DEFAULT_MAX_ITERATIONS);
    }

    @Test public void theBudgetIsNotTheIterationCap() {
        // They bound different resources, so raising one must not raise the other.
        assertNotEquals(MlsFetchBudget.RECOVERY_LOOKS, MlsDriveLoop.DEFAULT_MAX_ITERATIONS);
        assertTrue("the loop must not be able to reach the tenth fetch",
                MlsFetchBudget.RECOVERY_LOOKS < MlsDriveLoop.DEFAULT_MAX_ITERATIONS);
    }

    @Test public void theAllowanceIsSpentExactlyOnceEach() {
        assertEquals(MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(0, 2));
        assertEquals(MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(1, 2));
        assertEquals(MlsFetchBudget.Verdict.DENIED_BUDGET, MlsFetchBudget.mayLook(2, 2));
        assertEquals(MlsFetchBudget.Verdict.DENIED_BUDGET, MlsFetchBudget.mayLook(3, 2));
    }

    @Test public void aNonsenseBudgetStillBuysOneLook() {
        // A budget of zero would fail a drive that was never allowed to ask anything.
        for (final int budget : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertEquals("budget " + budget,
                    MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(0, budget));
            assertEquals("budget " + budget,
                    MlsFetchBudget.Verdict.DENIED_BUDGET, MlsFetchBudget.mayLook(1, budget));
        }
    }

    @Test public void aNegativeSpendCountReadsAsNone() {
        // Total: nonsense input gets the conservative answer rather than an exception on a recovery
        // path.
        assertEquals(MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(-5, 2));
    }

    @Test public void theRetryAfterAThrottleWaitsLongerThanTheMeasuredCooldown() {
        // The retry ladder's first rungs are 30 seconds apart and a GetMlsGroupInfo throttle takes
        // about 200 seconds to clear, so the default schedule would spend attempts inside the
        // window it is waiting out. The chosen attempt clears the window.
        final int attempt = MlsFetchBudget.firstAttemptClearingTheCooldown();
        assertTrue("attempt " + attempt + " must be a real attempt", attempt >= 1);
        assertTrue("attempt " + attempt + " delay="
                        + MlsRetryPolicy.retryDelayMs(attempt) + "ms must clear "
                        + MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS + "ms",
                MlsRetryPolicy.retryDelayMs(attempt)
                        > MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
    }

    @Test public void itIsTheFirstSuchAttemptAndNotMerelyALargeOne() {
        // Waiting longer than necessary holds a conversation silent past recovery, so every earlier
        // rung is too short.
        final int attempt = MlsFetchBudget.firstAttemptClearingTheCooldown();
        for (int a = 1; a < attempt; a++) {
            assertTrue("attempt " + a + " should have been inside the window",
                    MlsRetryPolicy.retryDelayMs(a)
                            <= MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
        }
    }

    @Test public void theChosenAttemptIsNotAlreadyExhausted() {
        // Skipping rungs does not skip the whole allowance: the attempt number is also the
        // outermost give-up counter.
        assertTrue(MlsFetchBudget.firstAttemptClearingTheCooldown()
                < MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM);
    }

    @Test public void theCooldownIsTheOneMeasuredValue() {
        // One observed value, not a published limit; pinned so a change to it is deliberate.
        assertEquals(200_000L, MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
    }

    @Test public void aDenialSaysTheQuotaIsOursAndNotTheServers() {
        // The log says the quota is ours, not the server's.
        final String s = MlsFetchBudget.describeDenial(2, 2, "reconcile:g:abc");
        assertTrue(s, s.contains("OUR bound, not the server's"));
        assertTrue(s, s.contains("reconcile:g:abc"));
        assertTrue(s, s.contains("The work is still owed"));
    }

    @Test public void anUnreadableLookIsClassifiedAsSelfInflictedWithItsCooldown() {
        final String after = MlsFetchBudget.describeUnreadableLook(3, "reconcile:g:abc");
        assertTrue(after, after.contains("RESOURCE_EXHAUSTED"));
        assertTrue(after, after.contains("throttle WE CAUSED"));
        assertTrue("the cooldown belongs next to the diagnosis", after.contains("200s"));
        assertTrue(after, after.contains("3 look(s)"));
        // "AT LEAST one GetMlsGroupInfo": a pass may escalate to an anchor check or a rebuild's
        // state pack, so this layer knows the number of looks and only a lower bound on fetches.
        assertTrue(after, after.contains("AT LEAST one GetMlsGroupInfo"));
    }

    @Test public void theSpendLineNeverClaimsAnExactFetchCount() {
        final String s =
                MlsFetchBudget.describeSpend(2, MlsFetchBudget.RECOVERY_LOOKS, "reconcile");
        assertTrue(s, s.contains("2 of " + MlsFetchBudget.RECOVERY_LOOKS + " permitted look(s)"));
        assertTrue(s, s.contains("AT LEAST one GetMlsGroupInfo"));
    }

    @Test public void aFirstLookDoesNotClaimInnocence() {
        // "This operation has spent none of its own look-ups" bounds where a throttle came from
        // without ruling one out, since whatever ran just before spends look-ups on the same
        // conversation.
        final String first = MlsFetchBudget.describeUnreadableLook(0, "self-heal g:abc");
        assertTrue(first, first.contains("none of its own"));
        assertTrue("it must still name the throttle as a possibility",
                first.contains("RESOURCE_EXHAUSTED"));
        assertTrue(first, first.contains("provoked by whatever ran before this"));
        assertEquals("a negative count is the same case as zero",
                first, MlsFetchBudget.describeUnreadableLook(-2, "self-heal g:abc"));
    }
}
