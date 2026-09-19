/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The durable out-of-order pending queue, one {@link MlsPendingQueue} per (identity, group); the
 * app's {@code MlsPendingQueueStore} implements it.
 */
public interface MlsPendingQueueAccess {
    MlsPendingQueue load(String identity, byte[] groupId);
    String store(String identity, byte[] groupId, MlsPendingQueue q);
    void remove(String identity, byte[] groupId);
}
