/*
 * Copyright (C) 2016 The CyanogenMod Project
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

import android.content.Context;
import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.util.BuglePrefs;

public class PrefsUtils {
    public static final String SHOW_EMOTICONS_ENABLED = "pref_show_emoticons";

    private PrefsUtils() {
        //Don't instantiate
    }

    /**
     * Returns whether or not swipe to dismiss in the ConversationListFragment deletes
     * the conversation rather than archiving it.
     * @return hopefully true
     */
    public static boolean isSwipeRightToDeleteEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.swipe_right_deletes_conversation_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.swipe_right_deletes_conversation_default);
        return prefs.getBoolean(prefKey, defaultValue);
    }

    public static boolean isShowEmoticonsEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.show_emoticons_pref_default);
        return prefs.getBoolean(SHOW_EMOTICONS_ENABLED, defaultValue);
    }
}
