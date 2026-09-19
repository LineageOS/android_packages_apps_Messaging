/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.log.LogMask;
/**
 * The RCC.16 §11.2.2 external-commit resync: rejoin the server's current group from its GroupInfo
 * when our state cannot be reconciled by replay. Charged against the external-commit budget, a
 * separate allowance from the era quota, and rolled back to a snapshot if the provider refuses. A
 * dry run answers viable or not without building, sending or charging the budget.
 */
public final class MlsExternalCommitResync {
    private MlsExternalCommitResync() {}

    /**
     * {@code getMlsGroupInfoForGroup}, charged: the unanchored GroupInfo an external commit needs.
     */
    public static Look<MlsProviderRpc.ControlResult> lookGroupInfoForGroup(final MlsShellPort shell,
            final MlsLogSink log,
            final MlsFetchLedger.Caller caller, final String key, final String peerE164,
            final String rcsGroupId) {
        return MlsFetchLedger.spendOneLook(shell, log, caller,
                MlsFetchLedger.Primitive.GET_MLS_GROUP_INFO_FOR_GROUP, key,
                () -> shell.rpc("getMlsGroupInfoForGroup")
                        .getMlsGroupInfoForGroup(peerE164, rcsGroupId));
    }

    /**
     * Is an external-commit resync worth attempting here? False while we are still a member on a
     * transport whose profile refuses ExternalInit from members (RFC 9420 §12.4.3.2).
     */
    public static boolean resyncApplies(final MlsShellPort shell, final boolean weAreStillAMember) {
        return MlsRecoveryPolicy.externalCommitResyncApplies(
                shell.transportProfile().acceptsMemberExternalCommit, weAreStillAMember);
    }

    /**
     * A dry run answered no: the unanchored GroupInfo carries no {@code external_pub}, so RFC 9420
     * §12.4.3.2 has nothing to derive an ExternalInit from.
     */
    public static final int RESYNC_DRY_RUN_NOT_VIABLE = -5;

    /**
     * A dry run reached the build and stopped: the group can be rejoined by external commit.
     * Distinct from -1, which means an attempt failed; a dry run makes no attempt.
     */
    public static final int RESYNC_DRY_RUN_VIABLE = -4;

