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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsConversationRecord;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.StoreRead;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The app-layer store for {@link MlsConversationRecord} — rework item 2.1's second half.
 *
 * <p>The split is the item's: the record type and its whole-blob codec are engine-module work
 * because they are protocol facts and host-testable; <em>where the blob lives</em> is an
 * application fact and lives here.
 *
 * <h2>One key, one blob, one write</h2>
 *
 * <p>Each record is a single Base64 value under {@code <identity>/<groupIdHex>}. Not a field per
 * preference key — that is the shape §4.8 shape-rule 1 forbids, and it is exactly what the existing
 * {@code mls_provider_conv} prefs do (a {@code .gid}, {@code .peer}, {@code .era}, {@code .auth}
 * per conversation, updated independently). Scattered scalars are how a record ends up internally
 * inconsistent: any one of the five writes can be the last one before the process dies.
 *
 * <h2>No index, no query</h2>
 *
 * <p>There is deliberately no {@code findUnhealthy()}, no {@code allWithPendingOperation()}, and
 * there must never be one (§4.8 shape-rule 2, item 2.4). The only enumeration is
 * {@link #dumpAll()}, which is a debug dump and says so — the moment an index exists it becomes a
 * second source of truth, and the second source is the one that goes stale.
 *
 * <p>Locking is the caller's: {@code MlsProviderTransport} holds the {@code (identity, group)} lock
 * across the engine call <em>and</em> this write, which is the clause the durability argument rests
 * on. This class deliberately takes no lock of its own — a second lock here would create an
 * ordering it has no way to reason about.
 */
public final class MlsRecordStore implements MlsPerConversationState {
    private static final String TAG = MlsLog.TAG;

    /** Separate from {@code mls_provider_conv} so the migration can be observed and reverted. */
    private static final String PREFS = "mls_conversation_record";

    private final Context mCtx;

    public MlsRecordStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Read one record.
     *
     * <p>Three-valued ({@link StoreRead}): a conversation with no record yet is
     * {@code NotFound}, which is a NORMAL answer the caller acts on by creating one — and it is
     * emphatically not the same as a record that failed to decode, which means something is wrong
     * with what we persisted and must not be papered over by starting fresh.
     */
    public StoreRead<MlsConversationRecord> get(final String identity, final byte[] groupId) {
        final String k = MlsConversationRecord.key(identity, groupId);
        final String v = prefs().getString(k, null);
        if (v == null) return StoreRead.notFound();
        final byte[] blob;
        try {
            blob = Base64.decode(v, Base64.NO_WRAP);
        } catch (final IllegalArgumentException e) {
            return StoreRead.err("record " + k + " is not valid Base64");
        }
        final MlsConversationRecord r = MlsConversationRecord.decode(blob);
        if (r == null) {
            // Err, NOT NotFound. Laundering a corrupt record into "no record" would silently reset
            // the group to Unknown and send it back through the entire recovery ladder.
            return StoreRead.err("record " + k + " did not decode (" + blob.length + "B)");
        }
        return StoreRead.ok(r);
    }

    /**
     * Write one record WHOLE.
     *
     * <p>{@code commit()} rather than {@code apply()}: the caller holds the group lock across the
     * engine call and this write, and the whole point of that bracket is that the write is durable
     * before the lock is released. An asynchronous {@code apply()} would return while the write is
     * still in flight, which quietly re-opens the window the lock exists to close.
     *
     * @return {@code null} on success, or the reason it failed
     */
    public String put(final MlsConversationRecord record) {
        if (record == null) return "null record";
        final String k = record.key();
        try {
            final String v = Base64.encodeToString(record.encode(), Base64.NO_WRAP);
            if (!prefs().edit().putString(k, v).commit()) {
                return "commit() returned false for " + k;
            }
            return null;
        } catch (final RuntimeException e) {
            LogUtil.e(TAG, "MlsRecordStore: failed to persist " + k, e);
            return "encode/persist failed for " + k + ": " + e;
        }
    }

    /** Forget one record entirely. Used by {@code forget()} and the downgrade path. */
    public void remove(final String identity, final byte[] groupId) {
        prefs().edit().remove(MlsConversationRecord.key(identity, groupId)).commit();
    }

    /**
     * EVERY record, as text — the debug dump that exists <em>instead of</em> an index (item 2.4).
     *
     * <p>Built at the same time as the record, deliberately: the first time someone needs to answer
     * "which groups are unhealthy?" without this, the answer grows a query, and the query grows an
     * index.
     */
    public String dumpAll() {
        final Map<String, ?> all = prefs().getAll();
        if (all.isEmpty()) return "MlsRecordStore: (no records)";
        final List<String> keys = new ArrayList<>(all.keySet());
        java.util.Collections.sort(keys);
        final StringBuilder sb = new StringBuilder(256 * keys.size());
        sb.append("MlsRecordStore: ").append(keys.size()).append(" record(s)\n");
        for (final String k : keys) {
            final Object v = all.get(k);
            if (!(v instanceof String)) { sb.append("  ").append(k).append(": ?\n"); continue; }
            MlsConversationRecord r = null;
            try {
                r = MlsConversationRecord.decode(Base64.decode((String) v, Base64.NO_WRAP));
            } catch (final IllegalArgumentException ignored) {
                // fall through to the undecodable line below
            }
            sb.append("  ").append(r == null ? k + ": UNDECODABLE" : r.dump()).append('\n');
        }
        return sb.toString();
    }

    /** How many records exist. Diagnostic only — never a query predicate. */
    public int size() { return prefs().getAll().size(); }

    // ---- the conversation -> (identity, group) join --------------------------------------------
    //
    // NOT an index on record state, and the distinction is the whole of §4.8 shape-rule 2. Indexing
    // the record on health_status or on whether an operation is pending would create a second source
    // of truth about the record's CONTENTS. This is the §4.6 JOIN: the app addresses a conversation
    // by its own id, the record is keyed (identity, groupId), and something has to map between them.
    // The rework plan calls that join legitimate in as many words (item 2.4, on the equivalent
    // indexes in the provider's MlsConversationStore).
    //
    // Kept in its OWN preferences file rather than beside the records, so dumpAll() can decode
    // everything it iterates instead of skipping keys by prefix.

    private static final String ALIAS_PREFS = "mls_conversation_alias";

    private SharedPreferences aliases() {
        return mCtx.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE);
    }

    private static String aliasKey(final String identity, final String conversationId) {
        return MlsConversationRecord.aliasKey(identity, conversationId);
    }

    /**
     * The stored form of a group id — ONE encoder, because the teardown matches rows by this VALUE.
     *
     * <p>Spelling the Base64 flags out in two places is how a write and a match drift apart, and a
     * teardown that computes a different string from the same bytes stops matching silently.
     */
    private static String encodeGroupId(final byte[] groupId) {
        return (groupId == null || groupId.length == 0)
                ? null : Base64.encodeToString(groupId, Base64.NO_WRAP);
    }

    /** Remember which MLS group a conversation id names, for this identity. */
    public void putAlias(final String identity, final String conversationId, final byte[] groupId) {
        if (conversationId == null || groupId == null || groupId.length == 0) return;
        aliases().edit()
                .putString(aliasKey(identity, conversationId), encodeGroupId(groupId))
                .commit();
    }

    /** The MLS group id this conversation names, or null. */
    public byte[] groupIdFor(final String identity, final String conversationId) {
        if (conversationId == null) return null;
        final String v = aliases().getString(aliasKey(identity, conversationId), null);
        if (v == null) return null;
        try {
            return Base64.decode(v, Base64.NO_WRAP);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Every conversation this identity has an MLS group for, in a STABLE total order.
     *
     * <h2>This is NOT the index §4.8 shape-rule 2 forbids, and the distinction is exact</h2>
     *
     * <p>Shape-rule 2 forbids indexing or querying the record's <b>contents</b> —
     * {@code findUnhealthy()}, {@code allWithPendingOperation()} — because such an index becomes a
     * second source of truth about state that only the record should answer for, and the second
     * source is the one that goes stale. This enumerates the KEYS of the §4.6 join and answers
     * nothing about any record: every caller still has to {@link #get} the record it names and read
     * the state from there, so there is exactly one source of truth and it is unchanged.
     *
     * <p>It exists because the group-maintenance sweep must reach groups nobody has opened, and the
     * in-memory maps it could otherwise walk are empty on a cold start — which is precisely when a
     * sweep matters most.
     *
     * <p><b>Sorted, and that is load-bearing.</b> The sweep is a continuation loop whose cursor is
     * "the last conversation key walked"; without a total order that is stable across passes, a
     * cursor cannot mean anything and the walk could starve an entry indefinitely.
     */
    public List<String> conversationIds(final String identity) {
        // Derived from aliasKey rather than spelled out again, so the two cannot drift: the
        // separator is what makes the prefix unambiguous, and an E.164 cannot contain it.
        final String prefix = aliasKey(identity, "");
        final List<String> out = new ArrayList<>();
        for (final String k : aliases().getAll().keySet()) {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Forget the join for one conversation. Paired with {@link #remove}. */
    public void removeAlias(final String identity, final String conversationId) {
        if (conversationId == null) return;
        aliases().edit().remove(aliasKey(identity, conversationId)).commit();
    }

    /**
     * Forget every join naming this group, whatever key it was written under.
     *
     * <p>{@link #removeAlias} is correct only for a caller that knows the key {@code putGroup} used;
     * {@code rollBackAdoption} does, because it holds the canonical key it just adopted under. The
     * TEARDOWN does not — see {@link MlsConversationRecord#aliasKeysToForget} for what it was given
     * instead and what that cost on {@code deviceB}.
     *
     * @return how many rows were actually dropped, never how many removals were attempted
     */
    public int removeAliasesFor(final String identity, final byte[] groupId,
            final String... conversationKeys) {
        final List<String> keys = MlsConversationRecord.aliasKeysToForget(
                aliases().getAll(), identity, encodeGroupId(groupId), conversationKeys);
        if (keys.isEmpty()) return 0;
        final SharedPreferences.Editor e = aliases().edit();
        for (final String k : keys) e.remove(k);
        e.commit();
        return keys.size();
    }

    /**
     * Both halves — the record and its conversation→group join.
     *
     * <p>Neither {@link #remove} nor {@link #removeAlias} had a caller anywhere in the tree before
     * this. So a forgotten conversation kept its persisted record, and re-joining it inherited a
     * health status, a pending operation with its attempt count, a spent self-heal budget and the
     * FTD resend counts of the group that no longer existed. Every one of those gates behaviour,
     * and unlike the in-memory maps they survive a reboot: a conversation could be forgotten,
     * re-joined, and still be refused a self-heal because the budget was exhausted days earlier.
     *
     * <p>The alias is removed even when the record is absent. They are written at different moments
     * ({@code putAlias} on the app's first send, {@code put} on the first state transition), so
     * "record missing" is a normal state in which a stale alias can still exist and still point a
     * conversation id at a group id that has been deleted underneath it.
     *
     * <h2>And it was removed under a key that could never match</h2>
     *
     * <p>The paragraph above was right about the hazard and wrong about the remedy being in place.
     * The alias went out by {@code scope.conversationId}, which {@code scopeFor} fills from
     * {@code conversationIdFor(key)} — the app's BUGLE conversation row id — while {@code putGroup}
     * had written the row under the transport's CANONICAL key. Two key spaces, one removal, no
     * match, and no way to see it: the record disappeared, so every later {@code getGroup} answered
     * null and the conversation looked forgotten.
     *
     * <p>It is the SAME defect as {@code mConvAlias.remove(key)}, found in the in-memory half on
     * 2026-08-05 and fixed there. The durable half kept it for another five weeks, which is the
     * argument for choosing the row by its VALUE now rather than by a key somebody has to get right:
     * see {@link MlsConversationRecord#aliasKeysToForget}.
     */
    @Override
    public int forgetConversation(final Scope scope) {
        int n = 0;
        if (scope.groupId != null && scope.groupId.length > 0) {
            remove(scope.identity, scope.groupId);
            n++;
        }
        // BY THE GROUP ID FIRST, and BOTH candidate keys after it. Removing by
        // scope.conversationId alone could not match: putGroup writes the row under the CANONICAL
        // key and this field is the app's Bugle conversation row id. The record went, the alias
        // stayed, and a "DEEP: both halves dropped" forget said otherwise.
        //
        // Every field here is nullable and every one is OPTIONAL to the selection, which is the
        // whole change: it skips what it was not given instead of the caller picking the one key to
        // trust. Picking was the defect.
        final String byConvId = scope.conversationId == null ? "" : scope.conversationId;
        final String byCanonicalKey = scope.canonicalKey == null ? "" : scope.canonicalKey;
        n += removeAliasesFor(scope.identity, scope.groupId, byConvId, byCanonicalKey);
        return n;
    }
}
