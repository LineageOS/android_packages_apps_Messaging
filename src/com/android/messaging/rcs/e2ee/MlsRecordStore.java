/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsConversationRecord;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsRecordAccess;
import com.android.messaging.rcs.engine.mls.StoreRead;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stores {@link MlsConversationRecord}: one Base64 blob per record under
 * {@code <identity>/<groupIdHex>}, written whole, so a process death cannot leave a record half
 * updated. There is no index or query over record contents; {@link #dumpAll()} is the only
 * enumeration. Locking is the caller's: the transport holds the {@code (identity, group)} lock
 * across the engine call and this write. See docs/mls/group-lifecycle.md#persistence.
 */
public final class MlsRecordStore implements MlsPerConversationState, MlsRecordAccess {
    private static final String TAG = MlsLog.TAG;

    /** A separate file from {@code mls_provider_conv}. */
    private static final String PREFS = "mls_conversation_record";

    private final Context mCtx;

    public MlsRecordStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Read one record. {@code NotFound} is normal (the caller creates one); a record that fails to
     * decode is an error, never "no record".
     */
    @Override
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
            // Err, not NotFound: reading corruption as absence would reset the group to Unknown.
            return StoreRead.err("record " + k + " did not decode (" + blob.length + "B)");
        }
        return StoreRead.ok(r);
    }

    /**
     * Write one record whole, with {@code commit()} so it is durable before the caller releases the
     * group lock.
     *
     * @return {@code null} on success, or the reason it failed
     */
    @Override
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

    /** Forget one record. Used by {@code forget()} and the downgrade path. */
    @Override
    public void remove(final String identity, final byte[] groupId) {
        prefs().edit().remove(MlsConversationRecord.key(identity, groupId)).commit();
    }

    /** Every record as text: the debug dump that stands in for an index. */
    @Override
    public String dumpAll() {
        final Map<String, ?> all = prefs().getAll();
        if (all.isEmpty()) return "MlsRecordStore: (no records)";
        final List<String> keys = new ArrayList<>(all.keySet());
        java.util.Collections.sort(keys);
        final StringBuilder sb = new StringBuilder(256 * keys.size());
        sb.append("MlsRecordStore: ").append(keys.size()).append(" record(s)\n");
        for (final String k : keys) {
            final Object v = all.get(k);
            if (!(v instanceof String)) {
                sb.append("  ").append(MlsConversationKey.forLog(k)).append(": ?\n");
                continue;
            }
            MlsConversationRecord r = null;
            try {
                r = MlsConversationRecord.decode(Base64.decode((String) v, Base64.NO_WRAP));
            } catch (final IllegalArgumentException ignored) {
            }
            sb.append("  ").append(r == null ? MlsConversationKey.forLog(k) + ": UNDECODABLE"
                    : r.dump()).append('\n');
        }
        return sb.toString();
    }

    /** How many records exist; diagnostic only. */
    public int size() { return prefs().getAll().size(); }

    // The conversation to (identity, group) join. It maps the app's conversation id to the record
    // key and says nothing about record contents, so it is not an index. Its own preferences file,
    // so dumpAll() decodes everything it iterates.

    private static final String ALIAS_PREFS = "mls_conversation_alias";

    private SharedPreferences aliases() {
        return mCtx.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE);
    }

    private static String aliasKey(final String identity, final String conversationId) {
        return MlsConversationRecord.aliasKey(identity, conversationId);
    }

    /** The stored form of a group id. One encoder, because teardown matches rows by this value. */
    private static String encodeGroupId(final byte[] groupId) {
        return (groupId == null || groupId.length == 0)
                ? null : Base64.encodeToString(groupId, Base64.NO_WRAP);
    }

    /** Remember which MLS group a conversation id names, for this identity. */
    @Override
    public void putAlias(final String identity, final String conversationId, final byte[] groupId) {
        if (conversationId == null || groupId == null || groupId.length == 0) return;
        aliases().edit()
                .putString(aliasKey(identity, conversationId), encodeGroupId(groupId))
                .commit();
    }

    /** The MLS group id this conversation names, or null. */
    @Override
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
     * Every conversation this identity has an MLS group for, sorted. It enumerates join keys only;
     * callers still {@link #get} each record. The group-maintenance sweep uses it to reach groups
     * nobody opened, and its cursor needs an order that is stable across passes.
     */
    @Override
    public List<String> conversationIds(final String identity) {
        // Derived from aliasKey so the prefix cannot drift; an E.164 cannot contain the separator.
        final String prefix = aliasKey(identity, "");
        final List<String> out = new ArrayList<>();
        for (final String k : aliases().getAll().keySet()) {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Forget the join for one conversation. Paired with {@link #remove}. */
    @Override
    public void removeAlias(final String identity, final String conversationId) {
        if (conversationId == null) return;
        aliases().edit().remove(aliasKey(identity, conversationId)).commit();
    }

    /**
     * Forget every join naming this group, whatever key it was written under (see {@link
     * MlsConversationRecord#aliasKeysToForget}). {@link #removeAlias} suits only a caller that
     * knows the key {@code putGroup} used.
     *
     * @return rows actually dropped
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
     * Drop the record and its join. A retained record would hand a re-joined conversation the old
     * health, pending operation and spent budgets. The join is removed even without a record, since
     * the two are written at different moments.
     */
    @Override
    public int forgetConversation(final Scope scope) {
        int n = 0;
        if (scope.groupId != null && scope.groupId.length > 0) {
            remove(scope.identity, scope.groupId);
            n++;
        }
        // By group id, then by both candidate keys: putGroup writes the row under the canonical
        // key, while scope.conversationId is the app's row id. Every field is optional to the
        // selection.
        final String byConvId = scope.conversationId == null ? "" : scope.conversationId;
        final String byCanonicalKey = scope.canonicalKey == null ? "" : scope.canonicalKey;
        n += removeAliasesFor(scope.identity, scope.groupId, byConvId, byCanonicalKey);
        return n;
    }
}
