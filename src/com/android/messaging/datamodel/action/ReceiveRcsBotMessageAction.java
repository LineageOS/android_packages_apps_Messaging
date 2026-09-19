/*
 * Copyright (C) 2026 The LineageOS Project
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
 * "Receive" an inbound RCS Business Messaging (RBM) agent message and land it in
 * messaging.db (contract-v10).
 *
 * <p>RBM rides our Tachygram stream as GSMA JSON: a kind=36 message from an
 * RCS_BOT sender ({@code <agent>@rbm.goog}) whose primary part is content-type
 * {@code application/vnd.gsma.botmessage.v1.0+json} (a rich card) or
 * {@code text/plain} (an agent fallback / confirmation line). See memory
 * {@code rbm-tachygram-gsma-json-contract-2026-06-10}.
 *
 * <p>This Phase-1 receiver mirrors {@link ReceiveRcsMessageAction} (resolve the
 * sender + conversation the same unified-thread way) so the agent message lands
 * in a real thread keyed by the bot id — the storage is verifiable end-to-end.
 * It differs in two deliberate ways:
 * <ul>
 *   <li>the stored body is the RAW GSMA JSON ({@code jsonBody}) when present, so
 *       no structured data is lost; Phase 2 parses it and Phase 4 renders the
 *       card (until then it shows as text). A plain-text agent line is stored as
 *       its text.</li>
 *   <li>it fires NO delivered IMDN — a phone-style receipt to a bot address fails
 *       server-side (INVALID_ARGUMENT) and a bot does not expect one; the
 *       user-driven response is the postback (Phase 5).</li>
 * </ul>
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
            return null;  // only the primary user persists (mirrors SMS receive)
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
        // Phase 1: store the structured JSON when present (Phase 2 parses it,
        // Phase 4 renders the card) so nothing is lost; otherwise the plain-text
        // agent line. The bot id is the conversation key (a thread for the agent).
        final String text = !TextUtils.isEmpty(jsonBody) ? jsonBody
                : (fallbackText != null ? fallbackText : "");

        final long received = System.currentTimeMillis();
        final long sent = serverTsUsec > 0 ? serverTsUsec / 1000L : received;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(received);

        // A bot id ("admin@rbm.goog") is not an E.164 number; it rides the same
        // address-keyed participant/thread machinery the unified inbox already
        // uses for non-phone (e.g. email) MMS recipients, so it lands in its own
        // 1:1-style conversation keyed by the bot id.
        final ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(botId, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());
        final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(context, botId);
        final String conversationId = BugleDatabaseOperations
                .getOrCreateConversationFromRecipient(db, threadId, blocked, rawSender);

        // Derive the display surfaces so the list snippet + notification show
        // a human summary (not raw JSON) and the thread/sender name shows the
        // resolved verified-business brand (not <agent>@rbm.goog). Brand resolve is
        // a blocking binder->cached HTTPS fetch; we're off-main here, so do it
        // BEFORE opening the db transaction. Both are best-effort (null -> leave
        // as-is); the stored body stays the raw JSON for the in-thread renderer.
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

            // Tag the RCS metadata columns (rcs_message_id + delivered status) the
            // same way ReceiveRcsMessageAction does, so Phase 4 can find the row.
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                    RcsMessageStore.rcsMetaValues(rcsMessageId,
                            IRcsProviderCallback.STATUS_DELIVERED));

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), received, blocked,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);

            // Override the snippet (-> human summary) + thread/participant
            // name (-> brand) AFTER the generic metadata update set snippet=body.
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

        // NO delivered IMDN: a bot address is not an MSISDN and a phone-style
        // receipt fails server-side. The user-driven response is the postback
        // (Phase 5).
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
