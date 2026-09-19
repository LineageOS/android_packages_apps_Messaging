/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Allowlist;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.BudgetKey;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Facts;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Guard;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.KeySource;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Outcome;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Reason;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.RecordState;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Tier;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Verdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The tier table, asserted as behaviour through {@link MlsStateChangeGate#decide}. The load-bearing
 * test reconciles {@link MlsStateChangeGate#guardsFor} against the decision over every
 * {@code (Tier, Guard)} pair from {@code values()}; the named operations are pinned separately in
 * the form a person reads. See docs/mls/budgets.md.
 */
public final class MlsStateChangeGateTest {

    /** A consulted guard, tripped alone, refuses and is named; an unconsulted one does nothing. */
    @Test
    public void theTableAndTheDecisionAgreeOnEveryTierGuardPair() {
        final List<String> wrong = new ArrayList<>();
        for (final Tier tier : Tier.values()) {
            for (final Guard guard : Guard.values()) {
                final boolean claimed = MlsStateChangeGate.consults(tier, guard);
                final Verdict tripped = MlsStateChangeGate.decide(tier, trip(tier, guard));

                if (claimed) {
                    if (tripped.outcome() != Outcome.REFUSE) {
                        wrong.add(tier + " claims to consult " + guard + " but tripping it gave "
                                + tripped + " instead of a refusal");
                    } else if (tripped.guard() != guard) {
                        wrong.add(tier + " claims to consult " + guard + " but tripping it was "
                                + "refused by " + tripped.guard() + " instead");
                    }
                } else {
                    if (tripped.outcome() == Outcome.REFUSE || !tripped.answered()) {
                        wrong.add(tier + " does NOT claim to consult " + guard + " but tripping it "
                                + "gave " + tripped
                                + " — the guard is being applied by a tier whose "
                                + "row does not list it, which is exactly the shape of those "
                                + "defects");
                    }
                }
            }
        }
        assertTrue("guardsFor() and decide() disagree:\n  " + String.join("\n  ", wrong), wrong
                .isEmpty());
    }

    /** The table is not empty: a deleted row would otherwise make every pair vacuously agree. */
    @Test
    public void everyTierIsClassified() {
        for (final Tier tier : Tier.values()) {
            final Guard[] row = MlsStateChangeGate.guardsFor(tier);
            assertTrue(tier + " has no guards at all. An unclassified tier must not read as "
                    + "'nothing applies, proceed' — that is how a new operation ships ungated.",
                    row.length > 0);
            final Verdict clean = MlsStateChangeGate.decide(tier, passing(tier));
            assertTrue(tier + " refuses on facts where every guard passes: " + clean,
                    clean.mayProceed());
        }
        assertEquals("a Tier was added or removed — re-read this file's assertions before changing "
                + "this number, because several of them are about the SET of tiers", 4,
                Tier.values().length);
        assertEquals("MlsStateChangeGate.Guard has gained or lost a constant — the same warning "
                + "applies. NAMED rather than left as 'a Guard': this file asserts FOUR on both "
                + "Guard and Outcome, so an unqualified 'expected:<4> but was:<5>' cannot tell a "
                + "reader which enum moved, let alone that it is in this file at all.", 4,
                Guard.values().length);
    }

    @Test
    public void theKillSwitchIsFirstEverywhere() {
        for (final Tier tier : Tier.values()) {
            final Guard[] row = MlsStateChangeGate.guardsFor(tier);
            assertSame("G6 is not the first guard " + tier + " consults. Without it there "
                    + "is no way to stop a runaway short of not running commands, so one "
                    + "sysprop must beat everything — including an allowlisted peer and an unspent "
                    + "budget.", Guard.G6_FREEZE, row[0]);
        }
    }

    /** When several guards would refuse, the first in the row is named. */
    @Test
    public void theFirstGuardInTheRowIsTheOneThatAnswers() {
        // Everything wrong at once, on the widest tier.
        final Verdict all = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder()
                        .frozen(true)
                        .allowlist(Allowlist.NOT_LISTED)
                        .peerHealth(RecordState.PRESENT,
                                MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .build());
        assertSame("with the freeze on, a wedged peer and a number outside the allowlist, the "
                + "refusal is not the kill switch's", Guard.G6_FREEZE, all.guard());
        assertSame(Reason.STATE_CHANGES_FROZEN, all.reason());

        // G4 before G1: a wedged allowed peer is refused by health, not by the allowlist.
        final Verdict health = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder()
                        .allowlist(Allowlist.NOT_LISTED)
                        .peerHealth(RecordState.PRESENT,
                                MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .build());
        assertSame("G1 answered before G4", Guard.G4_PEER_HEALTH, health.guard());

        // G4 before G2 on the era tier.
        final Verdict era = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT,
                                MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ true)
                        .build());
        assertSame("G2 answered before G4", Guard.G4_PEER_HEALTH, era.guard());
    }

    /** The reconciliation proves consistency; this proves the table says the intended thing. */
    @Test
    public void theSixNamedOperationsTakeTheTiersTheyDo() {
        // {operation, tier, G1?, G2?, G4?, G6?}
        assertRow("debug arm (mlsahead-arm, mlshold-arm, RcsDebugSendReceiver)",
                Tier.ALLOWLISTED_STATE_CHANGE, true, false, true, true);
        assertRow("organic add (addMember, allowedToJoinAll)",
                Tier.ALLOWLISTED_STATE_CHANGE, true, false, true, true);
        assertRow("remove (removeMember)",
                Tier.ORGANIC_STATE_CHANGE, false, false, true, true);
        assertRow("self-departure (leaveGroup)",
                Tier.SELF_DEPARTURE, false, false, false, true);
        assertRow("era advance (eraAdvance)",
                Tier.ERA_ADVANCE, false, true, true, true);
        assertRow("rebuild (rebuildConversation, reestablish-1to1)",
                Tier.ERA_ADVANCE, false, true, true, true);
    }

    private static void assertRow(final String op, final Tier tier, final boolean g1,
            final boolean g2, final boolean g4, final boolean g6) {
        assertEquals(op + " / G1 peer allowlist", g1,
                MlsStateChangeGate.consults(tier, Guard.G1_PEER_ALLOWLIST));
        assertEquals(op + " / G2 era budget", g2,
                MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET));
        assertEquals(op + " / G4 peer health", g4,
                MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH));
        assertEquals(op + " / G6 kill switch", g6,
                MlsStateChangeGate.consults(tier, Guard.G6_FREEZE));
    }

    /**
     * The era tier and the allowlisted tier are siblings: a re-creation pays G2 once at the funnel
     * and G1 once per member through {@code allowedToJoinAll}.
     */
    @Test
    public void theTwoSiblingTiersDoNotNest() {
        assertFalse("the era advance now takes the peer allowlist. Organic era advances happen on "
                + "real conversations; gating them on the fleet list is a different decision from "
                + "the one taken for the ADD, and it has not been taken here.",
                MlsStateChangeGate.consults(Tier.ERA_ADVANCE, Guard.G1_PEER_ALLOWLIST));
        assertFalse("an allowlisted state change now charges the era budget. An ADD is not an era "
                + "advance — charging one would spend the circuit breaker's allowance on an "
                + "operation that does not re-Welcome the group.",
                MlsStateChangeGate.consults(
                        Tier.ALLOWLISTED_STATE_CHANGE, Guard.G2_ERA_BUDGET));
    }

    /** A 1:1 era advance is charged to {@code peer:<digits>}. */
    @Test
    public void aOneToOneIsChargedToThePeer() {
        final BudgetKey k = MlsStateChangeGate.eraBudgetKey(null, "+15715550100");
        assertSame("a 1:1 no longer derives a key from the peer — every 1:1 era advance is "
                + "unbudgeted again, which is the original defect", KeySource.PEER, k.source());
        assertEquals("peer:5715550100", k.key());
        assertTrue(k.derived());
    }

    /** Two spellings of the same number are one budget. */
    @Test
    public void theOneToOneKeyCannotBeEvadedByFormat() {
        final String expected = "peer:5715550100";
        for (final String spelling : new String[] {
                "+15715550100", "15715550100", "5715550100", "+1 (571) 555-0100",
                "571-555-0100", "tel:+15715550100"}) {
            assertEquals("\"" + spelling
                    + "\" derives a different budget key, so the same peer has "
                    + "two allowances", expected,
                    MlsStateChangeGate.eraBudgetKey(null, spelling).key());
        }
    }

    /** A group id wins over the peer and is used verbatim. */
    @Test
    public void aGroupIsChargedToTheGroup() {
        final BudgetKey k = MlsStateChangeGate.eraBudgetKey("22ac7628", "+15715550100");
        assertSame(KeySource.GROUP_ID, k.source());
        assertEquals("22ac7628", k.key());
    }

    /** No key means no bound, so the advance is refused rather than run unbounded. */
    @Test
    public void anAdvanceWithNothingToChargeIsRefused() {
        for (final String[] pair : new String[][] {
                {null, null}, {"", ""}, {null, ""}, {"", null}, {null, "+"}, {"",
                        "not-a-number"}}) {
            final BudgetKey k = MlsStateChangeGate.eraBudgetKey(pair[0], pair[1]);
            assertSame("(" + pair[0] + ", " + pair[1] + ") derived a key from nothing",
                    KeySource.NONE, k.source());
            assertNull(k.key());
            assertFalse(k.derived());

            final Verdict v = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                    Facts.builder()
                            .peerHealth(RecordState.NOT_CONSULTED, 0)
                            .eraBudget(k, RecordState.NOT_CONSULTED, false)
                            .build());
            assertSame("(" + pair[0] + ", " + pair[1] + ") was not refused — an era advance with "
                    + "nothing to charge against cannot be bounded at all",
                    Outcome.REFUSE, v.outcome());
            assertSame(Reason.NO_BUDGET_KEY, v.reason());
            assertSame(Guard.G2_ERA_BUDGET, v.guard());
            assertFalse("a refusal asked for a record discard, but there is no record to discard",
                    v.discardRecord());
        }
    }

    /** {@link Tier#ERA_ADVANCE} never yields {@link Outcome#ALLOW}, only a charged allow. */
    @Test
    public void anEraAdvanceCannotProceedWithoutACharge() {
        final Verdict v = MlsStateChangeGate.decide(Tier.ERA_ADVANCE, passing(Tier.ERA_ADVANCE));
        assertSame("an era advance was allowed without a charge", Outcome.CHARGE_AND_ALLOW,
                v.outcome());
        assertTrue(v.mayProceed());
        assertTrue(v.mustChargeEraBudget());
        assertNotEquals("ERA_ADVANCE reached the bare ALLOW outcome", Outcome.ALLOW, v.outcome());

        for (final Tier tier : Tier.values()) {
            final Verdict clean = MlsStateChangeGate.decide(tier, passing(tier));
            assertEquals(tier + " must charge the era budget iff its row consults G2",
                    MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET),
                    clean.mustChargeEraBudget());
        }
    }

    /** An unparseable record refuses and asks to be discarded; an empty record allows. */
    @Test
    public void unreadableIsNotAbsentInEitherRecord() {
        final Verdict health = MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                Facts.builder().peerHealth(RecordState.UNREADABLE, 0).build());
        assertSame(Outcome.REFUSE, health.outcome());
        assertSame(Reason.PEER_HEALTH_UNREADABLE, health.reason());
        assertTrue("an unreadable health record must be discarded, or a format skew is a permanent "
                + "block on this peer", health.discardRecord());

        final Verdict era = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT, 0)
                        .eraBudget(groupKey(), RecordState.UNREADABLE, false)
                        .build());
        assertSame(Outcome.REFUSE, era.outcome());
        assertSame(Reason.ERA_BUDGET_UNREADABLE, era.reason());
        assertTrue("an unreadable era record must be discarded, or a format skew blocks this "
                + "conversation's repair for good", era.discardRecord());

        // An empty record is PRESENT and allows.
        final Verdict empty = MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build());
        assertTrue("a peer with nothing stored against it was refused", empty.mayProceed());
        assertFalse("nothing was refused, so nothing should be discarded", empty.discardRecord());
    }

    /** The streak refuses at the record's trip point and not before. */
    @Test
    public void theStreakRefusesAtTheShippedTripPoint() {
        final int trip = MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES;
        for (int streak = 0; streak < trip; streak++) {
            assertTrue("a streak of " + streak + " refused, below the shipped trip point of "
                    + trip, MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                            Facts.builder().peerHealth(RecordState.PRESENT, streak).build())
                            .mayProceed());
        }
        for (int streak = trip; streak <= trip + 2; streak++) {
            final Verdict v = MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                    Facts.builder().peerHealth(RecordState.PRESENT, streak).build());
            assertSame("a streak of " + streak + " was allowed, at or above the trip point of "
                    + trip, Outcome.REFUSE, v.outcome());
            assertSame(Reason.PEER_WEDGED, v.reason());
        }
    }

    /**
     * A blank peer passes G4 (no counterparty, no health opinion) and fails G1 (a blank is not a
     * number that may be brought into MLS).
     */
    @Test
    public void aBlankPeerPassesHealthAndFailsTheAllowlist() {
        final Verdict organic = MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                Facts.builder().peerHealth(RecordState.NOT_CONSULTED, 0).build());
        assertTrue("an organic state change naming no peer was refused. There is nothing to look "
                + "up and nothing to judge; refusing here would break the operations that address a "
                + "conversation rather than a member.", organic.mayProceed());

        final Verdict allowlisted = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder()
                        .peerHealth(RecordState.NOT_CONSULTED, 0)
                        .allowlist(Allowlist.NOT_LISTED)
                        .build());
        assertSame("a blank peer cleared the peer allowlist", Outcome.REFUSE,
                allowlisted.outcome());
        assertSame(Reason.PEER_NOT_ALLOWLISTED, allowlisted.reason());
    }

    /** G1 is a debug-build restriction only: a user build admits every peer. */
    @Test
    public void theAllowlistNeverRefusesOnAUserBuild() {
        for (final String list : new String[] {null, "", "+15715550100,+15715550101"}) {
            for (final String peer : new String[] {"+15715550199", "+15715550100", "", null}) {
                assertTrue("a user build refused " + peer + " with the list '" + list + "'. G1 is "
                        + "a test-device restriction; on a user build it would refuse MLS with "
                        + "every real peer", joins(false, list, peer));
            }
        }
    }

    /** An empty or unset list on a debug build restricts nothing. */
    @Test
    public void anEmptyListOnADebugBuildAllows() {
        for (final String list : new String[] {null, "", " ", ",", " , "}) {
            assertSame("the list '" + list + "' restricted a peer", Allowlist.UNRESTRICTED,
                    MlsStateChangeGate.allowlistFor(true, list, "+15715550199"));
            assertTrue(joins(true, list, "+15715550199"));
        }
    }

    /** A debug build with a list refuses a peer absent from it, and a blank peer. */
    @Test
    public void aListOnADebugBuildRefusesAnAbsentPeer() {
        final String list = "+15715550100, 5715550101";
        assertFalse("an absent peer cleared a set allowlist", joins(true, list, "+15715550199"));
        assertFalse("a blank peer cleared a set allowlist", joins(true, list, ""));
        assertSame(Allowlist.NOT_LISTED,
                MlsStateChangeGate.allowlistFor(true, list, "+15715550199"));
        // Listed, in any format that normalises to the same digits.
        assertTrue(joins(true, list, "5715550100"));
        assertTrue(joins(true, list, "+1 571 555 0101"));
    }

    /** Whether the tier that composes G1 lets {@code peer} join, all else passing. */
    private static boolean joins(final boolean debuggableBuild, final String list,
            final String peer) {
        return MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE, Facts.builder()
                .peerHealth(RecordState.PRESENT, 0)
                .allowlist(MlsStateChangeGate.allowlistFor(debuggableBuild, list, peer))
                .build()).mayProceed();
    }

    @Test
    public void leavingIsGatedByTheKillSwitchAndNothingElse() {
        assertArrayEqualsGuards(new Guard[] {Guard.G6_FREEZE},
                MlsStateChangeGate.guardsFor(Tier.SELF_DEPARTURE));

        // A wedged, peer outside the allowlist with a spent budget still gets out.
        final Verdict out = MlsStateChangeGate.decide(Tier.SELF_DEPARTURE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT, 99)
                        .allowlist(Allowlist.NOT_LISTED)
                        .eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ true)
                        .build());
        assertTrue(
                "a self-departure was refused by a guard its row does not list. Leaving is how a "
                + "person stops talking to a peer that has stopped answering, and the health streak "
                + "refuses it exactly then; for a group it would refuse on ONE member's streak.",
                out.mayProceed());

        final Verdict frozen = MlsStateChangeGate.decide(Tier.SELF_DEPARTURE,
                Facts.builder().frozen(true).build());
        assertSame("the kill switch no longer stops a self-leave, which DOES transmit — a "
                + "SelfRemove proposal on KickGroupUsers", Outcome.REFUSE, frozen.outcome());
        assertSame(Guard.G6_FREEZE, frozen.guard());
    }

    /** A tier missing a fact its row requires is UNANSWERABLE, distinct from a refusal. */
    @Test
    public void aMissingFactIsUnanswerableAndNotARefusal() {
        // The era tier with no budget facts at all.
        final Verdict noBudget = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build());
        assertSame("the era tier decided without being told anything about the budget",
                Outcome.UNANSWERABLE, noBudget.outcome());
        assertSame(Reason.FACT_NOT_SUPPLIED, noBudget.reason());
        assertSame(Guard.G2_ERA_BUDGET, noBudget.guard());
        assertFalse("UNANSWERABLE must fail closed", noBudget.mayProceed());
        assertFalse("UNANSWERABLE is not an answer", noBudget.answered());
        assertNotEquals("UNANSWERABLE shares a value with REFUSE — a caller cannot tell a guard "
                + "doing its job from a guard that could not be asked", Outcome.REFUSE,
                noBudget.outcome());

        // The allowlisted tier with G1 unanswered.
        final Verdict noAllowlist = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build());
        assertSame("an unanswered allowlist read as 'not an allowed peer', which hides the "
                + "omission behind a plausible line", Outcome.UNANSWERABLE, noAllowlist.outcome());
        assertSame(Guard.G1_PEER_ALLOWLIST, noAllowlist.guard());

        // The era tier told the key is derived but never told what the record says.
        final Verdict noRecord = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT, 0)
                        .eraBudget(groupKey(), RecordState.NOT_CONSULTED, false)
                        .build());
        assertSame(Outcome.UNANSWERABLE, noRecord.outcome());
        assertSame(Reason.FACT_NOT_SUPPLIED, noRecord.reason());

        // No facts at all, and a null tier.
        assertSame(Outcome.UNANSWERABLE,
                MlsStateChangeGate.decide(Tier.ERA_ADVANCE, null).outcome());
        assertSame(Outcome.UNANSWERABLE,
                MlsStateChangeGate.decide(null, passing(Tier.ORGANIC_STATE_CHANGE)).outcome());
        assertSame(Reason.TIER_NOT_CLASSIFIED,
                MlsStateChangeGate.decide(null, passing(Tier.ORGANIC_STATE_CHANGE)).reason());
    }

    @Test
    public void everyOutcomeIsDistinguishable() {
        assertEquals("MlsStateChangeGate.Outcome has gained or lost a constant; every caller "
                + "switching on it needs re-reading. NAMED for the same reason as Guard above — two "
                + "ratchets of 4 in one file, and a bare number belongs to neither.",
                4, Outcome.values().length);
        // Each outcome is reachable from facts that produce it.
        final List<Outcome> seen = new ArrayList<>();
        seen.add(MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                passing(Tier.ORGANIC_STATE_CHANGE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ERA_ADVANCE, passing(Tier.ERA_ADVANCE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                trip(Tier.ORGANIC_STATE_CHANGE, Guard.G6_FREEZE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build()).outcome());
        for (final Outcome o : Outcome.values()) {
            assertTrue(o + " is declared and nothing in this file can produce it",
                    seen.contains(o));
        }

        // A refusal never asks for a charge, and an allow never names a guard.
        for (final Tier tier : Tier.values()) {
            final Verdict allowed = MlsStateChangeGate.decide(tier, passing(tier));
            assertNull(tier + " allowed and still named a guard", allowed.guard());
            assertSame(Reason.NOTHING_REFUSED, allowed.reason());
            assertFalse(tier + " allowed and asked for a record discard", allowed.discardRecord());

            final Verdict refused = MlsStateChangeGate.decide(tier,
                    trip(tier, Guard.G6_FREEZE));
            assertFalse(tier + " refused and still asked to be charged",
                    refused.mustChargeEraBudget());
        }
    }

    @Test
    public void theTableCannotBeMutatedByACaller() {
        final Guard[] first = MlsStateChangeGate.guardsFor(Tier.ERA_ADVANCE);
        first[0] = Guard.G1_PEER_ALLOWLIST;
        assertSame("guardsFor handed out its own array — one caller's scribble rewrites the tier "
                + "table for the process", Guard.G6_FREEZE,
                MlsStateChangeGate.guardsFor(Tier.ERA_ADVANCE)[0]);
    }

    private static BudgetKey groupKey() {
        return MlsStateChangeGate.eraBudgetKey("22ac7628", null);
    }

    /** Facts on which every guard {@code tier} consults passes; the rest are not supplied. */
    private static Facts passing(final Tier tier) {
        final Facts.Builder b = Facts.builder().frozen(false);
        if (MlsStateChangeGate.consults(tier, Guard.G1_PEER_ALLOWLIST)) {
            b.allowlist(Allowlist.LISTED);
        }
        if (MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH)) {
            b.peerHealth(RecordState.PRESENT, 0);
        }
        if (MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET)) {
            b.eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ false);
        }
        return b.build();
    }

    /**
     * {@link #passing} with {@code guard}'s fact set to refuse, supplied whether or not the tier
     * consults it, so "ignored" and "never told" cannot be confused.
     */
    private static Facts trip(final Tier tier, final Guard guard) {
        final Facts.Builder b = Facts.builder();
        b.frozen(guard == Guard.G6_FREEZE);

        if (guard == Guard.G1_PEER_ALLOWLIST) {
            b.allowlist(Allowlist.NOT_LISTED);
        } else if (MlsStateChangeGate.consults(tier, Guard.G1_PEER_ALLOWLIST)) {
            b.allowlist(Allowlist.LISTED);
        }

        if (guard == Guard.G4_PEER_HEALTH) {
            b.peerHealth(RecordState.PRESENT, MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES);
        } else if (MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH)) {
            b.peerHealth(RecordState.PRESENT, 0);
        }

        if (guard == Guard.G2_ERA_BUDGET) {
            b.eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ true);
        } else if (MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET)) {
            b.eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ false);
        }
        return b.build();
    }

    private static void assertArrayEqualsGuards(final Guard[] expected, final Guard[] actual) {
        assertEquals(Arrays.toString(expected) + " != " + Arrays.toString(actual),
                Arrays.asList(expected), Arrays.asList(actual));
    }
}
