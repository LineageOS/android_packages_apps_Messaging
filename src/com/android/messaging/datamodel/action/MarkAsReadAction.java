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

package com.android.messaging.datamodel.action;

import android.content.ContentValues;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.ReadReceiptSettings;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.LogUtil;

import java.util.List;

/**
 * Action used to mark all the messages in a conversation as read
 */
public class MarkAsReadAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_CONVERSATION_ID = "conversation_id";

    /**
     * Mark all the messages as read for a particular conversation.
     */
    public static void markAsRead(final String conversationId) {
        final MarkAsReadAction action = new MarkAsReadAction(conversationId);
        action.start();
    }

    private MarkAsReadAction(final String conversationId) {
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
    }

    @Override
    protected Object executeAction() {
        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);

        // TODO: Consider doing this in background service to avoid delaying other actions
        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Mark all messages in thread as read in telephony
        final long threadId = BugleDatabaseOperations.getThreadId(db, conversationId);
        if (threadId != -1) {
            MmsUtils.updateSmsReadStatus(threadId, Long.MAX_VALUE);
        }

        // Update local db
        db.beginTransaction();
        try {
            final ContentValues values = new ContentValues();
            values.put(MessageColumns.CONVERSATION_ID, conversationId);
            values.put(MessageColumns.READ, 1);
            values.put(MessageColumns.SEEN, 1);     // if they read it, they saw it

            final int count = db.update(DatabaseHelper.MESSAGES_TABLE, values,
                    "(" + MessageColumns.READ + " !=1 OR " +
                            MessageColumns.SEEN + " !=1 ) AND " +
                            MessageColumns.CONVERSATION_ID + "=?",
                    new String[] { conversationId });
            if (count > 0) {
                MessagingContentProvider.notifyMessagesChanged(conversationId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // Send displayed receipts for the unread inbound RCS messages; delivered receipts are
        // sent on receipt.
        sendDisplayedReceiptsForRcs(db, conversationId);

        // After marking messages as read, update the notifications. This will
        // clear the now stale notifications.
        BugleNotifications.update(false/*silent*/, BugleNotifications.UPDATE_ALL);
        return null;
    }

    /**
     * Sends a displayed receipt for each inbound RCS message not yet acknowledged and stamps
     * {@code rcs_displayed_timestamp}, so each fires once.
     */
    private static void sendDisplayedReceiptsForRcs(final DatabaseWrapper db,
            final String conversationId) {
        // Returns before stamping when receipts are off, so the rows fire if they are turned
        // back on. Delivered receipts are not gated.
        if (!ReadReceiptSettings.resolve(conversationId)) {
            return;
        }
        final List<RcsMessageStore.PendingDisplayed> pending =
                RcsMessageStore.findUndisplayedInboundRcs(db, conversationId);
        if (pending.isEmpty()) {
            return;
        }
        final Context context = Factory.get().getApplicationContext();
        final ProviderTransport transport = ProviderTransport.getInstance(context);
        for (final RcsMessageStore.PendingDisplayed p : pending) {
            // Claim the row first so a concurrent mark-as-read cannot send twice.
            final int claimed = RcsMessageStore.markDisplayedSent(
                    db, p.localMessageId, System.currentTimeMillis());
            if (claimed <= 0) {
                continue;
            }
            if (TextUtils.isEmpty(p.peerDestination)) {
                LogUtil.w(TAG, "IMDN DISPLAYED skipped for " + p.rcsMessageId
                        + ": unknown peer destination");
                continue;
            }
            // A message received in a group is acknowledged in that group, not the 1:1.
            final String rcsGroupId = RcsMessageStore.findGroupIdByRcsMessageId(p.rcsMessageId);
            transport.sendImdn(p.rcsMessageId, p.peerDestination,
                    IRcsProviderCallback.IMDN_DISPLAYED, rcsGroupId);
            LogUtil.i(TAG, "IMDN DISPLAYED sent for " + p.rcsMessageId
                    + " -> " + p.peerDestination
                    + (rcsGroupId == null ? " (1:1)" : " in group " + rcsGroupId));
        }
    }

    private MarkAsReadAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<MarkAsReadAction> CREATOR = new Parcelable.Creator<>() {
        @Override
        public MarkAsReadAction createFromParcel(final Parcel in) {
            return new MarkAsReadAction(in);
        }

        @Override
        public MarkAsReadAction[] newArray(final int size) {
            return new MarkAsReadAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
