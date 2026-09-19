/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.HashMap;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
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

    /** Renders a 4-byte era extension as {@code <decimal> (0x…)}, or why it could not be read. */
    public static String eraExtOf(final byte[] v) {
        if (v == null || v.length == 0) return "ABSENT";
        if (v.length != 4) return "UNEXPECTED-LENGTH(" + v.length + "B)";
        final long be = ((long) (v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16)
                | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
        return be + " (0x" + String.format("%08X", be) + ")";
    }

    /** A count, not a duration, so a device asleep for a week wakes with its budget intact. */
    public static int eraYieldMaxObservations(final MlsConfig cfg) { return cfg.eraYieldLooks; }

    /** Creates no state for a conversation without one. */
    public static void clearEraYield(final MlsShellPort shell, final String key) {
        final ConvState s = shell.convIfAny(key);
        if (s != null) synchronized (s) { s.eraYield = null; }
    }

    /**
     * Records that we heard from {@code fromE164}, including undecryptable bytes and failure
     * reports. This is presence, not health: do not clear {@code MlsPeerGuard}'s failure streak
     * from here.
     */
    public static void noteHeardFrom(final MlsShellPort shell, final String key,
            final String fromE164) {
        if (key == null || fromE164 == null || fromE164.isEmpty()) return;
        final String self = shell.selfE164();
        if (self != null && self.equals(fromE164)) return;
        final ConvState s = shell.conv(key);
        synchronized (s) {
            s.heardSeq++;
            s.heardAtSeq.put(fromE164, Long.valueOf(s.heardSeq));
        }
    }

    /** Logs, never throws, when the yield about to start can never end. */
    public static void reportTakeoverReachability(final MlsConfig cfg, final MlsLogSink log,
            final String key, final String what, final MlsAdvancerElection.Decision d) {
        final MlsSelfHealPass.Reach reach = MlsSelfHealPass.reach(
                d, MlsAdvancerElection.eraYieldMaxObservations(cfg), cfg.selfHealRetryLimit);
        if (reach.why == null) return;
        final String line = "MlsAdvancerElection: yielding " + what + " on "
                + MlsConversationKey.forLog(key) + " — " + reach
                + ": " + reach.why + " (" + d + ", eraYieldLooks="
                + MlsAdvancerElection.eraYieldMaxObservations(cfg)
                + ", selfHealRetries=" + cfg.selfHealRetryLimit + ")";
        if (reach.isACollision) log.w(line); else log.i(line);
    }

    /**
     * Re-evaluates a live yield from local state, with no fetch and no self-heal charge. Returns
     * {@code -1} while still yielding, or {@code null} to run the full ladder.
     */
    public static Integer relookLiveYield(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key) {
        final ConvState s = shell.convIfAny(key);
        if (s == null) return null;
        final EraYield held;
        final java.util.Map<String, Long> heard;
        synchronized (s) {
            held = s.eraYield;
            heard = new java.util.HashMap<>(s.heardAtSeq);
        }
        if (held == null || held.electorate == null || held.electorate.isEmpty()) return null;
        final Group g = shell.getGroup(key);
        if (g == null || g.groupId == null) return null;
        final MlsAppMessage.Moment now =
                MlsAppMessage.Moment.from(shell.session().eraEpoch(g.groupId));
        if (now == null) return null;      // cannot read the moment; do not guess
        if (MlsRecoveryPolicy.eraYieldSatisfied(held.at, now)) {
            log.i("MlsAdvancerElection: the group moved (" + held.at + " → " + now
                    + ") while yielding " + MlsConversationKey.forLog(key)
                    + " — the yield worked; running the full ladder to "
                    + "apply what moved it.");
            MlsAdvancerElection.clearEraYield(shell, key);
            return null;
        }
        final MlsAdvancerElection.Decision d = MlsAdvancerElection.decide(
                shell.selfE164(), held.electorate, heard, held.heardSeqAtStart,
                MlsAdvancerElection.eraYieldMaxObservations(cfg));
        if (d.weAdvanceNow()) return null;       // we are the advancer now; the ladder must run
        final MlsSelfHealPass.Look look = MlsSelfHealPass.look(held.observations, d.looks);
        if (!look.recorded) return null;
        synchronized (s) {
            s.eraYield = new EraYield(held.at, look.number, held.heardSeqAtStart, held.electorate);
        }
        log.i("MlsAdvancerElection: still yielding " + MlsConversationKey.forLog(key) + " — look "
                + look.number
                + "/" + d.looks + ", group unmoved at " + now + " (" + d
                + "). Re-looked from local state:"
                + " no fetch and no self-heal attempt charged.");
        return Integer.valueOf(-1);
    }

    /**
     * The bounded era yield; returns true when we should stop yielding and act. Caller holds the
     * conversation lock (read-modify-write on {@code ConvState.eraYield}).
     *
     * @param maxLooks from {@link #looksBeforeTakeover}; {@code <= 0} means yield forever
     */
    public static boolean eraYieldExhausted(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String what, final int maxLooks,
            final java.util.List<String> electorate) {
        // The engine's moment, not the record's: the record is written only on health transitions
        // and can be stale.
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        final Group yg = shell.getGroup(key);
        MlsAppMessage.Moment now = (yg == null || yg.groupId == null)
                ? null : MlsAppMessage.Moment.from(shell.session().eraEpoch(yg.groupId));
        if (now == null) {
            now = (rec == null) ? null : rec.moment;
        }
        final ConvState s = shell.conv(key);
        final EraYield held;
        synchronized (s) { held = s.eraYield; }
        if (held == null) {
            synchronized (s) { s.eraYield = new EraYield(now, 1, s.heardSeq, electorate); }
            log.i("MlsAdvancerElection: yielding " + what + " at " + now);
            return false;
        }
        if (MlsRecoveryPolicy.eraYieldSatisfied(held.at, now)) {
            log.i("MlsAdvancerElection: the group moved (" + held.at + " → " + now
                    + ") while yielding " + what + " — the yield worked; not acting");
            synchronized (s) { s.eraYield = null; }
            return false;
        }
        final MlsSelfHealPass.Look look = MlsSelfHealPass.look(held.observations, maxLooks);
        if (look.exhausted) {
            // A duel is self-limiting (whoever lands first wins), a deadlock is not.
            log.w("MlsAdvancerElection: era yield EXHAUSTED after " + look.number + "/"
                    + maxLooks + " looks with the group still at " + now
                    + " — TAKING OVER the era advance. Was yielding " + what);
            shell.telemetry().count(MlsMetrics.ADVANCER_TAKEOVER, 1);
            synchronized (s) { s.eraYield = null; }
            return true;
        }
        synchronized (s) {
            s.eraYield = new EraYield(held.at, look.number, held.heardSeqAtStart, held.electorate);
        }
        log.i("MlsAdvancerElection: still yielding " + what + " — look " + look.number
                + "/" + maxLooks + ", group unmoved at " + now);
        return false;
    }

    /** Whether we advance now or yield; {@code members} includes ourselves, in any order. */
    public static boolean advanceOrYield(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final String selfE164,
            final java.util.Collection<String> members, final String what) {
        final ConvState s = shell.conv(key);
        final long startedAt;
        final java.util.Map<String, Long> heard;
        synchronized (s) {
            startedAt = (s.eraYield == null) ? s.heardSeq : s.eraYield.heardSeqAtStart;
            heard = new java.util.HashMap<>(s.heardAtSeq);
        }
        final MlsAdvancerElection.Decision d = MlsAdvancerElection.decide(
                selfE164, members, heard, startedAt,
                MlsAdvancerElection.eraYieldMaxObservations(cfg));
        if (d.weAdvanceNow()) {
            MlsAdvancerElection.clearEraYield(shell, key);
            return true;
        }
        MlsAdvancerElection.reportTakeoverReachability(cfg, log, key, what, d);
        return MlsAdvancerElection.eraYieldExhausted(shell, log, key, what
                + " (we are not the designated advancer: " + LogMask.number(selfE164)
                + " > " + LogMask.number(d.designated) + "; " + d + ")", d.looks,
                MlsAdvancerElection.order(members));
    }

    /**
     * On an era gap, whether we advance or yield to the peer. Neither RFC 9420 nor RCC.16 defines a
     * tie-break, so the lower E.164 advances.
     */
    public static boolean shouldAdvanceEra(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final String selfE164, final String peerE164) {
        shell.lock(key);
        try {
        return MlsAdvancerElection.advanceOrYield(cfg, shell, log, key, selfE164,
                java.util.Arrays.asList(selfE164, peerE164),
                "the era advance to " + LogMask.number(peerE164));
        } finally { shell.unlock(key); }
    }
}
