/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The age {@link MlsMonotonicAge} reports is never larger than the time that actually passed: an
 * over-estimate refills a durable budget, an under-estimate only keeps it spent a little longer.
 * See docs/mls/budgets.md.
 */
public final class MlsMonotonicAgeTest {

    @Test
    public void withinOneBootTheAgeIsExact() {
        assertEquals(0L, MlsMonotonicAge.ageMs(1_000L, 1_000L));
        assertEquals(500L, MlsMonotonicAge.ageMs(1_000L, 1_500L));
        assertEquals(86_400_000L, MlsMonotonicAge.ageMs(0L, 86_400_000L));
    }

    /** A reading that went backwards means a reboot; the uptime is the largest provable age. */
    @Test
    public void afterARebootTheAgeIsTheUptime() {
        assertEquals(0L, MlsMonotonicAge.ageMs(5_000_000L, 0L));
        assertEquals(90_000L, MlsMonotonicAge.ageMs(5_000_000L, 90_000L));
        assertTrue(MlsMonotonicAge.rebootedSince(5_000_000L, 90_000L));
        assertFalse(MlsMonotonicAge.rebootedSince(1_000L, 90_000L));
    }

    /** Swept over every pair of readings: the age never exceeds the time that could have passed. */
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
                    // Rebooted: the uptime is the largest provable age and never exceeds the truth.
                    assertEquals("after a reboot the age is the uptime", now, age);
                }
            }
        }
    }

    /** A reading that cannot be true answers the youngest age, never the oldest. */
    @Test
    public void anImpossibleReadingIsTreatedAsYoung() {
        assertEquals(0L, MlsMonotonicAge.ageMs(-1L, 5_000L));
        assertEquals(0L, MlsMonotonicAge.ageMs(5_000L, -1L));
        assertFalse(MlsMonotonicAge.rebootedSince(-1L, 5_000L));
    }
}
