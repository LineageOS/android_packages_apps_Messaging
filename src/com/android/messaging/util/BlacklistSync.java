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

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.Log;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;

public class BlacklistSync extends AsyncTask<Void, Void, Void> {
    private Context mContext;

    private static final String TAG = BlacklistSync.class.getSimpleName();

    public BlacklistSync(Context context) {
        mContext = context;

    }

    @Override
    protected Void doInBackground(Void... params) {
        // TODO - need to extract URI from TelephonyProvider
        Uri CONTENT_URI = Uri.parse("content://blacklist");
        Cursor cursor;

        DatabaseWrapper db = DataModel.get().getDatabase();
        ArrayList<String> blockedParticipants = getBlockedParticipants(db);

        BugleDatabaseOperations.resetBlockedParticpants(db);

        // need to update local blacklist database - we are simply overwriting the
        // local database with the framework database - the local database is used
        // as a WriteThrough Cache of the Framework Database
        cursor = mContext.getContentResolver().query(CONTENT_URI, null, null, null, null);
        if (cursor != null && cursor.getCount() > 0) {
            int normalizedNumberIndex = cursor.getColumnIndex("normalized_number");
            int blockedIndex = cursor.getColumnIndex("message");
            int nonNormalizedNumberIndex = cursor.getColumnIndex("number");
            int regexIndex = cursor.getColumnIndex("is_regex");
            int updateCount;
            if (normalizedNumberIndex < 0 || blockedIndex < 0) {
                cursor.close();
                return null;
            }

            while(cursor.moveToNext()) {
                String number = cursor.getString(normalizedNumberIndex);
                String blocked = cursor.getString(blockedIndex);
                boolean isBlocked = blocked.compareTo("1") == 0;
                String formattedNumber = cursor.getInt(regexIndex) != 0
                        ? cursor.getString(nonNormalizedNumberIndex) : null;
                updateCount = BugleDatabaseOperations.updateDestination(db, number, isBlocked,
                        false);
                if (updateCount == 0) {
                    // there was no phone number in the local participants database that was
                    // blacklisted in the framework blacklist database, create a new participant
                    // and insert him into the local participants database
                    db.beginTransaction();
                    try {
                        ParticipantData participant = ParticipantData
                                .getFromRawPhoneBySystemLocale(number, formattedNumber);
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db,
                                participant);
                        BugleDatabaseOperations.updateDestination(db, number,
                                isBlocked, false);
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                }
            }
        }
        if (cursor != null) {
            cursor.close();
        }

        // unarchive conversations from participants that are no longer blocked
        boolean conversationsUpdated = false;
        ArrayList<String> updateBlockedParticipants = getBlockedParticipants(db);
        for (String participant : blockedParticipants) {
            if (!updateBlockedParticipants.contains(participant)) {
                String selection =
                        DatabaseHelper.ConversationColumns.OTHER_PARTICIPANT_NORMALIZED_DESTINATION
                        + "=?";
                String[] selectionArgs = {participant};
                ContentValues values = new ContentValues();
                values.put(DatabaseHelper.ConversationColumns.ARCHIVE_STATUS, 0);
                db.update(DatabaseHelper.CONVERSATIONS_TABLE, values, selection, selectionArgs);
                conversationsUpdated = true;
            }
        }

        MessagingContentProvider.notifyAllParticipantsChanged();
        if (conversationsUpdated) MessagingContentProvider.notifyConversationListChanged();
        return null;
    }

    private ArrayList getBlockedParticipants(DatabaseWrapper db) {
        final String[] projection = {DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION};
        final String selection = DatabaseHelper.ParticipantColumns.BLOCKED + "=?";
        final String[] selectionArgs = { "1" };
        final ArrayList<String> blockedParticipants = new ArrayList<>();
        try (Cursor cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE, projection, selection,
                selectionArgs, null, null, null)) {
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String normalizedDestination = cursor.getString(0);
                    blockedParticipants.add(normalizedDestination);
                }
            }
        }

        return blockedParticipants;
    }
}
