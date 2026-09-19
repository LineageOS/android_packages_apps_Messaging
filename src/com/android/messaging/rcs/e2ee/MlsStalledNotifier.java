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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.android.messaging.R;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationsUtil;

/**
 * Tell the user an encrypted conversation is stuck, and let them choose what to do.
 *
 * <h2>Why a person is asked at all</h2>
 *
 * <p>An era gap we cannot cross is the one failure where the machine genuinely has no further move
 * AND the two ways forward cost the user different things. Waiting keeps confidentiality and keeps a
 * conversation that currently receives nothing. Turning encryption off makes it work and gives up
 * end-to-end encryption for that thread. Nothing in the protocol tells us which the user prefers.
 *
 * <p><b>We deliberately do not decide it for them, in either direction.</b>
 *
 * <ul>
 *   <li><b>Not an automatic downgrade.</b> Google Messages' own answer to an uncrossable era gap is a local
 *       downgrade, and copying it was the obvious move. What is established is that its
 *       kill path EXISTS and what it does — <i>never what invokes it</i> — and our own measurement
 *       showed an undelivered message is not the trigger. Downgrading automatically would mean
 *       inventing a condition and then silently spending the user's confidentiality on it.</li>
 *   <li><b>Not silence.</b> Silence is what the conversation already does, and it is the worst
 *       option available: it receives nothing, permanently, while still showing a closed padlock.
 *       That is the appearance of confidentiality over a dead channel, which is worse than either
 *       honest outcome.</li>
 * </ul>
 *
 * <h2>What each action does</h2>
 *
 * <p><b>Try again</b> resets every bound that could be why we stopped — the self-heal budget, the
 * rebuild rate bound, the era budget and the peer-health streak — and re-drives recovery. It is
 * worth offering because the blocker is genuinely external: the peer or the server may have moved
 * since we gave up, and a retry costs nothing but a few RPCs. It changes no encryption state and can
 * be taken any number of times.
 *
 * <p>The last two of those are the guards that protect the PEER (G2/G4, wired to
 * the rebuild), and they fail closed on purpose. This button is their manual
 * reset — the one G2's own wording asks for — and it is the reason failing closed automatically is
 * not the same as giving up: the automatic path stops, a person is told, and the person decides.
 *
 * <p><b>Turn off encryption</b> runs the ordinary downgrade ladder with
 * {@link com.android.messaging.rcs.engine.mls.MlsDowngradeReason#UNRECOVERABLE_SERVER_FAILURE}. Two
 * properties of that reason matter here and are why this is a safe thing to put in a user's hands:
 * it is marked <b>not expected</b>, so it stamps the unexpected-downgrade bookkeeping and the
 * backoff-governed re-upgrade loop will try to bring the conversation back on its own; and the
 * downgrade path drains the pending queue before it ends MLS, so nothing parked is thrown away.
 * <b>It is not a one-way door</b>, and the notification says so rather than making the user guess.
 *
 * <h2>One notification per conversation</h2>
 *
 * <p>Keyed by conversation, so a stuck thread cannot produce a stream of identical alerts — the
 * condition is persistent by nature and would otherwise re-fire on every inbound message from a peer
 * that is still happily sending.
 */
public final class MlsStalledNotifier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Its own channel: this is not a message, and it must not be silenced with message alerts. */
    private static final String CHANNEL_ID = "mls_stalled_channel";

    /** Offset so these ids cannot collide with the message notifications. */
    private static final int ID_BASE = 0x4D4C_0000;

    public static final String ACTION_RETRY =
            "com.android.messaging.rcs.e2ee.MLS_STALLED_RETRY";
    public static final String ACTION_DOWNGRADE =
            "com.android.messaging.rcs.e2ee.MLS_STALLED_DOWNGRADE";
    public static final String EXTRA_CONVERSATION_KEY = "conversation_key";
    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_RCS_GROUP_ID = "rcs_group_id";
    public static final String EXTRA_SUB_ID = "sub_id";

    private MlsStalledNotifier() {}

    /** Stable per-conversation id, so re-raising REPLACES rather than stacks. */
    static int notificationId(final String conversationKey) {
        return ID_BASE | (conversationKey == null ? 0 : conversationKey.hashCode() & 0xFFFF);
    }

    /**
     * Raise (or refresh) the stall alert for one conversation.
     *
     * @param conversationKey our internal conversation key — the id the actions come back with
     * @param peerE164        the peer, shown to the user and needed by both actions
     * @param rcsGroupId      the RCS group id, or null for a 1:1
     */
    public static void raise(final Context ctx, final int subId, final String conversationKey,
            final String peerE164, final String rcsGroupId, final String detail) {
        if (ctx == null || conversationKey == null) return;
        try {
            NotificationsUtil.createNotificationChannel(ctx, CHANNEL_ID,
                    ctx.getString(R.string.mls_stalled_channel_title),
                    NotificationManager.IMPORTANCE_DEFAULT, null);

            final String who = peerE164 == null ? "" : peerE164;
            final Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sms_light)
                    .setContentTitle(ctx.getString(R.string.mls_stalled_title))
                    .setContentText(ctx.getString(R.string.mls_stalled_body, who))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(ctx.getString(R.string.mls_stalled_body_long, who)))
                    // Persistent condition, not a moment — do not buzz repeatedly for it.
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .addAction(0, ctx.getString(R.string.mls_stalled_action_retry),
                            pending(ctx, ACTION_RETRY, subId, conversationKey, peerE164, rcsGroupId))
                    .addAction(0, ctx.getString(R.string.mls_stalled_action_downgrade),
                            pending(ctx, ACTION_DOWNGRADE, subId, conversationKey, peerE164,
                                    rcsGroupId))
                    .build();

            ctx.getSystemService(NotificationManager.class)
                    .notify(notificationId(conversationKey), n);
            LogUtil.i(TAG, "MlsStalledNotifier: raised the stall choice for " + conversationKey
                    + " (peer=" + who + ") — " + detail + ". NOTHING has been downgraded; the user "
                    + "picks retry or turn-off-encryption, and until they do the conversation stays "
                    + "encrypted.");
        } catch (final RuntimeException e) {
            // A notification failing must never take down the recovery path that called it.
            LogUtil.w(TAG, "MlsStalledNotifier: could not raise the stall choice for "
                    + conversationKey + " — the condition still holds and is in the log above", e);
        }
    }

    /** Clear it once the conversation is no longer stuck (either choice, or it healed by itself). */
    public static void clear(final Context ctx, final String conversationKey) {
        if (ctx == null || conversationKey == null) return;
        try {
            ctx.getSystemService(NotificationManager.class)
                    .cancel(notificationId(conversationKey));
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsStalledNotifier: could not clear " + conversationKey, e);
        }
    }

    private static PendingIntent pending(final Context ctx, final String action, final int subId,
            final String conversationKey, final String peerE164, final String rcsGroupId) {
        final Intent i = new Intent(action)
                .setClass(ctx, MlsStalledActionReceiver.class)
                .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
                .putExtra(EXTRA_PEER, peerE164)
                .putExtra(EXTRA_RCS_GROUP_ID, rcsGroupId)
                .putExtra(EXTRA_SUB_ID, subId);
        // The request code must vary per (conversation, action) or the two actions would share one
        // PendingIntent and the second would silently reuse the first's extras.
        final int requestCode = notificationId(conversationKey) ^ action.hashCode();
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
