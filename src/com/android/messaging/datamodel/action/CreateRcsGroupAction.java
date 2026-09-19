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
import com.android.messaging.datamodel.action.ActionMonitor.ActionCompletedListener;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.RunsOnMainThread;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Creates an RCS group from the new-group flow, or reports that the caller should fall back to
 * group MMS. Runs off the main thread; the provider calls block. See docs/rcs/groups.md.
 */
public class CreateRcsGroupAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Callback (main thread) for the new-group UI flow. */
    public interface CreateRcsGroupListener {
        /** RCS group created; open this group conversation. */
        @RunsOnMainThread
        void onRcsGroupCreated(String conversationId);

        /**
         * The group could not be created; the UI falls back to a group-MMS compose with
         * {@code recipients}.
         */
        @RunsOnMainThread
        void onRcsGroupFallbackToMms(ArrayList<String> recipients);
    }

    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_RECIPIENTS = "recipients";

    public static CreateRcsGroupActionMonitor createRcsGroup(final Object data,
            final String groupName, final ArrayList<String> recipientE164s,
            final CreateRcsGroupListener listener) {
        final CreateRcsGroupActionMonitor monitor =
                new CreateRcsGroupActionMonitor(data, recipientE164s, listener);
        final CreateRcsGroupAction action = new CreateRcsGroupAction(groupName, recipientE164s,
                monitor.getActionKey());
        action.start(monitor);
        return monitor;
    }

    private CreateRcsGroupAction(final String groupName,
            final ArrayList<String> recipientE164s, final String actionKey) {
        super(actionKey);
        actionParameters.putString(KEY_GROUP_NAME, groupName);
        actionParameters.putStringArrayList(KEY_RECIPIENTS, recipientE164s);
    }

    /** @return the new conversation id, or null to fall back to group MMS. */
    @Override
    protected Object executeAction() {
        final String groupName = actionParameters.getString(KEY_GROUP_NAME);
        final ArrayList<String> recipients =
                actionParameters.getStringArrayList(KEY_RECIPIENTS);
        if (recipients == null || recipients.size() < 2) {
            LogUtil.w(TAG, "CreateRcsGroupAction: <2 recipients; falling back to MMS");
            return null;
        }

        final Context context = Factory.get().getApplicationContext();
        final int subId = defaultSubId();

        // Use the subscription's selected transport when it is the provider, else the singleton.
        final ProviderTransport transport;
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            transport = (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "CreateRcsGroupAction: RCS transport unavailable", t);
            return null;
        }

        if (!transport.getRouteSelector().isGroupRcsAvailableForSub(subId)) {
            LogUtil.i(TAG, "CreateRcsGroupAction: group-RCS unavailable for sub=" + subId
                    + "; falling back to MMS");
            return null;
        }

        // The provider adds self.
        final ArrayList<String> memberE164s = new ArrayList<>(recipients.size());
        for (final String r : recipients) {
            final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(r);
            memberE164s.add(TextUtils.isEmpty(canonical) ? r : canonical);
        }

        // Any member that is not known to be RCS falls the whole group back to MMS. The lookup also
        // warms the RouteSelector cache the compose UI reads.
        for (final String member : memberE164s) {
            final int cap;
            try {
                cap = transport.lookupRcsCapability(subId, member);
            } catch (final Throwable t) {
                LogUtil.w(TAG, "CreateRcsGroupAction: capability lookup threw; "
                        + "falling back to MMS", t);
                return null;
            }
            if (cap != org.lineageos.rcs.provider.IRcsProvider.CAP_RCS) {
                LogUtil.i(TAG, "CreateRcsGroupAction: a member is not RCS-capable (cap="
                        + cap + "); falling back to group MMS");
                return null;
            }
        }

        // Client-minted 32-char lowercase-hex group id; the server echoes it.
        final String desiredGroupId =
                UUID.randomUUID().toString().replace("-", "").toLowerCase();
        final String name = groupName != null ? groupName : "";

        final RcsGroupInfo info;
        try {
            info = transport.createGroup(subId, desiredGroupId, name, memberE164s,
                    0 /* default group type */);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "CreateRcsGroupAction: createGroup threw; falling back to MMS", t);
            return null;
        }
        if (info == null || TextUtils.isEmpty(info.groupId)) {
            LogUtil.i(TAG, "CreateRcsGroupAction: createGroup failed; falling back to MMS");
            return null;
        }

        // Name: the user's, else the server's, else the generated one.
        final String effectiveName = !TextUtils.isEmpty(name) ? name : info.name;
        final java.util.List<String> roster =
                (info.members != null && !info.members.isEmpty()) ? info.members : memberE164s;

        final DatabaseWrapper db = DataModel.get().getDatabase();
        final String conversationId = BugleDatabaseOperations.getOrCreateGroupConversation(
                db, info.groupId, effectiveName, subId, roster, false /* rosterIncomplete */);
        if (TextUtils.isEmpty(conversationId)) {
            LogUtil.w(TAG, "CreateRcsGroupAction: local group conversation create failed; "
                    + "falling back to MMS");
            return null;
        }

        MessagingContentProvider.notifyConversationListChanged();
        LogUtil.i(TAG, "CreateRcsGroupAction: created RCS group groupId=" + info.groupId
                + " conv=" + conversationId + " members=" + roster.size());
        return conversationId;
    }

    private static int defaultSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        return subId;
    }

    /** Monitor that routes the result to the success / MMS-fallback callbacks. */
    public static class CreateRcsGroupActionMonitor extends ActionMonitor
            implements ActionCompletedListener {
        private final CreateRcsGroupListener mListener;
        private final ArrayList<String> mRecipients;

        CreateRcsGroupActionMonitor(final Object data, final ArrayList<String> recipients,
                final CreateRcsGroupListener listener) {
            super(STATE_CREATED, generateUniqueActionKey("CreateRcsGroupAction"), data);
            setCompletedListener(this);
            mListener = listener;
            mRecipients = recipients;
        }

        @Override
        public void onActionSucceeded(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            if (result == null) {
                mListener.onRcsGroupFallbackToMms(mRecipients);
            } else {
                mListener.onRcsGroupCreated((String) result);
            }
        }

        @Override
        public void onActionFailed(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            Assert.fail("CreateRcsGroupAction: unreachable onActionFailed");
            mListener.onRcsGroupFallbackToMms(mRecipients);
        }
    }

    private CreateRcsGroupAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<CreateRcsGroupAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public CreateRcsGroupAction createFromParcel(final Parcel in) {
            return new CreateRcsGroupAction(in);
        }

        @Override
        public CreateRcsGroupAction[] newArray(final int size) {
            return new CreateRcsGroupAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
