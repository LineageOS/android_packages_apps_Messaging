/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
import java.util.Arrays;

/**
 * Server comparison for group health. Above the server's epoch, our retained authenticator for the
 * server's {@code (era, epoch)} separates "further along the same chain" from a fork; only
 * {@link Verdict#DIFFERENT_CHAIN} is evidence of one. See docs/mls/health-and-recovery.md.
 */
public final class MlsAheadChainCheck {

    /** What the chain test concluded, or why it concluded nothing. */
    public enum Verdict {
        /** Never returned by {@link #decide}. */
        TESTABLE,
        SERVER_ERA_UNKNOWN,
        NO_RETAINED_ANCHOR,
        LOOK_REFUSED,
        SERVER_ANCHOR_ABSENT,
        SAME_CHAIN,
        DIFFERENT_CHAIN,
    }

    private MlsAheadChainCheck() {}

    /**
     * Whether the test can run, asked before paying for the server look.
     *
     * @param serverEra   negative when it could not be read
     * @param retainedEra {@code MlsConversationRecord.epochAuthEra}, the one era the map belongs to
     */
    public static Verdict blockedBefore(final long serverEra, final int retainedEra,
            final byte[] oursAtServerEpoch) {
        if (serverEra < 0L) {
            return Verdict.SERVER_ERA_UNKNOWN;
        }
        // An unstamped map may hold entries from an older era.
        if (retainedEra == MlsConversationRecord.ERA_UNKNOWN) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        if (retainedEra != serverEra) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Verdict.TESTABLE;
    }

    /** The verdict once the server look has been paid for. */
    public static Verdict decide(final byte[] oursAtServerEpoch, final boolean lookRefused,
            final byte[] serverAnchor) {
        if (lookRefused) {
            return Verdict.LOOK_REFUSED;
        }
        if (serverAnchor == null || serverAnchor.length == 0) {
            return Verdict.SERVER_ANCHOR_ABSENT;
        }
        // Defensive: "nothing to compare" must never read as a fork.
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Arrays.equals(oursAtServerEpoch, serverAnchor)
                ? Verdict.SAME_CHAIN : Verdict.DIFFERENT_CHAIN;
    }

    /** Only this verdict may demote {@code AHEAD}. */
    public static boolean isFork(final Verdict v) {
        return v == Verdict.DIFFERENT_CHAIN;
    }

    /**
     * Called only with our epoch strictly above the server's. Leaves the identity unset: a
     * {@code SAME_CHAIN} server holds an earlier roster, which must not be written as our baseline.
     */
    public static ServerComparison aheadOrForked(final MlsShellPort shell, final MlsLogSink log,
            final MlsFetchLedger.Caller caller,
            final String rcsGroupId, final String peerE164, final Group g, final long[] server) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        // By g.groupId: re-resolving the key can find a different group from the one compared.
        final MlsConversationRecord rec = MlsRecordState.recordForGroup(shell, log, g);
        final int retainedEra =
                (rec == null) ? MlsConversationRecord.ERA_UNKNOWN : rec.epochAuthEra;
        final byte[] oursThen =
                (rec == null) ? null : rec.epochAuthenticators.get(Long.valueOf(server[1]));

