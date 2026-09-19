/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Every path that spends a server group-info read is charged to {@link MlsFetchLedger}. The
 * primitives are enumerated from {@link MlsFetchLedger.Primitive} at runtime, and three properties
 * hold: every primitive invocation sits in a charged wrapper, every caller of a wrapper or funnel
 * names its {@code Caller}, and nothing reaches the primitives around the transport. Negative
 * controls for these scans comment the regression out rather than delete it, and run against a copy
 * of the transport. See docs/mls/budgets.md and docs/testing.md.
 */
public final class MlsGetGroupInfoLedgerGuardTest {

    /** The only methods allowed to invoke a primitive, and the primitive each must charge. */
    private static final String[][] CHARGED_WRAPPERS = {
        {"lookMissedCommits", "FETCH_MISSED_COMMITS"},
        {"lookServerEpochAuthenticator", "FETCH_SERVER_EPOCH_AUTHENTICATOR"},
        {"lookGroupInfoForGroup", "GET_MLS_GROUP_INFO_FOR_GROUP"},
        {"lookServerEraEpoch", "GET_MLS_SERVER_ERA_EPOCH"},
        {"lookGroupInfo", "GET_MLS_GROUP_INFO"},
    };

    /** The one method that asks the ledger; every wrapper must go through it. */
    private static final String CHARGE_POINT = "spendOneLook(";

    /**
     * Methods whose first argument must be an {@code MlsFetchLedger.Caller}: the wrappers plus the
     * funnels that pass one down. A funnel has no ration of its own, because charging the funnel
     * would put many different decisions under one ration.
     */
    private static final String[] MUST_DECLARE_A_CALLER = {
        "lookMissedCommits",
        "lookServerEpochAuthenticator",
        "lookGroupInfoForGroup",
        "lookServerEraEpoch",
        "lookGroupInfo",
        "fetchServerPack",
        "serverStateCheck",
        // The funnel for a group the host has not adopted; `serverStateCheck\s*\(` does not match
        // it.
        "serverStateCheckFor",
        "detectHealth",
        // detectHealth's comparison, shared with runMaintenanceOnce; it spends an
        // epoch-authenticator read on its equal-era-and-epoch arm for two different callers.
        "healthAgainstServer",
    };

