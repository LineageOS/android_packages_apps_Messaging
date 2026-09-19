/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

/**
 * Inserts the outgoing bubble that echoes a tapped business-messaging suggestion chip, after the
 * postback has gone out through {@link
 * com.android.messaging.rcs.ProviderTransport#sendBotPostback}. The row is written already sent: a
 * postback is fire-and-forget and a bot sends no receipt.
 */
public class InsertRbmPostbackEchoAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_BOT_ID = "bot_id";
    private static final String KEY_TEXT = "text";

    public InsertRbmPostbackEchoAction(final int subId, final String botId, final String text) {
        actionParameters.putInt(KEY_SUB_ID, subId);
        actionParameters.putString(KEY_BOT_ID, botId);
        actionParameters.putString(KEY_TEXT, text);
    }

    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            return null;  // only the primary user persists
        }
        final int subId = actionParameters.getInt(KEY_SUB_ID,
                ParticipantData.DEFAULT_SELF_SUB_ID);
        final String botId = actionParameters.getString(KEY_BOT_ID);
        final String text = actionParameters.getString(KEY_TEXT);
        if (TextUtils.isEmpty(botId) || TextUtils.isEmpty(text)) {
            return null;
        }

        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final long now = System.currentTimeMillis();

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(now);

        // Same address-keyed thread the inbound bot card lands in.
        final ParticipantData rawBot = ParticipantData.getFromRawPhoneBySimLocale(botId, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawBot.getNormalizedDestination());
        final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(context, botId);
        final String conversationId = BugleDatabaseOperations
                .getOrCreateConversationFromRecipient(db, threadId, blocked, rawBot);

        final ParticipantData self = ParticipantData.getSelfParticipant(subId);

        MessageData message;
        db.beginTransaction();
        try {
            final String selfId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);
            message = MessageData.createOutgoingRcsMessage(conversationId, selfId, text);
            message.updateSendingMessage(conversationId, null /* messageUri */, now);
            message.markMessageSent(now);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            // A sentinel rcs_message_id marks the row as a selected option; a bot sends no receipt
            // for a postback, so it cannot collide with receipt routing.
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                    RcsMessageStore.rcsMetaValues(RcsConstants.RBM_POSTBACK_ECHO_MARKER,
                            RcsConstants.RCS_STATUS_NONE));

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), now, blocked,
                    null /* serviceCenter */, false /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "InsertRbmPostbackEchoAction: echoed reply (" + text.length()
                + " chars) to bot " + botId + " in conversation " + conversationId);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
    }

    private InsertRbmPostbackEchoAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<InsertRbmPostbackEchoAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public InsertRbmPostbackEchoAction createFromParcel(final Parcel in) {
            return new InsertRbmPostbackEchoAction(in);
        }

        @Override
        public InsertRbmPostbackEchoAction[] newArray(final int size) {
            return new InsertRbmPostbackEchoAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
