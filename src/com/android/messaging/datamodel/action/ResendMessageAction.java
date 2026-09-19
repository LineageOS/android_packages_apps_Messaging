/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 The LineageOS Project
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

        // AN RCS ROW TAKES THE RCS PATH AND NEVER THE SMS QUEUE.
        //
        // Everything below moves the row to BUGLE_STATUS_OUTGOING_YET_TO_SEND and hands it to
        // ProcessPendingMessagesAction. That queue excludes TRANSPORT_RCS BY NAME — RCS rows are
        // sent synchronously and carry no telephony Uri — so for an RCS row it does not resend
        // anything: it parks the message at "Sending…" and walks away, which is the exact defect
        // the terminal-status rules exist to remove, re-created by the button offered to escape it.
        //
        // This used to REFUSE the row here, which was honest but left a plaintext SMS as the only
        // recovery on a conversation the app had told the user was encrypted. That refusal is now
        // a real over-RCS resend. It must NEVER fall through to the code below.
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
     * <b>A real over-RCS resend.</b> Never touches the SMS send queue.
     *
     * <p>An earlier change made a FAILED RCS row terminal and honest, and refused the resend here
     * because the path below would have parked the row back at {@code OUTGOING_YET_TO_SEND} in a
     * queue that excludes {@code TRANSPORT_RCS} by name. That was correct and it left the user one
     * affordance: "Send as SMS", which re-composes the body as legacy SMS and deletes the RCS row.
     * On an E2EE conversation that made a plaintext SMS the ONLY recovery.
     *
     * <p>This reaches {@code MlsProviderTransport.resendByUser}, i.e. the SAME machinery a
     * peer-reported resend uses, rather than growing a second answer beside it. That matters
     * because the questions are already answered there: the resend re-seals at a FRESH generation
     * under a FRESH {@code rcs_message_id} (invariant 62 forbids replaying a consumed one) and
     * records its rung in the §10.3 ledger.
     *
     * <h2>Why the row keeps its ORIGINAL id</h2>
     *
     * <p>The wire id changes; the chat row's does not. What makes that safe is that the receipt
     * for the new id root-resolves through the ledger back to this row. Before that fix this
     * method would have sent correctly and produced a bubble that could never show Delivered,
     * which is why this was sequenced behind it.
     *
     * <h2>The outcome is SYNCHRONOUS, so reaching the write IS the measurement</h2>
     *
     * <p>{@code resendByUser} seals and sends on this thread and returns whether the wire accepted
     * it. There is no callback to wait for, so the row's status is written from that answer exactly
     * as {@code InsertNewMessageAction}'s app-owned arm does — and the row is never
     * left at a status only the SMS queue services. On failure it goes back to FAILED, which is
     * where it already was: the user is no worse off and "Send as SMS" is still on the bubble.
     * That is the material difference from the button that was removed, which was an
     * unrecoverable strand rather than a retry that can fail.
     */
    private Object resendOverRcs(final DatabaseWrapper db, final String messageId,
            final MessageData message, final RcsMessageStore.RcsMeta rcsMeta) {
        // Only from FAILED, the same precondition the SMS path applies below. Checked here rather
        // than inherited: the RCS branch runs BEFORE that check, so without this a SENDING row
        // could be sent a second time.
        if (message == null || !message.canResendMessage()) {
            LogUtil.w(TAG, "ResendMessageAction: not resending RCS message " + messageId
                    + " — status is "
                    + (message == null ? "<row not found>"
                            : MessageData.getStatusDescription(message.getStatus()))
                    + ", and a manual resend is only offered from FAILED.");
            return null;
        }
        // No wire id, no resend: there is nothing to root-resolve and therefore no body to
        // recover. This is the REFUSED rows, which are landed FAILED with a
        // null rcs_message_id on purpose — they never reached the wire, so "resend" is not the
        // verb they need. They keep "Send as SMS", which is.
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
            // THE PEER OF AN OUTGOING ROW IS NOT ON THE ROW. sender_participant_id is SELF on
            // anything we sent, so the only source is the conversation's participants — the same
            // two steps InsertNewMessageAction takes, canonicalisation included. That second step
            // is load-bearing rather than tidy: send_destination can hold the national/dialable
            // form and Tachyon answers a non-E.164 destination with NOT_FOUND tachyonerror=40.
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
            // A throw here must land the row at FAILED, not leave it mid-flight. It was already
            // FAILED, so the write below is what keeps that true rather than what makes it so.
            LogUtil.e(TAG, "ResendMessageAction: the MLS transport threw resending " + messageId,
                    t);
            sent = false;
        }

        // The measured outcome, written in one place. rcs_status gets the provider's own
        // SENT/FAILED so the row is indistinguishable from one a callback drove, and
        // message_status gets the mapping UpdateRcsMessageStatusAction.mapBugleStatus applies to
        // exactly that callback. Reports the SEND and never DELIVERY: the peer's IMDN still
        // upgrades this row to OUTGOING_DELIVERED — through the chain, since it will name the
        // resend's id — and its absence still means nothing was confirmed.
        //
        // rcsMessageId is passed back UNCHANGED and deliberately: this row's identity does not
        // move when its message is resent.
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
