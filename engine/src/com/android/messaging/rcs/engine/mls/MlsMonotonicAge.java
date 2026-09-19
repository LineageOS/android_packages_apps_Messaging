/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The age of a durable guard entry, from {@code SystemClock.elapsedRealtime()} so a wall-clock jump
 * cannot refill a budget. Every answer is a lower bound: an entry may be retained past its window
 * but is never dropped early. See docs/mls/budgets.md.
 */
public final class MlsMonotonicAge {

    private MlsMonotonicAge() {}

    /**
     * Milliseconds provably passed since {@code chargedElapsedMs}. Never negative; a negative input
     * answers 0, the most conservative age.
     */
    public static long ageMs(final long chargedElapsedMs, final long nowElapsedMs) {
        if (chargedElapsedMs < 0L || nowElapsedMs < 0L) return 0L;
        // A reading ahead of now is from an earlier boot; the uptime is all that can be proven.
        if (chargedElapsedMs > nowElapsedMs) return nowElapsedMs;
        return nowElapsedMs - chargedElapsedMs;
    }

    /**
     * Whether the device has rebooted since {@code chargedElapsedMs}. {@code true} is proof;
     * {@code false} only means no reboot is detectable this way.
     */
    public static boolean rebootedSince(final long chargedElapsedMs, final long nowElapsedMs) {
        return chargedElapsedMs >= 0L && nowElapsedMs >= 0L && chargedElapsedMs > nowElapsedMs;
    }
}
