/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsUpgradeClaim;
import com.android.messaging.rcs.engine.mls.MlsUpgradePolicy;
import com.android.messaging.util.LogUtil;

/**
 * Upgrades a group conversation to MLS when it is opened, and runs the maintenance pass on one
 * that already is. A group is created unencrypted, so opening it is the upgrade
 * trigger; creation and sending are not. Background work, idempotent through its guards, and every
 * guard that stops it is logged.
 */
public final class MlsConversationOpenListener {
    private static final String TAG = MlsLog.TAG;

    private MlsConversationOpenListener() {}

    /**
     * Called when a conversation is opened. Returns at once and works off-thread (it claims
     * KeyPackages over the network). Safe on every open: the guards are the throttle, which is
     * state-based rather than a timer.
     */
    public static void onConversationOpened(final Context ctx, final int subId,
            final String conversationId) {
        if (ctx == null || conversationId == null || conversationId.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    upgrade(app, subId, conversationId);
                } catch (final Throwable t) {
                    // A failed upgrade must never take the UI down.
                    LogUtil.w(TAG, "MlsConversationOpenListener: upgrade threw for "
                            + MlsConversationKey.forLog(conversationId), t);
                }
            }
        }, "mls-conv-open").start();
    }

    private static void upgrade(final Context ctx, final int subId, final String conversationId) {
        final MlsProviderTransport t = MlsProviderTransport.get(ctx, subId);

        String rcsGroupId = null;
        final java.util.List<String> participants = new java.util.ArrayList<>();
        boolean metadataAvailable = false;
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            rcsGroupId = BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
            for (final ParticipantData p
                    : BugleDatabaseOperations.getParticipantsForConversation(db, conversationId)) {
                if (p.isSelf()) continue;
                final String d = p.getNormalizedDestination();
                if (d != null && !d.isEmpty() && !participants.contains(d)) participants.add(d);
            }
            metadataAvailable = true;
        } catch (final Throwable e) {
            LogUtil.w(TAG, "MlsConversationOpenListener: could not read conversation metadata for "
                    + MlsConversationKey.forLog(conversationId), e);
        }

        // A 1:1 is established by the send path, not here.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return;

        final boolean alreadyMls = t.hasMlsGroup(rcsGroupId);
        final boolean online = online(ctx);
        final boolean cheapGuardsPass = online && !alreadyMls && metadataAvailable
                && !participants.isEmpty();
        // A participant with zero registrations has no routing target, so a group built around them
        // admits someone unreachable. "No registrations" is distinct from "could not ask".
        final boolean dummyToken = cheapGuardsPass && t.anyParticipantUnroutable(participants);
        final boolean selfHasKeyPackages = t.isMlsReady();
        // The single-in-flight-operation registry is the "group is initializing" state.
        final boolean initializing = t.groupIsInitializing(rcsGroupId);
        // The joining gate, ahead of the claim: a create Welcomes every member, so one refused
        // participant makes it impossible. establishGroup asks again; the predicate is local and
        // free.
        final boolean peersMayJoin = !cheapGuardsPass || t.upgradeMayWelcome(rcsGroupId, participants);

        // Every free guard first, then the claim. A proceed here means only that the claim is worth
        // making.
        final MlsUpgradePolicy.Decision before = MlsUpgradePolicy.evaluateBeforeClaiming(
                online, dummyToken, alreadyMls, metadataAvailable, selfHasKeyPackages, rcsGroupId,
                initializing, alreadyMls, participants.size(), peersMayJoin);
        // One claim, kept and handed to the create (see MlsUpgradeClaim). Rationed per peer by
        // MlsClaimLedger; a ledger refusal is CLAIM_REFUSED, never read as participants being
        // short.
        final MlsUpgradeClaim claim = before.proceed() ? t.claimForUpgrade(participants) : null;
        final MlsUpgradePolicy.Decision d = (claim == null)
                ? before
                : MlsUpgradePolicy.evaluate(online, dummyToken, alreadyMls, metadataAvailable,
                        selfHasKeyPackages, rcsGroupId, initializing, claim.count(),
                        participants.size(), alreadyMls, peersMayJoin);

        if (!d.proceed()) {
            final String why = (d == MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES)
                    ? MlsUpgradePolicy.notEnoughKeyPackagesLine(
                            claim == null ? 0 : claim.count(), participants.size())
                    : d.line();
            LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " → " + d + " — " + why);
            // Refusing here is acceptable where a user action would not be: the conversation opens
            // either way, the resource is key material on other devices, and the rate is set by a
            // person tapping a list. The retry is the next open after the claim window.
            if (d == MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER) {
                LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " is unchanged and "
                        + "still usable — it simply stays unencrypted until the next open after "
                        + "the claim window. NOTHING was claimed, so this is not a statement about "
                        + "anyone's key packages.");
            }
            // Already MLS is the maintenance case. The pass decides for itself whether anything is
            // due (a membership delta or expired members) and answers NOT_NEEDED otherwise, so
            // running it on every open is affordable.
            if (d == MlsUpgradePolicy.Decision.ALREADY_MLS && t.maintenanceOnOpen()) {
                LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " is already MLS — "
                        + "running the §8.7 maintenance pass, which is what 'already MLS' actually "
                        + "calls for");
                try {
                    LogUtil.i(TAG, "MlsConversationOpenListener: maintenance → "
                            + t.runMaintenance(rcsGroupId, participants.get(0),
                                    "conversation opened"));
                } catch (final Throwable e) {
                    // A maintenance failure must never take the conversation down.
                    LogUtil.w(TAG, "MlsConversationOpenListener: maintenance threw for "
                            + rcsGroupId, e);
                }
            }
            return;
        }

        LogUtil.i(TAG, "MlsConversationOpenListener: upgrading " + rcsGroupId + " to MLS on open ("
                + participants.size() + " participant(s))");
        // establishGroup consumes the packages claimed above rather than claiming again.
        final int era = t.establishGroup(rcsGroupId, participants, /*carryGroupInfo=*/ null, claim);
        LogUtil.i(TAG, "On conversation open, conversationMlsUpdaterImpl returned: "
                + (era > 0 ? "UPGRADED_TO_MLS" : "NOT_UPGRADED") + " (era=" + era + ")");
    }

    /** The cheapest guard, so it runs first. */
    private static boolean online(final Context ctx) {
        try {
            final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return true;   // cannot tell; do not invent a refusal
            final NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (final Throwable t) {
            return true;
        }
    }
}
