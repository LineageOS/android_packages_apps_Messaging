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

/**
 * <b>WHICH guards an MLS state change must pass, and in what order.</b>
 *
 * <h2>The problem this class exists to solve</h2>
 *
 * <p>{@code MlsPeerGuard}'s arithmetic already lived in the engine — {@link MlsEraBudgetRecord} and
 * {@link MlsPeerHealthRecord} are host-tested, and their properties (a charge survives a restart, no
 * clock reading refills a spent window) are pinned. Its <b>composition</b> did not. Four tiers were
 * expressed as four methods calling each other, and the question "which of G1/G2/G4/G6 applies to
 * <i>this</i> operation" was answerable only by reading them in order. <b>Three separate fixes each
 * got exactly one row of that table wrong</b>:
 *
 * <ul>
 *   <li>the automatic rebuild reached an era advance without passing G2 at all, and
 *       G2 never charged a 1:1, so the advance forced at the peer that was wedged for a month was
 *       never counted by the budget written because of it;</li>
 *   <li>an ADD initiated by a menu row rather than a debug arm skipped G1, the
 *       allowlist, whose whole subject is "may this number be brought into MLS at all";</li>
 *   <li>a self-departure was about to be given {@code allowStateChange} because it
 *       was nearest, which refuses a person's escape exactly when the peer looks wedged.</li>
 * </ul>
 *
 * <p>None of those is a wrong answer from a budget. Each is the <b>wrong set of budgets consulted</b>
 * — and that set was not a value anywhere, so nothing could assert it. Here it is a value:
 * {@link #guardsFor} <i>is</i> the tier table, {@link #decide} is derived from the same facts, and
 * {@code MlsStateChangeGateTest} reconciles the two over every {@code (Tier, Guard)} pair rather than
 * over a hand-written list of rows.
 *
 * <h2>The four compositions, verified against {@code MlsPeerGuard} at {@code feac553c}</h2>
 *
 * <table>
 *   <caption>Tier compositions, in consultation order</caption>
 *   <tr><th>{@code MlsPeerGuard} entry point</th><th>{@link Tier}</th><th>guards, in order</th></tr>
 *   <tr><td>{@code allowSelfDeparture}</td><td>{@link Tier#SELF_DEPARTURE}</td>
 *       <td>G6</td></tr>
 *   <tr><td>{@code allowStateChange}</td><td>{@link Tier#ORGANIC_STATE_CHANGE}</td>
 *       <td>G6, G4</td></tr>
 *   <tr><td>{@code allowDebugStateChange}, {@code allowJoiningPeer}</td>
 *       <td>{@link Tier#ALLOWLISTED_STATE_CHANGE}</td><td>G6, G4, G1</td></tr>
 *   <tr><td>{@code allowEraAdvance}</td><td>{@link Tier#ERA_ADVANCE}</td>
 *       <td>G6, G4, G2</td></tr>
 * </table>
 *
 * <p><b>The era advance does NOT take the allowlist, and the allowlisted tiers do NOT take the era
 * budget.</b> The plan's prose for D1 reads "{@code allowStateChange} = G6 ∧ G4;
 * {@code allowEraAdvance} = that ∧ G2; {@code allowJoiningPeer}/{@code allowDebugStateChange} = that
 * ∧ G1", where the second "that" can be read as the era-advance composition. It is not: in source
 * {@code allowAllowlistedStateChange} calls {@code allowStateChange}, not {@code allowEraAdvance}.
 * The two branches are siblings, and a group re-creation pays both separately — G2 once at the
 * rebuild funnel, G1 once per member through {@code allowedToJoinAll}.
 *
 * <h2>What is NOT here, deliberately</h2>
 *
 * <p><b>The {@code op} label.</b> Every {@code MlsPeerGuard} entry point takes one and it appears in
 * every refusal line, but it is a free-form string chosen at the call site and it decides nothing.
 * We have already shipped a guard that keyed on exactly that kind of label and
 * enumerated 17 of 22 doors; a decision function that accepted one would invite the same. The op
 * stays in the reporting layer, where it is what it claims to be.
 *
 * <p><b>Persistence and effects.</b> {@code MlsPeerGuard} keeps {@code prefs()}/{@code load}/
 * {@code store}/{@code drop}, the {@code SystemProperties} reads, the redaction and the refusal
 * logs. Its storage decisions — its own preferences file, {@code commit()} not
 * {@code apply()}, aged by {@link MlsMonotonicAge} rather than the wall clock — are untouched; the
 * option that would have re-opened them was rejected.
 *
 * <h2>Three shapes this class refuses to have</h2>
 *
 * <ol>
 *   <li><b>An allowed era advance that was not charged is unrepresentable.</b> {@link Tier#ERA_ADVANCE}
 *       never yields {@link Outcome#ALLOW}; its only proceeding outcome is
 *       {@link Outcome#CHARGE_AND_ALLOW}, so a caller cannot fall through the switch into an
 *       uncharged advance. That is the first defect above made impossible rather than fixed.</li>
 *   <li><b>Unreadable does not share a value with absent.</b> {@link RecordState} separates
 *       {@link RecordState#NOT_CONSULTED} from {@link RecordState#PRESENT} from
 *       {@link RecordState#UNREADABLE}; a record that stores nothing decodes to an empty record and
 *       is PRESENT. No sentinel integer carries that distinction into a decision.</li>
 *   <li><b>A question this gate cannot answer is not an answer.</b> {@link Outcome#UNANSWERABLE} is
 *       a fourth value, not a refusal and not an allow. A {@link Tier} with no arm, or a tier asked
 *       to decide without a fact its own {@link #guardsFor} row requires, lands there and says
 *       which. {@link MlsEraAdvanceCharge#basisFor} makes the same move for an undeclared era mode.</li>
 * </ol>
 */
