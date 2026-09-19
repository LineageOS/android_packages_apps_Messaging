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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The recovery ladder as a SEQUENCE, not as individual legal pairs (rework 8.5's testable half).
 *
 * <p>Every other health test here asserts that one transition is in the §5.3 table. That is not the
 * property that matters at runtime: the table has 127 legal pairs, and a ladder can be built entirely
 * out of legal pairs and still not go anywhere — which is exactly what we had. Four edges wired this
 * session (§8.1's two in-flight rungs, §8.2's HealedAfterRemoteCommit, §8.4b's
 * EndMlsAppliedByRemoteClient) were all legal in the table for months with no caller, so "the pair is
 * legal" was true the whole time the recovery was broken.
 *
 * <p>These walk the paths the code actually takes, end to end, and assert the EDGE NAMES along the
 * way. A future change that reroutes a rung through a different edge fails here even when every
 * individual step stays legal.
 */
public class MlsRecoveryLadderTest {

    /** Walk a sequence of target states, returning the edge name taken at each step. */
    private static List<String> walk(final int from, final int... targets) {
        MlsConversationRecord rec = MlsConversationRecord.builder()
                .healthStatus(from)
                .build();
        final List<String> edges = new ArrayList<>();
        for (final int to : targets) {
            final MlsHealthMachine.Applied a =
                    MlsHealthMachine.transition(rec, to, rec.moment, /*allowIllegal=*/ false);
            assertEquals("step to " + MlsHealthStates.name(to) + " from "
                            + MlsHealthStates.name(rec.healthStatus) + " must be LEGAL",
                    MlsHealthMachine.Outcome.LEGAL, a.decision.outcome);
            assertNotNull(a.decision.edge);
            edges.add(a.decision.edge.wireName);
            rec = a.record;
        }
        return edges;
    }

    @Test public void theEpochAdvanceLadderWalksEndToEnd() {
        // What selfHeal does when the replay catches us up: request, start, succeed.
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement",
                        "EpochAdvancementSuccessful"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.HEALTHY));
    }

    @Test public void theEscalationToEraWalksEndToEnd() {
        // What selfHeal does when the replay CANNOT bridge the gap: the epoch attempt escalates to
        // an era advance rather than reporting a heal it did not achieve (rework 8.3).
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement",
                        "StartedEraAdvancement", "EraAdvancementSuccessful"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.ONGOINGERAADVANCEMENT,
                        MlsHealthStates.HEALTHY));
    }

    @Test public void aPeersCommitUnwedgesUsFromEveryInFlightRung() {
        // §5.11 / rework 8.2 — the edge that needs NO local action. It has to work from every rung a
        // heal can be sitting on when the peer's commit lands, because which rung we are on when
        // that happens is not ours to choose.
        for (final int stuckAt : new int[] {
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                MlsHealthStates.ONGOINGERAADVANCEMENT,
                MlsHealthStates.ERAADVANCEMENTREQUESTED,
        }) {
            final MlsConversationRecord rec = MlsConversationRecord.builder()
                    .healthStatus(stuckAt).build();
            final MlsHealthMachine.Applied a = MlsHealthMachine.transition(
                    rec, MlsHealthStates.HEALTHY, rec.moment, /*allowIllegal=*/ false);
            assertEquals("an inbound decrypt must recover from "
                            + MlsHealthStates.name(stuckAt),
                    MlsHealthMachine.Outcome.LEGAL, a.decision.outcome);
            assertEquals(MlsHealthStates.HEALTHY, a.record.healthStatus);
        }
    }

    @Test public void aRemoteDowngradeIsReachableFromAHealInFlight() {
        // rework 8.4b: finding end_mls on the server's GroupInfo mid-heal must record DoneEndMls,
        // not return a bare number. The heal is by definition in flight when that is discovered.
        assertEquals(Arrays.asList("RequestedSelfHeal", "EndMlsAppliedByRemoteClient"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.DONEENDMLS));
    }

    @Test public void theBudgetExhaustionEscalatesToEraNeverToSelfHealFailed() {
        // §9.3e, invariants 87/98. Both exhaustion arms produce EraAdvancementRequested. Routing
        // exhaustion to SelfHealFailed instead is the mistake that ends recovery permanently, and
        // it is easy to reach for because the name reads like what happened.
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .healthStatus(MlsHealthStates.EPOCHADVANCEMENTREQUESTED).build();
        final MlsHealthMachine.Applied a = MlsHealthMachine.transition(
                rec, MlsHealthStates.ERAADVANCEMENTREQUESTED, rec.moment, /*allowIllegal=*/ false);
        assertEquals(MlsHealthMachine.Outcome.LEGAL, a.decision.outcome);
        assertEquals(MlsHealthStates.ERAADVANCEMENTREQUESTED, a.record.healthStatus);
    }

    @Test public void theBudgetIsARateNotAOneShotAllowance() {
        // The budget bounds self-heals to `limit` attempts per `windowMs`. The COUNT is the
        // exhaustion arm; the WINDOW rolls (it used to exhaust, permanently).
        final MlsConversationRecord.SelfHealBudget fresh =
                MlsConversationRecord.SelfHealBudget.EMPTY;
        assertTrue("a fresh budget is not exhausted", !fresh.exhausted(3, 1000L, 60_000L));

        MlsConversationRecord.SelfHealBudget b = fresh;
        for (int i = 0; i < 3; i++) b = b.counted(1000L);
        assertTrue("three attempts against a limit of three is exhausted",
                b.exhausted(3, 1000L, 60_000L));

        // ...and once the WINDOW has passed it is spent no longer, however high the count went.
        // This is the assertion that used to say the opposite, and the opposite was a deadlock:
        // the only other reset is forward progress, and forward progress needs the heal the budget
        // was refusing. Device-proven on a Google Messages peer 2026-08-03 — see SelfHealBudget#exhausted.
        assertTrue("an elapsed window frees a budget that the COUNT had exhausted",
                !b.exhausted(3, 1000L + 60_000L, 60_000L));
        assertTrue("and the window is what elapsed", b.windowElapsed(1000L + 60_000L, 60_000L));

        // Rolling yields a fresh window; the healed marker survives it (it is cleared by §5.10).
        final MlsConversationRecord.SelfHealBudget rolled = b.rolled();
        assertEquals("a rolled window starts at zero", 0, rolled.retryCount);
        assertEquals("and is un-anchored until the next attempt", 0L, rolled.firstAttemptAtMs);
        assertTrue("a rolled budget is spendable again", !rolled.exhausted(3, 1000L, 60_000L));
    }

    @Test public void forwardProgressIsWhatResetsTheBudget() {
        // Invariant 87: reset ONLY on real forward progress, never from a transition hook. A budget
        // reset by a transition is one the limit can never be reached against — a crash loop rides
        // it forever.
        MlsConversationRecord.SelfHealBudget b = MlsConversationRecord.SelfHealBudget.EMPTY;
        b = b.counted(1000L);
        b = b.counted(2000L);
        assertEquals(2, b.retryCount);
        assertEquals("the FIRST attempt's timestamp is what the window is measured from",
                1000L, b.firstAttemptAtMs);
        assertTrue("a heal has run since the last clear", b.healedSinceClear);
        assertEquals(0, MlsConversationRecord.SelfHealBudget.EMPTY.retryCount);
    }

    @Test public void aKilledHealIsRecordedAndIsNotTerminal() {
        // Invariants 43/91, rework 8.4d. The pending slot is single, so another operation taking it
        // means the heal holding it is no longer active — Google Messages calls that a kill, and invariant
        // 91 says SelfHealFailed has EXACTLY ONE producer. Until it was wired, state 6 had none and
        // was unreachable.
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement", "SelfHealKilled"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.SELFHEALFAILED));

        // NOT terminal — a later trigger must be able to start a fresh heal, or one interruption
        // would end recovery for the conversation permanently.
        assertEquals(Arrays.asList("RequestedSelfHeal"),
                walk(MlsHealthStates.SELFHEALFAILED, MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        // ...and a peer's commit still rescues it with no local action at all.
        assertEquals(Arrays.asList("HealedAfterRemoteCommit"),
                walk(MlsHealthStates.SELFHEALFAILED, MlsHealthStates.HEALTHY));
    }

    @Test public void anEraAdvanceInFlightIsAlsoKillable() {
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement",
                        "StartedEraAdvancement", "SelfHealKilled"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.ONGOINGERAADVANCEMENT,
                        MlsHealthStates.SELFHEALFAILED));
    }

    @Test public void onlyHealKindsHoldTheHealGuard() {
        // The pending guard drops a repeat heal ONLY when the occupant is itself a heal. Ordinary
        // work in the slot must not block recovery: dropping a heal because a subject commit is in
        // flight would leave a diverged conversation broken until someone triggered recovery again.
        for (final MlsPendingOperation.Kind k : new MlsPendingOperation.Kind[] {
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT,
                MlsPendingOperation.Kind.ERA_ADVANCEMENT,
                MlsPendingOperation.Kind.ERA_ADVANCEMENT_FOR_REVIVE,
                MlsPendingOperation.Kind.REVIVE_MLS,
                MlsPendingOperation.Kind.PHOENIX_MODE,
        }) {
            assertTrue(k + " is a self-heal", k.isSelfHeal());
        }
        for (final MlsPendingOperation.Kind k : new MlsPendingOperation.Kind[] {
                MlsPendingOperation.Kind.UNKNOWN,
                MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS,
                MlsPendingOperation.Kind.GROUP_METADATA,
                // END_MLS deliberately: a downgrade in flight is answered by the end-MLS guard,
                // which records CannotHealDuringEndMls — a refusal that says WHY, where a silent
                // drop here would say nothing.
                MlsPendingOperation.Kind.END_MLS,
        }) {
            assertFalse(k + " must NOT hold the heal guard", k.isSelfHeal());
        }
    }
}
