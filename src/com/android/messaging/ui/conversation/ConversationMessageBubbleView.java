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

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ConversationMessageBubbleData;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.util.UiUtils;

/**
 * Shows the message bubble for one conversation message. It is able to animate size changes
 * by morphing when the message content changes size.
 */
// TODO: Move functionality from ConversationMessageView into this class as appropriate
public class ConversationMessageBubbleView extends LinearLayout {
    private int mIntrinsicWidth;
    private int mMorphedWidth;
    private ObjectAnimator mAnimator;
    private boolean mShouldAnimateWidthChange;
    private final ConversationMessageBubbleData mData;
    private int mRunningStartWidth;
    private ViewGroup mBubbleBackground;
    // Emoji-reactions: the reaction badge overlaps the bubble's inner-bottom
    // corner (iMessage/Google-Messages style) rather than flowing below it.
    private View mReactions;
    private View mAttachments;
    private boolean mReactionIncoming;

    public ConversationMessageBubbleView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mData = new ConversationMessageBubbleData();
        // The reaction badge is positioned (in onLayout) straddling the bubble's
        // bottom edge, so it must be allowed to draw outside the normal child box.
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBubbleBackground = findViewById(R.id.message_text_and_info);
        mReactions = findViewById(R.id.reactions_container);
        mAttachments = findViewById(R.id.message_attachments);
    }

    /** Which side the reaction badge hugs: incoming bubbles (start-aligned) put it
     *  at their END corner, outgoing at their START corner — i.e. the inner side,
     *  toward the center of the thread. Set from ConversationMessageView.bind(). */
    public void setReactionIncoming(final boolean incoming) {
        mReactionIncoming = incoming;
    }

    /** The bottom-most visible bubble element the reaction badge anchors to. */
    private View reactionAnchor() {
        if (mBubbleBackground != null && mBubbleBackground.getVisibility() != GONE) {
            return mBubbleBackground;
        }
        if (mAttachments != null && mAttachments.getVisibility() != GONE) {
            return mAttachments;
        }
        return null;
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int newIntrinsicWidth = getMeasuredWidth();
        if (mIntrinsicWidth == 0 && newIntrinsicWidth != mIntrinsicWidth) {
            if (mShouldAnimateWidthChange) {
                kickOffMorphAnimation(mIntrinsicWidth, newIntrinsicWidth);
            }
            mIntrinsicWidth = newIntrinsicWidth;
        }

        if (mMorphedWidth > 0) {
            mBubbleBackground.getLayoutParams().width = mMorphedWidth;
        } else {
            mBubbleBackground.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
        }
        mBubbleBackground.requestLayout();
        // NB: the badge's vertical overlap onto the bubble is reserved via a
        // NEGATIVE top margin on reactions_container (set in the layout XML), so
        // LinearLayout computes the (reduced) total height in one consistent
        // measure pass. We deliberately do NOT post-hoc shrink the measured height
        // here -- doing that re-measured and CLIPPED the bubble background's top
        // padding on the first message after an info/system row (b/ the avatar +
        // morph requestLayout re-measure against the shrunk parent height).
    }

    @Override
    protected void onLayout(final boolean changed, final int l, final int t,
            final int r, final int b) {
        super.onLayout(changed, l, t, r, b);
        if (mReactions == null || mReactions.getVisibility() == GONE) {
            return;
        }
        final View anchor = reactionAnchor();
        if (anchor == null) {
            return;
        }
        // Straddle the bubble's bottom edge at the inner corner, overlapping it by
        // a fixed amount so the lower part hangs below (iMessage style). The
        // negative reactions_container top margin already reserved this overlap in
        // measure; here we only fix the horizontal inner-corner position + keep the
        // vertical consistent with that reservation.
        final int badgeW = mReactions.getMeasuredWidth();
        final int badgeH = mReactions.getMeasuredHeight();
        final int overlap = getResources()
                .getDimensionPixelSize(R.dimen.reaction_badge_overlap);
        final int inset = getResources()
                .getDimensionPixelSize(R.dimen.reaction_badge_side_inset);
        final int top = anchor.getBottom() - overlap;
        final int left = mReactionIncoming
                ? anchor.getRight() - badgeW + inset   // incoming -> END (inner) corner
                : anchor.getLeft() - inset;            // outgoing -> START (inner) corner
        mReactions.layout(left, top, left + badgeW, top + badgeH);
    }

    public void setMorphWidth(final int width) {
        mMorphedWidth = width;
        requestLayout();
    }

    public void bind(final ConversationMessageData data) {
        final boolean changed = mData.bind(data);
        // Animate width change only when we are binding to the same message, so that we may
        // animate view size changes on the same message bubble due to things like status text
        // change.
        // Don't animate width change when the bubble contains attachments. Width animation is
        // only suitable for text-only messages (where the bubble size change due to status or
        // time stamp changes).
        mShouldAnimateWidthChange = !changed && !data.hasAttachments();
        if (mAnimator == null) {
            mMorphedWidth = 0;
        }
    }

    public void kickOffMorphAnimation(final int oldWidth, final int newWidth) {
        if (mAnimator != null) {
            mAnimator.setIntValues(mRunningStartWidth, newWidth);
            return;
        }
        mRunningStartWidth = oldWidth;
        mAnimator = ObjectAnimator.ofInt(this, "morphWidth", oldWidth, newWidth);
        mAnimator.setDuration(UiUtils.MEDIAPICKER_TRANSITION_DURATION);
        mAnimator.addListener(new AnimatorListener() {
            @Override
            public void onAnimationStart(@NonNull Animator animator) {
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animator) {
                mAnimator = null;
                mMorphedWidth = 0;
                // Allow the bubble to resize if, for example, the status text changed during
                // the animation.  This will snap to the bigger size if needed.  This is intentional
                // as animating immediately after looks really bad and switching layout params
                // during the original animation does not achieve the desired effect.
                mBubbleBackground.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                mBubbleBackground.requestLayout();
            }

            @Override
            public void onAnimationCancel(@NonNull Animator animator) {
            }

            @Override
            public void onAnimationRepeat(@NonNull Animator animator) {
            }
        });
        mAnimator.start();
    }
}