public final class MlsStateChangeGate {

    private MlsStateChangeGate() {}

    // ---- the vocabulary ------------------------------------------------------------------------

    /** The four gates, named as {@code MlsPeerGuard}'s javadoc names them. */
    public enum Guard {
        /**
         * G6 — the kill switch, {@code debug.rcs.mls_freeze_state_changes}. Consulted by every
         * tier and consulted FIRST by every tier: during the incident there was no way to stop the
         * bleeding short of not running commands, so one sysprop must beat everything, including an
         * allowlisted peer and an unspent budget.
         */
        G6_FREEZE,
        /**
         * G4 — the peer-health streak. A peer that has told us {@link
         * MlsPeerHealthRecord#MAX_CONSECUTIVE_FAILURES} times that our control messages fail on its
         * side stops receiving state changes.
         */
        G4_PEER_HEALTH,
        /**
         * G1 — the lab allowlist, {@code persist.rcs.mls_lab_peers}. The number we once wedged
         * was a personal phone that had never been on any test device list.
         */
        G1_LAB_ALLOWLIST,
        /**
         * G2 — the era budget, {@link MlsEraBudgetRecord}. Bounds every operation that makes every
         * member of a conversation re-join by Welcome, whichever door it came through.
         */
        G2_ERA_BUDGET,
    }

