/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/**
 * The per-conversation record store as engine code reaches it ({@link MlsShellPort#records()});
 * the app's {@code MlsRecordStore} implements it and defines each operation's guarantees.
 */
public interface MlsRecordAccess {
    /**
     * The record for {@code (identity, groupId)}: Ok, NotFound, or Err (unreadable — not absent).
     */
    StoreRead<MlsConversationRecord> get(String identity, byte[] groupId);

    /** Write a record whole. {@code null} on success, otherwise the reason it was not written. */
    String put(MlsConversationRecord record);

    void remove(String identity, byte[] groupId);

    /** Every record, rendered for a diagnostic dump. */
    String dumpAll();

    /** The MLS group id a conversation is joined to, or {@code null}. */
    byte[] groupIdFor(String identity, String conversationId);

    void putAlias(String identity, String conversationId, byte[] groupId);

    List<String> conversationIds(String identity);

    void removeAlias(String identity, String conversationId);
}
