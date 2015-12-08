package com.android.messaging;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;
import android.provider.Telephony.Sms;
import android.support.v7.mms.MmsManager;
import android.support.v7.mms.pdu.ContentType;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.action.BugleActionToasts;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.mmslib.InvalidHeaderValueException;
import com.android.messaging.mmslib.MmsException;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.mmslib.pdu.CharacterSets;
import com.android.messaging.mmslib.pdu.EncodedStringValue;
import com.android.messaging.mmslib.pdu.PduBody;
import com.android.messaging.mmslib.pdu.PduPart;
import com.android.messaging.mmslib.pdu.PduPersister;
import com.android.messaging.mmslib.pdu.RetrieveConf;
import com.android.messaging.sms.DatabaseMessages;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

import org.whispersystems.whisperpush.api.MessagingBridge;
import org.whispersystems.whisperpush.util.Util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhisperPushMessagingBridge implements MessagingBridge {

    private static final String TAG = "WPMessagingBridge";

    private final Context mContext;

    private static final int SUBSCRIPTION_ID = 0;

    public WhisperPushMessagingBridge(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void storeIncomingTextMessage(String sender, String message, long sentAt,
                                         boolean read) {

        // See com.android.messaging.datamodel.action.ReceiveSmsMessageAction for details

        long receivedAt = System.currentTimeMillis();
        ParticipantData self = ParticipantData.getSelfParticipant(SUBSCRIPTION_ID);
        ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(sender, SUBSCRIPTION_ID);
        DataModel dataModel = DataModel.get();
        DatabaseWrapper db = dataModel.getDatabase();
        long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(mContext, sender);
        boolean blocked = BugleDatabaseOperations.isBlockedDestination(db,
                rawSender.getNormalizedDestination());
        String conversationId = BugleDatabaseOperations.getOrCreateConversationFromRecipient(db,
                threadId, blocked, rawSender);
        boolean messageInFocusedConversation =
                dataModel.isFocusedConversation(conversationId);
        boolean messageInObservableConversation =
                dataModel.isNewMessageObservable(conversationId);
        read = read || messageInFocusedConversation;
        boolean seen = read || messageInObservableConversation || blocked;

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(receivedAt);

        Uri messageUri = null;
        if (!OsUtil.isSecondaryUser()) { // see ReceiveSmsMessageAction for details
            ContentValues messageValues = new ContentValues();
            messageValues.put(Sms.THREAD_ID, threadId);
            messageValues.put(Sms.ADDRESS, sender);
            messageValues.put(Sms.BODY, message);
            messageValues.put(Sms.DATE, receivedAt);
            messageValues.put(Sms.DATE_SENT, sentAt);
            messageValues.put(Sms.SUBSCRIPTION_ID, SUBSCRIPTION_ID);
            messageValues.put(Sms.Inbox.READ, read ? 1 : 0);
            // incoming messages are marked as seen in the telephony db
            messageValues.put(Sms.Inbox.SEEN, 1);

            // Insert into telephony
            messageUri = mContext.getContentResolver().insert(Sms.Inbox.CONTENT_URI, messageValues);

            if (messageUri != null) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "ReceiveSmsMessageAction: Inserted SMS message into telephony, "
                            + "uri = " + messageUri);
                }
            } else {
                LogUtil.e(TAG, "ReceiveSmsMessageAction: Failed to insert SMS into telephony!");
            }
        }

        db.beginTransaction();
        try {
            String participantId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, rawSender);
            String selfId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);
            MessageData messageData = MessageData.createReceivedSmsMessage(messageUri,
                    conversationId, participantId, selfId, message, null /*subject*/, sentAt,
                    receivedAt, seen, read);
            messageData.setProviderId(MessageData.PROVIDER_WHISPER_PUSH);

            BugleDatabaseOperations.insertNewMessageInTransaction(db, messageData);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    messageData.getMessageId(), messageData.getReceivedTimeStamp(), blocked,
                    null /*conversationServiceCenter*/, true /* shouldAutoSwitchSelfId */);

            ParticipantData senderData = ParticipantData.getFromId(db, participantId);
            BugleActionToasts.onMessageReceived(conversationId, senderData, messageData);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
