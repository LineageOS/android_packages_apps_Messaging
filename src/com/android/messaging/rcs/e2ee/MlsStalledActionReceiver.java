/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsDowngradeReason;
import com.android.messaging.util.LogUtil;

/**
 * Carries out the user's choice on a stalled encrypted conversation. It is the only way the stall
 * path reaches a downgrade: a conversation stops being encrypted only because a person chose it.
 * Both arms run off the main thread (network recovery, or building and sending a commit). See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsStalledActionReceiver extends BroadcastReceiver {

    private static final String TAG = LogUtil.BUGLE_TAG;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final String key = intent.getStringExtra(MlsStalledNotifier.EXTRA_CONVERSATION_KEY);
        final String peer = intent.getStringExtra(MlsStalledNotifier.EXTRA_PEER);
        final String rcsGroupId = intent.getStringExtra(MlsStalledNotifier.EXTRA_RCS_GROUP_ID);
        final int subId = intent.getIntExtra(MlsStalledNotifier.EXTRA_SUB_ID, -1);
        // A group carries an rcsGroupId, a 1:1 a peer; one of the two is required.
        if (key == null || (peer == null && rcsGroupId == null)) {
            LogUtil.w(TAG, "MlsStalledActionReceiver: " + action + " with no conversation — ignoring");
            return;
        }
        final Context app = context.getApplicationContext();

        if (MlsStalledNotifier.ACTION_RETRY.equals(action)) {
            LogUtil.i(TAG, "MlsStalledActionReceiver: user chose RETRY for " + MlsConversationKey.forLog(key)
                    + " — resetting the self-heal budget, the rebuild rate bound and its episode "
                    + "suppressor, the era budget, the peer-health streak, the re-establish "
                    + "cooldown and the §11.2.2 external-commit allowance, then re-driving "
                    + "recovery. Encryption is UNCHANGED.");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(app, subId);
                    // Try again resets every durable bound that fails closed, or the button would
                    // do nothing; see docs/mls/budgets.md. The fetch and claim ledgers are
                    // deliberately left alone.
                    t.resetSelfHealBudget(key);
                    t.resetRebuildRateBound(key);
                    MlsPeerGuard.resetEraBudget(rcsGroupId, peer);
                    // For every member of a group; resetPeerHealth rather than notePeerRecovered,
                    // because a button press is no evidence the peer processed anything.
                    t.resetPeerHealth(rcsGroupId, peer);
                    // A separate bound from the rebuild rate bound: it refuses a different
                    // operation.
                    t.resetReestablishCooldown(rcsGroupId, peer);
                    t.resetExternalCommitBudget(key);
                    final Object r = t.driveReconcile(rcsGroupId, peer);
                    LogUtil.i(TAG, "MlsStalledActionReceiver: retry for " + MlsConversationKey.forLog(key) + " → " + r);
                    // Re-raise or clear from what the drive achieved, using the same health check
                    // as the stall.
                    t.refreshStallNotification(rcsGroupId, peer, key);
                }
            }, "mls-stall-retry").start();
            return;
        }

        if (MlsStalledNotifier.ACTION_DOWNGRADE.equals(action)) {
            LogUtil.w(TAG, "MlsStalledActionReceiver: user chose TURN OFF ENCRYPTION for " + MlsConversationKey.forLog(key)
                    + " — running the downgrade ladder with UNRECOVERABLE_SERVER_FAILURE. The queue "
                    + "is drained before MLS ends, and the reason is marked not-expected so the "
                    + "re-upgrade loop will try to bring this conversation back on its own.");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(app, subId);
                    final int r = t.endMls(rcsGroupId, peer, /*resume=*/ false,
                            MlsDowngradeReason.UNRECOVERABLE_SERVER_FAILURE);
                    LogUtil.i(TAG, "MlsStalledActionReceiver: downgrade for " + MlsConversationKey.forLog(key) + " → " + r);
                    MlsStalledNotifier.clear(app, key);
                }
            }, "mls-stall-downgrade").start();
            return;
        }

        LogUtil.w(TAG, "MlsStalledActionReceiver: unknown action " + action);
    }
}
