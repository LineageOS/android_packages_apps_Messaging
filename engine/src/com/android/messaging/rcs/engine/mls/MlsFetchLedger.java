/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * The per-conversation ledger for server group-info reads, charged at the primitive across every
 * caller, with a ration per caller and a shared ceiling. {@link MlsFetchBudget} bounds one drive;
 * this bounds the resource. The policy methods are total and read no clock; the counters live in
 * {@link MlsFetchLedgerRecord}. See docs/mls/budgets.md.
 */
public final class MlsFetchLedger {

    private MlsFetchLedger() {}

    /**
     * The provider primitives that each cost the provider exactly one server group-info read. The
     * source-scan guard reads these names from this enum, so a new spelling must be added here.
     */
    public enum Primitive {
        FETCH_MISSED_COMMITS("fetchMissedCommits"),
        FETCH_SERVER_EPOCH_AUTHENTICATOR("fetchServerEpochAuthenticator"),
        GET_MLS_GROUP_INFO_FOR_GROUP("getMlsGroupInfoForGroup"),
        GET_MLS_SERVER_ERA_EPOCH("getMlsServerEraEpoch"),
        GET_MLS_GROUP_INFO("getMlsGroupInfo");

        /** The AIDL method name. */
        public final String aidlName;

        Primitive(final String aidlName) {
            this.aidlName = aidlName;
        }
    }

    /** A ration that is never counted: the caller may always look, and the log says so. */
    public static final int EXEMPT = -1;

    /**
     * One constant per method that decides to spend, with its own ration per {@link #WINDOW_MS} per
     * conversation. Funnels ({@code fetchServerPack}, {@code detectHealth}, ...) have none: they
     * take the deciding caller's constant and pass it down. Ordinal-persisted by {@link
     * MlsFetchLedgerRecord}: never remove or reorder a constant.
     */
    public enum Caller {
        /**
         * {@code reconcileAction} in {@code driveReconcileInner}: two passes of up to two reads.
         */
        RECONCILE_DRIVE(4, true),
        /** {@code probeAnchor}, the divergence probe; outside the ceiling. */
        HEALTH_PROBE(2, false),
        /** {@code selfHealInner}. */
        SELF_HEAL(3, true),
        /** {@code reporterIsAMember}, {@code onPeerReportedFailure}; peer-driven, fails open. */
        PEER_REPORT_VERIFY(2, true),
        /** {@code runMaintenanceOnce}. */
        MAINTENANCE(2, true),
        /** {@code runMaintenanceOnce}'s same-era identity check; outside the ceiling. */
        MAINTENANCE_IDENTITY(1, false),
        /** {@code eraAdvanceLocked}: the innermost of three bounds on the rebuild rung. */
        ERA_ADVANCE(4, true),
        /** {@code eraQuotaBound}. */
        ERA_QUOTA_CHECK(1, true),
        /** {@code resyncViaExternalCommit}. */
        EXTERNAL_COMMIT_RESYNC(3, true),
        /** {@code offerTheStallChoiceOffThread}. */
        STALL_CHOICE(1, true),
        /** {@code refreshStallNotification}, after Try again; outside the ceiling. */
        STALL_REFRESH(2, false),
        /** {@code rebuildConversation} and {@code serverPackForRebuild}. */
        REBUILD(4, true),
        /** {@code reestablishOutbound}. */
        REESTABLISH(2, true),
        /** {@code requestReWelcome}: asking a current member to Welcome us again. */
        RE_WELCOME_REQUEST(2, true),
        /** Retired: nothing charges it. Kept because removing it would shift persisted ordinals. */
        @Deprecated
        RE_WELCOME_SWEEP(1, true),
        /** {@code rejoinOnEraAdvance}. */
        REJOIN_ON_ERA_ADVANCE(2, true),
        /** {@code establishGroup}. */
        GROUP_ESTABLISH(2, true),
        /** {@code escalateForDivergedPeer}. */
        DIVERGED_PEER_ESCALATION(2, true),
        /** {@code onDecryptFailure}: "did we converge?" after a failed decrypt. */
        DECRYPT_FAILURE_CHECK(2, true),
        /** {@code changeGroupSubject} and {@code changeGroupIcon}: one ration for the pair. */
        SUBJECT_CHANGE(1, true),
        /** {@code endMls}. */
        END_MLS(2, true),
        /** {@code ensureReady}: create, era-bump and reclaim arms; fails open inside a rebuild. */
        ENSURE_READY(4, true),
        /** {@code quarantineIfAheadOfServer}, an audit read; outside the ceiling. */
        QUARANTINE_CHECK(2, false),
        /** {@code keepUnacknowledgedCommit}: two reads per invocation; outside the ceiling. */
        COMMIT_OUTCOME_CHECK(4, false),
        /** {@code dumpGroupExtensions}, a debug arm: exempt. */
        DEBUG_DUMP(EXEMPT, false),
        /** The debug receiver's health read; exempt. */
        DEBUG_HEALTH(EXEMPT, false);

