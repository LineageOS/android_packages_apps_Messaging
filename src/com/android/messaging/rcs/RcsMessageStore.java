/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the RCS columns and side tables. An RCS message is inserted through the
 * existing SMS-shaped {@code MessageData} path, whose positional insert is left unchanged; the RCS
 * columns are then written with a column-agnostic
 * {@code BugleDatabaseOperations.updateMessageRow}. The conversation projection is not widened
 * either; the renderer reads RCS metadata on demand through {@link #readByLocalId}.
 */
public final class RcsMessageStore {
    private RcsMessageStore() {}

    // Column names, kept in sync with DatabaseHelper.MessageColumns.
    public static final String COLUMN_TRANSPORT_TYPE = "transport_type";
    public static final String COLUMN_RCS_MESSAGE_ID = "rcs_message_id";
    public static final String COLUMN_RCS_STATUS = "rcs_status";
    public static final String COLUMN_RCS_DELIVERED_TS = "rcs_delivered_timestamp";
    public static final String COLUMN_RCS_DISPLAYED_TS = "rcs_displayed_timestamp";
    public static final String COLUMN_RCS_CONTRIBUTION_ID = "rcs_contribution_id";

    // Reaction side-table column names, kept in sync with RcsReactionColumns.
    public static final String COLUMN_REACTION_TARGET = "target_rcs_message_id";
    public static final String COLUMN_REACTION_REACTOR = "reactor_uri";
    public static final String COLUMN_REACTION_EMOJI = "emoji";

    /** Sentinel reactor_uri for a reaction we sent (so tapping again removes it). */
    public static final String SELF_REACTOR_URI = "self";

    /** Snapshot of the RCS metadata for one local message row. */
    public static final class RcsMeta {
        public final String localMessageId;
        public final int transportType;
        @Nullable public final String rcsMessageId;
        public final int rcsStatus;
        public final long deliveredTs;
        public final long displayedTs;

        RcsMeta(final String localMessageId, final int transportType,
                @Nullable final String rcsMessageId, final int rcsStatus,
                final long deliveredTs, final long displayedTs) {
            this.localMessageId = localMessageId;
            this.transportType = transportType;
            this.rcsMessageId = rcsMessageId;
            this.rcsStatus = rcsStatus;
            this.deliveredTs = deliveredTs;
            this.displayedTs = displayedTs;
        }

        public boolean isRcs() {
            return transportType == RcsConstants.TRANSPORT_RCS;
        }
    }

    /** ContentValues marking a row as RCS with its server message id + status. */
    public static ContentValues rcsMetaValues(@Nullable final String rcsMessageId,
            final int rcsStatus) {
        final ContentValues v = new ContentValues();
        v.put(COLUMN_TRANSPORT_TYPE, RcsConstants.TRANSPORT_RCS);
        if (rcsMessageId != null) {
            v.put(COLUMN_RCS_MESSAGE_ID, rcsMessageId);
        }
        v.put(COLUMN_RCS_STATUS, rcsStatus);
        return v;
    }

    /** The local {@code messages._id} for a wire message id, or null. Indexed. */
    @Nullable
    public static String findLocalIdByRcsMessageId(final DatabaseWrapper db,
            final String rcsMessageId) {
        if (rcsMessageId == null) {
            return null;
        }
        try (Cursor c = db.query(DatabaseHelper.MESSAGES_TABLE,
                new String[] { MessageColumns._ID },
                COLUMN_RCS_MESSAGE_ID + "=?", new String[] { rcsMessageId },
                null, null, null, "1")) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    /**
     * The {@code rcs_message_id} a quoted-text tapback refers to, among the {@code limit} most
     * recent RCS messages in the conversation. An exact match on the untrimmed text wins; otherwise
     * one trailing ellipsis (the sender's truncation marker) is stripped and prefix or suffix
     * matches are tried. Returns null unless exactly one message matches.
     */
    @Nullable
    public static String findReactionTargetByQuotedText(final DatabaseWrapper db,
            final String conversationId, final String quotedText, final int limit) {
        if (db == null || TextUtils.isEmpty(conversationId) || TextUtils.isEmpty(quotedText)) {
            return null;
        }
        final String sql =
                "SELECT m." + COLUMN_RCS_MESSAGE_ID + ", p." + DatabaseHelper.PartColumns.TEXT
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " AS m"
                + " JOIN " + DatabaseHelper.PARTS_TABLE + " AS p"
                + " ON p." + DatabaseHelper.PartColumns.MESSAGE_ID + " = m." + MessageColumns._ID
                + " WHERE m." + MessageColumns.CONVERSATION_ID + " = ?"
                + " AND m." + COLUMN_RCS_MESSAGE_ID + " IS NOT NULL"
                + " AND p." + DatabaseHelper.PartColumns.TEXT + " IS NOT NULL"
                + " ORDER BY m." + MessageColumns.RECEIVED_TIMESTAMP + " DESC"
                + " LIMIT " + Math.max(1, limit);
        final java.util.LinkedHashSet<String> exact = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> fuzzy = new java.util.LinkedHashSet<>();
        final String stripped = stripOneTrailingEllipsis(quotedText);
        try (Cursor c = db.rawQuery(sql, new String[] { conversationId })) {
            while (c != null && c.moveToNext()) {
                final String rcsId = c.getString(0);
                final String text = c.getString(1);
                if (rcsId == null || text == null) {
                    continue;
                }
                if (text.equals(quotedText)) {
                    exact.add(rcsId);
                } else if (stripped != null && (text.startsWith(stripped)
                        || text.endsWith(stripped) || text.endsWith(quotedText))) {
                    fuzzy.add(rcsId);
                }
            }
        }
        if (exact.size() == 1) {
            return exact.iterator().next();
        }
        if (exact.isEmpty() && fuzzy.size() == 1) {
            return fuzzy.iterator().next();
        }
        return null;
    }

    private static String stripOneTrailingEllipsis(final String s) {
        return (s != null && s.endsWith("…")) ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The text of one of our sent messages, for an RCC.16 §10 resend to a peer that failed to
     * decrypt it. Text parts only; a media resend needs the file re-uploaded and re-keyed.
     *
     * @return the message text, or null if unknown, empty, or not a text message
     */
    @Nullable
    public static String findTextByRcsMessageId(final String rcsMessageId) {
        if (TextUtils.isEmpty(rcsMessageId)) {
            return null;
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        if (db == null) {
            return null;
        }
        final String sql =
                "SELECT p." + DatabaseHelper.PartColumns.TEXT
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " AS m"
                + " JOIN " + DatabaseHelper.PARTS_TABLE + " AS p"
                + " ON p." + DatabaseHelper.PartColumns.MESSAGE_ID + " = m." + MessageColumns._ID
                + " WHERE m." + COLUMN_RCS_MESSAGE_ID + " = ?"
                + " AND p." + DatabaseHelper.PartColumns.TEXT + " IS NOT NULL"
                // Outgoing rows only (statuses below BUGLE_STATUS_INCOMING_COMPLETE): this is
                // resend material, and resending a received message would put another member's
                // words under our identity (RCC.16 §10.3). Every member of a group sees a failure
                // report and may answer it.
                + " AND m." + MessageColumns.STATUS + " < "
                + MessageData.BUGLE_STATUS_INCOMING_COMPLETE
                + " LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[] { rcsMessageId })) {
            if (c != null && c.moveToFirst()) {
                final String text = c.getString(0);
                return TextUtils.isEmpty(text) ? null : text;
            }
        } catch (final Exception e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "findTextByRcsMessageId(" + rcsMessageId + ") failed", e);
        }
        return null;
    }

    /**
     * The local id of a group status line by its de-duplication signature, or null. Restricted to
     * {@code TRANSPORT_RCS_SYSTEM} rows so a content row's server id can never match a signature.
     */
    @Nullable
    public static String findLocalIdByRcsSystemSignature(final DatabaseWrapper db,
            final String signature) {
        if (signature == null) {
            return null;
        }
        try (Cursor c = db.query(DatabaseHelper.MESSAGES_TABLE,
                new String[] { MessageColumns._ID },
                COLUMN_RCS_MESSAGE_ID + "=? AND " + COLUMN_TRANSPORT_TYPE + "=?",
                new String[] { signature,
                        String.valueOf(RcsConstants.TRANSPORT_RCS_SYSTEM) },
                null, null, null, "1")) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    /**
     * An inbound RCS message still awaiting a displayed receipt: its wire id and the sender's
     * normalized destination. Produced by {@link #findUndisplayedInboundRcs}.
     */
    public static final class PendingDisplayed {
        public final String localMessageId;
        public final String rcsMessageId;
        public final String peerDestination;
        public final long receivedTs;

        PendingDisplayed(final String localMessageId, final String rcsMessageId,
                final String peerDestination, final long receivedTs) {
            this.localMessageId = localMessageId;
            this.rcsMessageId = rcsMessageId;
            this.peerDestination = peerDestination;
            this.receivedTs = receivedTs;
        }
    }

    /**
     * Inbound RCS rows in the conversation with a wire id and no displayed receipt sent yet
     * ({@code rcs_displayed_timestamp == 0}), with each sender's destination. Read-only; the caller
     * calls {@link #markDisplayedSent} after sending, so each row is receipted once.
     */
    public static List<PendingDisplayed> findUndisplayedInboundRcs(
            final DatabaseWrapper db, final String conversationId) {
        final List<PendingDisplayed> out = new ArrayList<>();
        if (conversationId == null) {
            return out;
        }
        final String sql =
                "SELECT m." + MessageColumns._ID
                + ", m." + COLUMN_RCS_MESSAGE_ID
                + ", p." + ParticipantColumns.NORMALIZED_DESTINATION
                + ", m." + MessageColumns.RECEIVED_TIMESTAMP
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " AS m"
                + " LEFT JOIN " + DatabaseHelper.PARTICIPANTS_TABLE + " AS p"
                + " ON m." + MessageColumns.SENDER_PARTICIPANT_ID + " = p." + ParticipantColumns._ID
                + " WHERE m." + MessageColumns.CONVERSATION_ID + " = ?"
                + " AND m." + COLUMN_TRANSPORT_TYPE + " = " + RcsConstants.TRANSPORT_RCS
                + " AND m." + COLUMN_RCS_MESSAGE_ID + " IS NOT NULL"
                + " AND m." + COLUMN_RCS_DISPLAYED_TS + " = 0"
                + " AND m." + MessageColumns.STATUS + " >= "
                + MessageData.BUGLE_STATUS_FIRST_INCOMING;
        try (Cursor c = db.rawQuery(sql, new String[] { conversationId })) {
            if (c != null) {
                while (c.moveToNext()) {
                    final String localId = c.getString(0);
                    final String rcsId = c.getString(1);
                    final String peer = c.getString(2);
                    if (rcsId != null) {
                        out.add(new PendingDisplayed(localId, rcsId, peer, c.getLong(3)));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The received time of the newest inbound RCS row in the conversation already marked reported
     * ({@code rcs_displayed_timestamp != 0}), or 0 when there is none.
     */
    public static long newestDisplayedInboundTs(final DatabaseWrapper db,
            final String conversationId) {
        if (conversationId == null) {
            return 0;
        }
        final String sql =
                "SELECT MAX(" + MessageColumns.RECEIVED_TIMESTAMP + ")"
                + " FROM " + DatabaseHelper.MESSAGES_TABLE
                + " WHERE " + MessageColumns.CONVERSATION_ID + " = ?"
                + " AND " + COLUMN_TRANSPORT_TYPE + " = " + RcsConstants.TRANSPORT_RCS
                + " AND " + COLUMN_RCS_DISPLAYED_TS + " != 0"
                + " AND " + MessageColumns.STATUS + " >= "
                + MessageData.BUGLE_STATUS_FIRST_INCOMING;
        try (Cursor c = db.rawQuery(sql, new String[] { conversationId })) {
            return c != null && c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /**
     * Stamp {@code rcs_displayed_timestamp} so the displayed receipt is not sent again. Idempotent.
     * Returns the number of rows updated.
     */
    public static int markDisplayedSent(final DatabaseWrapper db, final String localMessageId,
            final long timestamp) {
        if (localMessageId == null) {
            return 0;
        }
        final ContentValues v = new ContentValues();
        v.put(COLUMN_RCS_DISPLAYED_TS, timestamp);
        return db.update(DatabaseHelper.MESSAGES_TABLE, v,
                MessageColumns._ID + "=? AND " + COLUMN_RCS_DISPLAYED_TS + "=0",
                new String[] { localMessageId });
    }

    /**
     * The {@code rcs_group_id} of the conversation one of our sent messages belongs to, or null for
     * a 1:1 or an unknown message. A receipt carries no group context on the wire, and the remedy
     * for an RCC.16 §7.7.2.2 negative-delivery report must be applied to the group the message was
     * sent to.
     */
    @Nullable
    public static String findGroupIdByRcsMessageId(final String rcsMessageId) {
        if (TextUtils.isEmpty(rcsMessageId)) {
            return null;
        }
        final DatabaseWrapper db = DataModel.get().getDatabase();
        if (db == null) {
            return null;
        }
        final String sql =
                "SELECT c." + DatabaseHelper.ConversationColumns.RCS_GROUP_ID
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " m"
                + " JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c"
                + " ON m." + MessageColumns.CONVERSATION_ID
                + " = c." + DatabaseHelper.ConversationColumns._ID
                + " WHERE m." + COLUMN_RCS_MESSAGE_ID + " = ?"
                + " LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[] { rcsMessageId })) {
            if (c != null && c.moveToFirst()) {
                final String groupId = c.getString(0);
                return TextUtils.isEmpty(groupId) ? null : groupId;
            }
        } catch (final Exception e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "findGroupIdByRcsMessageId(" + rcsMessageId + ") failed",
                    e);
        }
        return null;
    }

    // ---- group read receipts (rcs_group_receipts side table) ----

    /**
     * True if the local row is an incoming message. {@code rcs_message_id} is not unique across a
     * group: every member stores its received copy under the sender's id. A status or receipt is
     * always about our own send, so callers use this to avoid rewriting a received copy.
     */
    public static boolean isIncomingLocalId(final DatabaseWrapper db,
            final String localMessageId) {
        if (localMessageId == null) {
            return false;
        }
        final String sql =
                "SELECT " + MessageColumns.STATUS
                + " FROM " + DatabaseHelper.MESSAGES_TABLE
                + " WHERE " + MessageColumns._ID + " = ?"
                + " LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[] { localMessageId })) {
            if (c != null && c.moveToFirst()) {
                return c.getInt(0) >= MessageData.BUGLE_STATUS_INCOMING_COMPLETE;
            }
        }
        return false;
    }

    /**
     * True if the row's conversation is an RCS group. The provider sends a group receipt alongside
     * every 1:1 receipt; this lets the group path ignore the 1:1 ones.
     */
    public static boolean isGroupMessageLocalId(final DatabaseWrapper db,
            final String localMessageId) {
        if (localMessageId == null) {
            return false;
        }
        final String sql =
                "SELECT c." + DatabaseHelper.ConversationColumns.RCS_GROUP_ID
                + " FROM " + DatabaseHelper.MESSAGES_TABLE + " m"
                + " JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c"
                + " ON m." + MessageColumns.CONVERSATION_ID
                + " = c." + DatabaseHelper.ConversationColumns._ID
                + " WHERE m." + MessageColumns._ID + " = ?"
                + " LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[] { localMessageId })) {
            if (c != null && c.moveToFirst()) {
                final String groupId = c.getString(0);
                return groupId != null && !groupId.isEmpty();
            }
        }
        return false;
    }

    /**
     * Upsert a per-member receipt keyed by (message_id, participant_uri), setting the delivered or
     * displayed timestamp and keeping the other. Returns true if a row was inserted or updated.
     */
    public static boolean upsertGroupReceipt(final DatabaseWrapper db,
            final String localMessageId, final String participantUri,
            final boolean displayed, final long timestamp) {
        if (localMessageId == null || participantUri == null
                || participantUri.isEmpty()) {
            return false;
        }
        final String tsColumn = displayed
                ? DatabaseHelper.RcsGroupReceiptColumns.DISPLAYED_TIMESTAMP
                : DatabaseHelper.RcsGroupReceiptColumns.DELIVERED_TIMESTAMP;
        final ContentValues v = new ContentValues();
        v.put(DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID,
                Long.parseLong(localMessageId));
        v.put(DatabaseHelper.RcsGroupReceiptColumns.PARTICIPANT_URI, participantUri);
        v.put(tsColumn, timestamp);

        // Update first, so the other timestamp is kept; insert if no row exists yet.
        final int updated = db.update(DatabaseHelper.RCS_GROUP_RECEIPTS_TABLE, v,
                DatabaseHelper.RcsGroupReceiptColumns.MESSAGE_ID + "=? AND "
                        + DatabaseHelper.RcsGroupReceiptColumns.PARTICIPANT_URI + "=?",
                new String[] { localMessageId, participantUri });
        if (updated > 0) {
            return true;
        }
        final long rowId = db.insert(
                DatabaseHelper.RCS_GROUP_RECEIPTS_TABLE, null, v);
        return rowId != -1;
    }

    /**
     * Add or replace the reaction keyed by (targetRcsMessageId, reactorUri): a reactor has at most
     * one reaction per target. Returns true if the write was attempted.
     */
    public static boolean upsertReaction(final DatabaseWrapper db,
            final String targetRcsMessageId, final String reactorUri,
            final String emoji, final long timestamp) {
        if (targetRcsMessageId == null || reactorUri == null || emoji == null) {
            return false;
        }
        final ContentValues v = new ContentValues();
        v.put(DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID, targetRcsMessageId);
        v.put(DatabaseHelper.RcsReactionColumns.REACTOR_URI, reactorUri);
        v.put(DatabaseHelper.RcsReactionColumns.EMOJI, emoji);
        v.put(DatabaseHelper.RcsReactionColumns.TIMESTAMP, timestamp);
        // Insert or replace on the (target, reactor) primary key.
        db.insertWithOnConflict(DatabaseHelper.RCS_REACTIONS_TABLE, null, v,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    /** Remove a reactor's reaction from a target (message-reaction-remove). */
    public static int removeReaction(final DatabaseWrapper db,
            final String targetRcsMessageId, final String reactorUri) {
        if (targetRcsMessageId == null || reactorUri == null) {
            return 0;
        }
        return db.delete(DatabaseHelper.RCS_REACTIONS_TABLE,
                DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID + "=? AND "
                        + DatabaseHelper.RcsReactionColumns.REACTOR_URI + "=?",
                new String[] { targetRcsMessageId, reactorUri });
    }

    /**
     * Delete every reaction targeting the messages {@code where} selects.
     *
     * <p>Reactions key on the wire id rather than {@code messages._id} so that one can arrive
     * before its target, which rules out a foreign key; orphans are swept at the delete sites
     * instead. An orphan is live, not merely untidy: synthesised ids are not UUIDs and can recur,
     * and a later message with the same id would show a reaction nobody made.
     *
     * @param where a {@code WHERE} clause over {@code messages}, e.g. {@code _id=?}
     * @param args its bind arguments
     * @return rows removed
     */
    public static int deleteReactionsForMessages(final DatabaseWrapper db, final String where,
            final String[] args) {
        if (where == null || where.isEmpty()) {
            return 0;
        }
        try {
            // Correlated by the wire id; rows with no rcs_message_id are excluded explicitly.
            return db.delete(DatabaseHelper.RCS_REACTIONS_TABLE,
                    COLUMN_REACTION_TARGET + " IN (SELECT " + COLUMN_RCS_MESSAGE_ID + " FROM "
                            + DatabaseHelper.MESSAGES_TABLE + " WHERE (" + where + ") AND "
                            + COLUMN_RCS_MESSAGE_ID + " IS NOT NULL)",
                    args);
        } catch (final Throwable t) {
            // A failed sweep must not fail the delete it accompanies.
            LogUtil.w(LogUtil.BUGLE_TAG,
                    "RcsMessageStore: could not sweep reactions for deleted messages", t);
            return 0;
        }
    }

    /** Read the RCS metadata for a local messages._id, or null if the row is gone. */
    @Nullable
    public static RcsMeta readByLocalId(final DatabaseWrapper db, final String localMessageId) {
        try (Cursor c = db.query(DatabaseHelper.MESSAGES_TABLE,
                new String[] {
                        MessageColumns._ID,
                        COLUMN_TRANSPORT_TYPE,
                        COLUMN_RCS_MESSAGE_ID,
                        COLUMN_RCS_STATUS,
                        COLUMN_RCS_DELIVERED_TS,
                        COLUMN_RCS_DISPLAYED_TS,
                },
                MessageColumns._ID + "=?", new String[] { localMessageId },
                null, null, null, "1")) {
            if (c != null && c.moveToFirst()) {
                return new RcsMeta(c.getString(0), c.getInt(1), c.getString(2),
                        c.getInt(3), c.getLong(4), c.getLong(5));
            }
        }
        return null;
    }
}
