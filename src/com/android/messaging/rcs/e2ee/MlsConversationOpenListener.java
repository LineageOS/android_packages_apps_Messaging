/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsUpgradeClaim;
import com.android.messaging.rcs.engine.mls.MlsUpgradePolicy;
import com.android.messaging.util.LogUtil;

/**
 * Upgrade a conversation to MLS when it is OPENED — the same trigger Google Messages uses, where
 * an open-conversation listener drives a conversation MLS updater and an upgrade-to-MLS
 * operation.
 *
 * <h2>Why conversation OPEN, and not create or send</h2>
 *
 * <p>This was the missing lever, and its absence is why a group we created never became MLS no
 * matter what we did to it. Two facts about that client, both structural:
 *
 * <ul>
 *   <li><b>{@code CreateGroup} has no MlsControlMessage field at all</b>, and the MLS interceptor's
 *       CreateGroup verb is a deliberate no-op — <em>a group is created UNENCRYPTED in Google Messages
 *       too</em>. "Create then send" was never going to work.</li>
 *   <li>Its creation listener is hard-gated to a reason of
 *       INCOMING_ENCRYPTED_RCS_MESSAGE, a RECEIVE-side reason. The four self-created reasons are
 *       different constants, so <b>a conversation you create gets nothing from it, by
 *       construction</b>.</li>
 * </ul>
 *
 * <p>We even observed the real lever and did not recognise it: driving Google Messages' UI logged
 * <i>"On conversation open, conversationMlsUpdaterImpl returned: UPGRADED_TO_MLS"</i>.
 *
 * <h2>Background, and idempotent by its own guards</h2>
 *
 * <p>It is a BACKGROUND operation in Google Messages, which is why no synchronous action of ours ever
 * triggered it. Every guard is a silent skip there; here each one is REPORTED, because "nothing
 * happened and nothing said why" is the exact failure mode this work spent weeks in. The
 * already-MLS and group-exists guards make repeated opens free.
 */
public final class MlsConversationOpenListener {
    private static final String TAG = MlsLog.TAG;

    private MlsConversationOpenListener() {}

