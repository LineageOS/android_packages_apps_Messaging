/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.RosterClaim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * An era advance as the transport carries it out: charge and guard it at the funnel, build the new
 * group in the engine, hand it to the provider, verify the server's era, and roll back on refusal.
 * Whether and when to advance is {@link MlsAdvancerElection}. See docs/mls/group-lifecycle.md.
 */
public final class MlsEraAdvance {
    private MlsEraAdvance() {}

    /**
     * "Could not build", as against "server refused"; only the former may fall back to the create.
     */
    public static final int BUILD_FAILED = -2;

    // preserve via the apply RPC; the engine refuses to build it (RCC.16 §8.3)
    public static final int ERA_MODE_PRESERVE_CTRL = 2;

    /**
     * Era advance as an in-group commit, keeping membership: no KeyPackage claimed, no Welcome. The
     * engine refuses to build this shape, so in practice it returns {@link #BUILD_FAILED}.
     *
     * @return the new era, -1 on a server refusal, or {@link #BUILD_FAILED} if the commit could not
     *         be built (the only case the caller may retry differently)
     */
    public static int eraAdvancePreserving(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g, final String rcsGroupId,
            final String peerE164, final int curEra, final int newEra, final int mode) {
        // The engine applies the commit before the server agrees, so a refusal must roll us back.
        final byte[] preAdvance = shell.session().exportGroupSnapshot(g.groupId);
        final byte[] baseAuth = shell.session().epochAuth(g.groupId);
        final MlsGroupArtifacts art =
                shell.session().commitEraAdvance(g.groupId, /*aad=*/ null, newEra);
        if (art == null || art.commit == null || art.commit.length == 0) {
            log.w("MlsEraAdvance: commitEraAdvance produced no commit for "
                    + MlsConversationKey.forLog(key));
            if (preAdvance != null) shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            return BUILD_FAILED;
        }
        final MlsProviderRpc pt = shell.rpc("eraAdvancePreserving");
        final MlsProviderRpc.ControlResult r;
        if (mode == ERA_MODE_PRESERVE_CTRL) {
            r = pt.applyMlsControl(peerE164, java.util.UUID.randomUUID().toString(),
                    art.groupInfo, art.commit, art.tag, art.ratchetTree, baseAuth, rcsGroupId);
        } else {
            // Same RPC as the create path, with an empty Welcome and no re-added members.
            r = pt.createMlsConversation(peerE164, g.groupId, /*welcome=*/ new byte[0],
                    art.commit, art.groupInfo, art.tag, art.ratchetTree, newEra, key, rcsGroupId);
        }
        MlsDriveLoop.noteControlVerdict(shell, key, r);
        if (r == null || !r.ok()) {
            final boolean rolled = preAdvance != null
                    && shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            log.w("MlsEraAdvance: PRESERVING era advance to " + newEra + " (mode="
                    + mode + ") REFUSED → " + r
                    + (rolled ? " (rolled back to era " + curEra + ")"
                              : " (ROLLBACK FAILED — engine may sit at " + newEra + ")"));
            if (!rolled) {
                shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164, newEra,
                        "PRESERVING era advance (rollback reported FAILED)");
            }
            return -1;
        }
        g.era = newEra;
        g.epochAuth = art.tag;
        g.sendsThisEpoch = 0;
        // The advance commit carries an UpdatePath, so our leaf rotates and the usage counter
        // resets.
        g.sendsSinceLeafRotation = 0;
        shell.putGroup(key, g);
        log.i("MlsEraAdvance: PRESERVING era advance to " + newEra + " ACCEPTED for "
                + MlsConversationKey.forLog(key) + " (mode=" + mode
                + ") — membership untouched, no Welcome needed");
        return newEra;
    }

    public static int eraAdvanceMode(final MlsConfig cfg) {
        return cfg.eraAdvanceMode;
    }

    /**
     * Why an era advance did not take, as a log diagnostic naming the two levers
     * ({@code debug.rcs.mls_advance_fresh_ctxid}, {@code debug.rcs.mls_advance_create_fallback}).
     * The automatic callers only log it; unlike {@link MlsFloorRebuild#floorRebuild} they do not
     * decline, because declining would turn "expensive but it recovers" into "stuck". Reads the
     * props live.
     */
    public static String eraAdvanceLeverDiagnostic(final MlsShellPort shell) {
        final String mode =
                shell.sysprops().get("debug.rcs.mls_advance_fresh_ctxid", "off");
        final boolean fallback = shell.sysprops().getBoolean(
                "debug.rcs.mls_advance_create_fallback", true);
        return "debug.rcs.mls_advance_fresh_ctxid=" + mode
                + " debug.rcs.mls_advance_create_fallback=" + fallback
                + (("off".equalsIgnoreCase(mode) || mode == null || mode.isEmpty())
                    ? " — SO THIS ADVANCE RE-USED THE STORED contextId, which is the shape "
                      + "that does not take, on a 1:1 and on a group alike: a freshly minted "
                      + "contextId is GRANTED and the stored one is NOT APPLIED, whatever the ordinal "
                      + "position, era value or group identity. A fresh contextId is the one "
                      + "variable that flips it: setprop debug.rcs.mls_advance_fresh_ctxid "
                      + "1to1 (or 'all' for a group). NOTE the server says nothing either way — the "
                      + "create RPC returns gRPC OK and its response has no verdict field to refuse in, so "
                      + "the era re-read above is the ONLY signal there is."
                    : " — a fresh contextId WAS requested for a re-create, so the known cause "
                      + "does not apply here and this refusal is something else. Check the create "
                      + "log for 'contextId MINTED FRESH'; if it is absent the lever did not fire "
                      + "(it only fires on a RE-CREATE, i.e. when a provider record exists under "
                      + "this MLS group id, and for a group it needs mode 'all').");
    }

    public static void noteEraAsked(final MlsShellPort shell, final String key, final int era) {
        if (key == null || era <= 0) return;
        synchronized (shell.eraAsked()) {
            final Integer prev = shell.eraAsked().get(key);
            if (prev == null || era > prev) shell.eraAsked().put(key, era);
        }
    }

    /** The server named the era-advancement quota outright. */
    public static void noteEraQuotaReported(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return;
        synchronized (shell.eraQuotaReported()) { shell.eraQuotaReported().add(key); }
        log.w("MlsEraAdvance: the SERVER named the era-advancement quota for "
                + MlsConversationKey.forLog(key)
                + ". That is the explicit form of what is usually a silent no-move, and it "
                + "is the predicate some peers use for END_MLS_REQUESTED.");
    }

    /**
     * The advance could not look: our own {@link MlsFetchLedger} refused a read it needs. Distinct
     * from a server refusal (-1), and still negative so every {@code < 0} check reads "did not
     * advance".
     */
    public static final int ERA_ADVANCE_LOOK_UNAVAILABLE = -2;

    public static final int ERA_MODE_CREATE = 0;  // rebuild from KeyPackages (create RPC)

    public static int eraAdvanceLocked(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final byte[] carryGroupInfo, final MlsAdvanceEraKind kind,
            final boolean requireRebuildableRoster) {
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsEraAdvance: era advance — no group for " + LogMask.number(peerE164));
            return -1;
        }
        // A downgraded group may be advanced: the kind decides what happens to 0xF002 (normal
        // carries it, revival drops it, phoenix installs it), so a plain advance cannot clear a
        // downgrade.
        if (kind.carriesEndMls() && MlsRecordState.hasEndMlsStatus(shell, log, key)) {
            log.i("MlsEraAdvance: era advance on the DOWNGRADED group "
                    + MlsConversationKey.forLog(key)
                    + " at mode NORMAL — 0xF002 is carried forward, so the conversation stays "
                    + "plaintext across the new era. (A revival or a phoenix would pass a "
                    + "different mode; recovery deliberately does not.)");
        }
        // Advance past the higher of our era and the server's: if a peer advanced first, local+1
        // targets an era the server already holds and is refused.
        final int curEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        final Look<long[]> base =
                shell.lookServerEraEpoch(MlsFetchLedger.Caller.ERA_ADVANCE, key, peerE164,
                        rcsGroupId);
        if (base.refused()) {
            // Refuse rather than guess local+1: every member pays a Welcome for the guess.
            log.w("MlsEraAdvance: era advance for " + MlsConversationKey.forLog(key)
                    + " DECLINED — the fetch "
                    + "ledger refused the look that establishes the server's era. We will not "
                    + "advance to a guessed era: local+1 targets an era the server may already hold, "
                    + "and every member pays a Welcome for it. Nothing was asked; re-driven from a "
                    + "fresh trigger.");
            return ERA_ADVANCE_LOOK_UNAVAILABLE;
        }
        final long[] srv = base.orNull();
        final long serverEra = (srv != null && srv.length > 0 && srv[0] > 0) ? srv[0] : 0L;
        final long baseEra =
                Math.max(curEra > 0 ? curEra : MlsTransportTypes.ERA_INITIAL, serverEra);
        if (serverEra > curEra) {
            log.i("MlsEraAdvance: the server is AHEAD on " + MlsConversationKey.forLog(key)
                    + " (local era="
                    + curEra + " server era=" + serverEra + ") — advancing past the SERVER's era, "
                    + "not ours; local+1 would target an era it already holds");
        }
        final long nextEra = MlsAppMessage.nextEra(baseEra);
        if (nextEra < 0) {
            log.e("MlsEraAdvance: " + MlsConversationKey.forLog(key) + " is at era " + curEra
                    + " — the u32 "
                    + "ceiling; advancing would wrap to 0 and read as a move BACKWARDS. Refusing.");
            return -1;
        }
        // Rebound below to the era the engine actually built; until then, the host's expectation.
        int newEra = (int) nextEra;
        // Record the ask: a silently refused advance (RPC OK, era unmoved) is only observable as
        // "asked for N, server still below N", which eraQuotaBound needs.
        MlsEraAdvance.noteEraAsked(shell, key, newEra);
        final int mode = MlsEraAdvance.eraAdvanceMode(cfg);
        if (mode != ERA_MODE_CREATE) {
            final int r = MlsEraAdvance.eraAdvancePreserving(shell, log, key, g, rcsGroupId,
                    peerE164, curEra, newEra, mode);
            // Fall through to the create only when the preserving shape could not be built; a
            // server refusal is a real answer.
            if (r != MlsEraAdvance.BUILD_FAILED) return r;
            log.w("MlsEraAdvance: preserving era advance could not be BUILT for "
                    + MlsConversationKey.forLog(key) + " — falling back to the legacy create path");
        }
        final MlsProviderRpc pt = shell.rpc("eraAdvanceCreate");
        // The new era must match the server's roster, which is matched on participant MSISDNs, so a
        // group is rebuilt around the server's member set with freshly claimed KeyPackages.
        final java.util.List<String> members = new java.util.ArrayList<>();
        // One fetch serves both: the pack's GroupInfo (slot 0) and roster (slot 2).
        final Look<byte[]> packLook = (rcsGroupId == null || rcsGroupId.isEmpty())
                ? null : shell.fetchServerPack(MlsFetchLedger.Caller.ERA_ADVANCE, rcsGroupId,
                        peerE164, g);
        if (packLook != null && packLook.refused()) {
            log.e("MlsEraAdvance: era advance for GROUP " + rcsGroupId + " DECLINED "
                    + "— the fetch ledger refused the roster look. The roster is what the advance "
                    + "must match on (self-heal matches on the roster), and building one "
                    + "member's worth of group from a guess is the mismatch this refuses to create.");
            return ERA_ADVANCE_LOOK_UNAVAILABLE;
        }
        final byte[] serverPack = (packLook == null) ? null : packLook.orNull();
        final java.util.List<String> serverRoster =
                MlsServerBundle.rosterFromPack(shell, serverPack);
        if (serverRoster != null && !serverRoster.isEmpty()) {
            members.addAll(serverRoster);
        } else {
            if (rcsGroupId != null && !rcsGroupId.isEmpty()) {
                log.e("MlsEraAdvance: era advance for GROUP " + rcsGroupId
                        + " — could not fetch the server roster, and the roster is exactly what the "
                        + "server matches. Refusing rather than advancing with a guessed member set.");
                return -1;
            }
            members.add(peerE164);   // 1:1: the peer is the roster
        }
        // Record the roster the new era is built around, so the maintenance delta sees membership.
        MlsRecordState.recordMembership(shell, log, key, g, members);
        final RosterClaim claim = MlsFloorRebuild.claimRosterForAdvance(cfg, shell, log, key,
                members, requireRebuildableRoster);
        if (claim.kps == null) return claim.failure;
        final java.util.List<byte[]> kps = claim.kps;
        log.i("MlsEraAdvance: era advance to " + newEra + " for " + MlsConversationKey.forLog(key)
                + " with the SERVER's roster (" + kps.size() + " member(s): "
                + LogMask.numbers(members) + ")");
        // Reuse the group id: the server keys the conversation on it. Snapshot first, because the
        // create replaces our engine state before the server agrees.
        final byte[] preAdvance = shell.session().exportGroupSnapshot(g.groupId);
        final boolean endMlsBefore = shell.session().endMlsPresent(g.groupId);
        // Carry the server's GroupInfo so the new era inherits 0xF003-0xF006: the server refuses an
        // advance that drops committed metadata, and a recovering member's own copy is stale.
        byte[] carry = carryGroupInfo;
        if (carry == null || carry.length == 0) {
            final byte[] serverGi = (serverPack == null) ? null
                    : MlsWireScan.firstPacked(serverPack, 0);
            if (serverGi != null && serverGi.length > 0) {
                carry = serverGi;
                log.i("MlsEraAdvance: era advance inheriting RCC.16 metadata from "
                        + "the SERVER's GroupInfo (" + serverGi.length + "B)");
            }
        }
        // Key packages iff the purpose is an era advance; thrown, not returned, since the
        // finally-release and rollback make throwing safe here.
        MlsAdvancePurpose.ERA_ADVANCEMENT.assertKeyPackages(kps == null ? 0 : kps.size());
        // Reject an incomplete server state before the engine sees it, so a missing tree is
        // reported here rather than as a codec error, or as RatchetTreeNotFound on the peer.
        if (serverPack != null) {
            // Our own authenticator binds the commit's base; the pack does not separate the latest
            // from the paginated identifier, so it is passed for both.
            final byte[] baseAuth = shell.session().epochAuth(g.groupId);
            final MlsGroupInfoGate.Verdict v = MlsGroupInfoGate.check(
                    MlsWireScan.firstPacked(serverPack, 0), MlsWireScan.firstPacked(serverPack, 1),
                    baseAuth, baseAuth);
            if (!v.ok()) {
                log.w("MlsEraAdvance: the server state backing this era advance is "
                        + "incomplete (" + v + ") for " + MlsConversationKey.forLog(key)
                        + ". Proceeding, because our own "
                        + "GroupInfo carry may still cover it — but if the engine now fails, THIS is "
                        + "the reason, not a codec fault.");
            }
        }
        final MlsGroupArtifacts art =
                shell.session().createGroupPlanned(kps, g.groupId, carry, kind);
        if (art == null || art.groupId == null || art.welcome == null) {
            log.e("MlsEraAdvance: era advance — the engine produced nothing"
                    + (carry == null ? "" : " (carry-over GroupInfo " + carry.length + "B)"));
            if (preAdvance != null) shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            return -1;
        }
        if (art.welcomeAction == null || art.era < MlsTransportTypes.ERA_INITIAL) {
            log.e("MlsEraAdvance: era advance — the engine reported no plan for "
                    + MlsConversationKey.forLog(key) + " (era=" + art.era + " action="
                    + art.welcomeAction + ")");
            if (preAdvance != null) shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            return -1;
        }
        // The kind decides 0xF002 in the new era; a group built otherwise must not reach the
        // server, since a revival that kept it or a phoenix that lacks it cannot be undone there.
        final boolean endMlsAfter = shell.session().endMlsPresent(art.groupId);
        if (endMlsAfter != kind.endMlsAfter(endMlsBefore)) {
            log.e("MlsEraAdvance: era advance for " + MlsConversationKey.forLog(key) + " at " + kind
                    + " built a group with "
                    + "end_mls " + (endMlsAfter ? "PRESENT" : "ABSENT") + " (before: "
                    + (endMlsBefore ? "present" : "absent") + "), which that kind does not produce "
                    + "— the engine built some other kind. Rolling back; nothing was sent.");
            if (preAdvance != null) shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            return -1;
        }
        // The engine's era wins; a disagreement with the host's expectation is logged.
        if (art.era != newEra) {
            log.w("MlsEraAdvance: era advance for " + MlsConversationKey.forLog(key)
                    + " — the host would have "
                    + "targeted era " + newEra + " but the engine built era " + art.era
                    + " from its own state and the server's GroupInfo. Going with the ENGINE's: it "
                    + "is the layer that writes 0xF001, and only what it wrote reaches the server.");
        }
        newEra = (int) art.era;
        final MlsProviderRpc.ControlResult r = pt.createMlsConversation(peerE164, art.groupId,
                art.welcome, art.commit, art.groupInfo, art.tag, art.ratchetTree, newEra, key,
                rcsGroupId);
        MlsDriveLoop.noteControlVerdict(shell, key, r);
        if (r == null || !r.ok()) {
            final boolean rolled = preAdvance != null
                    && shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            log.w("MlsEraAdvance: era advance to " + newEra + " REFUSED → " + r
                    + (rolled ? " (rolled back to era " + curEra + ")"
                              : " (ROLLBACK FAILED — engine may sit at " + newEra + ")"));
            if (!rolled) {
                shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164, newEra,
                        "era advance (rollback reported FAILED)");
            }
            return -1;
        }
        // An OK from the create RPC is not proof the era advanced: the server assigns it, so read
        // it back.
        final Look<long[]> verify =
                shell.lookServerEraEpoch(MlsFetchLedger.Caller.ERA_ADVANCE, key, peerE164,
                        rcsGroupId);
        if (verify.refused()) {
            // Neither success nor rollback: rolling back an advance that may have taken would
            // manufacture the divergence this check exists to prevent.
            log.w("MlsEraAdvance: era advance to " + newEra + " for "
                    + MlsConversationKey.forLog(key)
                    + " CANNOT BE VERIFIED — the fetch ledger refused the look. NOT rolling back "
                    + "and NOT claiming success: the engine holds era " + newEra + " and whether "
                    + "the server agrees is unknown. Reporting failure so nothing is built on an "
                    + "unverified advance; the next trigger re-reads the server.");
            return ERA_ADVANCE_LOOK_UNAVAILABLE;
        }
        long[] after = verify.orNull();
        if (after == null || after[0] != newEra) {
            // Accepted but not applied: one re-dial with a fresh context id before anything
            // destructive. See MlsFreshContextRetry.
            final Look<long[]> retried = MlsFreshContextRetry.freshContextIdRetry(shell, log, pt,
                    key, rcsGroupId, peerE164, art,
                    newEra, r, /*eraWasReadBack=*/ !verify.refused(), after);
            if (retried != null && retried.refused()) {
                // The re-dial was accepted and cannot be read: report unverified, roll nothing
                // back.
                log.w("MlsEraAdvance: the fresh-contextId re-dial for "
                        + MlsConversationKey.forLog(key)
                        + " was accepted and its result CANNOT BE READ — the fetch ledger refused "
                        + "the look (" + retried.why() + "). NOT rolling back and NOT rebuilding: "
                        + "the server may hold era " + newEra
                        + " now. The next trigger re-reads it.");
                return ERA_ADVANCE_LOOK_UNAVAILABLE;
            }
            if (retried != null && retried.orNull() != null) after = retried.orNull();
        }
        if (after == null || after[0] != newEra) {
            final boolean rolled = preAdvance != null
                    && shell.session().restoreGroupSnapshot(g.groupId, preAdvance);
            log.e("MlsEraAdvance: era advance to " + newEra + " did NOT take — the "
                    + "RPC returned OK but the server reports era="
                    + (after == null ? "?" : after[0]) + ". Refusing to report success"
                    + (rolled ? " (rolled back to era " + curEra + ")"
                              : " (ROLLBACK FAILED — engine may sit at " + newEra + ")"));
            log.w("MlsEraAdvance: " + MlsConversationKey.forLog(key) + " — era advance to " + newEra
                    + " not applied: " + MlsEraAdvance.eraAdvanceLeverDiagnostic(shell));
            // The silent-refusal shape: RPC OK, era unmoved, rollback failed.
            if (!rolled) {
                shell.quarantineIfAheadOfServer(g, rcsGroupId, peerE164, newEra,
                        "era advance (rollback reported FAILED)");
            }
            // Fallback (on by default; debug.rcs.mls_advance_create_fallback=0 disables it): drop
            // the local group and re-establish, which lands at the server's era + 1 where the
            // advance could not. Its cost to peers is the one an era advance already imposes, a
            // re-join by Welcome on the same group id.
            if (rolled && shell.sysprops().getBoolean(
                    "debug.rcs.mls_advance_create_fallback", true)) {
                log.i("MlsEraAdvance: FALLBACK — the advance to " + newEra
                        + " was silently refused; rebuilding so the next establish takes the CREATE "
                        + "path, which reached this exact era on this exact group when the advance "
                        + "could not.");
                // The create above was accepted, so this episode's era-budget charge is consumed
                // and the rebuild is a second peer-facing re-creation. Read from r rather than
                // asserted.
                final MlsRecreationEpisode.PriorCharge prior = MlsRecreationEpisode.priorCharge(
                        /*spent=*/ true, r != null && r.ok());
                log.i("MlsEraAdvance: " + MlsConversationKey.forLog(key) + " — "
                        + MlsRecreationEpisode
                        .line(prior));
                final MlsRebuildOutcome outcome = MlsConversationRebuild.rebuildConversation(shell,
                        log, rcsGroupId, peerE164,
                        "the era advance to " + newEra + " was silently refused", prior);
                final boolean ok = outcome.repaired();
                final Look<long[]> postLook = shell.lookServerEraEpoch(
                        MlsFetchLedger.Caller.ERA_ADVANCE, key, peerE164, rcsGroupId);
                // An unreadable result reports "not advanced", the safe direction, and logs that
                // nothing was read.
                if (postLook.refused()) {
                    log.w("MlsEraAdvance: the rebuild FALLBACK for "
                            + MlsConversationKey.forLog(key) + " ran and "
                            + "its result CANNOT BE READ — the fetch ledger refused the look. "
                            + "Reporting it as not-advanced, which is the safe direction, but "
                            + "nothing here says the rebuild failed.");
                }
                final long[] post = postLook.orNull();
                final long reached = (post == null || post.length == 0) ? -1L : post[0];
                log.i("MlsEraAdvance: FALLBACK result rebuilt=" + outcome
                        + " (" + outcome.line() + ") server era now=" + reached
                        + (reached > curEra ? " — ADVANCED where the advance path could not"
                                : " — still stuck; the create path does not rescue this one"));
                if (ok && reached > curEra) return (int) reached;
            }
            return -1;
        }
        g.groupId = art.groupId;
        g.era = newEra;
        g.epochAuth = art.tag;
        g.sendsThisEpoch = 0;
        // A new group gives us a new leaf, so the usage counter resets.
        g.sendsSinceLeafRotation = 0;
        shell.putGroup(key, g);
        // Record membership again at the new era: the era is part of the key.
        MlsRecordState.recordMembership(shell, log, key, g, members);
        log.i("MlsEraAdvance: ERA ADVANCE " + curEra + " → " + newEra
                + " (group id reused, peer must re-join via the Welcome; server CONFIRMED era "
                + after[0] + ")");
        return newEra;
    }

    /**
     * Is this conversation bound by the era-advancement quota? Either the server said so, or we
     * asked for era N and it is still below N (the usual, silent, form). Never "the self-heal
     * budget is spent": that would downgrade conversations whose fault is elsewhere. Unprovable
     * reads as false.
     */
    public static boolean eraQuotaBound(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String rcsGroupId, final String peerE164) {
        synchronized (shell.eraQuotaReported()) {
            if (shell.eraQuotaReported().contains(key)) return true;
        }
        final Integer asked;
        synchronized (shell.eraAsked()) { asked = shell.eraAsked().get(key); }
        if (asked == null) return false;
        final Look<long[]> quotaLook =
                shell.lookServerEraEpoch(MlsFetchLedger.Caller.ERA_QUOTA_CHECK, key, peerE164,
                        rcsGroupId);
        if (quotaLook.refused()) {
            // A refused look leaves the predicate unproven, and the log says which signal is
            // missing.
            log.i("MlsEraAdvance: the era-quota predicate for " + MlsConversationKey.forLog(key)
                    + " is "
                    + "UNPROVEN — the fetch ledger refused the server-era look, so no reading was "
                    + "taken. Staying report-only; a conversation is not dropped out of encryption "
                    + "on a signal we did not ask for.");
            return false;
        }
        final long[] srv = quotaLook.orNull();
        final long serverEra = (srv != null && srv.length > 0 && srv[0] > 0) ? srv[0] : 0L;
        if (serverEra <= 0L) {
            log.i("MlsEraAdvance: cannot read the server era for " + MlsConversationKey.forLog(key)
                    + ", so the "
                    + "era-quota predicate is UNPROVEN — not downgrading on a reading we do not have.");
            return false;
        }
        final boolean bound = serverEra < asked;
        log.i("MlsEraAdvance: era-quota predicate for " + MlsConversationKey.forLog(key)
                + " — we asked for era "
                + asked + ", the server reports era " + serverEra + " → "
                + (bound ? "QUOTA-BOUND (the advance was accepted and never applied)"
                         : "not quota-bound (the server took our era; the fault is elsewhere)"));
        return bound;
    }

    /** An era advance with no carry GroupInfo, at an explicit {@link MlsAdvanceEraKind}. */
    public static int eraAdvanceWithKind(final MlsShellPort shell, final String rcsGroupId,
            final String peerE164, final MlsAdvanceEraKind kind) {
        return shell.eraAdvance(rcsGroupId, peerE164, /*carryGroupInfo=*/ null, kind,
                /*requireRebuildableRoster=*/ false);
    }

    /**
     * The era-advance funnel: mode check, era budget, and the one-advance-at-a-time slot, all
     * charged here so no caller reaches {@code eraAdvanceLocked} without them. {@code
     * requireRebuildableRoster} refuses claimed KeyPackages whose certificate is inside the RCC.16
     * floor; only a floor rebuild sets it, since a recovery around an ageing certificate still
     * carries messages.
     */
    public static int eraAdvance(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final byte[] carryGroupInfo, final MlsAdvanceEraKind kind,
            final boolean requireRebuildableRoster) {
        if (!shell.ensureSession()) return -1;
        // The era budget. Charged above the mode branch, which is exact: both preserving modes fall
        // through to a re-creation inside this call. An undeclared mode is refused, not charged.
        if (!MlsEraAdvanceCharge.chargeableAtTheFunnel(MlsEraAdvance.eraAdvanceMode(cfg))) {
            log.e("MlsEraAdvance: era advance REFUSED for rcsGroupId=" + rcsGroupId
                    + " peer=" + LogMask.number(peerE164) + " — "
                    + MlsEraAdvanceCharge.line(MlsEraAdvance.eraAdvanceMode(cfg)));
            return -1;
        }
        if (!shell.peerGuard().allowEraAdvance(rcsGroupId, peerE164)) return -1;
        final String guardKey = MlsGroupState.resolveInbound(shell, log, rcsGroupId, peerE164);
        // A null key must not take the slot: it would be shared by every unresolvable conversation.
        if (guardKey == null) {
            log.w("MlsEraAdvance: era advance refused — no group resolves for "
                    + "rcsGroupId=" + rcsGroupId + " peer=" + LogMask.number(peerE164)
                    + ". NOT taking the pending "
                    + "slot: a null key is shared by every conversation that fails to resolve, so "
                    + "claiming it would block unrelated advances and report the collision as "
                    + "'already pending for null'.");
            return -1;
        }
        if (!MlsPendingOperation.claimAdvance(shell, log, guardKey,
                (kind == null ? MlsAdvanceEraKind.NORMAL : kind) + "/" + peerE164)) {
            log.w("MlsEraAdvance: era advancement already pending for "
                    + MlsConversationKey.forLog(guardKey)
                    + " — not issuing a second one. Two advances mint two eras and fork the group.");
            return -1;
        }
        try {
            final int era = MlsEraAdvance.eraAdvanceLocked(cfg, shell, log, rcsGroupId, peerE164,
                    carryGroupInfo,
                    kind == null ? MlsAdvanceEraKind.NORMAL : kind, requireRebuildableRoster);
            // What was parked in the era we just left can never decrypt, and our own advance
            // drains nothing, so drop it here and report it.
            if (era > 0) MlsInboundHold.pruneSupersededEras(shell, log, guardKey, rcsGroupId);
            return era;
        } finally {
            MlsPendingOperation.releaseAdvance(shell, log, guardKey);
        }
    }
}
