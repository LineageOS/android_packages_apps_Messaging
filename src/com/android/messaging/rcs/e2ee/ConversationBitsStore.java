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

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.util.LogUtil;

/**
 * SQLite-backed {@link E2eeSchemeGate.BitsStore} — persists the durable, latching
 * {@link EncryptionProtocolBits} per conversation in the additive
 * {@code conversations.encryption_protocol} column, the app-layer
 * analogue of Google Messages's {@code conversation_encryption} row.
 *
 * <p>Runs on the DataModel action thread (the send path already does), matching the
 * off-main-thread contract of {@link BugleDatabaseOperations}. Read/write failures
 * degrade to {@link EncryptionProtocolBits#NONE} / no-op rather than throwing, so a
 * storage hiccup never blocks a send (it just recomputes eligibility next round).
 */
public final class ConversationBitsStore implements E2eeSchemeGate.BitsStore {
    private static final String TAG = com.android.messaging.util.LogUtil.BUGLE_TAG;

    @Override
    public EncryptionProtocolBits load(final String conversationId) {
        final EncryptionProtocolBits b = loadOrNull(conversationId);
        return b == null ? EncryptionProtocolBits.NONE : b;
    }

    /**
     * As {@link #load}, but returns {@code null} when the read FAILED rather than flattening the
     * failure to {@link EncryptionProtocolBits#NONE}.
     *
     * <p><b>The two are not interchangeable for a caller deciding whether to act.</b> {@code NONE}
     * says "this conversation is plaintext"; a failed read says "we do not know". Collapsing them is
     * safe for {@code selectScheme}, which recomputes eligibility next round anyway — and is NOT safe
     * for the downgrade path, where "we do not know" flattened to "already plaintext" would silently
     * ABSORB a real downgrade and leave the conversation offering encryption it should have stopped
     * offering.
     *
     * <p>The concrete way that happens: {@link BugleDatabaseOperations} asserts it is off the main
     * thread, so a downgrade reaching this from the wrong thread would read as "already not MLS" and
     * skip. The MLS paths are all off-main today; this makes the failure mode wrong-but-loud instead
     * of wrong-and-silent if one ever is not.
     */
    public EncryptionProtocolBits loadOrNull(final String conversationId) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final int bits = BugleDatabaseOperations.getConversationEncryptionProtocol(
                    db, conversationId);
            return EncryptionProtocolBits.fromColumnValue(bits);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ConversationBitsStore.load failed for " + conversationId, t);
            return null;
        }
    }

    @Override
    public void store(final String conversationId, final EncryptionProtocolBits bits) {
        if (bits == null) {
            return;
        }
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            BugleDatabaseOperations.setConversationEncryptionProtocol(
                    db, conversationId, bits.toColumnValue());
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ConversationBitsStore.store failed for " + conversationId, t);
        }
    }
}
