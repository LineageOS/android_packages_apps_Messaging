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
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.GroupDepartureApplier;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes;

/**
 * Group management from People &amp; options: rename, add, remove, leave and change icon. Runs
 * off the main thread. Each op first asks {@link MlsProviderTransport} which plane the group is on,
 * then makes the change, and writes the local mirror and "You ..." status line only once the change
 * was accepted. The status line shares its signature with the server's echo, so the two collapse
 * to one. See docs/rcs/groups.md.
 */
public class ManageRcsGroupAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    /** Main-thread result callback so the UI can toast on a failed change. */
    public interface ManageRcsGroupListener {
        void onManageFailed(int op);
    }

    public static final int OP_RENAME = IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE;
    public static final int OP_ADD = IRcsProviderCallback.GROUP_OP_ADD_USERS;
    public static final int OP_REMOVE = IRcsProviderCallback.GROUP_OP_KICK_USERS;
    /**
     * Leave this group. A local op, outside the wire range 7 to 12: the MLS arm writes its own
     * local state and the plaintext arm records the departure through {@link
     * GroupDepartureApplier}, so {@link #applyLocalMirror} and {@link #buildSelfStatusText}
     * deliberately ignore it.
     */
    public static final int OP_LEAVE = 1000;

    /** Change the group icon (RCC.16 §9.7.1.4). A local op; no inbound group event carries it. */
    public static final int OP_CHANGE_ICON = 1001;

    private static final String KEY_OP = "op";
    private static final String KEY_CONVERSATION_ID = "conversation_id";
    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_NAME = "name";
    private static final String KEY_MEMBERS = "members";
    /** The scaled image bytes for {@link #OP_CHANGE_ICON}; see {@link #changeIcon}. */
    private static final String KEY_ICON = "icon";
    /** The image's MIME type, carried in the RCC.16 §7.8.1 FileInfo. */
    private static final String KEY_ICON_MIME = "icon_mime";

    /** Queue a rename; the conversation is renamed locally once the change is accepted. */
    public static void renameGroup(final String conversationId, final String groupId,
            final String newName, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_RENAME, conversationId,
                groupId, newName, null);
        action.startWithListener(listener);
    }

    /**
     * Queue a group icon change. The caller must scale the image: the receive side caps an inbound
     * icon (256 KB by default), and the bytes cross binder twice.
     *
     * @param iconMime the image's MIME type, carried in the RCC.16 §7.8.1 FileInfo
     */
    public static void changeIcon(final String conversationId, final String groupId,
            final byte[] iconBytes, final String iconMime,
            final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_CHANGE_ICON, conversationId,
                groupId, null, null);
        action.actionParameters.putByteArray(KEY_ICON, iconBytes);
        action.actionParameters.putString(KEY_ICON_MIME, iconMime);
        action.startWithListener(listener);
    }

    /** Queue an add of {@code memberE164s} (already E.164-canonicalized). */
    public static void addUsers(final String conversationId, final String groupId,
            final ArrayList<String> memberE164s, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_ADD, conversationId,
                groupId, null, memberE164s);
        action.startWithListener(listener);
    }

    /** Queue removal of a single member (passed as a 1-element E.164 list). */
    public static void removeUsers(final String conversationId, final String groupId,
            final ArrayList<String> memberE164s, final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_REMOVE, conversationId,
                groupId, null, memberE164s);
        action.startWithListener(listener);
    }

    /** Queue our own departure from {@code groupId}. */
    public static void leaveGroup(final String conversationId, final String groupId,
            final ManageRcsGroupListener listener) {
        final ManageRcsGroupAction action = new ManageRcsGroupAction(OP_LEAVE, conversationId,
                groupId, null, null);
        action.startWithListener(listener);
    }

    private ManageRcsGroupAction(final int op, final String conversationId, final String groupId,
            @Nullable final String name, @Nullable final ArrayList<String> memberE164s) {
        actionParameters.putInt(KEY_OP, op);
        actionParameters.putString(KEY_CONVERSATION_ID, conversationId);
        actionParameters.putString(KEY_GROUP_ID, groupId);
        actionParameters.putString(KEY_NAME, name);
        actionParameters.putStringArrayList(KEY_MEMBERS,
                memberE164s == null ? new ArrayList<>() : memberE164s);
    }

    private void startWithListener(final ManageRcsGroupListener listener) {
        final ManageRcsGroupActionMonitor monitor =
                new ManageRcsGroupActionMonitor(actionParameters.getInt(KEY_OP), listener);
        // Re-create the action with the monitor's key so completion reaches the listener.
        final ManageRcsGroupAction keyed = new ManageRcsGroupAction(this, monitor.getActionKey());
        keyed.start(monitor);
    }

    private ManageRcsGroupAction(final ManageRcsGroupAction src, final String actionKey) {
        super(actionKey);
        actionParameters.putAll(src.actionParameters);
    }

    /** @return whether the change was accepted; never null. */
    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            return Boolean.TRUE;
        }
        final int op = actionParameters.getInt(KEY_OP);
        final String conversationId = actionParameters.getString(KEY_CONVERSATION_ID);
        final String groupId = actionParameters.getString(KEY_GROUP_ID);
        final String name = actionParameters.getString(KEY_NAME);
        final ArrayList<String> members = actionParameters.getStringArrayList(KEY_MEMBERS);

        if (TextUtils.isEmpty(conversationId) || TextUtils.isEmpty(groupId)) {
            LogUtil.w(TAG, "ManageRcsGroupAction: missing conversationId/groupId; op=" + op);
            return Boolean.FALSE;
        }

        final Context context = Factory.get().getApplicationContext();
        final int subId = defaultSubId();
        final DatabaseWrapper db = DataModel.get().getDatabase();

        final String selfE164 = selfE164(subId);

        // Add and remove: an MLS group refuses the plain membership RPC, so MLS routing comes first
        // and there is no plain fallback for it.
        if (op == OP_ADD || op == OP_REMOVE) {
            final MlsTransportTypes.GroupMembershipRouting routed =
                    routeThroughMls(context, subId, groupId, members, op == OP_ADD);
            if (routed != MlsTransportTypes.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsTransportTypes.GroupMembershipRouting.APPLIED;
                if (applied) {
                    applyLocalMirror(db, conversationId, groupId, subId, op, name, members,
                            selfE164);
                    MessagingContentProvider.notifyMessagesChanged(conversationId);
                    MessagingContentProvider.notifyConversationListChanged();
                }
                // REFUSED is the verdict on the request as a whole, not a claim that the server is
                // unchanged.
                LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? "" : " — nothing written to messaging.db"));
                return Boolean.valueOf(applied);
            }
        }

        // Leave. On the MLS arm the transport has already written all local state, so nothing more
        // is written here. On the plaintext arm GroupDepartureApplier writes the "You left" line,
        // which applyLocalMirror cannot ("You removed You"); the server sends no echo to the
        // leaver.
        if (op == OP_LEAVE) {
            final MlsTransportTypes.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId).leaveGroup(groupId);
            } catch (final Throwable t) {
                // REFUSED, not PLAINTEXT: leaving is irreversible, and an MLS group refuses the
                // bare leave.
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the leave rather than sending the "
                        + "bare RPC an MLS group rejects", t);
                return Boolean.FALSE;
            }
            if (routed != MlsTransportTypes.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsTransportTypes.GroupMembershipRouting.APPLIED;
                LogUtil.i(TAG, "ManageRcsGroupAction: leave groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? " — the transport wrote its own local state" : ""));
                return Boolean.valueOf(applied);
            }
            final ProviderTransport plain = resolveTransport(context, subId);
            if (plain == null) return Boolean.FALSE;
            boolean leftOk;
            try {
                leftOk = !TextUtils.isEmpty(selfE164)
                        && plain.removeGroupUsers(subId, groupId,
                                new ArrayList<>(java.util.Collections.singletonList(selfE164)));
            } catch (final Throwable t) {
                LogUtil.w(TAG, "ManageRcsGroupAction: plaintext leave threw; groupId=" + groupId,
                        t);
                leftOk = false;
            }
            if (leftOk) {
                // After the RPC, as on every arm.
                GroupDepartureApplier.apply(subId, groupId, selfE164);
            }
            LogUtil.i(TAG, "ManageRcsGroupAction: leave groupId=" + groupId
                    + " took the PLAINTEXT path (removeGroupUsers naming ourselves) → " + leftOk
                    + (TextUtils.isEmpty(selfE164)
                            ? " — REFUSED: our own E.164 is unknown, so there is nobody to remove"
                            : ""));
            return Boolean.valueOf(leftOk);
        }

        // Rename and change icon. On an MLS group a rename is the encrypted subject (RCC.16
        // §9.7.1.5); the plain rename would put the name on the server in the clear. The decision
        // belongs to renameGroupRouting and iconChangeRouting; this site obeys it. There is no
        // plaintext icon path, so an icon change never falls through to an RPC.
        if (op == OP_CHANGE_ICON) {
            final byte[] icon = actionParameters.getByteArray(KEY_ICON);
            final String iconMime = actionParameters.getString(KEY_ICON_MIME);
            final MlsTransportTypes.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId)
                        .iconChangeRouting(groupId, icon, iconMime);
            } catch (final Throwable t) {
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the icon change", t);
                return Boolean.FALSE;
            }
            final boolean applied =
                    routed == MlsTransportTypes.GroupMembershipRouting.APPLIED;
            if (applied) {
                // The engine already showed the sender its own icon on acceptance; the mirror
                // repeats it in this transaction and writes the status line.
                applyLocalMirror(db, conversationId, groupId, subId, op, name, members, selfE164);
                MessagingContentProvider.notifyMessagesChanged(conversationId);
                MessagingContentProvider.notifyConversationListChanged();
            }
            LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " (CHANGE_ICON) groupId=" + groupId
                    + " went through MLS → " + routed + " (" + (icon == null ? 0 : icon.length)
                    + "B " + iconMime + ")"
                    + (applied ? " — §9.7.1.4 encrypted icon sent, photo mirrored locally"
                               : " — nothing sent and nothing written to messaging.db"));
            return Boolean.valueOf(applied);
        }

        if (op == OP_RENAME) {
            final MlsTransportTypes.GroupMembershipRouting routed;
            try {
                routed = MlsProviderTransport.get(context, subId)
                        .renameGroupRouting(groupId, name);
            } catch (final Throwable t) {
                // REFUSED, not PLAINTEXT: the bare rename would leak the name of a group that may
                // be encrypted.
                LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                        + groupId + " is encrypted — refusing the rename rather than sending the "
                        + "group name in the clear on a conversation that may be encrypted", t);
                return Boolean.FALSE;
            }
            if (routed != MlsTransportTypes.GroupMembershipRouting.PLAINTEXT) {
                final boolean applied =
                        routed == MlsTransportTypes.GroupMembershipRouting.APPLIED;
                if (applied) {
                    // The engine already showed the sender its own subject on acceptance; the
                    // mirror repeats it in this transaction and writes the status line. Storing the
                    // readable name locally is not a leak; only the wire carries ciphertext.
                    applyLocalMirror(db, conversationId, groupId, subId, op, name, members,
                            selfE164);
                    MessagingContentProvider.notifyMessagesChanged(conversationId);
                    MessagingContentProvider.notifyConversationListChanged();
                }
                LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " (RENAME) groupId=" + groupId
                        + " went through MLS → " + routed
                        + (applied ? " — §9.7.1.5 encrypted subject sent, name mirrored locally"
                                   : " — nothing sent and nothing written to messaging.db"));
                return Boolean.valueOf(applied);
            }
        }

        // Plaintext arm: the provider RPC runs before the local mirror, so a refused change writes
        // nothing. Rolling back instead would need an inverse the mirror does not have. The status
        // line is order-independent: whichever of it and the echo arrives second is dropped.
        final ProviderTransport transport = resolveTransport(context, subId);
        if (transport == null) {
            return Boolean.FALSE;
        }

        boolean ok;
        try {
            switch (op) {
                case OP_RENAME:
                    ok = transport.renameGroup(subId, groupId, name);
                    break;
                case OP_ADD:
                    ok = transport.addGroupUsers(subId, groupId, members);
                    break;
                case OP_REMOVE:
                    ok = transport.removeGroupUsers(subId, groupId, members);
                    break;
                default:
                    LogUtil.w(TAG, "ManageRcsGroupAction: unknown op=" + op);
                    ok = false;
                    break;
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ManageRcsGroupAction: provider call threw; op=" + op, t);
            ok = false;
        }

        // Mirror only if the server accepted the change.
        if (ok) {
            applyLocalMirror(db, conversationId, groupId, subId, op, name, members, selfE164);
            MessagingContentProvider.notifyMessagesChanged(conversationId);
            MessagingContentProvider.notifyConversationListChanged();
        }

        LogUtil.i(TAG, "ManageRcsGroupAction: op=" + op + " groupId=" + groupId
                + " result=" + ok + (ok ? "" : " — nothing written to messaging.db"));
        return Boolean.valueOf(ok);
    }

    /**
     * The subscription's selected {@link ProviderTransport}, else the singleton; null if neither.
     */
    @Nullable
    private static ProviderTransport resolveTransport(final Context context, final int subId) {
        try {
            final ProviderRegistry registry = ProviderRegistry.peek();
            final RcsTransport selected = (registry != null)
                    ? registry.getActiveTransport(subId) : null;
            return (selected instanceof ProviderTransport)
                    ? (ProviderTransport) selected : ProviderTransport.getInstance(context);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ManageRcsGroupAction: RCS transport unavailable", t);
            return null;
        }
    }

    /**
     * Asks the MLS layer to make a membership change, or to say the group is not on MLS. An
     * unreachable transport answers REFUSED, never PLAINTEXT: the bare RPC must not fire on a group
     * that could not be classified.
     */
    private static MlsTransportTypes.GroupMembershipRouting routeThroughMls(final Context context,
            final int subId, final String groupId, final List<String> members, final boolean add) {
        try {
            return MlsProviderTransport.get(context, subId)
                    .changeGroupMembership(groupId, members, add);
        } catch (final Throwable t) {
            LogUtil.e(TAG, "ManageRcsGroupAction: the MLS transport could not be asked whether "
                    + groupId + " is encrypted — refusing the " + (add ? "add" : "remove") + " "
                    + "rather than sending the bare RPC an MLS group rejects and mirroring it "
                    + "locally as though it had worked", t);
            return MlsTransportTypes.GroupMembershipRouting.REFUSED;
        }
    }

    /**
     * Mirrors an accepted change into messaging.db and writes the matching status line, attributed
     * to self so it renders "You ..." and shares its signature with the server's echo.
     */
    private void applyLocalMirror(final DatabaseWrapper db, final String conversationId,
            final String groupId, final int subId, final int op, @Nullable final String name,
            @NonNull final List<String> members, @Nullable final String selfE164) {
        switch (op) {
            case OP_RENAME:
                if (!TextUtils.isEmpty(name)) {
                    BugleDatabaseOperations.renameGroupConversation(db, conversationId, name);
                }
                break;
            case OP_ADD:
                if (!members.isEmpty()) {
                    // Roster reconcile adds the missing members and keeps the existing name.
                    BugleDatabaseOperations.getOrCreateGroupConversation(db, groupId,
                            null /* groupName */, subId, members, false /* rosterIncomplete */);
                }
                break;
            case OP_REMOVE:
                if (!members.isEmpty()) {
                    BugleDatabaseOperations.removeGroupParticipants(db, conversationId, subId,
                            members);
                }
                break;
            case OP_CHANGE_ICON:
                // GroupIconApplier is the only writer of ConversationColumns.ICON.
                final byte[] mirrorIcon = actionParameters.getByteArray(KEY_ICON);
                if (mirrorIcon != null && mirrorIcon.length > 0) {
                    com.android.messaging.rcs.GroupIconApplier.apply(
                            Factory.get().getApplicationContext(), groupId, mirrorIcon);
                }
                break;
            default:
                break;
        }

        final String statusText = buildSelfStatusText(subId, op, name, members);
        if (TextUtils.isEmpty(statusText)) {
            return;
        }
        // requester = self, so the signature matches the echo of our own action.
        final ArrayList<String> affected = (op == OP_RENAME)
                ? null : new ArrayList<>(members);
        final String signature = ReceiveRcsGroupEventAction.buildEventSignature(
                subId, op, groupId, (op == OP_RENAME) ? name : null, selfE164, affected);
        ReceiveRcsGroupEventAction.insertStatusMessage(db, conversationId, subId,
                selfE164, statusText, signature);
    }

    /** Self-attributed status text ("You renamed ...", "You added ...", "You removed ..."). */
    @Nullable
    private String buildSelfStatusText(final int subId, final int op,
            @Nullable final String name, @NonNull final List<String> members) {
        final Context context = Factory.get().getApplicationContext();
        final String you = context.getString(R.string.rcs_group_event_self);
        switch (op) {
            case OP_RENAME:
                if (TextUtils.isEmpty(name)) {
                    return null;
                }
                return context.getString(R.string.rcs_group_event_renamed_by, you, name);
            case OP_CHANGE_ICON:
                return context.getString(R.string.rcs_group_event_icon_changed_by, you);
            case OP_ADD:
                if (members.isEmpty()) {
                    return context.getString(R.string.rcs_group_event_added_generic, you);
                }
                return context.getString(R.string.rcs_group_event_added, you,
                        joinDisplayNames(subId, members));
            case OP_REMOVE:
                if (members.isEmpty()) {
                    return context.getString(R.string.rcs_group_event_removed_generic, you);
                }
                return context.getString(R.string.rcs_group_event_removed, you,
                        joinDisplayNames(subId, members));
            default:
                return null;
        }
    }

    /** Comma-joined display names for the affected members. */
    private String joinDisplayNames(final int subId, final List<String> members) {
        final ArrayList<String> names = new ArrayList<>(members.size());
        for (final String m : members) {
            if (TextUtils.isEmpty(m)) {
                continue;
            }
            names.add(ParticipantData.getFromRawPhoneBySimLocale(m, subId)
                    .getDisplayName(true /* preferFullName */));
        }
        return TextUtils.join(", ", names);
    }

    @Nullable
    private String selfE164(final int subId) {
        try {
            return PhoneUtils.get(subId).getCanonicalForSelf(true /* allowOverride */);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static int defaultSubId() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        return subId;
    }

    /** Routes the result back to the listener so a failure can toast. */
    public static class ManageRcsGroupActionMonitor extends ActionMonitor
            implements ActionMonitor.ActionCompletedListener {
        private final ManageRcsGroupListener mListener;
        private final int mOp;

        ManageRcsGroupActionMonitor(final int op, final ManageRcsGroupListener listener) {
            super(STATE_CREATED, generateUniqueActionKey("ManageRcsGroupAction"), null);
            setCompletedListener(this);
            mOp = op;
            mListener = listener;
        }

        @Override
        public void onActionSucceeded(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            if (mListener != null && Boolean.FALSE.equals(result)) {
                mListener.onManageFailed(mOp);
            }
        }

        @Override
        public void onActionFailed(final ActionMonitor monitor, final Action action,
                final Object data, final Object result) {
            if (mListener != null) {
                mListener.onManageFailed(mOp);
            }
        }
    }

    private ManageRcsGroupAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ManageRcsGroupAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ManageRcsGroupAction createFromParcel(final Parcel in) {
            return new ManageRcsGroupAction(in);
        }

        @Override
        public ManageRcsGroupAction[] newArray(final int size) {
            return new ManageRcsGroupAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
