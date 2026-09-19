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
package com.android.messaging.datamodel.data;

import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.support.v7.mms.pdu.ContentType;
import android.text.TextUtils;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;

import com.android.messaging.rcs.rbm.RbmBotMessage;
import com.android.messaging.rcs.rbm.RbmBotParser;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseHelper.PartColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.Dates;
import com.android.messaging.util.LogUtil;
import com.google.common.base.Predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Class representing a message within a conversation sequence. The message parts
 * are available via the getParts() method.
 *
 * TODO: See if we can delegate to MessageData for the logic that this class duplicates
 * (e.g. getIsMms).
 */
public class ConversationMessageData {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private String mMessageId;
    private String mConversationId;
    private String mParticipantId;
    private int mPartsCount;
    private List<MessagePartData> mParts;
    private long mSentTimestamp;
    private long mReceivedTimestamp;
    private boolean mSeen;
    private boolean mRead;
    private int mProtocol;
    private int mStatus;
    private String mSmsMessageUri;
    private int mSmsPriority;
    private int mSmsMessageSize;
    private String mMmsSubject;
    private long mMmsExpiry;
    private int mRawTelephonyStatus;
    private String mSenderFullName;
    private String mSenderFirstName;
    private String mSenderDisplayDestination;
    private String mSenderNormalizedDestination;
    private String mSenderProfilePhotoUri;
    private long mSenderContactId;
    private String mSenderContactLookupKey;
    private String mSelfParticipantId;
    private int mTransportType;
    private int mRcsStatus;
    private long mRcsDeliveredTimestamp;
    private long mRcsDisplayedTimestamp;
    private int mRcsGroupReadCount;
    private int mRcsGroupDeliveredCount;
    private int mRcsGroupMemberCount;
    private int mRcsReactionCount;
    // Emoji-reactions: the TARGET's own rcs message-id (In-Reply-To id for a
    // reaction we send) and the per-emoji aggregated chips parsed from the packed
    // reactions_blob column. Both are null/empty for SMS/MMS and reaction-free rows.
    private String mRcsMessageId;
    /** Opaque E2EE scheme id (null/empty = plaintext) → per-message lock indicator. */
    private String mRcsE2eeSchemeId;
    private List<ReactionAggregate> mReactionAggregates = Collections.emptyList();

    // RBM: an inbound RCS Business Messaging agent row stores
    // the raw GSMA botmessage JSON as its text body. Parsed lazily (and cached per
    // bind) into the neutral model the renderer consumes. mRbmParsed guards the
    // one-shot parse so a recycled view re-parses on the next bind().
    private boolean mRbmParsed;
    private RbmBotMessage mRbmBotMessage;

    // geopush: lazily-parsed location, same one-shot/recycle guard.
    private boolean mGeoParsed;
    private GeoLoc mGeoLoc;

    /** A parsed RCS location share (geopush). */
    public static final class GeoLoc {
        public final double lat;
        public final double lon;
        public final String label;   // nullable
        GeoLoc(final double lat, final double lon, final String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }

    /** Are we similar enough to the previous/next messages that we can cluster them? */
    private boolean mCanClusterWithPreviousMessage;
    private boolean mCanClusterWithNextMessage;

    public ConversationMessageData() {
    }

    public void bind(final Cursor cursor) {
        mMessageId = cursor.getString(INDEX_MESSAGE_ID);
        mConversationId = cursor.getString(INDEX_CONVERSATION_ID);
        mParticipantId = cursor.getString(INDEX_PARTICIPANT_ID);
        mPartsCount = cursor.getInt(INDEX_PARTS_COUNT);

        mParts = makeParts(
                cursor.getString(INDEX_PARTS_IDS),
                cursor.getString(INDEX_PARTS_CONTENT_TYPES),
                cursor.getString(INDEX_PARTS_CONTENT_URIS),
                cursor.getString(INDEX_PARTS_WIDTHS),
                cursor.getString(INDEX_PARTS_HEIGHTS),
                cursor.getString(INDEX_PARTS_TEXTS),
                mPartsCount,
                mMessageId);

        mSentTimestamp = cursor.getLong(INDEX_SENT_TIMESTAMP);
        mReceivedTimestamp = cursor.getLong(INDEX_RECEIVED_TIMESTAMP);
        mSeen = (cursor.getInt(INDEX_SEEN) != 0);
        mRead = (cursor.getInt(INDEX_READ) != 0);
        mProtocol = cursor.getInt(INDEX_PROTOCOL);
        mStatus = cursor.getInt(INDEX_STATUS);
        mSmsMessageUri = cursor.getString(INDEX_SMS_MESSAGE_URI);
        mSmsPriority = cursor.getInt(INDEX_SMS_PRIORITY);
        mSmsMessageSize = cursor.getInt(INDEX_SMS_MESSAGE_SIZE);
        mMmsSubject = cursor.getString(INDEX_MMS_SUBJECT);
        mMmsExpiry = cursor.getLong(INDEX_MMS_EXPIRY);
        mRawTelephonyStatus = cursor.getInt(INDEX_RAW_TELEPHONY_STATUS);
        mSenderFullName = cursor.getString(INDEX_SENDER_FULL_NAME);
        mSenderFirstName = cursor.getString(INDEX_SENDER_FIRST_NAME);
        mSenderDisplayDestination = cursor.getString(INDEX_SENDER_DISPLAY_DESTINATION);
        mSenderNormalizedDestination = cursor.getString(INDEX_SENDER_NORMALIZED_DESTINATION);
        mSenderProfilePhotoUri = cursor.getString(INDEX_SENDER_PROFILE_PHOTO_URI);
        mSenderContactId = cursor.getLong(INDEX_SENDER_CONTACT_ID);
        mSenderContactLookupKey = cursor.getString(INDEX_SENDER_CONTACT_LOOKUP_KEY);
        mSelfParticipantId = cursor.getString(INDEX_SELF_PARTICIPIANT_ID);
        mTransportType = cursor.getInt(INDEX_TRANSPORT_TYPE);
        mRcsStatus = cursor.getInt(INDEX_RCS_STATUS);
        mRcsDeliveredTimestamp = cursor.getLong(INDEX_RCS_DELIVERED_TIMESTAMP);
        mRcsDisplayedTimestamp = cursor.getLong(INDEX_RCS_DISPLAYED_TIMESTAMP);
        mRcsGroupReadCount = cursor.getInt(INDEX_RCS_GROUP_READ_COUNT);
        mRcsGroupDeliveredCount = cursor.getInt(INDEX_RCS_GROUP_DELIVERED_COUNT);
        mRcsGroupMemberCount = cursor.getInt(INDEX_RCS_GROUP_MEMBER_COUNT);
        mRcsReactionCount = cursor.getInt(INDEX_RCS_REACTION_COUNT);
        mRcsMessageId = cursor.getString(INDEX_RCS_MESSAGE_ID);
        mRcsE2eeSchemeId = cursor.getString(INDEX_RCS_E2EE_SCHEME_ID);
        mReactionAggregates = mRcsReactionCount > 0
                ? parseReactionBlob(cursor.getString(INDEX_REACTIONS_BLOB))
                : Collections.<ReactionAggregate>emptyList();

        // RBM: invalidate the lazily-parsed bot message for this (recycled) view.
        mRbmParsed = false;
        mRbmBotMessage = null;
        // geopush: invalidate the lazily-parsed location for this recycled view.
        mGeoParsed = false;
        mGeoLoc = null;

        if (!cursor.isFirst() && cursor.moveToPrevious()) {
            mCanClusterWithPreviousMessage = canClusterWithMessage(cursor);
            cursor.moveToNext();
        } else {
            mCanClusterWithPreviousMessage = false;
        }
        if (!cursor.isLast() && cursor.moveToNext()) {
            mCanClusterWithNextMessage = canClusterWithMessage(cursor);
            cursor.moveToPrevious();
        } else {
            mCanClusterWithNextMessage = false;
        }
    }

