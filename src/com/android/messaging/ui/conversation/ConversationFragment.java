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

package com.android.messaging.ui.conversation;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.support.v7.mms.pdu.ContentType;
import android.telephony.PhoneNumberUtils;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.emoji2.text.EmojiCompat;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.loader.app.LoaderManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.InsertNewMessageAction;
import com.android.messaging.datamodel.action.InsertRbmPostbackEchoAction;
import com.android.messaging.datamodel.action.SendRcsLocationAction;
import com.android.messaging.datamodel.action.UpdateRcsReactionAction;
import com.android.messaging.datamodel.binding.Binding;
import com.android.messaging.datamodel.binding.BindingBase;
import com.android.messaging.datamodel.binding.ImmutableBindingRef;
import com.android.messaging.datamodel.data.ConversationData;
import com.android.messaging.datamodel.data.ConversationData.ConversationDataListener;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.ConversationParticipantsData;
import com.android.messaging.datamodel.data.DraftMessageData;
import com.android.messaging.datamodel.data.DraftMessageData.DraftMessageDataListener;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.ui.AsyncImageView;
import org.lineageos.rcs.provider.RcsBotBrand;
import com.android.messaging.rcs.rbm.RbmSuggestion;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RcsFileAttachment;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.ui.AttachmentPreview;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.ui.SnackBar;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.animation.PopupTransitionAnimation;
import com.android.messaging.ui.attachmentchooser.AttachmentChooserActivity;
import com.android.messaging.ui.contact.AddContactsConfirmationDialog;
import com.android.messaging.ui.conversation.ComposeMessageView.IComposeMessageViewHost;
import com.android.messaging.ui.conversation.ConversationInputManager.ConversationInputHost;
import com.android.messaging.ui.conversation.ConversationMessageView.ConversationMessageViewHost;
import com.android.messaging.ui.mediapicker.MediaPicker;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ChangeDefaultSmsAppHelper;
import com.android.messaging.util.ImeUtil;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.TextUtil;
import com.android.messaging.util.ThreadUtil;
import com.android.messaging.util.UiUtils;
import com.android.messaging.util.UriUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a list of messages/parts comprising a conversation.
 */