        /** Looks this caller may spend on one conversation within one {@link #WINDOW_MS}. */
        public final int ration;

        /** Whether this caller's spend also counts against {@link #SHARED_CEILING}. */
        public final boolean chargesTheSharedCeiling;

        Caller(final int ration, final boolean chargesTheSharedCeiling) {
            this.ration = ration;
            this.chargesTheSharedCeiling = chargesTheSharedCeiling;
        }

        /** Whether this caller may always look. */
        public boolean isExempt() {
            return ration == EXEMPT;
        }

        /**
         * Whether a refusal leaves this caller proceeding as if it had looked and seen nothing
         * objectionable. True only for {@link #PEER_REPORT_VERIFY}: our own budget is not evidence
         * against a peer's report.
         */
        public boolean failsOpenOnRefusal() {
            return this == PEER_REPORT_VERIFY;
        }
    }

    /** The window the counters roll on: the known throttle cooldown, referenced so both move. */
    public static final long WINDOW_MS = MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS;

    /** Looks all ceiling-charging callers may spend on one conversation per window. */
    public static final int SHARED_CEILING = 8;

    /** Whether a look may be spent, and if not, which bound refused it. */
    public enum Verdict {
        /** Spend it, and charge it. */
        SPEND,
        /** Spend it and charge nothing: a debug arm. */
        SPEND_EXEMPT,
        /** This caller has spent its own allowance on this conversation in this window. */
        DENIED_CALLER_RATION,
        /** The conversation's shared allowance is gone, spent by other callers too. */
        DENIED_SHARED_CEILING;

        /** Whether the look happens. */
        public boolean permitted() {
            return this == SPEND || this == SPEND_EXEMPT;
        }

        /** Whether the ledger should record a charge for it. */
        public boolean charges() {
            return this == SPEND;
        }
    }

    /**
     * May {@code caller} spend one server group-info read on this conversation? Checks, in order: a
     * null caller (refused, never defaulted), exemption, the caller's ration, then the ceiling.
     * Negative spends read as zero; a ceiling below 1 is clamped to 1.
     */
    public static Verdict mayFetch(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling, final int sharedCeiling) {
        if (caller == null) {
            return Verdict.DENIED_CALLER_RATION;
        }
        if (caller.isExempt()) return Verdict.SPEND_EXEMPT;
        final int mine = Math.max(0, spentByThisCaller);
        if (mine >= Math.max(1, caller.ration)) return Verdict.DENIED_CALLER_RATION;
        if (caller.chargesTheSharedCeiling) {
            final int shared = Math.max(0, spentAgainstCeiling);
            if (shared >= Math.max(1, sharedCeiling)) return Verdict.DENIED_SHARED_CEILING;
        }
        return Verdict.SPEND;
    }

    /** {@link #mayFetch} against {@link #SHARED_CEILING}. */
    public static Verdict mayFetch(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling) {
        return mayFetch(caller, spentByThisCaller, spentAgainstCeiling, SHARED_CEILING);
    }

    // -- log lines
    // ----------------------------------------------------------------------------------

    /**
     * The refusal line: says the bound is ours, not the server's, and which of our two bounds
     * refused, so "this caller is looping" and "the conversation is spent" are told apart.
     */
    public static String describeRefusal(final Caller caller, final Verdict verdict,
            final int spentByThisCaller, final int spentAgainstCeiling, final int sharedCeiling,
            final String conversation, final Primitive wanted) {
        final String who = "MLS fetch ledger REFUSED " + name(wanted) + " for "
                + name(caller) + " on " + safe(conversation) + ": ";
        final String common = " This is OUR bound, not the server's — one GetMlsGroupInfo is the "
                + "scarce resource in recovery, and ten of them on one conversation drew "
                + "grpcStatus=8 RESOURCE_EXHAUSTED with the identical single fetch succeeding "
                + (WINDOW_MS / 1000L) + "s later. NOTHING WAS ASKED, so this operation "
                + "learned nothing — it must not be read as the server having nothing to say. The "
                + "work is still owed and is re-driven from a fresh trigger.";
        if (verdict == Verdict.DENIED_SHARED_CEILING) {
            return who + "the conversation's shared allowance of " + Math.max(1, sharedCeiling)
                    + " look(s) per " + (WINDOW_MS / 1000L) + "s is gone ("
                    + Math.max(0, spentAgainstCeiling) + " spent, of which "
                    + Math.max(0, spentByThisCaller) + " by this caller). OTHER CALLERS SPENT MOST "
                    + "OF IT — this one is not necessarily looping." + common;
        }
        if (caller == null) {
            return who + "no caller was declared, so no ration could be consulted and the look is "
                    + "refused rather than defaulted — defaulting would hand a new call site a free "
                    + "allowance by omission." + common;
        }
        // The ration is checked first, so a ration refusal may also be at the ceiling; say so
        // instead of blaming this caller for a loop.
        final int mine = Math.max(0, spentByThisCaller);
        final int shared = Math.max(0, spentAgainstCeiling);
        final int ceiling = Math.max(1, sharedCeiling);
        final String ration = who + "it has spent its own " + Math.max(1, caller.ration)
                + "-look allowance (" + mine + " spent per " + (WINDOW_MS / 1000L) + "s)";
        if (!caller.chargesTheSharedCeiling) {
            // This caller's ration is its only bound; the ceiling had no part in the refusal.
            return ration + ", which is the only bound this caller draws on — it does not charge "
                    + "the conversation's shared ceiling, so that ceiling had no part in this "
                    + "refusal. THIS CALLER is the one repeating itself." + common;
        }
        if (shared >= ceiling) {
            return ration + " AND the conversation's shared allowance is ALSO gone (" + shared
                    + " of " + ceiling
                    + "). BOTH bounds are exhausted. The ration is named because "
                    + "it is checked first, not because it is the only thing refusing — so this "
                    + "caller is NOT necessarily the one repeating itself, and the remedy is to let "
                    + "the " + (WINDOW_MS / 1000L) + "s window pass rather than to look for a loop "
                    + "here." + common;
        }
        return ration + " while the conversation's shared allowance still has room (" + shared
                + " of " + ceiling + "). THIS CALLER is the one repeating itself." + common;
    }

