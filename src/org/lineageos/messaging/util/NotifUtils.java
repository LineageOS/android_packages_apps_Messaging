/*
 * Copyright (C) 2019 The LineageOS Project
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

package org.lineageos.messaging.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public final class NotifUtils {

    private NotifUtils() {
    }

    public static void createNotificationChannel(Context context, String id,
            int titleResId, int priority) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel existing = manager.getNotificationChannel(id);
        if (existing != null) {
            return;
        }

        String title = context.getString(titleResId);
        NotificationChannel newChannel = new NotificationChannel(id, title, priority);
        manager.createNotificationChannel(newChannel);
    }
}