    /**
     * The resync. {@code dryRun} fetches what the real path fetches, through the same readers, and
     * returns {@link #RESYNC_DRY_RUN_VIABLE} or {@link #RESYNC_DRY_RUN_NOT_VIABLE} having
     * transmitted nothing and charged no external-commit allowance (it still spends three ledger
     * looks).
     */
    public static int resyncViaExternalCommit(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile, final long removeLeafIndex,
            final boolean dryRun) {
        if (!shell.ensureSession()) return -1;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsExternalCommitResync: external-commit resync refused — no group resolves "
                    + "for rcsGroupId=" + rcsGroupId + " peer=" + LogMask.number(peerE164));
            return -1;
        }
        // The transport profile must accept a member external commit. That is our client policy,
        // not a server limitation (the server's GroupInfo often carries external_pub). Checked
        // before the budget so a refusing transport cannot spend the allowance discovering it.
        if (!forcePastProfile
                && !MlsExternalCommitResync.resyncApplies(shell, /*weAreStillAMember=*/ true)) {
            log.w("MlsExternalCommitResync: NOT attempting a resync external commit for "
                    + MlsConversationKey.forLog(key)
                    + " — resyncApplies() is false for an existing member under profile "
                    + shell.transportProfile()
                    + ". This is OUR POLICY, not a server limitation: the server's "
                    + "GroupInfo can carry external_pub, "
                    + "and "
                    + "the path is built and correct. Recovery falls through to the "
                    + "rebuild and then to the END_MLS terminal.");
            return -1;
        }
        // A dry run transmits nothing, so it must not spend the allowance that bounds transmitting.
        if (!dryRun && !shell.xcBudget().claim(key)) return -1;

        // The group-addressed, unanchored fetch: the anchored GroupInfo that self-heal reads is
        // trimmed to our anchor and may lack external_pub, which the external commit is derived
        // from.
        final Look<MlsProviderRpc.ControlResult> giLook =
                MlsExternalCommitResync.lookGroupInfoForGroup(shell, log,
                        MlsFetchLedger.Caller.EXTERNAL_COMMIT_RESYNC, key, peerE164, rcsGroupId);
        if (giLook.refused()) {
            // Logged apart from an unreadable GroupInfo: here nothing was asked.
            log.w("MlsExternalCommitResync: external-commit resync for "
                    + MlsConversationKey.forLog(key) + " NOT "
                    + "ATTEMPTED — the fetch ledger refused the unanchored GroupInfo look. This is "
                    + "the resync rung declining to spend, not a group whose GroupInfo could not be "
                    + "read; the ladder re-drives it from a fresh trigger.");
            return -1;
        }
        final MlsProviderRpc.ControlResult giR = giLook.orNull();
        final byte[] gi = (giR != null && giR.verdict == MlsProviderRpc.ControlResult.VERDICT_OK)
                ? giR.response : null;
        if (gi == null || gi.length == 0) {
            log.w("MlsExternalCommitResync: external-commit resync for "
                    + MlsConversationKey.forLog(key) + " could not "
                    + "fetch the GROUP's GroupInfo → " + giR
                    + ". Not falling back to the self-heal "
                    + "fetch's copy: it lacks external_pub, so building from it fails anyway and the "
                    + "failure would look like an engine bug rather than a missing fetch.");
            return -1;
        }
        // The tree comes from the self-heal pack; this fetch does not carry one.
        final Look<byte[]> treeLook = shell.fetchServerPack(
                MlsFetchLedger.Caller.EXTERNAL_COMMIT_RESYNC, rcsGroupId, peerE164, g);
        if (treeLook.refused()) {
            log.w("MlsExternalCommitResync: external-commit resync for "
                    + MlsConversationKey.forLog(key) + " ABANDONED "
                    + "part-way — the fetch ledger refused the ratchet-tree look after the "
                    + "GroupInfo had already been fetched. Nothing has been committed or persisted "
                    + "at this point, so this costs a look and no state.");
            return -1;
        }
        final byte[] pack = treeLook.orNull();
        // A GroupInfo carrying end_mls: rejoining would re-encrypt a deliberately plaintext
        // conversation.
        if (MlsStateChangeGate.groupInfoHasEndMls(shell.session(), log, gi)) {
            log.w("MlsExternalCommitResync: NOT resyncing " + MlsConversationKey.forLog(key)
                    + " — the server's GroupInfo "
                    + "carries end_mls (0xF002). This group was downgraded out of MLS; an external "
                    + "commit would re-encrypt a conversation that is deliberately plaintext.");
            return -1;
        }
        final byte[] tree = MlsWireScan.firstPacked(pack, 1);

        // The base is the server's epoch authenticator, not ours: we commit on top of the server's
        // epoch, and ours is exactly what does not match.
        final Look<byte[]> baseLook = shell.lookServerEpochAuthenticator(
                MlsFetchLedger.Caller.EXTERNAL_COMMIT_RESYNC, key, peerE164, rcsGroupId);
        if (baseLook.refused()) {
            log.w("MlsExternalCommitResync: external-commit resync for "
                    + MlsConversationKey.forLog(key) + " has no "
                    + "SERVER epoch authenticator to commit against because the fetch ledger "
                    + "refused that look — not because the server had none. Falling back to our own "
                    + "would base the commit on the very state the server is rejecting, so this "
                    + "refuses for the same reason the absent case does.");
            return -1;
        }
        final byte[] baseEpochAuth = baseLook.orNull();
        if (baseEpochAuth == null || baseEpochAuth.length == 0) {
            log.w("MlsExternalCommitResync: external-commit resync for "
                    + MlsConversationKey.forLog(key) + " has no "
                    + "SERVER epoch authenticator to commit against. Falling back to our own would "
                    + "base the commit on the very state the server is rejecting — refusing instead.");
            return -1;
        }

        // Snapshot first: the build persists the post-commit group before the server accepts it,
        // and a refusal must not leave us diverged by our own repair.
        final byte[] preResync = shell.session().exportGroupSnapshot(g.groupId);
        if (preResync == null) {
            log.w("MlsExternalCommitResync: refusing the resync for "
                    + MlsConversationKey.forLog(key) + " — could not "
                    + "snapshot the group first, and this path persists before the server answers. "
                    + "Without a rollback a refusal would leave us diverged by our own repair.");
            return -1;
        }
        // Pre-flight: without external_pub (0x0004) RFC 9420 §12.4.3.2 says the build cannot
        // succeed, so do not spend an external-commit charge on it. Read through
        // groupInfoContinuity, which walks the GroupInfo's own extension list (groupInfoExtTypes
        // walks the GroupContext list). Logged either way, since this is the only reader of the
        // unanchored copy.
        final byte[] giExternalPub = shell.session().groupInfoContinuity(gi, 0x0004);
        final boolean giHasExternalPub = giExternalPub != null && giExternalPub.length > 0;
        log.i("MlsExternalCommitResync: resync pre-flight for " + MlsConversationKey.forLog(key)
                + " — UNANCHORED "
                + "GroupInfo " + gi.length + "B, external_pub(0x0004)="
                + (giHasExternalPub ? giExternalPub.length + "B" : "ABSENT")
                + ". This is the copy the commit is BUILT from, which is NOT the anchored copy "
                + "`--ez groupexts` prints.");
        if (!giHasExternalPub) {
            log.w("MlsExternalCommitResync: NOT building a resync external commit for "
                    + MlsConversationKey.forLog(key)
                    + " — the UNANCHORED GroupInfo (" + gi.length + "B) carries no external_pub "
                    + "(0x0004), so RFC 9420 §12.4.3.2 has nothing to derive the ExternalInit from "
                    + "and the engine would answer MissingExternalPubExtension. Refusing HERE rather "
                    + "than letting the build fail spends no §11.2.2 external-commit charge on an "
                    + "attempt RFC 9420 has already decided. Nothing was built, nothing was sent, "
                    + "and nothing local was touched — on a current engine a failed build "
                    + "would not have touched it either.");
            return dryRun ? RESYNC_DRY_RUN_NOT_VIABLE : -1;
        }
        if (dryRun) {
            log.i("MlsExternalCommitResync: resync DRY RUN for " + MlsConversationKey.forLog(key)
                    + " — VIABLE. The "
                    + "unanchored GroupInfo carries external_pub, the tree and the server epoch "
                    + "authenticator both read, and end_mls is absent, so the build would be "
                    + "attempted. NOTHING was built, sent or deleted and the §11.2.2 budget was not "
                    + "charged. Re-run without --ez dryrun to attempt it for real — that commit "
                    + "RE-ENTERS the group and every member sees it.");
            return RESYNC_DRY_RUN_VIABLE;
        }
        final MlsSession.ExternalCommit ec =
                shell.session().externalCommitResync(gi, tree, removeLeafIndex);
        if (ec == null) {
            // Restore anyway: the current engine leaves the record untouched on a failed build, but
            // the native library is versioned separately and an older one deleted the group first.
            final boolean rolledBack = shell.session().restoreGroupSnapshot(g.groupId, preResync);
            log.w("MlsExternalCommitResync: the engine could not BUILD a resync external commit "
                    + "for " + MlsConversationKey.forLog(key) + " (gi=" + gi.length + "B tree="
                    + (tree == null ? 0 : tree.length) + "B). Nothing was sent"
                    + (rolledBack
                            ? ", and the pre-resync snapshot was written back. On a current engine "
                                    + "that is a no-op over identical bytes; on an older one it "
                                    + "is what puts the group back."
                            : ", and THE SNAPSHOT WRITE-BACK FAILED. On a current engine a "
                                    + "failed build leaves the record untouched, so this is a "
                                    + "storage fault to investigate rather than a lost group; on an "
                                    + "older engine this conversation may now hold no group at all "
                                    + "and needs a re-Welcome."));
            if (!rolledBack) {
                MlsExternalCommitResync.afterFailedRollback(shell, log, key, g, rcsGroupId,
                        peerE164, /*serverRefused=*/ false);
            }
            return -1;
        }
        // An ExternalInit is valid only from new_member_commit (RFC 9420 §12.4.3.2); log the sender
        // type so a framing bug is not mistaken for a policy refusal.
        final int senderType = MlsWireScan.senderTypeOf(ec.commit);
        log.i("MlsExternalCommitResync: RESYNC EXTERNAL COMMIT built for "
                + MlsConversationKey.forLog(key) + " (" + why
                + ") — commit=" + ec.commit.length + "B groupInfo=" + ec.groupInfo.length
                + "B tree=" + ec.ratchetTree.length + "B epochAuth=" + ec.epochAuth.length
                + "B sender=" + MlsWireScan.senderTypeName(senderType) + "(" + senderType + ")");
        if (senderType != MlsWireScan.SENDER_NEW_MEMBER_COMMIT) {
            log.e("MlsExternalCommitResync: this resync is framed as "
                    + MlsWireScan.senderTypeName(senderType) + ", NOT new_member_commit. RFC 9420 "
                    + "§12.4.3.2 only accepts an ExternalInit from new_member_commit, so the server "
                    + "will answer 'Invalid proposal type for sender' and that answer will be "
                    + "CORRECT. Sending anyway so the refusal is on the record, but read this as a "
                    + "framing bug on our side, not as a transport policy.");
        }

        final String ctrlId = java.util.UUID.randomUUID().toString();
        final MlsProviderRpc.ControlResult r = shell.rpc("applyMlsControl").applyMlsControl(
                peerE164, ctrlId, ec.groupInfo, ec.commit, ec.epochAuth, ec.ratchetTree,
                baseEpochAuth, rcsGroupId);
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            final boolean rolled = shell.session().restoreGroupSnapshot(g.groupId, preResync);
            log.w("MlsExternalCommitResync: the SERVER REFUSED the resync external commit for "
                    + MlsConversationKey.forLog(key) + " → " + r + (rolled
                            ? " (rolled back to the pre-resync group — the build had already "
                                    + "persisted it)"
                            : " (ROLLBACK FAILED — local state may now be an external-commit group "
                                    + "the members never adopted; this conversation needs a heal)")
                    + ". Not retrying with a different shape: a refusal is a real answer, and "
                    + "laundering it into a second attempt is how we lose track of which shape the "
                    + "server accepts.");
            if (!rolled) {
                MlsExternalCommitResync.afterFailedRollback(shell, log, key, g, rcsGroupId,
                        peerE164, /*serverRefused=*/ true);
            }
            return r == null ? -1 : r.verdict;
        }
        log.w("MlsExternalCommitResync: " + MlsConversationKey.forLog(key)
                + " REJOINED by resync external commit (" + why
                + "). Our stale leaf was self-removed as part of the commit — if a removal of OUR "
                + "OWN leaf is observed for this group now, it is EXPECTED and is not a kick "
                + "(some peers log it as \"Client removed post self-heal; expected.\").");
        MlsRecoveryPolicy.noteForwardProgress(shell, log, key, "resync external commit accepted");
        return r.verdict;
    }

    /**
     * Acts on a resync whose snapshot write-back failed, per
     * {@link MlsRecoveryPolicy#onResyncFailure}. The snapshot counts as held when the group still
     * loads. A group that no longer loads leaves a record pointing at nothing, which fails every
     * send and is skipped by establish, so it is forgotten and the next send re-establishes. A
     * group that loads after a refusal may be the external-commit group the members never adopted,
     * so it is dropped only if it is ahead of the server.
     */
    static MlsRecoveryPolicy.ResyncFailureAction afterFailedRollback(final MlsShellPort shell,
            final MlsLogSink log, final String key, final Group g, final String rcsGroupId,
            final String peerE164, final boolean serverRefused) {
        final byte[] eraEpoch = shell.session().eraEpoch(g.groupId);
        final MlsRecoveryPolicy.ResyncFailureAction action =
                MlsRecoveryPolicy.onResyncFailure(/*haveSnapshot=*/ eraEpoch != null);
        if (action == MlsRecoveryPolicy.ResyncFailureAction.DROP_RECORD_AND_REESTABLISH) {
            log.e("MlsExternalCommitResync: " + MlsConversationKey.forLog(key)
                    + " holds no loadable group after the failed "
                    + "resync and its rollback — forgetting the conversation so the next send "
                    + "re-establishes, instead of keeping a record that fails every send.");
            shell.forget(rcsGroupId, peerE164);
        } else if (serverRefused) {
            shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164,
                    MlsAppMessage.eraFrom(eraEpoch), "resync external commit (rollback FAILED)");
        }
        return action;
    }

    /**
     * @param removeLeafIndex {@code >= 0} forces the self-remove flavour (drop our stale leaf and
     *     come back as a new member); {@code < 0} asks for the plain flavour. The engine falls back
     *     to the self-remove flavour on {@code DuplicateLeafData}; the built flavour is logged
     *     either way.
     */
    public static int resyncViaExternalCommit(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile, final long removeLeafIndex) {
        return MlsExternalCommitResync.resyncViaExternalCommit(shell, log, rcsGroupId, peerE164,
                why, forcePastProfile, removeLeafIndex,
                /*dryRun=*/ false);
    }

    /**
     * @param forcePastProfile operator override for the transport-profile gate, so the profile can
     *     be re-tested; never set from the product
     */
    public static int resyncViaExternalCommit(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final String why, final boolean forcePastProfile) {
        return MlsExternalCommitResync.resyncViaExternalCommit(shell, log, rcsGroupId, peerE164,
                why, forcePastProfile,
                /*removeLeafIndex=*/ 0L);
    }

    /**
     * Resync by external commit: rejoin a group we are nominally in but can no longer follow, by
     * committing on top of the server's GroupInfo. Unlike {@code MlsConversationRebuild}, which
     * creates a new group, it adopts the server's. The commit must carry an ExternalInit and a
     * self-remove of our stale leaf, so the self-remove is forced. After success our own old leaf
     * is seen removed; that is expected, not a kick.
     *
     * @return the provider's control verdict, or -1 if nothing was sent
     */
    public static int resyncViaExternalCommit(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String why) {
        return MlsExternalCommitResync.resyncViaExternalCommit(shell, log, rcsGroupId, peerE164,
                why, /*forcePastProfile=*/ false);
    }

    /**
     * Clear this conversation's RCC.16 §11.2.2 external-commit allowance; reached from the stall
     * notification's Try again. Keyed by the canonical conversation key, as the resync charges it.
     */
    public static void resetExternalCommitBudget(final MlsShellPort shell, final String key) {
        if (key == null) return;
        shell.xcBudget().reset(key);
    }
}
