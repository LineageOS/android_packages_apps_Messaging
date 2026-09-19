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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * <b>A GROUP rebuild whose re-create the server will not apply must be refused BEFORE anything is
 * destroyed or charged, and must ask for the one repair that IS available</b>.
 *
 * <h2>The state this pins, measured rather than reasoned</h2>
 *
 * <p>{@code deviceB}, RCS group {@code 0144736a…}, 2026-09-11 23:09. The ladder detected
 * {@code DIVERGED} correctly — matching era and epoch with a DIFFERING epoch authenticator, which is
 * a requirement firing on hardware for the first time — and then:
 *
 * <pre>
 *   23:09:42.582  health(+15715550103) local era=1 epoch=7 · server era=1 epoch=7
 *   23:09:43.224  rebuild → RECREATES_EXISTING … charged to the era budget
 *   23:09:43.247  AUTOMATIC REBUILD … Dropping BOTH halves of our state and re-esta…
 *   23:09:43.291  MLS claim ledger charged a KeyPackage claim to GROUP_ESTABLISH
 *   23:09:44.020  establishGroup … the engine chose NEW_ERA_EXISTING_GROUP at era=2
 *   23:09:44.614  rebuild … → ensureReady=false serverState=UNKNOWN — did NOT converge
 *   23:10:29.211  no local group but the server has one (era=1 epoch=7) → REJOIN
 * </pre>
 *
 * <p><b>The server was at era 1 epoch 7 before the rebuild and at era 1 epoch 7 after it.</b> The
 * create asked for era 2 and was not applied — measured 8/8, reaching this
 * arm. What the rebuild actually achieved was to destroy the only state that made the conversation
 * repairable by anything cheaper, spend a peer KeyPackage and an era-budget charge, and land in
 * {@code REJOIN} awaiting a Welcome only a peer can send. The charge is per ATTEMPT: a second
 * attempt at 23:11:22 logged the identical era-budget line.
 *
 * <p>{@link MlsReestablishPolicy#forksAtEraInitial} did not catch it because a GroupInfo carry WAS
 * in hand, and that predicate answers false the moment there is one.
 *
 * <h2>The 23 milliseconds that make this an ORDERING defect</h2>
 *
 * <p>{@code RECREATES_EXISTING} was printed at {@code .224} and the state dropped at {@code .247}.
 * The arm had every input the refusal needs 23 ms before it spent anything. Nothing had to be
 * discovered; something had to be asked earlier.
 *
 * <h2>Why a source guard and not a unit test</h2>
 *
 * <p>Same reason as {@link MlsRebuildForkRefusalGuardTest}, which watches the sibling predicate: the
 * decision is pure and is unit-tested where it lives ({@code MlsReestablishPolicyTest}). What that
 * cannot show is that {@code rebuildConversation} ASKS, and asks EARLY. A refusal below
 * {@code mlsForgetGroupConversation} refuses a rebuild whose state it has already destroyed, which
 * is strictly worse than not asking at all. Ordering is the property.
 *
 * <h2>The way out is part of the property</h2>
 *
 * <p>{@link #WAY_OUT} is asserted, not just the refusal. "Stranded and quiet" is an acceptable
 * outcome only if it is a CHOSEN state with an exit; a refusal that returns and says nothing to
 * anybody is the end of a ladder, which is the half of this that is not about cost.
 * {@code establishGroup} already sends this exact ask — but only from its accepted-and-discarded
 * arm, i.e. after the forgets, the claim and the charge have all happened.
 */
public final class MlsRebuildReCreateRefusalGuardTest {

    /** The consultation this guard exists to pin. */
    private static final String ASK = "MlsReestablishPolicy.reCreateWouldNotTake(";

    /** What it must answer with. */
    private static final String ANSWER = "return MlsRebuildOutcome.RECREATE_WOULD_NOT_TAKE;";

    /** The exit the refusal must offer, and it must be offered before the refusal returns. */
    private static final String WAY_OUT = "requestReWelcome(";

    /**
     * Everything that must happen AFTER the ask, and why each one is on the list.
     *
     * <p>The first two DESTROY state — the 010T run's {@code Deleted group state from MlsGroupStates
     * table} and {@code forgot group … → engine=true}. The next two SPEND: {@code allowEraAdvance}
     * is the era budget the run's own log says it charged, and {@code rebuildLimiter().claim} is the
     * {@code charging rebuild 1/3} line. The last is the one that takes a peer's one-time
     * KeyPackage, which no budget of ours refills.
     */
    private static final String[] MUST_FOLLOW = {
        ".mlsForgetGroupConversation(",
        "forget(rcsGroupId, peerE164);",
        "MlsPeerGuard.allowEraAdvance(",
        "rebuildLimiter().claim(",
        "establishGroup(rcsGroupId, roster, carry)",
    };

    private static String rebuildBody() throws Exception {
        final String body = SourceScan.bodyOf(
                SourceScan.transport(), "rebuildConversation");
        assertTrue("rebuildConversation was not found in " + SourceScan.TRANSPORT
                + " — if it was renamed, this guard must follow it rather than silently pass",
                body.length() > 500);
        return body;
    }

    /**
     * @return {@code null} when the body consults the predicate before it destroys or spends
     *     anything and offers the exit, else the first violation found, in the wording a reader
     *     needs.
     */
    static String orderingFault(final String body) {
        final int ask = body.indexOf(ASK);
        if (ask < 0) {
            return "rebuildConversation never calls " + ASK + ". A GROUP re-create goes out under "
                    + "the contextId the server already holds and is not applied at any era "
                    + "(measured 8/8); running it anyway drops both halves, spends a KeyPackage "
                    + "and an era-budget charge, and lands in REJOIN. The "
                    + "carry-less FORK refusal above is a different predicate and does not cover "
                    + "this — it answers false the moment a carry is in hand.";
        }
        if (body.indexOf(ASK, ask + 1) >= 0) {
            return "rebuildConversation calls " + ASK + " more than once — two asks are two "
                    + "decisions, and the second one is the one that goes stale.";
        }
        if (!body.contains(ANSWER)) {
            return "rebuildConversation consults " + ASK + " and does not return "
                    + "RECREATE_WOULD_NOT_TAKE. An answer that is not acted on is not a guard.";
        }
        final int wayOut = body.indexOf(WAY_OUT);
        if (wayOut < 0 || wayOut < ask) {
            return "rebuildConversation's refusal does not call " + WAY_OUT + " after " + ASK
                    + ". Refusing is half of it: the conversation still needs a member who IS "
                    + "current to re-admit it, and asking is the only lever we have. A refusal "
                    + "that returns in silence is the dead end this is about, not the fix "
                    + "for it.";
        }
        for (final String later : MUST_FOLLOW) {
            final int at = body.indexOf(later);
            if (at < 0) {
                return "rebuildConversation no longer contains " + later + " — this guard's "
                        + "ordering claim is about that call and cannot be checked without it.";
            }
            if (at < ask) {
                return "rebuildConversation reaches " + later + " BEFORE it asks " + ASK
                        + ". The refusal must come first: below the forgets it refuses a rebuild "
                        + "whose state is already destroyed, and below the charges it spends a "
                        + "budget, a rate slot and a peer's one-time KeyPackage on a create the "
                        + "server will not apply.";
            }
            if (at < wayOut) {
                return "rebuildConversation reaches " + later + " BEFORE it asks for a re-Welcome. "
                        + "The ask is the refusal's exit and must be offered from the intact "
                        + "conversation; issued after the forgets it is the ask establishGroup's "
                        + "accepted-and-discarded arm already makes, which is the behaviour this "
                        + "change is replacing.";
            }
        }
        return null;
    }

    /** Production holds the property. */
    @Test
    public void theRefusalComesBeforeAnythingIsDestroyedOrCharged() throws Exception {
        final String fault = orderingFault(rebuildBody());
        if (fault != null) fail(fault);
    }

    /**
     * THE CONTROL. Break the real body three ways and require the guard to notice each.
     *
     * <p>Applied to the production text rather than to a hand-written sample, so what is proven is
     * that this guard would catch the regression on the code it actually watches. Without it this
     * class would be a check whose output is the same whether or not the property holds.
     */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = rebuildBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                orderingFault(body));

        // (1) The consultation removed entirely — the state this was written against.
        final String noAsk = body.replace(ASK, "neverAsked(");
        final String faultA = orderingFault(noAsk);
        assertNotNull("deleting the consultation must fail this guard", faultA);
        assertTrue("and must say the consultation is missing: " + faultA,
                faultA.contains("never calls"));

        // (2) The consultation MOVED below the forget — the version that refuses a rebuild whose
        // local state it has already dropped, which is the 010T outcome with an extra log line.
        final String movedBase = noAsk.replace(WAY_OUT, "didNotAsk(");
        final String moved = movedBase.replace("forget(rcsGroupId, peerE164);",
                "forget(rcsGroupId, peerE164); if (" + ASK + "a, b, c)) { " + WAY_OUT + "x, y, z); "
                        + ANSWER + " }");
        final String faultB = orderingFault(moved);
        assertNotNull("moving the consultation below the forget must fail this guard", faultB);
        assertTrue("and must say WHICH call it now follows: " + faultB,
                faultB.contains("BEFORE it asks"));

        // (3) The refusal kept, the EXIT dropped. This is the mutation that looks harmless — the
        // cost is bounded, nothing is destroyed, and the conversation is stranded for good with a
        // tidy log. Requirement 4 is that the quiet state be a chosen one with a way
        // out, so the guard has to be able to tell the two apart.
        final String silent = body.replace(WAY_OUT, "didNotAsk(");
        final String faultC = orderingFault(silent);
        assertNotNull("dropping the re-Welcome ask must fail this guard", faultC);
        assertTrue("and must say the exit is missing rather than complaining about ordering: "
                + faultC, faultC.contains("does not call"));
    }

    /**
     * The predicate, the outcome and the refusal sentence are the engine's, not the transport's.
     *
     * <p>Stated as a test for {@link MlsRebuildForkRefusalGuardTest}'s reason: a decision living in
     * the transport is a decision no host test can reach, which is how the sibling defect survived
     * a month.
     */
    @Test
    public void theDecisionLivesInTheEngine() {
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsReestablishPolicy.class.getPackage().getName());
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsRebuildOutcome.class.getPackage().getName());
        assertTrue("the refusal sentence must be the engine's too, or the argument drifts back "
                + "into the transport where nothing can assert it",
                MlsReestablishPolicy.wouldNotTakeLine(1L).contains("contextId"));
    }
}