    /**
     * The operation tiers. One per {@code MlsPeerGuard} entry point, because that is the granularity
     * at which the composition differs — {@code allowDebugStateChange} and {@code allowJoiningPeer}
     * share one tier because in source they share one body.
     */
    public enum Tier {
        /**
         * OUR OWN departure from a conversation — {@code allowSelfDeparture}.
         *
         * <p>G6 alone, and the two it omits are omitted for reasons, not by oversight. G1 would
         * refuse our escape from a group containing a non-lab peer, which is backwards: leaving is
         * how you stop talking to a number you should not have been talking to. G4 would refuse it
         * precisely when the peer looks wedged, which is when a person most wants out — and for a
         * group it would refuse on ONE member's streak, since a group has no single peer to charge.
         * G6 is kept because a self-leave transmits: a SelfRemove proposal on {@code KickGroupUsers}.
         */
        SELF_DEPARTURE,
        /**
         * An organic protocol state change with no allowlist — {@code allowStateChange}. The REMOVE
         * is the operation that matters here: refusing to remove a non-lab peer would trap the one
         * peer you most need out of the group.
         */
        ORGANIC_STATE_CHANGE,
        /**
         * A state change that must additionally clear the lab allowlist — {@code allowDebugStateChange}
         * (anything a debug arm or an experiment initiates: the category that actually caused the
         * incident) and {@code allowJoiningPeer} (bringing a peer INTO a group, whoever asked —
         * item 1 of the incident report is literally that operation).
         */
        ALLOWLISTED_STATE_CHANGE,
        /**
         * Any operation that makes every member of a conversation re-join by Welcome —
         * {@code allowEraAdvance}. The era advance itself, the automatic rebuild, and
         * {@code ensureReady}'s 1:1 reclaim arm (they all charge one budget,
         * because two limiters counting one peer-facing cost with separate accounting is how a total
         * of seventeen stays plausible while both read "within budget").
         *
         * <p><b>Every era-advance MODE is chargeable and that is exact rather than conservative</b>
         * — an era advance cannot be a commit, so the engine refuses to build a
         * preserving one and every mode falls through to a re-creation within the same call.
         * {@link MlsEraAdvanceCharge#chargeableAtTheFunnel} holds that reasoning and is asked ABOVE
         * this gate, so an <i>undeclared</i> mode is refused by name before the budget is consulted
         * rather than charged on an unstated basis. This tier therefore has no mode input: by the
         * time it is reached, the mode question is already answered.
         */
        ERA_ADVANCE,
    }

    /**
     * What a durable record lookup produced.
     *
     * <p>Three values because there are three states and a decision that conflates any two of them
     * is the bug. {@code MlsPeerHealthRecord.decode} and {@code MlsEraBudgetRecord.decode} already
     * distinguish "nothing stored" (an empty record) from "stored and unparseable" (null); this adds
     * the third, "the caller did not look", which a null would otherwise absorb.
     */
    public enum RecordState {
        /**
         * The caller did not consult this record. Legitimate when the tier does not compose the
         * guard that reads it, or when there is no key to look it up under; an error when the tier
         * DOES compose it, and {@link Outcome#UNANSWERABLE} is what that error produces.
         */
        NOT_CONSULTED,
        /** Read and decoded. A record that stores nothing is PRESENT with a zero value. */
        PRESENT,
        /** A record EXISTS under this key and did not parse. Not healthy, not uncharged, not absent. */
        UNREADABLE,
    }

    /** Whether the peer named by a state change is on the lab allowlist (G1). */
    public enum Allowlist {
        /** G1 was not consulted — correct for the tiers that do not compose it. */
        NOT_CONSULTED,
        /** A peer identifier was supplied and it is in the lab set. */
        LISTED,
        /**
         * G1 refuses. Covers both "a peer was named and is not in the lab set" and "no usable peer
         * identifier was supplied at all" — {@code allowAllowlistedStateChange} treats an empty peer
         * as not-a-lab-peer, and it must: the allowlist answers "may this number be brought into MLS
         * at all", and a blank is not a number that answer can be given for.
         */
        NOT_LISTED,
    }

    /** What {@link #decide} concluded. */
    public enum Outcome {
        /** Proceed. Nothing to record. */
        ALLOW,
        /**
         * Proceed <b>only after</b> the era budget charge is on disk. The caller must store
         * {@code record.charged(now)} before the operation begins — an advance that CRASHES must
         * still count, or a reproducible crash is an unthrottled loop.
         *
         * <p>This is a distinct outcome rather than a flag on {@link #ALLOW} so that a caller
         * handling {@link Tier#ERA_ADVANCE} cannot reach a proceeding path without meeting the
         * charge: {@link Tier#ERA_ADVANCE} never yields {@link #ALLOW}.
         */
        CHARGE_AND_ALLOW,
        /** Do not proceed. {@link Verdict#guard()} names which gate closed and {@link Verdict#reason()} why. */
        REFUSE,
        /**
         * <b>The gate cannot answer from these facts.</b> Not an allow and not a refusal — a caller
         * must treat it as a refusal AND as a defect, because it means either a tier nothing has
         * classified or a fact the tier's own {@link #guardsFor} row requires and the caller did not
         * supply. A gate that answered "refuse" here would hide a missing arm behind correct-looking
         * behaviour until the day the arm was supposed to allow something.
         */
        UNANSWERABLE,
    }

