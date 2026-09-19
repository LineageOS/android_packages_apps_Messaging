/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A look costs no self-heal attempt. Whether it does is the order of {@code relookLiveYield} and
 * {@code chargeSelfHealBudget} in {@code selfHealInner}; this guard derives the charge from that
 * order and feeds it to {@code MlsAdvancerElection.takeoverReachableWithin}. The engine-side half
 * is {@code MlsSelfHealPassTest}. See docs/mls/health-and-recovery.md.
 */
public final class MlsSelfHealChargeGuardTest {

    /** The pass classifier: does a live-yield re-look return before the budget is charged? */
    private static final String RELOOK = "relookLiveYield(";
    /** The charge against {@code MlsConfig.selfHealRetryLimit}. */
    private static final String CHARGE = "chargeSelfHealBudget(";
    /** The ladder-side evaluation of the same look. */
    private static final String LADDER_YIELD = "eraYieldExhausted(";
    /** The engine's look arithmetic, which both yield paths go through. */
    private static final String LOOK = "MlsSelfHealPass.look(";
    /** The exhaustion test; legitimate only in {@link #SELF_HEAL_PASS}. */
    private static final String EXHAUSTED = "eraYieldObservationsExhausted(";
    private static final String SELF_HEAL_PASS =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsSelfHealPass.java";

    /**
     * Moving {@code chargeSelfHealBudget} above the re-look's early return makes the derived
     * {@code enclosingAttempts} the retry limit instead of 0, and this test fails.
     */
    @Test public void theTakeoverStaysReachableForTheChargeProductionActuallyMakes()
            throws IOException {
        // as if unsplit: relookLiveYield moved out
        final String src = SourceScan.transportUnsplitCode();
        final int attemptsChargedPerLook = attemptsChargedPerLook(src);
        final int enclosing = attemptsChargedPerLook <= 0
                ? 0 : MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT;

        for (final Presence p : Presence.values()) {
            for (int rank = 1; rank <= 16; rank++) {
                assertTrue("A LOOK NOW COSTS " + attemptsChargedPerLook
                        + " SELF-HEAL ATTEMPT(S), so"
                        + " rank " + rank + " (ahead=" + p + ") can never reach its takeover inside"
                        + " a budget of " + MlsConfig.DEF_SELF_HEAL_RETRY_LIMIT + ". This is"
                        + " the defect exactly — at rank 3 of a group,"
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
     * The engine's charge table and the transport's control flow agree. Separate from the
     * reachability test so a failure says which side drifted.
     */
    @Test public void theEngineChargeTableAndTheTransportControlFlowHaveNotDrifted()
            throws IOException {
        // as if unsplit: relookLiveYield moved out
        final String src = SourceScan.transportUnsplitCode();
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
     * Each name has exactly one invocation in the transport, inside {@code selfHealInner}; a second
     * charge on the look path would defeat the ordering check.
     */
    @Test public void theChargeAndTheRelookAreEachInvokedExactlyOnceAndBothFromSelfHealInner()
            throws IOException {
        // as if unsplit: relookLiveYield moved out
        final String src = SourceScan.transportUnsplitCode();
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
     * Both yield paths still exist, are reached from one pass, and neither increments
     * {@code EraYield.observations} in place, which is how a look gets recorded twice.
     */
    @Test public void neitherYieldPathIncrementsTheLookCounterInPlace() throws IOException {
        // as if unsplit: relookLiveYield moved out
        final String src = SourceScan.transportUnsplitCode();
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
     * The exhaustion arithmetic has one home, {@code MlsSelfHealPass.look}: it is invoked at least
     * twice in the transport, and {@code eraYieldObservationsExhausted(} zero times there and
     * exactly once in {@code MlsSelfHealPass}, so a stale needle fails rather than reporting the
     * transport clean.
     */
    @Test public void theExhaustionArithmeticHasExactlyOneHome() throws IOException {
        final String src = SourceScan.transportUnsplitCode();   /**
 * {@code MlsSelfHealPass.look} is the one home of the exhaustion arithmetic: both yield paths call
 * it, and the transport never calls {@code eraYieldObservationsExhausted(} itself.
 */
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
     * How many attempts one look charges, read from {@code selfHealInner}: zero when a
     * {@code return} sits between the re-look and the charge.
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
