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
 * A group rebuild that carries no GroupInfo over a group the server holds is refused before
 * anything is destroyed or charged. Such a rebuild is born at {@code ERA_INITIAL}; the post-create
 * check compares era numbers only, so it would adopt a fork the server has no trace of.
 *
 * <p>The decision is unit-tested in {@code MlsReestablishPolicyTest}; this guard pins that
 * {@code rebuildConversation} asks it before the forgets and the charges. {@link #theGuardCanFail}
 * is the negative control. See docs/testing.md.
 */
public final class MlsRebuildForkRefusalGuardTest {

    private static final String ASK = "MlsReestablishPolicy.forksAtEraInitial(";

    private static final String ANSWER = "return MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL;";

    /**
     * Calls that must come after the ask: the first two destroy local state, the last two spend a
     * peer allowance and our rate bound on a rebuild that will not run.
     */
    private static final String[] MUST_FOLLOW = {
        ".mlsForgetGroupConversation(",
        "forget(rcsGroupId, peerE164);",
        "MlsPeerGuard.allowEraAdvance(",
        "rebuildLimiter().claim(",
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
     * Null when the body asks the predicate before it destroys or spends anything, else the first
     * violation.
     */
    static String orderingFault(final String body) {
        final int ask = body.indexOf(ASK);
        if (ask < 0) {
            return "rebuildConversation never calls " + ASK + ". A carry-less re-establish over a "
                    + "group the server holds is born at ERA_INITIAL and forks; the "
                    + "no-carry WARNING further down is not a refusal.";
        }
        if (body.indexOf(ASK, ask + 1) >= 0) {
            return "rebuildConversation calls " + ASK + " more than once — two asks are two "
                    + "decisions, and the second one is the one that goes stale.";
        }
        if (!body.contains(ANSWER)) {
            return "rebuildConversation consults " + ASK + " and does not return "
                    + "WOULD_FORK_AT_ERA_INITIAL. An answer that is not acted on is not a guard.";
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
                        + "budget on a rebuild that never runs.";
            }
        }
        return null;
    }

    @Test
    public void theRefusalComesBeforeAnythingIsDestroyedOrCharged() throws Exception {
        final String fault = orderingFault(rebuildBody());
        if (fault != null) fail(fault);
    }

    /** Breaks the production body two ways and requires the guard to notice each. */
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

        // (2) The consultation moved below the forget, re-inserted as a bare call so only the order
        // changes.
        final String moved = noAsk.replace("forget(rcsGroupId, peerE164);",
                "forget(rcsGroupId, peerE164); if (" + ASK + "a, b, c)) { " + ANSWER + " }");
        final String faultB = orderingFault(moved);
        assertNotNull("moving the consultation below the forget must fail this guard", faultB);
        assertTrue("and must say WHICH call it now follows: " + faultB,
                faultB.contains("BEFORE it asks"));
    }

    /** The predicate and the outcome live in the engine, where a host test can reach them. */
    @Test
    public void theDecisionLivesInTheEngine() {
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsReestablishPolicy.class.getPackage().getName());
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsRebuildOutcome.class.getPackage().getName());
    }
}
