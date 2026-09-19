/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2026 The LineageOS Project
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
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.rcs.engine.mls.RccMlsBody;
import com.android.messaging.rcs.e2ee.MlsSendRouting;
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
     * Inserts a message and sends it over SMS, skipping the RCS forks. Used only by the user's
     * "Send as SMS" on a failed RCS message; never an automatic fallback.
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
    // Set only by "Send as SMS": skip the RCS forks.
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

        // RCS forks; see docs/rcs/architecture.md. Anything they do not accept falls through to
        // the SMS/MMS path unchanged.
        final boolean forceSms = actionParameters.getBoolean(KEY_FORCE_SMS, false);

        // Group text: only a conversation that already has an rcs_group_id, set by the "New
        // group" flow, takes this fork, whatever the draft's protocol (every multi-recipient
        // draft is MMS-shaped). Attachments and plain group MMS fall through.
        final boolean convIsRcsGroup = !TextUtils.isEmpty(
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId));
        if (!forceSms
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

        // Group media: an established RCS group with one media part.
        if (!forceSms
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
            // No ProcessPendingMessagesAction: the RCS send already happened.
            return message;
        }

        // 1:1 media: the provider gets a content URI, never inline bytes (binder size limit).
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
     * Sends a 1:1 text over RCS and, if accepted, inserts the RCS row. Returns false without
     * touching the database for every case that should fall back to SMS. The send precedes the
     * insert so a rejection leaves no orphan row; {@code rcs_message_id} carries the client-minted
     * id that status callbacks correlate on.
     */
    private boolean tryInsertSendingRcsMessage(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        final RouteSelector routeSelector;
        try {
            routeSelector = RcsCallbackRouter.getInstance(context).getRouteSelector();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "InsertNewMessageAction: RCS transport unavailable", t);
            return false;
        }
        // A send can start this process and run before the transport reports its state; it waits
        // for the state, bounded, rather than routing SMS.
        if (!routeSelector.awaitRcsAvailableForSub(subId)) {
            return false;
        }

        // The transport selected for this subscription, else the ProviderTransport singleton. Read
        // after the wait: the selection is committed when the state arrives.
        final RcsTransport transport;
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected != null) ? selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "InsertNewMessageAction: RCS transport unavailable", t);
            return false;
        }

        // Peer capability: CAP_SMS_ONLY skips RCS; CAP_UNKNOWN proceeds and lets the send result
        // decide. Must run off the main thread. The destination is canonicalized to E.164, which
        // the provider requires.
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

        final String rcsMessageId = UUID.randomUUID().toString();

        // Resolve the conversation's E2EE scheme (never throws; degrades to plaintext). MLS goes
        // through one of the MLS paths below. A provider-layer scheme is stamped only when the
        // provider reports applying it, on STATUS_SENT.
        final String e2eeScheme = E2eeSendGate.get().resolveForSend(
                conversationId, subId, dest, false /* isGroup */);

        final boolean isMls = RcsE2eeScheme.MLS.equals(e2eeScheme);
        boolean dispatchedViaMls = false;
        // Path 1: MLS over the in-app carrier transport. All three MLS paths frame the body with
        // RccMlsBody; the generation counter is stamped at encrypt time.
        if (isMls && transport.isMlsReady(subId)) {
            dispatchedViaMls = transport.sendMlsMessage(subId,
                    dest.startsWith("tel:") ? dest : "tel:" + dest,
                    RccMlsBody.frameText(messageText), rcsMessageId);
        }

        // Path 1b: MLS sealed by this app and carried by the provider. Must precede Path 2, which
        // asks the provider to encrypt: both would be owners of one ratchet.
        boolean appOwnedFailed = false;
        // The outcome of Path 1b, the one arm whose result is known synchronously; null when it
        // did not decide the row, in which case a status callback owns it.
        Boolean appOwnedAccepted = null;
        if (isMls && !dispatchedViaMls) {
            // The chat row's rcs_message_id is the wire id, so a reported resend can find it.
            final MlsProviderTransport.SendOutcome outcome = MlsProviderTransport.sendAppOwned(
                    Factory.get().getApplicationContext(), subId, conversationId, dest,
                    RccMlsBody.frameText(messageText), rcsMessageId);
            if (outcome == MlsProviderTransport.SendOutcome.SENT) {
                dispatchedViaMls = true;
                appOwnedAccepted = Boolean.TRUE;
            } else if (outcome == MlsProviderTransport.SendOutcome.FAILED) {
                // A generation is already consumed: neither let the provider re-encrypt nor
                // downgrade to plaintext. Leave it unsent and retryable.
                appOwnedFailed = true;
                appOwnedAccepted = Boolean.FALSE;
                LogUtil.w(TAG, "InsertNewMessageAction: app-owned MLS send FAILED for "
                        + rcsMessageId
                        + " — not re-encrypting via the provider and not downgrading");
            }
            // NOT_APPLICABLE (no adopted identity) falls through to Path 2.
        }

        if (!dispatchedViaMls && !appOwnedFailed) {
            // Path 2: the provider encrypts. For MLS the RCC.16 body is framed here and the scheme
            // tagged; otherwise plaintext, which the provider may encrypt itself.
            final byte[] outBody = isMls
                    ? RccMlsBody.frameText(messageText)
                    : messageText.getBytes(StandardCharsets.UTF_8);
            final RcsSendResult result = transport.sendMessage(new RcsOutgoingMessage(
                    subId, rcsMessageId, dest,
                    isMls ? "message/mls" : "text/plain;charset=UTF-8",
                    outBody,
                    isMls ? RcsE2eeScheme.MLS : null,
                    /*groupId=*/ null));
            if (isMls && result != null && result.accepted) {
                dispatchedViaMls = true;
            }

            if (result == null || !result.accepted) {
                LogUtil.i(TAG, "InsertNewMessageAction: RCS send not accepted (reason="
                        + (result != null ? result.reasonCode : -1)
                        + "); falling back to SMS");
                // Teach the per-recipient cache, so the next send skips RCS up front.
                if (result != null && result.reasonCode == RcsSendResult.REASON_PEER_NOT_RCS) {
                    routeSelector.notePeerNotRcs(recipient);
                }
                return false;
            }
        }

        // The scheme for our own row's padlock: MLS if it was actually sealed.
        final String stampedScheme = dispatchedViaMls ? RcsE2eeScheme.MLS : null;

        sLastSentMessageTimestamp = timestamp;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        final DatabaseWrapper db = DataModel.get().getDatabase();
        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMessage(
                    conversationId, content.getSelfId(), messageText);
            // No telephony Uri: RCS rows are SMS-shaped without one.
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);

            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);

            // The RCS metadata, plus Path 1b's synchronous outcome when there was one, mapped as
            // UpdateRcsMessageStatusAction maps the matching callback. It reports the send, not
            // delivery: a later IMDN still upgrades the row.
            final ContentValues rcsVals = RcsMessageStore.rcsMetaValues(rcsMessageId,
                    appOwnedAccepted == null
                            ? RcsConstants.RCS_STATUS_NONE
                            : RcsSendStatus.rcsStatusForMeasuredHandoff(appOwnedAccepted));
            if (appOwnedAccepted != null) {
                rcsVals.put(DatabaseHelper.MessageColumns.STATUS,
                        RcsSendStatus.bugleStatusForMeasuredHandoff(appOwnedAccepted));
            }
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), rcsVals);

            // Stamp a sealed send so outgoing bubbles show the padlock too.
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
        UpdateRcsMessageStatusAction.applyParkedStatus(db, rcsMessageId);

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /** True when the message has no attachment parts. */
    private static boolean isTextOnlyMessage(final MessageData message) {
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment()) {
                return false;
            }
        }
        return true;
    }

    /** Returns the first media part of a draft, or null. */
    private static MessagePartData firstMediaAttachment(final MessageData message) {
        for (final MessagePartData part : message.getParts()) {
            if (part.isAttachment() && part.getContentUri() != null) {
                return part;
            }
        }
        return null;
    }

    /**
     * Sends a 1:1 media message over RCS and, if accepted, inserts the RCS row; returns false
     * for every case that should fall back to MMS. The provider gets a content URI and opens it
     * itself.
     *
     * <p>An MLS refusal returns true: the attachment must not reach MMS either, so a failed row
     * is written by {@link #insertRefusedRcsMediaMessage}. The gate therefore runs ahead of every
     * other {@code return false}.
     */
    private boolean tryInsertSendingRcsFile(final MessageData content, final int subId,
            final String recipient, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // Ahead of the MLS gate, which must precede every fall-through to MMS.
        final MessagePartData media = firstMediaAttachment(content);
        if (media == null || media.getContentUri() == null
                || TextUtils.isEmpty(media.getContentType())) {
            // Must stay ahead of the gate: refusing a draft with no media would land an empty
            // failed bubble.
            return false;
        }

        // E.164, as for text. It is also the key the gate reads: the national form would answer
        // NO_MLS_STATE for a conversation we hold state for, failing open.
        final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(recipient);
        final String dest = TextUtils.isEmpty(canonical) ? recipient : canonical;

        // Refuse rather than degrade. The verdict comes from MlsProviderTransport (see
        // MlsSendRouting). Only PLAINTEXT may send: there is no encrypted media send, and SEAL
        // says only that the conversation could be sealed. A non-MLS conversation always gets
        // PLAINTEXT.
        final MlsSendRouting.Verdict mlsVerdict =
                MlsProviderTransport.oneToOneSendVerdict(context, subId, dest, conversationId);
        if (mlsVerdict != MlsSendRouting.Verdict.PLAINTEXT) {
            LogUtil.e(TAG, "tryInsertSendingRcsFile: NOT uploading this attachment in the clear "
                    + "(conversation " + conversationId + ", verdict " + mlsVerdict + "). The app "
                    + "is presenting this thread as encrypted and we cannot seal the file, so it is "
                    + "left unsent and failed rather than silently downgraded -- an unencrypted "
                    + "upload would sit at a URL on a content server.");
            return insertRefusedRcsMediaMessage(content, timestamp, conversationId, media);
        }

        // sendFile exists only on ProviderTransport: the selected transport if it is one, else
        // the singleton.
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

        if (!transport.getRouteSelector().awaitRcsAvailableForSub(subId)) {
            return false;
        }

        final int peerCap = transport.lookupRcsCapability(subId, dest);
        if (peerCap == IRcsProvider.CAP_SMS_ONLY) {
            return false;
        }

        // The caption is the media part's text.
        final String caption = media.getText();
        final Uri contentUri = media.getContentUri();
        final String contentType = media.getContentType();
        // No original file name; the provider derives one.
        final String fileName = null;

        final String rcsMessageId = UUID.randomUUID().toString();

        // Synchronous, on the action thread, before any database write.
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
        UpdateRcsMessageStatusAction.applyParkedStatus(db, rcsMessageId);

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /**
     * Sends a group media message over RCS to the conversation's group id and inserts the RCS
     * row; returns false to fall back to group MMS. An MLS refusal returns true after
     * {@link #insertRefusedRcsMediaMessage}, and the gate runs ahead of every other
     * {@code return false}.
     */
    private boolean tryInsertSendingRcsGroupFile(final MessageData content,
            final int subId, final long timestamp, final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // Ahead of the MLS gate, which must precede every fall-through to MMS.
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String groupId =
                BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        if (TextUtils.isEmpty(groupId)) {
            return false;
        }

        final MessagePartData media = firstMediaAttachment(content);
        if (media == null || media.getContentUri() == null
                || TextUtils.isEmpty(media.getContentType())) {
            // Ahead of the gate: refusing a draft with no media would land an empty failed bubble.
            return false;
        }

        // Refuse rather than degrade, as in tryInsertSendingRcsFile: only PLAINTEXT may send
        // an attachment.
        final MlsSendRouting.Verdict mlsVerdict =
                MlsProviderTransport.groupSendVerdict(context, subId, groupId, conversationId);
        if (mlsVerdict != MlsSendRouting.Verdict.PLAINTEXT) {
            LogUtil.e(TAG, "tryInsertSendingRcsGroupFile: NOT uploading this attachment in the "
                    + "clear (conversation " + conversationId + ", groupId " + groupId
                    + ", verdict " + mlsVerdict + "). The app is presenting this thread as "
                    + "encrypted and we cannot seal the file, so it is left unsent and failed "
                    + "rather than silently downgraded.");
            return insertRefusedRcsMediaMessage(content, timestamp, conversationId, media);
        }

        // sendFile exists only on ProviderTransport.
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

        if (!transport.getRouteSelector().awaitGroupRcsAvailableForSub(subId)) {
            return false;
        }

        final String caption = media.getText();
        final Uri contentUri = media.getContentUri();
        final String contentType = media.getContentType();
        final String fileName = null;

        final String rcsMessageId = UUID.randomUUID().toString();

        // Synchronous, before any database write.
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
        UpdateRcsMessageStatusAction.applyParkedStatus(db, rcsMessageId);

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
        return true;
    }

    /**
     * Sends a group text over RCS, creating the group on first send if needed. Returns false to
     * fall back to MMS; see docs/rcs/groups.md.
     */
    private boolean tryInsertSendingRcsGroupMessage(final MessageData content,
            final int subId, final ArrayList<String> recipients, final long timestamp,
            final String conversationId) {
        final Context context = Factory.get().getApplicationContext();

        // createGroup exists only on ProviderTransport.
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

        if (!transport.getRouteSelector().awaitGroupRcsAvailableForSub(subId)) {
            return false;
        }

        final String messageText = content.getMessageText();
        if (TextUtils.isEmpty(messageText)) {
            return false;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();

        // No group id yet: create the group and map it onto this conversation.
        String groupId = BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        if (TextUtils.isEmpty(groupId)) {
            // The provider adds self to the members.
            final ArrayList<String> memberE164s = new ArrayList<>(recipients.size());
            for (final String r : recipients) {
                final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(r);
                memberE164s.add(TextUtils.isEmpty(canonical) ? r : canonical);
            }
            // 32 lowercase hex characters; the server echoes it.
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
            BugleDatabaseOperations.setConversationRcsGroupId(db, conversationId, groupId);
        }

        // Refuse rather than degrade: an unsealable send on a conversation presented as
        // encrypted must not go out. MlsProviderTransport decides; see docs/rcs/groups.md.
        final MlsSendRouting.Verdict mlsVerdict =
                MlsProviderTransport.groupSendVerdict(context, subId, groupId, conversationId);
        if (mlsVerdict != MlsSendRouting.Verdict.SEAL
                && mlsVerdict != MlsSendRouting.Verdict.PLAINTEXT) {
            LogUtil.e(TAG, "tryInsertSendingRcsGroupMessage: NOT sending this group text in the "
                    + "clear (conversation " + conversationId + ", groupId " + groupId
                    + ", verdict " + mlsVerdict + "). The app is presenting this thread as "
                    + "encrypted and we cannot seal it, so the message is left unsent and failed "
                    + "rather than silently downgraded.");
            return insertRefusedRcsGroupMessage(content, timestamp, conversationId);
        }

        // Sent before any database write so a rejection leaves no orphan row.
        final String rcsMessageId = UUID.randomUUID().toString();

        if (mlsVerdict == MlsSendRouting.Verdict.SEAL) {
            // The 3-argument sendToGroup binds the wire id to this row's rcs_message_id, so
            // receipts correlate and a resend replays rather than re-seals. It frames and seals.
            boolean sealed;
            try {
                sealed = MlsProviderTransport.get(context, subId)
                        .sendToGroup(groupId, messageText, rcsMessageId);
            } catch (final Throwable t) {
                LogUtil.e(TAG, "tryInsertSendingRcsGroupMessage: the MLS group send threw for "
                        + rcsMessageId, t);
                sealed = false;
            }
            if (!sealed) {
                // Never fall back to plaintext: a generation may already be consumed.
                LogUtil.e(TAG, "tryInsertSendingRcsGroupMessage: the MLS group send FAILED for "
                        + rcsMessageId + " on " + groupId + " — NOT retrying it in the clear");
                return insertRefusedRcsGroupMessage(content, timestamp, conversationId);
            }
        } else {
            final RcsSendResult result = transport.sendGroupMessage(subId, groupId, messageText,
                    rcsMessageId);
            if (result == null || !result.accepted) {
                LogUtil.i(TAG, "tryInsertSendingRcsGroupMessage: send not accepted (reason="
                        + (result != null ? result.reasonCode : -1) + "); using MMS");
                return false;
            }
        }
        // Both arms are synchronous, so reaching here means the send was accepted.
        final boolean sealedForMls = mlsVerdict == MlsSendRouting.Verdict.SEAL;

        sLastSentMessageTimestamp = timestamp;
        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);

        db.beginTransaction();
        try {
            final MessageData message = MessageData.createOutgoingRcsMessage(
                    conversationId, content.getSelfId(), messageText);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            // No status callback follows a group send, so record the outcome now; see
            // docs/rcs/architecture.md.
            final ContentValues groupVals = RcsMessageStore.rcsMetaValues(rcsMessageId,
                    RcsSendStatus.rcsStatusForMeasuredHandoff(true));
            groupVals.put(DatabaseHelper.MessageColumns.STATUS,
                    RcsSendStatus.bugleStatusForMeasuredHandoff(true));
            // Padlock on a sealed send.
            if (sealedForMls) {
                groupVals.put(DatabaseHelper.MessageColumns.RCS_E2EE_SCHEME_ID, RcsE2eeScheme.MLS);
            }
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
     * Writes a refused group text as a failed RCS row and returns true, even if the insert
     * fails: returning false would fall through to MMS and send the text in the clear. FAILED,
     * because the SMS queue never picks up an RCS row. No {@code rcs_message_id}: nothing was
     * sent.
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
            // Still true: a lost message is better than cleartext over MMS.
            LogUtil.e(TAG, "insertRefusedRcsGroupMessage: could not land the refused message on "
                    + conversationId + " — it is NOT being sent and its text is lost from the "
                    + "thread. Still refusing rather than falling through to MMS.", t);
        }
        return true;
    }

    /**
     * The media counterpart of {@link #insertRefusedRcsGroupMessage}. Takes the part the caller
     * was about to upload rather than re-deriving it, so the row describes the refused file.
     * Written terminal because no status callback will follow.
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
            // Still true: a lost attachment is better than cleartext over MMS.
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
