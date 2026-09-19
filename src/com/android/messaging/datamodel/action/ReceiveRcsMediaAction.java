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
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsIncomingFile;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

/**
 * "Receive" an inbound RCS MEDIA (FT-HTTP attachment) and land it in
 * messaging.db so the existing conversation cursor renders it as a normal
 * attachment bubble through the unchanged MMS render path
 * (AttachmentPreviewFactory / MultiAttachmentLayout / VideoThumbnailView /
 * AudioAttachmentView).
 *
 * <p>Cloned from {@link ReceiveRcsMessageAction}: it resolves sender +
 * conversation IDENTICALLY (getFromRawPhoneBySimLocale / getOrCreateThreadId /
 * getOrCreateConversationFromRecipient, and the same {@code isGroup}
 * roster-fill branch when a groupId is present), runs the same RCS-column tag
 * + IMDN + notify tail, but builds the message via
 * {@link MessageData#createReceivedRcsMediaMessage} (a MEDIA part instead of a
 * text part; caption added as a sibling text part when present).
 *
 * <p><b>ZERO transport branching</b> (Phase-5 acceptance crit 4): the
 * {@link RcsIncomingFile} descriptor already carries a RESOLVED
 * {@code content://} URI of a blob the PROVIDER downloaded internally, plus its
 * real MIME / filename / size / caption / eager-thumbnail URI. This Action only
 * sees a content URI + MIME — it never looks at a copper / googleapis URL, an
 * {@code Authorization}/{@code Bearer} token, or any {@code rcs-ft-http+xml}.
 *
 * <p><b>FD vs URI across the Action parcel.</b> We persist the content URI
 * <em>string</em> (durable: the provider granted our package a READ permission
 * on its FileProvider URI). We deliberately do NOT parcel the descriptor's
 * {@link android.os.ParcelFileDescriptor} into {@code actionParameters} — an
 * Action is itself queued/parceled and an FD would dup/leak. The provider must
 * therefore expose a grantable FileProvider content URI (the same shape Google
 * Messages uses after a download); if only an FD is available there is
 * nothing durable to store.
 *
 * <p>The {@link RcsIncomingFile#contentUri file URI} may be {@code null} (a
 * thumbnail-only push awaiting user ACCEPT, brief §4e); this Action tolerates
 * that by rendering the thumbnail URI as the media part when the file URI is not
 * yet present, and a later {@code onIncomingMedia} (after accept) supplies the
 * resolved file. If both are null there is nothing to render and we drop.
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

    public ReceiveRcsMediaAction(final RcsIncomingFile file) {
        this(file, null, null);
    }

    /**
     * @param resolvedFileUri   a DURABLE content URI for the file blob that the
     *                          caller already staged into our own storage (e.g.
     *                          MediaScratch, from the provider's one-shot FD) —
     *                          overrides {@link RcsIncomingFile#contentUri} when
     *                          non-null. The provider may hand only an FD (no
     *                          grantable URI); the FD cannot be parceled across
     *                          the Action queue, so the caller ingests it first.
     * @param resolvedThumbUri  same, for the thumbnail (may be null).
     */
    public ReceiveRcsMediaAction(final RcsIncomingFile file,
            final String resolvedFileUri, final String resolvedThumbUri) {
        actionParameters.putInt(KEY_SUB_ID, file.subId);
        actionParameters.putString(KEY_RCS_MESSAGE_ID, file.messageId);
        actionParameters.putString(KEY_FROM_URI, file.fromUri);
        // Persist the content URI STRING (durable). Prefer the caller-resolved
        // durable URI (FD ingested into our store) over the provider's contentUri
        // (which may be null). The FD is intentionally NOT parceled (it would
        // dup/leak across the Action queue — see class javadoc).
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
    }

    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            // Mirror ReceiveRcsMessageAction: only the primary user persists.
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

        // Render whichever resolved URI we have: the file when downloaded, else
        // the eager thumbnail (thumbnail-only push preview, brief §4e). If
        // neither is present there is nothing resolved to render — drop.
        final String renderUriStr;
        final String renderMime;
        if (!TextUtils.isEmpty(fileUriStr)) {
            renderUriStr = fileUriStr;
            renderMime = mimeType;
        } else if (!TextUtils.isEmpty(thumbUriStr)) {
            renderUriStr = thumbUriStr;
            renderMime = !TextUtils.isEmpty(thumbMime) ? thumbMime : mimeType;
        } else {
            LogUtil.w(TAG, "ReceiveRcsMediaAction: no resolved content URI (file or "
                    + "thumbnail); dropping rcsId=" + rcsMessageId);
            return null;
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

        final ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(fromUri, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());

        final String conversationId;
        if (isGroup) {
            // FLOW4b parity: a fanned-out group-FT message files into the GROUP
            // conversation keyed by groupId (carried forward-compat; group FT is
            // NOT exercised in 1:1 scope). Roster-fill identical to
            // ReceiveRcsMessageAction.
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

        // The FILE downloads only on user ACCEPT. The first delivery
        // carries the eager THUMBNAIL (renderUri = thumb, fileUriStr empty) -> mark
        // the row RCS_FILE_PENDING so a tap triggers acceptIncomingFile. The second
        // delivery (after accept) carries the FILE -> update the SAME row's part URI
        // in place (no duplicate) + flip the status to DELIVERED.
        final boolean fileDownloaded = !TextUtils.isEmpty(fileUriStr);
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
                // RE-DELIVERY (file resolved after accept): swap the media part's URI
                // in place; do NOT insert a duplicate row.
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
                        RcsMessageStore.rcsMetaValues(rcsMessageId, rcsStatus));
            } else {
                wasInsert = true;
                final String participantId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, rawSender);
                final String selfId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);

                message = MessageData.createReceivedRcsMediaMessage(conversationId, participantId,
                        selfId, renderMime, renderUri, caption, sent, received, seen, read);
                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                // Same column-agnostic RCS-metadata write as the text path.
                BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                        RcsMessageStore.rcsMetaValues(rcsMessageId, rcsStatus));
            }

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

        // Delivered-IMDN only on the FIRST (insert) delivery — the re-delivery of
        // the accepted file must not re-fire it.
        if (wasInsert && wantsDelivered && !TextUtils.isEmpty(rcsMessageId)) {
            final ProviderTransport transport = ProviderTransport.peekInstance();
            if (transport != null) {
                // Same group binding as the text path — a media receipt is stamped from the same
                // MLS conversation and mis-binds the same way without it.
                transport.sendImdn(rcsMessageId, fromUri, IRcsProviderCallback.IMDN_DELIVERED,
                        isGroup ? groupId : null);
            }
        }

        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
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