    private boolean canClusterWithMessage(final Cursor cursor) {
        // WAVE-B: group-event system lines never cluster — neither this row nor
        // the adjacent row may be a system line, so each renders as its own
        // standalone centered status line (and a real message next to it keeps
        // its avatar / bubble rather than being collapsed into the system run).
        if (mTransportType == RcsConstants.TRANSPORT_RCS_SYSTEM
                || cursor.getInt(INDEX_TRANSPORT_TYPE) == RcsConstants.TRANSPORT_RCS_SYSTEM) {
            return false;
        }
        final String otherParticipantId = cursor.getString(INDEX_PARTICIPANT_ID);
        if (!TextUtils.equals(getParticipantId(), otherParticipantId)) {
            return false;
        }
        final int otherStatus = cursor.getInt(INDEX_STATUS);
        final boolean otherIsIncoming = (otherStatus >= MessageData.BUGLE_STATUS_FIRST_INCOMING);
        if (getIsIncoming() != otherIsIncoming) {
            return false;
        }
        final long otherReceivedTimestamp = cursor.getLong(INDEX_RECEIVED_TIMESTAMP);
        final long timestampDeltaMillis = Math.abs(mReceivedTimestamp - otherReceivedTimestamp);
        if (timestampDeltaMillis > DateUtils.MINUTE_IN_MILLIS) {
            return false;
        }
        final String otherSelfId = cursor.getString(INDEX_SELF_PARTICIPIANT_ID);
        if (!TextUtils.equals(getSelfParticipantId(), otherSelfId)) {
            return false;
        }
        return true;
    }

    private static final Character QUOTE_CHAR = '\'';
    private static final char DIVIDER = '|';

    // statics to avoid unnecessary object allocation
    private static final StringBuilder sUnquoteStringBuilder = new StringBuilder();
    private static final ArrayList<String> sUnquoteResults = new ArrayList<>();

    // this lock is used to guard access to the above statics
    private static final Object sUnquoteLock = new Object();

    private static void addResult(final ArrayList<String> results, final StringBuilder value) {
        if (value.length() > 0) {
            results.add(value.toString());
        } else {
            results.add(EMPTY_STRING);
        }
    }

    static String[] splitUnquotedString(final String inputString) {
        if (TextUtils.isEmpty(inputString)) {
            return new String[0];
        }

        return inputString.split("\\" + DIVIDER);
    }

    /**
     * Takes a group-concated and quoted string and decomposes it into its constituent
     * parts.  A quoted string starts and ends with a single quote.  Actual single quotes
     * within the string are escaped using a second single quote.  So, for example, an
     * input string with 3 constituent parts might look like this:
     *
     * 'now is the time'|'I can''t do it'|'foo'
     *
     * This would be returned as an array of 3 strings as follows:
     * now is the time
     * I can't do it
     * foo
     *
     * This is achieved by walking through the inputString, character by character,
     * ignoring the outer quotes and the divider and replacing any pair of consecutive
     * single quotes with a single single quote.
     *
     * @return array of constituent strings
     */
    static String[] splitQuotedString(final String inputString) {
        if (TextUtils.isEmpty(inputString)) {
            return new String[0];
        }

        // this method can be called from multiple threads but it uses a static
        // string builder
        synchronized (sUnquoteLock) {
            final int length = inputString.length();
            final ArrayList<String> results = sUnquoteResults;
            results.clear();

            int characterPos = -1;
            while (++characterPos < length) {
                final char mustBeQuote = inputString.charAt(characterPos);
                Assert.isTrue(QUOTE_CHAR == mustBeQuote);
                while (++characterPos < length) {
                    final char currentChar = inputString.charAt(characterPos);
                    if (currentChar == QUOTE_CHAR) {
                        final char peekAhead = characterPos < length - 1
                                ? inputString.charAt(characterPos + 1) : 0;

                        if (peekAhead == QUOTE_CHAR) {
                            characterPos += 1;  // skip the second quote
                        } else {
                            addResult(results, sUnquoteStringBuilder);
                            sUnquoteStringBuilder.setLength(0);

                            Assert.isTrue((peekAhead == DIVIDER) || (peekAhead == (char) 0));
                            characterPos += 1;  // skip the divider
                            break;
                        }
                    }
                    sUnquoteStringBuilder.append(currentChar);
                }
            }
            return results.toArray(new String[results.size()]);
        }
    }

    static MessagePartData makePartData(
            final String partId,
            final String contentType,
            final String contentUriString,
            final String contentWidth,
            final String contentHeight,
            final String text,
            final String messageId) {
        if (ContentType.isTextType(contentType)) {
            final MessagePartData textPart = MessagePartData.createTextMessagePart(text);
            textPart.updatePartId(partId);
            textPart.updateMessageId(messageId);
            return textPart;
        } else {
            final Uri contentUri = Uri.parse(contentUriString);
            final int width = Integer.parseInt(contentWidth);
            final int height = Integer.parseInt(contentHeight);
            final MessagePartData attachmentPart = MessagePartData.createMediaMessagePart(
                    contentType, contentUri, width, height);
            attachmentPart.updatePartId(partId);
            attachmentPart.updateMessageId(messageId);
            return attachmentPart;
        }
    }

    static List<MessagePartData> makeParts(
            final String rawIds,
            final String rawContentTypes,
            final String rawContentUris,
            final String rawWidths,
            final String rawHeights,
            final String rawTexts,
            final int partsCount,
            final String messageId) {
        final List<MessagePartData> parts = new LinkedList<>();
        if (partsCount == 1) {
            parts.add(makePartData(
                    rawIds,
                    rawContentTypes,
                    rawContentUris,
                    rawWidths,
                    rawHeights,
                    rawTexts,
                    messageId));
        } else {
            unpackMessageParts(
                    parts,
                    splitUnquotedString(rawIds),
                    splitQuotedString(rawContentTypes),
                    splitQuotedString(rawContentUris),
                    splitUnquotedString(rawWidths),
                    splitUnquotedString(rawHeights),
                    splitQuotedString(rawTexts),
                    partsCount,
                    messageId);
        }
        return parts;
    }

    static void unpackMessageParts(
            final List<MessagePartData> parts,
            final String[] ids,
            final String[] contentTypes,
            final String[] contentUris,
            final String[] contentWidths,
            final String[] contentHeights,
            final String[] texts,
            final int partsCount,
            final String messageId) {

        Assert.equals(partsCount, ids.length);
        Assert.equals(partsCount, contentTypes.length);
        Assert.equals(partsCount, contentUris.length);
        Assert.equals(partsCount, contentWidths.length);
        Assert.equals(partsCount, contentHeights.length);
        Assert.equals(partsCount, texts.length);

        for (int i = 0; i < partsCount; i++) {
            parts.add(makePartData(
                    ids[i],
                    contentTypes[i],
                    contentUris[i],
                    contentWidths[i],
                    contentHeights[i],
                    texts[i],
                    messageId));
        }

        if (parts.size() != partsCount) {
            LogUtil.wtf(TAG, "Only unpacked " + parts.size() + " parts from message (id="
                    + messageId + "), expected " + partsCount + " parts");
        }
    }

    public final String getMessageId() {
        return mMessageId;
    }

    public final String getConversationId() {
        return mConversationId;
    }

    public final String getParticipantId() {
        return mParticipantId;
    }

    public List<MessagePartData> getParts() {
        return mParts;
    }

