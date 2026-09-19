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
 * Apply a DECRYPTED RCC.16 §9.7.1.4 group ICON to the conversation.
 *
 * <p>The icon twin of {@link MlsSubjectApplier}, written at the same time as the receive path
 * rather than weeks later, because the subject's history says what happens otherwise: the whole
 * flow worked end to end, the plaintext was logged, nothing applied it, and the feature read as
 * broken at the crypto layer for weeks. The icon has never had an applier at
 * all, so this is that defect pre-empted rather than repeated.
 *
 * <h2>ONE FILE, AND THE COLUMN POINTS AT IT</h2>
 *
 * <p>The plaintext is written once, to a persistent app-private file under
 * {@code files/mls-group-icons/}, and {@code ConversationColumns.ICON} is set to that file's
 * {@code file://} URI. There is no second copy and no cache involved.
 *
 * <p><b>Why not {@link MediaScratchFileProvider}, which is the obvious choice.</b> It is backed by
 * {@code getCacheDir()} — as is {@code MmsFileProvider}; this app has no persistent app-private
 * content provider — so the system may evict the file and leave the column pointing at nothing,
 * with no way back, because a peer does not resend an icon on demand. The first version of this
 * class wrote BOTH (scratch for display, files/ for durability) and left the durable copy with no
 * reader, which is a verb with no caller wearing a different hat.
 *
 * <p><b>A {@code file://} URI is safe here, checked rather than assumed.</b> The render path
 * accepts it — {@code UriUtil.isLocalResourceUri} admits {@code file}, {@code content} and
 * {@code android.resource}, and {@code AvatarRequest} opens it through the ContentResolver — and
 * the value never leaves the process: its readers are {@code ConversationListItemView},
 * {@code ShareIntentAdapter} (an in-process {@code PersonItemView}) and
 * {@code MultiSelectActionModeCallback}. Nothing puts it in an Intent, so there is no
 * {@code FileUriExposedException} to earn. {@code WidgetConversationProvider.UI_INTENT_EXTRA_ICON}
 * is declared and referenced nowhere. If any of that changes, this choice has to change with it.
 *
 * <p><b>The column has an OWNER now.</b> {@code BugleDatabaseOperations.fillParticipantData} writes
 * the participant-derived avatar only over a value it produced — it asks
 * {@code AvatarUriUtil.isDerivedAvatarUri} of the CURRENT value — so a member add or remove no
 * longer reverts a group's icon. That was silent: the user saw a plausible derived avatar with
 * nothing to say a decrypted icon had been dropped. {@code NAME} had the rule already; the two now
 * agree, and exactly one writer clears each.
 */
public final class GroupIconApplier {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Persistent, app-private, one file per group. NOT the cache. */
    private static final String ICON_DIR = "mls-group-icons";

    private GroupIconApplier() {}

    /**
     * Set the conversation's icon for {@code rcsGroupId} to {@code iconBytes}.
     *
     * <p>Must not run on the main thread — the caller is already on a background thread.
     *
     * @return true if a conversation was found and its icon set
     */
    public static boolean apply(final Context context, final String rcsGroupId,
            final byte[] iconBytes) {
        if (TextUtils.isEmpty(rcsGroupId) || iconBytes == null || iconBytes.length == 0) {
            return false;
        }
        try {
            // THE FILE FIRST, and before the conversation lookup: it is worth keeping even for a
            // group whose conversation we do not hold yet, because the sender will not send the
            // icon again and the roster path may create the conversation moments later.
            final File file = persist(context, rcsGroupId, iconBytes);
            if (file == null) return false;

            final DatabaseWrapper db = DataModel.get().getDatabase();
            // Resolve WITHOUT creating — same rule as the subject applier. An icon can arrive for a
            // group we hold no conversation for, and inventing one from an avatar change would
            // produce a conversation with no participants and no messages.
            final String conversationId = BugleDatabaseOperations
                    .getExistingGroupConversation(db, rcsGroupId);
            if (TextUtils.isEmpty(conversationId)) {
                LogUtil.w(TAG, "GroupIconApplier: decrypted an icon for a group we hold no "
                        + "conversation for (" + rcsGroupId + ") — stored, not applied");
                return false;
            }

            // "jpg" IS COSMETIC HERE, CHECKED RATHER THAN ASSUMED. The obvious objection is that a
            // peer's PNG lands in a .jpg-named file and we have mislabelled it — the F2 question
            // (when it does emit, are the bytes right?) applied to this line. It does not hold:
            // FileProvider.getType() returns null outright ("No need for mime types"), so no
            // consumer takes a MIME from this provider; the extension only names the file on disk
            // and is round-tripped in the URI's own query parameter, so the same file resolves on
            // every later open; and the image pipeline sniffs content. Plumbing the real MIME
            // through from the §7.8.1 FileInfo (which HAS it, as metadata.contentType) would mean
            // widening openStoredIconSubject's return on a path shared with the subject, and would
            // buy nothing. Recorded so the next reader does not re-derive it or "fix" it.
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
            // Never let an avatar take down the inbound path. A missing icon is cosmetic; an
            // exception here would propagate into control-message handling.
            LogUtil.w(TAG, "GroupIconApplier: failed to apply the decrypted icon for "
                    + rcsGroupId, t);
            return false;
        }
    }

    /**
     * Write the icon and hand back the file the column will point at.
     *
     * <p>Not best-effort any more: this file IS the displayed icon, so a failure here means there
     * is nothing to display and the caller must stop rather than write a column pointing at a file
     * that does not exist. When it was a spare copy beside a scratch file, swallowing the failure
     * was right; it is not right now, and the return type is what says so.
     *
     * @return the file, or {@code null} if it could not be written
     */
    private static File persist(final Context context, final String rcsGroupId,
            final byte[] iconBytes) {
        try {
            final File dir = new File(context.getFilesDir(), ICON_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                LogUtil.w(TAG, "GroupIconApplier: cannot create " + dir);
                return null;
            }
            // The group id is 32 hex characters on this transport, but it arrives from the wire, so
            // it is sanitised rather than trusted: anything outside [A-Za-z0-9_-] would otherwise
            // be able to name a path.
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
