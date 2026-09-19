/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 The LineageOS Project
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
package com.android.messaging.ui.conversationsettings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;

import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.action.ManageRcsGroupAction;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.ParticipantListItemData;
import com.android.messaging.datamodel.data.PeopleAndOptionsData;
import com.android.messaging.datamodel.data.PeopleAndOptionsData.PeopleAndOptionsDataListener;
import com.android.messaging.datamodel.data.PeopleOptionsItemData;
import com.android.messaging.datamodel.data.PersonItemData;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.ReadReceiptSettings;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.ui.CompositeAdapter;
import com.android.messaging.ui.PersonItemView;
import com.android.messaging.ui.conversation.ConversationActivity;
import com.android.messaging.util.Assert;
import com.android.messaging.util.NotificationsUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a list of participants of a conversation and displays options.
 */
public class PeopleAndOptionsFragment extends Fragment
        implements PeopleAndOptionsDataListener, PeopleOptionsItemView.HostInterface {
    private ListView mListView;
    private OptionsListAdapter mOptionsListAdapter;
    private PeopleListAdapter mPeopleListAdapter;
    private GroupActionsAdapter mGroupActionsAdapter;
    private List<ParticipantData> mOtherParticipants;
    private final Binding<PeopleAndOptionsData> mBinding =
            BindingBase.createBinding(this);

    // WAVE-C: the RCS group this conversation maps to (null for 1:1 / MMS) and
    // its current display name. Extracted from the conversation-metadata cursor.
    // Drives visibility of the GROUP member-management section and the
    // per-member "Remove from group" long-press affordance.
    private String mRcsGroupId;
    private String mGroupName;

    // WAVE-C: request code for the system contact picker launched by "Add
    // people". The picked contact's phone is canonicalized and added to the
    // group.
    private static final int REQUEST_PICK_CONTACT_TO_ADD = 7301;
    /** Pick an image to use as the group icon. */
    private static final int REQUEST_PICK_GROUP_ICON = 7302;
    /**
     * Longest edge, in px, the picked group photo is scaled to, and the JPEG quality it is
     * encoded at.
     *
     * <p>Chosen against the RECEIVER's bound, not ours: an inbound icon is capped at 256 KB before
     * it crosses the Binder ({@code debug.rcs.mls_icon_max_bytes}), so anything larger is an icon
     * our own peers drop. 512 px at quality 85 lands well inside that with room to spare, and a
     * group avatar is rendered at a fraction of it.
     */
    private static final int ICON_MAX_EDGE_PX = 512;
    private static final int ICON_JPEG_QUALITY = 85;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding.getData().init(LoaderManager.getInstance(this), mBinding);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.people_and_options_fragment, container, false);
        mListView = view.findViewById(android.R.id.list);
        mPeopleListAdapter = new PeopleListAdapter(getActivity());
        mOptionsListAdapter = new OptionsListAdapter();
        mGroupActionsAdapter = new GroupActionsAdapter();
        final CompositeAdapter compositeAdapter = new CompositeAdapter(getActivity());
        compositeAdapter.addPartition(new PeopleAndOptionsPartition(mOptionsListAdapter,
                R.string.general_settings_title, false));
        // WAVE-C: GROUP member-management section, shown ONLY for an RCS group
        // (rcs_group_id != null AND group-RCS available). The adapter reports
        // getCount()==0 for everything else, so the partition (created with
        // showIfEmpty=false) is fully hidden — header included — for 1:1 and
        // MMS-group conversations, leaving their People & options unchanged.
        compositeAdapter.addPartition(new PeopleAndOptionsPartition(mGroupActionsAdapter,
                R.string.rcs_group_section_title, true, false /* showIfEmpty */));
        compositeAdapter.addPartition(new PeopleAndOptionsPartition(mPeopleListAdapter,
                R.string.participant_list_title, true));
        mListView.setAdapter(compositeAdapter);
        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBinding.unbind();
    }

    public void setConversationId(final String conversationId) {
        Assert.isTrue(getView() == null);
        Assert.notNull(conversationId);
        mBinding.bind(DataModel.get().createPeopleAndOptionsData(conversationId, getActivity(),
                this));
    }

    @Override
    public void onOptionsCursorUpdated(final PeopleAndOptionsData data, final Cursor cursor) {
        Assert.isTrue(cursor == null || cursor.getCount() == 1);
        mBinding.ensureBound(data);
        mOptionsListAdapter.swapCursor(cursor);
        // WAVE-C: read the conversation's rcs_group_id + name from the same
        // metadata cursor (the loader projects all conversation columns). These
        // gate + populate the GROUP section and the rename dialog.
        // mGroupName tracks the (possibly server-owned) conversation name for the
        // rename dialog prefill; refresh it from the metadata cursor each update.
        mGroupName = null;
        if (cursor != null && cursor.moveToFirst()) {
            final int nameIdx = cursor.getColumnIndex(
                    com.android.messaging.datamodel.DatabaseHelper
                            .ConversationColumns.NAME);
            if (nameIdx >= 0) {
                mGroupName = cursor.getString(nameIdx);
            }
        }
        // WAVE-C fix: rcs_group_id is NOT a column of conversation_list_view (the
        // table the metadata loader queries), so it can't be read from this
        // cursor. Resolve it directly off the main thread, once (immutable per
        // conversation); on result the GROUP section is revealed.
        resolveRcsGroupId();
        // NOT one-shot, unlike the group id. The group id is immutable per conversation; membership
        // is exactly the thing that changes while this screen is open, so it is re-asked on every
        // metadata update.
        resolveMembership();
        if (mPeopleListAdapter != null) {
            // Repaint so per-member "Remove from group" long-press appears/clears.
            mPeopleListAdapter.notifyDataSetChanged();
        }
    }

    private boolean mRcsGroupIdResolving;
    private boolean mMembershipResolving;

    /**
     * Re-ask the MLS transport whether we are still in this group, off the main thread.
     *
     * <p>Re-entrancy-guarded rather than one-shot: it is called from every metadata update and from
     * the participants callback, and both can arrive in a burst after a leave writes its status
     * line. The guard drops the duplicates; the next update re-asks.
     *
     * <p>Off the main thread because the answer comes from the record store, which reads
     * preferences. The adapters are repainted on the UI thread only when the answer CHANGED, so the
     * common case — a group we are still in — costs nothing visible.
     */
    private void resolveMembership() {
        if (mMembershipResolving || TextUtils.isEmpty(mRcsGroupId)) {
            return;
        }
        final String groupId = mRcsGroupId;
        mMembershipResolving = true;
        final PeopleAndOptionsData membershipData = mBinding.getData();
        final String membershipConvId =
                (membershipData == null) ? null : membershipData.getConversationId();
        new Thread(() -> {
            boolean left = false;
            try {
                final int subId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
                left = com.android.messaging.rcs.e2ee.MlsProviderTransport
                        .get(com.android.messaging.Factory.get().getApplicationContext(), subId)
                        .haveWeLeft(groupId, /*peerE164=*/ null);
            } catch (final Throwable t) {
                // Best-effort: an unanswerable question leaves the rows OFFERED. Hiding them on a
                // failure would take the Leave row away from a group the user is still in, which is
                // the worse direction — every path they open ends in a refusal, not in damage.
            }
            // AND THE TWO CASES THE MLS RECORD CANNOT SEE, named rather than pretended to
            // cover: a PLAINTEXT leave writes no MLS record, and somebody
            // else removing us writes none either. Both are recorded on the conversation itself by
            // ReceiveRcsGroupEventAction, which is where both of them arrive.
            //
            // ORed, never substituted — the two sources answer about different conversations and
            // neither is a superset. haveWeLeft speaks for MLS groups, rcs_self_left for the other
            // two routes, and a failure on either side contributes false, which keeps the rows
            // offered for the reason the catch above gives.
            if (!left) {
                try {
                    left = com.android.messaging.datamodel.BugleDatabaseOperations
                            .getConversationSelfLeft(
                                    com.android.messaging.datamodel.DataModel.get().getDatabase(),
                                    membershipConvId);
                } catch (final Throwable t) {
                    // Same rule, same direction.
                }
            }
            final boolean resolved = left;
            final android.app.Activity activity = getActivity();
            if (activity == null) {
                mMembershipResolving = false;
                return;
            }
            activity.runOnUiThread(() -> {
                mMembershipResolving = false;
                if (mWeLeftGroup == resolved) {
                    return;
                }
                mWeLeftGroup = resolved;
                if (mGroupActionsAdapter != null) {
                    mGroupActionsAdapter.notifyDataSetChanged();
                }
                if (mPeopleListAdapter != null) {
                    mPeopleListAdapter.notifyDataSetChanged();
                }
            });
        }, "rcs-people-membership").start();
    }

    /**
     * WAVE-C: reads {@code conversations.rcs_group_id} off the main thread
     * (one-shot, cached) and, on a non-empty result, reveals the GROUP section.
     * The metadata loader's {@code conversation_list_view} does not project this
     * column, so it must be read directly.
     */
    private void resolveRcsGroupId() {
        if (!TextUtils.isEmpty(mRcsGroupId) || mRcsGroupIdResolving) {
            return;
        }
        final PeopleAndOptionsData data = mBinding.getData();
        if (data == null) {
            return;
        }
        final String conversationId = data.getConversationId();
        if (TextUtils.isEmpty(conversationId)) {
            return;
        }
        mRcsGroupIdResolving = true;
        new Thread(() -> {
            String gid = null;
            try {
                gid = com.android.messaging.datamodel.BugleDatabaseOperations
                        .getConversationRcsGroupId(
                                com.android.messaging.datamodel.DataModel.get().getDatabase(),
                                conversationId);
            } catch (final Throwable t) {
                // best-effort: leave the GROUP section hidden on failure
            }
            final String resolved = gid;
            final android.app.Activity activity = getActivity();
            if (activity == null) {
                mRcsGroupIdResolving = false;
                return;
            }
            activity.runOnUiThread(() -> {
                mRcsGroupIdResolving = false;
                if (!TextUtils.isEmpty(resolved)) {
                    mRcsGroupId = resolved;
                    if (mGroupActionsAdapter != null) {
                        mGroupActionsAdapter.notifyDataSetChanged();
                    }
                    if (mPeopleListAdapter != null) {
                        mPeopleListAdapter.notifyDataSetChanged();
                    }
                }
            });
        }, "rcs-people-groupid").start();
    }

    /**
     * WAVE-C: true when this conversation is an RCS group whose member
     * management UI should be exposed: it maps to an {@code rcs_group_id} AND
     * group-RCS is available on the device. Gates the GROUP section and the
     * per-member remove affordance. 1:1 and MMS-group conversations return
     * false (their People & options is unchanged).
     */
    private boolean isManageableRcsGroup() {
        // AND WE ARE STILL IN IT. Neither half above changes when we leave, so
        // after a successful leave all three rows — Rename group, Add people, Leave group — were
        // still offered on a conversation we are no longer a member of. Leave is idempotent and so
        // looked like nothing happening; ADD and REMOVE are not, and they route through
        // changeGroupMembership, which would try to build a membership commit for a group we are
        // not in. The refusal is the far end of a path that should not have been offered.
        //
        // The MLS record covers ONE of the three ways this stops being our group. The other two
        // are closed here: mWeLeftGroup is the MLS terminal mark ORed with
        // conversations.rcs_self_left, which carries a plaintext leave and a removal by someone
        // else. See the field's javadoc.
        return !TextUtils.isEmpty(mRcsGroupId) && !mWeLeftGroup && isGroupRcsAvailable();
    }

    /**
     * Whether we have left this group, as far as the MLS record knows.
     *
     * <p>Starts {@code false} and is refreshed off the main thread beside the group id. False is the
     * right initial value in both directions: a conversation we have not left must not lose its
     * management rows for the moment before the answer arrives, and a resolve that FAILS leaves the
     * rows offered rather than hiding a group the user is still in.
     *
     * <p><b>It is TWO marks ORed, because no single one covers the three ways this stops being our
     * group.</b> {@code MlsProviderTransport.haveWeLeft} is the MLS record's terminal mark, which is
     * written only by a successful MLS leave; {@code conversations.rcs_self_left}
     * is the other two — a PLAINTEXT leave, and somebody else removing us — both recorded by
     * {@code ReceiveRcsGroupEventAction}, which is where both arrive.
     *
     * <p>The participants table cannot answer any of them: {@code removeGroupParticipants} skips the
     * self participant by design, so a self-removal is a no-op there and the SELF row survives a
     * leave in both directions.
     */
    private volatile boolean mWeLeftGroup;

    /** True when group-RCS is provisioned/available on the default SMS sub. */
    private boolean isGroupRcsAvailable() {
        final ProviderTransport transport = ProviderTransport.peekInstance();
        final RouteSelector selector =
                (transport != null) ? transport.getRouteSelector() : null;
        if (selector == null) {
            return false;
        }
        final int subId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
        return selector.isGroupRcsAvailableForSub(subId);
    }

    @Override
    public void onParticipantsListLoaded(final PeopleAndOptionsData data,
            final List<ParticipantData> participants) {
        mBinding.ensureBound(data);
        mPeopleListAdapter.updateParticipants(participants);
        mOtherParticipants = participants;
        // A leave writes a group event, which repaints this list — so this is the callback that
        // fires soonest after the departure. Re-ask here too, or the rows stay until the next
        // metadata update.
        resolveMembership();
        final ParticipantData otherParticipant = participants.size() == 1 ?
                participants.get(0) : null;
        mOptionsListAdapter.setOtherParticipant(otherParticipant);
    }

    @Override
    public void onOptionsItemViewClicked(final PeopleOptionsItemData item) {
        if (item.getItemId() == PeopleOptionsItemData.SETTING_NOTIFICATION) {
            ArrayList<String> participantsNames = new ArrayList<>();
            for (ParticipantData participant : mOtherParticipants) {
                participantsNames.add(participant.getDisplayName(true));
            }
            NotificationsUtil.createNotificationChannelGroup(getActivity(),
                    NotificationsUtil.CONVERSATION_GROUP_NAME,
                    R.string.notification_channel_messages_title);
            NotificationsUtil.createNotificationChannel(getActivity(),
                    mBinding.getData().getConversationId(),
                    String.join(", ", participantsNames),
                    NotificationManager.IMPORTANCE_HIGH,
                    NotificationsUtil.CONVERSATION_GROUP_NAME);
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            startActivity(intent);
        } else if (item.getItemId() == PeopleOptionsItemData.SETTING_RCS_READ_RECEIPTS) {
            showReadReceiptOverrideDialog();
        } else if (item.getItemId() == PeopleOptionsItemData.SETTING_BLOCKED) {
            if (item.getOtherParticipant().isBlocked()) {
                mBinding.getData().setDestinationBlocked(mBinding, false);
                return;
            }
            final Resources res = getResources();
            final Activity activity = getActivity();
            new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                    .setTitle(res.getString(R.string.block_confirmation_title,
                            item.getOtherParticipant().getDisplayDestination()))
                    .setMessage(res.getString(R.string.block_confirmation_message))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (arg0, arg1) -> {
                        mBinding.getData().setDestinationBlocked(mBinding, true);
                        activity.setResult(ConversationActivity.FINISH_RESULT_CODE);
                        activity.finish();
                    })
                    .create()
                    .show();
        }
    }

    /**
     * Per-conversation read-receipt override chooser. Tri-state
     * Default / On / Off, persisted via {@link ReadReceiptSettings}. Default
     * clears the override (falls back to the global switch). After a choice the
     * options list is repainted so the row's subtitle reflects the new state.
     */
    private void showReadReceiptOverrideDialog() {
        if (!mBinding.isBound()) {
            return;
        }
        final String conversationId = mBinding.getData().getConversationId();
        final int current = ReadReceiptSettings.getThreadOverride(conversationId);
        // Dialog row order mirrors the tri-state constants: 0=Default,1=On,2=Off.
        final CharSequence[] labels = new CharSequence[] {
                getString(R.string.rcs_read_receipts_use_default),
                getString(R.string.rcs_read_receipts_on),
                getString(R.string.rcs_read_receipts_off),
        };
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                .setTitle(R.string.rcs_read_receipts_dialog_title)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    ReadReceiptSettings.setThreadOverride(conversationId, which);
                    mOptionsListAdapter.notifyDataSetChanged();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A simple adapter that takes a conversation metadata cursor and binds
     * PeopleAndOptionsItemViews to individual COLUMNS of the first cursor record. (Note
     * that this is not a CursorAdapter because it treats individual columns of the cursor as
     * separate options to display for the conversation, e.g. notification settings).
     */
    private class OptionsListAdapter extends BaseAdapter {
        private Cursor mOptionsCursor;
        private ParticipantData mOtherParticipantData;

        public Cursor swapCursor(final Cursor newCursor) {
            final Cursor oldCursor = mOptionsCursor;
            if (newCursor != oldCursor) {
                mOptionsCursor = newCursor;
                notifyDataSetChanged();
            }
            return oldCursor;
        }

        public void setOtherParticipant(final ParticipantData participantData) {
            if (mOtherParticipantData != participantData) {
                mOtherParticipantData = participantData;
                notifyDataSetChanged();
            }
        }

        /**
         * The ordered list of setting types to display. NOTIFICATION is always
         * present; BLOCKED only for a 1:1 thread (single other participant);
         * RCS read-receipts only for a 1:1 thread when RCS is available on this
         * device (consistent with the rest of the RCS-only UI gating). The list
         * — not a fixed offset — drives both count and the position-to-type
         * mapping, so any item can be hidden independently.
         */
        private List<Integer> visibleSettings() {
            final List<Integer> settings = new ArrayList<>();
            settings.add(PeopleOptionsItemData.SETTING_NOTIFICATION);
            if (mOtherParticipantData != null) {
                settings.add(PeopleOptionsItemData.SETTING_BLOCKED);
                if (isRcsAvailable()) {
                    settings.add(PeopleOptionsItemData.SETTING_RCS_READ_RECEIPTS);
                }
            }
            return settings;
        }

        @Override
        public int getCount() {
            return mOptionsCursor == null ? 0 : visibleSettings().size();
        }

        @Override
        public Object getItem(final int position) {
            return null;
        }

        @Override
        public long getItemId(final int position) {
            return 0;
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final PeopleOptionsItemView itemView;
            if (convertView != null && convertView instanceof PeopleOptionsItemView) {
                itemView = (PeopleOptionsItemView) convertView;
            } else {
                final LayoutInflater inflater = (LayoutInflater) getActivity()
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                itemView = (PeopleOptionsItemView)
                        inflater.inflate(R.layout.people_options_item_view, parent, false);
            }
            mOptionsCursor.moveToFirst();
            final int settingType = visibleSettings().get(position);
            final String conversationId = mBinding.isBound()
                    ? mBinding.getData().getConversationId() : null;
            itemView.bind(mOptionsCursor, settingType, mOtherParticipantData,
                    conversationId, PeopleAndOptionsFragment.this);
            return itemView;
        }
    }

    /** True when RCS is provisioned/available on the default SMS subscription. */
    private boolean isRcsAvailable() {
        final ProviderTransport transport = ProviderTransport.peekInstance();
        final RouteSelector selector =
                (transport != null) ? transport.getRouteSelector() : null;
        if (selector == null) {
            return false;
        }
        final int subId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
        return selector.isRcsAvailableForSub(subId);
    }

    /**
     * An adapter that takes a list of ParticipantData and displays them as a list of
     * ParticipantListItemViews.
     */
    private class PeopleListAdapter extends ArrayAdapter<ParticipantData> {
        public PeopleListAdapter(final Context context) {
            super(context, R.layout.people_list_item_view, new ArrayList<>());
        }

        public void updateParticipants(final List<ParticipantData> newList) {
            clear();
            addAll(newList);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(final int position, final View convertView,
                            @NonNull final ViewGroup parent) {
            PersonItemView itemView;
            final ParticipantData item = getItem(position);
            if (convertView != null && convertView instanceof PersonItemView) {
                itemView = (PersonItemView) convertView;
            } else {
                final LayoutInflater inflater = (LayoutInflater) getContext()
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                itemView = (PersonItemView) inflater.inflate(R.layout.people_list_item_view, parent,
                        false);
            }
            final ParticipantListItemData itemData =
                    DataModel.get().createParticipantListItemData(item);
            itemView.bind(itemData);

            // Any click on the row should have the same effect as clicking the avatar icon
            final PersonItemView itemViewClosure = itemView;
            itemView.setListener(new PersonItemView.PersonItemViewListener() {
                @Override
                public void onPersonClicked(final PersonItemData data) {
                    itemViewClosure.performClickOnAvatar();
                }

                @Override
                public boolean onPersonLongClicked(final PersonItemData data) {
                    if (!mBinding.isBound()) {
                        return false;
                    }
                    // WAVE-C: in a manageable RCS group, a long-press on a
                    // non-self member offers a chooser with "Remove from group".
                    // For 1:1 / MMS-group / self rows, the original copy-detail
                    // dialog is unchanged.
                    if (isManageableRcsGroup() && item != null && !item.isSelf()) {
                        final CharSequence[] choices = new CharSequence[] {
                                getString(R.string.rcs_group_action_remove_member),
                        };
                        new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                                .setItems(choices, (dialog, which) -> {
                                    if (which == 0) {
                                        confirmRemoveMember(item);
                                    }
                                })
                                .show();
                        return true;
                    }
                    final CopyContactDetailDialog dialog = new CopyContactDetailDialog(
                            getContext(), data.getDetails());
                    dialog.show();
                    return true;
                }
            });
            return itemView;
        }
    }

    // =====================================================================
    // WAVE-C: GROUP member-management section (RCS groups only).
    // =====================================================================

    private static final int GROUP_ACTION_RENAME = 0;
    private static final int GROUP_ACTION_ADD_PEOPLE = 1;
    /** Leave this group. Group-only; a 1:1 has nothing to leave. */
    private static final int GROUP_ACTION_LEAVE = 2;
    /** Set the group's photo — RCC.16 §9.7.1.4. */
    private static final int GROUP_ACTION_CHANGE_ICON = 3;

    /**
     * Adapter for the GROUP section's action rows ("Rename group", "Add
     * people"). Reports {@code getCount()==0} unless this is a manageable RCS
     * group, which (with the partition's {@code showIfEmpty=false}) hides the
     * whole section — header included — for 1:1 and MMS-group conversations.
     */
    private class GroupActionsAdapter extends BaseAdapter {
        // Rename re-enabled: the provider now builds the GroupPropertiesUpdate
        // name change (TachyonRegistrar.buildGroupNameUpdate) so ChangeGroupProfile
        // actually applies the new name server-side.
        //
        // LEAVE IS LAST, AND ONLY ON A GROUP. isManageableRcsGroup() already requires an
        // rcs_group_id, which a 1:1 does not have, so the whole section — this row included — is
        // absent there. That matters for this row specifically: MlsProviderTransport.leave() refuses
        // a 1:1 outright (endMls is the verb), so offering it would be an affordance that cannot
        // work, which is exactly what had to be removed one row over.
        // CHANGE PHOTO sits with RENAME because they are the same operation on two fields — both
        // are §9.7 group metadata, both route through groupPlane, and both mirror locally on
        // acceptance because the sender cannot decrypt its own. LEAVE stays LAST.
        private final int[] mActions = { GROUP_ACTION_RENAME, GROUP_ACTION_CHANGE_ICON,
                GROUP_ACTION_ADD_PEOPLE, GROUP_ACTION_LEAVE };

        @Override
        public int getCount() {
            return isManageableRcsGroup() ? mActions.length : 0;
        }

        @Override
        public Object getItem(final int position) {
            return mActions[position];
        }

        @Override
        public long getItemId(final int position) {
            return mActions[position];
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final TextView row;
            if (convertView instanceof TextView) {
                row = (TextView) convertView;
            } else {
                row = (TextView) LayoutInflater.from(getActivity())
                        .inflate(android.R.layout.simple_list_item_1, parent, false);
            }
            final int action = mActions[position];
            final int label;
            if (action == GROUP_ACTION_RENAME) {
                label = R.string.rcs_group_action_rename;
            } else if (action == GROUP_ACTION_CHANGE_ICON) {
                label = R.string.rcs_group_action_change_icon;
            } else if (action == GROUP_ACTION_ADD_PEOPLE) {
                label = R.string.rcs_group_action_add_people;
            } else {
                label = R.string.rcs_group_action_leave;
            }
            row.setText(label);
            row.setOnClickListener(v -> {
                if (action == GROUP_ACTION_RENAME) {
                    showRenameGroupDialog();
                } else if (action == GROUP_ACTION_CHANGE_ICON) {
                    launchGroupIconPicker();
                } else if (action == GROUP_ACTION_ADD_PEOPLE) {
                    launchAddPeoplePicker();
                } else {
                    confirmLeaveGroup();
                }
            });
            return row;
        }
    }

    /**
     * WAVE-C: rename dialog. EditText prefilled with the current group name;
     * on OK we optimistically rename + drop a "You renamed ..." system line and
     * fire the (best-effort) provider rename RPC off-main. A failed result
     * toasts but leaves the local rename in place.
     */
    private void showRenameGroupDialog() {
        if (!isManageableRcsGroup()) {
            return;
        }
        final String conversationId = mBinding.getData().getConversationId();
        final String groupId = mRcsGroupId;
        final EditText editText = new EditText(getActivity());
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setSingleLine(true);
        if (!TextUtils.isEmpty(mGroupName)) {
            editText.setText(mGroupName);
            editText.setSelection(mGroupName.length());
        }
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                .setTitle(R.string.rcs_group_rename_dialog_title)
                .setMessage(R.string.rcs_group_rename_dialog_message)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String newName = editText.getText().toString().trim();
                    if (TextUtils.isEmpty(newName)) {
                        return;
                    }
                    ManageRcsGroupAction.renameGroup(conversationId, groupId, newName,
                            op -> UiUtils.showToast(R.string.rcs_group_rename_failed));
                })
                .show();
    }

    /**
     * WAVE-C: launch the system contact picker to choose a person to add. The
     * picked phone is canonicalized and added via {@link #onActivityResult}.
     * (Picks one contact per invocation — matches the single-result picker.)
     */
    private void launchAddPeoplePicker() {
        if (!isManageableRcsGroup()) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, REQUEST_PICK_CONTACT_TO_ADD);
        } catch (final android.content.ActivityNotFoundException e) {
            UiUtils.showToast(R.string.rcs_group_add_failed);
        }
    }

    /**
     * Pick an image for the group photo — RCC.16 §9.7.1.4.
     *
     * <p>{@code ACTION_GET_CONTENT} rather than the photo picker, to match {@link
     * #launchAddPeoplePicker}'s use of {@code ACTION_PICK} — one result-handling idiom in this
     * fragment rather than two.
     */
    private void launchGroupIconPicker() {
        if (!isManageableRcsGroup()) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_GROUP_ICON);
        } catch (final android.content.ActivityNotFoundException e) {
            UiUtils.showToast(R.string.rcs_group_icon_failed);
        }
    }

    /**
     * Read and SCALE the picked image, then queue the change.
     *
     * <p><b>The read happens here, on a background thread, while the Activity still holds the
     * picker's URI grant.</b> Passing the content URI into the action instead would be smaller and
     * would break: the action runs later, in a background service, by which time the grant scoped
     * to this Activity may be gone — and the failure would look like a corrupt image rather than a
     * permission that expired.
     *
     * <p><b>The SCALE is not cosmetic and the bound is not arbitrary.</b> The receive side caps an
     * inbound icon at 256 KB before it crosses the Binder ({@code debug.rcs.mls_icon_max_bytes}),
     * so an icon above that is one our own peers drop — the sender must respect the receiver's
     * bound, and ours is the only one we know. An unscaled camera photo is also several megabytes
     * travelling twice through a Binder transaction whose limit is about one. 512 px on the long
     * edge at JPEG 85 lands well inside both, and a group avatar is rendered small.
     */
    private void onGroupIconPicked(final Uri picked) {
        final String conversationId = mBinding.getData().getConversationId();
        final String groupId = mRcsGroupId;
        final android.content.ContentResolver cr = getActivity().getContentResolver();
        new Thread(() -> {
            byte[] scaled = null;
            try (java.io.InputStream in = cr.openInputStream(picked)) {
                final android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    final int longEdge = Math.max(bmp.getWidth(), bmp.getHeight());
                    final android.graphics.Bitmap out = longEdge <= ICON_MAX_EDGE_PX ? bmp
                            : android.graphics.Bitmap.createScaledBitmap(bmp,
                                    Math.max(1, bmp.getWidth() * ICON_MAX_EDGE_PX / longEdge),
                                    Math.max(1, bmp.getHeight() * ICON_MAX_EDGE_PX / longEdge),
                                    /*filter=*/ true);
                    final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, ICON_JPEG_QUALITY,
                            bos);
                    scaled = bos.toByteArray();
                }
            } catch (final Throwable t) {
                LogUtil.w(LogUtil.BUGLE_TAG, "PeopleAndOptionsFragment: could not read the picked "
                        + "group photo", t);
            }
            if (scaled == null || scaled.length == 0) {
                UiUtils.showToast(R.string.rcs_group_icon_read_failed);
                return;
            }
            LogUtil.i(LogUtil.BUGLE_TAG, "PeopleAndOptionsFragment: group photo picked → "
                    + scaled.length + "B JPEG for " + groupId);
            ManageRcsGroupAction.changeIcon(conversationId, groupId, scaled, "image/jpeg",
                    op -> UiUtils.showToast(R.string.rcs_group_icon_failed));
        }, "rcs-group-icon-scale").start();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == REQUEST_PICK_GROUP_ICON) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null
                    && isManageableRcsGroup()) {
                onGroupIconPicked(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_PICK_CONTACT_TO_ADD) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null
                    && isManageableRcsGroup()) {
                final String e164 = resolvePickedPhoneE164(data.getData());
                if (!TextUtils.isEmpty(e164)) {
                    final ArrayList<String> members = new ArrayList<>(1);
                    members.add(e164);
                    ManageRcsGroupAction.addUsers(mBinding.getData().getConversationId(),
                            mRcsGroupId, members,
                            op -> UiUtils.showToast(R.string.rcs_group_add_failed));
                } else {
                    UiUtils.showToast(R.string.rcs_group_add_failed);
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Read the picked Phone row's number and canonicalize it to E.164. */
    private String resolvePickedPhoneE164(final Uri phoneUri) {
        final ContentResolver cr = getActivity().getContentResolver();
        try (Cursor c = cr.query(phoneUri,
                new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final String raw = c.getString(0);
                if (TextUtils.isEmpty(raw)) {
                    return null;
                }
                final String canonical =
                        PhoneUtils.getDefault().getCanonicalBySimLocale(raw);
                return TextUtils.isEmpty(canonical) ? raw : canonical;
            }
        } catch (final Exception e) {
            // Fall through to null.
        }
        return null;
    }

    /**
     * WAVE-C: confirm + remove a single member from the RCS group. Optimistically
     * drops the participant + a "You removed ..." system line, then fires the
     * provider RPC off-main. A failed result toasts. Never offered for self.
     */
    private void confirmRemoveMember(final ParticipantData participant) {
        if (!isManageableRcsGroup() || participant == null || participant.isSelf()) {
            return;
        }
        final String memberE164 = participant.getNormalizedDestination();
        if (TextUtils.isEmpty(memberE164)) {
            return;
        }
        final String displayName = participant.getDisplayName(true /* preferFullName */);
        final String conversationId = mBinding.getData().getConversationId();
        final String groupId = mRcsGroupId;
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                .setTitle(getString(R.string.rcs_group_remove_confirm_title, displayName))
                .setMessage(R.string.rcs_group_remove_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rcs_group_remove_confirm_button,
                        (dialog, which) -> {
                            final ArrayList<String> members = new ArrayList<>(1);
                            members.add(memberE164);
                            ManageRcsGroupAction.removeUsers(conversationId, groupId, members,
                                    op -> UiUtils.showToast(R.string.rcs_group_remove_failed));
                        })
                .show();
    }

    /**
     * Confirm + leave the group.
     *
     * <p><b>Confirmed, unlike the other two rows, because it cannot be undone from this device.</b>
     * MLS forbids committing your own removal, so once the SelfRemove proposal is out there is
     * nothing local that puts us back — only another member adding us. The dialog says that in the
     * body rather than leaving the user to discover it.
     *
     * <p>Routing is {@link ManageRcsGroupAction#leaveGroup}, which asks
     * {@code MlsProviderTransport.leaveGroup} for the MLS/plaintext decision — the same shape as
     * Add people and Remove member. <b>Nothing optimistic is written here</b>
     * on either arm: the MLS path writes its own "You left" line, and the plaintext path writes one
     * after the RPC is accepted. A write from this fragment would be a third producer of one fact.
     */
    private void confirmLeaveGroup() {
        if (!isManageableRcsGroup()) {
            return;
        }
        final String conversationId = mBinding.getData().getConversationId();
        final String groupId = mRcsGroupId;
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                .setTitle(R.string.rcs_group_leave_confirm_title)
                .setMessage(R.string.rcs_group_leave_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rcs_group_leave_confirm_button,
                        (dialog, which) -> ManageRcsGroupAction.leaveGroup(conversationId, groupId,
                                op -> UiUtils.showToast(R.string.rcs_group_leave_failed)))
                .show();
    }

    /**
     * Represents a partition/section in the People & Options list (e.g. "general options" and
     * "people in this conversation" sections).
     */
    private class PeopleAndOptionsPartition extends CompositeAdapter.Partition {
        private final int mHeaderResId;
        private final boolean mNeedDivider;

        public PeopleAndOptionsPartition(final BaseAdapter adapter, final int headerResId,
                final boolean needDivider) {
            this(adapter, headerResId, needDivider, true /* showIfEmpty */);
        }

        public PeopleAndOptionsPartition(final BaseAdapter adapter, final int headerResId,
                final boolean needDivider, final boolean showIfEmpty) {
            super(showIfEmpty, true /* hasHeader */, adapter);
            mHeaderResId = headerResId;
            mNeedDivider = needDivider;
        }

        @Override
        public View getHeaderView(final View convertView, final ViewGroup parentView) {
            View view;
            if (convertView != null && convertView.getId() == R.id.people_and_options_header) {
                view = convertView;
            } else {
                view = LayoutInflater.from(getActivity()).inflate(
                        R.layout.people_and_options_section_header, parentView, false);
            }
            final TextView text = view.findViewById(R.id.header_text);
            final View divider = view.findViewById(R.id.divider);
            text.setText(mHeaderResId);
            divider.setVisibility(mNeedDivider ? View.VISIBLE : View.GONE);
            return view;
        }
    }
}
