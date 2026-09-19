/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.datamodel.action;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.rcs.RcsInboundConfirmation;
import com.android.messaging.rcs.RcsMessageStore;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * Writes one reaction change to {@code rcs_reactions} and refreshes the target's conversation.
 * Serves both an inbound reaction (the real reactor) and our own, recorded before the send with
 * {@link RcsMessageStore#SELF_REACTOR_URI}. Rows are keyed by the target's wire id, so a reaction
 * may arrive before its target.
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
     * Records our own reaction so the chip appears at once; the caller sends it separately, off the
     * main thread.
     */
    public static void recordSelfReaction(final String targetRcsId, final String emoji,
            final boolean add) {
        new UpdateRcsReactionAction(targetRcsId, RcsMessageStore.SELF_REACTOR_URI,
                emoji, add, System.currentTimeMillis()).start();
    }

    /** Confirms {@code ticket} to the provider that delivered this, once it is stored. */
    public UpdateRcsReactionAction confirming(
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        RcsInboundConfirmation.attach(ticket, actionParameters);
        return this;
    }

    @Override
    protected Object executeAction() {
        // Confirmed after the store has returned: a store that throws confirms nothing,
        // and the provider offers the message again.
        final Object stored = store();
        RcsInboundConfirmation.afterStore(actionParameters, "UpdateRcsReactionAction");
        return stored;
    }

    private Object store() {
        final String reportedTargetRcsId = actionParameters.getString(KEY_TARGET_RCS_ID);
        final String reactorUri = actionParameters.getString(KEY_REACTOR_URI);
        final String emoji = actionParameters.getString(KEY_EMOJI);
        final boolean add = actionParameters.getBoolean(KEY_ADD);
        final long ts = actionParameters.getLong(KEY_TIMESTAMP);
        if (TextUtils.isEmpty(reportedTargetRcsId) || TextUtils.isEmpty(reactorUri)) {
            LogUtil.w(TAG, "UpdateRcsReactionAction: empty target/reactor; dropping");
            return null;
        }

        final String targetRcsId = reportedTargetRcsId;

        final DatabaseWrapper db = DataModel.get().getDatabase();
        // Canonicalize a member URI so the per-reactor key matches stored participant URIs; the
        // self sentinel is left as is.
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

        // Refresh the target's conversation so the chip row redraws.
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
