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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A rejoin clears the self-leave mark, and NOTHING ELSE DOES.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code selfLeftAtMs} had one writer ({@code recordSelfDeparture}) and no clearer anywhere in
 * either repo. That is correct while we stay out of the group and wrong the moment a remaining
 * member adds us back: the Welcome is joined, {@code adoptGroup}'s {@code putGroup} OVERLAYS the
 * record without touching the field, and {@code weLeft()} stays true on a group we are
 * demonstrably in. Every ED-1 guard then declines by name — self-heal, the maintenance pass, the
 * floor rebuild, {@code commitPendingProposals}, the drop lever — and the UI keeps hiding Rename /
 * Add people / Leave group.
 *
 * <h2>Why a SOURCE guard</h2>
 *
 * <p>{@code MlsProviderTransport} needs a {@code Context}, so the host suite cannot construct one;
 * same reasoning as {@code MlsSelfDepartureGuardTest} and {@code MlsGuardPersistenceTest}. The
 * behavioural half that IS reachable — that the record's own overlay preserves the mark, and that
 * setting it to zero clears it — lives in {@code MlsConversationRecordTest}, because that is the
 * property this bug stood on.
 *
 * <h2>What each assertion would take to break</h2>
 *
 * <p>Every one of these fails on a change somebody has a reason to make, which is the point:
 * moving the clear into {@code adoptGroup} (the obvious place, and the one first
 * proposed), hoisting it into {@code joinFromWelcome} (one line shorter), resetting the field in
 * {@code writeRecord} (fixes it "for every path at once"), or deleting the zero-length test in
 * {@code ourRoster} (it reads as redundant next to the null test one line above).
 */
public final class MlsRejoinClearsSelfLeftGuardTest {

    /**
     * As {@code MlsSelfDepartureGuardTest}'s, with the access modifier made OPTIONAL — and that is a
     * fix, not a loosening.
     *
     * <p>{@code adoptGroup} is declared PACKAGE-PRIVATE ({@code String adoptGroup(…)}, so a test in
     * the same package can drive it), and the shared pattern requires one of
     * {@code public|private|protected}. It therefore could not find the method at all, and
     * {@code bodyOf} answered "" — which every assertion below would have read as "this method does
     * not contain the thing I am forbidding". Caught only because each read is preceded by a
     * {@code length() > 0} check; without that this test would have passed on a method it never saw.
     *
     * <p><b>{@code (?! )} IS LOAD-BEARING, and dropping the modifier without it silently inverts
     * this whole test.</b> {@code ^    } matches the first four spaces of ANY indentation, so with
     * the modifier gone the lazy span simply eats the rest of an eight-space indent and the pattern
     * matches a CALL SITE. Measured: {@code bodyOf("selfHealInner")} then returned the block after
     * {@code return selfHealInner(rcsGroupId, peerE164, key);} — a neighbour's body, in which
     * {@code weLeft(} does not appear — and four assertions failed naming code that was still
     * exactly where they said it should be. The strict pattern never needed the lookahead because
     * the keyword itself forced the indent to be exactly four.
     *
     * <p>The {@code [^\n=;]*?} span still excludes anything with an {@code =} or a {@code ;} before
     * the parenthesis, so a field initialiser cannot match. Each read below additionally asserts a
     * landmark it KNOWS that method contains, so a wrong match is caught rather than certified.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?! )(?:(?:public|private|protected)\\s)?[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /**
     * <b>Two writers of the field, and they are the leave and the rejoin.</b>
     *
     * <p>The census is the assertion. A third {@code selfLeftAtMs(} in this file is a path that
     * sets or clears the terminal mark without having been argued for — and the two directions are
     * not symmetric in cost: a stray SET makes a live conversation refuse every repair, a stray
     * CLEAR un-leaves a person from a group they asked to leave, which is the reversal
     * the drop lever is forbidden from performing.
     */
    @Test
    public void theMarkHasExactlyTwoWritersOneEachWay() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        int writes = 0;
        for (int i = src.indexOf("selfLeftAtMs("); i >= 0;
                i = src.indexOf("selfLeftAtMs(", i + 1)) {
            writes++;
        }
        // ZERO MUST FAIL, and so must one. A scan that finds no writer has certified a field it
        // never located; finding one means either the leave or the rejoin has gone.
        assertEquals("MlsProviderTransport no longer has exactly two writers of selfLeftAtMs. Two "
                + "is the design: recordSelfDeparture sets it on a server-ACCEPTED SelfRemove, and "
                + "clearSelfLeftOnRejoin zeroes it on an ACCEPTED Welcome. A third is a path that "
                + "moves the terminal mark without an argument — and a stray CLEAR silently puts a "
                + "person back into a group they left.", 2, writes);

