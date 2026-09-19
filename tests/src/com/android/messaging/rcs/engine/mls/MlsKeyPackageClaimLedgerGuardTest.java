/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Every path that claims a peer's KeyPackages is charged to {@link MlsClaimLedger}. Keyed on the
 * app-side spellings in {@link MlsClaimLedger#AIDL_SPELLINGS}, which are one spend point: both
 * reach the same server claim, and the singular discards the extra packages afterwards. The
 * provider-side invariants (its own claim doors, and that no transport status becomes {@code
 * PEER_HAS_NONE}) live in the provider and are not checked here. See docs/mls/budgets.md and
 * docs/testing.md.
 */
public final class MlsKeyPackageClaimLedgerGuardTest {

    /**
     * The only methods allowed to invoke an AIDL claim, and the spelling each carries: one package
     * back for a 1:1, every device's for a group create.
     */
    private static final String[][] CHARGED_WRAPPERS = {
        {"claimOne", "claimPeerKeyPackagesWithOutcome"},
        {"claimAll", "claimPeerKeyPackages"},
    };

    /** The one method that asks the ledger; both wrappers go through it. */
    private static final String CHARGE_POINT = "spendOneClaim(";

    /** Methods whose first argument must be an {@code MlsClaimLedger.Caller}. */
    private static final String[] MUST_DECLARE_A_CALLER = {"claimOne", "claimAll"};

    /**
     * The transport methods allowed to pass an unrefusable caller, named rather than matched by
     * prefix. Three are operator diagnostics; {@code ensureReady} holds {@code REBUILD_RECREATE},
     * because a rebuild that has already dropped our state is made of the claim's product.
     */
    private static final java.util.Set<String> MAY_PASS_AN_UNREFUSABLE_CALLER =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dumpKeyPackageCount",        // --ez kpcount
                    "debugClaimAllKeyPackages",   // --es claimkp
                    "debugClaimOneKeyPackage",    // --ez ctrl
                    "ensureReady"));              // REBUILD_RECREATE

