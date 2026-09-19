/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/** The durable resend ledger: which resend belongs to which original, and resends per peer. */
public interface MlsResendLedgerAccess {
    String rootOf(String rcsMessageId);
    String conversationKeyOf(String rcsMessageId);
    List<MlsResendRecord> siblings(String originalRcsMessageId);
    String recordResend(String originalRcsMessageId, String replacesRcsMessageId,
            String recipientAddress, String recipientClientId, String conversationKey, long nowMs);
    int resendsToPeerSince(String conversationKey, String recipientAddress, long sinceMs);
    int repeatResendsToPeerSince(String conversationKey, String recipientAddress, long sinceMs);
    int forgetConversation(String conversationKey);

    /**
     * Takes a delivered chain out of the escalation counts while keeping its rows, which are what
     * maps a receipt naming one of its resends back to the original row.
     *
     * @return rows retired
     */
    int retireChain(String originalRcsMessageId);

    /**
     * Deletes the rows of chains retired at least {@code maxAgeMs} ago.
     *
     * @return rows deleted
     */
    int forgetRetiredChains(long maxAgeMs);
}
