/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MlsReestablishPolicy}: whether a re-establish is charged, and when it is refused. */
public final class MlsReestablishPolicyTest {

    /**
     * Anything that is not an observed "nobody re-joins" is charged; the flags are declared
     * independently.
     */
    @Test
    public void everyVerdictThatCanMakeAPeerReJoinIsCharged() {
        for (final MlsReestablishPolicy.Verdict v : MlsReestablishPolicy.Verdict.values()) {
            final boolean mustBeFree = v.reJoin() == MlsReestablishPolicy.ReJoin.NO;
            assertEquals(v + " declares reJoin=" + v.reJoin() + " but chargesEraBudget="
                    + v.chargesEraBudget() + ". Only a certain 'nobody re-joins' may be free: "
                    + "every other outcome makes a peer re-join by Welcome, or might, and that is "
                    + "the cost MlsPeerGuard's era budget (G2) bounds.",
                    !mustBeFree, v.chargesEraBudget());
        }
    }

    /** "Not answered" and "answered, holds nothing" never share an outcome. */
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
        // ERA_INITIAL is a real era; anything below it is "no group".
        assertEquals(MlsReestablishPolicy.Verdict.RECREATES_EXISTING,
                MlsReestablishPolicy.classify(true, MlsReestablishPolicy.ERA_INITIAL));
        assertEquals(MlsReestablishPolicy.Verdict.FIRST_CREATE,
                MlsReestablishPolicy.classify(true, MlsReestablishPolicy.ERA_INITIAL - 1));
    }

    /** A 1:1 rebuild and a REJOIN group have no carry yet re-create; the carry is not an input. */
    @Test
    public void aCarrylessRebuildOverAHeldConversationIsStillCharged() {
        // 1:1 rebuild: no pack is fetched, so no carry; the server holds the conversation at era 7.
        assertTrue("a 1:1 rebuild has no carry and re-creates anyway",
                MlsReestablishPolicy.classify(true, 7L).chargesEraBudget());
        // REJOIN: no local group to ask with, so no carry; the server holds the group at era 3.
        assertTrue("a REJOIN rebuild has no carry and re-creates anyway",
                MlsReestablishPolicy.classify(true, 3L).chargesEraBudget());
    }

    /**
     * The fork predicate needs the carry: with nothing local or carried the engine builds at
     * {@code ERA_INITIAL}, beside the server's group.
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

    /** Where it must not fire; firing too often blocks a first encryption. */
    @Test
    public void theForkPredicateDoesNotFireOnTheCasesThatAreFine() {
        assertFalse("a carry makes plan_group take ARM 2 and derive server_era + 1, so this is not "
                        + "a FORK at ERA_INITIAL. It is ALSO not a repair: "
                        + "the era the "
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
     * SERVER_UNREADABLE is charged (an unobserved cost is not the cheap one) but not refused, which
     * would block a first encryption over a transient read failure.
     */
    @Test
    public void anUnreadableServerIsChargedButNotRefusedAsAFork() {
        final MlsReestablishPolicy.Verdict v = MlsReestablishPolicy.classify(false, -1L);
        assertEquals(MlsReestablishPolicy.Verdict.SERVER_UNREADABLE, v);
        assertTrue("still charged", v.chargesEraBudget());
        assertFalse("but not refused: we did not measure that the server holds anything",
                MlsReestablishPolicy.forksAtEraInitial(true, v, false));
    }

    /** The refusal says what it observed. */
    @Test
    public void theForkLineNamesTheServersEraAndTheEraWeWouldBeBornAt() {
        final String line = MlsReestablishPolicy.forkLine(4L);
        assertTrue(line, line.contains("era 4"));
        assertTrue("it must name the era the build would land at, not just the server's",
                line.contains("born at era " + MlsReestablishPolicy.ERA_INITIAL));
        assertTrue("and must say nothing was dropped, because that is the behaviour change",
                line.contains("BEFORE anything is dropped"));
    }

    /** The log line describes what was observed, not what was assumed. */
    @Test
    public void theLineSaysWhichAnswerItHad() {
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.RECREATES_EXISTING, 9L)
                .contains("era 9"));
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.SERVER_UNREADABLE, -1L)
                .contains("did not answer"));
        assertTrue(MlsReestablishPolicy.line(MlsReestablishPolicy.Verdict.FIRST_CREATE, -1L)
                .contains("not charged"));
    }

    // reCreateWouldNotTake: a re-create the server will not apply.

    /**
     * With a carry the fork predicate says proceed, yet a re-create under a held contextId is not
     * applied.
     */
    @Test
    public void aCarriedGroupReCreateOverAHeldGroupIsRefusedEvenThoughItIsNotAFork() {
        final MlsReestablishPolicy.Verdict held = MlsReestablishPolicy.classify(true, 1L);
        assertFalse(
                        "precondition — with a carry the FORK predicate says proceed, which is what let "
                        + "a group rebuild spend and destroy",
                MlsReestablishPolicy.forksAtEraInitial(/*isGroup=*/ true, held,
                        /*haveCarry=*/ true));
        assertTrue("and the create still goes out under the contextId the server holds, which "
                        + "is not applied — so this must be refused",
                MlsReestablishPolicy.reCreateWouldNotTake(/*isGroup=*/ true, held,
                        /*contextIdIsReused=*/ true));
    }

    /**
     * The carry decides the engine's era; the contextId decides whether the server applies the
     * create.
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

    /** Where it must not fire; one would remove the only recovery a 1:1 has. */
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

    /** SERVER_UNREADABLE is charged and not refused, for the same reason as the fork predicate. */
    @Test
    public void anUnreadableServerIsNotRefusedAsANonTakingReCreate() {
        final MlsReestablishPolicy.Verdict v = MlsReestablishPolicy.classify(false, -1L);
        assertTrue("still charged", v.chargesEraBudget());
        assertFalse("but not refused: we did not measure that the server holds anything",
                MlsReestablishPolicy.reCreateWouldNotTake(true, v, true));
    }

    /** The refusal line carries what was observed and the way out. */
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

    /** Ten minutes: bounds how often we claim a peer's KeyPackage, so a burst cannot drain it. */
    @Test
    public void theCooldownIsTenMinutes() {
        assertEquals(10L * 60L * 1000L, MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS);
        assertTrue("a cooldown this short stops bounding the peer's one-time pool",
                MlsReestablishPolicy.REESTABLISH_COOLDOWN_MS >= 5L * 60L * 1000L);
    }

}
