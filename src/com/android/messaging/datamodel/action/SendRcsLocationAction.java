/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.content.ContentValues;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.RcsSendStatus;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends a location share (geopush) to a 1:1 or group conversation through the provider, and on
 * acceptance inserts an outgoing row whose body is a maps link, rendered like an inbound share.
 * Runs off the main thread.
 */
public class SendRcsLocationAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_CONVERSATION_ID = "conversation_id";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_ACCURACY = "accuracy";

    /** Fire-and-forget; the local row + wire send happen in executeAction. */
    public static void shareLocation(final String conversationId, final double lat,
            final double lon, final double accuracyMeters) {
        final SendRcsLocationAction action =
                new SendRcsLocationAction(conversationId, lat, lon, accuracyMeters);
        action.start();
    }

    private SendRcsLocationAction(final String conversationId, final double lat,
            final double lon, final double accuracyMeters) {
        super();
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
        actionParameters.putDouble(KEY_LAT, lat);
        actionParameters.putDouble(KEY_LON, lon);
        actionParameters.putDouble(KEY_ACCURACY, accuracyMeters);
    }

    @Override
    protected Object executeAction() {
        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final double lat = actionParameters.getDouble(KEY_LAT);
        final double lon = actionParameters.getDouble(KEY_LON);
        final double accuracy = actionParameters.getDouble(KEY_ACCURACY);
        if (TextUtils.isEmpty(conversationId)) {
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String groupId =
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        final boolean isGroup = !TextUtils.isEmpty(groupId);
        final String selfId = BugleDatabaseOperations.getConversationSelfId(db, conversationId);
        // Route on the concrete default subscription, as the text path does; the conversation's
        // self is usually DEFAULT_SELF_SUB_ID.
        final int subId = PhoneUtils.getDefault().getEffectiveSubId(
                com.android.messaging.datamodel.data.ParticipantData.DEFAULT_SELF_SUB_ID);

        // Use the subscription's selected transport when it is the provider, else the singleton.
        final ProviderRegistry registry = ProviderRegistry.peek();
        final RcsTransport selected = (registry != null)
                ? registry.getActiveTransport(subId) : null;
        final ProviderTransport transport = (selected instanceof ProviderTransport)
                ? (ProviderTransport) selected : ProviderTransport.peekInstance();
        if (transport == null || !transport.getRouteSelector().awaitRcsAvailableForSub(subId)) {
            LogUtil.w(TAG, "SendRcsLocationAction: RCS not available; skipping");
            return null;
        }

        final String recipient;
        if (isGroup) {
            recipient = groupId;
        } else {
            final ArrayList<String> recips =
                    BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
            if (recips.isEmpty()) {
                return null;
            }
            final String canonical =
                    PhoneUtils.getDefault().getCanonicalBySimLocale(recips.get(0));
            recipient = TextUtils.isEmpty(canonical) ? recips.get(0) : canonical;
        }

        final String body = "📍 Shared location\n"
                + String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon);
        final long timestamp = System.currentTimeMillis() * 1000L;

        final String rcsMessageId = UUID.randomUUID().toString();
        final Double acc = accuracy > 0 ? accuracy : null;
        final RcsSendResult result = transport.sendLocation(subId, recipient, isGroup,
                lat, lon, acc == null ? 0d : acc, /*label=*/ null, rcsMessageId);
        if (result == null || !result.accepted) {
            LogUtil.i(TAG, "SendRcsLocationAction: send not accepted (reason="
                    + (result != null ? result.reasonCode : -1) + ")");
            return null;
        }

        db.beginTransaction();
        try {
            final MessageData message =
                    MessageData.createOutgoingRcsMessage(conversationId, selfId, body);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            // The provider accepts a location share synchronously and sends no status callback for
            // it, so the accepted outcome is written now; a row left at YET_TO_SEND would be owned
            // by nothing. See docs/rcs/architecture.md. This reports the send, never delivery; a
            // later IMDN still upgrades it.
            final ContentValues locVals = RcsMessageStore.rcsMetaValues(rcsMessageId,
                    RcsSendStatus.rcsStatusForMeasuredHandoff(true));
            locVals.put(DatabaseHelper.MessageColumns.STATUS,
                    RcsSendStatus.bugleStatusForMeasuredHandoff(true));
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), locVals);
            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyConversationListChanged();
        LogUtil.i(TAG, "SendRcsLocationAction: sent location to "
                + (isGroup ? "group" : "1-1") + " conv=" + conversationId);
        return null;
    }

    private SendRcsLocationAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<SendRcsLocationAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public SendRcsLocationAction createFromParcel(final Parcel in) {
            return new SendRcsLocationAction(in);
        }

        @Override
        public SendRcsLocationAction[] newArray(final int size) {
            return new SendRcsLocationAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
