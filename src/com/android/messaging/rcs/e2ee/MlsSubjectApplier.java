/*
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
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.util.LogUtil;

/**
 * Apply a DECRYPTED RCC.16 §9.7.1.5 group subject to the conversation.
 *
 * <h2>The bug this closes</h2>
 *
 * <p>The whole encrypted-subject flow worked — device-verified end to end: the sender encrypts and
 * commits the subject, the key rides in-band in the commit's private message, the receiver stores
 * it, verifies the Annex C.1 commitment, and decrypts. And then <b>both</b> receive paths did this
 * and nothing else:
 *
 * <pre>
 *   LogUtil.i(TAG, "... DECRYPTED group subject ... = \"" + subject + "\"");
 * </pre>
 *
 * <p><b>The plaintext was written to logcat and dropped.</b> No conversation was ever renamed, so no
 * subject ever appeared — for us or for any peer. The crypto was never the problem; the last two
 * lines were missing.
 *
 * <h2>Why it looked like a working feature for so long</h2>
 *
 * <p>Every layer reports success and the log line is triumphant: it prints the correct decrypted
 * subject. Reading the log, the flow looks complete. Only the CONVERSATION shows otherwise, and
 * nothing was asserting on that. The regression test worth having is the cheapest one — set a
 * subject, then read the conversation's name back.
 *
 * <p>Note also what this is NOT: the sender cannot open its own subject, because the key rides in an
 * MLS private message and MLS gives you no way to decrypt your own. A sender showing the old name is
 * expected and is not evidence of this bug — chasing that cost a wrong diagnosis before the peers
 * were checked.
 */
public final class MlsSubjectApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private MlsSubjectApplier() {}

    /**
     * Rename the conversation for {@code rcsGroupId} to {@code subject}.
     *
     * <p>Must not run on the main thread — both callers are already on a background thread.
     *
     * @return true if a conversation was found and renamed
     */
    public static boolean apply(final String rcsGroupId, final String subject) {
        if (TextUtils.isEmpty(rcsGroupId) || TextUtils.isEmpty(subject)) return false;
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            // Resolve WITHOUT creating. A subject can arrive for a group whose conversation we do
            // not hold yet; creating one here from a rename would invent a conversation with no
            // participants and no messages, which is worse than dropping the rename — the roster
            // paths own creation and will apply the name when they run.
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
                    + conversationId + " (" + rcsGroupId + ")");
            return true;
        } catch (final Throwable t) {
            // Never let a rename take down the inbound path. A missing subject is cosmetic; an
            // exception here would propagate into control-message handling.
            LogUtil.w(TAG, "MlsSubjectApplier: failed to apply the decrypted subject for "
                    + rcsGroupId, t);
            return false;
        }
    }
}
