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

package com.cyanogenmod.messaging.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.UnicodeFilter;

public class PrefsUtils {

    // QuickMessage
    public static final String QUICKMESSAGE_ENABLED      = "pref_key_quickmessage";
    public static final String QM_CLOSE_ALL_ENABLED      = "pref_key_close_all";

    private PrefsUtils() {
        //Don't instantiate
    }

    /**
     * Returns whether or not swipe to dismiss in the ConversationListFragment deletes
     * the conversation rather than archiving it.
     * @return hopefully true
     */
    public static boolean isSwipeToDeleteEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.swipe_deletes_conversation_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.swipe_deletes_conversation_default);
        return prefs.getBoolean(prefKey, defaultValue);
    }

    public static boolean isQuickMessagingEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        return prefs.getBoolean(QUICKMESSAGE_ENABLED, false);
    }

    public static boolean isQuickMessagingCloseAllEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        return prefs.getBoolean(QM_CLOSE_ALL_ENABLED, false);
    }

    public static UnicodeFilter getUnicodeFilterIfEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String unicodeIntactValue =
                context.getString(R.string.unicode_stripping_leave_intact_value);
        final String unicodePrefKey = context.getString(R.string.unicode_stripping_pref_key);
        final String unicodeStripping = prefs.getString(unicodePrefKey, unicodeIntactValue);
        if (!unicodeIntactValue.equals(unicodeStripping)) {
            String unicodeNonEncodableValue = context.getString(
                    R.string.unicode_stripping_non_encodable_value);
            boolean stripNonEncodableOnly = unicodeNonEncodableValue.equals(unicodeStripping);
            return new UnicodeFilter(stripNonEncodableOnly);
        }
        return null;
    }
}
