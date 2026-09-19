/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * Creates a group conversation's MLS group: claims every member's KeyPackages, builds the group,
 * creates it at the provider, verifies it against the server and only then adopts it. When the
 * engine plans an add to an existing group, the members are added instead. See
 * docs/mls/group-lifecycle.md.
 */
public final class MlsGroupEstablish {
    private MlsGroupEstablish() {}

    /**
     * Establishes the group and returns its era, or -1. {@code carryGroupInfo} is the server's
     * GroupInfo when rebuilding a conversation the server still holds, so the engine derives
     * {@code server_era + 1} and inherits the group metadata extensions; null otherwise.
     *
     * @param preClaimed KeyPackages already claimed by the upgrade path, or null to claim here;
     *     consumed once, since a KeyPackage is single-use (RFC 9420 §10)
     */
    public static int establishGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final List<String> members,
            final byte[] carryGroupInfo, final MlsUpgradeClaim preClaimed) {
        if (rcsGroupId == null || rcsGroupId.isEmpty() || members == null || members.isEmpty()) {
            log.w("MlsGroupEstablish: establishGroup needs a group id and ≥1 member");
            return -1;
        }
        // A create Welcomes every member, so it is gated like an add; the whole create is refused
        // rather than a member dropped.
        if (!MlsMembership.allowedToJoinAll(shell, log, "establishGroup", rcsGroupId,
                members)) return -1;
        if (!shell.ensureSession()) return -1;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, members.get(0));
        if (key == null) return -1;
        // Never create over local state: a create builds the extension list from scratch and would
        // drop end_mls from a downgraded group.
        if (shell.getGroup(key) != null) {
            log.i("Group already exists, not creating new group: " + rcsGroupId);
            if (MlsRecordState.hasEndMlsStatus(shell, log, key)) {
                log.w("MlsGroupEstablish: ...and it is DOWNGRADED. Creating over it "
                        + "would rebuild its GroupContext from scratch, dropping end_mls and "
                        + "re-encrypting a conversation someone deliberately left. Reviving is a "
                        + "separate, explicit action (INVARIANT ED-1).");
            }
            return (int) shell.getGroup(key).era;
        }
        final MlsProviderRpc pt = shell.rpc("createGroupEra");

        // No era is chosen here: the engine derives it and reports it; the server era is read only
        // afterwards, as verification.

        final String first = members.get(0);
        final java.util.List<byte[]> kps =
                MlsKeyPackageClaims.gatherInitialKeyPackages(shell, log, members, preClaimed);
        if (kps == null) return -1;

        // The MLS group id is the RCS group id; a fresh id is a group the server has no RCS state
        // for. Snapshot before the engine mutates, so a create the server does not take is undone
        // and the engine never derives a later era from state the server refused.
        final byte[] gidBytes = rcsGroupId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] preCreate = shell.session().exportGroupSnapshot(gidBytes);
        // The add arm sends the previous epoch's authenticator, so read it before the commit.
        final byte[] preEpochAuth = shell.session().epochAuth(gidBytes);
        if (preCreate != null) {
            log.i("MlsGroupEstablish: establishGroup " + rcsGroupId + " — the engine "
                    + "ALREADY holds state at this group id (" + preCreate.length + "B snapshot "
                    + "taken). If the server does not take this create, that state is restored "
                    + "rather than left one era further ahead.");
        }
        if (carryGroupInfo != null && carryGroupInfo.length > 0) {
            log.i("MlsGroupEstablish: establishGroup " + rcsGroupId + " is carrying the "
                    + "SERVER's GroupInfo (" + carryGroupInfo.length + "B), so the engine derives "
                    + "the era from the server's 0xF001 rather than being born at "
                    + MlsTransportTypes.ERA_INITIAL
                    + " — this is a REBUILD of a conversation the server still holds, not a new "
                    + "group.");
        }
        final MlsGroupArtifacts art = shell.session().createGroupPlanned(kps, gidBytes,
                carryGroupInfo, MlsAdvanceEraKind.NORMAL);
        if (art == null || art.groupId == null || art.commit == null) {
            log.e("MlsGroupEstablish: establishGroup — the engine produced nothing for "
                    + kps.size() + " member(s)");
            return -1;
        }
        final long targetEra = art.era;
        final MlsWelcomeAction action = art.welcomeAction;
        if (action == null || targetEra < MlsTransportTypes.ERA_INITIAL) {
            // Refuse rather than guess that an unrecognised action is a create.
            log.e("MlsGroupEstablish: establishGroup — the engine reported no plan for "
                    + rcsGroupId + " (era=" + targetEra + " action=" + action
                    + "). Refusing rather "
                    + "than assuming a create.");
            return -1;
        }
        // Route on the engine's decision: an add commit belongs on the add request, naming the
        // members the engine admitted.
        if (action.routesToAddMembers()) {
            return MlsMembership.addMembersToExistingGroup(shell, log, rcsGroupId, key, members,
                    art, preEpochAuth, preCreate);
        }
        // requireJoinable throws; this method's contract is the era or -1, so convert it.
        try {
            MlsWelcomeAction.requireJoinable(action);
        } catch (final IllegalStateException notJoinable) {
            log.e("MlsGroupEstablish: establishGroup — " + notJoinable.getMessage()
                    + " Refusing " + rcsGroupId + " rather than treating an action this build "
                    + "cannot name as a create.");
            MlsUpgradePolicy.rollBackDiscardedCreate(shell.session(), log, gidBytes, preCreate);
            return -1;
        }
        // Cross-check the era the engine reported against the 0xF001 it wrote.
        final byte[] eraExt =
                shell.session().groupInfoExt(art.groupInfo, MlsTransportDiagnostics.ERA_EXT_TYPE);
        final String eraSays = MlsAdvancerElection.eraExtOf(eraExt);
        final boolean eraAgrees = eraExt != null && eraExt.length == 4
                && (((long) (eraExt[0] & 0xFF) << 24) | ((eraExt[1] & 0xFF) << 16)
                        | ((eraExt[2] & 0xFF) << 8) | (eraExt[3] & 0xFF)) == targetEra;
        log.i("MlsGroupEstablish: establishGroup " + rcsGroupId + " — the engine chose "
                + action + " at era=" + targetEra + ", the GroupInfo's 0xF001 says " + eraSays
                + (eraAgrees ? " (CONSISTENT)"
                             : " ⚠ DISAGREES with the era the engine reported — the engine's report "
                               + "and its own GroupInfo do not match, which is an engine bug"));
        if (art.welcome == null || art.welcome.length == 0) {
            log.e("MlsGroupEstablish: establishGroup — " + action + " produced no "
                    + "Welcome; nobody could join the group this creates. Refusing.");
            return -1;
        }
        final MlsProviderRpc.ControlResult r = pt.createMlsConversation(first, art.groupId,
                art.welcome, art.commit, art.groupInfo, art.tag, art.ratchetTree,
                (int) targetEra, /*contextId=*/ rcsGroupId, rcsGroupId);
        MlsDriveLoop.noteControlVerdict(shell, key, r);
        if (r == null || !r.ok()) {
            final boolean rolled = MlsUpgradePolicy.rollBackDiscardedCreate(shell.session(), log,
                    gidBytes, preCreate);
            log.w("MlsGroupEstablish: establishGroup REFUSED → " + r
                    + (rolled ? " (engine state rolled back)"
                              : " ⚠ (ENGINE ROLLBACK FAILED — it may hold an era the server never "
                                + "accepted, which the next create would derive from)"));
            return -1;
        }
        // An accepted create is not proof the server applied it. A failed read is not a refusal:
        // only a contrary reading undoes the create.
        final Look<long[]> confirm = shell.lookServerEraEpoch(MlsFetchLedger.Caller.GROUP_ESTABLISH,
                MlsConversationKey.canonicalKey(rcsGroupId, first), first, rcsGroupId);
        if (confirm.refused()) {
            // Fall through; the authenticator check below still governs success.
            log.w("MlsGroupEstablish: the post-create era confirmation for " + rcsGroupId
                    + " was refused by the fetch ledger — no reading was taken, so nothing is undone "
                    + "on its account. The epoch-authenticator check below still governs whether "
                    + "this establish is reported as a success.");
        }
        final long[] after = confirm.orNull();
        if (after != null && after[0] >= 0 && after[0] != targetEra) {
            // Accepted and discarded: roll the engine back too, or it sits an era ahead of the
            // server.
            final boolean rolled = MlsUpgradePolicy.rollBackDiscardedCreate(shell.session(), log,
                    gidBytes, preCreate);
            log.e("MlsGroupEstablish: establishGroup for " + rcsGroupId + " reported OK "
                    + "for era " + targetEra + " but the server still holds era " + after[0]
                    + " — the create was ACCEPTED AND DISCARDED. Not adopting it: doing so would "
                    + "leave the engine ahead of the server, where every inbound message parks at a "
                    + "moment we can never reach. (Reproducible on a group.)"
                    + (rolled ? " Engine state rolled back to what the server last accepted."
                            : ""));
            // A server-side INCORRECT_ERA on a create means the group already exists. With no local
            // state we can neither create nor advance, so ask a current member to re-Welcome us
            // (RCC.16 §7.7.2.2).
            MlsRecoveryPolicy.requestReWelcome(shell, log, rcsGroupId, first,
                    "the server already holds this group and we hold no "
                    + "state for it, so we can neither create it nor advance it");
            return -1;
        }
        // Equal era is not equal state: only the epoch authenticator says we are in the server's
        // group. Adoption waits for a match; see docs/mls/group-lifecycle.md.
        final MlsWelcomeAdmission.ServerState createState = MlsWelcomeAdmission.serverStateCheckFor(
                shell, MlsFetchLedger.Caller.GROUP_ESTABLISH, rcsGroupId, first, art.groupId);
        if (createState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            // Refused like UNKNOWN, but the cause is our own ledger, not a server answer.
            log.e("MlsGroupEstablish: " + rcsGroupId + " — the create returned OK and we "
                    + "DID NOT ASK the server for its view: our own fetch ledger refused the look. "
                    + "Refusing to report success on an unverified create for the same reason the "
                    + "unreadable case refuses it — a false establish is worse than a clean failure "
                    + "because a caller cannot tell it from a real one. There is no "
                    + "server status to interpret here; the remedy is to retry once the ledger's "
                    + "window has passed. NOTHING IS ADOPTED.");
            return -1;
        }
        if (createState == MlsWelcomeAdmission.ServerState.UNKNOWN) {
            // Unverified: do not adopt. PERMISSION_DENIED means the conversation is not ours,
            // NOT_FOUND that it does not exist.
            log.e("MlsGroupEstablish: " + rcsGroupId + " — the create returned OK but we "
                    + "CANNOT READ the server's view of the group. Refusing to report success: "
                    + "whether we are in ITS group or one beside it is unverified, and a false "
                    + "success here is indistinguishable from a real establish until the first send "
                    + "fails. Check the GetMlsGroupInfo status in the provider log — PERMISSION_DENIED "
                    + "means the conversation is not ours, NOT_FOUND means it does not exist "
                    + "NOTHING IS ADOPTED.");
            return -1;
        }
        if (createState == MlsWelcomeAdmission.ServerState.DIFFERS) {
            return MlsRecordState.healOntoServerGroupOrUnadopt(shell, log, rcsGroupId, first, key,
                    art.groupId, targetEra);
        }
        // Adoption is the first irreversible step.
        MlsRecordState.adoptGroup(shell, log, rcsGroupId, first, art.groupId);
        // Record health and the roster the initial commit was built around, which the maintenance
        // delta gate reads.
        shell.moveHealth(key, MlsHealthStates.HEALTHY,
                "group established with " + kps.size() + " member(s)");
        final Group established = shell.getGroup(key);
        if (established != null) MlsRecordState.recordMembership(shell, log, key, established,
                members);
        log.i("MlsGroupEstablish: ESTABLISHED MLS group " + rcsGroupId + " at era="
                + targetEra + " with " + kps.size() + " member(s): " + LogMask.numbers(members)
                + (after == null ? " (server era unreadable — not verified)"
                                 : " — server CONFIRMED era " + after[0]));

        // Every member was in the initial commit, so one Welcome admits all of them.
        return (int) targetEra;
    }

    /** Establishes the group, claiming the members' KeyPackages here. */
    public static int establishGroup(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final List<String> members, final byte[] carryGroupInfo) {
        return MlsGroupEstablish.establishGroup(shell, log, rcsGroupId, members, carryGroupInfo,
                /*preClaimed=*/ null);
    }
}