    /**
     * The line a debug arm logs instead of a charge: exempt is not free, the server still counts
     * it.
     */
    public static String describeExemption(final Caller caller, final Primitive wanted,
            final String conversation, final int spentAgainstCeiling, final int sharedCeiling) {
        return "MLS fetch ledger EXEMPT: " + name(caller) + " is a debug arm, so its "
                + name(wanted) + " on " + safe(conversation) + " is NOT charged and cannot be "
                + "refused. It is not free — the server still counts it, and the conversation has "
                + Math.max(0, spentAgainstCeiling) + " of " + Math.max(1, sharedCeiling)
                + " shared look(s) spent in the last " + (WINDOW_MS / 1000L)
                + "s. A debug arm that "
                + "silently spent a recovery look would be lying; one refused for budget would be "
                + "useless.";
    }

    /** The accounting line for a charge that happened. */
    public static String describeCharge(final Caller caller, final Primitive wanted,
            final String conversation, final int spentByThisCallerAfter,
            final int spentAgainstCeilingAfter, final int sharedCeiling) {
        return "MLS fetch ledger charged " + name(wanted) + " to " + name(caller) + " on "
                + safe(conversation) + " — " + Math.max(0, spentByThisCallerAfter) + " of "
                + Math.max(1, caller.ration) + " for this caller, "
                + Math.max(0, spentAgainstCeilingAfter) + " of " + Math.max(1, sharedCeiling)
                + " shared, per " + (WINDOW_MS / 1000L) + "s.";
    }

    /**
     * What a fail-open caller logs when it was refused and proceeded: nothing was verified. Kept
     * apart from {@link #describeRefusal} so a reader cannot believe a verification happened.
     */
    public static String describeFailOpen(final Caller caller, final String conversation,
            final String whatItProceededWith) {
        return "MLS fetch ledger: " + name(caller) + " on " + safe(conversation) + " was refused a "
                + "look and is PROCEEDING ANYWAY (" + safe(whatItProceededWith) + "). This is "
                + "deliberate and it is the only caller that does it: the cost of acting on a real "
                + "report we could not verify is a redundant repair, while the cost of dropping one "
                + "is a peer that stays diverged forever. NOTHING WAS VERIFIED HERE — our own budget "
                + "refused the check, which is not evidence about the peer.";
    }

    /**
     * The line for a look that was spent and came back unreadable, adding what the whole
     * conversation had already spent across callers: a throttle is provoked by the conversation,
     * not one caller.
     */
    public static String describeUnreadableLook(final Caller caller, final Primitive wanted,
            final String conversation, final int spentAgainstCeilingBefore) {
        return "MLS fetch ledger: " + name(wanted) + " for " + name(caller) + " on "
                + safe(conversation) + " came back unreadable after the CONVERSATION had spent "
                + Math.max(0, spentAgainstCeilingBefore) + " shared look(s) in the last "
                + (WINDOW_MS / 1000L) + "s across all callers. "
                + MlsFetchBudget.describeUnreadableLook(spentAgainstCeilingBefore, name(caller));
    }

    private static String name(final Caller c) {
        return c == null ? "<no caller declared>" : c.name();
    }

    private static String name(final Primitive p) {
        return p == null ? "<no primitive named>" : p.aidlName;
    }

    private static String safe(final String s) {
        return (s == null || s.isEmpty()) ? "<no conversation key>" : MlsConversationKey.forLog(s);
    }
}
