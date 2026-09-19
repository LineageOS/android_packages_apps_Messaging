/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2026 The LineageOS Project
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
package com.android.messaging.datamodel;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.android.messaging.Factory;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;

public class DatabaseUpgradeHelper {
    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;

    public void doOnUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        Assert.isTrue(newVersion >= oldVersion);
        if (oldVersion == newVersion) {
            return;
        }

        LogUtil.i(TAG, "Database upgrade started from version " + oldVersion + " to " + newVersion);
        try {
            doUpgradeWithExceptions(db, oldVersion, newVersion);
            LogUtil.i(TAG, "Finished database upgrade");
        } catch (final Exception ex) {
            LogUtil.e(TAG, "Failed to perform db upgrade from version " +
                    oldVersion + " to version " + newVersion, ex);
            DatabaseHelper.rebuildTables(db);
        }
    }

    public void doUpgradeWithExceptions(final SQLiteDatabase db, final int oldVersion,
            final int newVersion) throws Exception {
        int currentVersion = oldVersion;
        if (currentVersion < 2) {
            currentVersion = upgradeToVersion2(db);
        }
        if (currentVersion < 3) {
            currentVersion = upgradeToVersion3(db);
        }
        if (currentVersion < 4) {
            currentVersion = upgradeToVersion4(db);
        }
        // Rebuild all the views
        final Context context = Factory.get().getApplicationContext();
        DatabaseHelper.dropAllViews(db);
        DatabaseHelper.rebuildAllViews(new DatabaseWrapper(context, db));
        // Finally, check if we have arrived at the final version.
        checkAndUpdateVersionAtReleaseEnd(currentVersion, Integer.MAX_VALUE, newVersion);
    }

    private int upgradeToVersion2(final SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN " +
                DatabaseHelper.ConversationColumns.IS_ENTERPRISE + " INT DEFAULT(0)");
        LogUtil.i(TAG, "Ugraded database to version 2");
        return 2;
    }

    // The RCS client's schema additions. Frozen once shipped (see upgradeToVersion4): additive
    // only, so a failure part-way leaves the database readable at its version.
    private int upgradeToVersion3(final SQLiteDatabase db) {
        {   // the RCS columns and index on messages
            final String t = DatabaseHelper.MESSAGES_TABLE;
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.TRANSPORT_TYPE + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_MESSAGE_ID + " TEXT");
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_STATUS + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_DELIVERED_TIMESTAMP + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_DISPLAYED_TIMESTAMP + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + t + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_CONTRIBUTION_ID + " TEXT");
            db.execSQL("CREATE INDEX index_" + t + "_rcs_id ON " + t + "("
                    + DatabaseHelper.MessageColumns.RCS_MESSAGE_ID + ")");
        }
        {   // the group mapping on conversations
            final String c = DatabaseHelper.CONVERSATIONS_TABLE;
            db.execSQL("ALTER TABLE " + c + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.RCS_GROUP_ID + " TEXT");
            db.execSQL("CREATE INDEX index_" + c + "_rcs_group_id ON " + c + "("
                    + DatabaseHelper.ConversationColumns.RCS_GROUP_ID + ")");
        }
        {   // the group-UI columns
            final String c = DatabaseHelper.CONVERSATIONS_TABLE;
            db.execSQL("ALTER TABLE " + c + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.NEEDS_ROSTER_REFILL + " INT DEFAULT(0)");
        }
        {   // the per-member receipts side table
            final String t = DatabaseHelper.RCS_GROUP_RECEIPTS_TABLE;
            db.execSQL("CREATE TABLE " + t + " ("
                    + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID + " INT NOT NULL, "
                    + DatabaseHelper.RcsGroupReceiptColumns.PARTICIPANT_URI + " TEXT NOT NULL, "
                    + DatabaseHelper.RcsGroupReceiptColumns.DELIVERED_TIMESTAMP
                    + " INT DEFAULT(0), "
                    + DatabaseHelper.RcsGroupReceiptColumns.DISPLAYED_TIMESTAMP
                    + " INT DEFAULT(0), "
                    + "PRIMARY KEY (" + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID + ", "
                    + DatabaseHelper.RcsGroupReceiptColumns.PARTICIPANT_URI + "), "
                    + "FOREIGN KEY (" + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID
                    + ") REFERENCES " + DatabaseHelper.MESSAGES_TABLE + "("
                    + DatabaseHelper.MessageColumns._ID + ") ON DELETE CASCADE "
                    + ");");
            db.execSQL("CREATE INDEX index_" + t + "_message_id ON " + t + "("
                    + DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID + ")");
        }
        {   // the reactions side table
            final String t = DatabaseHelper.RCS_REACTIONS_TABLE;
            db.execSQL("CREATE TABLE " + t + " ("
                    + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID + " TEXT NOT NULL, "
                    + DatabaseHelper.RcsReactionColumns.REACTOR_URI + " TEXT NOT NULL, "
                    + DatabaseHelper.RcsReactionColumns.EMOJI + " TEXT NOT NULL, "
                    + DatabaseHelper.RcsReactionColumns.TIMESTAMP + " INT DEFAULT(0), "
                    + "PRIMARY KEY (" + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID
                    + ", " + DatabaseHelper.RcsReactionColumns.REACTOR_URI + ")"
                    + ");");
            db.execSQL("CREATE INDEX index_" + t + "_target ON " + t + "("
                    + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID + ")");
        }
        {   // the per-message E2EE scheme id
            db.execSQL("ALTER TABLE " + DatabaseHelper.MESSAGES_TABLE + " ADD COLUMN "
                    + DatabaseHelper.MessageColumns.RCS_E2EE_SCHEME_ID + " TEXT");
        }
        {   // the per-conversation encryption-protocol bits
            db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.ENCRYPTION_PROTOCOL + " INT DEFAULT(0)");
        }
        {   // the self-left marker
            db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.RCS_SELF_LEFT + " INT DEFAULT(0)");
        }
        LogUtil.i(TAG, "Upgraded database to version 3");
        return 3;
    }

    // The MLS schema additions; a device may sit at version 3 without MLS. The statements are
    // spelled out rather than shared with DatabaseHelper so a later schema change cannot alter
    // what this shipped upgrade does.
    private int upgradeToVersion4(final SQLiteDatabase db) {
        {   // the resend side table
            db.execSQL("CREATE TABLE " + DatabaseHelper.MLS_RESENDS_TABLE + " ("
                    + DatabaseHelper.MlsResendColumns.RCS_MESSAGE_ID
                    + " TEXT PRIMARY KEY NOT NULL, "
                    + DatabaseHelper.MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + " TEXT NOT NULL, "
                    + DatabaseHelper.MlsResendColumns.MANUAL_RESEND_OF + " TEXT NOT NULL, "
                    + DatabaseHelper.MlsResendColumns.RECIPIENT_ADDRESS + " TEXT, "
                    + DatabaseHelper.MlsResendColumns.RECIPIENT_CLIENT_ID + " TEXT, "
                    + DatabaseHelper.MlsResendColumns.FTD_RESEND_COUNT
                    + " INT DEFAULT(0) NOT NULL, "
                    + DatabaseHelper.MlsResendColumns.CONVERSATION_KEY + " TEXT, "
                    + DatabaseHelper.MlsResendColumns.TIMESTAMP + " INT DEFAULT(0) NOT NULL"
                    + ");");
            db.execSQL("CREATE INDEX index_" + DatabaseHelper.MLS_RESENDS_TABLE + "_original ON "
                    + DatabaseHelper.MLS_RESENDS_TABLE + "("
                    + DatabaseHelper.MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + ")");
            db.execSQL("CREATE INDEX index_" + DatabaseHelper.MLS_RESENDS_TABLE + "_recipient ON "
                    + DatabaseHelper.MLS_RESENDS_TABLE + "("
                    + DatabaseHelper.MlsResendColumns.RECIPIENT_ADDRESS + ")");
        }
        {   // the re-upgrade counters
            db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.MLS_LAST_UNEXPECTED_DOWNGRADE
                    + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.MLS_REUPGRADE_ATTEMPTS
                    + " INT DEFAULT(0)");
            db.execSQL("ALTER TABLE " + DatabaseHelper.CONVERSATIONS_TABLE + " ADD COLUMN "
                    + DatabaseHelper.ConversationColumns.MLS_EAGERLY_DOWNGRADED
                    + " INT DEFAULT(0)");
        }
        LogUtil.i(TAG, "Upgraded database to version 4");
        return 4;
    }

    /**
     * Checks db version correctness at the end of each milestone release. If target database
     * version lies beyond the version range that the current release may handle, we snap the
     * current version to the end of the release, so that we may go on to the next release' upgrade
     * path. Otherwise, if target version is within reach of the current release, but we are not
     * at the target version, then throw an exception to force a table rebuild.
     */
    private int checkAndUpdateVersionAtReleaseEnd(final int currentVersion,
            final int maxVersionForRelease, final int targetVersion) throws Exception {
        if (maxVersionForRelease < targetVersion) {
            // Target version is beyond the current release. Snap to max version for the
            // current release so we can go on to the upgrade path for the next release.
            return maxVersionForRelease;
        }

        // If we are here, this means the current release' upgrade handler should upgrade to
        // target version...
        if (currentVersion != targetVersion) {
            // No more upgrade handlers. So we can't possibly upgrade to the final version.
            throw new Exception("Missing upgrade handler from version " +
                    currentVersion + " to version " + targetVersion);
        }
        // Upgrade succeeded.
        return targetVersion;
    }

    public void onDowngrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        DatabaseHelper.rebuildTables(db);
        LogUtil.e(TAG, "Database downgrade requested for version " +
                oldVersion + " version " + newVersion + ", forcing db rebuild!");
    }
}
