/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A rejoin clears the self-leave mark, and nothing else does. Without a clearer, a member added
 * back after leaving keeps {@code weLeft()} true on a group it is in, every left-group guard
 * declines, and the UI hides the group actions. The transport needs a {@code Context}, so this is a
 * source guard; the record's own overlay behaviour is tested in {@code MlsConversationRecordTest}.
 * See docs/rcs/groups.md.
 */
public final class MlsRejoinClearsSelfLeftGuardTest {

    /**
     * As {@code MlsSelfDepartureGuardTest}'s, with the access modifier optional so package-private
     * {@code adoptGroup} is found. The {@code (?! )} lookahead stops the pattern matching a call
     * site at a deeper indent; {@code [^\n=;]*?} excludes field initialisers. Each read also
     * asserts a landmark the method is known to contain.
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?! )(?:(?:public|private|protected)\\s)?[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /**
     * Exactly two writers of the field: the leave and the rejoin. A stray set makes a live
     * conversation refuse every repair; a stray clear un-leaves a person from a group they asked to
     * leave.
     */
    @Test
    public void theMarkHasExactlyTwoWritersOneEachWay() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        int writes = 0;
        for (int i = src.indexOf("selfLeftAtMs("); i >= 0;
                i = src.indexOf("selfLeftAtMs(", i + 1)) {
            writes++;
        }
        // Zero fails, and so does one.
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
     * The clear follows acceptance of a Welcome, at two sites: {@code applyInboundControl}'s
     * NEW_GROUP arm, which a re-added leaver takes, and {@code rejoinOnEraAdvance}, reachable when
     * the departure's {@code deleteGroup} did not take.
     */
    @Test
    public void theClearIsCalledOnlyFromTheTwoWelcomeAcceptanceSites() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        int calls = 0;
        for (int i = src.indexOf("clearSelfLeftOnRejoin("); i >= 0;
                i = src.indexOf("clearSelfLeftOnRejoin(", i + 1)) {
            calls++;
        }
        // Three: the declaration and the two call sites.
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
     * In {@code rejoinOnEraAdvance} the clear is below the {@code keepsJoin()} gate:
     * {@code joinFromWelcome} rolls back a Welcome minted for another new member, and clearing
     * above the gate would un-leave us on it.
     */
    @Test
    public void theClearSitsBelowTheJoinAdmissionGate() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        final String rejoin = bodyOf(src, "rejoinOnEraAdvance");
        assertTrue("rejoinOnEraAdvance is gone", rejoin.length() > 0);
        final int gate = rejoin.indexOf("verdict.keepsJoin()");
        assertTrue("rejoinOnEraAdvance no longer consults keepsJoin(), so there is no admission "
                + "gate for the clear to sit below and this ordering check means nothing",
                gate > 0);
        final int clear = rejoin.indexOf("clearSelfLeftOnRejoin(");
        assertTrue("rejoinOnEraAdvance does not clear the mark at all", clear > 0);
        assertTrue("rejoinOnEraAdvance clears the self-leave mark ABOVE the keepsJoin() gate "
                + "(clear at " + clear + ", gate at " + gate + "). The refused verdicts include "
                + "NOT_ADDRESSED_TO_US — a Welcome for a DIFFERENT new member — and the join is "
                + "rolled back. Clearing there un-leaves us on somebody else's Welcome.",
                clear > gate);

        // Not in joinFromWelcome either: both arms call it, including the one that rolls back.
        final String join = bodyOf(src, "joinFromWelcome");
        assertTrue("joinFromWelcome is gone", join.length() > 0);
        assertFalse(
                "joinFromWelcome clears the self-leave mark. It is called by BOTH inbound arms, "
                + "and rejoinOnEraAdvance rolls its join back on four separate verdicts — so the "
                + "mark would be cleared for a join that was refused.",
                join.contains("clearSelfLeftOnRejoin("));
    }

    /**
     * {@code adoptGroup} does not clear the mark: adoption also runs on the create path, and an era
     * advance that re-creates the group with us in it must not undo a departure.
     */
    @Test
    public void adoptionAndTheRecordWritersLeaveTheMarkAlone() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        // Method -> a landmark it is known to contain; bodyOf answers "" for a method it cannot
        // find, so a positive check comes first.
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

        // The send paths are covered by the whole-file counts above: bodyOf returns the first
        // encryptForSend overload, the two-argument delegator.
    }

    /**
     * {@code ourRoster} reads a zero-length recorded set as no baseline, not as an empty group:
     * {@code recordSelfDeparture} stores the roster empty, and once the mark is cleared an empty
     * {@code ours} would read every server member as new and issue an era advance. Tested on the
     * stored array, since {@code [self]} filters to a real, empty answer.
     */
    @Test
    public void anEmptyRecordedRosterIsNotARoster() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
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
     * The refusals that make the mark worth having are still in place. {@code encryptForSend}'s is
     * listed separately: it guards the 1:1 {@code endMls} route, which a self-leave cannot reach.
     */
    @Test
    public void theEdOneRefusalsSurvive() throws IOException {
        final String src = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
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

    /** Comments and string-literal contents blanked; offsets preserved. */
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
                while (i < out.length
                        && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
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
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
