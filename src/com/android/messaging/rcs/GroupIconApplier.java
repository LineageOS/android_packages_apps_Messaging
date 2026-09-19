/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Applies a decrypted group icon (RCC.16 §9.7.1.4) to the conversation. The icon is written once
 * to an app-private file under {@code files/mls-group-icons/} and {@code ConversationColumns.ICON}
 * points at its {@code file://} URI. See docs/rcs/groups.md.
 *
 * <p>Not the cache: an evicted file would leave the column pointing at nothing, and a peer does
 * not resend an icon. The {@code file://} URI is safe only while the value never leaves the
 * process (no reader puts it in an Intent).
 */
public final class GroupIconApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Persistent, app-private, one file per group. */
    private static final String ICON_DIR = "mls-group-icons";

    private GroupIconApplier() {}

    /**
     * Sets the icon of the conversation for {@code rcsGroupId}. Must not run on the main thread.
     *
     * @return true if a conversation was found and its icon set
     */
    public static boolean apply(final Context context, final String rcsGroupId,
            final byte[] iconBytes) {
        if (TextUtils.isEmpty(rcsGroupId) || iconBytes == null || iconBytes.length == 0) {
            return false;
        }
        try {
            // The file is written before the lookup and kept even with no conversation yet: the
            // roster path may create it moments later and the sender will not send the icon again.
            final File file = persist(context, rcsGroupId, iconBytes);
            if (file == null) return false;

            final DatabaseWrapper db = DataModel.get().getDatabase();
            // Resolve without creating.
            final String conversationId = BugleDatabaseOperations
                    .getExistingGroupConversation(db, rcsGroupId);
            if (TextUtils.isEmpty(conversationId)) {
                LogUtil.w(TAG, "GroupIconApplier: decrypted an icon for a group we hold no "
                        + "conversation for (" + rcsGroupId + ") — stored, not applied");
                return false;
            }

            final ContentValues values = new ContentValues();
            values.put(ConversationColumns.ICON, Uri.fromFile(file).toString());
            db.beginTransaction();
            try {
                BugleDatabaseOperations.updateConversationRowIfExists(db, conversationId, values);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            LogUtil.i(TAG, "GroupIconApplier: applied decrypted group icon to conversation "
                    + conversationId + " (" + rcsGroupId + ", " + iconBytes.length + "B) at "
                    + ICON_DIR);
            return true;
        } catch (final Throwable t) {
            // A missing icon is cosmetic; never throw into control-message handling.
            LogUtil.w(TAG, "GroupIconApplier: failed to apply the decrypted icon for "
                    + rcsGroupId, t);
            return false;
        }
    }

    /**
     * Writes the icon file the column will point at.
     *
     * @return the file, or {@code null} if it could not be written; the caller must then stop
     */
    private static File persist(final Context context, final String rcsGroupId,
            final byte[] iconBytes) {
        try {
            final File dir = new File(context.getFilesDir(), ICON_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                LogUtil.w(TAG, "GroupIconApplier: cannot create " + dir);
                return null;
            }
            // The group id comes from the wire, so it is sanitised before naming a file.
            final File f = new File(dir, rcsGroupId.replaceAll("[^A-Za-z0-9_-]", "_"));
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(iconBytes);
            }
            return f;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "GroupIconApplier: could not write the icon for " + rcsGroupId, t);
            return null;
        }
    }
}