    /** Why {@link #decide} concluded what it did. One value per refusal line {@code MlsPeerGuard} emits. */
    public enum Reason {
        /** Nothing refused. */
        NOTHING_REFUSED,
        /** G6: {@code debug.rcs.mls_freeze_state_changes} is set. */
        STATE_CHANGES_FROZEN,
        /** G4: a peer-health record exists under this key and did not parse. Discard it. */
        PEER_HEALTH_UNREADABLE,
        /** G4: the consecutive-failure streak has reached its trip point. */
        PEER_WEDGED,
        /** G1: the peer is not in the lab set, or no usable peer identifier was supplied. */
        NOT_A_LAB_PEER,
        /**
         * G2: neither a group id nor a peer number was supplied, so there is nothing to charge the
         * era budget against and the operation cannot be bounded at all.
         */
        NO_BUDGET_KEY,
        /** G2: an era budget record exists under this key and did not parse. Discard it. */
        ERA_BUDGET_UNREADABLE,
        /** G2: the hourly or daily allowance is exhausted. */
        ERA_BUDGET_SPENT,
        /** {@link Outcome#UNANSWERABLE}: this tier has no arm in {@link #decide}. */
        TIER_NOT_CLASSIFIED,
        /** {@link Outcome#UNANSWERABLE}: a fact this tier's guards require was left NOT_CONSULTED. */
        FACT_NOT_SUPPLIED,
    }

    // ---- the tier table ------------------------------------------------------------------------

    /** {@link Tier#SELF_DEPARTURE}'s row. */
    private static final Guard[] SELF_DEPARTURE_GUARDS = {Guard.G6_FREEZE};

    /** {@link Tier#ORGANIC_STATE_CHANGE}'s row. */
    private static final Guard[] ORGANIC_GUARDS = {Guard.G6_FREEZE, Guard.G4_PEER_HEALTH};

    /** {@link Tier#ALLOWLISTED_STATE_CHANGE}'s row. */
    private static final Guard[] ALLOWLISTED_GUARDS = {
        Guard.G6_FREEZE, Guard.G4_PEER_HEALTH, Guard.G1_LAB_ALLOWLIST,
    };

    /** {@link Tier#ERA_ADVANCE}'s row. */
    private static final Guard[] ERA_ADVANCE_GUARDS = {
        Guard.G6_FREEZE, Guard.G4_PEER_HEALTH, Guard.G2_ERA_BUDGET,
    };

    /**
     * <b>The tier table.</b> Which guards {@code tier} composes, in the order they are consulted.
     *
     * <p>Order is part of the answer, not a detail: when two guards would both refuse, the one that
     * fires is the one a person reads in the log, and G6 is first everywhere because the kill switch
     * must win over an allowlisted peer and an unspent budget both.
     *
     * @return a fresh array — callers may not mutate the table
     * @throws IllegalArgumentException never for a declared tier; an unclassified tier returns an
     *     EMPTY array, which {@link #decide} turns into {@link Reason#TIER_NOT_CLASSIFIED} rather
     *     than into "no guards apply, proceed"
     */
    public static Guard[] guardsFor(final Tier tier) {
        if (tier == null) return new Guard[0];
        switch (tier) {
            case SELF_DEPARTURE:
                return SELF_DEPARTURE_GUARDS.clone();
            case ORGANIC_STATE_CHANGE:
                return ORGANIC_GUARDS.clone();
            case ALLOWLISTED_STATE_CHANGE:
                return ALLOWLISTED_GUARDS.clone();
            case ERA_ADVANCE:
                return ERA_ADVANCE_GUARDS.clone();
            default:
                // A tier added to the enum and not to this table. Empty, NOT "everything" and not
                // "nothing" — decide() reports it as unclassified.
                return new Guard[0];
        }
    }

