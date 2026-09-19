/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * Whether an inbound Welcome may replace the group state we already hold, and the join and
 * re-join flows that ask. An era advance is kept without a round trip; at the same or an earlier
 * era the server decides, because a legal same-era refresh can restart the epoch and look exactly
 * like a replay or downgrade locally. Unknown or unasked never keeps the join.
 * See docs/mls/group-lifecycle.md.
 */
public final class MlsWelcomeAdmission {

    /** What the server says about the state we just joined into. */
    public enum ServerState {
        /** The state we hold is the server's current state. */
        MATCHES,
        /** The server holds something else: what we joined is not current. */
        DIFFERS,
        /**
         * We could not ask, or could not understand the answer. Evidence about the network, not the
         * Welcome, so never reported as a replay.
         */
        UNKNOWN,
        /**
         * Our own {@link MlsFetchLedger} refused the read, so nothing was asked; the remedy is
         * waiting out the window. {@link #decide} treats it as {@link Verdict#REJECT_UNVERIFIED}.
         */
        REFUSED_BY_LEDGER
    }

    /** The verdict and its reason, which is what gets logged. */
    public enum Verdict {
        /** The Welcome moved us to a later era. Keep the re-join. */
        ACCEPT_ERA_ADVANCE(MlsWelcomeAction.NEW_ERA_EXISTING_GROUP, true),
        /** Same era, and the server confirms this is the current state. Keep the re-join. */
        ACCEPT_REFRESH(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP, true),
        /** Same or earlier era, and the server holds something else. Roll back. */
        REJECT_REPLAY(null, false),
        /** Same or earlier era, and we could not verify. Roll back: unknown is not consent. */
        REJECT_UNVERIFIED(null, false),
        /**
         * The join produced no group for us. Not a refusal: this is how an add-members commit looks
         * from inside the group, and the commit riding with the Welcome is still ours to apply.
         */
        NOT_ADDRESSED_TO_US(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP, false),
        /**
         * We joined, but the resulting era could not be read. Rolled back like a refusal, but
         * reported as a parse problem rather than an addressing one.
         */
        REJECT_ERA_UNREADABLE(null, false);

        private final MlsWelcomeAction mAction;
        private final boolean mKeep;

        Verdict(final MlsWelcomeAction action, final boolean keep) {
            mAction = action;
            mKeep = keep;
        }

        /** Whether the joined state is kept (true) or rolled back (false). */
        public boolean keepsJoin() { return mKeep; }

        /** The {@link MlsWelcomeAction} this outcome corresponds to, or null. */
        public MlsWelcomeAction action() { return mAction; }

        /** Whether a server round trip was needed to reach this verdict. */
        public boolean consultedServer() {
            return this == ACCEPT_REFRESH || this == REJECT_REPLAY || this == REJECT_UNVERIFIED;
        }
    }

    private MlsWelcomeAdmission() {}

    /**
     * Decide from facts the caller gathered; the server consult is the caller's, gated by
     * {@link #needsServerConsult}.
     *
     * @param joinedGroup whether the Welcome admitted us to a group
     * @param oldEra      the era we held before, or negative if unknown
     * @param newEra      the era after joining, or negative if unreadable
     * @param serverState the server's answer; used only when the era did not advance
     */
    public static Verdict decide(final boolean joinedGroup, final int oldEra, final int newEra,
            final ServerState serverState) {
        if (!joinedGroup) return Verdict.NOT_ADDRESSED_TO_US;
        // Joined, but where to is unknown: same rollback, different diagnosis.
        if (newEra < 0) return Verdict.REJECT_ERA_UNREADABLE;
        if (newEra > oldEra) return Verdict.ACCEPT_ERA_ADVANCE;
        // An earlier era is a downgrade unless the server says it is current (then we were behind);
        // the server answers both cases the same way.
        if (serverState == null) return Verdict.REJECT_UNVERIFIED;
        switch (serverState) {
            case MATCHES: return Verdict.ACCEPT_REFRESH;
            case DIFFERS: return Verdict.REJECT_REPLAY;
            case UNKNOWN:
            // Named rather than left to the default, so the audit of which states keep a join is
            // visible.
            case REFUSED_BY_LEDGER:
            default: return Verdict.REJECT_UNVERIFIED;
        }
    }

    /**
     * Whether this outcome needs the server: only when we joined and the era did not advance, so
     * the common era-advance case costs no round trip on the inbound control path.
     */
    public static boolean needsServerConsult(final boolean joinedGroup, final int oldEra,
            final int newEra) {
        return joinedGroup && newEra >= 0 && newEra <= oldEra;
    }

    /** A log line naming what happened and why. */
    public static String line(final Verdict v, final int oldEra, final int newEra) {
        switch (v) {
            case ACCEPT_ERA_ADVANCE:
                return "era " + oldEra + " → " + newEra + ", accepted as "
                        + MlsWelcomeAction.NEW_ERA_EXISTING_GROUP;
            case ACCEPT_REFRESH:
                return "same era (" + newEra
                        + ") and the SERVER confirms this is the current state "
                        + "— accepted as " + MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP
                        + ", the arm a forward-only rule used to refuse";
            case REJECT_ERA_UNREADABLE:
                return "we JOINED from the Welcome but its era could not be READ (ours=" + oldEra
                        + " offered=" + newEra + ") — rolling back, because an era we cannot "
                        + "establish is one we cannot order against ours. This is NOT 'the Welcome "
                        + "was not for us': it was, and it applied. Look at the era parse, not at "
                        + "addressing";
            case REJECT_REPLAY:
                return "same era (ours=" + oldEra + " offered=" + newEra
                        + ") and the server holds a "
                        + "DIFFERENT state — this is a replayed or stale Welcome, not a refresh; "
                        + "rolled back";
            case REJECT_UNVERIFIED:
                return "same era (ours=" + oldEra + " offered=" + newEra + ") and the server could "
                        + "NOT be asked which state is current — rolled back, because unknown is not "
                        + "consent for replacing live group state. This is a transient failure, not "
                        + "an accusation about the sender; it will be retried on the next Welcome.";
            case NOT_ADDRESSED_TO_US:
            default:
                return MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.addMembersLine();
        }
    }
}
