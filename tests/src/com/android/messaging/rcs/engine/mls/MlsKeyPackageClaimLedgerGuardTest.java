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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * <b>Every path that claims a peer's KeyPackage is charged to ONE ledger</b> — invariant I2, row R2
 * of the Stage 0 resource table.
 *
 * <p>{@code MlsGetGroupInfoLedgerGuardTest} is the model and this follows it deliberately rather
 * than inventing a second shape. What differs is dictated by the resource, and each difference is
 * asserted here rather than merely described at the constant.
 *
 * <h2>THE NEEDLE, and this row's is a variant the usual six axes do not name</h2>
 *
 * <p>Every instance of the usual failure is a scan that looked in the RIGHT PLACE with a WRONG KEY. This
 * row's hazard is the mirror image: <b>a plausible key aimed at the wrong module.</b>
 *
 * <p>The plan's own I2 table names this primitive by the name of the provider-side DIAL, which is
 * not an app-side name at all: it lives in the out-of-tree RCS provider, a different repository. A
 * scan keyed on it would search over there, find the one call it expects, and <b>go green while
 * all the app-side doors sat untouched in {@code MlsProviderTransport}</b>. The needle would be
 * well-formed, resolvable, and pointed somewhere else — and it degrades the same way every other
 * instance does, by finding SOMETHING, so "zero hits must FAIL" never fires.
 *
 * <p>So this guard keys on the APP-SIDE half: {@code .claimPeerKeyPackage(} and
 * {@code .claimPeerKeyPackages(} in {@code MlsProviderTransport}, read out of
 * {@link MlsClaimLedger#AIDL_SPELLINGS} rather than restated, and cross-checked against the methods
 * {@code ProviderTransport} declares so a provider-side rename cannot leave the enum pointing at
 * nothing.
 *
 * <h2>THE PROVIDER-SIDE HALF IS NOT GUARDED HERE, and it is not guarded anywhere yet</h2>
 *
 * <p>This class used to carry two checks that read the provider's source across a repository
 * boundary, and both are gone because the provider is not part of this repository — a scan that
 * cannot find its subject certifies nothing. They were:
 *
 * <ul>
 *   <li><b>the provider-side doors, enumerated.</b> Every call reaching the provider's KeyPackage
 *       claimer had to be one of three known callers, each with its reason recorded, so that a
 *       fourth could not arrive unnoticed. One of the doors it watched is a debug receiver that no
 *       app-side ledger can see at all, because it is in another process.</li>
 *   <li><b>no transport status may be translated into blame.</b> The provider turns a KDS status
 *       into the spec-level outcome vocabulary before it crosses the binder, and exactly one value
 *       in that vocabulary is evidence ABOUT A PEER — "this peer has published none". A status is
 *       never that; it is a fact about the CALL. The specific trap is that the wrong-instance
 *       status reads like "they have none", so a peer whose pool is full would be reported as
 *       having published nothing.</li>
 * </ul>
 *
 * <p><b>Both are PROVIDER invariants and neither has a guard in the provider's own suite.</b> That
 * is a real gap, recorded here rather than left implicit, and it is the provider's to close: a
 * guard in a repository that cannot see the code is not a guard, which is why these were removed
 * rather than made conditional. The detector for the second one survives below as
 * {@link #theProviderBlameGuardActuallyFails} — see that method's comment for what it now proves
 * and does not.
 *
 * <p>And the two AIDL forms must be pinned as one spend point (Stage 0 §2.0): both call
 * the identical {@code MlsKeyPackageClaimer.claim(ctx, singletonList(peer), -1)} and the singular
 * discards everything past index 0 AFTER the claim, so <b>a scan treating them as two primitives
 * double-counts while one picking either name alone misses half the doors</b>.
 *
 * <h2>The eight app-side doors this replaced, and the ninth nobody had counted</h2>
 *
 * <p>Six in {@code MlsProviderTransport} ({@code ensureReady}, {@code establishGroup},
 * {@code claimForUpgrade}, {@code addMember}, {@code dumpKeyPackageCount},
 * {@code eraAdvanceLocked}) and two in {@code RcsDebugSendReceiver}, all charged now. <b>The ninth
 * is {@code MlsProvisionDebugReceiver}</b> in the provider — {@code --es claim true} calls
 * {@code MlsKeyPackageClaimer.claim} directly, same dial, same pool, no AIDL. No app-side ledger can
 * reach it, because it is a different APK in a different process, so it is a NAMED EXCLUSION with a
 * reason and {@link #theProviderSideClaimDoorsAreEnumerated} fails if a fourth appears.
 *
 * <h2>The negative controls</h2>
 *
 * <p><b>Every source-scanning check below was injected, observed RED, and reverted — against a COPY
 * of the tree, and in the COMMENT-OUT form rather than by deleting.</b> A control that deletes
 * proves less than one that comments out, because a real change is far more likely to leave the old
 * text behind; that is the lesson that cost the model guard two rounds.
 *
 * <table>
 *   <caption>What was injected, and which check went red</caption>
 *   <tr><th>check</th><th>injected regression</th></tr>
 *   <tr><td>{@link #everyClaimCallSiteIsInsideAChargedWrapper}</td>
 *       <td>a stray {@code pt("x").claimPeerKeyPackage(…)} in {@code addMember}; separately, a
 *           wrapper's invocation commented out, so the spelling survived only in prose</td></tr>
 *   <tr><td>{@link #everyWrapperReachesTheChargePoint}</td>
 *       <td>{@code claimAll} returning the RPC directly, and the same with the charge left behind
 *           as {@code // spendOneClaim(…)} — the form that defeats a raw read</td></tr>
 *   <tr><td>{@link #aRefusedClaimDoesNotReachTheKds}</td>
 *       <td>the {@code doIt.claim()} hoisted above the refusal, so the peer's pool is spent before
 *           the bound applies</td></tr>
 *   <tr><td>{@link #everyCallerDeclaresTheLedgerItCharges}</td>
 *       <td>a new method calling {@code claimOne} with a bare peer and no {@code Caller}</td></tr>
 *   <tr><td>{@link #everyCallerConstantIsActuallyUsed}</td>
 *       <td>{@code ADD_MEMBER}'s only call site commented out — a ration nobody spends reading as a
 *           bounded door</td></tr>
 *   <tr><td>{@link #anUnrefusableCallerIsPassedOnlyFromANamedSite}</td>
 *       <td>{@code UPGRADE_PROBE} swapped for {@code DEBUG_KP_COUNT} in
 *           {@code claimForUpgrade} — a production path made unrefusable while the ledger
 *           reports the arm as correctly exempt. This is mutation B4's direction,
 *           where a spelling PERMITS rather than misses, and it is why the permitted set is three
 *           NAMED methods and not a {@code debug}/{@code dump} prefix rule</td></tr>
 *   <tr><td>{@link #theUiDoorsRefusalIsNotACount}</td>
 *       <td>{@code claimForUpgrade} returning {@code n} instead of
 *           {@code CLAIM_REFUSED} on a refusal — the laundering this row exists to prevent</td></tr>
 *   <tr><td>{@link #theRefusalIsTestedBeforeTheShortfall}</td>
 *       <td>the {@code CLAIM_REFUSED} arm moved BELOW the {@code <} comparison in
 *           {@code MlsUpgradePolicy}, where {@code -1 < N} silently reports the refusal as the
 *           participants being short of key packages</td></tr>
 *   <tr><td>{@link #theRebuildRecreateArmIsUnrefusable}</td>
 *       <td>{@code ensureReady}'s ternary collapsed to a bare {@code Caller.ENSURE_READY}, so a
 *           rebuild that has already forgotten both halves of our state can be refused the input it
 *           is made of</td></tr>
 *   <tr><td>{@link #nothingReachesTheClaimPrimitivesAroundTheLedger}</td>
 *       <td>{@code RcsDebugSendReceiver} calling {@code pt.claimPeerKeyPackage} directly again —
 *           which is exactly where it was before this landed; and {@code return
 *           pt::claimPeerKeyPackage;} in a third file, the method-reference spelling with no parens
 *           for a call-shaped needle to match</td></tr>
 *   <tr><td>{@link #theProviderSideClaimDoorsAreEnumerated}</td>
 *       <td>a FOURTH provider-side file calling {@code MlsKeyPackageClaimer.claim}</td></tr>
 *   <tr><td>{@link #theAidlSpellingsAreOnesTheProviderDeclares}</td>
 *       <td>a spelling in the enum renamed to one {@code ProviderTransport} does not declare —
 *           the enum pointing at nothing while every scan keyed on it reports green</td></tr>
 * </table>
 */
public final class MlsKeyPackageClaimLedgerGuardTest {

    /**
     * The methods ALLOWED to invoke an AIDL claim, and the spelling each must carry.
     *
     * <p>Two, because the resource has one spend point in two spellings and callers genuinely need
     * both shapes — one package back for a 1:1, every device's for a group create.
     */
    private static final String[][] CHARGED_WRAPPERS = {
        {"claimOne", "claimPeerKeyPackagesWithOutcome"},
        {"claimAll", "claimPeerKeyPackages"},
    };

    /** The one method that asks the ledger. Both wrappers must go through it. */
    private static final String CHARGE_POINT = "spendOneClaim(";

    /** Methods whose FIRST argument must be an {@code MlsClaimLedger.Caller}. */
    private static final String[] MUST_DECLARE_A_CALLER = {"claimOne", "claimAll"};

    /**
     * The transport methods allowed to pass an UNREFUSABLE caller — <b>named, never matched by a
     * prefix</b>.
     *
     * <p>Mutation B4 showed what a {@code startsWith("debug")} rule
     * permits: a method called {@code dumpAndRepair} that spells everything correctly, is exempt
     * purely because of its name, and then rebuilds a conversation. A prefix rule is a hand
     * inventory written as a predicate.
     *
     * <p>Two of these three are operator diagnostics that return what they read. The third is not a
     * diagnostic at all, and it is why this list is not called "the debug arms": {@code ensureReady}
     * holds {@code REBUILD_RECREATE}, which is unrefusable for the opposite reason — reached from a
     * rebuild that has already dropped both halves of our state, the claim's product is the input
     * the re-create is MADE OF, so there is nothing to fail open to.
     */
    private static final java.util.Set<String> MAY_PASS_AN_UNREFUSABLE_CALLER =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dumpKeyPackageCount",        // --ez kpcount: reads a pool, names who blocks
                    "debugClaimAllKeyPackages",   // --es claimkp: the device-count probe's way in
                    "debugClaimOneKeyPackage",    // --ez ctrl:    the control-plane smoke test's
                    "ensureReady"));              // REBUILD_RECREATE — see this field's javadoc

;

    private static final String TRANSPORT = "e2ee/MlsProviderTransport.java";
    private static final String PROVIDER_SHIM = "rcs/ProviderTransport.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    // ---- 1. every claim call site is inside a charged wrapper -------------------------------------

    @Test
    public void everyClaimCallSiteIsInsideAChargedWrapper() throws IOException {
        // CODE ONLY. Both spellings appear in prose all over this file — the header of the ledger
        // block names them, and a log line carries one inside a string — and a scan that reads prose
        // as a call site reports doors that do not exist. Blanked in place, so offsets still line up
        // with the real source and the enclosing-method attribution below stays honest.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        int sites = 0;
        final List<String> stray = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        final List<String> duplicated = new ArrayList<>();
        for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
            // THE INVOCATION, NOT THE pt("…") LABEL. pt(String) takes a LOCK-ASSERTION label, not a
            // method name — 29 distinct tags exist in this file and 10 are not AIDL names at all,
            // three name methods that do not exist, and one names a DIFFERENT REAL METHOD. Two of
            // the eight held `final ProviderTransport pt = pt("…")` locals carried KeyPackage spends
            // (claimForUpgrade, kpCountProbe), so a tag-keyed scan was blind inside exactly
            // the method bodies this row is about (instances 1 and 2).
            //
            // BOTH SPELLINGS of the invocation, because a METHOD REFERENCE has no parens:
            // `return pt::claimPeerKeyPackage;` hands the door out to be called later and a
            // call-shaped needle never sees it (mutation A1).
            final String q = Pattern.quote(aidl);
            final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                    .matcher(src);
            int perSpelling = 0;
            while (m.find()) {
                sites++;
                perSpelling++;
                final String enclosing = enclosingMethod(src, decls, m.start());
                if (wrapperRowFor(enclosing) == null) {
                    stray.add(aidl + " in " + enclosing + "()");
                }
            }
            // ZERO MUST FAIL, NOT PASS. A source scan that misses its own subject
            // degrades to "found fewer than exist", and the limit of that is "found none, certified
            // the file". PER SPELLING, never a total: a total would still pass if one spelling
            // dropped to zero while the other gained a site.
            if (perSpelling == 0) {
                vanished.add(aidl);
            } else if (perSpelling != 1) {
                duplicated.add(aidl + " x" + perSpelling);
            }
        }
        // AND THE RETIRED SPELLINGS MUST STAY GONE. claimOne moved to contract v60's
        // outcome-bearing call, so the singular form is no longer a door — but a needle DELETED on
        // purpose leaves exactly the hole this guard exists to prevent, so it is asserted at zero
        // instead of dropped. A reappearance is a claim site nothing is accounting for.
        final List<String> resurrected = new ArrayList<>();
        for (final String aidl : MlsClaimLedger.RETIRED_AIDL_SPELLINGS) {
            final String q = Pattern.quote(aidl);
            final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                    .matcher(src);
            while (m.find()) {
                resurrected.add(aidl + " in " + enclosingMethod(src, decls, m.start()) + "()");
            }
        }
        if (!resurrected.isEmpty()) {
            fail("These AIDL spellings are RETIRED — no longer doors from MlsProviderTransport — and "
                    + "have come back: " + resurrected + ". Either route the call through the live "
                    + "wrapper, or move the spelling from MlsClaimLedger.RETIRED_AIDL_SPELLINGS back "
                    + "into AIDL_SPELLINGS with a CHARGED_WRAPPERS row, so the ledger is accounting "
                    + "for it again.");
        }
        if (!vanished.isEmpty()) {
            fail("This guard found NO invocation of " + vanished + " in MlsProviderTransport, so it "
                    + "is no longer watching those doors — and a scan that misses its subject "
                    + "passes silently, which is how an I2 guard fails in exactly the manner the "
                    + "invariant exists to prevent. It has just certified a transport it cannot "
                    + "see. Either the wrapper was renamed or removed (re-point CHARGED_WRAPPERS), "
                    + "the AIDL method was renamed provider-side (update "
                    + "MlsClaimLedger.AIDL_SPELLINGS), or the file moved (fix readTransport()).");
        }
        if (!duplicated.isEmpty()) {
            fail("These AIDL spellings are invoked more than once in MlsProviderTransport: "
                    + duplicated + ". Exactly one invocation each is the whole architecture — the "
                    + "wrapper is the single charge point, and a second invocation is a second door "
                    + "whether or not it happens to sit inside a wrapper too.");
        }
        assertEquals("the number of claim INVOCATIONS no longer matches the number of wrappers. "
                + "Each wrapper contains exactly one, so any other count means either a wrapper "
                + "lost its call or a site appeared outside one — including one reached through a "
                + "held `final ProviderTransport pt = pt(…)` local, which is how five GetMlsGroupInfo "
                + "sites hid from the first version of the model guard.",
                CHARGED_WRAPPERS.length, sites);
        if (!stray.isEmpty()) {
            fail("These calls claim a peer's KeyPackage from a method that is not a charged wrapper, "
                    + "so the peer's pool is spent with nothing counting it: " + stray + ". Route "
                    + "the call through claimOne()/claimAll() and pass the MlsClaimLedger.Caller "
                    + "that owns the decision. The pool being spent is NOT OURS — a peer with none "
                    + "left cannot be added, re-Welcomed or brought into a rebuilt group.");
        }
    }

    @Test
    public void everyWrapperReachesTheChargePoint() throws IOException {
        // CODE ONLY. Read raw, a DELETED charge left behind in a comment satisfies every assertion
        // below — measured against the model guard's equivalent method, where replacing a wrapper's
        // body with `// The charge used to be here: spendOneLook(…)` plus a bare RPC left the test
        // reporting green while the wrapper spent the resource uncounted.
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_WRAPPERS) {
            final String body = bodyOf(src, row[0]);
            assertTrue(row[0] + "() is gone — the wrapper for " + row[1] + " has been renamed or "
                    + "removed, and this guard can no longer see whether its claim is charged",
                    body.length() > 0);
            assertTrue(row[0] + "() no longer calls " + CHARGE_POINT + " — it claims the peer's "
                    + "KeyPackage without asking the ledger, which is a budget whose answer is "
                    + "discarded. That reads as a guard in review and is a no-op at runtime.",
                    body.contains(CHARGE_POINT));
            assertTrue(row[0] + "() no longer invokes ." + row[1] + "(), so it declares a spelling "
                    + "it does not spend — the ledger would be charging for a call nobody makes and "
                    + "the real claim would be somewhere this guard is not looking",
                    body.contains("." + row[1] + "("));
        }
    }

    /**
     * <b>The two AIDL forms are ONE spend point, and this is where that stops being prose.</b>
     *
     * <p>Stage 0 §2.0 established it by walking the chain: both forms call the identical
     * {@code MlsKeyPackageClaimer.claim(ctx, singletonList(peer), -1)}, and the singular discards
     * everything past index 0 AFTER the claim has happened. The contract-v21 javadoc that says
     * "claim one KeyPackage" describes what the method RETURNS, not what it SPENDS — and Stage 0's
     * own first draft of this row believed it.
     *
     * <p>Pinned three ways, because the failure is a scan that is correctly shaped and mis-costed:
     * one {@link MlsClaimLedger.Primitive} constant, both wrappers reaching the same charge point,
     * and neither wrapper claiming to be cheaper than the other.
     */
    @Test
    public void theTwoSpellingsAreOneSpendPoint() throws IOException {
        assertEquals("MlsClaimLedger.Primitive has gained a constant. The two AIDL spellings are ONE "
                + "spend point — one KdsClient.claimKeyPackages dial consuming every device's "
                + "package — so a second constant means either a genuinely new resource (give it "
                + "its own ledger) or the two forms have been split back apart, which double-counts "
                + "the doors.",
                1, MlsClaimLedger.Primitive.values().length);
        assertEquals("MlsClaimLedger's live and retired spellings overlap, so one name is asserted "
                + "at both exactly-once and zero and the guard cannot be satisfied.", 0,
                java.util.Arrays.stream(MlsClaimLedger.AIDL_SPELLINGS)
                        .filter(a2 -> java.util.Arrays.asList(MlsClaimLedger.RETIRED_AIDL_SPELLINGS)
                                .contains(a2))
                        .count());
        assertEquals("MlsClaimLedger.AIDL_SPELLINGS no longer holds exactly the two forms. A third "
                + "spelling added provider-side must be listed here or every scan below misses its "
                + "doors; a spelling removed means one of the two forms is gone and the guard is "
                + "watching a method that no longer exists.",
                2, MlsClaimLedger.AIDL_SPELLINGS.length);
        final String src = codeOnly(readTransport());
        for (final String[] row : CHARGED_WRAPPERS) {
            final String body = bodyOf(src, row[0]);
            assertTrue(row[0] + "() no longer reaches " + CHARGE_POINT + ", so the two spellings no "
                    + "longer share a charge point and one of them is cheaper than the other in the "
                    + "ledger while costing the same at the KDS", body.contains(CHARGE_POINT));
        }
    }

    /**
     * The charge point must consult {@link MlsClaimLedger#mayClaim} and REFUSE before the dial.
     *
     * <p>"It never asks" is the hole; "it asks and ignores the answer" is worse, because the next
     * reader believes it. A refusal after the claim bounds nothing: the peer's packages are gone.
     */
    @Test
    public void aRefusedClaimDoesNotReachTheKds() throws IOException {
        // CODE ONLY, and it must be the BLANKING form: this test compares OFFSETS, so a stripping
        // pass would move the very positions it asserts about.
        final String body = bodyOf(codeOnly(readTransport()), "spendOneClaim");
        assertTrue("spendOneClaim() is gone — every wrapper's charge went with it", body.length() > 0);
        assertTrue("spendOneClaim() does not ask MlsClaimLedger.mayClaim at all",
                body.contains("MlsClaimLedger.mayClaim("));
        final int ask = body.indexOf("MlsClaimLedger.mayClaim(");
        final int refuse = body.indexOf("Claim.refusedByLedger(", ask);
        assertTrue("spendOneClaim() asks the ledger and has no arm that returns a refusal — a budget "
                + "whose answer is discarded is worse than no budget", refuse >= 0);

        // EVERY OCCURRENCE, NOT THE ONE AFTER THE ASK — and this is the negative control that did
        // not fire on first injection (and the model guard still has this hole; see the
        // class javadoc's control table). The obvious form is
        //     doIt = body.indexOf("doIt.claim()", ask);  assertTrue(refuse < doIt);
        // and it is BLIND TO A CLAIM HOISTED ABOVE THE ASK, which is the very regression the check
        // is named for: searching forward FROM the ask cannot see anything before it, so a
        // `final T early = doIt.claim();` inserted above mayClaim() passes untouched.
        //
        // Searching from zero instead would go red on CORRECT code, because the unreadable-ledger
        // arm legitimately claims before the ask — it is guarded by isUnrefusable() rather than by
        // a verdict. That accommodation is exactly how the hole got made, so the property is pinned
        // PER OCCURRENCE instead: there are two, and each one's context is named.
        final List<Integer> claims = new ArrayList<>();
        int at = body.indexOf("doIt.claim()");
        while (at >= 0) {
            claims.add(at);
            at = body.indexOf("doIt.claim()", at + 1);
        }
        assertEquals("spendOneClaim() has " + claims.size() + " call(s) to doIt.claim() and this "
                + "guard knows the context of exactly two: the unreadable-ledger arm, which is "
                + "permitted by isUnrefusable() rather than by a verdict, and the one after the "
                + "verdict. A THIRD is a claim whose permission this check cannot account for — and "
                + "the whole failure mode here is a KDS round-trip that happens before the bound "
                + "applies, spending every device's KeyPackage for that peer.",
                2, claims.size());
        final String firstArm = armContaining(body, claims.get(0));
        assertTrue("the FIRST doIt.claim() in spendOneClaim() is not inside an arm that checks "
                + "isUnrefusable(). It runs before the ledger is consulted, so its only possible "
                + "justification is that this caller can never be refused — and nothing here says "
                + "so. Arm read: " + firstArm.trim(),
                firstArm.contains("isUnrefusable()")
                        || body.substring(0, claims.get(0)).contains("isUnrefusable()"));
        final int last = claims.get(1);
        assertTrue("spendOneClaim() claims BEFORE it can refuse (refusal at " + refuse + ", claim at "
                + last + "). A refusal after the KDS round-trip bounds nothing: every device's "
                + "KeyPackage for that peer is already consumed.", refuse < last);
        assertTrue("spendOneClaim()'s second claim (" + last + ") happens before it asks the ledger ("
                + ask + ")", ask < last);
    }

    // ---- 2. every caller declares its ledger ------------------------------------------------------

    @Test
    public void everyCallerDeclaresTheLedgerItCharges() throws IOException {
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        final List<String> undeclared = new ArrayList<>();
        int calls = 0;
        for (final String wrapper : MUST_DECLARE_A_CALLER) {
            final Matcher m = Pattern.compile("\\b" + Pattern.quote(wrapper) + "\\s*\\(")
                    .matcher(src);
            while (m.find()) {
                // Skip the declaration itself; only CALLS carry an argument list to inspect.
                if (isDeclaration(src, decls, m.start())) continue;
                calls++;
                final String args = argumentPrefix(src, m.end());
                if (!args.contains("MlsClaimLedger.Caller.")) {
                    undeclared.add(wrapper + "() in " + enclosingMethod(src, decls, m.start())
                            + "() — first argument was: " + args.trim());
                }
            }
        }
        assertTrue("no calls to either charged wrapper matched — the names have changed and this "
                + "guard is silently passing", calls >= MUST_DECLARE_A_CALLER.length);
        if (!undeclared.isEmpty()) {
            fail("These calls spend a peer's KeyPackages without naming the ledger they charge, so "
                    + "a new path would inherit a ration by omission — the exact shape of the defect "
                    + "this guard closed: " + undeclared + ". Pass the MlsClaimLedger.Caller that "
                    + "owns the decision, and add a constant if none fits.");
        }
    }

    /**
     * Every {@link MlsClaimLedger.Caller} must be USED at a call site.
     *
     * <p>A constant nobody passes is a ration nobody spends, which reads in review as a door that is
     * bounded and is in fact a door that was renamed or deleted while its constant stayed.
     */
    /**
     * <b>RETIRED is mirrored from {@code MlsGetGroupInfoLedgerGuardTest}, where this same advice
     * was measured to be harmful</b> (2026-09-14). Nothing in
     * {@link MlsClaimLedger} is retired today, so the branch is dormant here — it is present so the
     * two sibling guards do not drift, and so the FIRST person to delete a claim-ledger caller is
     * not told to do the damaging thing.
     *
     * <p>The advice that used to stand here was "Delete it or wire it". Deleting is the one action
     * that must not be taken: {@link MlsClaimLedgerRecord} persists the caller BY ORDINAL
     * ({@code who[n] = caller.ordinal()}, read back by comparing ordinals at three sites), so
     * removing a constant shifts every later one and silently reattributes charges already on disk
     * to the wrong caller. Reclaiming a slot is a migration, not a deletion.
     */
    @Test
    public void everyCallerConstantIsActuallyUsed() throws IOException {
        // CODE ONLY: a constant named in a comment — "we used to charge ADD_MEMBER here" — would
        // otherwise satisfy this and report a ration nobody spends as a live door.
        final String src = codeOnly(readTransport()) + codeOnly(readDebugReceiver());
        // RAW, not codeOnly: the RETIRED marker lives in the constant's JAVADOC, and codeOnly()
        // blanks comments — reading it through codeOnly would find no marker on any constant and
        // silently restore the old behaviour.
        final String ledger = read("engine/src/com/android/messaging/rcs/engine/mls/"
                + "MlsClaimLedger.java");
        final List<String> unusedAndLive = new ArrayList<>();
        final List<String> retiredButStillCharged = new ArrayList<>();
        for (final MlsClaimLedger.Caller c : MlsClaimLedger.Caller.values()) {
            final boolean used = src.contains("MlsClaimLedger.Caller." + c.name());
            if (isRetired(ledger, c.name())) {
                if (used) retiredButStillCharged.add(c.name());
            } else if (!used) {
                unusedAndLive.add(c.name());
            }
        }
        if (!unusedAndLive.isEmpty()) {
            fail("These MlsClaimLedger.Caller constants are passed at no call site: " + unusedAndLive
                    + ". A ration nobody spends looks like a bounded door and is not one — either "
                    + "the door was renamed and its constant left behind, or the constant was added "
                    + "for a caller that never landed. Wire it — or, if its caller was deliberately "
                    + "DELETED, mark the constant RETIRED in its MlsClaimLedger javadoc and say what "
                    + "replaced it. DO NOT DELETE THE CONSTANT: MlsClaimLedgerRecord persists the "
                    + "caller by ORDINAL, so removing one shifts every later constant and silently "
                    + "reattributes charges already on disk.");
        }
        if (!retiredButStillCharged.isEmpty()) {
            fail("These MlsClaimLedger.Caller constants are marked RETIRED in their javadoc but ARE "
                    + "still passed at a call site: " + retiredButStillCharged + ". RETIRED means "
                    + "the door is gone and the ordinal is only reserved; a live charge against one "
                    + "is either a caller that came back without the javadoc being updated, or the "
                    + "word being used to silence this test for a door that is still open.");
        }
    }

    /**
     * Does {@code name}'s declaration in {@code MlsClaimLedger} carry a RETIRED marker in the
     * javadoc immediately above it? Anchored on the DECLARATION and searching back only to the end
     * of the previous constant, so a constant cannot inherit its neighbour's marker.
     *
     * <p>Deliberately a copy of {@code MlsGetGroupInfoLedgerGuardTest.isRetired} rather than a
     * shared helper, matching how {@code blockAfter} is carried in each guard in this package.
     */
    private static boolean isRetired(final String ledgerSrc, final String name) {
        final Matcher m = Pattern.compile("(?m)^        " + Pattern.quote(name) + "\\s*\\(")
                .matcher(ledgerSrc);
        if (!m.find()) return false;
        final int declAt = m.start();
        // THE ATTACHED JAVADOC BLOCK, EXACTLY — not "the text since the previous constant".
        // The loose window matched RETIRED_AIDL_SPELLINGS, an unrelated CLASS-level constant 60
        // lines above, and reported the first enum constant as retired. Measured; it is why this
        // reads a delimited block instead of a range.
        final int close = ledgerSrc.lastIndexOf("*/", declAt);
        if (close < 0) return false;
        // ATTACHED means only whitespace between the javadoc's close and the declaration. Without
        // this a constant with NO javadoc of its own silently inherits its predecessor's.
        if (!ledgerSrc.substring(close + 2, declAt).trim().isEmpty()) return false;
        final int open = ledgerSrc.lastIndexOf("/**", close);
        if (open < 0) return false;
        return ledgerSrc.substring(open, close).contains("RETIRED");
    }

    /**
     * An UNREFUSABLE caller may be passed only from a named site.
     *
     * <p>The stated reason for unrefusability is that an operator asked, or that a rebuild has
     * already dropped our state — <b>and a reason in an exemption table is an assertion</b>
     * on its own. Nothing would otherwise stop a production path taking
     * {@code DEBUG_KP_COUNT} and claiming without bound while the ledger reported the arm as
     * correctly exempt: a wrong reason with a right verdict never fails a test.
     */
    @Test
    public void anUnrefusableCallerIsPassedOnlyFromANamedSite() throws IOException {
        final String transport = codeOnly(readTransport());
        final List<int[]> decls = declarations(transport);
        final List<String> production = new ArrayList<>();
        int sites = 0;
        for (final MlsClaimLedger.Caller c : MlsClaimLedger.Caller.values()) {
            if (!c.isUnrefusable()) continue;
            final String needle = "MlsClaimLedger.Caller." + c.name();
            int at = transport.indexOf(needle);
            while (at >= 0) {
                sites++;
                final String enclosing = enclosingMethod(transport, decls, at);
                if (!MAY_PASS_AN_UNREFUSABLE_CALLER.contains(enclosing)) {
                    production.add(c.name() + " passed from " + enclosing + "()");
                }
                at = transport.indexOf(needle, at + 1);
            }
        }
        assertTrue("no unrefusable caller is passed anywhere in the transport — either the debug "
                + "arms stopped charging or the constants were renamed, and this check is watching "
                + "nothing", sites > 0);
        if (!production.isEmpty()) {
            fail("An UNREFUSABLE caller is passed from a method that is not on the named list: "
                    + production + ". Unrefusable means never denied — defensible for an operator's "
                    + "diagnostic, and for the rebuild's re-create where the claim's product is the "
                    + "input we cannot proceed without, and for nothing else. A production path "
                    + "holding it drains a peer's pool without bound while the ledger reports the "
                    + "arm as correctly exempt. Give the path its own Caller with a ration.");
        }
        assertEquals("the set of UNREFUSABLE callers has changed. Unrefusable is the strongest thing "
                + "this enum can say about somebody else's key material, and it was argued for "
                + "exactly these four. A fifth needs its own argument, not this list widened.",
                java.util.Arrays.asList(
                        "DEBUG_CLAIM_KP", "DEBUG_CTRL", "DEBUG_KP_COUNT", "REBUILD_RECREATE"),
                unrefusableNames());
    }

    /**
     * <b>Every permitted claim charges — including the unrefusable ones.</b>
     *
     * <p>This is the design decision that separates this ledger from {@link MlsFetchLedger}, whose
     * exempt arms are permitted and charge nothing. There the resource is a rate window; here it is
     * a consumable taken out of a third party's device, so a claim that happened and went uncounted
     * makes every later answer wrong about how many packages the peer has left.
     *
     * <p>Asserted rather than left to a reader noticing a one-line method, because it is exactly the
     * kind of thing a fourth verdict would break silently.
     */
    @Test
    public void everyPermittedClaimCharges() {
        for (final MlsClaimLedger.Verdict v : MlsClaimLedger.Verdict.values()) {
            assertEquals(v + " no longer charges exactly when it is permitted. This ledger models a "
                    + "pool that was really emptied: a permitted claim that does not charge leaves "
                    + "the next caller told the peer is fuller than it is, and a refused one that "
                    + "charges bills a peer for a round trip nobody made.",
                    v.permitted(), v.charges());
        }
        assertTrue("SPEND_UNREFUSABLE is no longer permitted — the arms that cannot be refused are "
                + "being refused", MlsClaimLedger.Verdict.SPEND_UNREFUSABLE.permitted());
        assertTrue("SPEND_UNREFUSABLE no longer charges, which is the one thing that separates it "
                + "from MlsFetchLedger's SPEND_EXEMPT and the whole reason it has a different name",
                MlsClaimLedger.Verdict.SPEND_UNREFUSABLE.charges());
    }

    // ---- 3. the UI door's refusal is not laundered into an answer ---------------------------------

    /**
     * <b>{@code claimForUpgrade} must answer "I could not look" distinctly from "I looked and saw
     * nothing".</b>
     *
     * <p>The count it feeds {@code MlsUpgradePolicy} is an {@code int}, and zero ALREADY means "we
     * claimed for every participant and nobody had a package" — a statement about the peers.
     * Reporting a partial count on a refusal feeds the policy an under-count, which answers
     * {@code NOT_ENOUGH_KEY_PACKAGES} and logs Google Messages' verbatim line naming two numbers nobody
     * measured. That is a refused look laundered into a wrong ANSWER, which we met
     * once already when a refusal was routed into {@code Health.UNKNOWN} and a drive reported "the
     * look came back unreadable" for a look never made.
     *
     * <p><b>Was {@code claimableKeyPackageCount}, and the rename is the fix</b>: the
     * method used to return an {@code int} because it DISCARDED what it claimed. It now returns the
     * packages, so the sentinel lives inside {@link MlsUpgradeClaim} — and the three states are
     * pinned below by BEHAVIOUR rather than by a source scan, which is the stronger check the
     * refactor made available.
     */
    @Test
    public void theUiDoorsRefusalIsNotACount() throws IOException {
        assertTrue("MlsClaimLedger.CLAIM_REFUSED is no longer negative, so it can be mistaken for a "
                + "count of claimable key packages", MlsClaimLedger.CLAIM_REFUSED < 0);
        // THE THREE STATES, EXERCISED. A refusal is not a count; "we asked and they had none" is a
        // count of zero; and "this claim says nothing about that peer" is neither — take() answers
        // null there and an EMPTY LIST for the peer the KDS served nothing for. Collapsing the last
        // two blames a participant for our own bookkeeping.
        assertEquals("a refused MlsUpgradeClaim no longer counts as CLAIM_REFUSED, so a refusal is "
                + "being reported to MlsUpgradePolicy as a number of participants",
                MlsClaimLedger.CLAIM_REFUSED, MlsUpgradeClaim.refusedByLedger("why").count());
        final java.util.LinkedHashMap<String, java.util.List<byte[]>> served =
                new java.util.LinkedHashMap<>();
        served.put("+15550000001", java.util.Collections.<byte[]>emptyList());
        served.put("+15550000002", java.util.Arrays.asList(new byte[] {1, 2, 3}));
        final MlsUpgradeClaim asked = MlsUpgradeClaim.claimed(served);
        assertEquals("a claim that reached the KDS for two peers and got a package for one no "
                + "longer counts 1 — the ALL-OR-NOTHING guard is being fed the wrong number",
                1, asked.count());
        assertFalse("a claim that reached the KDS reports itself as refused", asked.refused());
        assertNotNull("a peer the KDS served NOTHING for must come back as an empty list, not null "
                + "— null means 'this claim carries no answer about them', which is a different "
                + "fact and would send the create to claim for them a second time",
                asked.take("+15550000001"));
        assertNull("a peer this claim never covered must come back null, distinctly from the peer "
                + "above", asked.take("+15559999999"));
        assertNull("take() did not CONSUME — the same KeyPackage would be handed to two Welcomes, "
                + "and a KeyPackage is one-time (RFC 9420 s10)", asked.take("+15550000001"));

        final String body = bodyOf(codeOnly(readTransport()), "claimForUpgrade");
        assertTrue("claimForUpgrade() is gone — the UI door has been renamed and this guard no "
                + "longer watches it", body.length() > 0);
        assertTrue("claimForUpgrade() no longer charges the ledger. It CONSUMES a key package per "
                + "participant and its only caller is MlsConversationOpenListener, so an uncounted "
                + "spend here is a peer's pool drained at the rate a person taps a conversation "
                + "list.", body.contains("MlsClaimLedger.Caller.UPGRADE_PROBE"));
        final int refused = body.indexOf(".refused()");
        assertTrue("claimForUpgrade() no longer distinguishes a refused claim at all — it is "
                + "reporting our own rate ledger as a fact about the participants", refused >= 0);
        // THE ARM'S OWN BLOCK, brace-matched — NOT a fixed window forward from the marker. A
        // distance acquires whatever is inserted between the landmarks: it goes red when the arm
        // grows and green when the property moves into a neighbour (axis 4).
        final String arm = armContaining(body, refused);
        assertTrue("claimForUpgrade() detects the refusal and does not return a REFUSED claim from "
                + "THAT ARM, so it falls through to the claimed() form — and a claimed() form with "
                + "a partial map is read as 'this many participants have key packages', which is a "
                + "statement about them that we never made. A return somewhere else in the method "
                + "does not count: this must be the arm the refusal takes.",
                arm.contains("MlsUpgradeClaim.refusedByLedger("));
    }

    /**
     * <b>The upgrade path claims ONCE.</b>
     *
     * <p>{@code claimableKeyPackageCount} claimed a package per participant, read the byte array
     * only to increment an {@code int}, dropped it, and then {@code establishGroup} claimed AGAIN
     * per member. The resource is spent AT CLAIM TIME — we measured three claims returning
     * three different package sizes on deviceA — so the first round was a KeyPackage removed from
     * somebody else's device for nothing, once per participant, on the conversation-open path.
     *
     * <p>Two properties, and neither is checkable from the other end: the listener must PASS what it
     * claimed to the create, and the create must CONSUME it rather than re-claiming. Either half
     * alone restores the double spend silently — a create that ignores the parameter still works,
     * and a listener that drops the claim still upgrades.
     */
    @Test
    public void theUpgradePathClaimsOnce() throws IOException {
        final String listener = codeOnly(read(
                "src/com/android/messaging/rcs/e2ee/MlsConversationOpenListener.java"));
        final String upgrade = bodyOf(listener, "upgrade");
        assertTrue("MlsConversationOpenListener.upgrade() is gone and this guard is watching "
                + "nothing", upgrade.length() > 0);
        assertTrue("the upgrade-on-open path no longer calls claimForUpgrade(), so either the claim "
                + "moved somewhere this guard cannot see it or the probe is back", 
                upgrade.contains("claimForUpgrade("));
        final int claimAt = upgrade.indexOf("claimForUpgrade(");
        final int establishAt = upgrade.indexOf("establishGroup(");
        assertTrue("MlsConversationOpenListener.upgrade() no longer calls establishGroup(), so the "
                + "upgrade cannot complete and this guard is watching a path that does nothing",
                establishAt >= 0);
        assertTrue("upgrade() claims AFTER it calls establishGroup — the create would then do its "
                + "own claiming and the claim above it is discarded, which is the double spend",
                claimAt < establishAt);
        final String establishCall = upgrade.substring(establishAt,
                Math.min(upgrade.length(), establishAt + 200));
        assertTrue("upgrade() calls establishGroup WITHOUT handing over the claim it just made. "
                + "That claim is then thrown away and establishGroup dials the KDS a second time "
                + "for every member — the exact double spend that was removed, restored by "
                + "dropping one argument: " + establishCall.trim(),
                establishCall.contains("claim)"));

        final String gather = bodyOf(codeOnly(readTransport()), "gatherInitialKeyPackages");
        assertTrue("gatherInitialKeyPackages() is gone — the create's claim loop has moved and this "
                + "guard no longer watches whether it consumes the pre-claim", gather.length() > 0);
        assertTrue("the create no longer CONSUMES the pre-claim (preClaimed.take), so it is "
                + "re-claiming for members whose packages are already in hand",
                gather.contains("preClaimed.take("));
        assertTrue("the create no longer asks whether the pre-claim covers a member before claiming "
                + "live for them, so 'this claim says nothing about that peer' and 'that peer has "
                + "no packages' have been collapsed", gather.contains("preClaimed.covers("));
        final int refusedAt = gather.indexOf("preClaimed.refused()");
        assertTrue("the create no longer refuses a REFUSED pre-claim. It would fall through to a "
                + "live claim and spend exactly what the ledger's refusal withheld — the ledger "
                + "obeyed on one path and stepped around on another", refusedAt >= 0);
        assertTrue("the create checks the pre-claim's refusal AFTER it has already started claiming "
                + "for members, so the first members are claimed for before the refusal is honoured",
                refusedAt < gather.indexOf("claimAll("));
    }

    /**
     * The refusal must be tested BEFORE the shortfall comparison.
     *
     * <p>{@link MlsClaimLedger#CLAIM_REFUSED} is negative, so {@code -1 < remoteParticipants} is
     * true: an arm placed after the comparison never runs, and the refusal reports itself as the
     * participants being short of key packages. The ordering is the whole safety of the sentinel.
     */
    @Test
    public void theRefusalIsTestedBeforeTheShortfall() throws IOException {
        // CODE ONLY and offsets preserved — this asserts an ORDER, so a comment mentioning either
        // landmark would otherwise move the positions being compared.
        final String src = codeOnly(read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsUpgradePolicy.java"));
        final int refused = src.indexOf("MlsClaimLedger.CLAIM_REFUSED");
        final int shortfall = src.indexOf("claimedKeyPackages < remoteParticipants");
        assertTrue("MlsUpgradePolicy no longer tests for MlsClaimLedger.CLAIM_REFUSED, so a refused "
                + "probe is reported as a key-package shortfall — a claim about the participants "
                + "made on the strength of our own rate ledger", refused >= 0);
        assertTrue("MlsUpgradePolicy no longer compares claimedKeyPackages against "
                + "remoteParticipants — the guard this sentinel protects has moved and this check "
                + "no longer means anything", shortfall >= 0);
        assertTrue("MlsUpgradePolicy tests the shortfall (" + shortfall + ") BEFORE the refusal ("
                + refused + "). CLAIM_REFUSED is negative, so the comparison swallows it and every "
                + "refused probe is reported as NOT_ENOUGH_KEY_PACKAGES — the exact laundering the "
                + "sentinel exists to prevent, restored by an ordering.", refused < shortfall);
        assertEquals("a refused probe no longer answers CLAIM_REFUSED_BY_LEDGER",
                MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER,
                MlsUpgradePolicy.evaluate(true, false, false, true, true, "g:1", false,
                        MlsClaimLedger.CLAIM_REFUSED, 2, false));
        assertEquals("a real shortfall no longer answers NOT_ENOUGH_KEY_PACKAGES — the sentinel "
                + "has swallowed the case it was carved out of",
                MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES,
                MlsUpgradePolicy.evaluate(true, false, false, true, true, "g:1", false,
                        0, 2, false));
        assertFalse("CLAIM_REFUSED_BY_LEDGER must not proceed — it is not an upgrade decision, it is "
                + "the absence of one", MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.proceed());
        assertTrue("CLAIM_REFUSED_BY_LEDGER has no line. It is the one Decision that is NOT "
                + "Google Messages', so it must say so in its own words rather than borrow one of theirs "
                + "and corrupt a trace diff.",
                MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.line() != null
                        && MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.line()
                                .contains("OUR OWN"));
    }

    /**
     * <b>The rebuild's re-create must be unrefusable</b> — the H1 lesson, in the one
     * place where its remedy does not transfer.
     *
     * <p>{@code rebuildConversation} hoists its guards ABOVE its two forgets because a refusal
     * arriving after the forget leaves the conversation with no provider record, no engine state and
     * no group. This charge sits BELOW the forget and cannot be hoisted. Stage 1's answer there was
     * to FAIL OPEN and proceed without the server's era; <b>that answer does not exist here</b>,
     * because a claim's product is the input the re-create is made of. So the ledger must not refuse
     * this arm at all.
     */
    @Test
    public void theRebuildRecreateArmIsUnrefusable() throws IOException {
        assertTrue("REBUILD_RECREATE is no longer unrefusable. Reached from rebuildConversation both "
                + "halves of our state are already gone and the claim's product is what the "
                + "re-create is built from — a refusal there leaves the conversation with nothing at "
                + "all, which is strictly worse than the churn the refusal prevents.",
                MlsClaimLedger.Caller.REBUILD_RECREATE.isUnrefusable());
        final String ready = bodyOf(codeOnly(readTransport()), "ensureReady",
                "final boolean stateAlreadyDestroyed)");
        assertTrue("ensureReady's stateAlreadyDestroyed overload is gone", ready.length() > 0);
        assertTrue("ensureReady no longer selects its Caller on stateAlreadyDestroyed, so a rebuild "
                + "that has already dropped both halves of our state can be refused the KeyPackage "
                + "the re-create is made of. There is nothing to fail open TO here — unlike a "
                + "refused look, a refused claim leaves no group to build.",
                ready.contains("stateAlreadyDestroyed")
                        && ready.contains("MlsClaimLedger.Caller.REBUILD_RECREATE")
                        && ready.contains("MlsClaimLedger.Caller.ENSURE_READY"));
    }

    // ---- 4. nothing reaches the primitives around the ledger --------------------------------------

    @Test
    public void nothingReachesTheClaimPrimitivesAroundTheLedger() throws IOException {
        // ENUMERATED, NOT HAND-LISTED. A list of file names is correct on the day it is written and
        // blind to the next file ever added — the same hand-inventory defect the whole guard exists
        // to replace, sitting inside the guard's own exemption table. It walks every .java under
        // src/ and the allow-list is TWO files with a stated reason each.
        final List<String> around = new ArrayList<>();
        final List<File> all = javaSourcesUnderSrc();
        assertTrue("found no Java sources under src/ — the locator has gone stale and this check is "
                + "certifying a tree it cannot see", all.size() > 50);
        // ...AND THAT THEY HAVE CONTENT. The assertion above counts FILES, which are still there
        // when a scan is reading nothing: measured with every source truncated to
        // zero bytes this check stayed GREEN because the locator still found >50 files. A corpus
        // assertion on the wrong quantity is itself a check that cannot fail.
        int withContent = 0;
        for (final File f : all) if (f.length() > 0) withContent++;
        assertTrue("the locator found " + all.size() + " Java sources under src/ and " + withContent
                + " of them have any content — this check would be certifying a tree it cannot "
                + "read", withContent > 50);
        for (final File f : all) {
            final String rel = f.getPath();
            // MlsProviderTransport holds the two charged wrappers. ProviderTransport is the binder
            // shim and necessarily declares and forwards them — it is the layer the wrappers call
            // THROUGH, so a hit there is the ledger working, not a bypass.
            if (rel.endsWith(TRANSPORT) || rel.endsWith(PROVIDER_SHIM)) continue;
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
                final String q = Pattern.quote(aidl);
                final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                        .matcher(src);
                if (m.find()) around.add(rel + " calls " + aidl + "()");
            }
        }
        if (!around.isEmpty()) {
            fail("These claim a peer's KeyPackages without going through MlsProviderTransport, so "
                    + "they are doors to somebody else's pool OUTSIDE the class that owns the "
                    + "resource — invisible to any enumeration of MlsProviderTransport, which is "
                    + "where every inventory of these doors has looked: " + around + ". That is "
                    + "exactly where RcsDebugSendReceiver's two arms used to sit, one of "
                    + "them two lines above a call that had just been routed through the transport "
                    + "for the same reason. Route it through the transport with an "
                    + "MlsClaimLedger.Caller (an unrefusable one, if it is a debug arm).");
        }
    }

    /**
     * The AIDL spellings in the enum must be methods the shim actually declares.
     *
     * <p>Otherwise the enum points at nothing and every scan keyed on it reports green: the
     * "found none, certified the file" degradation, one indirection earlier than the scans catch it.
     */
    @Test
    public void theAidlSpellingsAreOnesTheProviderDeclares() throws IOException {
        final String shim = codeOnly(read("src/com/android/messaging/rcs/ProviderTransport.java"));
        for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
            assertTrue("MlsClaimLedger.AIDL_SPELLINGS names " + aidl + ", which ProviderTransport "
                    + "does not declare. The enum is the ground truth every scan in this class "
                    + "enumerates from, so a spelling that resolves to nothing makes each of them "
                    + "look at a method that does not exist and report green.",
                    Pattern.compile("\\b" + Pattern.quote(aidl) + "\\s*\\(").matcher(shim).find());
        }
    }

    // ---- 5. the arithmetic -----------------------------------------------------------------------

    /**
     * The ceiling must clear the one sequence a correct upgrade runs.
     *
     * <p>A ceiling that binds on the healthy case is not the bound this class is for — and here it
     * would be self-defeating in a way the fetch ledger's could not be: the probe would spend the
     * peer's allowance, then the establish it exists to authorise would be refused, and the upgrade
     * would fail immediately after its own precondition said it would succeed. That is the two-bounds
     * -fighting-each-other shape a device run found between the self-heal budget and the
     * fetch ledger, and it is cheap to design out here rather than measure later.
     */
    @Test
    public void theCeilingClearsTheUpgradeSequence() {
        final int upgrade = MlsClaimLedger.Caller.UPGRADE_PROBE.ration
                + MlsClaimLedger.Caller.GROUP_ESTABLISH.ration;
        assertTrue("the shared ceiling (" + MlsClaimLedger.SHARED_CEILING + ") leaves no room above "
                + "the probe-then-establish sequence a single successful upgrade runs (" + upgrade
                + "). Below it, the probe spends the peer's allowance and the establish it authorises "
                + "is then refused — the upgrade fails immediately after its own precondition said "
                + "it would succeed, and the ledger has defeated itself.",
                MlsClaimLedger.SHARED_CEILING >= upgrade);
        for (final MlsClaimLedger.Caller c : MlsClaimLedger.Caller.values()) {
            if (c.isUnrefusable()) continue;
            assertTrue(c + " has a ration of " + c.ration + ". A refusable caller needs a positive "
                    + "allowance; zero is a self-inflicted outage rather than a tight bound.",
                    c.ration >= 1);
            assertTrue(c + "'s ration (" + c.ration + ") exceeds the shared ceiling ("
                    + MlsClaimLedger.SHARED_CEILING + "), so its own allowance can never be what "
                    + "refuses it and the per-caller half of the ledger is decorative for it",
                    c.ration <= MlsClaimLedger.SHARED_CEILING);
        }
        assertTrue("UPGRADE_PROBE's ration (" + MlsClaimLedger.Caller.UPGRADE_PROBE.ration
                + ") is no longer the tightest. It is the ONE door of the nine whose rate is set by "
                + "a person tapping a conversation list, on a path whose only other bound "
                + "(!alreadyMls) goes away exactly when the upgrade keeps failing.",
                MlsClaimLedger.Caller.UPGRADE_PROBE.ration
                        <= MlsClaimLedger.Caller.GROUP_ESTABLISH.ration);
    }

    /** The record must keep UNREADABLE and ABSENT apart, which is what the strict posture rests on. */
    @Test
    public void anUnreadableRecordIsNotAnEmptyOne() {
        assertEquals("a record that is not there must decode to EMPTY",
                MlsClaimLedgerRecord.EMPTY, MlsClaimLedgerRecord.decode(null));
        assertTrue("a record that is STORED and unreadable must decode to null, not to EMPTY. "
                + "Merging them hands back a full allowance against somebody else's pool on the "
                + "strength of a parse failure.", MlsClaimLedgerRecord.decode("9|garbage") == null);
        final long now = 1_000_000L;
        MlsClaimLedgerRecord r = MlsClaimLedgerRecord.EMPTY;
        r = r.charged(MlsClaimLedger.Caller.UPGRADE_PROBE, now);
        r = r.charged(MlsClaimLedger.Caller.GROUP_ESTABLISH, now);
        assertEquals("a charge is not counted against its own caller", 1,
                r.spentBy(MlsClaimLedger.Caller.UPGRADE_PROBE, now));
        assertEquals("EVERY caller counts against this peer's ceiling — unlike the fetch ledger, "
                + "nothing is held outside it, because the reader that would need protecting is "
                + "unrefusable already", 2, r.spentAgainstCeiling(now));
        final MlsClaimLedgerRecord round = MlsClaimLedgerRecord.decode(r.encode());
        assertTrue("the record no longer round-trips through encode/decode", round != null);
        assertEquals("the round-tripped record lost a charge", 2, round.spentAgainstCeiling(now));
        assertEquals("charges do not age out of the window", 0,
                r.spentAgainstCeiling(now + MlsClaimLedger.WINDOW_MS + 1));
    }

    // ---- 16. the reduction that decides whether a peer is named ---------------------------------

    /**
     * <b>Exactly ONE outcome may be reduced to {@code Attribution.PEER_HAS_NONE}, and only inside
     * the reduction.</b>
     *
     * <p>{@code Attribution.PEER_HAS_NONE} is the only value whose ledger sentence is evidence
     * about the claim on a peer. It must come from {@code OUTCOME_PEER_HAS_NONE} and nothing else:
     * the six-value contract exists precisely so that {@code NOT_AUTHORIZED} — our own dead register
     * token, device-measured on {@code 00AU} — cannot reach that sentence.
     *
     * <p>RED WHEN (both reachable, and both are the natural next edit):
     * <ul>
     *   <li>a second outcome is admitted, e.g. {@code || outcome == OUTCOME_SERVED}, which is the
     *       tempting one because a SERVED-but-unpacked-to-nothing claim looks like an empty pool
     *       and is in fact a fault on OUR side;</li>
     *   <li>a NEW claim call site writes {@code Attribution.PEER_HAS_NONE} for itself instead of
     *       routing through the reduction — the same shape as the AIDL spellings this class
     *       retires.</li>
     * </ul>
     *
     * <p>Not an assertion that the reduction is the only site mentioning outcomes: the shim
     * legitimately names {@code OUTCOME_NOT_ATTEMPTED} and {@code OUTCOME_TRANSPORT_FAILED}. What is
     * pinned is the one direction that can print a peer's number next to a finding about them.
     */
    @Test
    public void onlyOneOutcomeCanBlameThePeer() throws IOException {
        assertEquals("", blameReduction(readTransport()));
    }

    /**
     * <b>THE NEGATIVE CONTROL for the guard above, and it is permanent rather than a transient I
     * reverted.</b> A guard whose failure has not been seen is one nobody knows works, and the two
     * failures that matter live in {@code MlsProviderTransport.java} — which this round belongs to
     * another agent, so they are produced against SYNTHETIC sources instead of by editing theirs.
     *
     * <p>Both mutations are the natural next edit rather than invented ones: admitting
     * {@code OUTCOME_SERVED} (tempting, because a SERVED claim that unpacked to nothing looks
     * exactly like an empty pool and is in fact a fault on OUR side), and a new call site writing
     * the blaming value for itself.
     */
    @Test
    public void theBlameReductionGuardActuallyFails() {
        final String clean =
                "    private static MlsClaimLedger.Attribution attributionOf(final int outcome) {\n"
                + "        return outcome == RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE\n"
                + "                ? MlsClaimLedger.Attribution.PEER_HAS_NONE\n"
                + "                : MlsClaimLedger.Attribution.NOT_ABOUT_THE_PEER;\n"
                + "    }\n";
        assertEquals("The guard must pass the shape it is written for.", "", blameReduction(clean));

        final String twoOutcomes = clean.replace(
                "outcome == RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE",
                "outcome == RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE\n"
                        + "                        || outcome == RcsMlsClaimResult.OUTCOME_SERVED");
        assertTrue("A SECOND outcome reduced to the blaming value must be caught.",
                blameReduction(twoOutcomes).contains("OUTCOME_SERVED"));

        final String secondSite = clean
                + "    private void someNewClaimCaller() {\n"
                + "        final MlsClaimLedger.Attribution a ="
                + " MlsClaimLedger.Attribution.PEER_HAS_NONE;\n"
                + "    }\n";
        assertTrue("A call site constructing the blaming value for itself must be caught.",
                blameReduction(secondSite).contains("someNewClaimCaller"));

        final String renamedAway = clean.replace("attributionOf", "reduceOutcome");
        assertFalse("A guard that cannot find its subject must SAY so rather than pass — an "
                + "absent needle reporting green is the failure this file is about.",
                blameReduction(renamedAway).isEmpty());
    }

    /**
     * The reduction check itself. Returns "" when {@code src} is clean, otherwise WHY — so the live
     * file and the synthetic mutations above run the identical logic.
     */
    private static String blameReduction(final String rawSrc) {
        final String src = codeOnly(rawSrc);
        final List<int[]> decls = declarations(src);

        final String blaming = "MlsClaimLedger.Attribution.PEER_HAS_NONE";
        final List<String> sites = new ArrayList<>();
        for (int at = src.indexOf(blaming); at >= 0; at = src.indexOf(blaming, at + 1)) {
            sites.add(enclosingMethod(src, decls, at));
        }
        if (!java.util.Collections.singletonList("attributionOf").equals(sites)) {
            return "The value that blames a peer may be MENTIONED in ONE place — the reduction — "
                    + "and these methods mention it: " + sites + ". This scan matches any reference, "
                    + "a comparison as much as an assignment, and that is deliberate: a call site "
                    + "that BRANCHES on PEER_HAS_NONE is deciding what it means just as much as one "
                    + "that produces it, and the policy it duplicates named an innocent device for a "
                    + "month. If the new site legitimately explains an ABSENCE, route it "
                    + "through the reduction rather than naming the constant — and note that "
                    + "theAttributionIsOnlyEverReadToExplainAnEmptyClaim guards the other half, so "
                    + "a read that explains nothing fails there too.";
        }

        final String body = bodyOf(src, "attributionOf", "int outcome");
        if (body.isEmpty()) {
            return "attributionOf(int) was not found. The guard cannot confirm what it is for, "
                    + "which is a FAILURE and not a pass.";
        }
        final List<String> outcomes = new ArrayList<>();
        final Matcher m = Pattern.compile("OUTCOME_[A-Z_]+").matcher(body);
        while (m.find()) outcomes.add(m.group());
        if (!java.util.Collections.singletonList("OUTCOME_PEER_HAS_NONE").equals(outcomes)) {
            return "The reduction may test exactly ONE outcome constant, and it must be the one "
                    + "that means the KDS ANSWERED with nothing for this peer. It tests: "
                    + outcomes;
        }
        return "";
    }

    /**
     * <b>The fallback to an older contract fires only on "we never asked"</b>,
     * commits {@code 416eb66f} and {@code 365f96ad}.
     *
     * <p>The shim's pre-v48 fallback used to key on "null or empty", which cannot tell a genuinely
     * empty pool from a claim the KDS REFUSED — so a refusal dialled {@code ClaimKeyPackages} a
     * SECOND time, two dials on the wire charged as one by this ledger, on exactly the lines already
     * in trouble. {@code OUTCOME_NOT_ATTEMPTED} and a {@code NoSuchMethodError} are the only two
     * facts that PROVE no dial was spent; a {@code RemoteException} is not one of them, because
     * whether the KDS was reached is then unknowable.
     *
     * <p>RED WHEN: the outcome test is replaced by an emptiness test, or the {@code NoSuchMethodError}
     * flag is inferred from an empty result rather than caught. Reachable — it is the code that was
     * there until the fix, in two separate contract windows.
     */
    @Test
    public void theOlderContractFallbackFiresOnlyWhenNoDialWasSpent() throws IOException {
        assertEquals("", fallbackPredicate(read("src/com/android/messaging/" + PROVIDER_SHIM)));
    }

    /**
     * <b>THE NEGATIVE CONTROL, run against the code that was really there.</b> The mutation is not
     * invented: it is the pre-{@code 416eb66f} shim, whose "null or empty" predicate dialled
     * {@code ClaimKeyPackages} a second time on every refusal, charged once.
     */
    @Test
    public void theFallbackGuardActuallyFails() {
        final String fixed =
                "    public java.util.List<byte[]> claimPeerKeyPackages(final int subId,"
                + " final String phoneE164, final int[] outcomeSink) {\n"
                + "        final RcsMlsClaimResult withOutcome = claimPeerKeyPackagesWithOutcome();\n"
                + "        if (withOutcome.outcome != RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED) {\n"
                + "            reportOutcome(outcomeSink, withOutcome.outcome);\n"
                + "            return null;\n"
                + "        }\n"
                + "        boolean neverDialled = false;\n"
                + "        try {\n"
                + "            packed = provider.claimPeerKeyPackages();\n"
                + "        } catch (final RemoteException e) {\n"
                + "        } catch (final NoSuchMethodError preV48) {\n"
                + "            neverDialled = true;\n"
                + "        }\n"
                + "        if (unpacked != null) {\n"
                + "            reportOutcome(outcomeSink, RcsMlsClaimResult.OUTCOME_SERVED);\n"
                + "            return unpacked;\n"
                + "        }\n"
                + "        if (!neverDialled) return null;\n"
                + "        return null;\n"
                + "    }\n";
        assertEquals("The guard must pass the shipping shape.", "", fallbackPredicate(fixed));

        // THE VALUE THE SINK MUST NEVER CARRY, on the path where nothing came back and nothing is
        // known. It reads as obviously right, which is how the defect got written.
        final String sinkInventsBlame = fixed.replace(
                "        if (!neverDialled) return null;\n",
                "        if (!neverDialled) {\n"
                        + "            reportOutcome(outcomeSink,"
                        + " RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE);\n"
                        + "            return null;\n"
                        + "        }\n");
        assertTrue("The shim inventing PEER_HAS_NONE for an answer it cannot explain must be "
                        + "caught.",
                fallbackPredicate(sinkInventsBlame).contains("PEER_HAS_NONE"));

        final String sinkNeverWired = fixed
                .replace("            reportOutcome(outcomeSink, withOutcome.outcome);\n", "")
                .replace("            reportOutcome(outcomeSink,"
                        + " RcsMlsClaimResult.OUTCOME_SERVED);\n", "");
        assertTrue("A sink that is declared and never written must be caught — otherwise the "
                        + "caller silently keeps its initial value and the guard reports green.",
                fallbackPredicate(sinkNeverWired).contains("not wired"));

        final String theOldBug = fixed
                .replace("if (withOutcome.outcome != RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED) {",
                        "if (packed == null || packed.length == 0) {")
                .replace("        if (!neverDialled) return null;\n", "");
        assertTrue("The predicate that cannot tell an empty pool from a refusal must be caught — "
                        + "it is what dialled twice and charged once.",
                fallbackPredicate(theOldBug).contains("NO DIAL"));

        final String remoteAlsoFallsBack = fixed.replace(
                "        } catch (final RemoteException e) {\n",
                "        } catch (final RemoteException e) {\n"
                        + "            neverDialled = true;\n");
        assertTrue("A RemoteException must NOT set the never-dialled flag: the binder failed and "
                        + "whether the KDS was reached is unknowable, so another dial is not free.",
                fallbackPredicate(remoteAlsoFallsBack).contains("RemoteException"));

        assertFalse("A guard that cannot find its subject must SAY so rather than pass.",
                fallbackPredicate("class X {}").isEmpty());
    }

    /** "" when the shim falls back only on "we never asked", otherwise WHY. */
    private static String fallbackPredicate(final String rawSrc) {
        final String claim = bodyOf(codeOnly(rawSrc), "claimPeerKeyPackages", "int subId");
        if (claim.isEmpty()) {
            return "claimPeerKeyPackages(int, String) was not found in the shim. The guard cannot "
                    + "confirm what it is for, which is a FAILURE and not a pass.";
        }
        if (!claim.contains("outcome != RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED")) {
            return "The contract-v60 fallback must be gated on the one outcome that means NO DIAL "
                    + "was spent. Keying it on an empty answer cannot tell a genuinely empty pool "
                    + "from a refusal, and spends a second ClaimKeyPackages on exactly the lines "
                    + "already in trouble (commit 416eb66f): " + claim;
        }
        if (!claim.contains("catch (final NoSuchMethodError")
                || !claim.contains("if (!neverDialled) return null;")) {
            return "The pre-v48 fallback must be gated on a flag set ONLY by NoSuchMethodError and "
                    + "read before the last dial — the same rule one contract window down, which "
                    + "the first fix missed (commit 365f96ad): " + claim;
        }
        final int remoteAt = claim.indexOf("catch (final RemoteException");
        final int noSuchAt = claim.indexOf("catch (final NoSuchMethodError");
        if (remoteAt < 0 || noSuchAt < 0) {
            return "Both catch arms must be present and distinct: " + claim;
        }
        if (claim.substring(remoteAt, noSuchAt).contains("neverDialled = true")) {
            return "A RemoteException must NOT set the never-dialled flag. The binder failed, so "
                    + "whether the KDS was reached is unknowable and another dial is not free — "
                    + "fall back only on 'we never asked': " + claim;
        }
        return sinkWritesOnlyWhatItKnows(claim);
    }

    /**
     * The outcome the shim reports must be one the PROVIDER told it, or one of the exactly two
     * facts the shim can establish for itself.
     *
     * <p>{@code NOT_ATTEMPTED}: no dial was spent, which the shim knows because it did not make
     * one. {@code SERVED}: it is holding the packages. <b>Everything else is the provider's to
     * say</b>, and must arrive as {@code withOutcome.outcome} rather than be chosen here.
     *
     * <p>The value this exists to keep out is {@code PEER_HAS_NONE}. It is the only outcome whose
     * ledger sentence is evidence about a peer, the shim has two paths on which nothing came back
     * and nothing is known, and writing it there would read as obviously right — which is how the
     * original defect was written in the first place.
     */
    private static String sinkWritesOnlyWhatItKnows(final String claimBody) {
        final java.util.Set<String> shimMayAssert = new java.util.HashSet<>(java.util.Arrays.asList(
                "RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED",
                "RcsMlsClaimResult.OUTCOME_SERVED",
                "withOutcome.outcome"));
        final String call = "reportOutcome(";
        int seen = 0;
        for (int at = claimBody.indexOf(call); at >= 0; at = claimBody.indexOf(call, at + 1)) {
            final String args = argumentList(claimBody, at + call.length());
            final int comma = topLevelComma(args);
            if (comma < 0) {
                return "reportOutcome is called with one argument, so this guard cannot read the "
                        + "outcome it writes — a scan that cannot see must FAIL, not pass: "
                        + args;
            }
            final String outcome = args.substring(comma + 1).trim();
            seen++;
            if (!shimMayAssert.contains(outcome)) {
                return "The shim reports an outcome it was not told and cannot establish: "
                        + outcome + ". It may pass through withOutcome.outcome, or assert exactly "
                        + "two things of its own — NOT_ATTEMPTED (it spent no dial) and SERVED (it "
                        + "holds the packages). Anything else, and PEER_HAS_NONE above all, is an "
                        + "invention about somebody's device.";
            }
        }
        if (seen == 0) {
            return "No reportOutcome call was found in the plural claim, so the outcome sink this "
                    + "guard is about is not wired — or it was renamed and the guard is now "
                    + "watching nothing.";
        }
        return "";
    }

    /** The text between an opening paren and its match, exclusive. */
    private static String argumentList(final String src, final int afterOpenParen) {
        int depth = 1;
        for (int i = afterOpenParen; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return src.substring(afterOpenParen, i);
        }
        return src.substring(afterOpenParen);
    }

    /** Index of the first comma at nesting depth zero, or -1. */
    private static int topLevelComma(final String args) {
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            final char c = args.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    // ---- 18. the attribution exists ONLY to explain an ABSENCE ----------------------------------

    /**
     * <b>Every read of {@code Claim.attribution()} must feed a line that explains an EMPTY claim.</b>
     *
     * <h2>The property this makes explicit, which nothing designed</h2>
     *
     * <p>A claim can return PACKAGES alongside a FAILURE outcome: the first dial fails at transport,
     * the alternate-KDS retry succeeds, and its peers are merged while the reported outcome keeps the
     * first dial's verdict ({@code MlsKeyPackageClaimer}: <i>"an alternate-kds retry can only add
     * peers, never explain a refusal away"</i>). That is deliberate and is not being changed. It is
     * safe only because <b>the attribution is currently read exclusively inside empty-answer
     * branches</b> — so on that path it is computed and never consulted, and the group is built from
     * the bytes.
     *
     * <p>Verified at HEAD across all four sites, three of them directly inside a null/empty guard and
     * the fourth ({@code gatherInitialKeyPackages}) via a {@code blame} local that is initialised to
     * {@code UNKNOWN} and read only in the empty branch. <b>But nobody designed that</b> — it falls
     * out of the attribution having existed only to explain an absence. The moment a caller reads it
     * on a SUCCESSFUL claim, a failure outcome riding along with good packages starts meaning
     * something, and it would start silently. This turns "currently safe" into "checked".
     *
     * <p>RED WHEN: a new call site branches on {@code attribution()} outside an empty-claim
     * explanation — e.g. {@code if (claim.attribution() == PEER_HAS_NONE) skipPeer();} in a method
     * that logs no blocked line. Reachable, and it is the natural next use of the value.
     */
    @Test
    public void theAttributionIsOnlyEverReadToExplainAnEmptyClaim() throws IOException {
        assertEquals("", attributionReadsExplainAbsence(readTransport()));
    }

    /** THE NEGATIVE CONTROL, against synthetic sources — the transport is another agent's file. */
    @Test
    public void theAttributionUseGuardActuallyFails() {
        final String ok =
                "    private boolean ensureReady(final String peer) {\n"
                + "        if (peerKp == null) {\n"
                + "            logClaimBlocked(MlsClaimLedger.oneToOneCreateBlockedLine(peer,"
                + " claim.attribution()));\n"
                + "        }\n"
                + "    }\n";
        assertEquals("The guard must pass the shipping shape.", "",
                attributionReadsExplainAbsence(ok));

        final String readOnSuccess = ok.replace(
                "            logClaimBlocked(MlsClaimLedger.oneToOneCreateBlockedLine(peer,"
                        + " claim.attribution()));\n",
                "            skipPeer(claim.attribution());\n");
        assertTrue("A read of attribution() that explains no absence must be caught: "
                        + attributionReadsExplainAbsence(readOnSuccess),
                attributionReadsExplainAbsence(readOnSuccess).contains("ensureReady"));

        assertFalse("A scan that finds NO read at all must FAIL, not certify — the plumbing would "
                        + "have been removed and the guard would be watching nothing.",
                attributionReadsExplainAbsence("class X { void f() {} }").isEmpty());
    }

    /**
     * The lines that exist to explain an EMPTY claim. Deliberately NOT {@code describeCharge},
     * {@code describeRefusal} or {@code describeUnrefusable} — those are the ledger's own accounting
     * and say nothing about a peer.
     */
    private static final String[] ABSENCE_EXPLAINERS = {
        "blockedByEmptyClaim", "describeEmptyClaim", "oneToOneCreateBlockedLine",
        "establishGroupBlockedLine", "addMemberBlockedLine", "rosterClaimBlockedLine",
    };

    /** "" when every attribution() read feeds an absence explanation, otherwise WHY. */
    private static String attributionReadsExplainAbsence(final String rawSrc) {
        final String src = codeOnly(rawSrc);
        final List<int[]> decls = declarations(src);
        final String read = ".attribution()";
        int seen = 0;
        for (int at = src.indexOf(read); at >= 0; at = src.indexOf(read, at + 1)) {
            seen++;
            final String method = enclosingMethod(src, decls, at);
            final String body = bodyOf(src, method);
            final String haystack = body.isEmpty() ? src : body;
            boolean explains = false;
            for (final String c : ABSENCE_EXPLAINERS) {
                if (haystack.contains(c)) { explains = true; break; }
            }
            if (!explains) {
                return "Claim.attribution() is read in " + method + ", which produces none of the "
                        + "lines that explain an EMPTY claim " + java.util.Arrays.toString(
                                ABSENCE_EXPLAINERS) + ". The attribution exists ONLY to say whose "
                        + "fault an ABSENCE is: a claim can return PACKAGES alongside a FAILURE "
                        + "outcome (first dial fails at transport, alternate-KDS retry succeeds), "
                        + "and reading the attribution on that path gives a failure verdict about a "
                        + "claim that worked.";
            }
        }
        if (seen == 0) {
            return "No read of Claim.attribution() was found in the transport, so contract v60's "
                    + "attribution is not reaching any log line — or it was renamed and this guard "
                    + "now certifies nothing.";
        }
        return "";
    }

    // ---- 17. the PROVIDER tier: no transport status may be translated into blame ----------------

    /**
     * <b>A DETECTOR WITH NO LIVE SUBJECT — read this before trusting its green.</b>
     *
     * <p>This was the negative control for a check that read the out-of-tree RCS provider's source
     * and required that nothing the transport says can become the outcome which blames a peer. That
     * check has been removed: the provider is not part of this repository, so it could only ever
     * have reported that it could not find its subject.
     *
     * <p>What survives is the DETECTOR and its proof that the detector works — it runs entirely on
     * synthetic sources written out below, so it passes here and always would. <b>It therefore says
     * nothing whatever about the provider's shipping code.</b> It is kept because the detector is
     * the expensive half and is ready to be adopted by the provider's own suite, which is where the
     * subject lives; a reader who sees this green and concludes the translation is checked has read
     * the name and not the subject, which is the exact failure this file is otherwise about.
     *
     * <p>If the provider's suite takes it, delete this copy rather than leaving two.
     */
    @Test
    public void theProviderBlameGuardActuallyFails() {
        final String claimer =
                "    private static Outcome outcomeForStatus(final String statusCode) {\n"
                + "        if (statusCode == null) return Outcome.TRANSPORT_FAILED;\n"
                + "        if (\"UNAUTHENTICATED\".equals(statusCode)) return Outcome.NOT_AUTHORIZED;\n"
                + "        if (\"TRANSPORT\".equals(statusCode) || \"EXCEPTION\".equals(statusCode)\n"
                + "                || \"UNKNOWN\".equals(statusCode)) return Outcome.TRANSPORT_FAILED;\n"
                + "        return Outcome.REFUSED;\n"
                + "    }\n"
                + "    private static ClaimResult claimWithOutcome() {\n"
                + "        if (outcome == Outcome.SERVED && total == 0) {\n"
                + "            outcome = Outcome.PEER_HAS_NONE;\n"
                + "        }\n"
                + "    }\n";
        final String service =
                "        private static int aidlOutcome(final Outcome o, final byte[] packed) {\n"
                + "            switch (o) {\n"
                + "                case SERVED:\n"
                + "                    return (packed == null || packed.length == 0)\n"
                + "                            ? RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE\n"
                + "                            : RcsMlsClaimResult.OUTCOME_SERVED;\n"
                + "            }\n"
                + "        }\n";
        assertEquals("The guard must pass the shipping shape.", "",
                providerBlameTranslation(claimer, service));

        final String notFoundIsBlame = claimer.replace(
                "        return Outcome.REFUSED;\n",
                "        if (\"NOT_FOUND\".equals(statusCode)) return Outcome.PEER_HAS_NONE;\n"
                        + "        return Outcome.REFUSED;\n");
        assertTrue("A transport status translated into blame must be caught — NOT_FOUND is the "
                        + "wrong-KDS shape, not an empty pool.",
                providerBlameTranslation(notFoundIsBlame, service).contains("outcomeForStatus"));

        final String noAnswerIsARefusal = claimer.replace(
                "        if (\"TRANSPORT\".equals(statusCode) || \"EXCEPTION\".equals(statusCode)\n"
                        + "                || \"UNKNOWN\".equals(statusCode)) return Outcome.TRANSPORT_FAILED;\n",
                "");
        assertTrue("A request that never reached a server falling through to REFUSED must be "
                        + "caught — that was the shipping behaviour until it was fixed.",
                providerBlameTranslation(noAnswerIsARefusal, service).contains("TRANSPORT"));

        final String armDeleted = claimer.replace(
                "        if (outcome == Outcome.SERVED && total == 0) {\n", "        if (false) {\n");
        assertTrue("Losing the claimer's answered-and-empty rule must be caught.",
                providerBlameTranslation(armDeleted, service).contains("SERVED"));

        final String aidlArmDeleted = service.replace(
                "                    return (packed == null || packed.length == 0)\n"
                        + "                            ? RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE\n"
                        + "                            : RcsMlsClaimResult.OUTCOME_SERVED;\n",
                "                    return RcsMlsClaimResult.OUTCOME_SERVED;\n");
        assertTrue("Losing aidlOutcome's empty-answer arm must be caught.",
                providerBlameTranslation(claimer, aidlArmDeleted).contains("aidlOutcome"));

        assertFalse("A guard that cannot find its subject must SAY so rather than pass.",
                providerBlameTranslation("class X {}", "class Y {}").isEmpty());
    }

    /** "" when the provider's translation cannot turn a transport status into blame, else WHY. */
    private static String providerBlameTranslation(final String rawClaimer,
            final String rawService) {
        final String claimer = codeOnly(rawClaimer);
        final String translate = declaredBodyAnyIndent(claimer, "outcomeForStatus");
        // THE SAME BODY WITH ITS STRING LITERALS INTACT. codeOnly blanks literal CONTENTS, so a
        // check for "TRANSPORT" against the blanked view can never pass — which it did not, loudly,
        // on first run. It preserves offsets character-for-character precisely so the raw text can
        // be recovered at the same indices, which is what this does.
        final int bodyAt = translate.isEmpty() ? -1 : claimer.indexOf(translate);
        final String translateRaw = bodyAt < 0 ? ""
                : rawClaimer.substring(bodyAt, bodyAt + translate.length());
        if (translate.isEmpty()) {
            return "outcomeForStatus(String) was not found in MlsKeyPackageClaimer. The guard "
                    + "cannot confirm what it is for, which is a FAILURE and not a pass.";
        }
        if (translate.contains("PEER_HAS_NONE")) {
            return "outcomeForStatus maps a TRANSPORT STATUS onto PEER_HAS_NONE. A status is a "
                    + "fact about the CALL; PEER_HAS_NONE is the one outcome whose ledger sentence "
                    + "is evidence about a PEER, and it must come only from a round-trip the KDS "
                    + "ANSWERED. NOT_FOUND in particular is the wrong-KDS-instance shape "
                    + "(device-measured grpc-13 then grpc-5, 2026-09-10), not an empty pool: "
                    + translate;
        }
        // NO ANSWER IS NOT A REFUSAL. KdsClient spells a request that never reached a server
        // "TRANSPORT" (a Cronet transport error), "EXCEPTION" (a throw around the dial) or
        // "UNKNOWN" (neither a gRPC status nor a transport error) — its OWN names, not gRPC ones.
        // All three fell through to REFUSED until the fix, which is the same defect one label
        // over: "refused" says something received the request and rejected it, and a DNS failure
        // means nothing heard it. Device-measured 2026-09-11 via an RFC 2606 .invalid host.
        for (final String noAnswer : new String[] {"TRANSPORT", "EXCEPTION", "UNKNOWN"}) {
            final int at = translateRaw.indexOf('"' + noAnswer + '"');
            if (at < 0) {
                return "outcomeForStatus does not mention KdsClient's \"" + noAnswer + "\" status, "
                        + "so a request that NEVER REACHED A SERVER falls through to REFUSED — a "
                        + "label claiming something rejected us when nothing answered. "
                        + "Every failure path in KdsClient sets a non-null statusCode, so the "
                        + "`statusCode == null` arm cannot cover this: " + translate;
            }
            if (!translateRaw.substring(at).contains("Outcome.TRANSPORT_FAILED")) {
                return "outcomeForStatus names \"" + noAnswer + "\" but does not map it to "
                        + "TRANSPORT_FAILED: " + translate;
            }
        }
        if (!claimer.contains("outcome == Outcome.SERVED && total == 0")) {
            return "The claimer's only path to Outcome.PEER_HAS_NONE must be the round-trip that "
                    + "was SERVED and produced nothing. That rule is gone, so either the outcome "
                    + "is now unreachable (the ledger's most confident sentence can never fire) or "
                    + "it is reachable from something weaker. This arm is recorded as "
                    + "UNEXERCISED on any device, which is exactly why it is pinned rather than "
                    + "trusted.";
        }
        final String service = codeOnly(rawService);
        final String aidl = declaredBodyAnyIndent(service, "aidlOutcome");
        if (aidl.isEmpty()) {
            return "aidlOutcome(Outcome, byte[]) was not found in RcsProviderService. The guard "
                    + "cannot confirm what it is for, which is a FAILURE and not a pass.";
        }
        if (!aidl.contains("packed.length == 0")
                || !aidl.contains("OUTCOME_PEER_HAS_NONE")) {
            return "aidlOutcome must still turn a SERVED round-trip that carried no bytes into "
                    + "OUTCOME_PEER_HAS_NONE. This and the claimer's rule are BELT AND BRACES — "
                    + "both would have to go for an empty answer to reach the app as SERVED — so "
                    + "each looks redundant on its own and neither is: " + aidl;
        }
        return "";
    }

    /**
     * The brace-matched body of a method DECLARATION at any indent, found structurally rather than
     * by the four-space {@link #METHOD_DECL} pattern — the provider declares {@code aidlOutcome}
     * inside a nested Binder stub, so it sits at eight.
     *
     * <p>A declaration is distinguished from a CALL by what follows the closing paren: a body.
     * Matching on the name alone would answer with the block after a call site, which for
     * {@code aidlOutcome} appears EARLIER in the file than its declaration — a guard reading the
     * wrong method is that degradation wearing a structural disguise.
     */
    private static String declaredBodyAnyIndent(final String src, final String name) {
        for (int at = src.indexOf(name); at >= 0; at = src.indexOf(name, at + 1)) {
            int i = at + name.length();
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            if (i >= src.length() || src.charAt(i) != '(') continue;
            int depth = 0;
            for (; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
            }
            int j = i + 1;
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == '{') return bracedBlock(src, j);
        }
        return "";
    }

    private static List<String> unrefusableNames() {
        final List<String> out = new ArrayList<>();
        for (final MlsClaimLedger.Caller c : MlsClaimLedger.Caller.values()) {
            if (c.isUnrefusable()) out.add(c.name());
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ---- 19. a claim that was NEVER SENT is refunded, and never framed as one that was MADE ------

    /**
     * <b>{@code OUTCOME_NOT_ATTEMPTED} must reach a REFUND, and must never reach the MADE frame.</b>
     *
     * <h2>The defect</h2>
     *
     * <p>{@code spendOneClaim} charges BEFORE it dials, and that ordering is right and stays:
     * a claim that HAPPENED and went uncounted leaves the next caller told the pool is
     * fuller than it is). Nothing handled the mirror. {@code NOT_ATTEMPTED} is the one outcome that
     * ASSERTS no dial was spent — an unbound provider, or one predating contract v60 — and there was
     * no refund anywhere, so a claim nobody made spent one of a peer's four-per-ten-minutes and the
     * ledger then logged it through a frame that hard-codes <i>"was MADE and came back EMPTY"</i>.
     * Device-measured on 010T 2026-09-11: two such claims took {@code +15715550103} from 1 to 2 of 4
     * while zero dials and zero KeyPackages were spent.
     *
     * <p>It is a RECOVERY-WINDOW defect rather than a permanent one, which is what makes it worth a
     * guard: four phantom charges in ten minutes make the ledger REFUSE a real claim at exactly the
     * moment a provider has come back and the first real claim matters.
     *
     * <h2>What is checked, and why each half</h2>
     *
     * <ol>
     *   <li>The not-attempted check RETURNS before {@code describeEmptyClaim} — a branch that falls
     *       through reaches the MADE frame, which is the original defect.</li>
     *   <li>Every {@code describeNotAttempted} call site either REFUNDS or passes
     *       {@link MlsClaimLedger#NO_COUNT}. You may say the charge came back, or you may say you
     *       could not give it back; saying the first without doing it is the same defect one layer up.</li>
     *   <li>Both charged wrappers WRITE the sink. Without that the branch is unreachable and every
     *       check above passes over dead code.</li>
     * </ol>
     *
     * <p>RED WHEN: the refund is deleted, the branch is moved below the empty-claim log, a wrapper
     * stops reporting the outcome, or a refund line is emitted by a method that does not refund.
     * Every one of those is one edit away, and the first three are what a "simplify the claim path"
     * pass does.
     */
    @Test
    public void aClaimThatWasNeverSentIsRefundedAndNeverFramedAsMade() throws IOException {
        assertEquals("", notAttemptedIsRefundedNotCharged(readTransport()));
    }

    /** THE NEGATIVE CONTROL, against synthetic sources — the transport is another agent's file. */
    @Test
    public void theNotAttemptedRefundGuardActuallyFails() {
        assertEquals("The guard must pass the shipping shape.", "",
                notAttemptedIsRefundedNotCharged(SHIPPING_SHAPE));

        final String noReturn = SHIPPING_SHAPE.replace(
                "            return notAttemptedAfterCharge(caller, peer, now, ceiling, outcome);\n",
                "            logIt(outcome);\n");
        assertTrue("A not-attempted branch that falls through into the MADE frame must be caught: "
                        + notAttemptedIsRefundedNotCharged(noReturn),
                notAttemptedIsRefundedNotCharged(noReturn).contains("RETURN"));

        final String noRefund = SHIPPING_SHAPE.replace("        r.refunded(caller, now);\n", "");
        assertTrue("A method that PRINTS a refund without making one must be caught: "
                        + notAttemptedIsRefundedNotCharged(noRefund),
                notAttemptedIsRefundedNotCharged(noRefund).contains("refund"));

        final String deadBranch = SHIPPING_SHAPE.replace(
                "            outcome.notAttempted = r.outcome"
                        + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n", "");
        assertTrue("A wrapper that never reports the outcome leaves the refund branch DEAD and must "
                        + "be caught: " + notAttemptedIsRefundedNotCharged(deadBranch),
                notAttemptedIsRefundedNotCharged(deadBranch).contains("claimOne"));

        assertFalse("A scan that finds NO describeNotAttempted at all must FAIL, not certify — the "
                        + "whole third frame would have been removed and the guard would be "
                        + "watching nothing.",
                notAttemptedIsRefundedNotCharged(
                        SHIPPING_SHAPE.replace("MlsClaimLedger.describeNotAttempted(", "logIt("))
                        .isEmpty());

        // THE ONE THAT WOULD DO REAL DAMAGE, and it is the reason the gate is checked at all rather
        // than only its presence. Widening the flag to "the answer was empty" makes every empty
        // claim take the refund path: a claim that DID dial and DID drain a peer gets its charge
        // handed back, which is the accounting defect with the sign flipped — and it silently
        // retires describeEmptyClaim's "was MADE" frame, whose own unit test would stay green
        // because it calls the formatter directly.
        final String widened = SHIPPING_SHAPE.replace(
                "            outcome.notAttempted = r.outcome"
                        + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n",
                "            outcome.notAttempted = r.keyPackages == null;\n");
        assertTrue("A not-attempted flag derived from the ANSWER rather than from the OUTCOME must "
                        + "be caught — it refunds claims that really dialled: "
                        + notAttemptedIsRefundedNotCharged(widened),
                notAttemptedIsRefundedNotCharged(widened).contains("OUTCOME_NOT_ATTEMPTED"));
    }

    /**
     * The shipping shape, reduced to what this guard reads. Synthetic on purpose: the transport is
     * another agent's file, so a control that mutates the real source cannot be run here.
     */
    private static final String SHIPPING_SHAPE =
            "    private <T> Claim<T> spendOneClaim(final Caller caller, final String peer,\n"
            + "            final ClaimOutcomeSink outcome, final PeerClaim<T> doIt) {\n"
            + "        final T answer = doIt.claim();\n"
            + "        if (outcome.notAttempted) {\n"
            + "            return notAttemptedAfterCharge(caller, peer, now, ceiling, outcome);\n"
            + "        }\n"
            + "        if (answer == null) {\n"
            + "            logIt(MlsClaimLedger.describeEmptyClaim(caller, peer, shared));\n"
            + "        }\n"
            + "        return Claim.asked(answer, outcome.attribution);\n"
            + "    }\n"
            + "\n"
            + "    private <T> Claim<T> notAttemptedAfterCharge(final Caller caller,\n"
            + "            final String peer, final long now, final int ceiling,\n"
            + "            final ClaimOutcomeSink outcome) {\n"
            + "        r.refunded(caller, now);\n"
            + "        final String why = MlsClaimLedger.describeNotAttempted(caller, peer, n,\n"
            + "                ceiling, outcome.detail);\n"
            + "        return Claim.notAttempted(outcome.attribution, why);\n"
            + "    }\n"
            + "\n"
            + "    private Claim<byte[]> claimOne(final Caller caller, final String peer) {\n"
            + "        final ClaimOutcomeSink outcome = new ClaimOutcomeSink();\n"
            + "        return spendOneClaim(caller, peer, outcome, () -> {\n"
            + "            final RcsMlsClaimResult r = pt(\"x\")"
            + ".claimPeerKeyPackagesWithOutcome(mSubId, peer);\n"
            + "            outcome.notAttempted = r.outcome"
            + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n"
            + "            return firstClaimedPackage(r.keyPackages);\n"
            + "        });\n"
            + "    }\n"
            + "\n"
            + "    private Claim<java.util.List<byte[]>> claimAll(final Caller caller,\n"
            + "            final String peer) {\n"
            + "        final ClaimOutcomeSink outcome = new ClaimOutcomeSink();\n"
            + "        return spendOneClaim(caller, peer, outcome, () -> {\n"
            + "            final java.util.List<byte[]> kps = pt(\"y\")"
            + ".claimPeerKeyPackages(mSubId, peer, sink);\n"
            + "            outcome.notAttempted = sink[0]"
            + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n"
            + "            return kps;\n"
            + "        });\n"
            + "    }\n";

    /** "" when a never-sent claim is refunded and reframed, otherwise WHY. */
    private static String notAttemptedIsRefundedNotCharged(final String rawSrc) {
        final String src = codeOnly(rawSrc);
        final String body = bodyOf(src, "spendOneClaim");
        if (body.isEmpty()) {
            return "spendOneClaim() is gone, so the charge point this guard reads no longer exists "
                    + "and every claim's accounting is somewhere else.";
        }
        final int made = body.indexOf("describeEmptyClaim(");
        if (made < 0) {
            return "spendOneClaim() no longer logs describeEmptyClaim, so this guard cannot see "
                    + "whether a NEVER-SENT claim is being framed as one that was MADE — the exact "
                    + "thing this was filed for.";
        }
        // THE WINDOW IS THE DIAL-TO-FRAME GAP, not "anywhere before the frame". spendOneClaim's
        // unreadable-ledger arm reads the same field much earlier and returns there, so a check
        // anchored on the first read — or on the last one, which lands on the HELPER'S NAME inside
        // the return rather than on the condition — certifies a different branch than the one that
        // matters. Both of those were tried; the second passed the shipping shape and would have
        // gone red on correct code.
        final int afterDial = Math.max(0, body.lastIndexOf("doIt.claim()", made));
        final String gap = body.substring(afterDial, made);
        final int flag = gap.indexOf("notAttempted");
        if (flag < 0) {
            return "nothing between spendOneClaim()'s dial and its \"was MADE and came back EMPTY\" "
                    + "line asks whether the provider ever SENT the claim. OUTCOME_NOT_ATTEMPTED "
                    + "means no dial was spent, so that line asserts an action that did not happen "
                    + "and the charge taken in front of it is a phantom.";
        }
        final int ret = gap.indexOf("return", flag);
        if (ret < 0) {
            return "spendOneClaim()'s not-attempted branch does not RETURN before describeEmptyClaim "
                    + "— a claim the provider never sent falls through into the frame that says it "
                    + "was MADE, which is the second fault verbatim.";
        }
        final List<int[]> decls = declarations(src);
        final String call = "describeNotAttempted(";
        int seen = 0;
        for (int at = src.indexOf(call); at >= 0; at = src.indexOf(call, at + 1)) {
            seen++;
            final String method = enclosingMethod(src, decls, at);
            final String m = bodyOf(src, method);
            final String hay = m.isEmpty() ? src : m;
            if (!hay.contains(".refunded(") && !hay.contains("NO_COUNT")) {
                return method + "() logs the not-attempted line without either giving the charge "
                        + "back (.refunded) or declaring that it could not (MlsClaimLedger."
                        + "NO_COUNT). That line SAYS the charge was refunded, so emitting it "
                        + "without a refund is the same defect one layer up: a ledger "
                        + "sentence asserting more than the evidence supports.";
            }
        }
        if (seen == 0) {
            return "No call to MlsClaimLedger.describeNotAttempted was found, so a claim the "
                    + "provider never sent has no frame of its own and falls back into REFUSED or "
                    + "MADE — the two-word vocabulary a third word was added to.";
        }
        for (final String[] row : CHARGED_WRAPPERS) {
            final String w = bodyOf(src, row[0]);
            final int set = w.indexOf("notAttempted =");
            if (w.isEmpty() || set < 0) {
                return row[0] + "() never reports whether the provider SENT the claim, so the "
                        + "refund branch in spendOneClaim() is unreachable from it and this "
                        + "wrapper's phantom charges stand. A guard over a branch nothing can reach "
                        + "certifies dead code.";
            }
            // THE GATE MUST BE THAT ONE OUTCOME, AND NOTHING WIDER. This is the dangerous
            // regression rather than a style rule: derive the flag from the ANSWER instead — a
            // null list, an empty byte[] — and every empty answer starts taking the refund path,
            // so a claim that really dialled and really drained a peer gets its charge handed back.
            // That is the accounting defect with the sign flipped, arriving through its own fix, and no
            // wording test would see it because the NOT ATTEMPTED line would be correct-looking.
            final int end = w.indexOf(';', set);
            final String rhs = end < 0 ? w.substring(set) : w.substring(set, end);
            if (!rhs.contains("OUTCOME_NOT_ATTEMPTED")) {
                return row[0] + "() sets the not-attempted flag from something other than an "
                        + "equality against OUTCOME_NOT_ATTEMPTED — read: " + rhs.trim() + ". Only "
                        + "that one outcome ASSERTS no dial was spent. Anything broader (a null or "
                        + "empty ANSWER, a failure outcome, a caught exception) sends a claim that "
                        + "DID dial down the refund path, handing back an allowance for packages "
                        + "that are really gone — and it also makes describeEmptyClaim's \"was "
                        + "MADE\" frame unreachable for the five outcomes that still earn it.";
            }
        }
        return "";
    }

    // ---- helpers (the model guard's, unchanged where they were already right) ----------------------

    /**
     * The source with every comment and string literal replaced by spaces, character for character.
     *
     * <p>Blanked rather than removed so that every offset — and therefore
     * {@link #enclosingMethod} — still refers to the same place in the real file. A scan that
     * reported the right defect at the wrong line would be worse than one that missed it.
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
                i++;                                  // the opening quote itself always stays
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

    /**
     * The brace-matched block of the {@code if} arm containing {@code at}, or the rest of the body
     * if {@code at} is not inside one. Used instead of a fixed window forward from a marker: a
     * distance acquires whatever is inserted between the landmarks (axis 4).
     */
    private static String armContaining(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return body.substring(at);
        final int open = body.indexOf('{', at);
        if (open < 0) return body.substring(at);
        final int semi = body.indexOf(';', at);
        if (semi >= 0 && semi < open) return body.substring(at);   // braceless arm; no block to take
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

    private static File firstDirectory(final String... candidates) {
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    private static List<File> javaFilesUnder(final File root) {
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

    /** Every {@code .java} under the module's {@code src/}, wherever the runner's cwd happens to be. */
    private static List<File> javaSourcesUnderSrc() throws IOException {
        final File root = firstDirectory("src", "packages/apps/Messaging/src", "../src");
        if (root == null) {
            throw new IOException("src/ not found from " + new File(".").getAbsolutePath());
        }
        return javaFilesUnder(root);
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
     * The brace-matched body of the first method whose name matches EXACTLY and whose declaration
     * contains {@code declFragment}.
     *
     * <p>Exact, never a substring: {@code claimOne} would otherwise match inside a longer name and
     * answer with the wrong body, reporting the right one unguarded.
     */
    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The DECLARATION only, never into the body: a method that merely CALLS the one you
            // asked for would otherwise answer as though it were it.
            if (!declFragment.isEmpty() && !src.substring(m.start(), open).contains(declFragment)) {
                continue;
            }
            final String body = bracedBlock(src, open);
            if (body.isEmpty() || isDelegateTo(body, name)) continue;
            return body;
        }
        return "";
    }

    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    /**
     * Whether {@code body} is a DELEGATING overload — its only statement is a return of a call to
     * its own name.
     *
     * <p>{@link #bodyOf} matches by name and answers the FIRST declaration, so a delegate added
     * above the real one hands every check a one-line body and reports every property missing. This
     * is not hypothetical: a delegating {@code rebuildConversation} overload turned two of the model
     * guard's assertions red on correct code. Recognised STRUCTURALLY — a declaration fragment would
     * be the spelling-keyed defect one field over.
     */
    private static boolean isDelegateTo(final String body, final String name) {
        final String inner = codeOnly(body).trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) return false;
        final String stmt = inner.substring(1, inner.length() - 1).trim();
        return stmt.startsWith("return " + name + "(") && stmt.indexOf(';') == stmt.length() - 1;
    }

    private static String readTransport() throws IOException {
        return read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java");
    }

    private static String readDebugReceiver() throws IOException {
        return read("src/com/android/messaging/rcs/RcsDebugSendReceiver.java");
    }

    /** Same locator as the model guard: works from the module dir or the tree root. */
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
