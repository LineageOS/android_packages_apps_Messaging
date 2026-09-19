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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coverage claims about {@link MlsFetchLedger}'s refusal arms are rows in
 * {@link MlsFetchLedgerCoverage}, and each is held to checks that can fail: every
 * {@link MlsFetchLedger.Caller} has a row; a row counts every call site of its caller (scanned from
 * code, keyed on the constant's use); the host coverage a row names exists; and a reachability
 * claim has a falsifier that checks the fact it rests on. See docs/testing.md.
 */
public final class MlsFetchLedgerCoverageGuardTest {

    /**
     * Every caller has a row (enumerated from {@link MlsFetchLedger.Caller#values()}), and each
     * row's fields agree with its status.
     */
    @Test
    public void everyLedgerCallerHasACoverageRow() {
        final List<String> bad = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            final MlsFetchLedgerCoverage.Row r = MlsFetchLedgerCoverage.of(c);
            if (r == null) {
                bad.add(c + ": no row");
                continue;
            }
            if (r.caller != c) bad.add(c + ": row is keyed to " + r.caller);
            if (r.evidence == null || r.evidence.length() < 20) {
                bad.add(c + ": a row with no evidence is a status with nothing behind it");
            }
            if (r.makesAClaim()) {
                if (r.callSites < 1) {
                    bad.add(c + ": claims " + r.status + " but declares no call sites. A claim "
                            + "about an arm has to say how many arms it covers.");
                }
                if (r.hostTest == null) {
                    bad.add(c + ": claims " + r.status
                            + " and names no host test. The required shape "
                            + "is 'device-exercised OR declares what covers it instead'.");
                }
                bad.addAll(statusAgreesWithTheSites(r));
            } else {
                if (!r.sites.isEmpty()) {
                    bad.add(c + ": NOT_EXAMINED but declares call sites — a site with a state is a "
                            + "claim and this row is not making one.");
                }
                if (r.hostTest != null || r.claimFalsifier != null) {
                    bad.add(c + ": NOT_EXAMINED but names a test. Say what was examined instead.");
                }
            }
            if (r.status == MlsFetchLedgerCoverage.Status.DEVICE_REACHABLE_NOT_YET_RUN
                    && r.recipeDrives < 1) {
                bad.add(c + ": says a device can reach it and does not say how many drives the "
                        + "recipe takes. That number is what somebody at a device budgets against, "
                        + "and it is the first thing to go stale when a ration moves.");
            }
            // A recipe is for a row with an outstanding site, not for a particular status:
            // PARTIALLY_EXERCISED has both proven and outstanding sites.
            if (r.recipeDrives != MlsFetchLedgerCoverage.UNCOUNTED && r.sitesOutstanding() == 0) {
                bad.add(c + ": declares a recipe drive count with nothing outstanding to run it "
                        + "for. Every site is either settled or has no distinguishable refusal — "
                        + "delete the recipe or say which site it is still for.");
            }
            if (r.recipeDrives != MlsFetchLedgerCoverage.UNCOUNTED && r.claimFalsifier == null) {
                bad.add(c + ": records an adb recipe and nothing goes red when the fact it rests "
                        + "on stops being true. That is an unfalsifiable comment, retyped as a "
                        + "number somebody will take to a device.");
            }
            if (r.makesAReachabilityClaim() && r.claimFalsifier == null) {
                bad.add(c + ": claims " + r.status + " with no falsifier. That is exactly the "
                        + "unfalsifiable comment this class exists to eliminate, retyped as data.");
            }
            if (!r.makesAReachabilityClaim()
                    && r.blocker != MlsFetchLedgerCoverage.Blocker.NONE) {
                bad.add(c + ": names a blocker without claiming reachability is blocked.");
            }
            if (r.status == MlsFetchLedgerCoverage.Status.NO_ADB_FIXTURE_KNOWN
                    && r.blocker == MlsFetchLedgerCoverage.Blocker.NONE) {
                bad.add(c + ": says no fixture is known and names no bound. 'We could not think of "
                        + "one' and 'this bound prevents one' are different claims.");
            }
        }
        if (!bad.isEmpty()) fail("coverage rows are inconsistent with their own status: " + bad);
        assertEquals("every caller must have exactly one row",
                MlsFetchLedger.Caller.values().length, MlsFetchLedgerCoverage.rows().size());
    }

    /**
     * The declared status and the declared sites agree, so
     * {@link MlsFetchLedgerCoverage.Status#PARTIALLY_EXERCISED} cannot become a softer
     * {@code DEVICE_EXERCISED}. The two reachability statuses are predictions and require no proven
     * site.
     */
    private static List<String> statusAgreesWithTheSites(final MlsFetchLedgerCoverage.Row r) {
        final List<String> bad = new ArrayList<>();
        final int done = r.sitesExercised();
        final int of = r.exercisableSites();
        final String arithmetic = done + " of " + of + " exercisable site(s) (" + r.sites + ")";
        switch (r.status) {
            case DEVICE_EXERCISED:
                if (of < 1 || done != of) {
                    bad.add(r.caller + ": DEVICE_EXERCISED with " + arithmetic + ". Every "
                            + "exercisable site must have been refused on a device AND had its "
                            + "remedy seen; " + r.sitesIn(
                                    MlsFetchLedgerCoverage.SiteState.UNEXERCISED)
                            + " has not been reached and " + r.sitesIn(MlsFetchLedgerCoverage
                                    .SiteState.REFUSAL_OBSERVED_REMEDY_UNVERIFIED)
                            + " was reached on a build that predates the arm it is about.");
                }
                break;
            case PARTIALLY_EXERCISED:
                if (done < 1 || done >= of) {
                    bad.add(r.caller + ": PARTIALLY_EXERCISED with " + arithmetic + ". The value "
                            + "means SOME and not ALL — 0 is one of the reachability statuses or "
                            + "NOT_EXAMINED, and all of them is DEVICE_EXERCISED. This is the "
                            + "arithmetic that keeps it from drifting into the stronger value.");
                }
                break;
            case DEVICE_REACHABLE_NOT_YET_RUN:
            case NO_ADB_FIXTURE_KNOWN:
                if (done != 0) {
                    bad.add(r.caller + ": " + r.status + " with " + arithmetic + ". These two are "
                            + "PREDICTIONS about what a device could be made to do; a row with a "
                            + "site already proven has been run and must say so, "
                            + "or the row reads 'nobody has run it' while "
                            + "some of its sites are already device-exercised.");
                }
                break;
            default:
                break;
        }
        return bad;
    }

    /**
     * A coverage claim covers the caller's whole surface: {@code REBUILD} has three call sites (the
     * pack look, the opening look and the confirming look). Keyed on the constant's use in
     * {@link #codeOnly} output, with a word boundary so {@code REBUILD_SOMETHING} does not count.
     */
    @Test
    public void aCoverageClaimCountsEveryCallSiteOfItsCaller() throws IOException {
        final String src = codeOnly(readTransport()) + "\n" + codeOnly(readDebugReceiver());
        final List<String> wrong = new ArrayList<>();
        int claims = 0;
        int sitesSeen = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            final int found = occurrences(src,
                    Pattern.compile("MlsFetchLedger\\.Caller\\." + r.caller.name() + "\\b"));
            sitesSeen += found;
            if (!r.makesAClaim()) continue;
            claims++;
            if (found != r.callSites) {
                wrong.add(r.caller + ": row says " + r.callSites + " call site(s), source has "
                        + found);
            }
        }
        // Zero hits fail: a renamed constant or broken locator would satisfy every count by
        // absence.
        assertTrue(
                "no MlsFetchLedger.Caller constant was found at any call site — the needle or the "
                + "source locator is broken, and this guard is passing on an empty scan",
                sitesSeen >= MlsFetchLedger.Caller.values().length);
        assertTrue("no coverage row makes a claim, so nothing above was checked. A table of "
                + "NOT_EXAMINED rows is a record, not a guard.", claims >= 1);
        if (!wrong.isEmpty()) {
            fail("a coverage claim no longer covers its caller's call sites: " + wrong
                    + ". A caller that grew a site grew an ARM, and a claim written before that site "
                    + "existed cannot be inherited by it — re-derive the row (which of the sites has "
                    + "been reached on a device, and what covers the rest) rather than editing the "
                    + "number to match.");
        }
    }

    /**
     * Every {@code Class#method} a row names resolves to a real test method; a row naming a renamed
     * or deleted test would read as covered forever.
     */
    @Test
    public void everyClaimNamesHostCoverageThatExists() throws IOException {
        final List<String> missing = new ArrayList<>();
        int checked = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            for (final String ref : new String[] {r.hostTest, r.claimFalsifier}) {
                if (ref == null) continue;
                checked++;
                final int hash = ref.indexOf('#');
                if (hash <= 0 || hash == ref.length() - 1) {
                    missing.add(r.caller + ": '" + ref + "' is not Class#method");
                    continue;
                }
                final String cls = ref.substring(0, hash);
                final String method = ref.substring(hash + 1);
                final String body;
                try {
                    body = readTest(cls);
                } catch (final IOException e) {
                    missing.add(r.caller + ": " + cls + " does not exist under tests/");
                    continue;
                }
                if (!Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(")
                        .matcher(codeOnly(body)).find()) {
                    missing.add(r.caller + ": " + cls + " has no method " + method + "()");
                }
            }
        }
        assertTrue("no coverage row named a test, so this guard checked nothing", checked >= 2);
        if (!missing.isEmpty()) {
            fail("coverage rows name host tests that are not there: " + missing
                    + ". A row naming a "
                    + "test that does not exist reports the arm as covered and always will — the "
                    + "same shape. Point the row at the test that replaced it, or drop the claim.");
        }
    }

    /**
     * The era budget in {@code MlsPeerGuard} cannot be cleared from {@code adb}: it is the one
     * bound the stalled-conversation notification's Try again clears that limits the peer's cost
     * rather than ours. A debug lever may be right later (gated on {@code Build.TYPE} and listed in
     * {@code RcsDebugSendReceiver}'s {@code STATE_CHANGING}); when it lands this fails and names
     * the coverage claims to re-derive.
     */
    @Test
    public void theEraBudgetHasNoDebugLeverToday() throws IOException {
        final String needle =
                MlsFetchLedgerCoverage.Blocker.ERA_BUDGET_RATE.clearedBy;
        assertTrue("the blocker no longer names the method that would clear it, so this falsifier "
                + "has nothing to look for", needle != null && !needle.isEmpty());
        final String receiver = codeOnly(readDebugReceiver());
        // The debug receiver alone: MlsStalledActionReceiver calls resetEraBudget, which is a
        // person pressing Try again, the reset this bound is designed to have.
        assertTrue(
                "the debug receiver source did not load, or is empty — this falsifier is passing "
                + "on nothing", receiver.length() > 10_000);
        if (receiver.contains(needle)) {
            fail("RcsDebugSendReceiver now reaches " + needle
                    + "(), so MlsPeerGuard's era budget is "
                    + "clearable from adb. That may be the right call — but "
                    + "it invalidates every MlsFetchLedgerCoverage row whose reachability rests on "
                    + "the era budget, and the lever must be named in STATE_CHANGING so the "
                    + "peer allowlist runs for it (a gate that never runs is the failure above). "
                    + "Re-derive the rows, then update this check to reflect the new reachability.");
        }
    }

    /**
     * The falsifier for {@code REBUILD}'s recorded adb recipe: the opening look is charged before
     * the era budget is consulted (so the ration is spent by attempts and the era budget never
     * applies), and the recipe's drive count follows from the rations. That
     * {@code debug.rcs.mls_fetch_ceiling} is read live is a device property and is not checked
     * here.
     */
    @Test
    public void theAdbRecipeForTheRebuildArmStillHolds() throws IOException {
        final MlsFetchLedgerCoverage.Row row =
                MlsFetchLedgerCoverage.of(MlsFetchLedger.Caller.REBUILD);
        // Keyed on what is left to run, not on the status label.
        assertTrue("REBUILD has nothing outstanding, so there is no arm left for this recipe to "
                        + "reach and the recipe should be deleted rather than kept green: " + row,
                row.sitesOutstanding() >= 1);

        // (1) The charge order, read out of rebuildConversation's own body.
        final String body = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        assertTrue("rebuildConversation's body did not resolve — the scan has nothing to order",
                body.length() > 2_000);
        final int look = body.indexOf("MlsFetchLedger.Caller.REBUILD");
        final int eraBudget = body.indexOf("MlsPeerGuard.allowEraAdvance(");
        assertTrue("no MlsFetchLedger.Caller.REBUILD in rebuildConversation — the opening look has "
                + "moved or been renamed, and the recipe is written about a call that is not there",
                look >= 0);
        assertTrue("no MlsPeerGuard.allowEraAdvance( in rebuildConversation — the era budget is no "
                + "longer charged here, which changes the interlock this recipe navigates",
                eraBudget >= 0);
        assertTrue("the rebuild's opening look is now charged AFTER MlsPeerGuard.allowEraAdvance. "
                        + "That reverses the second interlock: the ledger would then be "
                        + "spent only by rebuilds the era budget ALLOWED, so REBUILD's ration could "
                        + "never be reached from adb and the recipe on the coverage row is dead. "
                        + "Re-derive the row before changing this order deliberately.",
                look < eraBudget);

        // (2) The arithmetic. A rebuild that does not converge never reaches the confirming look
        // and spends one REBUILD look, and within a run ENSURE_READY exhausts its own ration first,
        // so the worst case is one look per attempt and the recipe needs REBUILD.ration + 1 drives.
        // The drive's charge lands on a ledger keyed "<no conversation key>", so RECONCILE_DRIVE's
        // ration does not compete. The row's drive count stays a literal: deriving it from the
        // ration would compare a number with itself.
        final int rebuildRation = MlsFetchLedger.Caller.REBUILD.ration;
        assertEquals("REBUILD's ration is " + rebuildRation + ", so the recorded adb recipe needs "
                        + (rebuildRation + 1) + " drives inside one WINDOW_MS in the WORST case — "
                        + "one attempt per look, where no rebuild converges and each spends only "
                        + "its opening look, plus the attempt that is refused. (The one device run "
                        + "so far took 4, because its first rebuild DID converge and earned the "
                        + "confirming look too.) The row says " + row.recipeDrives
                        + ". Changing the "
                        + "ration changes a plan somebody is about to run on hardware, and it must "
                        + "not go stale silently: re-derive the row and warn whoever is at the "
                        + "device, rather than editing the number to match.",
                rebuildRation + 1, row.recipeDrives);

        // The constraint that binds: ENSURE_READY outspends REBUILD inside a single rebuild. If
        // that inverts, the row's drive count becomes an overestimate.
        assertTrue("ENSURE_READY's ration (" + MlsFetchLedger.Caller.ENSURE_READY.ration + ") no "
                        + "longer sits at or below REBUILD's (" + rebuildRation + "). The device "
                        + "run's whole shape — a second rebuild that does not converge, so the "
                        + "confirming look is never charged — depends on ENSURE_READY running out "
                        + "first, because it spends 3 per rebuild against REBUILD's 2. Re-derive "
                        + "the row's recipe before anyone runs it again.",
                MlsFetchLedger.Caller.ENSURE_READY.ration <= rebuildRation);
    }

    /**
     * A site exempted as {@link MlsFetchLedgerCoverage.SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM}
     * really discards its look: {@code .orNull()} is applied in the same statement as the
     * {@code MlsFetchLedger.Caller} constant, so a refusal and "nothing" are indistinguishable. The
     * counts must be equal. A discard in a later statement (as in {@code ENSURE_READY}'s reclaim)
     * reads as a coverage gap, the conservative direction.
     */
    @Test
    public void anUnexercisableSiteReallyDiscardsItsLook() throws IOException {
        final String src = codeOnly(readTransport()) + "\n" + codeOnly(readDebugReceiver());
        int discardsAnywhere = 0;
        final List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (final MlsFetchLedgerCoverage.Row r : MlsFetchLedgerCoverage.rows()) {
            final int discards = sitesDiscardingTheirLook(src, r.caller);
            discardsAnywhere += discards;
            if (r.sites.isEmpty()) continue;
            checked++;
            final List<String> exempt =
                    r.sitesIn(MlsFetchLedgerCoverage.SiteState.NO_DISTINGUISHABLE_REFUSAL_ARM);
            if (exempt.size() != discards) {
                wrong.add(r.caller + ": the row exempts " + exempt.size() + " site(s) from the "
                        + "denominator " + exempt + ", and " + discards + " of its call sites "
                        + "actually discard the look");
            }
        }
        // Zero hits fail: the needle is negative for most rows, so it must find something first.
        assertTrue(
                "no call site anywhere applies .orNull() in the statement that names its Caller. "
                + "Either the needle or the statement scan is broken, and every 'this site has no "
                + "distinguishable refusal' claim below is being confirmed by an empty scan.",
                discardsAnywhere >= 1);
        assertTrue("no coverage row declares any sites, so this checked nothing", checked >= 1);
        if (!wrong.isEmpty()) {
            fail("A site's exemption from the coverage denominator does not match the source: "
                    + wrong + ". Exempting a site that HAS a distinguishable refusal arm hides a "
                    + "real gap; refusing to exempt one that does not leaves a caller permanently "
                    + "short of DEVICE_EXERCISED for a reason nobody can close. Re-derive the row "
                    + "against the call sites, rather than editing the count to agree.");
        }
    }

    /**
     * {@code ENSURE_READY}'s distinguishable sites each test {@code refused()} twice, with and
     * without {@code stateAlreadyDestroyed} (the decline and the fail-open when a rebuild already
     * dropped the state), and its exempt site tests it not at all; derived from the row.
     */
    @Test
    public void bothDistinguishableEnsureReadyArmsStillDecline() throws IOException {
        final MlsFetchLedgerCoverage.Row row =
                MlsFetchLedgerCoverage.of(MlsFetchLedger.Caller.ENSURE_READY);
        final String body = bodyOf(codeOnly(readTransport()), "ensureReady");
        assertTrue("ensureReady's body did not resolve — this guard has nothing to read", 
                body.length() > 5_000);
        final List<Integer> at = new ArrayList<>();
        final Matcher m =
                Pattern.compile("MlsFetchLedger\\.Caller\\.ENSURE_READY\\b").matcher(body);
        while (m.find()) at.add(Integer.valueOf(m.start()));
        assertEquals("ensureReady no longer holds the call sites the coverage row is about — the "
                        + "sites moved and the row must move with them",
                row.sites.size(), at.size());

        final List<String> wrong = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) {
            final int from = at.get(i).intValue();
            final int to = (i + 1 < at.size()) ? at.get(i + 1).intValue() : body.length();
            final String arm = body.substring(from, to);
            final MlsFetchLedgerCoverage.Site site = row.sites.get(i);
            final int refused = occurrences(arm, Pattern.compile("\\.refused\\(\\)"));
            final int destroyed = occurrences(arm, Pattern.compile("\\bstateAlreadyDestroyed\\b"));
            if (site.state.exercisable()) {
                if (refused < 2) {
                    wrong.add(site.name + ": " + refused + " refused() test(s), 2 required — the "
                            + "decline and the fail-open are different arms and a site with one of "
                            + "them has lost the other");
                }
                if (destroyed < 1) {
                    wrong.add(site.name + ": no stateAlreadyDestroyed test. Without it the arm "
                            + "either always declines (leaving a rebuilt conversation with nothing "
                            + "at all) or always proceeds (re-creating over a conversation the "
                            + "server holds at an era we declined to read — the second door).");
                }
            } else if (refused != 0) {
                wrong.add(site.name + ": the row exempts this site as having no distinguishable "
                        + "refusal, and it tests refused() " + refused + " time(s)");
            }
        }
        if (!wrong.isEmpty()) {
            fail("ENSURE_READY's refusal arms no longer match what its coverage row says about "
                    + "them: " + wrong);
        }
    }

    /**
     * How many of {@code caller}'s call sites discard their look in the statement that names it:
     * the span from the constant to the next {@code ;} in {@link #codeOnly} output.
     */
    private static int sitesDiscardingTheirLook(final String src, final MlsFetchLedger.Caller c) {
        final Matcher m =
                Pattern.compile("MlsFetchLedger\\.Caller\\." + c.name() + "\\b").matcher(src);
        int n = 0;
        while (m.find()) {
            final int end = src.indexOf(';', m.end());
            if (end < 0) continue;
            if (src.substring(m.start(), end).contains(".orNull()")) n++;
        }
        return n;
    }

    private static int occurrences(final String src, final Pattern p) {
        final Matcher m = p.matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * The brace-matched body of {@code name}, skipping a delegating overload:
     * {@code rebuildConversation}'s two-argument overload only returns a call to the other.
     */
    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            return body;
        }
        return "";
    }

    private static boolean isDelegateTo(final String body, final String name) {
        final String inner = body.trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) return false;
        final String stmt = inner.substring(1, inner.length() - 1).trim();
        return stmt.startsWith("return " + name + "(") && stmt.indexOf(';') == stmt.length() - 1;
    }

    private static String bracedBlock(final String src, final int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    /**
     * The source with comments and string contents blanked, offsets preserved, so a constant in a
     * log message is not counted as a call site.
     */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        final int n = out.length;
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
                i++;
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        i++;
                        if (out[i] != '\n') out[i] = ' ';
                        i++;
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String readTransport() throws IOException {
        // The transport's code as if unsplit: delegates and the MlsShellPort adapter blanked, moved
        // methods restored (see SourceScan.transportAsUnsplit).
        return com.android.messaging.rcs.SourceScan.transportAsUnsplit();
    }

    private static String readDebugReceiver() throws IOException {
        return read("src/com/android/messaging/rcs/RcsDebugSendReceiver.java");
    }

    private static String readTest(final String simpleClassName) throws IOException {
        return read("tests/src/com/android/messaging/rcs/engine/mls/" + simpleClassName + ".java");
    }

    /** Works from the module dir or the tree root, like the sibling guards. */
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
