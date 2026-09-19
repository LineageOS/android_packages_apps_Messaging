/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The rebuild rate limit as decision code reaches it; the app's durable {@code MlsRebuildLimiter}
 * implements it. See docs/mls/budgets.md.
 */
public interface MlsRebuildLimits {
    boolean claim(String key);
    void reset(String key);
}
