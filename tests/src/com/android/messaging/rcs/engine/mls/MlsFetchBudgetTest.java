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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A look-up is the scarce resource, and these are the rules that ration it.
 *
 * <p>The invariant is asserted DIRECTLY — "a drive may spend at most N server look-ups, whatever its
 * passes return" — rather than through a worked example, because the worked example is unreproducible
 * on demand: it needs a conversation stuck BEHIND, ten passes that cannot converge, and a server
 * willing to answer {@code RESOURCE_EXHAUSTED}. That combination was observed once, on deviceA on
 * 2026-09-08, and cannot be asked for again.
 */
public class MlsFetchBudgetTest {

    @Test public void aDriveSpendsAtMostItsBudgetHoweverManyPassesRun() {
        // THE INVARIANT. Simulate a drive whose passes never converge and count the look-ups it is
        // permitted — the number must be the budget, not the iteration cap, and the two must not be
        // the same number.
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
        // They bound different resources. Tying them together is how raising one silently raises the
        // other, which is the non-fix this rules out by name.
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
        // Same trap MlsDriveLoop clamps for: a budget of zero would fail a drive that was never
        // allowed to ask anything — a self-inflicted outage rather than a tight bound.
        for (final int budget : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertEquals("budget " + budget,
                    MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(0, budget));
            assertEquals("budget " + budget,
                    MlsFetchBudget.Verdict.DENIED_BUDGET, MlsFetchBudget.mayLook(1, budget));
        }
    }

    @Test public void aNegativeSpendCountReadsAsNone() {
        // Total, like every other decision in this module: a caller that hands us nonsense gets the
        // conservative answer rather than an exception on a recovery path.
        assertEquals(MlsFetchBudget.Verdict.SPEND, MlsFetchBudget.mayLook(-5, 2));
    }

    // -- the throttle arm --------------------------------------------------------------------------

    @Test public void theRetryAfterAThrottleWaitsLongerThanTheMeasuredCooldown() {
        // THE POINT OF ITEM 4. The retry ladder's first three rungs are 30 seconds apart and the one
        // measured GetMlsGroupInfo throttle took 200 seconds to clear — so the default schedule
        // spends three attempts INSIDE the window it is waiting out, each of them another look-up
        // feeding the throttle. The chosen attempt must clear the window.
        final int attempt = MlsFetchBudget.firstAttemptClearingTheCooldown();
        assertTrue("attempt " + attempt + " must be a real attempt", attempt >= 1);
        assertTrue("attempt " + attempt + " delay="
                        + MlsRetryPolicy.retryDelayMs(attempt) + "ms must clear "
                        + MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS + "ms",
                MlsRetryPolicy.retryDelayMs(attempt)
                        > MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
    }

    @Test public void itIsTheFirstSuchAttemptAndNotMerelyALargeOne() {
        // Waiting longer than necessary is its own failure — a conversation held silent past the
        // point where it could have recovered. So every earlier rung must genuinely be too short.
        final int attempt = MlsFetchBudget.firstAttemptClearingTheCooldown();
        for (int a = 1; a < attempt; a++) {
            assertTrue("attempt " + a + " should have been inside the window",
                    MlsRetryPolicy.retryDelayMs(a)
                            <= MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
        }
    }

    @Test public void theChosenAttemptIsNotAlreadyExhausted() {
        // Skipping rungs must not skip the conversation's whole allowance: the attempt number is
        // also the outermost give-up counter.
        assertTrue(MlsFetchBudget.firstAttemptClearingTheCooldown()
                < MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM);
    }

    @Test public void theCooldownIsTheOneMeasuredValue() {
        // 200 seconds, from deviceA on 2026-09-08 — one datum, not a published limit. Pinned so a
        // later "round it to five minutes" is a deliberate change rather than a drift.
        assertEquals(200_000L, MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS);
    }

    // -- what the log says, which is where the next person meets this ------------------------------

    @Test public void aDenialSaysTheQuotaIsOursAndNotTheServers() {
        // The available wrong conclusion is "Tachyon rate-limits our recovery". It does not; we do.
        // This project has a standing rule against concluding a server-side wall, and this is
        // the worked example of it paying off — so the log has to carry the correction.
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
        // "AT LEAST one GetMlsGroupInfo", never an exact fetch count: a pass opens with the health
        // probe and may then escalate to an anchor check or a rebuild's state pack, so this layer
        // knows the number of LOOKS and only a lower bound on the number of fetches. Claiming the
        // exact figure would be a log line asserting something it cannot know, which costs as much
        // as one that hides what it does.
        assertTrue(after, after.contains("AT LEAST one GetMlsGroupInfo"));
    }

    @Test public void theSpendLineNeverClaimsAnExactFetchCount() {
        final String s = MlsFetchBudget.describeSpend(2, MlsFetchBudget.RECOVERY_LOOKS, "reconcile");
        assertTrue(s, s.contains("2 of " + MlsFetchBudget.RECOVERY_LOOKS + " permitted look(s)"));
        assertTrue(s, s.contains("AT LEAST one GetMlsGroupInfo"));
    }

    @Test public void aFirstLookDoesNotClaimInnocence() {
        // Precision about a negative: "this operation has spent none of its own look-ups" bounds
        // where a throttle could have come from. It does not rule one out — whatever ran immediately
        // before spends look-ups on the same conversation — and saying otherwise would send the next
        // reader away from the cause.
        final String first = MlsFetchBudget.describeUnreadableLook(0, "self-heal g:abc");
        assertTrue(first, first.contains("none of its own"));
        assertTrue("it must still name the throttle as a possibility",
                first.contains("RESOURCE_EXHAUSTED"));
        assertTrue(first, first.contains("provoked by whatever ran before this"));
        assertEquals("a negative count is the same case as zero",
                first, MlsFetchBudget.describeUnreadableLook(-2, "self-heal g:abc"));
    }
}
