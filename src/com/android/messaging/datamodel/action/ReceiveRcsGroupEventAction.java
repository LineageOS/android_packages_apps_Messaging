/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsGroupInfo;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a group lifecycle event from {@code IRcsProviderCallback.onGroupEvent} to the group
 * conversation: roster sync, member removal, rename, the {@code rcs_self_left} mark, and a status
 * line. {@code op} is one of {@code IRcsProviderCallback.GROUP_OP_*}. See docs/rcs/groups.md.
 */
public class ReceiveRcsGroupEventAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_SUB_ID = "sub_id";
    private static final String KEY_OP = "op";
    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_NAME = "name";
    private static final String KEY_CONFERENCE_URI = "conference_uri";
    private static final String KEY_REQUESTER = "requester";
    private static final String KEY_MEMBERS = "members";
    private static final String KEY_AFFECTED = "affected";

    public ReceiveRcsGroupEventAction(final int subId, final int op,
            @Nullable final String groupId, @Nullable final String name,
            @Nullable final String conferenceUri, @Nullable final String requester,
            @NonNull final ArrayList<String> members,
            @NonNull final ArrayList<String> affectedMembers) {
        actionParameters.putInt(KEY_SUB_ID, subId);
        actionParameters.putInt(KEY_OP, op);
        actionParameters.putString(KEY_GROUP_ID, groupId);
        actionParameters.putString(KEY_NAME, name);
        actionParameters.putString(KEY_CONFERENCE_URI, conferenceUri);
        actionParameters.putString(KEY_REQUESTER, requester);
        actionParameters.putStringArrayList(KEY_MEMBERS, members);
        actionParameters.putStringArrayList(KEY_AFFECTED, affectedMembers);
    }

    @Override
    protected Object executeAction() {
        if (OsUtil.isSecondaryUser()) {
            // Only the primary user persists.
            return null;
        }

        final int subId = actionParameters.getInt(KEY_SUB_ID);
        final int op = actionParameters.getInt(KEY_OP);
        final String groupId = actionParameters.getString(KEY_GROUP_ID);
        final String name = actionParameters.getString(KEY_NAME);
        final String requester = actionParameters.getString(KEY_REQUESTER);
        ArrayList<String> members = actionParameters.getStringArrayList(KEY_MEMBERS);
        final ArrayList<String> affected = actionParameters.getStringArrayList(KEY_AFFECTED);

        if (TextUtils.isEmpty(groupId)) {
            LogUtil.w(TAG, "ReceiveRcsGroupEventAction: empty groupId; dropping op=" + op);
            return null;
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();

        // Some ops carry no roster; fetch it so the conversation gets every member.
        if ((members == null || members.isEmpty())
                && (op == IRcsProviderCallback.GROUP_OP_CREATE
                        || op == IRcsProviderCallback.GROUP_OP_ADD_USERS)) {
            members = fetchGroupMembers(subId, groupId);
        }

        final String conversationId = BugleDatabaseOperations.getOrCreateGroupConversation(
                db, groupId, name, subId, members);
        if (conversationId == null) {
            LogUtil.w(TAG, "ReceiveRcsGroupEventAction: could not create group conversation for "
                    + groupId);
            return null;
        }

        // The only writer of rcs_self_left. The participants table cannot answer membership:
        // removing self is a no-op there. The clear is wider than the set, because hiding Leave
        // from a member is worse than offering it to a non-member.
        if (op == IRcsProviderCallback.GROUP_OP_KICK_USERS && listIncludesSelf(subId, affected)) {
            BugleDatabaseOperations.setConversationSelfLeft(db, conversationId, true);
            LogUtil.i(TAG, "ReceiveRcsGroupEventAction: WE are out of group " + groupId + " ("
                    + (samePhone(subId, requester, selfIn(subId, affected))
                            ? "we left" : "removed by " + LogMask.number(requester))
                    + ") — conversation " + conversationId
                    + " marked rcs_self_left; the group-management rows stop being offered");
        } else if ((op == IRcsProviderCallback.GROUP_OP_ADD_USERS
                        || op == IRcsProviderCallback.GROUP_OP_CREATE)
                && (listIncludesSelf(subId, affected) || listIncludesSelf(subId, members))) {
            BugleDatabaseOperations.setConversationSelfLeft(db, conversationId, false);
        }

        switch (op) {
            case IRcsProviderCallback.GROUP_OP_KICK_USERS:
                BugleDatabaseOperations.removeGroupParticipants(db, conversationId, subId,
                        affected);
                break;
            case IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE:
                if (!TextUtils.isEmpty(name)) {
                    BugleDatabaseOperations.renameGroupConversation(db, conversationId, name);
                }
                break;
            default:
                // Membership was synced above. CHANGE_INFO accompanies every mutation and is not a
                // rename.
                break;
        }

        // A status line, stored with TRANSPORT_RCS_SYSTEM and de-duplicated on the event signature.
        final String statusText = buildStatusText(subId, op, requester, name, affected);
        if (!TextUtils.isEmpty(statusText)) {
            final String signature =
                    buildEventSignature(subId, op, groupId, name, requester, affected);
            insertStatusMessage(db, conversationId, subId, requester, statusText, signature);
        }

        LogUtil.i(TAG, "ReceiveRcsGroupEventAction: op=" + op + " groupId=" + groupId
                + " conv=" + conversationId + " members="
                + (members == null ? 0 : members.size()) + " affected="
                + (affected == null ? 0 : affected.size()));

        BugleNotifications.update(false /*silent*/, conversationId, BugleNotifications.UPDATE_ALL);
        MessagingContentProvider.notifyMessagesChanged(conversationId);
        MessagingContentProvider.notifyConversationListChanged();
        return null;
    }

    /** Best-effort full-roster backfill via the provider; empty list on failure. */
    @NonNull
    private ArrayList<String> fetchGroupMembers(final int subId, final String groupId) {
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport == null) {
            return new ArrayList<>();
        }
        final RcsGroupInfo info = transport.getGroupInfo(subId, groupId);
        if (info == null || info.members == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(info.members);
    }

    /**
     * The status line for the change, with self rendered as "You", or null when there is nothing to
     * show (for example CHANGE_ROLE, or a rename with no name).
     */
    @Nullable
    private String buildStatusText(final int subId, final int op,
            @Nullable final String requester, @Nullable final String name,
            @Nullable final List<String> affected) {
        final Context context = Factory.get().getApplicationContext();
        final boolean requesterIsSelf = isSelfNumber(subId, requester);
        final String actor = requesterIsSelf
                ? context.getString(R.string.rcs_group_event_self)
                : resolveDisplayName(subId, requester);
        final String firstAffected = (affected == null || affected.isEmpty())
                ? null : affected.get(0);
        final boolean affectedIsSelf = isSelfNumber(subId, firstAffected);

        switch (op) {
            case IRcsProviderCallback.GROUP_OP_CREATE:
                return (TextUtils.isEmpty(requester) || requesterIsSelf)
                        ? context.getString(R.string.rcs_group_event_created)
                        : context.getString(R.string.rcs_group_event_created_by, actor);

            case IRcsProviderCallback.GROUP_OP_ADD_USERS:
                if (firstAffected == null) {
                    return context.getString(R.string.rcs_group_event_added_generic, actor);
                }
                if (affectedIsSelf) {
                    return context.getString(R.string.rcs_group_event_you_added, actor);
                }
                return context.getString(R.string.rcs_group_event_added, actor,
                        resolveDisplayName(subId, firstAffected));

            case IRcsProviderCallback.GROUP_OP_KICK_USERS:
                if (firstAffected == null) {
                    return context.getString(R.string.rcs_group_event_removed_generic, actor);
                }
                // requester == affected means the member left. Tested before the "removed you" arm,
                // which is also true when we are the leaver (our own leave arrives here with
                // requester == affected == self).
                if (samePhone(subId, requester, firstAffected)) {
                    return context.getString(R.string.rcs_group_event_left, actor);
                }
                if (affectedIsSelf) {
                    return context.getString(R.string.rcs_group_event_you_removed, actor);
                }
                return context.getString(R.string.rcs_group_event_removed, actor,
                        resolveDisplayName(subId, firstAffected));

            case IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE:
                if (TextUtils.isEmpty(name)) {
                    return null;
                }
                return (TextUtils.isEmpty(requester) || requesterIsSelf)
                        ? context.getString(R.string.rcs_group_event_renamed, name)
                        : context.getString(R.string.rcs_group_event_renamed_by, actor, name);

            // CHANGE_INFO accompanies every mutation; it gets no status line.

            default:
                // CHANGE_ROLE and anything unmapped: no status line.
                return null;
        }
    }

    /** Display name for a raw phone (formatted destination), or empty if null. */
    @NonNull
    private String resolveDisplayName(final int subId, @Nullable final String rawPhone) {
        if (TextUtils.isEmpty(rawPhone)) {
            return "";
        }
        return ParticipantData.getFromRawPhoneBySimLocale(rawPhone, subId)
                .getDisplayName(true /* preferFullName */);
    }

    /** True when {@code rawPhone} canonicalizes to this device's self number. */
    private boolean isSelfNumber(final int subId, @Nullable final String rawPhone) {
        if (TextUtils.isEmpty(rawPhone)) {
            return false;
        }
        try {
            final String selfE164 = com.android.messaging.util.PhoneUtils.get(subId)
                    .getCanonicalForSelf(true /* allowOverride */);
            final String otherE164 = com.android.messaging.util.PhoneUtils.get(subId)
                    .getCanonicalBySimLocale(rawPhone);
            return !TextUtils.isEmpty(selfE164) && selfE164.equals(otherE164);
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Whether the list contains our number. Checks the whole list: a multi-member kick that
     * includes us need not list us first.
     */
    private boolean listIncludesSelf(final int subId, @Nullable final List<String> members) {
        return selfIn(subId, members) != null;
    }

    /** Our own entry in {@code members} as the list spells it, or null. */
    @Nullable
    private String selfIn(final int subId, @Nullable final List<String> members) {
        if (members == null) {
            return null;
        }
        for (final String m : members) {
            if (isSelfNumber(subId, m)) {
                return m;
            }
        }
        return null;
    }

    /** True when two raw phone numbers canonicalize to the same E.164. */
    private boolean samePhone(final int subId, @Nullable final String a, @Nullable final String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return false;
        }
        try {
            final String ca = com.android.messaging.util.PhoneUtils.get(subId)
                    .getCanonicalBySimLocale(a);
            final String cb = com.android.messaging.util.PhoneUtils.get(subId)
                    .getCanonicalBySimLocale(b);
            return !TextUtils.isEmpty(ca) && ca.equals(cb);
        } catch (final Throwable t) {
            return a.equals(b);
        }
    }

    /**
     * The de-duplication key for a status line, stored in {@code rcs_message_id} of the system row.
     * A local write and the server's echo of the same action must produce the same key, so numbers
     * are canonicalised to E.164 here whatever format each side passed.
     */
    @NonNull
    static String buildEventSignature(final int subId, final int op, final String groupId,
            @Nullable final String name, @Nullable final String requester,
            @Nullable final List<String> affected) {
        final ArrayList<String> sorted = new ArrayList<>();
        if (affected != null) {
            for (final String a : affected) {
                sorted.add(canonicalize(subId, a));
            }
        }
        java.util.Collections.sort(sorted);
        // Only rename-class ops key on the name: an add or kick echo may carry an incidental name.
        final boolean nameKeyed = (op == IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE
                || op == IRcsProviderCallback.GROUP_OP_CHANGE_INFO);
        final String nameComponent = (nameKeyed && name != null) ? name : "";
        // The echo of our own rename carries no requester, so rename-class ops do not key on it.
        final String requesterComponent = nameKeyed ? "" : canonicalize(subId, requester);
        return "grpevt:" + op + ":" + groupId + ":" + nameComponent
                + ":" + requesterComponent
                + ":" + TextUtils.join(",", sorted);
    }

    /** E.164-canonicalize a raw phone for the signature; empty string on null/failure. */
    @NonNull
    private static String canonicalize(final int subId, @Nullable final String rawPhone) {
        if (TextUtils.isEmpty(rawPhone)) {
            return "";
        }
        try {
            final String c = com.android.messaging.util.PhoneUtils.get(subId)
                    .getCanonicalBySimLocale(rawPhone);
            return TextUtils.isEmpty(c) ? rawPhone : c;
        } catch (final Throwable t) {
            return rawPhone;
        }
    }

    /**
     * Inserts the status line as a read, received row attributed to the requester (self when there
     * is none) and stamped {@code TRANSPORT_RCS_SYSTEM}. Skipped when {@code signature} already
     * exists.
     */
    static void insertStatusMessage(final DatabaseWrapper db, final String conversationId,
            final int subId, @Nullable final String requester, final String text,
            final String signature) {
        final long now = System.currentTimeMillis();

        // Scoped to system rows, so a content row whose server id happens to match is never hit.
        if (RcsMessageStore.findLocalIdByRcsSystemSignature(db, signature) != null) {
            LogUtil.d(TAG, "ReceiveRcsGroupEventAction: duplicate group event, skipping insert");
            return;
        }

        final ParticipantData sender = !TextUtils.isEmpty(requester)
                ? ParticipantData.getFromRawPhoneBySimLocale(requester, subId)
                : ParticipantData.getSelfParticipant(subId);
        final ParticipantData self = ParticipantData.getSelfParticipant(subId);

        db.beginTransaction();
        try {
            final String senderId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, sender);
            final String selfId =
                    BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);
            final MessageData message = MessageData.createReceivedRcsMessage(conversationId,
                    senderId, selfId, text, now, now, true /* seen */, true /* read */);
            BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
            final android.content.ContentValues v = new android.content.ContentValues();
            v.put(RcsMessageStore.COLUMN_TRANSPORT_TYPE, RcsConstants.TRANSPORT_RCS_SYSTEM);
            v.put(RcsMessageStore.COLUMN_RCS_MESSAGE_ID, signature);
            BugleDatabaseOperations.updateMessageRow(db, message.getMessageId(), v);
            BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                    message.getMessageId(), now, false /* senderBlocked */,
                    null /* serviceCenter */, true /* shouldAutoSwitchSelfId */);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private ReceiveRcsGroupEventAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<ReceiveRcsGroupEventAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public ReceiveRcsGroupEventAction createFromParcel(final Parcel in) {
            return new ReceiveRcsGroupEventAction(in);
        }

        @Override
        public ReceiveRcsGroupEventAction[] newArray(final int size) {
            return new ReceiveRcsGroupEventAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
