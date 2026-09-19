/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2026 The LineageOS Project
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

    // The RCS group this conversation maps to (null for 1:1 and MMS) and its name.
    private String mRcsGroupId;
    private String mGroupName;

    // Picks a contact to add to the group.
    private static final int REQUEST_PICK_CONTACT_TO_ADD = 7301;
    /** Picks an image for the group icon. */
    private static final int REQUEST_PICK_GROUP_ICON = 7302;
    /**
     * Longest edge in px, and JPEG quality, of the picked group icon: well inside the
     * receiver's 256 KB inbound icon cap ({@code debug.rcs.mls_icon_max_bytes}).
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
        // The group section, empty and so hidden (header included) unless this is a manageable
        // RCS group.
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
        // Refresh the group name, which prefills the rename dialog.
        mGroupName = null;
        if (cursor != null && cursor.moveToFirst()) {
            final int nameIdx = cursor.getColumnIndex(
                    com.android.messaging.datamodel.DatabaseHelper
                            .ConversationColumns.NAME);
            if (nameIdx >= 0) {
                mGroupName = cursor.getString(nameIdx);
            }
        }
        // rcs_group_id is not projected by conversation_list_view, so it is read directly.
        resolveRcsGroupId();
        // Unlike the group id, membership changes while the screen is open.
        resolveMembership();
        if (mPeopleListAdapter != null) {
            // Repaint the per-member "Remove from group" long-press.
            mPeopleListAdapter.notifyDataSetChanged();
        }
    }

    private boolean mRcsGroupIdResolving;
    private boolean mMembershipResolving;

    /**
     * Re-asks off the main thread whether we are still in this group. Guarded against re-entry,
     * as bursts of updates arrive after a leave; repaints only when the answer changes.
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
                // Leave the rows offered: a wrong offer ends in a refusal, not damage.
            }
            // ORed with rcs_self_left, which covers a plaintext leave and removal by someone
            // else; a failure on either side contributes false.
            if (!left) {
                try {
                    left = com.android.messaging.datamodel.BugleDatabaseOperations
                            .getConversationSelfLeft(
                                    com.android.messaging.datamodel.DataModel.get().getDatabase(),
                                    membershipConvId);
                } catch (final Throwable t) {
                }
            }
            boolean iconRoutes = false;
            try {
                final int subId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();
                iconRoutes = com.android.messaging.rcs.e2ee.MlsProviderTransport
                        .get(com.android.messaging.Factory.get().getApplicationContext(), subId)
                        .groupPlane(groupId, "icon offer")
                        == com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane.MLS;
            } catch (final Throwable t) {
                // Hide the row: an icon change that cannot be classified is refused.
            }
            final boolean resolved = left;
            final boolean iconResolved = iconRoutes;
            final android.app.Activity activity = getActivity();
            if (activity == null) {
                mMembershipResolving = false;
                return;
            }
            activity.runOnUiThread(() -> {
                mMembershipResolving = false;
                if (mWeLeftGroup == resolved && mIconChangeRoutes == iconResolved) {
                    return;
                }
                mWeLeftGroup = resolved;
                mIconChangeRoutes = iconResolved;
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
     * Reads {@code conversations.rcs_group_id} once off the main thread and reveals the group
     * section when it is set.
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
                // Leave the group section hidden.
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
                    resolveMembership();
                }
            });
        }, "rcs-people-groupid").start();
    }

    /**
     * True when this is an RCS group we are still in and group RCS is available; gates the
     * group section and the per-member remove.
     */
    private boolean isManageableRcsGroup() {
        return !TextUtils.isEmpty(mRcsGroupId) && !mWeLeftGroup && isGroupRcsAvailable();
    }

    /**
     * Whether we have left this group: {@code MlsProviderTransport.haveWeLeft} ORed with
     * {@code conversations.rcs_self_left}. Starts false, so the rows stay offered until the
     * answer arrives and after a failed read. See docs/rcs/groups.md.
     */
    private volatile boolean mWeLeftGroup;

    /**
     * Whether a group icon change would be carried: only an MLS group has an icon path
     * ({@code MlsMembership.iconChangeRouting}). Starts false, so the row stays hidden until the
     * answer arrives and after a failed read.
     */
    private volatile boolean mIconChangeRoutes;

    /** True when group RCS is available on the default SMS subscription. */
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
        // A leave's status line repaints this list first, so re-ask here too.
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
     * The per-conversation read-receipt override: default, on or off, stored in
     * {@link ReadReceiptSettings}; default clears the override.
     */
    private void showReadReceiptOverrideDialog() {
        if (!mBinding.isBound()) {
            return;
        }
        final String conversationId = mBinding.getData().getConversationId();
        final int current = ReadReceiptSettings.getThreadOverride(conversationId);
        // Rows in the order of the tri-state constants.
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
         * The setting types to show: notifications always, blocking for a 1:1, and read
         * receipts for a 1:1 when RCS is available. Drives both the count and the positions.
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

    /** True when RCS is available on the default SMS subscription. */
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
                    // A non-self member of a manageable RCS group offers "Remove from group".
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

    // Group management section, RCS groups only.

    private static final int GROUP_ACTION_RENAME = 0;
    private static final int GROUP_ACTION_ADD_PEOPLE = 1;
    /** Leave the group. */
    private static final int GROUP_ACTION_LEAVE = 2;
    /** Set the group icon (RCC.16 §9.7.1.4). */
    private static final int GROUP_ACTION_CHANGE_ICON = 3;

    /**
     * The group section's action rows. Empty unless this is a manageable RCS group, which hides
     * the section.
     */
    private class GroupActionsAdapter extends BaseAdapter {
        // Leave stays last.
        private final int[] mActions = { GROUP_ACTION_RENAME, GROUP_ACTION_CHANGE_ICON,
                GROUP_ACTION_ADD_PEOPLE, GROUP_ACTION_LEAVE };
        private final int[] mActionsWithoutIcon = { GROUP_ACTION_RENAME,
                GROUP_ACTION_ADD_PEOPLE, GROUP_ACTION_LEAVE };

        private int[] actions() {
            return mIconChangeRoutes ? mActions : mActionsWithoutIcon;
        }

        @Override
        public int getCount() {
            return isManageableRcsGroup() ? actions().length : 0;
        }

        @Override
        public Object getItem(final int position) {
            return actions()[position];
        }

        @Override
        public long getItemId(final int position) {
            return actions()[position];
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
            final int action = actions()[position];
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
     * Rename dialog, prefilled with the group name; the rename runs in
     * {@link ManageRcsGroupAction} and a failure toasts.
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
     * Picks one contact to add; the number is canonicalized in {@link #onActivityResult}.
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
     * Picks an image for the group icon (RCC.16 §9.7.1.4), with {@code ACTION_GET_CONTENT} to
     * match the other picker's result handling.
     */
    private void launchGroupIconPicker() {
        if (!isManageableRcsGroup() || !mIconChangeRoutes) {
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
     * Reads and scales the picked image on a background thread while this activity still holds
     * the URI grant, then queues the change. The scale keeps the icon within the receiver's cap
     * and the binder transaction limit.
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

    /** Reads the picked phone row's number in E.164. */
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
            // Null below.
        }
        return null;
    }

    /**
    /**
     * Confirms and removes a member (never self) through {@link ManageRcsGroupAction}; a
     * failure toasts.
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
     * Confirms and leaves the group through {@link ManageRcsGroupAction#leaveGroup}. Confirmed
     * because it cannot be undone from this device: only another member can add us back. Nothing
     * is written here; each route writes its own status line.
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
