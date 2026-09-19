/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsIncomingFile;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsFileAttachment;
import com.android.messaging.rcs.RcsInboundConfirmation;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.e2ee.E2eeObservationStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

import java.util.Random;

/**
 * Stores an inbound RCS file as an ordinary attachment row, so the upstream MMS attachment views
 * render it. Resolves the sender and conversation as {@link ReceiveRcsMessageAction} does.
 *
 * <p>The action stores a content URI string, never a file descriptor: an action is parcelled on
 * the queue and a descriptor would be duplicated or leaked. A file the provider has not downloaded
 * is delivered twice: first with a thumbnail, or with no handle at all (a file over the provider's
 * download limit), stored as {@link RcsConstants#RCS_FILE_PENDING}; then after the user accepts,
 * when the same row's part is repointed at the file. The part URI carries the file's name and size
 * ({@link RcsFileAttachment}).
 */
public class ReceiveRcsMediaAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_FROM_URI = "from_uri";
    private static final String KEY_CONTENT_URI = "content_uri";
    private static final String KEY_MIME_TYPE = "mime_type";
    private static final String KEY_FILE_NAME = "file_name";
    private static final String KEY_SIZE = "size";
    private static final String KEY_CAPTION = "caption";
    private static final String KEY_CONTENT_URI_THUMBNAIL = "content_uri_thumbnail";
    private static final String KEY_MIME_TYPE_THUMBNAIL = "mime_type_thumbnail";
    private static final String KEY_SERVER_TS_USEC = "server_ts_usec";
    private static final String KEY_WANTS_DELIVERED = "wants_delivered";
    private static final String KEY_WANTS_DISPLAYED = "wants_displayed";
    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_E2EE_SCHEME_ID = "e2ee_scheme_id";

    public ReceiveRcsMediaAction(final RcsIncomingFile file) {
        this(file, null, null);
    }

    /**
     * @param resolvedFileUri a durable content URI for a file the caller has already copied into
     *     this app's storage; overrides {@link RcsIncomingFile#contentUri} when non-null
     * @param resolvedThumbUri the same for the thumbnail; may be null
     */
    public ReceiveRcsMediaAction(final RcsIncomingFile file,
            final String resolvedFileUri, final String resolvedThumbUri) {
        actionParameters.putInt(KEY_SUB_ID, file.subId);
        actionParameters.putString(KEY_RCS_MESSAGE_ID, file.messageId);
        actionParameters.putString(KEY_FROM_URI, file.fromUri);
        actionParameters.putString(KEY_CONTENT_URI,
                !TextUtils.isEmpty(resolvedFileUri) ? resolvedFileUri : file.contentUri);
        actionParameters.putString(KEY_MIME_TYPE, file.mimeType);
        actionParameters.putString(KEY_FILE_NAME, file.fileName);
        actionParameters.putLong(KEY_SIZE, file.size);
        actionParameters.putString(KEY_CAPTION, file.caption);
        actionParameters.putString(KEY_CONTENT_URI_THUMBNAIL,
                !TextUtils.isEmpty(resolvedThumbUri) ? resolvedThumbUri
                        : file.contentUriThumbnail);
        actionParameters.putString(KEY_MIME_TYPE_THUMBNAIL, file.mimeTypeThumbnail);
        actionParameters.putLong(KEY_SERVER_TS_USEC, file.serverTimestampUsec);
        actionParameters.putBoolean(KEY_WANTS_DELIVERED, file.wantsDeliveredImdn);
        actionParameters.putBoolean(KEY_WANTS_DISPLAYED, file.wantsDisplayedImdn);
        actionParameters.putString(KEY_GROUP_ID, file.groupId);
        actionParameters.putString(KEY_E2EE_SCHEME_ID, file.e2eeSchemeId);
    }

    /** Confirms {@code ticket} to the provider that delivered this, once it is stored. */
    public ReceiveRcsMediaAction confirming(@Nullable final RcsInboundConfirmation.Ticket ticket) {
        RcsInboundConfirmation.attach(ticket, actionParameters);
        return this;
    }

    @Override
    protected Object executeAction() {
        // Confirmed after the store has returned: a store that throws confirms nothing,
        // and the provider offers the message again.
        final Object stored = store();
        RcsInboundConfirmation.afterStore(actionParameters, "ReceiveRcsMediaAction");
        return stored;
    }

    private Object store() {
        if (OsUtil.isSecondaryUser()) {
            // Only the primary user persists.
            return null;
        }
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final int subId = actionParameters.getInt(KEY_SUB_ID,
                ParticipantData.DEFAULT_SELF_SUB_ID);
        final String rcsMessageId = actionParameters.getString(KEY_RCS_MESSAGE_ID);
        String fromUri = actionParameters.getString(KEY_FROM_URI);
        final String fileUriStr = actionParameters.getString(KEY_CONTENT_URI);
        final String mimeType = actionParameters.getString(KEY_MIME_TYPE);
        final String caption = actionParameters.getString(KEY_CAPTION);
        final String thumbUriStr = actionParameters.getString(KEY_CONTENT_URI_THUMBNAIL);
        final String thumbMime = actionParameters.getString(KEY_MIME_TYPE_THUMBNAIL);
        final long serverTsUsec = actionParameters.getLong(KEY_SERVER_TS_USEC);
        final boolean wantsDelivered = actionParameters.getBoolean(KEY_WANTS_DELIVERED);
        final String groupId = actionParameters.getString(KEY_GROUP_ID);
        final boolean isGroup = !TextUtils.isEmpty(groupId);
        final String e2eeSchemeId = actionParameters.getString(KEY_E2EE_SCHEME_ID);

        final String fileName = actionParameters.getString(KEY_FILE_NAME);
        final long size = actionParameters.getLong(KEY_SIZE, -1);

        // The file when downloaded, else the thumbnail, else a placeholder: a file with no handle
        // is kept as a pending row the user can accept, never dropped (the provider has acked it
        // upstream once this is confirmed).
        final RcsFileAttachment.Stored storedAs =
                RcsFileAttachment.storedAs(fileUriStr, thumbUriStr);
        if (storedAs != RcsFileAttachment.Stored.FILE && hasItsFile(db, rcsMessageId)) {
            // A pending copy offered again after the file arrived: keep the file.
            LogUtil.i(TAG, "ReceiveRcsMediaAction: rcsId=" + rcsMessageId
                    + " already has its file; not repointed");
            return null;
        }
        final String renderUriStr;
        final String renderMime;
        if (storedAs == RcsFileAttachment.Stored.FILE) {
            renderUriStr = RcsFileAttachment.describe(fileUriStr, fileName, size);
            renderMime = mimeType;
        } else if (storedAs == RcsFileAttachment.Stored.THUMBNAIL) {
            renderUriStr = thumbUriStr;
            renderMime = !TextUtils.isEmpty(thumbMime) ? thumbMime : mimeType;
        } else {
            renderUriStr = RcsFileAttachment.placeholder(MediaScratchFileProvider.getUriBuilder()
                    .appendPath(Long.toString(Math.abs(new Random().nextLong())))
                    .build().toString(), fileName, size);
            renderMime = !TextUtils.isEmpty(mimeType) ? mimeType : "application/octet-stream";
            LogUtil.i(TAG, "ReceiveRcsMediaAction: rcsId=" + rcsMessageId + " has no file or "
                    + "thumbnail handle (size=" + size + "); stored pending until accepted");
        }
        final Uri renderUri = Uri.parse(renderUriStr);

        if (TextUtils.isEmpty(fromUri)) {
            LogUtil.w(TAG, "ReceiveRcsMediaAction: empty sender; using unknown");
            fromUri = ParticipantData.getUnknownSenderDestination();
        }

        final long received = System.currentTimeMillis();
        final long sent = serverTsUsec > 0 ? serverTsUsec / 1000L : received;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(received);

        final ParticipantData rawSender =
                ParticipantData.getFromRawPhoneBySimLocale(fromUri, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());

        final String conversationId;
        if (isGroup) {
            // Group file: same conversation resolution and roster fill as ReceiveRcsMessageAction.
            java.util.List<String> roster = java.util.Collections.singletonList(fromUri);
            String groupName = null;
            boolean rosterIncomplete = false;
            if (BugleDatabaseOperations.getExistingGroupConversation(db, groupId) == null) {
                rosterIncomplete = true;
                try {
                    final org.lineageos.rcs.provider.RcsGroupInfo gi =
                            ProviderTransport.getInstance(context).getGroupInfo(subId, groupId);
                    if (gi != null && gi.members != null && !gi.members.isEmpty()) {
                        roster = gi.members;
                        groupName = gi.name;
                        rosterIncomplete = false;
                    } else {
                        LogUtil.w(TAG, "ReceiveRcsMediaAction: getGroupInfo empty; "
                                + "deferring roster-refill for groupId=" + groupId);
                    }
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "ReceiveRcsMediaAction: getGroupInfo roster-fill failed; "
                            + "deferring roster-refill", t);
                }
            }
            conversationId = BugleDatabaseOperations.getOrCreateGroupConversation(
                    db, groupId, groupName, subId, roster, rosterIncomplete);
        } else {
            final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(context, fromUri);
            conversationId = BugleDatabaseOperations
                    .getOrCreateConversationFromRecipient(db, threadId, blocked, rawSender);
        }

        final boolean inFocused = DataModel.get().isFocusedConversation(conversationId);
        final boolean inObservable = DataModel.get().isNewMessageObservable(conversationId);
        final boolean read = inFocused;
        final boolean seen = read || inObservable || blocked;

        final ParticipantData self = ParticipantData.getSelfParticipant(subId);

        // A thumbnail-only delivery marks the row pending so a tap accepts the file; the delivery
        // after accept updates the same row in place.
        final boolean fileDownloaded = storedAs == RcsFileAttachment.Stored.FILE;
        final int rcsStatus = fileDownloaded
                ? IRcsProviderCallback.STATUS_DELIVERED
                : RcsConstants.RCS_FILE_PENDING;

        MessageData message;
        boolean wasInsert;
        db.beginTransaction();
        try {
            final String existingId =
                    RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId);
            if (existingId != null
                    && (message = BugleDatabaseOperations.readMessage(db, existingId)) != null) {
                // Second delivery: repoint the attachment part; no new row.
                wasInsert = false;
                for (final MessagePartData part : message.getParts()) {
                    if (part.isAttachment()) {
                        final ContentValues pv = new ContentValues();
                        pv.put(DatabaseHelper.PartColumns.CONTENT_URI, renderUriStr);
                        pv.put(DatabaseHelper.PartColumns.CONTENT_TYPE, renderMime);
                        BugleDatabaseOperations.updatePartRowIfExists(db, part.getPartId(), pv);
                        break;
                    }
                }
                BugleDatabaseOperations.updateMessageRow(db, existingId,
                        rcsValues(rcsMessageId, rcsStatus, e2eeSchemeId));
            } else {
                wasInsert = true;
                final String participantId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, rawSender);
                final String selfId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);

                message = MessageData.createReceivedRcsMediaMessage(conversationId, participantId,
                        selfId, renderMime, renderUri, caption, sent, received, seen, read);
                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                        rcsValues(rcsMessageId, rcsStatus, e2eeSchemeId));
            }

            E2eeObservationStore.applyInbound(db, conversationId, e2eeSchemeId, isGroup);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), received, blocked,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "ReceiveRcsMediaAction: " + (wasInsert ? "stored" : "updated")
                + " RCS media message " + message.getMessageId()
                + " (rcsId=" + rcsMessageId + ", mime=" + renderMime
                + ", fileDownloaded=" + fileDownloaded + ") in conversation " + conversationId);

        // Delivered receipt on the first delivery only.
        if (wasInsert && wantsDelivered && !TextUtils.isEmpty(rcsMessageId)) {
            final ProviderTransport transport = ProviderTransport.peekInstance();
            if (transport != null) {
                transport.sendImdn(rcsMessageId, fromUri, IRcsProviderCallback.IMDN_DELIVERED,
                        isGroup ? groupId : null);
            }
        }

        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
    }

    /**
     * Whether a row for {@code rcsMessageId} exists and has its file. A row marked unavailable has
     * none: a pending copy offered again means the provider holds the file once more.
     */
    private static boolean hasItsFile(final DatabaseWrapper db, final String rcsMessageId) {
        final String localId = RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId);
        final RcsMessageStore.RcsMeta meta =
                localId == null ? null : RcsMessageStore.readByLocalId(db, localId);
        return meta != null && !RcsFileAttachment.lacksFile(meta.rcsStatus);
    }

    /** The RCS metadata columns, plus the scheme the file was decrypted under when it was. */
    private static ContentValues rcsValues(final String rcsMessageId, final int rcsStatus,
            final String e2eeSchemeId) {
        final ContentValues v = RcsMessageStore.rcsMetaValues(rcsMessageId, rcsStatus);
        if (!TextUtils.isEmpty(e2eeSchemeId)) {
            v.put(DatabaseHelper.MessageColumns.RCS_E2EE_SCHEME_ID, e2eeSchemeId);
        }
        return v;
    }

    private ReceiveRcsMediaAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ReceiveRcsMediaAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ReceiveRcsMediaAction createFromParcel(final Parcel in) {
            return new ReceiveRcsMediaAction(in);
        }

        @Override
        public ReceiveRcsMediaAction[] newArray(final int size) {
            return new ReceiveRcsMediaAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
