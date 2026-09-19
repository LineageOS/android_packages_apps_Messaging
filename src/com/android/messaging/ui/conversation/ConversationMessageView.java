/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
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
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.v7.mms.pdu.ContentType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.datamodel.media.ImageRequestDescriptor;
import com.android.messaging.datamodel.media.MessagePartImageRequestDescriptor;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.rcs.rbm.RbmBotMessage;
import com.android.messaging.rcs.rbm.RbmCard;
import com.android.messaging.rcs.rbm.RbmSuggestion;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.AsyncImageView;
import com.android.messaging.ui.AsyncImageView.AsyncImageViewDelayLoader;
import com.android.messaging.ui.AudioAttachmentView;
import com.android.messaging.ui.ContactIconView;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.ui.MultiAttachmentLayout;
import com.android.messaging.ui.MultiAttachmentLayout.OnAttachmentClickListener;
import com.android.messaging.ui.PersonItemView;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.VideoThumbnailView;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.LinkifyHelper;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UiUtils;
import com.android.messaging.util.YouTubeUtil;
import com.google.common.base.Predicate;

import java.util.Comparator;
import java.util.List;

/**
 * The view for a single entry in a conversation.
 */
public class ConversationMessageView extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener, OnAttachmentClickListener {
    public interface ConversationMessageViewHost {
        boolean onAttachmentClick(ConversationMessageView view, MessagePartData attachment,
                Rect imageBounds, boolean longPress);
        SubscriptionListEntry getSubscriptionEntryForSelfParticipant(String selfParticipantId,
                boolean excludeDefault);

        /**
         * Emoji-reactions: the user picked a reaction in the long-press quick-row
         * (or re-tapped an existing chip). The host owns the transport: it
         * resolves the target's rcs message-id + 1:1 recipient / group id and
         * dispatches {@code ProviderTransport.sendReaction} off the main thread,
         * plus writes the optimistic self-row so the chip updates immediately.
         *
         * @param view  the target message's view (carries its ConversationMessageData)
         * @param emoji the bare emoji glyph (no U+200A wrapping; the provider wraps)
         * @param add   true = add this reaction, false = retract our own reaction
         */
        void onReactionSelected(ConversationMessageView view, String emoji, boolean add);

        /**
         * Emoji-reactions: the user tapped the "+" in the quick-row to choose an
         * arbitrary emoji. The host launches the system emoji picker and, after
         * validating the pick with EmojiCompat, routes it back through
         * {@link #onReactionSelected} with {@code add=true}.
         */
        void onOpenSystemEmojiPicker(ConversationMessageView view);

        /**
         * Emoji-reactions: the user tapped the reaction badge on a message. The
         * host shows a "who reacted with what" breakdown (each emoji + the
         * reactor names), resolving reactor URIs to display names. Add/remove a
         * reaction is done from the long-press picker, not here.
         */
        void onReactionDetailsRequested(ConversationMessageView view);

        /**
         * RBM: the user tapped a suggestion chip under a bot
         * card. The host owns the transport: it builds the
         * {@code botsuggestion.response} payload (echoing the chip's opaque
         * {@code postbackData}) and sends it to the bot ({@code view.getData()
         * .getBotId()}) via {@code ProviderTransport.sendBotPostback} off the main
         * thread.
         */
        void onBotSuggestionTapped(ConversationMessageView view, RbmSuggestion suggestion);
    }

    /**
     * The 11 classic tapback emoji shown in the long-press quick-row (the design's
     * fixed set; the "+" button covers arbitrary unicode). Order is the product
     * order: like, love, laugh, heart-eyes, wow, sad-relieved, angry-face,
     * dislike, thinking, cry, rage.
     */
    static final String[] REACTION_QUICK_SET = {
            "👍", // 👍
            "❤️", // ❤️
            "😂", // 😂
            "😍", // 😍
            "😮", // 😮
            "😥", // 😥
            "😠", // 😠
            "👎", // 👎
            "🤔", // 🤔
            "😢", // 😢
            "😡", // 😡
    };

    private final ConversationMessageData mData;

    private LinearLayout mMessageAttachmentsView;
    private MultiAttachmentLayout mMultiAttachmentView;
    private AsyncImageView mMessageImageView;
    private TextView mMessageTextView;
    private boolean mMessageTextHasLinks;
    private boolean mMessageHasYouTubeLink;
    private TextView mStatusTextView;
    private TextView mTitleTextView;
    private TextView mMmsInfoTextView;
    private LinearLayout mMessageTitleLayout;
    private TextView mSenderNameTextView;
    private ContactIconView mContactIconView;
    private ConversationMessageBubbleView mMessageBubble;
    private View mSubjectView;
    private TextView mSubjectLabel;
    private TextView mSubjectText;
    private View mDeliveredBadge;
    private ImageView mE2eeLockIcon;
    private ViewGroup mMessageMetadataView;
    private ViewGroup mMessageTextAndInfoView;
    private TextView mSimNameView;
    // WAVE-B: centered, muted group lifecycle event status line. Only visible
    // for a TRANSPORT_RCS_SYSTEM row; null/GONE for every normal message.
    private TextView mSystemMessageView;
    // Emoji-reactions: wrapping row of reaction chips below the bubble. GONE for
    // any message with no reactions.
    private com.android.messaging.ui.LineWrapLayout mReactionsContainer;
    // Active reaction quick-row popup (long-press), so we can dismiss it.
    @Nullable private android.widget.PopupWindow mReactionPicker;
    // RBM: rich-card body (inside the bubble) + suggestion
    // chips (below it). All GONE for every non-bot row.
    private LinearLayout mRbmCardView;
    private FrameLayout mRbmCardMediaFrame;
    private AsyncImageView mRbmCardMedia;
    private ImageView mRbmCardMediaPlay;
    private LinearLayout mRbmCardFile;
    private TextView mRbmCardFileName;
    private LinearLayout mRbmCardTextCol;
    private TextView mRbmCardTitle;
    private TextView mRbmCardDescription;
    private com.android.messaging.ui.LineWrapLayout mRbmSuggestionsContainer;
    // RBM (Phase 6): horizontal carousel of cards, shown instead of the single
    // rbm_card when the message carries >1 card.
    private android.widget.HorizontalScrollView mRbmCarousel;
    private LinearLayout mRbmCarouselStrip;
    private LinearLayout mRbmCarouselDots;
    private TextView mRbmSelectedOption;

    // geopush: location-card body (OSM map thumbnail + address).
    private LinearLayout mLocationCard;
    private AsyncImageView mLocationMap;
    private TextView mLocationAddress;

    private boolean mOneOnOne;
    private ConversationMessageViewHost mHost;

    public ConversationMessageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        // TODO: we should switch to using Binding and DataModel factory methods.
        mData = new ConversationMessageData();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContactIconView = findViewById(R.id.conversation_icon);
        mContactIconView.setOnLongClickListener(view -> {
            ConversationMessageView.this.performLongClick();
            return true;
        });

        mMessageAttachmentsView = findViewById(R.id.message_attachments);
        mMultiAttachmentView = findViewById(R.id.multiple_attachments);
        mMultiAttachmentView.setOnAttachmentClickListener(this);

        mMessageImageView = findViewById(R.id.message_image);
        mMessageImageView.setOnClickListener(this);
        mMessageImageView.setOnLongClickListener(this);

        mMessageTextView = findViewById(R.id.message_text);
        mMessageTextView.setOnClickListener(this);
        IgnoreLinkLongClickHelper.ignoreLinkLongClick(mMessageTextView, this);

