/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/**
 * The framed plaintext of a message we sent, kept until a resend can no longer need it; the app's
 * {@code MlsPendingBodyStore} implements it.
 */
public interface MlsPendingBodyAccess {
    byte[] get(String rcsMessageId);
    void put(String rcsMessageId, byte[] framedBody);
    void release(String rcsMessageId);
    List<String> sweepExpired(long maxAgeMs);
}