    /** Whether {@code tier} composes {@code guard}. The one place a caller may ask what to gather. */
    public static boolean consults(final Tier tier, final Guard guard) {
        for (final Guard g : guardsFor(tier)) {
            if (g == guard) return true;
        }
        return false;
    }

    // ---- the era budget key --------------------------------------------------------------------

    /** Where an era budget key came from. */
    public enum KeySource {
        /** The RCS group id. */
        GROUP_ID,
        /** The peer's digits, for a 1:1 — {@code peer:<digits>}. */
        PEER,
        /** Neither identifier was usable. There is nothing to charge against. */
        NONE,
    }

    /**
     * An era budget key and where it came from.
     *
     * <p>The source is carried rather than inferred from the string because {@link KeySource#NONE}
     * must not be spelled the same way as "a key that happens to be null right now": the defect
     * this closes was precisely that a missing group id read as "allow", so every 1:1 era
     * advance was unbudgeted and the log never said so.
     */
    public static final class BudgetKey {

        /** The key to store under, or null exactly when {@link #source()} is {@link KeySource#NONE}. */
        private final String mKey;

        private final KeySource mSource;

        private BudgetKey(final String key, final KeySource source) {
            mKey = key;
            mSource = source;
        }

        /** The store key, or null when {@link #source()} is {@link KeySource#NONE}. */
        public String key() {
            return mKey;
        }

        public KeySource source() {
            return mSource;
        }

        /** Whether a key could be derived at all. */
        public boolean derived() {
            return mSource != KeySource.NONE;
        }

        @Override
        public String toString() {
            return "BudgetKey[" + mSource + " " + (mKey == null ? "-" : mKey) + "]";
        }
    }

    /** The one instance for "nothing to charge against". */
    private static final BudgetKey NO_KEY = new BudgetKey(null, KeySource.NONE);

    /**
     * What the era budget is counted against: the RCS group, or the PEER for a 1:1.
     *
     * <p>This used to be "if the group id is empty, allow", so every 1:1 era advance was unbudgeted.
     * That is the worse half of the incident's own shape: the wedged peer's 1:1 was
     * force-advanced to break a deadlock, three weeks in, and nothing counted it. <b>A
     * conversation without a group id is not a conversation without a cost; it is a conversation
     * whose cost is charged to the peer.</b>
     *
     * <p>The peer arm normalises to digits, so {@code "+15715550100"} and {@code "5715550100"} are
     * one key and a budget cannot be evaded by format.
     */
    public static BudgetKey eraBudgetKey(final String groupId, final String peerE164) {
        if (groupId != null && !groupId.isEmpty()) return new BudgetKey(groupId, KeySource.GROUP_ID);
        final String n = normalisePeer(peerE164);
        return n.isEmpty() ? NO_KEY : new BudgetKey("peer:" + n, KeySource.PEER);
    }

    /**
     * Digits-only comparison, so {@code "+1571…"} and {@code "1571…"} are the same peer, with a
     * missing or extra US country code tolerated so a fleet entry cannot be defeated by format.
     *
     * @return the normalised digits, or {@code ""} when there are none — never null
     */
    public static String normalisePeer(final String e164) {
        if (e164 == null) return "";
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < e164.length(); i++) {
            final char c = e164.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        final String d = b.toString();
        return (d.length() == 11 && d.charAt(0) == '1') ? d.substring(1) : d;
    }

    // ---- the facts -----------------------------------------------------------------------------

