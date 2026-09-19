/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupMembershipRouting;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
import java.util.List;
/**
 * Group membership changes: the RCS roster RPCs and the MLS add, remove and leave that must agree
 * with them. See docs/mls/group-lifecycle.md.
 */
public final class MlsMembership {
    private MlsMembership() {}

    /**
     * Make the RCS-layer participant change that must precede an MLS membership commit. If it fails
     * the MLS commit is not attempted; if it succeeds and the MLS commit then fails, the RCS change
     * is not rolled back and the next commit reconciles from it.
     */
    public static boolean rcsMembershipChange(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String memberE164, final boolean add) {
        final java.util.List<String> one = java.util.Collections.singletonList(memberE164);
        final MlsProviderRpc pt = shell.rpc("changeGroupMembers");
        final boolean ok = add
                ? pt.addGroupUsers(rcsGroupId, one)
                : pt.removeGroupUsers(rcsGroupId, one);
        log.i("MlsMembership: RCS " + (add ? "add" : "remove") + " of " + LogMask.number(memberE164)
                + " on group " + rcsGroupId + " → " + (ok ? "OK, proceeding to the MLS commit"
                        : "FAILED — not attempting the MLS commit, which the server would refuse "
                        + "with mismatched-rcs-group-state"));
        return ok;
    }

