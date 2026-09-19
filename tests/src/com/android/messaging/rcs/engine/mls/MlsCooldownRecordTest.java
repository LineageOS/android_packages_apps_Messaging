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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>The restart test for the two stamps that were made durable</b> — the rebuild episode
 * suppressor and the 1:1 re-establish cooldown. Invariant I3(a):
 * a charge survives a simulated restart, and no clock reading a rebooted device can produce refills
 * a spent bound.
 *
 * <p>The two windows are the transport's ({@code REBUILD_EPISODE_MS} = 60s,
 * {@code REESTABLISH_COOLDOWN_MS} = 10 min) and are passed in rather than declared here, so this
 * test is about the RECORD and not about either tuning. The values below are named after them only
 * so the failures read in the terms the caller uses.
 */
public final class MlsCooldownRecordTest {

    private static final long EPISODE_MS = 60_000L;
    private static final long COOLDOWN_MS = 10L * 60L * 1000L;
    private static final long HOUR = 60L * 60L * 1000L;

    /** The whole of "a process restart": through the stored form and back. */
    private static MlsCooldownRecord restart(final MlsCooldownRecord rec) {
        final MlsCooldownRecord back = MlsCooldownRecord.decode(rec.encode());
        assertNotNull("the stored form must be readable by the next process", back);
        return back;
    }

    // ---- I3(a): the charge survives a restart --------------------------------------------------

