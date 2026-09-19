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
import com.android.messaging.rcs.RcsIosTapback;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

/**
 * "Receive" an inbound RCS message and land it in messaging.db so the
 * existing conversation cursor renders it as a normal bubble.
 *
 * <p>Cloned from {@link ReceiveSmsMessageAction} (60-178) with the telephony
 * insert removed (RCS is not SMS, so no {@code Sms.Inbox.CONTENT_URI} write)
 * and the message built via {@link MessageData#createReceivedRcsMessage}.
 * After the base insert the RCS columns are written with a second
 * {@code updateMessageRow} inside the same transaction.
 *
 * <p>Flow: resolve sender + conversation the SAME way SMS does
 * (getFromRawPhoneBySimLocale + getOrCreateThreadId +
 * getOrCreateConversationFromRecipient) so RCS lands in the unified thread,
 * insert, tag RCS columns, update conversation metadata, fire the delivered
 * IMDN if requested, notify the content provider.
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
     * @param msg an inbound message whose {@code contentType} and {@code body} are ALREADY the real
     *        inner content — see the ownership rule in the body of this constructor.
     */
    public ReceiveRcsMessageAction(final RcsIncomingMessage msg) {
        actionParameters.putInt(KEY_SUB_ID, msg.subId);
        actionParameters.putString(KEY_RCS_MESSAGE_ID, msg.messageId);
        actionParameters.putString(KEY_FROM_URI, msg.fromUri);
        // WHOEVER PRODUCES AN RcsIncomingMessage OWNS RCC.16 UNFRAMING. contentType and
        // body are taken VERBATIM here — by the time a message reaches this Action they are the real
        // inner type and content, for MLS exactly as for plaintext. Do NOT call RccMlsBody.parse.
        //
        // This used to re-parse whenever e2eeSchemeId contained "mls", on a body the MLS producer
        // had ALREADY unframed. RccMlsBody.parse returns a payload with no MIME frame verbatim as
        // text/plain (its no-frame fallback), so on unframed TEXT the second parse was a no-op and
        // hid the defect for every message we have ever sent. An already-unframed IMAGE has no frame
        // either: the second parse handed back text/plain over raw image bytes, the producer's
        // image/jpeg was discarded, and the picture rendered as a bubble of binary.
        //
        // The PRODUCER is the owner because it is the only layer that can be. An MLS producer has to
        // know the inner type before it knows whether there is a message here at all — an IMDN is a
        // receipt, a §7.8.1 FileInfo is a key, a reaction attaches to an existing row — so it
        // unframes as part of routing, and MlsProviderTransport.decryptInbound already hands the
        // router an RccMlsBody.Parsed rather than bytes. "The Action is the only unframer" was never
        // actually available: it would have left the producer parsing for routing and this layer
        // parsing again for content, two derivations of one fact.
        //
        // And parse must NOT be made safe to run twice. A legitimate text/plain message whose text
        // happens to begin with something frame-shaped would be eaten by the second pass, so
        // "parsing twice is harmless" cannot be made true — it can only be made to look true for
        // the one content type (text) that hid this bug in the first place.
        actionParameters.putString(KEY_CONTENT_TYPE, msg.contentType);
        actionParameters.putByteArray(KEY_BODY, msg.body);
        actionParameters.putLong(KEY_SERVER_TS_USEC, msg.serverTimestampUsec);
        actionParameters.putBoolean(KEY_WANTS_DELIVERED, msg.wantsDeliveredImdn);
        actionParameters.putBoolean(KEY_WANTS_DISPLAYED, msg.wantsDisplayedImdn);
        actionParameters.putString(KEY_GROUP_ID, msg.groupId);
        actionParameters.putString(KEY_E2EE_SCHEME_ID, msg.e2eeSchemeId);
    }

    @Override
    protected Object executeAction() {
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

        // Route by the content-type instead of stringifying every body. Per the
        // ownership rule in the constructor, `contentType` + `body` arrived here
        // already unframed by the producer — the real inner type/content for an
        // MLS message, and the content itself for plaintext / Etouffee.
        final Disposition disp = classify(contentType);

        // CONTROL types must never surface as a bubble — typing indicators and
        // delivery/read receipts are handled provider-side; defensively drop here
        // so a stray XML body never renders. (Matches the provider's own
        // handleIncomingComposing / handleIncomingImdn no-bubble handling.)
        if (disp == Disposition.DROP) {
            LogUtil.i(TAG, "ReceiveRcsMessageAction: dropping control content-type "
                    + contentType + " (no bubble)");
            return null;
        }

        // Compute the render form: a MEDIA attachment part (image/video/audio —
        // the body bytes ARE the media), or a text bubble (text/plain, plus FT and
        // location rendered as a viable text placeholder — never raw XML). Media
        // staging happens off the DB transaction (it does file I/O).
        Uri mediaUri = null;
        String mediaMime = null;
        final String text;
        switch (disp) {
            case MEDIA: {
                // NORMALISE ONCE, AT THE TOP OF THE ARM.
                //
                // MIME types are case-insensitive (RFC 2045 §5.1), so a conforming peer may send
                // IMAGE/JPEG, and inbound casing is the PEER'S choice — the shape neither copy of
                // the fact controls. classify() lower-cases, so the message ROUTED here correctly;
                // what it then handed on was the peer's bytes verbatim, and every renderer tests
                // them case-sensitively (ContentType.isImageType is startsWith("image/")). So the
                // message routed as media, the bytes staged correctly, and the attachment rendered
                // as NOTHING — with ConversationMessageView's Assert.isTrue logging and continuing,
                // because proguard strips setIfEngBuild on userdebug. The quiet kind.
                //
                // AT THE TOP AND NOT AT THE mediaMime ASSIGNMENT, which is where the narrow fix was
                // first written: the arm reads the type TWICE and the first read is the staging
                // call below. That consumer is benign today — MimeTypeMap.getExtensionFromMimeType
                // delegates to libcore's MimeMap, which lower-cases before the lookup, so
                // extensionFor("IMAGE/JPEG") really does return jpg — but that is AN EXTERNAL
                // CONTRACT WE DO NOT OWN, and the consumer list here will grow. Normalising at the
                // top depends on neither.
                final String mediaType = RccContentDisposition.canonicalType(contentType);
                mediaUri = stageBytesToScratch(context, body, mediaType);
                if (mediaUri != null) {
                    mediaMime = mediaType;
                    text = "";
                } else {
                    // Staging failed: don't crash — fall back to a non-XML note.
                    LogUtil.w(TAG, "ReceiveRcsMessageAction: media staging failed for "
                            + mediaType + "; text fallback");
                    text = "[" + mediaType + "]";
                }
                break;
            }
            case FT:
                // Full HTTP download of the ft-http descriptor is a follow-up
                // (TODO: fetch <data url=…> + stage like ReceiveRcsMediaAction);
                // for now render a downloadable-looking placeholder, not raw XML.
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

        final ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(fromUri, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());

        final String conversationId;
        if (isGroup) {
            // FLOW4b: a fanned-out group message. File it into the GROUP
            // conversation keyed by groupId (NOT a 1:1 thread keyed by sender),
            // reusing the same multi-participant model group MMS uses.
            //
            // Roster-fill: if we have NEVER seen this group (a content message
            // arrived before the CREATE/ADD event, e.g. messaging2 wasn't bound
            // when the event fired), seed the conversation with the FULL server
            // roster via getGroupInfo so it isn't a degenerate sender-only
            // (participant_count=1) conversation. Off-main here, so the blocking
            // binder call is fine. Falls back to just the sender if unavailable.
            java.util.List<String> roster = java.util.Collections.singletonList(fromUri);
            String groupName = null;
            // A2.6: when the conversation does NOT yet exist we must seed the full
            // roster; if getGroupInfo is unavailable we fall back to just the
            // sender and FLAG the conversation needs_roster_refill so a later
            // provider bind / inbound self-heals the full membership + count.
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

        // Inbound iOS-tapback interop: an iPhone reacting over RCS sends a text
        // like Disliked “<quoted message>” (NOT a structured reaction). Detect it,
        // resolve the quoted target in this conversation, and apply it as a
        // reaction (the same UpdateRcsReactionAction the provider's
        // onIncomingReaction uses) instead of inserting a text bubble. Mirrors
        // Google Messages' own classifier + quoted-text target match. On no unique
        // target we fall through and keep it as an ordinary message (nothing lost).
        // Only Google Messages text/plain can be an iOS tapback — media/FT/location render
        // forms must never be reinterpreted as a reaction.
        final RcsIosTapback.Parsed tapback =
                disp == Disposition.TEXT ? RcsIosTapback.parse(text) : null;
        if (tapback != null) {
            final String targetRcsId = RcsMessageStore.findReactionTargetByQuotedText(
                    db, conversationId, tapback.quotedText, RcsIosTapback.SEARCH_LIMIT);
            if (!TextUtils.isEmpty(targetRcsId)) {
                final String reactorUri = rawSender.getNormalizedDestination();
                new UpdateRcsReactionAction(targetRcsId, reactorUri, tapback.emoji,
                        tapback.add, received).start();
                LogUtil.i(TAG, "ReceiveRcsMessageAction: iOS tapback "
                        + (tapback.add ? "add" : "remove") + " " + tapback.emoji
                        + " -> target " + targetRcsId + " from " + reactorUri);
                return null;  // do NOT insert the tapback as a normal message
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
                // Inline media over MLS: the decoded body bytes were staged to a
                // MediaScratch content URI above; land a MEDIA part so the existing
                // MMS attachment renderer displays it (no caption sibling — MLS
                // inline media carries no caption).
                message = MessageData.createReceivedRcsMediaMessage(conversationId, participantId,
                        selfId, mediaMime, mediaUri, /*caption=*/ null, sent, received, seen, read);
            } else {
                message = MessageData.createReceivedRcsMessage(conversationId, participantId, selfId,
                        text, sent, received, seen, read);
            }
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            // Second, column-agnostic write of the RCS metadata (does NOT touch
            // MessageData's positional insert). Carry the opaque E2EE scheme id so the
            // bubble can render a per-message lock indicator.
            final android.content.ContentValues rcsValues =
                    RcsMessageStore.rcsMetaValues(rcsMessageId,
                            IRcsProviderCallback.STATUS_DELIVERED);
            final String e2eeSchemeId = actionParameters.getString(KEY_E2EE_SCHEME_ID);
            if (!android.text.TextUtils.isEmpty(e2eeSchemeId)) {
                rcsValues.put(com.android.messaging.datamodel.DatabaseHelper
                        .MessageColumns.RCS_E2EE_SCHEME_ID, e2eeSchemeId);
            }
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), rcsValues);

            // E2EE: latch the conversation's encryption_protocol on
            // INBOUND too, so the receiver's action-bar badge + compose padlock light
            // (and its next send stays E2EE) — symmetric with the send-path gate, and
            // mirroring Google Messages' accumulate-on-either-direction.
            if (!android.text.TextUtils.isEmpty(e2eeSchemeId)) {
                final int prior = BugleDatabaseOperations
                        .getConversationEncryptionProtocol(db, conversationId);
                final com.android.messaging.rcs.e2ee.EncryptionProtocolBits bits =
                        com.android.messaging.rcs.e2ee.EncryptionProtocolBits.fromColumnValue(prior)
                                .accumulate(
                                        com.android.messaging.rcs.e2ee.RcsE2eeScheme.ETOUFFEE
                                                .equals(e2eeSchemeId),
                                        com.android.messaging.rcs.e2ee.RcsE2eeScheme.MLS
                                                .equals(e2eeSchemeId));
                BugleDatabaseOperations.setConversationEncryptionProtocol(
                        db, conversationId, bits.toColumnValue());
            }

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), received, blocked,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        LogUtil.i(TAG, "ReceiveRcsMessageAction: stored RCS message " + message.getMessageId()
                + " (rcsId=" + rcsMessageId + ") in conversation " + conversationId);

        // Fire the delivered IMDN immediately if the peer asked for it. The
        // displayed IMDN is driven later from the "user opened thread" signal.
        if (wantsDelivered && !TextUtils.isEmpty(rcsMessageId)) {
            final ProviderTransport transport = ProviderTransport.peekInstance();
            if (transport != null) {
                // The GROUP the message arrived in selects which MLS conversation the receipt is
                // stamped from. Without it the stamping resolves by peer and finds the 1:1.
                transport.sendImdn(rcsMessageId, fromUri, IRcsProviderCallback.IMDN_DELIVERED,
                        isGroup ? groupId : null);
            }
        }

        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return message;
    }

    /** How a decoded (contentType, body) pair should be surfaced. */
    private enum Disposition { TEXT, MEDIA, FT, LOCATION, DROP }

    /**
     * Classify the (MLS-decoded) content-type into a render disposition.
     *
     * <p>Delegates to {@link RccContentDisposition}, which is pure and host-tested. This used to be a
     * second copy of the provider's switch — and the copy defaulted UNKNOWN types to TEXT, which is
     * how a non-text payload reaches the UI as a bubble. The shared classifier defaults them to a
     * drop instead, and every "raw bytes in the thread" bug we have had was that default.
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
     * Stage raw media bytes into our own {@link MediaScratchFileProvider} storage
     * and return a durable {@code content://} URI the media part can reference.
     * Runs on the action-service thread under our own uid (no binder identity to
     * clear, unlike {@code RcsCallbackRouter.ingestFdToScratch}). Returns
     * {@code null} on any failure so the caller can fall back to a text note.
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
     * Render an {@code rcs-ft-http+xml} descriptor as a viable, downloadable-looking
     * placeholder (filename + size) — NEVER the raw XML. Best-effort regex extract;
     * any failure degrades to a generic attachment label. Full HTTP download of the
     * {@code <data url=…>} + staging as a media part is a follow-up.
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
        final StringBuilder sb = new StringBuilder("📎 ");  // 📎
        sb.append(!TextUtils.isEmpty(name) ? name : "Attachment");
        if (!TextUtils.isEmpty(size)) {
            sb.append(" · ").append(size);  // · separator
        }
        return sb.toString();
    }

    /**
     * Render a {@code rcspushlocation+xml} (PIDF-LO) body as a tappable Google Maps
     * link — the cheapest viable form the provider itself uses for plaintext
     * location (TachyonTransport.handleIncomingLocation). Malformed → generic label.
     */
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
                return "📍 Shared location\n"  // 📍
                        + String.format(java.util.Locale.US,
                                "https://maps.google.com/?q=%.6f,%.6f", lat, lon);
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ReceiveRcsMessageAction: location parse failed", t);
        }
        return "📍 Shared location";  // 📍
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
