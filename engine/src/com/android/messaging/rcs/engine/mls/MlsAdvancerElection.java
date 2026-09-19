/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Who advances the era, and when another member may stop waiting for it. The designated advancer
 * is the lowest E.164, derived from the roster alone; presence only sets how long the others wait,
 * staggered by rank as {@code presenceLooks + (rank - 1) * baseLooks}. One look is one group-info
 * fetch, and nothing here reads a clock. See docs/mls/health-and-recovery.md.
 */
public final class MlsAdvancerElection {

    private MlsAdvancerElection() {}

    /** What we know about a member's participation in this conversation. */
    public enum Presence {
        /** The ledger is empty: we have heard from nobody here. */
        UNKNOWN,
        /** Heard from others here but never from this member. */
        NEVER_HEARD,
        /** Heard from before, but not since this yield began. */
        QUIET,
        /** Heard from since this yield began. */
        ACTIVE
    }

    /** A member heard from during this yield may be mid-advance, so it gets double the base. */
    private static final int ACTIVE_MULTIPLE = 2;

    /** Not zero: {@link MlsRecoveryPolicy#eraYieldObservationsExhausted} reads 0 as forever. */
    private static final int LOOKS_NEVER_HEARD = 1;

    public static final class Decision {
        /** The lowest E.164 in the conversation, or null if the roster was unusable. */
        public final String designated;
        /** Our 0-based position in the ascending roster; 0 = we are the designated advancer. */
        public final int rank;
        /** The strongest-evidence presence among the members ahead of us. */
        public final Presence ahead;
        /** Unmoved looks before we may take over; {@code 0} while yielding means yield forever. */
        public final int looks;

        Decision(final String designated, final int rank, final Presence ahead, final int looks) {
            this.designated = designated;
            this.rank = rank;
            this.ahead = ahead;
            this.looks = looks;
        }

        public boolean weAdvanceNow() { return rank <= 0; }

        @Override public String toString() {
            return weAdvanceNow()
                    ? "advancer(self)"
                    : "yield to " + LogMask.number(designated) + " (rank " + rank
                            + ", ahead=" + ahead
                            + ", takeover after " + looks + " unmoved look(s))";
        }
    }

    /**
     * An unknown {@code selfE164} fails open to "we advance": a duel is self-limiting, a
     * conversation that never recovers is not.
     *
     * @param heardAtSeq member to the sequence value at which we last heard from them
     * @param baseLooks  {@code MlsConfig.eraYieldLooks}; {@code <= 0} means yield forever
     */
    public static Decision decide(final String selfE164, final Collection<String> members,
            final Map<String, Long> heardAtSeq, final long yieldStartedAtSeq, final int baseLooks) {
        final List<String> order = order(members);
        if (selfE164 == null || selfE164.isEmpty() || order.isEmpty()) {
            // Fails open exactly as weAreEraAdvancer does.
            return new Decision(order.isEmpty() ? null : order.get(0), 0, Presence.UNKNOWN, 0);
        }
        final int rank = order.indexOf(selfE164);
        if (rank <= 0) {
            // rank -1: not in the roster we were given; act rather than wait on a group we may not
            // be in.
            return new Decision(order.get(0), 0, Presence.UNKNOWN, 0);
        }
        final Presence ahead = strongestAhead(order, rank, heardAtSeq, yieldStartedAtSeq);
        return new Decision(order.get(0), rank, ahead, looksBeforeTakeover(rank, ahead, baseLooks));
    }

    /** Ascending E.164, sorted rather than pack order so every member elects the same advancer. */
    public static List<String> order(final Collection<String> members) {
        final List<String> out = new ArrayList<String>();
        if (members == null) return out;
        for (final String m : members) {
            if (m != null && !m.isEmpty() && !out.contains(m)) out.add(m);
        }
        Collections.sort(out);
        return out;
    }

    public static String designated(final Collection<String> members) {
        final List<String> order = order(members);
        return order.isEmpty() ? null : order.get(0);
    }

    /**
     * Whether a yield at {@code rank} reaches its takeover before the enclosing self-heal budget
     * escalates. Call it through {@link MlsSelfHealPass#takeoverReachable}.
     *
     * @param enclosingAttempts attempts per window, or {@code <= 0} if looks cost nothing
     */
    public static boolean takeoverReachableWithin(final int rank, final Presence ahead,
            final int baseLooks, final int enclosingAttempts) {
        if (baseLooks <= 0) return false;          // configured to yield forever, deliberately
        if (rank <= 0) return true;                // designated; never waits at all
        if (enclosingAttempts <= 0) return true;   // looks are free
        // Strictly less: the last permitted attempt leaves no margin.
        return looksBeforeTakeover(rank, ahead, baseLooks) < enclosingAttempts;
    }

    public static Presence classify(final String member, final Map<String, Long> heardAtSeq,
            final long yieldStartedAtSeq) {
        if (heardAtSeq == null || heardAtSeq.isEmpty()) return Presence.UNKNOWN;
        if (member == null || member.isEmpty()) return Presence.UNKNOWN;
        final Long at = heardAtSeq.get(member);
        if (at == null) return Presence.NEVER_HEARD;
        return at.longValue() > yieldStartedAtSeq ? Presence.ACTIVE : Presence.QUIET;
    }

    /** Any live member ahead of us is a reason to wait: taking over claims the advance from all. */
    public static Presence strongestAhead(final List<String> order, final int rank,
            final Map<String, Long> heardAtSeq, final long yieldStartedAtSeq) {
        Presence worst = Presence.NEVER_HEARD;
        boolean any = false;
        for (int i = 0; i < rank && i < order.size(); i++) {
            final Presence p = classify(order.get(i), heardAtSeq, yieldStartedAtSeq);
            any = true;
            if (rankOfPresence(p) > rankOfPresence(worst)) worst = p;
        }
        return any ? worst : Presence.UNKNOWN;
    }

    /** How much patience a presence justifies, ascending. */
    private static int rankOfPresence(final Presence p) {
        switch (p) {
            case NEVER_HEARD: return 0;
            case QUIET:       return 1;
            case UNKNOWN:     return 2;
            case ACTIVE:      return 3;
            default:          return 2;
        }
    }

    /**
     * Unmoved looks before taking over: {@code presenceLooks + (rank - 1) * baseLooks}, strictly
     * increasing in {@code rank} so no two members hold the same budget.
     */
    public static int looksBeforeTakeover(final int rank, final Presence ahead,
            final int baseLooks) {
        if (rank <= 0) return 0;             // designated; not applicable
        if (baseLooks <= 0) return 0;        // yield forever; presence cannot override
        final int presenceLooks;
        switch (ahead == null ? Presence.UNKNOWN : ahead) {
            case NEVER_HEARD:
                presenceLooks = LOOKS_NEVER_HEARD;
                break;
            case ACTIVE:
                presenceLooks = ACTIVE_MULTIPLE * baseLooks;
                break;
            case QUIET:
            case UNKNOWN:
            default:
                // Quiet is not gone, and no evidence is not evidence.
                presenceLooks = baseLooks;
                break;
        }
        return presenceLooks + (rank - 1) * baseLooks;
    }
}
