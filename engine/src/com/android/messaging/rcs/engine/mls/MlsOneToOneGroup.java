/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.log.LogMask;
/**
 * Brings a 1:1 conversation's MLS group into being: use the one we hold if it still loads, adopt
 * the provider's group for this peer, or claim the peer's KeyPackage and create one, re-creating
 * past an era or at the server's group id when a create is refused for either reason. See
 * docs/mls/group-lifecycle.md.
 */
public final class MlsOneToOneGroup {
    private MlsOneToOneGroup() {}

    /**
     * Make the 1:1 with {@code peers.get(0)} ready to send.
     *
     * @param recreateAlreadyCharged the caller (a rebuild) already charged the era budget for this
     *     re-creation, so the re-create arms here do not charge it again
     * @param stateAlreadyDestroyed both halves of our state are already gone (a rebuild); a fetch
     *     ledger refusal of an era read then fails open, since declining would leave nothing at all
     */
    public static boolean ensureReady(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final int subId,
            final List<String> peers, final boolean recreateAlreadyCharged,
            final boolean stateAlreadyDestroyed) {
        if (!shell.ensureSession()) return false;
        if (peers == null || peers.size() != 1) {
            log.w("MlsOneToOneGroup: 1:1 only (peers="
                    + (peers == null ? 0 : peers.size()) + ")");
            return false;
        }
        final String peer = peers.get(0);
        // 1:1 conversations key on the peer, not the UI's conversation id, which inbound lacks.
        final String key = MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peer);
        if (key == null) return false;
        shell.convAlias().put(conversationId, key);
        shell.keyToConvId().put(key, conversationId);
        // A cached entry can outlive its engine state; report ready only if the group still loads.
        final Group cached = shell.getGroup(key);
        if (cached != null) {
            if (cached.groupId == null
                    || MlsGroupState.groupLoads(shell.session(), log, cached.groupId)) {
                return true;
            }
            log.w("MlsOneToOneGroup: cached group for " + LogMask.number(peer)
                    + " no longer loads in "
                    + "the engine — dropping the stale entry and re-establishing rather than "
                    + "reporting ready for a conversation that cannot send");
            shell.groups().remove(key);
        }

        final MlsProviderRpc pt = shell.rpc("establishGroup");

