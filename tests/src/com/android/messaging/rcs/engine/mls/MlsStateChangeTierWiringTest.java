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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Guard;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Reason;
import com.android.messaging.rcs.engine.mls.MlsStateChangeGate.Tier;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>{@code MlsPeerGuard} asks the tier table rather than restating it, and every tier, guard and
 * reason the engine declares is wired</b> — Stage 2.
 *
 * <h2>Why this half is a source scan, and what it is careful not to be</h2>
 *
 * <p>{@link MlsStateChangeGateTest} asserts the table itself against real calls, which is where
 * everything provable by behaviour belongs. What it cannot reach is the WIRING: {@code MlsPeerGuard}
 * needs a {@code Context} and system properties, so no host test can call it, and the regression
 * this file exists for is a composition creeping back into an entry point — one {@code if} that
 * reads a sysprop before delegating, and the tier table is a value in the engine that production no
 * longer entirely obeys.
 *
 * <p>We have six recorded instances of a source guard that encoded a SPELLING and
 * reported green anyway, so this one holds to the rules those fixes settled on:
 *
 * <ul>
 *   <li><b>Key on the invoked method name</b>, never on a receiver, a label or a character
 *       distance. Every needle below is {@code name(} or an enum constant.</li>
 *   <li><b>Enumerate the subject from a checked-in ground truth</b>, never from a hand list. The
 *       entry points come from parsing {@code MlsPeerGuard}'s own declarations; the tiers, guards
 *       and reasons come from {@code values()} on the real enums, which are on this test's
 *       classpath. There is no list in this file that can go stale.</li>
 *   <li><b>Zero hits per needle FAILS.</b> Every assertion is a per-item lower bound, never a total
 *       — those instances all degrade to "found fewer than exist", and a scan that can
 *       silently find none is that bug at its limit.</li>
 * </ul>
 *
 * <p>Bodies are brace-matched over COMMENT-STRIPPED source ({@link SourceScan#codeOnly}),
 * which is
 * the fourth axis: a needle that a javadoc can satisfy proves nothing, and this file's
 * subject is a class whose comments quote every method name it discusses.
 */
public final class MlsStateChangeTierWiringTest {

    /**
     * The reading machinery is {@link SourceScan}'s, not a fifth copy of it. That class was
     * extracted while two guards were being written, with the note that "a third and
     * fourth copy is where the drift starts"; what stays local here is {@link #bodiesOf}, because
     * it answers a question the shared helper deliberately does not — see its javadoc.
     */
    private static final String GUARD_SRC = "src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java";
    private static final String TRANSPORT_SRC =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";
    private static final String RECEIVER_SRC =
            "src/com/android/messaging/rcs/RcsDebugSendReceiver.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /** {@code public static boolean allowSomething(} — the guard's entry points, from the source. */
    private static final Pattern ENTRY_POINT = Pattern.compile(
            "(?m)^    public static boolean (allow\\w+)\\s*\\(");

    /**
     * <b>Every entry point delegates, and none of them composes.</b>
     *
     * <p>The entry points are read out of {@code MlsPeerGuard} rather than listed here, so one added
     * later is covered on the day it is added — which is the half that was needed and did not
     * have: {@code allowJoiningPeer} was written as a third tier and nothing asked whether it had
     * inherited the right guards.
     */
    @Test
    public void everyEntryPointRoutesThroughTheOneDecisionPath() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final List<String> entryPoints = entryPoints(guard);

        // The enumeration must not be empty, and it must not be smaller than the tier table needs:
        // four tiers cannot be reached by fewer than four entry points.
        assertTrue("no 'public static boolean allow…' declarations found in MlsPeerGuard — the "
                + "locator is wrong and every assertion below is silently vacuous, which is the "
                + "exact failure mode this guard is about",
                entryPoints.size() >= Tier.values().length);

        for (final String name : entryPoints) {
            // EVERY overload, not the first. allowEraAdvance has two: the two-argument form is a
            // delegation to the three-argument one. Taking the first body would have asserted the
            // property of a one-line delegate and reported green for the arm that actually decides
            // — the same shape, arriving through an overload rather than through a needle.
            final List<String> bodies = bodiesOf(guard, name);
            assertTrue(name + "'s body could not be brace-matched", !bodies.isEmpty());

            int routes = 0;
            for (final String body : bodies) {
                if (body.contains("decide(Tier.")) routes++;

                // A composition creeping back in. Each of these is a guard being consulted HERE
                // rather than declared in the table, and each names the rung it would resurrect.
                assertFalse("MlsPeerGuard." + name + " reads the freeze property itself. G6's "
                        + "placement is a row of the tier table (it is first everywhere, "
                        + "deliberately); an entry point that checks it directly can be first, "
                        + "last, or missing and nothing would say so.",
                        body.contains("SystemProperties."));
                assertFalse("MlsPeerGuard." + name + " consults the lab allowlist itself — G1 "
                        + "outside the table again.", body.contains("labPeers("));
                assertFalse("MlsPeerGuard." + name + " reads the store itself. Reading a record is "
                        + "how a guard gets applied off the table; the one decision path gathers "
                        + "exactly what MlsStateChangeGate.consults says this tier needs.",
                        body.contains("load(") || body.contains("store(") || body.contains("drop("));
            }
            assertTrue("no overload of MlsPeerGuard." + name + " routes through decide(Tier.…) — "
                    + bodies.size() + " found. It is answering the question itself, so the tier "
                    + "table in MlsStateChangeGate is no longer what production obeys, and that "
                    + "table is what three separate changes each got one row of wrong.", routes >= 1);
        }
    }

    /**
     * <b>Every {@link Tier} the engine declares is reached by an entry point.</b>
     *
     * <p>This is that regression as a test. The defect it prevents is not a wrong answer:
     * it is a self-departure pointed at {@code allowStateChange} because that is nearest, which
     * leaves {@link Tier#SELF_DEPARTURE} declared, tested, documented and referenced by nothing —
     * and a person taps <b>Leave group</b> and stays in the group with a peer that has stopped
     * answering.
     */
    @Test
    public void everyTierIsReachedFromAnEntryPoint() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        for (final Tier tier : Tier.values()) {
            final String needle = "decide(Tier." + tier.name();
            assertTrue("Tier." + tier + " is declared in the engine and no MlsPeerGuard entry point "
                    + "reaches it. A tier nothing routes to is a composition that exists only in "
                    + "the table — its guards are never applied to anything.",
                    guard.contains(needle));
        }
    }

    /**
     * <b>Every {@link Reason} has its own line.</b>
     *
     * <p>{@code report}'s {@code default} arm says "the gate could not ANSWER", which is right for
     * {@link Reason#TIER_NOT_CLASSIFIED} and {@link Reason#FACT_NOT_SUPPLIED} and wrong for every
     * real refusal. A reason added to the gate without a line here would fall into it, and a person
     * reading the log would be told a composition was undeclared when in fact a budget had refused
     * them — the reporting failure the house rule names ("a reader that refuses a
     * case must not share a return value with 'absent'"), arriving in the log instead of in a
     * return.
     *
     * <p>Enumerated from {@code Reason.values()} on this test's own classpath, so it is the engine's
     * list and not a copy of it.
     */
    @Test
    public void everyRefusalReasonHasItsOwnLine() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final String body = bodyOf(guard, "report");
        assertTrue("MlsPeerGuard.report is gone — nothing turns a verdict into a sentence",
                body.length() > 0);

        final List<String> missing = new ArrayList<>();
        for (final Reason reason : Reason.values()) {
            if (!body.contains("case " + reason.name() + ":")) missing.add(reason.name());
        }
        if (!missing.isEmpty()) {
            fail("MlsStateChangeGate declares " + missing + " and MlsPeerGuard.report has no arm "
                    + "for them, so they fall into the default arm and are announced as 'the gate "
                    + "could not ANSWER' — a person is told the composition is undeclared when a "
                    + "guard has in fact refused them. (" + Reason.values().length + " reasons "
                    + "declared.)");
        }
    }

    /**
     * <b>Every {@link Guard} the engine declares has its fact gathered.</b>
     *
     * <p>A guard added to the gate and to a tier's row, with nothing in {@code decide} reading the
     * store or sysprop it needs, produces {@link MlsStateChangeGate.Outcome#UNANSWERABLE} at
     * runtime — which fails closed and says so, so this is a second net rather than the only one.
     * It is worth having because the first net fires on a device and this one fires in a build.
     */
    @Test
    public void everyGuardsFactIsGathered() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final String body = bodyOf(guard, "decide");
        assertTrue("MlsPeerGuard.decide is gone", body.length() > 0);
        assertTrue("MlsPeerGuard.decide no longer asks MlsStateChangeGate anything — the guard is "
                + "back to composing for itself", body.contains("MlsStateChangeGate.decide("));

        for (final Guard g : Guard.values()) {
            assertTrue("MlsStateChangeGate declares Guard." + g + " and MlsPeerGuard.decide never "
                    + "mentions it, so no fact is gathered for it. Every tier that composes it will "
                    + "answer UNANSWERABLE and refuse.", body.contains("Guard." + g.name()));
        }

        // The gathering must be driven by the table, not by an order written out again here.
        assertTrue("MlsPeerGuard.decide no longer asks MlsStateChangeGate.consults which facts this "
                + "tier needs. Deciding that for itself is a second copy of the tier table, and two "
                + "copies is what Stage 2 exists to end.",
                body.contains("MlsStateChangeGate.consults("));
    }

    /**
     * <b>Every entry point is CALLED from somewhere in the tree.</b>
     *
     * <p>A per-needle lower bound rather than a total, because those instances all degrade
     * to "found fewer than exist": a total of eleven is satisfied by eleven calls to one method
     * while three tiers are unreachable.
     */
    @Test
    public void everyEntryPointHasALiveCaller() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final String callers = SourceScan.codeOnly(SourceScan.read(TRANSPORT_SRC)) + "\n" + SourceScan.codeOnly(SourceScan.read(RECEIVER_SRC));

        assertTrue("ZERO HITS MUST FAIL: entryPoints(MlsPeerGuard) is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                !entryPoints(guard).isEmpty());
        for (final String name : entryPoints(guard)) {
            final int n = SourceScan.count(callers, "MlsPeerGuard." + name + "(");
            assertTrue("MlsPeerGuard." + name + " is declared and NOTHING calls it. Either an "
                    + "operation stopped being gated, or it moved to a different tier and nobody "
                    + "said which — both are the shape of the three defects Stage 2 came from.",
                    n >= 1);
        }
    }

    /**
     * The count of production call sites, so a change to it is a decision rather than a drift.
     *
     * <p>Stage 0 measured <b>20</b> guard/limiter call sites tree-wide, correcting the plan's "13".
     * Eleven of those twenty are the {@code allow*} family this stage owns; the rest are the three
     * {@code note*}/{@code reset*} producers, the two operator-lever resets, and the four
     * {@code MlsRebuildLimiter}/{@code MlsExternalCommitBudget} sites that are Stage 3's.
     *
     * <p>Asserted as a floor with a named ceiling rather than an equality, so adding a gated
     * operation does not fail a test that has nothing to say about it — but REMOVING one does.
     */
    @Test
    public void theAllowFamilyHasElevenProductionCallSites() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final String callers = SourceScan.codeOnly(SourceScan.read(TRANSPORT_SRC)) + "\n" + SourceScan.codeOnly(SourceScan.read(RECEIVER_SRC));
        int total = 0;
        final StringBuilder detail = new StringBuilder();
        for (final String name : entryPoints(guard)) {
            final int n = SourceScan.count(callers, "MlsPeerGuard." + name + "(");
            total += n;
            detail.append("\n  ").append(name).append(" x").append(n);
        }
        assertTrue("the allow* family now has " + total + " production call sites, fewer than the "
                + "11 Stage 0 enumerated. An operation has stopped being gated." + detail,
                total >= 11);
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The {@code allow*} entry points {@code MlsPeerGuard} declares, in source order. */
    private static List<String> entryPoints(final String guardSrc) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = ENTRY_POINT.matcher(guardSrc);
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    /**
     * The brace-matched body of the first class-level method named {@code name}, or "" if none.
     *
     * <p>Use {@link #bodiesOf} wherever a method may be OVERLOADED. {@code allowEraAdvance} has two
     * forms and the two-argument one is a single delegating line: asserting on "the first body"
     * would have proved a property of the delegate and reported green for the arm that decides.
     */
    private static String bodyOf(final String src, final String name) {
        final List<String> all = bodiesOf(src, name);
        return all.isEmpty() ? "" : all.get(0);
    }

    /** Every brace-matched class-level body named {@code name} — one per overload, in source order. */
    private static List<String> bodiesOf(final String src, final String name) {
        final List<String> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    out.add(src.substring(open, i + 1));
                    break;
                }
            }
        }
        return out;
    }

}