    /**
     * The facts {@link #decide} reasons over. Everything here is READ by the host layer and DECIDED
     * here — that is the whole of D1(a).
     *
     * <p>Every fact defaults to "not consulted". A tier that composes a guard whose fact is still
     * unconsulted yields {@link Outcome#UNANSWERABLE}, so forgetting to gather one is a named
     * failure rather than an accidental allow.
     */
    public static final class Facts {

        private final boolean mFrozen;
        private final Allowlist mAllowlist;
        private final RecordState mHealthState;
        private final int mStreak;
        private final BudgetKey mBudgetKey;
        private final RecordState mBudgetState;
        private final boolean mBudgetSpent;

        private Facts(final Builder b) {
            mFrozen = b.mFrozen;
            mAllowlist = b.mAllowlist;
            mHealthState = b.mHealthState;
            mStreak = b.mStreak;
            mBudgetKey = b.mBudgetKey;
            mBudgetState = b.mBudgetState;
            mBudgetSpent = b.mBudgetSpent;
        }

        public static Builder builder() {
            return new Builder();
        }

        public boolean frozen() {
            return mFrozen;
        }

        public Allowlist allowlist() {
            return mAllowlist;
        }

        public RecordState healthState() {
            return mHealthState;
        }

        public int streak() {
            return mStreak;
        }

        public BudgetKey budgetKey() {
            return mBudgetKey;
        }

        public RecordState budgetState() {
            return mBudgetState;
        }

        public boolean budgetSpent() {
            return mBudgetSpent;
        }

        /** Assembles {@link Facts}. Unset facts are {@link RecordState#NOT_CONSULTED}. */
        public static final class Builder {

            private boolean mFrozen;
            private Allowlist mAllowlist = Allowlist.NOT_CONSULTED;
            private RecordState mHealthState = RecordState.NOT_CONSULTED;
            private int mStreak;
            private BudgetKey mBudgetKey;
            private RecordState mBudgetState = RecordState.NOT_CONSULTED;
            private boolean mBudgetSpent;

            private Builder() {}

            /** G6: the value of {@code debug.rcs.mls_freeze_state_changes}. */
            public Builder frozen(final boolean frozen) {
                mFrozen = frozen;
                return this;
            }

            /** G1: whether the peer is in the lab set. */
            public Builder allowlist(final Allowlist allowlist) {
                mAllowlist = (allowlist == null) ? Allowlist.NOT_CONSULTED : allowlist;
                return this;
            }

            /**
             * G4: the streak this peer's health record reports.
             *
             * <p>Pass {@link RecordState#NOT_CONSULTED} when there is no peer to look one up for —
             * {@code allowStateChange} allows an operation that names no peer, because there is
             * nothing to judge, and that is a row of the table rather than an oversight. The
             * allowlisted tiers do not inherit that leniency: G1 refuses a blank.
             *
             * <p>{@code streak} is read only when {@code state} is {@link RecordState#PRESENT}; an
             * unreadable record carries no number, which is why one is not passed for it.
             */
            public Builder peerHealth(final RecordState state, final int streak) {
                mHealthState = (state == null) ? RecordState.NOT_CONSULTED : state;
                mStreak = streak;
                return this;
            }

            /**
             * G2: the derived key, what its record says, and whether the allowance is exhausted.
             *
             * @param key from {@link #eraBudgetKey}; {@link KeySource#NONE} refuses
             * @param state {@link RecordState#UNREADABLE} refuses and asks for a discard
             * @param spent {@code record.pruned(now).spent(now)} — the arithmetic stays in
             *     {@link MlsEraBudgetRecord}, which is where it is already host-tested
             */
            public Builder eraBudget(final BudgetKey key, final RecordState state,
                    final boolean spent) {
                mBudgetKey = key;
                mBudgetState = (state == null) ? RecordState.NOT_CONSULTED : state;
                mBudgetSpent = spent;
                return this;
            }

            public Facts build() {
                return new Facts(this);
            }
        }
    }

    // ---- the verdict ---------------------------------------------------------------------------

    /**
     * What the gate decided, why, and what the caller must do to the store as a result.
     *
     * <p>A refusal is distinguishable from an allow AND from an unanswerable question — three
     * values, not a boolean with a comment.
     */
    public static final class Verdict {

