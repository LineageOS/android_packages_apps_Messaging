/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/** Continuity extension code points (RCC.16 §7.11.12). See docs/mls/rust-core.md. */
public final class MlsContinuityCodePoints {

    private MlsContinuityCodePoints() { }

    /** The secret token; Welcome-only, never in a server-bound GroupInfo. */
    public static final int TOKEN = 0xF010;

    /** The token's commitment, required in every GroupInfo and Welcome. */
    public static final int COMMITMENT = 0xF011;

    public static boolean isContinuity(final int ty) { return ty == TOKEN || ty == COMMITMENT; }
}
