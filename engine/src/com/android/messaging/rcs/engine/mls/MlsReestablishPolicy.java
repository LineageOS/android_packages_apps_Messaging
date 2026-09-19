/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Whether a re-establish is a first create or a re-creation every member must re-join by Welcome,
 * so a rebuild is charged to G2 (the era budget) like an era advance. Decided by whether the server
 * holds the conversation, not by whether a GroupInfo carry is present: a 1:1 rebuild and a group in
 * REJOIN have no carry and still re-create. An unreadable server is its own verdict and is charged.
 */
public final class MlsReestablishPolicy {

    /** The era a group with nothing to inherit is born at. */
    public static final long ERA_INITIAL = 1L;

    /**
     * Minimum interval between re-establish attempts with one peer. Each attempt claims one of the
     * peer's KeyPackages, and a burst of undecryptable inbound would otherwise drain its pool.
     */
    public static final long REESTABLISH_COOLDOWN_MS = 10L * 60L * 1000L;

    /** Whether the peers of this conversation must RE-join for the re-establish to take. */
    public enum ReJoin {
        /** The server holds this conversation, so its members are already in a group. */
        YES,
        /** The server holds nothing here; every member joins for the first time. */
        NO,
        /** The server did not answer. */
        UNKNOWN
    }

    /**
     * What a re-establish of this conversation would be. The two flags are declared independently
     * so a constant with them out of step fails a test rather than leaving a door uncharged.
     */
    public enum Verdict {
        /** Nothing server-side to re-create; not charged as an era advance. */
        FIRST_CREATE(ReJoin.NO, /*chargesEraBudget=*/ false),
        /** The server holds it: re-establishing lands above its era and every member re-joins. */
        RECREATES_EXISTING(ReJoin.YES, /*chargesEraBudget=*/ true),
        /** The server could not be read; charged, as an unknown outcome is not the cheap one. */
        SERVER_UNREADABLE(ReJoin.UNKNOWN, /*chargesEraBudget=*/ true);

        private final ReJoin mReJoin;
        private final boolean mChargesEraBudget;

        Verdict(final ReJoin reJoin, final boolean chargesEraBudget) {
            mReJoin = reJoin;
            mChargesEraBudget = chargesEraBudget;
        }

        public ReJoin reJoin() {
            return mReJoin;
        }

        /** Whether this is charged to G2 before it runs; only {@link ReJoin#NO} is free. */
        public boolean chargesEraBudget() {
            return mChargesEraBudget;
        }
    }

    private MlsReestablishPolicy() {}

    /**
     * @param serverAnswered whether the server-era read returned anything at all
     * @param serverEra      meaningless unless {@code serverAnswered}
     */
    public static Verdict classify(final boolean serverAnswered, final long serverEra) {
        if (!serverAnswered) return Verdict.SERVER_UNREADABLE;
        return serverEra >= ERA_INITIAL ? Verdict.RECREATES_EXISTING : Verdict.FIRST_CREATE;
    }

    /**
     * Whether a group re-establish would be born at {@link #ERA_INITIAL} beside a group the server
     * already holds: with no local group and no GroupInfo carry the engine plans era 1, which forks
     * (adopted when the server is at era 1, rolled back above it). {@link #classify} does not
     * depend on the carry; only this question does. {@link Verdict#SERVER_UNREADABLE} is not
     * refused, so a blipped read does not block a group's first encryption.
     *
     * @param haveCarry whether a server GroupInfo is in hand to give the engine
     */
    public static boolean forksAtEraInitial(final boolean isGroup, final Verdict verdict,
            final boolean haveCarry) {
        return isGroup && !haveCarry && verdict == Verdict.RECREATES_EXISTING;
    }

    /**
     * Whether a group re-create would go out under the contextId the server already holds. The
     * server does not apply such a create, whatever era it asks for, so the rebuild would drop both
     * halves of our state for nothing. A group rebuild reuses it because the provider derives a
     * group's contextId from the RCS group id, which does not change with the era; a 1:1 rebuild
     * mints a fresh one and is never refused.
     *
     * @param contextIdIsReused whether the caller read that the create will reuse the server's
     *     contextId; {@code false} does not guarantee a fresh one
     */
    public static boolean reCreateWouldNotTake(final boolean isGroup, final Verdict verdict,
            final boolean contextIdIsReused) {
        return isGroup && contextIdIsReused && verdict == Verdict.RECREATES_EXISTING;
    }

    /** The log line for {@link #reCreateWouldNotTake}. */
    public static String wouldNotTakeLine(final long serverEra) {
        return "the server holds this group at era " + serverEra
                + " and the re-create would go out "
                + "under the contextId it ALREADY HOLDS — for a group that id is the RCS group id "
                + "and it does not change with the era, so there is no era at which this create "
                + "takes. Taking this arm anyway "
                + "spends a KeyPackage claim and an era-budget "
                + "charge, drops BOTH halves of our state, and leaves the conversation in REJOIN with "
                + "the server still at the era it started from. A GroupInfo carry does not change "
                + "it: the carry decides the era the ENGINE derives, not whether the SERVER applies "
                + "the create. Refused BEFORE anything is dropped or charged. The exit "
                + "is a member who IS current re-admitting us — asked for on the §7.7.2.2 channel "
                + "at this refusal — or an operator setting debug.rcs.mls_ctxid_group_engine_id=0 "
                + "so the re-create can mint a fresh contextId.";
    }

    /** The log line for {@link #forksAtEraInitial}. */
    public static String forkLine(final long serverEra) {
        return "the server holds this group at era " + serverEra + " and we have NO GroupInfo to "
                + "carry, so the re-establish would be born at era " + ERA_INITIAL
                + " — a second group beside the server's, not a repair of it. At THAT era the "
                + "create is adopted (the transport compares era NUMBERS and they match) and the "
                + "fork is permanent; above it the create is rolled back and both halves of our "
                + "state were destroyed for nothing. Refused BEFORE anything is dropped.";
    }

    /** The log line for a {@link Verdict}. */
    public static String line(final Verdict v, final long serverEra) {
        switch (v) {
            case FIRST_CREATE:
                return "the server holds no group for this conversation, so this create makes "
                        + "nobody RE-join — not charged to the era budget";
            case RECREATES_EXISTING:
                return "the SERVER holds this conversation at era " + serverEra + ", so "
                        + "re-establishing re-creates it and every member must re-join by Welcome "
                        + "— charged to the era budget, the same budget an era advance charges";
            case SERVER_UNREADABLE:
            default:
                return "the server did not answer when asked whether it holds this conversation, "
                        + "so we cannot tell a first create from a re-creation — charged to the era "
                        + "budget, because the outcome we cannot measure must not be the cheap one";
        }
    }
}