        // Adopt first, keyed on the peer: the provider may hold a group this class has never seen,
        // and creating a second one would be refused and burn an era.
        final byte[] existingGid = pt.getMlsGroupIdForPeer(peer);
        if (existingGid != null && existingGid.length > 0) {
            final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(existingGid));
            // A readable era is not a loadable group; groupLoads() performs a real load.
            final boolean loadable = (era >= 0)
                    && MlsGroupState.groupLoads(shell.session(), log, existingGid);
            if (loadable) {
                final Group adopted = new Group();
                adopted.groupId = existingGid;
                adopted.peerE164 = peer;
                adopted.era = era;
                adopted.epochAuth = shell.session().epochAuth(existingGid);
                shell.putGroup(key, adopted);
                log.i("MlsOneToOneGroup: ADOPTED migrated group for " + LogMask.number(peer)
                        + " at era=" + era + " (no re-establish, no era burned)");
                return true;
            }
            // The provider knows the group but our engine cannot load it: re-establish by an era
            // advance from the server's era and roster, reusing the group id so the server sees the
            // same conversation. The NORMAL kind, not revival, so a downgrade is not silently
            // cleared.
            log.w("MlsOneToOneGroup: provider has a group for " + LogMask.number(peer)
                    + " but our engine state cannot read it — RE-ESTABLISHING at a new era from the "
                    + "server's roster rather than refusing (the group id is reused, so the server "
                    + "sees the same conversation)");
            final Group orphan = new Group();
            orphan.groupId = existingGid;
            orphan.peerE164 = peer;
            orphan.era = 0;                 // unknown — eraAdvance derives it from the server
            shell.putGroup(key, orphan);
            final int revivedEra = shell.eraAdvance(/*rcsGroupId=*/ null, peer,
                    /*carryGroupInfo=*/ null,
                    MlsAdvanceEraKind.NORMAL, /*requireRebuildableRoster=*/ false);
            if (revivedEra > 0) {
                log.i("MlsOneToOneGroup: RE-ESTABLISHED " + LogMask.number(peer) + " at era "
                        + revivedEra + " — the conversation is usable again");
                return true;
            }
            // Evicts only the cached row; getGroup() can rebuild it from the alias and record. The
            // real teardown is clearConversationState.
            shell.groups().remove(key);
            log.e("MlsOneToOneGroup: could not re-establish " + LogMask.number(peer)
                    + " (era advance returned " + revivedEra
                    + "). The conversation stays unusable; "
                    + "the group id is intact, so a later attempt can retry this same path.");
            return false;
        }

        // Initiator path: claim the peer's KeyPackage, build the group here, and hand the provider
        // the artifacts. From a rebuild the claim is REBUILD_RECREATE, which the ledger never
        // refuses: the claimed package is what the re-create is made of.
        final Claim<byte[]> claim = shell.claimOne(stateAlreadyDestroyed
                ? MlsClaimLedger.Caller.REBUILD_RECREATE
                : MlsClaimLedger.Caller.ENSURE_READY, peer);
        // Checked here too rather than relying on REBUILD_RECREATE being unrefusable elsewhere.
        if (claim.refused() && !stateAlreadyDestroyed) {
            // Our own ledger refused; this says nothing about the peer's pool.
            log.w("MlsOneToOneGroup: NOT creating the 1:1 with " + LogMask.number(peer)
                    + " — our own claim ledger refused the KeyPackage claim, so we never asked and "
                    + "know NOTHING about their pool. The conversation is unchanged and this is "
                    + "retried from the next send. " + claim.why());
            return false;
        }
        final byte[] peerKp = claim.orNull();
        if (peerKp == null) {
            if (claim.refused()) {
                // Unreachable while REBUILD_RECREATE is unrefusable; logged so the cause is not
                // blamed on the peer. There is no fail-open: the claim's product is the input.
                log.e("MlsOneToOneGroup: the rebuild's re-create for " + LogMask.number(peer)
                        + " was "
                        + "REFUSED a KeyPackage claim, which cannot happen while REBUILD_RECREATE is "
                        + "unrefusable — the ledger and ensureReady disagree. The conversation is "
                        + "left with no group, and unlike a refused LOOK there is nothing to proceed "
                        + "without: the claim's product IS the input. " + claim.why());
            }
            MlsKeyPackageClaims.logClaimBlocked(log,
                    MlsClaimLedger.oneToOneCreateBlockedLine(peer, claim.attribution()));
            return false;
        }
        if (!shell.keyPackageUsable(peerKp, peer)) return false;   // RCC.16 A.4.1.2 floor
        // The engine chooses the era from its own state; a host-picked era can be accepted and
        // silently discarded, while a server refusal is recoverable.
        // Read-only diagnostic: log when the server already holds this conversation, but do not
        // refuse. See docs/mls/group-lifecycle.md.
        try {
            // Charged like any look; a refused look prints nothing.
            final long[] srvNow = shell.lookServerEraEpoch(MlsFetchLedger.Caller.ENSURE_READY,
                    MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peer), peer,
                    /*rcsGroupId=*/ null).orNull();
            if (srvNow != null && srvNow.length >= 2 && srvNow[0]
                    >= MlsTransportTypes.ERA_INITIAL) {
                log.w("MlsOneToOneGroup: ESTABLISH-OVER-HELD — creating a 1:1 with "
                        + LogMask.number(peer)
                        + " while the SERVER already holds this conversation at era "
                        + srvNow[0] + " epoch " + srvNow[1] + ". Twice observed to leave a"
                        + " persistent era gap that recovered only via the rebuild path. NOT"
                        + " refusing (see the comment above) — recorded so the behaviour can be"
                        + " characterised from real usage rather than from two runs.");
            }
        } catch (final Throwable t) {
            // A diagnostic must never be able to fail an establish.
            log.i("MlsOneToOneGroup: could not read the server era before establishing "
                    + "with " + LogMask.number(peer) + " (diagnostic only)");
        }
        MlsGroupArtifacts art = shell.session().createGroupPlanned(
                java.util.Collections.singletonList(peerKp), /*groupIdOverride=*/ null,
                /*carryGroupInfo=*/ null, MlsAdvanceEraKind.NORMAL);
        if (art == null || art.groupId == null || art.welcome == null) {
            log.e("MlsOneToOneGroup: createGroup failed for " + LogMask.number(peer));
            return false;
        }
        if (art.welcomeAction == null || art.era < MlsTransportTypes.ERA_INITIAL) {
            log.e("MlsOneToOneGroup: the engine reported no plan for " + LogMask.number(peer)
                    + " (era=" + art.era + " action=" + art.welcomeAction + "). Refusing rather "
                    + "than assuming a create.");
            return false;
        }
        // Contained: a throw here would escape into whichever send triggered the establish.
        try {
            MlsWelcomeAction.requireJoinable(art.welcomeAction);
        } catch (final IllegalStateException notJoinable) {
            log.e("MlsOneToOneGroup: " + notJoinable.getMessage() + " Refusing to "
                    + "establish with " + LogMask.number(peer)
                    + " rather than treating an action this build cannot "
                    + "name as a create.");
            return false;
        }
        long targetEra = art.era;
        log.i("MlsOneToOneGroup: the engine chose " + art.welcomeAction + " at era="
                + targetEra + " for " + LogMask.number(peer));
        MlsProviderRpc.ControlResult r = pt.createMlsConversation(peer, art.groupId,
                art.welcome, art.commit, art.groupInfo, art.tag, art.ratchetTree,
                (int) targetEra, conversationId, /*rcsGroupId=*/ null);
        MlsDriveLoop.noteControlVerdict(shell, key, r);

        // Prior engine state at the id we build at, for the rollback below. Only the reclaim arm
        // sets it, since the initial create mints a fresh UUID.
        byte[] preCreate1to1 = null;

        // After local state loss the server refuses "Era changed from" before it will name its
        // group id, so re-create at the server's era + 1 to reach that answer; the reclaim below
        // then uses it. The era budget applies from here on: these arms re-create over a
        // conversation the server holds, making the peer re-join. Charged once per call, since the
        // reclaim is only reachable through the bump's create being refused (MlsRecreationEpisode).
        MlsRecreationEpisode.PriorCharge prior = MlsRecreationEpisode.priorCharge(
                recreateAlreadyCharged, /*consumedByAnAcceptedRecreation=*/ false);
        if (r != null && MlsServerMessage.groupIdFromChangeReject(r.detail) == null
                && r.detail != null && r.detail.contains("Era changed from")) {
            final Look<long[]> bump = shell.lookServerEraEpoch(MlsFetchLedger.Caller.ENSURE_READY,
                    MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peer), peer,
                    /*rcsGroupId=*/ null);
            if (bump.refused() && !stateAlreadyDestroyed) {
                // Without the server's era the re-create would skip the era-budget charge; decline.
                log.w("MlsOneToOneGroup: NOT re-creating the 1:1 with " + LogMask.number(peer)
                        + " after an 'Era changed from' refusal — the fetch ledger refused the look "
                        + "that establishes the server's era. We will not re-create over a "
                        + "conversation the server holds at an era we did not read. The original "
                        + "refusal stands and the next trigger re-reads.");
                return false;
            }
            if (bump.refused()) {
                // Fail open: see stateAlreadyDestroyed.
                log.w("MlsOneToOneGroup: the 'Era changed from' re-create for "
                        + LogMask.number(peer)
                        + " is PROCEEDING WITHOUT the server's era — the fetch ledger refused that "
                        + "look, but a rebuild has already dropped both halves of our state, so "
                        + "declining would leave this conversation with nothing at all. Proceeding "
                        + "at the engine's own era, exactly as an unreadable read already does.");
            }
            final long[] srvEra = bump.orNull();
            final long known = (srvEra != null && srvEra.length > 0) ? srvEra[0] : -1L;
            final long next = known >= MlsTransportTypes.ERA_INITIAL ? MlsAppMessage.nextEra(known)
                    : -1L;
            if (next >= MlsTransportTypes.ERA_INITIAL
                    && MlsRecreationEpisode.needsItsOwnCharge(prior)
                    && !shell.peerGuard().allowEraAdvance("reestablish-1to1", /*rcsGroupId=*/ null,
                            peer)) {
                log.e("MlsOneToOneGroup: the server holds this conversation with "
                        + LogMask.number(peer) + " at era " + known
                        + " and re-creating over it would make them "
                        + "re-join by Welcome — but the era budget refused. Leaving the original "
                        + "refusal in place; the allowance refills.");
                return false;
            }
            if (next >= MlsTransportTypes.ERA_INITIAL) {
                // A refused create re-joins nobody, so the reclaim inherits this charge.
                prior = MlsRecreationEpisode.priorCharge(
                        /*spent=*/ true, /*consumedByAnAcceptedRecreation=*/ false);
            }
            if (next >= MlsTransportTypes.ERA_INITIAL) {
                log.i("MlsOneToOneGroup: the server refused the create for " + LogMask.number(peer)
                        + " with an ERA change (" + r.detail + ") and named no group id. It holds "
                        + "era=" + known + "; re-creating at era=" + next + " so the refusal can "
                        + "progress to the group-id answer the reclaim arm needs. Without this the "
                        + "conversation is unrecoverable after any local state loss.");
                final MlsGroupArtifacts bumped =
                        shell.session().createGroupWithId(next, peerKp, art.groupId);
                if (bumped != null && bumped.welcome != null) {
                    art = bumped;
                    targetEra = next;
                    r = pt.createMlsConversation(peer, art.groupId,
                            art.welcome, art.commit, art.groupInfo, art.tag, art.ratchetTree,
                            (int) targetEra, conversationId, /*rcsGroupId=*/ null);
                    MlsDriveLoop.noteControlVerdict(shell, key, r);
                } else {
                    log.w("MlsOneToOneGroup: could not rebuild the group at era "
                            + next + " for " + LogMask.number(peer)
                            + " — leaving the original refusal in place.");
                }
            } else {
                log.w("MlsOneToOneGroup: the server refused the create for " + LogMask.number(peer)
                        + " with an ERA change but reports no readable era of its own (got "
                        + known + "). Nothing to advance past; leaving the refusal in place.");
            }
        }

        final String reclaimed = (r == null) ? null
                : MlsServerMessage.groupIdFromChangeReject(r.detail);
        if (reclaimed != null) {
            // The era budget, unless the bump arm already charged this re-creation.
            if (MlsRecreationEpisode.needsItsOwnCharge(prior)
                    && !shell.peerGuard().allowEraAdvance("reestablish-1to1", /*rcsGroupId=*/ null,
                            peer)) {
                log.e("MlsOneToOneGroup: the server named its own group id for "
                        + LogMask.number(peer)
                        + ", so re-creating at it would make them re-join by Welcome — but the era "
                        + "budget refused. Leaving the refusal in place; the allowance refills.");
                return false;
            }
            // The one host-supplied era in the create path. It is read only after the server named
            // its own group id in a refusal, so both the id and the era come from the server.
            final Look<long[]> reclaim = shell.lookServerEraEpoch(
                    MlsFetchLedger.Caller.ENSURE_READY,
                    MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peer), peer,
                    /*rcsGroupId=*/ null);
            if (reclaim.refused() && !stateAlreadyDestroyed) {
                log.w("MlsOneToOneGroup: NOT reclaiming the 1:1 with " + LogMask.number(peer)
                        + " — the fetch ledger refused the server-era look. The reclaim re-creates "
                        + "at the server's own group id AND at server_era+1, so without that era it "
                        + "has neither of the two things this comment says are needed together. "
                        + "Declining rather than guessing one of them.");
                return false;
            }
            if (reclaim.refused()) {
                // Fail open: a null read skips the era bump below and keeps the engine's era.
                log.w("MlsOneToOneGroup: the era reclaim for " + LogMask.number(peer) + " is "
                        + "PROCEEDING WITHOUT the server's era — the fetch ledger refused that look, "
                        + "but a rebuild has already dropped both halves of our state, so declining "
                        + "would leave this conversation with nothing at all.");
            }
            final long[] srv = reclaim.orNull();
            if (srv != null && srv[0] >= MlsTransportTypes.ERA_INITIAL) {
                final long next = MlsAppMessage.nextEra(srv[0]);
                if (next < 0) {
                    log.e("MlsOneToOneGroup: the server holds " + LogMask.number(peer) + " at era "
                            + srv[0] + " — the u32 ceiling. Advancing would wrap to 0, which reads "
                            + "as a move BACKWARDS to every peer and cannot be undone. Refusing.");
                    return false;
                }
                targetEra = next;
            }
            log.i("MlsOneToOneGroup: the server holds this conversation under group id "
                    + reclaimed + " — re-creating at era=" + targetEra + " with that id");
            final byte[] gidBytes = reclaimed.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            preCreate1to1 = shell.session().exportGroupSnapshot(gidBytes);
            final MlsGroupArtifacts revived =
                    shell.session().createGroupWithId(targetEra, peerKp, gidBytes);
            if (revived != null && revived.groupId != null && revived.welcome != null) {
                art = revived;
                r = pt.createMlsConversation(peer, art.groupId, art.welcome, art.commit,
                        art.groupInfo, art.tag, art.ratchetTree, (int) targetEra, conversationId,
                        /*rcsGroupId=*/ null);
                MlsDriveLoop.noteControlVerdict(shell, key, r);
            } else {
                log.e("MlsOneToOneGroup: createGroupWithId refused the server's id");
            }
        }
        if (r == null || !r.ok()) {
            // Roll back to the prior snapshot if there was one, otherwise keep what was built (see
            // MlsUpgradePolicy.rollBackDiscardedCreate).
            final boolean rolled = MlsUpgradePolicy.rollBackDiscardedCreate(shell.session(), log,
                    art.groupId, preCreate1to1);
            // Log the spec-level verdict, never a backend string.
            log.w("MlsOneToOneGroup: createMlsConversation refused → " + r
                    + (rolled ? " (engine state rolled back to what the server last accepted)"
                            : ""));
            return false;
        }
        final Group g = new Group();
        g.groupId = art.groupId;
        g.peerE164 = peer;
        g.era = targetEra;
        g.epochAuth = art.tag;
        shell.putGroup(key, g);
        // The group exists and is usable: Unknown --GroupCreated--> Healthy.
        shell.moveHealth(key, MlsHealthStates.HEALTHY, "group created with "
                + LogMask.number(peer));
        log.i("MlsOneToOneGroup: established with " + LogMask.number(peer)
                + " (gid " + art.groupId.length + "B)");
        return true;
    }
}
