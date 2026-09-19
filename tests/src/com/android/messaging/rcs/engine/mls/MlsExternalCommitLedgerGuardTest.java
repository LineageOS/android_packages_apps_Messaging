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
 * <b>Every path that spends an EXTERNAL COMMIT is charged to one ledger</b> — invariant I2, resource
 * R4 of the Stage 0 resource table, §5.
 *
 * <h2>What this is enumerated from, and why not from the RPC</h2>
 *
 * <p>Stage 0 established the enumeration point rather than assuming it, and its finding is the whole
 * design of this file: <b>{@code applyMlsControl} is a FAMILY, not a primitive</b> — eight call sites
 * carrying six different events, of which exactly one is an external commit. What makes a commit an
 * external commit is not the RPC that carries it but the <b>ARTIFACT</b>: a commit built against the
 * SERVER's GroupInfo and its epoch authenticator rather than against our own state.
 *
 * <p>So the needle is {@code externalCommitResync(} — <b>the engine call that MINTS one</b>. That is
 * {@code MlsPeerReJoinBudgetGuardTest}'s method, whose lesson is precise: it enumerated from the
 * engine calls that mint a Welcome rather than from the dial that delivers one, and found a sixth
 * call site two hand inventories in the same session had missed. The mint is the tighter funnel, it
 * is an ENGINE-DECLARED fact ({@code MlsSession.externalCommitResync}), and it cannot be reached by a
 * path that skips it.
 *
 * <p>The consequence is that the seven non-external-commit {@code applyMlsControl} sites are not a
 * hand list this file has to be trusted about. They are <b>derived</b>: a site is excluded because
 * its enclosing method does not mint, which is checked, and
 * {@link #theApplyMlsControlFamilyCarriesExactlyOneExternalCommit} fails the day one of them starts
 * minting.
 *
 * <h2>The source-scan rules, and how each is met</h2>
 *
 * <ul>
 *   <li><b>Key on the INVOKED METHOD NAME, never on a receiver or a free-form label.</b> Every needle
 *       here is {@code name(}. The mint is written {@code mSelf.externalCommitResync(...)} today, and
 *       {@code openMls()} hands back a second live reference to the same session — a
 *       receiver-qualified needle would report green for {@code openMls().externalCommitResync(...)},
 *       which is instance 3 of that failure. And {@code pt("applyMlsControl")}'s tag is a LOCK-ASSERTION
 *       LABEL, not an identifier: one of the eight family sites
 *       ({@code eraAdvancePreserving}) invokes through a HELD LOCAL and carries no tag at all, so a
 *       tag-keyed scan would see seven of eight.</li>
 *   <li><b>Per-item lower bounds, never a total.</b> Every assertion below names the item it is
 *       about. A total of "one charge site" is satisfied by one charge on the wrong door.</li>
 *   <li><b>Zero hits FAIL.</b> {@link #theSubjectsOfThisGuardAreAllNonEmpty} runs first and every
 *       enumeration asserts it found something before anything is concluded from it.</li>
 *   <li><b>Tied to an engine-visible fact where one exists</b> (recommendation (d)). The ledger's
 *       numbers and posture are read off {@link MlsWindowBudget#EXTERNAL_COMMIT} by reflection — the
 *       instance the adapter constructs, not a constant re-typed here. The mint is anchored against
 *       {@code MlsSession}'s DECLARATION rather than by reflection, because that interface is one of
 *       the six engine classes deliberately off the host classpath; a rename fails
 *       {@link #theSubjectsOfThisGuardAreAllNonEmpty} rather than enumerating zero.</li>
 * </ul>
 *
 * <h2>Why a resource with ONE door still needs a scan</h2>
 *
 * <p>Because it has one door <em>today</em>. R3's guard found a call site two hand inventories had
 * missed, and later phases add recovery arms. A ledger with no scan is correct on the day it
 * lands and silently incomplete on the day someone adds one.
 */
public final class MlsExternalCommitLedgerGuardTest {

    private static final String TRANSPORT = "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";
    private static final String DEBUG_RECEIVER = "src/com/android/messaging/rcs/RcsDebugSendReceiver.java";
    private static final String STALL_RECEIVER = "src/com/android/messaging/rcs/e2ee/MlsStalledActionReceiver.java";
    private static final String LEDGER = "src/com/android/messaging/rcs/e2ee/MlsExternalCommitBudget.java";

    /** The engine call that MINTS an external commit. The enumeration point; see the class javadoc. */
    private static final String MINT = "externalCommitResync(";

    /** The one door. Three overloads; only the widest decides. */
    private static final String DOOR = "resyncViaExternalCommit(";

    /**
     * The deciding overload's declaration carries this parameter and its delegates do not.
     *
     * <p>It was {@code removeLeafIndex} until 2026-09-11 and that <b>silently stopped selecting the
     * right body</b>: a sixth parameter was added, so the 5-arg overload became a
     * delegate that still declares {@code removeLeafIndex}, {@code bodyOf} answered the FIRST match,
     * and three assertions in this file reported that the §11.2.2 allowance had stopped being
     * charged when it had not. A selector that can be inherited by a delegate is a selector that
     * will go stale again — so {@link #decidingOverload} no longer trusts it to be unique, it
     * CHECKS, and fails naming both bodies rather than picking one.
     */
    private static final String DECIDING_OVERLOAD = "dryRun";

    /** The lazily-initialised ledger accessor. Charge and reset both go through it. */
    private static final String LEDGER_ACCESSOR = "xcBudget(";

    /** The RPC family the resource table records the exclusions against. */
    private static final String FAMILY = "applyMlsControl(";

    // ---- zero hits fail ------------------------------------------------------------------------

    /**
     * Every enumeration this class rests on finds something.
     *
     * <p>If the subjects are not read, everything below is a statement about an empty string and
     * would report green. Run this first when the file is failing.
     */
    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        for (final String rel : new String[] {TRANSPORT, DEBUG_RECEIVER, STALL_RECEIVER, LEDGER}) {
            assertTrue(rel + " did not read, or read empty — this guard has no subject",
                    SourceScan.codeOnly(SourceScan.read(rel)).length() > 500);
        }
        // THE MINT IS ANCHORED IN THE ENGINE, and it has to be anchored by SOURCE rather than by
        // reflection: MlsSession is one of the six engine classes deliberately kept OFF the host
        // classpath (it imports android through the JNI session), so Class.forName cannot see it.
        // Reading its declaration is the strongest key available here — the needle below is the
        // name of a method the engine declares, and a rename that left this guard scanning for a
        // name nothing declares fails HERE rather than silently enumerating zero mints.
        final String session = SourceScan.codeOnly(
                SourceScan.read("engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java"));
        assertTrue("MlsSession.java did not read — the mint's engine anchor is gone",
                session.length() > 500);
        assertTrue("MlsSession no longer declares " + MINT + ". That is the ENGINE CALL this whole "
                + "guard enumerates from — renamed, every scan below finds zero and the resource "
                + "would read as covered while nothing enumerated it.", session.contains(MINT));
        assertTrue("MlsWindowBudget is not on the host classpath — the ledger's numbers and posture "
                + "cannot be read from the instance production uses",
                SourceScan.engineClass("MlsWindowBudget") != null);
    }

    // ---- the mint, enumerated from the engine call ---------------------------------------------

    /**
     * <b>Every mint of an external commit, anywhere under {@code src/}, is inside the one door.</b>
     *
     * <p>Walks every Java source rather than the transport alone: a mint added in a new class would
     * be invisible to a scan of one file, and "the resource has one door" is a claim about the tree,
     * not about {@code MlsProviderTransport}.
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
                    + offenders + ". Every mint spends one of the day's fifty against a real group; "
                    + "a second door is the known defect on this resource — the budget would exist, "
                    + "read as spent, and be ignored. Route it through resyncViaExternalCommit, or "
                    + "charge it where it is.");
        }
    }

    /**
     * <b>The charge comes before the mint, and before the RPC.</b>
     *
     * <p>Order, not presence. A charge after the mint is a budget that counts what has already been
     * spent, and a charge after the send is one the crash it exists to bound can skip — the same
     * discipline {@code MlsRebuildLimiter.claim} states for itself: an operation that CRASHES must
     * still count, or a reproducible crash becomes an unthrottled loop.
     */
    @Test
    public void theChargeComesBeforeTheMintAndBeforeTheSend() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        final int mint = body.indexOf(MINT);
        final int send = body.indexOf(FAMILY);
        assertTrue("the deciding resyncViaExternalCommit overload no longer charges " + LEDGER_ACCESSOR
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
     * <b>The charge can refuse, and a refusal returns without minting.</b>
     *
     * <p>A ledger whose verdict is read and then ignored is worse than no ledger: the budget would
     * then exist, read as spent, and be ignored. Asserted structurally: the
     * charge appears in a negated condition whose arm returns.
     */
    @Test
    public void aRefusedChargeDoesNotReachTheServer() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        assertTrue("no charge site in the deciding overload", charge >= 0);
        final int lineStart = body.lastIndexOf('\n', charge) + 1;
        final int lineEnd = body.indexOf('\n', charge);
        final String line = body.substring(lineStart, lineEnd < 0 ? body.length() : lineEnd);
        assertTrue("the ledger is charged at a site that does not read its verdict: \"" + line.trim()
                + "\". A budget that is charged and not consulted exists, reads as spent, and is "
                + "ignored — the worse-than-nothing case.",
                line.contains("if (!") && line.contains("claim("));
        assertTrue("the refusal arm does not return, so a refused external commit proceeds anyway: \""
                + line.trim() + "\"", line.contains("return"));
    }

    // ---- the exclusions are derived, not asserted ----------------------------------------------

    /**
     * <b>Exactly one member of the {@code applyMlsControl} family is an external commit, and the
     * exclusion of the others is CHECKED rather than claimed.</b>
     *
     * <p>The resource table lists eight sites and marks seven "no". This does not read that list: it
     * enumerates the family from the invoked method name, groups by enclosing method, and asks each
     * whether it MINTS. A site is excluded because it does not mint — so an eighth site added on a
     * path that does mint fails here, and a listed exclusion that starts minting fails here too.
     *
     * <p>{@code eraAdvancePreserving} is the site that makes the needle choice load-bearing: it
     * invokes through a held local ({@code pt.applyMlsControl(...)}) and carries no {@code pt("…")}
     * tag of its own, so a tag-keyed scan would enumerate seven of eight and report green.
     */
    @Test
    public void theApplyMlsControlFamilyCarriesExactlyOneExternalCommit() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        final List<int[]> decls = SourceScan.declarations(code);
        final List<Integer> sites = SourceScan.invocationsOf(code, decls, FAMILY);
        assertTrue("no applyMlsControl invocation found in the transport — the family's name changed "
                + "and this check is silently passing", sites.size() >= 2);

        final Set<String> enclosing = new LinkedHashSet<>();
        for (final Integer at : sites) {
            enclosing.add(SourceScan.enclosingMethod(code, decls, at.intValue()));
        }
        assertTrue("every applyMlsControl site is in one method — either the family collapsed or the "
                + "declaration pattern has gone stale", enclosing.size() >= 2);

        final List<String> minting = new ArrayList<>();
        for (final String name : enclosing) {
            boolean mints = false;
            for (final String body : bodiesOf(code, name)) {
                if (body.contains(MINT)) { mints = true; break; }
            }
            if (mints) minting.add(name);
        }
        assertEquals("The set of applyMlsControl callers that build an EXTERNAL commit has changed: "
                + minting + " out of " + enclosing + ". Exactly one member of this family is an "
                + "external commit — the artifact is what makes it one, not the RPC — and every "
                + "other member is excluded because it does not mint, which is checked here rather "
                + "than believed from the resource table. A new name in this list is a second door "
                + "onto RCC.16 §11.2.2's fifty a day.",
                java.util.Collections.singletonList("resyncViaExternalCommit"), minting);
    }

    // ---- one charge site, and every door reaches it ---------------------------------------------

    /**
     * <b>Every use of the ledger accessor is one of two named sites.</b>
     *
     * <p>Per site with its enclosing method, not a count: a total of two is satisfied by two charges
     * in the wrong place. The reset is here rather than elsewhere because a lever that reaches the
     * ledger is part of the ledger.
     */
    @Test
    public void everyLedgerSiteIsOneOfTheTwoNamedOnes() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        final List<int[]> decls = SourceScan.declarations(code);
        final List<String> sites = new ArrayList<>();
        for (final Integer at : SourceScan.invocationsOf(code, decls, LEDGER_ACCESSOR)) {
            sites.add(SourceScan.enclosingMethod(code, decls, at.intValue()));
        }
        assertTrue("no use of " + LEDGER_ACCESSOR + " outside its own declaration — the accessor was "
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
     * <b>Every caller of the door reaches the overload that charges.</b>
     *
     * <p>The door has three overloads and only the widest decides; the other two delegate. A caller
     * that reached a delegating overload which had stopped delegating would mint nothing and charge
     * nothing, and the resource would read as covered. Asserted per overload.
     */
    @Test
    public void everyOverloadOfTheDoorReachesTheChargingOne() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
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
     * <b>The operator arm is CHARGED, not exempt</b> — and that is the opposite of resource R1's
     * answer, on purpose.
     *
     * <p>{@code MlsFetchLedger} exempts its debug callers, correctly: a debug LOOK costs the server a
     * lookup and refusing it makes the arm useless. A debug EXTERNAL COMMIT costs a real peer a real
     * commit against a real group, and §11.2.2 bounds what the GROUP pays, not what we pay. An
     * exemption here would put the one operation this budget exists to bound behind the one arm that
     * is not bounded — and the {@code --ez resync} arm is reached with
     * {@code forcePastProfile=true}, which is already the loudest thing in this path.
     *
     * <p>Asserted structurally rather than by an absence: the debug arm's call carries five
     * arguments, i.e. it is the deciding overload, which is the one that charges.
     */
    @Test
    public void theDebugArmGoesThroughTheChargingOverload() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(DEBUG_RECEIVER));
        final List<Integer> calls =
                SourceScan.invocationsOf(code, SourceScan.declarations(code), DOOR);
        assertTrue("the debug resync arm is gone from RcsDebugSendReceiver — either the operator "
                + "lever for this path was removed, or it was renamed and this check has stopped "
                + "watching it", calls.size() >= 1);
        // READ the deciding arity rather than writing it down. It was hardcoded as 5 and a later
        // change made it 6, so this assertion failed for a change that did not touch the property it
        // guards — and a guard that cries wolf on every signature change is one somebody eventually
        // edits without reading.
        final int arity = decidingArity();
        assertTrue("could not read the deciding overload's parameter count — the selector "
                + DECIDING_OVERLOAD + " matched no declaration", arity > 0);
        for (final Integer at : calls) {
            final List<String> args = SourceScan.topLevelArguments(
                    SourceScan.argumentListAt(code, at.intValue()));
            assertEquals("the debug resync arm calls resyncViaExternalCommit with " + args.size()
                    + " arguments (" + args + "), not the " + arity + " of the deciding overload. A "
                    + "debug arm that reached a delegating overload would still charge today — but "
                    + "the point of this assertion is that the debug door is NOT exempt from "
                    + "§11.2.2, unlike R1's fetch ledger, because a debug external commit costs a "
                    + "real peer a real commit and this bound is on what the GROUP pays.",
                    arity, args.size());
        }
    }

    /**
     * <b>The ONE way the charge is skipped is the dry run, and a dry run cannot mint or send.</b>
     *
     * <p>The charge is conditional — {@code if (!dryRun && !xcBudget().claim(key))}
     * — which is correct, because a dry run transmits nothing and charging it would let an operator
     * asking <i>"is this safe to attempt?"</i> exhaust the 50-per-day allowance for the attempts
     * themselves. But a conditional charge is exactly the shape that turns into an unbudgeted door:
     * the condition only has to grow one more disjunct.
     *
     * <p>So both halves are asserted. The charge may be skipped ONLY under the dry-run flag, and the
     * dry run must RETURN before the mint — {@code externalCommitResync} is the artifact §11.2.2
     * bounds, and it also DELETES the persisted group before it builds, so a "dry" run that reached
     * it would be neither dry nor free.
     *
     * <p>Without this, the previous two tests are satisfied by a charge that never happens.
     */
    @Test
    public void theChargeIsSkippedOnlyForADryRunAndADryRunNeverMints() throws IOException {
        final String body = decidingOverload();
        final int charge = body.indexOf(LEDGER_ACCESSOR);
        assertTrue("no charge site in the deciding overload", charge >= 0);
        final int lineStart = body.lastIndexOf('\n', charge) + 1;
        final int lineEnd = body.indexOf('\n', charge);
        final String line = body.substring(lineStart, lineEnd < 0 ? body.length() : lineEnd).trim();
        // The guard condition's ONLY exemption. A second one would have to be added here
        // deliberately, with a reason, rather than arriving as another `||`.
        // ASSERTED AS A PROPERTY, NOT AS A STRING. An exact match on a source line fails on
        // reformatting, which teaches the next reader to edit the expectation rather than read it.
        // What must hold is the SHAPE: one guard, one sanctioned exemption, no alternatives.
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

    // ---- the lever is part of the ledger -----------------------------------------

    /**
     * <b>A person can clear this allowance</b>, and the clearing reaches the disk.
     *
     * <p>{@code MlsExternalCommitBudget.reset} was public with ZERO production
     * callers, so a conversation whose recovery ladder was stuck behind a spent 50-per-day allowance
     * had no operator lever at all and a wait of up to 24 h. G2's governing rule is
     * "on trip: stop, log loudly, REQUIRE MANUAL RESET"; there was no manual reset.
     *
     * <p>Three links, each asserted, because a lever is only as good as its weakest one — and
     * the trap is precisely a lever that reaches something other than the record refusing
     * the repair.
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

        final String transport = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
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

    // ---- the ledger is the engine policy, at the numbers that ship -----------------------------

    /**
     * <b>The ledger is {@link MlsWindowBudget#EXTERNAL_COMMIT}</b>, at §11.2.2's numbers and D2's
     * posture.
     *
     * <p>Read off the instance the adapter constructs, not off a constant re-typed here: the
     * strongest key a scan can have is a symbol that resolves. The source
     * half asserts only that the adapter still delegates — everything else about the ledger is a
     * fact about a live object.
     */
    @Test
    public void theLedgerIsTheEnginePolicyAtTheSpecNumbers() throws IOException {
        final String budget = SourceScan.codeOnly(SourceScan.read(LEDGER));
        assertTrue("MlsExternalCommitBudget no longer delegates to MlsWindowBudget, so its numbers "
                + "and its posture are back in a class no host test can reach",
                budget.contains("MlsWindowBudget"));
        assertEquals("MAX_PER_DAY moved off RCC.16 §11.2.2's fifty. It is not a tuning knob: no "
                + "value can match Google Messages, which implements no budget at all, so tuning yields "
                + "neither parity nor a principled bound. If parity is the goal, DELETE "
                + "the class.", 50, MlsWindowBudget.EXTERNAL_COMMIT.maxPerWindow());
        assertEquals("the §11.2.2 window is no longer a day",
                24L * 60 * 60 * 1000, MlsWindowBudget.EXTERNAL_COMMIT.windowMs());
        assertEquals("the no-store posture moved off REFUSE. D2 flipped it there from "
                + "ALLOW_UNCOUNTED on its own review — a bound argued for on the grounds that it "
                + "protects a peer who cannot ask us to stop must not have an arm whose effect is "
                + "no bound at all.",
                MlsWindowBudget.OnNoStore.REFUSE, MlsWindowBudget.EXTERNAL_COMMIT.onNoStore());
        assertEquals("an unreadable §11.2.2 record no longer refuses and discards, so a parse error "
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
        assertEquals("The §11.2.2 ledger is constructed from " + owners + ". A second instance is a "
                + "second counter unless it shares the preference file AND the key space, and "
                + "'spending one cannot spend the other' is the property this budget's first "
                + "paragraph is about — in the wrong direction it becomes 'spending it does not "
                + "spend it'.",
                java.util.Collections.singletonList("MlsProviderTransport.xcBudget()"), owners);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The deciding overload's body — selected by a parameter only it declares.
     *
     * <p>{@code SourceScan.bodyOf} without an overload selector answers the FIRST declaration
     * with that name, which here is a three-argument delegate whose body is one line. Asserting on it
     * would prove a property of the delegate and report green for the arm that decides — the same
     * shape as a stale needle, arriving through an overload instead.
     */
    private static String decidingOverload() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        final List<String> matching = decidingDeclarations(code);
        assertTrue("no resyncViaExternalCommit overload declares " + DECIDING_OVERLOAD + " — the "
                + "overload selector has gone stale and this guard cannot find the arm that decides",
                !matching.isEmpty());
        // THE AMBIGUITY IS THE FAILURE THIS ASSERTION EXISTS FOR, and it is not hypothetical: the
        // previous selector was inherited by a new delegate and bodyOf answered the first match,
        // which reported the ledger unspent on a path that spends it. Naming both bodies is the
        // point — a guard that picks one is a guard that can be wrong quietly.
        assertEquals("TWO resyncViaExternalCommit overloads declare " + DECIDING_OVERLOAD + ", so "
                + "the selector no longer identifies the arm that decides and this guard would be "
                + "asserting a property of whichever one came first in the file. Give the deciding "
                + "overload a parameter its delegates do not have, and name it in "
                + "DECIDING_OVERLOAD.", 1, matching.size());
        return matching.get(0);
    }

    /** Bodies of every {@code resyncViaExternalCommit} whose DECLARATION carries the selector. */
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
        final String src = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        for (final int[] d : SourceScan.declarations(src)) {
            if (!src.substring(d[2], d[3]).equals("resyncViaExternalCommit")) continue;
            final String decl = src.substring(d[0], d[1]);
            if (!decl.contains(DECIDING_OVERLOAD)) continue;
            final int open = decl.indexOf('(');
            final int close = decl.lastIndexOf(')');
            if (open < 0 || close <= open) continue;
            // argumentListAt hands its callers the INNER text, so topLevelArguments expects no
            // outer parens: passing them makes every comma depth-1 and the count comes back 1.
            return SourceScan.topLevelArguments(decl.substring(open + 1, close)).size();
        }
        return -1;
    }

    /**
     * EVERY class-level body named {@code name}, in file order.
     *
     * <p>Local rather than in {@code SourceScan} for the reason Stage 2 gave when it wrote the
     * same helper: the shared {@code bodyOf} deliberately answers ONE body, and a caller that wants
     * all of them wants a different question asked.
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
