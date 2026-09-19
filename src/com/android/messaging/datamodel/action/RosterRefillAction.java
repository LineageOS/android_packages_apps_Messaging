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

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.lineageos.rcs.provider.RcsGroupInfo;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

import java.util.List;

/**
 * WAVE-A / A2.6: deferred self-heal for RCS group conversations that were
 * created with an INCOMPLETE roster.
 *
 * <p>When an inbound fanned-out group message creates a conversation while the
 * provider is unbound, {@code getGroupInfo} can't be called, so the conversation
 * is seeded with only the sender and flagged {@code needs_roster_refill}. A
 * single-sender group has {@code participant_count == 1}, which makes
 * {@link com.android.messaging.datamodel.data.ConversationListItemData#getIsGroup()}
 * revert the row to 1:1 styling -- a permanently-broken-looking group.
 *
 * <p>This action walks every flagged conversation, re-fetches the full server
 * roster via {@code ProviderTransport.getGroupInfo}, and reconciles it (adding
 * the missing members + recomputing {@code participant_count}) with
 * {@code rosterIncomplete=false} so the flag clears. It is fired when the
 * provider (re)binds; flagged conversations that still can't be filled (provider
 * returned nothing) keep the flag and are retried on the next bind.
 */
public class RosterRefillAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Fire-and-forget: drain all pending roster refills off the main thread. */
    public static void refillPendingRosters() {
        new RosterRefillAction().start();
    }

    public RosterRefillAction() {
        super();
    }

    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            return null;
        }
        final Context context = Factory.get().getApplicationContext();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final List<String[]> pending =
                BugleDatabaseOperations.getConversationsNeedingRosterRefill(db);
        if (pending.isEmpty()) {
            return null;
        }

        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport == null || !transport.isAttached()) {
            LogUtil.i(TAG, "RosterRefillAction: provider unbound; "
                    + pending.size() + " conversation(s) still deferred");
            return null;
        }

        final int subId = defaultSubId();
        int healed = 0;
        for (final String[] row : pending) {
            final String conversationId = row[0];
            final String groupId = row[1];
            try {
                final RcsGroupInfo gi = transport.getGroupInfo(subId, groupId);
                if (gi == null || gi.members == null || gi.members.isEmpty()) {
                    LogUtil.w(TAG, "RosterRefillAction: getGroupInfo empty for groupId="
                            + groupId + "; keeping deferred");
                    continue;
                }
                // rosterIncomplete=false -> full roster in hand; this reconciles
                // membership + participant_count and clears needs_roster_refill.
                // A server name (gi.name) still wins per A2.2; a null name leaves
                // the existing (possibly generated) name intact.
                BugleDatabaseOperations.getOrCreateGroupConversation(
                        db, groupId, gi.name, subId, gi.members, false /* rosterIncomplete */);
                MessagingContentProvider.notifyMessagesChanged(conversationId);
                healed++;
            } catch (final Throwable t) {
                LogUtil.w(TAG, "RosterRefillAction: refill failed for groupId=" + groupId, t);
            }
        }
        if (healed > 0) {
            MessagingContentProvider.notifyConversationListChanged();
            LogUtil.i(TAG, "RosterRefillAction: healed " + healed + "/" + pending.size()
                    + " group conversation roster(s)");
        }
        return null;
    }

    private static int defaultSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        return subId;
    }

    private RosterRefillAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<RosterRefillAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public RosterRefillAction createFromParcel(final Parcel in) {
            return new RosterRefillAction(in);
        }

        @Override
        public RosterRefillAction[] newArray(final int size) {
            return new RosterRefillAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
