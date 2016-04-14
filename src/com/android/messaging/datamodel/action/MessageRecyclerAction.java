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


package com.android.messaging.datamodel.action;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.LogUtil;
import com.cyanogenmod.messaging.util.PrefsUtils;

/*
 * Action used to delete a messages based on limit specified by user/config.
 */


public class MessageRecyclerAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    public static void deleteMessagesOverLimit(final String conversationId, int protocol) {

        if (!PrefsUtils.isAutoDeleteEnabled() || (protocol != MessageData.PROTOCOL_SMS
                && protocol != MessageData.PROTOCOL_MMS)) {
            return;
        }

        int cutOffLimit = PrefsUtils.getMessagesPerThreadLimitByProtocol(protocol);
        long cutOffTimeStampFromLimit = BugleDatabaseOperations.
                getCutOffTimeStampFromLimit(conversationId, cutOffLimit, MessageData.PROTOCOL_SMS);

        if (cutOffTimeStampFromLimit > Long.MIN_VALUE) {
            deleteMessages(conversationId, protocol, cutOffTimeStampFromLimit);
        }
    }

    public static void deleteMessages(final String conversationId,
            final int protocol, final long cutOffTimeStamp) {
        final MessageRecyclerAction action =
                new MessageRecyclerAction(conversationId, protocol, cutOffTimeStamp);
        action.start();
    }

    private static final String KEY_CONVERSATION_ID = "key_conversation_id";
    private static final String KEY_PROTOCOL = "key_protocol";
    private static final String KEY_CUTOFF_TIMESTAMP = "key_cutoff_timestamp";

    private MessageRecyclerAction(final String conversationId,
            final int protocol, final long cutOffTimeStamp) {
        super();
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
        actionParameters.putInt(KEY_PROTOCOL, protocol);
        actionParameters.putLong(KEY_CUTOFF_TIMESTAMP, cutOffTimeStamp);
    }

    @Override
    protected Bundle doBackgroundWork() {
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final int protocol = actionParameters.getInt(KEY_PROTOCOL);
        final long cutOffTimeStamp = actionParameters.getLong(KEY_CUTOFF_TIMESTAMP);

        if (!TextUtils.isEmpty(conversationId)) {
            // Delete from local DB
            BugleDatabaseOperations.
                    deleteConversationByProtocol(db, conversationId, cutOffTimeStamp, protocol);

            // We may have changed the conversation list
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyConversationListChanged();

            int count = MmsUtils.deleteMessagesOlderThanByProtocol(cutOffTimeStamp, protocol);
            if (count > 0) {
                LogUtil.i(TAG, "MessageRecyclerAction: Deleted telephony messages "
                        + count);
            } else {
                LogUtil.w(TAG, "MessageRecyclerAction: Could not delete message from telephony");
            }
        }
        return null;
    }

    @Override
    protected Object executeAction() {
        requestBackgroundWork();
        return null;
    }

    private MessageRecyclerAction(final Parcel in) {
        super(in);
    }

    public static final Creator<MessageRecyclerAction> CREATOR
            = new Creator<MessageRecyclerAction>() {
        @Override
        public MessageRecyclerAction createFromParcel(final Parcel in) {
            return new MessageRecyclerAction(in);
        }

        @Override
        public MessageRecyclerAction[] newArray(final int size) {
            return new MessageRecyclerAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
