/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every path that makes a peer join or re-join by Welcome is charged to a budget. The ground truth
 * is the engine calls that mint a Welcome: any method containing one must appear in
 * {@link #CHARGED_BY} naming the guard it charges, so a new create or recovery path fails here
 * until someone says which budget pays for it. Needles key on the invoked method name (and method
 * references), never on a receiver; the door list is checked against {@code MlsSession}; each door
 * has its own minimum call-site count; blocks are brace-matched, never a character window; scans
 * read comment- and string-blanked source. See docs/mls/budgets.md and docs/testing.md.
 */
public final class MlsPeerReJoinBudgetGuardTest {

    /**
     * The engine calls that mint a Welcome, each with the minimum number of transport call sites
     * and why. The minimum is per door, so a renamed door goes red instead of enumerating nothing;
     * zero is a declaration that a door is watched with no call site today.
     */
    private static final String[][] WELCOME_MINTING_CALLS = {
        // {invoked method, minimum call sites, why that minimum}
        {"createGroupPlanned", "3", "the era-free create entry point: ensureReady's 1:1 "
                + "create, establishGroup's group create, and eraAdvanceLocked's advance"},
        {"createGroupWithId", "2", "ensureReady's two revive arms — the era bump and the id "
                + "reclaim, both re-using the id the server named"},
        {"addMember", "4", "the 2-arg delegate, the MLS arm in mlsMembershipChange, the plaintext "
                + "arm through commitAndSend, and changeGroupMembership's ADD route"},
        {"createGroup", "0", "no site today — createGroupPlanned is the entry point every create "
                + "should use, and this is the era-naming overload it replaced"},
        {"createGroupMulti", "0",
                "no site today — createGroupPlanned reaches it inside the engine. "
                + "It builds an N-member group in ONE commit, so one Welcome covers everyone, and a "
                + "transport-side call to it would be an unbudgeted mass re-Welcome"},
        {"addMembers", "0", "no site today — establishGroup ships an addMembers commit that the "
                + "engine builds. N packages means N DEVICES, so a direct call is a Welcome per "
                + "device with nothing counting them"},
    };

    /**
     * Every {@code MlsSession} method returning {@code MlsGroupArtifacts} is classified:
     * {@code MINTS} (a door above) or a recorded reason it admits nobody.
     */
    private static final String MINTS = "MINTS";
    private static final String[][] ARTIFACT_RETURNING = {
        {"createGroup", MINTS},
        {"createGroupWithId", MINTS},
        {"createGroupMulti", MINTS},
        {"createGroupPlanned", MINTS},
        {"addMember", MINTS},
        {"addMembers", MINTS},
        {"removeMember", "a Remove commit — its own javadoc says 'no welcome'; nobody is admitted"},
        {"removeMemberByMsisdn", "as removeMember, for every leaf of one participant"},
        {"selfLeave",
                "a by-reference SelfRemoveProposal; the artifacts carry welcome(empty) and it "
                + "does not even advance the epoch"},
        {"selfUpdate", "a self Update commit — the roster is unchanged, so there is nobody to "
                + "Welcome"},
        {"selfUpdateExtPub", "as selfUpdate, republishing our own extension key"},
        {"commitGroupMetadata", "a GroupContextExtensions commit (RCC.16 §9.7.1.4/.5)"},
        {"commitIconSubject", "a GroupContextExtensions commit (RCC.16 §7.11.4/.6)"},
        {"commitEndMls", "a GroupContextExtensions commit (0xF002)"},
        // Classified from the engine: the core's commit_era_advance always fails because the Era
        // (0xF001) is immutable within a group, so eraAdvancePreserving answers BUILD_FAILED and
        // every advance falls through to the create. It mints nothing because it returns nothing.
        {"commitEraAdvance", "mints nothing because it returns nothing: commit_era_advance in "
                + "rust/rcs_mls_ffi/src/ffi.rs refuses unconditionally (the Era is immutable "
                + "within a group instance), so every era advance falls through to the CREATE path "
                + "— which is why createGroupPlanned carries eraAdvanceLocked's site"},
    };

    /**
     * {@code enclosing method}, {@code charging method}, {@code declaration fragment},
     * {@code guard}. The charging method can differ from the enclosing one
     * ({@code eraAdvanceLocked} is charged at the {@code eraAdvance} funnel). The fragment picks
     * the funnel overload over its one-line delegate and stops at a parameter name, so adding a
     * parameter does not break it; empty means unambiguous.
     */
    private static final String[][] CHARGED_BY = {
        // The 1:1 create: the first create is free (nobody re-joins a group that does not exist);
        // the two re-create arms, the era bump and the id reclaim, charge the era budget.
        {"ensureReady", "ensureReady", "final boolean recreateAlreadyCharged",
                "MlsPeerGuard.allowEraAdvance("},
        // The group create: Welcomes every member, so every member passes the joining gate, reached
        // through allowedToJoinAll because rebuildConversation asks the same question before it
        // destroys anything (theGuardHelpersReachMlsPeerGuard pins that link).
        {"establishGroup", "establishGroup", "final MlsUpgradeClaim preClaimed",
                "allowedToJoinAll("},
        // The Add, MLS-conversation arm; reachable only through addMember, which carries the gate.
        {"mlsMembershipChange", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The Add, plaintext-conversation arm: commitAndSend is generic, and Op.ADD reaches it only
        // from addMember.
        {"commitAndSend", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The era advance.
        {"eraAdvanceLocked", "eraAdvance", "final MlsAdvanceEraKind kind",
                "MlsPeerGuard.allowEraAdvance("},
        // The two-argument delegate: a bare addMember(null, …) reaching the gated overload.
        {"addMember", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
        // The AIDL membership route: a single-member ADD calls addMember with the group id.
        {"changeGroupMembership", "addMember", "final String rcsGroupId, final String peerE164,",
                "MlsPeerGuard.allowJoiningPeer("},
    };

    /** A class-level method declaration: 4-space indent, a visibility modifier, no initialiser. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /** {@code MlsGroupArtifacts foo(} in an interface or class declaration. */
    private static final Pattern ARTIFACT_DECL = Pattern.compile(
            "\\bMlsGroupArtifacts\\s+(\\w+)\\s*\\(");

    @Test
    public void everyWelcomeMintingCallSiteChargesABudget() throws IOException {
        // Code only: the transport's prose names these calls, and an unqualified needle would count
        // every mention.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        final List<String> unaccounted = new ArrayList<>();
        final List<String> uncharged = new ArrayList<>();
        final List<String> unselectable = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        for (final String[] door : WELCOME_MINTING_CALLS) {
            final String call = door[0];
            int sites = 0;
            for (final int at : invocationsOf(src, call)) {
                if (isDeclaration(decls, at)) continue;     // the method itself, not a call to it
                sites++;
                final String enclosing = enclosingMethod(src, decls, at);
                final String[] row = rowFor(enclosing);
                if (row == null) {
                    unaccounted.add(call + "() in " + enclosing + "()");
                } else {
                    final String charger = bodyOf(src, row[1], row[2]);
                    if (charger.isEmpty()) {
                        // A different failure: the fragment selects no overload (a parameter was
                        // renamed), which is not "the guard has moved".
                        unselectable.add(row[1] + "(" + row[2] + " …) from " + enclosing + "()");
                    } else if (!charger.contains(row[3])) {
                        uncharged.add(enclosing + "() → " + row[1] + "("
                                + (row[2].isEmpty() ? "" : row[2] + " …") + ") must call "
                                + row[3]);
                    }
                }
            }
            final int min = Integer.parseInt(door[1]);
            if (sites < min) {
                vanished.add(call + "(): " + sites + " call sites, expected at least " + min
                        + " — " + door[2]);
            }
        }

        if (!vanished.isEmpty()) {
            fail("This guard ENUMERATES DOORS; it does not police names. Fewer call sites matched "
                    + "than are declared to exist, which means either the engine call was RENAMED — "
                    + "in which case every site behind the new name is now invisible here and this "
                    + "test would have gone on reporting green — or the path really was removed, in "
                    + "which case lower the number in WELCOME_MINTING_CALLS and say why: "
                    + vanished
                    + ". Do not delete the door: a door with zero sites is still watched, and that "
                    + "is what the zeros in that table mean.");
        }
        if (!unaccounted.isEmpty()) {
            fail(
                    "These calls MINT A WELCOME from a method no budget accounts for, so a peer can be "
                    + "made to join or re-join a group with nothing counting it — the exact hole "
                    + "this guard closed in rebuildConversation: " + unaccounted + ". Charge the "
                    + "operation (MlsPeerGuard.allowEraAdvance for a re-creation, allowJoiningPeer "
                    + "for bringing a peer in) and add a row to CHARGED_BY.");
        }
        if (!unselectable.isEmpty()) {
            fail("The declaration fragment in CHARGED_BY no longer picks any overload of the "
                    + "charging method: " + unselectable + ". This is NOT a missing charge — it is "
                    + "the guard failing to find the method to look in, which is the same disease "
                    + "the needles had: the fragment encodes a SPELLING of the "
                    + "signature. Trim it to the least that distinguishes the overload from its "
                    + "delegating sibling, or update it to the new one.");
        }
        if (!uncharged.isEmpty()) {
            fail(
                    "These paths mint a Welcome but the method declared as charging for them no longer "
                    + "does: " + uncharged
                    + ". A guard that has moved out from under its call site "
                    + "is indistinguishable from one that was never there.");
        }
    }

    /**
     * The door list is every Welcome-minting engine call: every admitting call returns
     * {@code MlsGroupArtifacts}, so a new one in {@code MlsSession} fails until it is classified.
     */
    @Test
    public void theDoorListIsEveryWelcomeMintingEngineCall() throws IOException {
        final String session = codeOnly(read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java"));
        final Set<String> declared = new LinkedHashSet<>();
        final Matcher m = ARTIFACT_DECL.matcher(session);
        while (m.find()) declared.add(m.group(1));
        assertTrue(
                "no MlsGroupArtifacts-returning methods found in MlsSession — either the engine's "
                + "artifact type has been renamed or this scan has gone stale, and a stale scan here "
                + "makes the door list unfalsifiable again", declared.size() > 5);

        final Set<String> classified = new LinkedHashSet<>();
        final List<String> unreasoned = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            classified.add(row[0]);
            if (!MINTS.equals(row[1]) && row[1].trim().isEmpty()) unreasoned.add(row[0]);
        }
        if (!unreasoned.isEmpty()) {
            fail("These are classified as not minting a Welcome with no reason given: " + unreasoned
                    + ". The reason is the whole value of the row — it is what a later reader checks "
                    + "against the engine, and 'somebody decided this once' is not checkable.");
        }

        final List<String> unclassified = new ArrayList<>();
        for (final String name : declared) {
            if (!classified.contains(name)) unclassified.add(name);
        }
        if (!unclassified.isEmpty()) {
            fail("MlsSession has grown method(s) returning MlsGroupArtifacts that this guard has "
                    + "never been told about: " + unclassified + ". Every artifact-returning call "
                    + "either ADMITS somebody — in which case it mints a Welcome, add it to "
                    + "ARTIFACT_RETURNING as MINTS and give it a row in WELCOME_MINTING_CALLS — or "
                    + "it does not, in which case say so with the reason. Leaving it out is how a "
                    + "door stays invisible: createGroupMulti and addMembers both mint one and "
                    + "neither was named until this guard was widened.");
        }

        final List<String> stale = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            if (!declared.contains(row[0])) stale.add(row[0]);
        }
        if (!stale.isEmpty()) {
            fail("ARTIFACT_RETURNING names engine methods that no longer exist: " + stale
                    + ". A classification for a method that is gone excuses nothing and misleads the "
                    + "next reader; delete the row, and the door with it if it had one.");
        }

        final Set<String> doors = new LinkedHashSet<>();
        for (final String[] d : WELCOME_MINTING_CALLS) doors.add(d[0]);
        final List<String> unwatched = new ArrayList<>();
        for (final String[] row : ARTIFACT_RETURNING) {
            if (MINTS.equals(row[1]) && !doors.contains(row[0])) unwatched.add(row[0]);
        }
        if (!unwatched.isEmpty()) {
            fail("These engine calls mint a Welcome and no needle looks for them: " + unwatched
                    + ". Add each to WELCOME_MINTING_CALLS — with a minimum of 0 if the transport "
                    + "does not call it today, which still watches the door.");
        }
        final List<String> phantom = new ArrayList<>();
        for (final String[] d : WELCOME_MINTING_CALLS) {
            boolean minting = false;
            for (final String[] row : ARTIFACT_RETURNING) {
                if (row[0].equals(d[0]) && MINTS.equals(row[1])) minting = true;
            }
            if (!minting) phantom.add(d[0]);
        }
        if (!phantom.isEmpty()) {
            fail(
                    "WELCOME_MINTING_CALLS watches names that are not classified as minting a Welcome: "
                    + phantom + ". A needle for a door that does not exist is an assertion about "
                    + "nothing, and it inflates the count that is supposed to prove the scan works.");
        }
    }

    /**
     * The rebuild charges before it destroys anything; a charge after the forget would leave no
     * provider record, no engine state and no group.
     */
    @Test
    public void theRebuildChargesBeforeItDestroysAnything() throws IOException {
        final String body = bodyOfFunnel(codeOnly(readTransport()), "rebuildConversation");
        assertTrue("rebuildConversation not found — it has been renamed, and it is the second door "
                + "to the era budget", body.length() > 0);

        final int charge = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        final int joinGate = body.indexOf("allowedToJoinAll(");
        final int destroy =
                indexOfFirst(body, "mlsForgetGroupConversation(", "mlsForgetConversation(");
        assertTrue("rebuildConversation does not charge the era budget at all. It re-creates a "
                + "conversation the server holds, which makes EVERY member re-join by Welcome — the "
                + "same cost an era advance imposes and the one ~17 of wedged a peer for a month "
                + ". MlsRebuildLimiter bounds our side, not the peer's.", charge >= 0);
        assertTrue("rebuildConversation does not check whether its roster may be brought into an "
                + "MLS group", joinGate >= 0);
        assertTrue(
                "rebuildConversation does not drop the provider half — the recipe has changed and "
                + "this ordering check no longer means anything", destroy >= 0);
        assertTrue(
                "rebuildConversation charges the era budget AFTER it starts destroying state (at "
                + charge + " vs " + destroy + "). A refusal "
                + "would then leave the conversation with nothing at all, which is worse than the "
                + "churn the refusal prevents.", charge < destroy);
        assertTrue("the joining gate must also run before the forget, for the same reason",
                joinGate < destroy);
    }

    /**
     * A refusal stops the rebuild: charging and ignoring the answer reads as a guard and does
     * nothing.
     */
    @Test
    public void aRefusedRebuildStops() throws IOException {
        final String body = bodyOfFunnel(codeOnly(readTransport()), "rebuildConversation");
        final int charge = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        assertTrue("rebuildConversation does not charge the era budget", charge >= 0);
        // The block the refusal opens, brace-matched rather than a fixed window after the charge.
        final String refusalArm = blockAfter(body, charge);
        assertTrue("the era-budget charge in rebuildConversation is not followed by a block, so "
                + "this guard cannot see what happens on a refusal at all",
                refusalArm.length() > 0);
        // rebuildConversation returns an MlsRebuildOutcome; the refusal must stop as a refusal, not
        // as an outcome that is re-driven silently.
        assertTrue("the era-budget check in rebuildConversation does not return on refusal — a "
                + "budget whose answer is discarded is worse than no budget, because the next "
                + "reader believes it. The refusal arm reads: "
                + refusalArm.substring(0, Math.min(200, refusalArm.length())),
                refusalArm.contains("return MlsRebuildOutcome.REFUSED_BY_GUARD"));
    }

    /** The era budget is countable for a 1:1: a 1:1 has an era-budget key. */
    @Test
    public void theEraBudgetHasAKeyForAOneToOne() throws IOException {
        // Asked of MlsStateChangeGate directly, which is pure and on the host classpath.
        final MlsStateChangeGate.BudgetKey oneToOne =
                MlsStateChangeGate.eraBudgetKey(/*groupId=*/ null, "+15715550100");
        assertEquals("a 1:1 does not fall back to the PEER, so it has no budget key and its era "
                + "advances are uncounted — including one forced at a "
                + "wedged peer, which is exactly what the guard must count",
                MlsStateChangeGate.KeySource.PEER, oneToOne.source());
        assertEquals("peer:5715550100", oneToOne.key());

        // The wiring: the guard uses that derivation rather than its own.
        final String guard = codeOnly(readGuard());
        final String decide = bodyOf(guard, "decide", "final Tier tier");
        assertTrue("MlsPeerGuard.decide is gone", decide.length() > 0);
        assertTrue("MlsPeerGuard no longer derives its budget key from "
                + "MlsStateChangeGate.eraBudgetKey. A second derivation is how the 1:1 fell through "
                + "the guard in the first place.",
                decide.contains("MlsStateChangeGate.eraBudgetKey("));
    }

    /**
     * A guard in {@link #CHARGED_BY} that is not itself an {@code MlsPeerGuard} call is a transport
     * helper that makes one, so a row cannot be satisfied by any method name in the body.
     */
    @Test
    public void theGuardHelpersReachMlsPeerGuard() throws IOException {
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_BY) {
            final String g = row[3];
            if (g.startsWith("MlsPeerGuard.")) continue;
            final String helper = bodyOf(src, g.substring(0, g.length() - 1));
            assertTrue(g + " is named as a guard in CHARGED_BY but is not a method of "
                    + "MlsProviderTransport", helper.length() > 0);
            assertTrue(g + " is named as a guard in CHARGED_BY but never asks MlsPeerGuard "
                    + "anything, so the paths that rely on it are ungated",
                    helper.contains("MlsPeerGuard."));
        }
    }

    /**
     * Every offset at which {@code name} is invoked, by any receiver or none, including as a method
     * reference.
     */
    private static List<Integer> invocationsOf(final String src, final String name) {
        final List<Integer> out = new ArrayList<>();
        final Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*\\(|::\\s*" + Pattern.quote(name) + "\\b")
                .matcher(src);
        while (m.find()) out.add(src.indexOf(name, m.start()));
        return out;
    }

    private static String[] rowFor(final String method) {
        for (final String[] row : CHARGED_BY) {
            if (row[0].equals(method)) return row;
        }
        return null;
    }

    /**
     * The funnel overload of {@code name}, brace-matched. A delegate is identified structurally, a
     * body whose only statement returns a call to its own name, so a parameter change does not
     * break it. "" if every overload is a delegate or none is.
     */
    private static String bodyOfFunnel(final String src, final String name) {
        String best = "";
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = blockAfter(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            if (body.length() > best.length()) best = body;
        }
        return best;
    }

    /** A body whose only statement is {@code return name(…);}: a delegating overload. */
    private static boolean isDelegateTo(final String body, final String name) {
        final String flat = body.replaceAll("\\s+", "");
        return flat.matches("\\{return" + Pattern.quote(name) + "\\(.*\\);\\}");
    }

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if the arm is braceless. A
     * {@code ;} before the brace ends the search, or a braceless arm would borrow its neighbour's
     * block.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";        // braceless arm: that brace is not its own
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static int indexOfFirst(final String body, final String... needles) {
        int best = -1;
        for (final String n : needles) {
            final int at = body.indexOf(n);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** Start offset and name of every class-level method declaration, in source order. */
    private static List<int[]> declarations(final String src) {
        final List<int[]> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) out.add(new int[] {m.start(), m.start(1), m.end(1)});
        return out;
    }

    /** True if {@code at} is where a class-level method declares the name rather than calls it. */
    private static boolean isDeclaration(final List<int[]> decls, final int at) {
        for (final int[] d : decls) {
            if (d[1] == at) return true;
            if (d[0] > at) break;
        }
        return false;
    }

    private static String enclosingMethod(final String src, final List<int[]> decls, final int at) {
        int[] best = null;
        for (final int[] d : decls) {
            if (d[0] > at) break;
            best = d;
        }
        return best == null ? "<file scope>" : src.substring(best[1], best[2]);
    }

    /**
     * The brace-matched body of the first method whose declaration line contains {@code name}, or
     * "".
     */
    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The declaration only, up to the opening brace, so a method that calls the one asked
            // for does not answer for it; and the exact name, since "eraAdvance" prefixes its
            // delegates' names.
            final String decl = src.substring(m.start(), open);
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            if (!declFragment.isEmpty() && !decl.contains(declFragment)) continue;
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

    /**
     * Comments and string-literal contents blanked, character for character, so offsets still point
     * into the real file.
     */
    private static String codeOnly(final String src) {
        return blank(src, /*alsoStringContents=*/ true);
    }

    /** Comments blanked, string literals intact, for the one scan whose needle is a literal. */
    private static String withoutComments(final String src) {
        return blank(src, /*alsoStringContents=*/ false);
    }

    private static String blank(final String src, final boolean alsoStringContents) {
        final char[] out = src.toCharArray();
        final int n = out.length;
        int i = 0;
        while (i < n) {
            final char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                final char quote = c;
                i++;                                    // the quotes themselves are code
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        if (alsoStringContents) out[i] = ' ';
                        i++;
                        if (alsoStringContents && out[i] != '\n') out[i] = ' ';
                        i++;
                        continue;
                    }
                    if (alsoStringContents && out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * The transport as if unsplit: a Welcome-minting call that moved into an engine class is still
     * one of the transport's doors.
     */
    private static String readTransport() throws IOException {
        return com.android.messaging.rcs.SourceScan.transportAsUnsplit();
    }

    private static String readGuard() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java");
    }

    /** A source file relative to this module, resolved from the working directory. */
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