        final String setter = bodyOf(src, "recordSelfDeparture");
        assertTrue("recordSelfDeparture is gone — the mark has no writer", setter.length() > 0);
        assertTrue("recordSelfDeparture no longer stamps the mark with a timestamp, so a departure "
                + "is no longer recorded and weLeft() cannot distinguish a leave from a wedge",
                setter.contains("selfLeftAtMs(System.currentTimeMillis())"));

        final String clearer = bodyOf(src, "clearSelfLeftOnRejoin");
        assertTrue("clearSelfLeftOnRejoin is gone — the defect is back: a re-added member rejoins "
                + "the group and every recovery path keeps declining on INVARIANT ED-1",
                clearer.length() > 0);
        assertTrue("clearSelfLeftOnRejoin no longer zeroes the mark", clearer.contains(
                "selfLeftAtMs(0L)"));
        assertTrue("clearSelfLeftOnRejoin writes without first checking that there IS a mark. "
                + "recordFor() MINTS an initial record when none is persisted, so an unguarded "
                + "write would persist a fresh record for every group that ever receives a "
                + "Welcome — and would do it on the inbound control path.",
                clearer.contains("!rec.selfLeft()"));
    }

    /**
     * <b>The clear hangs off the ACCEPTANCE of a Welcome, at exactly two sites.</b>
     *
     * <p>{@code applyInboundControl}'s NEW_GROUP arm is the one a re-added leaver actually takes —
     * the departure deleted the engine group, so {@code resolveInbound} answers null and the
     * control lands there. {@code rejoinOnEraAdvance} is the other arm, reachable when the
     * departure's best-effort {@code deleteGroup} did not take.
     */
    @Test
    public void theClearIsCalledOnlyFromTheTwoWelcomeAcceptanceSites() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        int calls = 0;
        for (int i = src.indexOf("clearSelfLeftOnRejoin("); i >= 0;
                i = src.indexOf("clearSelfLeftOnRejoin(", i + 1)) {
            calls++;
        }
        // Three: the declaration plus the two call sites.
        assertEquals("clearSelfLeftOnRejoin is not called from exactly two places (found "
                + (calls - 1) + " call site(s) plus its declaration). Two is the design — the two "
                + "points at which a Welcome becomes a DECISION rather than an event. A third "
                + "caller is a path that un-leaves us on something other than an accepted Welcome.",
                3, calls);

        final String inbound = bodyOf(src, "applyInboundControl");
        assertTrue("applyInboundControl is gone", inbound.length() > 0);
        assertTrue("applyInboundControl's join arm no longer clears the mark. That is THE arm a "
                + "re-added member who left takes: markLeft deletes the engine group, so "
                + "resolveInbound answers null and the Welcome lands here.",
                inbound.contains("clearSelfLeftOnRejoin("));

        final String rejoin = bodyOf(src, "rejoinOnEraAdvance");
        assertTrue("rejoinOnEraAdvance is gone", rejoin.length() > 0);
        assertTrue("rejoinOnEraAdvance no longer clears the mark", rejoin.contains(
                "clearSelfLeftOnRejoin("));
    }

    /**
     * <b>In {@code rejoinOnEraAdvance} the clear is BELOW the {@code keepsJoin()} gate.</b>
     *
     * <p>This is the whole reason the clear is not simply inside {@code joinFromWelcome}, which
     * would be one line shorter and cover both arms at once. That method's refusal set includes
     * {@code NOT_ADDRESSED_TO_US} — a Welcome minted for some OTHER new member, which is an
     * entirely ordinary thing to receive from inside a group — and it ROLLS THE JOIN BACK. Clearing
     * above the gate un-leaves us on a stranger's Welcome, silently, and nothing downstream would
     * ever say so.
     */
    @Test
    public void theClearSitsBelowTheJoinAdmissionGate() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        final String rejoin = bodyOf(src, "rejoinOnEraAdvance");
        assertTrue("rejoinOnEraAdvance is gone", rejoin.length() > 0);
        final int gate = rejoin.indexOf("verdict.keepsJoin()");
        assertTrue("rejoinOnEraAdvance no longer consults keepsJoin(), so there is no admission "
                + "gate for the clear to sit below and this ordering check means nothing", gate > 0);
        final int clear = rejoin.indexOf("clearSelfLeftOnRejoin(");
        assertTrue("rejoinOnEraAdvance does not clear the mark at all", clear > 0);
        assertTrue("rejoinOnEraAdvance clears the self-leave mark ABOVE the keepsJoin() gate "
                + "(clear at " + clear + ", gate at " + gate + "). The refused verdicts include "
                + "NOT_ADDRESSED_TO_US — a Welcome for a DIFFERENT new member — and the join is "
                + "rolled back. Clearing there un-leaves us on somebody else's Welcome.",
                clear > gate);

        // AND NOT ON THE JOIN ITSELF. joinFromWelcome is called by BOTH arms, including the one
        // that rolls back, so a clear placed there inherits the rollback's blind spot.
        final String join = bodyOf(src, "joinFromWelcome");
        assertTrue("joinFromWelcome is gone", join.length() > 0);
        assertFalse("joinFromWelcome clears the self-leave mark. It is called by BOTH inbound arms, "
                + "and rejoinOnEraAdvance rolls its join back on four separate verdicts — so the "
                + "mark would be cleared for a join that was refused.",
                join.contains("clearSelfLeftOnRejoin("));
    }

    /**
     * <b>{@code adoptGroup} does not clear the mark, and INVARIANT ED-1 is why.</b>
     *
     * <p>Adoption is the obvious place — it is where the record is written and it is inside the
     * conversation lock — and it is wrong, because adoption ALSO runs on the create path
     * ({@code establishGroup}). ED-1 is precisely that an era advance re-creates the group with us
     * in it and must not silently undo a departure. Today three guards stop a create being reached
     * for a left group; putting the clear there would make the terminal mark depend on all three
     * staying correct, which is the one thing a terminal mark must not do.
     */
    @Test
    public void adoptionAndTheRecordWritersLeaveTheMarkAlone() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        // method -> a landmark it is KNOWN to contain. The landmark is not decoration: bodyOf
        // answers "" for a method it cannot find, and "" contains nothing, so every assertFalse
        // below would PASS on a method that was never read. Asserting a positive first is what
        // makes the negatives mean something.
        final String[][] writers = {
            {"adoptGroup",   "ensureRecord(key)"},
            {"ensureRecord", "MlsConversationRecord.initial("},
            {"putGroup",     "writeRecord(g)"},
            {"writeRecord",  "prior.isOk()"},
        };
        for (final String[] w : writers) {
            final String m = w[0];
            final String body = bodyOf(src, m);
            assertTrue(m + " is gone — this guard is reading a method that no longer exists",
                    body.length() > 0);
            assertTrue("the body read for " + m + " does not contain " + w[1] + ", so the method "
                    + "regex matched something else and the two checks below are about the wrong "
                    + "code", body.contains(w[1]));
            assertFalse(m + " touches selfLeftAtMs. The mark must survive an overlay: writeRecord "
                    + "is a read-modify-write that runs on EVERY putGroup, so resetting it there "
                    + "would clear a departure on the next send — and adoptGroup also runs on the "
                    + "CREATE path, where clearing it is INVARIANT ED-1's exact prohibition.",
                    body.contains("selfLeftAtMs"));
            assertFalse(m + " calls clearSelfLeftOnRejoin. Only an ACCEPTED Welcome may un-leave "
                    + "us — not an adoption and not a record write.",
                    body.contains("clearSelfLeftOnRejoin("));
        }

        // THE SEND PATHS ARE COVERED BY THE CENSUS, NOT BY A PER-METHOD READ, and deliberately:
        // encryptForSend has TWO overloads and bodyOf returns the FIRST, which is the two-argument
        // delegator — a body that contains neither name and would have certified the three-argument
        // one it never opened. The whole-file counts above (two writers, two call sites) already
        // prove no send path writes or clears the mark, and they cannot be fooled by an overload.
    }

    /**
     * <b>{@code ourRoster} reads a zero-length recorded set as NO BASELINE, not as an empty group.</b>
     *
     * <p>The other half of the fix, and it is not optional: {@code recordSelfDeparture} writes §4.8
     * row 14 EMPTY and says so in its own comment — "the two changes are one change; neither is
     * safe without the other". While the mark stands nobody reads that row, because
     * {@code runMaintenanceOnce} refuses on {@code weLeft} first. Clear the mark and the pass runs:
     * an empty {@code ours} minus the server's roster reads as "every member is new", and THAT
     * ISSUES AN ERA ADVANCE. So clearing the mark without this line trades one defect for a louder
     * one.
     *
     * <p>The test is on the STORED array and not on the filtered list, deliberately. A group
     * recorded as {@code [self]} filters down to an empty list and is a REAL roster with a real
     * answer — the group has only us, and every server member genuinely is new.
     */
    @Test
    public void anEmptyRecordedRosterIsNotARoster() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        final String body = bodyOf(src, "ourRoster");
        assertTrue("ourRoster is gone", body.length() > 0);
        assertTrue("ourRoster no longer distinguishes a zero-length recorded membership from a "
                + "real one. recordSelfDeparture is the only writer of an empty array, and after "
                + "the mark is cleared the maintenance pass reads it as 'every server member is "
                + "new' and era-advances the group we have only just rejoined.",
                body.contains("members.length == 0"));
        final int nullTest = body.indexOf("members == null");
        final int emptyTest = body.indexOf("members.length == 0");
        assertTrue("the zero-length test runs before the members are filtered — otherwise the "
                + "[self]-only case, which filters to an empty list, is caught by it too and a "
                + "group that really does contain only us reports no baseline",
                nullTest > 0 && emptyTest > nullTest
                        && emptyTest < body.indexOf("new java.util.ArrayList<>()"));
    }

    /**
     * <b>The refusals that make the mark worth having are still there.</b>
     *
     * <p>A fix that quietly loosened {@code weLeft}'s readers would "pass" every
     * check above while giving up what the mark is FOR. These five are ED-1's enforcement points;
     * {@code encryptForSend}'s is listed separately because it is the 1:1 path and a self-leave
     * cannot reach it — it guards the 1:1 {@code endMls} route, not this one.
     */
    @Test
    public void theEdOneRefusalsSurvive() throws IOException {
        final String src = codeOnly(read(TRANSPORT));
        for (final String m : new String[] {"selfHealInner", "floorRebuild", "runMaintenanceOnce",
                "commitPendingProposals", "dropPendingProposals"}) {
            final String body = bodyOf(src, m);
            assertTrue(m + " is gone", body.length() > 0);
            assertTrue(m + " no longer refuses on weLeft(). The mark exists so a departed member "
                    + "stops acting; clearing it on a rejoin is only safe while the refusals it "
                    + "drives are intact. INVARIANT ED-1: an era advance re-creates the group WITH "
                    + "US IN IT.", body.contains("weLeft("));
        }
    }

    // ---- helpers (same shape as MlsSelfDepartureGuardTest's) ---------------------------------

    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
            return "";
        }
        return "";
    }

    /** Comments and string-literal contents blanked; offsets preserved. See MlsGuardPersistenceTest. */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        while (i < out.length) {
            final char c = out[i];
            final char next = (i + 1 < out.length) ? out[i + 1] : '\0';
            if (c == '/' && next == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && next == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < out.length && out[i] != c) {
                    if (out[i] == '\\') {
                        out[i++] = ' ';
                        if (i < out.length) out[i++] = ' ';
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
