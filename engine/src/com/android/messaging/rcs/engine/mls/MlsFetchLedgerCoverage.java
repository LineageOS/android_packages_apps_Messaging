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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * <b>How each {@link MlsFetchLedger.Caller}'s REFUSAL arm has actually been exercised.</b>
 *
 * <h2>The claim this class exists to make falsifiable</h2>
 *
 * <p>This exists because a refusal arm had host coverage and no device path, which asked
 * the right question: <b>"structurally unreachable from adb" is not the same as "cannot happen",
 * and we had no way to tell those apart.</b> The answer was written where such answers usually go —
 * a paragraph of javadoc on {@link MlsFetchLedger.Caller#ERA_ADVANCE} saying the recovery arms are
 * unreachable from {@code adb}. That paragraph is prose. It cannot go red, it cannot be re-derived
 * mechanically, and it was <b>wrong in one direction and out of date in another within a day</b>:
 *
 * <ul>
 *   <li>It was over-general. "REBUILD's refusal is unreachable" was read as "refusals inside a
 *       rebuild are unreachable"; the second is false, and {@code arms-on-device} then exercised two
 *       of them on hardware.</li>
 *   <li>It named the wrong bound as decisive. The interlock it describes rests on the fetch ledger's
 *       ceiling being a floor as well as a cap — but the ceiling is an operator sysprop that can be
 *       raised as easily as lowered, and raising it separates the health read from the rung it
 *       gates without any code change at all.</li>
 * </ul>
 *
 * <p>So the coverage claim becomes DATA, one row per caller, and
 * {@code MlsFetchLedgerCoverageGuardTest} holds each row to something the machine can check. A row
 * cannot merely assert; it must name a host test that exists and a falsifier that goes red when the
 * fact the claim rests on stops being true.
 *
 * <h2>What a row does NOT say</h2>
 *
 * <p><b>{@link Status#NO_ADB_FIXTURE_KNOWN} is not "cannot happen"</b>, and every reader of this
 * class has to carry that. The bounds that make these arms hard to provoke — {@code MlsPeerGuard}'s
 * era budget, the self-heal budget, the ledger itself — are all charged by looks that <i>succeeded</i>.
 * Organic traffic spends them without any of our levers, so an arm no fixture can drive is still an
 * arm production takes. The interlock stops the deep path being PROVOKED, not from RUNNING.
 *
 * <p>And {@link Status#NOT_EXAMINED} is the honest majority — every caller but the two below have
 * had nobody look at whether a device could reach their refusal. Recording that as a status rather
 * than as silence is the whole point: it makes the coverage gap a number instead of an
 * impression, and it makes a new caller land as a question rather than as a blank. The count is
 * deliberately not written here as a sentence; ask {@link #countOf}, because a number in prose is
 * the thing this class exists to replace.
 *
 * <h2>What a row says: per SITE, and on two axes</h2>
 *
 * <p>A per-caller adjective could not express either of the only two situations anybody had
 * measured. Both distinctions came from {@code arms-on-device}'s device runs rather than from
 * reading, and both had to become data:
 *
 * <ol>
 *   <li><b>A site can be UNEXERCISABLE rather than unexercised.</b> {@code ENSURE_READY}'s
 *       read-only diagnostic calls {@code .orNull()} and discards the look, so a refusal there is
 *       indistinguishable from "the server had nothing" — both are silence, BY DESIGN. Counting it
 *       as a coverage gap leaves that caller permanently short of {@link Status#DEVICE_EXERCISED}
 *       for a reason nobody can close. It is excluded from the DENOMINATOR, and
 *       {@code MlsFetchLedgerCoverageGuardTest} corroborates every exclusion against the transport
 *       source — so the exemption cannot hide a real arm, and a real arm cannot be marked
 *       exempt.</li>
 *   <li><b>"The refusal fired" and "the remedy is verified" are different facts.</b>
 *       {@code REBUILD}'s opening arm fired on a build that PREDATES the
 *       {@code DEFERRED_BY_OUR_OWN_LEDGER} fix — so that run proved REACHABILITY and reproduced the
 *       DEFECT (a stall alert asserting a peer-protecting guard had refused when the fetch ledger
 *       had). One boolean per site records it as done and quietly loses that the arm still ships a
 *       user-visible false alert.</li>
 * </ol>
 */
public final class MlsFetchLedgerCoverage {

    private MlsFetchLedgerCoverage() {}

    /** How a caller's refusal arm stands with respect to a real device. */
    public enum Status {
        /**
         * A refusal has been OBSERVED on a device, and its REMEDY seen, at every one of this
         * caller's EXERCISABLE call sites.
         *
         * <p>Every site, deliberately. {@code REBUILD} has three and only two have been reached;
         * letting one stand for the caller would report the arm that decides whether a rebuild
         * STARTS as covered by evidence about the arm that decides whether it was VERIFIED.
         *
         * <p>"Exercisable" rather than "all", because a site whose look is discarded has no refusal
         * a device can distinguish — see {@link SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM}, whose
         * exclusion from the denominator is corroborated against the source rather than asserted.
         */
        DEVICE_EXERCISED,
        /**
         * <b>Observed at SOME sites and not at others</b> — the true state of both callers anybody
         * has examined, and a value this enum did not have at first.
         *
         * <p>Without it {@code REBUILD} sat at {@link #DEVICE_REACHABLE_NOT_YET_RUN} ("nobody has
         * run it") while two of its three sites were device-proven, and {@code ENSURE_READY} had no
         * row at all and so read {@link #NOT_EXAMINED} ("nobody has looked") after two device
         * passes had asked. {@code countOf(NOT_EXAMINED)} is the number quoted as the
         * coverage gap, so the missing value made it wrong in BOTH directions.
         *
         * <p>It is the same defect the rest of this class is about, sitting inside the instrument
         * built to measure it: <b>a reader with partial knowledge must not share a value with one
         * that has none.</b> What stops it becoming a softer {@link #DEVICE_EXERCISED} is that it
         * is arithmetic — {@code 0 < sitesExercised() < exercisableSites()}, checked per row.
         */
        PARTIALLY_EXERCISED,
        /**
         * A deterministic {@code adb} recipe exists and is written down, and nobody has run it.
         *
         * <p>Distinct from {@link #DEVICE_EXERCISED} because a recipe is a derivation and a
         * derivation is not a measurement — this project has re-learned that twice in a week. The
         * row's falsifier is what keeps the recipe honest between now and the run.
         */
        DEVICE_REACHABLE_NOT_YET_RUN,
        /**
         * Nobody has found an {@code adb} recipe, and the bound believed to prevent one is named.
         *
         * <p>Read the class javadoc before treating this as a safety property. It is a statement
         * about our fixtures, not about production.
         */
        NO_ADB_FIXTURE_KNOWN,
        /** Nobody has looked. Not a pass, not a failure — an unanswered question with a name. */
        NOT_EXAMINED,
    }

    /**
     * A bound that stands between a fixture and a refusal arm, named with the method that would
     * clear it.
     *
     * <p>The method name is the falsifier's needle, not documentation: the guard scans the debug
     * receiver for it, so a lever added later turns a {@link Status#NO_ADB_FIXTURE_KNOWN} row red
     * instead of leaving a stale claim in place.
     */
    public enum Blocker {
        /** No bound is claimed. */
        NONE(null),
        /**
         * {@code MlsPeerGuard}'s era budget — 2 per rolling hour, 5 per day, per conversation.
         *
         * <p>Its only production reset is {@code MlsStalledActionReceiver}'s {@code ACTION_RETRY},
         * on a receiver declared {@code android:exported="false"}. <b>The manifest's stated reason
         * is about the OTHER action on that receiver</b> — the one that turns end-to-end encryption
         * off for a conversation, which no other app may trigger. The era budget being out of adb's
         * reach is a consequence of the two actions sharing a receiver, not a decision anyone
         * recorded about the era budget. Worth stating precisely, because a right verdict resting on
         * a reason nobody checked is the one defect no negative control catches.
         */
        ERA_BUDGET_RATE("resetEraBudget"),
        /**
         * The fetch ledger's own {@link MlsFetchLedger#SHARED_CEILING}, when a caller competes for
         * it with the health read that gates its rung.
         *
         * <p>Named as a blocker only where the competition is genuinely unavoidable. For
         * {@code REBUILD} it is not: the ceiling is an operator sysprop read live, and raising it
         * removes the competition outright.
         */
        SHARED_CEILING_SHARED_WITH_THE_HEALTH_READ(null);

        /**
         * The method that would clear this bound, as it would be spelled at a debug call site, or
         * null when no such method exists.
         */
        public final String clearedBy;

        Blocker(final String clearedBy) {
            this.clearedBy = clearedBy;
        }
    }

    /**
     * <b>How ONE call site of one caller stands.</b>
     *
     * <p>The two axes {@link MlsFetchLedgerCoverage}'s javadoc names, as four values rather than a
     * boolean. Splitting them is not tidiness: a boolean makes {@code REBUILD}'s opening arm read
     * "done" while the remedy for it is unverified, and makes {@code ENSURE_READY}'s discarded look
     * read as a gap somebody could close.
     */
    public enum SiteState {
        /** Nobody has seen this site's refusal on a device. An open question, and closeable. */
        UNEXERCISED,
        /**
         * A refusal FIRED here on a device, and what the arm does about it has NOT been seen on a
         * build that has the arm's current behaviour.
         *
         * <p>{@code REBUILD}'s opening look is the case: the refusal is device-proven on
         * a build that predates the fix, so that run proved the site
         * REACHABLE and simultaneously reproduced the defect. Reachability and remedy are different
         * facts and they came apart on the same line.
         *
         * <p><b>This value is LIVE, not terminal.</b> Only a re-run closes it: the remedy is what a
         * device measures.
         * A site sitting here is waiting for ONE SPECIFIC RUN, not recording a permanent limitation
         * the way {@link #NO_DISTINGUISHABLE_REFUSAL_ARM} does — read the site's own evidence for
         * which run, and move it to {@link #REFUSAL_AND_REMEDY_OBSERVED} in the same change that
         * lands the measurement.
         */
        REFUSAL_OBSERVED_REMEDY_UNVERIFIED,
        /** A refusal fired on a device AND the arm's current behaviour was observed. Settled. */
        REFUSAL_AND_REMEDY_OBSERVED,
        /**
         * <b>This site's refusal has no observable consequence, by design</b> — the look is
         * discarded, so a refusal and "the server had nothing" are the same silence.
         *
         * <p>Not a gap and not coverage: a third answer. It is excluded from the denominator
         * {@link Status#DEVICE_EXERCISED} is measured against, and that exclusion is the one thing
         * here a person could abuse — so it is not taken on trust.
         * {@code MlsFetchLedgerCoverageGuardTest.anUnexercisableSiteReallyDiscardsItsLook} counts
         * the caller's sites that call {@code .orNull()} in the transport and requires the two
         * counts to be EQUAL. Both directions: a real refusal arm cannot be exempted, and an
         * exempt site cannot quietly grow one.
         */
        NO_DISTINGUISHABLE_REFUSAL_ARM;

        /** Does a device have a refusal here it could tell apart? The denominator's membership. */
        public boolean exercisable() {
            return this != NO_DISTINGUISHABLE_REFUSAL_ARM;
        }

        /** Has a refusal been seen here at all — reachability, whatever the remedy's state. */
        public boolean refusalObserved() {
            return this == REFUSAL_OBSERVED_REMEDY_UNVERIFIED || this == REFUSAL_AND_REMEDY_OBSERVED;
        }

        /** Is there anything left for somebody at a device to do here? */
        public boolean outstanding() {
            return this == UNEXERCISED || this == REFUSAL_OBSERVED_REMEDY_UNVERIFIED;
        }
    }

    /** One call site of one caller, with what is known about its refusal arm. */
    public static final class Site {
        /** What the site IS, for a person reading the row. Never used as a locator. */
        public final String name;
        /** How it stands. */
        public final SiteState state;
        /** What was measured, or why nothing can be. */
        public final String evidence;

        Site(final String name, final SiteState state, final String evidence) {
            this.name = name;
            this.state = state;
            this.evidence = evidence;
        }

        @Override public String toString() {
            return name + "=" + state;
        }
    }

    private static Site site(final String name, final SiteState state, final String evidence) {
        return new Site(name, state, evidence);
    }

    /** Declared instead of a real count when a row makes no coverage claim. */
    public static final int UNCOUNTED = -1;

    /** One caller's coverage claim, and what makes it checkable. */
    public static final class Row {
        /** The door. */
        public final MlsFetchLedger.Caller caller;
        /** How its refusal arm stands. */
        public final Status status;
        /**
         * Every call site this caller has, each with its own state. Empty for
         * a {@link Status#NOT_EXAMINED} row, which is making no claim about any of them.
         *
         * <p>This was a bare count, and a count cannot say WHICH site. {@code REBUILD} had two of
         * three device-proven and a status that said "nobody has run it".
         */
        public final List<Site> sites;
        /**
         * How many call sites this caller has, or {@link #UNCOUNTED} for a
         * {@link Status#NOT_EXAMINED} row. Derived from {@link #sites}.
         *
         * <p>Checked against the source. A caller that grows a site has grown an arm, and a coverage
         * claim written before that site existed has to be re-derived rather than inherited.
         */
        public final int callSites;
        /** The bound believed to prevent a fixture; {@link Blocker#NONE} unless one is claimed. */
        public final Blocker blocker;
        /** {@code Class#method} of the host test that covers the arm's BEHAVIOUR. */
        public final String hostTest;
        /**
         * {@code Class#method} of the test that goes RED when the fact this row's claim rests on
         * stops being true. Required for the two statuses that make a reachability claim.
         */
        public final String claimFalsifier;
        /**
         * How many drives the row's recorded adb recipe needs, or {@link #UNCOUNTED}.
         *
         * <p><b>Device-derived, and it was wrong when derived from source alone.</b> The first
         * version of this recipe said three drives, reasoning that a rebuild spends two
         * {@code REBUILD} looks (the opening one and the confirming one). Measured on
         * On a device: a rebuild that does NOT converge never reaches the
         * confirming look, so it spends ONE — and the second rebuild of a run does not converge,
         * because {@link MlsFetchLedger.Caller#ENSURE_READY} spends 3 per rebuild against
         * {@code REBUILD}'s 2 and therefore hits its own ration first. So the worst case is one
         * attempt per look plus the one that is refused, and the recipe is
         * {@code REBUILD.ration + 1}. {@code theAdbRecipeForTheRebuildArmStillHolds} pins that
         * relationship, so raising the ration reds the row rather than silently invalidating the
         * plan somebody is about to run on a device.
         */
        public final int recipeDrives;
        /** What was measured or derived, in one paragraph. */
        public final String evidence;

        Row(final MlsFetchLedger.Caller caller, final Status status, final List<Site> sites,
                final Blocker blocker, final String hostTest, final String claimFalsifier,
                final int recipeDrives, final String evidence) {
            this.recipeDrives = recipeDrives;
            this.caller = caller;
            this.status = status;
            this.sites = Collections.unmodifiableList(new ArrayList<>(sites));
            this.callSites = this.sites.isEmpty() ? UNCOUNTED : this.sites.size();
            this.blocker = blocker;
            this.hostTest = hostTest;
            this.claimFalsifier = claimFalsifier;
            this.evidence = evidence;
        }

        /** Whether this row asserts something about a device rather than recording a gap. */
        public boolean makesAClaim() {
            return status != Status.NOT_EXAMINED;
        }

        /** Whether this row asserts something about REACHABILITY, which needs a falsifier. */
        public boolean makesAReachabilityClaim() {
            return status == Status.DEVICE_REACHABLE_NOT_YET_RUN
                    || status == Status.NO_ADB_FIXTURE_KNOWN;
        }

        /**
         * Sites where a device HAS been made to refuse. The numerator, and it is a count rather
         * than an adjective so {@link Status#PARTIALLY_EXERCISED} cannot drift into
         * {@link Status#DEVICE_EXERCISED}.
         */
        public int sitesExercised() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.refusalObserved()) n++;
            }
            return n;
        }

        /**
         * <b>The honest denominator</b>: sites whose refusal a device could tell apart at all.
         *
         * <p>Excludes {@link SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM}. Without the exclusion a
         * caller with such a site can never reach {@link Status#DEVICE_EXERCISED}, for a reason
         * that is not a coverage gap and that nobody can close.
         */
        public int exercisableSites() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.exercisable()) n++;
            }
            return n;
        }

        /**
         * Sites with something left for somebody at a device — never reached, or reached on a build
         * that predated the arm's current behaviour. What a recipe is FOR.
         */
        public int sitesOutstanding() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.outstanding()) n++;
            }
            return n;
        }

        /** Sites in a given state, named — for a failure message that says WHICH. */
        public List<String> sitesIn(final SiteState state) {
            final List<String> out = new ArrayList<>();
            for (final Site x : sites) {
                if (x.state == state) out.add(x.name);
            }
            return out;
        }

        @Override public String toString() {
            return sites.isEmpty() ? caller + "=" + status
                    : caller + "=" + status + " (" + sitesExercised() + "/" + exercisableSites()
                            + " exercisable sites)";
        }
    }

    private static final Map<MlsFetchLedger.Caller, Row> ROWS =
            new EnumMap<>(MlsFetchLedger.Caller.class);

    private static void row(final MlsFetchLedger.Caller caller, final Status status,
            final List<Site> sites, final Blocker blocker, final String hostTest,
            final String claimFalsifier, final int recipeDrives, final String evidence) {
        ROWS.put(caller, new Row(caller, status, sites, blocker, hostTest, claimFalsifier,
                recipeDrives, evidence));
    }

    /** A caller nobody has examined, recorded as such rather than left out. */
    private static void unexamined(final MlsFetchLedger.Caller caller) {
        row(caller, Status.NOT_EXAMINED, Collections.<Site>emptyList(), Blocker.NONE, null, null,
                UNCOUNTED,
                "Nobody has asked whether a device can reach this caller's refusal. Not a claim in "
                        + "either direction.");
    }

    static {
        // ---- the two callers anybody has actually examined --------------------------------------
        //
        // Both are PARTIALLY_EXERCISED, and this enum originally had no such value: REBUILD
        // read DEVICE_REACHABLE_NOT_YET_RUN ("nobody has run it") with two of three sites proven,
        // and ENSURE_READY had no row and so read NOT_EXAMINED ("nobody has looked") after two
        // device passes. The per-site evidence below is from those runs; it is
        // recorded here rather than re-derived.
        row(MlsFetchLedger.Caller.REBUILD, Status.PARTIALLY_EXERCISED,
                Arrays.asList(
                        site("serverPackForRebuild's pack look", SiteState.UNEXERCISED,
                                "Never charges on a forgetgroup fixture: serverPackForRebuild "
                                        + "returns BEFORE charging when getGroup(key) is null, "
                                        + "which is exactly the REJOIN state --ez forgetgroup "
                                        + "produces. Reaching it needs a group with local state "
                                        + "(--es mlshold BEHIND/ERA_GAP) and we have none — "
                                        + "every conversation on the test device is a 1:1, "
                                        + "device-confirmed. "
                                        + "CITE THE PREDICATE, not the derivation: "
                                        + "MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH"
                                        + ".spentALook() == false, host-asserted. A "
                                        + "derivation from a method body rots silently."),
                        site("rebuildConversation's OPENING look",
                                SiteState.REFUSAL_OBSERVED_REMEDY_UNVERIFIED,
                                "REACHABILITY IS ESTABLISHED, THE REMEDY IS NOT, and they came apart on "
                                        + "one line. The refusal fired on a device AT A "
                                        + "RAISED CEILING OF 20: 'MLS fetch ledger REFUSED "
                                        + "getMlsServerEraEpoch for REBUILD ... it has spent its "
                                        + "own 4-look allowance (4 spent per 200s) while the "
                                        + "conversation's SHARED allowance STILL HAS ROOM (8 of "
                                        + "20)' — DENIED_CALLER_RATION, not the ceiling. THAT LAST "
                                        + "CLAUSE IS THE DISCRIMINATING ONE, and it is why the "
                                        + "recipe RAISES the ceiling rather than exhausting it: "
                                        + "this arm is reached by spending the CALLER's ration with "
                                        + "the shared one deliberately un-exhausted. Exhausting the "
                                        + "shared ceiling instead -- which is the right method for "
                                        + "a DIFFERENT measurement, the escalation look count -- "
                                        + "stops the ladder ABOVE this rung, and the arm is never "
                                        + "asked. But that build PREDATES the fix, so that run "
                                        + "also REPRODUCED the defect: REFUSED_BY_GUARD plus a "
                                        + "stall alert telling a person a peer-protecting guard had "
                                        + "refused when the fetch ledger had. What is unverified is "
                                        + "the arm as it ships now — DEFERRED_BY_OUR_OWN_LEDGER and "
                                        + "silence. The re-run for it was lost when drive 1 spent "
                                        + "the last daily era slot at 13:57:37 and drives 2-4 "
                                        + "missed the 200s window. WAITING ON ONE RUN, not on a "
                                        + "permanent limitation. It needs an era "
                                        + "slot on p:+15715550104 (spent until the daily budget "
                                        + "refills) AND the fixture re-armed first -- --ez "
                                        + "forgetgroup and a CONFIRMED '-> REJOIN', because drive 1 "
                                        + "converged that conversation to IN_SYNC at era 25 and an "
                                        + "IN_SYNC conversation never reaches the arm."),
                        site("rebuildConversation's CONFIRMING look",
                                SiteState.REFUSAL_AND_REMEDY_OBSERVED,
                                "On a device: the refusal fired and the arm did what it "
                                        + "ships to do — RAN_BUT_UNVERIFIED, and no MlsStalledNotifier "
                                        + "raise. Reachability and remedy both seen on the same run, "
                                        + "which is what the site above does not have.")),
                Blocker.NONE,
                "MlsRebuildOutcomeTest#aRefusalByOurOwnLedgerIsNotAGuardRefusal",
                "MlsFetchLedgerCoverageGuardTest#theAdbRecipeForTheRebuildArmStillHolds",
                // A LITERAL, NOT A DERIVATION, and the difference is the whole value of the check.
                // This was `REBUILD.ration + 1` for about ten minutes, which made
                // theAdbRecipeForTheRebuildArmStillHolds compare a number to itself: raising the
                // ration moved BOTH sides and the guard stayed green on a recipe that had just
                // gone stale. The negative control is what caught it, not review. A number a
                // person wrote down is the only kind a guard can hold them to — same reason
                // MlsRebuildOutcome declares its two flags independently rather than deriving one
                // from the other.
                /*recipeDrives=*/ 5,
                // The derivation, kept here because a recipe with no arithmetic beside it is a
                // recipe nobody can check has gone stale. Corrected by a device run.
                "Two of these three sites were recorded as unreachable from adb, on two interlocks: "
                        + "(1) the health read is charged against the same ceiling as the rung it "
                        + "gates, so exhausting the ledger stops the ladder ABOVE the rung; (2) "
                        + "spending REBUILD's own ration needs more rebuilds than MlsPeerGuard's "
                        + "era budget allows, and that budget's only reset is an exported=false "
                        + "receiver. BOTH ARE ESCAPABLE and neither needs a new lever. (1) falls to "
                        + "RAISING debug.rcs.mls_fetch_ceiling, which is read live: that record treated a "
                        + "sysprop that goes both ways as one that only goes down. (2) never "
                        + "applies, because the opening look is CHARGED BEFORE "
                        + "MlsPeerGuard.allowEraAdvance is consulted, so an attempt the era budget "
                        + "REFUSES still spends a look and costs NO era slot. "
                        + "RECIPE for what is left: CONFIRM THE SERVER STILL HOLDS THE CONVERSATION "
                        + "FIRST (--ez health, which is ledger-exempt: expect 'no local group but "
                        + "the server has one (era=N epoch=1) -> REJOIN'). This precondition was "
                        + "missing from the first version and arms-on-device added it: if the "
                        + "server does NOT hold it, MlsReestablishPolicy classifies the rebuild as "
                        + "FIRST_CREATE, whose chargesEraBudget is FALSE, so allowEraAdvance is "
                        + "never consulted and every attempt completes — the arithmetic here is for "
                        + "a RECREATES_EXISTING verdict and does not describe that case. Note an "
                        + "ABSENT MlsRecordStore entry is the fixture ARMED, not missing: a prior "
                        + "--ez forgetgroup is what leaves the ledger row with no record, which IS "
                        + "the REJOIN state. Then age the ledger 200s (so ENSURE_READY starts at 0 "
                        + "and the first rebuild can converge), then drive, --ez forgetgroup to "
                        + "remake REJOIN and --ez rebuildreset to clear the 60s episode suppressor "
                        + "between drives, all inside one WINDOW_MS. TWO CORRECTIONS THE DEVICE RUN "
                        + "MADE, both from arms-on-device: a rebuild that does NOT converge never "
                        + "reaches the confirming look and so spends ONE REBUILD look, not two; and "
                        + "the second rebuild of a run does not converge, because ENSURE_READY "
                        + "spends 3 per rebuild against REBUILD's 2 and hits its OWN ration first. "
                        + "So the drive count is REBUILD.ration + 1 in the worst case, not three, "
                        + "and the checkpoint is READING THE REBUILD COUNTER off each ledger line — "
                        + "counting 'AUTOMATIC REBUILD' and 'ensureReady=' lines does NOT catch it, "
                        + "because both print twice while the count is short. STEP 1 IS "
                        + "DEVICE-VALIDATED, the rest is not. A "
                        + "re-run reached drive 1 and its checkpoint PASSED: the durable ledger read "
                        + "1|...:10,...:20,...:20,...:20,...:10 — ordinal 10 is REBUILD twice, so "
                        + "BOTH the opening and the confirming look ran, and the confirming look is "
                        + "only reached once allowEraAdvance has passed. NOTE THE LEDGER, NOT THE "
                        + "LOG, IS WHAT PROVES THAT: the capture had stalled and the device's main "
                        + "buffer rotated the drive-1 lines out entirely.");

        row(MlsFetchLedger.Caller.ENSURE_READY, Status.PARTIALLY_EXERCISED,
                Arrays.asList(
                        site("the read-only ESTABLISH-OVER-HELD diagnostic",
                                SiteState.NO_DISTINGUISHABLE_REFUSAL_ARM,
                                "CHARGED many times; its refusal is NOT OBSERVABLE and cannot be "
                                        + "made so. It calls .orNull() and discards the Look, so a "
                                        + "refusal and 'the server had nothing' are the same "
                                        + "silence — the ESTABLISH-OVER-HELD line simply does not "
                                        + "print. Its own comment says exactly that: 'A refused "
                                        + "look leaves the line unprinted — the arm below only "
                                        + "fires on a positive reading, so nothing here concludes "
                                        + "anything from silence.' This is not a gap anybody can "
                                        + "close, which is why it is out of the denominator rather "
                                        + "than sitting in it forever."),
                        site("the 'Era changed from' bump", SiteState.REFUSAL_AND_REMEDY_OBSERVED,
                                "Device-measured at ceiling 2: the ledger "
                                        + "refused, and then \"the 'Era changed from' re-create for "
                                        + "… is PROCEEDING WITHOUT the server's era\" printed — the "
                                        + "fail-open arm, with NEITHER decline line firing. Both "
                                        + "facts on one run: the refusal was reached and the arm "
                                        + "did what it ships to do."),
                        site("the reclaim arm", SiteState.UNEXERCISED,
                                "Charged, never refused. Distinguishable, unlike the diagnostic "
                                        + "above — it tests reclaim.refused() before calling "
                                        + ".orNull() on a later statement — so this one IS a "
                                        + "coverage gap and it is counted as one. No adb recipe has "
                                        + "been written for it.")),
                Blocker.NONE,
                "MlsFetchLedgerCoverageGuardTest#bothDistinguishableEnsureReadyArmsStillDecline",
                /*claimFalsifier=*/ null,
                /*recipeDrives=*/ UNCOUNTED,
                "THREE call sites in the transport, none in the debug receiver, and the honest "
                        + "denominator is TWO. This caller is why SiteState exists: a bare "
                        + "'sitesExercised == callSites' rule would leave it permanently short of "
                        + "DEVICE_EXERCISED because of a site whose refusal has no observable "
                        + "consequence BY DESIGN. The exclusion is corroborated against the "
                        + "transport source rather than trusted — see "
                        + "SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM. This caller once had "
                        + "NO ROW AT ALL and therefore read NOT_EXAMINED, 'nobody has asked whether "
                        + "a device can reach this caller's refusal', after two device passes had "
                        + "asked and one had answered.");

        // ---- everything else: no claim, and the gap is the finding -------------------------------
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (!ROWS.containsKey(c)) unexamined(c);
        }
    }

    /** This caller's coverage row; never null, because every caller has one. */
    public static Row of(final MlsFetchLedger.Caller caller) {
        return ROWS.get(caller);
    }

    /** Every row, in {@link MlsFetchLedger.Caller} order. */
    public static List<Row> rows() {
        final List<Row> out = new ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) out.add(ROWS.get(c));
        return Collections.unmodifiableList(out);
    }

    /** How many rows carry the given status — the coverage gap, as a number. */
    public static int countOf(final Status status) {
        int n = 0;
        for (final Row r : rows()) {
            if (r.status == status) n++;
        }
        return n;
    }
}
