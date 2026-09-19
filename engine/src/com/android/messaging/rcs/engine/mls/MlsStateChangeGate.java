/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.HashSet;
import java.util.Set;

/**
 * Which guards an MLS state change must pass, and in what order. {@link #guardsFor} is the tier
 * table and {@link #decide} is pure; {@code MlsPeerGuard} gathers the facts, persists records and
 * logs. The op label a call site passes is never an input. See docs/mls/budgets.md.
 */
public final class MlsStateChangeGate {

    private MlsStateChangeGate() {}

    /** The four guards, named as {@code MlsPeerGuard} names them. */
    public enum Guard {
        /**
         * G6: the kill switch, {@code debug.rcs.mls_freeze_state_changes}. First in every tier, so
         * it wins over an allowlisted peer and an unspent budget.
         */
        G6_FREEZE,
        /**
         * G4: the peer-health streak. A peer that has reported
         * {@link MlsPeerHealthRecord#MAX_CONSECUTIVE_FAILURES} consecutive failures of our control
         * messages stops receiving state changes.
         */
        G4_PEER_HEALTH,
        /**
         * G1: the peer allowlist, {@code persist.rcs.mls_allowed_peers}. A debug-build restriction
         * only: see {@link #allowlistFor}.
         */
        G1_PEER_ALLOWLIST,
        /**
         * G2: the era budget ({@link MlsEraBudgetRecord}), bounding every re-join-by-Welcome
         * operation.
         */
        G2_ERA_BUDGET,
    }

    /**
     * The operation tiers, one per {@code MlsPeerGuard} entry point ({@code allowDebugStateChange}
     * and {@code allowJoiningPeer} share a body, so share a tier).
     */
    public enum Tier {
        /**
         * Our own departure ({@code allowSelfDeparture}): G6 only. G1 would block leaving a group
         * with a peer outside the allowlist and G4 would block it when a peer looks wedged; G6
         * stays because a leave transmits.
         */
        SELF_DEPARTURE,
        /**
         * An organic protocol state change ({@code allowStateChange}): no allowlist, since refusing
         * to remove a peer outside the allowlist would trap them in the group.
         */
        ORGANIC_STATE_CHANGE,
        /**
         * A state change that must also clear the peer allowlist: anything a debug arm initiates
         * ({@code allowDebugStateChange}) and bringing any peer into a group
         * ({@code allowJoiningPeer}).
         */
        ALLOWLISTED_STATE_CHANGE,
        /**
         * Any operation that makes every member re-join by Welcome ({@code allowEraAdvance}): the
         * era advance, the automatic rebuild and {@code ensureReady}'s 1:1 reclaim all charge one
         * budget. Every era-advance mode is chargeable;
         * {@link MlsEraAdvanceCharge#chargeableAtTheFunnel} refuses an undeclared mode before this
         * gate is reached.
         */
        ERA_ADVANCE,
    }

    /**
     * What a durable record lookup produced: not consulted, present (a record storing nothing is
     * present with a zero value), or unreadable.
     */
    public enum RecordState {
        /**
         * The caller did not consult this record: correct when the tier does not compose its guard
         * or there is no key; otherwise {@link Outcome#UNANSWERABLE}.
         */
        NOT_CONSULTED,
        /** Read and decoded. A record that stores nothing is PRESENT with a zero value. */
        PRESENT,
        /**
         * A record exists under this key and did not parse. Not healthy, not uncharged, not absent.
         */
        UNREADABLE,
    }

    /** Whether the peer named by a state change is on the peer allowlist (G1). */
    public enum Allowlist {
        /** G1 was not consulted, correct for the tiers that do not compose it. */
        NOT_CONSULTED,
        /** No allowlist applies: a user build, or a debug build with an empty or unset list. */
        UNRESTRICTED,
        /** A peer identifier was supplied and it is in the allowed set. */
        LISTED,
        /**
         * G1 refuses: the peer is not in the allowed set, or no usable peer identifier was supplied
         * (a blank is not a number the allowlist can admit).
         */
        NOT_LISTED,
    }

    /** What {@link #decide} concluded. */
    public enum Outcome {
        /** Proceed. Nothing to record. */
        ALLOW,
        /**
         * Proceed only after the era-budget charge is on disk ({@code record.charged(now)}), so an
         * operation that crashes still counts. {@link Tier#ERA_ADVANCE} never yields
         * {@link #ALLOW}.
         */
        CHARGE_AND_ALLOW,
        /**
         * Do not proceed; {@link Verdict#guard()} and {@link Verdict#reason()} say which gate and
         * why.
         */
        REFUSE,
        /**
         * The gate cannot answer: an unclassified tier, or a fact the tier's row requires was not
         * supplied. Callers treat it as a refusal and as a defect.
         */
        UNANSWERABLE,
    }