    /**
     * <b>The property this record exists for.</b> The in-memory version was handed back on every
     * restart, so a crash-restart loop walked straight through a suppressor whose whole purpose was
     * to stop a burst.
     */
    @Test
    public void anAttemptSurvivesAProcessRestart() {
        final long t = 5 * HOUR;
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(t));
        assertTrue("a restart one second after an attempt must still be inside the episode — that "
                + "restart is the burst the suppressor exists for",
                rec.withinCooldown(EPISODE_MS, t + 1000L));
        assertEquals(1000L, rec.ageMs(t + 1000L));
        assertEquals(EPISODE_MS - 1000L, rec.remainingMs(EPISODE_MS, t + 1000L));
    }

    /** Repeated restarts inside the window do not add up to an exit from it. */
    @Test
    public void aCrashLoopDoesNotOutrunTheWindow() {
        final long t = 3 * HOUR;
        MlsCooldownRecord rec = MlsCooldownRecord.attemptedAt(t);
        for (int i = 1; i <= 50; i++) {
            rec = restart(rec);
            assertTrue("restart " + i + " inside the window must still be suppressed",
                    rec.withinCooldown(EPISODE_MS, t + i * 1000L));
        }
        assertFalse("and the window does end — this is a suppressor, not a total",
                rec.withinCooldown(EPISODE_MS, t + EPISODE_MS));
    }

    /** The window ends exactly at its length: at it, not one millisecond before. */
    @Test
    public void theWindowEndsAtItsLength() {
        final long t = 7 * HOUR;
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(t));
        assertTrue(rec.withinCooldown(COOLDOWN_MS, t + COOLDOWN_MS - 1L));
        assertFalse(rec.withinCooldown(COOLDOWN_MS, t + COOLDOWN_MS));
        assertEquals(0L, rec.remainingMs(COOLDOWN_MS, t + COOLDOWN_MS));
    }

    // ---- the clock rule: retain, never refill ---------------------------------------------------

    /**
     * <b>No clock reading a rebooted device produces a refill.</b> A stored stamp AHEAD of the
     * current reading can only mean the device rebooted, and {@link MlsMonotonicAge} answers with
     * the current uptime — the largest age it can prove — so a reading below the window keeps the
     * cooldown shut.
     */
    @Test
    public void noClockReadingAfterARebootRefillsASpentCooldown() {
        final long charged = 40 * HOUR;
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(charged));
        for (long now = 0L; now < COOLDOWN_MS; now += 1000L) {
            assertTrue("a reading of " + now + " is below the stamp, i.e. a reboot — the cooldown "
                    + "must not open before the window has passed in UPTIME",
                    rec.withinCooldown(COOLDOWN_MS, now));
        }
        assertFalse("and once the uptime alone exceeds the window it does open — the price is "
                + "bounded, not permanent", rec.withinCooldown(COOLDOWN_MS, COOLDOWN_MS));
    }

    /** The mirror: a stamp far in the past is not made young again by any reading. */
    @Test
    public void theAgeIsNeverLargerThanTheTruth() {
        final long charged = 2 * HOUR;
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(charged));
        for (long now = 0L; now < 6 * HOUR; now += 7 * 60_000L) {
            final long age = rec.ageMs(now);
            assertTrue("an age must never be negative (now=" + now + ")", age >= 0L);
            final long truth = now >= charged ? now - charged : now;
            assertEquals("MlsMonotonicAge's answer is the whole of what this record may claim",
                    truth, age);
        }
    }

    // ---- the reader's three answers --------------------------------------------------------------

    /**
     * <b>UNREADABLE must not share a return value with ABSENT.</b> {@link MlsCooldownRecord#NONE}
     * means the operation is free to run; a record we cannot read means we cannot say whether it
     * just ran, and a caller that spells those the same way lets a format skew hand the allowance
     * back — which is the bug the record was written to close.
     */
    @Test
    public void absentAndUnreadableAreDifferentAnswers() {
        assertSame("nothing stored is NONE", MlsCooldownRecord.NONE, MlsCooldownRecord.decode(null));
        assertSame("an empty string is nothing stored",
                MlsCooldownRecord.NONE, MlsCooldownRecord.decode(""));
        for (final String bad : new String[] {
            "garbage", "1", "1|", "1|abc", "2|1234", "|1234", "1|-5", "0|1234", "1|1|1",
        }) {
            assertNull("\"" + bad + "\" is stored and unreadable, which is not the same answer as "
                    + "nothing being stored", MlsCooldownRecord.decode(bad));
        }
    }

    /** NONE is the OLDEST answer, not the youngest — spelling it 0 would suppress on an empty store. */
    @Test
    public void nothingRecordedReadsAsInfinitelyOld() {
        assertEquals(Long.MAX_VALUE, MlsCooldownRecord.NONE.ageMs(12345L));
        assertFalse("an operation never attempted must not be suppressed",
                MlsCooldownRecord.NONE.withinCooldown(COOLDOWN_MS, 12345L));
        assertEquals(0L, MlsCooldownRecord.NONE.remainingMs(COOLDOWN_MS, 12345L));
        assertFalse(MlsCooldownRecord.NONE.present());
        assertTrue(MlsCooldownRecord.attemptedAt(0L).present());
    }

    /**
     * Zero is a real stamp, not "absent". elapsedRealtime really is ~0 for the first milliseconds
     * after a boot, and a record that read those as "never attempted" would drop exactly the
     * attempts made during the boot storm this bound exists for.
     */
    @Test
    public void aStampOfZeroIsAnAttempt() {
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(0L));
        assertTrue(rec.present());
        assertTrue(rec.withinCooldown(EPISODE_MS, 1L));
        assertEquals(0L, rec.ageMs(0L));
    }

    /** A disabled window suppresses nothing — the same reading {@code gateExpired} gives it. */
    @Test
    public void aNonPositiveWindowSuppressesNothing() {
        final MlsCooldownRecord rec = MlsCooldownRecord.attemptedAt(1000L);
        assertFalse(rec.withinCooldown(0L, 1000L));
        assertFalse(rec.withinCooldown(-1L, 1000L));
    }

    /** {@code sameAs} is what keeps a burst of attempts off the disk. */
    @Test
    public void sameAsComparesTheStampAndPresence() {
        assertTrue(MlsCooldownRecord.attemptedAt(77L).sameAs(MlsCooldownRecord.attemptedAt(77L)));
        assertFalse(MlsCooldownRecord.attemptedAt(77L).sameAs(MlsCooldownRecord.attemptedAt(78L)));
        assertFalse(MlsCooldownRecord.attemptedAt(0L).sameAs(MlsCooldownRecord.NONE));
        assertFalse(MlsCooldownRecord.NONE.sameAs(null));
        assertTrue(MlsCooldownRecord.NONE.sameAs(MlsCooldownRecord.decode(null)));
    }
}
