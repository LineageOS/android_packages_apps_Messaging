/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Fills in the roster of every group conversation flagged {@code needs_roster_refill}, which is
 * set when a group message creates the conversation while the provider is unbound. Runs on each
 * provider attach; a group that still returns nothing keeps the flag. See docs/rcs/groups.md.
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
                // rosterIncomplete=false clears the flag; a null server name keeps the existing
                // one.
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
