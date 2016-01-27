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
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

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

        // need to update local blacklist database - we are simply overwriting the
        // local database with the framework database - the local database is used
        // as a WriteThrough Cache of the Framework Database
        cursor = mContext.getContentResolver().query(CONTENT_URI, null, null, null, null);
        if (cursor != null && cursor.getCount() > 0) {
            int normalizedNumberIndex = cursor.getColumnIndex("normalized_number");
            int blockedIndex = cursor.getColumnIndex("message");
            int updateCount;
            if (normalizedNumberIndex < 0 || blockedIndex < 0) {
                cursor.close();
                return null;
            }

            DatabaseWrapper db = DataModel.get().getDatabase();

            while(cursor.moveToNext()) {
                String number = cursor.getString(normalizedNumberIndex);
                String blocked = cursor.getString(blockedIndex);
                boolean isBlocked = blocked.compareTo("1") == 0;
                updateCount = BugleDatabaseOperations.updateDestination(db, number, isBlocked,
                        false);
                if (updateCount == 0) {
                    // there was no phone number in the local participants database that was
                    // blacklisted in the framework blacklist database, create a new participant
                    // and insert him into the local participants database
                    db.beginTransaction();
                    try {
                        ParticipantData participant = ParticipantData
                                .getFromRawPhoneBySystemLocale(number);
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

        return null;
    }
}
