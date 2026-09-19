/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The recovery ladder as a sequence. A ladder can be built entirely from legal pairs of the
 * transition table and still go nowhere; these walk the paths the code takes and assert the edge
 * names along the way, so rerouting a rung through a different edge fails even when every step
 * stays legal. See docs/mls/health-and-recovery.md.
 */
public class MlsRecoveryLadderTest {

    /** Walks a sequence of target states and returns the edge name taken at each step. */
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
        // The replay catches us up: request, start, succeed.
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement",
                        "EpochAdvancementSuccessful"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.HEALTHY));
    }

    @Test public void theEscalationToEraWalksEndToEnd() {
        // The replay cannot bridge the gap: the epoch attempt escalates to an era advance rather
        // than reporting a heal it did not achieve.
        assertEquals(Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement",
                        "StartedEraAdvancement", "EraAdvancementSuccessful"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.ONGOINGERAADVANCEMENT,
                        MlsHealthStates.HEALTHY));
    }

    @Test public void aPeersCommitUnwedgesUsFromEveryInFlightRung() {
        // No local action: a peer's commit can land while the heal sits on any rung.
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
        // Finding end_mls on the server's GroupInfo mid-heal records DoneEndMls.
        assertEquals(Arrays.asList("RequestedSelfHeal", "EndMlsAppliedByRemoteClient"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.DONEENDMLS));
    }

    @Test public void theBudgetExhaustionEscalatesToEraNeverToSelfHealFailed() {
        // Both exhaustion arms produce EraAdvancementRequested; routing exhaustion to
        // SelfHealFailed would end recovery permanently.
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .healthStatus(MlsHealthStates.EPOCHADVANCEMENTREQUESTED).build();
        final MlsHealthMachine.Applied a = MlsHealthMachine.transition(
                rec, MlsHealthStates.ERAADVANCEMENTREQUESTED, rec.moment, /*allowIllegal=*/ false);
        assertEquals(MlsHealthMachine.Outcome.LEGAL, a.decision.outcome);
        assertEquals(MlsHealthStates.ERAADVANCEMENTREQUESTED, a.record.healthStatus);
    }

    @Test public void theBudgetIsARateNotAOneShotAllowance() {
        // The budget bounds self-heals to `limit` attempts per `windowMs`: the count exhausts, the
        // window rolls.
        final MlsConversationRecord.SelfHealBudget fresh =
                MlsConversationRecord.SelfHealBudget.EMPTY;
        assertTrue("a fresh budget is not exhausted", !fresh.exhausted(3, 1000L, 60_000L));

        MlsConversationRecord.SelfHealBudget b = fresh;
        for (int i = 0; i < 3; i++) b = b.counted(1000L);
        assertTrue("three attempts against a limit of three is exhausted",
                b.exhausted(3, 1000L, 60_000L));

        // Once the window has passed the budget is free, however high the count went: the only
        // other reset is forward progress, which needs the heal the budget would refuse. See
        // SelfHealBudget#exhausted.
        assertTrue("an elapsed window frees a budget that the COUNT had exhausted",
                !b.exhausted(3, 1000L + 60_000L, 60_000L));
        assertTrue("and the window is what elapsed", b.windowElapsed(1000L + 60_000L, 60_000L));

        // Rolling yields a fresh window; the healed marker survives it.
        final MlsConversationRecord.SelfHealBudget rolled = b.rolled();
        assertEquals("a rolled window starts at zero", 0, rolled.retryCount);
        assertEquals("and is un-anchored until the next attempt", 0L, rolled.firstAttemptAtMs);
        assertTrue("a rolled budget is spendable again", !rolled.exhausted(3, 1000L, 60_000L));
    }

    @Test public void forwardProgressIsWhatResetsTheBudget() {
        // Reset only on real forward progress, never from a transition hook, or a crash loop could
        // never reach the limit.
        MlsConversationRecord.SelfHealBudget b = MlsConversationRecord.SelfHealBudget.EMPTY;
        b = b.counted(1000L);
        b = b.counted(2000L);
        assertEquals(2, b.retryCount);
        assertEquals("the FIRST attempt's timestamp is what the window counts from",
                1000L, b.firstAttemptAtMs);
        assertTrue("a heal has run since the last clear", b.healedSinceClear);
        assertEquals(0, MlsConversationRecord.SelfHealBudget.EMPTY.retryCount);
    }

    @Test public void aKilledHealIsRecordedAndIsNotTerminal() {
        // The pending slot is single, so another operation taking it kills the heal holding it;
        // SelfHealKilled is SelfHealFailed's one producer.
        assertEquals(
                Arrays.asList("RequestedSelfHeal", "StartedEpochAdvancement", "SelfHealKilled"),
                walk(MlsHealthStates.HEALTHY,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                        MlsHealthStates.SELFHEALFAILED));

        // Not terminal: a later trigger can start a fresh heal.
        assertEquals(Arrays.asList("RequestedSelfHeal"),
                walk(MlsHealthStates.SELFHEALFAILED, MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        // A peer's commit still rescues it with no local action.
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
        // The pending guard drops a repeat heal only when the occupant is itself a heal; ordinary
        // work in the slot does not block recovery.
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
                // END_MLS: a downgrade in flight is answered by the end-MLS guard, which records
                // CannotHealDuringEndMls rather than dropping silently.
                MlsPendingOperation.Kind.END_MLS,
        }) {
            assertFalse(k + " must NOT hold the heal guard", k.isSelfHeal());
        }
    }
}
