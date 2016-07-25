/*
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.util;

import android.content.Context;

import com.android.messaging.Factory;
import com.android.messaging.R;

public class NotificationUtil {
    /**
     * Get the final enabled status for notifications in this conversation, based on the given
     * value, and the default (application-wide) value.
     *
     * @param conversationVal the custom value for this conversation, or -1 if it does not exist.
     * @return whether notifications should be enabled for this conversation.
     */
    public static boolean getConversationNotificationEnabled(int conversationVal) {
        return getEnabledCustomOrDefault(conversationVal, R.string.notifications_enabled_pref_key);
    }

    /**
     * Get the final enabled status for notification vibration in this conversation, based on the
     * given value and the default (application-wide) value.
     *
     * @param conversationVal the custom value for this conversation, or -1 if it does not exist.
     * @return whether notification vibration should be enabled for this conversation.
     */
    public static boolean getConversationNotificationVibrateEnabled(int conversationVal) {
        return getEnabledCustomOrDefault(conversationVal, R.string.notification_vibration_pref_key);
    }

    private static boolean getEnabledCustomOrDefault(int customVal, int keyRes) {
        // Load default if we do not have a custom value set.
        if (customVal == -1) {
            final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
            final Context context = Factory.get().getApplicationContext();
            final String prefKey = context.getString(keyRes);
            return prefs.getBoolean(prefKey, true);
        } else {
            return customVal == 1;
        }
    }
}
