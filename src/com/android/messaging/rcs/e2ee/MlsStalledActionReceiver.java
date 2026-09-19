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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.messaging.rcs.engine.mls.MlsDowngradeReason;
import com.android.messaging.util.LogUtil;

/**
 * Carries out whichever choice the user made on a stalled encrypted conversation.
 *
 * <p>This is the ONLY place the downgrade can be reached from the stall path. There is no automatic
 * caller and there must not be one: the whole design here is that a conversation stops
 * being end-to-end encrypted only because a person said so. See {@link MlsStalledNotifier} for why.
 *
 * <p>Both arms run off the main thread — {@code retry} drives a recovery that talks to the network,
 * and {@code endMls} builds and sends a commit. A broadcast receiver's {@code onReceive} runs on the
 * main thread and is time-limited, so doing either inline would ANR.
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
        // A GROUP carries an rcsGroupId and no single peer; a 1:1 carries a peer and no group. One of
        // the two must be present or there is nothing to act on.
        if (key == null || (peer == null && rcsGroupId == null)) {
            LogUtil.w(TAG, "MlsStalledActionReceiver: " + action + " with no conversation — ignoring");
            return;
        }
        final Context app = context.getApplicationContext();

        if (MlsStalledNotifier.ACTION_RETRY.equals(action)) {
            LogUtil.i(TAG, "MlsStalledActionReceiver: user chose RETRY for " + key
                    + " — resetting the self-heal budget, the rebuild rate bound and its episode "
                    + "suppressor, the era budget, the peer-health streak, the re-establish "
                    + "cooldown and the §11.2.2 external-commit allowance, then re-driving "
                    + "recovery. Encryption is UNCHANGED.");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(app, subId);
                    // The budget is why we stopped trying; leaving it spent would make the retry a
                    // no-op and the user would press a button that does nothing.
                    t.resetSelfHealBudget(key);
                    // AND THE REBUILD RATE BOUND. The self-heal budget is no longer the
                    // only thing that can be why we stopped: once the automatic rebuild exists, a
                    // conversation can be sitting out its rebuild window instead. Resetting one and
                    // not the other reintroduces exactly the defect this button exists to escape —
                    // a person pressing "try again" and watching nothing happen.
                    t.resetRebuildRateBound(key);
                    // AND THE PEER-FACING BUDGETS. The rebuild now charges
                    // MlsPeerGuard's era budget and passes through its peer-health streak, so those
                    // are two more reasons the retry could do nothing — and both of them FAIL
                    // CLOSED by design, which only works if a person has a way to say otherwise.
                    // This is that way, and it is G2's own wording: "on trip: stop, log
                    // loudly, REQUIRE MANUAL RESET". Deliberately not automatic and deliberately
                    // loud; MlsPeerGuard logs both.
                    MlsPeerGuard.resetEraBudget(rcsGroupId, peer);
                    // THE PEER-HEALTH HALF, FOR EVERY MEMBER. Two things changed here.
                    // It goes through the transport because a GROUP retry has no single peer and
                    // one member's streak refuses the rebuild of the whole group — the receiver
                    // cannot resolve a roster and used to clear nothing at all in that case, which
                    // was survivable only while a force-stop cleared the streak anyway. And it calls
                    // resetPeerHealth rather than notePeerRecovered: the latter means "the peer
                    // processed something of ours" and says so in the log, which a button press is
                    // not evidence of.
                    t.resetPeerHealth(rcsGroupId, peer);
                    // AND THE RE-ESTABLISH COOLDOWN. It bounds the 1:1 outbound
                    // re-establish, and each attempt claims one of the peer's KeyPackages — so it
                    // was made durable so it survives a restart, and a durable refusal with no
                    // lever is a trap. Not folded into resetRebuildRateBound like the episode
                    // suppressor: that one refuses the SAME operation the rate bound does, while
                    // this refuses a different one, and a reset whose name does not match what it
                    // clears is how the hand-maintained list went wrong in the first place.
                    t.resetReestablishCooldown(rcsGroupId, peer);
                    // AND THE §11.2.2 EXTERNAL-COMMIT ALLOWANCE. It was the one
                    // durable refusal on this ladder that this button did not reach AT ALL —
                    // MlsExternalCommitBudget.reset was public with zero production callers — so a
                    // conversation stuck behind a spent 50-per-day allowance had no lever and a
                    // 24-hour wait. Why a person may clear a bound the SPEC places on us, when the
                    // other four are our own: see MlsProviderTransport.resetExternalCommitBudget.
                    t.resetExternalCommitBudget(key);
                    final Object r = t.driveReconcile(rcsGroupId, peer);
                    LogUtil.i(TAG, "MlsStalledActionReceiver: retry for " + key + " → " + r);
                    // Re-raise or clear based on what the drive actually achieved, rather than
                    // assuming the retry worked. detectHealth is the same authority the stall used.
                    t.refreshStallNotification(rcsGroupId, peer, key);
                }
            }, "mls-stall-retry").start();
            return;
        }

        if (MlsStalledNotifier.ACTION_DOWNGRADE.equals(action)) {
            LogUtil.w(TAG, "MlsStalledActionReceiver: user chose TURN OFF ENCRYPTION for " + key
                    + " — running the downgrade ladder with UNRECOVERABLE_SERVER_FAILURE. The queue "
                    + "is drained before MLS ends, and the reason is marked not-expected so the "
                    + "re-upgrade loop will try to bring this conversation back on its own.");
            new Thread(new Runnable() {
                @Override public void run() {
                    final MlsProviderTransport t = MlsProviderTransport.get(app, subId);
                    final int r = t.endMls(rcsGroupId, peer, /*resume=*/ false,
                            MlsDowngradeReason.UNRECOVERABLE_SERVER_FAILURE);
                    LogUtil.i(TAG, "MlsStalledActionReceiver: downgrade for " + key + " → " + r);
                    // The choice has been made and carried out; the alert has nothing left to ask.
                    MlsStalledNotifier.clear(app, key);
                }
            }, "mls-stall-downgrade").start();
            return;
        }

        LogUtil.w(TAG, "MlsStalledActionReceiver: unknown action " + action);
    }
}
