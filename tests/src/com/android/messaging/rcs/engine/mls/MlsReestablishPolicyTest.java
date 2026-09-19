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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule that decides whether a re-establish is charged to the era budget.
 */
public final class MlsReestablishPolicyTest {

    /**
     * THE PROPERTY, asserted over the whole enum rather than through one worked example: anything
     * that is not a measured "nobody re-joins" is charged.
     *
     * <p>The two flags are declared independently on the constants, so this has teeth: a verdict
     * added later that says peers re-join and does not charge — which is exactly the state the
     * rebuild path was in for months — fails here rather than shipping as a second uncharged door.
     */
    @Test
    public void everyVerdictThatCanMakeAPeerReJoinIsCharged() {
        for (final MlsReestablishPolicy.Verdict v : MlsReestablishPolicy.Verdict.values()) {
            final boolean mustBeFree = v.reJoin() == MlsReestablishPolicy.ReJoin.NO;
            assertEquals(v + " declares reJoin=" + v.reJoin() + " but chargesEraBudget="
                    + v.chargesEraBudget() + ". Only a measured 'nobody re-joins' may be free: "
                    + "every other outcome makes a peer re-join by Welcome, or might, and that is "
                    + "the cost MlsPeerGuard's era budget (G2) bounds.",
                    !mustBeFree, v.chargesEraBudget());
        }
    }

    /** "Not answered" and "answered, holds nothing" must never share an outcome. */
    @Test
    public void anUnreadableServerIsNotTheSameAsAnEmptyOne() {
        final MlsReestablishPolicy.Verdict unreadable =
                MlsReestablishPolicy.classify(/*serverAnswered=*/ false, -1L);
        final MlsReestablishPolicy.Verdict empty =
                MlsReestablishPolicy.classify(/*serverAnswered=*/ true, -1L);
        assertEquals(MlsReestablishPolicy.Verdict.SERVER_UNREADABLE, unreadable);
        assertEquals(MlsReestablishPolicy.Verdict.FIRST_CREATE, empty);
        assertTrue("an answer we did not get must not select the cheap outcome",
                unreadable.chargesEraBudget());
        assertFalse("a first create is not an era advance and must not spend one",
                empty.chargesEraBudget());
    }

    @Test
    public void aConversationTheServerHoldsIsARecreation() {
        for (final long era : new long[] {1L, 2L, 18L, 4294967295L}) {
            assertEquals("server era " + era + " means the server holds this conversation",
                    MlsReestablishPolicy.Verdict.RECREATES_EXISTING,
                    MlsReestablishPolicy.classify(true, era));
        }
        // The boundary: ERA_INITIAL is a real era, anything below it is "no group".
        assertEquals(MlsReestablishPolicy.Verdict.RECREATES_EXISTING,
                MlsReestablishPolicy.classify(true, MlsReestablishPolicy.ERA_INITIAL));
        assertEquals(MlsReestablishPolicy.Verdict.FIRST_CREATE,
                MlsReestablishPolicy.classify(true, MlsReestablishPolicy.ERA_INITIAL - 1));
    }

    /**
     * THE TWO CASES THE OBVIOUS PREDICATE GETS WRONG, pinned as cases rather than as prose.
     *
     * <p>One proposal was to charge "only when a GroupInfo carry is present". A 1:1 rebuild
     * never has a carry, and a group in REJOIN has none either because the pack is fetched with a
     * local group we do not hold — yet both re-create a conversation the server still holds and
     * re-Welcome every member. The verdict must not depend on the carry, so the carry is not an
     * input to it: what follows is the server's answer for those two shapes.
     */
    @Test
    public void aCarrylessRebuildOverAHeldConversationIsStillCharged() {
        // 1:1 rebuild: no pack is ever fetched (serverPackForRebuild returns null for a 1:1), so
        // there is no carry — and the server holds the conversation at era 7.
        assertTrue("a 1:1 rebuild has no carry and re-creates anyway",
                MlsReestablishPolicy.classify(true, 7L).chargesEraBudget());
        // Group in REJOIN: we hold no local group, so the pack fetch has nothing to ask with and the
        // carry is null — and the server holds the group at era 3.
        assertTrue("a REJOIN rebuild has no carry and re-creates anyway",
                MlsReestablishPolicy.classify(true, 3L).chargesEraBudget());
    }

    /**
     * THE FORK PREDICATE — and it is a DIFFERENT question from the charge.
     *
     * <p>{@link MlsReestablishPolicy#classify} deliberately does not see the carry (the test above
     * this one pins that, and it is the whole finding). But whether peers re-join
     * and whether what we are about to build IS the server's group are two questions, and only the
     * second one needs the carry: with nothing local — the rebuild's first act is to forget — and
     * nothing carried, {@code plan_group} takes ARM 1 and is born at {@link
     * MlsReestablishPolicy#ERA_INITIAL}, beside the group the server already holds.
     */
    @Test
    public void aCarrylessGroupReestablishOverAHeldGroupIsAFork() {
        assertTrue("the server holds it, we have nothing to carry: born at ERA_INITIAL beside it",
                MlsReestablishPolicy.forksAtEraInitial(/*isGroup=*/ true,
                        MlsReestablishPolicy.classify(true, 1L), /*haveCarry=*/ false));
        assertTrue("and the same at any era the server reports — above ERA_INITIAL the create is "
                        + "rolled back instead, which is a rebuild that destroyed both halves of "
                        + "our state for nothing",
                MlsReestablishPolicy.forksAtEraInitial(true,
                        MlsReestablishPolicy.classify(true, 9L), false));
    }