public class ConversationFragment extends Fragment implements ConversationDataListener,
        IComposeMessageViewHost, ConversationMessageViewHost, ConversationInputHost,
        DraftMessageDataListener, ProviderTransport.TypingListener,
        ProviderTransport.GroupTypingListener {

    public interface ConversationFragmentHost extends ImeUtil.ImeStateHost {
        void onStartComposeMessage();
        void onConversationMetadataUpdated();
        boolean shouldResumeComposeMessage();
        void onFinishCurrentConversation();
        void invalidateActionBar();
        ActionMode startActionMode(ActionMode.Callback callback);
        void dismissActionMode();
        ActionMode getActionMode();
        boolean isActiveAndFocused();
    }

    public static final String FRAGMENT_TAG = "conversation";

    private static final int JUMP_SCROLL_THRESHOLD = 15;
    // We animate the message from draft to message list, if we the message doesn't show up in the
    // list within this time limit, then we just do a fade in animation instead
    public static final int MESSAGE_ANIMATION_MAX_WAIT = 500;

    private ComposeMessageView mComposeMessageView;
    private RecyclerView mRecyclerView;
    private ConversationMessageAdapter mAdapter;
    private ConversationFastScroller mFastScroller;

    private View mConversationComposeDivider;
    private ChangeDefaultSmsAppHelper mChangeDefaultSmsAppHelper;

    private String mConversationId;
    // If the fragment receives a draft as part of the invocation this is set
    private MessageData mIncomingDraft;

    // This binding keeps track of our associated ConversationData instance
    // A binding should have the lifetime of the owning component,
    //  don't recreate, unbind and bind if you need new data
    final Binding<ConversationData> mBinding = BindingBase.createBinding(this);

    // Saved Instance State Data - only for temporal data which is nice to maintain but not
    // critical for correctness.
    private static final String SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY = "conversationViewState";
    private Parcelable mListState;

    private ConversationFragmentHost mHost;

    // The resolved brand of a business-messaging 1:1, cached for the header; mBotBrandBotId is
    // the agent it belongs to and mBotBrandFetching guards the single in-flight resolve.
    private RcsBotBrand mBotBrand;
    private String mBotBrandBotId;
    private boolean mBotBrandFetching;

    // ConversationMessageView that is currently selected
    private ConversationMessageView mSelectedMessage;

    // Attachment data for the attachment within the selected message that was long pressed
    private MessagePartData mSelectedAttachment;

    // Normally, as soon as draft message is loaded, we trust the UI state held in
    // ComposeMessageView to be the only source of truth (incl. the conversation self id). However,
    // there can be external events that forces the UI state to change, such as SIM state changes
    // or SIM auto-switching on receiving a message. This receiver is used to receive such
    // local broadcast messages and reflect the change in the UI.
    private final BroadcastReceiver mConversationSelfIdChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String conversationId =
                    intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID);
            final String selfId =
                    intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_CONVERSATION_SELF_ID);
            Assert.notNull(conversationId);
            Assert.notNull(selfId);
            if (isBound() && TextUtils
                    .equals(mBinding.getData().getConversationId(), conversationId)) {
                mComposeMessageView.updateConversationSelfIdOnExternalChange(selfId);
            }
        }
    };

    // Inbound typing: a "<name> is typing…" row above the compose bar, fed by
    // ProviderTransport.TypingListener. Hidden on idle or when the refresh window lapses.
    @androidx.annotation.Nullable
    private TextView mTypingIndicatorView;
    // The row hosting mTypingIndicatorView, a sibling of the list, so showing it reflows the list.
    @androidx.annotation.Nullable
    private android.widget.LinearLayout mTypingIndicatorRow;
    // Shorter than the RFC 3994 refresh so a lost idle does not strand the row; re-armed on
    // every active.
    private static final long TYPING_AUTO_EXPIRE_MS = 10_000L;
    private final Runnable mHideTypingRunnable = this::hideTypingIndicator;

    // Group typing: several members may type at once, each with its own expiry, keyed by
    // fromUri, with display names cached per sender. Main thread only.
    private final java.util.LinkedHashMap<String, Runnable> mGroupTypingSenders =
            new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, String> mGroupTypingNames =
            new java.util.HashMap<>();
    // The thread's rcs_group_id, resolved once off the main thread so group typing can match it.
    @androidx.annotation.Nullable
    private volatile String mConversationRcsGroupId;

    // Flag to prevent writing draft to DB on pause
    private boolean mSuppressWriteDraft;

    // Indicates whether local draft should be cleared due to external draft changes that must
    // be reloaded from db
    private boolean mClearLocalDraft;
    private ImmutableBindingRef<DraftMessageData> mDraftMessageDataModel;

    private boolean isScrolledToBottom() {
        if (mRecyclerView.getChildCount() == 0) {
            return true;
        }
        final View lastView = mRecyclerView.getChildAt(mRecyclerView.getChildCount() - 1);
        int lastVisibleItem = ((LinearLayoutManager) mRecyclerView
                .getLayoutManager()).findLastVisibleItemPosition();
        if (lastVisibleItem < 0) {
            // If the recyclerView height is 0, then the last visible item position is -1
            // Try to compute the position of the last item, even though it's not visible
            final long id = mRecyclerView.getChildItemId(lastView);
            final RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForItemId(id);
            if (holder != null) {
                lastVisibleItem = holder.getAdapterPosition();
            }
        }
        final int totalItemCount = mRecyclerView.getAdapter().getItemCount();
        final boolean isAtBottom = (lastVisibleItem + 1 == totalItemCount);
        return isAtBottom && lastView.getBottom() <= mRecyclerView.getHeight();
    }

    private void scrollToBottom(final boolean smoothScroll) {
        if (mAdapter.getItemCount() > 0) {
            scrollToPosition(mAdapter.getItemCount() - 1, smoothScroll);
        }
    }

    private int mScrollToDismissThreshold;
    private final RecyclerView.OnScrollListener mListScrollListener =
        new RecyclerView.OnScrollListener() {
            // Keeps track of cumulative scroll delta during a scroll event, which we may use to
            // hide the media picker & co.
            private int mCumulativeScrollDelta;
            private boolean mScrollToDismissHandled;
            private boolean mWasScrolledToBottom = true;
            private int mScrollState = RecyclerView.SCROLL_STATE_IDLE;

            @Override
            public void onScrollStateChanged(@NonNull final RecyclerView view, final int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Reset scroll states.
                    mCumulativeScrollDelta = 0;
                    mScrollToDismissHandled = false;
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    mRecyclerView.getItemAnimator().endAnimations();
                }
                mScrollState = newState;
            }

            @Override
            public void onScrolled(@NonNull final RecyclerView view, final int dx, final int dy) {
                if (mScrollState == RecyclerView.SCROLL_STATE_DRAGGING &&
                        !mScrollToDismissHandled) {
                    mCumulativeScrollDelta += dy;
                    // Dismiss the keyboard only when the user scroll up (into the past).
                    if (mCumulativeScrollDelta < -mScrollToDismissThreshold) {
                        mComposeMessageView.hideAllComposeInputs(false /* animate */);
                        mScrollToDismissHandled = true;
                    }
                }
                if (mWasScrolledToBottom != isScrolledToBottom()) {
                    mConversationComposeDivider.animate().alpha(isScrolledToBottom() ? 0 : 1);
                    mWasScrolledToBottom = isScrolledToBottom();
                }
            }
    };

    private final ActionMode.Callback mMessageActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(final ActionMode actionMode, final Menu menu) {
            if (mSelectedMessage == null) {
                return false;
            }
            final ConversationMessageData data = mSelectedMessage.getData();
            final MenuInflater menuInflater = getActivity().getMenuInflater();
            menuInflater.inflate(R.menu.conversation_fragment_select_menu, menu);
            menu.findItem(R.id.action_download).setVisible(data.getShowDownloadMessage());
            // "Send" resends over the original transport, which RCS rows do not support.
            menu.findItem(R.id.action_send)
                    .setVisible(data.getShowResendMessage() && !data.getIsRcs());
            // "Send as SMS" is on every failed RCS message; the user's choice, never automatic.
            menu.findItem(R.id.action_send_as_sms)
                    .setVisible(data.getShowResendMessage() && data.getIsRcs());

            // ShareActionProvider does not work with ActionMode. So we use a normal menu item.
            menu.findItem(R.id.share_message_menu).setVisible(data.getCanForwardMessage());
            menu.findItem(R.id.save_attachment).setVisible(mSelectedAttachment != null);
            menu.findItem(R.id.forward_message_menu).setVisible(data.getCanForwardMessage());

            // TODO: We may want to support copying attachments in the future, but it's
            // unclear which attachment to pick when we make this context menu at the message level
            // instead of the part level
            menu.findItem(R.id.copy_text).setVisible(data.getCanCopyMessageToClipboard());

            return true;
        }

        @Override
        public boolean onPrepareActionMode(final ActionMode actionMode, final Menu menu) {
            return true;
        }

        @Override
        public boolean onActionItemClicked(final ActionMode actionMode, final MenuItem menuItem) {
            final ConversationMessageData data = mSelectedMessage.getData();
            final String messageId = data.getMessageId();
            int itemId = menuItem.getItemId();
            if (itemId == R.id.save_attachment) {
                final SaveAttachmentTask saveAttachmentTask = new SaveAttachmentTask(
                        getActivity());
                for (final MessagePartData part : data.getAttachments()) {
                    saveAttachmentTask.addAttachmentToSave(part.getContentUri(),
                            part.getContentType());
                }
                if (saveAttachmentTask.getAttachmentCount() > 0) {
                    saveAttachmentTask.execute();
                    mHost.dismissActionMode();
                }
                return true;
            } else if (itemId == R.id.action_delete_message) {
                if (mSelectedMessage != null) {
                    deleteMessage(messageId);
                }
                return true;
            } else if (itemId == R.id.action_download) {
                if (mSelectedMessage != null) {
                    retryDownload(messageId);
                    mHost.dismissActionMode();
                }
                return true;
            } else if (itemId == R.id.action_send) {
                if (mSelectedMessage != null) {
                    retrySend(messageId);
                    mHost.dismissActionMode();
                }
                return true;
            } else if (itemId == R.id.action_send_as_sms) {
                if (mSelectedMessage != null) {
                    resendAsSms(messageId);
                    mHost.dismissActionMode();
                }
                return true;
            } else if (itemId == R.id.copy_text) {
                Assert.isTrue(data.hasText());
                final ClipboardManager clipboard = (ClipboardManager) getActivity()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(
                        ClipData.newPlainText(null /* label */, data.getText()));
                mHost.dismissActionMode();
                return true;
            } else if (itemId == R.id.details_menu) {
                MessageDetailsDialog.show(
                        getActivity(), data, mBinding.getData().getParticipants(),
                        mBinding.getData().getSelfParticipantById(data.getSelfParticipantId()));
                mHost.dismissActionMode();
                return true;
            } else if (itemId == R.id.share_message_menu) {
                shareMessage(data);
                mHost.dismissActionMode();
                return true;
            } else if (itemId == R.id.forward_message_menu) {
                // TODO: Currently we are forwarding one part at a time, instead of
                // the entire message. Change this to forwarding the entire message when we
                // use message-based cursor in conversation.
                final MessageData message = mBinding.getData().createForwardedMessage(data);
                UIIntents.get().launchForwardMessageActivity(getActivity(), message);
                mHost.dismissActionMode();
                return true;
            }
            return false;
        }

        private void shareMessage(final ConversationMessageData data) {
            // Figure out what to share.
            MessagePartData attachmentToShare = mSelectedAttachment;
            // If the user long-pressed on the background, we will share the text (if any)
            // or the first attachment.
            if (mSelectedAttachment == null
                    && TextUtil.isAllWhitespace(data.getText())) {
                final List<MessagePartData> attachments = data.getAttachments();
                if (attachments.size() > 0) {
                    attachmentToShare = attachments.get(0);
                }
            }

            final Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            if (attachmentToShare == null) {
                shareIntent.putExtra(Intent.EXTRA_TEXT, data.getText());
                shareIntent.setType("text/plain");
            } else {
                shareIntent.putExtra(
                        Intent.EXTRA_STREAM, attachmentToShare.getContentUri());
                shareIntent.setType(attachmentToShare.getContentType());
            }
            final CharSequence title = getResources().getText(R.string.action_share);
            startActivity(Intent.createChooser(shareIntent, title));
        }

        @Override
        public void onDestroyActionMode(final ActionMode actionMode) {
            selectMessage(null);
        }
    };

    private final ActivityResultLauncher<Intent> mLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    final ConversationFragment conversationFragment = getConversationFragment();
                    if (conversationFragment != null) {
                        conversationFragment.onAttachmentChoosen();
                    } else {
                        LogUtil.e(LogUtil.BUGLE_TAG,
                                "ConversationFragment is missing after launching " +
                                        "AttachmentChooserActivity!");
                    }
                }
            });

    public ConversationFragment getConversationFragment() {
        return (ConversationFragment) getParentFragmentManager().findFragmentByTag(
                ConversationFragment.FRAGMENT_TAG);
    }

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapter = new ConversationMessageAdapter(getActivity(), null, this,
                null,
                // Sets the item click listener on the Recycler item views.
                v -> {
                    final ConversationMessageView messageView = (ConversationMessageView) v;
                    handleMessageClick(messageView);
                },
                view -> {
                    selectMessage((ConversationMessageView) view);
                    return true;
                }
        );
    }

    /**
     * setConversationInfo() may be called before or after onCreate(). When a user initiate a
     * conversation from compose, the ConversationActivity creates this fragment and calls
     * setConversationInfo(), so it happens before onCreate(). However, when the activity is
     * restored from saved instance state, the ConversationFragment is created automatically by
     * the fragment, before ConversationActivity has a chance to call setConversationInfo(). Since
     * the ability to start loading data depends on both methods being called, we need to start
     * loading when onActivityCreated() is called, which is guaranteed to happen after both.
     */
    @Override
    public void onViewCreated(@NonNull View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Delay showing the message list until the participant list is loaded.
        mRecyclerView.setVisibility(View.INVISIBLE);
        mBinding.ensureBound();
        mBinding.getData().init(LoaderManager.getInstance(this), mBinding);

        // Build the input manager with all its required dependencies and pass it along to the
        // compose message view.
        final ConversationInputManager inputManager = new ConversationInputManager(
                getActivity(), this, mComposeMessageView, mHost, getFragmentManagerToUse(),
                mBinding, mComposeMessageView.getDraftDataModel(), savedInstanceState);
        mComposeMessageView.setInputManager(inputManager);
        mComposeMessageView.setConversationDataModel(BindingBase.createBindingReference(mBinding));
        mHost.invalidateActionBar();

        mDraftMessageDataModel =
                BindingBase.createBindingReference(mComposeMessageView.getDraftDataModel());
        mDraftMessageDataModel.getData().addListener(this);
    }

    public void onAttachmentChoosen() {
        // Attachment has been choosen in the AttachmentChooserActivity, so clear local draft
        // and reload draft on resume.
        mClearLocalDraft = true;
    }

    private int getScrollToMessagePosition() {
        final Activity activity = getActivity();
        if (activity == null) {
            return -1;
        }

        final Intent intent = activity.getIntent();
        if (intent == null) {
            return -1;
        }

        return intent.getIntExtra(UIIntents.UI_INTENT_EXTRA_MESSAGE_POSITION, -1);
    }

    private void clearScrollToMessagePosition() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        final Intent intent = activity.getIntent();
        if (intent == null) {
            return;
        }
        intent.putExtra(UIIntents.UI_INTENT_EXTRA_MESSAGE_POSITION, -1);
    }

    private final Handler mHandler = new Handler();

    /**
     * {@inheritDoc} from Fragment
     */
    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
        mRecyclerView = view.findViewById(android.R.id.list);
        final LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        manager.setStackFromEnd(true);
        manager.setReverseLayout(false);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setItemAnimator(new DefaultItemAnimator() {
            private final List<ViewHolder> mAddAnimations = new ArrayList<>();
            private PopupTransitionAnimation mPopupTransitionAnimation;

            @Override
            public boolean animateAdd(final ViewHolder holder) {
                final ConversationMessageView view =
                        (ConversationMessageView) holder.itemView;
                final ConversationMessageData data = view.getData();
                endAnimation(holder);
                final long timeSinceSend = System.currentTimeMillis() - data.getReceivedTimeStamp();
                if (data.getReceivedTimeStamp() ==
                                InsertNewMessageAction.getLastSentMessageTimestamp() &&
                        !data.getIsIncoming() &&
                        timeSinceSend < MESSAGE_ANIMATION_MAX_WAIT) {
                    final ConversationMessageBubbleView messageBubble =
                            view.findViewById(R.id.message_content);
                    final Rect startRect = UiUtils.getMeasuredBoundsOnScreen(mComposeMessageView);
                    final View composeBubbleView = mComposeMessageView.findViewById(
                            R.id.compose_message_text);
                    final Rect composeBubbleRect =
                            UiUtils.getMeasuredBoundsOnScreen(composeBubbleView);
                    final AttachmentPreview attachmentView =
                            mComposeMessageView.findViewById(R.id.attachment_draft_view);
                    final Rect attachmentRect = UiUtils.getMeasuredBoundsOnScreen(attachmentView);
                    if (attachmentView.getVisibility() == View.VISIBLE) {
                        startRect.top = attachmentRect.top;
                    } else {
                        startRect.top = composeBubbleRect.top;
                    }
                    startRect.top -= view.getPaddingTop();
                    startRect.bottom =
                            composeBubbleRect.bottom;
                    startRect.left += view.getPaddingEnd();

                    view.setAlpha(0);
                    mPopupTransitionAnimation = new PopupTransitionAnimation(startRect, view);
                    mPopupTransitionAnimation.setOnStartCallback(() -> {
                        final int startWidth = composeBubbleRect.width();
                        attachmentView.onMessageAnimationStart();
                        messageBubble.kickOffMorphAnimation(startWidth,
                                messageBubble.findViewById(R.id.message_text_and_info)
                                .getMeasuredWidth());
                    });
                    mPopupTransitionAnimation.setOnStopCallback(() -> {
                        view.setAlpha(1);
                        dispatchAddFinished(holder);
                    });
                    mPopupTransitionAnimation.startAfterLayoutComplete();
                    mAddAnimations.add(holder);
                    return true;
                } else {
                    return super.animateAdd(holder);
                }
            }

            @Override
            public void endAnimation(final ViewHolder holder) {
                if (mAddAnimations.remove(holder)) {
                    holder.itemView.clearAnimation();
                }
                super.endAnimation(holder);
            }

            @Override
            public void endAnimations() {
                for (final ViewHolder holder : mAddAnimations) {
                    holder.itemView.clearAnimation();
                }
                mAddAnimations.clear();
                if (mPopupTransitionAnimation != null) {
                    mPopupTransitionAnimation.cancel();
                }
                super.endAnimations();
            }
        });
        mRecyclerView.setAdapter(mAdapter);

        if (savedInstanceState != null) {
            mListState = savedInstanceState.getParcelable(SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY,
                    Parcelable.class);
        }

        mConversationComposeDivider = view.findViewById(R.id.conversation_compose_divider);
        mScrollToDismissThreshold = ViewConfiguration.get(getActivity()).getScaledTouchSlop();
        mRecyclerView.addOnScrollListener(mListScrollListener);
        mFastScroller = ConversationFastScroller.addTo(mRecyclerView,
                UiUtils.isRtlMode() ? ConversationFastScroller.POSITION_LEFT_SIDE :
                    ConversationFastScroller.POSITION_RIGHT_SIDE);

        mComposeMessageView = view.findViewById(R.id.message_compose_view_container);
        // Bind the compose message view to the DraftMessageData
        mComposeMessageView.bind(DataModel.get().createDraftMessageData(
                mBinding.getData().getConversationId()), this);

        return view;
    }

    private void scrollToPosition(final int targetPosition, final boolean smoothScroll) {
        if (smoothScroll) {
            final int maxScrollDelta = JUMP_SCROLL_THRESHOLD;

            final LinearLayoutManager layoutManager =
                    (LinearLayoutManager) mRecyclerView.getLayoutManager();
            final int firstVisibleItemPosition =
                    layoutManager.findFirstVisibleItemPosition();
            final int delta = targetPosition - firstVisibleItemPosition;
            final int intermediatePosition;

            if (delta > maxScrollDelta) {
                intermediatePosition = Math.max(0, targetPosition - maxScrollDelta);
            } else if (delta < -maxScrollDelta) {
                final int count = layoutManager.getItemCount();
                intermediatePosition = Math.min(count - 1, targetPosition + maxScrollDelta);
            } else {
                intermediatePosition = -1;
            }
            if (intermediatePosition != -1) {
                mRecyclerView.scrollToPosition(intermediatePosition);
            }
            mRecyclerView.smoothScrollToPosition(targetPosition);
        } else {
            mRecyclerView.scrollToPosition(targetPosition);
        }
    }

    private int getScrollPositionFromBottom() {
        final LinearLayoutManager layoutManager =
                (LinearLayoutManager) mRecyclerView.getLayoutManager();
        final int lastVisibleItem =
                layoutManager.findLastVisibleItemPosition();
        return Math.max(mAdapter.getItemCount() - 1 - lastVisibleItem, 0);
    }

    /**
     * Display a photo using the Photoviewer component.
     */
    @Override
    public void displayPhoto(final Uri photoUri, final Rect imageBounds, final boolean isDraft) {
        displayPhoto(photoUri, imageBounds, isDraft, mConversationId, getActivity());
    }

    public static void displayPhoto(final Uri photoUri, final Rect imageBounds,
            final boolean isDraft, final String conversationId, final Activity activity) {
        final Uri imagesUri =
                isDraft ? MessagingContentProvider.buildDraftImagesUri(conversationId)
                        : MessagingContentProvider.buildConversationImagesUri(conversationId);
        UIIntents.get().launchFullScreenPhotoViewer(
                activity, photoUri, imageBounds, imagesUri);
    }

    private void selectMessage(final ConversationMessageView messageView) {
        selectMessage(messageView, null /* attachment */);
    }

    private void selectMessage(final ConversationMessageView messageView,
            final MessagePartData attachment) {
        mSelectedMessage = messageView;
        if (mSelectedMessage == null) {
            mAdapter.setSelectedMessage(null);
            mHost.dismissActionMode();
            mSelectedAttachment = null;
            return;
        }
        mSelectedAttachment = attachment;
        mAdapter.setSelectedMessage(messageView.getData().getMessageId());
        mHost.startActionMode(mMessageActionModeCallback);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mListState != null) {
            outState.putParcelable(SAVED_INSTANCE_STATE_LIST_VIEW_STATE_KEY, mListState);
        }
        mComposeMessageView.saveInputState(outState);
    }

    public void onRestart() {
        mBinding.getData().restart(mBinding);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mIncomingDraft == null) {
            mComposeMessageView.requestDraftMessage(mClearLocalDraft);
        } else {
            mComposeMessageView.setDraftMessage(mIncomingDraft);
            mIncomingDraft = null;
        }
        mClearLocalDraft = false;

        // On resume, check if there's a pending request for resuming message compose. This
        // may happen when the user commits the contact selection for a group conversation and
        // goes from compose back to the conversation fragment.
        if (mHost.shouldResumeComposeMessage()) {
            mComposeMessageView.resumeComposeMessage();
        }

        setConversationFocus();

        // On resume, invalidate all message views to show the updated timestamp.
        mAdapter.notifyDataSetChanged();

        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(
                mConversationSelfIdChangeReceiver,
                new IntentFilter(UIIntents.CONVERSATION_SELF_ID_CHANGE_BROADCAST_ACTION));

        // Inbound typing while the thread is visible; nothing without a provider.
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport != null) {
            transport.registerTypingListener(this);
            transport.registerGroupTypingListener(this);
        }
    }

    void setConversationFocus() {
        if (mHost.isActiveAndFocused()) {
            mBinding.getData().setFocus();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (mHost.getActionMode() != null) {
            return;
        }

        inflater.inflate(R.menu.conversation_menu, menu);

        final ConversationData data = mBinding.getData();

        // Disable the "people & options" item if we haven't loaded participants yet.
        menu.findItem(R.id.action_people_and_options).setEnabled(data.getParticipantsLoaded());

        // See if we can show add contact action.
        final ParticipantData participant = data.getOtherParticipant();
        final boolean addContactActionVisible = (participant != null
                && TextUtils.isEmpty(participant.getLookupKey()));
        menu.findItem(R.id.action_add_contact).setVisible(addContactActionVisible);

        // See if we should show archive or unarchive.
        final boolean isArchived = data.getIsArchived();
        menu.findItem(R.id.action_archive).setVisible(!isArchived);
        menu.findItem(R.id.action_unarchive).setVisible(isArchived);

        // Conditionally enable the phone call button.
        final boolean supportCallAction = (PhoneUtils.getDefault().isVoiceCapable() &&
                data.getParticipantPhoneNumber() != null);
        menu.findItem(R.id.action_call).setVisible(supportCallAction);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_people_and_options) {
            Assert.isTrue(mBinding.getData().getParticipantsLoaded());
            UIIntents.get().launchPeopleAndOptionsActivity(getActivity(), mConversationId);
            return true;
        } else if (itemId == R.id.action_call) {
            final String phoneNumber = mBinding.getData().getParticipantPhoneNumber();
            Assert.notNull(phoneNumber);
            // Can't make a call to emergency numbers using ACTION_CALL.
            if (PhoneNumberUtils.isEmergencyNumber(phoneNumber)) {
                UiUtils.showToast(R.string.disallow_emergency_call);
            } else {
                final View targetView = getActivity().findViewById(R.id.action_call);
                Point centerPoint;
                if (targetView != null) {
                    final int[] screenLocation = new int[2];
                    targetView.getLocationOnScreen(screenLocation);
                    final int centerX = screenLocation[0] + targetView.getWidth() / 2;
                    final int centerY = screenLocation[1] + targetView.getHeight() / 2;
                    centerPoint = new Point(centerX, centerY);
                } else {
                    // In the overflow menu, just use the center of the screen.
                    final Display display =
                            getActivity().getWindowManager().getDefaultDisplay();
                    centerPoint = new Point(display.getWidth() / 2, display.getHeight() / 2);
                }
                UIIntents.get()
                        .launchPhoneCallActivity(getActivity(), phoneNumber, centerPoint);
            }
            return true;
        } else if (itemId == R.id.action_share_location) {
            shareLocation();
            return true;
        } else if (itemId == R.id.action_archive) {
            mBinding.getData().archiveConversation(mBinding);
            closeConversation(mConversationId);
            return true;
        } else if (itemId == R.id.action_unarchive) {
            mBinding.getData().unarchiveConversation(mBinding);
            return true;
        } else if (itemId == R.id.action_settings) {
            return true;
        } else if (itemId == R.id.action_add_contact) {
            final ParticipantData participant = mBinding.getData().getOtherParticipant();
            Assert.notNull(participant);
            final String destination = participant.getNormalizedDestination();
            final Uri avatarUri = AvatarUriUtil.createAvatarUri(participant);
            (new AddContactsConfirmationDialog(getActivity(), avatarUri, destination)).show();
            return true;
        } else if (itemId == R.id.action_delete) {
            if (isReadyForDeleteAction()) {
                new AlertDialog.Builder(getActivity(), R.style.AlertDialogTheme)
                        .setTitle(getResources().getQuantityString(
                                R.plurals.delete_conversations_confirmation_dialog_title, 1))
                        .setPositiveButton(R.string.delete_conversation_confirmation_button,
                                (dialog, button) -> deleteConversation())
                        .setNegativeButton(R.string.delete_conversation_decline_button, null)
                        .show();
            } else {
                warnOfMissingActionConditions(false /*sending*/,
                        null /*commandToRunAfterActionConditionResolved*/);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final int REQ_SHARE_LOCATION_PERMISSION = 4521;

    /**
     * Shares the current location over RCS, after the location permission;
     * {@link SendRcsLocationAction} sends and writes the row.
     */
    private void shareLocation() {
        if (!com.android.messaging.util.OsUtil.hasLocationPermission()) {
            requestPermissions(
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_SHARE_LOCATION_PERMISSION);
            return;
        }
        doShareLocation();
    }

    private void doShareLocation() {
        if (mConversationId == null || getActivity() == null) {
            return;
        }
        final android.location.LocationManager lm =
                (android.location.LocationManager) getActivity()
                        .getSystemService(android.content.Context.LOCATION_SERVICE);
        android.location.Location best = null;
        if (lm != null) {
            for (final String prov : new String[]{
                    android.location.LocationManager.FUSED_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER}) {
                try {
                    final android.location.Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (final SecurityException | IllegalArgumentException ignore) {
                    // Provider unavailable or permission race.
                }
            }
        }
        if (best == null) {
            UiUtils.showToast(R.string.rcs_location_unavailable);
            return;
        }
        SendRcsLocationAction.shareLocation(mConversationId, best.getLatitude(),
                best.getLongitude(), best.hasAccuracy() ? best.getAccuracy() : 0f);
        UiUtils.showToast(R.string.rcs_location_sharing);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
            @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        if (requestCode == REQ_SHARE_LOCATION_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                doShareLocation();
            } else {
                UiUtils.showToast(R.string.rcs_location_permission_needed);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * {@inheritDoc} from ConversationDataListener
     */
    @Override
    public void onConversationMessagesCursorUpdated(final ConversationData data,
            final Cursor cursor, final ConversationMessageData newestMessage,
            final boolean isSync) {
        mBinding.ensureBound(data);

        // This needs to be determined before swapping cursor, which may change the scroll state.
        final boolean scrolledToBottom = isScrolledToBottom();
        final int positionFromBottom = getScrollPositionFromBottom();

        // If participants not loaded, assume 1:1 since that's the 99% case
        final boolean oneOnOne =
                !data.getParticipantsLoaded() || data.getOtherParticipant() != null;
        mAdapter.setOneOnOne(oneOnOne, false /* invalidate */);

        // Ensure that the action bar is updated with the current data.
        invalidateOptionsMenu();
        final Cursor oldCursor = mAdapter.swapCursor(cursor);

        if (cursor != null && oldCursor == null) {
            if (mListState != null) {
                mRecyclerView.getLayoutManager().onRestoreInstanceState(mListState);
                // RecyclerView restores scroll states without triggering scroll change events, so
                // we need to manually ensure that they are correctly handled.
                mListScrollListener.onScrolled(mRecyclerView, 0, 0);
            }
        }

        if (isSync) {
            // This is a message sync. Syncing messages changes cursor item count, which would
            // implicitly change RV's scroll position. We'd like the RV to keep scrolled to the same
            // relative position from the bottom (because RV is stacked from bottom), so that it
            // stays relatively put as we sync.
            final int position = Math.max(mAdapter.getItemCount() - 1 - positionFromBottom, 0);
            scrollToPosition(position, false /* smoothScroll */);
        } else if (newestMessage != null) {
            // Show a snack bar notification if we are not scrolled to the bottom and the new
            // message is an incoming message.
            if (!scrolledToBottom && newestMessage.getIsIncoming()) {
                // If the conversation activity is started but not resumed (if another dialog
                // activity was in the foregrond), we will show a system notification instead of
                // the snack bar.
                if (mBinding.getData().isFocused()) {
                    UiUtils.showSnackBarWithCustomAction(getActivity(),
                            getView().getRootView(),
                            getString(R.string.in_conversation_notify_new_message_text),
                            SnackBar.Action.createCustomAction(() -> {
                                scrollToBottom(true /* smoothScroll */);
                                mComposeMessageView.hideAllComposeInputs(false /* animate */);
                            },
                            getString(R.string.in_conversation_notify_new_message_action)),
                            null /* interactions */,
                            SnackBar.Placement.above(mComposeMessageView));
                }
            } else {
                // We are either already scrolled to the bottom or this is an outgoing message,
                // scroll to the bottom to reveal it.
                // Don't smooth scroll if we were already at the bottom; instead, we scroll
                // immediately so RecyclerView's view animation will take place.
                scrollToBottom(!scrolledToBottom);
            }
        }

        if (cursor != null) {
            // Are we coming from a widget click where we're told to scroll to a particular item?
            final int scrollToPos = getScrollToMessagePosition();
            if (scrollToPos >= 0) {
                LogUtil.v(LogUtil.BUGLE_TAG, "onConversationMessagesCursorUpdated " +
                        " scrollToPos: " + scrollToPos +
                        " cursorCount: " + cursor.getCount());
                scrollToPosition(scrollToPos, true /*smoothScroll*/);
                clearScrollToMessagePosition();
            }
        }

        mHost.invalidateActionBar();

        // A message that arrives while the thread is open and focused gets its displayed
        // receipt now; the on-open path runs only on resume. The same path, idempotent.
        if (newestMessage != null
                && newestMessage.getIsIncoming()
                && newestMessage.getIsRcs()
                && mConversationId != null
                && isBound()
                && mBinding.getData().isFocused()) {
            BugleNotifications.markMessagesAsRead(mConversationId);
        }
    }

    /**
     * {@inheritDoc} from ConversationDataListener
     */
    @Override
    public void onConversationMetadataUpdated(final ConversationData conversationData) {
        mBinding.ensureBound(conversationData);

        if (mSelectedMessage != null && mSelectedAttachment != null) {
            // We may have just sent a message and the temp attachment we selected is now gone.
            // and it was replaced with some new attachment.  Since we don't know which one it
            // is we shouldn't reselect it (unless there is just one) In the multi-attachment
            // case we would just deselect the message and allow the user to reselect, otherwise we
            // may act on old temp data and may crash.
            final List<MessagePartData> currentAttachments = mSelectedMessage.getData().getAttachments();
            if (currentAttachments.size() == 1) {
                mSelectedAttachment = currentAttachments.get(0);
            } else if (!currentAttachments.contains(mSelectedAttachment)) {
                selectMessage(null);
            }
        }
        // Ensure that the action bar is updated with the current data.
        invalidateOptionsMenu();
        mHost.onConversationMetadataUpdated();
        mAdapter.notifyDataSetChanged();
    }

    public void setConversationInfo(final Context context, final String conversationId,
            final MessageData draftData) {
        // TODO: Eventually I would like the Factory to implement
        // Factory.get().bindConversationData(mBinding, getActivity(), this, conversationId));
        if (!mBinding.isBound()) {
            mConversationId = conversationId;
            mIncomingDraft = draftData;
            mBinding.bind(DataModel.get().createConversationData(context, this, conversationId));
            // For group typing.
            warmConversationRcsGroupId(conversationId);
        } else {
            Assert.isTrue(TextUtils.equals(mBinding.getData().getConversationId(), conversationId));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unbind all the views that we bound to data
        if (mComposeMessageView != null) {
            mComposeMessageView.unbind();
        }

        // In case the fragment is destroyed without a pause.
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport != null) {
            transport.unregisterTypingListener(this);
            transport.unregisterGroupTypingListener(this);
        }
        mHandler.removeCallbacks(mHideTypingRunnable);
        clearGroupTypingSenders();
        mTypingIndicatorView = null;
        mTypingIndicatorRow = null;

        // And unbind this fragment from its data
        mBinding.unbind();
        mConversationId = null;
    }

    void suppressWriteDraft() {
        mSuppressWriteDraft = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mComposeMessageView != null && !mSuppressWriteDraft) {
            mComposeMessageView.writeDraftMessage();
        }
        mSuppressWriteDraft = false;
        mBinding.getData().unsetFocus();
        mListState = mRecyclerView.getLayoutManager().onSaveInstanceState();

        LocalBroadcastManager.getInstance(getActivity())
                .unregisterReceiver(mConversationSelfIdChangeReceiver);

        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport != null) {
            transport.unregisterTypingListener(this);
            transport.unregisterGroupTypingListener(this);
        }
        // Drop the typing row and its pending expiry.
        mHandler.removeCallbacks(mHideTypingRunnable);
        clearGroupTypingSenders();
        hideTypingIndicator();
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mRecyclerView.getItemAnimator().endAnimations();
    }

    /**
     * Inbound 1:1 typing, on the main thread. Shows or hides the typing row when the sender is
     * this conversation's peer. No database write.
     */
    @Override
    public void onTyping(final int subId, final String fromUri, final boolean active) {
        // The listener is process-wide; ignore typing for a thread we are not showing.
        if (mConversationId == null || !isBound()) {
            return;
        }
        if (!isTypingFromConversationPeer(fromUri)) {
            return;
        }
        if (active) {
            showTypingIndicator();
            // Re-arm so a missed idle cannot strand the row.
            mHandler.removeCallbacks(mHideTypingRunnable);
            mHandler.postDelayed(mHideTypingRunnable, TYPING_AUTO_EXPIRE_MS);
        } else {
            mHandler.removeCallbacks(mHideTypingRunnable);
            hideTypingIndicator();
        }
    }

    /**
     * Inbound group typing, on the main thread, for this thread's rcs_group_id only. Each
     * sender has its own expiry and the row text is rebuilt from the live set.
     */
    @Override
    public void onGroupTyping(final int subId, final String groupId,
            final String fromUri, final boolean active) {
        if (mConversationId == null || !isBound()) {
            return;
        }
        final String myGroupId = mConversationRcsGroupId;
        if (TextUtils.isEmpty(myGroupId) || TextUtils.isEmpty(groupId)
                || !TextUtils.equals(myGroupId, groupId)) {
            return;
        }
        if (TextUtils.isEmpty(fromUri)) {
            return;
        }
        if (active) {
            // Replace the sender's expiry, refresh the name, re-arm.
            final Runnable prev = mGroupTypingSenders.remove(fromUri);
            if (prev != null) {
                mHandler.removeCallbacks(prev);
            }
            if (!mGroupTypingNames.containsKey(fromUri)) {
                mGroupTypingNames.put(fromUri, resolveGroupSenderName(subId, fromUri));
            }
            final Runnable expire = () -> {
                mGroupTypingSenders.remove(fromUri);
                mGroupTypingNames.remove(fromUri);
                refreshGroupTypingRow();
            };
            mGroupTypingSenders.put(fromUri, expire);
            mHandler.postDelayed(expire, TYPING_AUTO_EXPIRE_MS);
        } else {
            final Runnable prev = mGroupTypingSenders.remove(fromUri);
            if (prev != null) {
                mHandler.removeCallbacks(prev);
            }
            mGroupTypingNames.remove(fromUri);
        }
        refreshGroupTypingRow();
    }

    /** Shows the typing row for the current group senders, or hides it when there are none. */
    private void refreshGroupTypingRow() {
        if (mGroupTypingSenders.isEmpty()) {
            hideTypingIndicator();
            return;
        }
        showTypingIndicator(buildGroupTypingText());
    }

    /** "a is typing…", "a, b are typing…", or "a, b +N are typing…", in arrival order. */
    private String buildGroupTypingText() {
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (final String sender : mGroupTypingSenders.keySet()) {
            String n = mGroupTypingNames.get(sender);
            if (TextUtils.isEmpty(n)) {
                n = sender;
            }
            names.add(n);
        }
        final int count = names.size();
        if (count <= 0) {
            return getResources().getString(R.string.rcs_typing_generic);
        }
        if (count == 1) {
            return getResources().getString(R.string.rcs_typing_named, names.get(0));
        }
        if (count == 2) {
            return getResources().getString(
                    R.string.rcs_group_typing_two, names.get(0), names.get(1));
        }
        return getResources().getString(R.string.rcs_group_typing_many,
                names.get(0), names.get(1), count - 2);
    }

    /** Display name for a group member's number; never null. */
    private String resolveGroupSenderName(final int subId, final String rawPhone) {
        if (TextUtils.isEmpty(rawPhone)) {
            return "";
        }
        try {
            return ParticipantData.getFromRawPhoneBySimLocale(
                    stripTelScheme(rawPhone), subId).getDisplayName(false /* preferFullName */);
        } catch (final Throwable t) {
            return rawPhone;
        }
    }

    /** Cancels every sender's expiry and clears the set. */
    private void clearGroupTypingSenders() {
        for (final Runnable r : mGroupTypingSenders.values()) {
            mHandler.removeCallbacks(r);
        }
        mGroupTypingSenders.clear();
        mGroupTypingNames.clear();
    }

    /** Loads this thread's rcs_group_id off the main thread; null for a 1:1. */
    private void warmConversationRcsGroupId(final String conversationId) {
        if (TextUtils.isEmpty(conversationId)) {
            mConversationRcsGroupId = null;
            return;
        }
        mGroupTypingResolveExecutor.execute(() -> {
            String gid = null;
            try {
                final com.android.messaging.datamodel.DatabaseWrapper db =
                        DataModel.get().getDatabase();
                gid = com.android.messaging.datamodel.BugleDatabaseOperations
                        .getConversationRcsGroupId(db, conversationId);
            } catch (final Throwable t) {
                gid = null;
            }
            mConversationRcsGroupId = gid;
        });
    }

    private final ExecutorService mGroupTypingResolveExecutor =
            Executors.newSingleThreadExecutor();

    /** True when {@code fromUri} is this 1:1 conversation's other participant. */
    private boolean isTypingFromConversationPeer(final String fromUri) {
        if (TextUtils.isEmpty(fromUri) || !mBinding.getData().getParticipantsLoaded()) {
            return false;
        }
        final ParticipantData other = mBinding.getData().getOtherParticipant();
        if (other == null) {
            // Not a 1:1 thread.
            return false;
        }
        final String peerRaw = stripTelScheme(fromUri);
        // Compare canonical forms, so tel: URIs and dialled numbers match.
        final PhoneUtils phoneUtils = PhoneUtils.getDefault();
        final String peerCanonical = phoneUtils.getCanonicalBySimLocale(peerRaw);
        final String otherNormalized = other.getNormalizedDestination();
        final String otherCanonical = !TextUtils.isEmpty(otherNormalized)
                ? phoneUtils.getCanonicalBySimLocale(otherNormalized)
                : null;
        if (!TextUtils.isEmpty(peerCanonical) && !TextUtils.isEmpty(otherCanonical)
                && TextUtils.equals(peerCanonical, otherCanonical)) {
            return true;
        }
        // Numbers canonicalization cannot handle, such as short codes: compare raw forms.
        return TextUtils.equals(peerRaw, otherNormalized)
                || TextUtils.equals(peerRaw, other.getSendDestination());
    }

    private static String stripTelScheme(final String uri) {
        if (uri == null) {
            return null;
        }
        if (uri.startsWith("tel:")) {
            return uri.substring("tel:".length());
        }
        if (uri.startsWith("sip:")) {
            // sip:+15551234567@domain -> +15551234567
            final String rest = uri.substring("sip:".length());
            final int at = rest.indexOf('@');
            return at >= 0 ? rest.substring(0, at) : rest;
        }
        return uri;
    }

    /**
     * Shows the typing row, inflated once, between the message list and the compose bar, so the
     * list reflows rather than being overlaid.
     */
    private void showTypingIndicator() {
        showTypingIndicator(buildTypingText());
    }

    /** As {@link #showTypingIndicator()} with an explicit label. */
    private void showTypingIndicator(final String label) {
        final View root = getView();
        if (root == null) {
            return;
        }
        if (mTypingIndicatorView == null) {
            final TextView tv = new TextView(getActivity());
            tv.setTextAppearance(android.R.style.TextAppearance_Small);
            // Theme-aware secondary text color.
            final android.util.TypedValue tvColor = new android.util.TypedValue();
            if (getContext().getTheme().resolveAttribute(
                    android.R.attr.textColorSecondary, tvColor, true)) {
                tv.setTextColor(getResources().getColor(tvColor.resourceId,
                        getContext().getTheme()));
            }
            tv.setSingleLine(true);
            final float density = getResources().getDisplayMetrics().density;
            final int padH = (int) (14f * density);
            final int padV = (int) (8f * density);
            tv.setPadding(padH, padV, padH, padV);

            // Insert the row right after the list's FrameLayout in its parent LinearLayout.
            final View list = root.findViewById(android.R.id.list);
            final ViewGroup listFrame = (list != null && list.getParent() instanceof ViewGroup)
                    ? (ViewGroup) list.getParent() : null;
            final ViewGroup rowParent = (listFrame != null
                    && listFrame.getParent() instanceof android.widget.LinearLayout)
                    ? (android.widget.LinearLayout) listFrame.getParent() : null;
            if (rowParent == null) {
                // Unexpected layout; skip the row.
                return;
            }

            final android.widget.LinearLayout rowContainer =
                    new android.widget.LinearLayout(getActivity());
            rowContainer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowContainer.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            final int rowPadH = (int) (12f * density);
            final int rowPadV = (int) (6f * density);
            rowContainer.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);
            rowContainer.addView(tv, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            final int insertIndex = rowParent.indexOfChild(listFrame) + 1;
            rowParent.addView(rowContainer, insertIndex,
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            mTypingIndicatorRow = rowContainer;
            mTypingIndicatorView = tv;
        }
        mTypingIndicatorView.setText(label);
        if (mTypingIndicatorRow != null) {
            mTypingIndicatorRow.setVisibility(View.VISIBLE);
        }
        mTypingIndicatorView.setVisibility(View.VISIBLE);
    }

    private void hideTypingIndicator() {
        // Gone, so the list reflows.
        if (mTypingIndicatorRow != null) {
            mTypingIndicatorRow.setVisibility(View.GONE);
        }
        if (mTypingIndicatorView != null) {
            mTypingIndicatorView.setVisibility(View.GONE);
        }
    }

    /** "<name> is typing…" when the peer's name is known, else "Typing…". */
    private String buildTypingText() {
        String name = null;
        if (isBound() && mBinding.getData().getParticipantsLoaded()) {
            final ParticipantData other = mBinding.getData().getOtherParticipant();
            if (other != null) {
                name = other.getDisplayName(false /* preferFullName */);
            }
        }
        if (!TextUtils.isEmpty(name)) {
            return getResources().getString(R.string.rcs_typing_named, name);
        }
        return getResources().getString(R.string.rcs_typing_generic);
    }

    // TODO: Remove isBound and replace it with ensureBound after b/15704674.
    public boolean isBound() {
        return mBinding.isBound();
    }

    private FragmentManager getFragmentManagerToUse() {
        return getChildFragmentManager();
    }

    public MediaPicker getMediaPicker() {
        return (MediaPicker) getFragmentManagerToUse().findFragmentByTag(
                MediaPicker.FRAGMENT_TAG);
    }

    @Override
    public void sendMessage(final MessageData message) {
        if (isReadyForAction()) {
            if (ensureKnownRecipients()) {
                // Merge the caption text from attachments into the text body of the messages
                message.consolidateText();

                mBinding.getData().sendMessage(mBinding, message);
                mComposeMessageView.resetMediaPickerState();
            } else {
                LogUtil.w(LogUtil.BUGLE_TAG, "Message can't be sent: conv participants not loaded");
            }
        } else {
            warnOfMissingActionConditions(true /*sending*/, () -> sendMessage(message));
        }
    }

    public void setHost(final ConversationFragmentHost host) {
        mHost = host;
    }

    public String getConversationName() {
        return mBinding.getData().getConversationName();
    }

    @Override
    public void onComposeEditTextFocused() {
        mHost.onStartComposeMessage();
    }

    @Override
    public void onAttachmentsCleared() {
        // When attachments are removed, reset transient media picker state such as image selection.
        mComposeMessageView.resetMediaPickerState();
    }

    /**
     * Called to check if all conditions are nominal and a "go" for some action, such as deleting
     * a message, that requires this app to be the default app. This is also a precondition
     * required for sending a draft.
     * @return true if all conditions are nominal and we're ready to send a message
     */
    @Override
    public boolean isReadyForAction() {
        return UiUtils.isReadyForAction();
    }

    public boolean isReadyForDeleteAction() {
        return UiUtils.isReadyForDeleteAction();
    }

    /**
     * When there's some condition that prevents an operation, such as sending a message,
     * call warnOfMissingActionConditions to put up a snackbar and allow the user to repair
     * that condition.
     * @param sending - true if we're called during a sending operation
     * @param commandToRunAfterActionConditionResolved - a runnable to run after the user responds
     *                  positively to the condition prompt and resolves the condition. If null,
     *                  the user will be shown a toast to tap the send button again.
     */
    @Override
    public void warnOfMissingActionConditions(final boolean sending,
            final Runnable commandToRunAfterActionConditionResolved) {
        if (mChangeDefaultSmsAppHelper == null) {
            mChangeDefaultSmsAppHelper = new ChangeDefaultSmsAppHelper();
        }
        mChangeDefaultSmsAppHelper.warnOfMissingActionConditions(sending,
                commandToRunAfterActionConditionResolved, mComposeMessageView,
                getView().getRootView(),
                getActivity(), this);
    }

    private boolean ensureKnownRecipients() {
        final ConversationData conversationData = mBinding.getData();

        if (!conversationData.getParticipantsLoaded()) {
            // We can't tell yet whether or not we have an unknown recipient
            return false;
        }

        final ConversationParticipantsData participants = conversationData.getParticipants();
        for (final ParticipantData participant : participants) {


            if (participant.isUnknownSender()) {
                UiUtils.showToast(R.string.unknown_sender);
                return false;
            }
        }

        return true;
    }

    public void retryDownload(final String messageId) {
        if (isReadyForAction()) {
            mBinding.getData().downloadMessage(mBinding, messageId);
        } else {
            warnOfMissingActionConditions(false /*sending*/,
                    null /*commandToRunAfterActionConditionResolved*/);
        }
    }

    public void retrySend(final String messageId) {
        if (isReadyForAction()) {
            if (ensureKnownRecipients()) {
                mBinding.getData().resendMessage(mBinding, messageId);
            }
        } else {
            warnOfMissingActionConditions(true /*sending*/, () -> retrySend(messageId));
        }
    }

    /**
     * "Send as SMS" for a failed RCS message: sends its body as a new SMS through the forced
     * SMS path and deletes the failed row. Only on the user's choice.
     */
    public void resendAsSms(final String messageId) {
        if (!isReadyForAction()) {
            warnOfMissingActionConditions(true /*sending*/, () -> resendAsSms(messageId));
            return;
        }
        if (!ensureKnownRecipients()) {
            return;
        }
        final ConversationMessageData data = mSelectedMessage.getData();
        final String body = data.getText();
        if (TextUtils.isEmpty(body)) {
            LogUtil.w(LogUtil.BUGLE_TAG,
                    "resendAsSms: failed RCS message has no text body; nothing to send as SMS");
            return;
        }
        final String conversationId = data.getConversationId();
        // The conversation's current self, as a normal send would use.
        final String selfId = mComposeMessageView.getConversationSelfId();
        final MessageData smsMessage =
                MessageData.createDraftSmsMessage(conversationId, selfId, body);
        mBinding.getData().sendMessageAsSms(mBinding, smsMessage);
        // The SMS supersedes the failed RCS row.
        mBinding.getData().deleteMessage(mBinding, messageId);
    }

    void deleteMessage(final String messageId) {
        if (isReadyForDeleteAction()) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.delete_message_confirmation_dialog_title)
                    .setMessage(R.string.delete_message_confirmation_dialog_text)
                    .setPositiveButton(R.string.delete_message_confirmation_button,
                            (dialog, which) ->
                                    mBinding.getData().deleteMessage(mBinding, messageId))
                    .setNegativeButton(android.R.string.cancel, null);
            builder.setOnDismissListener(dialog -> mHost.dismissActionMode());
            builder.create().show();
        } else {
            warnOfMissingActionConditions(false /*sending*/,
                    null /*commandToRunAfterActionConditionResolved*/);
            mHost.dismissActionMode();
        }
    }

    public void deleteConversation() {
        if (isReadyForDeleteAction()) {
            final Context context = getActivity();
            mBinding.getData().deleteConversation(mBinding);
            closeConversation(mConversationId);
        } else {
            warnOfMissingActionConditions(false /*sending*/,
                    null /*commandToRunAfterActionConditionResolved*/);
        }
    }

    @Override
    public void closeConversation(final String conversationId) {
        if (TextUtils.equals(conversationId, mConversationId)) {
            mHost.onFinishCurrentConversation();
            // TODO: Explicitly transition to ConversationList (or just go back)?
        }
    }

    @Override
    public void onConversationParticipantDataLoaded(final ConversationData data) {
        mBinding.ensureBound(data);
        if (mBinding.getData().getParticipantsLoaded()) {
            final boolean oneOnOne = mBinding.getData().getOtherParticipant() != null;
            mAdapter.setOneOnOne(oneOnOne, true /* invalidate */);

            // refresh the options menu which will enable the "people & options" item.
            invalidateOptionsMenu();

            mHost.invalidateActionBar();

            mRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onSubscriptionListDataLoaded(final ConversationData data) {
        mBinding.ensureBound(data);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void promptForSelfPhoneNumber() {
        if (mComposeMessageView != null) {
            // Avoid bug in system which puts soft keyboard over dialog after orientation change
            ImeUtil.hideSoftInput(getActivity(), mComposeMessageView);
        }

        final FragmentTransaction ft = getActivity().getSupportFragmentManager().beginTransaction();
        final EnterSelfPhoneNumberDialog dialog = EnterSelfPhoneNumberDialog
                .newInstance(getConversationSelfSubId());
        dialog.setTargetFragment(this, 0/*requestCode*/);
        dialog.show(ft, null/*tag*/);
    }

    public boolean hasMessages() {
        return mAdapter != null && mAdapter.getItemCount() > 0;
    }

    public boolean onBackPressed() {
        if (mComposeMessageView.onBackPressed()) {
            return true;
        }
        return false;
    }

    public boolean onNavigationUpPressed() {
        return mComposeMessageView.onNavigationUpPressed();
    }

    @Override
    public boolean onAttachmentClick(final ConversationMessageView messageView,
            final MessagePartData attachment, final Rect imageBounds, final boolean longPress) {
        if (longPress) {
            selectMessage(messageView, attachment);
            return true;
        } else if (messageView.getData().getOneClickResendMessage()) {
            handleMessageClick(messageView);
            return true;
        }

        // An inbound RCS file of any type may await accept: a thumbnail, or a placeholder for a
        // file the provider did not download. Once downloaded, a file no media view draws is
        // opened by another app.
        if (messageView.getData().getIsRcs()) {
            final Uri uri = attachment.getContentUri();
            if (RcsFileAttachment.rendersAsFile(
                    ContentType.isMediaType(attachment.getContentType()),
                    uri == null ? null : uri.toString())) {
                maybeAcceptOrViewRcsMedia(messageView.getData(),
                        () -> openRcsFile(uri, attachment.getContentType()));
                return true;
            }
            if (attachment.isImage() || attachment.isVideo()) {
                maybeAcceptOrViewRcsMedia(messageView.getData(), () -> {
                    if (uri != null) displayPhoto(uri, imageBounds, false /* isDraft */);
                });
                return true;
            }
        }

        if (attachment.isImage()) {
            displayPhoto(attachment.getContentUri(), imageBounds, false /* isDraft */);
        }

        if (attachment.isVCard()) {
            UIIntents.get().launchVCardDetailActivity(getActivity(), attachment.getContentUri());
        }

        return false;
    }

    /**
     * Off the main thread: requests the download of an inbound RCS file still pending (a thumbnail
     * or a placeholder), says so when the provider reported the file unavailable, else runs
     * {@code view} on the main thread.
     */
    private void maybeAcceptOrViewRcsMedia(final ConversationMessageData data,
            final Runnable view) {
        final Context appCtx = getActivity() == null
                ? Factory.get().getApplicationContext()
                : getActivity().getApplicationContext();
        final String localId = data.getMessageId();
        final String selfId = data.getSelfParticipantId();
        new Thread(() -> {
            try {
                final DatabaseWrapper db = DataModel.get().getDatabase();
                final RcsMessageStore.RcsMeta meta =
                        RcsMessageStore.readByLocalId(db, localId);
                final boolean pending = meta != null
                        && meta.rcsStatus == RcsConstants.RCS_FILE_PENDING
                        && !TextUtils.isEmpty(meta.rcsMessageId);
                if (meta != null && meta.rcsStatus == RcsConstants.RCS_FILE_UNAVAILABLE) {
                    // The provider said it can no longer download this file; another accept
                    // would only fail again.
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isAdded()) {
                            Toast.makeText(appCtx, R.string.rcs_file_unavailable,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } else if (pending) {
                    final int subId =
                            BugleDatabaseOperations.getSelfSubscriptionId(db, selfId);
                    ProviderTransport.getInstance(appCtx)
                            .acceptIncomingFile(subId, meta.rcsMessageId);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isAdded()) {
                            Toast.makeText(appCtx, R.string.rcs_downloading_attachment,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isAdded()) {
                            view.run();
                        }
                    });
                }
            } catch (final Throwable t) {
                LogUtil.w(LogUtil.BUGLE_TAG, "maybeAcceptOrViewRcsMedia failed", t);
            }
        }, "rcs-accept-file").start();
    }

    /** Opens a received RCS file in another app, with read access to it alone. */
    private void openRcsFile(final Uri uri, final String contentType) {
        if (uri == null || RcsFileAttachment.isPlaceholder(uri.toString())) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, contentType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            UiUtils.showToastAtBottom(R.string.rcs_file_no_viewer);
        }
    }

    private void handleMessageClick(final ConversationMessageView messageView) {
        if (messageView != mSelectedMessage) {
            final ConversationMessageData data = messageView.getData();
            final boolean isReadyToSend = isReadyForAction();
            if (data.getOneClickResendMessage()) {
                // Directly resend the message on tap if it's failed
                retrySend(data.getMessageId());
                selectMessage(null);
            } else if (data.getShowResendMessage() && isReadyToSend) {
                // Select the message to show the resend/download/delete options
                selectMessage(messageView);
            } else if (data.getShowDownloadMessage() && isReadyToSend) {
                // Directly download the message on tap
                retryDownload(data.getMessageId());
            } else {
                // Let the toast from warnOfMissingActionConditions show and skip
                // selecting
                warnOfMissingActionConditions(false /*sending*/,
                        null /*commandToRunAfterActionConditionResolved*/);
                selectMessage(null);
            }
        } else {
            selectMessage(null);
        }
    }

    private static class AttachmentToSave {
        public final Uri uri;
        public final String contentType;
        public Uri persistedUri;

        AttachmentToSave(final Uri uri, final String contentType) {
            this.uri = uri;
            this.contentType = contentType;
        }
    }

    public static class SaveAttachmentTask {
        private final Context mContext;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final List<AttachmentToSave> mAttachmentsToSave = new ArrayList<>();

        public SaveAttachmentTask(final Context context, final Uri contentUri,
                final String contentType) {
            mContext = context;
            addAttachmentToSave(contentUri, contentType);
        }

        public SaveAttachmentTask(final Context context) {
            mContext = context;
        }

        public void addAttachmentToSave(final Uri contentUri, final String contentType) {
            mAttachmentsToSave.add(new AttachmentToSave(contentUri, contentType));
        }

        public int getAttachmentCount() {
            return mAttachmentsToSave.size();
        }

        public void execute() {
            mExecutor.execute(() -> {
                onExecute();
                mHandler.post(this::onPostExecute);
            });
        }

        protected void onExecute() {
            final String appDir = Environment.DIRECTORY_PICTURES
                    + File.separator
                    + mContext.getResources().getString(R.string.app_name);
            final String downloadDir = Environment.DIRECTORY_DOWNLOADS;
            final ContentResolver resolver = mContext.getContentResolver();
            for (final AttachmentToSave attachment : mAttachmentsToSave) {
                final boolean isImageOrVideo = ContentType.isImageType(attachment.contentType)
                        || ContentType.isVideoType(attachment.contentType);
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.MIME_TYPE, attachment.contentType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, isImageOrVideo ?
                        appDir : downloadDir);
                attachment.persistedUri = resolver.insert(isImageOrVideo
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                UriUtil.persistContent(mContext, attachment.uri, attachment.persistedUri);
           }
        }

        protected void onPostExecute() {
            int failCount = 0;
            int imageCount = 0;
            int videoCount = 0;
            int otherCount = 0;
            for (final AttachmentToSave attachment : mAttachmentsToSave) {
                if (attachment.persistedUri == null) {
                   failCount++;
                   continue;
                }

                if (ContentType.isImageType(attachment.contentType)) {
                    imageCount++;
                } else if (ContentType.isVideoType(attachment.contentType)) {
                    videoCount++;
                } else {
                    otherCount++;
                }
            }

            String message;
            if (failCount > 0) {
                message = mContext.getResources().getQuantityString(
                        R.plurals.attachment_save_error, failCount, failCount);
            } else {
                int messageId = R.plurals.attachments_saved;
                if (otherCount > 0) {
                    if (imageCount + videoCount == 0) {
                        messageId = R.plurals.attachments_saved_to_downloads;
                    }
                } else {
                    if (videoCount == 0) {
                        messageId = R.plurals.photos_saved_to_album;
                    } else if (imageCount == 0) {
                        messageId = R.plurals.videos_saved_to_album;
                    } else {
                        messageId = R.plurals.attachments_saved_to_album;
                    }
                }
                final String appName = mContext.getResources().getString(R.string.app_name);
                final int count = imageCount + videoCount + otherCount;
                message = mContext.getResources().getQuantityString(
                        messageId, count, count, appName);
            }
            UiUtils.showToastAtBottom(message);
        }
    }

    private void invalidateOptionsMenu() {
        final Activity activity = getActivity();
        // TODO: Add the supportInvalidateOptionsMenu call to the host activity.
        if (activity == null || !(activity instanceof BugleActionBarActivity)) {
            return;
        }
        ((BugleActionBarActivity) activity).supportInvalidateOptionsMenu();
    }

    @Override
    public void setOptionsMenuVisibility(final boolean visible) {
        setHasOptionsMenu(visible);
    }

    @Override
    public int getConversationSelfSubId() {
        final String selfParticipantId = mComposeMessageView.getConversationSelfId();
        final ParticipantData self = mBinding.getData().getSelfParticipantById(selfParticipantId);
        // If the self id or the self participant data hasn't been loaded yet, fallback to
        // the default setting.
        return self == null ? ParticipantData.DEFAULT_SELF_SUB_ID : self.getSubId();
    }

    @Override
    public void invalidateActionBar() {
        mHost.invalidateActionBar();
    }

    @Override
    public void dismissActionMode() {
        mHost.dismissActionMode();
    }

    @Override
    public void selectSim(final SubscriptionListEntry subscriptionData) {
        mComposeMessageView.selectSim(subscriptionData);
        mHost.onStartComposeMessage();
    }

    @Override
    public void onStartComposeMessage() {
        mHost.onStartComposeMessage();
    }

    @Override
    public SubscriptionListEntry getSubscriptionEntryForSelfParticipant(
            final String selfParticipantId, final boolean excludeDefault) {
        // TODO: ConversationMessageView is the only one using this. We should probably
        // inject this into the view during binding in the ConversationMessageAdapter.
        return mBinding.getData().getSubscriptionEntryForSelfParticipant(selfParticipantId,
                excludeDefault);
    }

    /**
     * Records our reaction optimistically, then sends it off the main thread to the 1:1 peer or
     * the group.
     */
    @Override
    public void onReactionSelected(final ConversationMessageView view, final String emoji,
            final boolean add) {
        if (view == null || TextUtils.isEmpty(emoji)) {
            return;
        }
        final ConversationMessageData data = view.getData();
        final String targetRcsId = data.getRcsMessageId();
        if (TextUtils.isEmpty(targetRcsId)) {
            // Not an RCS message with a wire id.
            return;
        }
        // Optimistic, so the chip changes before the send completes.
        UpdateRcsReactionAction.recordSelfReaction(targetRcsId, emoji, add);

        final String groupId = mConversationRcsGroupId; // null for 1:1
        String toUri = null;
        if (TextUtils.isEmpty(groupId)) {
            final ParticipantData other = mBinding.getData().getOtherParticipant();
            // The provider needs E.164; the send destination may lack the country code.
            toUri = other == null ? null : other.getNormalizedDestination();
            if (other != null && TextUtils.isEmpty(toUri)) {
                toUri = other.getSendDestination();
            }
            if (TextUtils.isEmpty(toUri)) {
                LogUtil.w(LogUtil.BUGLE_TAG,
                        "onReactionSelected: no 1:1 recipient; chip kept, send skipped");
                return;
            }
        }
        final int subId = getConversationSelfSubId();
        final Context appCtx = getActivity() == null
                ? Factory.get().getApplicationContext()
                : getActivity().getApplicationContext();
        final String sendToUri = toUri;
        new Thread(() -> {
            try {
                ProviderTransport.getInstance(appCtx)
                        .sendReaction(subId, targetRcsId, sendToUri, emoji, add, groupId);
            } catch (final Throwable t) {
                LogUtil.w(LogUtil.BUGLE_TAG, "onReactionSelected: sendReaction failed", t);
            }
        }, "rcs-send-reaction").start();
    }

    /**
     * A suggestion chip under a bot card was tapped: sends the suggestion response, echoing
     * the chip's {@code postback.data} verbatim, off the main thread.
     */
    @Override
    public void onBotSuggestionTapped(final ConversationMessageView view,
            final RbmSuggestion suggestion) {
        if (view == null || suggestion == null) {
            return;
        }
        final String botId = view.getData().getBotId();
        if (TextUtils.isEmpty(botId)) {
            return;
        }
        final int subId = getConversationSelfSubId();

        // A suggested action fires a local intent and posts back only if it has postback data;
        // a suggested reply echoes its text as a sent bubble.
        if (suggestion.isAction()) {
            performBotAction(suggestion);
            if (TextUtils.isEmpty(suggestion.postbackData)) {
                return;            }
        } else if (!TextUtils.isEmpty(suggestion.displayText)) {
            new InsertRbmPostbackEchoAction(subId, botId, suggestion.displayText).start();
        }

        final String json = buildBotSuggestionResponse(suggestion);
        if (json == null) {
            return;        }
        final Context appCtx = getActivity() == null
                ? Factory.get().getApplicationContext()
                : getActivity().getApplicationContext();
        new Thread(() -> {
            try {
                ProviderTransport.getInstance(appCtx).sendBotPostback(subId, botId,
                        "application/vnd.gsma.botsuggestion.response.v1.0+json", json,
                        /*messageId=*/ null);
            } catch (final Throwable t) {
                LogUtil.w(LogUtil.BUGLE_TAG, "onBotSuggestionTapped: sendBotPostback failed", t);
            }
        }, "rbm-send-postback").start();
    }

    /**
     * Fires the intent for a suggested action, falling back to its {@code fallbackUrl}.
     * Share-location and unknown kinds do nothing here.
     */
    private void performBotAction(final RbmSuggestion s) {
        Intent intent = null;
        switch (s.type) {
            case OPEN_URL:
                if (!TextUtils.isEmpty(s.url)) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(s.url));
                }
                break;
            case DIAL:
                if (!TextUtils.isEmpty(s.phoneNumber)) {
                    intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + s.phoneNumber));
                }
                break;
            case VIEW_LOCATION:
                intent = new Intent(Intent.ACTION_VIEW, buildGeoUri(s));
                break;
            case CREATE_CALENDAR:
                intent = buildCalendarIntent(s);
                break;
            default:
                break;        }
        if (intent == null && !TextUtils.isEmpty(s.fallbackUrl)) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(s.fallbackUrl));
        }
        if (intent == null) {
            return;
        }
        final Activity activity = getActivity();
        final Context ctx = activity != null
                ? activity : Factory.get().getApplicationContext();
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            if (!TextUtils.isEmpty(s.fallbackUrl)) {
                try {
                    final Intent fb = new Intent(Intent.ACTION_VIEW, Uri.parse(s.fallbackUrl));
                    if (activity == null) {
                        fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    ctx.startActivity(fb);
                } catch (final ActivityNotFoundException ignore) {
                    LogUtil.w(LogUtil.BUGLE_TAG, "RBM action: no app for intent or fallback");
                }
            } else {
                LogUtil.w(LogUtil.BUGLE_TAG, "RBM action: no app handles " + intent.getAction());
            }
        }
    }

    /** {@code geo:} URI for a view-location action: coordinates if present, else a query. */
    private static Uri buildGeoUri(final RbmSuggestion s) {
        if (s.latitude != null && s.longitude != null) {
            final String ll = s.latitude + "," + s.longitude;
            if (!TextUtils.isEmpty(s.locationLabel)) {
                return Uri.parse("geo:" + ll + "?q=" + ll + "(" + Uri.encode(s.locationLabel)
                        + ")");
            }
            return Uri.parse("geo:" + ll + "?q=" + ll);
        }
        return Uri.parse("geo:0,0?q=" + Uri.encode(s.locationQuery == null ? "" : s.locationQuery));
    }

    /** {@code ACTION_INSERT} intent for a create-calendar action. */
    private static Intent buildCalendarIntent(final RbmSuggestion s) {
        final Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI);
        if (!TextUtils.isEmpty(s.calTitle)) {
            intent.putExtra(CalendarContract.Events.TITLE, s.calTitle);
        }
        if (!TextUtils.isEmpty(s.calDescription)) {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, s.calDescription);
        }
        final long start = parseRfc3339(s.calStart);
        final long end = parseRfc3339(s.calEnd);
        if (start > 0) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start);
        }
        if (end > 0) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end);
        }
        return intent;
    }

    private static long parseRfc3339(final String t) {
        if (TextUtils.isEmpty(t)) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(t).toEpochMilli();
        } catch (final Exception e) {
            return 0L;
        }
    }

    /**
     * Builds {@code {response:{reply:{displayText,postback:{data}}}}}, omitting absent
     * fields, or null when there is nothing to send.
     */
    private static String buildBotSuggestionResponse(final RbmSuggestion s) {
        if (TextUtils.isEmpty(s.displayText) && TextUtils.isEmpty(s.postbackData)) {
            return null;
        }
        try {
            final org.json.JSONObject reply = new org.json.JSONObject();
            if (!TextUtils.isEmpty(s.displayText)) {
                reply.put("displayText", s.displayText);
            }
            if (!TextUtils.isEmpty(s.postbackData)) {
                reply.put("postback", new org.json.JSONObject().put("data", s.postbackData));
            }
            return new org.json.JSONObject()
                    .put("response", new org.json.JSONObject().put("reply", reply))
                    .toString();
        } catch (final org.json.JSONException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "buildBotSuggestionResponse failed", e);
            return null;
        }
    }

    /**
     * "+" in the reaction quick row: an emoji entry dialog; a valid single emoji goes through
     * {@link #onReactionSelected} as an add.
     */
    @Override
    public void onOpenSystemEmojiPicker(final ConversationMessageView view) {
        if (view == null || getActivity() == null) {
            return;
        }
        final EditText input = new EditText(getActivity());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.reaction_custom_emoji_hint);
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.reaction_custom_emoji_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String glyph = input.getText() == null
                            ? null : input.getText().toString().trim();
                    final String emoji = validateReactionEmoji(glyph);
                    if (emoji == null) {
                        Toast.makeText(getActivity(), R.string.reaction_invalid_emoji,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onReactionSelected(view, emoji, true /* add */);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Shows who reacted with which emoji ("You" for our own). */
    @Override
    public void onReactionDetailsRequested(final ConversationMessageView view) {
        if (view == null || getActivity() == null) {
            return;
        }
        final ConversationMessageData data = view.getData();
        final List<ConversationMessageData.ReactionAggregate> aggs =
                data.getReactionAggregates();
        if (aggs == null || aggs.isEmpty()) {
            return;
        }
        final int subId = getConversationSelfSubId();
        final StringBuilder sb = new StringBuilder();
        for (final ConversationMessageData.ReactionAggregate r : aggs) {
            final List<String> names = new ArrayList<>();
            for (final String uri : r.reactorUris) {
                if (RcsMessageStore.SELF_REACTOR_URI.equals(uri)) {
                    names.add(getString(R.string.reaction_details_you));
                } else {
                    names.add(resolveGroupSenderName(subId, uri));
                }
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r.emoji).append("   ").append(TextUtils.join(", ", names));
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.reaction_details_title)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Returns the bare glyph when {@code candidate} is a single emoji, else null. Without
     * EmojiCompat metadata it accepts a short string with a non-ASCII code point.
     */
    @androidx.annotation.Nullable
    private static String validateReactionEmoji(
            @androidx.annotation.Nullable final String candidate) {
        if (TextUtils.isEmpty(candidate)) {
            return null;
        }
        try {
            final EmojiCompat ec = EmojiCompat.get();
            if (ec != null && ec.getLoadState() == EmojiCompat.LOAD_STATE_SUCCEEDED) {
                final int match = ec.getEmojiStart(candidate, 0);
                if (match == 0 && ec.getEmojiEnd(candidate, 0) == candidate.length()) {
                    return candidate;
                }
                return null;
            }
        } catch (final Throwable ignored) {
            // Not initialized: use the fallback.
        }
        if (candidate.length() > 16) {
            return null;
        }
        boolean hasNonAscii = false;
        for (int i = 0; i < candidate.length(); ) {
            final int cp = candidate.codePointAt(i);
            if (cp > 0x7F) {
                hasNonAscii = true;
            }
            i += Character.charCount(cp);
        }
        return hasNonAscii ? candidate : null;
    }

    @Override
    public SimSelectorView getSimSelectorView() {
        return getView().findViewById(R.id.sim_selector);
    }

    @Override
    public MediaPicker createMediaPicker() {
        return new MediaPicker(getActivity());
    }

    @Override
    public void notifyOfAttachmentLoadFailed() {
        UiUtils.showToastAtBottom(R.string.attachment_load_failed_dialog_message);
    }

    @Override
    public void warnOfExceedingMessageLimit(final boolean sending, final boolean tooManyVideos) {
        warnOfExceedingMessageLimit(sending, mComposeMessageView, mConversationId,
                getActivity(), tooManyVideos);
    }

    public void warnOfExceedingMessageLimit(final boolean sending,
            final ComposeMessageView composeMessageView, final String conversationId,
            final Activity activity, final boolean tooManyVideos) {
        final AlertDialog.Builder builder =
                new AlertDialog.Builder(activity)
                    .setTitle(R.string.mms_attachment_limit_reached);

        if (sending) {
            if (tooManyVideos) {
                builder.setMessage(R.string.video_attachment_limit_exceeded_when_sending);
            } else {
                builder.setMessage(R.string.attachment_limit_reached_dialog_message_when_sending)
                        .setNegativeButton(R.string.attachment_limit_reached_send_anyway,
                                (dialog, which) ->
                                        composeMessageView.sendMessageIgnoreMessageSizeLimit());
            }
            builder.setPositiveButton(android.R.string.ok, (dialog, which) ->
                    showAttachmentChooser(conversationId, activity));
        } else {
            builder.setMessage(R.string.attachment_limit_reached_dialog_message_when_composing)
                    .setPositiveButton(android.R.string.ok, null);
        }
        builder.show();
    }

    @Override
    public void showAttachmentChooser() {
        showAttachmentChooser(mConversationId, getActivity());
    }

    public void showAttachmentChooser(final String conversationId,
            final Activity activity) {
        final Intent intent = new Intent(activity, AttachmentChooserActivity.class);
        intent.putExtra(UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID, conversationId);
        mLauncher.launch(intent);
    }

    private void updateActionAndStatusBarColor(final ActionBar actionBar) {
        final int themeColor = ConversationDrawables.get().getConversationThemeColor();
        actionBar.setBackgroundDrawable(new ColorDrawable(themeColor));
        UiUtils.setStatusBarColor(getActivity(), themeColor);
    }

    public void updateActionBar(final ActionBar actionBar) {
        if (mComposeMessageView == null || !mComposeMessageView.updateActionBar(actionBar)) {
            updateActionAndStatusBarColor(actionBar);
            // We update this regardless of whether or not the action bar is showing so that we
            // don't get a race when it reappears.
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
            actionBar.setDisplayHomeAsUpEnabled(true);
            // Reset the back arrow to its default
            actionBar.setHomeAsUpIndicator(0);
            View customView = actionBar.getCustomView();
            if (customView == null || customView.getId() != R.id.conversation_title_container) {
                final LayoutInflater inflator = (LayoutInflater)
                        getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                customView = inflator.inflate(R.layout.action_bar_conversation_name, null);
                customView.setOnClickListener(v -> onBackPressed());
                actionBar.setCustomView(customView);
            }

            // Group conversations get a "N people" subtitle.
            final TextView conversationSubtitleView =
                    customView.findViewById(R.id.conversation_subtitle);
            if (conversationSubtitleView != null) {
                final int othersCount = mBinding.getData().getNumberOfParticipantsExcludingSelf();
                final boolean isGroup = othersCount > 1;
                if (isGroup) {
                    // Including self.
                    final int peopleCount = othersCount + 1;
                    final String subtitle = getResources().getQuantityString(
                            R.plurals.group_people_count, peopleCount, peopleCount);
                    conversationSubtitleView.setText(subtitle);
                    conversationSubtitleView.setContentDescription(subtitle);
                    conversationSubtitleView.setVisibility(View.VISIBLE);
                } else {
                    conversationSubtitleView.setVisibility(View.GONE);
                }
            }

            final TextView conversationNameView = customView.findViewById(R.id.conversation_title);
            final String conversationName = getConversationName();
            if (!TextUtils.isEmpty(conversationName)) {
                // RTL : To format conversation title if it happens to be phone numbers.
                final BidiFormatter bidiFormatter = BidiFormatter.getInstance();
                final String formattedName = bidiFormatter.unicodeWrap(
                        UiUtils.commaEllipsize(
                                conversationName,
                                conversationNameView.getPaint(),
                                conversationNameView.getWidth(),
                                getString(R.string.plus_one),
                                getString(R.string.plus_n)).toString(),
                        TextDirectionHeuristicsCompat.LTR);
                conversationNameView.setText(formattedName);
                // In case phone numbers are mixed in the conversation name, we need to vocalize it.
                final String vocalizedConversationName =
                        AccessibilityUtil.getVocalizedPhoneNumber(getResources(), conversationName);
                conversationNameView.setContentDescription(vocalizedConversationName);
                getActivity().setTitle(conversationName);
            } else {
                final String appName = getString(R.string.app_name);
                conversationNameView.setText(appName);
                getActivity().setTitle(appName);
            }

            updateBotBrandHeader(customView);

            // When conversation is showing and media picker is not showing, then hide the action
            // bar only when we are in landscape mode, with IME open.
            if (mHost.isImeOpen() && UiUtils.isLandscapeMode()) {
                actionBar.hide();
            } else {
                actionBar.show();
            }
        }
    }

    /** Suffix of a business-messaging agent address. */
    private static final String RBM_BOT_SUFFIX = "@rbm.goog";

    /**
     * Shows a business-messaging 1:1's brand logo, name and verified badge in the header. The
     * brand is resolved once off the main thread and cached; other conversations are unchanged.
     */
    private void updateBotBrandHeader(final View customView) {
        final AsyncImageView logo = customView.findViewById(R.id.conversation_brand_logo);
        final ImageView badge = customView.findViewById(R.id.conversation_verified_badge);
        if (logo == null || badge == null) {
            return;
        }
        final String botId = getBotConversationId();
        if (botId == null) {
            logo.setImageResourceId(null);
            logo.setVisibility(View.GONE);
            badge.setVisibility(View.GONE);
            return;
        }
        if (mBotBrand != null && botId.equals(mBotBrandBotId)) {
            renderBotBrand(customView, logo, badge, mBotBrand);
        } else {
            // Not resolved yet: plain header, start the fetch.
            logo.setVisibility(View.GONE);
            badge.setVisibility(View.GONE);
            fetchBotBrandAsync(botId);
        }
    }

    /** The agent id of a business-messaging 1:1, else null. */
    private String getBotConversationId() {
        if (!isBound() || !mBinding.getData().getParticipantsLoaded()) {
            return null;
        }
        if (mBinding.getData().getNumberOfParticipantsExcludingSelf() != 1) {
            return null;
        }
        final ParticipantData other = mBinding.getData().getOtherParticipant();
        if (other == null) {
            return null;
        }
        final String dest = other.getNormalizedDestination();
        return (dest != null && dest.endsWith(RBM_BOT_SUFFIX)) ? dest : null;
    }

    private void fetchBotBrandAsync(final String botId) {
        if (mBotBrandFetching) {
            return;
        }
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport == null) {
            return;
        }
        mBotBrandFetching = true;
        final int subId = getConversationSelfSubId();
        new Thread(() -> {
            final RcsBotBrand brand = transport.getBotBrand(subId, botId);
            final Activity activity = getActivity();
            if (activity == null) {
                mBotBrandFetching = false;
                return;
            }
            activity.runOnUiThread(() -> {
                mBotBrandFetching = false;
                if (brand == null) {
                    return;                }
                mBotBrand = brand;
                mBotBrandBotId = botId;
                if (isBound()) {
                    mHost.invalidateActionBar();                }
            });
        }, "rbm-brand-header").start();
    }

    private void renderBotBrand(final View customView, final AsyncImageView logo,
            final ImageView badge, final RcsBotBrand brand) {
        // The brand name instead of the agent address.
        final TextView nameView = customView.findViewById(R.id.conversation_title);
        if (nameView != null && !TextUtils.isEmpty(brand.name)) {
            nameView.setText(brand.name);
            getActivity().setTitle(brand.name);
        }
        if (!TextUtils.isEmpty(brand.logoUrl)) {
            final int size = getResources().getDimensionPixelSize(R.dimen.rbm_brand_logo_size);
            logo.setImageResourceId(new UriImageRequestDescriptor(Uri.parse(brand.logoUrl),
                    size, size, true /* cropToCircle */, 0 /* circleBackgroundColor */,
                    0 /* circleStrokeColor */));
            logo.setVisibility(View.VISIBLE);
        } else {
            logo.setImageResourceId(null);
            logo.setVisibility(View.GONE);
        }
        badge.setVisibility(brand.verified ? View.VISIBLE : View.GONE);

        // The verified badge opens an explanation and consumes its own tap.
        if (brand.verified) {
            badge.setClickable(true);
            badge.setOnClickListener(v -> showVerifiedBusinessDialog(brand));
        }

        // The header opens the business-info screen.
        customView.setClickable(true);
        customView.setOnClickListener(v -> {
            final Activity activity = getActivity();
            if (activity != null && brand != null) {
                activity.startActivity(
                        com.android.messaging.ui.rcs.RbmBusinessInfoActivity.intent(activity,
                                brand));
            }
        });
    }

    /** Explains the verified-business state. */
    private void showVerifiedBusinessDialog(final RcsBotBrand brand) {
        final Activity activity = getActivity();
        if (activity == null || brand == null) {
            return;
        }
        final String verifier = !TextUtils.isEmpty(brand.verifierName)
                ? brand.verifierName : getString(R.string.rbm_bot_info_verifier_generic);
        new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.rbm_bot_info_verified, verifier))
                .setMessage(getString(R.string.rbm_verified_dialog_body, verifier))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.rbm_verified_dialog_more, (d, w) ->
                        activity.startActivity(com.android.messaging.ui.rcs
                                .RbmBusinessInfoActivity.intent(activity, brand)))
                .show();
    }

    @Override
    public boolean shouldShowSubjectEditor() {
        return true;
    }

    @Override
    public boolean shouldHideAttachmentsWhenSimSelectorShown() {
        return false;
    }

    @Override
    public int getSimSelectorItemLayoutId() {
        return R.layout.sim_selector_item_view;
    }

    @Override
    public Uri getSelfSendButtonIconUri() {
        return null;    // use default button icon uri
    }

    @Override
    public int overrideCounterColor() {
        return -1;      // don't override the color
    }

    @Override
    public void onDraftChanged(final DraftMessageData data, final int changeFlags) {
        mDraftMessageDataModel.ensureBound(data);
        // We're specifically only interested in ATTACHMENTS_CHANGED from the widget. Ignore
        // other changes. When the widget changes an attachment, we need to reload the draft.
        if (changeFlags ==
                (DraftMessageData.WIDGET_CHANGED | DraftMessageData.ATTACHMENTS_CHANGED)) {
            mClearLocalDraft = true;        // force a reload of the draft in onResume
        }
    }

    @Override
    public void onDraftAttachmentLimitReached(final DraftMessageData data) {
        // no-op for now
    }

    @Override
    public void onDraftAttachmentLoadFailed() {
        // no-op for now
    }

    @Override
    public int getAttachmentsClearedFlags() {
        return DraftMessageData.ATTACHMENTS_CHANGED;
    }
}
