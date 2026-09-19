/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/** The sealed-ciphertext cache: what was sealed for a message, kept until the send settles. */
public interface MlsSealedCacheAccess {
    MlsSealedMessage get(String rcsMessageId);
    void put(MlsSealedMessage sealed);
    void release(String rcsMessageId);
    int releaseConversation(String conversationKey, String why);
    void invalidateForReEncrypt(String rcsMessageId, String why);
    int size();
    int sweepExpired(long maxAgeMs, long nowElapsedMs);
}
