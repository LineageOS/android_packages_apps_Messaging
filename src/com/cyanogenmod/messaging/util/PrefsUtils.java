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
import com.android.messaging.util.PhoneUtils;

public class PrefsUtils {

    // QuickMessage
    public static final String QUICKMESSAGE_ENABLED      = "pref_key_quickmessage";
    public static final String QM_CLOSE_ALL_ENABLED      = "pref_key_close_all";
    public static final String SHOW_EMOTICONS_ENABLED    = "pref_show_emoticons";

    // Forceable data enable for mms
    public static final String DATA_ENABLED_ON_MMS_ENABLED = "pref_key_mms_enabled";

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

    /**
     * Returns whether or not data should be enabled prior to attempting a an http request to a
     * valid mms apn if data is already disabled
     * @return
     */
    public static boolean isEnableDataForMmsEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final boolean defaultValue = context.getResources().getBoolean(R.bool.enable_data_for_mms);
        return prefs.getBoolean(DATA_ENABLED_ON_MMS_ENABLED, defaultValue);
    }

    public static boolean isQuickMessagingEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        return prefs.getBoolean(QUICKMESSAGE_ENABLED, false);
    }

    public static boolean isQuickMessagingCloseAllEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        return prefs.getBoolean(QM_CLOSE_ALL_ENABLED, false);
    }

    public static boolean isShowEmoticonsEnabled() {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.show_emoticons_pref_default);
        return prefs.getBoolean(SHOW_EMOTICONS_ENABLED, defaultValue);
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

    public static int getValidityPeriod(int slot) {
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        String validityPeriod = context.getString(R.string.def_sms_validity_period_value);
        String prefKey = context.getString(R.string.pref_key_sms_validity_period);

        if (PhoneUtils.getDefault().isMultiSimEnabledMms()) {
            String prefKeySlot1 = context.getString(R.string.pref_key_sms_validity_period_slot1);
            String prefKeySlot2 = context.getString(R.string.pref_key_sms_validity_period_slot2);
            validityPeriod = prefs.getString((slot == 0) ? prefKeySlot1 : prefKeySlot2, null);
        } else {
            validityPeriod = prefs.getString(prefKey, null);
        }
        return (validityPeriod == null) ? -1 : Integer.parseInt(validityPeriod);
    }

}