    /** Why {@link #decide} concluded what it did; one value per refusal line. */
    public enum Reason {
        /** Nothing refused. */
        NOTHING_REFUSED,
        /** G6: {@code debug.rcs.mls_freeze_state_changes} is set. */
        STATE_CHANGES_FROZEN,
        /** G4: a peer-health record exists under this key and did not parse; discard it. */
        PEER_HEALTH_UNREADABLE,
        /** G4: the consecutive-failure streak has reached its trip point. */
        PEER_WEDGED,
        /** G1: the peer is not in the allowed set, or no usable peer identifier was supplied. */
        PEER_NOT_ALLOWLISTED,
        /**
         * G2: neither a group id nor a peer number was supplied, so nothing bounds the operation.
         */
        NO_BUDGET_KEY,
        /** G2: an era budget record exists under this key and did not parse; discard it. */
        ERA_BUDGET_UNREADABLE,
        /** G2: the hourly or daily allowance is exhausted. */
        ERA_BUDGET_SPENT,
        /** {@link Outcome#UNANSWERABLE}: the tier has no row. */
        TIER_NOT_CLASSIFIED,
        /** {@link Outcome#UNANSWERABLE}: a fact the tier's guards require was not consulted. */
        FACT_NOT_SUPPLIED,
    }

    private static final Guard[] SELF_DEPARTURE_GUARDS = {Guard.G6_FREEZE};

    private static final Guard[] ORGANIC_GUARDS = {Guard.G6_FREEZE, Guard.G4_PEER_HEALTH};

    private static final Guard[] ALLOWLISTED_GUARDS = {
        Guard.G6_FREEZE, Guard.G4_PEER_HEALTH, Guard.G1_PEER_ALLOWLIST,
    };

    private static final Guard[] ERA_ADVANCE_GUARDS = {
        Guard.G6_FREEZE, Guard.G4_PEER_HEALTH, Guard.G2_ERA_BUDGET,
    };

    /**
     * The guards {@code tier} composes, in consultation order: when two would refuse, the first is
     * the one logged.
     *
     * @return a fresh array; empty for an unclassified tier, which {@link #decide} reports as
     *     {@link Reason#TIER_NOT_CLASSIFIED}
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
                // A tier missing from this table: decide() reports it as unclassified.
                return new Guard[0];
        }
    }

    /** Whether {@code tier} composes {@code guard}: which facts a caller must gather. */
    public static boolean consults(final Tier tier, final Guard guard) {
        for (final Guard g : guardsFor(tier)) {
            if (g == guard) return true;
        }
        return false;
    }

    /** Where an era budget key came from. */
    public enum KeySource {
        /** The RCS group id. */
        GROUP_ID,
        /** The peer's digits, for a 1:1: {@code peer:<digits>}. */
        PEER,
        /** Neither identifier was usable; there is nothing to charge against. */
        NONE,
    }

    /** An era budget key and its source, so "no key" is never confused with a null key. */
    public static final class BudgetKey {

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

    private static final BudgetKey NO_KEY = new BudgetKey(null, KeySource.NONE);

    /**
     * G1's fact. The allowlist is a debug-build restriction and nothing else: a user build never
     * has one, and on a debug build an empty or unset list restricts nothing. Only a debug build
     * with a non-empty list refuses, and then it refuses a peer absent from the list or blank.
     *
     * @param debuggableBuild {@code ro.debuggable}, which adb cannot set on a user build
     * @param list comma-separated numbers; null or blank for none
     */
    public static Allowlist allowlistFor(final boolean debuggableBuild, final String list,
            final String peerE164) {
        if (!debuggableBuild) return Allowlist.UNRESTRICTED;
        final Set<String> listed = new HashSet<>();
        if (list != null) {
            for (final String s : list.split(",")) {
                final String n = normalisePeer(s);
                if (!n.isEmpty()) listed.add(n);
            }
        }
        if (listed.isEmpty()) return Allowlist.UNRESTRICTED;
        final String n = normalisePeer(peerE164);
        return !n.isEmpty() && listed.contains(n) ? Allowlist.LISTED : Allowlist.NOT_LISTED;
    }

    /**
     * The era budget key: the RCS group, or {@code peer:<digits>} for a 1:1, so a conversation
     * without a group id is still charged. Digits are normalised so format cannot evade a budget.
     */
    public static BudgetKey eraBudgetKey(final String groupId, final String peerE164) {
        if (groupId != null && !groupId.isEmpty()) return new BudgetKey(groupId,
                KeySource.GROUP_ID);
        final String n = normalisePeer(peerE164);
        return n.isEmpty() ? NO_KEY : new BudgetKey("peer:" + n, KeySource.PEER);
    }

    /**
     * Digits only, with a leading US country code dropped, so {@code "+1571…"} and {@code "571…"}
     * match.
     *
     * @return the normalised digits, or {@code ""}; never null
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

    /**
     * The facts {@link #decide} reasons over, read by the host layer. Every fact defaults to not
     * consulted, so a missing one yields {@link Outcome#UNANSWERABLE} rather than an allow.
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

        /** Assembles {@link Facts}. Unset facts are not consulted. */
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

