/*
 * Copyright (C) 2015 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.util;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.UpdateConversationArchiveStatusAction;
import com.android.messaging.datamodel.data.ParticipantData;

// ContentObserver class to monitor changes to the Framework Blacklist DB
public class BlacklistObserver extends ContentObserver {

    private static final String TAG = BlacklistObserver.class.getSimpleName();
    private final Context mContext;
    private ContentResolver mResolver;

    public BlacklistObserver(Handler handler, Context context) {
        super(handler);
        mResolver = context.getContentResolver();
        mContext = context;
    }

    @Override
    public void onChange(boolean selfChange, final Uri uri) {
        // depending on the Thread in which the handler was created, this function
        // may or may not run on the UI thread, to be sure that it doesn't, launch
        // it via AsyncTask
        SafeAsyncTask.executeOnThreadPool(new Runnable() {
            @Override
            public void run() {
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    Log.i(TAG, "BlacklistObserver: onChange: Uri:" + uri.toString());
                }

                boolean requiresNotifyAllParticipants = false;

                // we need to find the phone number being blacklisted and add/update it
                // in the bugle database
                Cursor cursor = null;
                try {
                    cursor = mResolver.query(uri, null, null, null, null);

                    DatabaseWrapper db = DataModel.get().getDatabase();

                    // Row was deleted
                    if (cursor.getCount() == 0) {
                        if (!uri.getPath().contains("bynumber")) {
                            // We don't have enough information, lets run a full sync
                            BlacklistSync blacklistSync = new BlacklistSync(mContext);
                            blacklistSync.execute();
                        } else {
                            // We have the number that was deleted, lets update it locally
                            String number = uri.getLastPathSegment();
                            BugleDatabaseOperations.updateDestination(db, number,
                                    false, false);
                            String conversationId = updateArchiveStatusForConversation(false, number, db);
                            if (conversationId == null) {
                                requiresNotifyAllParticipants = true;
                            } else {
                                MessagingContentProvider.notifyParticipantsChanged(conversationId);
                            }
                        }
                        return;
                    }

                    int normalizedNumberIndex = cursor.getColumnIndex("normalized_number");
                    int blockedIndex = cursor.getColumnIndex("message");
                    int nonNormalizedNumberIndex = cursor.getColumnIndex("number");
                    int regexIndex = cursor.getColumnIndex("is_regex");
                    // if the column indices are not valid, don't perform the queries
                    if (normalizedNumberIndex < 0 || blockedIndex < 0) {
                        return;
                    }

                    while(cursor.moveToNext()) {
                        String number = cursor.getString(normalizedNumberIndex);
                        String blocked = cursor.getString(blockedIndex);
                        boolean isBlocked = blocked.compareTo("1") == 0;
                        String formattedNumber = cursor.getInt(regexIndex) != 0
                                ? cursor.getString(nonNormalizedNumberIndex) : null;
                        // don't update the framework db - the 'false' argument
                        int updateCount = BugleDatabaseOperations.updateDestination(db, number,
                                isBlocked, false);
                        if (updateCount == 0) {
                            // there was no phone number in the local participants database that was
                            // blacklisted in the framework blacklist database,
                            // create a new participant
                            // and insert him into the local participants database
                            ParticipantData participant =
                                    ParticipantData.getFromRawPhoneBySystemLocale(number, formattedNumber);
                            BugleDatabaseOperations.getOrCreateParticipantInTransaction(db,
                                    participant);
                            BugleDatabaseOperations.updateDestination(db, number,
                                    isBlocked, false);
                        }
                        String conversationId = updateArchiveStatusForConversation(isBlocked, number, db);
                        if (!requiresNotifyAllParticipants && conversationId != null) {
                            MessagingContentProvider.notifyParticipantsChanged(conversationId);
                        } else {
                            requiresNotifyAllParticipants = true;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "BlacklistObserver: onChange: " + e.getMessage());
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                    if (requiresNotifyAllParticipants) {
                        MessagingContentProvider.notifyAllParticipantsChanged();
                    }
                }
            }
        });

    }

    private String updateArchiveStatusForConversation(boolean isBlocked, String number, DatabaseWrapper db) {
        String conversationId = BugleDatabaseOperations
                .getConversationFromOtherParticipantDestination(db, number);
        if (conversationId != null) {
            if (isBlocked) {
                UpdateConversationArchiveStatusAction
                        .archiveConversation(conversationId);
            } else {
                UpdateConversationArchiveStatusAction
                        .unarchiveConversation(conversationId);
            }
        }
        return conversationId;
    }
}
