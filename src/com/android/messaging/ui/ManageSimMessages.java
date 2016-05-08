/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.messaging.ui;

import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SqliteWrapper;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.SubscriptionListData;
import com.android.messaging.sms.SimMessagesUtils;
import com.android.messaging.ui.conversation.ConversationMessageAdapter;
import com.android.messaging.ui.conversation.ConversationMessageView;

/**
 * Displays a list of the SMS messages stored on the ICC.
 */
public class ManageSimMessages extends BugleActionBarActivity
        implements ConversationMessageView.ConversationMessageViewHost,
        View.OnCreateContextMenuListener {
    private static final String TAG = ManageSimMessages.class.getSimpleName();

    private static final int SHOW_LIST = 0;
    private static final int SHOW_EMPTY = 1;
    private static final int SHOW_BUSY = 2;
    private int mState;
    private int mSlot;
    private int mSubscription;

    private Uri mIccUri;
    private ContentResolver mContentResolver;
    private Cursor mCursor = null;
    private RecyclerView mSimList;
    private TextView mMessage;
    private ConversationMessageAdapter mListAdapter = null;
    private AsyncQueryHandler mQueryHandler = null;
    private boolean mIsDeleteAll = false;
    private boolean mIsQuery = false;
    private ConversationMessageView mSelectedMessage = null;
    public static final int TYPE_INBOX = 1;

    private final ContentObserver simChangeObserver =
            new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfUpdate) {
            refreshMessageList();
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                refreshMessageList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mContentResolver = getContentResolver();
        mQueryHandler = new QueryHandler(mContentResolver, this);
        setContentView(R.layout.sim_list);
        mSimList = (RecyclerView) findViewById(android.R.id.list);
        final LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setStackFromEnd(true);
        manager.setReverseLayout(false);
        mSimList.setHasFixedSize(true);
        mSimList.setLayoutManager(manager);
        mMessage = (TextView) findViewById(R.id.empty_message);
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        registerReceiver(mReceiver, filter);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        init();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);

        init();
    }

    private void init() {
        mSlot = getIntent().getIntExtra(PhoneConstants.PHONE_KEY, SimMessagesUtils.SUB_INVALID);

        mSubscription = SimMessagesUtils.SUB_INVALID;
        int[] subIds = SubscriptionManager.getSubId(mSlot);
        if (subIds != null && subIds.length > 0) {
            mSubscription = subIds[0];
        }

        mIccUri = SimMessagesUtils.getIccUriBySlot(mSlot);
        updateState(SHOW_BUSY);
        startQuery();
    }

    @Override
    public boolean onAttachmentClick(ConversationMessageView view, MessagePartData attachment,
            Rect imageBounds, boolean longPress) {
        return false;
    }

    @Override
    public SubscriptionListData.SubscriptionListEntry getSubscriptionEntryForSelfParticipant(
            String selfParticipantId, boolean excludeDefault) {
        return null;
    }

    private class QueryHandler extends AsyncQueryHandler {
        private final ManageSimMessages mParent;

        public QueryHandler(
                ContentResolver contentResolver, ManageSimMessages parent) {
            super(contentResolver);
            mParent = parent;
        }

        @Override
        protected void onQueryComplete(
                int token, Object cookie, Cursor cursor) {
            if (mCursor != null) {
                stopManagingCursor(mCursor);
            }
            mCursor = cursor;
            if (mCursor != null) {
                if (!mCursor.moveToFirst()) {
                    // Let user know the SIM is empty
                    updateState(SHOW_EMPTY);
                } else if (mListAdapter == null) {
                    mListAdapter = new ConversationMessageAdapter(
                            ManageSimMessages.this, mCursor, ManageSimMessages.this, null,
                            onMessageListItemClick, onMessageListItemLongClick, true);
                    mSimList.setAdapter(mListAdapter);
                    mSimList.setOnCreateContextMenuListener(mParent);
                    updateState(SHOW_LIST);
                } else {
                    mListAdapter.changeCursor(mCursor);
                    updateState(SHOW_LIST);
                }
                startManagingCursor(mCursor);
            } else {
                // Let user know the SIM is empty
                updateState(SHOW_EMPTY);
            }
            mIsQuery = false;
        }
    }

    private void startQuery() {
        try {
            if (mIsQuery) {
                return;
            }
            mIsQuery = true;
            mQueryHandler.startQuery(0, null, mIccUri, null, null, null, null);
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    private void refreshMessageList() {
        updateState(SHOW_BUSY);
        startQuery();
    }


    @Override
    public void onResume() {
        super.onResume();
        registerSimChangeObserver();
    }

    @Override
    public void onPause() {
        super.onPause();
        mContentResolver.unregisterContentObserver(simChangeObserver);
    }

    @Override
    public void onBackPressed() {
        if (mIsDeleteAll) {
            mIsDeleteAll = false;
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private void registerSimChangeObserver() {
        mContentResolver.registerContentObserver(
                mIccUri, true, simChangeObserver);
    }

    private void updateState(int state) {
        if (mState == state) {
            return;
        }

        mState = state;
        switch (state) {
            case SHOW_LIST:
                mSimList.setVisibility(View.VISIBLE);
                mMessage.setVisibility(View.GONE);
                setTitle(getString(R.string.sim_manage_messages_title));
                setProgressBarIndeterminateVisibility(false);
                mSimList.requestFocus();
                break;
            case SHOW_EMPTY:
                mSimList.setVisibility(View.GONE);
                mMessage.setVisibility(View.VISIBLE);
                setTitle(getString(R.string.sim_manage_messages_title));
                setProgressBarIndeterminateVisibility(false);
                break;
            case SHOW_BUSY:
                mSimList.setVisibility(View.GONE);
                mMessage.setVisibility(View.GONE);
                setTitle(getString(R.string.refreshing));
                setProgressBarIndeterminateVisibility(true);
                break;
            default:
                Log.e(TAG, "Invalid State");
        }
    }

    public Context getContext() {
        return ManageSimMessages.this;
    }

    View.OnClickListener onMessageListItemClick = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            //Do Nothing
        }
    };

    View.OnLongClickListener onMessageListItemLongClick = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View view) {
            return true;
        }
    };
}

