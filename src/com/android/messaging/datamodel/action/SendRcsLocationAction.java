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

import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.RcsSendStatus;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/**
 * Send an RCS location share (geopush). Runs off the main thread:
 * resolves the conversation's recipient (1-1 E.164 or RCS GROUP_ID), ships the
 * GSMA {@code rcspushlocation+xml} over the provider
 * ({@link ProviderTransport#sendLocation}), and on acceptance inserts a local
 * outbound RCS row whose body is a tappable maps link — so our own share renders
 * the same way an inbound one does. Falls back to nothing (a toast is shown by
 * the caller) when RCS isn't available or the send is rejected.
 *
 * <p>Modeled on {@code InsertNewMessageAction.tryInsertSendingRcsMessage}'s local
 * RCS-row insert + {@code CreateRcsGroupAction}'s off-main action shape.
 */
public class SendRcsLocationAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_CONVERSATION_ID = "conversation_id";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_ACCURACY = "accuracy";

    /** Fire-and-forget; the local row + wire send happen in executeAction. */
    public static void shareLocation(final String conversationId, final double lat,
            final double lon, final double accuracyMeters) {
        final SendRcsLocationAction action =
                new SendRcsLocationAction(conversationId, lat, lon, accuracyMeters);
        action.start();
    }

    private SendRcsLocationAction(final String conversationId, final double lat,
            final double lon, final double accuracyMeters) {
        super();
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
        actionParameters.putDouble(KEY_LAT, lat);
        actionParameters.putDouble(KEY_LON, lon);
        actionParameters.putDouble(KEY_ACCURACY, accuracyMeters);
    }

    @Override
    protected Object executeAction() {
        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final double lat = actionParameters.getDouble(KEY_LAT);
        final double lon = actionParameters.getDouble(KEY_LON);
        final double accuracy = actionParameters.getDouble(KEY_ACCURACY);
        if (TextUtils.isEmpty(conversationId)) {
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String groupId = BugleDatabaseOperations.getConversationRcsGroupId(db, conversationId);
        final boolean isGroup = !TextUtils.isEmpty(groupId);
        final String selfId = BugleDatabaseOperations.getConversationSelfId(db, conversationId);
        // The RCS path routes on the concrete default-SMS sub (the conversation's
        // self sub is typically DEFAULT_SELF_SUB_ID), mirroring the text path.
        final int subId = PhoneUtils.getDefault().getEffectiveSubId(
                com.android.messaging.datamodel.data.ParticipantData.DEFAULT_SELF_SUB_ID);

        // Multi-transport framework (design §2, §5.4): route the rich provider-only
        // sendLocation surface through the sub's selected transport when it IS a
        // ProviderTransport, else fall back to the legacy singleton (nullable
        // peekInstance so a provider-absent device still short-circuits below).
        final ProviderRegistry registry = ProviderRegistry.peek();
        final RcsTransport selected = (registry != null)
                ? registry.getActiveTransport(subId) : null;
        final ProviderTransport transport = (selected instanceof ProviderTransport)
                ? (ProviderTransport) selected : ProviderTransport.peekInstance();
        if (transport == null || !transport.getRouteSelector().isRcsAvailableForSub(subId)) {
            LogUtil.w(TAG, "SendRcsLocationAction: RCS not available; skipping");
            return null;
        }

        final String recipient;
        if (isGroup) {
            recipient = groupId;
        } else {
            final ArrayList<String> recips =
                    BugleDatabaseOperations.getRecipientsForConversation(db, conversationId);
            if (recips.isEmpty()) {
                return null;
            }
            final String canonical =
                    PhoneUtils.getDefault().getCanonicalBySimLocale(recips.get(0));
            recipient = TextUtils.isEmpty(canonical) ? recips.get(0) : canonical;
        }

        // Local outbound row: a maps-link text rendered like an inbound location. Built BEFORE the
        // send because the refusal below lands the same body on a FAILED row — the user has to be
        // able to see WHICH share did not go out, and "a location" is not an answer.
        final String body = "📍 Shared location\n"
                + String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon);
        final long timestamp = System.currentTimeMillis() * 1000L;  // micros, matches RCS path


        final String rcsMessageId = UUID.randomUUID().toString();
        final Double acc = accuracy > 0 ? accuracy : null;
        final RcsSendResult result = transport.sendLocation(subId, recipient, isGroup,
                lat, lon, acc == null ? 0d : acc, /*label=*/ null, rcsMessageId);
        if (result == null || !result.accepted) {
            LogUtil.i(TAG, "SendRcsLocationAction: send not accepted (reason="
                    + (result != null ? result.reasonCode : -1) + ")");
            return null;
        }

        db.beginTransaction();
        try {
            final MessageData message =
                    MessageData.createOutgoingRcsMessage(conversationId, selfId, body);
            message.updateSendingMessage(conversationId, null /* messageUri */, timestamp);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            // REACHING THIS WRITE *IS* THE MEASUREMENT (the same stranding defect, on a verb the
            // original fix never reached). `updateSendingMessage` above leaves the row at
            // BUGLE_STATUS_OUTGOING_YET_TO_SEND, which on a TRANSPORT_RCS row is terminal by
            // neglect: nothing will ever move it.
            //
            //   - RcsProviderService.sendLocation is SYNCHRONOUS on the binder thread and routes
            //     STATICALLY to TachyonRegistrar.sendLocation / sendGroupLocation. It never touches
            //     the transport object that owns the ProviderSink, so no onMessageStatus will ever
            //     be delivered for this rcs_message_id. There is no async half to wait for.
            //   - ProcessPendingMessagesAction.findNextMessageToSend excludes TRANSPORT_RCS from
            //     the SMS/MMS queue BY NAME, so that queue will not pick it up either.
            //   - ResendMessageAction refuses a TRANSPORT_RCS row, so the retry
            //     affordance cannot re-strand it either.
            //   - AND THE ONE THAT MAKES THIS WORSE THAN A SPINNER:
            //     FixupMessageStatusOnStartupAction — a third sweep arm, added by that same fix
            //     — moves a TRANSPORT_RCS row at YET_TO_SEND/AWAITING_RETRY to
            //     OUTGOING_FAILED on the next cold start.
            //
            // So a SUCCESSFUL location share did not merely read "Sending…" for ever: it read
            // "Sending…" until the next process start and then went RED, "Not sent", on a location
            // the peer had already received. An affirmatively wrong statement, not an ambiguous one.
            //
            // THE SWEEP'S ESCAPE HATCH IS HALF SHUT ON THIS VERB, and its own correctness argument
            // is what has to be checked arm by arm (FixupMessageStatusOnStartupAction :96-101): a
            // false FAILED is acceptable there because it is "visible, actionable, and
            // SELF-CORRECTING — UpdateRcsMessageStatusAction's KIND_STATUS and KIND_IMDN arms both
            // write the row unconditionally, so a late callback or receipt still upgrades it".
            // Those are TWO arms and they do not have the same answer here:
            //
            //   - KIND_STATUS — unavailable. It is driven by ProviderSink.onMessageStatus,
            //     and this verb reaches none of the three emission sites (first bullet above). No
            //     callback will ever arrive, so this arm cannot fire.
            //   - KIND_IMDN — untraced, and structurally reachable. An IMDN comes from the PEER
            //     on the inbound path, not from the sink, so "it never enters TachyonTransport"
            //     says nothing about it. UpdateRcsMessageStatusAction writes
            //     MessageColumns.STATUS = OUTGOING_DELIVERED on that arm, and it finds the row by
            //     findLocalIdByRcsMessageId (:96) against the rcs_message_id the write below
            //     stamps. Whether a peer actually returns an IMDN for rcspushlocation+xml is peer
            //     behaviour this send path cannot observe, and nobody has measured it.
            //
            // So a false FAILED MAY be permanent, not IS. The hedge is deliberate and is the whole
            // reason the write below matters: if the IMDN arm does not fire, nothing else can, and
            // a delivered share stays labelled failed. Recording the weaker claim rather than the
            // one that reads better — an earlier revision of this comment asserted PERMANENT
            // outright, twenty-two lines above its own sentence saying "a later IMDN still upgrades
            // this row", which is the contradiction that produced this paragraph.
            //
            // The answer we already hold is the only one there will be: the `!result.accepted`
            // early return above means control only arrives here when the provider ACCEPTED.
            //
            // DO NOT RESTATE THIS AS A RULE ABOUT WHICH LAYER THE SERVICE CALLS. That is the
            // natural generalisation — "routed through TachyonTransport ⇒ fires a sink; routed
            // statically to TachyonRegistrar ⇒ fires nothing" — it stood in this comment, and it is
            // FALSE: RcsProviderService.sendReaction dispatches through transport().sendReaction,
            // i.e. it DOES go through the class that owns the sink, and still emits nothing,
            // because TachyonTransport.sendReaction never reaches finishSend.
            //
            // The predicate is whether a verb's bytes leave through one of the THREE emission sites
            // in TachyonTransport (the inline emit in sendFile, reportSendFailed, finishSend) — a
            // claim about what the file contains, so it is enumerable rather than inferred.
            // RcsSendStatusGuardTest is the mechanical form. Five successive attempts to
            // compress that table into a rule were each correct on the verbs that motivated them and
            // broken by the next one.
            //
            // Reports the SEND and never DELIVERY — a later IMDN still upgrades this row to
            // OUTGOING_DELIVERED, and its absence still means nothing was ever confirmed.
            final ContentValues locVals = RcsMessageStore.rcsMetaValues(rcsMessageId,
                    RcsSendStatus.rcsStatusForMeasuredHandoff(true));
            locVals.put(DatabaseHelper.MessageColumns.STATUS,
                    RcsSendStatus.bugleStatusForMeasuredHandoff(true));
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), locVals);
            BugleDatabaseOperations.updateConversationMetadataInTransaction(db,
                    conversationId, message.getMessageId(), timestamp,
                    false /* senderBlocked */, false /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        final SyncManager syncManager = DataModel.get().getSyncManager();
        syncManager.onNewMessageInserted(timestamp);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyConversationListChanged();
        LogUtil.i(TAG, "SendRcsLocationAction: sent location to "
                + (isGroup ? "group" : "1-1") + " conv=" + conversationId);
        return null;
    }

    /**
     * Land a location share we REFUSED to send, as a visibly FAILED outgoing RCS row — the
     * counterpart of {@code InsertNewMessageAction.insertRefusedRcsGroupMessage}.
     *
     * <p><b>The trap on THIS verb is silence, not a downgrade to another transport.</b> That is the
     * one way it differs from its siblings and it is why a bare {@code return null} could not be
     * reused: {@code executeAction} has no MMS fall-through to worry about, but it also writes no row
     * on any failure path, so a refused share would leave the user with the caller's optimistic
     * "Sharing location…" toast and then nothing at all — unable to tell a refusal from a slow send
     * from a share that went out. A leak the user cannot see is the defect; a refusal the user cannot
     * see is only half a fix.
     *
     * <p><b>{@code OUTGOING_FAILED} is terminal here, and that was checked rather than inherited.</b>
     * {@code ProcessPendingMessagesAction.findNextMessageToSend} excludes {@code TRANSPORT_RCS} from
     * the SMS/MMS queue by name (its query passes {@code TRANSPORT_TYPE !=?} with
     * {@code RcsConstants.TRANSPORT_RCS}), so a row left at {@code OUTGOING_YET_TO_SEND} is owned by
     * nothing and reads "Sending…" for ever. {@code FAILED} renders red, is what
     * every other failed RCS send already lands as, and the resend button cannot
     * re-strand it: {@code ResendMessageAction} refuses a {@code TRANSPORT_RCS} row before the write.
     * The escape hatch left on the bubble is "Send as SMS" — a downgrade the USER chooses, which is a
     * different thing from one we perform silently.
     *
     * <p>No {@code rcs_message_id} is stamped: nothing was sent, so there is no wire id to correlate,
     * and minting one would put an id in the resend register that no peer has ever seen.
     */
    private void insertRefusedLocationRow(final DatabaseWrapper db, final String conversationId,
            final String selfId, final String body, final long timestamp) {
        try {
            db.beginTransaction();
            try {
                final MessageData message =
                        MessageData.createOutgoingRcsMessage(conversationId, selfId, body);
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
                LogUtil.w(TAG, "insertRefusedLocationRow: landed " + message.getMessageId()
                        + " as OUTGOING_FAILED on conversation " + conversationId
                        + " — the location was NOT shared, in any form");
            } finally {
                db.endTransaction();
            }
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyConversationListChanged();
        } catch (final Throwable t) {
            // The refusal stands. A failed insert costs the user the visible record of a share that
            // did not happen; sending the location anyway would be the defect itself.
            LogUtil.e(TAG, "insertRefusedLocationRow: could not land the refused share on "
                    + conversationId + " — it is NOT being sent and leaves no trace in the thread.",
                    t);
        }
    }

    private SendRcsLocationAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<SendRcsLocationAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public SendRcsLocationAction createFromParcel(final Parcel in) {
            return new SendRcsLocationAction(in);
        }

        @Override
        public SendRcsLocationAction[] newArray(final int size) {
            return new SendRcsLocationAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
