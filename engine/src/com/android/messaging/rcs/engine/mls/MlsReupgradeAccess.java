/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/** The per-conversation re-upgrade state: why it was downgraded and whether an attempt may run. */
public interface MlsReupgradeAccess {
    MlsReupgradeState load(String conversationId);
    void store(String conversationId, MlsReupgradeState state);
    MlsReupgradeState markDowngrade(String conversationId, MlsDowngradeReason reason, long nowMs);
    void setEagerlyDowngraded(String conversationId, boolean v);
    boolean claimReupgradeAttempt(String conversationId, long nowMs);
    void clear(String conversationId);
}
