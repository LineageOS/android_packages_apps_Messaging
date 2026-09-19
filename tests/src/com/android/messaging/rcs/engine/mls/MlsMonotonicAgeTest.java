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
 * The clock rule the durable guards age by.
 *
 * <p>The property under test is not "the arithmetic is right"; it is <b>the answer is never larger
 * than the time that actually passed</b>. Everything a durable budget does with an age is drop the
 * entry when it exceeds a window, so an over-estimate is a refilled budget and an under-estimate is
 * a budget that stays spent slightly too long. Those two are not symmetric, which is the whole
 * design.
 */
public final class MlsMonotonicAgeTest {

    @Test
    public void withinOneBootTheAgeIsExact() {
        assertEquals(0L, MlsMonotonicAge.ageMs(1_000L, 1_000L));
        assertEquals(500L, MlsMonotonicAge.ageMs(1_000L, 1_500L));
        assertEquals(86_400_000L, MlsMonotonicAge.ageMs(0L, 86_400_000L));
    }

    /**
     * A reading that has gone BACKWARDS means the device rebooted. The charge predates this boot, so
     * the uptime is a true lower bound — and it is all we can prove without consulting a wall clock
     * we have just decided not to trust.
     */
    @Test
    public void afterARebootTheAgeIsTheUptime() {
        assertEquals(0L, MlsMonotonicAge.ageMs(5_000_000L, 0L));
        assertEquals(90_000L, MlsMonotonicAge.ageMs(5_000_000L, 90_000L));
        assertTrue(MlsMonotonicAge.rebootedSince(5_000_000L, 90_000L));
        assertFalse(MlsMonotonicAge.rebootedSince(1_000L, 90_000L));
    }

    /**
     * The invariant, swept rather than sampled: for every pair of readings, the age never exceeds
     * the time that could actually have passed. Within a boot that ceiling is the difference; across
     * one it is unbounded above but at least the uptime, so the uptime is the value to check
     * against.
     */
    @Test
    public void theAgeIsNeverLargerThanTheTimeThatPassed() {
        final long[] readings = {0L, 1L, 999L, 60_000L, 3_600_000L, 86_400_000L, 987_654_321L};
        for (final long charged : readings) {
            for (final long now : readings) {
                final long age = MlsMonotonicAge.ageMs(charged, now);
                assertTrue("age must not be negative: " + charged + " -> " + now, age >= 0L);
                if (charged <= now) {
                    assertEquals("same boot: the age is the difference", now - charged, age);
                } else {
                    // Rebooted. The true age is at least the uptime and may be far more; answering
                    // the uptime is the largest provable value and never exceeds the truth.
                    assertEquals("after a reboot the age is the uptime", now, age);
                }
            }
        }
    }

    /** A reading that cannot be true answers the YOUNGEST age, never the oldest. */
    @Test
    public void anImpossibleReadingIsTreatedAsYoung() {
        assertEquals(0L, MlsMonotonicAge.ageMs(-1L, 5_000L));
        assertEquals(0L, MlsMonotonicAge.ageMs(5_000L, -1L));
        assertFalse(MlsMonotonicAge.rebootedSince(-1L, 5_000L));
    }
}
