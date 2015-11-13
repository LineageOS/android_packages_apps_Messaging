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
    private SharedPreferences mSharedPrefs;

    private static final String TAG = BlacklistSync.class.getSimpleName();
    public static final String BLACKLIST_TIMESTAMP_FILE =
            "bugle_blacklist_timestamp_file";
    public static final String BLACKLIST_TIMESTAMP = "blacklist_timestamp";

    public BlacklistSync(Context context) {
        mContext = context;
        mSharedPrefs = mContext.getSharedPreferences(BLACKLIST_TIMESTAMP_FILE, Context
                .MODE_PRIVATE);
    }

    private long getTimestamp() {
        long timestamp = mSharedPrefs.getLong(BLACKLIST_TIMESTAMP, 0);
        return timestamp;
    }

    private long updateTimestamp() {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        Time time = new Time();
        time.setToNow();
        long now = time.toMillis(true);
        editor.putLong(BlacklistSync.BLACKLIST_TIMESTAMP, now);
        editor.commit();
        return now;
    }
    @Override
    protected Void doInBackground(Void... params) {

        // TODO - need to extract URI from TelephonyProvider
        Uri CONTENT_URI = Uri.parse("content://blacklist");
        Uri TIMESTAMP_URI = CONTENT_URI.withAppendedPath(CONTENT_URI, "timestamp");

        // get the timestamp of the last update to the local blacklist database
        long localDbTimestamp = getTimestamp();
        long fwDbTimestamp = 0;

        Cursor cursor = mContext.getContentResolver().query(TIMESTAMP_URI, null, null, null, null);
        if (cursor != null && cursor.getCount() > 0 && cursor.moveToNext()) {
            try {
                fwDbTimestamp = cursor.getLong(0);
            } catch (Exception e) {
                Log.e(TAG, "Blacklist: Exception: " + e.getMessage());
            }

        }

        if (cursor != null) {
            cursor.close();
        } else {
            return null;
        }
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            Log.v(TAG, "BlacklistSync: LocalTimestamp:" + localDbTimestamp + " " +
                    "frameworkTimestamp:" + fwDbTimestamp);
        }


        if (localDbTimestamp < fwDbTimestamp) {
            // need to update local blacklist database - we are simply overwriting the
            // local database with the framework database
            cursor = mContext.getContentResolver().query(CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.getCount() > 0) {
                // TODO - use names from BlacklistProvider
                int normalizedNumberIndex = cursor.getColumnIndex("normalized_number");
                int blockedIndex = cursor.getColumnIndex("message");
                int updateCount;
                if (normalizedNumberIndex < 0 || blockedIndex < 0) {
                    cursor.close();
                    return null;
                }

                DatabaseWrapper db = DataModel.get().getDatabase();
                TelephonyManager tm = (TelephonyManager)mContext.getSystemService(mContext
                        .TELEPHONY_SERVICE);
                String countryCode = tm.getNetworkCountryIso();


                while(cursor.moveToNext()) {
                    String number = cursor.getString(normalizedNumberIndex);
                    String message = cursor.getString(blockedIndex);
                    boolean isBlocked = message.compareTo("1") == 0;
                    updateCount = BugleDatabaseOperations.updateDestination(db, number, isBlocked,
                            false);
                    if (updateCount == 0) {
                        // there was no phone number in the local participants database that was
                        // blacklisted in the framework blacklist database, create a new participant
                        // and insert him into the local participants database
                        ParticipantData participant = ParticipantData
                                .getFromRawPhoneBySystemLocale(number);
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db,
                                participant);
                        updateCount = BugleDatabaseOperations.updateDestination(db, number,
                                isBlocked, false);
                    }
                }
                updateTimestamp();
            }
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
}
