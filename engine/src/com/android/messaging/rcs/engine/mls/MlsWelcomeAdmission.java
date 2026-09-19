/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.log.LogMask;
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

    /**
     * {@link #serverStateCheck} for a group the host has not adopted yet, identified by its MLS
     * group id. The establish path must decide before adopting; checking after adoption would make
     * a fork permanent. Reads our authenticator under the lock and asks the server outside it; an
     * absent server answer is {@link ServerState#UNKNOWN}, not a mismatch.
     */
    public static MlsWelcomeAdmission.ServerState serverStateCheckFor(final MlsShellPort shell,
            final MlsFetchLedger.Caller caller,
            final String rcsGroupId, final String peerE164, final byte[] mlsGroupId) {
        if (!shell.ensureSession()) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null || mlsGroupId == null) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        final byte[] ours;
        shell.lock(key);
        try {
            ours = shell.session().epochAuth(mlsGroupId);
        } finally { shell.unlock(key); }
        if (ours == null || ours.length == 0) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        // Outside the lock: the answer is a snapshot comparison either way, and holding the lock
        // would only block inbound commits during the RPC.
        final Look<byte[]> look =
                shell.lookServerEpochAuthenticator(caller, key, peerE164, rcsGroupId);
        if (look.refused()) return MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER;
        final byte[] server = look.orNull();
        // An absent answer is unknown, not a mismatch.
        if (server == null || server.length == 0) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        return java.util.Arrays.equals(ours, server)
                ? MlsWelcomeAdmission.ServerState.MATCHES
                : MlsWelcomeAdmission.ServerState.DIFFERS;
    }

    /**
     * Whether our state is the server's current state, by epoch authenticator (equal era and epoch
     * do not imply equal state). Four-valued; there is deliberately no boolean form, so each caller
     * handles "could not ask" and "did not ask" itself. No ledger of its own: the {@code caller}
     * declares which budget it spends.
     */
    public static MlsWelcomeAdmission.ServerState serverStateCheck(final MlsShellPort shell,
            final MlsFetchLedger.Caller caller, final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return MlsWelcomeAdmission.ServerState.UNKNOWN;
        final byte[] mlsGroupId;
        shell.lock(key);
        try {
            final Group g = shell.getGroup(key);
            if (g == null || g.groupId == null) return MlsWelcomeAdmission.ServerState.UNKNOWN;
            mlsGroupId = g.groupId;
        } finally { shell.unlock(key); }
        return MlsWelcomeAdmission.serverStateCheckFor(shell, caller, rcsGroupId, peerE164,
                mlsGroupId);
    }

    /**
     * Join a group we are being added to. Tries a Welcome without a {@code ratchet_tree} extension
     * first (RFC 9420 makes it optional; the tree then travels as LeafNodes in the same blob), then
     * a plain join. On failure, republishes our KeyPackage pool, since a Welcome we cannot open
     * means the KDS serves packages this engine lacks the private half of.
     *
     * @param blob the full control payload as delivered (the tree may be in here)
     * @return the conversation key of the joined group, or {@code null}
     */
    public static String joinFromWelcome(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String fromE164,
            final byte[] blob) {
        if (!shell.ensureSession() || blob == null || blob.length == 0) return null;
        final byte[] welcome = MlsWireScan.findWelcome(blob);
        if (welcome == null) {
            log.i("MlsWelcomeAdmission: inbound control from " + LogMask.number(fromE164)
                    + " carries no MLS Welcome (" + blob.length + "B) — not a join");
            return null;
        }
        byte[] gid = null;
        // null unless the engine can splice
        gid = shell.session().joinTreelessWelcome(welcome, blob);
        if (gid != null) {
            log.i("MlsWelcomeAdmission: JOINED by splicing the ratchet_tree from the "
                    + "LeafNodes beside the Welcome (no ratchet_tree extension present) — gid="
                    + gid.length + "B, from=" + LogMask.number(fromE164));
        }
        if (gid == null) {
            gid = shell.session().join(welcome);
            if (gid != null) {
                log.i("MlsWelcomeAdmission: JOINED via plain Welcome (gid="
                        + gid.length + "B)");
            }
        }
        if (gid == null) {
            log.w("MlsWelcomeAdmission: could not join from the " + welcome.length
                    + "B Welcome in a " + blob.length + "B blob");
            // A Welcome we cannot open means the published pool holds packages this engine cannot
            // open, and every peer that claims one will fail the same way. Republish from this
            // engine. Rate-limited by its own hourly gate (the ordinary publish gate is 24h) since
            // the condition belongs to the pool, not the peer. The trigger is peer-supplied and not
            // yet classified by cause, so do not loosen the gate without first making the republish
            // conditional on "no matching key package".
            MlsKeyPackagePool.republishPoolAfterUnopenableWelcome(cfg, shell, log, fromE164);
            return null;
        }
        final String key = MlsRecordState.adoptGroup(shell, log, rcsGroupId, fromE164, gid);
        dropParkedOnJoin(shell, log, key, rcsGroupId, gid);
        log.i("MlsWelcomeAdmission: joined group rcsGroupId=" + rcsGroupId
                + " key=" + MlsConversationKey.forLog(key));
        // The Welcome consumed one of our published KeyPackages. Cross off the exact package if its
        // ref is known; only otherwise decrement the counter, so it is not counted twice.
        if (!MlsKeyPackagePool.crossOffConsumedKeyPackage(cfg, shell, log, welcome)) {
            MlsKeyPackagePool.noteKeyPackageConsumed(cfg, shell, log);
        }
        return key;
    }

    /**
     * Empties the pending queue of a group just joined by Welcome. Everything parked was framed
     * against the branch the Welcome replaces; replaying it onto the new state would fork the group
     * (same epoch number, different state), after which no commit applies. The application entries
     * are read before the clear and reported, so their senders resend at the new moment.
     *
     * @return how many parked entries were dropped
     */
    static int dropParkedOnJoin(final MlsShellPort shell, final MlsLogSink log, final String key,
            final String rcsGroupId, final byte[] gid) {
        final String self = shell.selfE164();
        if (key == null || self.isEmpty() || gid == null) return 0;
        final java.util.List<MlsPendingQueue.Entry> dropped;
        shell.lock(key);
        try {
            final MlsPendingQueue stale = shell.pendingQueue().load(self, gid);
            dropped = stale.peekAll();
            if (dropped.isEmpty()) return 0;
            stale.clear();
            shell.pendingQueue().store(self, gid, stale);
        } finally { shell.unlock(key); }
        log.w("MlsWelcomeAdmission: dropped " + dropped.size() + " parked control/app "
                + "message(s) on joining " + rcsGroupId + " — they were framed against the "
                + "branch this Welcome replaces, and replaying them onto the new state would "
                + "fork it. They are unreadable here; the application ones are reported.");
        MlsInboundHold.reportDropped(shell, log, key, rcsGroupId, dropped, "joined by Welcome");
        return dropped.size();
    }

    /**
     * Re-join a group we are already in when a peer's Welcome re-creates it (an era advance, or a
     * same-era membership refresh). Snapshots first, joins, and keeps the result only if
     * {@link #decide} does; anything else is restored. A Welcome that fails to join is usually for
     * a member being added, and the caller then applies the commit riding with it.
     *
     * @return true if the re-join was kept (the caller is done with this control)
     */
    public static boolean rejoinOnEraAdvance(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String conversationId, final String rcsGroupId,
            final String fromE164, final byte[] blob) {
        final Group cur = shell.getGroup(conversationId);
        if (cur == null || cur.groupId == null) return false;
        final int oldEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(cur.groupId));
        final byte[] snapshot = shell.session().exportGroupSnapshot(cur.groupId);

        final String key =
                MlsWelcomeAdmission.joinFromWelcome(cfg, shell, log, rcsGroupId, fromE164, blob);
        final Group after = (key == null) ? null : shell.getGroup(key);
        final int newEra = (after == null || after.groupId == null)
                ? -1 : MlsAppMessage.eraFrom(shell.session().eraEpoch(after.groupId));

        // Consult the server only when era ordering cannot answer (see the class doc).
        final MlsWelcomeAdmission.ServerState serverState =
                MlsWelcomeAdmission.needsServerConsult(key != null, oldEra, newEra)
                        ? MlsWelcomeAdmission.serverStateCheck(shell,
                                MlsFetchLedger.Caller.REJOIN_ON_ERA_ADVANCE, rcsGroupId, fromE164)
                        : null;
        if (serverState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            // Say why it is unverified: a ledger refusal, not a network fault or a replay.
            log.w("MlsWelcomeAdmission: the same-era re-Welcome from " + LogMask.number(fromE164)
                    + " cannot be verified because OUR OWN fetch ledger refused the look — nothing "
                    + "was asked of the server. The join is rolled back (unasked is not consent), "
                    + "and this is NOT evidence that the Welcome was a replay.");
        }
        final MlsWelcomeAdmission.Verdict verdict =
                MlsWelcomeAdmission.decide(key != null, oldEra, newEra, serverState);
        if (!verdict.keepsJoin()) {
            // Restore exactly: a half-applied join looks like it worked.
            if (snapshot != null) shell.session().restoreGroupSnapshot(cur.groupId, snapshot);
            shell.putGroup(conversationId, cur);
            final String why = MlsWelcomeAdmission.line(verdict, oldEra, newEra);
            if (verdict == MlsWelcomeAdmission.Verdict.REJECT_ERA_UNREADABLE) {
                // Our Welcome applied but its era is unreadable: a parse defect, logged at error.
                log.e("MlsWelcomeAdmission: " + why + " — from " + LogMask.number(fromE164));
            } else if (verdict == MlsWelcomeAdmission.Verdict.NOT_ADDRESSED_TO_US) {
                // Not a refusal: the Welcome belongs to a new member. A malformed Welcome lands
                // here too, since the join's return cannot tell them apart.
                log.i("MlsWelcomeAdmission: " + why + " — the Welcome from "
                        + LogMask.number(fromE164)
                        + " is not addressed to us; applying the commit that rides with it");
            } else {
                log.w("MlsWelcomeAdmission: REFUSED a re-join from " + LogMask.number(fromE164)
                        + " — "
                        + why);
            }
            return false;
        }
        // The single place the accept-set is enforced, using the verdict's action, so a new enum
        // arm is never joinable by default.
        MlsWelcomeAction.requireJoinable(verdict.action());
        // Clear our self-left mark only after keepsJoin(): a Welcome for some other new member must
        // not un-leave us. Reached only when the departure's best-effort deleteGroup did not take.
        MlsRecordState.clearSelfLeftOnRejoin(shell, log, key, fromE164);
        if (verdict == MlsWelcomeAdmission.Verdict.ACCEPT_REFRESH) {
            log.i("MlsWelcomeAdmission: SAME-ERA REFRESH accepted from " + LogMask.number(fromE164)
                    + " on " + (rcsGroupId == null ? LogMask.number(fromE164) : rcsGroupId) + " — "
                    + MlsWelcomeAdmission.line(verdict, oldEra, newEra));
        }

        // Replay buffered controls rather than discarding them: controls from the new era become
        // applicable now. Anything still inapplicable is re-buffered and aged out by the existing
        // bound.
        log.i("MlsWelcomeAdmission: RE-JOIN from " + LogMask.number(fromE164) + " ACCEPTED as "
                + verdict.action() + " — " + MlsWelcomeAdmission.line(verdict, oldEra, newEra)
                + " on " + (rcsGroupId == null ? LogMask.number(fromE164) : rcsGroupId)
                + " (strand cleared; retrying buffered controls at the new epoch)");
        MlsInboundHold.drainDeferredControl(shell, log, conversationId, fromE164, rcsGroupId);
        return true;
    }
}
