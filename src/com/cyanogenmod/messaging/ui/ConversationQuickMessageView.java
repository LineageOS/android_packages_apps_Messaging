/*

 * Copyright (C) 2015 The Android Open Source Project
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
package com.cyanogenmod.messaging.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.ui.conversation.ConversationMessageBubbleView;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.UiUtils;
import com.cyanogen.lookup.phonenumber.response.LookupResponse;
import com.cyanogenmod.messaging.quickmessage.QuickMessage;

/**
 * The view for a single message in a quickmessage.
 */
public class ConversationQuickMessageView extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener {

    private TextView mMessageTextView;
    private AttributionContactIconView mContactIconView;
    private ConversationMessageBubbleView mMessageBubble;
    private ViewGroup mMessageTextAndInfoView;

    private QuickMessage mQuickMessage;

    public ConversationQuickMessageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        mContactIconView = (AttributionContactIconView) findViewById(R.id.conversation_icon);
        mContactIconView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View view) {
                ConversationQuickMessageView.this.performLongClick();
                return true;
            }
        });

        mMessageTextView = (TextView) findViewById(R.id.message_text);
        mMessageTextView.setOnClickListener(this);
        mMessageTextView.setMovementMethod(new ScrollingMovementMethod());
        IgnoreLinkLongClickHelper.ignoreLinkLongClick(mMessageTextView, this);

        mMessageBubble = (ConversationMessageBubbleView) findViewById(R.id.message_content);
        mMessageTextAndInfoView = (ViewGroup) findViewById(R.id.message_text_and_info);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int horizontalSpace = MeasureSpec.getSize(widthMeasureSpec);
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
                - arrowWidth - getPaddingLeft() - getPaddingRight();
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

        final int iconWidth = mContactIconView.getMeasuredWidth();
        final int iconHeight = mContactIconView.getMeasuredHeight();
        final int iconTop = getPaddingTop();
        final int contentWidth = (right -left) - iconWidth - getPaddingLeft() - getPaddingRight();
        final int contentHeight = mMessageBubble.getMeasuredHeight();
        final int contentTop = iconTop;

        final int iconLeft;
        final int contentLeft;

        if (isRtl) {
            iconLeft = (right - left) - getPaddingRight() - iconWidth;
            contentLeft = iconLeft - contentWidth;
        } else {
            iconLeft = getPaddingLeft();
            contentLeft = iconLeft + iconWidth;
        }

        mContactIconView.layout(iconLeft, iconTop, iconLeft + iconWidth, iconTop + iconHeight);

        mMessageBubble.layout(contentLeft, contentTop, contentLeft + contentWidth,
                contentTop + contentHeight);
    }


    public void bind(QuickMessage quickMessage) {
        mQuickMessage = quickMessage;

        // Update text and image content for the view.
        updateViewContent();

        // Update colors and layout parameters for the view.
        updateViewAppearance();
    }

    private void updateViewContent() {
        updateMessageContent();
        if (!mQuickMessage.hasFromAvatarUri() && !mQuickMessage.hasFromName()) {
            mContactIconView.setImageResourceUri(null);
        } else {
            final Uri avatarUri = mQuickMessage.hasFromAvatarUri() ?
                    mQuickMessage.getFromAvatarUri() :
                    AvatarUriUtil.createAvatarUri(
                            mQuickMessage.getFromContactUri(),
                            mQuickMessage.getFromName(),
                            null,
                            null);
            mContactIconView.setImageResourceUri(avatarUri);
        }
    }

    private void updateMessageContent() {
        // We must update the text before the attachments since we search the text to see if we
        // should make a preview youtube image in the attachments
        updateMessageText();
        mMessageBubble.bind(mQuickMessage);
    }

    private void updateMessageText() {
        final String text = mQuickMessage.getMessageBody();
        if (!TextUtils.isEmpty(text)) {
            mMessageTextView.setText(text);
            Linkify.addLinks(mMessageTextView, Linkify.ALL);
        }
    }

    private void updateViewAppearance() {
        final Resources res = getResources();
        final ConversationDrawables drawableProvider = ConversationDrawables.get();

        final int messageTopPaddingDefault =
                res.getDimensionPixelSize(R.dimen.qm_message_padding_default);
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

        // Text only
        contentLeftPadding = 0;
        contentRightPadding =  0;
        textBackground = drawableProvider.getBubbleDrawable(
                isSelected(),
                true,
                true,
                false);
        textMinHeight = messageTextMinHeightDefault;
        textTopMargin = 0;
        textTopPadding = textTopPaddingDefault;
        textBottomPadding = textBottomPaddingDefault;
        textLeftPadding = messageTextLeftRightPadding + arrowWidth;
        textRightPadding = messageTextLeftRightPadding;

        final int gravity = (Gravity.START | Gravity.CENTER_VERTICAL);
        final int messageTopPadding = messageTopPaddingDefault;

        // Update the message text/info views
        ImageUtils.setBackgroundDrawableOnView(mMessageTextAndInfoView, textBackground);
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
        setPadding(getPaddingLeft(), messageTopPadding, getPaddingRight(), 0);
        mMessageBubble.setGravity(gravity);

        updateTextAppearance();

        requestLayout();
    }

    private void updateTextAppearance() {
        int messageColorResId = R.color.message_text_color_incoming;
        final int messageColor = getResources().getColor(messageColorResId);
        mMessageTextView.setTextColor(messageColor);
        mMessageTextView.setLinkTextColor(messageColor);
    }

    @Override
    public void onClick(final View view) {
        final Object tag = view.getTag();
        if (tag instanceof String) {
            UIIntents.get().launchBrowserForUrl(getContext(), (String) tag);
        }
    }

    @Override
    public boolean onLongClick(final View view) {
        if (view == mMessageTextView) {
            // Preemptively handle the long click event on message text so it's not handled by
            // the link spans.
            return performLongClick();
        }

        return false;
    }

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
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mIsLongClick = false;
            }
            return false;
        }
    }

    public void onLookupResponse(LookupResponse response) {
        if (response != null) {
            if (mContactIconView != null) {
                if (response.mAttributionLogo != null) {
                    mContactIconView.setAttributionDrawable(response.mAttributionLogo);
                }
                if (!TextUtils.isEmpty(response.mPhotoUrl)) {
                    mContactIconView.setImageUrl(response.mPhotoUrl);
                }
                mContactIconView.setLookupResponse(response);
            }
        }
    }

}