    /**
     * Called when a conversation is opened. Cheap and non-blocking: returns immediately and does its
     * work off-thread, because it performs network I/O (a key-package claim per participant).
     *
     * <p>Safe to call on every open — the guards are the throttle, exactly as Google Messages' are. There
     * is deliberately no timer or cooldown: §8.7's throttle is state-based, and a conversation that
     * is already MLS costs one map lookup here.
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
                    // A conversation that fails to upgrade must never take the UI down with it.
                    LogUtil.w(TAG, "MlsConversationOpenListener: upgrade threw for "
                            + conversationId, t);
                }
            }
        }, "mls-conv-open").start();
    }

    private static void upgrade(final Context ctx, final int subId, final String conversationId) {
        final MlsProviderTransport t = MlsProviderTransport.get(ctx, subId);

        // ---- gather the policy's inputs ---------------------------------------------------------
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
                    + conversationId, e);
        }

        // A 1:1 has no RCS group id and is not this path's business — 1:1 MLS is established by the
        // send path, which already works. Leaving early here keeps the log quiet for the common case.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return;

        final boolean alreadyMls = t.hasMlsGroup(rcsGroupId);
        final boolean online = online(ctx);
        final boolean cheapGuardsPass = online && !alreadyMls && metadataAvailable
                && !participants.isEmpty();
        // ZERO registrations is Google Messages' DUMMY destination token — no routing target, so an MLS
        // group built around them admits someone unreachable. Expressible only since contract v54:
        // before it, "no registrations" and "we could not ask" were one value, and reading that as a
        // dummy token would refuse the upgrade on a transient lookup failure.
        final boolean dummyToken = cheapGuardsPass && t.anyParticipantUnroutable(participants);
        final boolean selfHasKeyPackages = t.isMlsReady();
        // The single-in-flight-operation registry IS "the group is initializing": starting one would
        // be the second concurrent operation that registry exists to refuse.
        final boolean initializing = t.groupIsInitializing(rcsGroupId);
        // THE JOINING GATE, IN FRONT OF THE CLAIM. establishGroup asks the same question
        // of the same peers and still does; this moves it ahead of the spend, because a create
        // Welcomes every member and a participant the gate refuses makes the whole create
        // impossible. Pure local predicate, which is what makes asking twice free.
        final boolean peersMayJoin = !cheapGuardsPass || t.upgradeMayWelcome(rcsGroupId, participants);

        // EVERY FREE GUARD FIRST, THEN THE CLAIM. PROCEED here is not "upgrade" — it is
        // "nothing cheaper has an answer, so the claim is worth making". See MlsUpgradePolicy.
        final MlsUpgradePolicy.Decision before = MlsUpgradePolicy.evaluateBeforeClaiming(
                online, dummyToken, alreadyMls, metadataAvailable, selfHasKeyPackages, rcsGroupId,
                initializing, alreadyMls, participants.size(), peersMayJoin);
        // ONE CLAIM, KEPT, and handed to the create below — the argument is in MlsUpgradeClaim.
        // Rationed per PEER by MlsClaimLedger: a refusal comes back as CLAIM_REFUSED
        // rather than as a count, so it cannot be read as the participants being short.
        final MlsUpgradeClaim claim = before.proceed() ? t.claimForUpgrade(participants) : null;
        final MlsUpgradePolicy.Decision d = (claim == null)
                ? before
                : MlsUpgradePolicy.evaluate(online, dummyToken, alreadyMls, metadataAvailable,
                        selfHasKeyPackages, rcsGroupId, initializing, claim.count(),
                        participants.size(), alreadyMls, peersMayJoin);

        if (!d.proceed()) {
            // REPORTED, where Google Messages' are silent. The whole problem was "nothing happened and
            // nothing said why".
            final String why = (d == MlsUpgradePolicy.Decision.NOT_ENOUGH_KEY_PACKAGES)
                    ? MlsUpgradePolicy.notEnoughKeyPackagesLine(
                            claim == null ? 0 : claim.count(), participants.size())
                    : d.line();
            LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " → " + d + " — " + why);
            // WHY THE UI DOOR IS ALLOWED TO BE REFUSED, written where the refusal lands.
            // An earlier stage let changeGroupSubject PROCEED on a refused look, "because that check
            // is an optimisation and refusing a user's action over our rate ledger is the worse
            // trade". This door sits on the OTHER side of that line, for three reasons that do not
            // hold there: the person asked to OPEN A CONVERSATION and the conversation opens
            // either way — the upgrade is speculative, background, and unobservable to them; the
            // resource is not ours to trade away, it is key material on somebody else's device
            // (the harm class this whole gate exists for); and this is the one door of the nine whose rate is set by a
            // human tapping a list, on a path whose only other bound goes away exactly when the
            // upgrade keeps failing. Refusing costs one window's delay on a conversation that
            // already works unencrypted, and the retry is the next open.
            if (d == MlsUpgradePolicy.Decision.CLAIM_REFUSED_BY_LEDGER) {
                LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " is unchanged and "
                        + "still usable — it simply stays unencrypted until the next open after "
                        + "the claim window. NOTHING was claimed, so this is not a statement about "
                        + "anyone's key packages.");
            }
            // ALREADY MLS IS NOT "NOTHING TO DO" — it is the maintenance case.
            //
            // The §8.7 pass was built, device-verified and left with exactly ONE caller: the debug
            // lever. So the whole PROACTIVE half of the era-advance design could not fire in
            // production at all — every refresh this project has ever performed was hand-driven.
            // This is its trigger, and conversation open is the right one for the same reason it is
            // the right trigger for the upgrade: it is Google Messages' own lever, it is where a
            // conversation's state is about to matter to a human, and it is state-based rather than
            // a timer (§8.7 says "Build no timer", and no client-side interval was found in the
            // reference client either).
            //
            // The pass decides for itself whether anything is warranted; it is not an era advance.
            // It gates on a membership delta and an expired-member count, and answers NOT_NEEDED for
            // the ordinary case — which is why running it on every open of an MLS conversation is
            // affordable.
            if (d == MlsUpgradePolicy.Decision.ALREADY_MLS && t.maintenanceOnOpen()) {
                LogUtil.i(TAG, "MlsConversationOpenListener: " + rcsGroupId + " is already MLS — "
                        + "running the §8.7 maintenance pass, which is what 'already MLS' actually "
                        + "calls for");
                try {
                    LogUtil.i(TAG, "MlsConversationOpenListener: maintenance → "
                            + t.runMaintenance(rcsGroupId, participants.get(0),
                                    "conversation opened"));
                } catch (final Throwable e) {
                    // A refresh that throws must never take the conversation down with it. Same
                    // rule as the upgrade path above.
                    LogUtil.w(TAG, "MlsConversationOpenListener: maintenance threw for "
                            + rcsGroupId, e);
                }
            }
            return;
        }

        LogUtil.i(TAG, "MlsConversationOpenListener: upgrading " + rcsGroupId + " to MLS on open ("
                + participants.size() + " participant(s))");
        // THE CLAIM ABOVE IS WHAT THIS GROUP IS BUILT OUT OF: establishGroup consumes
        // these packages instead of dialling the KDS a second time for every member.
        final int era = t.establishGroup(rcsGroupId, participants, /*carryGroupInfo=*/ null, claim);
        LogUtil.i(TAG, "On conversation open, conversationMlsUpdaterImpl returned: "
                + (era > 0 ? "UPGRADED_TO_MLS" : "NOT_UPGRADED") + " (era=" + era + ")");
    }

    /** Google Messages' first guard. Cheapest of the eleven, and it runs first for that reason. */
    private static boolean online(final Context ctx) {
        try {
            final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return true;   // cannot tell — do not invent a refusal
            final NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (final Throwable t) {
            return true;
        }
    }
}
