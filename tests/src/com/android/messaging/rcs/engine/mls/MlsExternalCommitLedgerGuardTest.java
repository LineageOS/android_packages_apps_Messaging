/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every path that spends an external commit is charged to one ledger. {@code applyMlsControl} is a
 * family carrying several kinds of event, and what makes a commit external is the artefact (built
 * against the server's GroupInfo), so the enumeration point is the engine call that mints one,
 * {@code externalCommitResync(}; the other {@code applyMlsControl} sites are excluded because their
 * enclosing methods do not mint, which is checked.
 *
 * <p>Needles are invoked method names, never receivers or lock-assertion tags; assertions are per
 * item, not totals; zero hits fail; and the ledger's numbers are read off
 * {@link MlsWindowBudget#EXTERNAL_COMMIT}. See docs/mls/budgets.md.
 */
public final class MlsExternalCommitLedgerGuardTest {

    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";
    private static final String DEBUG_RECEIVER =
            "src/com/android/messaging/rcs/RcsDebugSendReceiver.java";
    private static final String STALL_RECEIVER =
            "src/com/android/messaging/rcs/e2ee/MlsStalledActionReceiver.java";
    private static final String LEDGER =
            "src/com/android/messaging/rcs/e2ee/MlsExternalCommitBudget.java";

    /** The engine call that mints an external commit; the enumeration point. */
    private static final String MINT = "externalCommitResync(";

    /** The one door. Three overloads; only the widest decides. */
    private static final String DOOR = "resyncViaExternalCommit(";

    /**
     * A parameter only the deciding overload declares. {@link #decidingOverload} checks it is
     * unique and fails naming both bodies otherwise, since a delegate can inherit a selector.
     */
    private static final String DECIDING_OVERLOAD = "dryRun";

    /** The lazily-initialised ledger accessor. Charge and reset both go through it. */
    private static final String LEDGER_ACCESSOR = "xcBudget(";

    /** The RPC family; only one of its sites may mint. */
    private static final String FAMILY = "applyMlsControl(";

    /**
     * Every enumeration this class rests on finds something; otherwise everything below is a
     * statement about an empty string. Run this first when the file is failing.
     */
    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        for (final String rel : new String[] {TRANSPORT, DEBUG_RECEIVER, STALL_RECEIVER, LEDGER}) {
            assertTrue(rel + " did not read, or read empty — this guard has no subject",
                    SourceScan.codeOnly(SourceScan.read(rel)).length() > 500);
        }
        // The mint is anchored by source, not reflection: MlsSession is off the host classpath. A
        // rename fails here rather than enumerating zero mints.
        final String session = SourceScan.codeOnly(
                SourceScan.read("engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java"));
        assertTrue("MlsSession.java did not read — the mint's engine anchor is gone",
                session.length() > 500);
        assertTrue("MlsSession no longer declares " + MINT + ". That is the ENGINE CALL this whole "
                + "guard enumerates from — renamed, every scan below finds zero and the resource "
                + "would read as covered while nothing enumerated it.", session.contains(MINT));
        assertTrue(
                "MlsWindowBudget is not on the host classpath — the ledger's numbers and posture "
                + "cannot be read from the instance production uses",
                SourceScan.engineClass("MlsWindowBudget") != null);
    }

    /**
     * Every mint of an external commit anywhere under {@code src/} is inside the one door; the
     * whole tree is walked, since a mint in a new class would be invisible to a scan of the
     * transport.
     */
    @Test
    public void everyMintIsInsideTheChargedDoor() throws IOException {
        final List<String> offenders = new ArrayList<>();
        int mints = 0;
        for (final File f : javaSourcesUnderSrc()) {
            final String code = SourceScan.codeOnly(read(f));
            final List<int[]> decls = SourceScan.declarations(code);
            for (final Integer at : SourceScan.invocationsOf(code, decls, MINT)) {
                mints++;
                final String enclosing = SourceScan.enclosingMethod(code, decls, at.intValue());
                if (!"resyncViaExternalCommit".equals(enclosing)) {
                    offenders.add(f.getName() + ":" + lineOf(code, at.intValue()) + " mints an "
                            + "external commit inside " + enclosing + "()");
                }
            }
        }
        assertTrue("NO external-commit mint found anywhere under src/. Either the engine call was "
                + "renamed — in which case every other assertion in this class is passing on an "
                + "empty enumeration — or the resync path is gone. Both must fail loudly.",
                mints >= 1);
        if (!offenders.isEmpty()) {
            fail("An external commit is minted outside the door that charges the §11.2.2 ledger: "
                    + offenders
                    + ". Every mint spends one of the day's fifty against a real group; "
                    + "a second door is the known defect on this resource — the budget would exist, "
                    + "read as spent, and be ignored. Route it through resyncViaExternalCommit, or "
                    + "charge it where it is.");
        }
    }

    /**
     * The charge comes before the mint and before the RPC, so an operation that crashes still
     * counts, as with {@code MlsRebuildLimiter.claim}.
     */
    @Test
    public void theChargeComesBeforeTheMintAndBeforeTheSend() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        final int mint = body.indexOf(MINT);
        final int send = body.indexOf(FAMILY);
        assertTrue("the deciding resyncViaExternalCommit overload no longer charges "
                + LEDGER_ACCESSOR
                + " at all — the §11.2.2 allowance is not being spent by the one thing that spends "
                + "it", charge >= 0);
        assertTrue("the deciding overload no longer mints an external commit", mint >= 0);
        assertTrue("the deciding overload no longer sends one", send >= 0);
        assertTrue("the external commit is MINTED at " + mint + " before the ledger is charged at "
                + charge + ". A budget charged after the artifact is built counts what has already "
                + "been spent.", charge < mint);
        assertTrue("the external commit is SENT at " + send + " before the ledger is charged at "
                + charge + ". Charging after the RPC means a crash between the two is free, which "
                + "is the one failure mode a limiter exists to prevent.", charge < send);
    }

    /**
     * The charge can refuse, and a refusal returns without minting: the charge appears in a negated
     * condition whose arm returns.
     */
    @Test
    public void aRefusedChargeDoesNotReachTheServer() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        assertTrue("no charge site in the deciding overload", charge >= 0);
        final int lineStart = body.lastIndexOf('\n', charge) + 1;
        final int lineEnd = body.indexOf('\n', charge);
        final String line = body.substring(lineStart, lineEnd < 0 ? body.length() : lineEnd);
        assertTrue("the ledger is charged at a site that does not read its verdict: \""
                + line.trim()
                + "\". A budget that is charged and not consulted exists, reads as spent, and is "
                + "ignored — the worse-than-nothing case.",
                line.contains("if (!") && line.contains("claim("));
        assertTrue(
                "the refusal arm does not return, so a refused external commit proceeds anyway: \""
                + line.trim() + "\"", line.contains("return"));
    }

    /**
     * Exactly one member of the {@code applyMlsControl} family mints an external commit, derived by
     * grouping sites by enclosing method. {@code eraAdvancePreserving} invokes through a held local
     * with no {@code pt("…")} tag, which is why the needle is the method name.
     */
    @Test
    public void theApplyMlsControlFamilyCarriesExactlyOneExternalCommit() throws IOException {
        final String code = SourceScan.transportUnsplitCode();
        final List<int[]> decls = SourceScan.declarations(code);
        final List<Integer> sites = SourceScan.invocationsOf(code, decls, FAMILY);
        assertTrue(
                "no applyMlsControl invocation found in the transport — the family's name changed "
                + "and this check is silently passing", sites.size() >= 2);

        final Set<String> enclosing = new LinkedHashSet<>();
        for (final Integer at : sites) {
            enclosing.add(SourceScan.enclosingMethod(code, decls, at.intValue()));
        }
        assertTrue(
                "every applyMlsControl site is in one method — either the family collapsed or the "
                + "declaration pattern has gone stale", enclosing.size() >= 2);

        final List<String> minting = new ArrayList<>();
        for (final String name : enclosing) {
            boolean mints = false;
            for (final String body : bodiesOf(code, name)) {
                if (body.contains(MINT)) { mints = true; break; }
            }
            if (mints) minting.add(name);
        }
        assertEquals(
                "The set of applyMlsControl callers that build an EXTERNAL commit has changed: "
                + minting + " out of " + enclosing + ". Exactly one member of this family is an "
                + "external commit — the artifact is what makes it one, not the RPC — and every "
                + "other member is excluded because it does not mint, which is checked here rather "
                + "than believed from the resource table. A new name in this list is a second door "
                + "onto RCC.16 §11.2.2's fifty a day.",
                java.util.Collections.singletonList("resyncViaExternalCommit"), minting);
    }

    /**
     * Every use of the ledger accessor is one of two named sites (the charge and the reset),
     * asserted per site with its enclosing method rather than as a total.
     */
    @Test
    public void everyLedgerSiteIsOneOfTheTwoNamedOnes() throws IOException {
        // As if unsplit: the MlsShellPort adapter's xcBudget() forwarder is blanked, and a moved
        // site is counted in its original method.
        final String code = SourceScan.transportUnsplitCode();
        final List<int[]> decls = SourceScan.declarations(code);
        final List<String> sites = new ArrayList<>();
        for (final Integer at : SourceScan.invocationsOf(code, decls, LEDGER_ACCESSOR)) {
            sites.add(SourceScan.enclosingMethod(code, decls, at.intValue()));
        }
        assertTrue("no use of " + LEDGER_ACCESSOR
                + " outside its own declaration — the accessor was "
                + "renamed and every ledger assertion in this class is scanning for a name nothing "
                + "uses", sites.size() >= 2);
        final List<String> unexpected = new ArrayList<>();
        for (final String s : sites) {
            if (!"resyncViaExternalCommit".equals(s) && !"resetExternalCommitBudget".equals(s)
                    && !"xcBudget".equals(s)) {
                unexpected.add(s);
            }
        }
        if (!unexpected.isEmpty()) {
            fail("The §11.2.2 ledger is reached from methods this guard does not know about: "
                    + unexpected + ". One CHARGE (resyncViaExternalCommit) and one RESET (the Try "
                    + "again lever) is the whole of it; anything else is either a second door or a "
                    + "second lever, and both need their own review.");
        }
        assertTrue("the CHARGE site is gone — resyncViaExternalCommit no longer reaches the ledger",
                sites.contains("resyncViaExternalCommit"));
        assertTrue("the RESET site is gone — MlsExternalCommitBudget.reset is back to having zero "
                + "production callers, so the operator lever is gone",
                sites.contains("resetExternalCommitBudget"));
    }

    /**
     * Every caller of the door reaches the charging overload; the other two delegate, and one that
     * stopped delegating would mint and charge nothing.
     */
    @Test
    public void everyOverloadOfTheDoorReachesTheChargingOne() throws IOException {
        final String code = SourceScan.transportUnsplitCode();
        final List<String> bodies = bodiesOf(code, "resyncViaExternalCommit");
        assertTrue("resyncViaExternalCommit is gone — the one door into the external commit no "
                + "longer exists under that name", bodies.size() >= 1);
        int charging = 0;
        final List<String> broken = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            final String b = bodies.get(i);
            final boolean charges = b.contains(LEDGER_ACCESSOR);
            final boolean mints = b.contains(MINT);
            final boolean delegates = b.contains(DOOR);
            if (charges && mints) { charging++; continue; }
            if (charges != mints) {
                broken.add("overload " + i + " charges=" + charges + " mints=" + mints
                        + " — a charge without a mint spends the allowance on nothing, and a mint "
                        + "without a charge is the unbudgeted door");
            } else if (!delegates) {
                broken.add("overload " + i + " neither mints, charges nor delegates — a caller "
                        + "reaching it gets silence");
            }
        }
        if (!broken.isEmpty()) fail("The door's overloads no longer agree: " + broken);
        assertEquals("exactly one overload of resyncViaExternalCommit must both charge and mint; "
                + "two would be two doors and none would be no ledger", 1, charging);
    }

    /**
     * The operator arm ({@code --ez resync}) is charged, unlike {@code MlsFetchLedger}'s debug
     * callers: a debug external commit costs a real group a real commit. The debug call carries the
     * deciding overload's arity, so it is the overload that charges.
     */
    @Test
    public void theDebugArmGoesThroughTheChargingOverload() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(DEBUG_RECEIVER));
        final List<Integer> calls =
                SourceScan.invocationsOf(code, SourceScan.declarations(code), DOOR);
        assertTrue("the debug resync arm is gone from RcsDebugSendReceiver — either the operator "
                + "lever for this path was removed, or it was renamed and this check has stopped "
                + "watching it", calls.size() >= 1);
        // The deciding arity is read, not written down, so a signature change does not fail this.
        final int arity = decidingArity();
        assertTrue("could not read the deciding overload's parameter count — the selector "
                + DECIDING_OVERLOAD + " matched no declaration", arity > 0);
        for (final Integer at : calls) {
            final List<String> args = SourceScan.topLevelArguments(
                    SourceScan.argumentListAt(code, at.intValue()));
            assertEquals("the debug resync arm calls resyncViaExternalCommit with " + args.size()
                    + " arguments (" + args + "), not the " + arity
                    + " of the deciding overload. A "
                    + "debug arm that reached a delegating overload would still charge today — but "
                    + "the point of this assertion is that the debug door is NOT exempt from "
                    + "§11.2.2, unlike R1's fetch ledger, because a debug external commit costs a "
                    + "real peer a real commit and this bound is on what the GROUP pays.",
                    arity, args.size());
        }
    }

    /**
     * The only skip of the charge is the dry run ({@code if (!dryRun && !xcBudget().claim(key))}),
     * and a dry run returns before the mint, which also deletes the persisted group before it
     * builds. Charging a dry run would let "is this safe to attempt?" exhaust the daily allowance.
     */
    @Test
    public void theChargeIsSkippedOnlyForADryRunAndADryRunNeverMints() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        assertTrue("no charge site in the deciding overload", charge >= 0);
        final int lineStart = body.lastIndexOf('\n', charge) + 1;
        final int lineEnd = body.indexOf('\n', charge);
        final String line = body.substring(lineStart, lineEnd < 0 ? body.length() : lineEnd).trim();
        // The guard condition's only exemption, asserted as a shape (one guard, one exemption, no
        // alternatives) rather than as an exact source line.
        final int condOpen = line.indexOf('(');
        final int condClose = line.lastIndexOf(") return");
        assertTrue("the charge site is not an `if (...) return` any more: \"" + line + "\"",
                condOpen >= 0 && condClose > condOpen);
        final String cond = line.substring(condOpen + 1, condClose);
        assertTrue("the §11.2.2 charge no longer reads its verdict: \"" + line + "\"",
                cond.contains("!" + LEDGER_ACCESSOR + ").claim("));
        assertTrue("the charge is skipped on something other than the dry run: \"" + line + "\"",
                cond.contains("!" + DECIDING_OVERLOAD));
        assertTrue("the charge condition contains an ALTERNATIVE (||): \"" + cond + "\". A second "
                + "way to reach the external commit without charging is an unbudgeted door on an "
                + "operation that DELETES the local group before it builds.", !cond.contains("||"));
        assertEquals("the charge condition has more than one conjunct besides the claim itself: \""
                + cond + "\". Exactly ONE exemption is sanctioned — the dry run, which transmits "
                + "nothing. Another was added without this guard being told why.",
                1, cond.split("&&", -1).length - 1);
        final int mint = body.indexOf(MINT);
        final int send = body.indexOf(FAMILY);
        assertTrue("the deciding overload no longer mints an external commit", mint >= 0);
        assertTrue("the deciding overload no longer sends one", send >= 0);
        final int dryReturn = body.indexOf("return RESYNC_DRY_RUN_VIABLE");
        assertTrue("the dry run no longer has a VIABLE return in the deciding overload, so nothing "
                + "stops it falling through into the build", dryReturn >= 0);
        assertTrue("the dry run returns at " + dryReturn + ", AFTER the external commit is minted "
                + "at " + mint + ". A dry run that reaches externalCommitResync deletes the "
                + "persisted group and builds a real commit while skipping the §11.2.2 charge — "
                + "both halves of the bargain broken at once.", dryReturn < mint);
        assertTrue("the dry run returns at " + dryReturn + ", AFTER the commit is sent at " + send
                + ". A dry run that transmits is not a dry run.", dryReturn < send);
    }

    /**
     * An operator can clear this allowance through {@code MlsExternalCommitBudget.reset}, and the
     * clearing reaches the record that refuses the repair; each link is asserted.
     */
    @Test
    public void theOperatorLeverReachesTheRecordThatRefuses() throws IOException {
        final String receiver = SourceScan.codeOnly(SourceScan.read(STALL_RECEIVER));
        final int retry = receiver.indexOf("ACTION_RETRY.equals(action)");
        assertTrue("the retry arm is gone from MlsStalledActionReceiver", retry > 0);
        final String arm = blockAfter(receiver, retry);
        assertTrue("the retry arm's block could not be brace-matched, so the assertion below would "
                + "be about the whole file — a span bounded by a neighbour rather than by the arm",
                arm.length() > 0);
        assertTrue("Try again no longer clears the §11.2.2 allowance. It is the ONE "
                + "durable refusal on this ladder whose only other exit is a day of uptime.",
                arm.contains("resetExternalCommitBudget("));

        final String transport = SourceScan.transportUnsplitCode();
        final String lever = SourceScan.bodyOf(transport, "resetExternalCommitBudget");
        assertTrue("MlsProviderTransport.resetExternalCommitBudget is gone — the retry arm's call "
                + "does not resolve", lever.length() > 0);
        assertTrue("the lever no longer reaches the ledger", lever.contains(LEDGER_ACCESSOR));

        final String budget = SourceScan.codeOnly(SourceScan.read(LEDGER));
        final String reset = SourceScan.bodyOf(budget, "reset");
        assertTrue("MlsExternalCommitBudget.reset is gone", reset.length() > 0);
        assertTrue("MlsExternalCommitBudget.reset no longer removes the PERSISTED record. A reset "
                + "that clears only what this process remembers leaves the record that is actually "
                + "refusing the repair on disk, and the button does nothing.",
                reset.contains("remove(") && reset.contains("commit("));
    }

    /**
     * The ledger is {@link MlsWindowBudget#EXTERNAL_COMMIT}, read off the instance the adapter
     * constructs; the source half asserts only that the adapter delegates.
     */
    @Test
    public void theLedgerIsTheEnginePolicyAtTheSpecNumbers() throws IOException {
        final String budget = SourceScan.codeOnly(SourceScan.read(LEDGER));
        assertTrue("MlsExternalCommitBudget no longer delegates to MlsWindowBudget, so its numbers "
                + "and its posture are back in a class no host test can reach",
                budget.contains("MlsWindowBudget"));
        assertEquals("MAX_PER_DAY moved off RCC.16 §11.2.2's fifty. It is not a tuning knob: no "
                + "value can match peers that implement no budget at all, so tuning yields "
                + "neither parity nor a principled bound. If parity is the goal, DELETE "
                + "the class.", 50, MlsWindowBudget.EXTERNAL_COMMIT.maxPerWindow());
        assertEquals("the §11.2.2 window is no longer a day",
                24L * 60 * 60 * 1000, MlsWindowBudget.EXTERNAL_COMMIT.windowMs());
        assertEquals("the no-store posture moved off REFUSE. D2 flipped it there from "
                + "ALLOW_UNCOUNTED on its own review — a bound argued for on the grounds that it "
                + "protects a peer who cannot ask us to stop must not have an arm whose effect is "
                + "no bound at all.",
                MlsWindowBudget.OnNoStore.REFUSE, MlsWindowBudget.EXTERNAL_COMMIT.onNoStore());
        assertEquals(
                "an unreadable §11.2.2 record no longer refuses and discards, so a parse error "
                + "either restores the whole day's allowance or blocks the group permanently",
                MlsWindowBudget.OnUnreadable.REFUSE_AND_DISCARD,
                MlsWindowBudget.EXTERNAL_COMMIT.onUnreadable());
    }

    /** The ledger has one owner: nothing constructs a second {@code MlsExternalCommitBudget}. */
    @Test
    public void theLedgerHasOneOwner() throws IOException {
        final List<String> owners = new ArrayList<>();
        for (final File f : javaSourcesUnderSrc()) {
            final String code = SourceScan.codeOnly(read(f));
            final List<int[]> decls = SourceScan.declarations(code);
            for (final Integer at
                    : SourceScan.invocationsOf(code, decls, "new MlsExternalCommitBudget(")) {
                owners.add(f.getName().replace(".java", "") + "."
                        + SourceScan.enclosingMethod(code, decls, at.intValue()) + "()");
            }
        }
        assertEquals("The §11.2.2 ledger is constructed from " + owners
                + ". A second instance is a "
                + "second counter unless it shares the preference file AND the key space, and "
                + "'spending one cannot spend the other' is the property this budget's first "
                + "paragraph is about — in the wrong direction it becomes 'spending it does not "
                + "spend it'.",
                java.util.Collections.singletonList("MlsProviderTransport.xcBudget()"), owners);
    }

    /**
     * The deciding overload's body, selected by a parameter only it declares;
     * {@code SourceScan.bodyOf} answers the first declaration, a one-line delegate.
     */
    private static String decidingOverload() throws IOException {
        final String code = SourceScan.transportUnsplitCode();
        final List<String> matching = decidingDeclarations(code);
        assertTrue("no resyncViaExternalCommit overload declares " + DECIDING_OVERLOAD + " — the "
                + "overload selector has gone stale and this guard cannot find the arm that decides",
                !matching.isEmpty());
        // Two matches fail naming both bodies rather than picking one.
        assertEquals("TWO resyncViaExternalCommit overloads declare " + DECIDING_OVERLOAD + ", so "
                + "the selector no longer identifies the arm that decides and this guard would be "
                + "asserting a property of whichever one came first in the file. Give the deciding "
                + "overload a parameter its delegates do not have, and name it in "
                + "DECIDING_OVERLOAD.", 1, matching.size());
        return matching.get(0);
    }

    /** Bodies of every {@code resyncViaExternalCommit} whose declaration carries the selector. */
    private static List<String> decidingDeclarations(final String src) {
        final List<String> out = new ArrayList<>();
        for (final int[] d : SourceScan.declarations(src)) {
            if (!src.substring(d[2], d[3]).equals("resyncViaExternalCommit")) continue;
            if (!src.substring(d[0], d[1]).contains(DECIDING_OVERLOAD)) continue;
            int depth = 0;
            for (int i = d[1]; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { out.add(src.substring(d[1], i + 1)); break; }
            }
        }
        return out;
    }

    /** How many parameters the deciding overload declares — read, never hardcoded. */
    private static int decidingArity() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
        for (final int[] d : SourceScan.declarations(src)) {
            if (!src.substring(d[2], d[3]).equals("resyncViaExternalCommit")) continue;
            final String decl = src.substring(d[0], d[1]);
            if (!decl.contains(DECIDING_OVERLOAD)) continue;
            final int open = decl.indexOf('(');
            final int close = decl.lastIndexOf(')');
            if (open < 0 || close <= open) continue;
            // argumentListAt returns the inner text, so topLevelArguments expects no outer parens.
            return SourceScan.topLevelArguments(decl.substring(open + 1, close)).size();
        }
        return -1;
    }

    /**
     * Every class-level body named {@code name}, in file order; the shared {@code bodyOf} answers
     * only one.
     */
    private static List<String> bodiesOf(final String src, final String name) {
        final List<String> out = new ArrayList<>();
        for (final int[] d : SourceScan.declarations(src)) {
            if (!src.substring(d[2], d[3]).equals(name)) continue;
            int depth = 0;
            for (int i = d[1]; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { out.add(src.substring(d[1], i + 1)); break; }
            }
        }
        return out;
    }

    /** The brace-matched block that opens at or after {@code from}. */
    private static String blockAfter(final String src, final int from) {
        final int open = src.indexOf('{', from);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static int lineOf(final String src, final int at) {
        int n = 1;
        for (int i = 0; i < at && i < src.length(); i++) {
            if (src.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static String read(final File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Every {@code .java} under {@code src/}, wherever the module root is. */
    private static List<File> javaSourcesUnderSrc() throws IOException {
        File root = null;
        for (final String c : new String[] {"src", "packages/apps/Messaging/src", "../src"}) {
            if (new File(c).isDirectory()) { root = new File(c); break; }
        }
        if (root == null) throw new IOException("no src/ directory found from "
                + new File(".").getAbsolutePath());
        final List<File> out = new ArrayList<>();
        collect(root, out);
        // ...and the transport's destinations: a door moved into an engine class is still a door.
        // Not the whole engine: MlsSession and OpenMlsSession define the mint.
        for (final String d : SourceScan.transportDestinations()) {
            final File f = new File(root.getParentFile(), d);
            if (f.isFile()) out.add(f);
        }
        assertTrue("found no Java sources under " + root + " — the locator has gone stale and the "
                + "tree-wide checks are scanning nothing", out.size() > 50);
        return out;
    }

    private static void collect(final File dir, final List<File> out) {
        final File[] kids = dir.listFiles();
        if (kids == null) return;
        for (final File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".java")) out.add(k);
        }
    }
}
