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

import android.content.ContentValues;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.e2ee.MlsResendLedger;
import com.android.messaging.util.LogUtil;

/**
 * Map an async provider status callback (onMessageStatus / onImdnReceipt)
 * onto messaging.db.
 *
 * <p>Looks up the local row by {@code rcs_message_id} (indexed in v3), then
 * {@code updateMessageRow}s (BugleDatabaseOperations 745-750) with:
 * <ul>
 *   <li>{@code message_status} <- mapped BUGLE_STATUS so the existing UI
 *       renders the delivered / failed bubble state (MessageData 121-143);
 *   <li>{@code rcs_status} <- the raw AIDL code;
 *   <li>{@code rcs_delivered_timestamp} / {@code rcs_displayed_timestamp} on
 *       IMDN receipt.
 * </ul>
 * Then notifies the content provider to refresh the conversation cursor.
 */
public class UpdateRcsMessageStatusAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** kind == status callback (value is IRcsProviderCallback.STATUS_*). */
    public static final int KIND_STATUS = 0;
    /** kind == imdn receipt (value is IRcsProviderCallback.IMDN_*). */
    public static final int KIND_IMDN = 1;
    /**
     * WAVE-E: per-member GROUP imdn receipt (value is IRcsProviderCallback
     * .IMDN_*, fromUri is the member who sent the receipt). Upserts a row into
     * rcs_group_receipts; no-op when the message is not in a group conversation.
     */
    public static final int KIND_GROUP_IMDN = 2;

    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_KIND = "kind";
    private static final String KEY_VALUE = "value";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_FROM_URI = "from_uri";

    public UpdateRcsMessageStatusAction(final String rcsMessageId, final int kind,
            final int value, final long timestamp) {
        this(rcsMessageId, kind, value, timestamp, null);
    }

    public UpdateRcsMessageStatusAction(final String rcsMessageId, final int kind,
            final int value, final long timestamp, final String fromUri) {
        actionParameters.putString(KEY_RCS_MESSAGE_ID, rcsMessageId);
        actionParameters.putInt(KEY_KIND, kind);
        actionParameters.putInt(KEY_VALUE, value);
        actionParameters.putLong(KEY_TIMESTAMP, timestamp);
        if (fromUri != null) {
            actionParameters.putString(KEY_FROM_URI, fromUri);
        }
    }

    @Override
    protected Object executeAction() {
        final String rcsMessageId = actionParameters.getString(KEY_RCS_MESSAGE_ID);
        final int kind = actionParameters.getInt(KEY_KIND);
        final int value = actionParameters.getInt(KEY_VALUE);
        final long timestamp = actionParameters.getLong(KEY_TIMESTAMP);

        if (TextUtils.isEmpty(rcsMessageId)) {
            LogUtil.w(TAG, "UpdateRcsMessageStatusAction: empty rcsMessageId; dropping");
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        String localId = RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId);

        // A RESEND HAS NO CHAT ROW, SO THE RECEIPT FOR IT MUST BE RESOLVED THROUGH THE CHAIN
        //. Every MLS resend goes out under a FRESH rcs_message_id — invariant 62
        // forbids replaying a consumed generation, and the server dedupes on message id — and that
        // new id is recorded ONLY in mls_resends. The `messages` row keeps the ORIGINAL id for
        // ever. So a peer's receipt names an id that is not in the messages table, this lookup
        // missed, and the update was dropped.
        //
        // OBSERVED, not inferred — one second of device log on 2026-08-01:
        //   17:58:25.368  MlsResendLedger: resend 1 of MxRhDXs-… → new id 6e6f0ff8-…
        //   17:58:25.390  sendGroupMessage … id=6e6f0ff8-…
        //   17:58:25.903  incoming IMDN <message-id>6e6f0ff8-…</message-id> <delivered/>
        //   17:58:25.953  UpdateRcsMessageStatusAction: no local row for rcsId=6e6f0ff8-…
        // The resend was DELIVERED and the chat row never learned. Note the line between the last
        // two: MlsProviderTransport handled the same receipt correctly, because it root-resolves.
        // Two consumers of one receipt, one chain-aware and one not.
        //
        // A FALLBACK, NOT A REPLACEMENT, and that is what makes this safe to land. The exact lookup
        // runs first and is unchanged; the chain step executes ONLY where we previously returned
        // null. So no receipt that resolved before can resolve differently now — the change is
        // strictly additive, which matters because index_messages_rcs_id is NOT unique and the
        // lookup is LIMIT 1.
        //
        // rootOf() returns the id unchanged when it is not a resend, so the inequality below is
        // what distinguishes "this was a resend and its root is elsewhere" from "the ledger knows
        // nothing about this id" — and the two log lines differ accordingly. An outcome that reads
        // the same whether or not the chain step fired would be no better than the silence this
        // replaces.
        if (localId == null) {
            final String root = MlsResendLedger.rootOf(rcsMessageId);
            if (!TextUtils.equals(root, rcsMessageId)) {
                localId = RcsMessageStore.findLocalIdByRcsMessageId(db, root);
                if (localId != null) {
                    LogUtil.i(TAG, "UpdateRcsMessageStatusAction: rcsId=" + rcsMessageId
                            + " is a RESEND; resolved it through the §10.3 ledger to its chain root "
                            + root + " and will update that row.");
                } else {
                    LogUtil.w(TAG, "UpdateRcsMessageStatusAction: no local row for rcsId="
                            + rcsMessageId + " NOR for its chain root " + root + " — the ledger has "
                            + "the chain but the originating row is gone (deleted conversation?).");
                    return null;
                }
            } else {
                LogUtil.w(TAG, "UpdateRcsMessageStatusAction: no local row for rcsId=" + rcsMessageId
                        + " (the §10.3 ledger has no resend chain for it either, so this is not a "
                        + "resend whose root we could have found).");
                return null;
            }
        }

        // DIRECTION GUARD. Every kind handled below reports the fate of a message WE SENT. But
        // rcs_message_id is not unique across devices in a group: each member stores its received
        // copy under the sender's id, so a lookup by rcs id alone resolves to the RECEIVED row on
        // every member that is not the originator, and the writes below then stamp it
        // BUGLE_STATUS_OUTGOING_DELIVERED.
        //
        // Measured 2026-08-16: all 28 group rows on a receiver carried status 2 for messages it had
        // only received. That made them look like outgoing rows to the outgoing-only filter in
        // RcsMessageStore.findTextByRcsMessageId, so an FTD naming a non-originator found a body and
        // it resent ANOTHER MEMBER'S message (§10.3 forbids this; Google Messages answers
        // 24 ZINNIA_FAILURE_INVALID_RESEND_SENDER). Dropping the update is correct and lossless: the
        // originator holds the outgoing row this receipt is actually about.
        if (RcsMessageStore.isIncomingLocalId(db, localId)) {
            LogUtil.i(TAG, "UpdateRcsMessageStatusAction: rcsId=" + rcsMessageId + " resolves to an "
                    + "INCOMING row on this device — a receipt for it belongs to the originator, not "
                    + "to us. Not rewriting a received message as outgoing.");
            return null;
        }

        // WAVE-E: per-member group receipt. The provider fires KIND_GROUP_IMDN
        // ALONGSIDE the 1:1 KIND_IMDN for every inbound IMDN. We only act when
        // the message belongs to a group conversation (rcs_group_id != null);
        // otherwise the 1:1 KIND_IMDN path owns the update and this is a no-op,
        // so the 1:1 single Delivered/Read render stays byte-unchanged.
        if (kind == KIND_GROUP_IMDN) {
            if (!RcsMessageStore.isGroupMessageLocalId(db, localId)) {
                return null;
            }
            final String fromUri = actionParameters.getString(KEY_FROM_URI);
            if (TextUtils.isEmpty(fromUri)) {
                LogUtil.w(TAG, "group IMDN: empty fromUri for rcsId=" + rcsMessageId);
                return null;
            }
            // Canonicalize so the per-member key matches stored participant URIs.
            String memberUri = fromUri;
            try {
                final String e164 = com.android.messaging.util.PhoneUtils.getDefault()
                        .getCanonicalBySimLocale(fromUri);
                if (!TextUtils.isEmpty(e164)) {
                    memberUri = e164;
                }
            } catch (final Exception e) {
                // Fall back to the raw fromUri.
            }
            final boolean displayed = (value == IRcsProviderCallback.IMDN_DISPLAYED);
            final boolean ok = RcsMessageStore.upsertGroupReceipt(
                    db, localId, memberUri, displayed, timestamp);
            if (ok) {
                final String convId =
                        BugleDatabaseOperations.getConversationIdFromMessageId(db, localId);
                if (convId != null) {
                    MessagingContentProvider.notifyMessagesChanged(convId);
                }
                LogUtil.i(TAG, "group IMDN upsert rcsId=" + rcsMessageId
                        + " member=" + memberUri + " displayed=" + displayed);
            }
            return null;
        }

        final ContentValues values = new ContentValues();
        String conversationId = null;

        if (kind == KIND_STATUS) {
            values.put(RcsMessageStore.COLUMN_RCS_STATUS, value);
            final int bugleStatus = mapBugleStatus(value);
            if (bugleStatus != MessageData.BUGLE_STATUS_UNKNOWN) {
                values.put(MessageColumns.STATUS, bugleStatus);
            }
            if (value == IRcsProviderCallback.STATUS_DELIVERED) {
                values.put(RcsMessageStore.COLUMN_RCS_DELIVERED_TS, System.currentTimeMillis());
            }
        } else { // KIND_IMDN
            if (value == IRcsProviderCallback.IMDN_DELIVERED) {
                values.put(RcsMessageStore.COLUMN_RCS_DELIVERED_TS, timestamp);
                values.put(MessageColumns.STATUS,
                        MessageData.BUGLE_STATUS_OUTGOING_DELIVERED);
                values.put(RcsMessageStore.COLUMN_RCS_STATUS,
                        IRcsProviderCallback.STATUS_DELIVERED);
            } else if (value == IRcsProviderCallback.IMDN_DISPLAYED) {
                values.put(RcsMessageStore.COLUMN_RCS_DISPLAYED_TS, timestamp);
                values.put(RcsMessageStore.COLUMN_RCS_STATUS,
                        IRcsProviderCallback.STATUS_DISPLAYED);
                // There is no distinct "displayed" coarse state; keep delivered.
                values.put(MessageColumns.STATUS,
                        MessageData.BUGLE_STATUS_OUTGOING_DELIVERED);
            }
        }

        if (values.size() == 0) {
            return null;
        }

        db.beginTransaction();
        try {
            BugleDatabaseOperations.updateMessageRow(db, localId, values);
            conversationId = BugleDatabaseOperations.getConversationIdFromMessageId(db, localId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (conversationId != null) {
            MessagingContentProvider.notifyMessagesChanged(conversationId);
        }
        return null;
    }

    private static int mapBugleStatus(final int rcsStatus) {
        switch (rcsStatus) {
            case IRcsProviderCallback.STATUS_SENT:
                return MessageData.BUGLE_STATUS_OUTGOING_COMPLETE;
            case IRcsProviderCallback.STATUS_DELIVERED:
            case IRcsProviderCallback.STATUS_DISPLAYED:
                return MessageData.BUGLE_STATUS_OUTGOING_DELIVERED;
            case IRcsProviderCallback.STATUS_FAILED:
                return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
            default:
                return MessageData.BUGLE_STATUS_UNKNOWN;
        }
    }

    private UpdateRcsMessageStatusAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<UpdateRcsMessageStatusAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public UpdateRcsMessageStatusAction createFromParcel(final Parcel in) {
            return new UpdateRcsMessageStatusAction(in);
        }

        @Override
        public UpdateRcsMessageStatusAction[] newArray(final int size) {
            return new UpdateRcsMessageStatusAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
