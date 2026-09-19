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

package com.android.messaging.ui.conversation;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.mms.pdu.ContentType;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;

import com.android.messaging.R;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.ActionMonitor;
import com.android.messaging.datamodel.action.CreateRcsGroupAction;
import com.android.messaging.datamodel.action.GetOrCreateConversationAction;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.contact.ContactPickerFragment;
import com.android.messaging.ui.contact.ContactPickerFragment.ContactPickerFragmentHost;
import com.android.messaging.ui.conversation.ConversationActivityUiState.ConversationActivityUiStateHost;
import com.android.messaging.ui.conversation.ConversationFragment.ConversationFragmentHost;
import com.android.messaging.ui.conversationlist.ConversationListActivity;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.UiUtils;

public class ConversationActivity extends BugleActionBarActivity
        implements ContactPickerFragmentHost, ConversationFragmentHost,
        ConversationActivityUiStateHost {
    public static final int FINISH_RESULT_CODE = 1;
    private static final String SAVED_INSTANCE_STATE_UI_STATE_KEY = "uistate";

    private ConversationActivityUiState mUiState;

    // Fragment transactions cannot be performed after onSaveInstanceState() has been called since
    // it will cause state loss. We don't want to call commitAllowingStateLoss() since it's
    // dangerous. Therefore, we note when instance state is saved and avoid performing UI state
    // updates concerning fragments past that point.
    private boolean mInstanceStateSaved;

    // Tracks whether onPause is called.
    private boolean mIsPaused;

    // WAVE-A / A1: true when this new-conversation flow was launched in RCS-group
    // mode (contact picker shows the group-name field, confirm creates a group).
    private boolean mNewGroupMode;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.conversation_activity);

        final Intent intent = getIntent();

        // WAVE-A / A1: remember whether we were launched in RCS-group mode. Only
        // meaningful for a fresh (no conversationId) launch; surviving config
        // changes is fine because the contact picker keeps the user's chips.
        mNewGroupMode = intent.getBooleanExtra(
                UIIntents.UI_INTENT_EXTRA_NEW_GROUP_MODE, false);

        // Do our best to restore UI state from saved instance state.
        if (savedInstanceState != null) {
            mUiState = savedInstanceState.getParcelable(SAVED_INSTANCE_STATE_UI_STATE_KEY,
                    ConversationActivityUiState.class);
        } else {
            if (intent.
                    getBooleanExtra(UIIntents.UI_INTENT_EXTRA_GOTO_CONVERSATION_LIST, false)) {
                // See the comment in BugleWidgetService.getViewMoreConversationsView() why this
                // is unfortunately necessary. The Bugle desktop widget can display a list of
                // conversations. When there are more conversations that can be displayed in
                // the widget, the last item is a "More conversations" item. The way widgets
                // are built, the list items can only go to a single fill-in intent which points
                // to this ConversationActivity. When the user taps on "More conversations", we
                // really want to go to the ConversationList. This code makes that possible.
                finish();
                final Intent convListIntent = new Intent(this, ConversationListActivity.class);
                convListIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(convListIntent);
                return;
            }
        }

        // If saved instance state doesn't offer a clue, get the info from the intent.
        if (mUiState == null) {
            final String conversationId = intent.getStringExtra(
                    UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID);
            mUiState = new ConversationActivityUiState(conversationId);
        }
        mUiState.setHost(this);
        mInstanceStateSaved = false;

        // Don't animate UI state change for initial setup.
        updateUiState(false /* animate */);

        // See if we're getting called from a widget to directly display an image or video
        final String extraToDisplay =
                intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_URI);
        if (!TextUtils.isEmpty(extraToDisplay)) {
            final String contentType =
                    intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_ATTACHMENT_TYPE);
            final Rect bounds = UiUtils.getMeasuredBoundsOnScreen(
                    findViewById(R.id.conversation_and_compose_container));
            if (ContentType.isImageType(contentType)) {
                final Uri imagesUri = MessagingContentProvider.buildConversationImagesUri(
                        mUiState.getConversationId());
                UIIntents.get().launchFullScreenPhotoViewer(
                        this, Uri.parse(extraToDisplay), bounds, imagesUri);
            } else if (ContentType.isVideoType(contentType)) {
                UIIntents.get().launchFullScreenVideoViewer(this, Uri.parse(extraToDisplay));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        // After onSaveInstanceState() is called, future changes to mUiState won't update the UI
        // anymore, because fragment transactions are not allowed past this point.
        // For an activity recreation due to orientation change, the saved instance state keeps
        // using the in-memory copy of the UI state instead of writing it to parcel as an
        // optimization, so the UI state values may still change in response to, for example,
        // focus change from the framework, making mUiState and actual UI inconsistent.
        // Therefore, save an exact "snapshot" (clone) of the UI state object to make sure the
        // restored UI state ALWAYS matches the actual restored UI components.
        outState.putParcelable(SAVED_INSTANCE_STATE_UI_STATE_KEY, mUiState.clone());
        mInstanceStateSaved = true;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        final ConversationFragment conversationFragment = getConversationFragment();
        if (conversationFragment != null) {
            conversationFragment.onRestart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // we need to reset the mInstanceStateSaved flag since we may have just been restored from
        // a previous onStop() instead of an onDestroy().
        mInstanceStateSaved = false;
        mIsPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsPaused = true;
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        final ConversationFragment conversationFragment = getConversationFragment();
        // When the screen is turned on, the last used activity gets resumed, but it gets
        // window focus only after the lock screen is unlocked.
        if (hasFocus && conversationFragment != null) {
            conversationFragment.setConversationFocus();
        }
    }

    @Override
    public void onDisplayHeightChanged(final int heightSpecification) {
        super.onDisplayHeightChanged(heightSpecification);
        invalidateActionBar();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUiState != null) {
            mUiState.setHost(null);
        }
    }

    @Override
    public void updateActionBar(final ActionBar actionBar) {
        super.updateActionBar(actionBar);
        final ConversationFragment conversation = getConversationFragment();
        final ContactPickerFragment contactPicker = getContactPicker();
        if (contactPicker != null && mUiState.shouldShowContactPickerFragment()) {
            contactPicker.updateActionBar(actionBar);
        } else if (conversation != null && mUiState.shouldShowConversationFragment()) {
            conversation.updateActionBar(actionBar);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem menuItem) {
        if (super.onOptionsItemSelected(menuItem)) {
            return true;
        }
        if (menuItem.getItemId() == android.R.id.home) {
            onNavigationUpPressed();
            return true;
        }
        return false;
    }

    public void onNavigationUpPressed() {
        // Let the conversation fragment handle the navigation up press.
        final ConversationFragment conversationFragment = getConversationFragment();
        if (conversationFragment != null && conversationFragment.onNavigationUpPressed()) {
            return;
        }
        onFinishCurrentConversation();
    }

    @Override
    public void onBackPressed() {
        // If action mode is active dismiss it
        if (getActionMode() != null) {
            dismissActionMode();
            return;
        }

        // Let the conversation fragment handle the back press.
        final ConversationFragment conversationFragment = getConversationFragment();
        if (conversationFragment != null && conversationFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    private ContactPickerFragment getContactPicker() {
        return (ContactPickerFragment) getSupportFragmentManager().findFragmentByTag(
                ContactPickerFragment.FRAGMENT_TAG);
    }

    public ConversationFragment getConversationFragment() {
        return (ConversationFragment) getSupportFragmentManager().findFragmentByTag(
                ConversationFragment.FRAGMENT_TAG);
    }

    @Override // From ContactPickerFragmentHost
    public void onGetOrCreateNewConversation(final String conversationId) {
        Assert.isTrue(conversationId != null);
        mUiState.onGetOrCreateConversation(conversationId);
    }

    @Override // From ContactPickerFragmentHost
    public void onBackButtonPressed() {
        onBackPressed();
    }

    @Override // From ContactPickerFragmentHost
    public void onInitiateAddMoreParticipants() {
        mUiState.onAddMoreParticipants();
    }

    @Override // From ContactPickerFragmentHost
    public void onCreateRcsGroup(final String groupName,
            final ArrayList<String> recipientE164s) {
        // WAVE-A / A1: decide RCS-vs-MMS off the main thread (the provider calls
        // are blocking binder). On success the action opens the group thread;
        // on RCS-unavailable / create-failure it falls back to the existing
        // multi-recipient (group MMS) compose and toasts.
        CreateRcsGroupAction.createRcsGroup(this, groupName, recipientE164s,
                new CreateRcsGroupAction.CreateRcsGroupListener() {
                    @Override
                    public void onRcsGroupCreated(final String conversationId) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        // Replace the picker with the freshly created group thread.
                        finish();
                        UIIntents.get().launchConversationActivity(
                                ConversationActivity.this, conversationId, null);
                    }

                    @Override
                    public void onRcsGroupFallbackToMms(
                            final ArrayList<String> recipients) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        UiUtils.showToast(R.string.rcs_group_fallback_to_mms);
                        // Fall back to the unchanged group-MMS path: resolve a
                        // plain multi-recipient conversation and open it.
                        final ArrayList<ParticipantData> participants =
                                new ArrayList<>();
                        for (final String recipient : recipients) {
                            if (recipient != null && !recipient.trim().isEmpty()) {
                                participants.add(ParticipantData
                                        .getFromRawPhoneBySystemLocale(recipient.trim()));
                            }
                        }
                        GetOrCreateConversationAction.getOrCreateConversation(
                                participants, null, mGroupMmsFallbackListener);
                    }
                });
    }

    // WAVE-A / A1: opens the multi-recipient conversation when an RCS group
    // create falls back to group MMS.
    private final GetOrCreateConversationAction.GetOrCreateConversationActionListener
            mGroupMmsFallbackListener =
            new GetOrCreateConversationAction.GetOrCreateConversationActionListener() {
        @Override
        public void onGetOrCreateConversationSucceeded(final ActionMonitor monitor,
                final Object data, final String conversationId) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            mUiState.onGetOrCreateConversation(conversationId);
        }

        @Override
        public void onGetOrCreateConversationFailed(final ActionMonitor monitor,
                final Object data) {
            LogUtil.e(LogUtil.BUGLE_TAG, "onCreateRcsGroup MMS fallback failed");
        }
    };


    @Override
    public void onParticipantCountChanged(final boolean canAddMoreParticipants) {
        mUiState.onParticipantCountUpdated(canAddMoreParticipants);
    }

    @Override // From ConversationFragmentHost
    public void onStartComposeMessage() {
        mUiState.onStartMessageCompose();
    }

    @Override // From ConversationFragmentHost
    public void onConversationMetadataUpdated() {
        invalidateActionBar();
    }

    @Override // From ConversationFragmentHost
    public boolean isActiveAndFocused() {
        return !mIsPaused && hasWindowFocus();
    }

    @Override // From ConversationActivityUiStateListener
    public void onConversationContactPickerUiStateChanged(final int oldState, final int newState,
            final boolean animate) {
        Assert.isTrue(oldState != newState);
        updateUiState(animate);
    }

    private void updateUiState(final boolean animate) {
        if (mInstanceStateSaved || mIsPaused) {
            return;
        }
        Assert.notNull(mUiState);
        final Intent intent = getIntent();
        final String conversationId = mUiState.getConversationId();

        final FragmentManager fragmentManager = getSupportFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

        final boolean needConversationFragment = mUiState.shouldShowConversationFragment();
        final boolean needContactPickerFragment = mUiState.shouldShowContactPickerFragment();
        ConversationFragment conversationFragment = getConversationFragment();

        // Set up the conversation fragment.
        if (needConversationFragment) {
            Assert.notNull(conversationId);
            if (conversationFragment == null) {
                conversationFragment = new ConversationFragment();
                fragmentTransaction.add(R.id.conversation_fragment_container,
                        conversationFragment, ConversationFragment.FRAGMENT_TAG);
            }
            final MessageData draftData = intent.getParcelableExtra(
                    UIIntents.UI_INTENT_EXTRA_DRAFT_DATA, MessageData.class);
            if (!needContactPickerFragment) {
                // Once the user has committed the audience,remove the draft data from the
                // intent to prevent reuse
                intent.removeExtra(UIIntents.UI_INTENT_EXTRA_DRAFT_DATA);
            }
            conversationFragment.setHost(this);
            conversationFragment.setConversationInfo(this, conversationId, draftData);
        } else if (conversationFragment != null) {
            // Don't save draft to DB when removing conversation fragment and switching to
            // contact picking mode.  The draft is intended for the new group.
            conversationFragment.suppressWriteDraft();
            fragmentTransaction.remove(conversationFragment);
        }

        // Set up the contact picker fragment.
        ContactPickerFragment contactPickerFragment = getContactPicker();
        if (needContactPickerFragment) {
            if (contactPickerFragment == null) {
                contactPickerFragment = new ContactPickerFragment();
                fragmentTransaction.add(R.id.contact_picker_fragment_container,
                        contactPickerFragment, ContactPickerFragment.FRAGMENT_TAG);
            }
            contactPickerFragment.setHost(this);
            // WAVE-A / A1: propagate RCS-group creation mode (set from the
            // launch intent) so the picker shows the group-name field and routes
            // confirm to onCreateRcsGroup.
            contactPickerFragment.setGroupCreationMode(mNewGroupMode);
            // For a brand-new conversation's INITIAL contact pick (not
            // the long-press New-group flow, not the add-more-participants hybrid
            // flow), enable the unified deferred picker: multi-select + live RCS
            // discovery + existing-1:1 preview, RCS group when all capable else
            // group MMS. Other hosts (forward/share/widget) never reach this and
            // keep their auto-open behavior.
            contactPickerFragment.setUnifiedStartMode(!mNewGroupMode
                    && mUiState.getDesiredContactPickingMode()
                            == ContactPickerFragment.MODE_PICK_INITIAL_CONTACT);
            contactPickerFragment.setContactPickingMode(mUiState.getDesiredContactPickingMode(),
                    animate);
        } else if (contactPickerFragment != null) {
            fragmentTransaction.remove(contactPickerFragment);
        }

        fragmentTransaction.commit();
        invalidateActionBar();
    }

    @Override
    public void onFinishCurrentConversation() {
        // Simply finish the current activity. The current design is to leave any empty
        // conversations as is.
        finishAfterTransition();
    }

    @Override
    public boolean shouldResumeComposeMessage() {
        return mUiState.shouldResumeComposeMessage();
    }
}
