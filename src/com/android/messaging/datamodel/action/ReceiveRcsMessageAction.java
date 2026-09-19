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
import org.lineageos.rcs.provider.RcsIncomingMessage;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsInboundConfirmation;
import com.android.messaging.rcs.RcsIosTapback;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

/**
 * Stores an inbound RCS message as an ordinary row so the upstream conversation cursor renders
 * it. Follows {@link ReceiveSmsMessageAction} without the telephony insert, then writes the RCS
 * columns in the same transaction. See docs/rcs/architecture.md.
 */
public class ReceiveRcsMessageAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_RCS_MESSAGE_ID = "rcs_message_id";
    private static final String KEY_FROM_URI = "from_uri";
    private static final String KEY_CONTENT_TYPE = "content_type";
    private static final String KEY_BODY = "body";
    private static final String KEY_SERVER_TS_USEC = "server_ts_usec";
    private static final String KEY_WANTS_DELIVERED = "wants_delivered";
    private static final String KEY_WANTS_DISPLAYED = "wants_displayed";
    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_E2EE_SCHEME_ID = "e2ee_scheme_id";

    /**
     * @param msg an inbound message whose {@code contentType} and {@code body} are already the
     *     inner content, with any RCC.16 framing removed by the producer
     */
    public ReceiveRcsMessageAction(final RcsIncomingMessage msg) {
        actionParameters.putInt(KEY_SUB_ID, msg.subId);
        actionParameters.putString(KEY_RCS_MESSAGE_ID, msg.messageId);
        actionParameters.putString(KEY_FROM_URI, msg.fromUri);
        // The producer owns RCC.16 unframing; contentType and body are taken verbatim. Do not call
        // RccMlsBody.parse here: parsing twice is not safe, since a text message that begins with
        // something frame-shaped would be consumed by the second pass.
        actionParameters.putString(KEY_CONTENT_TYPE, msg.contentType);
        actionParameters.putByteArray(KEY_BODY, msg.body);
        actionParameters.putLong(KEY_SERVER_TS_USEC, msg.serverTimestampUsec);
        actionParameters.putBoolean(KEY_WANTS_DELIVERED, msg.wantsDeliveredImdn);
        actionParameters.putBoolean(KEY_WANTS_DISPLAYED, msg.wantsDisplayedImdn);
        actionParameters.putString(KEY_GROUP_ID, msg.groupId);
        actionParameters.putString(KEY_E2EE_SCHEME_ID, msg.e2eeSchemeId);
    }

    /** Confirms {@code ticket} to the provider that delivered this, once it is stored. */
    public ReceiveRcsMessageAction confirming(
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        RcsInboundConfirmation.attach(ticket, actionParameters);
        return this;
    }

    @Override
    protected Object executeAction() {
        // Confirmed after the store has returned: a store that throws confirms nothing,
        // and the provider offers the message again.
        final Object stored = store();
        RcsInboundConfirmation.afterStore(actionParameters, "ReceiveRcsMessageAction");
        return stored;
    }

    private Object store() {
        if (OsUtil.isSecondaryUser()) {
            // Mirror ReceiveSmsMessageAction: only the primary user persists.
            return null;
        }
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final int subId = actionParameters.getInt(KEY_SUB_ID,
                ParticipantData.DEFAULT_SELF_SUB_ID);
        final String rcsMessageId = actionParameters.getString(KEY_RCS_MESSAGE_ID);
        String fromUri = actionParameters.getString(KEY_FROM_URI);
        final String contentType = actionParameters.getString(KEY_CONTENT_TYPE);
        final byte[] body = actionParameters.getByteArray(KEY_BODY);
        final long serverTsUsec = actionParameters.getLong(KEY_SERVER_TS_USEC);
        final boolean wantsDelivered = actionParameters.getBoolean(KEY_WANTS_DELIVERED);
        final String groupId = actionParameters.getString(KEY_GROUP_ID);
        final boolean isGroup = !TextUtils.isEmpty(groupId);

        if (TextUtils.isEmpty(fromUri)) {
            LogUtil.w(TAG, "ReceiveRcsMessageAction: empty sender; using unknown");
            fromUri = ParticipantData.getUnknownSenderDestination();
        }

        final Disposition disp = classify(contentType);

        // Control types (typing, receipts) never become a bubble.
        if (disp == Disposition.DROP) {
            LogUtil.i(TAG, "ReceiveRcsMessageAction: dropping control content-type "
                    + contentType + " (no bubble)");
            return null;
        }

        // A redelivery is not stored again. When the app dies during the provider's call, the
        // provider does not ack and the message arrives a second time. The id is the sender's
        // Message-ID, looked up across conversations as the other inbound paths do. The delivered
        // receipt is repeated: the first delivery may have died before sending it.
        if (!TextUtils.isEmpty(rcsMessageId)
                && RcsMessageStore.findLocalIdByRcsMessageId(db, rcsMessageId) != null) {
            LogUtil.i(TAG, "ReceiveRcsMessageAction: rcsId=" + rcsMessageId
                    + " is already stored; redelivery not inserted");
            if (wantsDelivered) {
                sendDeliveredReceipt(rcsMessageId, fromUri, isGroup ? groupId : null);
            }
            return null;
        }

        // Media bodies are staged to scratch storage outside the transaction; file transfer and
        // location render as text placeholders, never raw XML.
        Uri mediaUri = null;
        String mediaMime = null;
        final String text;
        switch (disp) {
            case MEDIA: {
                // MIME types are case-insensitive (RFC 2045 §5.1) and the renderers compare
                // case-sensitively, so normalise before any use.
                final String mediaType = RccContentDisposition.canonicalType(contentType);
                mediaUri = stageBytesToScratch(context, body, mediaType);
                if (mediaUri != null) {
                    mediaMime = mediaType;
                    text = "";
                } else {
                    // Staging failed: fall back to a text note.
                    LogUtil.w(TAG, "ReceiveRcsMessageAction: media staging failed for "
                            + mediaType + "; text fallback");
                    text = "[" + mediaType + "]";
                }
                break;
            }
            case FT:
                // TODO: download the file-transfer descriptor's data URL and stage it as
                // ReceiveRcsMediaAction does; until then render a placeholder.
                text = ftPlaceholder(body);
                break;
            case LOCATION:
                text = locationText(body);
                break;
            case TEXT:
            default:
                text = (body != null)
                        ? new String(body, java.nio.charset.StandardCharsets.UTF_8) : "";
                break;
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
            // A group message files into the group conversation, not a 1:1 thread with the sender.
            // If the group is new here (the message beat the create event), seed the roster from
            // getGroupInfo; on failure seed the sender alone and flag needs_roster_refill. See
            // docs/rcs/groups.md.
            java.util.List<String> roster = java.util.Collections.singletonList(fromUri);
            String groupName = null;
            boolean rosterIncomplete = false;
            if (BugleDatabaseOperations.getExistingGroupConversation(db, groupId) == null) {
                rosterIncomplete = true;
                try {
                    final org.lineageos.rcs.provider.RcsGroupInfo gi =
                            com.android.messaging.rcs.ProviderTransport.getInstance(context)
                                    .getGroupInfo(subId, groupId);
                    if (gi != null && gi.members != null && !gi.members.isEmpty()) {
                        roster = gi.members;
                        groupName = gi.name;
                        rosterIncomplete = false;
                        LogUtil.i(TAG, "ReceiveRcsMessageAction: roster-fill groupId=" + groupId
                                + " members=" + gi.members.size());
                    } else {
                        LogUtil.w(TAG, "ReceiveRcsMessageAction: getGroupInfo empty; "
                                + "deferring roster-refill for groupId=" + groupId);
                    }
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "ReceiveRcsMessageAction: getGroupInfo roster-fill failed; "
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

        // A tapback sent as quoted text is applied as a reaction to the unique quoted target in
        // this conversation. With no unique target it stays an ordinary message. Only text can be a
        // tapback.
        final RcsIosTapback.Parsed tapback =
                disp == Disposition.TEXT ? RcsIosTapback.parse(text) : null;
        if (tapback != null) {
            final String targetRcsId = RcsMessageStore.findReactionTargetByQuotedText(
                    db, conversationId, tapback.quotedText, RcsIosTapback.SEARCH_LIMIT);
            if (!TextUtils.isEmpty(targetRcsId)) {
                final String reactorUri = rawSender.getNormalizedDestination();
                final UpdateRcsReactionAction reaction = new UpdateRcsReactionAction(targetRcsId,
                        reactorUri, tapback.emoji, tapback.add, received);
                // The reaction's store is this message's: it confirms once it has stored.
                RcsInboundConfirmation.moveTo(actionParameters, reaction.actionParameters);
                reaction.start();
                LogUtil.i(TAG, "ReceiveRcsMessageAction: iOS tapback "
                        + (tapback.add ? "add" : "remove") + " -> target " + targetRcsId);
                return null;  // not inserted as a message
            }
            LogUtil.i(TAG, "ReceiveRcsMessageAction: iOS tapback with no unique target;"
                    + " keeping as a normal text message");
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

            if (mediaUri != null) {
                // Inline MLS media carries no caption.
                message = MessageData.createReceivedRcsMediaMessage(conversationId, participantId,
                        selfId, mediaMime, mediaUri, /*caption=*/ null, sent, received, seen, read);
            } else {
                message = MessageData.createReceivedRcsMessage(conversationId, participantId,
                        selfId, text, sent, received, seen, read);
            }
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            // The E2EE scheme id drives the per-message padlock.
            final android.content.ContentValues rcsValues =
                    RcsMessageStore.rcsMetaValues(rcsMessageId,
                            IRcsProviderCallback.STATUS_DELIVERED);
            final String e2eeSchemeId = actionParameters.getString(KEY_E2EE_SCHEME_ID);
            if (!android.text.TextUtils.isEmpty(e2eeSchemeId)) {
                rcsValues.put(com.android.messaging.datamodel.DatabaseHelper
                        .MessageColumns.RCS_E2EE_SCHEME_ID, e2eeSchemeId);
            }
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), rcsValues);

            // Latch the conversation's encryption bits on receive as well as on send. A
            // provider-layer tag latches only a 1:1: in a group it says one member encrypted.
            com.android.messaging.rcs.e2ee.E2eeObservationStore.applyInbound(
                    db, conversationId, e2eeSchemeId, isGroup);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), received, blocked,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "ReceiveRcsMessageAction: stored RCS message " + message.getMessageId()
                + " (rcsId=" + rcsMessageId + ") in conversation " + conversationId);

        // Delivered receipt now if asked; the displayed receipt follows when the user opens the
        // thread.
        if (wantsDelivered && !TextUtils.isEmpty(rcsMessageId)) {
            sendDeliveredReceipt(rcsMessageId, fromUri, isGroup ? groupId : null);
        }

        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
    }

    /** The group id selects which MLS group state stamps the receipt; null uses the 1:1. */
    private static void sendDeliveredReceipt(final String rcsMessageId, final String fromUri,
            @Nullable final String groupId) {
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport != null) {
            transport.sendImdn(rcsMessageId, fromUri, IRcsProviderCallback.IMDN_DELIVERED,
                    groupId);
        }
    }

    /** How a decoded (contentType, body) pair should be surfaced. */
    private enum Disposition { TEXT, MEDIA, FT, LOCATION, DROP }

    /**
     * Maps the content type to a disposition via {@link RccContentDisposition}. Unknown types are
     * dropped, never rendered as text.
     */
    private static Disposition classify(final String contentType) {
        final int d = RccContentDisposition.classify(contentType);
        switch (d) {
            case RccContentDisposition.MEDIA:    return Disposition.MEDIA;
            case RccContentDisposition.FT:       return Disposition.FT;
            case RccContentDisposition.LOCATION: return Disposition.LOCATION;
            case RccContentDisposition.TEXT:     return Disposition.TEXT;
            default:
                LogUtil.i(TAG, "ReceiveRcsMessageAction: dropping contentType=" + contentType
                        + " (" + RccContentDisposition.name(d) + ") — not rendered as a message");
                return Disposition.DROP;
        }
    }

    /**
     * Writes media bytes to {@link MediaScratchFileProvider} storage and returns its content URI,
     * or null on failure. Runs under this app's uid, so no calling identity needs clearing.
     */
    private static Uri stageBytesToScratch(final Context ctx, final byte[] bytes,
            final String mime) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri(extensionFor(mime));
        try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                return null;
            }
            out.write(bytes);
            return uri;
        } catch (final Exception e) {
            LogUtil.w(TAG, "ReceiveRcsMessageAction: stage media to scratch failed", e);
            return null;
        }
    }

    /** Best-effort file extension for a MIME (MediaScratch keys files by it). */
    private static String extensionFor(final String mime) {
        final String ext = TextUtils.isEmpty(mime) ? null
                : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return TextUtils.isEmpty(ext) ? "dat" : ext;
    }

    /**
     * A file-transfer descriptor rendered as a placeholder (file name and size), never raw XML. Any
     * parse failure degrades to a generic label.
     */
    private static String ftPlaceholder(final byte[] body) {
        String name = null;
        String size = null;
        try {
            final String xml = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            final java.util.regex.Matcher mn = java.util.regex.Pattern.compile(
                    "<file-name>\\s*([^<]+?)\\s*</file-name>",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
            if (mn.find()) {
                name = mn.group(1).trim();
            }
            final java.util.regex.Matcher ms = java.util.regex.Pattern.compile(
                    "<file-size>\\s*([0-9]+)\\s*</file-size>",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
            if (ms.find()) {
                size = humanSize(Long.parseLong(ms.group(1)));
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ReceiveRcsMessageAction: FT descriptor parse failed", t);
        }
        final StringBuilder sb = new StringBuilder("📎 ");
        sb.append(!TextUtils.isEmpty(name) ? name : "Attachment");
        if (!TextUtils.isEmpty(size)) {
            sb.append(" · ").append(size);
        }
        return sb.toString();
    }

    /** A PIDF-LO location body rendered as a maps link; a malformed body gives a generic label. */
    private static String locationText(final byte[] body) {
        try {
            final String xml = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            // PIDF-LO carries the point as "<gml:pos>LAT LON</gml:pos>".
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<[^>]*pos[^>]*>\\s*([-+0-9.]+)\\s+([-+0-9.]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
            if (m.find()) {
                final double lat = Double.parseDouble(m.group(1));
                final double lon = Double.parseDouble(m.group(2));
                return "📍 Shared location\n"
                        + String.format(java.util.Locale.US,
                                "https://maps.google.com/?q=%.6f,%.6f", lat, lon);
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ReceiveRcsMessageAction: location parse failed", t);
        }
        return "📍 Shared location";
    }

    /** Compact human byte size (e.g. "1.2 MB") for the FT placeholder. */
    private static String humanSize(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private ReceiveRcsMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ReceiveRcsMessageAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ReceiveRcsMessageAction createFromParcel(final Parcel in) {
            return new ReceiveRcsMessageAction(in);
        }

        @Override
        public ReceiveRcsMessageAction[] newArray(final int size) {
            return new ReceiveRcsMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