    /**
     * The three ways it must NOT fire, each for its own reason. A predicate that answers true too
     * often blocks the first encryption of a conversation, which is worse than the fork.
     */
    @Test
    public void theForkPredicateDoesNotFireOnTheCasesThatAreFine() {
        assertFalse("a carry makes plan_group take ARM 2 and derive server_era + 1, so this is not "
                        + "a FORK at ERA_INITIAL. It is ALSO not a repair — that half was never "
                        + "measured, and was measured false on 2026-09-11; the era the "
                        + "engine derives is not what decides whether the server applies the "
                        + "create. reCreateWouldNotTake is the predicate that covers it",
                MlsReestablishPolicy.forksAtEraInitial(true,
                        MlsReestablishPolicy.classify(true, 3L), /*haveCarry=*/ true));
        assertFalse("the server holds nothing, so a carry-less create is a real FIRST create "
                        + "and is exactly right",
                MlsReestablishPolicy.forksAtEraInitial(true,
                        MlsReestablishPolicy.classify(true, -1L), false));
        assertFalse("a 1:1 never has a carry and re-establishes through ensureReady's id reclaim "
                        + "arm, not through plan_group ARM 1 — refusing it would remove the only "
                        + "recovery a 1:1 that lost its group has",
                MlsReestablishPolicy.forksAtEraInitial(/*isGroup=*/ false,
                        MlsReestablishPolicy.classify(true, 3L), false));
    }

    /**
     * SERVER_UNREADABLE is charged like a re-creation and is deliberately NOT refused as a fork.
     *
     * <p>Named as its own case because the two flags point opposite ways and that looks like an
     * oversight until it is written down: the charge is conservative because an unmeasured cost must
     * not be the cheap one, and the refusal is NOT, because blocking a rebuild on an unmeasured
     * server state would block the first encryption of a group whose server read merely blipped.
     * Widen it only with a measurement — and change this test when you do.
     */
    @Test
    public void anUnreadableServerIsChargedButNotRefusedAsAFork() {
        final MlsReestablishPolicy.Verdict v = MlsReestablishPolicy.classify(false, -1L);
        assertEquals(MlsReestablishPolicy.Verdict.SERVER_UNREADABLE, v);
        assertTrue("still charged", v.chargesEraBudget());
        assertFalse("but not refused: we did not measure that the server holds anything",
                MlsReestablishPolicy.forksAtEraInitial(true, v, false));
    }

    /** The refusal has to say what it measured, or the next reader chases the wrong half. */
    @Test
    public void theForkLineNamesTheServersEraAndTheEraWeWouldBeBornAt() {
        final String line = MlsReestablishPolicy.forkLine(4L);
        assertTrue(line, line.contains("era 4"));
        assertTrue("it must name the era the build would land at, not just the server's",
                line.contains("born at era " + MlsReestablishPolicy.ERA_INITIAL));
        assertTrue("and must say nothing was dropped, because that is the behaviour change",
                line.contains("BEFORE anything is dropped"));
    }

