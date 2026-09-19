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
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsEarlyStatusPark;
import com.android.messaging.rcs.RcsInboundConfirmation;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.e2ee.E2eeObservation;
import com.android.messaging.rcs.e2ee.E2eeObservationStore;
import com.android.messaging.util.LogUtil;

/**
 * Applies a provider status callback or receipt ({@code onMessageStatus}, {@code onImdnReceipt},
 * {@code onGroupImdnReceipt}) to the row found by {@code rcs_message_id}: {@code message_status},
 * {@code rcs_status} and the receipt timestamps. See docs/rcs/architecture.md.
 */
public class UpdateRcsMessageStatusAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Status callback; the value is an {@code IRcsProviderCallback.STATUS_*}. */
    public static final int KIND_STATUS = 0;
    /** Receipt; the value is an {@code IRcsProviderCallback.IMDN_*}. */
    public static final int KIND_IMDN = 1;
    /**
     * Per-member group receipt; the value is an {@code IMDN_*} and fromUri names the member.
     * Upserts {@code rcs_group_receipts}; a no-op outside a group conversation.
     */
    public static final int KIND_GROUP_IMDN = 2;

    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_KIND = "kind";
    private static final String KEY_VALUE = "value";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_FROM_URI = "from_uri";
    private static final String KEY_E2EE_SCHEME_ID = "e2ee_scheme_id";
    private static final String KEY_SOURCE = "source";

    public UpdateRcsMessageStatusAction(final String rcsMessageId, final int kind,
            final int value, final long timestamp) {
        this(rcsMessageId, kind, value, timestamp, null);
    }

    /**
     * A KIND_STATUS update. {@code e2eeSchemeId} is the scheme the transport reports it applied,
     * meaningful on STATUS_SENT; only a PROVIDER status may change the row's scheme or the
     * conversation's bits.
     */
    public static UpdateRcsMessageStatusAction forStatus(final String rcsMessageId,
            final int status, final String e2eeSchemeId, final E2eeObservation.Source source) {
        final UpdateRcsMessageStatusAction a =
                new UpdateRcsMessageStatusAction(rcsMessageId, KIND_STATUS, status, 0L);
        a.actionParameters.putString(KEY_E2EE_SCHEME_ID, e2eeSchemeId);
        a.actionParameters.putString(KEY_SOURCE, source.name());
        return a;
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

    /** Confirms {@code ticket} to the provider that delivered this, once it is stored. */
    public UpdateRcsMessageStatusAction confirming(
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        RcsInboundConfirmation.attach(ticket, actionParameters);
        return this;
    }

    @Override
    protected Object executeAction() {
        // Confirmed after the store has returned: a store that throws confirms nothing,
        // and the provider offers the message again.
        final Object stored = store();
        RcsInboundConfirmation.afterStore(actionParameters, "UpdateRcsMessageStatusAction");
        return stored;
    }

    private Object store() {
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

        if (localId == null && kind == KIND_STATUS) {
            // A status can overtake the insert of its row. Park it for the insert path, then
            // look again: whichever side runs second applies it, once.
            RcsEarlyStatusPark.get().park(rcsMessageId, value,
                    value == IRcsProviderCallback.STATUS_SENT,
                    actionParameters.getString(KEY_E2EE_SCHEME_ID),
                    actionParameters.getString(KEY_SOURCE), System.currentTimeMillis());
            LogUtil.i(TAG, "UpdateRcsMessageStatusAction: no local row yet for rcsId="
                    + rcsMessageId + "; parked status " + value + " for the insert path.");
            applyParkedStatus(db, rcsMessageId);
            return null;
        }
        if (localId == null) {
            LogUtil.w(TAG, "UpdateRcsMessageStatusAction: no local row for rcsId=" + rcsMessageId
                    + "; nothing to update. The row was deleted, or this outcome is for a message "
                    + "this device did not originate.");
            return null;
        }

        // Every kind below is about a message we sent. In a group each member stores its received
        // copy under the sender's id, so a lookup can hit a received row; rewriting it as delivered
        // would make it look outgoing. Drop the update; the originator owns it.
        if (RcsMessageStore.isIncomingLocalId(db, localId)) {
            LogUtil.i(TAG, "UpdateRcsMessageStatusAction: rcsId=" + rcsMessageId
                    + " resolves to an "
                    + "INCOMING row on this device — a receipt for it belongs to the originator, not "
                    + "to us. Not rewriting a received message as outgoing.");
            return null;
        }

        // The provider sends a group receipt alongside the 1:1 receipt; only a group row acts on
        // it.
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

        if (kind == KIND_STATUS) {
            applyStatus(db, localId, value, value == IRcsProviderCallback.STATUS_SENT,
                    actionParameters.getString(KEY_E2EE_SCHEME_ID),
                    sourceOf(actionParameters.getString(KEY_SOURCE)));
            return null;
        }

        final ContentValues values = new ContentValues();
        String conversationId = null;

        if (kind == KIND_IMDN) {
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

    /**
     * Applies a status parked because it arrived before its row. The insert paths call this right
     * after committing a row carrying {@code rcsMessageId}; a no-op when nothing is parked.
     */
    public static void applyParkedStatus(final DatabaseWrapper db, final String rcsMessageId) {
        if (TextUtils.isEmpty(rcsMessageId)) return;
        final String localId = RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId);
        if (localId == null || RcsMessageStore.isIncomingLocalId(db, localId)) return;
        final RcsEarlyStatusPark.Entry e =
                RcsEarlyStatusPark.get().take(rcsMessageId, System.currentTimeMillis());
        if (e == null) return;
        LogUtil.i(TAG, "UpdateRcsMessageStatusAction: applying parked status " + e.status
                + " to rcsId=" + rcsMessageId);
        applyStatus(db, localId, e.status, e.sent, e.scheme, sourceOf(e.source));
    }

    /** A KIND_STATUS write: the row's status columns, then the scheme observation on SENT. */
    private static void applyStatus(final DatabaseWrapper db, final String localId,
            final int value, final boolean sent, final String scheme,
            final E2eeObservation.Source source) {
        final ContentValues values = new ContentValues();
        values.put(RcsMessageStore.COLUMN_RCS_STATUS, value);
        final int bugleStatus = mapBugleStatus(value);
        if (bugleStatus != MessageData.BUGLE_STATUS_UNKNOWN) {
            values.put(MessageColumns.STATUS, bugleStatus);
        }
        if (value == IRcsProviderCallback.STATUS_DELIVERED) {
            values.put(RcsMessageStore.COLUMN_RCS_DELIVERED_TS, System.currentTimeMillis());
        }
        String conversationId;
        db.beginTransaction();
        try {
            BugleDatabaseOperations.updateMessageRow(db, localId, values);
            conversationId = BugleDatabaseOperations.getConversationIdFromMessageId(db, localId);
            if (sent) {
                E2eeObservationStore.applySentStatus(db, localId, true, scheme, source);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (conversationId != null) {
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            if (sent) {
                MessagingContentProvider.notifyConversationMetadataChanged(conversationId);
            }
        }
    }

    /** A status without a recorded source came from the provider. */
    private static E2eeObservation.Source sourceOf(final String name) {
        return E2eeObservation.Source.CARRIER.name().equals(name)
                ? E2eeObservation.Source.CARRIER : E2eeObservation.Source.PROVIDER;
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
