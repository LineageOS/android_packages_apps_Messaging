/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.util.LogUtil;

/**
 * {@link E2eeSchemeGate.BitsStore} over the {@code conversations.encryption_protocol} column.
 *
 * <p>Call off the main thread, as {@link BugleDatabaseOperations} requires. A failed read loads as
 * {@link EncryptionProtocolBits#NONE} and a failed write is logged and dropped, so storage trouble
 * never blocks a send; eligibility is recomputed on the next one.
 */
public final class ConversationBitsStore implements E2eeSchemeGate.BitsStore {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;

    @Override
    public EncryptionProtocolBits load(final String conversationId) {
        final EncryptionProtocolBits b = loadOrNull(conversationId);
        return b == null ? EncryptionProtocolBits.NONE : b;
    }

    /**
     * As {@link #load}, but {@code null} when the read failed. A caller deciding whether to act
     * needs the difference: on the downgrade path, "unknown" read as "already plaintext" would skip
     * a real downgrade.
     */
    public EncryptionProtocolBits loadOrNull(final String conversationId) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final int bits = BugleDatabaseOperations.getConversationEncryptionProtocol(
                    db, conversationId);
            return EncryptionProtocolBits.fromColumnValue(bits);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "ConversationBitsStore.load failed for "
                    + MlsConversationKey.forLog(conversationId), t);
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
            LogUtil.w(TAG, "ConversationBitsStore.store failed for "
                    + MlsConversationKey.forLog(conversationId), t);
        }
    }
}
