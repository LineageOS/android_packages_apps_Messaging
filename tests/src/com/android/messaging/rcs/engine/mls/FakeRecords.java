/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link MlsRecordAccess} for host tests, keyed the way the real store is:
 * {@code (identity, groupId)} for records and {@code (identity, conversationId)} for aliases.
 *
 * <p>{@link #unreadable} makes {@code get} answer {@code Err} — the "record exists but cannot be
 * read" case every reader must keep distinct from NotFound — and {@link #failWrites} makes
 * {@code put} refuse, so a test can drive the store's failure arms without a real store.
 */
public final class FakeRecords implements MlsRecordAccess {
    public final Map<String, MlsConversationRecord> records = new LinkedHashMap<>();
    public final Map<String, byte[]> aliases = new LinkedHashMap<>();
    public String unreadable;
    public String failWrites;

    private static String k(final String identity, final byte[] groupId) {
        return identity + "|" + MlsHex.hex(groupId);
    }

    @Override public StoreRead<MlsConversationRecord> get(final String identity,
            final byte[] groupId) {
        if (unreadable != null) return StoreRead.err(unreadable);
        final MlsConversationRecord r = records.get(k(identity, groupId));
        return r == null ? StoreRead.<MlsConversationRecord>notFound() : StoreRead.ok(r);
    }

    @Override public String put(final MlsConversationRecord record) {
        if (failWrites != null) return failWrites;
        records.put(k(record.identity, record.groupId), record);
        return null;
    }

    @Override public void remove(final String identity, final byte[] groupId) {
        records.remove(k(identity, groupId));
    }

    @Override public String dumpAll() {
        return records.keySet().toString();
    }

    @Override public byte[] groupIdFor(final String identity, final String conversationId) {
        return aliases.get(identity + "|" + conversationId);
    }

    @Override public void putAlias(final String identity, final String conversationId,
            final byte[] groupId) {
        aliases.put(identity + "|" + conversationId, groupId);
    }

    @Override public List<String> conversationIds(final String identity) {
        final List<String> out = new ArrayList<>();
        for (final String a : aliases.keySet()) {
            if (a.startsWith(identity + "|")) out.add(a.substring(identity.length() + 1));
        }
        return out;
    }

    @Override public void removeAlias(final String identity, final String conversationId) {
        aliases.remove(identity + "|" + conversationId);
    }
}