        final MlsAheadChainCheck.Verdict blocked =
                MlsAheadChainCheck.blockedBefore(server[0], retainedEra, oursThen);
        if (blocked != MlsAheadChainCheck.Verdict.TESTABLE) {
            log.i("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                    + ") local epoch is ABOVE the "
                    + "server's and the CHAIN TEST WAS NOT RUN — " + blocked + " (server era="
                    + server[0] + " epoch=" + server[1] + ", our retained map is for era="
                    + retainedEra
                    + "). Still AHEAD, which is what this arm always said; what is new "
                    + "is that the reason is named, so 'we are further along the same chain' is no "
                    + "longer indistinguishable from 'we never checked'.");
            return ServerComparison.chainTested(Health.AHEAD, blocked);
        }

        final Look<byte[]> look =
                shell.lookServerEpochAuthenticator(caller, key, peerE164, rcsGroupId);
        final MlsAheadChainCheck.Verdict v = MlsAheadChainCheck.decide(
                oursThen, look.refused(), look.refused() ? null : look.orNull());
        if (MlsAheadChainCheck.isFork(v)) {
            log.e("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                    + ") local epoch is ABOVE the "
                    + "server's (era=" + server[0] + " epoch=" + server[1] + ") and our OWN "
                    + "authenticator for that epoch does NOT match the server's → we are not ahead "
                    + "of anything, we are FORKED. Reporting DIVERGED so the maintenance pass aborts "
                    + "on the fork instead of counting the server's roster into the add arm and "
                    + "spending an era advance on a group that is not the server's.");
            return ServerComparison.chainTested(Health.DIVERGED, v);
        }
        log.i("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                + ") local epoch is ABOVE the "
                + "server's (era=" + server[0] + " epoch=" + server[1] + ") → AHEAD, chain test "
                + v + ".");
        return ServerComparison.chainTested(Health.AHEAD, v);
    }

    /**
     * Classifies our group against a server {@code [era, epoch]} (never null). The identity look
     * is asked only at equal epochs: one epoch has one authenticator.
     *
     * @return the verdict and the identity answer that gates writing the server's roster
     */
    public static ServerComparison healthAgainstServer(final MlsShellPort shell,
            final MlsLogSink log, final MlsFetchLedger.Caller caller,
            final String rcsGroupId, final String peerE164, final Group g, final long[] server) {
        if (g == null || g.groupId == null) {
            log.i("MlsAheadChainCheck: no local group but the server has one (era="
                    + server[0] + " epoch=" + server[1] + ") → REJOIN");
            return ServerComparison.notAsked(Health.REJOIN);
        }
        final byte[] ee = shell.session().eraEpoch(g.groupId);
        final int localEra = MlsAppMessage.eraFrom(ee);
        final long localEpoch = MlsAppMessage.epochFrom(ee);
        log.i("MlsAheadChainCheck: health(" + LogMask.number(peerE164) + ") local era=" + localEra
                + " epoch=" + localEpoch + " · server era=" + server[0] + " epoch=" + server[1]);
        if (localEra < 0) return ServerComparison.notAsked(Health.UNKNOWN);
        if (server[0] >= 0 && localEra != server[0]) {
            return ServerComparison.notAsked(Health.ERA_GAP);
        }
        // Unsigned: the epoch is a u64 on the wire, and the direction decides who catches up.
        final int cmp = Long.compareUnsigned(localEpoch, server[1]);
        if (cmp == 0) {
            // Equal numbers are a position, not an identity; only a definite DIFFERS demotes.
            final MlsWelcomeAdmission.ServerState anchor =
                    MlsWelcomeAdmission.serverStateCheckFor(shell, caller, rcsGroupId, peerE164, g.groupId);
            if (anchor == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
                log.w("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                        + ") era and epoch match "
                        + "the server and the DIVERGED test was NOT RUN — the fetch ledger refused "
                        + "the authenticator look for " + caller
                        + ". Reporting IN_SYNC because only "
                        + "a definite mismatch demotes it, but this verdict rests on numbers alone, "
                        + "which is the comparison numbers alone proved insufficient.");
                return ServerComparison.asked(Health.IN_SYNC_UNVERIFIED, anchor);
            }
            if (anchor == MlsWelcomeAdmission.ServerState.DIFFERS) {
                log.w("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                        + ") era and epoch MATCH "
                        + "the server but the epoch AUTHENTICATOR does not — this is a different "
                        + "group at the same position, not a healthy conversation → DIVERGED");
                return ServerComparison.asked(Health.DIVERGED, anchor);
            }
            return ServerComparison.asked(Health.IN_SYNC, anchor);
        }
        // Not "behind": the server serves no earlier anchor to tell its chain from another.
        if (cmp < 0) {
            log.i("MlsAheadChainCheck: health(" + LogMask.number(peerE164) + ") local epoch "
                    + localEpoch
                    + " < server " + server[1]
                    + " → LOWER_EPOCH_CHAIN_UNKNOWN. We are at an earlier "
                    + "POSITION; whether it is an earlier point on the SERVER'S chain or a different "
                    + "chain is NOT determinable here — one epoch has one authenticator, so the "
                    + "DIVERGED test can only answer DIFFERS below the server's epoch, and the "
                    + "server serves no earlier anchor to compare against.");
            return ServerComparison.notAsked(Health.LOWER_EPOCH_CHAIN_UNKNOWN);
        }
        return MlsAheadChainCheck.aheadOrForked(shell, log, caller, rcsGroupId, peerE164, g,
                server);
    }

    /** An era gap dominates an epoch gap: a new era is reached by a Welcome, not a commit. */
    public static Health detectHealth(final MlsShellPort shell, final MlsLogSink log,
            final MlsFetchLedger.Caller caller, final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return Health.UNKNOWN;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        // Up to two fetches: the era/epoch read, plus the authenticator look at equal epochs.
        final Look<long[]> look = shell.lookServerEraEpoch(caller, key, peerE164, rcsGroupId);
        if (look.refused()) {
            log.w("MlsAheadChainCheck: health(" + LogMask.number(peerE164)
                    + ") is LOOK_REFUSED — the "
                    + "fetch ledger refused the server era/epoch look for " + caller + ". NOT "
                    + "UNKNOWN (the server was not asked and did not fail to answer) and NOT "
                    + "NOT_FOUND (nothing here says the server has no group). Nothing was asked, so "
                    + "no remedy is chosen.");
            return Health.LOOK_REFUSED;
        }
        final long[] server = look.orNull();
        if (server == null) {
            return (g == null) ? Health.NOT_FOUND : Health.UNKNOWN;
        }
        return MlsAheadChainCheck.healthAgainstServer(shell, log, caller, rcsGroupId, peerE164, g,
                server).health;
    }

    /**
     * After a failed rollback, drops a group at an era the server never granted, so an inbound
     * Welcome can be taken as a fresh join. An unreadable comparison leaves the group alone.
     */
    public static EraReconcile quarantineIfAheadOfServer(final MlsShellPort shell,
            final MlsLogSink log, final Group g, final String rcsGroupId,
            final String peerE164, final int attemptedEra, final String what) {
        if (g == null || g.groupId == null) return EraReconcile.unknown();
        final int engineEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        long serverEra = -1L;
        long[] srv = null;
        try {
            // Its own ration: it runs after the audited operation has spent the caller's.
            final Look<long[]> look = shell.lookServerEraEpoch(
                    MlsFetchLedger.Caller.QUARANTINE_CHECK,
                    MlsConversationKey.canonicalKey(rcsGroupId, peerE164), peerE164, rcsGroupId);
            if (look.refused()) {
                log.w("MlsAheadChainCheck: could not check whether " + what + " left us "
                        + "ahead of the server — the fetch ledger refused the look. NOT quarantining "
                        + "on a guess; nothing was asked, so nothing is concluded about this group.");
                return EraReconcile.unknown();
            }
            srv = look.orNull();
            if (srv != null) serverEra = srv[0];
        } catch (final Throwable t) {
            log.w("MlsAheadChainCheck: could not read the server era while checking "
                    + "whether " + what + " left us ahead — NOT quarantining on a guess", t);
            return EraReconcile.unknown();
        }
        if (engineEra < 0 || serverEra < 0) {
            log.w("MlsAheadChainCheck: " + what + " — the eras "
                    + "are unreadable (engine=" + engineEra + " server=" + serverEra
                    + ") — leaving "
                    + "the group alone. Destroying state on an unreadable comparison is how a "
                    + "recoverable conversation becomes an unrecoverable one.");
            return EraReconcile.server(srv);
        }
        if (engineEra <= serverEra) {
            log.i("MlsAheadChainCheck: " + what + " — the engine is NOT ahead (engine "
                    + "era=" + engineEra + " server era=" + serverEra + "), no quarantine needed.");
            return EraReconcile.server(srv);
        }
        log.e("MlsAheadChainCheck: " + what + " to era " + attemptedEra + " was refused "
                + "AND the rollback failed, leaving the engine at era " + engineEra + " while the "
                + "server holds era " + serverEra + ". That group is at an era the server never "
                + "granted, so no other member has it — and keeping it would silently park every "
                + "future inbound message from this peer at a moment we can never reach again. "
                + "DROPPING the local group so an inbound Welcome can be taken as a fresh join.");
        shell.forget(rcsGroupId, peerE164);
        return EraReconcile.dropped(srv);
    }
}
