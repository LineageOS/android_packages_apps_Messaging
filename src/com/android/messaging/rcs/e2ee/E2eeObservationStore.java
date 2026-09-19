/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.ContentValues;
import android.database.Cursor;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;

/**
 * Writes {@link E2eeObservation} outcomes to messaging.db: the row's scheme stamp and the
 * conversation's encryption bits. The only writer of those bits for observed traffic. Runs on
 * the DataModel action thread.
 */
public final class E2eeObservationStore {
    private E2eeObservationStore() {}

    /**
     * Applies a send status to local row {@code localId}. Returns the row's conversation id, or
     * null when the row is gone.
     */
    public static String applySentStatus(final DatabaseWrapper db, final String localId,
            final boolean sent, final String scheme, final E2eeObservation.Source source) {
        final String sql = "SELECT m." + MessageColumns.CONVERSATION_ID
                + ", m." + MessageColumns.RCS_E2EE_SCHEME_ID
                + ", c." + ConversationColumns.RCS_GROUP_ID
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " m"
                + " JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c"
                + " ON m." + MessageColumns.CONVERSATION_ID
                + " = c." + ConversationColumns._ID
                + " WHERE m." + MessageColumns._ID + " = ? LIMIT 1";
        final String conversationId;
        final String rowScheme;
        final boolean isGroup;
        try (Cursor c = db.rawQuery(sql, new String[] { localId })) {
            if (c == null || !c.moveToFirst()) return null;
            conversationId = c.getString(0);
            rowScheme = c.getString(1);
            final String groupId = c.getString(2);
            isGroup = groupId != null && !groupId.isEmpty();
        }
        final E2eeObservation.Outcome o =
                E2eeObservation.forSentStatus(source, sent, scheme, isGroup, rowScheme);
        if (o.rewriteRow) {
            final ContentValues v = new ContentValues();
            if (o.rowScheme == null) {
                v.putNull(MessageColumns.RCS_E2EE_SCHEME_ID);
            } else {
                v.put(MessageColumns.RCS_E2EE_SCHEME_ID, o.rowScheme);
            }
            BugleDatabaseOperations.updateMessageRow(db, localId, v);
        }
        applyToConversation(db, conversationId, o);
        return conversationId;
    }

    /** Applies an inbound message's tag to its conversation's bits. */
    public static void applyInbound(final DatabaseWrapper db, final String conversationId,
            final String scheme, final boolean isGroup) {
        applyToConversation(db, conversationId, E2eeObservation.forInbound(scheme, isGroup));
    }

    private static void applyToConversation(final DatabaseWrapper db,
            final String conversationId, final E2eeObservation.Outcome o) {
        if (conversationId == null || o.scytale == E2eeObservation.BitChange.NONE) return;
        final EncryptionProtocolBits prior = EncryptionProtocolBits.fromColumnValue(
                BugleDatabaseOperations.getConversationEncryptionProtocol(db, conversationId));
        final EncryptionProtocolBits next = E2eeObservation.apply(prior, o);
        if (!next.equals(prior)) {
            BugleDatabaseOperations.setConversationEncryptionProtocol(
                    db, conversationId, next.toColumnValue());
        }
    }
}