    public boolean hasText() {
        for (final MessagePartData part : mParts) {
            if (part.isText()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a concatenation of all text parts
     *
     * @return the text that is a concatenation of all text parts
     */
    public String getText() {
        // This is optimized for single text part case, which is the majority

        // For single text part, we just return the part without creating the StringBuilder
        String firstTextPart = null;
        boolean foundText = false;
        // For multiple text parts, we need the StringBuilder and the separator for concatenation
        StringBuilder sb = null;
        String separator = null;
        for (final MessagePartData part : mParts) {
            if (part.isText()) {
                if (!foundText) {
                    // First text part
                    firstTextPart = part.getText();
                    foundText = true;
                } else {
                    // Second and beyond
                    if (sb == null) {
                        // Need the StringBuilder and the separator starting from 2nd text part
                        sb = new StringBuilder();
                        if (!TextUtils.isEmpty(firstTextPart)) {
                              sb.append(firstTextPart);
                        }
                        separator = BugleGservicesKeys.MMS_TEXT_CONCAT_SEPARATOR_DEFAULT;
                    }
                    final String partText = part.getText();
                    if (!TextUtils.isEmpty(partText)) {
                        if (!TextUtils.isEmpty(separator) && sb.length() > 0) {
                            sb.append(separator);
                        }
                        sb.append(partText);
                    }
                }
            }
        }
        if (sb == null) {
            // Only one text part
            return firstTextPart;
        } else {
            // More than one
            return sb.toString();
        }
    }

    /** RBM: a bot/agent address is {@code <agent>@rbm.goog}, never an
     *  MSISDN. The provider lands such inbound rows keyed by that address. */
    private static final String RBM_BOT_SUFFIX = "@rbm.goog";

    /**
     * RBM (Phase 4): true for an INBOUND RCS Business Messaging agent message —
     * the sender is an {@code <agent>@rbm.goog} address. Outbound rows (our
     * postbacks, Phase 5) and ordinary SMS/MMS/RCS are never bot messages, so the
     * card/chip render path below stays off for every other row.
     */
    public boolean getIsBotMessage() {
        if (!getIsIncoming()) {
            return false;
        }
        final String dest = mSenderNormalizedDestination;
        return dest != null && dest.endsWith(RBM_BOT_SUFFIX);
    }

    /** RBM: the bot/agent id (e.g. {@code admin@rbm.goog}) for a bot row; else null. */
    public String getBotId() {
        return getIsBotMessage() ? mSenderNormalizedDestination : null;
    }

    /**
     * RBM (Phase 4): the parsed bot message for an {@link #getIsBotMessage() bot}
     * row, or null for a non-bot row / an unparseable body (caller falls back to
     * plain-text rendering). The stored body is the raw
     * {@code application/vnd.gsma.botmessage.v1.0+json} payload (Phase 1); a plain
     * agent line stored as text simply fails the JSON parse and renders as text.
     * Parsed once and cached per {@link #bind}.
     */
    public RbmBotMessage getRbmBotMessage() {
        if (!mRbmParsed) {
            mRbmParsed = true;
            mRbmBotMessage = null;
            if (getIsBotMessage()) {
                final String body = getText();
                if (!TextUtils.isEmpty(body)) {
                    try {
                        mRbmBotMessage = RbmBotParser.parse(body);
                    } catch (final RbmBotParser.RbmParseException e) {
                        // Not a JSON botmessage (a plain agent line) -> render as text.
                        mRbmBotMessage = null;
                    }
                }
            }
        }
        return mRbmBotMessage;
    }

    private static final java.util.regex.Pattern GEO_PATTERN =
            java.util.regex.Pattern.compile(
                    "maps\\.google\\.com/\\?q=(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");

    /**
     * geopush: if this message is one of OUR rendered location shares
     * (a "📍 …\nhttps://maps.google.com/?q=lat,lon" body produced by
     * {@code handleIncomingLocation} / {@code SendRcsLocationAction}), return the
     * parsed lat/lon (+ label) so the renderer can show a location card; else null.
     * Parsed once and cached per {@link #bind} (recycle-safe). Ordinary maps
     * link messages don't match (no 📍 + different URL) → render as plain text.
     */
    public GeoLoc getGeoLocation() {
        if (!mGeoParsed) {
            mGeoParsed = true;
            mGeoLoc = null;
            final String body = getText();
            if (body != null && body.startsWith("📍")) {  // 📍
                final java.util.regex.Matcher m = GEO_PATTERN.matcher(body);
                if (m.find()) {
                    try {
                        final double lat = Double.parseDouble(m.group(1));
                        final double lon = Double.parseDouble(m.group(2));
                        String label = null;
                        final int nl = body.indexOf('\n');
                        if (nl > 0) {
                            label = body.substring(0, nl).replace("📍", "").trim();
                            // "Shared location" is OUR generic sentinel (no real
                            // sender label) — treat as none so the card shows the
                            // reverse-geocoded address / lat-lon instead of echoing
                            // our own placeholder.
                            if (label.isEmpty()
                                    || "Shared location".equalsIgnoreCase(label)) {
                                label = null;
                            }
                        }
                        mGeoLoc = new GeoLoc(lat, lon, label);
                    } catch (final NumberFormatException ignore) {
                        mGeoLoc = null;
                    }
                }
            }
        }
        return mGeoLoc;
    }

    /**
     * RBM: the one-line text the notification preview should show — a
     * human {@link com.android.messaging.rcs.RbmSummary} for a bot message (never
     * the raw botmessage JSON), otherwise the plain message text.
     */
    public String getNotificationPreviewText() {
        if (getIsBotMessage()) {
            return com.android.messaging.rcs.RbmSummary.of(getRbmBotMessage(), null);
        }
        return getText();
    }

    /**
     * RBM: true for the local echo of a tapped suggestion-reply chip
     * (an outgoing RCS row stamped with the postback-echo sentinel id by
     * {@link com.android.messaging.datamodel.action.InsertRbmPostbackEchoAction}).
     * The renderer styles it as a "Selected option" rather than a normal sent
     * message; a free-text reply to a bot (a real send) is unaffected.
     */
    public boolean getIsBotPostbackEcho() {
        return !getIsIncoming() && getIsRcs()
                && com.android.messaging.rcs.RcsConstants.RBM_POSTBACK_ECHO_MARKER
                        .equals(mRcsMessageId);
    }

    public boolean hasAttachments() {
        for (final MessagePartData part : mParts) {
            if (part.isAttachment()) {
                return true;
            }
        }
        return false;
    }

    public List<MessagePartData> getAttachments() {
        return getAttachments(null);
    }

    public List<MessagePartData> getAttachments(final Predicate<MessagePartData> filter) {
        if (mParts.isEmpty()) {
            return Collections.emptyList();
        }
        final List<MessagePartData> attachmentParts = new LinkedList<>();
        for (final MessagePartData part : mParts) {
            if (part.isAttachment()) {
                if (filter == null || filter.apply(part)) {
                    attachmentParts.add(part);
                }
            }
        }
        return attachmentParts;
    }

    public final long getSentTimeStamp() {
        return mSentTimestamp;
    }

    public final long getReceivedTimeStamp() {
        return mReceivedTimestamp;
    }

    public final String getFormattedReceivedTimeStamp() {
        return Dates.getMessageTimeString(mReceivedTimestamp).toString();
    }

    public final boolean getIsSeen() {
        return mSeen;
    }

    public final boolean getIsRead() {
        return mRead;
    }

    public final boolean getIsMms() {
        return (mProtocol == MessageData.PROTOCOL_MMS ||
                mProtocol == MessageData.PROTOCOL_MMS_PUSH_NOTIFICATION);
    }

    public final boolean getIsMmsNotification() {
        return (mProtocol == MessageData.PROTOCOL_MMS_PUSH_NOTIFICATION);
    }

    public final boolean getIsSms() {
        return mProtocol == (MessageData.PROTOCOL_SMS);
    }

    public final int getStatus() {
        return mStatus;
    }

    public final String getSmsMessageUri() {
        return mSmsMessageUri;
    }

    public final int getSmsPriority() {
        return mSmsPriority;
    }

    public final int getSmsMessageSize() {
        return mSmsMessageSize;
    }

    public final String getMmsSubject() {
        return mMmsSubject;
    }

    public final long getMmsExpiry() {
        return mMmsExpiry;
    }

    public final int getRawTelephonyStatus() {
        return mRawTelephonyStatus;
    }

    public final String getSelfParticipantId() {
        return mSelfParticipantId;
    }

    /**
     * The transport that carried this message: {@link RcsConstants#TRANSPORT_DEFAULT}
     * for SMS/MMS, {@link RcsConstants#TRANSPORT_RCS} for RCS. RCS messages are
     * stored SMS-shaped (PROTOCOL_SMS) and distinguished only by this column, so
     * SMS/MMS rendering is unaffected when this returns the default.
     */
    public final int getTransportType() {
        return mTransportType;
    }

    /**
     * @return true if this message was delivered over RCS (additive column,
     *         defaults to false for every existing SMS/MMS row).
     */
    public final boolean getIsRcs() {
        return mTransportType == RcsConstants.TRANSPORT_RCS;
    }

    /**
     * WAVE-B: true if this row is a group lifecycle event (created / member
     * added / removed / left / renamed) that must render as a centered, muted,
     * no-avatar, no-bubble status line interleaved in the thread, rather than as
     * a normal sender bubble. Additive column; defaults to false for every
     * SMS/MMS/RCS message row, so normal rendering is unchanged.
     */
    public final boolean getIsRcsSystem() {
        return mTransportType == RcsConstants.TRANSPORT_RCS_SYSTEM;
    }

    /** Raw RCS status code (IRcsProviderCallback.STATUS_*); 0 == none yet. */
    public final int getRcsStatus() {
        return mRcsStatus;
    }

    /** IMDN delivery-receipt timestamp (ms), or 0 if not yet delivered. */
    public final long getRcsDeliveredTimestamp() {
        return mRcsDeliveredTimestamp;
    }

    /** IMDN display-receipt timestamp (ms), or 0 if not yet read. */
    public final long getRcsDisplayedTimestamp() {
        return mRcsDisplayedTimestamp;
    }

    /**
     * WAVE-E: number of DISTINCT group members who have DISPLAYED (read) this
     * sent message, from the rcs_group_receipts side-table. 0 for 1:1 / SMS /
     * MMS rows.
     */
    public final int getRcsGroupReadCount() {
        return mRcsGroupReadCount;
    }

    /**
     * WAVE-E: number of DISTINCT group members who have DELIVERED this sent
     * message, from the rcs_group_receipts side-table. 0 for 1:1 / SMS / MMS.
     */
    public final int getRcsGroupDeliveredCount() {
        return mRcsGroupDeliveredCount;
    }

    /**
     * WAVE-E: M, the number of OTHER members in this group conversation (group
     * participant_count, which already excludes self). 0 when the conversation
     * is not an RCS group (the subquery's rcs_group_id IS NOT NULL guard fails),
     * which is what disables the aggregate render for 1:1 / SMS / MMS rows.
     */
    public final int getRcsGroupMemberCount() {
        return mRcsGroupMemberCount;
    }

    /** Non-zero if this message has at least one reaction chip to render. */
    public final int getRcsReactionCount() {
        return mRcsReactionCount;
    }

    public final boolean hasReactions() {
        return mRcsReactionCount > 0;
    }

    /**
     * The TARGET message's own rcs message-id — the In-Reply-To id a reaction we
     * send must reference (same id space as IMDN). Null for SMS/MMS rows and for
     * any row that has not yet been assigned a server message id.
     */
    public final String getRcsMessageId() {
        return mRcsMessageId;
    }

    /** Opaque E2EE scheme id this message arrived under, or null/empty for plaintext.
     *  Non-empty ⇒ render the per-message lock indicator. */
    public final String getRcsE2eeSchemeId() {
        return mRcsE2eeSchemeId;
    }

    public final boolean isE2eeEncrypted() {
        return !android.text.TextUtils.isEmpty(mRcsE2eeSchemeId);
    }

    /**
     * Per-emoji aggregated reaction chips to render on THIS message's bubble,
     * already de-duplicated by emoji (count + reactor list + a self flag) and
     * ordered by first-seen. Empty for a row with no reactions. 1:1 yields
     * count==1 chips; a group aggregates each emoji across its members.
     */
    public final List<ReactionAggregate> getReactionAggregates() {
        return mReactionAggregates;
    }

    /**
     * The emoji WE reacted to this message with, or {@code null} if we haven't
     * reacted. At most one (the rcs_reactions PK is target+reactor). The
     * long-press picker uses this to show our current pick selected so re-tapping
     * it removes (deselect) and tapping a different glyph changes it.
     */
    public final String getSelfReactionEmoji() {
        for (final ReactionAggregate r : mReactionAggregates) {
            if (r.reactedBySelf) {
                return r.emoji;
            }
        }
        return null;
    }

    /** One aggregated reaction chip: an emoji, how many reacted with it, whether
     *  WE are one of them, and the reactor URIs (for a group "who" tooltip). */
    public static final class ReactionAggregate {
        public final String emoji;
        public final int count;
        public final boolean reactedBySelf;
        public final List<String> reactorUris;

        ReactionAggregate(final String emoji, final int count,
                final boolean reactedBySelf, final List<String> reactorUris) {
            this.emoji = emoji;
            this.count = count;
            this.reactedBySelf = reactedBySelf;
            this.reactorUris = reactorUris;
        }
    }

    // Packed reactions_blob separators (mirror the char(30)/char(31) the
    // projection group_concat emits).
    private static final char REACTION_RECORD_SEP = '\u001e';
    private static final char REACTION_UNIT_SEP = '\u001f';

    /**
     * Parse the packed {@code reactions_blob} ("emoji<US>reactor<RS>…") into
     * per-emoji aggregates: count, a self flag (reactor == SELF_REACTOR_URI), and
     * the reactor list, preserving first-seen order (the blob is already ordered
     * by timestamp). Tolerant of malformed records (skips any without the unit
     * separator). Returns an empty list for a null/blank blob.
     */
    private static List<ReactionAggregate> parseReactionBlob(final String blob) {
        if (TextUtils.isEmpty(blob)) {
            return Collections.emptyList();
        }
        // Preserve emoji insertion order; merge repeated emoji into one bucket.
        final java.util.LinkedHashMap<String, List<String>> byEmoji =
                new java.util.LinkedHashMap<>();
        for (final String record : blob.split(String.valueOf(REACTION_RECORD_SEP), -1)) {
            final int sep = record.indexOf(REACTION_UNIT_SEP);
            if (sep <= 0) {
                continue; // malformed / empty record
            }
            final String emoji = record.substring(0, sep);
            final String reactor = record.substring(sep + 1);
            List<String> reactors = byEmoji.get(emoji);
            if (reactors == null) {
                reactors = new ArrayList<>(2);
                byEmoji.put(emoji, reactors);
            }
            if (!reactors.contains(reactor)) {
                reactors.add(reactor);
            }
        }
        final List<ReactionAggregate> out = new ArrayList<>(byEmoji.size());
        for (final java.util.Map.Entry<String, List<String>> e : byEmoji.entrySet()) {
            final List<String> reactors = e.getValue();
            final boolean self =
                    reactors.contains(com.android.messaging.rcs.RcsMessageStore.SELF_REACTOR_URI);
            out.add(new ReactionAggregate(e.getKey(), reactors.size(), self, reactors));
        }
        return out;
    }

    public boolean getIsIncoming() {
        return (mStatus >= MessageData.BUGLE_STATUS_FIRST_INCOMING);
    }

    public boolean hasIncomingErrorStatus() {
        return (mStatus == MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE ||
                mStatus == MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED);
    }

    public boolean getIsSendComplete() {
        return (mStatus == MessageData.BUGLE_STATUS_OUTGOING_COMPLETE
                || mStatus == MessageData.BUGLE_STATUS_OUTGOING_DELIVERED);
    }

    public String getSenderFullName() {
        return mSenderFullName;
    }

    public String getSenderFirstName() {
        return mSenderFirstName;
    }

    public String getSenderDisplayDestination() {
        return mSenderDisplayDestination;
    }

    public String getSenderNormalizedDestination() {
        return mSenderNormalizedDestination;
    }

    public Uri getSenderProfilePhotoUri() {
        return mSenderProfilePhotoUri == null ? null : Uri.parse(mSenderProfilePhotoUri);
    }

    public long getSenderContactId() {
        return mSenderContactId;
    }

    public String getSenderDisplayName() {
        if (!TextUtils.isEmpty(mSenderFullName)) {
            return mSenderFullName;
        }
        if (!TextUtils.isEmpty(mSenderFirstName)) {
            return mSenderFirstName;
        }
        return mSenderDisplayDestination;
    }

    public String getSenderContactLookupKey() {
        return mSenderContactLookupKey;
    }

    public boolean getShowDownloadMessage() {
        return MessageData.getShowDownloadMessage(mStatus);
    }

    public boolean getShowResendMessage() {
        return MessageData.getShowResendMessage(mStatus);
    }

    public boolean getCanForwardMessage() {
        // Even for outgoing messages, we only allow forwarding if the message has finished sending
        // as media often has issues when send isn't complete
        return (mStatus == MessageData.BUGLE_STATUS_OUTGOING_COMPLETE
                || mStatus == MessageData.BUGLE_STATUS_OUTGOING_DELIVERED
                || mStatus == MessageData.BUGLE_STATUS_INCOMING_COMPLETE);
    }

    public boolean getCanCopyMessageToClipboard() {
        return (hasText() &&
                (!getIsIncoming() || mStatus == MessageData.BUGLE_STATUS_INCOMING_COMPLETE));
    }

    /**
     * Whether a TAP on this bubble should resend it outright.
     *
     * <p>For an RCS row this is now gated on {@link #canResendOverRcs()} rather than refused
     * outright.
     *
     * <p><b>Why it was refused, and why that reason is gone.</b> The affordance used to be
     * withheld from every RCS row because {@code ResendMessageAction} moved the row to
     * {@code OUTGOING_YET_TO_SEND} and handed it to {@code ProcessPendingMessagesAction}, whose
     * query excludes {@code TRANSPORT_RCS} by name — a one-way trip back to "Sending…", i.e. the
     * very state that rule existed to stop a message dying in, re-entered through the button
     * offered to escape it. That path is now a real over-RCS resend which
     * never writes {@code YET_TO_SEND} and never touches that queue, so the reason no longer
     * holds. <b>A gate whose stated reason has evaporated is worse than no gate</b> — it becomes a
     * fact by attrition — so it is re-derived rather than left standing.
     *
     * <p>{@link #getShowResendMessage()} is deliberately left alone: it is what makes the bubble
     * selectable, and "Send as SMS" is still offered on every failed RCS row, including the ones
     * this predicate excludes.
     */
    public boolean getOneClickResendMessage() {
        return MessageData.getOneClickResendMessage(mStatus, mRawTelephonyStatus)
                && (!getIsRcs() || canResendOverRcs());
    }

    /**
     * Whether a failed RCS row can actually be resent OVER RCS.
     *
     * <p>Three conditions, and each excludes a row for which the button would do nothing:
     *
     * <ul>
     *   <li><b>E2EE.</b> The user-driven resend seals; it has no plaintext arm. A non-encrypted
     *       RCS row would reach it, find no encrypted session, and return false. Offering a control
     *       that always fails is the defect that was removed, and re-creating it one
     *       conversation-type over would not be an improvement.</li>
     *   <li><b>A wire id.</b> The resend root-resolves this id to recover the body. The REFUSED
     *       rows are landed FAILED with a null {@code rcs_message_id}
     *       on purpose — they never reached the wire, so "resend" is not the verb they need.</li>
     *   <li><b>RCS at all</b>, since the SMS path owns everything else.</li>
     * </ul>
     *
     * <p>Rows this excludes are not left without an affordance: "Send as SMS" is shown on every
     * failed RCS row and is the correct answer for all three cases.
     */
    public boolean canResendOverRcs() {
        return getIsRcs() && isE2eeEncrypted()
                && !android.text.TextUtils.isEmpty(mRcsMessageId);
    }

    /**
     * Get sender's lookup uri.
     * This method doesn't support corp contacts.
     *
     * @return Lookup uri of sender's contact
     */
    public Uri getSenderContactLookupUri() {
        if (mSenderContactId > ParticipantData.PARTICIPANT_CONTACT_ID_NOT_RESOLVED
                && !TextUtils.isEmpty(mSenderContactLookupKey)) {
            return ContactsContract.Contacts.getLookupUri(mSenderContactId,
                    mSenderContactLookupKey);
        }
        return null;
    }

    public boolean getCanClusterWithPreviousMessage() {
        return mCanClusterWithPreviousMessage;
    }

    public boolean getCanClusterWithNextMessage() {
        return mCanClusterWithNextMessage;
    }

    @NonNull
    @Override
    public String toString() {
        return MessageData.toString(mMessageId, mParts);
    }

    // Data definitions

    public static String getConversationMessagesQuerySql() {
        return CONVERSATION_MESSAGES_QUERY_SQL
                + " AND "
                // Inject the conversation id
                + DatabaseHelper.MESSAGES_TABLE + "." + MessageColumns.CONVERSATION_ID + "=?)"
                + CONVERSATION_MESSAGES_QUERY_SQL_GROUP_BY;
    }

    static String getConversationMessageIdsQuerySql() {
        return CONVERSATION_MESSAGES_IDS_QUERY_SQL
                + " AND "
                // Inject the conversation id
                + DatabaseHelper.MESSAGES_TABLE + "." + MessageColumns.CONVERSATION_ID + "=?)"
                + CONVERSATION_MESSAGES_QUERY_SQL_GROUP_BY;
    }

    public static String getNotificationQuerySql() {
        return CONVERSATION_MESSAGES_QUERY_SQL
                + " AND "
                + "(" + DatabaseHelper.MessageColumns.STATUS + " in ("
                + MessageData.BUGLE_STATUS_INCOMING_COMPLETE + ", "
                + MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD + ")"
                + " AND "
                + DatabaseHelper.MessageColumns.SEEN + " = 0)"
                + ")"
                + NOTIFICATION_QUERY_SQL_GROUP_BY;
    }

    public static String getWearableQuerySql() {
        return CONVERSATION_MESSAGES_QUERY_SQL
                + " AND "
                + DatabaseHelper.MESSAGES_TABLE + "." + MessageColumns.CONVERSATION_ID + "=?"
                + " AND "
                + DatabaseHelper.MessageColumns.STATUS + " IN ("
                + MessageData.BUGLE_STATUS_OUTGOING_DELIVERED + ", "
                + MessageData.BUGLE_STATUS_OUTGOING_COMPLETE + ", "
                + MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND + ", "
                + MessageData.BUGLE_STATUS_OUTGOING_SENDING + ", "
                + MessageData.BUGLE_STATUS_OUTGOING_RESENDING + ", "
                + MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY + ", "
                + MessageData.BUGLE_STATUS_INCOMING_COMPLETE + ", "
                + MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD + ")"
                + ")"
                + NOTIFICATION_QUERY_SQL_GROUP_BY;
    }

    /*
     * Generate a sqlite snippet to call the quote function on the columnName argument.
     * The columnName doesn't strictly have to be a column name (e.g. it could be an
     * expression).
     */
    private static String quote(final String columnName) {
        return "quote(" + columnName + ")";
    }

    private static String makeGroupConcatString(final String column) {
        return "group_concat(" + column + ", '" + DIVIDER + "')";
    }

    private static String makeIfNullString(final String column) {
        return "ifnull(" + column + "," + "''" + ")";
    }

    private static String makePartsTableColumnString(final String column) {
        return DatabaseHelper.PARTS_TABLE + '.' + column;
    }

    private static String makeCaseWhenString(final String column,
                                             final boolean quote,
                                             final String asColumn) {
        final String fullColumn = makeIfNullString(makePartsTableColumnString(column));
        final String groupConcatTerm = quote
                ? makeGroupConcatString(quote(fullColumn))
                : makeGroupConcatString(fullColumn);
        return "CASE WHEN (" + CONVERSATION_MESSAGE_VIEW_PARTS_COUNT + ">1) THEN " + groupConcatTerm
                + " ELSE " + makePartsTableColumnString(column) + " END AS " + asColumn;
    }

    private static final String CONVERSATION_MESSAGE_VIEW_PARTS_COUNT =
            "count(" + DatabaseHelper.PARTS_TABLE + '.' + PartColumns._ID + ")";

    private static final String EMPTY_STRING = "";

    private static final String CONVERSATION_MESSAGES_QUERY_PROJECTION_SQL =
            DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns._ID
            + " as " + ConversationMessageViewColumns._ID + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.CONVERSATION_ID
            + " as " + ConversationMessageViewColumns.CONVERSATION_ID + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SENDER_PARTICIPANT_ID
            + " as " + ConversationMessageViewColumns.PARTICIPANT_ID + ", "

            + makeCaseWhenString(PartColumns._ID, false,
                    ConversationMessageViewColumns.PARTS_IDS) + ", "
            + makeCaseWhenString(PartColumns.CONTENT_TYPE, true,
                    ConversationMessageViewColumns.PARTS_CONTENT_TYPES) + ", "
            + makeCaseWhenString(PartColumns.CONTENT_URI, true,
                    ConversationMessageViewColumns.PARTS_CONTENT_URIS) + ", "
            + makeCaseWhenString(PartColumns.WIDTH, false,
                    ConversationMessageViewColumns.PARTS_WIDTHS) + ", "
            + makeCaseWhenString(PartColumns.HEIGHT, false,
                    ConversationMessageViewColumns.PARTS_HEIGHTS) + ", "
            + makeCaseWhenString(PartColumns.TEXT, true,
                    ConversationMessageViewColumns.PARTS_TEXTS) + ", "

            + CONVERSATION_MESSAGE_VIEW_PARTS_COUNT
            + " as " + ConversationMessageViewColumns.PARTS_COUNT + ", "

            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SENT_TIMESTAMP
            + " as " + ConversationMessageViewColumns.SENT_TIMESTAMP + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RECEIVED_TIMESTAMP
            + " as " + ConversationMessageViewColumns.RECEIVED_TIMESTAMP + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SEEN
            + " as " + ConversationMessageViewColumns.SEEN + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.READ
            + " as " + ConversationMessageViewColumns.READ + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.PROTOCOL
            + " as " + ConversationMessageViewColumns.PROTOCOL + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.STATUS
            + " as " + ConversationMessageViewColumns.STATUS + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SMS_MESSAGE_URI
            + " as " + ConversationMessageViewColumns.SMS_MESSAGE_URI + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SMS_PRIORITY
            + " as " + ConversationMessageViewColumns.SMS_PRIORITY + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SMS_MESSAGE_SIZE
            + " as " + ConversationMessageViewColumns.SMS_MESSAGE_SIZE + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.MMS_SUBJECT
            + " as " + ConversationMessageViewColumns.MMS_SUBJECT + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.MMS_EXPIRY
            + " as " + ConversationMessageViewColumns.MMS_EXPIRY + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RAW_TELEPHONY_STATUS
            + " as " + ConversationMessageViewColumns.RAW_TELEPHONY_STATUS + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.SELF_PARTICIPANT_ID
            + " as " + ConversationMessageViewColumns.SELF_PARTICIPANT_ID + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.FULL_NAME
            + " as " + ConversationMessageViewColumns.SENDER_FULL_NAME + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.FIRST_NAME
            + " as " + ConversationMessageViewColumns.SENDER_FIRST_NAME + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.DISPLAY_DESTINATION
            + " as " + ConversationMessageViewColumns.SENDER_DISPLAY_DESTINATION + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.NORMALIZED_DESTINATION
            + " as " + ConversationMessageViewColumns.SENDER_NORMALIZED_DESTINATION + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.PROFILE_PHOTO_URI
            + " as " + ConversationMessageViewColumns.SENDER_PROFILE_PHOTO_URI + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.CONTACT_ID
            + " as " + ConversationMessageViewColumns.SENDER_CONTACT_ID + ", "
            + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns.LOOKUP_KEY
            + " as " + ConversationMessageViewColumns.SENDER_CONTACT_LOOKUP_KEY + ", "
            // RCS additive columns. These default to 0 for every SMS/MMS row, so
            // the SMS/MMS render path is byte-identical; only RCS rows carry data.
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.TRANSPORT_TYPE
            + " as " + ConversationMessageViewColumns.TRANSPORT_TYPE + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_STATUS
            + " as " + ConversationMessageViewColumns.RCS_STATUS + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_DELIVERED_TIMESTAMP
            + " as " + ConversationMessageViewColumns.RCS_DELIVERED_TIMESTAMP + ", "
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_DISPLAYED_TIMESTAMP
            + " as " + ConversationMessageViewColumns.RCS_DISPLAYED_TIMESTAMP + ", "
            // WAVE-E group read-receipt aggregate. Correlated subqueries against
            // the rcs_group_receipts side-table + the conversation's
            // participant_count. These default to 0 for every 1:1 / SMS / MMS
            // row (no side-table rows + the renderer ignores them unless the
            // conversation is a group), so the existing single Delivered/Read
            // path is byte-unchanged. N_read = distinct members who displayed;
            // N_delivered = distinct members who delivered; M = other-member
            // count (group participant_count already excludes self).
            + "(SELECT COUNT(*) FROM " + DatabaseHelper.RCS_GROUP_RECEIPTS_TABLE + " gr"
            + " WHERE gr." + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID
            + " = " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns._ID
            + " AND gr." + DatabaseHelper.RcsGroupReceiptColumns.DISPLAYED_TIMESTAMP + " > 0)"
            + " as " + ConversationMessageViewColumns.RCS_GROUP_READ_COUNT + ", "
            + "(SELECT COUNT(*) FROM " + DatabaseHelper.RCS_GROUP_RECEIPTS_TABLE + " gr"
            + " WHERE gr." + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID
            + " = " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns._ID
            + " AND gr." + DatabaseHelper.RcsGroupReceiptColumns.DELIVERED_TIMESTAMP + " > 0)"
            + " as " + ConversationMessageViewColumns.RCS_GROUP_DELIVERED_COUNT + ", "
            + "(SELECT c." + DatabaseHelper.ConversationColumns.PARTICIPANT_COUNT
            + " FROM " + DatabaseHelper.CONVERSATIONS_TABLE + " c"
            + " WHERE c." + DatabaseHelper.ConversationColumns._ID
            + " = " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.CONVERSATION_ID
            + " AND c." + DatabaseHelper.ConversationColumns.RCS_GROUP_ID + " IS NOT NULL)"
            + " as " + ConversationMessageViewColumns.RCS_GROUP_MEMBER_COUNT + ", "
            // Emoji-reactions presence flag. A correlated COUNT against the
            // rcs_reactions side-table, keyed on the TARGET message's
            // rcs_message_id (the same id the reaction targets). The IS NOT NULL
            // guard keeps SMS/MMS rows (null rcs_message_id) at 0, so the
            // existing render path is byte-unchanged; the chip CONTENTS are loaded
            // on demand by the bubble view via RcsMessageStore.readReactions.
            + "(SELECT COUNT(*) FROM " + DatabaseHelper.RCS_REACTIONS_TABLE + " rr"
            + " WHERE rr." + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID
            + " = " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_MESSAGE_ID
            + " AND " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_MESSAGE_ID
            + " IS NOT NULL)"
            + " as " + ConversationMessageViewColumns.RCS_REACTION_COUNT + ", "
            // The chip CONTENTS, packed into one column so the bubble view renders
            // the reaction chips straight off the cursor (no main-thread DB read,
            // mirroring the WAVE-E aggregate). Each reactor is "emoji<US>reactor"
            // (US = char(31) unit separator) and the reactors are joined with
            // char(30) (record separator), ordered by first-seen timestamp so
            // chip order is stable. We re-aggregate per-emoji + sort in bind().
            // Self rows carry SELF_REACTOR_URI so the own-reaction chip styling +
            // re-tap-removes works. Null for any row with no reactions (the COUNT
            // flag above gates the parse). char(31)/char(30) avoid embedding
            // control bytes in this Java string and never collide with content.
            + "(SELECT group_concat(rr." + DatabaseHelper.RcsReactionColumns.EMOJI
            + " || char(31) || rr." + DatabaseHelper.RcsReactionColumns.REACTOR_URI
            + ", char(30)) FROM " + DatabaseHelper.RCS_REACTIONS_TABLE + " rr"
            + " WHERE rr." + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID
            + " = " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_MESSAGE_ID
            + " AND " + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_MESSAGE_ID
            + " IS NOT NULL"
            + " ORDER BY rr." + DatabaseHelper.RcsReactionColumns.TIMESTAMP + ")"
            + " as " + ConversationMessageViewColumns.REACTIONS_BLOB + ", "
            // The TARGET message's own rcs message-id (the In-Reply-To id space a
            // reaction we SEND must reference). Null for SMS/MMS rows.
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_MESSAGE_ID
            + " as " + ConversationMessageViewColumns.RCS_MESSAGE_ID + ", "
            // Opaque E2EE scheme id (null for plaintext) → per-message lock indicator.
            + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RCS_E2EE_SCHEME_ID
            + " as " + ConversationMessageViewColumns.RCS_E2EE_SCHEME_ID + " ";

    private static final String CONVERSATION_MESSAGES_QUERY_FROM_WHERE_SQL =
            " FROM " + DatabaseHelper.MESSAGES_TABLE
            + " LEFT JOIN " + DatabaseHelper.PARTS_TABLE
            + " ON (" + DatabaseHelper.MESSAGES_TABLE + "." + MessageColumns._ID
            + "=" + DatabaseHelper.PARTS_TABLE + "." + PartColumns.MESSAGE_ID + ") "
            + " LEFT JOIN " + DatabaseHelper.PARTICIPANTS_TABLE
            + " ON (" + DatabaseHelper.MESSAGES_TABLE + '.' +  MessageColumns.SENDER_PARTICIPANT_ID
            + '=' + DatabaseHelper.PARTICIPANTS_TABLE + '.' + ParticipantColumns._ID + ")"
            // Exclude draft messages from main view
            + " WHERE (" + DatabaseHelper.MESSAGES_TABLE + "." + MessageColumns.STATUS
            + " <> " + MessageData.BUGLE_STATUS_OUTGOING_DRAFT;

    // This query is mostly static, except for the injection of conversation id. This is for
    // performance reasons, to ensure that the query uses indices and does not trigger full scans
    // of the messages table. See b/17160946 for more details.
    private static final String CONVERSATION_MESSAGES_QUERY_SQL = "SELECT "
            + CONVERSATION_MESSAGES_QUERY_PROJECTION_SQL
            + CONVERSATION_MESSAGES_QUERY_FROM_WHERE_SQL;

    private static final String CONVERSATION_MESSAGE_IDS_PROJECTION_SQL =
            DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns._ID
                    + " as " + ConversationMessageViewColumns._ID + " ";

    private static final String CONVERSATION_MESSAGES_IDS_QUERY_SQL = "SELECT "
            + CONVERSATION_MESSAGE_IDS_PROJECTION_SQL
            + CONVERSATION_MESSAGES_QUERY_FROM_WHERE_SQL;

    // Note that we sort DESC and ConversationData reverses the cursor.  This is a performance
    // issue (improvement) for large cursors.
    private static final String CONVERSATION_MESSAGES_QUERY_SQL_GROUP_BY =
            " GROUP BY " + DatabaseHelper.PARTS_TABLE + '.' + PartColumns.MESSAGE_ID
          + " ORDER BY "
          + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RECEIVED_TIMESTAMP + " DESC";

    private static final String NOTIFICATION_QUERY_SQL_GROUP_BY =
            " GROUP BY " + DatabaseHelper.PARTS_TABLE + '.' + PartColumns.MESSAGE_ID
          + " ORDER BY "
          + DatabaseHelper.MESSAGES_TABLE + '.' + MessageColumns.RECEIVED_TIMESTAMP + " DESC";

    interface ConversationMessageViewColumns extends BaseColumns {
        String _ID = MessageColumns._ID;
        String CONVERSATION_ID = MessageColumns.CONVERSATION_ID;
        String PARTICIPANT_ID = MessageColumns.SENDER_PARTICIPANT_ID;
        String PARTS_COUNT = "parts_count";
        String SENT_TIMESTAMP = MessageColumns.SENT_TIMESTAMP;
        String RECEIVED_TIMESTAMP = MessageColumns.RECEIVED_TIMESTAMP;
        String SEEN = MessageColumns.SEEN;
        String READ = MessageColumns.READ;
        String PROTOCOL = MessageColumns.PROTOCOL;
        String STATUS = MessageColumns.STATUS;
        String SMS_MESSAGE_URI = MessageColumns.SMS_MESSAGE_URI;
        String SMS_PRIORITY = MessageColumns.SMS_PRIORITY;
        String SMS_MESSAGE_SIZE = MessageColumns.SMS_MESSAGE_SIZE;
        String MMS_SUBJECT = MessageColumns.MMS_SUBJECT;
        String MMS_EXPIRY = MessageColumns.MMS_EXPIRY;
        String RAW_TELEPHONY_STATUS = MessageColumns.RAW_TELEPHONY_STATUS;
        String SELF_PARTICIPANT_ID = MessageColumns.SELF_PARTICIPANT_ID;
        String SENDER_FULL_NAME = ParticipantColumns.FULL_NAME;
        String SENDER_FIRST_NAME = ParticipantColumns.FIRST_NAME;
        String SENDER_DISPLAY_DESTINATION = ParticipantColumns.DISPLAY_DESTINATION;
        String SENDER_NORMALIZED_DESTINATION =
                ParticipantColumns.NORMALIZED_DESTINATION;
        String SENDER_PROFILE_PHOTO_URI = ParticipantColumns.PROFILE_PHOTO_URI;
        String SENDER_CONTACT_ID = ParticipantColumns.CONTACT_ID;
        String SENDER_CONTACT_LOOKUP_KEY = ParticipantColumns.LOOKUP_KEY;
        String PARTS_IDS = "parts_ids";
        String PARTS_CONTENT_TYPES = "parts_content_types";
        String PARTS_CONTENT_URIS = "parts_content_uris";
        String PARTS_WIDTHS = "parts_widths";
        String PARTS_HEIGHTS = "parts_heights";
        String PARTS_TEXTS = "parts_texts";
        String TRANSPORT_TYPE = MessageColumns.TRANSPORT_TYPE;
        String RCS_STATUS = MessageColumns.RCS_STATUS;
        String RCS_DELIVERED_TIMESTAMP = MessageColumns.RCS_DELIVERED_TIMESTAMP;
        String RCS_DISPLAYED_TIMESTAMP = MessageColumns.RCS_DISPLAYED_TIMESTAMP;
        // WAVE-E aggregate group read-receipt columns (computed; not stored).
        String RCS_GROUP_READ_COUNT = "rcs_group_read_count";
        String RCS_GROUP_DELIVERED_COUNT = "rcs_group_delivered_count";
        String RCS_GROUP_MEMBER_COUNT = "rcs_group_member_count";
        String RCS_REACTION_COUNT = "rcs_reaction_count";
        // Emoji-reactions: packed chip contents (group_concat) + the target's own
        // rcs message-id (the In-Reply-To id a reaction we send references).
        String REACTIONS_BLOB = "reactions_blob";
        String RCS_MESSAGE_ID = MessageColumns.RCS_MESSAGE_ID;
        String RCS_E2EE_SCHEME_ID = MessageColumns.RCS_E2EE_SCHEME_ID;
    }

    private static int sIndexIncrementer = 0;

    private static final int INDEX_MESSAGE_ID                    = sIndexIncrementer++;
    private static final int INDEX_CONVERSATION_ID               = sIndexIncrementer++;
    private static final int INDEX_PARTICIPANT_ID                = sIndexIncrementer++;

    private static final int INDEX_PARTS_IDS                     = sIndexIncrementer++;
    private static final int INDEX_PARTS_CONTENT_TYPES           = sIndexIncrementer++;
    private static final int INDEX_PARTS_CONTENT_URIS            = sIndexIncrementer++;
    private static final int INDEX_PARTS_WIDTHS                  = sIndexIncrementer++;
    private static final int INDEX_PARTS_HEIGHTS                 = sIndexIncrementer++;
    private static final int INDEX_PARTS_TEXTS                   = sIndexIncrementer++;

    private static final int INDEX_PARTS_COUNT                   = sIndexIncrementer++;

    private static final int INDEX_SENT_TIMESTAMP                = sIndexIncrementer++;
    private static final int INDEX_RECEIVED_TIMESTAMP            = sIndexIncrementer++;
    private static final int INDEX_SEEN                          = sIndexIncrementer++;
    private static final int INDEX_READ                          = sIndexIncrementer++;
    private static final int INDEX_PROTOCOL                      = sIndexIncrementer++;
    private static final int INDEX_STATUS                        = sIndexIncrementer++;
    private static final int INDEX_SMS_MESSAGE_URI               = sIndexIncrementer++;
    private static final int INDEX_SMS_PRIORITY                  = sIndexIncrementer++;
    private static final int INDEX_SMS_MESSAGE_SIZE              = sIndexIncrementer++;
    private static final int INDEX_MMS_SUBJECT                   = sIndexIncrementer++;
    private static final int INDEX_MMS_EXPIRY                    = sIndexIncrementer++;
    private static final int INDEX_RAW_TELEPHONY_STATUS          = sIndexIncrementer++;
    private static final int INDEX_SELF_PARTICIPIANT_ID          = sIndexIncrementer++;
    private static final int INDEX_SENDER_FULL_NAME              = sIndexIncrementer++;
    private static final int INDEX_SENDER_FIRST_NAME             = sIndexIncrementer++;
    private static final int INDEX_SENDER_DISPLAY_DESTINATION    = sIndexIncrementer++;
    private static final int INDEX_SENDER_NORMALIZED_DESTINATION = sIndexIncrementer++;
    private static final int INDEX_SENDER_PROFILE_PHOTO_URI      = sIndexIncrementer++;
    private static final int INDEX_SENDER_CONTACT_ID             = sIndexIncrementer++;
    private static final int INDEX_SENDER_CONTACT_LOOKUP_KEY     = sIndexIncrementer++;
    private static final int INDEX_TRANSPORT_TYPE                = sIndexIncrementer++;
    private static final int INDEX_RCS_STATUS                    = sIndexIncrementer++;
    private static final int INDEX_RCS_DELIVERED_TIMESTAMP       = sIndexIncrementer++;
    private static final int INDEX_RCS_DISPLAYED_TIMESTAMP       = sIndexIncrementer++;
    private static final int INDEX_RCS_GROUP_READ_COUNT          = sIndexIncrementer++;
    private static final int INDEX_RCS_GROUP_DELIVERED_COUNT     = sIndexIncrementer++;
    private static final int INDEX_RCS_GROUP_MEMBER_COUNT        = sIndexIncrementer++;
    private static final int INDEX_RCS_REACTION_COUNT            = sIndexIncrementer++;
    private static final int INDEX_REACTIONS_BLOB                = sIndexIncrementer++;
    private static final int INDEX_RCS_MESSAGE_ID                = sIndexIncrementer++;
    private static final int INDEX_RCS_E2EE_SCHEME_ID            = sIndexIncrementer++;


    private static final String[] sProjection = {
        ConversationMessageViewColumns._ID,
        ConversationMessageViewColumns.CONVERSATION_ID,
        ConversationMessageViewColumns.PARTICIPANT_ID,

        ConversationMessageViewColumns.PARTS_IDS,
        ConversationMessageViewColumns.PARTS_CONTENT_TYPES,
        ConversationMessageViewColumns.PARTS_CONTENT_URIS,
        ConversationMessageViewColumns.PARTS_WIDTHS,
        ConversationMessageViewColumns.PARTS_HEIGHTS,
        ConversationMessageViewColumns.PARTS_TEXTS,

        ConversationMessageViewColumns.PARTS_COUNT,
        ConversationMessageViewColumns.SENT_TIMESTAMP,
        ConversationMessageViewColumns.RECEIVED_TIMESTAMP,
        ConversationMessageViewColumns.SEEN,
        ConversationMessageViewColumns.READ,
        ConversationMessageViewColumns.PROTOCOL,
        ConversationMessageViewColumns.STATUS,
        ConversationMessageViewColumns.SMS_MESSAGE_URI,
        ConversationMessageViewColumns.SMS_PRIORITY,
        ConversationMessageViewColumns.SMS_MESSAGE_SIZE,
        ConversationMessageViewColumns.MMS_SUBJECT,
        ConversationMessageViewColumns.MMS_EXPIRY,
        ConversationMessageViewColumns.RAW_TELEPHONY_STATUS,
        ConversationMessageViewColumns.SELF_PARTICIPANT_ID,
        ConversationMessageViewColumns.SENDER_FULL_NAME,
        ConversationMessageViewColumns.SENDER_FIRST_NAME,
        ConversationMessageViewColumns.SENDER_DISPLAY_DESTINATION,
        ConversationMessageViewColumns.SENDER_NORMALIZED_DESTINATION,
        ConversationMessageViewColumns.SENDER_PROFILE_PHOTO_URI,
        ConversationMessageViewColumns.SENDER_CONTACT_ID,
        ConversationMessageViewColumns.SENDER_CONTACT_LOOKUP_KEY,
        ConversationMessageViewColumns.TRANSPORT_TYPE,
        ConversationMessageViewColumns.RCS_STATUS,
        ConversationMessageViewColumns.RCS_DELIVERED_TIMESTAMP,
        ConversationMessageViewColumns.RCS_DISPLAYED_TIMESTAMP,
        ConversationMessageViewColumns.RCS_GROUP_READ_COUNT,
        ConversationMessageViewColumns.RCS_GROUP_DELIVERED_COUNT,
        ConversationMessageViewColumns.RCS_GROUP_MEMBER_COUNT,
        ConversationMessageViewColumns.RCS_REACTION_COUNT,
        ConversationMessageViewColumns.REACTIONS_BLOB,
        ConversationMessageViewColumns.RCS_MESSAGE_ID,
        ConversationMessageViewColumns.RCS_E2EE_SCHEME_ID,
    };

    public static String[] getProjection() {
        return sProjection;
    }
}
