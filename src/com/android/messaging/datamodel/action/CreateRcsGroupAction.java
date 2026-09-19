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
import com.android.messaging.datamodel.action.ActionMonitor.ActionCompletedListener;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.RunsOnMainThread;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.UUID;

/**
 * WAVE-A / A1: create an RCS group from the new-group UI flow.
 *
 * <p>Runs off the main thread (the provider calls are blocking binder). The
 * decision logic mirrors {@code InsertNewMessageAction.tryInsertSendingRcsGroupMessage}:
 * <ul>
 *   <li>if group-RCS is unavailable for the sub -> caller falls back to group
 *       MMS (this action returns the MMS-fallback result);</li>
 *   <li>Policy P2: probe every recipient via
 *       {@code lookupRcsCapability}; if any is not RCS-capable, fall back to
 *       group MMS. Only when ALL are RCS-capable do we client-mint a GROUP_ID
 *       and call {@code ProviderTransport.createGroup};</li>
 *   <li>on success, create-or-find the local group conversation mapped to the
 *       echoed GROUP_ID (with the optional user-supplied name) and return its
 *       conversationId so the UI opens the thread;</li>
 *   <li>on createGroup null/failure -> MMS-fallback result.</li>
 * </ul>
 *
 * <p>No message is sent here -- a group with no messages is fine; the first
 * compose send routes through the existing {@code tryInsertSendingRcsGroupMessage}
 * path which sees the {@code rcs_group_id} already mapped and reuses it.
 */
public class CreateRcsGroupAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Callback (main thread) for the new-group UI flow. */
    public interface CreateRcsGroupListener {
        /** RCS group created; open this group conversation. */
        @RunsOnMainThread
        void onRcsGroupCreated(String conversationId);

        /**
         * RCS group could not be created (RCS unavailable / provider unbound /
         * server rejected). The UI should fall back to the existing group-MMS
         * compose using {@code recipients}.
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

    /**
     * @return the conversationId on RCS-group success, or {@code null} to signal
     *     the caller should fall back to group MMS.
     */
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

        // Multi-transport framework (design §2, §5.4): route the rich provider-only
        // createGroup surface through the sub's selected transport when it IS a
        // ProviderTransport, else fall back to the legacy singleton (byte-identical on
        // a provider-only device, same fallback as InsertNewMessageAction).
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

        if (!RouteSelector.GROUP_RCS_ENABLED
                || !transport.getRouteSelector().isGroupRcsAvailableForSub(subId)) {
            LogUtil.i(TAG, "CreateRcsGroupAction: group-RCS unavailable for sub=" + subId
                    + "; falling back to MMS");
            return null;
        }

        // Canonicalize members to E.164 (provider adds self per the wire contract).
        final ArrayList<String> memberE164s = new ArrayList<>(recipients.size());
        for (final String r : recipients) {
            final String canonical = PhoneUtils.getDefault().getCanonicalBySimLocale(r);
            memberE164s.add(TextUtils.isEmpty(canonical) ? r : canonical);
        }

        // Policy P2: per-recipient capability discovery. Only form an
        // RCS group when EVERY member is RCS-capable; any SMS-only / undetermined
        // member falls the whole conversation back to group MMS. lookupRcsCapability
        // is a blocking binder probe (we're off the main thread) that also warms
        // the RouteSelector cache, so the compose UI agrees afterward.
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

        // Client-minted 32-char lowercase-hex GROUP_ID; the server echoes it.
        final String desiredGroupId =
                UUID.randomUUID().toString().replace("-", "").toLowerCase();
        final String name = groupName != null ? groupName : "";

        final RcsGroupInfo info;
        try {
            info = transport.createGroup(subId, desiredGroupId, name, memberE164s,
                    0 /* groupType=DEFAULT */);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "CreateRcsGroupAction: createGroup threw; falling back to MMS", t);
            return null;
        }
        if (info == null || TextUtils.isEmpty(info.groupId)) {
            LogUtil.i(TAG, "CreateRcsGroupAction: createGroup failed; falling back to MMS");
            return null;
        }

        // Create-or-find the local group conversation mapped to the echoed
        // GROUP_ID. Prefer the user-supplied name; else the server-echoed name;
        // else the generated joined-names fallback inside getOrCreateGroupConversation.
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
