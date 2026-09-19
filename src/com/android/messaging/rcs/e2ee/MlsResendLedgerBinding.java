/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsResendLedgerAccess;
import com.android.messaging.rcs.engine.mls.MlsResendRecord;

import java.util.List;

/**
 * {@link MlsResendLedgerAccess} over MlsResendLedger's static surface: every call forwards
 * unchanged.
 */
final class MlsResendLedgerBinding implements MlsResendLedgerAccess {
    static final MlsResendLedgerAccess INSTANCE = new MlsResendLedgerBinding();

    private MlsResendLedgerBinding() {}

    @Override public String rootOf(final String rcsMessageId) {
        return MlsResendLedger.rootOf(rcsMessageId);
    }
    @Override public String conversationKeyOf(final String rcsMessageId) {
        return MlsResendLedger.conversationKeyOf(rcsMessageId);
    }
    @Override public List<MlsResendRecord> siblings(final String originalRcsMessageId) {
        return MlsResendLedger.siblings(originalRcsMessageId);
    }
    @Override public String recordResend(final String originalRcsMessageId,
            final String replacesRcsMessageId, final String recipientAddress,
            final String recipientClientId, final String conversationKey, final long nowMs) {
        return MlsResendLedger.recordResend(originalRcsMessageId, replacesRcsMessageId,
                recipientAddress, recipientClientId, conversationKey, nowMs);
    }
    @Override public int resendsToPeerSince(final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        return MlsResendLedger.resendsToPeerSince(conversationKey, recipientAddress, sinceMs);
    }
    @Override public int repeatResendsToPeerSince(final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        return MlsResendLedger.repeatResendsToPeerSince(conversationKey, recipientAddress, sinceMs);
    }
    @Override public int forgetConversation(final String conversationKey) {
        return MlsResendLedger.forgetConversation(conversationKey);
    }
    @Override public int retireChain(final String originalRcsMessageId) {
        return MlsResendLedger.retireChain(originalRcsMessageId);
    }
    @Override public int forgetRetiredChains(final long maxAgeMs) {
        return MlsResendLedger.forgetRetiredChains(maxAgeMs);
    }
}
