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
package com.android.messaging.datamodel.action;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.rcs.e2ee.MlsResendLedger;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * Write a single emoji-reaction effect onto the {@code rcs_reactions}
 * side-table, then refresh the conversation cursor so the reaction-chip row on
 * the TARGET message redraws.
 *
 * <p>One action multiplexes BOTH directions, mirroring how
 * {@link UpdateRcsMessageStatusAction} multiplexes KIND_STATUS / KIND_IMDN /
 * KIND_GROUP_IMDN through a single action:
 * <ul>
 *   <li>INBOUND — the provider's {@code onIncomingReaction} callback passes the
 *       real reactor {@code fromUri} (a group member or the 1:1 peer);
 *   <li>OUTBOUND (optimistic) — the UI/transport seam passes
 *       {@link RcsMessageStore#SELF_REACTOR_URI} via {@link #recordSelfReaction}
 *       so our own chip appears instantly, before the binder send completes.
 * </ul>
 *
 * <p>The row is keyed by (target rcs message-id, reactor uri) — the same IMDN
 * id space {@code findLocalIdByRcsMessageId} resolves — so a reaction that
 * arrives before its target's local row exists is tolerated (no FK), and 1:1 vs
 * group is handled by construction (group = N reactor rows aggregated by
 * GROUP BY in {@link RcsMessageStore#readReactions}).
 *
 * <p><b>Specifically the ROOT of that id space</b>. A peer names whichever
 * message id it SAW, and a resend carries a fresh one that never gets a chat row — so the
 * reported id is run through {@link MlsResendLedger#rootOf} before it is used as a key. The
 * render side joins the side table against {@code messages.rcs_message_id}, which only ever
 * holds the root, so a row keyed on anything else is written and then invisible.
 */
public class UpdateRcsReactionAction extends Action implements Parcelable {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;

    private static final String KEY_TARGET_RCS_ID = "target_rcs_id";
    private static final String KEY_REACTOR_URI = "reactor_uri";
    private static final String KEY_EMOJI = "emoji";
    private static final String KEY_ADD = "add";
    private static final String KEY_TIMESTAMP = "timestamp";

    public UpdateRcsReactionAction(final String targetRcsId, final String reactorUri,
            final String emoji, final boolean add, final long timestamp) {
        actionParameters.putString(KEY_TARGET_RCS_ID, targetRcsId);
        actionParameters.putString(KEY_REACTOR_URI, reactorUri);
        actionParameters.putString(KEY_EMOJI, emoji);
        actionParameters.putBoolean(KEY_ADD, add);
        actionParameters.putLong(KEY_TIMESTAMP, timestamp);
    }

    /**
     * Write the optimistic self-row for a reaction WE are sending. The
     * UI/transport seam calls this so the chip appears instantly; the actual
     * binder send (ProviderTransport.sendReaction / sendGroupReaction) is fired
     * separately off the main thread.
     */
    public static void recordSelfReaction(final String targetRcsId, final String emoji,
            final boolean add) {
        new UpdateRcsReactionAction(targetRcsId, RcsMessageStore.SELF_REACTOR_URI,
                emoji, add, System.currentTimeMillis()).start();
    }

    @Override
    protected Object executeAction() {
        final String reportedTargetRcsId = actionParameters.getString(KEY_TARGET_RCS_ID);
        final String reactorUri = actionParameters.getString(KEY_REACTOR_URI);
        final String emoji = actionParameters.getString(KEY_EMOJI);
        final boolean add = actionParameters.getBoolean(KEY_ADD);
        final long ts = actionParameters.getLong(KEY_TIMESTAMP);
        if (TextUtils.isEmpty(reportedTargetRcsId) || TextUtils.isEmpty(reactorUri)) {
            LogUtil.w(TAG, "UpdateRcsReactionAction: empty target/reactor; dropping");
            return null;
        }

        // A REACTION NAMES THE MESSAGE THE PEER SAW, WHICH MAY BE A RESEND (the
        // sibling of the receipt-lookup problem one organ over — and this one is a WRITE key,
        // not a lookup).
        //
        // A resend goes out under a FRESH rcs_message_id (invariant 62 forbids replaying a
        // consumed generation) and NEVER gets a chat row: the new id lives only in mls_resends.
        // So a peer that reacts to a message we resent names the resend's id, and this action
        // used to store the row under it verbatim — while the render side JOINs
        //     rcs_reactions.target_rcs_message_id = messages.rcs_message_id
        // (ConversationMessageData), which only ever holds the ROOT. The write SUCCEEDED, the chip
        // never appeared, and nothing logged a word: an upsert reported as success because it was
        // one. Worse than the receipt case it mirrors, which at least printed "no local row".
        //
        // ROOTED ONCE, AT THE TOP, ON PURPOSE. Both the side-table key below AND the notify
        // lookup at the bottom read this id, and they must agree — a fix that rooted only the
        // write would store the row correctly and then fail to refresh the conversation it
        // belongs to, which is a stranger state than the bug.
        //
        // UNCONDITIONAL, unlike the lookup's fallback, and the difference is real rather than
        // stylistic: a lookup can try the exact id and fall back on a miss, but a WRITE has no
        // miss to detect — the key has to be decided before the row exists. That is safe here
        // because rootOf() returns its input unchanged for anything that is not a recorded
        // resend, so this is the identity for all three of our DB-sourced callers (the iOS
        // tapback path, which already resolves through messages.rcs_message_id, and both
        // self-reaction paths, which read the row's own id off the cursor).
        //
        // BEHAVIOUR CHANGE, STATED RATHER THAN DISCOVERED: the side table's PRIMARY KEY is
        // (target, reactor) with CONFLICT_REPLACE, so rooting changes what collides. A peer that
        // reacted to the original AND to a resend of it used to leave TWO rows and now leaves
        // ONE. That is the correct answer — it is one message and one reactor — but it is a
        // different answer, so it is written down here rather than found later.
        final String targetRcsId = MlsResendLedger.rootOf(reportedTargetRcsId);
        if (!TextUtils.equals(targetRcsId, reportedTargetRcsId)) {
            LogUtil.i(TAG, "UpdateRcsReactionAction: " + reportedTargetRcsId + " is a RESEND; "
                    + "keying this reaction on its chain root " + targetRcsId + " instead, which "
                    + "is the id the message's row carries and the one the chip query joins on "
                    + ".");
        }

        final DatabaseWrapper db = DataModel.get().getDatabase();
        // Canonicalize an inbound member URI the same way
        // UpdateRcsMessageStatusAction does (PhoneUtils.getCanonicalBySimLocale)
        // so the per-reactor key matches stored participant URIs. Our own
        // sentinel SELF_REACTOR_URI is left untouched.
        String canonicalReactor = reactorUri;
        if (!RcsMessageStore.SELF_REACTOR_URI.equals(reactorUri)) {
            try {
                final String e164 = PhoneUtils.getDefault().getCanonicalBySimLocale(reactorUri);
                if (!TextUtils.isEmpty(e164)) {
                    canonicalReactor = e164;
                }
            } catch (final Exception ignored) {
                // Fall back to the raw reactorUri.
            }
        }

        db.beginTransaction();
        try {
            if (add) {
                RcsMessageStore.upsertReaction(db, targetRcsId, canonicalReactor, emoji, ts);
            } else {
                RcsMessageStore.removeReaction(db, targetRcsId, canonicalReactor);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // Refresh the conversation cursor so the chip row redraws. Resolve the
        // local id of the TARGET to find its conversation.
        final String localId = RcsMessageStore.findLocalIdByRcsMessageId(db, targetRcsId);
        if (localId != null) {
            final String convId =
                    BugleDatabaseOperations.getConversationIdFromMessageId(db, localId);
            if (convId != null) {
                MessagingContentProvider.notifyMessagesChanged(convId);
            }
        }
        return null;
    }

    private UpdateRcsReactionAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<UpdateRcsReactionAction> CREATOR =
            new Parcelable.Creator<>() {
        @Override
        public UpdateRcsReactionAction createFromParcel(final Parcel in) {
            return new UpdateRcsReactionAction(in);
        }

        @Override
        public UpdateRcsReactionAction[] newArray(final int size) {
            return new UpdateRcsReactionAction[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
