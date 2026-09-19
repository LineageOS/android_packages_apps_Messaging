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
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.OsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Receive an inbound Tachygram group lifecycle event (kind=GROUP(5))
 * the provider decoded from a GroupEvent and delivered via
 * {@code IRcsProviderCallback.onGroupEvent}, and mutate messaging.db so the
 * existing group-conversation UI renders it.
 *
 * <p>Routes by the opaque GROUP_ID into the same multi-participant conversation
 * model group MMS uses:
 * <ul>
 *   <li>CREATE / ADD_USERS / CHANGE_* -> find-or-create the group conversation
 *       keyed by {@code rcs_group_id}, sync participants from {@code members}
 *       (backfilled via {@code getGroupInfo} when the event list is empty),</li>
 *   <li>KICK_USERS -> remove the affected members from the participants table,</li>
 *   <li>CHANGE_PROFILE / CHANGE_INFO with a name -> rename the conversation,</li>
 *   <li>each event also drops a status text bubble ("X added Y", "Group renamed
 *       to ...", "You were added") so the change is visible in-thread.</li>
 * </ul>
 *
 * <p>op is one of {@code IRcsProviderCallback.GROUP_OP_*} (on-wire 7..12).
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
            // Mirror the receive path: only the primary user persists.
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

        // If the event carries no membership (some ops only send affected
        // members / a profile change), backfill the full roster from the
        // provider so the conversation has the complete participant set.
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

        // ARE WE STILL IN THIS GROUP? The ONE place that answers it, because it is the
        // one place BOTH departure routes arrive at: the wire echo when somebody else
        // removes us, and GroupDepartureApplier for our own PLAINTEXT leave, which dispatches this
        // very Action.
        //
        // WHY NOT THE PARTICIPANTS TABLE, checked rather than assumed: removeGroupParticipants
        // builds its lookup from !p.isSelf() participants, so removing OURSELVES is a no-op by
        // construction and the SELF row survives in both directions — deliberately, since leaving a
        // group must not delete our own identity from the conversation.
        //
        // THE CLEAR IS NOT AN AFTERTHOUGHT. Without it a re-add leaves the rows hidden on a group
        // the user is back in, which is the WORSE direction: every path this gating closes ends in
        // a refusal, while hiding Leave on a group we are still in strands the user. So the clear is
        // deliberately WIDER than the set — any ADD/CREATE that lists us, in `affected` OR in the
        // roster the server reported — because a non-member does not receive these events at all,
        // and one that arrives naming us is the server saying we are in.
        //
        // IT IS A CONVERSATION COLUMN AND NOTHING MORE. It holds no key or session state; it is
        // the RCS-plane membership fact for exactly the two routes named above.
        if (op == IRcsProviderCallback.GROUP_OP_KICK_USERS && listIncludesSelf(subId, affected)) {
            BugleDatabaseOperations.setConversationSelfLeft(db, conversationId, true);
            LogUtil.i(TAG, "ReceiveRcsGroupEventAction: WE are out of group " + groupId + " ("
                    + (samePhone(subId, requester, selfIn(subId, affected))
                            ? "we left" : "removed by " + requester)
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
                // CREATE / ADD_USERS / CHANGE_ROLE / CHANGE_INFO: membership already
                // synced by getOrCreateGroupConversation above. CHANGE_INFO (op 12)
                // is a generic "group state changed" echo the server fires alongside
                // every mutation (rename, kick, add) — it is NOT a rename, so it
                // must not rename the conversation.
                break;
        }

        // WAVE-B: drop a centered, muted system status line describing the change
        // so it is visible in-thread (created / member added / removed / left /
        // renamed). Stored SMS-shaped but stamped TRANSPORT_RCS_SYSTEM so the
        // renderer draws it as a status line, not a sender bubble. De-duped on a
        // stable event signature so the same inbound event is never written
        // twice (full optimistic/echo de-dup is Wave C).
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
     * Human-readable status line for the change, using resolved display names
     * and rendering the self participant as "You". Returns null when there is
     * nothing worth showing (e.g. CHANGE_ROLE, or a rename with no name).
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
                // requester == affected -> the member left on their own. TESTED FIRST, and the
                // order is the fix: when the leaver is US both this and the
                // "you were removed" arm below are true, and only this one is right. With the
                // arms the other way round our own MLS self_remove — which is reported through
                // this same Action with requester == affected == self, since that is literally
                // what a self_remove is — rendered as "You removed you". `actor` already resolves
                // to "You" for self and to the display name otherwise, and requester and affected
                // are the same person in this arm, so one placeholder serves both cases.
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

            // GROUP_OP_CHANGE_INFO (op 12): generic state-change echo fired on every
            // mutation (rename/kick/add) -> no system line (handled in default).

            default:
                // CHANGE_ROLE and anything unmapped: non-noisy, no system line.
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
     * Does this member list contain OUR number?
     *
     * <p><b>The whole list, not {@code affected.get(0)}.</b> {@link #buildStatusText} looks only at
     * the first affected member, which is right for a status line (it renders one) and wrong for a
     * membership fact: a multi-member kick that includes us alongside somebody else would answer
     * "not us" and leave the rows offered on a group we are out of.
     */
    private boolean listIncludesSelf(final int subId, @Nullable final List<String> members) {
        return selfIn(subId, members) != null;
    }

    /** Our own entry in {@code members} as the list spells it, or null. See {@link #listIncludesSelf}. */
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
     * Stable per-event signature used to de-dup the system line, stored in the
     * additive {@code rcs_message_id} column on the SYSTEM row and looked up via
     * {@link RcsMessageStore#findLocalIdByRcsSystemSignature} before insert.
     *
     * <p>WAVE-C: this is the SHARED de-dup key for the optimistic-self write and
     * the inbound {@code onGroupEvent} echo. For them to collide to one line the
     * key must be format-independent, so the requester + affected phone numbers
     * are canonicalized to E.164 ({@code getCanonicalBySimLocale}) HERE — the
     * inbound path passes raw wire numbers while the optimistic path passes
     * {@code getCanonicalForSelf}; canonicalizing both inside this method makes
     * them equal regardless of how each side formatted the number. {@code subId}
     * drives the SIM-locale canonicalization. Coarse by design (op + group +
     * name + actor + sorted affected).
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
        // Only rename-class ops key on the name; for ADD/KICK the wire echo may
        // carry an incidental group name the optimistic write doesn't know, so
        // folding it in would break the collision. Affected members drive
        // ADD/KICK; name drives CHANGE_PROFILE/CHANGE_INFO.
        final boolean nameKeyed = (op == IRcsProviderCallback.GROUP_OP_CHANGE_PROFILE
                || op == IRcsProviderCallback.GROUP_OP_CHANGE_INFO);
        final String nameComponent = (nameKeyed && name != null) ? name : "";
        // Rename-class ops: the server echo for our OWN rename carries
        // requester=null while the optimistic write uses self, so they would never
        // collide if requester were keyed. A rename is identified by op+group+name,
        // so exclude the requester from the rename signature. ADD/KICK keep it (it
        // disambiguates who acted).
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
     * Insert the status line as a received-complete row attributed to the
     * requester (falls back to self when no requester), then stamp it
     * {@code TRANSPORT_RCS_SYSTEM} so the renderer draws it as a centered, muted
     * status line rather than a sender bubble. De-duped on {@code signature}.
     */
    static void insertStatusMessage(final DatabaseWrapper db, final String conversationId,
            final int subId, @Nullable final String requester, final String text,
            final String signature) {
        final long now = System.currentTimeMillis();

        // De-dup: skip if a SYSTEM row for this exact event signature already
        // exists. WAVE-C: scoped to TRANSPORT_RCS_SYSTEM so the optimistic-self
        // write and the inbound onGroupEvent echo (which compute the SAME
        // signature) collapse to one line, without ever false-matching a real
        // RCS content row that happens to carry a colliding server message id.
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
            // Stamp the additive RCS columns: TRANSPORT_RCS_SYSTEM marks this as a
            // system line; the signature lands in rcs_message_id for de-dup.
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
