/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@code MlsPeerGuard} asks the tier table rather than restating it, and every tier, guard and
 * reason the engine declares is wired. A source scan because {@code MlsPeerGuard} needs a
 * {@code Context}; {@link MlsStateChangeGateTest} covers the table itself. Entry points are parsed
 * from the guard's source and tiers, guards and reasons come from {@code values()}, over
 * comment-stripped code. See docs/testing.md.
 */
public final class MlsStateChangeTierWiringTest {

    private static final String GUARD_SRC = "src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java";
    private static final String TRANSPORT_SRC =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";
    private static final String RECEIVER_SRC =
            "src/com/android/messaging/rcs/RcsDebugSendReceiver.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /** {@code public static boolean allowSomething(}: the guard's entry points. */
    private static final Pattern ENTRY_POINT = Pattern.compile(
            "(?m)^    public static boolean (allow\\w+)\\s*\\(");

    @Test
    public void everyEntryPointRoutesThroughTheOneDecisionPath() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final List<String> entryPoints = entryPoints(guard);

        // Every tier needs at least one entry point.
        assertTrue("no 'public static boolean allow…' declarations found in MlsPeerGuard — the "
                + "locator is wrong and every assertion below is silently vacuous, which is the "
                + "exact failure mode this guard is about",
                entryPoints.size() >= Tier.values().length);

        for (final String name : entryPoints) {
            // Every overload: allowEraAdvance's two-argument form only delegates.
            final List<String> bodies = bodiesOf(guard, name);
            assertTrue(name + "'s body could not be brace-matched", !bodies.isEmpty());

            int routes = 0;
            for (final String body : bodies) {
                if (body.contains("decide(Tier.")) routes++;

                // A guard consulted here rather than declared in the table.
                assertFalse("MlsPeerGuard." + name + " reads the freeze property itself. G6's "
                        + "placement is a row of the tier table (it is first everywhere, "
                        + "deliberately); an entry point that checks it directly can be first, "
                        + "last, or missing and nothing would say so.",
                        body.contains("SystemProperties."));
                assertFalse("MlsPeerGuard." + name + " consults the peer allowlist itself — G1 "
                        + "outside the table again.", body.contains("allowedPeers("));
                assertFalse("MlsPeerGuard." + name + " reads the store itself. Reading a record is "
                        + "how a guard gets applied off the table; the one decision path gathers "
                        + "exactly what MlsStateChangeGate.consults says this tier needs.",
                        body.contains("load(") || body.contains("store(")
                        || body.contains("drop("));
            }
            assertTrue("no overload of MlsPeerGuard." + name + " routes through decide(Tier.…) — "
                    + bodies.size() + " found. It is answering the question itself, so the tier "
                    + "table in MlsStateChangeGate is no longer what production obeys, and that "
                    + "table is what three separate changes each got one row of wrong.", routes
                    >= 1);
        }
    }

    @Test
    public void everyTierIsReachedFromAnEntryPoint() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        for (final Tier tier : Tier.values()) {
            final String needle = "decide(Tier." + tier.name();
            assertTrue("Tier." + tier
                    + " is declared in the engine and no MlsPeerGuard entry point "
                    + "reaches it. A tier nothing routes to is a composition that exists only in "
                    + "the table — its guards are never applied to anything.",
                    guard.contains(needle));
        }
    }

    /** {@code report}'s default arm says the gate could not answer, wrong for a real refusal. */
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

    /** A guard with no gathered fact answers UNANSWERABLE at runtime; this catches it at build. */
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

        // The gathering is driven by the table.
        assertTrue(
                "MlsPeerGuard.decide no longer asks MlsStateChangeGate.consults which facts this "
                + "tier needs. Deciding that for itself is a second copy of the tier table, and two "
                + "copies is what the tier table exists to end.",
                body.contains("MlsStateChangeGate.consults("));
    }

    /** A per-entry-point lower bound: a total can be met by calls to one method. */
    @Test
    public void everyEntryPointHasALiveCaller() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        // The unsplit view spells shell.peerGuard().allowX( as MlsPeerGuard.allowX(.
        final String callers = SourceScan.transportUnsplitCode() + "\n"
                + SourceScan.codeOnly(SourceScan.read(RECEIVER_SRC));

        assertTrue(
                        "ZERO HITS MUST FAIL: entryPoints(MlsPeerGuard) is EMPTY, so the loop below never runs "
                        + "and this assertion certifies green having examined nothing. A zero-match "
                        + "scan is a broken scan, not a clean tree.",
                !entryPoints(guard).isEmpty());
        for (final String name : entryPoints(guard)) {
            final int n = SourceScan.count(callers, "MlsPeerGuard." + name + "(");
            assertTrue("MlsPeerGuard." + name + " is declared and NOTHING calls it. Either an "
                    + "operation stopped being gated, or it moved to a different tier and nobody "
                    + "said which — both are defects the tier table exists to prevent.",
                    n >= 1);
        }
    }

    /** A floor on {@code allow*} call sites: adding a gated operation passes, removing fails. */
    @Test
    public void theAllowFamilyHasElevenProductionCallSites() throws IOException {
        final String guard = SourceScan.codeOnly(SourceScan.read(GUARD_SRC));
        final String callers = SourceScan.transportUnsplitCode() + "\n"
                + SourceScan.codeOnly(SourceScan.read(RECEIVER_SRC));
        int total = 0;
        final StringBuilder detail = new StringBuilder();
        for (final String name : entryPoints(guard)) {
            final int n = SourceScan.count(callers, "MlsPeerGuard." + name + "(");
            total += n;
            detail.append("\n  ").append(name).append(" x").append(n);
        }
        assertTrue("the allow* family now has " + total + " production call sites, fewer than the "
                + "11 gated operations. An operation has stopped being gated." + detail,
                total >= 11);
    }

    /** The {@code allow*} entry points {@code MlsPeerGuard} declares, in source order. */
    private static List<String> entryPoints(final String guardSrc) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = ENTRY_POINT.matcher(guardSrc);
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    /** The first class-level body named {@code name}, or ""; overloads: {@link #bodiesOf}. */
    private static String bodyOf(final String src, final String name) {
        final List<String> all = bodiesOf(src, name);
        return all.isEmpty() ? "" : all.get(0);
    }

    /** Every class-level body named {@code name}, one per overload, in source order. */
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
