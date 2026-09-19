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
package com.android.messaging.rcs;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.messaging.R;
import com.android.messaging.ui.rcs.RcsCarrierTosActivity;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationsUtil;

/**
 * Surfaces the carrier/Google RCS Terms-of-Service prompt.
 *
 * <p>Registered {@code RECEIVER_NOT_EXPORTED}; receives only the package-scoped
 * {@link RcsConstants#ACTION_CARRIER_TOS_REQUIRED} that {@link ProviderTransport}
 * fires on {@code onCarrierTosStateChanged(TOS_REQUIRED)}. Decision:
 *
 * <ul>
 *   <li>app is foreground -> launch {@link RcsCarrierTosActivity} inline (the
 *       modal consent dialog);
 *   <li>app is backgrounded -> post a pending-ToS notification whose tap launches
 *       the same Activity. Provisioning runs headless at app start, so the prompt
 *       can arrive when no RCS Activity is foregrounded.
 * </ul>
 *
 * <p>Additive: never touches the SMS/MMS path. If extras are missing it no-ops.
 */
public final class RcsCarrierTosReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Stable notify-id for the single pending-ToS notification (per process). */
    private static final int TOS_NOTIFICATION_ID = 0x70_5C0;  // "tos" + collision-free
    private static final String TOS_NOTIFICATION_TAG = "rcs_carrier_tos";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null
                || !RcsConstants.ACTION_CARRIER_TOS_REQUIRED.equals(intent.getAction())) {
            return;
        }

        final Intent launch = new Intent(context, RcsCarrierTosActivity.class)
                .putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (isAppForeground(context)) {
            try {
                context.startActivity(launch);
                LogUtil.i(TAG, "RcsCarrierTosReceiver: launched consent dialog inline");
                return;
            } catch (final Throwable t) {
                // Fall through to a notification if the inline launch is denied
                // (e.g. background-activity-start restriction won a race).
                LogUtil.w(TAG, "RcsCarrierTosReceiver: inline launch failed; notifying", t);
            }
        }

        postNotification(context, launch);
    }

    /** True when this app has a foreground (or visible) process. */
    private static boolean isAppForeground(final Context context) {
        try {
            final ActivityManager am =
                    context.getSystemService(ActivityManager.class);
            if (am == null) {
                return false;
            }
            final ActivityManager.RunningAppProcessInfo info =
                    new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (final Throwable t) {
            return false;
        }
    }

    private static void postNotification(final Context context, final Intent launch) {
        try {
            NotificationsUtil.createNotificationChannelGroup(context,
                    NotificationsUtil.CONVERSATION_GROUP_NAME,
                    R.string.notification_channel_messages_title);
            NotificationsUtil.createNotificationChannel(context,
                    NotificationsUtil.DEFAULT_CHANNEL_ID,
                    R.string.notification_channel_messages_title,
                    NotificationManager.IMPORTANCE_HIGH,
                    null);

            final PendingIntent pi = PendingIntent.getActivity(
                    context, TOS_NOTIFICATION_ID, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            final NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, NotificationsUtil.DEFAULT_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_sms_light)
                            .setContentTitle(
                                    context.getString(R.string.rcs_tos_notification_title))
                            .setContentText(
                                    context.getString(R.string.rcs_tos_notification_body))
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                    context.getString(R.string.rcs_tos_notification_body)))
                            .setAutoCancel(true)
                            .setContentIntent(pi);

            NotificationManagerCompat.from(context).notify(
                    TOS_NOTIFICATION_TAG, TOS_NOTIFICATION_ID, builder.build());
            LogUtil.i(TAG, "RcsCarrierTosReceiver: posted pending-ToS notification");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "RcsCarrierTosReceiver: postNotification failed", t);
        }
    }
}