    /** The log line must describe what was measured, not what was assumed. */
    @Test
    public void theLineSaysWhichAnswerItHad() {
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.RECREATES_EXISTING, 9L)
                .contains("era 9"));
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.SERVER_UNREADABLE, -1L)
                .contains("did not answer"));
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.FIRST_CREATE, -1L)
                .contains("not charged"));
    }

    // ============================================================================================
    // reCreateWouldNotTake — the half of the rebuild arm the carry hid.
    // ============================================================================================

    /**
     * <b>THE HOLE, ASSERTED AS A PAIR.</b> This is the case the fork predicate answers FALSE on and
     * the one that fired on hardware.
     *
     * <p>{@code deviceB}, group {@code 0144736a…}, 2026-09-11 23:09: DIVERGED was detected
     * correctly, a GroupInfo carry WAS in hand, {@link MlsReestablishPolicy#forksAtEraInitial}
     * therefore said "not a fork — proceed", and the rebuild dropped both halves, claimed a peer
     * KeyPackage, charged the era budget, built era 2 — and the server, read again 46 seconds later,
     * still held era 1 epoch 7. Unchanged. The create was not applied.
     *
     * <p>The two predicates are asserted TOGETHER because the defect is the gap between them, not
     * either one alone: reverting the new predicate to delegate to the old one would satisfy any
     * single-predicate test and reopen exactly this.
     */
    @Test
    public void aCarriedGroupReCreateOverAHeldGroupIsRefusedEvenThoughItIsNotAFork() {
        final MlsReestablishPolicy.Verdict held = MlsReestablishPolicy.classify(true, 1L);
        assertFalse("precondition — with a carry the FORK predicate says proceed, which is what let "
                        + "the 010T rebuild spend and destroy",
                MlsReestablishPolicy.forksAtEraInitial(/*isGroup=*/ true, held,
                        /*haveCarry=*/ true));
        assertTrue("and the create still goes out under the contextId the server holds, which "
                        + "was measured 8/8 as not applied — so this must be refused",
                MlsReestablishPolicy.reCreateWouldNotTake(/*isGroup=*/ true, held,
                        /*contextIdIsReused=*/ true));
    }

    /**
     * The carry is IRRELEVANT to this predicate, and saying so is the point.
     *
     * <p>The carry decides the era the ENGINE derives; the contextId decides whether the SERVER
     * applies the create. Conflating them is the whole point, so the predicate does
     * not take a carry argument at all and this test pins that both carry states reach the same
     * answer through the fork predicate's own inputs.
     */
    @Test
    public void theAnswerIsTheSameAtEveryEraTheServerCanReport() {
        for (final long era : new long[] { 1L, 2L, 9L, 4096L }) {
            assertTrue("server era " + era + " — there is no era at which a re-create under a "
                            + "contextId the server already holds is applied",
                    MlsReestablishPolicy.reCreateWouldNotTake(true,
                            MlsReestablishPolicy.classify(true, era), true));
        }
    }

    /**
     * The three ways it must NOT fire. A predicate that answers true too often blocks the first
     * encryption of a conversation, and one of these would remove the only recovery a 1:1 has.
     */
    @Test
    public void theReCreatePredicateDoesNotFireOnTheCasesThatAreFine() {
        assertFalse("a 1:1 rebuild deletes the provider record and the resolver then MINTS a fresh "
                        + "contextId — that accident IS the proven recovery, and refusing it "
                        + "would take away the only repair a 1:1 that lost its group has",
                MlsReestablishPolicy.reCreateWouldNotTake(/*isGroup=*/ false,
                        MlsReestablishPolicy.classify(true, 3L), /*contextIdIsReused=*/ true));
        assertFalse("the server holds nothing here, so there is no contextId of ours for it to "
                        + "have seen and this is a real FIRST create",
                MlsReestablishPolicy.reCreateWouldNotTake(true,
                        MlsReestablishPolicy.classify(true, -1L), true));
        assertFalse("the group contextId arm is off, so the resolver MAY mint. Not certainly "
                        + "reused is not certainly hopeless, and only the certain case is refused",
                MlsReestablishPolicy.reCreateWouldNotTake(true,
                        MlsReestablishPolicy.classify(true, 3L), /*contextIdIsReused=*/ false));
    }

    /**
     * SERVER_UNREADABLE is charged and NOT refused, for {@link #anUnreadableServerIsChargedButNotRefusedAsAFork}'s
     * reason exactly: blocking a rebuild on an unmeasured server state would block the first
     * encryption of a group whose server read merely blipped.
     */
    @Test
    public void anUnreadableServerIsNotRefusedAsANonTakingReCreate() {
        final MlsReestablishPolicy.Verdict v = MlsReestablishPolicy.classify(false, -1L);
        assertTrue("still charged", v.chargesEraBudget());
        assertFalse("but not refused: we did not measure that the server holds anything",
                MlsReestablishPolicy.reCreateWouldNotTake(true, v, true));
    }

    /**
     * The refusal line must carry the two things a reader acts on: what was measured, and the way
     * out. A refusal that names neither is the dead end this is about.
     */
    @Test
    public void theWouldNotTakeLineNamesTheMeasurementAndTheWayOut() {
        final String line = MlsReestablishPolicy.wouldNotTakeLine(7L);
        assertTrue(line, line.contains("era 7"));
        assertTrue("it must say the contextId is the reason, not the era: a reader who takes this "
                        + "for an era problem will go and change the era — " + line,
                line.contains("contextId"));
        assertTrue("it must kill the carry theory explicitly, because that theory is what let this "
                        + "arm run — " + line, line.contains("carry"));
        assertTrue("nothing may be dropped or charged, and that IS the behaviour change — " + line,
                line.contains("BEFORE anything is dropped or charged"));
        assertTrue("and it must name the exit, or this is a dead end with a tidy log — " + line,
                line.contains("re-admitting us"));
        assertTrue("including the operator lever, which is the only client-side one there is — "
                        + line, line.contains("debug.rcs.mls_ctxid_group_engine_id"));
    }

        /**
     * The re-establish cooldown Stage 6 moved here, at its shipping value.
     *
     * <p>Ten minutes, and the assertion that matters is the second one: this window bounds how often
     * we CLAIM A PEER'S KEYPACKAGE, so it must stay long enough that a burst of undecryptable
     * inbound cannot drain a small pool. A minute would not.
     */
    @Test
    public void theCooldownIsTenMinutes() {
        assertEquals(10L * 60L * 1000L, MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS);
        assertTrue("a cooldown this short stops bounding the peer's one-time pool",
                MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS >= 5L * 60L * 1000L);
    }

}