            /** G1: whether the peer is in the allowed set; see {@link #allowlistFor}. */
            public Builder allowlist(final Allowlist allowlist) {
                mAllowlist = (allowlist == null) ? Allowlist.NOT_CONSULTED : allowlist;
                return this;
            }

            /**
             * G4: the peer's health record. Pass {@link RecordState#NOT_CONSULTED} when no peer is
             * named, which passes G4 (G1 still refuses a blank). {@code streak} is read only when
             * {@code state} is {@link RecordState#PRESENT}.
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
             * @param spent {@code record.pruned(now).spent(now)}, computed by
             * {@link MlsEraBudgetRecord}
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

    /** What the gate decided, why, and what the caller must do to the store. */
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
         * Whether the caller must drop the record {@link #guard()} names: only for the unreadable
         * arms, so a format skew costs one deferred operation rather than a permanent block or a
         * restored allowance.
         */
        public boolean discardRecord() {
            return mDiscardRecord;
        }

        /** Whether the operation may go ahead; {@link Outcome#UNANSWERABLE} may not. */
        public boolean mayProceed() {
            return mOutcome == Outcome.ALLOW || mOutcome == Outcome.CHARGE_AND_ALLOW;
        }

        /** Whether the caller must record an era-budget charge before the operation begins. */
        public boolean mustChargeEraBudget() {
            return mOutcome == Outcome.CHARGE_AND_ALLOW;
        }

        /**
         * Whether the gate reached a decision; false is a caller defect, distinct from a refusal.
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

    /**
     * Whether an operation of this tier may proceed: consults exactly {@link #guardsFor}'s guards
     * in order and stops at the first refusal. Pure: no clock, store, system property or log.
     *
     * @param facts what the host layer read
     */
    public static Verdict decide(final Tier tier, final Facts facts) {
        if (facts == null) return unanswerable(null, Reason.FACT_NOT_SUPPLIED);
        final Guard[] guards = guardsFor(tier);
        if (guards.length == 0) return unanswerable(null, Reason.TIER_NOT_CLASSIFIED);

        for (final Guard g : guards) {
            final Verdict v = ask(g, facts);
            if (v != null) return v;
        }

        // The era tier's allow is always a charge.
        return consults(tier, Guard.G2_ERA_BUDGET) ? CHARGE_AND_ALLOWED : ALLOWED;
    }

    /** One guard's answer, or null when it passes. A guard means the same thing in every tier. */
    private static Verdict ask(final Guard guard, final Facts facts) {
        switch (guard) {
            case G6_FREEZE:
                // Nothing can be missing: an unread sysprop defaults to not frozen.
                return facts.frozen()
                        ? refuse(Guard.G6_FREEZE, Reason.STATE_CHANGES_FROZEN) : null;

            case G4_PEER_HEALTH:
                switch (facts.healthState()) {
                    case NOT_CONSULTED:
                        // No peer named: nothing to judge, so G4 passes (allowStateChange's null
                        // health key).
                        return null;
                    case UNREADABLE:
                        // Unreadable is not healthy.
                        return refuseAndDiscard(
                                Guard.G4_PEER_HEALTH, Reason.PEER_HEALTH_UNREADABLE);
                    case PRESENT:
                        return facts.streak() >= MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES
                                ? refuse(Guard.G4_PEER_HEALTH, Reason.PEER_WEDGED) : null;
                    default:
                        return unanswerable(Guard.G4_PEER_HEALTH, Reason.FACT_NOT_SUPPLIED);
                }

            case G1_PEER_ALLOWLIST:
                switch (facts.allowlist()) {
                    case LISTED:
                    case UNRESTRICTED:
                        return null;
                    case NOT_LISTED:
                        return refuse(Guard.G1_PEER_ALLOWLIST, Reason.PEER_NOT_ALLOWLISTED);
                    case NOT_CONSULTED:
                    default:
                        // The tier composes G1 and the caller did not answer it; refusing would
                        // read as "not an allowed peer".
                        return unanswerable(Guard.G1_PEER_ALLOWLIST, Reason.FACT_NOT_SUPPLIED);
                }

            case G2_ERA_BUDGET:
                final BudgetKey key = facts.budgetKey();
                if (key == null) {
                    return unanswerable(Guard.G2_ERA_BUDGET, Reason.FACT_NOT_SUPPLIED);
                }
                if (!key.derived()) {
                    // No key means no bound: defer rather than run unbudgeted.
                    return refuse(Guard.G2_ERA_BUDGET, Reason.NO_BUDGET_KEY);
                }
                switch (facts.budgetState()) {
                    case UNREADABLE:
                        // Unreadable is not uncharged.
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
                // A guard missing from this switch does not pass.
                return unanswerable(guard, Reason.TIER_NOT_CLASSIFIED);
        }
    }
}