;

    private static final String TRANSPORT = "e2ee/MlsProviderTransport.java";
    private static final String PROVIDER_SHIM = "rcs/ProviderTransport.java";
    private static final String RPC_BINDING = "e2ee/MlsProviderRpcBinding.java";

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    @Test
    public void everyClaimCallSiteIsInsideAChargedWrapper() throws IOException {
        // Code only: both spellings also appear in comments and log strings. Blanked in place.
        final String src = codeOnly(readTransport());
        final List<int[]> decls = declarations(src);
        assertTrue("no method declarations matched — the pattern has gone stale and this guard is "
                + "silently passing, which is worse than failing", decls.size() > 20);

        int sites = 0;
        final List<String> stray = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        final List<String> duplicated = new ArrayList<>();
        for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
            // Keyed on the invocation, not on the pt("…") lock-assertion label, which is free-form.
            // A method reference has no parens, so both spellings are matched.
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
            // Per spelling: a total would pass if one dropped to zero while the other gained a
            // site.
            if (perSpelling == 0) {
                vanished.add(aidl);
            } else if (perSpelling != 1) {
                duplicated.add(aidl + " x" + perSpelling);
            }
        }
        // Retired spellings are asserted at zero rather than dropped from the scan.
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
            fail(
                    "These AIDL spellings are RETIRED — no longer doors from MlsProviderTransport — and "
                    + "have come back: " + resurrected + ". Either route the call through the live "
                    + "wrapper, or move the spelling from MlsClaimLedger.RETIRED_AIDL_SPELLINGS back "
                    + "into AIDL_SPELLINGS with a CHARGED_WRAPPERS row, so the ledger is accounting "
                    + "for it again.");
        }
        if (!vanished.isEmpty()) {
            fail("This guard found NO invocation of " + vanished
                    + " in MlsProviderTransport, so it "
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
            fail(
                    "These calls claim a peer's KeyPackage from a method that is not a charged wrapper, "
                    + "so the peer's pool is spent with nothing counting it: " + stray + ". Route "
                    + "the call through claimOne()/claimAll() and pass the MlsClaimLedger.Caller "
                    + "that owns the decision. The pool being spent is NOT OURS — a peer with none "
                    + "left cannot be added, re-Welcomed or brought into a rebuilt group.");
        }
    }

    @Test
    public void everyWrapperReachesTheChargePoint() throws IOException {
        // Code only: a charge left behind in a comment would otherwise satisfy every assertion.
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
     * The two AIDL forms are one spend point: one {@link MlsClaimLedger.Primitive}, and both
     * wrappers reach the same charge point, so neither is cheaper in the ledger than at the server.
     */
    @Test
    public void theTwoSpellingsAreOneSpendPoint() throws IOException {
        assertEquals(
                "MlsClaimLedger.Primitive has gained a constant. The two AIDL spellings are ONE "
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
            assertTrue(row[0] + "() no longer reaches " + CHARGE_POINT
                    + ", so the two spellings no "
                    + "longer share a charge point and one of them is cheaper than the other in the "
                    + "ledger while costing the same at the KDS", body.contains(CHARGE_POINT));
        }
    }

    /**
     * The charge point consults {@link MlsClaimLedger#mayClaim} and refuses before the dial; a
     * refusal after the claim bounds nothing.
     */
    @Test
    public void aRefusedClaimDoesNotReachTheKds() throws IOException {
        // Code only, blanked in place: this test compares offsets.
        final String body = bodyOf(codeOnly(readTransport()), "spendOneClaim");
        assertTrue("spendOneClaim() is gone — every wrapper's charge went with it",
                body.length() > 0);
        assertTrue("spendOneClaim() does not ask MlsClaimLedger.mayClaim at all",
                body.contains("MlsClaimLedger.mayClaim("));
        final int ask = body.indexOf("MlsClaimLedger.mayClaim(");
        final int refuse = body.indexOf("Claim.refusedByLedger(", ask);
        assertTrue(
                "spendOneClaim() asks the ledger and has no arm that returns a refusal — a budget "
                + "whose answer is discarded is worse than no budget", refuse >= 0);

        // Checked per occurrence of doIt.claim(), not from the ask onward, so a claim hoisted above
        // mayClaim() is visible. The unreadable-ledger arm legitimately claims before the ask,
        // guarded by isUnrefusable().
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
        assertTrue("spendOneClaim() claims BEFORE it can refuse (refusal at " + refuse
                + ", claim at "
                + last + "). A refusal after the KDS round-trip bounds nothing: every device's "
                + "KeyPackage for that peer is already consumed.", refuse < last);
        assertTrue("spendOneClaim()'s second claim (" + last
                + ") happens before it asks the ledger (" + ask + ")", ask < last);
    }

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
                // Skip the declaration itself; only calls carry an argument list.
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
     * Every live {@link MlsClaimLedger.Caller} is passed at a call site, and every retired one at
     * none. A constant is retired rather than deleted because {@link MlsClaimLedgerRecord} persists
     * callers by ordinal. None is retired today.
     */
    @Test
    public void everyCallerConstantIsActuallyUsed() throws IOException {
        // Code only: a constant named in a comment would read as a live door.
        final String src = codeOnly(readTransport()) + codeOnly(readDebugReceiver());
        // Raw: the retired marker is in the constant's javadoc, which codeOnly blanks.
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
            fail("These MlsClaimLedger.Caller constants are passed at no call site: "
                    + unusedAndLive
                    + ". A ration nobody spends looks like a bounded door and is not one — either "
                    + "the door was renamed and its constant left behind, or the constant was added "
                    + "for a caller that never landed. Wire it — or, if its caller was deliberately "
                    + "DELETED, mark the constant RETIRED in its MlsClaimLedger javadoc and say what "
                    + "replaced it. DO NOT DELETE THE CONSTANT: MlsClaimLedgerRecord persists the "
                    + "caller by ORDINAL, so removing one shifts every later constant and silently "
                    + "reattributes charges already on disk.");
        }
        if (!retiredButStillCharged.isEmpty()) {
            fail(
                    "These MlsClaimLedger.Caller constants are marked RETIRED in their javadoc but ARE "
                    + "still passed at a call site: " + retiredButStillCharged + ". RETIRED means "
                    + "the door is gone and the ordinal is only reserved; a live charge against one "
                    + "is either a caller that came back without the javadoc being updated, or the "
                    + "word being used to silence this test for a door that is still open.");
        }
    }

    /**
     * Whether {@code name}'s declaration in {@code MlsClaimLedger} carries a "RETIRED" marker in
     * the javadoc attached to it, so a constant cannot inherit a neighbour's marker. Unlike {@code
     * MlsGetGroupInfoLedgerGuardTest}, which reads {@code @Deprecated}, this reads the javadoc.
     */
    private static boolean isRetired(final String ledgerSrc, final String name) {
        final Matcher m = Pattern.compile("(?m)^        " + Pattern.quote(name) + "\\s*\\(")
                .matcher(ledgerSrc);
        if (!m.find()) return false;
        final int declAt = m.start();
        // The attached javadoc block exactly, not the text since the previous constant.
        final int close = ledgerSrc.lastIndexOf("*/", declAt);
        if (close < 0) return false;
        // Attached means only whitespace between the javadoc and the declaration.
        if (!ledgerSrc.substring(close + 2, declAt).trim().isEmpty()) return false;
        final int open = ledgerSrc.lastIndexOf("/**", close);
        if (open < 0) return false;
        return ledgerSrc.substring(open, close).contains("RETIRED");
    }

    /**
     * An unrefusable caller may be passed only from a method in
     * {@link #MAY_PASS_AN_UNREFUSABLE_CALLER}, and the unrefusable set is pinned.
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
                    + production
                    + ". Unrefusable means never denied — defensible for an operator's "
                    + "diagnostic, and for the rebuild's re-create where the claim's product is the "
                    + "input we cannot proceed without, and for nothing else. A production path "
                    + "holding it drains a peer's pool without bound while the ledger reports the "
                    + "arm as correctly exempt. Give the path its own Caller with a ration.");
        }
        assertEquals(
                "the set of UNREFUSABLE callers has changed. Unrefusable is the strongest thing "
                + "this enum can say about somebody else's key material, and it was argued for "
                + "exactly these four. A fifth needs its own argument, not this list widened.",
                java.util.Arrays.asList(
                        "DEBUG_CLAIM_KP", "DEBUG_CTRL", "DEBUG_KP_COUNT", "REBUILD_RECREATE"),
                unrefusableNames());
    }

    /**
     * Every permitted claim charges, the unrefusable ones included, unlike {@link MlsFetchLedger}'s
     * exempt arms: a claim takes a consumable from a peer's device, and an uncounted one overstates
     * what the peer has left.
     */
    @Test
    public void everyPermittedClaimCharges() {
        for (final MlsClaimLedger.Verdict v : MlsClaimLedger.Verdict.values()) {
            assertEquals(v
                    + " no longer charges exactly when it is permitted. This ledger models a "
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

    /**
     * {@code claimForUpgrade} answers "could not look" distinctly from "looked and saw nothing": a
     * count of zero already means every participant was claimed for and one had none, so a partial
     * count on a refusal would make {@code MlsUpgradePolicy} blame the participants.
     */
    @Test
    public void theUiDoorsRefusalIsNotACount() throws IOException {
        assertTrue(
                "MlsClaimLedger.CLAIM_REFUSED is no longer negative, so it can be mistaken for a "
                + "count of claimable key packages", MlsClaimLedger.CLAIM_REFUSED < 0);
        // A refusal is not a count; "asked and they had none" is zero; "this claim says nothing
        // about that peer" is take() == null, distinct from the empty list for a peer the server
        // served nothing for.
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
        // The arm's own brace-matched block, not a fixed window from the marker.
        final String arm = armContaining(body, refused);
        assertTrue("claimForUpgrade() detects the refusal and does not return a REFUSED claim from "
                + "THAT ARM, so it falls through to the claimed() form — and a claimed() form with "
                + "a partial map is read as 'this many participants have key packages', which is a "
                + "statement about them that we never made. A return somewhere else in the method "
                + "does not count: this must be the arm the refusal takes.",
                arm.contains("MlsUpgradeClaim.refusedByLedger("));
    }

    /**
     * The upgrade path claims once: the listener passes what it claimed to {@code establishGroup},
     * and the create consumes it rather than claiming again. Either half alone restores the double
     * spend.
     */
    @Test
    public void theUpgradePathClaimsOnce() throws IOException {
        final String listener = codeOnly(read(
                "src/com/android/messaging/rcs/e2ee/MlsConversationOpenListener.java"));
        final String upgrade = bodyOf(listener, "upgrade");
        assertTrue("MlsConversationOpenListener.upgrade() is gone and this guard is watching "
                + "nothing", upgrade.length() > 0);
        assertTrue(
                "the upgrade-on-open path no longer calls claimForUpgrade(), so either the claim "
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
        assertTrue(
                "gatherInitialKeyPackages() is gone — the create's claim loop has moved and this "
                + "guard no longer watches whether it consumes the pre-claim", gather.length() > 0);
        assertTrue("the create no longer CONSUMES the pre-claim (preClaimed.take), so it is "
                + "re-claiming for members whose packages are already in hand",
                gather.contains("preClaimed.take("));
        assertTrue(
                "the create no longer asks whether the pre-claim covers a member before claiming "
                + "live for them, so 'this claim says nothing about that peer' and 'that peer has "
                + "no packages' have been collapsed", gather.contains("preClaimed.covers("));
        final int refusedAt = gather.indexOf("preClaimed.refused()");
        assertTrue("the create no longer refuses a REFUSED pre-claim. It would fall through to a "
                + "live claim and spend exactly what the ledger's refusal withheld — the ledger "
                + "obeyed on one path and stepped around on another", refusedAt >= 0);
        assertTrue(
                "the create checks the pre-claim's refusal AFTER it has already started claiming "
                + "for members, so the first members are claimed for before the refusal is honoured",
                refusedAt < gather.indexOf("claimAll("));
    }

    /**
     * The refusal is tested before the shortfall: {@link MlsClaimLedger#CLAIM_REFUSED} is negative,
     * so {@code -1 < remoteParticipants} would report the refusal as a key-package shortfall.
     */
    @Test
    public void theRefusalIsTestedBeforeTheShortfall() throws IOException {
        // Code only, offsets preserved: this asserts an order.
        final String src = codeOnly(read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsUpgradePolicy.java"));
        final int refused = src.indexOf("MlsClaimLedger.CLAIM_REFUSED");
        final int shortfall = src.indexOf("claimedKeyPackages < remoteParticipants");
        assertTrue(
                "MlsUpgradePolicy no longer tests for MlsClaimLedger.CLAIM_REFUSED, so a refused "
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
        assertFalse(
                "CLAIM_REFUSED_BY_LEDGER must not proceed — it is not an upgrade decision, it is "
                + "the absence of one",
                MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.proceed());
        assertTrue("CLAIM_REFUSED_BY_LEDGER has no line. It is the one Decision that is NOT "
                + "a peer's line, so it must say so in its own words rather than borrow one of theirs "
                + "and corrupt a trace diff.",
                MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.line() != null
                        && MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER.line()
                                .contains("OUR OWN"));
    }

    /**
     * The rebuild's re-create is unrefusable. {@code rebuildConversation}'s claim sits below its
     * forgets and cannot be hoisted, and unlike a refused look there is nothing to fail open to.
     */
    @Test
    public void theRebuildRecreateArmIsUnrefusable() throws IOException {
        assertTrue(
                "REBUILD_RECREATE is no longer unrefusable. Reached from rebuildConversation both "
                + "halves of our state are already gone and the claim's product is what the "
                + "re-create is built from — a refusal there leaves the conversation with nothing at "
                + "all, which is strictly worse than the churn the refusal prevents.",
                MlsClaimLedger.Caller.REBUILD_RECREATE.isUnrefusable());
        final String ready = bodyOf(codeOnly(readTransport()), "ensureReady",
                "final boolean stateAlreadyDestroyed)");
        assertTrue("ensureReady's stateAlreadyDestroyed overload is gone", ready.length() > 0);
        assertTrue(
                "ensureReady no longer selects its Caller on stateAlreadyDestroyed, so a rebuild "
                + "that has already dropped both halves of our state can be refused the KeyPackage "
                + "the re-create is made of. There is nothing to fail open TO here — unlike a "
                + "refused look, a refused claim leaves no group to build.",
                ready.contains("stateAlreadyDestroyed")
                        && ready.contains("MlsClaimLedger.Caller.REBUILD_RECREATE")
                        && ready.contains("MlsClaimLedger.Caller.ENSURE_READY"));
    }

    @Test
    public void nothingReachesTheClaimPrimitivesAroundTheLedger() throws IOException {
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
            // The shim declares and forwards the calls the wrappers make through it.
            if (rel.endsWith(TRANSPORT) || rel.endsWith(PROVIDER_SHIM)) continue;
            if (rel.endsWith(RPC_BINDING)) continue;   // checked below
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
                final String q = Pattern.quote(aidl);
                final Matcher m = Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b")
                        .matcher(src);
                if (m.find()) around.add(rel + " calls " + aidl + "()");
            }
        }
        for (final File f : engineSourcesOutsideTheTransport()) {
            final String src = codeOnly(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (final String aidl : MlsClaimLedger.AIDL_SPELLINGS) {
                final String q = Pattern.quote(aidl);
                if (Pattern.compile("\\.\\s*" + q + "\\s*\\(|::\\s*" + q + "\\b").matcher(src)
                        .find()) {
                    around.add(f.getPath() + " calls " + aidl + "()");
                }
            }
        }
        onlyTheTransportBuildsTheRpcBinding(all, around);
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
     * The spellings in the enum are methods the shim declares, or every scan keyed on them is
     * blind.
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

    /**
     * The ceiling clears the probe-then-establish sequence of one successful upgrade; below it the
     * probe would spend the allowance the establish then needs.
     */
    @Test
    public void theCeilingClearsTheUpgradeSequence() {
        final int upgrade = MlsClaimLedger.Caller.UPGRADE_PROBE.ration
                + MlsClaimLedger.Caller.GROUP_ESTABLISH.ration;
        assertTrue("the shared ceiling (" + MlsClaimLedger.SHARED_CEILING
                + ") leaves no room above "
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

    /** Unreadable and absent records stay apart; the strict posture rests on it. */
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

    /**
     * Only {@code OUTCOME_PEER_HAS_NONE} may reduce to {@code Attribution.PEER_HAS_NONE}, and only
     * in {@code attributionOf}: it is the one value whose ledger sentence is evidence about a peer,
     * and our own {@code NOT_AUTHORIZED} must not reach it. Fails if a second outcome is admitted
     * or a call site names the value itself.
     */
    @Test
    public void onlyOneOutcomeCanBlameThePeer() throws IOException {
        assertEquals("", blameReduction(readTransport()));
    }

    /** The negative control for the guard above, run on synthetic sources. */
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

    /** The reduction check. Returns "" when {@code src} is clean, otherwise why. */
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
                    + "and these methods mention it: " + sites
                    + ". This scan matches any reference, "
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
     * The shim's one extra call, the older plural claim, fires only when no dial was spent: on
     * {@code OUTCOME_NOT_ATTEMPTED}. Keying on an empty answer would dial a refusal a second time
     * while charging once. Nothing dials after it, because an empty answer or a {@code
     * RemoteException} from it can follow a real request.
     */
    @Test
    public void theOlderContractFallbackFiresOnlyWhenNoDialWasSpent() throws IOException {
        assertEquals("", fallbackPredicate(read("src/com/android/messaging/" + PROVIDER_SHIM)));
    }

    /** The negative control, including the "null or empty" predicate the fallback must not use. */
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
                + "        try {\n"
                + "            packed = provider.claimPeerKeyPackages();\n"
                + "        } catch (final RemoteException e) {\n"
                + "        }\n"
                + "        if (unpacked != null) {\n"
                + "            reportOutcome(outcomeSink, RcsMlsClaimResult.OUTCOME_SERVED);\n"
                + "            return unpacked;\n"
                + "        }\n"
                + "        return null;\n"
                + "    }\n";
        assertEquals("The guard must pass the shipping shape.", "", fallbackPredicate(fixed));

        // The sink must never carry PEER_HAS_NONE where nothing came back and nothing is known.
        final String sinkInventsBlame = fixed.replace(
                "        }\n        return null;\n    }\n",
                "        }\n"
                        + "        reportOutcome(outcomeSink,"
                        + " RcsMlsClaimResult.OUTCOME_PEER_HAS_NONE);\n"
                        + "        return null;\n    }\n");
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
                        "if (packed == null || packed.length == 0) {");
        assertTrue("The predicate that cannot tell an empty pool from a refusal must be caught — "
                        + "it is what dialled twice and charged once.",
                fallbackPredicate(theOldBug).contains("NO DIAL"));

        final String remoteAlsoFallsBack = fixed.replace(
                "        } catch (final RemoteException e) {\n",
                "        } catch (final RemoteException e) {\n"
                        + "            packed = provider.claimPeerKeyPackage();\n");
        assertTrue("A RemoteException must NOT lead to another dial: the binder failed and "
                        + "whether the KDS was reached is unknowable, so another dial is not free.",
                fallbackPredicate(remoteAlsoFallsBack).contains("LAST dial"));

        // The pre-v48 single-package fallback, which D9 deleted, dialled after the plural claim.
        final String singularAfterward = fixed.replace("        }\n        return null;\n    }\n",
                "        }\n        return java.util.Collections.singletonList("
                        + "claimPeerKeyPackage(subId, phoneE164));\n    }\n");
        assertTrue("A dial after the older plural claim must be caught.",
                fallbackPredicate(singularAfterward).contains("LAST dial"));

        assertTrue("A shim with no older plural claim has lost this guard's subject.",
                fallbackPredicate(fixed.replace("provider.claimPeerKeyPackages()", "x()"))
                        .contains("not found"));

        assertFalse("A guard that cannot find its subject must SAY so rather than pass.",
                fallbackPredicate("class X {}").isEmpty());
    }

    /** "" when the shim falls back only on "we never asked", otherwise why. */
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
        final String older = "provider.claimPeerKeyPackages(";
        final int olderAt = claim.indexOf(older);
        if (olderAt < 0) {
            return "The older plural claim was not found in claimPeerKeyPackages. The guard cannot "
                    + "confirm what follows it, which is a FAILURE and not a pass: " + claim;
        }
        // Positional: an empty answer or a RemoteException from it may follow a real request.
        final Matcher dial = Pattern.compile("\\bclaimPeerKeyPackages?(?:WithOutcome)?\\s*\\(")
                .matcher(claim);
        if (dial.find(olderAt + older.length())) {
            return "The older plural claim must be the LAST dial. Whatever it returned, a request "
                    + "may have left the device, and another dial is not free: " + claim;
        }
        return sinkWritesOnlyWhatItKnows(claim);
    }

    /**
     * The outcome the shim reports is the provider's, or one of the two facts the shim knows
     * itself: {@code NOT_ATTEMPTED} (it made no dial) and {@code SERVED} (it holds the packages).
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

    /**
     * Every read of {@code Claim.attribution()} feeds a line explaining an empty claim. A claim can
     * return packages alongside a failure outcome (the first dial fails, an alternate-server retry
     * succeeds), which is safe only while the attribution is read nowhere but on the empty path.
     */
    @Test
    public void theAttributionIsOnlyEverReadToExplainAnEmptyClaim() throws IOException {
        assertEquals("", attributionReadsExplainAbsence(readTransport()));
    }

    /** The negative control, on synthetic sources. */
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
     * The lines that explain an empty claim; not the ledger's own accounting lines, which say
     * nothing about a peer.
     */
    private static final String[] ABSENCE_EXPLAINERS = {
        "blockedByEmptyClaim", "describeEmptyClaim", "oneToOneCreateBlockedLine",
        "establishGroupBlockedLine", "addMemberBlockedLine", "rosterClaimBlockedLine",
    };

    /** "" when every attribution() read feeds an absence explanation, otherwise why. */
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

    /**
     * A detector with no live subject: it runs only on the synthetic sources below and says nothing
     * about the provider's code. It is kept for the provider's own suite to adopt; delete this copy
     * if it does.
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
                "        if (outcome == Outcome.SERVED && total == 0) {\n",
                "        if (false) {\n");
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

    /** "" when the provider's translation cannot turn a transport status into blame, else why. */
    private static String providerBlameTranslation(final String rawClaimer,
            final String rawService) {
        final String claimer = codeOnly(rawClaimer);
        final String translate = declaredBodyAnyIndent(claimer, "outcomeForStatus");
        // The same body with string literals intact, recovered at the offsets codeOnly preserves.
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
                    + "(grpc-13 then grpc-5), not an empty pool: "
                    + translate;
        }
        // A request that never reached a server ("TRANSPORT", "EXCEPTION", "UNKNOWN" in the
        // client's own status names) maps to TRANSPORT_FAILED, not REFUSED: nothing received it.
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
     * The body of a method declaration at any indent, told from a call by the body that follows the
     * parameter list; {@code aidlOutcome} sits in a nested Binder stub, and a call to it appears
     * first.
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

    /**
     * {@code OUTCOME_NOT_ATTEMPTED} reaches a refund and never the "was made and came back empty"
     * line. {@code spendOneClaim} charges before it dials; a claim the provider never sent would
     * otherwise hold a phantom charge that refuses the first real claim once the provider is back.
     * Checked: the branch returns before {@code describeEmptyClaim}, every {@code
     * describeNotAttempted} site refunds or passes {@link MlsClaimLedger#NO_COUNT}, and both
     * wrappers set the flag from that one outcome.
     */
    @Test
    public void aClaimThatWasNeverSentIsRefundedAndNeverFramedAsMade() throws IOException {
        assertEquals("", notAttemptedIsRefundedNotCharged(readTransport()));
    }

    /** The negative control, on synthetic sources. */
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
        assertTrue(
                        "A wrapper that never reports the outcome leaves the refund branch DEAD and must "
                        + "be caught: " + notAttemptedIsRefundedNotCharged(deadBranch),
                notAttemptedIsRefundedNotCharged(deadBranch).contains("claimOne"));

        assertFalse("A scan that finds NO describeNotAttempted at all must FAIL, not certify — the "
                        + "whole third frame would have been removed and the guard would be "
                        + "watching nothing.",
                notAttemptedIsRefundedNotCharged(
                        SHIPPING_SHAPE.replace("MlsClaimLedger.describeNotAttempted(", "logIt("))
                        .isEmpty());

        // Deriving the flag from the answer would refund claims that really dialled and drained a
        // peer.
        final String widened = SHIPPING_SHAPE.replace(
                "            outcome.notAttempted = r.outcome"
                        + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n",
                "            outcome.notAttempted = r.keyPackages == null;\n");
        assertTrue("A not-attempted flag derived from the ANSWER rather than from the OUTCOME must "
                        + "be caught — it refunds claims that really dialled: "
                        + notAttemptedIsRefundedNotCharged(widened),
                notAttemptedIsRefundedNotCharged(widened).contains("OUTCOME_NOT_ATTEMPTED"));
    }

    /** The shipping shape, reduced to what this guard reads. */
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
            + ".claimPeerKeyPackagesWithOutcome(peer);\n"
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
            + ".claimPeerKeyPackages(peer, sink);\n"
            + "            outcome.notAttempted = sink[0]"
            + " == RcsMlsClaimResult.OUTCOME_NOT_ATTEMPTED;\n"
            + "            return kps;\n"
            + "        });\n"
            + "    }\n";

    /** "" when a never-sent claim is refunded and reframed, otherwise why. */
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
        // The gap between the dial and the "made" frame: the unreadable-ledger arm reads the same
        // field earlier and returns there.
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
            // Only that one outcome asserts no dial was spent; anything broader refunds real
            // claims.
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

    /**
     * The source with comments and string contents replaced by spaces, so offsets and
     * {@link #enclosingMethod} still match the real file.
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
                i++;                                  // the opening quote stays
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
     * The brace-matched block of the {@code if} arm containing {@code at}, or the rest of the body;
     * used instead of a fixed window.
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

    /**
     * Every {@code .java} under the module's {@code src/}, wherever the runner's cwd happens to be.
     */
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
     * The body of the first method named exactly {@code name} whose declaration contains
     * {@code declFragment}.
     */
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

    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    /**
     * Whether {@code body} is a delegating overload whose only statement returns a call to its own
     * name; {@link #bodyOf} skips it so a delegate declared above the real method cannot hide it.
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
