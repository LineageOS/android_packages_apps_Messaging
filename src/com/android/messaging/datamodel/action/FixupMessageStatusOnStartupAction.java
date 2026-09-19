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

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsSendStatus;
import com.android.messaging.util.LogUtil;

/**
 * Action used to fixup actively downloading or sending status at startup - just in case we
 * crash - never run this when a message might actually be sending or downloading.
 */
public class FixupMessageStatusOnStartupAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    public static void fixupMessageStatus() {
        final FixupMessageStatusOnStartupAction action = new FixupMessageStatusOnStartupAction();
        action.start();
    }

    private FixupMessageStatusOnStartupAction() {
    }

    @Override
    protected Object executeAction() {
        // Now mark any messages in active sending or downloading state as inactive
        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        int downloadFailedCnt;
        int sendFailedCnt;
        int rcsStrandedCnt;
        try {
            // For both sending and downloading messages, let's assume they failed.
            // For MMS sent/downloaded via platform, the sent/downloaded pending intent
            // may come back. That will update the message. User may see the message
            // in wrong status within a short window if that happens. But this should
            // rarely happen. This is a simple solution to situations like app gets killed
            // while the pending intent is still in the fly. Alternatively, we could
            // keep the status for platform sent/downloaded MMS and timeout these messages.
            // But that is much more complex.
            final ContentValues values = new ContentValues();
            values.put(DatabaseHelper.MessageColumns.STATUS,
                    MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED);
            downloadFailedCnt = db.update(DatabaseHelper.MESSAGES_TABLE, values,
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?)",
                    new String[]{
                            Integer.toString(MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING),
                            Integer.toString(MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING)
                    });

            values.clear();
            values.put(DatabaseHelper.MessageColumns.STATUS,
                    MessageData.BUGLE_STATUS_OUTGOING_FAILED);
            sendFailedCnt = db.update(DatabaseHelper.MESSAGES_TABLE, values,
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?)",
                    new String[]{
                            Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_SENDING),
                            Integer.toString(MessageData.BUGLE_STATUS_OUTGOING_RESENDING)
                    });

            // THE BACKSTOP FOR RCS ROWS. The sweep above deliberately leaves
            // YET_TO_SEND(4) and AWAITING_RETRY(7) alone, because for SMS/MMS those two are a
            // QUEUE STATE: ProcessPendingMessagesAction owns them and will pick the row up again
            // after the restart. For a TRANSPORT_RCS row they are the opposite — that same query
            // excludes TRANSPORT_RCS by name, so nothing will ever pick it up, and the bubble reads
            // "Sending…" with no timeout, no retry and no resend affordance (which is offered only
            // from FAILED). Every RCS send is dispatched synchronously by InsertNewMessageAction
            // BEFORE its row is inserted, so a process restart leaves nothing in flight to resume:
            // an RCS row still at 4 or 7 here is stranded by construction, not waiting.
            //
            // FAILED is the only direction this may move. "Sent" would be success we never
            // measured — the provider accepts sendMessage synchronously and reports the real
            // outcome later over onMessageStatus, so accept != delivered != sent — and a false
            // "sent" hides a lost message permanently. A false "failed" is visible and actionable.
            //
            // IT IS ALSO SELF-CORRECTING, BUT NOT FOR EVERY VERB, AND THIS COMMENT USED TO SAY
            // OTHERWISE (corrected 2026-09-13). The claim was that
            // UpdateRcsMessageStatusAction's KIND_STATUS and KIND_IMDN arms both write the row
            // unconditionally, so a late callback or receipt always upgrades it. That is sound for
            // a verb that HAS a late callback. Per verb:
            //
            //   KIND_STATUS — needs onMessageStatus, which is emitted from exactly three sites
            //     in TachyonTransport (sendFile, finishSend, reportSendFailed). Unavailable
            //     for sendLocation, sendGroupMessage and the two MLS ciphertext verbs:
            //     they return their whole report synchronously and reach none of those sites.
            //   KIND_IMDN — needs a receipt from the PEER. Untraced for sendLocation. The arm is
            //     structurally reachable (SendRcsLocationAction does stamp an rcs_message_id for it
            //     to match), but whether the rcspushlocation+xml form solicits an IMDN is not
            //     established here, and the answer finally depends on peer behaviour this code
            //     cannot observe.
            //
            // So on a synchronous verb a false FAILED may be PERMANENT: one arm is gone and the
            // other is unproven. That is an argument for recording the measured outcome at the send
            // site rather than leaning on this sweep — it is a backstop for a stranded row, never a
            // substitute for an answer we already held. This sweep stays correct for what it is:
            // the alternative to a false
            // FAILED here is not a true status, it is "Sending…" for ever.
            //
            // This is a cold-start-only sweep (DataModelImpl.onApplicationCreated), so it cannot
            // race a live send: no send can be in flight at the moment the process is created.
            //
            // Re-stated rather than inherited from the block above: this sweep's whole correctness
            // argument is "FAILED and nothing else", and leaning on what the previous ContentValues
            // happens to still hold would let an edit up there change it silently.
            values.clear();
            values.put(DatabaseHelper.MessageColumns.STATUS,
                    RcsSendStatus.BUGLE_STATUS_OUTGOING_FAILED);
            rcsStrandedCnt = db.update(DatabaseHelper.MESSAGES_TABLE, values,
                    DatabaseHelper.MessageColumns.STATUS + " IN (?, ?) AND "
                    + DatabaseHelper.MessageColumns.TRANSPORT_TYPE + " =?",
                    new String[]{
                            Integer.toString(RcsSendStatus.BUGLE_STATUS_OUTGOING_YET_TO_SEND),
                            Integer.toString(RcsSendStatus.BUGLE_STATUS_OUTGOING_AWAITING_RETRY),
                            Integer.toString(RcsConstants.TRANSPORT_RCS)
                    });

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "Fixup: Send failed - " + sendFailedCnt
                + " RCS stranded - " + rcsStrandedCnt
                + " Download failed - " + downloadFailedCnt);

        // Don't send contentObserver notifications as displayed text should not change
        return null;
    }

    private FixupMessageStatusOnStartupAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<FixupMessageStatusOnStartupAction> CREATOR
            = new Parcelable.Creator<>() {
        @Override
        public FixupMessageStatusOnStartupAction createFromParcel(final Parcel in) {
            return new FixupMessageStatusOnStartupAction(in);
        }

        @Override
        public FixupMessageStatusOnStartupAction[] newArray(final int size) {
            return new FixupMessageStatusOnStartupAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