    /**
     * One membership change on an MLS group, shared by add and remove: snapshot, build the commit
     * (with a control-message id minted first, since the AAD binds it), ship it with the RCS half
     * in one request outside the lock, and on refusal restore the snapshot or quarantine. See
     * docs/mls/group-lifecycle.md.
     */
    public static int mlsMembershipChange(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String memberE164, final Op op, final byte[] kp, final byte[] memberSigPub) {
        final boolean adding = op == Op.ADD;
        final String what = adding ? "add-member" : "remove-member";
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        if (key == null) return -1;
        shell.lock(key);
        final byte[] snapshot;
        final MlsGroupArtifacts art;
        final byte[] prevEpochAuth;
        final String ctrlId;
        final Group g;
        try {
            g = shell.getGroup(key);
            if (g == null || g.groupId == null) return -1;
            prevEpochAuth = shell.session().epochAuth(g.groupId);
            ctrlId = "mls-" + what + "-" + rcsGroupId + "-" + System.currentTimeMillis();
            final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            // the engine builds the AAD from this id + the group's own era
            final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            snapshot = shell.session().exportGroupSnapshot(g.groupId);
            // Remove by certified MSISDN so the MLS half removes the participant the RCS half
            // names, all of their leaves in one commit; the empty-key form removes an arbitrary
            // leaf in a larger group.
            final MlsGroupArtifacts removed = adding ? null
                    : shell.session().removeMemberByMsisdn(g.groupId, memberE164, aad);
            if (!adding && removed == null) {
                // Fall back to removal by signature key, and say which path ran.
                log.w("MlsMembership: no leaf certified to " + LogMask.number(memberE164)
                        + " in this group (or no MSISDN selector) — falling back to the signature-"
                        + "key removal, which cannot guarantee it removes the member the RCS half "
                        + "names");
            }
            art = adding
                    ? shell.session().addMember(g.groupId, kp, aad)
                    : (removed != null ? removed
                            : shell.session().removeMember(g.groupId,
                                    memberSigPub == null ? new byte[0] : memberSigPub, aad));
            if (art == null || art.commit == null) {
                if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
                log.e("MlsMembership: engine produced no " + what + " commit for "
                        + LogMask.number(memberE164) + " — rolled back");
                return -1;
            }
            log.i("MlsMembership: " + what + " commit=" + art.commit.length
                    + "B welcome=" + (art.welcome == null ? 0 : art.welcome.length)
                    + "B groupInfo=" + (art.groupInfo == null ? 0 : art.groupInfo.length)
                    + "B tree=" + (art.ratchetTree == null ? 0 : art.ratchetTree.length)
                    + "B — shipping it WITH the RCS change ("
                    + (adding ? "AddGroupUsers field 6" : "KickGroupUsers field 9") + ")");
        } finally {
            shell.unlock(key);
        }

        // Outside the lock: transport I/O under a group lock throws on a debuggable build.
        final java.util.List<String> one = java.util.Collections.singletonList(memberE164);
        final MlsProviderRpc.ControlResult r = adding
                ? shell.rpc("addGroupUsersMls").addGroupUsersMls(rcsGroupId, one, art.welcome,
                        art.commit, art.groupInfo, art.tag, art.ratchetTree, prevEpochAuth, ctrlId)
                // A removal produces no Welcome and no ratchet tree.
                : shell.rpc("removeGroupUsersMls").removeGroupUsersMls(rcsGroupId, one,
                        art.commit, art.groupInfo, art.tag, prevEpochAuth, ctrlId);

        shell.lock(key);
        try {
            if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
                final boolean rolledBack = snapshot != null
                        && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
                // A transport failure (retries already exhausted) is not a refusal: retry later.
                final MlsTransportDisposition disp =
                        (r == null) ? MlsTransportDisposition.RETRYABLE
                                    : MlsTransportDisposition.ofVerdict(r.verdict);
                log.w("MlsMembership: MLS " + what + " of " + LogMask.number(memberE164) + " on "
                        + rcsGroupId + (disp.mayRetry() ? " could not be sent (" + disp
                                + ", retry later)" : " REFUSED") + " → " + r
                        + (rolledBack ? " (rolled back to the pre-commit epoch)"
                                      : " (ROLLBACK FAILED — local state may be epoch-ahead)"));
                if (!rolledBack) {
                    shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164,
                            MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId)),
                            "MLS " + what + " (rollback reported FAILED)");
                }
                // Safe to queue: the rollback means a retry rebuilds the commit from current state.
                if (disp.mayRetry()
                        && MlsDriveLoop.maySchedule(shell, log, key, "an MLS " + what + " retry")) {
                    shell.scheduleRetry(rcsGroupId, memberE164, /*attempt=*/ 1);
                }
                return -1;
            }
            final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // Only a Remove forces an UpdatePath in mls-rs; after an add our leaf is unchanged.
            if (!adding) g.sendsSinceLeafRotation = 0;
            shell.putGroup(key, g);
            // The recorded roster follows the change, as a new (era, epoch) entry derived from what
            // this commit did. With no baseline for this era nothing is written; the maintenance
            // pass establishes one from the server.
            final java.util.List<String> before = MlsServerBundle.ourRoster(shell, log, key, g);
            if (before == null) {
                log.i("MlsMembership: no membership recorded at era " + era
                        + " for " + MlsConversationKey.forLog(key)
                        + ", so there is no baseline to apply this " + what
                        + " to — leaving row 14 for the maintenance pass to establish from the "
                        + "server's roster rather than inventing one from a single change.");
            } else {
                final java.util.List<String> updated = new java.util.ArrayList<>(before);
                if (adding) {
                    if (!updated.contains(memberE164)) updated.add(memberE164);
                } else {
                    updated.remove(memberE164);
                }
                MlsRecordState.recordMembership(shell, log, key, g, updated);
            }
            log.i("MlsMembership: " + (adding ? "ADDED " : "REMOVED ") + LogMask.number(memberE164)
                    + (adding ? " to" : " from") + " MLS group " + rcsGroupId
                    + " — RCS roster and MLS commit in ONE request, era=" + era);
            return era;
        } finally {
            shell.unlock(key);
        }
    }

    /**
     * {@link #establishGroup}'s addMembers arm, reached only when the engine holds the group and
     * the host does not, so the state this commit sits on was never agreed with the server. Sends
     * the add, rolls back on refusal, then verifies against the server's epoch authenticator before
     * adopting, like the create arm. Spends from {@link MlsFetchLedger.Caller#GROUP_ESTABLISH}; the
     * two arms are exclusive within one call. See docs/mls/group-lifecycle.md.
     *
     * @return the era the group stays at, or {@code -1} with the host holding nothing
     */
    public static int addMembersToExistingGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String key,
            final List<String> members, final MlsGroupArtifacts art, final byte[] preEpochAuth,
            final byte[] preCreate) {
        final String first = members.get(0);
        // On this arm the MLS group id is the RCS group id.
        final byte[] gidBytes = rcsGroupId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final long targetEra = art.era;
        log.i("MlsMembership: establishGroup " + rcsGroupId + " — "
                + art.welcomeAction.addMembersLine() + " (era stays " + targetEra + ", admitting "
                + LogMask.numbers(art.admittedMembers) + ")");
        final String ctrlId = "mls-add-member-" + rcsGroupId + "-"
                + System.currentTimeMillis();
        final MlsProviderRpc.ControlResult ar = shell.rpc("addGroupUsersMls").addGroupUsersMls(
                rcsGroupId, art.admittedMembers, art.welcome, art.commit, art.groupInfo,
                art.tag, art.ratchetTree, preEpochAuth, ctrlId);
        if (ar == null || !ar.ok()) {
            // The engine applied the add before we dialled; a refusal must roll it back.
            final boolean rolled = MlsUpgradePolicy.rollBackDiscardedCreate(shell.session(), log,
                    gidBytes, preCreate);
            log.w("MlsMembership: establishGroup's addMembers REFUSED → " + ar
                    + (rolled ? " (rolled back to the pre-commit epoch)"
                              : " ⚠ (ROLLBACK FAILED — the engine is an epoch AHEAD of the "
                                + "group and every subsequent send will be refused)"));
            return -1;
        }
        // Verify, then adopt. Only the epoch authenticator separates "in the server's group" from
        // "one epoch further into our own".
        final MlsWelcomeAdmission.ServerState addState = MlsWelcomeAdmission.serverStateCheckFor(
                shell, MlsFetchLedger.Caller.GROUP_ESTABLISH, rcsGroupId, first, art.groupId);
        if (addState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            log.e("MlsMembership: " + rcsGroupId + " — the addMembers returned OK "
                    + "and we DID NOT ASK the server for its view: our own fetch ledger refused the "
                    + "look. NOTHING IS ADOPTED. There is no server status to interpret "
                    + "here; the remedy is to retry once the ledger's window has passed. The engine "
                    + "keeps the commit — a ledger refusal is evidence about our budget, not about "
                    + "what the server did with it.");
            return -1;
        }
        if (addState == MlsWelcomeAdmission.ServerState.UNKNOWN) {
            log.e("MlsMembership: " + rcsGroupId + " — the addMembers returned OK "
                    + "but we CANNOT READ the server's view of the group. Refusing to report "
                    + "success: whether we are in ITS group or one epoch into our own is "
                    + "unverified, and a false success here is indistinguishable from a real one "
                    + "until the first send fails. Check the GetMlsGroupInfo status in "
                    + "the provider log — PERMISSION_DENIED means the conversation is not ours, "
                    + "NOT_FOUND means it does not exist. NOTHING IS ADOPTED.");
            return -1;
        }
        if (addState == MlsWelcomeAdmission.ServerState.DIFFERS) {
            return MlsRecordState.healOntoServerGroupOrUnadopt(shell, log, rcsGroupId, first, key,
                    art.groupId, targetEra);
        }
        // The server confirmed it: only now does the host take ownership.
        MlsRecordState.adoptGroup(shell, log, rcsGroupId, first, art.groupId);
        // After an add the membership is the requested roster.
        final Group added = shell.getGroup(key);
        if (added != null) MlsRecordState.recordMembership(shell, log, key, added, members);
        log.i("MlsMembership: ADDED " + LogMask.numbers(art.admittedMembers)
                + " to the existing "
                + "MLS group " + rcsGroupId + " at era=" + targetEra
                + " — server CONFIRMED with its epoch authenticator");
        return (int) targetEra;
    }

    /** Add a member to an MLS group; see {@link #mlsMembershipChange}. */
    public static int addMemberToMlsGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String newMemberE164, final byte[] kp) {
        return MlsMembership.mlsMembershipChange(shell, log, rcsGroupId, peerE164, newMemberE164,
                Op.ADD, kp,
                /*memberSigPub=*/ null);
    }

    /**
     * Remove a member from an MLS group; see {@link #mlsMembershipChange}. A removal has no
     * Welcome, so it rides control-message arm 3 at KickGroupUsers field 9, not the add's arm 2 at
     * AddGroupUsers field 6.
     */
    public static int removeMemberFromMlsGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String removedE164, final byte[] memberSigPub) {
        return MlsMembership.mlsMembershipChange(shell, log, rcsGroupId, peerE164, removedE164,
                Op.REMOVE, /*kp=*/ null,
                memberSigPub);
    }

    /**
     * May every one of these members be Welcomed into {@code rcsGroupId}? Pure over
     * {@link MlsPeerGuard#allowJoiningPeer}, so {@link #rebuildConversation} can ask it before it
     * destroys anything.
     */
    public static boolean allowedToJoinAll(final MlsShellPort shell, final MlsLogSink log,
            final String op, final String rcsGroupId, final List<String> members) {
        if (members == null) return false;
        for (final String m : members) {
            if (m == null || m.isEmpty()) continue;
            if (!shell.peerGuard().allowJoiningPeer(op, m)) {
                log.e("MlsMembership: NOT establishing MLS on " + rcsGroupId
                        + " — one of its " + members.size() + " member(s) may not be brought into "
                        + "an MLS group. The whole create is refused rather than the member "
                        + "dropped: a group smaller than the RCS roster is refused mlsError 5, and "
                        + "silently omitting someone leaves them believing they are in an encrypted "
                        + "conversation they cannot read.");
                return false;
            }
        }
        return true;
    }

    /** Add a member to a specific RCS group (null groupId = the 1:1 with {@code peerE164}). */
    public static int addMember(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String newMemberE164) {
        // The joining allowlist before the claim, which consumes one of the peer's KeyPackages.
        if (!shell.peerGuard().allowJoiningPeer("addMember", newMemberE164)) return -1;
        if (!shell.ensureSession()) return -1;
        final Claim<byte[]> addClaim =
                shell.claimOne(MlsClaimLedger.Caller.ADD_MEMBER, newMemberE164);
        if (addClaim.refused()) {
            // Our own ledger refused; the log must not blame the new member's pool.
            log.w("MlsMembership: NOT adding " + LogMask.number(newMemberE164)
                    + " — our own claim ledger refused the KeyPackage claim, so we never asked and "
                    + "know NOTHING about their pool. Try the add again after the window. "
                    + addClaim.why());
            return -1;
        }
        final byte[] kp = addClaim.orNull();
        if (kp == null || kp.length == 0) {
            MlsKeyPackageClaims.logClaimBlocked(log, 
                    MlsClaimLedger.addMemberBlockedLine(newMemberE164, addClaim.attribution()));
            return -1;
        }
        log.i("MlsMembership: claimed KeyPackage " + kp.length + "B for "
                + LogMask.number(newMemberE164));
        // RCC.16 A.4.2.2: the floor applies to an Add just as it does to a create.
        if (!shell.keyPackageUsable(kp, newMemberE164)) return -1;
        // On an MLS conversation the bare RCS add is refused and the server also refuses a commit
        // for a non-participant, so both ride one request (AddGroupUsers field 6). A plaintext
        // conversation keeps the RCS-first order. See docs/rcs/groups.md.
        if (rcsGroupId != null && !rcsGroupId.isEmpty()
                && MlsGroupState.conversationIsMls(shell, rcsGroupId, peerE164)) {
            return MlsMembership.addMemberToMlsGroup(shell, log, rcsGroupId, peerE164,
                    newMemberE164, kp);
        }
        if (rcsGroupId != null && !MlsMembership.rcsMembershipChange(shell, log, rcsGroupId,
                newMemberE164, /*add=*/ true)) {
            return -1;
        }
        return shell.commitAndSend(rcsGroupId, peerE164, kp, null, Op.ADD, "add-member");
    }

    /**
     * Remove a member. {@code peerE164} addresses the conversation and {@code removedE164} names
     * who leaves; without {@code removedE164} (the 1:1 case) there is no RCS half and the commit
     * goes out alone.
     */
    public static int removeMember(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] memberSigPub, final String removedE164) {
        // The kill switch and peer-health streak but not the joining allowlist, which would refuse
        // to remove exactly the peer that must go. Charged to whoever is leaving.
        if (!shell.peerGuard().allowStateChange("removeMember",
                (removedE164 != null && !removedE164.isEmpty()) ? removedE164 : peerE164)) {
            return -1;
        }
        // Before the MLS check: on a cold process getGroup() sees nothing until the session is up,
        // and the removal would take the bare route an MLS group refuses.
        if (!shell.ensureSession()) return -1;
        // As for the add: both halves in one request (KickGroupUsers field 9), which needs to name
        // who is leaving.
        if (rcsGroupId != null && !rcsGroupId.isEmpty() && removedE164 != null
                && MlsGroupState.conversationIsMls(shell, rcsGroupId, peerE164)) {
            return MlsMembership.removeMemberFromMlsGroup(shell, log, rcsGroupId, peerE164,
                    removedE164, memberSigPub);
        }
        if (rcsGroupId != null && removedE164 != null
                && !MlsMembership.rcsMembershipChange(shell, log, rcsGroupId, removedE164,
                        /*add=*/ false)) {
            return -1;
        }
        return shell.commitAndSend(rcsGroupId, peerE164, null, memberSigPub, Op.REMOVE,
                "remove-member");
    }

    /**
     * A commit removed someone (typically a peer's swept {@code self_remove}): update the recorded
     * roster and tell the conversation. Redundant with the RCS group event when there is one (same
     * Action and de-dup signature), and the only notice when there is not. Best-effort: it runs on
     * the inbound control path.
     *
     * @param rosterBefore the MLS roster read before the commit, or null if it was not readable, in
     *                     which case nothing is done
     */
    public static void applyDepartures(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g, final String rcsGroupId,
            final java.util.List<String> rosterBefore) {
        try {
            if (rosterBefore == null || g == null || g.groupId == null) return;
            final java.util.List<String> rosterAfter =
                    MlsServerBundle.mlsRosterMsisdns(shell, log, g.groupId);
            if (rosterAfter == null) {
                log.w("MlsMembership: the commit on " + MlsConversationKey.forLog(key)
                        + " landed but the "
                        + "post-commit roster is not fully readable — not computing who departed. "
                        + "A partial roster here would report members we merely could not read as "
                        + "having left, and we would remove them from the conversation.");
                return;
            }
            final java.util.List<String> departed = new java.util.ArrayList<>(rosterBefore);
            departed.removeAll(rosterAfter);
            // We built this commit, so we cannot be a departure.
            departed.remove(shell.selfE164());
            if (departed.isEmpty()) return;

            // The recorded roster follows, under the lock (a whole-record read-modify-write); with
            // no baseline nothing is written.
            shell.lock(key);
            try {
                final java.util.List<String> before = MlsServerBundle.ourRoster(shell, log, key, g);
                if (before == null) {
                    log.i("MlsMembership: no membership recorded for "
                            + MlsConversationKey.forLog(key)
                            + ", so there is no baseline to apply this departure to — leaving row "
                            + "14 for the maintenance pass to establish from the server's roster.");
                } else {
                    final java.util.List<String> updated = new java.util.ArrayList<>(before);
                    updated.removeAll(departed);
                    MlsRecordState.recordMembership(shell, log, key, g, updated);
                }
            } finally { shell.unlock(key); }

            log.i("MlsMembership: the proposal commit on " + MlsConversationKey.forLog(key)
                    + " REMOVED "
                    + departed + " — " + rosterBefore.size() + " member(s) before, "
                    + rosterAfter.size() + " after");

            // Outside the lock: this opens a database transaction. A 1:1 has no participant list.
            if (rcsGroupId == null || rcsGroupId.isEmpty()) return;
            for (final String who : departed) {
                shell.applyGroupDeparture(rcsGroupId, who);
            }
        } catch (final Throwable t) {
            log.w("MlsMembership: applying the departures from a proposal commit "
                    + "on " + MlsConversationKey.forLog(key)
                    + " failed — the MLS commit itself already landed", t);
        }
    }

    /**
     * Which plane a group conversation is on: the one evaluation the routed group operations share.
     * Reports and logs; each caller decides what {@link GroupPlane#MLS_LOCKED_OUT},
     * {@link GroupPlane#UNKNOWN} and {@link GroupPlane#MLS_DOWNGRADED} mean for it. Not a send
     * gate: a lost MLS identity reads {@link GroupPlane#PLAINTEXT} before the encryption latch is
     * read. See docs/rcs/groups.md.
     *
     * @param what the operation, for the log lines only ("add", "remove", "rename")
     */
    public static GroupPlane groupPlane(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String what) {
        // No session: with no MLS identity nothing here is encrypted; with one we cannot tell.
        if (!shell.ensureSession()) {
            if (shell.loadIdentity() == null) {
                log.i("MlsMembership: " + what + " on " + rcsGroupId
                        + " — this device has no adopted MLS identity, so nothing on it is "
                        + "encrypted; the bare RPC is correct.");
                return GroupPlane.PLAINTEXT;
            }
            log.e("MlsMembership: REFUSING the " + what + " on " + rcsGroupId
                    + " — an MLS identity exists but the engine would not open a session, so we "
                    + "CANNOT TELL whether this conversation is encrypted. Guessing plaintext here "
                    + "would send the bare RPC that an MLS group refuses and then mirror it locally "
                    + "anyway; guessing MLS would build a commit we have no engine for.");
            return GroupPlane.UNKNOWN;
        }
        // peerE164 is null: a group key ignores it, and resolveInbound never falls back to a 1:1.
        if (!MlsGroupState.conversationIsMls(shell, rcsGroupId, /*peerE164=*/ null)) {
            // Holding no MLS state is not the same as the conversation being plaintext. A set
            // encryption_protocol latch (cleared only by a downgrade or our own leave) means the
            // server still holds this group encrypted and the UI shows a padlock, so report
            // MLS_LOCKED_OUT and let add/remove refuse. The latch answers only the app's own UI
            // state; getGroup above is the engine authority. An unreadable latch falls through to
            // PLAINTEXT. A group we were dropped from before any MLS traffic has no latch and still
            // reads PLAINTEXT.
            final String convId = shell.conversationIdFor(
                    MlsConversationKey.canonicalKey(rcsGroupId, /*peerE164=*/ null));
            final Boolean mls = (convId == null || convId.isEmpty())
                    ? null : shell.conversationMlsBit(convId);
            if (Boolean.TRUE.equals(mls)) {
                log.e("MlsMembership: REFUSING the " + what + " on " + rcsGroupId
                        + " — we hold NO MLS state for it, but its encryption_protocol bit is SET, "
                        + "so this is an MLS conversation we are locked out of (REJOIN), not a "
                        + "plaintext one. The bare RPC would ask the server to change the roster of "
                        + "a group it holds as ENCRYPTED, and the app is showing this thread with a "
                        + "padlock.");
                return GroupPlane.MLS_LOCKED_OUT;
            }
            // Not our MLS conversation, or one never joined; either way the bare RPC.
            log.i("MlsMembership: " + what + " on " + rcsGroupId
                    + " takes the bare RPC — no MLS state and "
                    + (mls == null ? "the encryption bit could not be read"
                                    : "the encryption bit is clear"));
            return GroupPlane.PLAINTEXT;
        }
        // A downgraded group is plaintext (RCC.16 §9.1.1) even though the engine group remains.
        if (MlsRecordState.hasEndMlsStatus(shell, log,
                MlsConversationKey.canonicalKey(rcsGroupId, /*peerE164=*/ null))) {
            return GroupPlane.MLS_DOWNGRADED;
        }
        return GroupPlane.MLS;
    }

    /**
     * We left: update the app, since the server's group event goes only to the members who remain.
     * Posts the "You left" status line and clears the encryption bit directly, not through
     * {@link #downgradeLocally}: leaving is not a downgrade and must not schedule a re-upgrade.
     */
    public static void announceSelfDeparture(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String rcsGroupId) {
        final String self = shell.selfE164();
        try {
            final String convId = shell.conversationIdFor(key);
            if (convId != null && !convId.isEmpty()) {
                // An unreadable bit is not evidence of plaintext; clear it anyway.
                final Boolean mls = shell.conversationMlsBit(convId);
                if (mls == null || mls) {
                    shell.downgradeMlsScheme(convId);
                    log.i("MlsMembership: cleared the MLS encryption bit for "
                            + MlsConversationKey.forLog(convId) + " ("
                            + MlsConversationKey.forLog(key)
                            + ") — we LEFT this group, so the app must stop "
                            + "showing it as encrypted. NOT recorded as a downgrade: nothing here "
                            + "plans a re-upgrade, because there is nothing to come back to.");
                }
            }
        } catch (final Throwable t) {
            log.w("MlsMembership: could not clear the encryption bit after leaving "
                    + MlsConversationKey.forLog(key), t);
        }
        // leave() refuses a 1:1, so a missing group id here means we could not resolve one.
        if (rcsGroupId == null || rcsGroupId.isEmpty() || self.isEmpty()) {
            log.w("MlsMembership: left " + MlsConversationKey.forLog(key)
                    + " but cannot address the "
                    + "conversation (rcsGroupId=" + rcsGroupId + " self="
                    + (self.isEmpty() ? "UNKNOWN" : "known") + ") — the thread will not say so.");
            return;
        }
        shell.applyGroupDeparture(rcsGroupId, self);
    }

    /** Leave a specific RCS group; a null groupId (the 1:1) is refused. */
    public static boolean leave(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        // The SelfRemove proposal rides KickGroupUsers field 9 (control-message arm 1). A cached
        // SelfRemove persists and blocks every send, and MLS forbids committing it ourselves, so
        // the proposal is bracketed by a snapshot and restored on refusal. A 1:1 has no group to
        // leave; endMls is the verb there.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            log.w("MlsMembership: self-leave on a 1:1 is not a thing — there is no "
                    + "group to leave. Use endMls() to end encryption with "
                    + LogMask.number(peerE164) + ".");
            return false;
        }
        if (!shell.ensureSession()) return false;
        final String key = MlsGroupState.resolveInbound(shell, log, rcsGroupId, peerE164);
        if (key == null) return false;
        // Idempotent: a second SelfRemove on a group we already left could never be committed.
        if (MlsRecordState.weLeft(shell, log, key)) {
            log.i("MlsMembership: we have already LEFT " + rcsGroupId
                    + " — not proposing a second SelfRemove. A remaining member completes the "
                    + "departure; nothing further happens on this side.");
            return true;
        }
        shell.lock(key);
        final byte[] snapshot;
        final MlsGroupArtifacts art;
        final byte[] prevEpochAuth;
        final String ctrlId;
        final Group g;
        try {
            g = shell.getGroup(key);
            if (g == null || g.groupId == null) return false;
            prevEpochAuth = shell.session().epochAuth(g.groupId);
            ctrlId = "mls-self-leave-" + rcsGroupId + "-" + System.currentTimeMillis();
            final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            // the engine builds the AAD from this id + the group's own era
            final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            snapshot = shell.session().exportGroupSnapshot(g.groupId);
            if (snapshot == null) {
                // Without a snapshot a refusal could not be undone, leaving sends blocked for good.
                log.e("MlsMembership: refusing self-leave on " + rcsGroupId
                        + " — no snapshot, so a refusal could not be rolled back and a cached "
                        + "SelfRemove would block every send on this conversation permanently.");
                return false;
            }
            art = shell.session().selfLeave(g.groupId, aad);
            if (art == null || art.commit == null || art.commit.length == 0) {
                shell.session().restoreGroupSnapshot(g.groupId, snapshot);
                log.e("MlsMembership: engine produced no SelfRemove proposal for "
                        + rcsGroupId + " — rolled back");
                return false;
            }
            log.i("MlsMembership: self-leave proposal=" + art.commit.length
                    + "B — shipping it on KickGroupUsers (field 9, control-message arm 1)");
        } finally {
            shell.unlock(key);
        }

        // Outside the lock — transport I/O, same rule as add/remove.
        final MlsProviderRpc.ControlResult r = shell.rpc("selfLeaveGroupMls").selfLeaveGroupMls(
                rcsGroupId, art.commit, prevEpochAuth, ctrlId);

        shell.lock(key);
        try {
            if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
                final boolean rolledBack =
                        shell.session().restoreGroupSnapshot(g.groupId, snapshot);
                log.w("MlsMembership: self-leave of " + rcsGroupId + " REFUSED → "
                        + r + (rolledBack
                                ? " (rolled back — the cached SelfRemove is gone, sends still work)"
                                : " ⚠ ROLLBACK FAILED — a cached SelfRemove may now block every send "
                                  + "on this conversation. The proposal drop lever is the way out."));
                return false;
            }
            log.i("MlsMembership: LEFT MLS group " + rcsGroupId
                    + " — SelfRemove proposal accepted; a remaining member commits it.");
            MlsRecordState.recordSelfDeparture(shell, log, key, g);
        } finally {
            shell.unlock(key);
        }
        // Outside the lock: a database transaction and the app's encryption bit.
        MlsMembership.announceSelfDeparture(shell, log, key, rcsGroupId);
        return true;
    }

    /**
     * The 1:1 form of {@link #leave}; a 1:1 has no group to leave, so this always refuses.
     *
     * @return false
     */
    public static boolean leave(final MlsShellPort shell, final MlsLogSink log,
            final String peerE164) {
        return MlsMembership.leave(shell, log, null, peerE164);
    }

    /**
     * Leave this group, routed: the entry point a menu calls instead of choosing an RPC itself.
     * Gated by {@link MlsPeerGuard#allowSelfDeparture} (the kill switch) only. On the MLS arm
     * {@link #leave} writes the whole local mirror, so the caller must add nothing.
     *
     * @param rcsGroupId the RCS group to leave. A 1:1 is refused: {@code endMls} is the verb there.
     * @return {@link GroupMembershipRouting#PLAINTEXT} when the caller should make the bare
     *     {@code removeGroupUsers(self)} call, {@link GroupMembershipRouting#APPLIED} when we are
     *     out, {@link GroupMembershipRouting#REFUSED} when the departure did not happen
     */
    public static GroupMembershipRouting leaveGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId) {
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            // Refused, not plaintext: a 1:1 has no roster to remove anyone from.
            log.w("MlsMembership: leaveGroup needs an RCS group id — a 1:1 has "
                    + "nothing to leave, and endMls() is the verb for ending encryption there.");
            return GroupMembershipRouting.REFUSED;
        }
        if (!shell.peerGuard().allowSelfDeparture("leaveGroup")) {
            return GroupMembershipRouting.REFUSED;
        }
        // As in groupPlane: no identity means plaintext, an identity without a session is unknown.
        if (!shell.ensureSession()) {
            if (shell.loadIdentity() == null) {
                log.i("MlsMembership: leaving " + rcsGroupId + " — this device has "
                        + "no adopted MLS identity, so nothing on it is encrypted; the bare RPC is "
                        + "correct.");
                return GroupMembershipRouting.PLAINTEXT;
            }
            log.e("MlsMembership: REFUSING to leave " + rcsGroupId + " — an MLS "
                    + "identity exists but the engine would not open a session, so we CANNOT TELL "
                    + "whether this conversation is encrypted. A bare KickGroupUsers naming "
                    + "ourselves is what an MLS group refuses, and guessing MLS would need an "
                    + "engine we do not have.");
            return GroupMembershipRouting.REFUSED;
        }
        if (!MlsGroupState.conversationIsMls(shell, rcsGroupId, /*peerE164=*/ null)) {
            return GroupMembershipRouting.PLAINTEXT;
        }
        final boolean left = MlsMembership.leave(shell, log, rcsGroupId, /*peerE164=*/ null);
        log.i("MlsMembership: routed the departure from MLS group " + rcsGroupId
                + " through the SelfRemove proposal → " + (left ? "LEFT" : "refused"));
        return left ? GroupMembershipRouting.APPLIED : GroupMembershipRouting.REFUSED;
    }

    /**
     * The icon counterpart of {@link #renameGroupRouting}, following {@link #groupPlane}. Unlike a
     * rename, a plaintext or downgraded group is refused, since there is no plaintext group-icon
     * path. The setter never decrypts its own icon message, so {@link MlsGroupMetadata} applies it
     * locally on acceptance.
     *
     * @param iconBytes   the raw image, already scaled by the caller
     *                    ({@code ManageRcsGroupAction.changeIcon})
     * @param contentType the image's own MIME, carried in the RCC.16 §7.8.1 FileInfo; the wire
     * field                    carries {@code message/mls-ft}
     */
    public static GroupMembershipRouting iconChangeRouting(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId,
            final byte[] iconBytes, final String contentType) {
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            return GroupMembershipRouting.PLAINTEXT;
        }
        final GroupPlane plane = MlsMembership.groupPlane(shell, log, rcsGroupId, "icon");
        if (plane == GroupPlane.PLAINTEXT || plane == GroupPlane.MLS_DOWNGRADED) {
            // No plaintext group-icon path exists to fall back to.
            log.i("MlsMembership: REFUSING the icon change of " + rcsGroupId
                    + " — the conversation is " + plane + " and there is no plaintext group-icon "
                    + "path in this provider to fall back to");
            return GroupMembershipRouting.REFUSED;
        }
        if (plane != GroupPlane.MLS) {
            log.e("MlsMembership: REFUSING the icon change of " + rcsGroupId
                    + " — it is " + (plane == GroupPlane.UNKNOWN
                            ? "a conversation we CANNOT CLASSIFY"
                            : "an ENCRYPTED conversation we are LOCKED OUT of (REJOIN), so there is "
                              + "no group to carry a §9.7.1.4 icon commit"));
            return GroupMembershipRouting.REFUSED;
        }
        if (iconBytes == null || iconBytes.length == 0) {
            log.w("MlsMembership: REFUSING an EMPTY icon change on MLS group "
                    + rcsGroupId + " — clearing a group icon is a different operation and this "
                    + "provider has no verb for it");
            return GroupMembershipRouting.REFUSED;
        }
        // peerE164 null: a group key ignores it.
        final byte[] ct = MlsGroupMetadata.changeGroupIcon(shell, log, rcsGroupId,
                /*peerE164=*/ null, iconBytes, contentType);
        log.i("MlsMembership: routed the icon change of MLS group " + rcsGroupId
                + " through the §9.7.1.4 encrypted-icon flow → "
                + (ct == null ? "REFUSED" : "APPLIED, ciphertext=" + ct.length + "B"));
        return ct != null ? GroupMembershipRouting.APPLIED : GroupMembershipRouting.REFUSED;
    }

    public static GroupMembershipRouting renameGroupRouting(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String newName) {
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            // Not a group, so there is no MLS state to protect.
            return GroupMembershipRouting.PLAINTEXT;
        }
        final GroupPlane plane = MlsMembership.groupPlane(shell, log, rcsGroupId, "rename");
        if (plane == GroupPlane.PLAINTEXT) {
            return GroupMembershipRouting.PLAINTEXT;
        }
        if (plane == GroupPlane.MLS_DOWNGRADED) {
            // A downgraded conversation is plaintext (RCC.16 §9.1.1), and so is its name.
            log.i("MlsMembership: rename of " + rcsGroupId + " takes the bare RPC — "
                    + "we still hold engine state for it, but it has been DOWNGRADED (end_mls), so "
                    + "the conversation is plaintext and so is its name");
            return GroupMembershipRouting.PLAINTEXT;
        }
        if (plane != GroupPlane.MLS) {
            // UNKNOWN has no engine and MLS_LOCKED_OUT no group to commit in; the bare RPC would
            // put the name on the server in the clear.
            log.e("MlsMembership: REFUSING the rename of " + rcsGroupId + " — it is "
                    + (plane == GroupPlane.UNKNOWN
                            ? "a conversation we CANNOT CLASSIFY, and a bare ChangeGroupProfile on "
                              + "an encrypted group would put its name on the server in the clear"
                            : "an ENCRYPTED conversation we are LOCKED OUT of (REJOIN), so there is "
                              + "no group to carry a §9.7.1.5 subject commit and the bare "
                              + "ChangeGroupProfile would put its name at field 1 in the clear"));
            return GroupMembershipRouting.REFUSED;
        }
        if (newName == null || newName.isEmpty()) {
            // The bare RPC is not a fallback for an encrypted conversation.
            log.w("MlsMembership: REFUSING an EMPTY rename of MLS group "
                    + rcsGroupId + " — there is no subject to encrypt, and the bare "
                    + "ChangeGroupProfile is not the fallback for an encrypted conversation.");
            return GroupMembershipRouting.REFUSED;
        }
        // peerE164 null: a group key ignores it.
        final byte[] ct = MlsGroupMetadata.changeGroupSubject(shell, log, rcsGroupId,
                /*peerE164=*/ null,
                newName.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
        log.i("MlsMembership: routed the rename of MLS group " + rcsGroupId
                + " through the §9.7.1.5 encrypted-subject flow → "
                + (ct == null ? "REFUSED" : "APPLIED, ciphertext=" + ct.length + "B"));
        return ct != null ? GroupMembershipRouting.APPLIED : GroupMembershipRouting.REFUSED;
    }

    /**
     * One app-layer group membership change, routed: the entry point an app action calls instead of
     * the bare RPC, which an MLS conversation refuses. Routed here rather than in
     * {@code ProviderTransport}, because {@link #rcsMembershipChange} calls that layer and an
     * MLS-aware wrapper there would recurse. See docs/rcs/groups.md.
     *
     * @param rcsGroupId  the RCS group; required
     * @param memberE164s exactly one member on an MLS group
     * @param add         true to add, false to remove
     */
    public static GroupMembershipRouting changeGroupMembership(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId,
            final java.util.List<String> memberE164s, final boolean add) {
        final String what = add ? "add" : "remove";
        if (rcsGroupId == null || rcsGroupId.isEmpty()
                || memberE164s == null || memberE164s.isEmpty()) {
            log.w("MlsMembership: changeGroupMembership needs a group and at least "
                    + "one member — got groupId=" + rcsGroupId + " members="
                    + LogMask.numbers(memberE164s));
            return GroupMembershipRouting.REFUSED;
        }
        switch (MlsMembership.groupPlane(shell, log, rcsGroupId, what)) {
            case PLAINTEXT:
                return GroupMembershipRouting.PLAINTEXT;
            case UNKNOWN:
            case MLS_LOCKED_OUT:
                // Neither may take the bare RPC; groupPlane logged why.
                return GroupMembershipRouting.REFUSED;
            case MLS_DOWNGRADED:
                // Falls through to the commit arm, as it did before this state existed; whether a
                // membership commit on a downgraded group is right is an open question.
                break;
            default:
                break;   // MLS — we hold the group; fall through to the commit-carrying arm below
        }
        if (memberE164s.size() != 1) {
            // One commit carries one member's change; there is no MLS form for more.
            log.e("MlsMembership: REFUSING a " + memberE164s.size() + "-member "
                    + what + " on MLS group " + rcsGroupId + " — one commit carries one membership "
                    + "change, and there is no MLS shape for this request. Issue them singly.");
            return GroupMembershipRouting.REFUSED;
        }
        final String member = memberE164s.get(0);
        final int era = add
                ? MlsMembership.addMember(shell, log, rcsGroupId, /*peerE164=*/ null, member)
                : MlsMembership.removeMember(shell, log, rcsGroupId, /*peerE164=*/ null,
                        /*memberSigPub=*/ null, member);
        log.i("MlsMembership: routed the " + what + " of " + LogMask.number(member)
                + " on MLS group "
                + rcsGroupId + " through the commit-carrying RPC → "
                + (era >= 0 ? "era=" + era : "refused"));
        return era >= 0 ? GroupMembershipRouting.APPLIED : GroupMembershipRouting.REFUSED;
    }
}
