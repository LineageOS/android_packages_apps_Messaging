/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host tests for {@link MlsKeyPackagePolicy} — the published-pool cadence Stage 6 moved out of
 * {@code MlsProviderTransport}.
 *
 * <p>The three bounds are asserted against each other as well as at their values, because the defect
 * this class was extracted from was not a wrong number: it was a comment claiming the pool repair
 * used "the same weekly gate as any other publish", when the repair gate was hourly and the publish
 * gate was daily. Two of the three assertions below would have failed on that reading.
 */
public final class MlsKeyPackagePolicyTest {

    @Test
    public void theThreeBoundsAreThreeDifferentQuestions() {
        assertEquals(24L * 60 * 60 * 1000, MlsKeyPackagePolicy.KP_REPUBLISH_MS);
        assertEquals(60L * 60L * 1000L, MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS);
        assertEquals(3, MlsKeyPackagePolicy.KP_REPLENISH_AT);
        assertTrue("the repair gate must be SHORTER than the publish tick — it answers an "
                + "unopenable Welcome, which is a fault, not a schedule",
                MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS
                        < MlsKeyPackagePolicy.KP_REPUBLISH_MS);
    }

    /**
     * A device that has NEVER published always republishes, and is never called "drained".
     *
     * <p>The distinction is the whole reason there are two predicates over one threshold: a
     * never-published device reads an estimate of zero because nothing was counted, not because a
     * pool ran out, and calling that drained would log a replenish-ahead-of-the-tick line about a
     * pool that does not exist.
     */
    @Test
    public void aDeviceThatNeverPublishedPublishes_andIsNotDrained() {
        assertFalse(MlsKeyPackagePolicy.poolDrained(0L, 0));
        assertTrue(MlsKeyPackagePolicy.republishDue(0L, 0L, false));
        assertTrue(MlsKeyPackagePolicy.republishDue(0L, Long.MAX_VALUE / 2, false));
    }

    /** The drain signal fires EARLY — that is what it is for; the clock alone would keep silent. */
    @Test
    public void theDrainSignalRepublishesLongBeforeTheTick() {
        final long last = 1_000L;
        final long anHourLater = last + 60L * 60 * 1000;
        assertFalse("an hour is nowhere near the daily tick",
                MlsKeyPackagePolicy.republishDue(last, anHourLater, false));

        assertTrue(MlsKeyPackagePolicy.poolDrained(last, MlsKeyPackagePolicy.KP_REPLENISH_AT));
        assertTrue("a drained pool must not wait for the tick — a device popular for a day is "
                + "drained long before the day is out",
                MlsKeyPackagePolicy.republishDue(last, anHourLater, true));
    }

    /** Replenish AT three, not at zero, or a peer arrives to an empty pool. */
    @Test
    public void thePoolIsLowAtThreeNotAtZero() {
        assertTrue(MlsKeyPackagePolicy.poolLow(0));
        assertTrue(MlsKeyPackagePolicy.poolLow(MlsKeyPackagePolicy.KP_REPLENISH_AT));
        assertFalse(MlsKeyPackagePolicy.poolLow(MlsKeyPackagePolicy.KP_REPLENISH_AT + 1));
    }

    /** The periodic tick fires AT a day, not after it. */
    @Test
    public void theTickFiresAtTheDayBoundary() {
        final long last = 5_000L;
        assertFalse(MlsKeyPackagePolicy.republishDue(
                last, last + MlsKeyPackagePolicy.KP_REPUBLISH_MS - 1, false));
        assertTrue(MlsKeyPackagePolicy.republishDue(
                last, last + MlsKeyPackagePolicy.KP_REPUBLISH_MS, false));
    }

    /**
     * The FIRST unopenable Welcome always repairs; a second within the hour does not.
     *
     * <p>The first arm is the one worth pinning: a never-repaired device has a zero stamp, and the
     * asymmetry with {@link MlsKeyPackagePolicy#republishDue} — which needs an explicit
     * never-published arm — is that {@code now - 0} is already enormous here.
     */
    @Test
    public void theFirstRepairIsAllowedAndTheSecondWithinTheHourIsNot() {
        final long now = 9_000_000L;
        assertTrue(MlsKeyPackagePolicy.poolRepairAllowed(0L, now));
        assertFalse(MlsKeyPackagePolicy.poolRepairAllowed(now - 1, now));
        assertFalse(MlsKeyPackagePolicy.poolRepairAllowed(
                now - MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS + 1, now));
        assertTrue(MlsKeyPackagePolicy.poolRepairAllowed(
                now - MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS, now));
    }
}