//        ProcessPendingMessagesAction.scheduleProcessPendingMessagesAction(false, this); //FIXME ?

        showNotification(conversationId);
    }

    @Override
    public void storeIncomingMultimediaMessage(String sender, String message,
                                               List<Pair<byte[], Uri>> attachments,
                                               long sentAt) {
        storeIncomingGroupMessage(sender, message, attachments, sentAt, -1);
    }

    @Override
    public void storeIncomingGroupMessage(String sender, String message,
                                          List<Pair<byte[], Uri>> attachments,
                                          long sentAt, long threadId) {
        try {
            Uri messageUri = createMessageUri(sender, message, attachments, threadId);

            // Inform sync that message has been added at local received timestamp
            final SyncManager syncManager = DataModel.get().getSyncManager();
            syncManager.onNewMessageInserted(sentAt);

            final DatabaseMessages.MmsMessage mms = MmsUtils.loadMms(messageUri);

            if (mms != null) {
                saveInboxMmsMessage(mms, sender, sentAt);
            }
        } catch (InvalidHeaderValueException e) {
            Log.e(TAG, "storeIncomingMultimediaMessage failed", e);
        } catch (MmsException e) {
            Log.e(TAG, "storeIncomingMultimediaMessage failed", e);
        }
    }

    @Override
    public long getThreadId(Set<String> recipients) {
        return MmsSmsUtils.Threads.getOrCreateThreadId(mContext, recipients);
    }

    @Override
    public Set<String> getRecipientsByThread(long threadId) {
        return new HashSet<>(MmsUtils.getRecipientsByThread(threadId));
    }

    @Override
    public Uri persistPart(byte[] contentType, byte[] data, long threadId) {
        PduPersister pduPersister = PduPersister.getPduPersister(mContext);
        PduPart pduPart = new PduPart();
        pduPart.setContentType(contentType);
        pduPart.setData(data);
        try {
            return pduPersister.persistPart(pduPart, threadId, null);
        } catch (MmsException e) {
            Log.e(TAG, "persistPart failed", e);
            return null;
        }
    }

    // Show a notification to let the user know a new message has arrived
    private void showNotification(String conversationId) {
        BugleNotifications.update(false/*silent*/, conversationId,
                BugleNotifications.UPDATE_ALL);

        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyPartsChanged();
    }

    private Uri createMessageUri(String sender, String message,
                                 List<Pair<byte[], Uri>> attachments,
                                 long threadId)
            throws MmsException {
        PduBody pduBody = new PduBody();
        if (!TextUtils.isEmpty(message)) {
            PduPart pduPart = new PduPart();
            pduPart.setContentType(Util.toIsoBytes(ContentType.TEXT_PLAIN));
            pduPart.setCharset(CharacterSets.UTF_8);
            pduPart.setData(message.getBytes());
            pduBody.addPart(pduPart);
        }

        for (Pair<byte[], Uri> attachment : attachments) {
            PduPart pduPart = new PduPart();
            pduPart.setContentType(attachment.first);
            pduPart.setDataUri(attachment.second);
            pduBody.addPart(pduPart);
        }

        RetrieveConf retrieveConf = new RetrieveConf();
        retrieveConf.setFrom(new EncodedStringValue(sender));
        retrieveConf.setBody(pduBody);

        PduPersister persister = PduPersister.getPduPersister(mContext);
        Uri messageUri = persister.persist(retrieveConf,
                Telephony.Mms.Inbox.CONTENT_URI,
                SUBSCRIPTION_ID, null, null);

        if (threadId != -1) {
            ContentValues values = new ContentValues();
            values.put(Telephony.Mms.THREAD_ID, threadId);
            SqliteWrapper.update(mContext, mContext.getContentResolver(),
                    messageUri, values, null, null);
        }

        return messageUri;
    }

    private void saveInboxMmsMessage(DatabaseMessages.MmsMessage mms, String from, long sentAt) {
        int subId = SUBSCRIPTION_ID;
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final ParticipantData self = BugleDatabaseOperations.getOrCreateSelf(db, subId);
        if (from == null) {
            LogUtil.w("WhisperPushMessagingBridge", "Received an MMS without sender address; using unknown sender.");
            from = ParticipantData.getUnknownSenderDestination();
        }
        final ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(
                from, subId);
        final boolean blocked = BugleDatabaseOperations.isBlockedDestination(
                db, rawSender.getNormalizedDestination());
        final String conversationId =
                BugleDatabaseOperations.getOrCreateConversationFromThreadId(db, mms.mThreadId,
                        blocked, subId);

        boolean messageInFocusedConversation =
                DataModel.get().isFocusedConversation(conversationId);
        boolean messageInObservableConversation =
                DataModel.get().isNewMessageObservable(conversationId);
        mms.mRead = messageInFocusedConversation;
        mms.mSeen = messageInObservableConversation;

        // Write received placeholder message to our DB
        db.beginTransaction();
        try {
            final String participantId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, rawSender);
            final String selfId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);

            MessageData messageData = MmsUtils.createMmsMessage(mms, conversationId, participantId,
                    selfId, MessageData.BUGLE_STATUS_INCOMING_COMPLETE);
            messageData.setProviderId(MessageData.PROVIDER_WHISPER_PUSH);
            // Write the message
            BugleDatabaseOperations.insertNewMessageInTransaction(db, messageData);

            ContentValues values = new ContentValues(1);
            values.put(Telephony.Mms.Part.MSG_ID, messageData.getMessageId());
            SqliteWrapper.update(mContext, mContext.getContentResolver(),
                    Uri.parse("content://mms/" + sentAt + "/part"),
                    values, null, null);

            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, messageData.getMessageId(), messageData.getReceivedTimeStamp(),
                    blocked, true);
            final ParticipantData senderData = ParticipantData .getFromId(db, participantId);
            BugleActionToasts.onMessageReceived(conversationId, senderData, messageData);
            // else update the conversation once we have downloaded final message (or failed)
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        showNotification(conversationId);
    }

    @Override
    public boolean isAddressBlacklisted(String address) {
        DatabaseWrapper db = DataModel.get().getDatabase();
        ParticipantData rawSender = ParticipantData.getFromRawPhoneBySimLocale(address, SUBSCRIPTION_ID);
        return BugleDatabaseOperations.isBlockedDestination(db, rawSender.getNormalizedDestination());
    }

}
