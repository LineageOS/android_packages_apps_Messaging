/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
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

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.IRcsProvider;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsSendResult;

import android.content.ContentValues;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.e2ee.E2eeSendGate;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsCallbackRouter;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.RcsSendStatus;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Action used to convert a draft message to an outgoing message. Its writes SMS messages to
 * the telephony db, but {@link SendMessageAction} is responsible for inserting MMS message into
 * the telephony DB. The latter also does the actual sending of the message in the background.
 * The latter is also responsible for re-sending a failed message.
 */
public class InsertNewMessageAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static long sLastSentMessageTimestamp = -1;

    /**
     * Insert message (no listener)
     */
    public static void insertNewMessage(final MessageData message) {
        final InsertNewMessageAction action = new InsertNewMessageAction(message);
        action.start();
    }

    /**
     * Insert message (no listener) with a given non-default subId.
     */
    public static void insertNewMessage(final MessageData message, final int subId) {
        Assert.isFalse(subId == ParticipantData.DEFAULT_SELF_SUB_ID);
        final InsertNewMessageAction action = new InsertNewMessageAction(message, subId);
        action.start();
    }

    /**
     * Insert a message and FORCE it down the legacy SMS path, bypassing the
     * RCS-first routing fork in {@link #executeAction()}. Used exclusively by
     * the user-selected "Send as SMS" escape hatch for a failed RCS message --
     * this is never an automatic fallback. The message must be SMS-protocol and
     * single-recipient; otherwise it already routes as SMS/MMS without forcing.
     */
    public static void insertNewSmsMessage(final MessageData message) {
        final InsertNewMessageAction action = new InsertNewMessageAction(message);
        action.actionParameters.putBoolean(KEY_FORCE_SMS, true);
        action.start();
    }

    /**
     * Insert message (no listener)
     */
    public static void insertNewMessage(final int subId, final String recipients,
            final String messageText, final String subject) {
        final InsertNewMessageAction action = new InsertNewMessageAction(
                subId, recipients, messageText, subject);
        action.start();
    }

    public static long getLastSentMessageTimestamp() {
        return sLastSentMessageTimestamp;
    }

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_RECIPIENTS = "recipients";
    private static final String KEY_MESSAGE_TEXT = "message_text";
    private static final String KEY_SUBJECT_TEXT = "subject_text";
    // When true, skip the RCS-first routing fork and send over legacy SMS.
    // Set only by the user-selected "Send as SMS" path; never an auto fallback.
    private static final String KEY_FORCE_SMS = "force_sms";

    private InsertNewMessageAction(final MessageData message) {
        this(message, ParticipantData.DEFAULT_SELF_SUB_ID);
        actionParameters.putParcelable(KEY_MESSAGE, message);
    }

    private InsertNewMessageAction(final MessageData message, final int subId) {
        super();
        actionParameters.putParcelable(KEY_MESSAGE, message);
        actionParameters.putInt(KEY_SUB_ID, subId);
    }

    private InsertNewMessageAction(final int subId, final String recipients,
            final String messageText, final String subject) {
        super();
        if (TextUtils.isEmpty(recipients) || TextUtils.isEmpty(messageText)) {
            Assert.fail("InsertNewMessageAction: Can't have empty recipients or message");
        }
        actionParameters.putInt(KEY_SUB_ID, subId);
        actionParameters.putString(KEY_RECIPIENTS, recipients);
        actionParameters.putString(KEY_MESSAGE_TEXT, messageText);
        actionParameters.putString(KEY_SUBJECT_TEXT, subject);
    }

    /**
     * Add message to database in pending state and queue actual sending
     */
    @Override
    protected Object executeAction() {
        MessageData message = actionParameters.getParcelable(KEY_MESSAGE, MessageData.class);
        if (message == null) {
            LogUtil.i(TAG, "InsertNewMessageAction: Creating MessageData with provided data");
            message = createMessage();
            if (message == null) {
                LogUtil.w(TAG, "InsertNewMessageAction: Could not create MessageData");
                return null;
            }
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String conversationId = message.getConversationId();

        final ParticipantData self = getSelf(db, conversationId, message);
        if (self == null) {
            return null;
        }
        message.bindSelfId(self.getId());
        // If the user taps the Send button before the conversation draft is created/loaded by
        // ReadDraftDataAction (maybe the action service thread was busy), the MessageData may not
        // have the participant id set. It should be equal to the self id, so we'll use that.
        if (message.getParticipantId() == null) {
            message.bindParticipantId(self.getId());
        }

        final long timestamp = System.currentTimeMillis();
        final ArrayList<String> recipients =
                BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
        if (recipients.size() < 1) {
            LogUtil.w(TAG, "InsertNewMessageAction: message recipients is empty");
            return null;
        }
        final int subId = self.getSubId();
        LogUtil.i(TAG, "InsertNewMessageAction: inserting new message for subId " + subId);
        actionParameters.putInt(KEY_SUB_ID, subId);

        // RCS-first routing (Phase 1, 1-1 text only). If the provider says RCS
        // is affirmatively up for this sub AND the synchronous send is accepted,
        // land an RCS-shaped row and return. Anything else (RCS not available,
        // peer not RCS, provider unbound, internal error) falls through to the
        // existing SMS/MMS path unchanged -- byte-identical to today when RCS is
        // not up.
        final boolean forceSms = actionParameters.getBoolean(KEY_FORCE_SMS, false);

        // FLOW4b group-RCS fork. Gated behind RouteSelector.GROUP_RCS_ENABLED.
        // For a multi-recipient text send, tryInsertSendingRcsGroupMessage maps the
        // GROUP_ID onto the conversation, sends via the provider, and lands an RCS
        // row. Any reject falls through to the unchanged MMS path below.
        //
        // CORRECTED 2026-09-08 — this comment used to say the UI-initiated group
        // create "lands here lazily on first send". IT DOES NOT, AND CANNOT. The
        // fork below is gated on convIsRcsGroup, i.e. on rcs_group_id ALREADY being
        // non-empty, and only the explicit New-group create flow ever sets it. A
        // conversation made any other way can therefore never take the RCS path on
        // any later send, however many times you send into it.
        //
        // THE USER-VISIBLE CONSEQUENCE, device-measured: the FAB "Start chat" flow
        // (start_new_conversation_button -> add participants -> confirm) produces a
        // group MMS -- compose hint "Text message" with no "RCS", an MMS indicator,
        // EMPTY rcs_group_id, and a real positive sms_thread_id. Only the
        // conversation-list overflow "New group" (action_new_group) produces an RCS
        // group: hint "Text message - RCS", a "Send RCS message" button, and a
        // synthetic negative sms_thread_id.
        //
        // This wrong comment has already cost real time: it is why an earlier
        // attempt built a group the obvious way, got MMS, and recorded the
        // too-broad conclusion that SENDTO "produces a group MMS". The actual rule
        // is narrower and is about WHICH CREATE FLOW was used.
        // An ESTABLISHED RCS group (conversations.rcs_group_id set by the explicit
        // New-group create flow) routes a TEXT message over the provider to its
        // GROUP_ID, regardless of the SMS/MMS protocol the draft carries -- a
        // multi-recipient (incl 2-person) group draft is PROTOCOL_MMS, so the old
        // PROTOCOL_SMS guard wrongly excluded every group send and fell to MMS.
        // Attachments (FT not wired this wave) and plain non-RCS group-MMS
        // conversations fall through to the unchanged MMS path below.
        final boolean convIsRcsGroup = !TextUtils.isEmpty(
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId));
        if (!forceSms
                && com.android.messaging.rcs.RouteSelector.GROUP_RCS_ENABLED
                && convIsRcsGroup
                && isTextOnlyMessage(message)
                && !TextUtils.isEmpty(message.getMessageText())
                && tryInsertSendingRcsGroupMessage(message, subId, recipients,
                        timestamp, conversationId)) {
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
            MessagingContentProvider.notifyConversationListChanged();
            return message;
        }

        // Group-RCS MEDIA fork. An ESTABLISHED RCS group draft carrying a
        // media attachment routes the FT-HTTP file over the provider to the
        // conversation's GROUP_ID (server fans out), mirroring the group-text fork
        // above. Any reject falls through to the unchanged group-MMS path below.
        if (!forceSms
                && com.android.messaging.rcs.RouteSelector.GROUP_RCS_ENABLED
                && convIsRcsGroup
                && firstMediaAttachment(message) != null
                && tryInsertSendingRcsGroupFile(message, subId, timestamp, conversationId)) {
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
            MessagingContentProvider.notifyConversationListChanged();
            return message;
        }

        if (!forceSms
                && message.getProtocol() == MessageData.PROTOCOL_SMS
                && recipients.size() == 1
                && !TextUtils.isEmpty(message.getMessageText())
                && tryInsertSendingRcsMessage(message, subId, recipients.get(0),
                        timestamp, conversationId)) {
            // Can now clear draft from conversation (deleting attachments if necessary)
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
            MessagingContentProvider.notifyConversationListChanged();
            // No ProcessPendingMessagesAction: the RCS send already happened
            // synchronously over the provider; the terminal status arrives async
            // via UpdateRcsMessageStatusAction.
            return message;
        }

        // FLOW4c 1-1 RCS MEDIA (FT-HTTP). A draft carrying a single media
        // attachment is PROTOCOL_MMS, so the text branch above skips it. When RCS
        // is up for the sub, the recipient is a single party, and the draft has a
        // resolved media part, hand the picked attachment (content URI / openable
        // FD, NOT inline bytes -- the Binder 1 MiB cap) to the provider via
        // IRcsProvider.sendFile and land an RCS-shaped local row. Any reject (RCS
        // down, peer not RCS, provider unbound, internal error) falls through to
        // the unchanged MMS path below -- byte-identical to today when RCS is not
        // up. ZERO transport awareness here: we only pass a content URI + MIME;
        // the provider does the FT-HTTP upload + kind=36 SendMessage internally.
        if (!forceSms
                && recipients.size() == 1
                && firstMediaAttachment(message) != null
                && tryInsertSendingRcsFile(message, subId, recipients.get(0),
                        timestamp, conversationId)) {
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
            MessagingContentProvider.notifyConversationListChanged();
            return message;
        }

        // TODO: Work out whether to send with SMS or MMS (taking into account recipients)?
        final boolean isSms = (message.getProtocol() == MessageData.PROTOCOL_SMS);
        if (isSms) {
            String sendingConversationId = conversationId;
            if (recipients.size() > 1) {
                // Broadcast SMS - put message in "fake conversation" before farming out to real 1:1
                final long laterTimestamp = timestamp + 1;
                // Send a single message
                insertBroadcastSmsMessage(conversationId, message, subId,
                        laterTimestamp, recipients);

                sendingConversationId = null;
            }

            for (final String recipient : recipients) {
                // Start actual sending
                insertSendingSmsMessage(message, subId, recipient,
                        timestamp, sendingConversationId);
            }

            // Can now clear draft from conversation (deleting attachments if necessary)
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    null /* message */, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
        } else {
            final long timestampRoundedToSecond = 1000 * ((timestamp + 500) / 1000);
            // Write place holder message directly referencing parts from the draft
            final MessageData messageToSend = insertSendingMmsMessage(conversationId,
                    message, timestampRoundedToSecond);

            // Can now clear draft from conversation (preserving attachments which are now
            // referenced by messageToSend)
            BugleDatabaseOperations.updateDraftMessageData(db, conversationId,
                    messageToSend, BugleDatabaseOperations.UPDATE_MODE_CLEAR_DRAFT);
        }
        MessagingContentProvider.notifyConversationListChanged();
        ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(false, this);

        return message;
    }

    private ParticipantData getSelf(
            final DatabaseWrapper db, final String conversationId, final MessageData message) {
        ParticipantData self;
        // Check if we are asked to bind to a non-default subId. This is directly passed in from
        // the UI thread so that the sub id may be locked as soon as the user clicks on the Send
        // button.
        final int requestedSubId = actionParameters.getInt(
                KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);
        if (requestedSubId != ParticipantData.DEFAULT_SELF_SUB_ID) {
            self = BugleDatabaseOperations.getOrCreateSelf(db, requestedSubId);
        } else {
            String selfId = message.getSelfId();
            if (selfId == null) {
                // The conversation draft provides no self id hint, meaning that 1) conversation
                // self id was not loaded AND 2) the user didn't pick a SIM from the SIM selector.
                // In this case, use the conversation's self id.
                final ConversationListItemData conversation =
                        ConversationListItemData.getExistingConversation(db, conversationId);
                if (conversation != null) {
                    selfId = conversation.getSelfId();
                } else {
                    LogUtil.w(LogUtil.BUGLE_DATAMODEL_TAG, "Conversation " + conversationId +
                            "already deleted before sending draft message " +
                            message.getMessageId() + ". Aborting InsertNewMessageAction.");
                    return null;
                }
            }

            // We do not use SubscriptionManager.DEFAULT_SUB_ID for sending a message, so we need
            // to bind the message to the system default subscription if it's unbound.
            final ParticipantData unboundSelf = BugleDatabaseOperations.getExistingParticipant(
                    db, selfId);
            if (unboundSelf.getSubId() == ParticipantData.DEFAULT_SELF_SUB_ID) {
                final int defaultSubId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
                self = BugleDatabaseOperations.getOrCreateSelf(db, defaultSubId);
            } else {
                self = unboundSelf;
            }
        }
        return self;
    }

    /** Create MessageData using KEY_RECIPIENTS, KEY_MESSAGE_TEXT and KEY_SUBJECT */
    private MessageData createMessage() {
        // First find the thread id for this list of participants.
        final String recipientsList = actionParameters.getString(KEY_RECIPIENTS);
        final String messageText = actionParameters.getString(KEY_MESSAGE_TEXT);
        final String subjectText = actionParameters.getString(KEY_SUBJECT_TEXT);
        final int subId = actionParameters.getInt(
                KEY_SUB_ID, ParticipantData.DEFAULT_SELF_SUB_ID);

        final ArrayList<ParticipantData> participants = new ArrayList<>();
        for (final String recipient : recipientsList.split(",")) {
            participants.add(ParticipantData.getFromRawPhoneBySimLocale(recipient, subId));
        }
        if (participants.size() == 0) {
            Assert.fail("InsertNewMessage: Empty participants");
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        BugleDatabaseOperations.sanitizeConversationParticipants(participants);
        final ArrayList<String> recipients =
                BugleDatabaseOperations.getRecipientsFromConversationParticipants(participants);
        if (recipients.size() == 0) {
            Assert.fail("InsertNewMessage: Empty recipients");
            return null;
        }

        final long threadId = MmsUtils.getOrCreateThreadId(Factory.get().getApplicationContext(),
                recipients);

        if (threadId < 0) {
            Assert.fail("InsertNewMessage: Couldn't get threadId in SMS db for these recipients: "
                    + recipients);
            // TODO: How do we fail the action?
            return null;
        }

        final String conversationId = BugleDatabaseOperations.getOrCreateConversation(db, threadId,
                false, participants);

        final ParticipantData self = BugleDatabaseOperations.getOrCreateSelf(db, subId);

        if (TextUtils.isEmpty(subjectText)) {
            return MessageData.createDraftSmsMessage(conversationId, self.getId(), messageText);
        } else {
            return MessageData.createDraftMmsMessage(conversationId, self.getId(), messageText,
                    subjectText);
        }
    }

    private void insertBroadcastSmsMessage(final String conversationId,
            final MessageData message, final int subId, final long laterTimestamp,
            final ArrayList<String> recipients) {
        LogUtil.v(TAG, "InsertNewMessageAction: Inserting broadcast SMS message "
                + message.getMessageId());
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Inform sync that message is being added at timestamp
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(laterTimestamp);

        final long threadId = BugleDatabaseOperations.getThreadId(db, conversationId);
        final String address = TextUtils.join(" ", recipients);

        final String messageText = message.getMessageText();
        // Insert message into telephony database sms message table
        final Uri messageUri = MmsUtils.insertSmsMessage(context,
                Telephony.Sms.CONTENT_URI,
                subId,
                address,
                messageText,
                laterTimestamp,
                Telephony.Sms.STATUS_COMPLETE,
                Telephony.Sms.MESSAGE_TYPE_SENT, threadId);
        if (messageUri != null && !TextUtils.isEmpty(messageUri.toString())) {
            db.beginTransaction();
            try {
                message.updateSendingMessage(conversationId, messageUri, laterTimestamp);
                message.markMessageSent(laterTimestamp);

                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                        conversationId, message.getMessageId(), laterTimestamp,
                        false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: Inserted broadcast SMS message "
                        + message.getMessageId() + ", uri = " + message.getSmsMessageUri());
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } else {
            // Ignore error as we only really care about the individual messages?
            LogUtil.e(TAG,
                    "InsertNewMessageAction: No uri for broadcast SMS " + message.getMessageId()
                    + " inserted into telephony DB");
        }
    }

    /**
     * Insert SMS messaging into our database and telephony db.
     */
    private MessageData insertSendingSmsMessage(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String sendingConversationId) {
        sLastSentMessageTimestamp = timestamp;

        final Context context = Factory.get().getApplicationContext();

        // Inform sync that message is being added at timestamp
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Send a single message
        long threadId;
        String conversationId;
        if (sendingConversationId == null) {
            // For 1:1 message generated sending broadcast need to look up threadId+conversationId
            threadId = MmsUtils.getOrCreateSmsThreadId(context, recipient);
            conversationId = BugleDatabaseOperations.getOrCreateConversationFromRecipient(
                    db, threadId, false /* sender blocked */,
                    ParticipantData.getFromRawPhoneBySimLocale(recipient, subId));
        } else {
            // Otherwise just look up threadId
            threadId = BugleDatabaseOperations.getThreadId(db, sendingConversationId);
            conversationId = sendingConversationId;
        }

        final String messageText = content.getMessageText();

        // Insert message into telephony database sms message table
        final Uri messageUri = MmsUtils.insertSmsMessage(context,
                Telephony.Sms.CONTENT_URI,
                subId,
                recipient,
                messageText,
                timestamp,
                Telephony.Sms.STATUS_NONE,
                Telephony.Sms.MESSAGE_TYPE_SENT, threadId);

        MessageData message = null;
        if (messageUri != null && !TextUtils.isEmpty(messageUri.toString())) {
            db.beginTransaction();
            try {
                message = MessageData.createDraftSmsMessage(conversationId,
                        content.getSelfId(), messageText);
                message.updateSendingMessage(conversationId, messageUri, timestamp);

                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

                // Do not update the conversation summary to reflect autogenerated 1:1 messages
                if (sendingConversationId != null) {
                    BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                            conversationId, message.getMessageId(), timestamp,
                            false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: Inserted SMS message "
                        + message.getMessageId() + " (uri = " + message.getSmsMessageUri()
                        + ", timestamp = " + message.getReceivedTimeStamp() + ")");
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } else {
            LogUtil.e(TAG, "InsertNewMessageAction: No uri for SMS inserted into telephony DB");
        }

        return message;
    }

    /**
     * Attempt to send a 1-1 text message over RCS and, on acceptance, land an
     * RCS-shaped row in messaging.db.
     *
     * <p>Modeled on {@link #insertSendingSmsMessage} and the receive template
     * {@link ReceiveRcsMessageAction}. Returns {@code true} iff the provider
     * accepted ownership of the send (and the local row was inserted); returns
     * {@code false} for every fall-back-to-SMS case (RCS not available for the
     * sub, peer not RCS, provider unbound, rate limited, internal error) WITHOUT
     * touching the database, so the caller proceeds with the unchanged SMS path.
     *
     * <p>The send is attempted BEFORE any DB write so a non-accept leaves no
     * orphan row. The client-minted UUID ({@link RcsOutgoingMessage#messageId})
     * is stored in the additive {@code rcs_message_id} column so the later async
     * {@link UpdateRcsMessageStatusAction} can correlate the status/IMDN
     * callback back to the local row.
     */
    private boolean tryInsertSendingRcsMessage(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // Multi-transport framework (design §2, §5.4): the registry is the live RCS
        // path. Route through the transport RouteSelector picked for THIS sub (the
        // provider, carrier-IMS, or a future OTT provider). On a provider-only device the
        // selected transport IS the legacy ProviderTransport singleton, so behaviour is
        // byte-identical to the pre-registry path. If nothing is selected yet (selection
        // not run / registry not up), fall back to the legacy singleton so a
        // provider-only device still works exactly as before.
        final RouteSelector routeSelector;
        final RcsTransport transport;
        try {
            routeSelector = RcsCallbackRouter.getInstance(context).getRouteSelector();
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected != null) ? selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "InsertNewMessageAction: RCS transport unavailable", t);
            return false;
        }

        // Conservative gate: only attempt RCS when it is affirmatively up for
        // this sub. Otherwise behavior is byte-identical to today (pure SMS).
        if (!routeSelector.isRcsAvailableForSub(subId)) {
            return false;
        }

        // Per-recipient capability gate. Runs on the DataModel action thread
        // (this method already does), which is where the off-main
        // lookupRcsCapability contract requires it. Tri-state:
        //   CAP_SMS_ONLY -> peer is provably not on RCS; skip the RCS attempt
        //                   entirely and fall to the unchanged SMS path.
        //   CAP_RCS      -> peer is on RCS; proceed.
        //   CAP_UNKNOWN  -> unknown (not attached, transient, or never looked
        //                   up); proceed OPTIMISTICALLY exactly as today and let
        //                   the synchronous sendMessage REASON_PEER_NOT_RCS
        //                   result below be the authoritative backstop.
        // Tachyon requires a full E.164 destination. The conversation
        // participant's send_destination can be the national / dialable form
        // (e.g. "2025550101" with no country code), which the server rejects
        // with NOT_FOUND tachyonerror=40. Canonicalize to E.164 the same way
        // the capability cache (normalizeForCache) and the typing path do, so a
        // UI-composed send matches the working debug-broadcast / typing paths.
        final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(recipient);
        final String dest = TextUtils.isEmpty(canonical) ? recipient : canonical;

        final int peerCap = transport.lookupRcsCapability(subId, dest);
        if (peerCap == IRcsProvider.CAP_SMS_ONLY) {
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: peer not RCS-capable (cap="
                        + peerCap + "); using SMS");
            }
            return false;
        }

        final String messageText = content.getMessageText();
        if (TextUtils.isEmpty(messageText)) {
            return false;
        }

        // Mint the correlation UUID up front so it can be both sent over the wire
        // and stored on the local row.
        final String rcsMessageId = UUID.randomUUID().toString();

        // E2EE gate: resolve + persist the scheme for this conversation.
        //   MLS      -> the app carries the ciphertext over its own CPM/MSRP transport
        //               (transport.sendMlsMessage; the transport owns enrol/claim/
        //               group/encrypt and reports status async like a plaintext RCS send).
        //   ETOUFFEE -> the plaintext send below (the PROVIDER encrypts transparently);
        //               we still tag the row so the padlock lights.
        //   null     -> plaintext RCS.
        // Any gate failure degrades to plaintext (resolveForSend never throws).
        final String e2eeScheme = E2eeSendGate.get().resolveForSend(
                conversationId, subId, dest, false /* isGroup */);

        {
            // Send over the provider AIDL as plaintext. Any end-to-end encryption on this route is
            // the PROVIDER's own scheme, applied inside the provider; this layer hands it the text.
            final RcsSendResult result = transport.sendMessage(new RcsOutgoingMessage(
                    subId, rcsMessageId, dest,
                    "text/plain;charset=UTF-8",
                    messageText.getBytes(StandardCharsets.UTF_8),
                    /*e2eeSchemeId=*/ null,
                    /*groupId=*/ null));

            if (result == null || !result.accepted) {
                LogUtil.i(TAG, "InsertNewMessageAction: RCS send not accepted (reason="
                        + (result != null ? result.reasonCode : -1)
                        + "); falling back to SMS");
                // Authoritative backstop: if the provider rejected specifically
                // because the peer isn't on RCS, teach the per-recipient cache so the
                // next send to this recipient skips the RCS attempt up front (and the
                // compose bar reflects SMS).
                if (result != null && result.reasonCode == RcsSendResult.REASON_PEER_NOT_RCS) {
                    routeSelector.notePeerNotRcs(recipient);
                }
                return false;
            }
        }

        // The scheme to stamp on our OWN sent row, so the per-message padlock shows: the
        // provider's scheme iff the gate picked it, else null (plaintext, no lock).
        final String stampedScheme =
                RcsE2eeScheme.ETOUFFEE.equals(e2eeScheme) ? RcsE2eeScheme.ETOUFFEE : null;

        sLastSentMessageTimestamp = timestamp;

        // Inform sync that message is being added at timestamp
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMessage(
                    conversationId, content.getSelfId(), messageText);
            // Move to OUTGOING_YET_TO_SEND with no telephony Uri (RCS is not SMS),
            // mirroring the receive path's "SMS-shaped, no Uri" insert.
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);

            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            // Second, column-agnostic write of the RCS metadata (transport_type +
            // rcs_message_id) -- does NOT touch MessageData's positional insert.
            //
            // When the app-owned arm above measured the outcome, that measurement
            // lands here, in the same write, rather than being dropped. rcs_status gets the
            // provider's own STATUS_SENT / STATUS_FAILED so the row is indistinguishable from one
            // that arrived via onMessageStatus, and message_status gets the mapping
            // UpdateRcsMessageStatusAction.mapBugleStatus applies to exactly that callback. Both
            // report the SEND and neither reports DELIVERY: a later IMDN still upgrades the row to
            // OUTGOING_DELIVERED, and its absence still means nothing was ever confirmed.
            // No arm on this route answers synchronously, so the row goes in with no RCS status
            // and a provider callback owns it from here.
            final ContentValues rcsVals =
                    RcsMessageStore.rcsMetaValues(rcsMessageId, RcsConstants.RCS_STATUS_NONE);
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), rcsVals);

            // E2EE: stamp the resolved scheme on our own sent row so
            // the per-message padlock (ConversationMessageView) lights on outgoing
            // bubbles too, mirroring the inbound stamp in ReceiveRcsMessageAction.
            if (stampedScheme != null) {
                final ContentValues e2eeVals = new ContentValues();
                e2eeVals.put(DatabaseHelper.MessageColumns.RCS_E2EE_SCHEME_ID, stampedScheme);
                BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), e2eeVals);
            }

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);

            db.setTransactionSuccessful();

            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "InsertNewMessageAction: Inserted RCS message "
                        + message.getMessageId() + " (rcsId=" + rcsMessageId
                        + ", timestamp = " + timestamp + ")");
            }
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /**
     * FLOW4b: attempt to send a multi-recipient text message over provider-RCS
     * as a group. Resolves (or, for a never-grouped conversation, creates) the
     * opaque GROUP_ID, persists the {@code conversations.rcs_group_id} mapping,
     * sends via {@code ProviderTransport.sendGroupMessage}, and lands an
     * RCS-shaped outgoing row (mirroring the 1:1 path). Returns {@code false}
     * (caller falls through to the unchanged MMS path) when group-RCS is not
     * available, the create/send is rejected, or the provider is unbound.
     *
     * <p>Only reached when {@link
     * com.android.messaging.rcs.RouteSelector#GROUP_RCS_ENABLED} is true, so
     * with the flag off recipients>1 routing is byte-identical to today's MMS.
     */
    /**
     * True when the message carries no attachment parts (text-only). Group RCS
     * handles text only this wave; a group MMS with an attachment falls to MMS.
     */
    private static boolean isTextOnlyMessage(final MessageData message) {
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return the first media (attachment) part of a draft, or {@code null} if it
     * carries none. Used to detect a single-attachment 1-1 draft that can route
     * over RCS FT-HTTP.
     */
    private static MessagePartData firstMediaAttachment(final MessageData message) {
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment() && part.getContentUri() != null) {
                return part;
            }
        }
        return null;
    }

    /**
     * FLOW4c: attempt to send a 1-1 MEDIA message over RCS FT-HTTP and, on
     * acceptance, land an RCS-shaped media row in messaging.db.
     *
     * <p>Modeled on {@link #tryInsertSendingRcsMessage}: same sub-level + per-peer
     * capability gate, the send is attempted BEFORE any DB write so a non-accept
     * leaves no orphan row, and the client-minted UUID is stored in
     * {@code rcs_message_id} for later status correlation. Returns {@code false}
     * for every fall-back-to-MMS case (RCS down, peer not RCS, provider unbound,
     * open-URI failure, rate limited, internal error) WITHOUT touching the
     * database, so the caller proceeds with the unchanged MMS path.
     *
     * <p><b>With ONE exception: a REFUSAL returns
     * {@code true}.</b> When the conversation is one the app is presenting as MLS-encrypted and the
     * file cannot be sealed, the attachment must not reach MMS either, so the method lands a failed
     * row through {@link #insertRefusedRcsMediaMessage} instead of falling through. The gate runs
     * ahead of every other {@code return false} in the method for exactly that reason.
     *
     * <p><b>Bytes never cross the binder.</b> We hand the picked attachment's
     * resolved {@code content://} URI (the provider opens an FD from it -- the
     * Binder 1 MiB parcel cap forbids inline bytes) to
     * {@link ProviderTransport#sendFile}. The provider does the FT-HTTP upload +
     * kind=36 SendMessage internally; this method has ZERO transport awareness.
     */
    private boolean tryInsertSendingRcsFile(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // THESE TWO MOVED TO THE TOP OF THE METHOD, AND THE MOVE IS THE FIX, NOT TIDYING.
        // The MLS gate below has to run before ANY `return false` in this method, because
        // every one of them is a fall-through to the unchanged MMS path -- so a refusal expressed
        // downstream of one would put the same cleartext on a different transport. Both of these
        // are pure: no transport, no binder, no database.
        final MessagePartData media = firstMediaAttachment(content);
        if (media == null || media.getContentUri() == null
                || TextUtils.isEmpty(media.getContentType())) {
            // Nothing to send and nothing to leak -- the caller's own media fork already required a
            // resolved attachment, so this is the defensive arm, and it must stay ahead of the gate:
            // refusing a draft that carries no media would land an empty failed bubble.
            return false;
        }

        // Canonicalize to E.164 (same as the text path -- the server rejects the
        // national/dialable form). It is ALSO the key the gate reads: the national form keys
        // nothing, so passing it would answer NO_MLS_STATE for a conversation we hold state for,
        // and that is the direction that fails OPEN.
        final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(recipient);
        final String dest = TextUtils.isEmpty(canonical) ? recipient : canonical;

        // Multi-transport framework (design §2, §5.4): route the rich provider-only FT
        // surface through the transport RouteSelector picked for THIS sub. The rich
        // ops (sendFile) live only on ProviderTransport, so use the selected transport
        // when it IS one, else fall back to the legacy singleton -- byte-identical to
        // the pre-registry path on a provider-only device (same fallback as
        // tryInsertSendingRcsMessage).
        final ProviderTransport transport;
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "tryInsertSendingRcsFile: RCS transport unavailable", t);
            return false;
        }

        // Sub-level gate (identical to the 1:1 text path).
        if (!transport.getRouteSelector().isRcsAvailableForSub(subId)) {
            return false;
        }

        // Per-peer capability gate (tri-state, same as the text path).
        final int peerCap = transport.lookupRcsCapability(subId, dest);
        if (peerCap == IRcsProvider.CAP_SMS_ONLY) {
            return false;
        }

        // The caption rides as the media part's text (the existing caption+media
        // bubble); pass it to the provider so it can be packed alongside the FT.
        final String caption = media.getText();
        final Uri contentUri = media.getContentUri();
        final String contentType = media.getContentType();
        // MessagePartData carries no original file name; the provider derives one
        // from the content URI / MIME when building the FT-HTTP descriptor.
        final String fileName = null;

        final String rcsMessageId = UUID.randomUUID().toString();

        // Synchronous send (off-main: this Action runs on the action-service
        // thread, which is where sendFile's open-URI + binder call must happen).
        // Done before any DB write.
        final RcsSendResult result = transport.sendFile(subId, rcsMessageId, dest,
                contentUri, contentType, fileName, MessagePartData.UNSPECIFIED_SIZE, caption);

        if (result == null || !result.accepted) {
            LogUtil.i(TAG, "tryInsertSendingRcsFile: RCS file send not accepted (reason="
                    + (result != null ? result.reasonCode : -1) + "); falling back to MMS");
            if (result != null && result.reasonCode == RcsSendResult.REASON_PEER_NOT_RCS) {
                transport.notePeerNotRcs(recipient);
            }
            return false;
        }

        sLastSentMessageTimestamp = timestamp;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMediaMessage(
                    conversationId, content.getSelfId(), contentType, contentUri, caption);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);

            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                    RcsMessageStore.rcsMetaValues(rcsMessageId, RcsConstants.RCS_STATUS_NONE));

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);

            db.setTransactionSuccessful();
            LogUtil.i(TAG, "tryInsertSendingRcsFile: inserted RCS media message "
                    + message.getMessageId() + " (rcsId=" + rcsMessageId + ", mime="
                    + contentType + ")");
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /**
     * Group RCS MEDIA (FT-HTTP). An established RCS-group draft carrying
     * a single media attachment routes the picked file (content URI / FD, NOT
     * inline bytes -- the Binder 1 MiB cap) to the conversation's GROUP_ID via
     * {@link ProviderTransport#sendFile} (server fans out), then lands an
     * RCS-shaped local media row in the group conversation. Mirrors
     * {@link #tryInsertSendingRcsFile} (1-1) and
     * {@link #tryInsertSendingRcsGroupMessage} (group text). Any reject returns
     * false and the caller falls back to the unchanged group-MMS path.
     *
     * <p><b>Except an MLS REFUSAL, which returns {@code true}</b> — on a group
     * the app is presenting as encrypted, the file must not reach the MMS path either, so the
     * method lands a failed row through {@link #insertRefusedRcsMediaMessage}. The gate runs ahead
     * of every other {@code return false} here so that no earlier rejection can carry the file to
     * MMS before the question is asked.
     */
    private boolean tryInsertSendingRcsGroupFile(final MessageData content,
            final int subId, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // THESE THREE MOVED TO THE TOP OF THE METHOD FOR THE SAME REASON AS THE 1:1 SIBLING:
        // every `return false` below is a fall-through to the unchanged group-MMS path, so
        // the MLS gate has to run ahead of all of them or a refusal downstream of one would put the
        // same cleartext on a different transport. The two reads are a database lookup and a walk
        // over the draft's parts -- no transport and no binder, so nothing about the ordering can
        // fail in a way the old ordering could not.
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String groupId =
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        if (TextUtils.isEmpty(groupId)) {
            // The established-group dispatch guard already required this; defensive. Ahead of the
            // gate deliberately: with no rcs_group_id there is no MLS group to be honest about, and
            // this is not an RCS group conversation at all.
            return false;
        }

        final MessagePartData media = firstMediaAttachment(content);
        if (media == null || media.getContentUri() == null
                || TextUtils.isEmpty(media.getContentType())) {
            // Also ahead of the gate: refusing a draft that carries no media would land an empty
            // failed bubble on the thread.
            return false;
        }

        // Multi-transport framework (design §2, §5.4): route the rich provider-only
        // group-FT surface through the sub's selected transport when it IS a
        // ProviderTransport, else fall back to the legacy singleton (byte-identical on a
        // provider-only device).
        final ProviderTransport transport;
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "tryInsertSendingRcsGroupFile: RCS transport unavailable", t);
            return false;
        }

        if (!transport.getRouteSelector().isGroupRcsAvailableForSub(subId)) {
            return false;
        }

        final String caption = media.getText();
        final Uri contentUri = media.getContentUri();
        final String contentType = media.getContentType();
        // MessagePartData carries no original file name; the provider derives one.
        final String fileName = null;

        final String rcsMessageId = UUID.randomUUID().toString();

        // Synchronous send (off-main, on the action-service thread). Done before
        // any DB write so a non-accept leaves no orphan row.
        final RcsSendResult result = transport.sendFile(subId, rcsMessageId,
                /*toUri=*/ null, contentUri, contentType, fileName,
                MessagePartData.UNSPECIFIED_SIZE, caption, groupId);

        if (result == null || !result.accepted) {
            LogUtil.i(TAG, "tryInsertSendingRcsGroupFile: send not accepted (reason="
                    + (result != null ? result.reasonCode : -1) + "); using MMS");
            return false;
        }

        sLastSentMessageTimestamp = timestamp;
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMediaMessage(
                    conversationId, content.getSelfId(), contentType, contentUri, caption);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                    RcsMessageStore.rcsMetaValues(rcsMessageId, RcsConstants.RCS_STATUS_NONE));
            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
            LogUtil.i(TAG, "tryInsertSendingRcsGroupFile: inserted RCS group media message "
                    + message.getMessageId() + " (rcsId=" + rcsMessageId + ", groupId="
                    + groupId + ", mime=" + contentType + ")");
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    private boolean tryInsertSendingRcsGroupMessage(final MessageData content,
            final int subId, final ArrayList<String> recipients, final long timestamp,
            final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // Multi-transport framework (design §2, §5.4): route the rich provider-only
        // group-text surface (createGroup) through the sub's selected transport when it
        // IS a ProviderTransport, else fall back to the legacy singleton (byte-identical
        // on a provider-only device).
        final ProviderTransport transport;
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "tryInsertSendingRcsGroupMessage: RCS transport unavailable", t);
            return false;
        }

        // Sub-level gate (mirrors the 1:1 path). Group-availability also folds in
        // the GROUP_RCS_ENABLED flag, but the caller already checked it.
        if (!transport.getRouteSelector().isGroupRcsAvailableForSub(subId)) {
            return false;
        }

        final String messageText = content.getMessageText();
        if (TextUtils.isEmpty(messageText)) {
            return false;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Resolve the GROUP_ID this conversation maps to. If none, mint one and
        // create the group on the wire, then stamp the mapping on the row.
        String groupId = BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        if (TextUtils.isEmpty(groupId)) {
            // Canonicalize the OTHER recipients to E.164 (the provider adds self
            // to the member list itself per the wire contract).
            final ArrayList<String> memberE164s = new ArrayList<>(recipients.size());
            for (final String r : recipients) {
                final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(r);
                memberE164s.add(TextUtils.isEmpty(canonical) ? r : canonical);
            }
            // Client-minted 32-char lowercase-hex GROUP_ID; the server echoes it.
            final String desiredGroupId =
                    UUID.randomUUID().toString().replace("-", "").toLowerCase();
            final org.lineageos.rcs.provider.RcsGroupInfo info = transport.createGroup(subId,
                    desiredGroupId, "" /* groupName */, memberE164s,
                    0 /* groupType=DEFAULT */);
            if (info == null || TextUtils.isEmpty(info.groupId)) {
                LogUtil.i(TAG, "tryInsertSendingRcsGroupMessage: createGroup failed; using MMS");
                return false;
            }
            groupId = info.groupId;
            // Stamp the mapping onto the EXISTING (composed-in) conversation row
            // so subsequent sends and inbound fan-out route to it.
            BugleDatabaseOperations.setConversationRcsGroupId(db, conversationId, groupId);
        }

        // Mint the correlation UUID and send. Done before any DB write so a
        // non-accept leaves no orphan row.
        final String rcsMessageId = UUID.randomUUID().toString();

        {
            final RcsSendResult result = transport.sendGroupMessage(subId, groupId, messageText,
                    rcsMessageId);
            if (result == null || !result.accepted) {
                LogUtil.i(TAG, "tryInsertSendingRcsGroupMessage: send not accepted (reason="
                        + (result != null ? result.reasonCode : -1) + "); using MMS");
                return false;
            }
        }
        // The send above is SYNCHRONOUS and returned success, so reaching here IS the
        // measurement. See the row write below, which records it.

        sLastSentMessageTimestamp = timestamp;
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMessage(
                    conversationId, content.getSelfId(), messageText);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            // The group half of what the 1:1 arm above already does: both group send
            // routes are SYNCHRONOUS — RcsProviderService.sendGroupMessage and
            // sendGroupMlsCiphertext each call TachyonRegistrar directly on the binder thread and
            // fire no ProviderSink — so nothing will ever deliver a status callback for this id and
            // the answer we already hold is the only one there will be. Left at YET_TO_SEND the row
            // reads "Sending…" forever, because ProcessPendingMessagesAction excludes TRANSPORT_RCS
            // from the send queue by name. Reports the SEND, never DELIVERY: a later IMDN still
            // upgrades to OUTGOING_DELIVERED and its absence still means unconfirmed.
            final ContentValues groupVals = RcsMessageStore.rcsMetaValues(rcsMessageId,
                    RcsSendStatus.rcsStatusForMeasuredHandoff(true));
            groupVals.put(DatabaseHelper.MessageColumns.STATUS,
                    RcsSendStatus.bugleStatusForMeasuredHandoff(true));
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), groupVals);
            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "tryInsertSendingRcsGroupMessage: inserted RCS group message "
                        + message.getMessageId() + " (rcsId=" + rcsMessageId + ", groupId="
                        + groupId + ")");
            }
        } finally {
            db.endTransaction();
        }

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /**
     * Land a group text that we REFUSED to send, as a visibly FAILED outgoing RCS row.
     *
     * <p><b>Returning {@code false} is not available to a refusal, and that is the whole reason this
     * method exists.</b> The group-text fork falls through to the unchanged group-MMS path on
     * {@code false}, so expressing the refusal that way would put the same cleartext on a different
     * transport — a downgrade wearing the shape of a rejection. This returns {@code true}
     * unconditionally, including when the insert itself fails: the one outcome that must never
     * happen is falling through to MMS.
     *
     * <p><b>{@code OUTGOING_FAILED}, not the 1:1 path's {@code OUTGOING_YET_TO_SEND}</b>, and the
     * departure from that precedent is measured rather than stylistic.
     * {@code ProcessPendingMessagesAction.findNextMessageToSend} EXCLUDES {@code TRANSPORT_RCS} rows
     * from the SMS/MMS send queue by design, so an RCS row left in {@code YET_TO_SEND} is picked up
     * by nothing at all and shows "Sending…" forever — the same defect arriving from the other
     * direction. {@code FAILED} is terminal, is what every other failed RCS send already lands as
     * ({@code UpdateRcsMessageStatusAction}), renders red with a retry affordance, and keeps the
     * user's text in the thread where the existing "Send as SMS" escape hatch can reach it — a
     * downgrade the user chooses is a different thing from one we perform silently.
     *
     * <p><b>The retry affordance on this row does NOT strand it</b>, and the distinction is worth
     * stating because it was true until the terminal-status fix landed, and a stale caveat here is
     * exactly the kind that becomes a fact by attrition. {@code ResendMessageAction} used to move
     * any failed row
     * to {@code YET_TO_SEND} and hand it to {@code ProcessPendingMessagesAction}, which skips RCS
     * rows by name — so one tap re-stranded it at "Sending…" forever. That commit refuses a
     * {@code TRANSPORT_RCS} row before the write and hides the one-click resend and the
     * {@code action_send} item for RCS rows, so a failed row offers "Send as SMS" only, which
     * actually sends. <b>Do not add an over-RCS resend path back here</b>: a real one needs a fresh
     * generation under a fresh message id (invariant 62) and touches the &sect;10.3 ledger. That
     * gap is deliberately left open.
     *
     * <p>No {@code rcs_message_id} is stamped: nothing was sent, so there is no wire id to correlate
     * and minting one would put an id in the resend register that no peer has ever seen.
     */
    private boolean insertRefusedRcsGroupMessage(final MessageData content, final long timestamp,
            final String conversationId) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            db.beginTransaction();
            try {
                final MessageData message = MessageData.createOutgoingRcsMessage(
                        conversationId, content.getSelfId(), content.getMessageText());
                message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
                message.markMessageFailed(timestamp);
                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
                BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                        RcsMessageStore.rcsMetaValues(null /* rcsMessageId */,
                                RcsConstants.RCS_STATUS_NONE));
                BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                        conversationId, message.getMessageId(), timestamp,
                        false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                db.setTransactionSuccessful();
                LogUtil.w(TAG, "insertRefusedRcsGroupMessage: landed message "
                        + message.getMessageId() + " as OUTGOING_FAILED on conversation "
                        + conversationId + " — it was NOT sent, in any form");
            } finally {
                db.endTransaction();
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } catch (final Throwable t) {
            // Still true. A failed insert loses one message; falling through to MMS would send the
            // content of an encrypted conversation in the clear, which is the defect itself.
            LogUtil.e(TAG, "insertRefusedRcsGroupMessage: could not land the refused message on "
                    + conversationId + " — it is NOT being sent and its text is lost from the "
                    + "thread. Still refusing rather than falling through to MMS.", t);
        }
        return true;
    }

    /**
     * Land a MEDIA send that we REFUSED, as a visibly FAILED outgoing RCS media row &mdash; the
     * media sibling of {@link #insertRefusedRcsGroupMessage}.
     *
     * <p><b>Returning {@code false} is not available to a refusal</b>, which is the only reason this
     * exists as a method rather than a branch. Both media forks fall through to the unchanged MMS
     * path on {@code false}, so expressing the refusal that way would upload nothing and then send
     * the very same picture over MMS &mdash; a downgrade wearing the shape of a rejection. It
     * returns {@code true} on every path, the failed insert included: the one outcome that must
     * never happen is falling through.
     *
     * <p><b>It takes the resolved {@link MessagePartData} rather than re-deriving it.</b> The caller
     * already holds the exact part it was about to upload, and a second
     * {@code firstMediaAttachment} walk could pick a different part on a multi-attachment draft
     * &mdash; the row would then describe something other than the file that was refused.
     *
     * <p><b>{@code OUTGOING_FAILED}, and it is terminal by more than convention.</b>
     * {@code ProcessPendingMessagesAction.findNextMessageToSend} excludes {@code TRANSPORT_RCS} rows
     * from the SMS/MMS send queue by name, so an RCS row left at {@code OUTGOING_YET_TO_SEND} is
     * picked up by nothing and reads "Sending…" forever. {@code FAILED} renders
     * red, keeps the attachment in the thread, and leaves the user the "Send as SMS" escape hatch
     * that is now the only send affordance on a failed RCS bubble &mdash; a downgrade
     * the user chooses is a different thing from one we perform silently.
     *
     * <p><b>The status columns differ from the successful media path on purpose.</b> That path
     * inserts at {@code RCS_STATUS_NONE} and waits for {@code ProviderSink.onMessageStatus}, which
     * really does arrive for a file send ({@code TachyonTransport.sendFile} reports SENT/FAILED on
     * every arm, unlike the group TEXT route). Here nothing was handed to the wire, so no callback
     * is coming and the row must already be terminal when it is written. No {@code rcs_message_id}
     * is stamped either: minting one would put an id in the resend register that no peer has ever
     * seen, and would give a late callback for some other send something to correlate to.
     */
    private boolean insertRefusedRcsMediaMessage(final MessageData content, final long timestamp,
            final String conversationId, final MessagePartData media) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            db.beginTransaction();
            try {
                final MessageData message = MessageData.createOutgoingRcsMediaMessage(
                        conversationId, content.getSelfId(), media.getContentType(),
                        media.getContentUri(), media.getText());
                message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
                message.markMessageFailed(timestamp);
                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
                BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(),
                        RcsMessageStore.rcsMetaValues(null /* rcsMessageId */,
                                RcsConstants.RCS_STATUS_NONE));
                BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                        conversationId, message.getMessageId(), timestamp,
                        false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
                db.setTransactionSuccessful();
                LogUtil.w(TAG, "insertRefusedRcsMediaMessage: landed message "
                        + message.getMessageId() + " as OUTGOING_FAILED on conversation "
                        + conversationId + " (mime=" + media.getContentType() + ") — the file was "
                        + "NOT uploaded and NOT sent, in any form");
            } finally {
                db.endTransaction();
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyPartsChanged();
        } catch (final Throwable t) {
            // Same ruling as the group-text refusal: a failed insert loses one message from the
            // thread; falling through to MMS would put the content of a conversation the user was
            // told is encrypted onto the wire in the clear, which is the defect itself.
            LogUtil.e(TAG, "insertRefusedRcsMediaMessage: could not land the refused attachment on "
                    + conversationId + " — it is NOT being sent and it is gone from the thread. "
                    + "Still refusing rather than falling through to MMS.", t);
        }
        return true;
    }

    /**
     * Insert MMS messaging into our database.
     */
    private MessageData insertSendingMmsMessage(final String conversationId,
            final MessageData message, final long timestamp) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        final List<MessagePartData> attachmentsUpdated = new ArrayList<>();
        try {
            sLastSentMessageTimestamp = timestamp;

            // Insert "draft" message as placeholder until the final message is written to
            // the telephony db
            message.updateSendingMessage(conversationId, null/*messageUri*/, timestamp);

            // No need to inform SyncManager as message currently has no Uri...
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "InsertNewMessageAction: Inserted MMS message "
                    + message.getMessageId() + " (timestamp = " + timestamp + ")");
        }
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();

        return message;
    }

    private InsertNewMessageAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<InsertNewMessageAction> CREATOR
            = new Parcelable.Creator<>() {
        @Override
        public InsertNewMessageAction createFromParcel(final Parcel in) {
            return new InsertNewMessageAction(in);
        }

        @Override
        public InsertNewMessageAction[] newArray(final int size) {
            return new InsertNewMessageAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
