/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A group rebuild whose re-create the server will not apply is refused before anything is destroyed
 * or charged, and asks for the one repair that is available: a re-Welcome from a peer.
 *
 * <p>Such a rebuild drops both halves of our state, spends a peer KeyPackage and an era-budget
 * charge on every attempt, and leaves the server's group unchanged. It carries a GroupInfo, so
 * {@link MlsReestablishPolicy#forksAtEraInitial} does not catch it. As with
 * {@link MlsRebuildForkRefusalGuardTest}, the decision is unit-tested in
 * {@code MlsReestablishPolicyTest} and this guard pins that {@code rebuildConversation} asks it
 * first, and that the refusal offers {@link #WAY_OUT} rather than stranding the conversation.
 */
public final class MlsRebuildReCreateRefusalGuardTest {

    /** The consultation this guard exists to pin. */
    private static final String ASK = "MlsReestablishPolicy.reCreateWouldNotTake(";

    /** What it must answer with. */
    private static final String ANSWER = "return MlsRebuildOutcome.RECREATE_WOULD_NOT_TAKE;";

    /** The exit the refusal must offer, and it must be offered before the refusal returns. */
    private static final String WAY_OUT = "requestReWelcome(";

    /**
     * Calls that must come after the ask: the first two destroy state, the next two spend the era
     * budget and the rebuild limiter, and the last takes a peer's one-time KeyPackage.
     */
    private static final String[] MUST_FOLLOW = {
        ".mlsForgetGroupConversation(",
        "forget(rcsGroupId, peerE164);",
        "MlsPeerGuard.allowEraAdvance(",
        "rebuildLimiter().claim(",
        "establishGroup(rcsGroupId, roster, carry)",
    };

    private static String rebuildBody() throws Exception {
        // The unsplit view restores rebuildConversation in its original spelling.
        final String body = SourceScan.bodyOf(
                SourceScan.transportUnsplitCode(), "rebuildConversation");
        assertTrue("rebuildConversation was not found in " + SourceScan.TRANSPORT
                + " — if it was renamed, this guard must follow it rather than silently pass",
                body.length() > 500);
        return body;
    }

    /**
     * Null when the body asks the predicate before it destroys or spends anything and offers the
     * exit, else the first violation.
     */
    static String orderingFault(final String body) {
        final int ask = body.indexOf(ASK);
        if (ask < 0) {
            return "rebuildConversation never calls " + ASK + ". A GROUP re-create goes out under "
                    + "the contextId the server already holds and is not applied at any era "
                    + "— running it anyway drops both halves, spends a KeyPackage "
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

    @Test
    public void theRefusalComesBeforeAnythingIsDestroyedOrCharged() throws Exception {
        final String fault = orderingFault(rebuildBody());
        if (fault != null) fail(fault);
    }

    /** Breaks the production body three ways and requires the guard to notice each. */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = rebuildBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                orderingFault(body));

        // (1) The consultation removed.
        final String noAsk = body.replace(ASK, "neverAsked(");
        final String faultA = orderingFault(noAsk);
        assertNotNull("deleting the consultation must fail this guard", faultA);
        assertTrue("and must say the consultation is missing: " + faultA,
                faultA.contains("never calls"));

        // (2) The consultation moved below the forget.
        final String movedBase = noAsk.replace(WAY_OUT, "didNotAsk(");
        final String moved = movedBase.replace("forget(rcsGroupId, peerE164);",
                "forget(rcsGroupId, peerE164); if (" + ASK + "a, b, c)) { " + WAY_OUT + "x, y, z); "
                        + ANSWER + " }");
        final String faultB = orderingFault(moved);
        assertNotNull("moving the consultation below the forget must fail this guard", faultB);
        assertTrue("and must say WHICH call it now follows: " + faultB,
                faultB.contains("BEFORE it asks"));

        // (3) The refusal kept, the exit dropped: bounded and non-destructive, but stranded for
        // good.
        final String silent = body.replace(WAY_OUT, "didNotAsk(");
        final String faultC = orderingFault(silent);
        assertNotNull("dropping the re-Welcome ask must fail this guard", faultC);
        assertTrue("and must say the exit is missing rather than complaining about ordering: "
                + faultC, faultC.contains("does not call"));
    }

    /**
     * The predicate, the outcome and the refusal sentence live in the engine, where a host test can
     * reach them.
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
