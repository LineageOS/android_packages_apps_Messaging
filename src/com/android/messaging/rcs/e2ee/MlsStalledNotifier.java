/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.android.messaging.R;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationsUtil;

/**
 * Tells the user an encrypted conversation is stuck and offers two actions, handled by
 * {@link MlsStalledActionReceiver}. The app never decides this itself: an automatic downgrade would
 * spend the user's confidentiality on a condition we invented, and silence leaves a closed padlock
 * on a conversation that receives nothing.
 *
 * <p><b>Try again</b> resets the bounds that fail closed and re-drives recovery; it changes no
 * encryption state. <b>Turn off encryption</b> runs the downgrade with
 * {@link com.android.messaging.rcs.engine.mls.MlsDowngradeReason#UNRECOVERABLE_SERVER_FAILURE},
 * which drains the pending queue first and is not an expected reason, so the re-upgrade loop can
 * bring the conversation back. One notification per conversation. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsStalledNotifier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Its own channel, so it is not silenced along with message alerts. */
    private static final String CHANNEL_ID = "mls_stalled_channel";

    /** Offset so these ids cannot collide with message notifications. */
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

    /** Stable per conversation, so re-raising replaces rather than stacks. */
    static int notificationId(final String conversationKey) {
        return ID_BASE | (conversationKey == null ? 0 : conversationKey.hashCode() & 0xFFFF);
    }

    /**
     * Raise or refresh the stall alert for one conversation.
     *
     * @param conversationKey the canonical key the actions come back with
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
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .addAction(0, ctx.getString(R.string.mls_stalled_action_retry),
                            pending(ctx, ACTION_RETRY, subId, conversationKey, peerE164,
                                    rcsGroupId))
                    .addAction(0, ctx.getString(R.string.mls_stalled_action_downgrade),
                            pending(ctx, ACTION_DOWNGRADE, subId, conversationKey, peerE164,
                                    rcsGroupId))
                    .build();

            ctx.getSystemService(NotificationManager.class)
                    .notify(notificationId(conversationKey), n);
            LogUtil.i(TAG, "MlsStalledNotifier: raised the stall choice for "
                    + MlsConversationKey.forLog(conversationKey)
                    + " (peer=" + LogMask.number(who) + ") — " + detail
                    + ". NOTHING has been downgraded; the user "
                    + "picks retry or turn-off-encryption, and until they do the conversation stays "
                    + "encrypted.");
        } catch (final RuntimeException e) {
            // A notification failure must never take down the recovery path that called it.
            LogUtil.w(TAG, "MlsStalledNotifier: could not raise the stall choice for "
                    + MlsConversationKey.forLog(conversationKey)
                    + " — the condition still holds and is in the log above", e);
        }
    }

    /** Clear the alert once the conversation is no longer stuck. */
    public static void clear(final Context ctx, final String conversationKey) {
        if (ctx == null || conversationKey == null) return;
        try {
            ctx.getSystemService(NotificationManager.class)
                    .cancel(notificationId(conversationKey));
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsStalledNotifier: could not clear "
                    + MlsConversationKey.forLog(conversationKey), e);
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
        // Distinct per (conversation, action), or the two actions would share one PendingIntent and
        // its extras.
        final int requestCode = notificationId(conversationKey) ^ action.hashCode();
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
