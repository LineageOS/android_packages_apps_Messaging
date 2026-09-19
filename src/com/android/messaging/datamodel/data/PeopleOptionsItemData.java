/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2026 The LineageOS Project
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
package com.android.messaging.datamodel.data;

import android.content.Context;
import android.database.Cursor;

import com.android.messaging.R;
import com.android.messaging.rcs.ReadReceiptSettings;
import com.android.messaging.util.Assert;

public class PeopleOptionsItemData {
    // Identification for each setting that's surfaced to the UI layer.
    public static final int SETTING_NOTIFICATION = 0;
    public static final int SETTING_BLOCKED = 1;
    // Per-conversation override for RCS read receipts (displayed IMDNs). Only shown
    // for 1:1 RCS-capable threads (gated by PeopleAndOptionsFragment); when
    // shown it binds a Default/On/Off tri-state from ReadReceiptSettings.
    public static final int SETTING_RCS_READ_RECEIPTS = 2;
    public static final int SETTINGS_COUNT = 3;

    private String mTitle;
    private String mSubtitle;
    private int mItemId;
    private ParticipantData mOtherParticipant;
    private String mConversationId;

    private final Context mContext;

    public PeopleOptionsItemData(final Context context) {
        mContext = context;
    }

    /**
     * Bind to a specific setting column on conversation metadata cursor. (Note
     * that it binds to columns because it treats individual columns of the cursor as
     * separate options to display for the conversation, e.g. notification settings).
     */
    public void bind(
            final Cursor cursor, final ParticipantData otherParticipant, final int settingType,
            final String conversationId) {
        mItemId = settingType;
        mOtherParticipant = otherParticipant;
        mConversationId = conversationId;
        mSubtitle = null;

        switch (settingType) {
            case SETTING_NOTIFICATION:
                mTitle = mContext.getString(R.string.notifications_enabled_conversation_pref_title);
                break;

            case SETTING_BLOCKED:
                Assert.notNull(otherParticipant);
                final int resourceId = otherParticipant.isBlocked() ?
                        R.string.unblock_contact_title : R.string.block_contact_title;
                mTitle = mContext.getString(resourceId, otherParticipant.getDisplayDestination());
                break;

            case SETTING_RCS_READ_RECEIPTS:
                mTitle = mContext.getString(R.string.rcs_read_receipts_conversation_title);
                final int override = ReadReceiptSettings.getThreadOverride(conversationId);
                final int summaryRes;
                switch (override) {
                    case ReadReceiptSettings.OVERRIDE_ON:
                        summaryRes = R.string.rcs_read_receipts_state_on;
                        break;
                    case ReadReceiptSettings.OVERRIDE_OFF:
                        summaryRes = R.string.rcs_read_receipts_state_off;
                        break;
                    case ReadReceiptSettings.OVERRIDE_DEFAULT:
                    default:
                        summaryRes = R.string.rcs_read_receipts_state_default;
                        break;
                }
                mSubtitle = mContext.getString(summaryRes);
                break;

             default:
                 Assert.fail("Unsupported conversation option type!");
        }
    }

    public String getTitle() {
        return mTitle;
    }

    public String getSubtitle() {
        return mSubtitle;
    }

    public int getItemId() {
        return mItemId;
    }

    public ParticipantData getOtherParticipant() {
        return mOtherParticipant;
    }

    public String getConversationId() {
        return mConversationId;
    }
}
