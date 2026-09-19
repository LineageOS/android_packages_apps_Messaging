/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.util.LogUtil;

/**
 * Applies an RCC.16 §9.7.1.5 group subject to the existing conversation: a decrypted one on a
 * receiver, and the sender's own once the provider accepted it, since the sender cannot decrypt its
 * own private message. See docs/mls/metadata.md.
 */
public final class MlsSubjectApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private MlsSubjectApplier() {}

    /**
     * Rename the conversation for {@code rcsGroupId} to {@code subject}. Not on the main thread.
     *
     * @return true if a conversation was found and renamed
     */
    public static boolean apply(final String rcsGroupId, final String subject) {
        if (TextUtils.isEmpty(rcsGroupId) || TextUtils.isEmpty(subject)) return false;
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            // Resolve without creating: a rename is no reason to invent an empty conversation, and
            // the roster paths that create one apply the name themselves.
            final String conversationId = BugleDatabaseOperations
                    .getExistingGroupConversation(db, rcsGroupId);
            if (TextUtils.isEmpty(conversationId)) {
                LogUtil.w(TAG, "MlsSubjectApplier: decrypted a subject for a group we hold no "
                        + "conversation for (" + rcsGroupId + ") — not applied");
                return false;
            }
            db.beginTransaction();
            try {
                BugleDatabaseOperations.renameGroupConversation(db, conversationId, subject);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            LogUtil.i(TAG, "MlsSubjectApplier: applied decrypted group subject to conversation "
                    + MlsConversationKey.forLog(conversationId) + " (" + rcsGroupId + ")");
            return true;
        } catch (final Throwable t) {
            // A rename must never break the inbound control path.
            LogUtil.w(TAG, "MlsSubjectApplier: failed to apply the decrypted subject for "
                    + rcsGroupId, t);
            return false;
        }
    }
}
