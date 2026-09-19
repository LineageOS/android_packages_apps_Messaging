/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/** Stored per-message stage results, so a duplicate inbound message is answered by replay. */
public interface MlsRendezvousAccess {
    MlsRendezvous.Stored get(String selfIdentity, String remoteUserId, String rcsMessageId,
            MlsRendezvous.Stage stage);
    void put(String selfIdentity, String remoteUserId, String rcsMessageId,
            MlsRendezvous.Stage stage, MlsRendezvous.Stored result);
    int deleteForMessage(String selfIdentity, String rcsMessageId);
    int size();
}
