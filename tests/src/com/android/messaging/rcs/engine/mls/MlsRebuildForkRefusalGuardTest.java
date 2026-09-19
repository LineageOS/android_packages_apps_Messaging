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
 * <b>A carry-less group rebuild over a group the SERVER holds must be refused BEFORE anything is
 * destroyed or charged</b>.
 *
 * <h2>The state this pins, measured rather than reasoned</h2>
 *
 * <p>{@code deviceC}, RCS group {@code b1189d9cfcdc446ab117f38a6adcedd2}, 2026-09-08:
 * {@code mls_rebuild_limiter.xml} charged the automatic rebuild at {@code 17:49:39.407} and the
 * engine wrote {@code g_6231313839643963….bin} at {@code 17:49:43.580} — and has not written it
 * since. The server holds that group at era 1; the pack carried no GroupInfo; {@code plan_group}
 * took its ARM 1 ("nothing local, nothing carried") and the new group was born at
 * {@code ERA_INITIAL}. {@code establishGroup}'s post-create confirmation compares era NUMBERS
 * ({@code after[0] != targetEra}) — {@code 1 != 1} is false — so the create was ADOPTED, and the
 * epoch-authenticator check that follows can only report {@code DIFFERS} after the adoption has
 * already happened. The device has held a complete three-member era-1 group the server has no trace
 * of ever since, itself at leaf 0 of its own tree and leaf 2 of the server's.
 *
 * <h2>Why a source guard and not a unit test</h2>
 *
 * <p>The decision is pure and is unit-tested where it lives ({@code MlsReestablishPolicyTest}). What
 * that cannot show is that {@code rebuildConversation} ASKS, and asks EARLY — a predicate consulted
 * after {@code mlsForgetGroupConversation} would refuse a rebuild whose state it had already
 * destroyed, which is strictly worse than not asking. Ordering is the property, and the transport is
 * an Android class with a dozen collaborators, so the property is asserted over its source.
 *
 * <h2>The negative control</h2>
 *
 * <p>{@link #theGuardCanFail} takes the REAL body and breaks it two ways — remove the consultation,
 * and move it below the forget — and asserts {@link #orderingFault} reports each. Without that, this
 * class would be a check whose output is the same whether or not the property holds, which is the
 * shape this project keeps catching.
 */
public final class MlsRebuildForkRefusalGuardTest {

    /** The consultation this guard exists to pin. */
    private static final String ASK = "MlsReestablishPolicy.forksAtEraInitial(";

    /** What it must answer with. */
    private static final String ANSWER = "return MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL;";

    /**
     * Everything that must happen AFTER the ask, and why each one is on the list.
     *
     * <p>The first two DESTROY state — a refusal below them is a refusal of a rebuild whose local
     * halves are already gone. The last two SPEND a peer-facing allowance and our own rate bound on
     * a rebuild that is not going to run; {@code RATE_LIMITED} then reaches a person with a
     * {@code Try again} that cannot change what stopped us.
     */
    private static final String[] MUST_FOLLOW = {
        ".mlsForgetGroupConversation(",
        "forget(rcsGroupId, peerE164);",
        "MlsPeerGuard.allowEraAdvance(",
        "rebuildLimiter().claim(",
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
     *     anything, else the first violation found, in the wording a reader needs.
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

    /** Production holds the property. */
    @Test
    public void theRefusalComesBeforeAnythingIsDestroyedOrCharged() throws Exception {
        final String fault = orderingFault(rebuildBody());
        if (fault != null) fail(fault);
    }

    /**
     * THE CONTROL. Break the real body two ways and require the guard to notice each.
     *
     * <p>Both mutations are applied to the production text rather than to a hand-written sample, so
     * what is proven is that this guard would catch the regression on the code it actually watches.
     */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = rebuildBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                orderingFault(body));

        // (1) The consultation removed entirely.
        final String noAsk = body.replace(ASK, "neverAsked(");
        final String faultA = orderingFault(noAsk);
        assertNotNull("deleting the consultation must fail this guard", faultA);
        assertTrue("and must say the consultation is missing: " + faultA,
                faultA.contains("never calls"));

        // (2) The consultation MOVED below the forget — the version that refuses a rebuild whose
        // local state it has already dropped. Re-inserted as a bare call so the mutation tests the
        // ORDER and nothing else.
        final String moved = noAsk.replace("forget(rcsGroupId, peerE164);",
                "forget(rcsGroupId, peerE164); if (" + ASK + "a, b, c)) { " + ANSWER + " }");
        final String faultB = orderingFault(moved);
        assertNotNull("moving the consultation below the forget must fail this guard", faultB);
        assertTrue("and must say WHICH call it now follows: " + faultB,
                faultB.contains("BEFORE it asks"));
    }

    /**
     * The predicate and the outcome are the engine's, not the transport's.
     *
     * <p>Stated as a test rather than as a convention because the whole point of the plan
     * is that a decision living in the transport is a decision no host test can reach — which is how
     * this one survived a month.
     */
    @Test
    public void theDecisionLivesInTheEngine() {
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsReestablishPolicy.class.getPackage().getName());
        assertEquals("com.android.messaging.rcs.engine.mls",
                MlsRebuildOutcome.class.getPackage().getName());
    }
}
