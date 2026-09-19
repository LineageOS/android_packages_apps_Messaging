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

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsBotBrand;
import org.lineageos.rcs.provider.RcsIncomingBotMessage;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RbmSummary;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

/**
 * Stores an inbound business-messaging (RBM) agent message in a thread keyed by the bot id.
 *
 * <p>The body is stored as the raw GSMA bot-message JSON when present, so the in-thread renderer
 * has the full card; a plain-text agent line is stored as its text. Unlike
 * {@link ReceiveRcsMessageAction}, no delivered receipt is sent: a bot address does not accept one.
 */
public class ReceiveRcsBotMessageAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_BOT_ID = "bot_id";
    private static final String KEY_CONTENT_TYPE = "content_type";
    private static final String KEY_JSON_BODY = "json_body";
    private static final String KEY_FALLBACK_TEXT = "fallback_text";
    private static final String KEY_SERVER_TS_USEC = "server_ts_usec";

    public ReceiveRcsBotMessageAction(final RcsIncomingBotMessage msg) {
        actionParameters.putInt(KEY_SUB_ID, msg.subId);
        actionParameters.putString(KEY_RCS_MESSAGE_ID, msg.messageId);
        actionParameters.putString(KEY_BOT_ID, msg.botId);
        actionParameters.putString(KEY_CONTENT_TYPE, msg.contentType);
        actionParameters.putString(KEY_JSON_BODY, msg.jsonBody);
        actionParameters.putString(KEY_FALLBACK_TEXT, msg.fallbackText);
        actionParameters.putLong(KEY_SERVER_TS_USEC, msg.serverTimestampUsec);
    }

    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            return null;  // only the primary user persists
        }
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final int subId = actionParameters.getInt(KEY_SUB_ID,
                ParticipantData.DEFAULT_SELF_SUB_ID);
        final String rcsMessageId = actionParameters.getString(KEY_RCS_MESSAGE_ID);
        String botId = actionParameters.getString(KEY_BOT_ID);
        final String contentType = actionParameters.getString(KEY_CONTENT_TYPE);
        final String jsonBody = actionParameters.getString(KEY_JSON_BODY);
        final String fallbackText = actionParameters.getString(KEY_FALLBACK_TEXT);
        final long serverTsUsec = actionParameters.getLong(KEY_SERVER_TS_USEC);

        if (TextUtils.isEmpty(botId)) {
            LogUtil.w(TAG, "ReceiveRcsBotMessageAction: empty bot id; using unknown");
            botId = ParticipantData.getUnknownSenderDestination();
        }
        // A redelivery (the provider did not get its ack in) is not stored again; see
        // ReceiveRcsMessageAction. A bot takes no delivered receipt, so nothing else is due.
        if (!TextUtils.isEmpty(rcsMessageId)
                && RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId) != null) {
            LogUtil.i(TAG, "ReceiveRcsBotMessageAction: rcsId=" + rcsMessageId
                    + " is already stored; redelivery not inserted");
            return null;
        }
        final String text = !TextUtils.isEmpty(jsonBody) ? jsonBody
                : (fallbackText != null ? fallbackText : "");

        final long received = System.currentTimeMillis();
        final long sent = serverTsUsec > 0 ? serverTsUsec / 1000L : received;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(received);

        // A bot id is not a phone number; it uses the address-keyed thread machinery that non-phone
        // MMS recipients use, so it gets its own 1:1-style conversation.
        final ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(botId, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());
        final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(context, botId);
        final String conversationId = BugleDatabaseOperations
                .getOrCreateConversationFromRecipient(db, threadId, blocked, rawSender);

        // The list snippet and notification show a summary rather than JSON, and the sender name
        // shows the verified brand. The brand lookup is a blocking binder call, so it runs before
        // the transaction. Both are best-effort.
        final String botSummary = RbmSummary.fromJson(jsonBody, fallbackText);
        String brandName = null;
        try {
            final RcsBotBrand brand =
                    ProviderTransport.getInstance(context).getBotBrand(subId, botId);
            if (brand != null && !TextUtils.isEmpty(brand.name)) {
                brandName = brand.name;
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ReceiveRcsBotMessageAction: brand resolve failed", t);
        }

        final boolean inFocused = DataModel.get().isFocusedConversation(conversationId);
        final boolean inObservable = DataModel.get().isNewMessageObservable(conversationId);
        final boolean read = inFocused;
        final boolean seen = read || inObservable || blocked;

        final ParticipantData self = ParticipantData.getSelfParticipant(subId);

        MessageData message;
        db.beginTransaction();
        try {
            final String participantId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, rawSender);
            final String selfId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);

            message = MessageData.createReceivedRcsMessage(conversationId, participantId, selfId,
                    text, sent, received, seen, read);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                    RcsMessageStore.rcsMetaValues(rcsMessageId,
                            IRcsProviderCallback.STATUS_DELIVERED));

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), received, blocked,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);

            // Must follow the metadata update, which sets the snippet to the raw body.
            BugleDatabaseOperations.applyBotConversationDisplayInTransaction(db, conversationId,
                    participantId, botSummary, brandName);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "ReceiveRcsBotMessageAction: stored RBM message "
                + message.getMessageId() + " (rcsId=" + rcsMessageId + ") from bot "
                + botId + " ct=" + contentType
                + (jsonBody != null ? " json=" + jsonBody.length() + "B" : " text")
                + " in conversation " + conversationId);

        // No delivered receipt: a bot address does not accept one.
        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
    }

    private ReceiveRcsBotMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ReceiveRcsBotMessageAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ReceiveRcsBotMessageAction createFromParcel(final Parcel in) {
            return new ReceiveRcsBotMessageAction(in);
        }

        @Override
        public ReceiveRcsBotMessageAction[] newArray(final int size) {
            return new ReceiveRcsBotMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
