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

package com.android.messaging.sms;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Telephony;
import android.util.Log;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

import java.io.File;

/*
 * Database helper class for looking up APNs.  This database has a single table
 * which stores the APNs that are initially created from an xml file.
 */
public class ApnDatabase extends SQLiteOpenHelper {
    private static final int DB_VERSION = 3; // added sub_id columns

    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final boolean DEBUG = false;

    private static Context sContext;
    private static ApnDatabase sApnDatabase;

    private static final String APN_DATABASE_NAME = "apn.db";

    /** table for carrier APN's */
    public static final String APN_TABLE = "apn";

    // APN table
    private static final String APN_TABLE_SQL =
            "CREATE TABLE " + APN_TABLE +
                    "(_id INTEGER PRIMARY KEY," +
                    Telephony.Carriers.NAME + " TEXT," +
                    Telephony.Carriers.NUMERIC + " TEXT," +
                    Telephony.Carriers.MCC + " TEXT," +
                    Telephony.Carriers.MNC + " TEXT," +
                    Telephony.Carriers.APN + " TEXT," +
                    Telephony.Carriers.USER + " TEXT," +
                    Telephony.Carriers.SERVER + " TEXT," +
                    Telephony.Carriers.PASSWORD + " TEXT," +
                    Telephony.Carriers.PROXY + " TEXT," +
                    Telephony.Carriers.PORT + " TEXT," +
                    Telephony.Carriers.MMSPROXY + " TEXT," +
                    Telephony.Carriers.MMSPORT + " TEXT," +
                    Telephony.Carriers.MMSC + " TEXT," +
                    Telephony.Carriers.AUTH_TYPE + " INTEGER," +
                    Telephony.Carriers.TYPE + " TEXT," +
                    Telephony.Carriers.CURRENT + " INTEGER," +
                    Telephony.Carriers.PROTOCOL + " TEXT," +
                    Telephony.Carriers.ROAMING_PROTOCOL + " TEXT," +
                    Telephony.Carriers.CARRIER_ENABLED + " BOOLEAN," +
                    Telephony.Carriers.BEARER + " INTEGER," +
                    Telephony.Carriers.MVNO_TYPE + " TEXT," +
                    Telephony.Carriers.MVNO_MATCH_DATA + " TEXT," +
                    Telephony.Carriers.SUBSCRIPTION_ID + " INTEGER DEFAULT " +
                            ParticipantData.DEFAULT_SELF_SUB_ID + ");";
    public static final int COLUMN_ID = 4;

    /**
     * ApnDatabase is initialized asynchronously from the application.onCreate
     * To ensure that it works in a testing environment it needs to never access the factory context
     */
    public static void initializeAppContext(final Context context) {
        sContext = context;
    }

    private ApnDatabase() {
        super(sContext, APN_DATABASE_NAME, null, DB_VERSION);
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase constructor");
        }
    }

    public static ApnDatabase getApnDatabase() {
        if (sApnDatabase == null) {
            sApnDatabase = new ApnDatabase();
        }
        return sApnDatabase;
    }

    public static boolean doesDatabaseExist() {
        final File dbFile = sContext.getDatabasePath(APN_DATABASE_NAME);
        return dbFile.exists();
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase onCreate");
        }
        // Build the table using defaults (apn info bundled with the app)
        rebuildTables(db);
    }

    @Override
    public void onOpen(final SQLiteDatabase db) {
        super.onOpen(db);
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase onOpen");
        }
    }

    @Override
    public void close() {
        super.close();
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase close");
        }
    }

    private void rebuildTables(final SQLiteDatabase db) {
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase rebuildTables");
        }
        db.execSQL("DROP TABLE IF EXISTS " + APN_TABLE + ";");
        db.execSQL(APN_TABLE_SQL);
        loadApnTable(db);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase onUpgrade");
        }
        rebuildTables(db);
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        if (DEBUG) {
            LogUtil.d(TAG, "ApnDatabase onDowngrade");
        }
        rebuildTables(db);
    }

    /**
     * Load APN table from app resources
     */
    private static void loadApnTable(final SQLiteDatabase db) {
        if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
            LogUtil.v(TAG, "ApnDatabase loadApnTable");
        }
        final Resources r = sContext.getResources();
        final XmlResourceParser parser = r.getXml(R.xml.apns);
        final ApnsXmlProcessor processor = ApnsXmlProcessor.get(parser);
        processor.setApnHandler(apnValues -> db.insert(APN_TABLE, null/*nullColumnHack*/,
                apnValues));
        try {
            processor.process();
        } catch (final Exception e) {
            Log.e(TAG, "Got exception while loading APN database.", e);
        } finally {
            parser.close();
        }
    }

    public static void forceBuildAndLoadApnTables() {
        final SQLiteDatabase db = getApnDatabase().getWritableDatabase();
        db.execSQL("DROP TABLE IF EXISTS " + APN_TABLE);
        // Table(s) always need for JB MR1 for APN support for MMS because JB MR1 throws
        // a SecurityException when trying to access the carriers table (which holds the
        // APNs). Some JB MR2 devices also throw the security exception, so we're building
        // the table for JB MR2, too.
        db.execSQL(APN_TABLE_SQL);

        loadApnTable(db);
    }
}
