/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * When our published KeyPackage pool is refreshed, and whether a claimed peer KeyPackage may be
 * consumed. The remaining-count estimate decrements only when we open a Welcome, while a package is
 * consumed when a peer claims it, so the estimate is an upper bound; every threshold here leans
 * toward republishing, which is cheap and idempotent, since serving a last-resort leaf makes peers
 * rotate on every commit.
 */
public final class MlsKeyPackagePolicy {

    private MlsKeyPackagePolicy() {}

    /** How often the published pool is refreshed absent a consumption signal: 24 hours. */
    public static final long KP_REPUBLISH_MS = 24L * 60 * 60 * 1000;

    /** Republish once the estimate reaches this, not zero: the estimate runs high. */
    public static final int KP_REPLENISH_AT = 3;

    /**
     * At most one pool repair (after an unopenable Welcome) per hour. Time-based because the
     * condition belongs to the published pool, not a peer. The trigger is peer-supplied (any blob
     * that looks like a Welcome), so do not loosen this without first making the repair conditional
     * on "no matching key package".
     */
    public static final long POOL_REPAIR_MIN_INTERVAL_MS = 60L * 60L * 1000L;

    /** {@link #POOL_REPAIR_MIN_INTERVAL_MS}, for log lines. */
    public static long poolRepairIntervalMs() {
        return POOL_REPAIR_MIN_INTERVAL_MS;
    }

    /**
     * Whether the published pool is low enough to replenish.
     *
     * @param remaining the lower of the two estimates; still an upper bound, so this means
     *     "possibly nearly empty"
     */
    public static boolean poolLow(final int remaining) {
        return remaining <= KP_REPLENISH_AT;
    }

    /**
     * {@link #poolLow}, only on a device that has published: before the first publish the estimate
     * is zero because nothing was counted. The first publish is driven by {@link #republishDue}.
     */
    public static boolean poolDrained(final long lastPublishMs, final int remaining) {
        return lastPublishMs != 0L && poolLow(remaining);
    }

    /**
     * Whether to republish now: on the timer, or when drained, since a busy device can drain
     * before the timer fires.
     *
     * @param lastPublishMs {@code 0} if we have never published, which always republishes
     * @param drained {@link #poolDrained} for this device
     */
    public static boolean republishDue(final long lastPublishMs, final long nowMs,
            final boolean drained) {
        if (lastPublishMs == 0L) return true;
        return drained || nowMs - lastPublishMs >= KP_REPUBLISH_MS;
    }

    /** Whether a pool repair after an unopenable Welcome is allowed; the first always is. */
    public static boolean poolRepairAllowed(final long lastRepairMs, final long nowMs) {
        return nowMs - lastRepairMs >= POOL_REPAIR_MIN_INTERVAL_MS;
    }
}