        private final Outcome mOutcome;
        private final Guard mGuard;
        private final Reason mReason;
        private final boolean mDiscardRecord;

        private Verdict(final Outcome outcome, final Guard guard, final Reason reason,
                final boolean discardRecord) {
            mOutcome = outcome;
            mGuard = guard;
            mReason = reason;
            mDiscardRecord = discardRecord;
        }

        public Outcome outcome() {
            return mOutcome;
        }

        /** Which gate closed, or null when nothing refused. */
        public Guard guard() {
            return mGuard;
        }

        public Reason reason() {
            return mReason;
        }

        /**
         * Whether the caller must DROP the record {@link #guard()} names.
         *
         * <p>True only for the two unreadable arms. Refuse once, loudly, then discard, so a format
         * skew costs one deferred operation per key rather than a permanent block or a silently
         * restored allowance.
         */
        public boolean discardRecord() {
            return mDiscardRecord;
        }

        /** Whether the operation may go ahead — {@link Outcome#UNANSWERABLE} may not. */
        public boolean mayProceed() {
            return mOutcome == Outcome.ALLOW || mOutcome == Outcome.CHARGE_AND_ALLOW;
        }

        /** Whether the caller must record an era-budget charge BEFORE the operation begins. */
        public boolean mustChargeEraBudget() {
            return mOutcome == Outcome.CHARGE_AND_ALLOW;
        }

        /**
         * Whether the gate reached a decision at all.
         *
         * <p>False means a defect in the caller or an unclassified tier, and a caller must not
         * proceed — but it is a different fact from "a guard refused", and the log must say which.
         */
        public boolean answered() {
            return mOutcome != Outcome.UNANSWERABLE;
        }

        @Override
        public String toString() {
            return "Verdict[" + mOutcome + (mGuard == null ? "" : " " + mGuard) + " " + mReason
                    + (mDiscardRecord ? " discard" : "") + "]";
        }
    }

    private static final Verdict ALLOWED =
            new Verdict(Outcome.ALLOW, null, Reason.NOTHING_REFUSED, false);
    private static final Verdict CHARGE_AND_ALLOWED =
            new Verdict(Outcome.CHARGE_AND_ALLOW, null, Reason.NOTHING_REFUSED, false);

    private static Verdict refuse(final Guard guard, final Reason reason) {
        return new Verdict(Outcome.REFUSE, guard, reason, false);
    }

    private static Verdict refuseAndDiscard(final Guard guard, final Reason reason) {
        return new Verdict(Outcome.REFUSE, guard, reason, true);
    }

    private static Verdict unanswerable(final Guard guard, final Reason reason) {
        return new Verdict(Outcome.UNANSWERABLE, guard, reason, false);
    }

    // ---- the decision --------------------------------------------------------------------------

    /**
     * <b>May an operation of this tier proceed, given these facts?</b>
     *
     * <p>Consults exactly the guards {@link #guardsFor} names for {@code tier}, in that order, and
     * stops at the first that refuses. Pure: no clock, no store, no system property, no log.
     *
     * @param tier which composition applies — the row of the table
     * @param facts what the host layer read; a fact a composed guard needs and did not get yields
     *     {@link Outcome#UNANSWERABLE} rather than a guess in either direction
     */
    public static Verdict decide(final Tier tier, final Facts facts) {
        if (facts == null) return unanswerable(null, Reason.FACT_NOT_SUPPLIED);
        final Guard[] guards = guardsFor(tier);
        if (guards.length == 0) return unanswerable(null, Reason.TIER_NOT_CLASSIFIED);

        for (final Guard g : guards) {
            final Verdict v = ask(g, facts);
            if (v != null) return v;
        }

        // Every composed guard passed. The era tier's allow is a CHARGE, never a bare ALLOW — a
        // caller cannot fall out of the switch into an advance nothing counted.
        return consults(tier, Guard.G2_ERA_BUDGET) ? CHARGE_AND_ALLOWED : ALLOWED;
    }