        mStatusTextView = findViewById(R.id.message_status);
        mTitleTextView = findViewById(R.id.message_title);
        mMmsInfoTextView = findViewById(R.id.mms_info);
        mMessageTitleLayout = findViewById(R.id.message_title_layout);
        mSenderNameTextView = findViewById(R.id.message_sender_name);
        mMessageBubble = findViewById(R.id.message_content);
        mSubjectView = findViewById(R.id.subject_container);
        mSubjectLabel = mSubjectView.findViewById(R.id.subject_label);
        mSubjectText = mSubjectView.findViewById(R.id.subject_text);
        mDeliveredBadge = findViewById(R.id.smsDeliveredBadge);
        mE2eeLockIcon = findViewById(R.id.e2ee_lock_icon);
        mMessageMetadataView = findViewById(R.id.message_metadata);
        mMessageTextAndInfoView = findViewById(R.id.message_text_and_info);
        mSimNameView = findViewById(R.id.sim_name);
        mSystemMessageView = findViewById(R.id.conversation_system_message);
        mReactionsContainer = findViewById(R.id.reactions_container);
        mLocationCard = findViewById(R.id.location_card);
        mLocationMap = findViewById(R.id.location_map);
        mLocationAddress = findViewById(R.id.location_address);
        mRbmCardView = findViewById(R.id.rbm_card);
        mRbmCardMediaFrame = findViewById(R.id.rbm_card_media_frame);
        mRbmCardMedia = findViewById(R.id.rbm_card_media);
        mRbmCardMediaPlay = findViewById(R.id.rbm_card_media_play);
        mRbmCardFile = findViewById(R.id.rbm_card_file);
        mRbmCardFileName = findViewById(R.id.rbm_card_file_name);
        mRbmCardTextCol = findViewById(R.id.rbm_card_textcol);
        mRbmCardTitle = findViewById(R.id.rbm_card_title);
        mRbmCardDescription = findViewById(R.id.rbm_card_description);
        mRbmSuggestionsContainer = findViewById(R.id.rbm_suggestions_container);
        mRbmCarousel = findViewById(R.id.rbm_carousel);
        mRbmCarouselStrip = findViewById(R.id.rbm_carousel_strip);
        mRbmCarouselDots = findViewById(R.id.rbm_carousel_dots);
        mRbmSelectedOption = findViewById(R.id.rbm_selected_option);
    }

    @Override
    protected void onDetachedFromWindow() {
        // Emoji-reactions: never leak the quick-row popup if the row is recycled
        // or the window goes away while the picker is showing.
        dismissReactionPicker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int horizontalSpace = MeasureSpec.getSize(widthMeasureSpec);

        // WAVE-B: a system status line occupies the full row width, centered.
        // Measure only the system view; the icon + bubble are GONE.
        if (mData.getIsRcsSystem()) {
            final int contentWidthSpec = MeasureSpec.makeMeasureSpec(
                    horizontalSpace - getPaddingStart() - getPaddingEnd(),
                    MeasureSpec.EXACTLY);
            final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            mSystemMessageView.measure(contentWidthSpec, unspecified);
            setMeasuredDimension(horizontalSpace,
                    mSystemMessageView.getMeasuredHeight() + getPaddingBottom() + getPaddingTop());
            return;
        }

        final int iconSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_message_contact_icon_size);

        final int unspecifiedMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int iconMeasureSpec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY);

        mContactIconView.measure(iconMeasureSpec, iconMeasureSpec);

        final int arrowWidth =
                getResources().getDimensionPixelSize(R.dimen.message_bubble_arrow_width);

        // We need to subtract contact icon width twice from the horizontal space to get
        // the max leftover space because we want the message bubble to extend no further than the
        // starting position of the message bubble in the opposite direction.
        final int maxLeftoverSpace = horizontalSpace - mContactIconView.getMeasuredWidth() * 2
                - arrowWidth - getPaddingStart() - getPaddingEnd();
        final int messageContentWidthMeasureSpec = MeasureSpec.makeMeasureSpec(maxLeftoverSpace,
                MeasureSpec.AT_MOST);

        mMessageBubble.measure(messageContentWidthMeasureSpec, unspecifiedMeasureSpec);

        final int maxHeight = Math.max(mContactIconView.getMeasuredHeight(),
                mMessageBubble.getMeasuredHeight());
        setMeasuredDimension(horizontalSpace, maxHeight + getPaddingBottom() + getPaddingTop());
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top, final int right,
            final int bottom) {
        final boolean isRtl = AccessibilityUtil.isLayoutRtl(this);

        // WAVE-B: lay out the centered system status line full-width.
        if (mData.getIsRcsSystem()) {
            final int contentLeft = getPaddingStart();
            final int contentTop = getPaddingTop();
            mSystemMessageView.layout(contentLeft, contentTop,
                    contentLeft + mSystemMessageView.getMeasuredWidth(),
                    contentTop + mSystemMessageView.getMeasuredHeight());
            return;
        }

        final int iconWidth = mContactIconView.getMeasuredWidth();
        final int iconHeight = mContactIconView.getMeasuredHeight();
        final int iconTop = getPaddingTop();
        final int contentWidth = (right -left) - iconWidth - getPaddingStart() - getPaddingEnd();
        final int contentHeight = mMessageBubble.getMeasuredHeight();
        final int contentTop = iconTop;

        final int iconLeft;
        final int contentLeft;
        if (mData.getIsIncoming()) {
            if (isRtl) {
                iconLeft = (right - left) - getPaddingEnd() - iconWidth;
                contentLeft = iconLeft - contentWidth;
            } else {
                iconLeft = getPaddingStart();
                contentLeft = iconLeft + iconWidth;
            }
        } else {
            if (isRtl) {
                iconLeft = getPaddingStart();
                contentLeft = iconLeft + iconWidth;
            } else {
                iconLeft = (right - left) - getPaddingEnd() - iconWidth;
                contentLeft = iconLeft - contentWidth;
            }
        }

        mContactIconView.layout(iconLeft, iconTop, iconLeft + iconWidth, iconTop + iconHeight);

        mMessageBubble.layout(contentLeft, contentTop, contentLeft + contentWidth,
                contentTop + contentHeight);
    }

    /**
     * Fills in the data associated with this view.
     *
     * @param cursor The cursor from a MessageList that this view is in, pointing to its entry.
     */
    public void bind(final Cursor cursor) {
        bind(cursor, true, null);
    }

    /**
     * Fills in the data associated with this view.
     *
     * @param cursor The cursor from a MessageList that this view is in, pointing to its entry.
     * @param oneOnOne Whether this is a 1:1 conversation
     */
    public void bind(final Cursor cursor, final boolean oneOnOne, final String selectedMessageId) {
        mOneOnOne = oneOnOne;

        // Update our UI model
        mData.bind(cursor);
        setSelected(TextUtils.equals(mData.getMessageId(), selectedMessageId));

        // WAVE-B: a group lifecycle event renders as a centered, muted status
        // line instead of a normal sender bubble. Take the dedicated path and
        // skip the entire bubble/avatar/metadata pipeline so it shares no state
        // with the message-bubble rendering (which is left untouched for every
        // normal SMS/MMS/RCS row).
        if (mData.getIsRcsSystem()) {
            bindSystemMessage();
            return;
        }
        setSystemMessageStyle(false /* system */);

        // Update text and image content for the view.
        updateViewContent();

        // Update colors and layout parameters for the view.
        updateViewAppearance();

        updateContentDescription();
    }

    /**
     * WAVE-B: render a group lifecycle event (created / member added / removed /
     * left / renamed) as a centered, muted, full-width status line — no contact
     * avatar, no bubble background, no sender name, not clustered, not laid out
     * as an incoming-or-outgoing bubble. Hides every normal-message view and
     * shows only {@link #mSystemMessageView}.
     */
    private void bindSystemMessage() {
        setSystemMessageStyle(true /* system */);
        final String text = mData.getText();
        mSystemMessageView.setText(text);
        setContentDescription(text);
        requestLayout();
    }

    /**
     * Toggle between the normal message-bubble view tree and the single centered
     * system-message line. When {@code system} is true the contact icon and the
     * whole message bubble are hidden and only {@link #mSystemMessageView} shows;
     * when false the system line is hidden and the normal views are restored to
     * VISIBLE so a recycled view never leaks the system styling onto a real
     * message.
     */
    private void setSystemMessageStyle(final boolean system) {
        mSystemMessageView.setVisibility(system ? View.VISIBLE : View.GONE);
        final int normalVisibility = system ? View.GONE : View.VISIBLE;
        mContactIconView.setVisibility(normalVisibility);
        mMessageBubble.setVisibility(normalVisibility);
    }

    public void setHost(final ConversationMessageViewHost host) {
        mHost = host;
    }

    /**
     * Sets a delay loader instance to manage loading / resuming of image attachments.
     */
    public void setImageViewDelayLoader(final AsyncImageViewDelayLoader delayLoader) {
        Assert.notNull(mMessageImageView);
        mMessageImageView.setDelayLoader(delayLoader);
        mMultiAttachmentView.setImageViewDelayLoader(delayLoader);
    }

    public ConversationMessageData getData() {
        return mData;
    }

    /**
     * Returns whether we should show simplified visual style for the message view (i.e. hide the
     * avatar and bubble arrow, reduce padding).
     */
    private boolean shouldShowSimplifiedVisualStyle() {
        return mData.getCanClusterWithPreviousMessage();
    }

    /**
     * Returns whether we need to show a message bubble for text content.
     */
    private boolean shouldShowMessageTextBubble() {
        if (mData.hasText()) {
            return true;
        }
        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        if (!TextUtils.isEmpty(subjectText)) {
            return true;
        }
        return false;
    }

    private void updateViewContent() {
        updateMessageContent();
        int titleResId = -1;
        int statusResId = -1;
        String statusText = null;
        switch(mData.getStatus()) {
            case MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING:
            case MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING:
            case MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD:
            case MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD:
                titleResId = R.string.message_title_downloading;
                statusResId = R.string.message_status_downloading;
                break;

            case MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_manual_download;
                    if (isSelected()) {
                        statusResId = R.string.message_status_download_action;
                    } else {
                        statusResId = R.string.message_status_download;
                    }
                }
                break;

            case MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_download_failed;
                    statusResId = R.string.message_status_download_error;
                }
                break;

            case MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_download_failed;
                    if (isSelected()) {
                        statusResId = R.string.message_status_download_action;
                    } else {
                        statusResId = R.string.message_status_download;
                    }
                }
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
            case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                statusResId = R.string.message_status_sending;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
            case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY:
                statusResId = R.string.message_status_send_retrying;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_FAILED_EMERGENCY_NUMBER:
                statusResId = R.string.message_status_send_failed_emergency_number;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_FAILED:
                // don't show the error state unless we're the default sms app
                if (PhoneUtils.getDefault().isDefaultSmsApp()) {
                    if (isSelected()) {
                        statusResId = R.string.message_status_resend;
                    } else {
                        statusResId = MmsUtils.mapRawStatusToErrorResourceId(
                                mData.getStatus(), mData.getRawTelephonyStatus());
                    }
                    break;
                }
                // FALL THROUGH HERE

            case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
            case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED:
            case MessageData.BUGLE_STATUS_INCOMING_COMPLETE:
            default:
                if (!mData.getCanClusterWithNextMessage()) {
                    statusText = getRcsReceiptStatusText();
                    if (statusText == null) {
                        statusText = mData.getFormattedReceivedTimeStamp();
                    }
                    // Last message of a transport run: append a small "· RCS" /
                    // "· SMS" sub-label (iPhone and Google Messages both show this). Purely
                    // additive — SMS-only threads keep today's plain timestamp.
                    statusText = appendTransportLabel(statusText);
                }
                break;
        }

        final boolean titleVisible = (titleResId >= 0);
        if (titleVisible) {
            final String titleText = getResources().getString(titleResId);
            mTitleTextView.setText(titleText);

            final String mmsInfoText = getResources().getString(
                    R.string.mms_info,
                    Formatter.formatFileSize(getContext(), mData.getSmsMessageSize()),
                    DateUtils.formatDateTime(
                            getContext(),
                            mData.getMmsExpiry(),
                            DateUtils.FORMAT_SHOW_DATE |
                            DateUtils.FORMAT_SHOW_TIME |
                            DateUtils.FORMAT_NUMERIC_DATE |
                            DateUtils.FORMAT_NO_YEAR));
            mMmsInfoTextView.setText(mmsInfoText);
            mMessageTitleLayout.setVisibility(View.VISIBLE);
        } else {
            mMessageTitleLayout.setVisibility(View.GONE);
        }

        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        final boolean subjectVisible = !TextUtils.isEmpty(subjectText);

        final boolean senderNameVisible = !mOneOnOne && !mData.getCanClusterWithNextMessage()
                && mData.getIsIncoming();
        if (senderNameVisible) {
            mSenderNameTextView.setText(mData.getSenderDisplayName());
            mSenderNameTextView.setVisibility(View.VISIBLE);
        } else {
            mSenderNameTextView.setVisibility(View.GONE);
        }

        if (statusResId >= 0) {
            statusText = getResources().getString(statusResId);
        }

        // We set the text even if the view will be GONE for accessibility
        mStatusTextView.setText(statusText);
        final boolean statusVisible = !TextUtils.isEmpty(statusText);
        if (statusVisible) {
            mStatusTextView.setVisibility(View.VISIBLE);
        } else {
            mStatusTextView.setVisibility(View.GONE);
        }

        // For RCS rows the Delivered/Read receipt is already conveyed by the status
        // text line (getRcsReceiptStatusText), so suppress the legacy SMS delivery
        // checkmark to avoid a redundant glyph. SMS/MMS rows are unaffected
        // (getIsRcs() is false for them) and keep the badge exactly as before.
        final boolean deliveredBadgeVisible =
                mData.getStatus() == MessageData.BUGLE_STATUS_OUTGOING_DELIVERED
                        && !mData.getIsRcs();
        mDeliveredBadge.setVisibility(deliveredBadgeVisible ? View.VISIBLE : View.GONE);

        // Per-message E2EE lock indicator: shown when the message arrived under an E2EE
        // scheme (opaque schemeId non-empty), scheme-blind.
        final boolean e2eeLockVisible = mData.isE2eeEncrypted();
        if (mE2eeLockIcon != null) {
            mE2eeLockIcon.setVisibility(e2eeLockVisible ? View.VISIBLE : View.GONE);
        }

        // Update the sim indicator.
        final boolean showSimIconAsIncoming = mData.getIsIncoming() &&
                (!mData.hasAttachments() || shouldShowMessageTextBubble());
        final SubscriptionListEntry subscriptionEntry =
                mHost.getSubscriptionEntryForSelfParticipant(mData.getSelfParticipantId(),
                        true /* excludeDefault */);
        final boolean simNameVisible = subscriptionEntry != null &&
                !TextUtils.isEmpty(subscriptionEntry.displayName) &&
                !mData.getCanClusterWithNextMessage();
        if (simNameVisible) {
            final String simNameText = mData.getIsIncoming() ? getResources().getString(
                    R.string.incoming_sim_name_text, subscriptionEntry.displayName) :
                        subscriptionEntry.displayName;
            mSimNameView.setText(simNameText);
            mSimNameView.setTextColor(showSimIconAsIncoming ? getResources().getColor(
                    R.color.timestamp_text_incoming, getContext().getTheme()) :
                    subscriptionEntry.displayColor);
            mSimNameView.setVisibility(VISIBLE);
        } else {
            mSimNameView.setText(null);
            mSimNameView.setVisibility(GONE);
        }

        final boolean metadataVisible = senderNameVisible || statusVisible
                || deliveredBadgeVisible || simNameVisible || e2eeLockVisible;
        mMessageMetadataView.setVisibility(metadataVisible ? View.VISIBLE : View.GONE);

        final boolean messageTextAndOrInfoVisible = titleVisible || subjectVisible
                || mData.hasText() || metadataVisible;
        mMessageTextAndInfoView.setVisibility(
                messageTextAndOrInfoVisible ? View.VISIBLE : View.GONE);

        if (shouldShowSimplifiedVisualStyle()) {
            mContactIconView.setVisibility(View.GONE);
            mContactIconView.setImageResourceUri(null);
        } else {
            mContactIconView.setVisibility(View.VISIBLE);
            final Uri avatarUri = AvatarUriUtil.createAvatarUri(
                    mData.getSenderProfilePhotoUri(),
                    mData.getSenderFullName(),
                    mData.getSenderNormalizedDestination(),
                    mData.getSenderContactLookupKey());
            mContactIconView.setImageResourceUri(avatarUri, mData.getSenderContactId(),
                    mData.getSenderContactLookupKey(), mData.getSenderNormalizedDestination());
        }
    }

    /**
     * Appends a small per-transport-run sub-label ("· RCS") to the run-terminal
     * status line, mirroring the iPhone / Google-Messages "transport tag under
     * the last message of a run" affordance.
     *
     * <p>Strictly additive: only RCS messages are tagged (with {@code · RCS}),
     * so an SMS-only thread renders exactly as before — its run-terminal rows
     * keep today's plain timestamp with no sub-label. In a mixed thread the RCS
     * runs are visibly marked, which is the whole point of the indicator. The
     * {@code rcs_thread_transport_rcs} string already carries its own leading
     * middot separator, so we join with a single space.
     *
     * @param baseStatus the already-computed status text (timestamp or receipt
     *        line); may be {@code null} / empty, in which case it is returned as-is.
     * @return the status text with the transport sub-label appended, or the
     *         unchanged input for non-RCS messages.
     */
    @Nullable
    private String appendTransportLabel(@Nullable final String baseStatus) {
        if (!mData.getIsRcs()) {
            // SMS / MMS: leave the status line exactly as it was.
            return baseStatus;
        }
        final String tag = getResources().getString(R.string.rcs_thread_transport_rcs);
        if (TextUtils.isEmpty(baseStatus)) {
            return tag;
        }
        return baseStatus + " " + tag;
    }

    /**
     * For an outgoing RCS message that has received an IMDN receipt, returns the
     * receipt-aware status line ("Read" / "Delivered", with the timestamp).
     * Returns {@code null} for any other message (incoming, non-RCS, or RCS
     * without a receipt yet), in which case the caller keeps the existing plain
     * timestamp. This is purely additive: non-RCS and unacknowledged messages are
     * rendered exactly as before.
     */
    @Nullable
    private String getRcsReceiptStatusText() {
        if (!mData.getIsRcs() || mData.getIsIncoming()) {
            return null;
        }
        final String timestamp = mData.getFormattedReceivedTimeStamp();

        // WAVE-E group aggregate. getRcsGroupMemberCount() (M) is > 0 ONLY for
        // a sent message in an RCS group conversation (the projection subquery
        // is gated on rcs_group_id IS NOT NULL); for a 1:1 RCS row it is 0, so
        // we fall through to the byte-unchanged single Delivered/Read path
        // below. M = other-member count (group size minus self).
        final int m = mData.getRcsGroupMemberCount();
        if (m > 0) {
            final int read = mData.getRcsGroupReadCount();
            final int delivered = mData.getRcsGroupDeliveredCount();
            if (read >= m) {
                // Everyone read it.
                return getResources().getString(
                        R.string.message_status_rcs_group_read_all, timestamp);
            }
            if (read > 0) {
                // Some read it: "Read by N of M".
                return getResources().getString(
                        R.string.message_status_rcs_group_read_partial, read, m);
            }
            if (delivered > 0) {
                // Delivered to at least one member, none read yet.
                return getResources().getString(
                        R.string.message_status_rcs_group_delivered, timestamp);
            }
            // No receipts yet -> fall back to the existing sending/sent text.
            return null;
        }

        // 1:1 path -- UNCHANGED.
        if (mData.getRcsDisplayedTimestamp() > 0) {
            return getResources().getString(R.string.message_status_rcs_read, timestamp);
        }
        if (mData.getRcsDeliveredTimestamp() > 0) {
            return getResources().getString(R.string.message_status_rcs_delivered, timestamp);
        }
        return null;
    }

    private void updateMessageContent() {
        // We must update the text before the attachments since we search the text to see if we
        // should make a preview youtube image in the attachments
        updateMessageText();
        updateMessageAttachments();
        updateMessageSubject();
        bindLocationCard();
        bindRbmCard();
        // A tapped suggestion-reply echo shows a "Selected option" sub-label.
        mRbmSelectedOption.setVisibility(
                mData.getIsBotPostbackEcho() ? View.VISIBLE : View.GONE);
        mMessageBubble.bind(mData);
        mMessageBubble.setReactionIncoming(mData.getIsIncoming());
        bindReactions();
    }

    /**
     * Emoji-reactions: render the per-emoji reaction chips on THIS message's
     * bubble from the aggregated data already on the cursor
     * ({@link ConversationMessageData#getReactionAggregates()}). One chip per
     * distinct emoji; the count is shown only when more than one reactor (group
     * aggregate). The own-reaction chip gets the accent background so re-tapping
     * to remove reads. Chip views are recycled across binds. When the message has
     * no reactions the whole container is GONE, so SMS/MMS and reaction-free RCS
     * rows are byte-unchanged.
     */
    private void bindReactions() {
        final java.util.List<ConversationMessageData.ReactionAggregate> reactions =
                mData.getReactionAggregates();
        if (reactions == null || reactions.isEmpty()) {
            mReactionsContainer.setVisibility(View.GONE);
            mReactionsContainer.removeAllViews();
            return;
        }

        // The container is wrap_content inside the bubble LinearLayout, whose
        // gravity (set in updateViewAppearance) already aligns it START for
        // incoming / END for outgoing -- so no per-row gravity is needed here.
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        // Recycle existing chip views; add/remove to match the aggregate count.
        while (mReactionsContainer.getChildCount() > reactions.size()) {
            mReactionsContainer.removeViewAt(mReactionsContainer.getChildCount() - 1);
        }
        for (int i = 0; i < reactions.size(); i++) {
            final ConversationMessageData.ReactionAggregate r = reactions.get(i);
            View chip = mReactionsContainer.getChildAt(i);
            if (chip == null) {
                chip = inflater.inflate(R.layout.reaction_chip, mReactionsContainer,
                        false /* attachToRoot */);
                mReactionsContainer.addView(chip);
            }
            final TextView emojiView = chip.findViewById(R.id.reaction_chip_emoji);
            final TextView countView = chip.findViewById(R.id.reaction_chip_count);
            emojiView.setText(r.emoji);
            if (r.count > 1) {
                countView.setText(String.valueOf(r.count));
                countView.setVisibility(View.VISIBLE);
                chip.setContentDescription(getResources().getString(
                        R.string.reaction_chip_content_description_group, r.count, r.emoji));
            } else {
                countView.setVisibility(View.GONE);
                chip.setContentDescription(getResources().getString(
                        R.string.reaction_chip_content_description, r.emoji));
            }
            chip.setBackgroundResource(r.reactedBySelf
                    ? R.drawable.reaction_chip_background_self
                    : R.drawable.reaction_chip_background);
            // Tapping the badge shows "who reacted with what". Adding / changing /
            // removing your own reaction is done from the long-press picker.
            chip.setOnClickListener(v -> {
                if (mHost != null) {
                    mHost.onReactionDetailsRequested(ConversationMessageView.this);
                }
            });
        }
        mReactionsContainer.setVisibility(View.VISIBLE);
    }

    /**
     * RBM: render an inbound bot rich card inside the bubble
     * (media on top, then title + description) and its suggestion chips below.
     * No-op (everything GONE) for every non-bot row and for a bot text/unknown
     * row, which renders as plain text via {@link #updateMessageText}.
     */
    /**
     * geopush: render an RCS location share as a card — an OSM
     * static-map thumbnail + an address line (reverse-geocoded, falling back to
     * lat/lon), tappable to the device's default map app via a {@code geo:} URI.
     * Hides the raw "📍 …maps-link" text when the card shows. GONE otherwise.
     */
    private void bindLocationCard() {
        if (mLocationCard == null) {
            return;
        }
        final com.android.messaging.datamodel.data.ConversationMessageData.GeoLoc geo =
                mData.getGeoLocation();
        if (geo == null) {
            mLocationCard.setVisibility(View.GONE);
            mLocationMap.setImageResourceId(null);
            return;
        }
        // The card supersedes the redundant "📍 …\nmaps-link" text body.
        mMessageTextView.setVisibility(View.GONE);
        mLocationCard.setVisibility(View.VISIBLE);

        final float density = getResources().getDisplayMetrics().density;
        final int w = (int) (240 * density);
        final int h = (int) (140 * density);
        // Clear any recycled tile, then build a point-centered OSM composite off
        // the main thread and load it from cache. The layout overlays the pin at
        // the frame center, which is exactly the point.
        mLocationMap.setImageResourceId(null);
        bindLocationMapAsync(geo.lat, geo.lon, w, h);

        // Address: a sender-supplied label wins; else show lat/lon now and try to
        // upgrade to a reverse-geocoded address asynchronously (timeout-bounded).
        if (geo.label != null) {
            mLocationAddress.setText(geo.label);
        } else {
            mLocationAddress.setText(com.android.messaging.rcs.RcsLocationUtil
                    .latLonText(geo.lat, geo.lon));
            bindLocationAddressAsync(geo.lat, geo.lon);
        }

        mLocationCard.setOnClickListener(v -> {
            try {
                getContext().startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(com.android.messaging.rcs.RcsLocationUtil
                                .geoUri(geo.lat, geo.lon, geo.label)))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (final android.content.ActivityNotFoundException e) {
                UiUtils.showToast(R.string.rcs_location_no_map_app);
            }
        });
    }

    /** Off-main: build the point-centered OSM tile composite and load it into the
     *  map view from cache, guarding against view recycling. On failure the frame
     *  stays empty (pin + address still carry the location). */
    private void bindLocationMapAsync(final double lat, final double lon,
            final int w, final int h) {
        final android.content.Context appCtx = getContext().getApplicationContext();
        com.android.messaging.rcs.OsmStaticMap.submit(() -> {
            final java.io.File mapFile = com.android.messaging.rcs.OsmStaticMap
                    .buildCenteredMap(appCtx, lat, lon, w, h);
            if (mapFile == null) {
                return;
            }
            post(() -> {
                final com.android.messaging.datamodel.data.ConversationMessageData.GeoLoc now =
                        (mData != null) ? mData.getGeoLocation() : null;
                if (now != null && now.lat == lat && now.lon == lon
                        && mLocationCard.getVisibility() == View.VISIBLE) {
                    mLocationMap.setImageResourceId(new com.android.messaging.datamodel.media
                            .UriImageRequestDescriptor(android.net.Uri.fromFile(mapFile), w, h));
                }
            });
        });
    }

    /** Off-main reverse-geocode (timeout-bounded); on success upgrade the address
     *  line, guarding against view recycling. */
    private void bindLocationAddressAsync(final double lat, final double lon) {
        final android.content.Context appCtx = getContext().getApplicationContext();
        new Thread(() -> {
            final String addr = com.android.messaging.rcs.RcsLocationUtil
                    .reverseGeocode(appCtx, lat, lon, 3500L);
            if (addr == null) {
                return;  // keep the lat/lon already shown
            }
            post(() -> {
                final com.android.messaging.datamodel.data.ConversationMessageData.GeoLoc now =
                        (mData != null) ? mData.getGeoLocation() : null;
                if (now != null && now.lat == lat && now.lon == lon
                        && mLocationCard.getVisibility() == View.VISIBLE) {
                    mLocationAddress.setText(addr);
                }
            });
        }, "geo-rev").start();
    }

    private void bindRbmCard() {
        final RbmBotMessage bot = mData.getIsBotMessage() ? mData.getRbmBotMessage() : null;
        if (bot == null) {
            hideRbm();
            return;
        }
        // A plain text message can carry its own suggestion chips (no card): the
        // text renders in the bubble (updateMessageText); show the chips below it.
        if (bot.kind == RbmBotMessage.Kind.TEXT && !bot.suggestions.isEmpty()) {
            mRbmCardView.setVisibility(View.GONE);
            mRbmCardMedia.setImageResourceId(null);
            mRbmCarousel.setVisibility(View.GONE);
            mRbmCarouselDots.setVisibility(View.GONE);
            bindSuggestionsInto(mRbmSuggestionsContainer, bot.suggestions);
            return;
        }
        if (bot.kind != RbmBotMessage.Kind.CARD || bot.cards.isEmpty()) {
            hideRbm();
            return;
        }

        // Carousel (Phase 6): >1 card renders as a horizontal strip instead of the
        // single in-bubble card; each card carries its own media + text + chips.
        if (bot.cards.size() > 1) {
            mRbmCardView.setVisibility(View.GONE);
            mRbmCardMedia.setImageResourceId(null);
            mRbmSuggestionsContainer.setVisibility(View.GONE);
            mRbmSuggestionsContainer.removeAllViews();
            bindCarousel(bot.cards);
            return;
        }

        // Single card.
        mRbmCarousel.setVisibility(View.GONE);
        final RbmCard card = bot.cards.get(0);

        // A HORIZONTAL standalone card lays media beside the text (only
        // meaningful when it actually has media). applyCardOrientation resets the
        // row + child layout params (the view is recycled).
        final boolean horizontal = card.hasMedia()
                && "HORIZONTAL".equalsIgnoreCase(card.orientation);
        applyCardOrientation(horizontal);

        final int iconSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_message_contact_icon_size);
        final int desiredWidth = horizontal
                ? getResources().getDimensionPixelSize(R.dimen.rbm_horizontal_media_size)
                : getResources().getDisplayMetrics().widthPixels - iconSize - iconSize;
        setRbmMedia(mRbmCardMediaFrame, mRbmCardMedia, mRbmCardMediaPlay,
                mRbmCardFile, mRbmCardFileName, card,
                desiredWidth, MessagePartData.UNSPECIFIED_SIZE);
        setRbmCardText(mRbmCardTitle, card.title);
        setRbmCardText(mRbmCardDescription, card.description);
        mRbmCardView.setVisibility(View.VISIBLE);

        bindSuggestionsInto(mRbmSuggestionsContainer, card.suggestions);
    }

    /** RBM: flip the single card between stacked (VERTICAL: media on
     *  top) and side-by-side (HORIZONTAL: fixed-width media left, text column
     *  right). Resets layout params each bind since the view is recycled. */
    private void applyCardOrientation(final boolean horizontal) {
        mRbmCardView.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        final int margin = getResources()
                .getDimensionPixelSize(R.dimen.rbm_card_media_bottom_margin);
        final LinearLayout.LayoutParams frameLp =
                (LinearLayout.LayoutParams) mRbmCardMediaFrame.getLayoutParams();
        final LinearLayout.LayoutParams colLp =
                (LinearLayout.LayoutParams) mRbmCardTextCol.getLayoutParams();
        if (horizontal) {
            frameLp.width = getResources()
                    .getDimensionPixelSize(R.dimen.rbm_horizontal_media_size);
            frameLp.bottomMargin = 0;
            frameLp.setMarginEnd(margin);
            colLp.width = 0;
            colLp.weight = 1f;
        } else {
            frameLp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            frameLp.bottomMargin = margin;
            frameLp.setMarginEnd(0);
            colLp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            colLp.weight = 0f;
        }
        mRbmCardMediaFrame.setLayoutParams(frameLp);
        mRbmCardTextCol.setLayoutParams(colLp);
    }

    /** RBM: hide every bot card/chip/carousel view (non-bot or non-card row). */
    private void hideRbm() {
        mRbmCardView.setVisibility(View.GONE);
        mRbmCardMedia.setImageResourceId(null);
        mRbmSuggestionsContainer.setVisibility(View.GONE);
        mRbmSuggestionsContainer.removeAllViews();
        mRbmCarousel.setVisibility(View.GONE);
        mRbmCarouselDots.setVisibility(View.GONE);
    }

    /** RBM (Phase 6): populate the horizontal carousel strip, one inflated card
     *  item per {@link RbmCard}, recycling item views across binds. */
    private void bindCarousel(final java.util.List<RbmCard> cards) {
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        while (mRbmCarouselStrip.getChildCount() > cards.size()) {
            mRbmCarouselStrip.removeViewAt(mRbmCarouselStrip.getChildCount() - 1);
        }
        final int mediaW = getResources().getDimensionPixelSize(R.dimen.rbm_carousel_card_width);
        final int mediaH = getResources().getDimensionPixelSize(R.dimen.rbm_carousel_media_height);
        for (int i = 0; i < cards.size(); i++) {
            View item = mRbmCarouselStrip.getChildAt(i);
            if (item == null) {
                item = inflater.inflate(R.layout.rbm_carousel_card, mRbmCarouselStrip,
                        false /* attachToRoot */);
                mRbmCarouselStrip.addView(item);
            }
            final RbmCard card = cards.get(i);
            setRbmMedia((FrameLayout) item.findViewById(R.id.rbm_cc_media_frame),
                    (AsyncImageView) item.findViewById(R.id.rbm_cc_media),
                    (ImageView) item.findViewById(R.id.rbm_cc_media_play),
                    null /* fileRow */, null /* fileName */, card, mediaW, mediaH);
            setRbmCardText((TextView) item.findViewById(R.id.rbm_cc_title), card.title);
            setRbmCardText((TextView) item.findViewById(R.id.rbm_cc_description), card.description);
            bindSuggestionsInto(
                    (com.android.messaging.ui.LineWrapLayout) item.findViewById(R.id.rbm_cc_suggestions),
                    card.suggestions);
            item.setVisibility(View.VISIBLE);
        }
        mRbmCarousel.setVisibility(View.VISIBLE);
        mRbmCarousel.scrollTo(0, 0);

        // Page dots so it's apparent the strip scrolls; the active dot tracks
        // scrollX (one card step = card width + its end margin).
        final int count = cards.size();
        bindCarouselDots(count);
        final int stepPx = getResources().getDimensionPixelSize(R.dimen.rbm_carousel_card_width)
                + getResources().getDimensionPixelSize(R.dimen.rbm_chip_spacing);
        mRbmCarousel.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            int idx = stepPx > 0 ? Math.round((float) sx / stepPx) : 0;
            idx = Math.max(0, Math.min(idx, count - 1));
            setActiveCarouselDot(idx);
        });
    }

    /** RBM (Phase 6): one page dot per carousel card; active dot is opaque, the
     *  rest dimmed. Recycled across binds. Hidden for a single card. */
    private void bindCarouselDots(final int count) {
        final int size = getResources().getDimensionPixelSize(R.dimen.rbm_dot_size);
        final int spacing = getResources().getDimensionPixelSize(R.dimen.rbm_dot_spacing);
        while (mRbmCarouselDots.getChildCount() > count) {
            mRbmCarouselDots.removeViewAt(mRbmCarouselDots.getChildCount() - 1);
        }
        for (int i = 0; i < count; i++) {
            View dot = mRbmCarouselDots.getChildAt(i);
            if (dot == null) {
                dot = new View(getContext());
                dot.setBackgroundResource(R.drawable.rbm_carousel_dot);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginStart(spacing);
                lp.setMarginEnd(spacing);
                mRbmCarouselDots.addView(dot, lp);
            }
            dot.setVisibility(View.VISIBLE);
        }
        setActiveCarouselDot(0);
        mRbmCarouselDots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
    }

    private void setActiveCarouselDot(final int active) {
        for (int i = 0; i < mRbmCarouselDots.getChildCount(); i++) {
            mRbmCarouselDots.getChildAt(i).setAlpha(i == active ? 1f : 0.3f);
        }
    }

    /** RBM media affordance. */
    private enum RbmMediaKind { NONE, IMAGE, VIDEO, FILE }

    /**
     * RBM: render a card's media per its detected kind — an image, a video
     * thumbnail with a centered play badge, or (non-image/video) a file row (icon
     * + derived name). {@code fileRow}/{@code fileName} may be null (carousel: a
     * pure-file card just hides its media). Network urls route through
     * NetworkUriImageRequest. Thumbnail is preferred over the file url for the
     * loaded bitmap.
     */
    private void setRbmMedia(final FrameLayout mediaFrame, final AsyncImageView media,
            final ImageView playOverlay, final View fileRow, final TextView fileName,
            final RbmCard card, final int width, final int height) {
        final RbmMediaKind kind = rbmMediaKind(card);

        if (kind == RbmMediaKind.FILE && fileRow != null) {
            media.setImageResourceId(null);
            if (mediaFrame != null) mediaFrame.setVisibility(View.GONE);
            if (playOverlay != null) playOverlay.setVisibility(View.GONE);
            if (fileName != null) fileName.setText(rbmFileName(card.mediaUrl));
            fileRow.setVisibility(View.VISIBLE);
            return;
        }
        if (fileRow != null) fileRow.setVisibility(View.GONE);

        if (kind == RbmMediaKind.NONE || kind == RbmMediaKind.FILE) {
            // No renderable media (or a file with nowhere to show it).
            media.setImageResourceId(null);
            if (mediaFrame != null) mediaFrame.setVisibility(View.GONE);
            else media.setVisibility(View.GONE);
            if (playOverlay != null) playOverlay.setVisibility(View.GONE);
            return;
        }

        final String url = !TextUtils.isEmpty(card.mediaThumbnailUrl)
                ? card.mediaThumbnailUrl : card.mediaUrl;
        media.setImageResourceId(new UriImageRequestDescriptor(Uri.parse(url), width, height));
        if (!TextUtils.isEmpty(card.mediaAltText)) {
            media.setContentDescription(card.mediaAltText);
        }
        media.setVisibility(View.VISIBLE);
        if (mediaFrame != null) mediaFrame.setVisibility(View.VISIBLE);
        if (playOverlay != null) {
            playOverlay.setVisibility(kind == RbmMediaKind.VIDEO ? View.VISIBLE : View.GONE);
        }
    }

    /** Classify card media from content-type / url extension / thumbnail presence.
     *  Cards are visual, so an ambiguous url defaults to IMAGE. */
    private static RbmMediaKind rbmMediaKind(final RbmCard card) {
        final boolean hasThumb = !TextUtils.isEmpty(card.mediaThumbnailUrl);
        final String url = card.mediaUrl;
        if (TextUtils.isEmpty(url) && !hasThumb) {
            return RbmMediaKind.NONE;
        }
        final String ct = card.mediaContentType != null
                ? card.mediaContentType.toLowerCase() : "";
        if (ct.startsWith("video/")
                || extIn(url, "mp4", "mov", "webm", "3gp", "m4v", "mkv")) {
            return RbmMediaKind.VIDEO;
        }
        if (ct.startsWith("image/") || hasThumb
                || extIn(url, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")) {
            return RbmMediaKind.IMAGE;
        }
        // A non-image/video content-type, or a url with a (non-media) extension.
        if (!ct.isEmpty() || hasExtension(url)) {
            return RbmMediaKind.FILE;
        }
        return RbmMediaKind.IMAGE;
    }

    private static String stripQuery(final String u) {
        final int q = u.indexOf('?');
        return q >= 0 ? u.substring(0, q) : u;
    }

    private static boolean hasExtension(final String url) {
        if (TextUtils.isEmpty(url)) return false;
        final String s = stripQuery(url);
        final int dot = s.lastIndexOf('.');
        final int slash = s.lastIndexOf('/');
        return dot > slash && dot < s.length() - 1;
    }

    private static boolean extIn(final String url, final String... exts) {
        if (TextUtils.isEmpty(url)) return false;
        final String s = stripQuery(url).toLowerCase();
        final int dot = s.lastIndexOf('.');
        if (dot < 0) return false;
        final String e = s.substring(dot + 1);
        for (final String x : exts) {
            if (e.equals(x)) return true;
        }
        return false;
    }

    /** Last path segment of a media url, for the file-attachment row label. */
    private static String rbmFileName(final String url) {
        if (TextUtils.isEmpty(url)) return "";
        final String s = stripQuery(url);
        final int slash = s.lastIndexOf('/');
        final String name = slash >= 0 ? s.substring(slash + 1) : s;
        return TextUtils.isEmpty(name) ? url : name;
    }

    private static void setRbmCardText(final TextView view, final String text) {
        if (TextUtils.isEmpty(text)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }

    /**
     * RBM (Phase 4/6): render tappable suggestion chips into {@code container},
     * recycling chip views across binds (mirrors {@link #bindReactions}). Used for
     * a single card's chips (below the bubble) and for each carousel card's own
     * chips. The tap routes to the host (Phase 5: postback / Phase 6: action intent).
     */
    private void bindSuggestionsInto(final com.android.messaging.ui.LineWrapLayout container,
            final java.util.List<RbmSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            container.setVisibility(View.GONE);
            container.removeAllViews();
            return;
        }
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        while (container.getChildCount() > suggestions.size()) {
            container.removeViewAt(container.getChildCount() - 1);
        }
        for (int i = 0; i < suggestions.size(); i++) {
            final RbmSuggestion s = suggestions.get(i);
            TextView chip = (TextView) container.getChildAt(i);
            if (chip == null) {
                chip = (TextView) inflater.inflate(R.layout.rbm_suggestion_chip,
                        container, false /* attachToRoot */);
                container.addView(chip);
            }
            chip.setText(s.displayText);
            chip.setContentDescription(s.displayText);
            chip.setOnClickListener(v -> onRbmSuggestionTapped(s));
        }
        container.setVisibility(View.VISIBLE);
    }

    /**
     * RBM (Phase 5): handle a suggestion-chip tap — route to the host, which owns
     * the transport and sends the {@code botsuggestion.response} postback to the
     * bot. (Open-url / dial actions are Phase 6; the test agent emits only reply
     * chips today.)
     */
    private void onRbmSuggestionTapped(final RbmSuggestion s) {
        if (mHost != null) {
            mHost.onBotSuggestionTapped(this, s);
        }
    }

    /**
     * Emoji-reactions: show the long-press quick-row reaction picker anchored just
     * above this message's bubble. Builds the 11 {@link #REACTION_QUICK_SET} glyph
     * buttons plus the "+" (system picker) button into a {@link PopupWindow}.
     * Tapping a glyph sends that reaction (add) and dismisses; the "+" delegates to
     * the host's system-emoji-picker. The popup auto-dismisses on outside touch so
     * it coexists with the existing select-CAB. No-op for a system row or before a
     * host is attached.
     */
    private void showReactionPicker() {
        if (mHost == null || mData.getIsRcsSystem()) {
            return;
        }
        dismissReactionPicker();
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final View content = inflater.inflate(R.layout.reaction_picker_row, this, false);
        final LinearLayout row = content.findViewById(R.id.reaction_picker_buttons);
        final View plus = content.findViewById(R.id.reaction_picker_plus);

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                true /* focusable */);
        popup.setOutsideTouchable(true);
        mReactionPicker = popup;

        final int buttonSize = getResources()
                .getDimensionPixelSize(R.dimen.reaction_picker_button_size);
        final float emojiTextSize = getResources()
                .getDimension(R.dimen.reaction_picker_emoji_text_size);
        // Insert the 11 glyph buttons BEFORE the trailing "+" (index of plus).
        final int plusIndex = row.indexOfChild(plus);
        // Our current reaction (if any) is shown selected so the picker doubles as
        // the un-send / change affordance: re-tapping it removes (deselect); tapping
        // a different glyph changes it.
        final String selfEmoji = mData.getSelfReactionEmoji();
        for (int i = 0; i < REACTION_QUICK_SET.length; i++) {
            final String emoji = REACTION_QUICK_SET[i];
            final boolean isSelf = emoji.equals(selfEmoji);
            final TextView btn = new TextView(getContext());
            btn.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
            btn.setGravity(Gravity.CENTER);
            btn.setText(emoji);
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, emojiTextSize);
            btn.setClickable(true);
            btn.setFocusable(true);
            if (isSelf) {
                btn.setBackgroundResource(R.drawable.reaction_picker_button_selected);
                btn.setContentDescription(getResources().getString(
                        R.string.reaction_picker_selected_content_description, emoji));
            } else {
                btn.setContentDescription(emoji);
            }
            btn.setOnClickListener(v -> {
                dismissReactionPicker();
                if (mHost != null) {
                    // Same glyph as our current reaction -> remove (deselect). A
                    // different glyph -> add, which REPLACES any prior self reaction
                    // (store + wire upsert is keyed by target + reactor).
                    mHost.onReactionSelected(ConversationMessageView.this, emoji, !isSelf);
                }
            });
            row.addView(btn, plusIndex + i);
        }
        plus.setOnClickListener(v -> {
            dismissReactionPicker();
            if (mHost != null) {
                mHost.onOpenSystemEmojiPicker(ConversationMessageView.this);
            }
        });

        // Anchor above the bubble, START-aligned to it, clamped on-screen by the
        // PopupWindow. A small upward offset so the row floats over the bubble top.
        final int yOffset = -(buttonSize + getResources().getDimensionPixelSize(
                R.dimen.reaction_picker_padding) * 2);
        popup.showAsDropDown(mMessageBubble, 0, yOffset, Gravity.START);
    }

    /** Dismiss the reaction quick-row popup if it's showing. */
    private void dismissReactionPicker() {
        if (mReactionPicker != null) {
            try {
                mReactionPicker.dismiss();
            } catch (final Throwable ignored) {
            }
            mReactionPicker = null;
        }
    }

    private void updateMessageAttachments() {
        // Bind video, audio, and VCard attachments. If there are multiple, they stack vertically.
        bindAttachmentsOfSameType(sVideoFilter,
                R.layout.message_video_attachment, mVideoViewBinder, VideoThumbnailView.class);
        bindAttachmentsOfSameType(sAudioFilter,
                R.layout.message_audio_attachment, mAudioViewBinder, AudioAttachmentView.class);
        bindAttachmentsOfSameType(sVCardFilter,
                R.layout.message_vcard_attachment, mVCardViewBinder, PersonItemView.class);

        // Bind image attachments. If there are multiple, they are shown in a collage view.
        final List<MessagePartData> imageParts = mData.getAttachments(sImageFilter);
        if (imageParts.size() > 1) {
            imageParts.sort(sImageComparator);
            mMultiAttachmentView.bindAttachments(imageParts, null, imageParts.size());
            mMultiAttachmentView.setVisibility(View.VISIBLE);
        } else {
            mMultiAttachmentView.setVisibility(View.GONE);
        }

        // In the case that we have no image attachments and exactly one youtube link in a message
        // then we will show a preview.
        String youtubeThumbnailUrl = null;
        String originalYoutubeLink = null;
        if (mMessageTextHasLinks && imageParts.size() == 0) {
            CharSequence messageTextWithSpans = mMessageTextView.getText();
            final URLSpan[] spans = ((Spanned) messageTextWithSpans).getSpans(0,
                    messageTextWithSpans.length(), URLSpan.class);
            for (URLSpan span : spans) {
                String url = span.getURL();
                String youtubeLinkForUrl = YouTubeUtil.getYoutubePreviewImageLink(url);
                if (!TextUtils.isEmpty(youtubeLinkForUrl)) {
                    if (TextUtils.isEmpty(youtubeThumbnailUrl)) {
                        // Save the youtube link if we don't already have one
                        youtubeThumbnailUrl = youtubeLinkForUrl;
                        originalYoutubeLink = url;
                    } else {
                        // We already have a youtube link. This means we have two youtube links so
                        // we shall show none.
                        youtubeThumbnailUrl = null;
                        originalYoutubeLink = null;
                        break;
                    }
                }
            }
        }
        // We need to keep track if we have a youtube link in the message so that we will not show
        // the arrow
        mMessageHasYouTubeLink = !TextUtils.isEmpty(youtubeThumbnailUrl);

        // We will show the message image view if there is one attachment or one youtube link
        if (imageParts.size() == 1 || mMessageHasYouTubeLink) {
            // Get the display metrics for a hint for how large to pull the image data into
            final WindowManager windowManager = (WindowManager) getContext().
                    getSystemService(Context.WINDOW_SERVICE);
            final DisplayMetrics displayMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);

            final int iconSize = getResources()
                    .getDimensionPixelSize(R.dimen.conversation_message_contact_icon_size);
            final int desiredWidth = displayMetrics.widthPixels - iconSize - iconSize;

            if (imageParts.size() == 1) {
                final MessagePartData imagePart = imageParts.get(0);
                // If the image is big, we want to scale it down to save memory since we're going to
                // scale it down to fit into the bubble width. We don't constrain the height.
                final ImageRequestDescriptor imageRequest =
                        new MessagePartImageRequestDescriptor(imagePart,
                                desiredWidth,
                                MessagePartData.UNSPECIFIED_SIZE,
                                false);
                adjustImageViewBounds(imagePart);
                mMessageImageView.setImageResourceId(imageRequest);
                mMessageImageView.setTag(imagePart);
            } else {
                // Youtube Thumbnail image
                final ImageRequestDescriptor imageRequest =
                        new UriImageRequestDescriptor(Uri.parse(youtubeThumbnailUrl), desiredWidth,
                            MessagePartData.UNSPECIFIED_SIZE, true /* allowCompression */,
                            true /* isStatic */, false /* cropToCircle */,
                            ImageUtils.DEFAULT_CIRCLE_BACKGROUND_COLOR /* circleBackgroundColor */,
                            ImageUtils.DEFAULT_CIRCLE_STROKE_COLOR /* circleStrokeColor */);
                mMessageImageView.setImageResourceId(imageRequest);
                mMessageImageView.setTag(originalYoutubeLink);
            }
            mMessageImageView.setVisibility(View.VISIBLE);
        } else {
            mMessageImageView.setImageResourceId(null);
            mMessageImageView.setVisibility(View.GONE);
        }

        // Show the message attachments container if any of its children are visible
        boolean attachmentsVisible = false;
        for (int i = 0, size = mMessageAttachmentsView.getChildCount(); i < size; i++) {
            final View attachmentView = mMessageAttachmentsView.getChildAt(i);
            if (attachmentView.getVisibility() == View.VISIBLE) {
                attachmentsVisible = true;
                break;
            }
        }
        mMessageAttachmentsView.setVisibility(attachmentsVisible ? View.VISIBLE : View.GONE);
    }

    private void bindAttachmentsOfSameType(final Predicate<MessagePartData> attachmentTypeFilter,
            final int attachmentViewLayoutRes, final AttachmentViewBinder viewBinder,
            final Class<?> attachmentViewClass) {
        final LayoutInflater layoutInflater = LayoutInflater.from(getContext());

        // Iterate through all attachments of a particular type (video, audio, etc).
        // Find the first attachment index that matches the given type if possible.
        int attachmentViewIndex = -1;
        View existingAttachmentView;
        do {
            existingAttachmentView = mMessageAttachmentsView.getChildAt(++attachmentViewIndex);
        } while (existingAttachmentView != null &&
                !(attachmentViewClass.isInstance(existingAttachmentView)));

        for (final MessagePartData attachment : mData.getAttachments(attachmentTypeFilter)) {
            View attachmentView = mMessageAttachmentsView.getChildAt(attachmentViewIndex);
            if (!attachmentViewClass.isInstance(attachmentView)) {
                attachmentView = layoutInflater.inflate(attachmentViewLayoutRes,
                        mMessageAttachmentsView, false /* attachToRoot */);
                attachmentView.setOnClickListener(this);
                attachmentView.setOnLongClickListener(this);
                mMessageAttachmentsView.addView(attachmentView, attachmentViewIndex);
            }
            viewBinder.bindView(attachmentView, attachment);
            attachmentView.setTag(attachment);
            attachmentView.setVisibility(View.VISIBLE);
            attachmentViewIndex++;
        }
        // If there are unused views left over, unbind or remove them.
        while (attachmentViewIndex < mMessageAttachmentsView.getChildCount()) {
            final View attachmentView = mMessageAttachmentsView.getChildAt(attachmentViewIndex);
            if (attachmentViewClass.isInstance(attachmentView)) {
                mMessageAttachmentsView.removeViewAt(attachmentViewIndex);
            } else {
                // No more views of this type; we're done.
                break;
            }
        }
    }

    private void updateMessageSubject() {
        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        final boolean subjectVisible = !TextUtils.isEmpty(subjectText);

        if (subjectVisible) {
            mSubjectText.setText(subjectText);
            mSubjectView.setVisibility(View.VISIBLE);
        } else {
            mSubjectView.setVisibility(View.GONE);
        }
    }

    private void updateMessageText() {
        String text = mData.getText();
        // RBM: a bot row stores the raw GSMA botmessage JSON
        // as its body. Show the PARSED agent text for a text message, and nothing
        // for a card (its body renders in bindRbmCard). Fall back to the raw text
        // only for an unrecognized payload (a plain agent line, or a shape we
        // don't model yet -- refined in Phase 6).
        final RbmBotMessage bot = mData.getIsBotMessage() ? mData.getRbmBotMessage() : null;
        if (bot != null) {
            if (bot.kind == RbmBotMessage.Kind.CARD) {
                text = null;
            } else if (bot.kind == RbmBotMessage.Kind.TEXT) {
                text = bot.text;
            }
        }
        if (!TextUtils.isEmpty(text)) {
            mMessageTextView.setText(text);
            // Linkify phone numbers, web urls, emails, and map addresses to allow users to
            // click on them and take the default intent.
            mMessageTextHasLinks = LinkifyHelper.addLinks(mMessageTextView);
            mMessageTextView.setVisibility(View.VISIBLE);
        } else {
            mMessageTextView.setVisibility(View.GONE);
            mMessageTextHasLinks = false;
        }
    }

    /**
     * Picks the message-bubble background for the current message. RCS messages
     * get a distinct accent-tinted bubble; everything else (SMS/MMS) keeps the
     * existing contact-keyed bubble, so non-RCS appearance is unchanged.
     */
    private Drawable getBubbleDrawableForData(final ConversationDrawables drawableProvider,
            final boolean incoming) {
        if (mData.getIsRcs()) {
            return drawableProvider.getRcsBubbleDrawable(
                    isSelected(),
                    incoming,
                    mData.hasIncomingErrorStatus());
        }
        return drawableProvider.getBubbleDrawable(
                isSelected(),
                incoming,
                mData.hasIncomingErrorStatus(),
                mData.getSenderContactLookupKey());
    }

    private void updateViewAppearance() {
        final Resources res = getResources();
        final ConversationDrawables drawableProvider = ConversationDrawables.get();
        final boolean incoming = mData.getIsIncoming();
        final boolean outgoing = !incoming;

        final int messageTopPaddingClustered =
                res.getDimensionPixelSize(R.dimen.message_padding_same_author);
        final int messageTopPaddingDefault =
                res.getDimensionPixelSize(R.dimen.message_padding_default);
        final int arrowWidth = res.getDimensionPixelOffset(R.dimen.message_bubble_arrow_width);
        final int messageTextMinHeightDefault = res.getDimensionPixelSize(
                R.dimen.conversation_message_contact_icon_size);
        final int messageTextLeftRightPadding = res.getDimensionPixelOffset(
                R.dimen.message_text_left_right_padding);
        final int textTopPaddingDefault = res.getDimensionPixelOffset(
                R.dimen.message_text_top_padding);
        final int textBottomPaddingDefault = res.getDimensionPixelOffset(
                R.dimen.message_text_bottom_padding);

        // These values depend on whether the message has text, attachments, or both.
        // We intentionally don't set defaults, so the compiler will tell us if we forget
        // to set one of them, or if we set one more than once.
        final int contentLeftPadding, contentRightPadding;
        final Drawable textBackground;
        final int textMinHeight;
        final int textTopMargin;
        final int textTopPadding, textBottomPadding;
        final int textLeftPadding, textRightPadding;

        if (mData.hasAttachments()) {
            if (shouldShowMessageTextBubble()) {
                // Text and attachment(s)
                contentLeftPadding = incoming ? arrowWidth : 0;
                contentRightPadding = outgoing ? arrowWidth : 0;
                textBackground = getBubbleDrawableForData(drawableProvider, incoming);
                textMinHeight = messageTextMinHeightDefault;
                textTopMargin = messageTopPaddingClustered;
                textTopPadding = textTopPaddingDefault;
                textBottomPadding = textBottomPaddingDefault;
                textLeftPadding = messageTextLeftRightPadding;
                textRightPadding = messageTextLeftRightPadding;
                mMessageTextView.setTextIsSelectable(isSelected());
            } else {
                // Attachment(s) only
                contentLeftPadding = incoming ? arrowWidth : 0;
                contentRightPadding = outgoing ? arrowWidth : 0;
                textBackground = null;
                textMinHeight = 0;
                textTopMargin = 0;
                textTopPadding = 0;
                textBottomPadding = 0;
                textLeftPadding = 0;
                textRightPadding = 0;
            }
        } else {
            // Text only
            contentLeftPadding = incoming ? arrowWidth : 0;
            contentRightPadding = outgoing ? arrowWidth : 0;
            textBackground = getBubbleDrawableForData(drawableProvider, incoming);
            textMinHeight = messageTextMinHeightDefault;
            textTopMargin = 0;
            textTopPadding = textTopPaddingDefault;
            textBottomPadding = textBottomPaddingDefault;
            mMessageTextView.setTextIsSelectable(isSelected());
            textLeftPadding = messageTextLeftRightPadding;
            textRightPadding = messageTextLeftRightPadding;
        }

        // These values do not depend on whether the message includes attachments
        final int gravity = incoming ? (Gravity.START | Gravity.CENTER_VERTICAL) :
                (Gravity.END | Gravity.CENTER_VERTICAL);
        final int messageTopPadding = shouldShowSimplifiedVisualStyle() ?
                messageTopPaddingClustered : messageTopPaddingDefault;
        final int metadataTopPadding = res.getDimensionPixelOffset(
                R.dimen.message_metadata_top_padding);

        // Update the message text/info views
        mMessageTextAndInfoView.setBackground(textBackground);
        mMessageTextAndInfoView.setMinimumHeight(textMinHeight);
        final LinearLayout.LayoutParams textAndInfoLayoutParams =
                (LinearLayout.LayoutParams) mMessageTextAndInfoView.getLayoutParams();
        textAndInfoLayoutParams.topMargin = textTopMargin;

        if (UiUtils.isRtlMode()) {
            // Need to switch right and left padding in RtL mode
            mMessageTextAndInfoView.setPadding(textRightPadding, textTopPadding, textLeftPadding,
                    textBottomPadding);
            mMessageBubble.setPadding(contentRightPadding, 0, contentLeftPadding, 0);
        } else {
            mMessageTextAndInfoView.setPadding(textLeftPadding, textTopPadding, textRightPadding,
                    textBottomPadding);
            mMessageBubble.setPadding(contentLeftPadding, 0, contentRightPadding, 0);
        }

        // Update the message row and message bubble views
        setPadding(getPaddingStart(), messageTopPadding, getPaddingEnd(), 0);
        mMessageBubble.setGravity(gravity);
        updateMessageAttachmentsAppearance(gravity);

        mMessageMetadataView.setPadding(0, metadataTopPadding, 0, 0);

        updateTextAppearance();

        requestLayout();
    }

    private void updateContentDescription() {
        StringBuilder description = new StringBuilder();

        Resources res = getResources();
        String separator = res.getString(R.string.enumeration_comma);

        // Sender information
        boolean hasPlainTextMessage = !(TextUtils.isEmpty(mData.getText()) ||
                mMessageTextHasLinks);
        if (mData.getIsIncoming()) {
            int senderResId = hasPlainTextMessage
                ? R.string.incoming_text_sender_content_description
                : R.string.incoming_sender_content_description;
            description.append(res.getString(senderResId, mData.getSenderDisplayName()));
        } else {
            int senderResId = hasPlainTextMessage
                ? R.string.outgoing_text_sender_content_description
                : R.string.outgoing_sender_content_description;
            description.append(res.getString(senderResId));
        }

        if (mSubjectView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mSubjectText.getText());
        }

        if (mMessageTextView.getVisibility() == View.VISIBLE) {
            // If the message has hyperlinks, we will let the user navigate to the text message so
            // that the hyperlink can be clicked. Otherwise, the text message does not need to
            // be reachable.
            if (mMessageTextHasLinks) {
                mMessageTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            } else {
                mMessageTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                description.append(separator);
                description.append(mMessageTextView.getText());
            }
        }

        if (mMessageTitleLayout.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mTitleTextView.getText());

            description.append(separator);
            description.append(mMmsInfoTextView.getText());
        }

        if (mStatusTextView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mStatusTextView.getText());
        }

        if (mSimNameView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mSimNameView.getText());
        }

        if (mDeliveredBadge.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(res.getString(R.string.delivered_status_content_description));
        }

        setContentDescription(description);
    }

    private void updateMessageAttachmentsAppearance(final int gravity) {
        mMessageAttachmentsView.setGravity(gravity);

        // Tint image/video attachments when selected
        final int selectedImageTint = getResources().getColor(R.color.message_image_selected_tint,
                getContext().getTheme());
        if (mMessageImageView.getVisibility() == View.VISIBLE) {
            if (isSelected()) {
                mMessageImageView.setColorFilter(selectedImageTint);
            } else {
                mMessageImageView.clearColorFilter();
            }
        }
        if (mMultiAttachmentView.getVisibility() == View.VISIBLE) {
            if (isSelected()) {
                mMultiAttachmentView.setColorFilter(selectedImageTint);
            } else {
                mMultiAttachmentView.clearColorFilter();
            }
        }
        for (int i = 0, size = mMessageAttachmentsView.getChildCount(); i < size; i++) {
            final View attachmentView = mMessageAttachmentsView.getChildAt(i);
            if (attachmentView instanceof VideoThumbnailView
                    && attachmentView.getVisibility() == View.VISIBLE) {
                final VideoThumbnailView videoView = (VideoThumbnailView) attachmentView;
                if (isSelected()) {
                    videoView.setColorFilter(selectedImageTint);
                } else {
                    videoView.clearColorFilter();
                }
            }
        }

        // If there are multiple attachment bubbles in a single message, add some separation.
        final int multipleAttachmentPadding =
                getResources().getDimensionPixelSize(R.dimen.message_padding_same_author);

        boolean previousVisibleView = false;
        for (int i = 0, size = mMessageAttachmentsView.getChildCount(); i < size; i++) {
            final View attachmentView = mMessageAttachmentsView.getChildAt(i);
            if (attachmentView.getVisibility() == View.VISIBLE) {
                final int margin = previousVisibleView ? multipleAttachmentPadding : 0;
                ((LinearLayout.LayoutParams) attachmentView.getLayoutParams()).topMargin = margin;
                // updateViewAppearance calls requestLayout() at the end, so we don't need to here
                previousVisibleView = true;
            }
        }
    }

    private void updateTextAppearance() {
        int messageColorResId;
        int statusColorResId;
        int infoColorResId;
        int timestampColorResId;
        int subjectLabelColorResId;
        if (isSelected()) {
            messageColorResId = R.color.message_text_color_incoming;
            statusColorResId = R.color.message_action_status_text;
            infoColorResId = R.color.message_action_info_text;
            if (shouldShowMessageTextBubble()) {
                timestampColorResId = R.color.message_action_timestamp_text;
                subjectLabelColorResId = R.color.message_action_timestamp_text;
            } else {
                // If there's no text, the timestamp will be shown below the attachments,
                // against the conversation view background.
                timestampColorResId = R.color.timestamp_text_outgoing;
                subjectLabelColorResId = R.color.timestamp_text_outgoing;
            }
        } else {
            messageColorResId = (mData.getIsIncoming() ?
                    R.color.message_text_color_incoming : R.color.message_text_color_outgoing);
            statusColorResId = messageColorResId;
            infoColorResId = R.color.timestamp_text_incoming;
            switch(mData.getStatus()) {

                case MessageData.BUGLE_STATUS_OUTGOING_FAILED:
                case MessageData.BUGLE_STATUS_OUTGOING_FAILED_EMERGENCY_NUMBER:
                    timestampColorResId = R.color.message_failed_timestamp_text;
                    subjectLabelColorResId = R.color.timestamp_text_outgoing;
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
                case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
                case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY:
                case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
                case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED:
                    timestampColorResId = R.color.timestamp_text_outgoing;
                    subjectLabelColorResId = R.color.timestamp_text_outgoing;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE:
                case MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED:
                    messageColorResId = R.color.message_text_color_incoming_download_failed;
                    timestampColorResId = R.color.message_download_failed_timestamp_text;
                    subjectLabelColorResId = R.color.message_text_color_incoming_download_failed;
                    statusColorResId = R.color.message_download_failed_status_text;
                    infoColorResId = R.color.message_info_text_incoming_download_failed;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD:
                case MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD:
                    timestampColorResId = R.color.message_text_color_incoming;
                    subjectLabelColorResId = R.color.message_text_color_incoming;
                    infoColorResId = R.color.timestamp_text_incoming;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_COMPLETE:
                default:
                    timestampColorResId = R.color.timestamp_text_incoming;
                    subjectLabelColorResId = R.color.timestamp_text_incoming;
                    infoColorResId = -1; // Not used
                    break;
            }
        }
        final Resources.Theme theme = getContext().getTheme();
        final int messageColor = getResources().getColor(messageColorResId, theme);
        mMessageTextView.setTextColor(messageColor);
        mMessageTextView.setLinkTextColor(messageColor);
        mSubjectText.setTextColor(messageColor);
        // geopush location card: the address line shares the bubble, so it
        // must use the same incoming/outgoing-aware color — otherwise it renders
        // dark-on-dark on the incoming (dark) bubble.
        if (mLocationAddress != null) {
            mLocationAddress.setTextColor(messageColor);
        }
        if (statusColorResId >= 0) {
            mTitleTextView.setTextColor(getResources().getColor(statusColorResId, theme));
        }
        if (infoColorResId >= 0) {
            mMmsInfoTextView.setTextColor(getResources().getColor(infoColorResId, theme));
        }
        if (timestampColorResId == R.color.timestamp_text_incoming &&
                mData.hasAttachments() && !shouldShowMessageTextBubble()) {
            timestampColorResId = R.color.timestamp_text_outgoing;
        }
        mStatusTextView.setTextColor(getResources().getColor(timestampColorResId, theme));

        mSubjectLabel.setTextColor(getResources().getColor(subjectLabelColorResId, theme));
        mSenderNameTextView.setTextColor(getResources().getColor(timestampColorResId, theme));

        // E2EE lock shares the metadata row with the "· RCS" status text, so tint it
        // with the same status/timestamp color rather than the drawable's white fill
        // (which rendered a mismatched bright shade next to the RCS label). Use
        // setImageTintList — setColorFilter does not reliably apply to a VectorDrawable
        // promoted to a hardware layer.
        if (mE2eeLockIcon != null) {
            mE2eeLockIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(timestampColorResId, theme)));
        }
    }

    /**
     * If we don't know the size of the image, we want to show it in a fixed-sized frame to
     * avoid janks when the image is loaded and resized. Otherwise, we can set the imageview to
     * take on normal layout params.
     */
    private void adjustImageViewBounds(final MessagePartData imageAttachment) {
        Assert.isTrue(ContentType.isImageType(imageAttachment.getContentType()));
        final ViewGroup.LayoutParams layoutParams = mMessageImageView.getLayoutParams();
        if (imageAttachment.getWidth() == MessagePartData.UNSPECIFIED_SIZE ||
                imageAttachment.getHeight() == MessagePartData.UNSPECIFIED_SIZE) {
            // We don't know the size of the image attachment, enable letterboxing on the image
            // and show a fixed sized attachment. This should happen at most once per image since
            // after the image is loaded we then save the image dimensions to the db so that the
            // next time we can display the full size.
            layoutParams.width = getResources()
                    .getDimensionPixelSize(R.dimen.image_attachment_fallback_width);
            layoutParams.height = getResources()
                    .getDimensionPixelSize(R.dimen.image_attachment_fallback_height);
            mMessageImageView.setScaleType(ScaleType.CENTER_CROP);
        } else {
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            // ScaleType.CENTER_INSIDE and FIT_CENTER behave similarly for most images. However,
            // FIT_CENTER works better for small images as it enlarges the image such that the
            // minimum size ("android:minWidth" etc) is honored.
            mMessageImageView.setScaleType(ScaleType.FIT_CENTER);
        }
    }

    @Override
    public void onClick(final View view) {
        final Object tag = view.getTag();
        if (tag instanceof MessagePartData) {
            final Rect bounds = UiUtils.getMeasuredBoundsOnScreen(view);
            onAttachmentClick((MessagePartData) tag, bounds, false /* longPress */);
        } else if (tag instanceof String) {
            // Currently the only object that would make a tag of a string is a youtube preview
            // image
            UIIntents.get().launchBrowserForUrl(getContext(), (String) tag);
        }
    }

    @Override
    public boolean onLongClick(final View view) {
        if (view == mMessageTextView) {
            // Avoid trying to reselect the message
            if (isSelected()) {
                return false;
            }

            // Emoji-reactions: surface the long-press reaction quick-row alongside
            // the existing select-CAB (it's a transient overlay popup, so the two
            // coexist; the popup auto-dismisses on outside touch).
            maybeShowReactionPicker();

            // Preemptively handle the long click event on message text so it's not handled by
            // the link spans.
            return performLongClick();
        }

        final Object tag = view.getTag();
        if (tag instanceof MessagePartData) {
            // Emoji-reactions on an attachment-only / media bubble too.
            maybeShowReactionPicker();
            final Rect bounds = UiUtils.getMeasuredBoundsOnScreen(view);
            return onAttachmentClick((MessagePartData) tag, bounds, true /* longPress */);
        }

        return false;
    }

    /**
     * Emoji-reactions: show the reaction quick-row for a reaction-eligible message.
     * Reactions only make sense for RCS messages (the wire carries them on the
     * Tachygram plane and the target must have an rcs message-id); a SMS/MMS row or
     * an RCS row without a server id yet shows nothing extra, so the long-press
     * keeps its existing select-CAB behavior unchanged.
     */
    private void maybeShowReactionPicker() {
        if (mData.getIsRcs() && !mData.getIsRcsSystem()
                && !TextUtils.isEmpty(mData.getRcsMessageId())) {
            showReactionPicker();
        }
    }

    @Override
    public boolean onAttachmentClick(final MessagePartData attachment,
            final Rect viewBoundsOnScreen, final boolean longPress) {
        return mHost.onAttachmentClick(this, attachment, viewBoundsOnScreen, longPress);
    }

    public ContactIconView getContactIconView() {
        return mContactIconView;
    }

    // Sort photos in MultiAttachLayout in the same order as the ConversationImagePartsView
    static final Comparator<MessagePartData> sImageComparator =
            Comparator.comparing(MessagePartData::getPartId);

    static final Predicate<MessagePartData> sVideoFilter = MessagePartData::isVideo;
    static final Predicate<MessagePartData> sAudioFilter = MessagePartData::isAudio;
    static final Predicate<MessagePartData> sVCardFilter = MessagePartData::isVCard;
    static final Predicate<MessagePartData> sImageFilter = MessagePartData::isImage;

    interface AttachmentViewBinder {
        void bindView(View view, MessagePartData attachment);
        void unbind(View view);
    }

    final AttachmentViewBinder mVideoViewBinder = new AttachmentViewBinder() {
        @Override
        public void bindView(final View view, final MessagePartData attachment) {
            ((VideoThumbnailView) view).setSource(attachment, mData.getIsIncoming());
        }

        @Override
        public void unbind(final View view) {
            ((VideoThumbnailView) view).setSource((Uri) null, mData.getIsIncoming());
        }
    };

    final AttachmentViewBinder mAudioViewBinder = new AttachmentViewBinder() {
        @Override
        public void bindView(final View view, final MessagePartData attachment) {
            final AudioAttachmentView audioView = (AudioAttachmentView) view;
            audioView.bindMessagePartData(attachment, mData.getIsIncoming(), isSelected());
            audioView.setBackground(ConversationDrawables.get().getBubbleDrawable(
                    isSelected(), mData.getIsIncoming(), mData.hasIncomingErrorStatus(),
                    mData.getSenderContactLookupKey()));
        }

        @Override
        public void unbind(final View view) {
            ((AudioAttachmentView) view).bindMessagePartData(null, mData.getIsIncoming(), false);
        }
    };

    final AttachmentViewBinder mVCardViewBinder = new AttachmentViewBinder() {
        @Override
        public void bindView(final View view, final MessagePartData attachment) {
            final PersonItemView personView = (PersonItemView) view;
            personView.bind(DataModel.get().createVCardContactItemData(getContext(),
                    attachment));
            personView.setBackground(ConversationDrawables.get().getBubbleDrawable(
                    isSelected(), mData.getIsIncoming(), mData.hasIncomingErrorStatus(),
                    mData.getSenderContactLookupKey()));
            final int nameTextColorRes;
            final int detailsTextColorRes;
            if (isSelected()) {
                nameTextColorRes = R.color.message_text_color_incoming;
                detailsTextColorRes = R.color.message_text_color_incoming;
            } else {
                nameTextColorRes = mData.getIsIncoming() ? R.color.message_text_color_incoming
                        : R.color.message_text_color_outgoing;
                detailsTextColorRes = mData.getIsIncoming() ? R.color.timestamp_text_incoming
                        : R.color.timestamp_text_outgoing;
            }
            Resources.Theme theme = getContext().getTheme();
            personView.setNameTextColor(getResources().getColor(nameTextColorRes, theme));
            personView.setDetailsTextColor(getResources().getColor(detailsTextColorRes, theme));
        }

        @Override
        public void unbind(final View view) {
            ((PersonItemView) view).bind(null);
        }
    };

    /**
     * A helper class that allows us to handle long clicks on linkified message text view (i.e. to
     * select the message) so it's not handled by the link spans to launch apps for the links.
     */
    private static class IgnoreLinkLongClickHelper implements OnLongClickListener, OnTouchListener {
        private boolean mIsLongClick;
        private final OnLongClickListener mDelegateLongClickListener;

        /**
         * Ignore long clicks on linkified texts for a given text view.
         * @param textView the TextView to ignore long clicks on
         * @param longClickListener a delegate OnLongClickListener to be called when the view is
         *        long clicked.
         */
        public static void ignoreLinkLongClick(final TextView textView,
                @Nullable final OnLongClickListener longClickListener) {
            final IgnoreLinkLongClickHelper helper =
                    new IgnoreLinkLongClickHelper(longClickListener);
            textView.setOnLongClickListener(helper);
            textView.setOnTouchListener(helper);
        }

        private IgnoreLinkLongClickHelper(@Nullable final OnLongClickListener longClickListener) {
            mDelegateLongClickListener = longClickListener;
        }

        @Override
        public boolean onLongClick(final View v) {
            // Record that this click is a long click.
            mIsLongClick = true;
            if (mDelegateLongClickListener != null) {
                return mDelegateLongClickListener.onLongClick(v);
            }
            return false;
        }

        @Override
        public boolean onTouch(final View v, final MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && mIsLongClick) {
                // This touch event is a long click, preemptively handle this touch event so that
                // the link span won't get a onClicked() callback.
                mIsLongClick = false;
                return false;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mIsLongClick = false;
            }
            return false;
        }
    }
}
