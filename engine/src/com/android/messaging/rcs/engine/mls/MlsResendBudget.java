/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * How many RCC.16 §10.3 resends one peer may draw, as a count within a rolling window, and the
 * send-gate that holds 1:1 resends until the peer converges. The window lets a burst age out; a
 * count with no time bound would disable resends in a conversation for good. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsResendBudget {

    private MlsResendBudget() {}

    /** Resends allowed to one peer, per conversation, within {@link #WINDOW_MS}. */
    public static final int MAX_PER_WINDOW = 2;

    /** The window; one hour, the app's own choice. */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * @param resendsInWindow resends already recorded for this peer and conversation in the window
     */
    public static boolean spent(final int resendsInWindow) {
        return resendsInWindow >= MAX_PER_WINDOW;
    }
}
