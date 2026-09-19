/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How each {@link MlsFetchLedger.Caller}'s refusal arm has been exercised on a device, as data: one
 * row per caller, per call site, each naming a host test and (for a reachability claim) a
 * falsifier, held to the source by {@code MlsFetchLedgerCoverageGuardTest}. A row separates "the
 * refusal fired" from "the remedy was seen". {@link Status#NO_ADB_FIXTURE_KNOWN} is a statement
 * about our fixtures, not a claim that production cannot reach the arm.
 */
public final class MlsFetchLedgerCoverage {

    private MlsFetchLedgerCoverage() {}

    /** How a caller's refusal arm stands with respect to a real device. */
    public enum Status {
        /**
         * A refusal and its remedy were seen at every exercisable call site of this caller.
         */
        DEVICE_EXERCISED,
        /**
         * Seen at some sites and not others: {@code 0 < sitesExercised() < exercisableSites()},
         * checked per row.
         */
        PARTIALLY_EXERCISED,
        /** A deterministic {@code adb} recipe is written down and nobody has run it. */
        DEVICE_REACHABLE_NOT_YET_RUN,
        /**
         * No {@code adb} recipe is known, and the bound believed to prevent one is named. About our
         * fixtures, not production.
         */
        NO_ADB_FIXTURE_KNOWN,
        /** Nobody has looked. */
        NOT_EXAMINED,
    }

    /**
     * A bound that stands between a fixture and a refusal arm, with the method that would clear it.
     * The guard scans the debug receiver for that name, so a lever added later turns a
     * {@link Status#NO_ADB_FIXTURE_KNOWN} row red.
     */
    public enum Blocker {
        /** No bound is claimed. */
        NONE(null),
        /**
         * {@code MlsPeerGuard}'s era budget. Its only reset is the stall notification's Try again,
         * on a non-exported receiver.
         */
        ERA_BUDGET_RATE("resetEraBudget"),
        /**
         * The ledger's shared ceiling, where a caller competes for it with the health read gating
         * its rung. Not a blocker where the ceiling can simply be raised (it is a live sysprop).
         */
        SHARED_CEILING_SHARED_WITH_THE_HEALTH_READ(null);

        /** The method that would clear this bound, as spelled at a debug call site, or null. */
        public final String clearedBy;

        Blocker(final String clearedBy) {
            this.clearedBy = clearedBy;
        }
    }

    /** How one call site of one caller stands: reachability and remedy are separate axes. */
    public enum SiteState {
        /** Nobody has seen this site's refusal on a device. */
        UNEXERCISED,
        /**
         * A refusal fired here on a device, but the arm's current behaviour has not been seen.
         * Waiting on a specific re-run, not a permanent limitation.
         */
        REFUSAL_OBSERVED_REMEDY_UNVERIFIED,
        /** A refusal fired on a device and the arm's current behaviour was observed. */
        REFUSAL_AND_REMEDY_OBSERVED,
        /**
         * The look is discarded, so a refusal and "the server had nothing" are the same silence.
         * Excluded from the denominator; the guard requires the count of such sites to equal the
         * sites that call {@code .orNull()} and discard the look.
         */
        NO_DISTINGUISHABLE_REFUSAL_ARM;

        /** Could a device tell this site's refusal apart? Membership of the denominator. */
        public boolean exercisable() {
            return this != NO_DISTINGUISHABLE_REFUSAL_ARM;
        }

        /** Has a refusal been seen here at all, whatever the remedy's state. */
        public boolean refusalObserved() {
            return this == REFUSAL_OBSERVED_REMEDY_UNVERIFIED || this
                    == REFUSAL_AND_REMEDY_OBSERVED;
        }

        /** Is there anything left for someone at a device to do here? */
        public boolean outstanding() {
            return this == UNEXERCISED || this == REFUSAL_OBSERVED_REMEDY_UNVERIFIED;
        }
    }

    /** One call site of one caller, with what is known about its refusal arm. */
    public static final class Site {
        /** What the site is, for a reader; never used as a locator. */
        public final String name;
        /** How it stands. */
        public final SiteState state;
        /** What was observed, or why nothing can be. */
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
        /** Every call site with its own state; empty for a {@link Status#NOT_EXAMINED} row. */
        public final List<Site> sites;
        /**
         * How many call sites this caller has, or {@link #UNCOUNTED}; checked against the source,
         * so a new site forces the claim to be re-derived.
         */
        public final int callSites;
        /** The bound believed to prevent a fixture; {@link Blocker#NONE} unless one is claimed. */
        public final Blocker blocker;
        /** {@code Class#method} of the host test that covers the arm's behaviour. */
        public final String hostTest;
        /**
         * {@code Class#method} of the test that goes red when the claim's premise stops holding.
         * Required for the two reachability statuses.
         */
        public final String claimFalsifier;
        /**
         * How many drives the recorded recipe needs, or {@link #UNCOUNTED}. A literal, not derived,
         * so {@code theAdbRecipeForTheRebuildArmStillHolds} can hold it against {@code
         * REBUILD.ration + 1}.
         */
        public final int recipeDrives;
        /** What was observed or derived. */
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

        /** Whether this row asserts reachability, which needs a falsifier. */
        public boolean makesAReachabilityClaim() {
            return status == Status.DEVICE_REACHABLE_NOT_YET_RUN
                    || status == Status.NO_ADB_FIXTURE_KNOWN;
        }

        /** Sites where a device has been made to refuse: the numerator. */
        public int sitesExercised() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.refusalObserved()) n++;
            }
            return n;
        }

        /**
         * Sites whose refusal a device could tell apart at all: the denominator. Excludes
         * {@link SiteState#NO_DISTINGUISHABLE_REFUSAL_ARM}.
         */
        public int exercisableSites() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.exercisable()) n++;
            }
            return n;
        }

        /** Sites with something left for someone at a device. */
        public int sitesOutstanding() {
            int n = 0;
            for (final Site x : sites) {
                if (x.state.outstanding()) n++;
            }
            return n;
        }

        /** Sites in a given state, by name, for a failure message. */
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
        // ---- the two callers that have been examined
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
                // A literal, not a derivation: a derived value would move with the ration and the
                // guard would compare a number with itself.
                /*recipeDrives=*/ 5,
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
                                "On a device at ceiling 2: the ledger "
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

        // ---- everything else: no claim
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
