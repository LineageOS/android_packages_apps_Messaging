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
 * <b>The tier table, asserted as behaviour rather than read off four methods calling each other</b>
 * — Stage 2, decision D1(a).
 *
 * <h2>What this test is for</h2>
 *
 * <p>Three separate changes each got ONE ROW of the tier table wrong, and none
 * of the three was a wrong answer from a budget: each was the wrong SET of budgets consulted. That
 * class of defect is invisible to a per-policy test, because every policy involved is correct in
 * isolation — {@link MlsEraBudgetRecordTest} and {@link MlsPeerHealthRecordTest} were green
 * throughout all three.
 *
 * <p>So the load-bearing test here is {@link #theTableAndTheDecisionAgreeOnEveryTierGuardPair}, and
 * it is deliberately NOT a list of rows. It iterates every {@code (Tier, Guard)} pair — from
 * {@code values()}, so a tier or guard added later is included whether or not anyone remembers this
 * file — and for each pair asserts BOTH directions against facts that trip that guard alone:
 *
 * <ul>
 *   <li>if {@link MlsStateChangeGate#guardsFor} claims the tier composes the guard, tripping it must
 *       REFUSE and must name that guard;</li>
 *   <li>if it claims the tier does not, tripping it must change nothing.</li>
 * </ul>
 *
 * <p>That reconciles the declared table against the decision, so neither can drift from the other,
 * and a hand-written row cannot be wrong because there are none. The six named operations of the
 * plan's Stage 2 — debug arm, organic add, remove, self-departure, era advance, rebuild — are
 * pinned separately in {@link #theSixNamedOperationsTakeTheTiersTheyDo}, which is the table in the
 * form a person reads.
 *
 * <h2>Why not a source scan</h2>
 *
 * <p>The rule: "stop proving these properties by grep … a property asserted against a
 * real call is not spellable-around". Every assertion here calls {@link MlsStateChangeGate#decide}.
 * The only source-scanning left for this subject is {@link MlsStateChangeTierWiringTest}, which
 * answers the one question no host test can — which tier each transport call site chose — and says
 * so.
 */
public final class MlsStateChangeGateTest {

    // ---- the pair-by-pair reconciliation of the table against the decision ----------------------

    /**
     * <b>The tier table and {@code decide} agree, over every pair, in both directions.</b>
     *
     * <p>The whole of D1(a) in one assertion. Enumerated from {@code values()} rather than from a
     * list of rows: a {@link Tier} or {@link Guard} added later is covered the day it is added, and
     * a tier with no arm in {@link MlsStateChangeGate#guardsFor} fails
     * {@link #everyTierIsClassified} rather than quietly passing this one with an empty row.
     */
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
                                + "gave " + tripped + " — the guard is being applied by a tier whose "
                                + "row does not list it, which is exactly the shape of those "
                                + "defects");
                    }
                }
            }
        }
        assertTrue("guardsFor() and decide() disagree:\n  " + String.join("\n  ", wrong), wrong
                .isEmpty());
    }

    /**
     * A tier with no row is UNANSWERABLE, never "no guards apply, proceed".
     *
     * <p>The reciprocal of the test above: it proves the table matches the decision, and this proves
     * the table is not empty. Without it, deleting a row would make every pair vacuously consistent.
     */
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

    /** Every tier consults the kill switch, and consults it FIRST. */
    @Test
    public void theKillSwitchIsFirstEverywhere() {
        for (final Tier tier : Tier.values()) {
            final Guard[] row = MlsStateChangeGate.guardsFor(tier);
            assertSame("G6 is not the first guard " + tier + " consults. During the incident there "
                    + "was no way to stop the bleeding short of not running commands, so one "
                    + "sysprop must beat everything — including an allowlisted peer and an unspent "
                    + "budget.", Guard.G6_FREEZE, row[0]);
        }
    }

    /**
     * When two guards would both refuse, the FIRST in the row is the one named.
     *
     * <p>Order is part of the answer, not a detail: it decides which sentence a person debugging a
     * stalled conversation reads, and reading "not a lab peer" when the fleet is frozen sends them
     * to the wrong lever.
     */
    @Test
    public void theFirstGuardInTheRowIsTheOneThatAnswers() {
        // Everything wrong at once, on the widest tier.
        final Verdict all = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder()
                        .frozen(true)
                        .allowlist(Allowlist.NOT_LISTED)
                        .peerHealth(RecordState.PRESENT, MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .build());
        assertSame("with the freeze on, a wedged peer and a non-lab number, the refusal is not the "
                + "kill switch's", Guard.G6_FREEZE, all.guard());
        assertSame(Reason.STATE_CHANGES_FROZEN, all.reason());

        // G4 before G1: a wedged LAB peer is refused by health, not by the allowlist.
        final Verdict health = MlsStateChangeGate.decide(Tier.ALLOWLISTED_STATE_CHANGE,
                Facts.builder()
                        .allowlist(Allowlist.NOT_LISTED)
                        .peerHealth(RecordState.PRESENT, MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .build());
        assertSame("G1 answered before G4", Guard.G4_PEER_HEALTH, health.guard());

        // G4 before G2 on the era tier, for the same reason.
        final Verdict era = MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT, MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES)
                        .eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ true)
                        .build());
        assertSame("G2 answered before G4", Guard.G4_PEER_HEALTH, era.guard());
    }

    // ---- the six named operations, in the form a person reads -----------------------------------

    /**
     * The plan's own six rows: {debug arm, organic add, remove, self-departure, era advance,
     * rebuild} against {G1, G2, G4, G6}.
     *
     * <p>Redundant with the pair reconciliation above <b>on purpose</b>. That test proves the table
     * is self-consistent; this one proves the table says what Stage 2 was asked to make it say, in
     * the words the plan uses. A reconciliation of two wrong things is still consistent.
     */
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
        assertEquals(op + " / G1 lab allowlist", g1,
                MlsStateChangeGate.consults(tier, Guard.G1_LAB_ALLOWLIST));
        assertEquals(op + " / G2 era budget", g2,
                MlsStateChangeGate.consults(tier, Guard.G2_ERA_BUDGET));
        assertEquals(op + " / G4 peer health", g4,
                MlsStateChangeGate.consults(tier, Guard.G4_PEER_HEALTH));
        assertEquals(op + " / G6 kill switch", g6,
                MlsStateChangeGate.consults(tier, Guard.G6_FREEZE));
    }

    /**
     * <b>The era advance does NOT take the allowlist, and the allowlisted tiers do NOT take the era
     * budget.</b>
     *
     * <p>The plan's D1 prose — "{@code allowStateChange} = G6 ∧ G4; {@code allowEraAdvance} = that ∧
     * G2; {@code allowJoiningPeer}/{@code allowDebugStateChange} = that ∧ G1" — can be read as
     * nesting the third clause inside the second. In source it does not: {@code
     * allowAllowlistedStateChange} calls {@code allowStateChange}. The two are siblings, and a group
     * re-creation pays both separately: G2 once at the rebuild funnel, G1 once per member through
     * {@code allowedToJoinAll}. Pinned because a reader of the plan alone would get it wrong.
     */
    @Test
    public void theTwoSiblingTiersDoNotNest() {
        assertFalse("the era advance now takes the lab allowlist. Organic era advances happen on "
                + "real conversations; gating them on the fleet list is a different decision from "
                + "the one taken for the ADD, and it has not been taken here.",
                MlsStateChangeGate.consults(Tier.ERA_ADVANCE, Guard.G1_LAB_ALLOWLIST));
        assertFalse("an allowlisted state change now charges the era budget. An ADD is not an era "
                + "advance — charging one would spend the circuit breaker's allowance on an "
                + "operation that does not re-Welcome the group.",
                MlsStateChangeGate.consults(
                        Tier.ALLOWLISTED_STATE_CHANGE, Guard.G2_ERA_BUDGET));
    }

    // ---- the 1:1 key bug, as a host test --------------------------------------------------------

    /**
     * <b>{@code eraBudgetKey(null, "+1…")} returns {@code peer:<digits>}.</b>
     *
     * <p>The derivation used to be "if the group id is empty, allow", so every 1:1 era advance was
     * unbudgeted and the log never said so. The wedged peer's 1:1 was force-advanced on 2026-08-25
     * to break a deadlock, three weeks into the incident, and nothing counted it.
     *
     * <p>Until Stage 2 this was reachable only through {@code MlsPeerGuard}, which needs a
     * {@code Context}, so the row that mattered most had no test at all.
     */
    @Test
    public void aOneToOneIsChargedToThePeer() {
        final BudgetKey k = MlsStateChangeGate.eraBudgetKey(null, "+15715550100");
        assertSame("a 1:1 no longer derives a key from the peer — every 1:1 era advance is "
                + "unbudgeted again, which is the original defect", KeySource.PEER, k.source());
        assertEquals("peer:5715550100", k.key());
        assertTrue(k.derived());
    }

    /** The same conversation reached by two spellings of the number is ONE budget. */
    @Test
    public void theOneToOneKeyCannotBeEvadedByFormat() {
        final String expected = "peer:5715550100";
        for (final String spelling : new String[] {
                "+15715550100", "15715550100", "5715550100", "+1 (571) 555-0100",
                "571-555-0100", "tel:+15715550100"}) {
            assertEquals("\"" + spelling + "\" derives a different budget key, so the same peer has "
                    + "two allowances", expected,
                    MlsStateChangeGate.eraBudgetKey(null, spelling).key());
        }
    }

    /** A group id wins over the peer, and is used verbatim. */
    @Test
    public void aGroupIsChargedToTheGroup() {
        final BudgetKey k = MlsStateChangeGate.eraBudgetKey("22ac7628", "+15715550100");
        assertSame(KeySource.GROUP_ID, k.source());
        assertEquals("22ac7628", k.key());
    }

    /**
     * <b>A {@code (null, null)} pair REFUSES.</b>
     *
     * <p>No key means no bound, and an unbounded re-creation is worse than one deferred. It is also
     * the only honest answer available: we cannot say an advance is within a budget we could not
     * look up.
     */
    @Test
    public void anAdvanceWithNothingToChargeIsRefused() {
        for (final String[] pair : new String[][] {
                {null, null}, {"", ""}, {null, ""}, {"", null}, {null, "+"}, {"", "not-a-number"}}) {
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

    /**
     * <b>An allowed era advance that was not charged is unrepresentable.</b>
     *
     * <p>{@link Tier#ERA_ADVANCE} never yields {@link Outcome#ALLOW}. That is the defect
     * — a door onto the re-Welcome cost that proceeded uncharged — made impossible in the type
     * rather than fixed at one call site: a caller switching on the outcome cannot fall through into
     * a proceeding path that skips the charge, because there is no such path.
     */
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

    // ---- unreadable is not absent ---------------------------------------------------------------

    /**
     * A record that EXISTS and does not parse refuses and asks to be discarded; a record that stores
     * nothing allows.
     *
     * <p>Reading an unparseable health record as "no failures" would answer "this peer is fine" on
     * the strength of a parse error — the posture this rung was already in — and
     * reading an unparseable budget as "uncharged" would silently restore the allowance on the
     * operation that wedged a phone.
     */
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

        // The other side: an EMPTY record is PRESENT and allows.
        final Verdict empty = MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build());
        assertTrue("a peer with nothing stored against it was refused", empty.mayProceed());
        assertFalse("nothing was refused, so nothing should be discarded", empty.discardRecord());
    }

    /** The streak refuses at the trip point and not before — the constant read from the record. */
    @Test
    public void theStreakRefusesAtTheShippedTripPoint() {
        final int trip = MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES;
        for (int streak = 0; streak < trip; streak++) {
            assertTrue("a streak of " + streak + " refused, below the shipped trip point of " + trip,
                    MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
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

    // ---- the rows where "no peer" is not one answer ----------------------------------------------

    /**
     * <b>An operation that names no peer passes G4 and is refused by G1.</b>
     *
     * <p>Two tiers, two different answers to the same blank, and both are deliberate.
     * {@code allowStateChange} allows: there is no counterparty, so there is no counterparty health
     * opinion. The allowlist refuses: its question is "may this number be brought into MLS at all",
     * and a blank is not a number that question can be answered for.
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
        assertSame("a blank peer cleared the lab allowlist", Outcome.REFUSE,
                allowlisted.outcome());
        assertSame(Reason.NOT_A_LAB_PEER, allowlisted.reason());
    }

    /** The self-departure tier reads nothing but the freeze. */
    @Test
    public void leavingIsGatedByTheKillSwitchAndNothingElse() {
        assertArrayEqualsGuards(new Guard[] {Guard.G6_FREEZE},
                MlsStateChangeGate.guardsFor(Tier.SELF_DEPARTURE));

        // A wedged, non-lab peer with a spent budget still gets out.
        final Verdict out = MlsStateChangeGate.decide(Tier.SELF_DEPARTURE,
                Facts.builder()
                        .peerHealth(RecordState.PRESENT, 99)
                        .allowlist(Allowlist.NOT_LISTED)
                        .eraBudget(groupKey(), RecordState.PRESENT, /*spent=*/ true)
                        .build());
        assertTrue("a self-departure was refused by a guard its row does not list. Leaving is how a "
                + "person stops talking to a peer that has stopped answering, and the health streak "
                + "refuses it exactly then; for a group it would refuse on ONE member's streak.",
                out.mayProceed());

        final Verdict frozen = MlsStateChangeGate.decide(Tier.SELF_DEPARTURE,
                Facts.builder().frozen(true).build());
        assertSame("the kill switch no longer stops a self-leave, which DOES transmit — a "
                + "SelfRemove proposal on KickGroupUsers", Outcome.REFUSE, frozen.outcome());
        assertSame(Guard.G6_FREEZE, frozen.guard());
    }

    // ---- the unanswerable question ----------------------------------------------------------------

    /**
     * <b>A question the gate cannot answer is a fourth value, not a refusal.</b>
     *
     * <p>The house rule already stated elsewhere: a reader that refuses a case must
     * not share a return value with "absent". Applied here, a tier asked to decide without a fact
     * its own row requires must be distinguishable from a guard refusing — otherwise a missing arm
     * hides behind correct-looking behaviour until the day it was supposed to allow something.
     * {@link MlsEraAdvanceCharge#basisFor} makes the same move for an undeclared era mode.
     */
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
        assertSame("an unanswered allowlist read as 'not a lab peer', which hides the omission "
                + "behind a plausible line", Outcome.UNANSWERABLE, noAllowlist.outcome());
        assertSame(Guard.G1_LAB_ALLOWLIST, noAllowlist.guard());

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

    /** The three outcomes are three, and {@code mayProceed} agrees with each of them. */
    @Test
    public void everyOutcomeIsDistinguishable() {
        assertEquals("MlsStateChangeGate.Outcome has gained or lost a constant; every caller "
                + "switching on it needs re-reading. NAMED for the same reason as Guard above — two "
                + "ratchets of 4 in one file, and a bare number belongs to neither.",
                4, Outcome.values().length);
        // Each of the four outcomes is REACHABLE, from facts that produce it — an outcome nothing
        // can reach is a branch that has never been executed, and this file would otherwise assert
        // four values while only ever producing three.
        final List<Outcome> seen = new ArrayList<>();
        seen.add(MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                passing(Tier.ORGANIC_STATE_CHANGE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ERA_ADVANCE, passing(Tier.ERA_ADVANCE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ORGANIC_STATE_CHANGE,
                trip(Tier.ORGANIC_STATE_CHANGE, Guard.G6_FREEZE)).outcome());
        seen.add(MlsStateChangeGate.decide(Tier.ERA_ADVANCE,
                Facts.builder().peerHealth(RecordState.PRESENT, 0).build()).outcome());
        for (final Outcome o : Outcome.values()) {
            assertTrue(o + " is declared and nothing in this file can produce it", seen.contains(o));
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

    /** The table is data the caller cannot corrupt. */
    @Test
    public void theTableCannotBeMutatedByACaller() {
        final Guard[] first = MlsStateChangeGate.guardsFor(Tier.ERA_ADVANCE);
        first[0] = Guard.G1_LAB_ALLOWLIST;
        assertSame("guardsFor handed out its own array — one caller's scribble rewrites the tier "
                + "table for the process", Guard.G6_FREEZE,
                MlsStateChangeGate.guardsFor(Tier.ERA_ADVANCE)[0]);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A budget key that exists, for the tiers that need one. */
    private static BudgetKey groupKey() {
        return MlsStateChangeGate.eraBudgetKey("22ac7628", null);
    }

    /**
     * Facts on which every guard {@code tier} composes PASSES, and every guard it does not is left
     * NOT_CONSULTED — so a passing verdict cannot be an accident of an unsupplied fact.
     *
     * <p>Built from {@link MlsStateChangeGate#consults} rather than per tier, so a tier that gains a
     * guard gains its fact here without this file being edited.
     */
    private static Facts passing(final Tier tier) {
        final Facts.Builder b = Facts.builder().frozen(false);
        if (MlsStateChangeGate.consults(tier, Guard.G1_LAB_ALLOWLIST)) {
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
     * {@link #passing} with exactly {@code guard}'s fact set to the value that refuses.
     *
     * <p>The point of the pair reconciliation: the SAME fact is supplied whether or not the tier
     * claims to consult the guard, so "the tier ignored it" and "the tier was never told" cannot be
     * confused. A tier that does not compose G2 is still handed a spent budget.
     */
    private static Facts trip(final Tier tier, final Guard guard) {
        final Facts.Builder b = Facts.builder();
        b.frozen(guard == Guard.G6_FREEZE);

        if (guard == Guard.G1_LAB_ALLOWLIST) {
            b.allowlist(Allowlist.NOT_LISTED);
        } else if (MlsStateChangeGate.consults(tier, Guard.G1_LAB_ALLOWLIST)) {
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
