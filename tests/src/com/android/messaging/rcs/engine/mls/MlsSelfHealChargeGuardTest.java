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
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAdvancerElection.Presence;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * <b>A LOOK COSTS NOTHING, read out of production and fed to the predicate</b> —
 * Stage 4, invariant I1 with I5's argument rule.
 *
 * <h2>What this file is for, in one paragraph</h2>
 *
 * <p>{@code MlsAdvancerElection.takeoverReachableWithin} reconciles two budgets that count the same
 * events on different axes: the election's look budget ({@code presenceLooks + (rank-1) x base} =
 * 3 / 6 / 9) and the enclosing self-heal budget ({@code selfHealRetryLimit} = 5). We
 * found the smaller pre-empting the larger, so the takeover was dead code at rank &gt;= 2. The fix
 * was to stop charging an attempt for a look. <b>The production fact behind that fix is the ORDER OF
 * TWO STATEMENTS</b> in {@code MlsProviderTransport.selfHealInner} — {@code relookLiveYield(key)}
 * returns early ABOVE {@code chargeSelfHealBudget(key)} — and until this file existed, moving the
 * charge up left all 1,154 host tests green.
 *
 * <p>So this guard READS THAT ORDER and DERIVES the per-look charge from it, then hands the derived
 * value to the predicate as its {@code enclosingAttempts} argument. That is the whole of I5: the
 * assertion's argument comes from production rather than from a literal a person typed.
 *
 * <h2>How it meets the source-scan caution</h2>
 *
 * <p>A source scan encodes a spelling. The mitigations, stated so the
 * next reader can check them rather than trust them:
 *
 * <ul>
 *   <li><b>Keys on INVOKED METHOD NAMES only</b> — {@code relookLiveYield(} and
 *       {@code chargeSelfHealBudget(}. No receiver, no local variable, no log text. Renaming either
 *       method and re-linking its callers is a compile-checked refactor that this guard will notice
 *       and report as a missing call site, which is the correct outcome: the reconciliation moved
 *       and somebody must say where to.</li>
 *   <li><b>Zero hits FAIL.</b> Each name is required to have exactly ONE invocation in the whole
 *       transport, and that invocation is required to be inside {@code selfHealInner}. A pattern
 *       that has gone stale reports zero and fails here rather than passing quietly.</li>
 *   <li><b>The single-call-site requirement is itself load-bearing</b>, not tidiness: a SECOND
 *       {@code chargeSelfHealBudget} call reachable on the look path would restore the collision
 *       while the statement order below still read correctly.</li>
 * </ul>
 *
 * <p>The complementary half — a per-look cost reintroduced through the engine's charge table rather
 * than through statement order — is {@code MlsSelfHealPassTest}. Neither file can catch the other's
 * mutation, which is why both exist.
 */
public final class MlsSelfHealChargeGuardTest {

    /** The pass classifier: does a live yield re-look short-circuit before the budget is charged? */
    private static final String RELOOK = "relookLiveYield(";
    /** The charge against {@code MlsConfig.selfHealRetryLimit}. */
    private static final String CHARGE = "chargeSelfHealBudget(";
    /** The ladder-side evaluation of the same look. */
    private static final String LADDER_YIELD = "eraYieldExhausted(";
    /** The engine's single look arithmetic — the one both yield paths must go through. */
    private static final String LOOK = "MlsSelfHealPass.look(";
    /** The exhaustion test itself. Legitimate in exactly one place, and it is not the transport. */
    private static final String EXHAUSTED = "eraYieldObservationsExhausted(";
    /** Where that one place is. */
    private static final String SELF_HEAL_PASS =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsSelfHealPass.java";

    /**
     * <b>The mutation gate (plan G5).</b> Re-introduce the per-look attempt charge — by moving
     * {@code chargeSelfHealBudget} above the re-look's early return — and this test fails by name.
     *
     * <p>It fails because the derived {@code enclosingAttempts} becomes the shipped retry limit
     * instead of 0, and {@code takeoverReachableWithin} then answers false for every rank &gt;= 2:
     * exactly the state measured on a device.
     */
    @Test public void theTakeoverStaysReachableForTheChargeProductionActuallyMakes() throws IOException {
        final String src = SourceScan.transport();
        final int attemptsChargedPerLook = attemptsChargedPerLook(src);
        final int enclosing = attemptsChargedPerLook <= 0
                ? 0 : MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT;

        for (final Presence p : Presence.values()) {
            for (int rank = 1; rank <= 16; rank++) {
                assertTrue("A LOOK NOW COSTS " + attemptsChargedPerLook + " SELF-HEAL ATTEMPT(S), so"
                        + " rank " + rank + " (ahead=" + p + ") can never reach its takeover inside"
                        + " a budget of " + MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT + ". This is"
                        + " the defect exactly — measured on a device at rank 3 of a group,"
                        + " where the yield ran to 'self-heal budget EXHAUSTED (retry limit 5)' and"
                        + " the 'TAKING OVER' line never appeared. Do NOT fix it by capping the"
                        + " takeover budget: that forces distinct ranks onto one value and two"
                        + " members holding the same budget expire together and both advance, which"
                        + " is the duel the election exists to prevent. Restore the ordering in"
                        + " selfHealInner — the re-look must return BEFORE the charge.",
                        MlsAdvancerElection.takeoverReachableWithin(
                                rank, p, MlsConfig.DEF_ERA_YIELD_LOOKS, enclosing));
            }
        }
    }

    /**
     * The engine's charge table and the transport's control flow must answer the same question the
     * same way.
     *
     * <p>Separate from the reachability assertion above because it catches the mutation that one
     * cannot: a per-look cost reintroduced in BOTH places consistently. That leaves the arithmetic
     * self-consistent and every rank unreachable, so the test above would fail for the right reason
     * — but a per-look cost added to the engine table ALONE, with the transport still short-
     * circuiting, would make the reachability assertion fail while production was fine. Naming the
     * drift separately is what tells those two apart in a failure message.
     */
    @Test public void theEngineChargeTableAndTheTransportControlFlowHaveNotDrifted()
            throws IOException {
        final String src = SourceScan.transport();
        final int attemptsChargedPerLook = attemptsChargedPerLook(src);
        assertEquals("the engine's charge table (MlsSelfHealPass.Kind.FREE_LOOK) says a look costs "
                        + MlsSelfHealPass.Kind.FREE_LOOK.selfHealAttemptsCharged
                        + " attempt(s), while MlsProviderTransport.selfHealInner charges "
                        + attemptsChargedPerLook + ". One of them has moved without the other, so "
                        + "neither is authoritative about what a look costs.",
                MlsSelfHealPass.enclosingSelfHealAttempts(MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT),
                attemptsChargedPerLook <= 0 ? 0 : MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT);
    }

    /**
     * The derivation itself, asserted separately so a failure says WHICH half moved.
     *
     * <p>Both names must have exactly one invocation in the transport and both must be inside
     * {@code selfHealInner}. That is the "zero hits fail" rule and the second-door rule in one:
     * a charge reachable from anywhere else on the look path would defeat the ordering check below.
     */
    @Test public void theChargeAndTheRelookAreEachInvokedExactlyOnceAndBothFromSelfHealInner()
            throws IOException {
        final String src = SourceScan.transport();
        final List<int[]> decls = SourceScan.declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        for (final String call : new String[] {RELOOK, CHARGE}) {
            final List<Integer> at = SourceScan.invocationsOf(src, decls, call);
            assertEquals(call + " is invoked " + at.size() + " time(s) in MlsProviderTransport. "
                    + "Exactly one is required: zero means this guard has gone stale and is passing "
                    + "for the wrong reason, and more than one means the look/charge ordering below "
                    + "no longer decides what a look costs — a second charge on the look path "
                    + "restores the budget collision while the ordering still reads correctly.",
                    1, at.size());
            assertEquals(call + " is no longer invoked from selfHealInner. The reconciliation "
                    + "between the election's look budget and the self-heal budget lives in that "
                    + "method's control flow; if it has moved, this guard must move with it.",
                    "selfHealInner",
                    SourceScan.enclosingMethod(src, decls, at.get(0).intValue()));
        }
    }

    /**
     * The look-ledger half — Stage 0's second instance of this shape,
     * found inside the earlier fix.
     *
     * <p>{@code relookLiveYield} and {@code eraYieldExhausted} both evaluate one pass, and what kept
     * them in step was a comment. This asserts the two things a comment cannot: that both paths
     * still exist and are reachable from one pass, and that neither has grown a second increment.
     *
     * <p>Keyed on {@code ++} against the counter's own field name rather than on prose. The field is
     * {@code EraYield.observations}; the mutation this catches is a path incrementing it directly
     * instead of deriving the next value, which is how "record the look twice" gets reintroduced.
     */
    @Test public void neitherYieldPathIncrementsTheLookCounterInPlace() throws IOException {
        final String src = SourceScan.transport();
        assertEquals("eraYieldExhausted is no longer invoked from the ladder — the pass that "
                + "re-runs the re-look's arithmetic is gone and this guard means nothing",
                1, SourceScan.invocationsOf(src, SourceScan.declarations(src),
                        LADDER_YIELD).size());
        for (final String bad : new String[] {"observations++", "++observations",
                "observations +=", ".observations++"}) {
            assertEquals("MlsProviderTransport contains '" + bad + "'. The era yield's look count "
                    + "is advanced by CONSTRUCTING a new EraYield from a value both paths derive "
                    + "identically, never by mutating the held one — relookLiveYield and "
                    + "eraYieldExhausted evaluate the SAME pass, and an in-place increment on "
                    + "either makes them disagree and takes over a look early.",
                    0, SourceScan.count(src, bad));
        }
    }

    /**
     * <b>The exhaustion arithmetic has exactly ONE home, and production is in it</b> —
     * catalogue row E2 pair 2.
     *
     * <p>{@code MlsSelfHealPass.look} was written in Stage 4 to be the single arithmetic both yield
     * paths share, and <b>production never called it</b>. {@code relookLiveYield} and
     * {@code eraYieldExhausted} each re-derived {@code observations + 1} and called
     * {@code MlsRecoveryPolicy.eraYieldObservationsExhausted} themselves, reconciled by a comment
     * saying "do not record the look here" — which is the same defect that was filed
     * one method along, and it was one grep from it for a day. Both paths route through
     * {@code MlsSelfHealPass.look} now.
     *
     * <p>Two halves, because either alone can be satisfied while the property is gone:
     *
     * <ul>
     *   <li><b>both callers still go through it</b> — {@code MlsSelfHealPass.look(} invoked at least
     *       twice in the transport. Zero or one FAILS: one is a path that has gone back to deriving
     *       its own answer, which is the state this closes.</li>
     *   <li><b>and nothing re-derives it</b> — {@code eraYieldObservationsExhausted(} invoked ZERO
     *       times in the transport. A negative needle proves nothing on its own, so the same needle
     *       is required to find its ONE legitimate site in {@code MlsSelfHealPass}: if the engine
     *       count is not 1 the needle or the locator is stale and this fails, rather than reporting
     *       the transport clean because it can no longer see anything.</li>
     * </ul>
     *
     * <p>Keyed on invoked method names throughout — never on a line, a receiver or a comment.
     */
    @Test public void theExhaustionArithmeticHasExactlyOneHome() throws IOException {
        final String src = SourceScan.transport();
        final List<int[]> decls = SourceScan.declarations(src);
        final int viaTheEngine = SourceScan.invocationsOf(src, decls, LOOK).size();
        assertTrue("MlsSelfHealPass.look is invoked " + viaTheEngine + " time(s) in "
                + "MlsProviderTransport; both yield paths must go through it. relookLiveYield and "
                + "eraYieldExhausted evaluate the SAME pass and must reach the identical verdict "
                + "from an unmoved counter — for the whole of Stages 4-7 what reconciled them was a "
                + "comment, and the function written to replace that comment had no production "
                + "caller at all. One invocation means a path has gone back to "
                + "deriving its own answer.", viaTheEngine >= 2);

        final String engine = SourceScan.codeOnly(SourceScan.read(SELF_HEAL_PASS));
        assertEquals("the needle '" + EXHAUSTED + "' no longer finds its one legitimate site in "
                + "MlsSelfHealPass, so the zero it reports for the transport below is a broken "
                + "locator rather than a clean transport. A probe that finds nothing proves nothing "
                + "until it has shown it can find something.",
                1, SourceScan.invocationsOf(engine, SourceScan.declarations(engine),
                        EXHAUSTED).size());
        assertEquals("MlsProviderTransport calls " + EXHAUSTED + " directly. The exhaustion test "
                + "and the look number are ONE arithmetic and it lives in MlsSelfHealPass.look; a "
                + "second copy in the transport is how the two yield paths came to disagree in the "
                + "first place. Call MlsSelfHealPass.look and read Look.number / Look.exhausted / "
                + "Look.recorded from it.",
                0, SourceScan.invocationsOf(src, decls, EXHAUSTED).size());
    }

    /**
     * How many self-heal attempts one LOOK charges, read out of {@code selfHealInner}.
     *
     * <p>A look is free exactly when the re-look's result short-circuits the method — i.e. there is
     * a {@code return} between the re-look invocation and the charge invocation. Without that
     * return the pass falls through to the charge whatever the re-look answered, which IS the
     * per-look cost.
     */
    private static int attemptsChargedPerLook(final String src) {
        final String body = SourceScan.bodyOf(src, "selfHealInner");
        assertTrue("MlsProviderTransport.selfHealInner is gone. It is where the election's look "
                + "budget and the self-heal budget are reconciled, and this guard cannot read the "
                + "reconciliation out of anywhere else.", body.length() > 0);
        final int relook = body.indexOf(RELOOK);
        final int charge = body.indexOf(CHARGE);
        assertTrue("selfHealInner no longer re-looks a live yield (" + RELOOK + ")", relook >= 0);
        assertTrue("selfHealInner no longer charges the self-heal budget (" + CHARGE + ")",
                charge >= 0);
        if (charge < relook) return 1;              // charged before the look was even classified
        final String between = body.substring(relook, charge);
        return between.contains("return") ? 0 : 1;  // no early exit: the look pays for the pass
    }
}
