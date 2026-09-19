/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.RcsFileAttachment;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.util.LogUtil;

/**
 * Applies {@code onIncomingFileUnavailable}: the pending file row for an {@code rcs_message_id}
 * is marked unavailable, so it shows that the file can no longer be downloaded instead of offering
 * a download that cannot succeed. Only a pending row changes ({@link
 * RcsFileAttachment#statusWhenUnavailable}). See "Files" in docs/rcs/provider-contract.md.
 */
public class MarkRcsFileUnavailableAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_REASON = "reason";

    /** @param reason an {@code IRcsProviderCallback.FILE_UNAVAILABLE_*}, for the log */
    public MarkRcsFileUnavailableAction(final String rcsMessageId, final int reason) {
        actionParameters.putString(KEY_RCS_MESSAGE_ID, rcsMessageId);
        actionParameters.putInt(KEY_REASON, reason);
    }

    @Override
    protected Object executeAction() {
        final String rcsMessageId = actionParameters.getString(KEY_RCS_MESSAGE_ID);
        final int reason = actionParameters.getInt(KEY_REASON);
        if (TextUtils.isEmpty(rcsMessageId)) {
            return null;
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String localId = RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId);
        final RcsMessageStore.RcsMeta meta =
                localId == null ? null : RcsMessageStore.readByLocalId(db, localId);
        if (meta == null) {
            LogUtil.i(TAG, "MarkRcsFileUnavailableAction: no row for rcsId=" + rcsMessageId);
            return null;
        }
        final int next = RcsFileAttachment.statusWhenUnavailable(meta.rcsStatus);
        if (next == meta.rcsStatus) {
            LogUtil.i(TAG, "MarkRcsFileUnavailableAction: rcsId=" + rcsMessageId + " is not "
                    + "pending (rcs_status=" + meta.rcsStatus + "); unchanged");
            return null;
        }
        BugleDatabaseOperations.updateMessageRow(db, localId,
                RcsMessageStore.rcsMetaValues(rcsMessageId, next));
        LogUtil.i(TAG, "MarkRcsFileUnavailableAction: rcsId=" + rcsMessageId
                + " marked unavailable (reason=" + reason + ")");
        final MessageData message = BugleDatabaseOperations.readMessage(db, localId);
        if (message != null) {
            MessagingContentProvider.notifyMessagesChanged(message.getConversationId());
        }
        return null;
    }

    private MarkRcsFileUnavailableAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<MarkRcsFileUnavailableAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public MarkRcsFileUnavailableAction createFromParcel(final Parcel in) {
            return new MarkRcsFileUnavailableAction(in);
        }

        @Override
        public MarkRcsFileUnavailableAction[] newArray(final int size) {
            return new MarkRcsFileUnavailableAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
