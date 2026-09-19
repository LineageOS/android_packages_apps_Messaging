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
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.RcsSendStatus;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Action used to manually resend an outgoing message
 */
public class ResendMessageAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";

    /**
     * Manual send of existing message (no listener)
     */
    public static void resendMessage(final String messageId) {
        final ResendMessageAction action = new ResendMessageAction(messageId);
        action.start();
    }

    // Core parameters needed for all types of message
    private static final String KEY_MESSAGE_ID = "message_id";

    /**
     * Constructor used for retrying sending in the background (only message id available)
     */
    ResendMessageAction(final String messageId) {
        super();
        actionParameters.putString(KEY_MESSAGE_ID, messageId);
    }

    /**
     * Read message from database and change status to allow sending
     */
    @Override
    protected Object executeAction() {
        final String messageId = actionParameters.getString(KEY_MESSAGE_ID);

        final DatabaseWrapper db = DataModel.get().getDatabase();

        final MessageData message = BugleDatabaseOperations.readMessage(db, messageId);

        // An RCS row is resent over RCS and never reaches the code below, which hands the row to
        // a queue that excludes TRANSPORT_RCS.
        final RcsMessageStore.RcsMeta rcsMeta = RcsMessageStore.readByLocalId(db, messageId);
        if (rcsMeta != null && rcsMeta.isRcs()) {
            return resendOverRcs(db, messageId, message, rcsMeta);
        }

        // Check message can be resent
        if (message != null && message.canResendMessage()) {
            final boolean isMms = message.getIsMms();
            long timestamp = System.currentTimeMillis();
            if (isMms) {
                // MMS expects timestamp rounded to nearest second
                timestamp = 1000 * ((timestamp + 500) / 1000);
            }

            LogUtil.i(TAG, "ResendMessageAction: Resending message " + messageId
                    + "; changed timestamp from " + message.getReceivedTimeStamp() + " to "
                    + timestamp);

            final ContentValues values = new ContentValues();
            values.put(MessageColumns.STATUS, MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND);
            values.put(MessageColumns.RETRY_START_TIMESTAMP, timestamp);

            // Row must exist as was just loaded above (on ActionService thread)
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), values);

            MessagingContentProvider.notifyMessagesChanged(message.getConversationId());

            actionParameters.putInt(KEY_SUB_ID,
                    BugleDatabaseOperations.getSelfSubscriptionId(db, message.getSelfId()));

            // Whether we succeeded or failed we will check and maybe schedule some more work
            ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(false, this);

            return message;
        } else {
            String error = "ResendMessageAction: Cannot resend message " + messageId + "; ";
            if (message != null) {
                error += ("status = " + MessageData.getStatusDescription(message.getStatus()));
            } else {
                error += "not found in database";
            }
            LogUtil.e(TAG, error);
        }

        return null;
    }

    /**
     * Resends a failed RCS row through {@code MlsProviderTransport.resendByUser}, the machinery a
     * peer-reported resend uses: a fresh generation under a fresh wire id, recorded in the resend
     * ledger. The row keeps its original id; the new id's receipt resolves back to it. The
     * outcome is synchronous and written directly. See docs/rcs/architecture.md.
     */
    private Object resendOverRcs(final DatabaseWrapper db, final String messageId,
            final MessageData message, final RcsMessageStore.RcsMeta rcsMeta) {
        // Only from FAILED. The RCS branch runs before the SMS path's check, so it is repeated.
        if (message == null || !message.canResendMessage()) {
            LogUtil.w(TAG, "ResendMessageAction: not resending RCS message " + messageId
                    + " — status is "
                    + (message == null ? "<row not found>"
                            : MessageData.getStatusDescription(message.getStatus()))
                    + ", and a manual resend is only offered from FAILED.");
            return null;
        }
        // Refused rows have no wire id: they never reached the wire, and keep "Send as SMS".
        final String rcsMessageId = rcsMeta.rcsMessageId;
        if (TextUtils.isEmpty(rcsMessageId)) {
            LogUtil.i(TAG, "ResendMessageAction: RCS row " + messageId + " carries no "
                    + "rcs_message_id — it never reached the wire (a refused send), so there is "
                    + "nothing to resend. Leaving it FAILED; \"Send as SMS\" still applies.");
            return null;
        }

        final String conversationId = message.getConversationId();
        final String rcsGroupId =
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        String peerE164 = null;
        if (TextUtils.isEmpty(rcsGroupId)) {
            // Our own rows carry SELF as the sender, so the peer comes from the conversation's
            // participants, canonicalized to E.164 as in InsertNewMessageAction.
            final ArrayList<String> recipients =
                    BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
            if (recipients.isEmpty()) {
                LogUtil.w(TAG, "ResendMessageAction: conversation " + conversationId
                        + " has no recipients, so there is nobody to resend " + messageId + " to.");
                return null;
            }
            final String canonical =
                    PhoneUtils.getDefault().getCanonicalBySimLocale(recipients.get(0));
            peerE164 = TextUtils.isEmpty(canonical) ? recipients.get(0) : canonical;
        }

        final int subId = PhoneUtils.getDefault()
                .getEffectiveSubId(ParticipantData.DEFAULT_SELF_SUB_ID);
        boolean sent;
        try {
            sent = MlsProviderTransport.get(Factory.get().getApplicationContext(), subId)
                    .resendByUser(rcsGroupId, peerE164, rcsMessageId);
        } catch (final Throwable t) {
            // The row stays FAILED.
            LogUtil.e(TAG, "ResendMessageAction: the MLS transport threw resending " + messageId,
                    t);
            sent = false;
        }

        // The outcome, mapped as UpdateRcsMessageStatusAction maps the matching callback; a later
        // IMDN, naming the resend's id, still upgrades the row. The row's rcsMessageId is kept.
        final ContentValues values = RcsMessageStore.rcsMetaValues(rcsMessageId,
                RcsSendStatus.rcsStatusForMeasuredHandoff(sent));
        values.put(MessageColumns.STATUS, RcsSendStatus.bugleStatusForMeasuredHandoff(sent));
        BugleDatabaseOperations.updateMessageRow(db, messageId, values);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        LogUtil.i(TAG, "ResendMessageAction: over-RCS resend of " + messageId + " (rcsId="
                + rcsMessageId + ") -> " + (sent ? "SENT" : "FAILED")
                + "; the row keeps its original rcs_message_id and the receipt for the resend's "
                + "fresh id will root-resolve back to it.");
        return sent ? message : null;
    }

    private ResendMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ResendMessageAction> CREATOR
            = new Parcelable.Creator<>() {
        @Override
        public ResendMessageAction createFromParcel(final Parcel in) {
            return new ResendMessageAction(in);
        }

        @Override
        public ResendMessageAction[] newArray(final int size) {
            return new ResendMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