    /**
     * The transport methods allowed to pass an exempt caller, named rather than matched by prefix:
     * a prefix rule would exempt any method whose name happens to start with {@code debug} or
     * {@code dump}. Each is an operator diagnostic that returns what it read and changes nothing.
     */
    private static final java.util.Set<String> MAY_PASS_AN_EXEMPT_CALLER =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dumpGroupExtensions",    // --ez groupexts
                    "debugServerEraEpoch",    // --ez serverera
                    "debugGroupInfo",         // --ez ctrl
                    // --ez servervalidity: parses the ratchet tree the same fetch returns and logs
                    // leaf identities and certificate windows; reads only.
                    "dumpServerValidity"));

    private static final String TRANSPORT = "e2ee/MlsProviderTransport.java";
    private static final String PROVIDER_SHIM = "rcs/ProviderTransport.java";
    private static final String RPC_BINDING = "e2ee/MlsProviderRpcBinding.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * Keyed on the invoked method name, never on the {@code pt("…")} argument: {@code pt(String)}
     * is a lock-assertion probe whose label is free-form, so a call through a held {@code
     * ProviderTransport} local or under a caller-named label would be missed.
     */

    @Test
    public void everyPrimitiveCallSiteIsInsideAChargedWrapper() throws IOException {
        // Code only: the primitive names also appear in comments and log strings. Blanked in place,
        // so offsets still match the source.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        int sites = 0;
        final List<String> stray = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        final List<String> duplicated = new ArrayList<>();
        for (final MlsFetchLedger.Primitive p : MlsFetchLedger.Primitive.values()) {
            int perPrimitive = 0;
            final String needle = "." + p.aidlName + "(";
            int at = src.indexOf(needle);
            while (at >= 0) {
                sites++;
                final String enclosing = enclosingMethod(src, decls, at);
                if (wrapperRowFor(enclosing) == null) {
                    stray.add(p.aidlName + " in " + enclosing + "()");
                }
                perPrimitive++;
                at = src.indexOf(needle, at + 1);
            }
            // Every primitive has exactly one invocation, inside its wrapper, so the count is
            // checked per primitive; a total alone would pass if one dropped to zero while another
            // gained a site.
            if (perPrimitive == 0) {
                vanished.add(p.aidlName);
            } else if (perPrimitive != 1) {
                duplicated.add(p.aidlName + " x" + perPrimitive);
            }
        }
        if (!vanished.isEmpty()) {
            fail("This guard found NO invocation of " + vanished
                    + " in MlsProviderTransport, so it "
                    + "is no longer watching those doors — and a scan that misses its subject "
                    + "passes silently, which is how an I2 guard fails in exactly the manner the "
                    + "invariant exists to prevent. This is NOT a complaint about naming: the guard "
                    + "enumerates DOORS TO A SCARCE SERVER RESOURCE, and it has just certified a "
                    + "transport it can no longer see. Either the wrapper was renamed or removed "
                    + "(re-point CHARGED_WRAPPERS), the primitive was renamed provider-side (update "
                    + "MlsFetchLedger.Primitive.aidlName), or the file moved (fix read()).");
        }
        if (!duplicated.isEmpty()) {
            fail("These primitives are invoked more than once in MlsProviderTransport: "
                    + duplicated
                    + ". Exactly one invocation each is the whole architecture — the wrapper is the "
                    + "single charge point, and a second invocation is a second door whether or not "
                    + "it happens to sit inside a wrapper too. The rule is one ledger per resource "
                    + "enumerated from its primitive; two wrappers over one primitive is two "
                    + "ledgers wearing one name.");
        }
        assertEquals(
                "the number of primitive INVOCATIONS no longer matches the number of wrappers. "
                + "Each wrapper contains exactly one, so any other count means either a wrapper lost "
                + "its call or a site appeared outside one — including one reached through a local "
                + "ProviderTransport variable, which is how five sites hid from the first version of "
                + "this guard.",
                MlsFetchLedger.Primitive.values().length, sites);
        if (!stray.isEmpty()) {
            fail("These calls spend a GetMlsGroupInfo from a method that is not a charged wrapper, "
                    + "so the RPC happens with nothing counting it: " + stray
                    + ". Do NOT add a new "
                    + "pt(\"…\") site — route the call through the matching look*() wrapper and pass "
                    + "the MlsFetchLedger.Caller that owns the decision. If the primitive is new, "
                    + "add it to MlsFetchLedger.Primitive and give it a wrapper.");
        }
    }

    @Test
    public void everyWrapperReachesTheChargePoint() throws IOException {
        // Code only: a charge left behind in a comment would otherwise satisfy every assertion.
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_WRAPPERS) {
            final String body = bodyOf(src, row[0]);
            assertTrue(row[0] + "() is gone — the wrapper for " + row[1] + " has been renamed or "
                    + "removed, and this guard can no longer see whether its primitive is charged",
                    body.length() > 0);
            assertTrue(row[0] + "() no longer calls " + CHARGE_POINT
                    + " — it makes the RPC without "
                    + "asking the ledger, which is a budget whose answer is discarded. That reads as "
                    + "a guard in review and is a no-op at runtime.",
                    body.contains(CHARGE_POINT));
            assertTrue(row[0] + "() no longer names MlsFetchLedger.Primitive." + row[1]
                    + ", so the ledger is charging the wrong resource or none",
                    body.contains("MlsFetchLedger.Primitive." + row[1]));
            final String aidl = MlsFetchLedger.Primitive.valueOf(row[1]).aidlName;
            assertTrue(row[0] + "() no longer invokes ." + aidl
                    + "(), so it declares a primitive it "
                    + "does not spend — the ledger would be charging a resource nobody is using and "
                    + "the real spend would be somewhere this guard is not looking",
                    body.contains("." + aidl + "("));
        }
    }

    /**
     * The charge point consults {@link MlsFetchLedger#mayFetch} and refuses before the RPC. Asking
     * and ignoring the answer is worse than never asking, because the next reader believes it.
     */
    @Test
    public void aRefusedLookDoesNotReachTheServer() throws IOException {
        // Code only, blanked in place: this test compares offsets.
        final String body = bodyOf(codeOnly(readTransport()), "spendOneLook");
        assertTrue("spendOneLook() is gone — every wrapper's charge went with it",
                body.length() > 0);
        assertTrue("spendOneLook() does not ask MlsFetchLedger.mayFetch at all",
                body.contains("MlsFetchLedger.mayFetch("));
        final int ask = body.indexOf("MlsFetchLedger.mayFetch(");
        final int refuse = body.indexOf("Look.refusedByLedger(", ask);
        assertTrue(
                "spendOneLook() asks the ledger and has no arm that returns a refusal — a budget "
                + "whose answer is discarded is worse than no budget", refuse >= 0);

        // Checked per occurrence of doIt.look(), not from the ask onward: a look hoisted above
        // mayFetch() must be visible. The unreadable-ledger arm legitimately looks before the ask
        // (permitted by isExempt()), so each occurrence's justification is named.
        final List<Integer> looks = new ArrayList<>();
        int at = body.indexOf("doIt.look()");
        while (at >= 0) {
            looks.add(at);
            at = body.indexOf("doIt.look()", at + 1);
        }
        assertEquals("spendOneLook() has " + looks.size()
                + " call(s) to doIt.look() and this guard "
                + "knows the context of exactly three: the unreadable-ledger arm (permitted by "
                + "isExempt() rather than by a verdict), the SPEND_EXEMPT arm, and the charged look. "
                + "A FOURTH is a look whose permission this check cannot account for — and the "
                + "failure mode is a GetMlsGroupInfo made before the bound applies, which we "
                + "know draws grpcStatus=8 RESOURCE_EXHAUSTED at ten on one "
                + "conversation.",
                3, looks.size());

        final int first = looks.get(0);
        assertTrue("the FIRST doIt.look() in spendOneLook() is not inside an arm whose condition "
                + "names isExempt(). It runs before the ledger is consulted, so its only possible "
                + "justification is that this caller can never be refused — and nothing there says "
                + "so. Condition read: " + conditionGoverning(body, first),
                conditionGoverning(body, first).contains("isExempt()"));
        assertTrue("the first doIt.look() is at " + first + " and the ask at " + ask + " — the "
                + "exempt arm must come BEFORE the ask, or it is not the arm this guard permits",
                first < ask);

        for (int i = 1; i < looks.size(); i++) {
            final int later = looks.get(i);
            assertTrue("spendOneLook() performs a look at " + later + " before it asks the ledger ("
                    + ask + "). Only the exempt arm may look without asking.", ask < later);
            assertTrue("spendOneLook() performs a look at " + later + " before it can refuse "
                    + "(refusal at " + refuse + "). A refusal after the RPC has been made bounds "
                    + "nothing: the resource is already spent.", refuse < later);
        }
    }

    /**
     * The condition of the {@code if} governing the statement at {@code at}, read by structure
     * rather than as a character window before it.
     */
    private static String conditionGoverning(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return "";
        final int open = body.indexOf('{', ifAt);
        if (open < 0 || open > at) return "";
        return body.substring(ifAt, open);
    }

    @Test
    public void everyCallerDeclaresTheLedgerItCharges() throws IOException {
        // Code only: the funnel names also appear in comments and log strings.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        final List<String> undeclared = new ArrayList<>();
        int calls = 0;
        for (final String funnel : MUST_DECLARE_A_CALLER) {
            final Matcher m = Pattern.compile("\\b" + Pattern.quote(funnel) + "\\s*\\(")
                    .matcher(src);
            while (m.find()) {
                // Skip the declaration itself; only calls carry an argument list.
                if (isDeclaration(src, decls, m.start())) continue;
                calls++;
                final String args = argumentPrefix(src, m.end());
                if (!args.contains("MlsFetchLedger.Caller.")
                        && !args.startsWith("caller")) {
                    undeclared.add(funnel + "() in " + enclosingMethod(src, decls, m.start())
                            + "() — first argument was: " + args.trim());
                }
            }
        }
        assertTrue("no calls to any charged wrapper or funnel matched — the names have changed and "
                + "this guard is silently passing", calls >= MUST_DECLARE_A_CALLER.length);
        if (!undeclared.isEmpty()) {
            fail("These calls spend a GetMlsGroupInfo without naming the ledger they charge, so a "
                    + "new path would inherit a ration by omission — the exact shape of the defect "
                    + "this guard closed: " + undeclared + ". Pass the MlsFetchLedger.Caller that "
                    + "owns the decision (or the enclosing method's own `caller` parameter if it is "
                    + "itself a funnel), and add a constant to MlsFetchLedger.Caller if none fits.");
        }
    }

    /**
     * Every live {@link MlsFetchLedger.Caller} is passed at a call site, and every retired ({@code
     * @Deprecated}) one at none. A retired constant is kept because {@link MlsFetchLedgerRecord}
     * persists callers by ordinal, and the check flips for it rather than skipping it, so the
     * annotation cannot silence a live door.
     */
    @Test
    public void everyCallerConstantIsActuallyUsed() throws IOException {
        // Code only: a constant named in a comment would read as a live door.
        final String src = codeOnly(readTransport()) + codeOnly(readDebugReceiver());
        final List<String> unusedAndLive = new ArrayList<>();
        final List<String> retiredButStillCharged = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            final boolean used = src.contains("MlsFetchLedger.Caller." + c.name());
            if (isRetired(c)) {
                if (used) retiredButStillCharged.add(c.name());
            } else if (!used) {
                unusedAndLive.add(c.name());
            }
        }
        if (!unusedAndLive.isEmpty()) {
            fail("These MlsFetchLedger.Caller constants are passed at no call site: "
                    + unusedAndLive
                    + ". A ration nobody spends looks like a bounded door and is not one — either "
                    + "the door was renamed and its constant left behind, or the constant was added "
                    + "for a caller that never landed. Wire it — or, if its caller was deliberately "
                    + "DELETED, mark the constant @Deprecated and say in its javadoc what "
                    + "replaced it. DO NOT DELETE THE CONSTANT: MlsFetchLedgerRecord persists the "
                    + "caller by ORDINAL, so removing one shifts every later constant and silently "
                    + "reattributes ledger rows already on disk.");
        }
        if (!retiredButStillCharged.isEmpty()) {
            fail("These MlsFetchLedger.Caller constants are @Deprecated (retired) but ARE "
                    + "still passed at a call site: " + retiredButStillCharged + ". RETIRED means "
                    + "the door is gone and the ordinal is only reserved; a live charge against one "
                    + "is either a caller that came back without the annotation being removed, or the "
                    + "annotation being used to silence this test for a door that is still open.");
        }
    }

    /**
     * A retired caller keeps its ordinal (the ledger persists it) and is marked {@code
     * @Deprecated}.
     */
    private static boolean isRetired(final MlsFetchLedger.Caller c) {
        try {
            return MlsFetchLedger.Caller.class.getField(c.name())
                    .isAnnotationPresent(Deprecated.class);
        } catch (final NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** Exactly one caller may fail open. */
    @Test
    public void onlyThePeerReportVerificationFailsOpen() {
        final List<String> failOpen = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.failsOpenOnRefusal()) failOpen.add(c.name());
        }
        assertEquals(
                "fail-open is a deliberate, single exception (decision D3): the cost of acting "
                + "on a real report we could not verify is a redundant repair, while the cost of "
                + "dropping one is a peer that stays diverged forever. Any OTHER caller failing "
                + "open would be spending the resource and then ignoring the refusal, which is a "
                + "budget in name only.",
                java.util.Collections.singletonList("PEER_REPORT_VERIFY"), failOpen);
    }

    /**
     * The fail-open caller must still charge and must say what it did: proceeding without charging
     * would leave a peer-driven spend uncounted.
     */
    @Test
    public void theFailOpenCallerChargesAndSaysSo() throws IOException {
        final String body = codeOnly(bodyOf(readTransport(), "reporterIsAMember"));
        assertTrue("reporterIsAMember() is gone — the fail-open exception has moved and this guard "
                + "no longer watches it", body.length() > 0);
        assertTrue("reporterIsAMember() no longer charges the ledger. Its rate is driven by a PEER "
                + "rather than by a loop of ours (onPeerReportedFailure calls it once per report, "
                + "and PEER_FTD_ESCALATE_AT bounds the escalation, not this fetch), so an uncounted "
                + "spend here is the one most likely to surprise the next recovery.",
                body.contains("MlsFetchLedger.Caller.PEER_REPORT_VERIFY"));
        assertTrue(
                "reporterIsAMember() no longer returns true on a refusal — it has stopped failing "
                + "open, and our own rate ledger is now acting as evidence against a peer. Dropping "
                + "a real report leaves that peer diverged forever.",
                body.contains("describeFailOpen("));
    }

    /** A debug arm is exempt and says so in the log. */
    @Test
    public void theDebugArmIsExemptAndSaysSo() throws IOException {
        assertTrue("DEBUG_DUMP is no longer exempt — a debug arm refused for budget is useless, "
                + "because the operator ran it precisely when something is already wrong",
                MlsFetchLedger.Caller.DEBUG_DUMP.isExempt());
        assertTrue("DEBUG_HEALTH is no longer exempt",
                MlsFetchLedger.Caller.DEBUG_HEALTH.isExempt());
        final String body = codeOnly(bodyOf(readTransport(), "spendOneLook"));
        assertTrue("spendOneLook() no longer logs describeExemption for an exempt caller. A debug "
                + "arm that silently spent a recovery look would be lying about what the operator's "
                + "next recovery has left.", body.contains("describeExemption("));
    }

    /**
     * {@code ensureReady}'s era reads run after {@code rebuildConversation} has forgotten both
     * halves of our state and cannot be hoisted above the forget, so the rebuild passes {@code
     * stateAlreadyDestroyed=true} and those refusal arms fail open.
     */
    @Test
    public void aLedgerRefusalAfterTheForgetFailsOpen() throws IOException {
        // The ordering is a fact about code, so it reads code only; the flag is carried by a
        // named-argument comment, so that one assertion reads raw.
        final String raw = readTransport();
        final String src = codeOnly(raw);
        final String rebuild = bodyOf(src, "rebuildConversation");
        final String rebuildRaw = bodyOf(raw, "rebuildConversation");
        assertTrue("rebuildConversation not found — it has been renamed, and it is the one caller "
                + "that reaches ensureReady with our state already dropped", rebuild.length() > 0);
        final int destroy = indexOfFirst(rebuild,
                "mlsForgetGroupConversation(", "mlsForgetConversation(");
        final int ensure = rebuild.indexOf("ensureReady(");
        assertTrue("rebuildConversation no longer drops the provider half — the ordering this test "
                + "is about has changed and it no longer means anything", destroy >= 0);
        assertTrue("rebuildConversation no longer reaches ensureReady", ensure >= 0);
        assertTrue("ensureReady is now called BEFORE the forget (" + ensure + " vs " + destroy
                + "). If that is deliberate the fail-open flag may no longer be needed — but check, "
                + "because this test was written on the opposite ordering.", ensure > destroy);
        assertTrue(
                "rebuildConversation reaches ensureReady AFTER dropping both halves of our state "
                + "and does not pass stateAlreadyDestroyed=true. A fetch-ledger refusal inside "
                + "ensureReady would then DECLINE, leaving the conversation with no provider "
                + "record, no engine state and no group — the exact outcome rebuildConversation "
                + "hoists its guards above the forget to prevent.",
                rebuildRaw.contains("stateAlreadyDestroyed=*/ true"));

        // Each refusal arm is read as its brace-matched block, not a character window.
        final String ready = bodyOf(src, "ensureReady", "final boolean stateAlreadyDestroyed)");
        assertTrue("ensureReady's stateAlreadyDestroyed overload is gone", ready.length() > 0);
        int at = ready.indexOf(".refused()");
        int arms = 0;
        while (at >= 0) {
            arms++;
            final int ifAt = ready.lastIndexOf("if (", at);
            assertTrue(
                    "a .refused() in ensureReady is not inside an if — this guard cannot read the "
                    + "arm and must not certify it", ifAt >= 0);
            final int open = ready.indexOf('{', at);
            assertTrue("a refusal arm in ensureReady has no block", open >= 0);
            // The brace must open the arm, not follow a braceless statement.
            final int semi = ready.indexOf(';', at);
            assertTrue("the refusal arm at " + at + " has a statement before its block, so this "
                    + "guard would read a NEIGHBOUR's braces as the arm — a span bounded by "
                    + "whatever comes next is not an arm", semi < 0 || open < semi);
            final String condition = ready.substring(ifAt, open);
            final String block = bracedBlock(ready, open);
            if (block.contains("return false")) {
                assertTrue("A REFUSAL ARM IN ensureReady DECLINES WITHOUT CHECKING "
                        + "stateAlreadyDestroyed: " + condition.trim() + ". Reached from "
                        + "rebuildConversation both halves of our state are already gone, so this "
                        + "return leaves the conversation with no provider record, no engine state "
                        + "and no group — strictly worse than the churn the refusal prevents, and "
                        + "the exact outcome rebuildConversation hoists its guards above the forget "
                        + "to avoid.",
                        condition.contains("!stateAlreadyDestroyed"));
            }
            at = ready.indexOf(".refused()", at + 1);
        }
        assertTrue(
                "no ledger refusal arms found in ensureReady — either the era reads stopped being "
                + "charged (which would be a hole) or they were renamed, and either way this guard "
                + "is no longer watching anything", arms >= 2);
    }

    /**
     * {@code !repaired()} raises the stalled-conversation notification, so a refused verification
     * must not collapse into {@code NOT_CONVERGED}.
     */
    @Test
    public void anUnverifiedRebuildIsNotReportedAsAFailedOne() throws IOException {
        final String rebuild = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        final int refused = rebuild.indexOf("ServerState.REFUSED_BY_LEDGER");
        assertTrue("rebuildConversation no longer distinguishes a refused confirming look at all — "
                + "it is reporting an unverified rebuild as a confirmed outcome", refused >= 0);
        // The arm's own block, brace-matched, rather than a fixed window from the marker.
        final String after = armContaining(rebuild, refused);
        assertTrue("rebuildConversation detects the refused verification and does not return "
                + "RAN_BUT_UNVERIFIED from THAT ARM, so it falls through to NOT_CONVERGED — which "
                + "raises a stall alert to a person about a rebuild nobody verified. "
                + "A return somewhere else in the method does not count: this must be the arm the "
                + "refusal takes.",
                after.contains("MlsRebuildOutcome.RAN_BUT_UNVERIFIED"));
        assertFalse(
                "RAN_BUT_UNVERIFIED must not reach a person: its exit is our own window passing, "
                + "which no Try again shortens",
                MlsRebuildOutcome.RAN_BUT_UNVERIFIED.needsAPerson());
    }

    /**
     * A refused opening look correctly stops the rebuild (proceeding would treat an unasked
     * question as a first create and charge nothing), but it must return {@code
     * DEFERRED_BY_OUR_OWN_LEDGER}, not {@code REFUSED_BY_GUARD}: no guard ran, and Try again does
     * not reset the fetch ledger. Pinned on the arm, not the method.
     */
    @Test
    public void aLedgerRefusalBeforeTheRebuildIsNotReportedAsAGuardRefusal() throws IOException {
        final String rebuild = bodyOf(codeOnly(readTransport()), "rebuildConversation");
        final int opening = rebuild.indexOf("MlsFetchLedger.Caller.REBUILD");
        assertTrue(
                "rebuildConversation no longer charges an opening look at all — the question that "
                + "decides whether this rebuild is billed to the era budget is not being asked, "
                + "which re-opens the second door", opening >= 0);
        final int refusalArm = rebuild.indexOf(".refused()", opening);
        assertTrue("rebuildConversation charges the opening look and never tests whether it was "
                + "refused — a ledger whose answer is discarded is worse than no ledger",
                refusalArm >= 0);
        final String arm = armContaining(rebuild, refusalArm);
        assertTrue(
                "the opening look's refusal arm does not return DEFERRED_BY_OUR_OWN_LEDGER. If it "
                + "returns REFUSED_BY_GUARD it is claiming a peer-protecting guard refused when none "
                + "ran, and raising a stall alert whose Try again cannot reach the fetch ledger "
                + ". Arm read: " + arm.trim(),
                arm.contains("MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER"));
        assertFalse("that arm must NOT still return REFUSED_BY_GUARD",
                arm.contains("MlsRebuildOutcome.REFUSED_BY_GUARD"));
        assertFalse("DEFERRED_BY_OUR_OWN_LEDGER must not reach a person: Try again resets the "
                + "self-heal budget, the rebuild rate bound and its episode suppressor, the era "
                + "budget, the peer-health streak, the re-establish cooldown and the external-commit "
                + "allowance — and NOT the fetch ledger, whose only exit is its own window passing",
                MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER.needsAPerson());
    }

    /**
     * {@code serverPackForRebuild}'s four ways to have no pack (a 1:1, no local state, our ledger
     * refusing, the look returning nothing or throwing) each return their own {@link
     * MlsServerPackOutcome}. Every arm degrades the rebuild the same way, so what this pins is the
     * diagnostic, including which arms spent a look.
     */
    @Test
    public void theFourWaysToHaveNoServerPackDoNotShareAValue() throws IOException {
        final String body = bodyOf(codeOnly(readTransport()), "serverPackForRebuild");
        assertFalse("serverPackForRebuild is not in MlsProviderTransport under that name — this "
                + "check is scanning nothing, which is the strongest form of a guard reporting "
                + "green", body.isEmpty());

        // Per occurrence: each return, up to its own semicolon, must name its situation.
        final List<String> bare = new ArrayList<>();
        int returns = 0;
        for (int at = body.indexOf("return"); at >= 0; at = body.indexOf("return", at + 1)) {
            final int semi = body.indexOf(';', at);
            if (semi < 0) continue;
            returns++;
            final String stmt = body.substring(at, semi + 1);
            if (!stmt.contains("ServerPack.")) bare.add(stmt.trim());
        }
        assertTrue("serverPackForRebuild has fewer than four returns, so it can no longer be "
                + "distinguishing four situations at all", returns >= 4);
        if (!bare.isEmpty()) {
            fail("serverPackForRebuild returns something that cannot say WHICH of the four "
                    + "situations it is in: " + bare + ". A bare null here is the state it was "
                    + "found in — a ledger refusal sharing a value with \"there was nothing to "
                    + "ask with\" — and it produced a wrong device fixture before it produced "
                    + "anything else.");
        }

        // The no-local-state arm returns above the charge.
        final int noState = body.indexOf("MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH");
        assertTrue("serverPackForRebuild no longer distinguishes the group-we-hold-no-state-for "
                + "arm. That arm is the REJOIN shape --ez forgetgroup produces and it returns "
                + "BEFORE the fetch ledger is consulted, so a fixture reaching the rebuild rung "
                + "that way exercises TWO of REBUILD's three sites, not three.",
                noState >= 0);
        final int fetch = body.indexOf("fetchServerPack(");
        assertTrue("serverPackForRebuild never calls fetchServerPack — the pack look is gone, and "
                + "with it the third REBUILD site this check is about", fetch >= 0);
        assertTrue("the no-local-state arm now sits BELOW the fetch (arm at " + noState + ", fetch "
                + "at " + fetch + "). Searched from the start of the body precisely so a hoisted "
                + "fetch is visible: if the fetch runs first then this arm DOES charge the ledger, "
                + "and MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH.spentALook() is now lying to "
                + "the next person planning a device fixture.", noState < fetch);

        // The ledger refusal, brace-matched to its own arm.
        final int refusal = body.indexOf(".refused()");
        assertTrue("serverPackForRebuild charges a look and never tests whether it was refused — a "
                + "ledger whose answer is discarded is worse than no ledger", refusal >= 0);
        final String arm = armContaining(body, refusal);
        assertTrue("the pack look's refusal arm does not return REFUSED_BY_LEDGER. If it returns "
                + "the same thing as the arms above, a fall-back caused by OUR OWN bound is "
                + "indistinguishable from one caused by having nothing to ask with — which is the "
                + "whole point. Arm read: " + arm.trim(),
                arm.contains("MlsServerPackOutcome.REFUSED_BY_LEDGER"));

        // Which outcomes spent a look, beside the arms that produce them.
        assertEquals("NO_LOCAL_STATE_TO_ASK_WITH returns above the charge, so it spends nothing",
                Boolean.FALSE, MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH.spentALook());
        assertEquals("NOT_A_GROUP never reaches the ledger either — a 1:1 fetches no pack",
                Boolean.FALSE, MlsServerPackOutcome.NOT_A_GROUP.spentALook());
        assertEquals(
                "a REFUSAL IS NOT A SPEND: spendOneLook calls MlsFetchLedgerRecord.charged only "
                + "on the permitted, non-exempt path, so a refused look consults the ledger and "
                + "increments nothing",
                Boolean.FALSE, MlsServerPackOutcome.REFUSED_BY_LEDGER.spentALook());
        assertEquals("SERVER_HAD_NOTHING is the one absence that DID charge — the look was taken",
                Boolean.TRUE, MlsServerPackOutcome.SERVER_HAD_NOTHING.spentALook());
        assertEquals("LOOK_FAILED must stay UNKNOWN. The throw can come from either side of "
                + "MlsFetchLedger.mayFetch, and answering false there is this type's own defect "
                + "committed inside this type.",
                null, MlsServerPackOutcome.LOOK_FAILED.spentALook());
    }

    /**
     * Every {@link MlsServerPackOutcome} is produced somewhere; a value nobody produces reads as
     * accounted for while covering nothing.
     */
    @Test
    public void everyServerPackOutcomeIsProducedSomewhereInTheTransport() throws IOException {
        // The fetched outcome is produced by ServerPack.of, in MlsTransportTypes.
        final String src = com.android.messaging.rcs.SourceScan.transportAndMoved();
        final List<String> unproduced = new ArrayList<>();
        for (final MlsServerPackOutcome o : MlsServerPackOutcome.values()) {
            final Matcher m = Pattern.compile(
                    "MlsServerPackOutcome\\s*\\.\\s*" + Pattern.quote(o.name()) + "\\b")
                    .matcher(src);
            if (!m.find()) unproduced.add(o.name());
        }
        if (!unproduced.isEmpty()) {
            fail(
                    "These MlsServerPackOutcome constants are named by nothing in MlsProviderTransport: "
                    + unproduced + ". Either an arm stopped producing one — in which case the "
                    + "situation it names is back to sharing a value with its neighbour — or a "
                    + "constant was added with no site, which is a vocabulary word for a case the "
                    + "code cannot be in. Both are the original defect returning.");
        }
    }

    /**
     * An exempt caller may be passed only from a method in {@link #MAY_PASS_AN_EXEMPT_CALLER}, and
     * the exempt set itself is pinned.
     */
    @Test
    public void anExemptCallerIsPassedOnlyFromADebugArm() throws IOException {
        final String transport = codeOnly(readTransport());
        final List<int[]> decls = declarations(transport);
        final List<String> production = new ArrayList<>();
        int sites = 0;
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (!c.isExempt()) continue;
            final String needle = "MlsFetchLedger.Caller." + c.name();
            int at = transport.indexOf(needle);
            while (at >= 0) {
                sites++;
                final String enclosing = enclosingMethod(transport, decls, at);
                if (!MAY_PASS_AN_EXEMPT_CALLER.contains(enclosing)) {
                    production.add(c.name() + " passed from " + enclosing + "()");
                }
                at = transport.indexOf(needle, at + 1);
            }
        }
        assertTrue("no exempt caller is passed anywhere in the transport — either the debug arms "
                + "stopped charging or the constants were renamed, and this check is watching "
                + "nothing", sites > 0);
        if (!production.isEmpty()) {
            fail("An EXEMPT caller is passed from a PRODUCTION method: " + production + ". Exempt "
                    + "means never refused and never counted, which is only defensible for an "
                    + "operator's diagnostic — a production path holding it spends GetMlsGroupInfo "
                    + "without bound while the ledger reports the arm as correctly exempt. Give the "
                    + "path its own Caller with a ration, or move the call into a debug arm.");
        }
        // The exemption must not spread beyond the two arms it was argued for.
        assertEquals("the set of EXEMPT callers has changed. Exemption is the strongest thing this "
                + "enum can say — never refused, never counted — and it was argued for exactly two "
                + "debug arms. A third needs its own argument, not this list widened.",
                java.util.Arrays.asList("DEBUG_DUMP", "DEBUG_HEALTH"), exemptNames());
    }

    private static List<String> exemptNames() {
        final List<String> out = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.isExempt()) out.add(c.name());
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * No consumer tests equality against a bare {@code -1}. {@code HEAL_LOOK_UNAVAILABLE} and
     * {@code ERA_ADVANCE_LOOK_UNAVAILABLE} ({@code -2}) are safe only because consumers test a
     * relation; {@code == -1} would silently exclude them. Comparing against the named constants is
     * fine.
     */
    @Test
    public void noConsumerTestsEqualityAgainstTheBareMinusOne() throws IOException {
        final String src = codeOnly(readTransport());
        final Matcher m = Pattern.compile("[=!]=\\s*-1\\b").matcher(src);
        final List<int[]> decls = declarations(src);
        assertTrue(
                        "ZERO HITS MUST FAIL: the transport's class-level declarations is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                decls.size() > 50);
        final List<String> bare = new ArrayList<>();
        while (m.find()) {
            bare.add(enclosingMethod(src, decls, m.start()) + "(): "
                    + src.substring(Math.max(0, m.start() - 40), m.end()).replace('\n', ' ')
                    .trim());
        }
        if (!bare.isEmpty()) {
            fail("These compare against the BARE LITERAL -1: " + bare
                    + ". Two returns here carry a "
                    + "second negative sentinel (HEAL_LOOK_UNAVAILABLE, "
                    + "ERA_ADVANCE_LOOK_UNAVAILABLE, both -2) whose entire safety argument is that "
                    + "consumers test a RELATION and so keep treating them as failure. An equality "
                    + "test silently EXCLUDES -2, turning a ledger refusal into a non-match — the "
                    + "defect those sentinels exist to fix, reintroduced by the fix. Use `< 0` for "
                    + "'did not succeed', or compare against the NAMED constant to tell them apart.");
        }
    }

    @Test
    public void nothingReachesTheProviderPrimitivesAroundTheLedger() throws IOException {
        // Every .java under src/ is walked; the allow-list is the transport and the provider shim.
        final List<String> around = new ArrayList<>();
        final List<File> all = javaSourcesUnderSrc();
        assertTrue(
                "found no Java sources under src/ — the locator has gone stale and this check is "
                + "certifying a tree it cannot see", all.size() > 50);
        // Count files with content too: truncated sources still count as files.
        int withContent = 0;
        for (final File f : all) if (f.length() > 0) withContent++;
        assertTrue("the locator found " + all.size() + " Java sources under src/ and " + withContent
                + " of them have any content — this check would be certifying a tree it cannot "
                + "read", withContent > 50);
        for (final File f : all) {
            final String rel = f.getPath();
            if (rel.endsWith(TRANSPORT) || rel.endsWith(PROVIDER_SHIM)) continue;
            if (rel.endsWith(RPC_BINDING)) continue;   // checked below
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final MlsFetchLedger.Primitive p : MlsFetchLedger.Primitive.values()) {
                // A method reference has no parens, so both spellings are matched.
                final String q = Pattern.quote(p.aidlName);
                final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                        .matcher(src);
                if (m.find()) around.add(rel + " calls " + p.aidlName + "()");
            }
        }
        for (final File f : engineSourcesOutsideTheTransport()) {
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final MlsFetchLedger.Primitive p : MlsFetchLedger.Primitive.values()) {
                final String q = Pattern.quote(p.aidlName);
                if (Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b").matcher(src)
                        .find()) {
                    around.add(f.getPath() + " calls " + p.aidlName + "()");
                }
            }
        }
        onlyTheTransportBuildsTheRpcBinding(all, around);
        if (!around.isEmpty()) {
            fail(
                    "These call a GetMlsGroupInfo primitive without going through MlsProviderTransport, "
                    + "so they are doors to the resource OUTSIDE the class that owns it — invisible "
                    + "to any enumeration of MlsProviderTransport, which is where every inventory of "
                    + "these doors has looked: " + around + ". That is how getMlsGroupInfo's only "
                    + "caller went unnoticed. Route it through the transport with an "
                    + "MlsFetchLedger.Caller (an exempt one, if it is a debug arm).");
        }
    }

    /**
     * The ledger's window is {@link MlsFetchBudget}'s throttle cooldown, one datum with one home.
     */
    @Test
    public void theWindowIsTheOneMeasuredDatum() {
        assertEquals(
                "MlsFetchLedger.WINDOW_MS has stopped being MlsFetchBudget's throttle cooldown. "
                + "There is ONE datum here — 200s, after which the identical single fetch succeeds "
                + "— and it must have one home.",
                MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS, MlsFetchLedger.WINDOW_MS);
    }

    /**
     * The shared ceiling sits below the ten-read burst the server throttles and above the
     * drive-then-self-heal sequence.
     */
    @Test
    public void theCeilingClearsTheFixtureAndStopsTheBurst() {
        assertTrue("the shared ceiling (" + MlsFetchLedger.SHARED_CEILING
                + ") is not below the ten "
                + "fetches that drew grpcStatus=8 RESOURCE_EXHAUSTED. A bound set at the value that "
                + "failed is a bound that fails.", MlsFetchLedger.SHARED_CEILING < 10);
        final int fixture = MlsFetchLedger.Caller.RECONCILE_DRIVE.ration
                + MlsFetchLedger.Caller.SELF_HEAL.ration;
        // Strictly above: the real sequence also carries a MAINTENANCE charge from session
        // bring-up, so a ceiling at the fixture's size would refuse the next look on a healthy
        // conversation.
        assertTrue("the shared ceiling (" + MlsFetchLedger.SHARED_CEILING
                + ") leaves no room above "
                + "the drive + self-heal sequence the device verification runs back to back ("
                + fixture
                + "). The real sequence carries a MAINTENANCE charge too, so a ceiling at "
                + "the fixture's own size refuses the healthy case.",
                MlsFetchLedger.SHARED_CEILING > fixture);
    }

    /** The health readers stay outside the shared ceiling, so recovery cannot starve them. */
    @Test
    public void theHealthReadersDoNotCompeteWithRecovery() {
        assertTrue(
                "HEALTH_PROBE now charges the shared ceiling, so recovery can starve the DIVERGED "
                + "health test. We already showed an unreadable health escalates the ladder to a "
                + "heavier remedy than the fault needed — which is why D3 rejected one global "
                + "ration.", !MlsFetchLedger.Caller.HEALTH_PROBE.chargesTheSharedCeiling);
        assertTrue("STALL_REFRESH now charges the shared ceiling. A person has just pressed Try "
                + "again and is watching the alert; refusing that read because recovery spent the "
                + "allowance leaves them looking at a notification that reflects nothing.",
                !MlsFetchLedger.Caller.STALL_REFRESH.chargesTheSharedCeiling);
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (c.isExempt()) {
                assertTrue(c + " is exempt but still charges the shared ceiling — it would refuse "
                        + "recovery without ever being refused itself, which is the starvation D3 "
                        + "rejected wearing the opposite hat", !c.chargesTheSharedCeiling);
            } else {
                assertTrue(c + " has a ration of " + c.ration + ". A non-exempt caller needs a "
                        + "positive allowance; zero is a self-inflicted outage rather than a tight "
                        + "bound.", c.ration >= 1);
            }
        }
    }

    /**
     * The source with comments and string contents replaced by spaces, so offsets and
     * {@link #enclosingMethod} still match the real file.
     */
    private static String codeOnly(final String src) {
        return blank(src, /*alsoStringContents=*/ true);
    }

    /** The source with comments blanked and string literals intact. */
    private static String withoutComments(final String src) {
        return blank(src, /*alsoStringContents=*/ false);
    }

    private static String blank(final String src, final boolean alsoStringContents) {
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
                i++;                                  // the opening quote stays
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
     * The brace-matched block of the {@code if} arm containing {@code at}, or the rest of the body.
     * Used instead of a fixed window, which grows red with the arm and green when the property
     * moves.
     */
    private static String armContaining(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return body.substring(at);
        final int open = body.indexOf('{', at);
        if (open < 0) return body.substring(at);
        final int semi = body.indexOf(';', at);
        // braceless arm; no block to take
        if (semi >= 0 && semi < open) return body.substring(at);
        final String block = bracedBlock(body, open);
        return block.isEmpty() ? body.substring(at) : block;
    }

    /** The brace-matched block starting at {@code open}, or "" if it does not close. */
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
     * Every {@code .java} under the module's {@code src/}, wherever the runner's cwd happens to be.
     */
    private static List<File> javaSourcesUnderSrc() throws IOException {
        File root = null;
        for (final String c : new String[] {"src", "packages/apps/Messaging/src", "../src"}) {
            final File f = new File(c);
            if (f.isDirectory()) { root = f; break; }
        }
        if (root == null) {
            throw new IOException("src/ not found from " + new File(".").getAbsolutePath());
        }
        final List<File> out = new ArrayList<>();
        final java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (k.isDirectory()) stack.push(k);
                else if (k.getName().endsWith(".java")) out.add(k);
            }
        }
        return out;
    }

    private static int indexOfFirst(final String body, final String... needles) {
        int best = -1;
        for (final String n : needles) {
            final int at = body.indexOf(n);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // Match the declaration only: a method that calls the one asked for must not answer for
            // it.
            if (!declFragment.isEmpty() && !src.substring(m.start(), open).contains(declFragment)) {
                continue;
            }
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            return body;
        }
        return "";
    }

    private static String[] wrapperRowFor(final String method) {
        for (final String[] row : CHARGED_WRAPPERS) {
            if (row[0].equals(method)) return row;
        }
        return null;
    }

    /** The text from an opening paren up to the first top-level comma or the closing paren. */
    private static String argumentPrefix(final String src, final int afterOpenParen) {
        int depth = 1;
        final StringBuilder b = new StringBuilder();
        for (int i = afterOpenParen; i < src.length() && b.length() < 200; i++) {
            final char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (--depth == 0) break;
            } else if (c == ',' && depth == 1) break;
            b.append(c);
        }
        return b.toString();
    }

    private static boolean isDeclaration(final String src, final List<int[]> decls, final int at) {
        for (final int[] d : decls) {
            if (d[1] == at) return true;
            if (d[0] > at) break;
        }
        return false;
    }

    /** Start offset and name bounds of every class-level method declaration, in source order. */
    private static List<int[]> declarations(final String src) {
        final List<int[]> out = new ArrayList<>();
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) out.add(new int[] {m.start(), m.start(1), m.end(1)});
        return out;
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
     * The body of the first method whose name matches exactly: {@code lookGroupInfo} is a prefix of
     * {@code lookGroupInfoForGroup}.
     */
    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;   // see isDelegateTo
            return body;
        }
        return "";
    }

    /**
     * Whether {@code body} is a delegating overload whose only statement returns a call to its own
     * name; {@link #bodyOf} skips such a body so a delegate declared above the real method cannot
     * hide it.
     */
    private static boolean isDelegateTo(final String body, final String name) {
        final String inner = codeOnly(body).trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) return false;
        final String stmt = inner.substring(1, inner.length() - 1).trim();
        return stmt.startsWith("return " + name + "(") && stmt.indexOf(';') == stmt.length() - 1;
    }

    private static String readTransport() throws IOException {
        // The unsplit view; see SourceScan.transportAsUnsplit.
        return com.android.messaging.rcs.SourceScan.transportAsUnsplit();
    }

    private static String readDebugReceiver() throws IOException {
        return read("src/com/android/messaging/rcs/RcsDebugSendReceiver.java");
    }

    /** Works from the module directory or the tree root. */
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
    /**
     * The engine's sources minus the transport's destinations: calls from moved code are the
     * transport's doors, and any other engine class naming a primitive is a door this scan must
     * see.
     */
    private static List<File> engineSourcesOutsideTheTransport() throws IOException {
        final java.util.Set<String> dest = new java.util.HashSet<>();
        for (final String d : com.android.messaging.rcs.SourceScan.transportDestinations()) {
            dest.add(new File(d).getName());
        }
        final File[] kids = new File("engine/src/com/android/messaging/rcs/engine/mls").listFiles();
        if (kids == null || kids.length < 50) {
            throw new IOException("engine sources not found from "
                    + new File(".").getAbsolutePath());
        }
        final List<File> out = new ArrayList<>();
        for (final File k : kids) {
            if (k.getName().endsWith(".java") && !dest.contains(k.getName())) out.add(k);
        }
        return out;
    }

    /**
     * {@code MlsProviderRpcBinding} forwards the transport's {@code pt(what)} as the port's
     * {@code rpc(what)}; it is a door only if something other than the transport builds one.
     */
    private static void onlyTheTransportBuildsTheRpcBinding(final List<File> all,
            final List<String> around) throws IOException {
        int built = 0;
        for (final File f : all) {
            final String src =
                    codeOnly(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!src.contains("new MlsProviderRpcBinding(")) continue;
            if (f.getPath().endsWith(TRANSPORT)) built++;
            else around.add(f.getPath() + " builds an MlsProviderRpcBinding");
        }
        assertEquals("exactly one place builds the rpc binding: the transport's own port", 1,
                built);
    }
}
