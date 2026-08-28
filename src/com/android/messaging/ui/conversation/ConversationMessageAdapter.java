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
import android.database.Cursor;
import androidx.collection.ArraySet;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Set;

import com.android.messaging.R;
import com.android.messaging.ui.CursorRecyclerAdapter;
import com.android.messaging.ui.AsyncImageView.AsyncImageViewDelayLoader;
import com.android.messaging.ui.conversation.ConversationMessageView.ConversationMessageViewHost;
import com.android.messaging.util.Assert;

/**
 * Provides an interface to expose Conversation Message Cursor data to a UI widget like a
 * RecyclerView.
 */
public class ConversationMessageAdapter extends
    CursorRecyclerAdapter<ConversationMessageAdapter.ConversationMessageViewHolder> {

    private final ConversationMessageViewHost mHost;
    private final AsyncImageViewDelayLoader mImageViewDelayLoader;
    private final View.OnClickListener mViewClickListener;
    private final View.OnLongClickListener mViewLongClickListener;
    private boolean mOneOnOne;
    private final Set<String> mSelectedMessageIds = new ArraySet<>();

    public ConversationMessageAdapter(final Context context, final Cursor cursor,
        final ConversationMessageViewHost host,
        final AsyncImageViewDelayLoader imageViewDelayLoader,
        final View.OnClickListener viewClickListener,
        final View.OnLongClickListener longClickListener) {
        super(context, cursor, 0);
        mHost = host;
        mViewClickListener = viewClickListener;
        mViewLongClickListener = longClickListener;
        mImageViewDelayLoader = imageViewDelayLoader;
        setHasStableIds(true);
    }

    @Override
    public void bindViewHolder(final ConversationMessageViewHolder holder,
            final Context context, final Cursor cursor) {
        Assert.isTrue(holder.mView instanceof ConversationMessageView);
        final ConversationMessageView conversationMessageView =
                (ConversationMessageView) holder.mView;
        conversationMessageView.bind(cursor, mOneOnOne, mSelectedMessageIds);
    }

    @Override
    public ConversationMessageViewHolder createViewHolder(final Context context,
            final ViewGroup parent, final int viewType) {
        final LayoutInflater layoutInflater = LayoutInflater.from(context);
        final ConversationMessageView conversationMessageView = (ConversationMessageView)
                layoutInflater.inflate(R.layout.conversation_message_view, null);
        conversationMessageView.setHost(mHost);
        conversationMessageView.setImageViewDelayLoader(mImageViewDelayLoader);
        return new ConversationMessageViewHolder(conversationMessageView,
                            mViewClickListener, mViewLongClickListener);
    }

    /** Replaces the whole selection with the single given message (or clears it if null). */
    public void setSelectedMessage(final String messageId) {
        mSelectedMessageIds.clear();
        if (messageId != null) {
            mSelectedMessageIds.add(messageId);
        }
        notifyDataSetChanged();
    }

    /** Adds or removes the given message from the selection. Returns its new selected state. */
    public boolean toggleSelectedMessage(final String messageId) {
        final boolean nowSelected;
        if (mSelectedMessageIds.remove(messageId)) {
            nowSelected = false;
        } else {
            mSelectedMessageIds.add(messageId);
            nowSelected = true;
        }
        notifyDataSetChanged();
        return nowSelected;
    }

    public void clearSelectedMessages() {
        if (!mSelectedMessageIds.isEmpty()) {
            mSelectedMessageIds.clear();
            notifyDataSetChanged();
        }
    }

    public Set<String> getSelectedMessageIds() {
        return Collections.unmodifiableSet(mSelectedMessageIds);
    }

    public int getSelectedMessageCount() {
        return mSelectedMessageIds.size();
    }

    public boolean isMessageSelected(final String messageId) {
        return mSelectedMessageIds.contains(messageId);
    }

    public void setOneOnOne(final boolean oneOnOne, final boolean invalidate) {
        if (mOneOnOne != oneOnOne) {
            mOneOnOne = oneOnOne;
            if (invalidate) {
                notifyDataSetChanged();
            }
        }
    }

    /**
    * ViewHolder that holds a ConversationMessageView.
    */
    public static class ConversationMessageViewHolder extends RecyclerView.ViewHolder {
        final View mView;

        /**
         * @param viewClickListener a View.OnClickListener that should define the interaction when
         *        an item in the RecyclerView is clicked.
         */
        public ConversationMessageViewHolder(final View itemView,
                final View.OnClickListener viewClickListener,
                final View.OnLongClickListener viewLongClickListener) {
            super(itemView);
            mView = itemView;

            mView.setOnClickListener(viewClickListener);
            mView.setOnLongClickListener(viewLongClickListener);
        }
    }
}
