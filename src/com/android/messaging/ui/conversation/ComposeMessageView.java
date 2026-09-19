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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.mms.pdu.ContentType;
import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputFilter.LengthFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.binding.ImmutableBindingRef;
import com.android.messaging.datamodel.data.ConversationData;
import com.android.messaging.datamodel.data.ConversationData.ConversationDataListener;
import com.android.messaging.datamodel.data.ConversationData.SimpleConversationDataListener;
import com.android.messaging.datamodel.data.DraftMessageData;
import com.android.messaging.datamodel.data.DraftMessageData.CheckDraftForSendTask;
import com.android.messaging.datamodel.data.DraftMessageData.DraftMessageDataListener;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.PendingAttachmentData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.sms.MmsConfig;
import com.android.messaging.ui.AttachmentPreview;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.PlainTextEditText;
import com.android.messaging.ui.conversation.ConversationInputManager.ConversationInputSink;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UiUtils;
import com.android.messaging.util.UriUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This view contains the UI required to generate and send messages.
 */
public class ComposeMessageView extends LinearLayout
        implements TextView.OnEditorActionListener, DraftMessageDataListener, TextWatcher,
        ConversationInputSink {

    public interface IComposeMessageViewHost extends
            DraftMessageData.DraftMessageSubscriptionDataProvider {
        void sendMessage(MessageData message);
        void onComposeEditTextFocused();
        void onAttachmentsCleared();
        void displayPhoto(Uri photoUri, Rect imageBounds, boolean isDraft);
        void promptForSelfPhoneNumber();
        boolean isReadyForAction();
        void warnOfMissingActionConditions(final boolean sending,
                final Runnable commandToRunAfterActionConditionResolved);
        void warnOfExceedingMessageLimit(final boolean showAttachmentChooser,
                boolean tooManyVideos);
        void notifyOfAttachmentLoadFailed();
        void showAttachmentChooser();
        boolean shouldShowSubjectEditor();
        boolean shouldHideAttachmentsWhenSimSelectorShown();
        Uri getSelfSendButtonIconUri();
        int overrideCounterColor();
        int getAttachmentsClearedFlags();
    }

    public static final int CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN = 10;

    // There is no draft and there is no need for the SIM selector
    private static final int SEND_WIDGET_MODE_SELF_AVATAR = 1;
    // There is no draft but we need to show the SIM selector
    private static final int SEND_WIDGET_MODE_SIM_SELECTOR = 2;
    // There is a draft
    private static final int SEND_WIDGET_MODE_SEND_BUTTON = 3;

    private PlainTextEditText mComposeEditText;
    private PlainTextEditText mComposeSubjectText;
    private TextView mMessageBodySize;
    private TextView mMmsIndicator;
    private SimIconView mSelfSendIcon;
    private ImageButton mSendButton;
    private View mSubjectView;
    private ImageButton mDeleteSubjectButton;
    private AttachmentPreview mAttachmentPreview;
    private ImageButton mAttachMediaButton;

    private final Binding<DraftMessageData> mBinding;
    private IComposeMessageViewHost mHost;
    private final Context mOriginalContext;
    private int mSendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;

    // Off-main lookups for the per-recipient RCS capability cache + a main-thread
    // handler to repaint the send button when a lookup completes. Single thread:
    // capability priming is rare (once per opened thread) and idempotent.
    private final ExecutorService mRcsCapabilityExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // ---- W2 typing-send state ----
    // Debounce for the outbound "is composing" indicator. We send active=true on
    // the first keystroke of an idle period and active=false after the user has
    // paused for TYPING_IDLE_MS, on send, and on clear/detach. Only ever fired
    // when the 1-1 peer is known CAP_RCS (never on an SMS/MMS/group thread), so a
    // non-RCS thread behaves exactly as before.
    private static final long TYPING_IDLE_MS = 10_000L;
    // True between sending active=true and the matching active=false; gates the
    // idle/clear/send active=false so we never send a redundant idle.
    private boolean mTypingActiveSent;
    // The peer + sub we sent the current active=true to, so the matching
    // active=false (idle / send / clear) targets the same recipient even if the
    // draft text was already cleared by the time we fire it.
    @androidx.annotation.Nullable private String mTypingPeerE164;
    private int mTypingSubId = -1;
    // Posted on each keystroke; fires active=false once the user pauses.
    private final Runnable mTypingIdleRunnable = this::onTypingIdle;

    // WAVE-D: parallel outbound GROUP-typing session state. True between sending
    // active=true and active=false to a GROUP_ID; mGroupTypingGroupId is the
    // GROUP_ID the active=true went to so the matching idle targets the same
    // group even if the cached mRcsGroupId changed.
    private boolean mGroupTypingActiveSent;
    @androidx.annotation.Nullable private String mGroupTypingGroupId;
    private int mGroupTypingSubId = -1;
    private final Runnable mGroupTypingIdleRunnable = this::onTypingIdle;

    // Shared data model object binding from the conversation.
    private ImmutableBindingRef<ConversationData> mConversationDataModel;

    // WAVE-B: cached "this conversation is an established RCS group" verdict,
    // warmed off-main (a DB read for rcs_group_id + a RouteSelector cache read)
    // by maybePrimeRcsGroup. Drives the compose-bar transport label to "RCS"
    // for an RCS group, where the draft is PROTOCOL_MMS and would otherwise show
    // "MMS". Defaults false so 1-1 + plain group-MMS threads are unchanged.
    private volatile boolean mIsRcsGroup;

    // WAVE-D: cached rcs_group_id (the 32-hex GROUP_ID) for the current
    // conversation, warmed alongside mIsRcsGroup by maybePrimeRcsGroup. Used by
    // the outbound group-typing send; null/empty for 1-1 + plain group-MMS.
    @androidx.annotation.Nullable private volatile String mRcsGroupId;

    // Centrally manages all the mutual exclusive UI components accepting user input, i.e.
    // media picker, IME keyboard and SIM selector.
    private ConversationInputManager mInputManager;

    private final ConversationDataListener mDataListener = new SimpleConversationDataListener() {
        @Override
        public void onConversationMetadataUpdated(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            // Recipient/metadata can change after the initial participant load
            // (e.g. the am-start deep-link path didn't have participants yet);
            // re-warm the capability cache. Idempotent + cheap (bails when the
            // verdict is already cached or this isn't a 1-1 text thread).
            maybePrimeRcsCapability();
            // WAVE-B: rcs_group_id lives on the conversation metadata; warm the
            // RCS-group verdict so the compose label reads "RCS" not "MMS".
            maybePrimeRcsGroup();
            updateVisualsOnDraftChanged();
        }

        @Override
        public void onConversationParticipantDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            // Recipient is known now -> warm the RCS capability cache off-main so
            // the send button reflects the right transport on first paint.
            maybePrimeRcsCapability();
            maybePrimeRcsGroup();
            updateVisualsOnDraftChanged();
        }

        @Override
        public void onSubscriptionListDataLoaded(ConversationData data) {
            mConversationDataModel.ensureBound(data);
            updateOnSelfSubscriptionChange();
            // Self sub may have changed which sub the draft sends on; the cache
            // is keyed per (sub, peer), so re-warm for the new sub.
            maybePrimeRcsCapability();
            updateVisualsOnDraftChanged();
        }
    };

    public ComposeMessageView(final Context context, final AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.ColorAccentBlueOverrideStyle), attrs);
        mOriginalContext = context;
        mBinding = BindingBase.createBinding(this);
    }

    /**
     * Host calls this to bind view to DraftMessageData object
     */
    public void bind(final DraftMessageData data, final IComposeMessageViewHost host) {
        mHost = host;
        mBinding.bind(data);
        data.addListener(this);
        data.setSubscriptionDataProvider(host);

        final int counterColor = mHost.overrideCounterColor();
        if (counterColor != -1) {
            mMessageBodySize.setTextColor(counterColor);
        }
    }

    /**
     * Host calls this to unbind view
     */
    public void unbind() {
        // W2: leaving the thread ends any composing session -> emit active=false
        // (and cancel the idle timer) so the peer doesn't see a stuck "typing…".
        stopTypingIfActive();
        mBinding.unbind();
        mHost = null;
        mInputManager.onDetach();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mComposeEditText = findViewById(R.id.compose_message_text);
        mComposeEditText.setOnEditorActionListener(this);
        mComposeEditText.addTextChangedListener(this);
        mComposeEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (v == mComposeEditText && hasFocus) {
                mHost.onComposeEditTextFocused();
            }
        });
        mComposeEditText.setOnClickListener(arg0 -> {
            if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
                hideSimSelector();
            }
        });

        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxTextLimit()) });

        mSelfSendIcon = findViewById(R.id.self_send_icon);
        mSelfSendIcon.setOnClickListener(v -> {
            boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                    getSelfSubscriptionListEntry());
            hideAttachmentsWhenShowingSims(shown);
        });
        mSelfSendIcon.setOnLongClickListener(v -> {
            if (mHost.shouldShowSubjectEditor()) {
                showSubjectEditor();
            } else {
                boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                        getSelfSubscriptionListEntry());
                hideAttachmentsWhenShowingSims(shown);
            }
            return true;
        });

        mComposeSubjectText = findViewById(R.id.compose_subject_text);
        // We need the listener to change the avatar to the send button when the user starts
        // typing a subject without a message.
        mComposeSubjectText.addTextChangedListener(this);
        // onFinishInflate() is called before self is loaded from db. We set the default text
        // limit here, and apply the real limit later in updateOnSelfSubscriptionChange().
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(ParticipantData.DEFAULT_SELF_SUB_ID)
                        .getMaxSubjectLength())});

        mDeleteSubjectButton = findViewById(R.id.delete_subject_button);
        mDeleteSubjectButton.setOnClickListener(clickView -> {
            hideSubjectEditor();
            mComposeSubjectText.setText(null);
            mBinding.getData().setMessageSubject(null);
        });

        mSubjectView = findViewById(R.id.subject_view);

        mSendButton = findViewById(R.id.send_message_button);
        mSendButton.setOnClickListener(clickView ->
                sendMessageInternal(true /* checkMessageSize */));
        mSendButton.setOnLongClickListener(arg0 -> {
            boolean shown = mInputManager.toggleSimSelector(true /* animate */,
                    getSelfSubscriptionListEntry());
            hideAttachmentsWhenShowingSims(shown);
            if (mHost.shouldShowSubjectEditor()) {
                showSubjectEditor();
            }
            return true;
        });
        mSendButton.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onPopulateAccessibilityEvent(@NonNull View host,
                                                     @NonNull AccessibilityEvent event) {
                super.onPopulateAccessibilityEvent(host, event);
                // When the send button is long clicked, we want TalkBack to announce the real
                // action (select SIM or edit subject), as opposed to "long press send button."
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    event.getText().clear();
                    event.getText().add(getResources()
                            .getText(shouldShowSimSelector(mConversationDataModel.getData()) ?
                            R.string.send_button_long_click_description_with_sim_selector :
                                R.string.send_button_long_click_description_no_sim_selector));
                    // Make this an announcement so TalkBack will read our custom message.
                    event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                }
            }
        });

        mAttachMediaButton = findViewById(R.id.attach_media_button);
        mAttachMediaButton.setOnClickListener(clickView -> {
            // Showing the media picker is treated as starting to compose the message.
            mInputManager.showHideMediaPicker(true /* show */, true /* animate */);
        });

        mAttachmentPreview = findViewById(R.id.attachment_draft_view);
        mAttachmentPreview.setComposeMessageView(this);

        mMessageBodySize = findViewById(R.id.message_body_size);
        mMmsIndicator = findViewById(R.id.mms_indicator);
    }

    private void hideAttachmentsWhenShowingSims(final boolean simPickerVisible) {
        if (!mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            return;
        }
        final boolean haveAttachments = mBinding.getData().hasAttachments();
        if (simPickerVisible && haveAttachments) {
            mAttachmentPreview.hideAttachmentPreview();
        } else {
            mAttachmentPreview.onAttachmentsChanged(mBinding.getData());
        }
    }

    public void setInputManager(final ConversationInputManager inputManager) {
        mInputManager = inputManager;
    }

    public void setConversationDataModel(final ImmutableBindingRef<ConversationData> refDataModel) {
        mConversationDataModel = refDataModel;
        mConversationDataModel.getData().addConversationDataListener(mDataListener);
        // WAVE-B: reset + re-warm the RCS-group verdict for the new conversation
        // so a reused compose view never carries the prior thread's label.
        mIsRcsGroup = false;
        mRcsGroupId = null;
        maybePrimeRcsGroup();
    }

    ImmutableBindingRef<DraftMessageData> getDraftDataModel() {
        return BindingBase.createBindingReference(mBinding);
    }

    // returns true if it actually shows the subject editor and false if already showing
    private void showSubjectEditor() {
        // show the subject editor
        if (mSubjectView.getVisibility() == View.GONE) {
            mSubjectView.setVisibility(View.VISIBLE);
            mSubjectView.requestFocus();
        }
    }

    private void hideSubjectEditor() {
        mSubjectView.setVisibility(View.GONE);
        mComposeEditText.requestFocus();
    }

    /**
     * {@inheritDoc} from TextView.OnEditorActionListener
     */
    @Override // TextView.OnEditorActionListener.onEditorAction
    public boolean onEditorAction(final TextView view, final int actionId, final KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            sendMessageInternal(true /* checkMessageSize */);
            return true;
        }
        return false;
    }

    private void sendMessageInternal(final boolean checkMessageSize) {
        LogUtil.i(LogUtil.BUGLE_TAG, "UI initiated message sending in conversation " +
                mBinding.getData().getConversationId());
        // W2: a send ends the composing session -> emit active=false immediately
        // (the actual message carries its own state). No-op if we weren't typing.
        stopTypingIfActive();
        if (mBinding.getData().isCheckingDraft()) {
            // Don't send message if we are currently checking draft for sending.
            LogUtil.w(LogUtil.BUGLE_TAG, "Message can't be sent: still checking draft");
            return;
        }
        // Check the host for pre-conditions about any action.
        if (mHost.isReadyForAction() && (mHost.getConversationSelfSubId() > 0
                || PhoneUtils.getDefault().getHasPreferredSmsSim())) {
            mInputManager.showHideSimSelector(false /* show */, true /* animate */);
            final String messageToSend = mComposeEditText.getText().toString();
            mBinding.getData().setMessageText(messageToSend);
            final String subject = mComposeSubjectText.getText().toString();
            mBinding.getData().setMessageSubject(subject);
            // Asynchronously check the draft against various requirements before sending.
            mBinding.getData().checkDraftForAction(checkMessageSize,
                    mHost.getConversationSelfSubId(), (data, result) -> {
                        mBinding.ensureBound(data);
                        switch (result) {
                            case CheckDraftForSendTask.RESULT_PASSED:
                                // Continue sending after check succeeded.
                                final MessageData message = mBinding.getData()
                                        .prepareMessageForSending(mBinding);
                                if (message != null && message.hasContent()) {
                                    playSentSound();
                                    mHost.sendMessage(message);
                                    hideSubjectEditor();
                                    if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
                                        AccessibilityUtil.announceForAccessibilityCompat(
                                                ComposeMessageView.this, null,
                                                R.string.sending_message);
                                    }
                                }
                                break;

                            case CheckDraftForSendTask.RESULT_HAS_PENDING_ATTACHMENTS:
                                // Cannot send while there's still attachment(s) being loaded.
                                UiUtils.showToastAtBottom(
                                        R.string.cant_send_message_while_loading_attachments);
                                break;

                            case CheckDraftForSendTask.RESULT_NO_SELF_PHONE_NUMBER_IN_GROUP_MMS:
                                mHost.promptForSelfPhoneNumber();
                                break;

                            case CheckDraftForSendTask.RESULT_MESSAGE_OVER_LIMIT:
                                Assert.isTrue(checkMessageSize);
                                mHost.warnOfExceedingMessageLimit(
                                        true /*sending*/, false /* tooManyVideos */);
                                break;

                            case CheckDraftForSendTask.RESULT_VIDEO_ATTACHMENT_LIMIT_EXCEEDED:
                                Assert.isTrue(checkMessageSize);
                                mHost.warnOfExceedingMessageLimit(
                                        true /*sending*/, true /* tooManyVideos */);
                                break;

                            case CheckDraftForSendTask.RESULT_SIM_NOT_READY:
                                // Cannot send if there is no active subscription
                                UiUtils.showToastAtBottom(
                                        R.string.cant_send_message_without_active_subscription);
                                break;

                            default:
                                break;
                        }
                    }, mBinding);
        } else {
            mHost.warnOfMissingActionConditions(true /*sending*/, () ->
                    sendMessageInternal(checkMessageSize));
        }
    }

    public static void playSentSound() {
        // Check if this setting is enabled before playing
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final Context context = Factory.get().getApplicationContext();
        final String prefKey = context.getString(R.string.send_sound_pref_key);
        final boolean defaultValue = context.getResources().getBoolean(
                R.bool.send_sound_pref_default);
        if (!prefs.getBoolean(prefKey, defaultValue)) {
            return;
        }
        MediaUtil.get().playSound(context, R.raw.message_sent, null /* completionListener */);
    }

    /**
     * {@inheritDoc} from DraftMessageDataListener
     */
    @Override // From DraftMessageDataListener
    public void onDraftChanged(final DraftMessageData data, final int changeFlags) {
        // As this is called asynchronously when message read check bound before updating text
        mBinding.ensureBound(data);

        // We have to cache the values of the DraftMessageData because when we set
        // mComposeEditText, its onTextChanged calls updateVisualsOnDraftChanged,
        // which immediately reloads the text from the subject and message fields and replaces
        // what's in the DraftMessageData.

        final String subject = data.getMessageSubject();
        final String message = data.getMessageText();

        boolean hasAttachmentsChanged = false;

        if ((changeFlags & DraftMessageData.MESSAGE_SUBJECT_CHANGED) ==
                DraftMessageData.MESSAGE_SUBJECT_CHANGED) {
            mComposeSubjectText.setText(subject);

            // Set the cursor selection to the end since setText resets it to the start
            mComposeSubjectText.setSelection(mComposeSubjectText.getText().length());
        }

        if ((changeFlags & DraftMessageData.MESSAGE_TEXT_CHANGED) ==
                DraftMessageData.MESSAGE_TEXT_CHANGED) {
            mComposeEditText.setText(message);

            // Set the cursor selection to the end since setText resets it to the start
            mComposeEditText.setSelection(mComposeEditText.getText().length());
        }

        if ((changeFlags & DraftMessageData.ATTACHMENTS_CHANGED) ==
                DraftMessageData.ATTACHMENTS_CHANGED) {
            mAttachmentPreview.onAttachmentsChanged(data);
            hasAttachmentsChanged = true;
        }

        if ((changeFlags & DraftMessageData.SELF_CHANGED) == DraftMessageData.SELF_CHANGED) {
            updateOnSelfSubscriptionChange();
        }
        updateVisualsOnDraftChanged(hasAttachmentsChanged);
    }

    @Override   // From DraftMessageDataListener
    public void onDraftAttachmentLimitReached(final DraftMessageData data) {
        mBinding.ensureBound(data);
        mHost.warnOfExceedingMessageLimit(false /* sending */, false /* tooManyVideos */);
    }

    private void updateOnSelfSubscriptionChange() {
        // Refresh the length filters according to the selected self's MmsConfig.
        mComposeEditText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxTextLimit()) });
        mComposeSubjectText.setFilters(new InputFilter[] {
                new LengthFilter(MmsConfig.get(mBinding.getData().getSelfSubId())
                        .getMaxSubjectLength())});
    }

    @Override
    public void onMediaItemsSelected(final Collection<MessagePartData> items) {
        mBinding.getData().addAttachments(items);
        announceMediaItemState(true /*isSelected*/);
    }

    @Override
    public void onMediaItemsUnselected(final MessagePartData item) {
        mBinding.getData().removeAttachment(item);
        announceMediaItemState(false /*isSelected*/);
    }

    @Override
    public void onPendingAttachmentAdded(final PendingAttachmentData pendingItem) {
        mBinding.getData().addPendingAttachment(pendingItem, mBinding);
        resumeComposeMessage();
    }

    private void announceMediaItemState(final boolean isSelected) {
        final Resources res = getContext().getResources();
        final String announcement = isSelected ? res.getString(
                R.string.mediapicker_gallery_item_selected_content_description) :
                    res.getString(R.string.mediapicker_gallery_item_unselected_content_description);
        AccessibilityUtil.announceForAccessibilityCompat(
                this, null, announcement);
    }

    private void announceAttachmentState() {
        if (AccessibilityUtil.isTouchExplorationEnabled(getContext())) {
            int attachmentCount = mBinding.getData().getReadOnlyAttachments().size()
                    + mBinding.getData().getReadOnlyPendingAttachments().size();
            final String announcement = getContext().getResources().getQuantityString(
                    R.plurals.attachment_changed_accessibility_announcement,
                    attachmentCount, attachmentCount);
            AccessibilityUtil.announceForAccessibilityCompat(
                    this, null, announcement);
        }
    }

    @Override
    public void resumeComposeMessage() {
        mComposeEditText.requestFocus();
        mInputManager.showHideImeKeyboard(true, true);
        announceAttachmentState();
    }

    public void clearAttachments() {
        mBinding.getData().clearAttachments(mHost.getAttachmentsClearedFlags());
        mHost.onAttachmentsCleared();
    }

    public void requestDraftMessage(boolean clearLocalDraft) {
        mBinding.getData().loadFromStorage(mBinding, null, clearLocalDraft);
    }

    public void setDraftMessage(final MessageData message) {
        mBinding.getData().loadFromStorage(mBinding, message, false);
    }

    public void writeDraftMessage() {
        final String messageText = mComposeEditText.getText().toString();
        mBinding.getData().setMessageText(messageText);

        final String subject = mComposeSubjectText.getText().toString();
        mBinding.getData().setMessageSubject(subject);

        mBinding.getData().saveToStorage(mBinding);
    }

    private void updateConversationSelfId(final String selfId, final boolean notify) {
        mBinding.getData().setSelfId(selfId, notify);
    }

    private Uri getSelfSendButtonIconUri() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri != null) {
            return overridenSelfUri;
        }
        final SubscriptionListEntry subscriptionListEntry = getSelfSubscriptionListEntry();

        if (subscriptionListEntry != null) {
            return subscriptionListEntry.selectedIconUri;
        }

        // Fall back to default self-avatar in the base case.
        final ParticipantData self = mConversationDataModel.getData().getDefaultSelfParticipant();
        return self == null ? null : AvatarUriUtil.createAvatarUri(self);
    }

    private SubscriptionListEntry getSelfSubscriptionListEntry() {
        return mConversationDataModel.getData().getSubscriptionEntryForSelfParticipant(
                mBinding.getData().getSelfId(), false /* excludeDefault */);
    }

    private boolean isDataLoadedForMessageSend() {
        // Check data loading prerequisites for sending a message.
        return mConversationDataModel != null && mConversationDataModel.isBound() &&
                mConversationDataModel.getData().getParticipantsLoaded();
    }

    private static class AsyncUpdateMessageBodySizeTask {

        private final Context mContext;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final TextView mSizeTextView;

        public AsyncUpdateMessageBodySizeTask(final Context context, final TextView tv) {
            mContext = context;
            mSizeTextView = tv;
        }

        protected void execute(final List<MessagePartData> attachments) {
            mExecutor.execute(() -> {
                long totalSize = 0;
                for (final MessagePartData attachment : attachments) {
                    final Uri contentUri = attachment.getContentUri();
                    if (contentUri != null) {
                        totalSize += UriUtil.getContentSize(attachment.getContentUri());
                    }
                }

                final long size = totalSize;
                mHandler.post(() -> {
                    onPostExecute(size);
                });
            });
        }

        protected void onPostExecute(Long size) {
            if (mSizeTextView != null) {
                mSizeTextView.setText(Formatter.formatFileSize(mContext, size));
                mSizeTextView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateVisualsOnDraftChanged() {
        updateVisualsOnDraftChanged(false);
    }

    private void updateVisualsOnDraftChanged(boolean hasAttachmentsChanged) {
        final String messageText = mComposeEditText.getText().toString();
        final DraftMessageData draftMessageData = mBinding.getData();
        draftMessageData.setMessageText(messageText);

        final String subject = mComposeSubjectText.getText().toString();
        draftMessageData.setMessageSubject(subject);
        if (!TextUtils.isEmpty(subject)) {
             mSubjectView.setVisibility(View.VISIBLE);
        }

        final boolean hasMessageText = (TextUtils.getTrimmedLength(messageText) > 0);
        final boolean hasSubject = (TextUtils.getTrimmedLength(subject) > 0);
        final boolean hasWorkingDraft = hasMessageText || hasSubject ||
                mBinding.getData().hasAttachments();

        final List<MessagePartData> attachments =
                new ArrayList<>(draftMessageData.getReadOnlyAttachments());
        if (draftMessageData.getIsMms()) { // MMS case
            if (draftMessageData.hasAttachments()) {
                if (hasAttachmentsChanged) {
                    // Calculate message attachments size and show it.
                    new AsyncUpdateMessageBodySizeTask(getContext(), mMessageBodySize)
                            .execute(attachments);
                } else {
                    // No update. Just show previous size.
                    mMessageBodySize.setVisibility(View.VISIBLE);
                }
            } else {
                mMessageBodySize.setVisibility(View.INVISIBLE);
            }
        } else { // SMS case
            // Update the SMS text counter.
            final int messageCount = draftMessageData.getNumMessagesToBeSent();
            final int codePointsRemaining =
                    draftMessageData.getCodePointsRemainingInCurrentMessage();
            // Show the counter only if we are going to send more than one message OR we are getting
            // close.
            if (messageCount > 1
                    || codePointsRemaining <= CODEPOINTS_REMAINING_BEFORE_COUNTER_SHOWN) {
                // Update the remaining characters and number of messages required.
                final String counterText =
                        messageCount > 1
                                ? codePointsRemaining + " / " + messageCount
                                : String.valueOf(codePointsRemaining);
                mMessageBodySize.setText(counterText);
                mMessageBodySize.setVisibility(View.VISIBLE);
            } else {
                mMessageBodySize.setVisibility(View.INVISIBLE);
            }
        }

        // Update the send message button. Self icon uri might be null if self participant data
        // and/or conversation metadata hasn't been loaded by the host.
        final Uri selfSendButtonUri = getSelfSendButtonIconUri();
        int sendWidgetMode = SEND_WIDGET_MODE_SELF_AVATAR;
        if (selfSendButtonUri != null) {
            if (hasWorkingDraft && isDataLoadedForMessageSend()) {
                UiUtils.revealOrHideViewWithAnimation(mSendButton, VISIBLE, null);
                if (isOverriddenAvatarAGroup()) {
                    // If the host has overriden the avatar to show a group avatar where the
                    // send button sits, we have to hide the group avatar because it can be larger
                    // than the send button and pieces of the avatar will stick out from behind
                    // the send button.
                    UiUtils.revealOrHideViewWithAnimation(mSelfSendIcon, GONE, null);
                }
                if (draftMessageData.getIsMms() && mIsRcsGroup) {
                    // WAVE-B: an established RCS group sends TEXT over RCS even
                    // though the multi-recipient draft is PROTOCOL_MMS. Show the
                    // RCS affordance instead of the stale "MMS" label (the bubble
                    // already shows "· RCS"; this fixes the pre-send label).
                    mMmsIndicator.setText(R.string.rcs_compose_send_label_rcs);
                    mMmsIndicator.setVisibility(VISIBLE);
                } else if (draftMessageData.getIsMms()) {
                    // MMS draft. A 1-1 attachment-only draft is actually routed
                    // over RCS FT-HTTP (FLOW4c) when the peer is RCS-capable --
                    // reflect that instead of the stale "MMS" label. Stay
                    // conservative (explicit CAP_RCS only) so the label never
                    // claims RCS for a send that will fall back to MMS.
                    if (draftMessageData.getIsMmsDueToAttachmentOnly()
                            && currentDraftRcsCap()
                                    == org.lineageos.rcs.provider.IRcsProvider.CAP_RCS) {
                        mMmsIndicator.setText(R.string.rcs_compose_send_label_rcs);
                    } else {
                        // Google Messages MMS draft: keep the as-shipped "MMS" indicator.
                        mMmsIndicator.setText(R.string.mms_text);
                    }
                    mMmsIndicator.setVisibility(VISIBLE);
                } else {
                    // Text draft: surface a visible "RCS"/"SMS" affordance using
                    // the same indicator slot when the peer's capability is known
                    // (hidden + neutral when unknown, so SMS-only threads look
                    // exactly as today).
                    mMmsIndicator.setVisibility(
                            updateSendButtonTransportLabel(draftMessageData));
                }
                sendWidgetMode = SEND_WIDGET_MODE_SEND_BUTTON;
                // Reflect whether this draft will go over RCS or SMS on the send
                // button (accessibility label). Pure cache read; never blocks.
                updateSendButtonTransportHint(draftMessageData);
            } else {
                mSelfSendIcon.setImageResourceUri(selfSendButtonUri);
                if (isOverriddenAvatarAGroup()) {
                    UiUtils.revealOrHideViewWithAnimation(mSelfSendIcon, VISIBLE, null);
                }
                UiUtils.revealOrHideViewWithAnimation(mSendButton, GONE, null);
                // Reset any RCS/SMS affordance back to the static MMS text so a
                // later MMS draft shows the right label.
                mMmsIndicator.setText(R.string.mms_text);
                mMmsIndicator.setVisibility(INVISIBLE);
                if (shouldShowSimSelector(mConversationDataModel.getData())) {
                    sendWidgetMode = SEND_WIDGET_MODE_SIM_SELECTOR;
                }
            }
        } else {
            mSelfSendIcon.setImageResourceUri(null);
        }

        if (mSendWidgetMode != sendWidgetMode || sendWidgetMode == SEND_WIDGET_MODE_SIM_SELECTOR) {
            setSendButtonAccessibility(sendWidgetMode);
            mSendWidgetMode = sendWidgetMode;
        }

        // E2EE: show a small padlock at the start of the compose box
        // when an E2EE plane (MLS/Etouffee) is active for this conversation.
        updateE2eeComposeAffordance();

        // Update the text hint on the message box depending on the attachment type.
        final int attachmentCount = attachments.size();
        if (attachmentCount == 0) {
            final SubscriptionListEntry subscriptionListEntry =
                    mConversationDataModel.getData().getSubscriptionEntryForSelfParticipant(
                            mBinding.getData().getSelfId(), false /* excludeDefault */);
            // Layer the iPhone-style transport tag ("Text message · RCS/SMS")
            // onto the compose hint for a 1-1 text thread when the peer's
            // capability is known; null means keep the existing (multi-SIM)
            // hint below.
            final CharSequence transportHint =
                    resolveComposeTransportHint(subscriptionListEntry);
            if (transportHint != null) {
                mComposeEditText.setHint(transportHint);
            } else if (subscriptionListEntry == null) {
                mComposeEditText.setHint(R.string.compose_message_view_hint_text);
            } else {
                mComposeEditText.setHint(Html.fromHtml(getResources().getString(
                        R.string.compose_message_view_hint_text_multi_sim,
                        subscriptionListEntry.displayName), Html.FROM_HTML_MODE_LEGACY));
            }
        } else {
            int type = -1;
            for (final MessagePartData attachment : attachments) {
                int newType;
                if (attachment.isImage()) {
                    newType = ContentType.TYPE_IMAGE;
                } else if (attachment.isAudio()) {
                    newType = ContentType.TYPE_AUDIO;
                } else if (attachment.isVideo()) {
                    newType = ContentType.TYPE_VIDEO;
                } else if (attachment.isVCard()) {
                    newType = ContentType.TYPE_VCARD;
                } else {
                    newType = ContentType.TYPE_OTHER;
                }

                if (type == -1) {
                    type = newType;
                } else if (type != newType || type == ContentType.TYPE_OTHER) {
                    type = ContentType.TYPE_OTHER;
                    break;
                }
            }

            switch (type) {
                case ContentType.TYPE_IMAGE:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_photo, attachmentCount));
                    break;

                case ContentType.TYPE_AUDIO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_audio, attachmentCount));
                    break;

                case ContentType.TYPE_VIDEO:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_video, attachmentCount));
                    break;

                case ContentType.TYPE_VCARD:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_vcard, attachmentCount));
                    break;

                case ContentType.TYPE_OTHER:
                    mComposeEditText.setHint(getResources().getQuantityString(
                            R.plurals.compose_message_view_hint_text_attachments, attachmentCount));
                    break;

                default:
                    Assert.fail("Unsupported attachment type!");
                    break;
            }
        }
    }

    private void setSendButtonAccessibility(final int sendWidgetMode) {
        switch (sendWidgetMode) {
            case SEND_WIDGET_MODE_SELF_AVATAR:
                // No send button and no SIM selector; the self send button is no longer
                // important for accessibility.
                mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                mSelfSendIcon.setContentDescription(null);
                mSendButton.setVisibility(View.GONE);
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SELF_AVATAR);
                break;

            case SEND_WIDGET_MODE_SIM_SELECTOR:
                mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
                mSelfSendIcon.setContentDescription(getSimContentDescription());
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SIM_SELECTOR);
                break;

            case SEND_WIDGET_MODE_SEND_BUTTON:
                mMmsIndicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                mMmsIndicator.setContentDescription(null);
                setSendWidgetAccessibilityTraversalOrder(SEND_WIDGET_MODE_SEND_BUTTON);
                break;
        }
    }

    /**
     * Reflect the active transport (RCS vs text/SMS) on the send button's
     * accessibility label, based on the per-recipient capability the RCS
     * provider reported for the current 1-1 conversation. This is a pure cache
     * read on the main thread; the cache is populated proactively off-main when
     * the conversation opens (see {@link #maybePrimeRcsCapability}) and by the
     * send backstop. When capability is unknown -- multi-party, MMS draft,
     * provider not bound/up, or simply not looked up yet -- we leave the default
     * "Send Message" description so behavior is unchanged.
     */
    private void updateSendButtonTransportHint(final DraftMessageData draftMessageData) {
        final int cap = currentDraftRcsCap();
        int label = R.string.sendButtonContentDescription;
        if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_RCS || mIsRcsGroup) {
            // WAVE-B: an RCS group send goes over RCS (PROTOCOL_MMS draft, CAP
            // unknown for the multi-party case) -> reflect RCS on the label.
            label = R.string.sendButtonContentDescriptionRcs;
        } else if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_SMS_ONLY) {
            label = R.string.sendButtonContentDescriptionSms;
        }
        // CAP_UNKNOWN -> keep the neutral default accessibility label.
        mSendButton.setContentDescription(getResources().getString(label));
    }

    /**
     * Drive the small label under the send button (Google-Messages style "RCS"
     * / "SMS" affordance) from the current draft's transport. This reuses the
     * existing {@code mms_indicator} TextView, which only ever shows for a
     * draft-present (send-button) state and which never collides with the RCS
     * case: an MMS draft can never be RCS, so when {@code getIsMms()} is true we
     * leave the "MMS" indicator untouched and make no RCS/SMS claim. For a text
     * draft we show "RCS" or "SMS" when the peer's capability is known, and hide
     * the label (neutral) when it's unknown -- so a non-RCS thread looks exactly
     * as it does today.
     *
     * @return the visibility the caller should apply to {@code mMmsIndicator}
     *         for a text (non-MMS) draft.
     */
    private int updateSendButtonTransportLabel(final DraftMessageData draftMessageData) {
        final int cap = currentDraftRcsCap();
        if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_RCS) {
            mMmsIndicator.setText(R.string.rcs_compose_send_label_rcs);
            return VISIBLE;
        }
        if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_SMS_ONLY) {
            mMmsIndicator.setText(R.string.rcs_compose_send_label_sms);
            return VISIBLE;
        }
        // CAP_UNKNOWN: make no claim. Restore the static MMS text so the view is
        // back to its as-shipped state for any later MMS draft and hide it.
        mMmsIndicator.setText(R.string.mms_text);
        return INVISIBLE;
    }

    /**
     * Resolve the compose-box hint for a text (non-attachment) draft, layering
     * the iPhone-style transport tag ("Text message &#183; RCS" / "&#183; SMS")
     * onto the recipient when the per-recipient capability is known. Falls back
     * to the neutral "Text message" when capability is unknown (group, MMS, sub
     * not RCS, provider down, not-yet-looked-up) so behavior is unchanged there.
     * Returns {@code null} when there's a multi-SIM display name to fold in --
     * the caller keeps its existing multi-SIM hint in that case.
     */
    /**
     * E2EE: show/hide a small padlock at the start of the compose
     * box reflecting whether an E2EE plane (MLS/Etouffee) is active for this
     * conversation. Best-effort; a data hiccup just leaves the box unmarked.
     */
    private void updateE2eeComposeAffordance() {
        boolean e2ee = false;
        try {
            e2ee = mConversationDataModel != null
                    && mConversationDataModel.isBound()
                    && mConversationDataModel.getData().isE2eeEncrypted();
        } catch (final Throwable t) {
            e2ee = false;
        }
        mComposeEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                e2ee ? R.drawable.ic_e2ee_lock : 0, 0, 0, 0);
        if (e2ee) {
            mComposeEditText.setCompoundDrawablePadding(getResources()
                    .getDimensionPixelSize(R.dimen.compose_message_text_box_padding_side));
        }
    }

    @androidx.annotation.Nullable
    private CharSequence resolveComposeTransportHint(
            @androidx.annotation.Nullable final SubscriptionListEntry subEntry) {
        // Preserve the existing multi-SIM hint, which embeds the SIM name.
        if (subEntry != null) {
            return null;
        }
        // WAVE-B: an established RCS group sends over RCS even though it's a
        // multi-recipient (PROTOCOL_MMS) draft; surface the "· RCS" hint so the
        // compose bar matches the bubble. 1-1 + plain group-MMS are unaffected
        // (mIsRcsGroup is false for them).
        if (mIsRcsGroup) {
            return getResources().getText(R.string.rcs_compose_hint_rcs);
        }
        final int cap = currentDraftRcsCap();
        if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_RCS) {
            return getResources().getText(R.string.rcs_compose_hint_rcs);
        }
        if (cap == org.lineageos.rcs.provider.IRcsProvider.CAP_SMS_ONLY) {
            return getResources().getText(R.string.rcs_compose_hint_sms);
        }
        // CAP_UNKNOWN -> neutral. This matches the shipped default hint, so a
        // non-RCS thread is visually identical to today.
        return getResources().getText(R.string.rcs_compose_hint_neutral);
    }

    /**
     * The current conversation's sole other-party number canonicalized to
     * E.164 for the RCS capability cache, or {@code null} when this isn't a 1-1
     * text conversation we can route over RCS (group, no number, not loaded).
     * Matches the cache key {@code ProviderTransport} uses
     * ({@code PhoneUtils.getCanonicalBySimLocale}).
     */
    @androidx.annotation.Nullable
    private String getCanonicalRecipientForRcs() {
        if (mConversationDataModel == null || !mConversationDataModel.isBound()) {
            return null;
        }
        final ConversationData data = mConversationDataModel.getData();
        if (data == null || !data.getParticipantsLoaded()
                || data.getNumberOfParticipantsExcludingSelf() != 1) {
            return null;
        }
        final String raw = data.getParticipantPhoneNumber();
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            final String e164 = PhoneUtils.getDefault().getCanonicalBySimLocale(raw);
            return !TextUtils.isEmpty(e164) ? e164 : raw.trim();
        } catch (final Throwable t) {
            return raw.trim();
        }
    }

    /**
     * Pure main-thread cache read of the active transport for the current draft,
     * as one of {@link org.lineageos.rcs.provider.IRcsProvider#CAP_RCS},
     * {@code CAP_SMS_ONLY}, or {@code CAP_UNKNOWN}. Returns CAP_UNKNOWN (the
     * neutral "make no SMS/RCS claim" case) for an MMS draft (EXCEPT a 1-1
     * attachment-only draft, which the FT path routes over RCS), a group/no-number
     * conversation, when the sub isn't RCS-enabled, when the provider isn't
     * bound, or when the per-recipient capability simply hasn't been looked up
     * yet. Never blocks and never crosses the binder -- the cache is warmed
     * off-main by {@link #maybePrimeRcsCapability}.
     */
    private int currentDraftRcsCap() {
        try {
            final DraftMessageData draft =
                    (mBinding != null && mBinding.isBound()) ? mBinding.getData() : null;
            if (draft == null) {
                return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
            }
            // An MMS draft normally goes MMS, not RCS -- EXCEPT a 1-1
            // attachment-only draft, which InsertNewMessageAction routes over
            // RCS FT-HTTP (FLOW4c) when the peer is RCS-capable. For that case
            // fall through and resolve the real per-recipient capability (the
            // 1-1 gate is enforced by getCanonicalRecipientForRcs() returning
            // null for groups/no-number).
            if (draft.getIsMms() && !draft.getIsMmsDueToAttachmentOnly()) {
                return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
            }
            final com.android.messaging.rcs.ProviderTransport transport =
                    com.android.messaging.rcs.ProviderTransport.peekInstance();
            final String e164 = getCanonicalRecipientForRcs();
            if (transport == null || e164 == null) {
                return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
            }
            // The draft's self-sub is often DEFAULT_SELF_SUB_ID (-1); the provider
            // tracks reg/prov state under the concrete default SMS sub, so resolve.
            final int subId = com.android.messaging.util.PhoneUtils.getDefault()
                    .getEffectiveSubId(draft.getSelfSubId());
            final com.android.messaging.rcs.RouteSelector rs = transport.getRouteSelector();
            final boolean avail = rs.isRcsAvailableForSub(subId);
            final int cachedCap = rs.isPeerRcsCapable(subId, e164);
            if (!avail) {
                return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
            }
            // Defensive: only commit the compose hint to a concrete SMS/RCS verdict
            // when the per-recipient cache holds an explicit, genuinely-probed value.
            // Any other value (a non-sticky/default/seeded result that is neither
            // CAP_RCS nor CAP_SMS_ONLY) must degrade to the neutral CAP_UNKNOWN so we
            // never render "* RCS" off a verdict that wasn't truly looked up for THIS
            // recipient+sub. RouteSelector normally returns CAP_UNKNOWN on a cache
            // miss, so this only changes behavior for an unexpected leaked value.
            if (cachedCap == org.lineageos.rcs.provider.IRcsProvider.CAP_RCS
                    || cachedCap == org.lineageos.rcs.provider.IRcsProvider.CAP_SMS_ONLY) {
                return cachedCap;
            }
            return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
        } catch (final Throwable t) {
            // Never let an RCS-status read affect the compose UI.
            return org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN;
        }
    }

    /**
     * Proactively warm the per-recipient RCS capability cache for the current
     * 1-1 conversation so the very first compose reflects the right transport.
     * Off-main (the lookup may cross the binder / hit the network in the
     * provider); on completion we re-run {@link #updateVisualsOnDraftChanged} on
     * the UI thread to repaint the send button. Safe no-op when the provider
     * isn't bound or this isn't a 1-1 text conversation.
     */
    private void maybePrimeRcsCapability() {
        final com.android.messaging.rcs.ProviderTransport transport =
                com.android.messaging.rcs.ProviderTransport.peekInstance();
        if (transport == null || mBinding == null || !mBinding.isBound()) {
            return;
        }
        final int subId = com.android.messaging.util.PhoneUtils.getDefault()
                .getEffectiveSubId(mBinding.getData().getSelfSubId());
        final boolean avail = transport.getRouteSelector().isRcsAvailableForSub(subId);
        final String e164 = getCanonicalRecipientForRcs();
        final int cached = (e164 == null) ? org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN
                : transport.getRouteSelector().isPeerRcsCapable(subId, e164);
        if (!avail) {
            return;
        }
        if (e164 == null) {
            return;
        }
        // Already known -> nothing to fetch.
        if (cached != org.lineageos.rcs.provider.IRcsProvider.CAP_UNKNOWN) {
            return;
        }
        mRcsCapabilityExecutor.execute(() -> {
            // Populates the RouteSelector cache as a side effect.
            transport.lookupRcsCapability(subId, e164);
            mHandler.post(() -> {
                if (mConversationDataModel != null && mConversationDataModel.isBound()) {
                    updateVisualsOnDraftChanged();
                }
            });
        });
    }

    /**
     * WAVE-B: warm the {@link #mIsRcsGroup} verdict off-main so the compose-bar
     * transport label can show "RCS" for an established RCS group (whose draft
     * is PROTOCOL_MMS and would otherwise read "MMS"). The verdict is true only
     * when the conversation carries a non-null {@code rcs_group_id} AND group RCS
     * is available for the self sub. On completion we repaint on the UI thread.
     * Safe no-op when the provider isn't bound or this isn't a group.
     */
    private void maybePrimeRcsGroup() {
        if (!com.android.messaging.rcs.RouteSelector.GROUP_RCS_ENABLED
                || mBinding == null || !mBinding.isBound()) {
            return;
        }
        final String conversationId = (mConversationDataModel != null
                && mConversationDataModel.isBound())
                ? mConversationDataModel.getData().getConversationId() : null;
        if (conversationId == null) {
            return;
        }
        final int subId = com.android.messaging.util.PhoneUtils.getDefault()
                .getEffectiveSubId(mBinding.getData().getSelfSubId());
        mRcsCapabilityExecutor.execute(() -> {
            boolean isRcsGroup = false;
            String resolvedGroupId = null;
            try {
                final com.android.messaging.rcs.ProviderTransport transport =
                        com.android.messaging.rcs.ProviderTransport.peekInstance();
                final com.android.messaging.datamodel.DatabaseWrapper db =
                        com.android.messaging.datamodel.DataModel.get().getDatabase();
                final String groupId =
                        com.android.messaging.datamodel.BugleDatabaseOperations
                                .getConversationRcsGroupId(db, conversationId);
                isRcsGroup = !TextUtils.isEmpty(groupId)
                        && transport != null
                        && transport.getRouteSelector().isGroupRcsAvailableForSub(subId);
                if (isRcsGroup) {
                    resolvedGroupId = groupId;
                }
            } catch (final Throwable t) {
                isRcsGroup = false;
                resolvedGroupId = null;
            }
            final boolean verdict = isRcsGroup;
            final String groupIdVerdict = resolvedGroupId;
            mHandler.post(() -> {
                // WAVE-D: cache the GROUP_ID for the outbound typing send.
                mRcsGroupId = groupIdVerdict;
                if (mIsRcsGroup != verdict) {
                    mIsRcsGroup = verdict;
                    if (mConversationDataModel != null && mConversationDataModel.isBound()) {
                        updateVisualsOnDraftChanged();
                    }
                }
            });
        });
    }

    private String getSimContentDescription() {
        final SubscriptionListEntry sub = getSelfSubscriptionListEntry();
        if (sub != null) {
            return getResources().getString(
                    R.string.sim_selector_button_content_description_with_selection,
                    sub.displayName);
        } else {
            return getResources().getString(
                    R.string.sim_selector_button_content_description);
        }
    }

    // Set accessibility traversal order of the components in the send widget.
    private void setSendWidgetAccessibilityTraversalOrder(final int mode) {
        mAttachMediaButton.setAccessibilityTraversalBefore(R.id.compose_message_text);
        switch (mode) {
            case SEND_WIDGET_MODE_SIM_SELECTOR:
                mComposeEditText.setAccessibilityTraversalBefore(R.id.self_send_icon);
                break;
            case SEND_WIDGET_MODE_SEND_BUTTON:
                mComposeEditText.setAccessibilityTraversalBefore(R.id.send_message_button);
                break;
            default:
                break;
        }
    }

    @Override
    public void afterTextChanged(final Editable editable) {
    }

    @Override
    public void beforeTextChanged(final CharSequence s, final int start, final int count,
            final int after) {
        if (mHost.shouldHideAttachmentsWhenSimSelectorShown()) {
            hideSimSelector();
        }
    }

    private void hideSimSelector() {
        if (mInputManager.showHideSimSelector(false /* show */, true /* animate */)) {
            // Now that the sim selector has been hidden, reshow the attachments if they
            // have been hidden.
            hideAttachmentsWhenShowingSims(false /*simPickerVisible*/);
        }
    }

    @Override
    public void onTextChanged(final CharSequence s, final int start, final int before,
            final int count) {
        final BugleActionBarActivity activity = (mOriginalContext instanceof BugleActionBarActivity)
                ? (BugleActionBarActivity) mOriginalContext : null;
        if (activity != null && activity.getIsDestroyed()) {
            LogUtil.v(LogUtil.BUGLE_TAG, "got onTextChanged after onDestroy");

            // if we get onTextChanged after the activity is destroyed then, ah, wtf
            // b/18176615
            // This appears to have occurred as the result of orientation change.
            return;
        }

        mBinding.ensureBound();
        updateVisualsOnDraftChanged();
        // W2: drive the outbound "is composing" indicator off keystrokes. Only
        // fires on an RCS 1-1 thread; pure no-op (and no work) otherwise.
        onComposeTextChangedForTyping(s);
    }

    /**
     * Keystroke hook for the outbound typing indicator. Sends {@code active=true}
     * on the first keystroke of an idle period and (re)arms a one-shot idle timer
     * that sends {@code active=false} after {@link #TYPING_IDLE_MS} of quiet. When
     * the field becomes empty we stop immediately. Gated on the 1-1 peer being
     * {@code CAP_RCS}; on any other thread (SMS-only, group, MMS, provider down)
     * this never touches the wire, so non-RCS threads behave exactly as before.
     */
    private void onComposeTextChangedForTyping(final CharSequence s) {
        try {
            // Typing-indicator master toggle: when off, never emit
            // typing (and drop any in-flight session). Inbound display is gated
            // separately at the dispatch point.
            if (!com.android.messaging.rcs.RcsFeatureSettings.isTypingIndicatorsEnabled()) {
                stopTypingIfActive();
                return;
            }
            final boolean hasText = s != null && TextUtils.getTrimmedLength(s) > 0;
            if (!hasText) {
                // Cleared the field -> stop typing.
                stopTypingIfActive();
                return;
            }
            // WAVE-D: RCS GROUP thread -> send group typing to the GROUP_ID
            // (mirrors the 1:1 cadence below). Gated on mIsRcsGroup (rcs_group_id
            // present AND group RCS available for the sub). A non-group / 1-1
            // thread falls through to the unchanged 1:1 path below.
            final String groupId = mRcsGroupId;
            if (mIsRcsGroup && !TextUtils.isEmpty(groupId)) {
                final int gSubId = (mBinding != null && mBinding.isBound())
                        ? mBinding.getData().getSelfSubId() : mGroupTypingSubId;
                if (!mGroupTypingActiveSent) {
                    sendGroupTyping(gSubId, groupId, true /* active */);
                    mGroupTypingActiveSent = true;
                    mGroupTypingGroupId = groupId;
                    mGroupTypingSubId = gSubId;
                }
                mHandler.removeCallbacks(mGroupTypingIdleRunnable);
                mHandler.postDelayed(mGroupTypingIdleRunnable, TYPING_IDLE_MS);
                return;
            }
            if (currentDraftRcsCap() != org.lineageos.rcs.provider.IRcsProvider.CAP_RCS) {
                // Not an RCS peer: never emit typing. If we had somehow started
                // (e.g. capability flipped), make sure we don't leave it dangling.
                stopTypingIfActive();
                return;
            }
            final String peer = getCanonicalRecipientForRcs();
            if (TextUtils.isEmpty(peer)) {
                stopTypingIfActive();
                return;
            }
            final int subId = (mBinding != null && mBinding.isBound())
                    ? mBinding.getData().getSelfSubId() : mTypingSubId;
            if (!mTypingActiveSent) {
                sendTyping(subId, peer, true /* active */);
                mTypingActiveSent = true;
                mTypingPeerE164 = peer;
                mTypingSubId = subId;
            }
            // (Re)arm the idle timer on every keystroke.
            mHandler.removeCallbacks(mTypingIdleRunnable);
            mHandler.postDelayed(mTypingIdleRunnable, TYPING_IDLE_MS);
        } catch (final Throwable t) {
            // Typing is advisory; never let it disturb compose.
        }
    }

    /** Idle-timer callback: the user paused; tell the peer we stopped typing. */
    private void onTypingIdle() {
        stopTypingIfActive();
    }

    /**
     * Send {@code active=false} for the in-flight typing session (if any) and
     * clear the idle timer. Safe to call repeatedly; a no-op when we never sent
     * an {@code active=true}. Reused on idle, send, clear, and detach.
     */
    private void stopTypingIfActive() {
        // WAVE-D: stop the group-typing session too (parallel + independent of
        // the 1:1 session; only one is ever active for a given thread).
        stopGroupTypingIfActive();
        mHandler.removeCallbacks(mTypingIdleRunnable);
        if (!mTypingActiveSent) {
            return;
        }
        mTypingActiveSent = false;
        try {
            sendTyping(mTypingSubId, mTypingPeerE164, false /* active */);
        } catch (final Throwable t) {
            // Advisory.
        }
        mTypingPeerE164 = null;
    }

    /** WAVE-D: send active=false for the in-flight GROUP-typing session (if any)
     *  and clear its idle timer. Mirrors {@link #stopTypingIfActive} for groups. */
    private void stopGroupTypingIfActive() {
        mHandler.removeCallbacks(mGroupTypingIdleRunnable);
        if (!mGroupTypingActiveSent) {
            return;
        }
        mGroupTypingActiveSent = false;
        try {
            sendGroupTyping(mGroupTypingSubId, mGroupTypingGroupId, false /* active */);
        } catch (final Throwable t) {
            // Advisory.
        }
        mGroupTypingGroupId = null;
    }

    /** Off-main GROUP-typing forwarder via the provider seam (Wave D). Silent
     *  no-op when unbound or the groupId is empty. */
    private void sendGroupTyping(final int subId, final String groupId,
            final boolean active) {
        if (TextUtils.isEmpty(groupId)) {
            return;
        }
        final com.android.messaging.rcs.ProviderTransport transport =
                com.android.messaging.rcs.ProviderTransport.peekInstance();
        if (transport == null) {
            return;
        }
        transport.sendGroupTyping(subId, groupId, active);
    }

    /** Off-main typing forwarder via the provider seam. Silent no-op when unbound. */
    private void sendTyping(final int subId, final String peerE164, final boolean active) {
        if (TextUtils.isEmpty(peerE164)) {
            return;
        }
        final com.android.messaging.rcs.ProviderTransport transport =
                com.android.messaging.rcs.ProviderTransport.peekInstance();
        if (transport == null) {
            return;
        }
        transport.sendTyping(subId, peerE164, active);
    }

    @Override
    public PlainTextEditText getComposeEditText() {
        return mComposeEditText;
    }

    public void displayPhoto(final Uri photoUri, final Rect imageBounds) {
        mHost.displayPhoto(photoUri, imageBounds, true /* isDraft */);
    }

    public void updateConversationSelfIdOnExternalChange(final String selfId) {
        updateConversationSelfId(selfId, true /* notify */);
    }

    /**
     * The selfId of the conversation. As soon as the DraftMessageData successfully loads (i.e.
     * getSelfId() is non-null), the selfId in DraftMessageData is treated as the sole source
     * of truth for conversation self id since it reflects any pending self id change the user
     * makes in the UI.
     */
    public String getConversationSelfId() {
        return mBinding.getData().getSelfId();
    }

    public void selectSim(SubscriptionListEntry subscriptionData) {
        final String oldSelfId = getConversationSelfId();
        final String newSelfId = subscriptionData.selfParticipantId;
        Assert.notNull(newSelfId);
        // Don't attempt to change self if self hasn't been loaded, or if self hasn't changed.
        if (oldSelfId == null || TextUtils.equals(oldSelfId, newSelfId)) {
            return;
        }
        updateConversationSelfId(newSelfId, true /* notify */);
    }

    public void hideAllComposeInputs(final boolean animate) {
        mInputManager.hideAllInputs(animate);
    }

    public void saveInputState(final Bundle outState) {
        mInputManager.onSaveInputState(outState);
    }

    public void resetMediaPickerState() {
        mInputManager.resetMediaPickerState();
    }

    public boolean onBackPressed() {
        return mInputManager.onBackPressed();
    }

    public boolean onNavigationUpPressed() {
        return mInputManager.onNavigationUpPressed();
    }

    public boolean updateActionBar(final ActionBar actionBar) {
        return mInputManager != null ? mInputManager.updateActionBar(actionBar) : false;
    }

    public static boolean shouldShowSimSelector(final ConversationData convData) {
        return convData.getSelfParticipantsCountExcludingDefault(true /* activeOnly */) > 1;
    }

    public void sendMessageIgnoreMessageSizeLimit() {
        sendMessageInternal(false /* checkMessageSize */);
    }

    public void onAttachmentPreviewLongClicked() {
        mHost.showAttachmentChooser();
    }

    @Override
    public void onDraftAttachmentLoadFailed() {
        mHost.notifyOfAttachmentLoadFailed();
    }

    private boolean isOverriddenAvatarAGroup() {
        final Uri overridenSelfUri = mHost.getSelfSendButtonIconUri();
        if (overridenSelfUri == null) {
            return false;
        }
        return AvatarUriUtil.TYPE_GROUP_URI.equals(AvatarUriUtil.getAvatarType(overridenSelfUri));
    }

    @Override
    public void setAccessibility(boolean enabled) {
        if (enabled) {
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setSendButtonAccessibility(mSendWidgetMode);
        } else {
            mSelfSendIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mComposeEditText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mSendButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mAttachMediaButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }
}