    /**
     * One guard's answer, or null when it passes.
     *
     * <p>Stated per guard rather than per tier so that adding a tier cannot change what a guard
     * means. A guard is the same question wherever it is asked; the tier decides only WHETHER it is
     * asked, which is the whole content of {@link #guardsFor}.
     */
    private static Verdict ask(final Guard guard, final Facts facts) {
        switch (guard) {
            case G6_FREEZE:
                // Nothing to gather and nothing that can be missing: a boolean sysprop read either
                // happened or defaulted false, and the default is the un-frozen fleet.
                return facts.frozen()
                        ? refuse(Guard.G6_FREEZE, Reason.STATE_CHANGES_FROZEN) : null;

            case G4_PEER_HEALTH:
                switch (facts.healthState()) {
                    case NOT_CONSULTED:
                        // NO PEER NAMED: nothing to look up, so nothing to judge. This is the arm
                        // allowStateChange takes when healthKey() is null, and it is a deliberate
                        // row — an operation that names no counterparty cannot have a counterparty
                        // health opinion. The allowlisted tiers do not inherit it: G1 refuses a
                        // blank on its own terms, further down this same loop.
                        return null;
                    case UNREADABLE:
                        // UNREADABLE IS NOT HEALTHY. Reading a record we cannot parse as "no
                        // failures" would answer "this peer is fine" on the strength of a parse
                        // error — the posture this rung was written to end).
                        return refuseAndDiscard(
                                Guard.G4_PEER_HEALTH, Reason.PEER_HEALTH_UNREADABLE);
                    case PRESENT:
                        return facts.streak() >= MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES
                                ? refuse(Guard.G4_PEER_HEALTH, Reason.PEER_WEDGED) : null;
                    default:
                        return unanswerable(Guard.G4_PEER_HEALTH, Reason.FACT_NOT_SUPPLIED);
                }

            case G1_LAB_ALLOWLIST:
                switch (facts.allowlist()) {
                    case LISTED:
                        return null;
                    case NOT_LISTED:
                        return refuse(Guard.G1_LAB_ALLOWLIST, Reason.NOT_A_LAB_PEER);
                    case NOT_CONSULTED:
                    default:
                        // The tier composes G1 and the caller did not answer it. Refusing here would
                        // read as "not a lab peer" and hide the omission behind a plausible line.
                        return unanswerable(Guard.G1_LAB_ALLOWLIST, Reason.FACT_NOT_SUPPLIED);
                }

            case G2_ERA_BUDGET:
                final BudgetKey key = facts.budgetKey();
                if (key == null) {
                    return unanswerable(Guard.G2_ERA_BUDGET, Reason.FACT_NOT_SUPPLIED);
                }
                if (!key.derived()) {
                    // NO KEY MEANS NO BOUND, and an unbounded re-creation is worse than one
                    // deferred. It is also the only honest answer available: we cannot say this is
                    // within a budget we could not look up.
                    return refuse(Guard.G2_ERA_BUDGET, Reason.NO_BUDGET_KEY);
                }
                switch (facts.budgetState()) {
                    case UNREADABLE:
                        // UNREADABLE IS NOT UNCHARGED, and the stakes here are the operation that
                        // wedged a phone.
                        return refuseAndDiscard(
                                Guard.G2_ERA_BUDGET, Reason.ERA_BUDGET_UNREADABLE);
                    case PRESENT:
                        return facts.budgetSpent()
                                ? refuse(Guard.G2_ERA_BUDGET, Reason.ERA_BUDGET_SPENT) : null;
                    case NOT_CONSULTED:
                    default:
                        return unanswerable(Guard.G2_ERA_BUDGET, Reason.FACT_NOT_SUPPLIED);
                }

            default:
                // A guard added to the enum and not to this switch. Not "passes".
                return unanswerable(guard, Reason.TIER_NOT_CLASSIFIED);
        }
    }
}
