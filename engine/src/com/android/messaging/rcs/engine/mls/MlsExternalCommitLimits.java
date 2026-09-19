/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The external-commit budget as decision code reaches it: claim an external-commit resync for a
 * conversation, or reset its budget. Implemented by the durable {@code MlsExternalCommitBudget}.
 */
public interface MlsExternalCommitLimits {
    boolean claim(String key);
    void reset(String key);
}
