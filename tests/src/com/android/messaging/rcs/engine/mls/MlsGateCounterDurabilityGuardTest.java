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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>DoD-3 — every gate counter is durable, or declares why not.</b> Decoupling plan §7, Stage 7.
 * Invariant I3(b); the sweep itself is Stage 5.
 *
 * <p>G2's and G4's counters were once IN MEMORY while the loop they bound restarts the
 * process. The fix landed for those two. The ENUMERATION half never did — which is why the plan's
 * §3.5 found three more in-memory counters nobody had asked about, and why Stage 0 found three more
 * again by enumerating from the gate sites instead of from {@code ConvState}'s field list.
 *
 * <h2>What a gate is, structurally — and why this is not enumerated from a field list</h2>
 *
 * <p>The plan is explicit that enumerating from {@code ConvState}'s fields <i>"is why the three
 * siblings were missed"</i>: two of them are on {@code Group}, one is a bare {@code HashMap}. So the
 * subject here is the OTHER operand — <b>a gate is a threshold</b>, and every threshold in this
 * subsystem is a {@code static final} scalar. Those declarations are found by their SHAPE
 * ({@link SourceScan#scalarConstantsDeclaredIn} over {@link SourceScan#codeOnly} source), and
 * the guard-class file list is not typed here either: it is read from the {@code GUARD} regex in
 * {@code tools/mls/transport-classify.py}, the same checked-in ground truth DoD-1 and DoD-5 use.
 *
 * <h2>Where the subject STOPPED, and what closing it cost</h2>
 *
 * <p>It used to stop at the transport, the guard classes, the policies behind a guard class and the
 * classes {@link #INVENTORY}'s own rows name. <b>A policy tunable declared in any other provider
 * {@code e2ee} class was seen by no DoD row at all</b> — measured on a mirror of both repos rather
 * than argued: {@code private static final long PEER_REJOIN_GRACE_MS = 90_000L;} added to
 * {@code MlsCiphertextCache.java} left DoD-1 and DoD-3 GREEN (17 tests, 0 failures), and so did the
 * same constant wired into a live gate in that file. The SAME injection one file over in
 * {@code MlsPeerGuard.java} goes red. The boundary was the guard set, not the property.
 *
 * <p>So {@link #allOwners} now takes the whole provider MLS layer — see {@link #providerLayerOwners}
 * for the two terms it is derived from, and for the measured reason the weaker-looking of the two
 * (a directory walk) cannot be dropped in favour of the stronger one. A THIRD enumeration keys on
 * the USE rather than the declaration ({@link #everyConstantTheLayerReadsFromOutsideItIsClassified}),
 * because both of those terms find declarations and a control showed what that leaves open: a
 * tunable declared in a shared provider class outside the layer and read at a gate inside it.
 *
 * <h2>What makes a {@code static final} a POLICY TUNABLE rather than something else</h2>
 *
 * <p>This is the hard half, and it splits cleanly into a part that is PROVABLE and a part that is
 * RECORDED. Pretending the second part is the first is how a widened guard becomes noise people
 * switch off.
 *
 * <p><b>Provable — an ALIAS is not a declaration.</b> {@code MlsPeerGuard} declares five
 * {@code static final}s and every one of them is
 * {@code = SomeEngineRecord.SOME_CONSTANT}. A naive "any scalar in the provider layer" rule flags
 * all five, five of the first thirteen findings are wrong on day one, and the guard is disabled by
 * the second person to read it. {@link #aliasTargetOf} decides this structurally — the initialiser,
 * read from {@code =} to the next {@code ;} so it spans lines, must be NOTHING BUT a qualified
 * reference — and {@link #aliasFault} resolves the target: an engine target by reflection (a
 * {@code static final} field of a class {@link Class#forName} answers), a provider target by
 * declaration shape PLUS its own {@code INVENTORY} row, so an alias chain cannot walk a constant out
 * of the inventory a hop at a time. An initialiser that DERIVES ({@code X.Y * 2}) is a declaration
 * and needs a row; over-requiring is the safe direction.
 *
 * <p><b>Recorded — POLICY versus WIRE/ABI is not decidable from the source, and this guard does not
 * pretend otherwise.</b> {@code ERA_EXT_TYPE = 0xF001} and {@code MAX_GATED_RESENDS = 3} are the
 * same shape to every scan that can be written: a {@code static final int} at a comparison. What
 * separates them is who chose the number — the RCC.16 spec and the peer, or us — and that fact is
 * not in the file. So {@link Durability#WIRE} is a human verdict, and what the guard enforces is
 * what DoD-4's markers already require: that a verdict EXISTS, is from the closed set,
 * names something that resolves, and does not change silently. It gets one attack, and only one is
 * available: {@link #nothingClassifiedWireIsOrderCompared}, because a peer's value is matched for
 * equality and a threshold is ordered. That can refute a WIRE claim; it cannot confirm one.
 *
 * <p><b>The three further things the rule cannot decide</b>, stated here rather than discovered:
 * (a) whether the REASON on a {@code TRANSIENT_DECLARED}, {@code NOT_A_GATE} or {@code WIRE} row is
 * true — the guard checks that the evidence resolves, and a person reads the argument; (b) whether a
 * {@code NOT_A_GATE} constant is bounding something through a path that is not a comparison or a
 * boolean predicate, e.g. handed through a local into another class; and (c) a threshold written as
 * a bare literal, which is the boundary the section below measures — that measurement is the
 * transport's only, and widening it across the layer is deliberately not done, for the reason given
 * there.
 *
 * <p>A fourth source is enumerated for the same reason DoD-1 exists: <b>Stage 6 moves these
 * constants into {@code MlsConfig}</b>, and a gate that reads {@code mCfg.rebuildEpisodeMs} instead
 * of {@code REBUILD_EPISODE_MS} must not fall out of DoD-3's subject the moment DoD-1 is met. So
 * every {@code MlsConfig} field — enumerated by REFLECTION, not from a list — that appears in the
 * transport at a gate site is a subject too. {@link #theSubjectsOfThisGuardAreAllNonEmpty} fails if
 * any of the four enumerations comes back empty.
 *
 * <h2>What "at a gate site" means, precisely</h2>
 *
 * <ol>
 *   <li>an operand of a relational or equality operator — {@code sendsSinceLeafRotation <
 *       REKEY_AFTER_SENDS}; or</li>
 *   <li>an argument to a method whose name some engine class declares returning {@code boolean} —
 *       {@code held.timedOut(now, PENDING_OP_MAX_AGE_MS)},
 *       {@code MlsRecoveryPolicy.gateExpired(opened, now, GATE_DEADLINE_MS)}.</li>
 * </ol>
 *
 * <p>(2) is not decoration. Four of this file's thresholds are spent through a predicate rather than
 * through an operator, and a comparison-only rule would have called them "not a gate" and stopped
 * asking — a silent pass, which is the failure this guard exists to prevent. The argument list is
 * PAREN-MATCHED ({@link SourceScan#argumentListAt}), never read to the next comma: instance 4 of
 * that failure is a window that encoded how far apart the code happened to sit.
 *
 * <h2>The rule this enforces</h2>
 *
 * <p>Every threshold carries a row in {@link #INVENTORY}. A threshold with no row FAILS — that is
 * the property, and it is the one thing a hand list normally cannot do. Each row states the counter
 * the threshold bounds and one of:
 *
 * <ul>
 *   <li><b>{@code DURABLE_RECORD}</b> — an engine record holds it. The record class must RESOLVE by
 *       reflection and the named restart test must resolve and declare a {@code @Test}.</li>
 *   <li><b>{@code DURABLE_PREF}</b> — {@code SharedPreferences} holds it. The named {@code PREF_*}
 *       key must be declared in the transport.</li>
 *   <li><b>{@code TRANSIENT_DECLARED}</b> — in memory ON PURPOSE, reason recorded HERE. Not read
 *       from a javadoc: a javadoc routinely answers a
 *       neighbouring question, and three of this subsystem's were read that way.</li>
 *   <li><b>{@code TRANSIENT_UNDECLARED}</b> — in memory and nobody has said why. <b>This is the
 *       number Stage 5 drives to zero</b>, and every row here names the gate it leaves unbounded
 *       across a restart.</li>
 *   <li><b>{@code NOT_A_GATE}</b> — a window length, a page size or an arithmetic offset. It has a
 *       reason, and {@link #nothingClassifiedNotAGateIsUsedAtAGateSite} checks the claim against the
 *       structural rule above rather than believing it.</li>
 * </ul>
 *
 * <p>The site COUNT of every threshold is pinned per constant, so a threshold that gains a door goes
 * red and someone re-answers the question for the new door. Per-item, never a total: a total stays
 * put while one gate loses a site and another gains one.
 *
 * <h2>Where this guard's subject ENDS — measured, so nobody has to assume it</h2>
 *
 * <p>All four enumerations above find NAMED things: a {@code static final} scalar, or an
 * {@code MlsConfig} field. <b>A threshold written as a bare literal is invisible to every one of
 * them</b>, and that is a real hole rather than a theoretical one — the in-memory siblings were
 * missed by enumerating from a field list, and this is the same failure one level down.
 *
 * <p>So it was measured rather than left as a caveat. Over comment- and string-stripped transport
 * source, every relational comparison whose right operand is an integer literal with a value of at
 * least <b>3</b> — three because the smallest threshold this inventory classifies is 3
 * ({@code UNCONVERGED_BEFORE_REBUILD}, {@code PARKS_BEFORE_UNSTICKING_A_HEAL},
 * {@code KP_REPLENISH_AT}), so every smaller literal in this file is a null/empty/index sentinel —
 * comes to <b>three</b> occurrences: a four-byte wire-prefix length, a fixed-width hex-dump loop,
 * and ONE Google Messages capacity bound, {@link #LITERAL_CAPS}. That one is pinned below.
 *
 * <p><b>What is deliberately NOT here is a general literal scan</b>, and the reason is worth
 * recording so the next reader does not re-derive it. Widening the same measurement to include
 * {@code ==}/{@code !=} and shift operators takes it from 3 hits to 33, almost all of them wire
 * byte-twiddling ({@code (b[0] & 0xFF) << 24}, {@code head[3] == 4}); separating those needs a rule
 * that excludes an operator by looking at the character next to it, which is the same
 * defect exactly — a guard keyed on how far apart the code happens to sit. A scan that must be
 * hand-tuned that way over a 19.6k-line file three people are editing would go red on unrelated
 * work, and a guard that cries wolf is removed. The narrow, structural check below covers the one
 * bound the measurement actually found.
 */
public final class MlsGateCounterDurabilityGuardTest {

    private static final String TOOL = "tools/mls/transport-classify.py";
    private static final String E2EE = "src/com/android/messaging/rcs/e2ee/";
    private static final String ENGINE_DIR = "engine/src/com/android/messaging/rcs/engine/mls";
    /** The provider MLS layer's root — walked RECURSIVELY. See {@link #providerLayerOwners}. */
    private static final String PROVIDER_MLS_DIR = "src/com/android/messaging/rcs/e2ee";
    private static final String TRANSPORT = "MlsProviderTransport";

    private enum Durability { DURABLE_RECORD, DURABLE_PREF, TRANSIENT_DECLARED,
        TRANSIENT_UNDECLARED, NOT_A_GATE, WIRE }

    /** One threshold: where it is declared, how many code sites it has, and what backs its counter. */
    private static final class Row {
        final String owner;          // the class that declares it
        final String constant;
        final int sites;             // code (not javadoc) reference sites in that class
        final Durability durability;
        final String counter;        // the thing the threshold bounds
        final String evidence;       // a record class, a PREF_ key, or the reason
        final String restartTest;    // for DURABLE_RECORD: the host test that proves it survives

        Row(final String owner, final String constant, final int sites,
                final Durability durability, final String counter, final String evidence,
                final String restartTest) {
            this.owner = owner;
            this.constant = constant;
            this.sites = sites;
            this.durability = durability;
            this.counter = counter;
            this.evidence = evidence;
            this.restartTest = restartTest;
        }

        String key() { return owner + "." + constant; }
    }

    private static Row durableRecord(final String owner, final String c, final int n,
            final String counter, final String record, final String test) {
        return new Row(owner, c, n, Durability.DURABLE_RECORD, counter, record, test);
    }

    private static Row durablePref(final String owner, final String c, final int n,
            final String counter, final String prefKey) {
        return new Row(owner, c, n, Durability.DURABLE_PREF, counter, prefKey, "");
    }

    private static Row transientUndeclared(final String owner, final String c, final int n,
            final String counter, final String whatItLeavesUnbounded) {
        return new Row(owner, c, n, Durability.TRANSIENT_UNDECLARED, counter,
                whatItLeavesUnbounded, "");
    }

    private static Row transientDeclared(final String owner, final String c, final int n,
            final String counter, final String whyItIsCorrectlyInMemory) {
        return new Row(owner, c, n, Durability.TRANSIENT_DECLARED, counter,
                whyItIsCorrectlyInMemory, "");
    }

    private static Row notAGate(final String owner, final String c, final int n,
            final String reason) {
        return new Row(owner, c, n, Durability.NOT_A_GATE, "—", reason, "");
    }

    /**
     * A value fixed OUTSIDE us — a spec, a peer, a platform ABI — rather than chosen by us.
     *
     * <p>Distinct from {@code NOT_A_GATE} because a wire value may legitimately sit at a gate site:
     * {@code info.cipherSuite != ourSuite} is a protocol check, not a bound on a counter, and
     * classifying it {@code NOT_A_GATE} would fail
     * {@link #nothingClassifiedNotAGateIsUsedAtAGateSite} for being right. It has its own attack
     * instead — {@link #nothingClassifiedWireIsOrderCompared} — because a peer's extension
     * type or suite id is compared for EQUALITY, and a threshold is ORDER-compared.
     *
     * <p><b>This is the verdict the classification rule cannot derive</b>, and saying so is the
     * point of having it: {@code ERA_EXT_TYPE = 0xF001} and {@code MAX_GATED_RESENDS = 3} are the
     * same shape to every scan ever written. What separates them is who chose the number, which is
     * not in the source. So the row records the authority and the guard enforces only that a person
     * recorded one, that it is from the closed set, and that it does not change silently.
     */
    private static Row wire(final String owner, final String c, final int n,
            final String whoFixesTheValue) {
        return new Row(owner, c, n, Durability.WIRE, "—", whoFixesTheValue, "");
    }

    /**
     * Every threshold in the subsystem, measured 2026-09-09 at the working tree.
     *
     * <p><b>Read the {@code TRANSIENT_UNDECLARED} rows as Stage 5's queue.</b> Eight gates lose
     * their bound at a restart and nothing states that they should.
     */
    private static final Row[] INVENTORY = {
        // ---- durable in an engine record, restart-tested ----------------------------------------
        // Stage 6 moved these two to the class whose FIELD each one bounds — attemptCount and
        // startedAtMs are MlsPendingOperation's, they are persisted on its record, and the bound on
        // a counter belongs with the counter.
        durableRecord("MlsPendingOperation", "PENDING_OP_MAX_ATTEMPTS", 3,
                "MlsPendingOperation.attemptCount", "MlsPendingOperation",
                "MlsConversationRecordTest"),
        durableRecord("MlsPendingOperation", "PENDING_OP_MAX_AGE_MS", 2,
                "MlsPendingOperation.claimedAtMs", "MlsPendingOperation",
                "MlsConversationRecordTest"),
        // §10.3's chain cap, now the DEFAULT of a knob rather than a bare number: the gate reads
        // mCfg.ftdMaxAttempts (its own row below) and this is what that resolves to. Both rows are
        // here on purpose — a default nothing reads and a gate reading something else is exactly the
        // drift a single row would hide.
        //
        // ZERO SITES, and that is correct rather than dead: its only reader is MlsConfig's
        // constructor, and MlsConfig is Stage 6's OTHER destination rather than a threshold owner —
        // adding it to allOwners() would drag every DEF_* default in it into this inventory as a
        // threshold, which none of them is. The door is the mCfg row.
        durableRecord("MlsFtdEscalation", "MAX_FTD_ATTEMPTS", 0,
                "MlsConversationRecord.ftdResendCounts", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        // MlsFtdEscalation's two siblings, enumerated for the first time because Stage 6 made that
        // class an owner. Both are real bounds over the same event and neither had a row.
        durableRecord("MlsFtdEscalation", "ESCALATE_AT", 4,
                "MlsResendLedger repeat-resend rows for (peer, conversation) in WINDOW_MS — a SQL "
                + "table, durable by construction because the count is derived from rows",
                "MlsResendRecord", "MlsResendRecordTest"),
        notAGate("MlsFtdEscalation", "WINDOW_MS", 1,
                "a window LENGTH subtracted in windowStart(); the rows it windows are the durable "
                + "ledger's, and ESCALATE_AT is the bound over them"),
        durableRecord("MlsPeerGuard", "MAX_ERA_ADVANCES_PER_HOUR", 1,
                "MlsEraBudgetRecord.countWithin(HOUR_MS)", "MlsEraBudgetRecord",
                "MlsEraBudgetRecordTest"),
        durableRecord("MlsPeerGuard", "MAX_ERA_ADVANCES_PER_DAY", 1,
                "MlsEraBudgetRecord.countWithin(DAY_MS)", "MlsEraBudgetRecord",
                "MlsEraBudgetRecordTest"),
        durableRecord("MlsPeerGuard", "MAX_CONSECUTIVE_PEER_FAILURES", 1,
                "MlsPeerHealthRecord consecutive-failure count", "MlsPeerHealthRecord",
                "MlsPeerHealthRecordTest"),
        // Stage 3 moved these two out of MlsRebuildLimiter/MlsExternalCommitBudget and
        // into the one engine policy both now delegate to. The threshold went where the record that
        // counts it and the comparison that reads it already were; the adapters kept the store.
        // Their restart test is MlsWindowBudgetTest, which asserts the property THROUGH the shipped
        // policy at the shipped numbers rather than against a record the test built (invariant I5).
        durableRecord("MlsWindowBudget", "REBUILD_MAX_PER_WINDOW", 1,
                "MlsRebuildWindowRecord.count()", "MlsRebuildWindowRecord",
                "MlsWindowBudgetTest"),
        durableRecord("MlsWindowBudget", "EXTERNAL_COMMIT_MAX_PER_DAY", 1,
                "MlsRebuildWindowRecord.count()", "MlsRebuildWindowRecord",
                "MlsWindowBudgetTest"),

        // ---- durable in SharedPreferences ------------------------------------------------------

        // Stage 6 moved these three into MlsKeyPackagePolicy. The COUNTERS did not move and
        // neither did their durability: all three are SharedPreferences stamps the transport writes,
        // which is why the pref-key evidence below still resolves against the transport's own
        // declarations. What moved is the arithmetic over them.
        durablePref("MlsKeyPackagePolicy", "KP_REPUBLISH_MS", 1, "the last KeyPackage publish",
                "MlsProviderTransport.PREF_LAST_KP_PUBLISH"),
        durablePref("MlsKeyPackagePolicy", "KP_REPLENISH_AT", 1, "our remaining published pool",
                "MlsProviderTransport.PREF_KP_REMAINING"),
        durablePref("MlsKeyPackagePolicy", "POOL_REPAIR_MIN_INTERVAL_MS", 2,
                "the last pool repair", "MlsProviderTransport.PREF_LAST_POOL_REPAIR"),
        durablePref("MlsMetadataKeysPolicy", "RETAINED_CONTENT_KEYS_PER_SLOT", 2,
                "the per-slot retention order list", "MlsProviderTransport.PREFS"),

        // ---- Stage 5's queue, ANSWERED ----------------------------------------------
        //
        // Three of the eight are now durable and five carry a declared reason. The rule the stage
        // applied to each, and it is DIRECTIONAL rather than "persist everything": ask what a
        // restart HANDS BACK. A bound whose loss lets the loop run MORE — a spent allowance refilled,
        // a suppressor reset, a cooldown on somebody else's scarce resource forgotten — must outlive
        // the process. A bound whose loss makes us MORE PATIENT, or whose counter dies together with
        // the thing it counts, must not: the first case is proven (an empty liveness ledger reads
        // as UNKNOWN and draws the unchanged base budget, so a restart can never buy an early
        // takeover), and persisting it would break the fix this invariant came from.
        //
        // REKEY_AFTER_SENDS WAS NEVER TRANSIENT — this row was WRONG, measured 2026-09-09. Both
        // Stage 0's audit ("a restart resets it to 0") and this inventory's first draft asserted
        // something neither had checked against the persistence path: noteSendAndMaybeRekey
        // increments the counter and calls putGroup on the very next line, putGroup calls
        // writeRecord, writeRecord puts sendsThisEpoch and sendsSinceLeafRotation into
        // MlsConversationRecord with commit() (not apply()), and loadFromRecord reads both back —
        // with a migration seed for records written before the field existed. It is the same defect
        // class the sweep exists to catch, committed by the sweep's own guard.
        durableRecord("MlsRekeyPolicy", "REKEY_AFTER_SENDS", 6, "Group.sendsSinceLeafRotation",
                "MlsConversationRecord", "MlsConversationRecordTest"),
        // THREE SITES EACH BECAME ONE OR TWO, and no door closed — Stage 6. Both windows
        // moved to the engine and MlsPeerGuard grew a one-argument claim, so the three transport
        // sites (the production gate, debugProbeDurableCooldowns' identical claim, and that probe
        // printing the window it used) now name a method rather than the number. The DOORS are
        // unchanged and still three each: what this count follows is where the threshold is spelled,
        // and after the move that is MlsPeerGuard's overload plus its accessor for the probe's line.
        // The probe is still a REAL door — it calls the same claim, stamps the same record, and
        // genuinely suppresses the next real operation — which is why it kept its own accessor
        // rather than being given a private copy of the window.
        durableRecord("MlsWindowBudget", "REBUILD_EPISODE_MS", 1,
                "the rebuild-episode stamp, MlsPeerGuard K_EPISODE/<conversation key>",
                "MlsCooldownRecord", "MlsCooldownRecordTest"),
        durableRecord("MlsReestablishPolicy", "REESTABLISH_COOLDOWN_MS", 1,
                "the re-establish stamp, MlsPeerGuard K_REESTABLISH/<peer>",
                "MlsCooldownRecord", "MlsCooldownRecordTest"),

        // ---- in memory ON PURPOSE, with the reason recorded HERE --------------------------------
        // Stage 6 moved these three into MlsRecoveryPolicy — the class that already owned
        // gateExpired, i.e. the decision each of them is the threshold for. The counters they bound
        // did not move and neither did their durability answers; what changed is that the
        // comparison is now a host-tested predicate rather than an inline `>=` in a method that
        // also performs an Android effect.
        transientDeclared("MlsRecoveryPolicy", "UNCONVERGED_BEFORE_REBUILD", 2,
                "ConvState.consecutiveUnconverged",
                "it drives TOWARD a repair, so a restart makes us MORE patient. Losing it costs a "
                + "few more messages before the rebuild, and can never buy an extra attempt: what "
                + "must survive is the RATE BOUND on the repair itself, and both of those are "
                + "durable (G2's era budget and MlsRebuildLimiter's window). The direction "
                + "argument, on the counter that carries it"),
        transientDeclared("MlsSelfHealPass", "PARKS_BEFORE_UNSTICKING_A_HEAL", 1,
                "ConvState.consecutiveParksAtSameMoment",
                "same direction. Reaching it KILLS a stuck self-heal, which is a purely local act "
                + "that lets the honest FTD path run — so a restart resetting it defers that, never "
                + "repeats it. Nothing peer-facing is downstream of the counter itself; the FTD and "
                + "the rebuild past it have their own durable bounds. And the moment it is counted "
                + "against (lastParkGroupMoment) plus the parked messages themselves die with the "
                + "same process, so a persisted count would be a run of parks at a moment nothing "
                + "remembers"),
        transientDeclared("MlsRecoveryPolicy", "GATE_DEADLINE_MS", 1, "ConvState.gateOpenedAt",
                "the counter BLOCKS our own sends rather than authorising anything, so losing it "
                + "RELEASES the gate — which is precisely what the deadline itself does on expiry, "
                + "for the reason sendBlockedByGate states: 'a generation the peer ignores is "
                + "strictly better than a conversation that never sends again'. A restart can only "
                + "make us send sooner, and it spends no allowance on anyone. Persisting it would "
                + "make a restart able to hold a conversation shut, which is the outcome this "
                + "deadline exists to prevent"),
        transientDeclared("MlsRecoveryPolicy", "MAX_GATED_RESENDS", 2, "ConvState.gatedResends",
                "the counter IS the collection: it bounds the depth of an in-memory ArrayDeque on "
                + "the same object. A restart empties the queue and the count together, so nothing "
                + "is handed back — there is no allowance to spend against a queue that no longer "
                + "exists. Persisting the bound without persisting the parked resends would bound a "
                + "queue that is not there, which is worse than not bounding it"),
        transientDeclared("MlsMetadataKeysPolicy", "MAX_PENDING_SUBJECT", 1,
                "ConvState.pendingSubject across mConv",
                "the same shape one level up: conversationsHoldingASubject() counts the mConv "
                + "entries holding an in-memory byte[], and a restart drops every one of them, so "
                + "the count and the thing counted die together. What it bounds is app-level "
                + "metadata whose key may never arrive — dropping one costs a subject line, and "
                + "nothing escalates on overflow"),
        // TWO SITES, AND ONLY ONE IS A DOOR. The gate is mayHoldAnotherIcon's comparison; the
        // second site is MlsProviderTransport printing the budget in the refusal log line, which
        // reads the value and decides nothing. Recorded rather than removed because a refusal that
        // does not say what it refused against is the harder one to diagnose — but named here so
        // the next reader is not hunting for a second gate that does not exist.
        transientDeclared("MlsMetadataKeysPolicy", "MAX_PENDING_ICON_BYTES", 2,
                "ConvState.pendingIcon across mConv, summed in BYTES",
                "the icon twin of MAX_PENDING_SUBJECT and transient for the same reason — "
                + "bytesHoldingAnIcon() sums in-memory byte[]s that a restart drops along with the "
                + "count. What differs is the UNIT, and that is the whole reason it is a separate "
                + "constant rather than a second user of the subject's: a held subject is dozens of "
                + "bytes so a holder count bounds the memory implicitly, while an icon is four or "
                + "five orders of magnitude larger and eight holders would be megabytes a count "
                + "cannot see. Overflow drops the NEWCOMER rather than evicting a hold, because the "
                + "older one is likelier to be about to open, and nothing escalates either way"),

        // ---- not a gate ------------------------------------------------------------------------
        notAGate("MlsRekeyPolicy", "REKEY_REFUSED_BACKOFF_SENDS", 1,
                "an arithmetic offset subtracted from REKEY_AFTER_SENDS to back a refused rekey "
                + "off; it bounds no counter of its own"),
        // Classified NOT_A_GATE in this inventory's first draft and moved by
        // nothingClassifiedNotAGateIsUsedAtAGateSite, which found it being compared. It IS a gate;
        // what makes it transient on purpose is that the counter is a per-pass local while the
        // position that must survive the pass is durable.
        new Row("MlsMaintenancePolicy", "SWEEP_PAGE", 1, Durability.TRANSIENT_DECLARED,
                "the per-pass local `maintained`",
                "a page SIZE, so the counter is born and dies inside one maintenance pass. What "
                + "must survive a restart is the POSITION, and PREF_SWEEP_CURSOR is durable — a "
                + "restart re-reads the cursor and continues, it does not re-sweep from zero", ""),
        notAGate("MlsPeerGuard", "HOUR_MS", 2,
                "a window LENGTH handed to MlsEraBudgetRecord.countWithin; the counter it windows "
                + "is the durable record"),
        notAGate("MlsPeerGuard", "DAY_MS", 4, "ditto"),
        notAGate("MlsWindowBudget", "REBUILD_WINDOW_MS", 1,
                "a window LENGTH handed to MlsRebuildWindowRecord.rolled"),
        notAGate("MlsWindowBudget", "EXTERNAL_COMMIT_WINDOW_MS", 1, "ditto"),

        // ---- already reading its tunable from MlsConfig (Stage 6's destination) -----------------
        durableRecord(TRANSPORT, "mCfg.selfHealRetryLimit", 1,
                "MlsConversationRecord.selfHealBudget.retryCount", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        durableRecord(TRANSPORT, "mCfg.selfHealWindowMs", 4,
                "MlsConversationRecord.selfHealBudget window start", "MlsConversationRecord",
                "MlsConversationRecordTest"),
        // WAS notAGate, AND THE ROW SAID "a WIRE extension type" WHILE THE VERDICT SAID NOT_A_GATE.
        // It is compared — `ext.type == mCfg.metadataKeysExtType` — so under the structural rule it
        // IS at a gate site, and nothingClassifiedNotAGateIsUsedAtAGateSite only stayed green
        // because that check skipped every mCfg. row wholesale. An exemption that exists to spare
        // one correct row is an exemption that spares every wrong one beside it: the skip is gone
        // and the row now carries the verdict its own reason had been arguing for.
        // An ABSENCE MARKER, not a bound: it pre-fills contract v60's outcome sink so
        // that "the provider did not say" is distinguishable from OUTCOME_SERVED (which is 0, the
        // value an int[] already has). WIRE rather than NOT_A_GATE because the value is fixed by
        // RcsMlsClaimResult's 0..5 space and not chosen by us — if the contract grows a sixth
        // outcome this has to move out of its way — and because its one comparison is an EQUALITY
        // against that space, which is what separates a wire value from a threshold.
        // RccMlsBody's two framing constants. Both are compared for EQUALITY -- a version byte
        // and a payload-type discriminator -- so they are WIRE and not NOT_A_GATE, which is the
        // distinction wire()'s own javadoc exists to draw. Neither bounds a counter.
        //
        // They surfaced when this guard was restored and could reach its subject again: while its
        // instrument lived in another repo the class threw before this assertion ran, so the two
        // were unclassified the whole time and nothing said so. That is the failure mode the
        // enumeration is for -- a threshold nobody asked about -- caught by the guard rather than
        // by a reader.
        wire("RccMlsBody", "HEADER_VERSION_1", 1,
                "the RCC.16 secret-payload framing: the 2-byte header version this parser accepts"),
        wire("RccMlsBody", "SECRET_PAYLOAD_TYPE_TEXT", 1,
                "the RCC.16 secret-payload framing: the payload-type discriminator for text"),
        wire(TRANSPORT, "NO_CLAIM_OUTCOME", 2,
                "RcsMlsClaimResult's OUTCOME_* value space, via the AIDL contract"),
        wire(TRANSPORT, "mCfg.metadataKeysExtType", 1,
                "RCC.16's metadata-keys GroupContext extension TYPE. The value is the spec's; we "
                + "compare it for equality to recognise the extension, which is a protocol check "
                + "and not a bound on anything we count"),
        // Stage 6's two new config gates. IDENTITY_REFRESH_MS became a knob because verifying the
        // refresh otherwise costs a week of wall clock; MAX_FTD_ATTEMPTS already had one and it was
        // a SystemProperties read inside the flush, which is the shape that made the method
        // unreachable from a host test.
        durablePref(TRANSPORT, "mCfg.identityRefreshMs", 1, "the last identity refresh",
                "MlsProviderTransport.PREF_LAST_IDENTITY_REFRESH"),
        // ITS FAILURE-SIDE SIBLING. The weekly window above records SUCCESSES, so it
        // could not rate-limit a call that always fails — on a device with no adopted identity the
        // stamp was never written, the guard never engaged, and every sealCapability made a fresh
        // binder round-trip. This bounds the FAILURE retry and is deliberately a SEPARATE key:
        // onIdentityChanged clears the success stamp to force an immediate re-read, and stamping
        // that same pref on attempt would have let the next call re-write what had just been
        // cleared, stranding the identity for a week.
        durablePref(TRANSPORT, "mCfg.identityRetryBackoffMs", 1, "the last FAILED identity attempt",
                "MlsProviderTransport.PREF_LAST_IDENTITY_ATTEMPT"),
        durableRecord(TRANSPORT, "mCfg.ftdMaxAttempts", 1, "MlsConversationRecord.ftdResendCounts",
                "MlsConversationRecord", "MlsConversationRecordTest"),
        // RCC.16's REMAINING-LIFETIME FLOOR. It reached a gate site for the
        // first time when the credential-floor report started measuring the server's own validation
        // skew against it — before that its only reader assigned it to a local first, so this guard
        // could not see the door it already had.
        //
        // WIRE and not a threshold, and the distinction is exactly the one wire() exists for: the
        // number is the SPECIFICATION's (A.4.1.2 / A.4.1.3 / A.4.2.2 / A.4.3.1 §1(a) all state 30
        // days), it bounds nothing WE count — a certificate's lifetime is issued by the KDS — and
        // the SERVER enforces the same value, device-measured at exactly 2,592,000 s of validation
        // skew on two independent samples (010T, 2026-09-10). A durability question about it has no
        // subject: there is no counter to lose across a restart.
        // 10 -> 11: dumpKeyPackageCount now DATES every claimed package on both clocks
        // and marks one whose certificate is inside the floor. A report site, not a new gate — the
        // probe stays UNREFUSABLE by design and still does not call keyPackageUsable, which
        // is what the ordering experiment depends on.
        //
        // ⚠ AND THIS NUMBER IS NOT CHECKED BY ANYTHING, which a reader should know before quoting
        // it. everyThresholdHasExactlyTheSitesItIsRecordedWith opens with
        //     if (r.constant.startsWith("mCfg.")) continue;
        // so every mCfg. row's site count is maintained BY HAND and no test can contradict it. It
        // is a recorded number that reads as measured
        // and cannot go red. It is left as documentation rather than deleted because a wrong count
        // is still a lead, and left UNGUARDED rather than newly guarded because the skip is load-
        // bearing for the row below it and unpicking it is not this change's job. Whoever adds a
        // site: update it, and do not read a green suite as confirmation that you did.
        // 11 -> 14, and the THREE new sites are worth naming because this count is
        // unguarded: floorRebuild and maybeFloorRebuild (the wedged-group repair reports the floor
        // it judged against) and claimRosterForAdvance (the era advance's per-member certificate
        // pre-flight). Counted by walking the file rather than by reading the previous number:
        //   reportCredentialRefusal 3, logFloorReport 3, membershipChangeAllowedByFloor 2,
        //   dumpKeyPackageCount 1, rosterFloorReport 1, keyPackageUsable 1,
        //   floorRebuild 1, maybeFloorRebuild 1, claimRosterForAdvance 1.
        // The suite was GREEN at 11 with 14 sites in the tree — exactly what the note above warns
        // about, and the reason kp-floor flagged it by hand rather than by test.
        wire(TRANSPORT, "mCfg.kpMinRemainingDays", 14,
                "RCC.16 v4.0 — A.4.1.2/A.4.1.3 (the client tier), A.4.2.2 (the KDS must not return "
                + "a KeyPackage inside it) and A.4.3.1 §1(a) (the RCS SPN, on every Commit). The "
                + "sysprop exists to test either side of the line, not to choose the number"),

        // ---- the rest of the PROVIDER MLS LAYER --------------------------------------
        //
        // Everything above this line was reachable from the transport, a guard class, a policy
        // behind a guard class or an INVENTORY row. Fourteen constants were in none of those, and
        // a tunable declared beside any of them was seen by no DoD row at all — measured, not
        // argued: `private static final long PEER_REJOIN_GRACE_MS = 90_000L;` in
        // MlsCiphertextCache.java left DoD-1 and DoD-3 GREEN, and so did the same constant WIRED
        // INTO A GATE in the same file. The same injection one file over in MlsPeerGuard goes red,
        // which is the whole finding: the boundary was the guard set, not the property.
        //
        // Read them as the answer to "what did the widening cost". Most are NOT_A_GATE or WIRE, and
        // "most are probably not gates" is the sentence this inventory exists to replace.

        // A gate on a store's own SharedPreferences entry count. The counter IS the file, so it is
        // durable by construction — and these three are the reason the widening was worth its
        // price: each bounds a store the transport writes on the send path, each is compared with
        // >=, and none of the three had ever been enumerated by anything.
        durablePref("MlsCiphertextCache", "MAX_ENTRIES", 3,
                "the sealed-ciphertext store's own entry count, p.getAll().size()",
                "MlsCiphertextCache.PREFS"),
        durablePref("MlsPendingBodyStore", "MAX_ENTRIES", 4,
                "the pending-body store's own entry count, p.getAll().size()",
                "MlsPendingBodyStore.PREFS"),
        durablePref("MlsRendezvousStore", "MAX_ROWS", 2,
                "the rendezvous store's own row count, p.getAll().size()",
                "MlsRendezvousStore.PREFS"),

        // ---- fixed outside us ---------------------------------------------------------------
        wire("MlsCarrierTransport", "ERA_INITIAL", 3,
                "RCC.16 §7.11.1.1: the Era ordinal of a fresh group is 1. The same value the "
                + "transport declares under the same name and the tool's WIRE_CONSTANTS exempts "
                + "there — this row exists because that exemption is now scoped to the transport, "
                + "so a policy tunable cannot inherit a wire name's pass by being declared next to "
                + "one"),
        wire("MlsEnrollDebugReceiver", "ERA", 1,
                "the RCC.16 Era GroupContext extension TYPE, 0xF001 — the transport's ERA_EXT_TYPE "
                + "under another name, handed to createGroup as the extension to write"),
        wire("MlsSelfTestReceiver", "ERA", 1, "ditto, on the self-test path"),
        wire("RcsE2eeScheme", "MLS_CIPHERSUITE_P256_AES128", 1,
                "the MLS ciphersuite identifier. Equality-compared twice in the transport against "
                + "info.cipherSuite, which arrives in a peer's KeyPackage — the peer picks the "
                + "number and RFC 9420 §7.2 makes the comparison mandatory"),
        wire("MlsCredential", "GOOGLE_VENDOR_ID", 5,
                "the vendorId written into the GSMA participant-info certificate extension "
                + "(2.23.146.2.1.4) and into the TBS the KDS signs. A value the verifier reads, "
                + "not one we choose"),
        // THE TWO WHOSE AUTHORITY THIS TREE CANNOT RESOLVE, said plainly rather than asserted. Both
        // javadocs cite an RCC.16 clause (A.1.5 caps a root at 3652 d, A.2.5 an ICA at 1827 d) and
        // the ICA one records a device-found validator refusal at 3650 d > 1827 d. NEITHER
        // VALIDATOR IS IN THIS TREE — it is in the Rust core — so the citation is the evidence and
        // the citation is a javadoc. Recorded as WIRE because the number is a spec ceiling we sit
        // under, and recorded HERE as unverifiable from source so the next reader does not take the
        // verdict for a measurement.
        wire("MlsCredential", "ROOT_LIFETIME_S", 1,
                "RCC.16 A.1.5's root-CA lifetime cap. NOT machine-checkable in this tree: the "
                + "validator that enforces it is in the Rust core, so this row rests on the spec "
                + "citation in the constant's own javadoc"),
        wire("MlsCredential", "ICA_LIFETIME_S", 1,
                "RCC.16 A.2.5's intermediate-CA lifetime cap, and the same caveat — 1825 rather "
                + "than the permitted 1827 to keep the 1-hour backdate inside the allowance, which "
                + "a device run found and this tree cannot re-derive"),

        // ---- bounds nothing we count -----------------------------------------------------------
        notAGate("RcsKdsClient", "TIMEOUT_MS", 2,
                "a socket timeout handed to HttpURLConnection.setConnectTimeout/setReadTimeout. "
                + "The clock it bounds begins and ends inside one call, so there is no counter to "
                + "survive a restart — a process that dies mid-request loses the request too"),
        notAGate("MlsCarrierTransport", "KP_POOL", 2,
                "a generation COUNT handed to generateKeyPackages(n) — how many KeyPackages one "
                + "call mints, not a bound on how many exist. The pool's actual replenishment gate "
                + "is MlsKeyPackagePolicy.KP_REPLENISH_AT, which has its own row above"),
        notAGate("MlsCredential", "LEAF_LIFETIME_S", 2,
                "an X.509 notAfter offset. The SPEC bounds it — RCC.16 A.4.1.2/A.4.2.2 refuse a "
                + "KeyPackage with under 30 days left, which MlsSession states at its own claim-time "
                + "check — but 60 days inside that window is ours, and it gates no counter: the "
                + "certificate carries its own expiry and the certificate is what persists"),
        notAGate("MlsStalledNotifier", "ID_BASE", 1,
                "a notification-id NAMESPACE, OR'd with a conversation-key hash so this class's "
                + "notifications cannot collide with another feature's. An identifier, not a bound"),

        // ---- THREE THE WIDENING FOUND THAT NOTHING HAD EVER ENUMERATED --------------------------
        //
        // Not part of the fourteen originally priced. Two came in through term (b) of
        // providerLayerOwners — a PROVIDER class outside e2ee that imports the engine — and the
        // third came from scoping the wire-name exemption to the transport. Both have the same
        // shape: a subject that
        // stopped somewhere, and a tunable on the other side of where it stopped.

        // RcsCallbackRouter is the provider callback surface. It is not in e2ee, it is not a guard
        // class, nothing delegates to it, and it declares two tunables that gate the MLS inbound
        // path. The e2ee directory walk alone would have missed both.
        transientDeclared("RcsCallbackRouter", "NEGATIVE_DELIVERY_DEDUPE_MS", 1,
                "RcsCallbackRouter.mNegativeDeliverySeen, an in-memory LinkedHashMap capped at 512",
                "the same DIRECTIONAL rule the rows above use, and it is worth stating carefully "
                + "because what this gate protects is expensive: the remedy behind it is "
                + "onPeerReportedFailure, the §10.3 resend, and a duplicate one on 2026-08-15 made "
                + "a working resend look like a failing one and escalated to an era advance that "
                + "tore down a healthy conversation. What a restart hands back is at "
                + "most ONE extra remedy, and the duplicate this suppresses is the provider "
                + "dispatching the same report twice on two threads MILLISECONDS apart — a process "
                + "that dies between them loses both dispatches rather than duplicating one. Every "
                + "scarce thing downstream is separately and DURABLY bounded: the repeat-resend "
                + "count is MlsResendLedger's SQL rows, its escalation is MlsFtdEscalation."
                + "ESCALATE_AT over those rows, and the era advance the incident reached is "
                + "refused by MlsPeerGuard's durable era budget. So persisting this would buy "
                + "nothing the era budget does not already refuse. NOT verified on device: that "
                + "the provider cannot re-dispatch a report across our process restart"),
        notAGate("RcsCallbackRouter", "JOIN_RACE_WINDOW_MS", 1,
                "a bounded WAIT handed to awaitJoinAndRetryDecrypt — how long a failed decrypt "
                + "waits for an in-flight join before it becomes an FTD (observed race 19 ms). It "
                + "bounds no counter. It IS an invariant-I1 subject rather than an I3 one: the "
                + "question for a bounded wait is whether its bound is reachable inside the budget "
                + "that charges its iterations, and this one is charged by nothing — it delays a "
                + "report on a message that has already failed"),

        // AND ONE IN THE ENGINE, unclassified for a reason that had nothing to do with it: the
        // name ERA_INITIAL is on the tool's WIRE_CONSTANTS, the exemption was matched by NAME
        // across every owner, so an engine policy class inherited the transport's pass. Scoping
        // that exemption to the transport is what exposed it.
        wire("MlsReestablishPolicy", "ERA_INITIAL", 2,
                "RCC.16's initial Era ordinal, mirroring the transport's constant of the same "
                + "name. serverEra >= ERA_INITIAL asks whether the server reported an era at or "
                + "above the first legal one, i.e. whether the server holds this conversation at "
                + "all — a protocol validity test, not a bound on anything we count. It is also "
                + "the COUNTEREXAMPLE nothingClassifiedWireIsOrderCompared is pinned against. "
                + "1 → 2: forkLine names the era a carry-less re-establish would be "
                + "BORN at, which is this same ordinal used as a LABEL in a log line rather than "
                + "as a test — no comparison, no counter, so the durability answer is unchanged. "
                + "The DECISION beside it (forksAtEraInitial) deliberately does not read the "
                + "constant at all: it asks the Verdict, so a second order-comparison against this "
                + "ordinal was not added and the pin above still describes the only one"),
    };

    /**
     * A constant the layer READS but does not DECLARE — term (c).
     *
     * <p>The two terms of {@link #providerLayerOwners} both enumerate by DECLARATION, and a control
     * measured what that leaves open: a tunable declared in a shared provider class OUTSIDE the
     * layer (a constants file, an app action) and read at a gate INSIDE it is declared where
     * neither term looks. So the third enumeration keys on the USE rather than on the declaration —
     * a qualified {@code Class.SHOUTING_NAME} reference in an owner's code, where the class is
     * provider-side and really does declare that name as a {@code static final} scalar.
     *
     * <p>The GRANULARITY is the constant, not the class, and deliberately: making
     * {@code ParticipantData} an owner would drag every unrelated app constant it declares into
     * DoD-3's inventory, which is how a widened guard becomes a chore. What the layer reads is what
     * the layer must answer for.
     *
     * <p><b>A constant declared out there and never read in here stays invisible, and that is
     * correct rather than blind</b> — it gates nothing. Measured: injecting one into
     * {@code RcsConstants} with no use site leaves this green; giving it a use site inside the layer
     * turns it red.
     */
    private static final class OutsideRef {
        final String owner;
        final String constant;
        final int refs;              // qualified references from INSIDE the layer
        final Durability durability;
        final String reason;

        OutsideRef(final String owner, final String constant, final int refs,
                final Durability durability, final String reason) {
            this.owner = owner;
            this.constant = constant;
            this.refs = refs;
            this.durability = durability;
            this.reason = reason;
        }

        String key() { return owner + "." + constant; }
    }

    /** Measured 2026-09-09. Six, and every one of them is an identifier or a query bound. */
    private static final OutsideRef[] OUTSIDE_LAYER_REFERENCES = {
        new OutsideRef("BugleNotifications", "UPDATE_ALL", 1, Durability.NOT_A_GATE,
                "a bitmask of which notification surfaces to refresh, handed to "
                + "BugleNotifications.update. A flag word, not a bound"),
        new OutsideRef("ParticipantData", "DEFAULT_SELF_SUB_ID", 1, Durability.NOT_A_GATE,
                "the sentinel sub id meaning 'the default SIM'. An identifier"),
        new OutsideRef("RcsIosTapback", "SEARCH_LIMIT", 1, Durability.NOT_A_GATE,
                "how many recent rows the tapback matcher scans backwards. It bounds one query's "
                + "result set, which is born and dies inside that query — nothing counts across "
                + "calls, so there is no counter to survive a restart"),
        new OutsideRef("UpdateRcsMessageStatusAction", "KIND_GROUP_IMDN", 1, Durability.WIRE,
                "a message-kind discriminator on the action's own contract, chosen by that class "
                + "and read by us. Not a value we may vary"),
        new OutsideRef("UpdateRcsMessageStatusAction", "KIND_IMDN", 1, Durability.WIRE, "ditto"),
        new OutsideRef("UpdateRcsMessageStatusAction", "KIND_STATUS", 1, Durability.WIRE, "ditto"),
    };

    /**
     * Every constant the layer reads from outside itself is classified, and its door count pinned.
     *
     * <p>Per item and in both directions: an unclassified one fails, a row whose constant the layer
     * has stopped reading fails, and a row that gains a reference fails — a second door onto a bound
     * declared somewhere DoD-3 does not own is exactly the second-door defect.
     */
    @Test
    public void everyConstantTheLayerReadsFromOutsideItIsClassified() throws IOException {
        final Map<String, Integer> found = constantsReadFromOutsideTheLayer();
        assertTrue("the layer reads NO static-final scalar from any provider class outside it, and "
                + "it reads six. The qualified-reference scan has gone stale and term (c) is "
                + "silently enumerating nothing.", found.size() >= 4);

        final Map<String, Integer> rows = new LinkedHashMap<>();
        for (final OutsideRef r : OUTSIDE_LAYER_REFERENCES) rows.put(r.key(), Integer.valueOf(r.refs));

        final List<String> unclassified = new ArrayList<>();
        final List<String> moved = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : found.entrySet()) {
            final Integer pinned = rows.get(e.getKey());
            if (pinned == null) {
                unclassified.add(e.getKey() + " (" + e.getValue() + " references from the layer)");
            } else if (!pinned.equals(e.getValue())) {
                moved.add(e.getKey() + ": " + pinned + " → " + e.getValue());
            }
        }
        final List<String> stale = new ArrayList<>();
        for (final String k : rows.keySet()) {
            if (!found.containsKey(k)) stale.add(k);
        }
        if (!unclassified.isEmpty()) {
            fail("The MLS provider layer reads these constants from classes OUTSIDE it, and none "
                    + "has a row: " + unclassified + ". Say which counter each bounds and whether "
                    + "that counter survives a restart — a tunable is not out of scope because it "
                    + "is declared in somebody else's file.");
        }
        if (!moved.isEmpty()) {
            fail("These outside-the-layer constants gained or lost doors into the layer: " + moved
                    + ". A new reference is a new gate on a bound this guard does not own the "
                    + "declaration of; re-answer it and update the pin in the same commit.");
        }
        if (!stale.isEmpty()) {
            fail("OUTSIDE_LAYER_REFERENCES rows for constants the layer no longer reads: " + stale
                    + ". Delete the row.");
        }
    }

    /**
     * {@code Class.CONSTANT -> how many qualified references the layer makes}, for provider classes
     * that are NOT themselves owners.
     */
    private static Map<String, Integer> constantsReadFromOutsideTheLayer() throws IOException {
        final Set<String> owners = new HashSet<>(providerLayerOwners());
        final Pattern ref = Pattern.compile("\\b([A-Z]\\w*)\\.([A-Z][A-Z0-9_]{2,})\\b");
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final String owner : providerLayerOwners()) {
            final Matcher m = ref.matcher(codeOf(owner));
            while (m.find()) {
                final String cls = m.group(1);
                final String member = m.group(2);
                if (owners.contains(cls)) continue;                 // already a declaration subject
                if (SourceScan.engineClass(cls) != null) continue;   // engine, and host-tested
                final String src = SourceScan.providerClassSource(cls);
                if (src == null) continue;                          // JDK, android, or a nested type
                if (!SourceScan.scalarConstantsDeclaredIn(src).contains(member)) continue;
                final String key = cls + "." + member;
                final Integer n = out.get(key);
                out.put(key, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            }
        }
        return out;
    }

    /**
     * The capacity bounds written as a bare literal rather than as a named constant.
     *
     * <p>One today, and it is the boundary case the class javadoc measures. It is NOT reachable by
     * any of the four enumerations, so it needs its own — see
     * {@link #everyEvictionCapInTheTransportIsClassified}, which finds them by the JDK contract they
     * override rather than by anything this file spells.
     */
    private static final class LiteralCap {
        final int value;
        final String counter;
        final String reason;

        LiteralCap(final int value, final String counter, final String reason) {
            this.value = value;
            this.counter = counter;
            this.reason = reason;
        }
    }

    private static final LiteralCap[] LITERAL_CAPS = {
        new LiteralCap(256, "ConvState.heardAtSeq — the advancer-liveness ledger",
                "TRANSIENT_DECLARED, and it must stay that way: an empty ledger "
                + "classifies every member UNKNOWN and draws the unchanged base budget, so losing "
                + "it across a restart makes us MORE patient and a restart can never buy an early "
                + "takeover. The cap itself bounds MEMORY, not an operation — the keys come off the "
                + "wire (fromE164 on an inbound the server routed to us), so the map is not bounded "
                + "by the roster. 256 is far above any RCS group size, so eviction is unreachable "
                + "for a real conversation; if it ever did evict, the evicted member reads as "
                + "NEVER_HEARD and draws a SHORTER wait, which is why the cap is set where a real "
                + "group cannot reach it rather than tuned tight"),
    };

    /**
     * <b>DoD-3 is met at 0, and Stage 5 got it there.</b> Measured 2026-09-09.
     *
     * <p>Of the eight: one was never transient at all and this inventory said it was
     * ({@code REKEY_AFTER_SENDS} — see its row), two went durable, five carry a declared reason.
     * The rule stays visible in the code rather than in a plan, because the next counter added at a
     * gate site fails {@link #everyThresholdInTheSubsystemIsClassified} until somebody answers it,
     * and this number going UP is the regression.
     */
    private static final int TRANSIENT_UNDECLARED_TODAY = 0;

    // ---- the checks ----------------------------------------------------------------------------

    /** Every enumeration this class rests on must find something. */
    @Test
    public void theSubjectsOfThisGuardAreAllNonEmpty() throws IOException {
        // 8 since Stage 6 emptied the transport of policy constants: what is left is the
        // wire/ABI list, which DoD-1's everyWireExemptionIsStillDeclaredHere pins name by name.
        assertTrue("no constants declared in " + TRANSPORT + " — the declaration shape changed",
                declaredIn(TRANSPORT).size() >= 8);
        final List<String> guards = guardClasses();
        assertTrue("no guard classes parsed out of " + TOOL + "'s GUARD regex — DoD-3 would then "
                + "silently stop watching MlsPeerGuard, MlsRebuildLimiter and "
                + "MlsExternalCommitBudget, which are the three classes this work is named after",
                guards.size() >= 3);
        for (final String g : guards) {
            if (!declaredIn(g).isEmpty()) continue;
            // A STORE ADAPTER declares no thresholds of its own, and that is the shape Stage 3
            // put MlsRebuildLimiter and MlsExternalCommitBudget into deliberately. It is only
            // acceptable while the bound is inventoried SOMEWHERE, so the class must name an engine
            // policy that declares one — derived from the source rather than from a list here, so
            // an adapter that stops delegating and starts deciding again fails this.
            final List<String> behind = enginePoliciesBehind(g);
            assertFalse(g + " declares no threshold and names no engine policy that declares one — "
                    + "either it stopped bounding anything, or its bound moved somewhere DoD-3 is "
                    + "not watching, or the locator is wrong. All three must fail.",
                    behind.isEmpty());
        }
        assertTrue("MlsConfig exposes no scalar fields by reflection — the Stage 6 destination is "
                + "invisible to this guard", configFields().size() >= 10);
        assertTrue("no engine class declares a boolean method — rule (2) is dead and four "
                + "thresholds would silently read as 'not a gate'", booleanMethodNames().size() > 20);
        // The two terms of the provider layer, each with its own floor, because a union
        // whose halves are only checked together lets one of them go to zero unnoticed.
        assertTrue("no .java found under " + PROVIDER_MLS_DIR + " — the provider half of this "
                + "guard's subject is empty and a tunable declared anywhere in the layer is "
                + "invisible again",
                SourceScan.javaSourcesUnder(PROVIDER_MLS_DIR).size() >= 30);
        assertTrue("no provider source imports com.android.messaging.rcs.engine.mls — term (b) of "
                + "providerLayerOwners finds nothing, so a class that leaves e2ee leaves this "
                + "guard's subject with it",
                providerClassesImportingTheEngine().size() >= 20);
        // And the union must be bigger than the transport-plus-guards set it replaces, or the
        // widening silently did not happen.
        assertTrue("the provider layer enumerates fewer than 30 classes — allOwners() has fallen "
                + "back to the guard set and the layer-wide hole is open again",
                providerLayerOwners().size() >= 30);
    }

    /**
     * Every threshold declared in the transport or in a guard class has a row here.
     *
     * <p><b>This is DoD-3.</b> A threshold with no row is a bound whose counter nobody has asked
     * about — which is the exact state G2 and G4 were found in, and the state the plan's
     * §3.5 found three more in after those two were fixed.
     */
    @Test
    public void everyThresholdInTheSubsystemIsClassified() throws IOException {
        final Set<String> known = new HashSet<>();
        for (final Row r : INVENTORY) known.add(r.key());

        // The eight wire/ABI constants are not thresholds and are exempt for the same reason
        // DoD-1 exempts them — and from the SAME checked-in list, so the two halves of this work
        // cannot disagree about which constants are policy.
        final Set<String> wire = new HashSet<>(
                MlsTransportPolicyConstantGuardTest.wireConstants());
        assertTrue("WIRE_CONSTANTS is empty or unparseable in " + TOOL + " — every wire value "
                + "would read as an unclassified threshold", wire.size() >= 8);

        // ZERO HITS MUST FAIL. allOwners() is a CHECKED-IN list of class names, so it is
        // never empty and asserting on it proves nothing — the corpus this test actually examines is
        // the constants those classes DECLARE, which a stale scan returns none of. Measured: with the
        // sources emptied, the first form of this assertion still passed.
        int scannedConstants = 0;
        for (final String owner : allOwners()) scannedConstants += declaredIn(owner).size();
        assertTrue("ZERO HITS MUST FAIL: the owners declare NO constants between them, "
                        + "so the classification loop below never runs and this assertion certifies "
                        + "green having examined nothing. A zero-match scan is a broken scan, not a "
                        + "clean tree.",
                scannedConstants > 0);
        final List<String> unclassified = new ArrayList<>();
        for (final String owner : allOwners()) {
            for (final String c : declaredIn(owner)) {
                // SCOPED TO THE TRANSPORT. WIRE_CONSTANTS is the transport's exemption
                // list and it is matched by NAME, so before this a tunable called ERA_INITIAL in
                // any other class inherited a pass nobody granted it. MlsCarrierTransport really
                // does declare that name, and it now carries its own WIRE row instead.
                if (TRANSPORT.equals(owner) && wire.contains(c)) continue;
                // A pure alias of another class's constant is not a declaration of a value; the
                // durability question belongs where the value is declared, and
                // everyAliasPointsAtAConstantThatResolves keeps this skip honest.
                if (isResolvedAlias(owner, c)) continue;
                if (!known.contains(owner + "." + c)) unclassified.add(owner + "." + c);
            }
        }
        for (final String field : configFieldsAtAGateSite()) {
            if (!known.contains(TRANSPORT + ".mCfg." + field)) {
                unclassified.add(TRANSPORT + ".mCfg." + field);
            }
        }
        Collections.sort(unclassified);
        if (!unclassified.isEmpty()) {
            fail("These thresholds have no row in this test's INVENTORY: " + unclassified
                    + ". Add one saying which counter each bounds and whether that counter is "
                    + "DURABLE_RECORD / DURABLE_PREF / TRANSIENT_DECLARED / TRANSIENT_UNDECLARED / "
                    + "NOT_A_GATE. A bound whose counter nobody asked about is the original defect, "
                    + "and enumerating it is invariant I3(b).");
        }
    }

    /**
     * <b>Every policy constant the tool names is classified WHEREVER IT NOW LIVES</b> —
     * Stage 6.
     *
     * <p>The enumerations above start from an OWNER and ask what it declares. This one starts from
     * the checked-in {@code POLICY_CONSTANTS} list — DoD-1's ground truth, which does not shrink
     * when a constant moves — and asks where each name is declared today, searching both the engine
     * package and the provider's {@code e2ee} package. That is the direction Stage 6 breaks: a
     * threshold moved out of the transport into a class no other term enumerates would leave DoD-3's
     * subject while every other assertion in this file stayed green.
     *
     * <p>A name that is declared NOWHERE needs no row, and that is not a loophole: DoD-1's
     * {@code noPolicyConstantThatHasLeftIsStillReferencedHere} independently requires such a
     * constant to have zero references in the transport, so it cannot have been replaced in place by
     * a literal. {@code PEER_FTD_ESCALATE_AT} is the case — a later change deleted it and gave
     * {@link MlsFtdEscalation#ESCALATE_AT} its own value, and requiring a row for a name nothing
     * declares would be an inventory of the past.
     */
    @Test
    public void everyPolicyConstantTheToolNamesHasARow() throws IOException {
        final List<String> policy = MlsTransportPolicyConstantGuardTest.policyConstants();
        assertTrue("POLICY_CONSTANTS is empty or unparseable in " + TOOL + " — this check would "
                + "then walk nothing and pass", policy.size() >= 15);

        final Map<String, String> rows = new LinkedHashMap<>();
        for (final Row r : INVENTORY) {
            if (!r.constant.startsWith("mCfg.")) rows.put(r.constant, r.owner);
        }

        final List<String> missing = new ArrayList<>();
        final List<String> misfiled = new ArrayList<>();
        int located = 0;
        for (final String c : policy) {
            final String owner = declaringOwnerOf(c);
            if (owner == null) continue;              // deleted outright; see the javadoc
            located++;
            final String rowOwner = rows.get(c);
            if (rowOwner == null) {
                missing.add(c + " (now declared in " + owner + ")");
            } else if (!rowOwner.equals(owner)) {
                misfiled.add(c + ": row says " + rowOwner + ", declared in " + owner);
            }
        }
        assertTrue("not one of the " + policy.size() + " policy constants " + TOOL + " names is "
                + "declared anywhere under the engine or e2ee packages — the locator is wrong, not "
                + "the code, and this check is passing on an empty set", located > 0);
        if (!missing.isEmpty()) {
            fail("These policy constants have no INVENTORY row: " + missing + ". A threshold that "
                    + "moves out of the transport must be re-classified where it landed — its "
                    + "counter's durability question does not move with it.");
        }
        if (!misfiled.isEmpty()) {
            fail("These INVENTORY rows name the wrong owner: " + misfiled + ". Point the row at the "
                    + "class that declares the constant today, in the same commit as the move.");
        }
    }

    /**
     * The class that declares scalar constant {@code name} today, or {@code null}.
     *
     * <p>Searches the engine MLS package and the provider's {@code e2ee} package — the two places a
     * threshold in this subsystem can live — by the DECLARATION SHAPE, so a constant cannot hide
     * from it by moving between them.
     */
    private static String declaringOwnerOf(final String name) throws IOException {
        for (final String dir : new String[] {ENGINE_DIR, E2EE}) {
            for (final String simple : javaFilesIn(dir)) {
                if (declaredIn(simple).contains(name)) return simple;
            }
        }
        return null;
    }

    /** The simple class names of the {@code .java} files in {@code dir}, or empty. */
    private static List<String> javaFilesIn(final String dir) {
        final List<String> out = new ArrayList<>();
        for (final String c : new String[] {dir, "packages/apps/Messaging/" + dir, "../" + dir}) {
            final File d = new File(c);
            if (!d.isDirectory()) continue;
            final String[] names = d.list();
            if (names == null) continue;
            for (final String n : names) {
                if (n.endsWith(".java")) out.add(n.substring(0, n.length() - ".java".length()));
            }
            Collections.sort(out);
            return out;
        }
        return out;
    }

    /** The other direction: a row for a threshold that is gone excuses nothing and misleads. */
    @Test
    public void everyRowNamesAThresholdThatStillExists() throws IOException {
        final Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (final String owner : allOwners()) declared.put(owner, new HashSet<>(declaredIn(owner)));
        final Set<String> configGates = new HashSet<>(configFieldsAtAGateSite());

        final List<String> stale = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) {
                if (!configGates.contains(r.constant.substring("mCfg.".length()))) {
                    stale.add(r.key() + " (no longer at a gate site in the transport)");
                }
                continue;
            }
            final Set<String> d = declared.get(r.owner);
            if (d == null || !d.contains(r.constant)) stale.add(r.key());
        }
        if (!stale.isEmpty()) {
            fail("INVENTORY rows name thresholds that no longer exist: " + stale
                    + ". Delete the row — a classification for a name that is gone is inherited "
                    + "silently by the next constant to take that name.");
        }
    }

    /**
     * Every threshold's code-site count is pinned individually.
     *
     * <p>A threshold that gains a door is a NEW gate on a counter nobody re-asked the durability
     * question about, and it is the shape this inventory keeps catching: a second door onto
     * the same cost by another route. Per-constant and not per-total, because a total that stays at
     * 39 while one gate loses a site and another gains one is a total that hid one.
     */
    @Test
    public void everyThresholdHasExactlyTheSitesItIsRecordedWith() throws IOException {
        final List<String> moved = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) continue;    // counted by its gate sites, below
            final int n = sitesOf(r);
            if (n != r.sites) moved.add(r.key() + ": " + r.sites + " → " + n);
        }
        if (!moved.isEmpty()) {
            fail("These thresholds gained or lost code reference sites: " + moved
                    + ". A new site is a new door onto the same bound — re-answer the durability "
                    + "question for it, then update the row's site count in the same commit.");
        }
    }

    /**
     * Every {@code DURABLE_RECORD} row's record class and restart test resolve by reflection.
     *
     * <p>Reflection, not a name match: a record class that no longer compiles, or a restart test
     * that was deleted, would otherwise leave the row reading as durable on the strength of a
     * string. That is the document form of a stale needle.
     */
    @Test
    public void everyDurableRecordRowResolvesItsRecordAndItsRestartTest() {
        final List<String> broken = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.DURABLE_RECORD) continue;
            checked++;
            if (SourceScan.engineClass(r.evidence) == null) {
                broken.add(r.key() + " names record " + r.evidence + ", which does not resolve");
            }
            final Class<?> t = SourceScan.engineClass(r.restartTest);
            if (t == null) {
                broken.add(r.key() + " names restart test " + r.restartTest + ", which does not "
                        + "resolve");
            } else if (SourceScan.testMethodCount(t) == 0) {
                broken.add(r.key() + "'s restart test " + r.restartTest + " declares no @Test");
            }
        }
        assertTrue("no DURABLE_RECORD rows — DoD-3's whole durable half has left the inventory",
                checked >= 5);
        if (!broken.isEmpty()) fail("Durability evidence that does not exist: " + broken);
    }

    /**
     * Every {@code DURABLE_PREF} row names a preference key as a DOTTED {@code Class.MEMBER}, and it
     * resolves in exactly that class.
     *
     * <p><b>It used to name a bare key and fall back to the transport, and a negative control caught
     * that being wrong</b>. Renaming {@code MlsCiphertextCache}'s own
     * {@code PREFS} left this GREEN, because {@code MlsProviderTransport} declares a constant of the
     * same name and the fallback found it. A row claiming durability in its own preferences file was
     * being certified by a different class's identically-named constant: the check passed, for the
     * wrong reason, on the exact mutation it exists to catch.
     *
     * <p>So the evidence is a resolvable dotted symbol now — the same discipline that is put on
     * DoD-4's markers and that the two-package locator puts on the catalogue. Three of these rows hold
     * their bound in their OWN preferences file ({@code MlsCiphertextCache}, {@code MlsPendingBodyStore},
     * {@code MlsRendezvousStore}, all from the widening) and the rest in the transport's; both are
     * expressible and neither can borrow the other's name.
     */
    @Test
    public void everyDurablePrefRowNamesAKeyThatResolvesInTheClassItNames() throws IOException {
        final List<String> broken = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.DURABLE_PREF) continue;
            checked++;
            final int dot = r.evidence.lastIndexOf('.');
            if (dot < 0) {
                broken.add(r.key() + " names durability evidence '" + r.evidence + "' with no "
                        + "declaring class. Write it as Class.MEMBER — a bare key is answerable by "
                        + "any class that happens to declare that name.");
                continue;
            }
            final String owner = r.evidence.substring(0, dot);
            final String member = r.evidence.substring(dot + 1);
            final String code;
            try {
                code = codeOf(owner);
            } catch (final IOException e) {
                broken.add(r.key() + " names durability evidence in " + owner + ", which is in "
                        + "neither the engine nor the provider");
                continue;
            }
            if (!Pattern.compile("static final String " + Pattern.quote(member) + "\\s*=")
                    .matcher(code).find()) {
                broken.add(r.key() + " claims durability through " + r.evidence + ", and " + owner
                        + " declares no such preference key");
            }
        }
        assertTrue("no DURABLE_PREF rows left", checked >= 3);
        if (!broken.isEmpty()) fail("Preference keys named as durability evidence that do not "
                + "exist: " + broken);
    }

    /**
     * A row claiming {@code NOT_A_GATE} is checked against the structural rule, not believed.
     *
     * <p>{@code NOT_A_GATE} is the only verdict that ends the conversation, so it is the one worth
     * attacking. If a window length or a page size ever becomes an operand of a comparison or an
     * argument to a predicate, it is bounding something and the row is wrong.
     */
    @Test
    public void nothingClassifiedNotAGateIsUsedAtAGateSite() throws IOException {
        final Set<String> booleanMethods = booleanMethodNames();
        assertTrue("ZERO HITS MUST FAIL: booleanMethodNames() scanned from source is EMPTY, so the check below is satisfied "
                        + "by having read nothing. A zero-match scan is a broken scan, not a clean "
                        + "tree.",
                !booleanMethods.isEmpty());
        final List<String> wrong = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.NOT_A_GATE) continue;
            // THE mCfg. SKIP IS GONE. It existed for one row — mCfg.metadataKeysExtType,
            // an extension type compared for equality — and it spared every other mCfg. row with
            // it. That row is WIRE now, which is the verdict its own reason had been arguing for,
            // and this check no longer has an exemption to inherit.
            for (final String[] where : new String[][] {
                    {r.owner, r.constant},
                    {TRANSPORT, r.owner + "." + r.constant} }) {
                if (TRANSPORT.equals(r.owner) && !where[0].equals(r.owner)) continue;
                final String code = codeOf(where[0]);
                for (final Integer at : SourceScan.usesOf(code, where[1])) {
                    final String why = gateSiteKind(code, at.intValue(), where[1], booleanMethods);
                    if (why != null) {
                        wrong.add(r.key() + " is classified NOT_A_GATE and is used at a gate site "
                                + "in " + where[0] + " (" + why + "): "
                                + lineAt(code, at.intValue()));
                    }
                }
            }
        }
        if (!wrong.isEmpty()) {
            fail("These thresholds are recorded as bounding nothing and are being compared or "
                    + "handed to a predicate: " + wrong + ". Re-classify each — the reason on the "
                    + "row is what a reader trusts instead of re-deriving it.");
        }
    }

    /**
     * A row claiming {@code WIRE} is not ORDER-compared anywhere in the subsystem.
     *
     * <p>{@code WIRE} is the verdict this guard cannot derive — see {@link #wire} — so it gets the
     * treatment {@code NOT_A_GATE} gets: the claim is attacked rather than believed, with the one
     * attack that is actually available. A value fixed by a peer or a spec is recognised by
     * EQUALITY: {@code ext.type == mCfg.metadataKeysExtType}, {@code info.cipherSuite != ourSuite}.
     * Nothing asks whether a peer's extension type is <i>less than</i> ours. So an ordering
     * comparison — {@code <}, {@code <=}, {@code >}, {@code >=} — over a WIRE constant is evidence
     * that it is bounding something, and the row is then wrong in the direction that matters: a
     * threshold recorded as a protocol value is a counter nobody asked the durability question
     * about, which is the original durability defect wearing a different label.
     *
     * <p><b>THE RULE AS FIRST WRITTEN WAS FALSIFIED BY THE FIRST CONSTANT IT MET, and the pin
     * below is what survived.</b> {@code MlsReestablishPolicy.ERA_INITIAL} is the RCC.16 initial
     * Era ordinal and it is ORDER-compared: {@code serverEra >= ERA_INITIAL} asks whether the
     * server reported an era at or above the first legal one, i.e. whether the server holds this
     * conversation. A wire ordinal with a spec-defined minimum, used as a floor test, is a real
     * counterexample to "a protocol value is only ever matched for equality" — and there is no
     * structural fact separating it from {@code sendsSinceLeafRotation < REKEY_AFTER_SENDS},
     * because both are a scalar beside a relational operator.
     *
     * <p>So the check does not claim the absolute. It PINS the WIRE rows that are order-compared
     * today, per item, and fails in BOTH directions — a new one is a question somebody must answer,
     * and a pinned one that stops being ordered is a row that has drifted from its own code. Same
     * shape as this file's per-threshold site counts, for the same reason: an absolute claim that
     * one true row refutes is worth less than a pinned set that moves loudly.
     *
     * <p><b>What this cannot do is PROVE a WIRE claim.</b> Equality-comparison is consistent with a
     * wire value and with a tunable; only a NEW ordering is checkable. Stated here rather than left
     * for a reader to discover, because a check that can only fire in one direction reads like a
     * full check to everyone who has not read it.
     */
    @Test
    public void nothingClassifiedWireIsOrderCompared() throws IOException {
        final Pattern ordering = Pattern.compile("(<=|>=|<|>)");
        final Set<String> ordered = new HashSet<>();
        final List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (final Row r : INVENTORY) {
            if (r.durability != Durability.WIRE) continue;
            checked++;
            final String bare = r.constant.startsWith("mCfg.")
                    ? r.constant : r.owner + "." + r.constant;
            for (final String owner : allOwners()) {
                final String code = codeOf(owner);
                final List<Integer> at = new ArrayList<>();
                at.addAll(SourceScan.usesOf(code, bare));
                if (owner.equals(r.owner)) at.addAll(SourceScan.usesOf(code, r.constant));
                for (final Integer i : at) {
                    final int end = i.intValue() + (code.startsWith(bare, i.intValue())
                            ? bare.length() : r.constant.length());
                    if (ordering.matcher(subExpressionBefore(code, i.intValue())).find()
                            || ordering.matcher(subExpressionAfter(code, end)).find()) {
                        ordered.add(r.key());
                        if (!WIRE_ROWS_ORDER_COMPARED.contains(r.key())) {
                            wrong.add(r.key() + " is classified WIRE and is ORDER-compared in "
                                    + owner + ": " + lineAt(code, i.intValue()));
                        }
                    }
                }
            }
        }
        assertTrue("no WIRE rows in the inventory — either the verdict has left the vocabulary or "
                + "the wire values of the provider layer have stopped being classified, and this "
                + "check is passing on an empty set", checked >= 5);
        if (!wrong.isEmpty()) {
            fail("These constants are recorded as values fixed outside us and are NEWLY being "
                    + "order-compared: " + new HashSet<>(wrong) + ". A protocol value is normally "
                    + "matched for equality and a threshold is ordered, so say which it is: if it "
                    + "bounds a counter, re-classify it and answer the durability question; if it "
                    + "is a spec ordinal used as a floor test, add it to WIRE_ROWS_ORDER_COMPARED "
                    + "with the reason, in the same commit.");
        }
        final List<String> gone = new ArrayList<>();
        for (final String pinned : WIRE_ROWS_ORDER_COMPARED) {
            if (!ordered.contains(pinned)) gone.add(pinned);
        }
        if (!gone.isEmpty()) {
            fail("These WIRE rows are pinned as order-compared and no longer are: " + gone
                    + ". Delete the pin — an exemption for a comparison that is gone is inherited "
                    + "silently by the next one to appear at that name.");
        }
    }

    /**
     * The WIRE rows that ARE order-compared, and why each is not a threshold.
     *
     * <p>One today. Pinned rather than exempted by a rule, because the rule that would cover it
     * ("a spec ordinal used as a floor test") is not decidable from source — see the javadoc on
     * {@link #nothingClassifiedWireIsOrderCompared}.
     *
     * <ul>
     *   <li>{@code MlsReestablishPolicy.ERA_INITIAL} — {@code serverEra >= ERA_INITIAL} in
     *       {@code classify}, distinguishing {@code RECREATES_EXISTING} from {@code FIRST_CREATE}.
     *       The operand it is compared against is the SERVER's era, read from GetMlsGroupInfo; no
     *       counter of ours is on either side.</li>
     * </ul>
     */
    private static final Set<String> WIRE_ROWS_ORDER_COMPARED =
            new HashSet<>(java.util.Arrays.asList("MlsReestablishPolicy.ERA_INITIAL"));

    /**
     * The count of gates whose counter dies with the process only falls. <b>DoD-3 is met at 0.</b>
     *
     * <p>The failure message is Stage 5's queue, in priority order, with what each one leaves
     * unbounded across a restart.
     */
    @Test
    public void theNumberOfUndeclaredTransientGatesOnlyFalls() {
        final List<String> open = new ArrayList<>();
        for (final Row r : INVENTORY) {
            if (r.durability == Durability.TRANSIENT_UNDECLARED) {
                open.add(r.key() + " → " + r.counter + " (" + r.evidence + ")");
            }
        }
        if (open.size() > TRANSIENT_UNDECLARED_TODAY) {
            fail("DoD-3 went BACKWARDS: " + open.size() + " gates have an in-memory counter with "
                    + "no declared reason, up from " + TRANSIENT_UNDECLARED_TODAY + ". " + open);
        }
        if (open.size() < TRANSIENT_UNDECLARED_TODAY) {
            fail("DoD-3 IMPROVED — " + open.size() + " undeclared transient gates remain, down "
                    + "from " + TRANSIENT_UNDECLARED_TODAY + ". Lower TRANSIENT_UNDECLARED_TODAY "
                    + "to " + open.size() + " in the SAME commit. Remaining: " + open);
        }
    }

    /**
     * Every EVICTION CAP in the transport is classified, and they are found by the JDK contract.
     *
     * <p>This is the boundary check the class javadoc measures its way to. The four enumerations
     * above find named things; a capacity bound written as a literal inside an anonymous
     * {@code LinkedHashMap} is named nowhere, and the one that exists bounds a map fed by KEYS OFF
     * THE WIRE — the shape that has to be bounded on sight.
     *
     * <p><b>Keyed on the method the JDK declares, resolved by REFLECTION</b>, not on the string
     * "removeEldestEntry": a class that overrides it is declaring an eviction bound whatever it
     * calls the map or the field, and a needle typed here would be a spelling of the property
     * rather than the property. Zero overrides FAILS — an eviction hook that
     * has been renamed by a JDK change, or a scan that stopped matching, must not read as "no
     * unbounded maps here".
     */
    @Test
    public void everyEvictionCapInTheTransportIsClassified() throws IOException {
        final String hook = evictionHookName();
        assertTrue("java.util.LinkedHashMap declares no boolean(Map.Entry) eviction hook, so this "
                + "check has no subject and would silently pass over an unbounded map fed from the "
                + "wire", !hook.isEmpty());

        final String code = SourceScan.transport();
        final List<Integer> overrides = SourceScan.usesOf(code, hook);
        assertTrue("no override of " + hook + " in the transport. Either the liveness ledger's cap "
                + "is gone — in which case a map keyed by an E.164 that arrives on the wire is now "
                + "unbounded — or this locator has gone stale. Both must fail.",
                !overrides.isEmpty());

        final List<String> found = new ArrayList<>();
        for (final Integer at : overrides) {
            final int open = code.indexOf('{', at.intValue());
            if (open < 0) continue;
            final String body = braceMatched(code, open);
            final Matcher m = Pattern.compile("(?:<=|>=|<|>)\\s*(\\d[\\d_]*)").matcher(body);
            while (m.find()) found.add(m.group(1).replace("_", ""));
        }
        assertTrue("the " + hook + " override(s) compare against no literal at all — the cap is "
                + "being read from somewhere this check cannot see, so classify it as a threshold "
                + "in INVENTORY instead", !found.isEmpty());

        final Set<String> declared = new HashSet<>();
        for (final LiteralCap c : LITERAL_CAPS) declared.add(Integer.toString(c.value));
        final List<String> unclassified = new ArrayList<>();
        for (final String v : found) {
            if (!declared.contains(v)) unclassified.add(v);
        }
        final Set<String> foundSet = new HashSet<>(found);
        final List<String> stale = new ArrayList<>();
        for (final String v : declared) {
            if (!foundSet.contains(v)) stale.add(v);
        }
        if (!unclassified.isEmpty()) {
            fail("These eviction caps have no LITERAL_CAPS row: " + unclassified + ". A capacity "
                    + "bound written as a literal is invisible to all four of this guard's "
                    + "enumerations — say which counter it bounds and why that counter may or may "
                    + "not die with the process.");
        }
        if (!stale.isEmpty()) {
            fail("LITERAL_CAPS rows for caps that no longer exist: " + stale + ". Delete the row — "
                    + "a classification for a value that is gone is inherited silently by the next "
                    + "cap to take it.");
        }
    }

    /** The name of {@code LinkedHashMap}'s eviction hook, from the JDK rather than from a string. */
    private static String evictionHookName() {
        for (final java.lang.reflect.Method m : LinkedHashMap.class.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Map.Entry.class) {
                return m.getName();
            }
        }
        return "";
    }

    /** The brace-matched block opened at {@code open}, brackets included, or "". */
    private static String braceMatched(final String src, final int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    // ---- the enumerations ----------------------------------------------------------------------

    /**
     * The transport, every guard class named by the tool's {@code GUARD} regex, and every engine
     * policy a guard class has DELEGATED its thresholds to.
     *
     * <p>The third term is what keeps DoD-3 watching a bound that moves out of a guard class into
     * the engine — Stage 3 did exactly that for the two window budgets, and without this the
     * four thresholds would have left the inventory silently while every assertion still passed.
     * Derived, never listed: an adapter that delegates to a new policy brings that policy's
     * thresholds into the inventory on the same day.
     */
    private static List<String> allOwners() throws IOException {
        final List<String> out = new ArrayList<>();
        out.add(TRANSPORT);
        final List<String> guards = guardClasses();
        out.addAll(guards);
        for (final String g : guards) {
            if (!declaredIn(g).isEmpty()) continue;
            for (final String policy : enginePoliciesBehind(g)) {
                if (!out.contains(policy)) out.add(policy);
            }
        }
        // Stage 6: the OTHER destination. A threshold that leaves the transport for the engine
        // policy that owns its decision is invisible to all three terms above — MlsRecoveryPolicy
        // and MlsSelfHealPass are not guard classes and no guard class delegates to them — so it
        // would leave the inventory silently, which is the exact failure this guard exists to
        // prevent and the one theSubjectsOfThisGuardAreAllNonEmpty names in its message.
        //
        // Taken from the rows rather than from a list typed here, and it is NOT self-serving: a row
        // whose owner no longer declares its constant fails everyRowNamesAThresholdThatStillExists,
        // a policy constant with no row at all fails everyPolicyConstantTheToolNamesHasARow, and a
        // constant that is declared in an owner and read nowhere fails the site pinning. The three
        // together are what make "the row names where it went" checkable rather than believed.
        for (final Row r : INVENTORY) {
            if (r.constant.startsWith("mCfg.")) continue;
            if (out.contains(r.owner)) continue;
            if (SourceScan.engineClass(r.owner) == null) continue;
            if (!new File(locate(ENGINE_DIR + "/" + r.owner + ".java")).isFile()) continue;
            out.add(r.owner);
        }
        // THE WHOLE PROVIDER MLS LAYER, not the classes the four terms above happen to
        // reach. See providerLayerOwners() for what "the layer" is derived from and what it cannot
        // reach either.
        for (final String c : providerLayerOwners()) {
            if (!out.contains(c)) out.add(c);
        }
        return out;
    }

    /**
     * <b>The provider MLS layer</b> — the union of two independently derived sets.
     *
     * <p>(a) every {@code .java} under {@link #PROVIDER_MLS_DIR}, RECURSIVELY, and (b) every
     * provider {@code .java} anywhere under {@code src/} that IMPORTS the engine MLS package. Each
     * has its own non-empty floor in {@link #theSubjectsOfThisGuardAreAllNonEmpty}, and each covers
     * the other's evasion: a tunable escapes (a) by moving out of {@code e2ee}, and it escapes (b)
     * by not importing the engine — it escapes both only by leaving the MLS provider layer under
     * either test, at which point {@code MlsTransportPolicyConstantGuardTest}'s
     * {@code noPolicyConstantHasMerelyRelocatedWithinTheProviderLayer} still re-locates it if it is
     * a name the tool calls policy.
     *
     * <p><b>(b) alone is NOT enough, and it was measured rather than assumed.</b> Of the fourteen
     * constants the widening brings in, {@code MlsStalledNotifier.ID_BASE} and
     * {@code RcsE2eeScheme.MLS_CIPHERSUITE_P256_AES128} are in classes that import no engine class
     * at all — so an import-derived scope, which is the version of this that keys on a fact rather
     * than on a directory, would have missed two of the fourteen. Both terms are kept for that
     * reason and the weaker-looking one is the directory.
     *
     * <p><b>What this is honestly keyed on.</b> The CLASSIFICATION below keys on structure and
     * reflection — an initialiser's shape, a gate site's operator, a member resolved by
     * {@link Class#forName}. The ENUMERATION keys on a directory, and it cannot do otherwise: a
     * constant is only findable by reading files. The rule forbids letting a LOCATION stand
     * in for the property, which is what DoD-1's "declared in one file" did; it does not forbid
     * walking a tree to find the subjects the property is then decided over.
     */
    private static List<String> providerLayerOwners() throws IOException {
        final List<String> out = new ArrayList<>();
        for (final File f : SourceScan.javaSourcesUnder(PROVIDER_MLS_DIR)) {
            out.add(f.getName().substring(0, f.getName().length() - ".java".length()));
        }
        for (final File f : providerClassesImportingTheEngine()) {
            final String simple = f.getName().substring(0, f.getName().length() - ".java".length());
            if (!out.contains(simple)) out.add(simple);
        }
        Collections.sort(out);
        return out;
    }

    /** Term (b): every provider source that imports {@code …rcs.engine.mls}. */
    private static List<File> providerClassesImportingTheEngine() throws IOException {
        final List<File> out = new ArrayList<>();
        for (final File f : SourceScan.javaSourcesUnder(SourceScan.PROVIDER_SRC)) {
            final String code = SourceScan.codeOnly(
                    new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
            if (code.contains("import com.android.messaging.rcs.engine.mls.")) out.add(f);
        }
        return out;
    }

    /**
     * The engine classes {@code guardClass} references in CODE which themselves declare a threshold.
     *
     * <p>Comment-stripped, so a class named only in a javadoc does not count as delegated-to, and
     * resolved through {@link SourceScan#engineClass} so a name that does not compile answers
     * nothing rather than reading as coverage — the rule that a document (or a
     * needle) is worth only the resolution of the names in it.
     */
    private static List<String> enginePoliciesBehind(final String guardClass) throws IOException {
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\\bMls[A-Za-z]\\w*\\b").matcher(codeOf(guardClass));
        while (m.find()) {
            final String name = m.group();
            if (out.contains(name) || name.equals(guardClass)) continue;
            if (SourceScan.engineClass(name) == null) continue;
            if (!new File(locate(ENGINE_DIR + "/" + name + ".java")).isFile()) continue;
            if (declaredIn(name).isEmpty()) continue;
            out.add(name);
        }
        return out;
    }

    /**
     * The guard classes, read out of {@code transport-classify.py}'s {@code GUARD} pattern.
     *
     * <p>Not typed here: a fourth budget added to the tool's GUARD regex is one this test starts
     * enumerating thresholds from on the same day, without anybody remembering to.
     */
    private static List<String> guardClasses() throws IOException {
        final String py = SourceScan.read(TOOL);
        final int at = py.indexOf("GUARD = re.compile(");
        if (at < 0) return new ArrayList<>();
        final String pattern = py.substring(at, Math.min(py.length(), at + 800));
        final Set<String> seen = new HashSet<>();
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\\bMls[A-Za-z]+\\b").matcher(pattern);
        while (m.find()) {
            if (seen.add(m.group()) && new File(locate(E2EE + m.group() + ".java")).isFile()) {
                out.add(m.group());
            }
        }
        return out;
    }

    private static String locate(final String rel) {
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            if (new File(c).isFile()) return c;
        }
        return rel;
    }

    /**
     * A threshold owner's source, comment- and string-stripped. Provider-side first, then the
     * engine — an owner is wherever the constant is declared, and since Stage 3 that can be
     * either side of the boundary.
     */
    private static String codeOf(final String simpleName) throws IOException {
        final String hit = CODE_CACHE.get(simpleName);
        if (hit != null) return hit;
        final String provider = E2EE + simpleName + ".java";
        final String code;
        if (new File(locate(provider)).isFile()) {
            code = SourceScan.codeOnly(SourceScan.read(provider));
        } else if (new File(locate(ENGINE_DIR + "/" + simpleName + ".java")).isFile()) {
            code = SourceScan.codeOnly(SourceScan.read(ENGINE_DIR + "/" + simpleName + ".java"));
        } else {
            // A provider class OUTSIDE e2ee — term (b) of providerLayerOwners. Recursive, and
            // already read once by SourceScan, which is also where the non-recursive dir.list()
            // form was fixed after moving MlsPeerGuard one directory down read as four lost sites.
            final String anywhere = SourceScan.providerClassSource(simpleName);
            if (anywhere == null) {
                throw new IOException(simpleName + ".java found in neither " + E2EE + ", "
                        + ENGINE_DIR + " nor anywhere under " + SourceScan.PROVIDER_SRC);
            }
            code = anywhere;
        }
        CODE_CACHE.put(simpleName, code);
        return code;
    }

    /**
     * {@code simpleName -> code-only source}. The widened subject asks the same ~40 files for their
     * declarations and then, per row, for qualified references from every other owner; without this
     * the transport's 20k lines are re-read and re-stripped some 1,800 times.
     */
    private static final Map<String, String> CODE_CACHE = new LinkedHashMap<>();

    private static List<String> declaredIn(final String simpleName) throws IOException {
        return SourceScan.scalarConstantsDeclaredIn(codeOf(simpleName));
    }

    /**
     * A threshold's code reference sites — <b>everywhere in the subsystem, not just its owner</b>.
     *
     * <p>For a transport-declared threshold this is what it always was. For one Stage 6 moved
     * into the engine it is the owner's own uses PLUS every QUALIFIED reference from any other
     * owner, because a new door onto the same bound is the defect being counted whichever class
     * opens it. Counting only the owner would have made {@code MlsProviderTransport} — or
     * {@code MlsPeerGuard}, which is where the two durable cooldown windows are actually spent —
     * able to add a second gate on a moved threshold without moving this number, which is precisely
     * the second-door defect exactly.
     */
    private static int sitesOf(final Row r) throws IOException {
        int n = SourceScan.usesOf(codeOf(r.owner), r.constant).size();
        for (final String other : allOwners()) {
            if (other.equals(r.owner)) continue;
            n += SourceScan.usesOf(codeOf(other), r.owner + "." + r.constant).size();
        }
        return n;
    }

    /** {@code MlsConfig}'s scalar fields, by REFLECTION — the Stage 6 destination. */
    private static List<String> configFields() {
        final List<String> out = new ArrayList<>();
        final Class<?> cfg = SourceScan.engineClass("MlsConfig");
        if (cfg == null) return out;
        for (final java.lang.reflect.Field f : cfg.getDeclaredFields()) {
            final Class<?> t = f.getType();
            if (t == int.class || t == long.class || t == boolean.class || t == short.class
                    || t == byte.class || t == double.class || t == float.class) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** Those config fields the transport uses at a gate site. */
    private static List<String> configFieldsAtAGateSite() throws IOException {
        final String code = SourceScan.transport();
        final Set<String> booleanMethods = booleanMethodNames();
        final List<String> out = new ArrayList<>();
        for (final String f : configFields()) {
            final String needle = "mCfg." + f;
            for (int i = code.indexOf(needle); i >= 0; i = code.indexOf(needle, i + 1)) {
                if (!Character.isJavaIdentifierPart(code.charAt(i + needle.length()))
                        && gateSiteKind(code, i, needle, booleanMethods) != null) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    // ---- alias vs declaration -------------------------------------------------------

    /**
     * The {@code Class.MEMBER} this constant is a pure ALIAS of, or {@code null}.
     *
     * <p><b>This is the one half of the classification that is provable rather than recorded</b>,
     * and it has to be, because the precedent it exists for would otherwise turn the widened guard
     * into noise: {@code MlsPeerGuard} declares five {@code static final}s and <b>all five are
     * aliases</b> — {@code MAX_ERA_ADVANCES_PER_HOUR = MlsEraBudgetRecord.MAX_PER_HOUR} and its
     * four siblings. A rule reading "any scalar in the provider layer is a tunable to classify"
     * flags every one of them, five of the first thirteen findings are wrong, and the guard gets
     * turned off. A rule reading "MlsPeerGuard is exempt" is the same defect again — a location
     * standing in for a property.
     *
     * <p>So: an alias is a declaration whose INITIALISER, taken from the {@code =} to the next
     * {@code ;} over comment-stripped source and therefore across line breaks, is <i>nothing but</i>
     * a qualified reference to another class's member. The value is declared elsewhere and the
     * durability question belongs where the value is; here it is a name.
     *
     * <p>And the target must RESOLVE, which is what stops this being a hole. An engine target is
     * checked by reflection — {@link Class#forName} plus a {@code static final} field of that name,
     * so a class that compiled and really does declare it. A provider target is checked by
     * declaration shape and must ALSO carry an {@link #INVENTORY} row of its own, so an alias chain
     * cannot walk a constant out of the inventory one hop at a time.
     *
     * <p><b>What it deliberately does NOT call an alias, and the direction is chosen:</b> an
     * initialiser that DERIVES a value ({@code MlsFoo.BAR * 2}, {@code MlsFoo.BAR + 1}) is a
     * declaration and needs its own row. The derived number is a new tunable wearing a borrowed
     * name, and over-requiring a row is the safe direction — the unsafe one is exactly the hole
     * this guard is about.
     */
    private static String aliasTargetOf(final String owner, final String constant)
            throws IOException {
        final String code = codeOf(owner);
        final Matcher m = Pattern.compile(
                "(?m)^[ \\t]+(?:(?:public|private|protected|static|final)[ \\t]+)*"
                + "(?:int|long|short|byte|double|float|boolean)[ \\t]+"
                + Pattern.quote(constant) + "[ \\t]*=([^;]*);").matcher(code);
        if (!m.find()) return null;
        final String init = m.group(1).trim().replaceAll("\\s+", " ");
        return Pattern.matches("[A-Z]\\w*(?:\\.[A-Z]\\w*)*\\.\\w+", init) ? init : null;
    }

    /** Is this constant a pure alias whose target resolves? Aliases carry no row. */
    private static boolean isResolvedAlias(final String owner, final String constant)
            throws IOException {
        return aliasTargetOf(owner, constant) != null
                && aliasFault(owner, constant, aliasTargetOf(owner, constant)) == null;
    }

    /** Why {@code target} is not a usable alias target, or {@code null} when it is one. */
    private static String aliasFault(final String owner, final String constant,
            final String target) throws IOException {
        final int dot = target.lastIndexOf('.');
        final String targetClass = target.substring(0, dot);
        final String member = target.substring(dot + 1);
        final Class<?> engine = SourceScan.engineClass(targetClass);
        if (engine != null) {
            for (final java.lang.reflect.Field f : engine.getDeclaredFields()) {
                if (!f.getName().equals(member)) continue;
                final int mod = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mod)
                        || !java.lang.reflect.Modifier.isFinal(mod)) {
                    return owner + "." + constant + " aliases " + target + ", which " + targetClass
                            + " declares but not as a static final field";
                }
                return null;
            }
            return owner + "." + constant + " aliases " + target + ", and " + targetClass
                    + " resolves but declares no field " + member;
        }
        final String provider = SourceScan.providerClassSource(targetClass);
        if (provider == null) {
            return owner + "." + constant + " aliases " + target + ", and " + targetClass
                    + " is in neither the engine nor the provider — the alias points at nothing "
                    + "this guard can see, which is indistinguishable from a tunable hiding behind "
                    + "a qualified name";
        }
        if (!SourceScan.scalarConstantsDeclaredIn(provider).contains(member)) {
            return owner + "." + constant + " aliases " + target + ", and provider class "
                    + targetClass + " declares no static-final scalar " + member;
        }
        for (final Row r : INVENTORY) {
            if (r.owner.equals(targetClass) && r.constant.equals(member)) return null;
        }
        return owner + "." + constant + " aliases the PROVIDER constant " + target + ", which has "
                + "no INVENTORY row of its own — an alias may only defer the durability question to "
                + "a place that answers it";
    }

    /**
     * Every alias in the subsystem points at a constant that really is declared, static and final.
     *
     * <p>The counterpart to {@link #everyThresholdInTheSubsystemIsClassified} skipping aliases: the
     * skip is only sound while the alias is one. Turn {@code MAX_ERA_ADVANCES_PER_HOUR}'s
     * initialiser into a literal and it stops being an alias and needs a row; point it at a member
     * {@code MlsEraBudgetRecord} does not declare and this fails rather than the skip quietly
     * widening.
     *
     * <p>Per item, and zero aliases FAILS — five exist today and they are the precedent the whole
     * rule is written around, so a run that finds none is a run whose initialiser matcher has gone
     * stale, not a layer that stopped using aliases.
     */
    @Test
    public void everyAliasPointsAtAConstantThatResolves() throws IOException {
        final List<String> aliases = new ArrayList<>();
        final List<String> broken = new ArrayList<>();
        for (final String owner : allOwners()) {
            for (final String c : declaredIn(owner)) {
                final String target = aliasTargetOf(owner, c);
                if (target == null) continue;
                aliases.add(owner + "." + c + " -> " + target);
                final String fault = aliasFault(owner, c, target);
                if (fault != null) broken.add(fault);
            }
        }
        assertTrue("no static-final scalar in the subsystem is an alias of another class's "
                + "constant, and five are (MlsPeerGuard's, every one of them). The initialiser "
                + "matcher has gone stale, and everyThresholdInTheSubsystemIsClassified is now "
                + "skipping nothing — or, worse, has stopped skipping and the five are about to be "
                + "reported as unclassified tunables.", aliases.size() >= 5);
        if (!broken.isEmpty()) {
            fail("These aliases do not resolve: " + broken + ". An unresolvable alias is not an "
                    + "alias — it is a declaration this guard is failing to ask about. (" 
                    + aliases.size() + " aliases seen: " + aliases + ")");
        }
    }

    /** Every method name some engine class declares returning {@code boolean}. */
    private static Set<String> booleanMethodNames() throws IOException {
        final Set<String> out = new HashSet<>();
        final File dir = new File(locate(ENGINE_DIR + "/MlsConfig.java")).getParentFile();
        final String[] names = dir == null ? null : dir.list();
        if (names == null) return out;
        final Pattern p = Pattern.compile("\\bboolean\\s+(\\w+)\\s*\\(");
        for (final String n : names) {
            if (!n.endsWith(".java")) continue;
            final Matcher m = p.matcher(SourceScan.read(ENGINE_DIR + "/" + n));
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    // ---- the structural rule -------------------------------------------------------------------

    /**
     * Why the occurrence of {@code name} at {@code at} is a gate site, or {@code null}.
     *
     * <p>(1) a relational or equality operator inside the same statement, or (2) the occurrence sits
     * inside the PAREN-MATCHED argument list of a call whose method name some engine class declares
     * returning {@code boolean}.
     */
    private static String gateSiteKind(final String code, final int at, final String name,
            final Set<String> booleanMethods) {
        final Pattern op = Pattern.compile("(<=|>=|==|!=|<|>)");
        if (op.matcher(subExpressionBefore(code, at)).find()) return "compared, operator before it";
        if (op.matcher(subExpressionAfter(code, at + name.length())).find()) {
            return "compared, operator after it";
        }

        final int stmt = statementStart(code, at);
        final Matcher call = Pattern.compile("(\\w+)\\s*\\(").matcher(code.substring(stmt, at));
        while (call.find()) {
            final String method = call.group(1);
            if (!booleanMethods.contains(method)) continue;
            final int open = stmt + call.end() - 1;
            final String args = SourceScan.argumentListAt(code, open);
            if (open + 1 + args.length() >= at) return "argument to boolean " + method + "(…)";
        }
        return null;
    }

    /**
     * The text from the nearest sub-expression boundary back to {@code at}.
     *
     * <p>NOT the whole statement. The sixth axis of that rule is a span bounded by a NEIGHBOUR
     * rather than by the arm, and a statement-wide window has exactly that defect: in
     * {@code rec == null ? -1 : rec.countWithin(DAY_MS, now)} the {@code ==} belongs to a different
     * sub-expression entirely, and reading it as DAY_MS's comparison called a window length a gate.
     * Measured, not hypothesised — this guard reported it against its own inventory.
     */
    private static String subExpressionBefore(final String code, final int at) {
        for (int i = at; i > 0; i--) {
            final char c = code.charAt(i - 1);
            if (c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == ',' || c == '?'
                    || c == ':' || c == '&' || c == '|' || c == '!') {
                return code.substring(i, at);
            }
        }
        return code.substring(0, at);
    }

    /** The mirror of {@link #subExpressionBefore}, forwards. */
    private static String subExpressionAfter(final String code, final int from) {
        for (int i = from; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == ',' || c == '?'
                    || c == ':' || c == '&' || c == '|') {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }

    /** The start of the statement containing {@code at} — a brace, a semicolon, or the file. */
    private static int statementStart(final String code, final int at) {
        for (int i = at; i > 0; i--) {
            final char c = code.charAt(i - 1);
            if (c == ';' || c == '{' || c == '}') return i;
        }
        return 0;
    }

    private static String lineAt(final String code, final int at) {
        final int a = code.lastIndexOf('\n', at) + 1;
        int b = code.indexOf('\n', at);
        if (b < 0) b = code.length();
        return "line " + (1 + count(code.substring(0, at), '\n')) + " — "
                + code.substring(a, b).trim();
    }

    private static int count(final String s, final char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    static {
        // A duplicate key would let one row silently answer for two thresholds.
        final Set<String> seen = new HashSet<>();
        for (final Row r : INVENTORY) {
            if (!seen.add(r.key())) throw new IllegalStateException("duplicate INVENTORY row " + r.key());
        }
    }
}
