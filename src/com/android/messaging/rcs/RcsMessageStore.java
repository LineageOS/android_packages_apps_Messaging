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
 * Thin DAO over the additive RCS columns on the {@code messages} table.
 *
 * <p>Design rationale (grounded in MessageData.java + BugleDatabaseOperations
 * .java): MessageData's positional insert (sProjection / INSERT_MESSAGE_SQL /
 * INDEX_* / bind() / populate() / getInsertStatement(), MessageData.java
 * 44-475) is hand-indexed and fragile -- widening it means editing six things
 * in lockstep. We deliberately do NOT widen it. Instead an RCS message is
 * inserted via the existing SMS-shaped path and the RCS columns are written
 * with a second, ContentValues-based {@code
 * BugleDatabaseOperations.updateMessageRow} (745-750), which is
 * column-agnostic and needs no positional bookkeeping. This DAO builds those
 * ContentValues and reads the columns back on demand.
 *
 * <p>The RCS columns are also NOT added to
 * {@code ConversationMessageData.CONVERSATION_MESSAGES_QUERY_PROJECTION_SQL}
 * (697-760): that projection is positionally indexed via the
 * {@code sIndexIncrementer} block and an RCS message rendered as a normal
 * messages row already appears in the existing cursor with zero UI change.
 * The renderer reads the RCS marker / receipt on demand via
 * {@link #readByLocalId}.
 */
public final class RcsMessageStore {
    private RcsMessageStore() {}

    // ---- additive column names (kept in sync with DatabaseHelper.MessageColumns) ----
    public static final String COLUMN_TRANSPORT_TYPE = "transport_type";
    public static final String COLUMN_RCS_MESSAGE_ID = "rcs_message_id";
    public static final String COLUMN_RCS_STATUS = "rcs_status";
    public static final String COLUMN_RCS_DELIVERED_TS = "rcs_delivered_timestamp";
    public static final String COLUMN_RCS_DISPLAYED_TS = "rcs_displayed_timestamp";
    public static final String COLUMN_RCS_CONTRIBUTION_ID = "rcs_contribution_id";

    // ---- reactions side-table column names (kept in sync with RcsReactionColumns) ----
    public static final String COLUMN_REACTION_TARGET = "target_rcs_message_id";
    public static final String COLUMN_REACTION_REACTOR = "reactor_uri";
    public static final String COLUMN_REACTION_EMOJI = "emoji";

    /** Sentinel reactor_uri marking a reaction WE sent (so re-tap = remove). */
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

    /**
     * Resolve the local messages._id from an RCS/server message id (the
     * IMDN-correlation key). Returns null if not found. Uses the index added
     * in upgradeToVersion3.
     */
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
     * iOS-tapback interop: resolve the {@code rcs_message_id} of the message an
     * inbound iOS tapback quoted. Searches the {@code limit} most-recent RCS
     * messages in the conversation (newest first) whose text body matches
     * {@code quotedText}, via Google Messages' matching ladder: exact
     * case-sensitive {@code equals} on the untrimmed text first, else strip one
     * trailing U+2026 (the iOS truncation marker) and try {@code startsWith} /
     * {@code endsWith}. Returns the target rcs_message_id only when EXACTLY ONE
     * distinct message matches; {@code null} on no match or an ambiguous
     * (multiple) match -- both of which Google Messages drops. (Omits its
     * URL-http:// fallback + duplicate distance tiebreaker; faithful core otherwise.)
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
     * RCC.16 §10: recover the TEXT of one of our sent messages so it can be re-sent to a peer that
     * reported it failed to decrypt.
     *
     * <p>The spec's remedy for {@code <failed-to-decrypt>} is that the sender advances to the latest
     * epoch and resends — which requires still having the plaintext. We do: the message is in our own
     * conversation, keyed by the same {@code rcs_message_id} the IMDN references.
     *
     * <p>Text parts only. A resend of a media message needs the file re-uploaded and re-keyed, which
     * is not a store lookup; returning null there means the caller reports honestly that it could not
     * reconstruct the message instead of sending an empty one.
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
                // OUTGOING ONLY — this is RESEND MATERIAL, not "the text of that message".
                //
                // Without this filter the lookup happily returns a message we RECEIVED, and the
                // §10.3 resend path then re-frames and re-encrypts another member's content under
                // OUR sender identity. Device-proven 2026-08-15: in a 4-member group, six of seven
                // resends were performed by a NON-originator, and the reporting peer stored each
                // affected message TWICE — once under the true author, once under the resender.
                // Duplicate messages, and a false claim about who said what inside an E2EE group.
                //
                // A group FTD fans out to EVERY member, so every member gets the chance to answer;
                // the pending-body store is outbound-only and correctly had nothing, and this was
                // the fallback that supplied someone else's words instead.
                //
                // Statuses below BUGLE_STATUS_INCOMING_COMPLETE (100) are the outgoing range.
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
     * WAVE-C: resolve the local messages._id of a group-event SYSTEM row by its
     * stable event signature.
     *
     * <p>Group lifecycle system lines (TRANSPORT_RCS_SYSTEM) stash their de-dup
     * signature in the {@code rcs_message_id} column — the SAME column a real
     * outbound/inbound RCS row uses for the server message id. The plain
     * {@link #findLocalIdByRcsMessageId} lookup is transport-agnostic, so it
     * could in principle collide a system signature against a real RCS server
     * message id. This variant pins the match to system rows
     * ({@code transport_type = TRANSPORT_RCS_SYSTEM}) so the optimistic-self
     * write and the inbound {@code onGroupEvent} echo de-dup to exactly one line
     * without ever false-matching a content message. Returns null if no system
     * row carries this signature yet.
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
     * One inbound RCS message still awaiting a DISPLAYED receipt: its server
     * message id (the IMDN key) and the peer's normalized destination (the
     * IMDN target). Produced by {@link #findUndisplayedInboundRcs}.
     */
    public static final class PendingDisplayed {
        public final String localMessageId;
        public final String rcsMessageId;
        public final String peerDestination;

        PendingDisplayed(final String localMessageId, final String rcsMessageId,
                final String peerDestination) {
            this.localMessageId = localMessageId;
            this.rcsMessageId = rcsMessageId;
            this.peerDestination = peerDestination;
        }
    }

    /**
     * Find the inbound RCS messages in a conversation that carry a server
     * message id and have not yet had a DISPLAYED receipt sent
     * ({@code rcs_displayed_timestamp == 0}).
     *
     * <p>Only inbound rows are returned ({@code status >=}
     * {@link MessageData#BUGLE_STATUS_FIRST_INCOMING}); outbound RCS and all
     * SMS/MMS rows are excluded. The peer destination is resolved by joining
     * the row's sender participant to its normalized destination so the caller
     * can address the IMDN to the peer.
     *
     * <p>Read-only; the caller stamps {@link #markDisplayedSent} after the
     * receipt is dispatched so each row fires exactly once.
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
                        out.add(new PendingDisplayed(localId, rcsId, peer));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Stamp {@code rcs_displayed_timestamp} on a local row so its DISPLAYED
     * receipt is never re-sent. Idempotent. Returns the number of rows updated.
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
     * The {@code rcs_group_id} of the conversation one of OUR sent messages belongs to, or null if
     * it was a 1:1 (or the message is unknown).
     *
     * <p>Needed because an inbound IMDN carries no group context: the server routes a group receipt
     * to the originator as a PHONE_NUMBER, so on the wire it is indistinguishable from a 1:1 one.
     * For a positive receipt that does not matter — the provider forwards both shapes and the app
     * branches. For an RCC.16 §7.7.2.2 NEGATIVE-delivery report it matters a great deal: the remedy
     * is an era advance or a resend, and applying it to the wrong conversation repairs a group
     * nobody complained about while leaving the broken one broken.
     *
     * <p>The message id in the report is one of ours, so the conversation it was sent to is the
     * conversation the report is about. That is the only reliable way to recover the group.
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
            LogUtil.w(LogUtil.BUGLE_TAG, "findGroupIdByRcsMessageId(" + rcsMessageId + ") failed", e);
        }
        return null;
    }

    // ---- WAVE-E group read-receipts (rcs_group_receipts side-table) ----

    /**
     * True if the conversation that owns {@code localMessageId} is an RCS group
     * (its {@code conversations.rcs_group_id} is non-null). Used to branch the
     * inbound group-IMDN upsert so a 1:1 IMDN (which the provider also forwards
     * as onGroupImdnReceipt) is dropped here and handled only by the unchanged
     * 1:1 onImdnReceipt path.
     */
    /**
     * True if the local row is an INCOMING message.
     *
     * <p>Needed because {@code rcs_message_id} is NOT unique across devices in a group: every
     * member stores its own received copy of a message under the SAME id the sender used. A status
     * or receipt callback is always about <em>our own send</em>, so resolving a row by rcs id alone
     * and writing an outgoing status to it rewrites the RECEIVED copy on every other member.
     *
     * <p>That is not hypothetical. It is what put {@code BUGLE_STATUS_OUTGOING_DELIVERED} on all 28
     * of a receiver's group rows (2026-08-16), which in turn defeated the outgoing-only filter in
     * {@link #findTextByRcsMessageId} and let a NON-ORIGINATOR resend another member's message — a
     * §10.3 violation Google Messages rejects with {@code 24 ZINNIA_FAILURE_INVALID_RESEND_SENDER}.
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
     * Upsert a per-member group receipt row into {@code rcs_group_receipts}
     * keyed by (message_id, participant_uri). Sets the DELIVERED or DISPLAYED
     * timestamp by {@code displayed}; the other timestamp is preserved (an
     * INSERT seeds it to 0, an UPDATE leaves it untouched). Idempotent-ish: a
     * later receipt of the same kind just overwrites the timestamp (monotonic in
     * practice). Returns true if a row was inserted or updated.
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

        // Try UPDATE first (preserves the other timestamp); INSERT the seed row
        // if no (message_id, participant_uri) row exists yet.
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
     * Add or replace a reaction row keyed by (targetRcsMessageId, reactorUri).
     * A reactor has at most one reaction per target; a new emoji REPLACES the
     * old (a REPLACE = remove+add collapses to this upsert). Returns true
     * if the write was attempted (the wrapper swallows DB-full internally).
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
        // INSERT-or-REPLACE on the (target, reactor) primary key.
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
     * Drop every reaction targeting the messages this {@code WHERE} selects.
     *
     * <h2>Why this and not a FOREIGN KEY</h2>
     *
     * <p>The sibling side-table {@code rcs_group_receipts} cascades off {@code messages(_id)} and is
     * the obvious model. <b>It is the wrong model here, and the reason is deliberate rather than
     * historical.</b> {@code rcs_reactions} keys on {@code target_rcs_message_id} &mdash; the TEXT
     * WIRE id, not {@code messages._id} &mdash; precisely so a reaction that arrives BEFORE its
     * target's local row is kept rather than dropped ({@code UpdateRcsReactionAction} says so in as
     * many words). An FK would forbid exactly that row. So the orphan is swept at the delete sites
     * instead, which removes the leak and leaves the early-arrival tolerance intact.
     *
     * <h2>Why an orphan is not merely untidy</h2>
     *
     * <p>{@code target_rcs_message_id} is NOT unique &mdash; it is half of a composite key and its
     * index is non-unique &mdash; so an orphaned row is <b>live and waiting for its key to be
     * reused</b>. On the app-owned path ids are UUIDs and reuse is implausible; the synthesised legs
     * are not UUIDs ({@code mls-<conv>-e<era>p<epoch>-<gen>}, and
     * {@code mls-<conv>-<era>}, which collides between every message at one era BY CONSTRUCTION).
     * An orphan plus a later message re-deriving the same id renders <b>a reaction on a message
     * nobody reacted to</b>.
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
            // Correlated by the WIRE id, and NULL-guarded: a non-RCS row has no rcs_message_id, and
            // `IN (SELECT ... )` over a NULL would match nothing anyway — the guard is here so the
            // intent is legible rather than incidental.
            return db.delete(DatabaseHelper.RCS_REACTIONS_TABLE,
                    COLUMN_REACTION_TARGET + " IN (SELECT " + COLUMN_RCS_MESSAGE_ID + " FROM "
                            + DatabaseHelper.MESSAGES_TABLE + " WHERE (" + where + ") AND "
                            + COLUMN_RCS_MESSAGE_ID + " IS NOT NULL)",
                    args);
        } catch (final Throwable t) {
            // A failed sweep must not fail the delete it is attached to: the row the user asked to
            // remove matters more than the orphan we are tidying, and the orphan is latent.
            LogUtil.w(LogUtil.BUGLE_TAG,
                    "RcsMessageStore: could not sweep reactions for deleted messages", t);
            return 0;
        }
    }

    /** One aggregated reaction on a target: an emoji, its count, and reactors. */
    public static final class ReactionTally {
        public final String emoji;
        public final int count;
        public final boolean reactedBySelf;
        public final List<String> reactorUris; // for the group "who" details
        ReactionTally(String emoji, int count, boolean reactedBySelf, List<String> reactorUris) {
            this.emoji = emoji; this.count = count;
            this.reactedBySelf = reactedBySelf; this.reactorUris = reactorUris;
        }
    }

    /**
     * Read all reactions on a target message (by its rcs message-id), aggregated
     * per-emoji with count + reactor list + a self flag. Drives the chip row on
     * the target bubble. 1:1 yields count==1 chips; group aggregates per emoji.
     * Returns empty list when the target has no reactions.
     */
    public static List<ReactionTally> readReactions(final DatabaseWrapper db,
            final String targetRcsMessageId) {
        final List<ReactionTally> out = new ArrayList<>();
        if (targetRcsMessageId == null) {
            return out;
        }
        // Per-emoji aggregate; preserve first-seen order via MIN(timestamp).
        final String sql =
                "SELECT " + DatabaseHelper.RcsReactionColumns.EMOJI
                + ", COUNT(*)"
                + ", SUM(CASE WHEN " + DatabaseHelper.RcsReactionColumns.REACTOR_URI
                + " = ? THEN 1 ELSE 0 END)"
                + ", GROUP_CONCAT(" + DatabaseHelper.RcsReactionColumns.REACTOR_URI + ")"
                + " FROM " + DatabaseHelper.RCS_REACTIONS_TABLE
                + " WHERE " + DatabaseHelper.RcsReactionColumns.TARGET_RCS_MESSAGE_ID + " = ?"
                + " GROUP BY " + DatabaseHelper.RcsReactionColumns.EMOJI
                + " ORDER BY MIN(" + DatabaseHelper.RcsReactionColumns.TIMESTAMP + ")";
        try (Cursor c = db.rawQuery(sql,
                new String[] { SELF_REACTOR_URI, targetRcsMessageId })) {
            if (c != null) {
                while (c.moveToNext()) {
                    final String emoji = c.getString(0);
                    final int count = c.getInt(1);
                    final boolean self = c.getInt(2) > 0;
                    final String concat = c.getString(3);
                    final List<String> reactors = new ArrayList<>();
                    if (concat != null) {
                        for (final String r : concat.split(",")) {
                            reactors.add(r);
                        }
                    }
                    out.add(new ReactionTally(emoji, count, self, reactors));
                }
            }
        }
        return out;
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
