/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link MlsCooldownRecord}, the durable stamp behind the rebuild-episode suppressor and the 1:1
 * re-establish cooldown: a charge survives a restart, and no clock reading on a rebooted device
 * refills a spent bound. The windows are the transport's and are passed in, so this tests the
 * record, not the tuning. See docs/mls/budgets.md.
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

    /** A charge survives a restart, so a crash-restart loop cannot walk through the suppressor. */
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

    /**
     * A stored stamp ahead of the current reading means the device rebooted;
     * {@link MlsMonotonicAge} then answers with the current uptime, the largest age it can prove,
     * so the cooldown stays shut.
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

    /**
     * Unreadable does not share a return value with absent: {@link MlsCooldownRecord#NONE} means
     * free to run, while an unreadable record cannot say whether the operation just ran.
     */
    @Test
    public void absentAndUnreadableAreDifferentAnswers() {
        assertSame("nothing stored is NONE", MlsCooldownRecord.NONE,
                MlsCooldownRecord.decode(null));
        assertSame("an empty string is nothing stored",
                MlsCooldownRecord.NONE, MlsCooldownRecord.decode(""));
        for (final String bad : new String[] {
            "garbage", "1", "1|", "1|abc", "2|1234", "|1234", "1|-5", "0|1234", "1|1|1",
        }) {
            assertNull("\"" + bad + "\" is stored and unreadable, which is not the same answer as "
                    + "nothing being stored", MlsCooldownRecord.decode(bad));
        }
    }

    /**
     * NONE is the oldest answer, not the youngest; spelling it 0 would suppress on an empty store.
     */
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
     * Zero is a real stamp: elapsedRealtime is near 0 just after boot, which is when the bound
     * matters most.
     */
    @Test
    public void aStampOfZeroIsAnAttempt() {
        final MlsCooldownRecord rec = restart(MlsCooldownRecord.attemptedAt(0L));
        assertTrue(rec.present());
        assertTrue(rec.withinCooldown(EPISODE_MS, 1L));
        assertEquals(0L, rec.ageMs(0L));
    }

    /** A disabled window suppresses nothing, as {@code gateExpired} reads it. */
    @Test
    public void aNonPositiveWindowSuppressesNothing() {
        final MlsCooldownRecord rec = MlsCooldownRecord.attemptedAt(1000L);
        assertFalse(rec.withinCooldown(0L, 1000L));
        assertFalse(rec.withinCooldown(-1L, 1000L));
    }

    /** {@code sameAs} keeps a burst of attempts off the disk. */
    @Test
    public void sameAsComparesTheStampAndPresence() {
        assertTrue(MlsCooldownRecord.attemptedAt(77L).sameAs(MlsCooldownRecord.attemptedAt(77L)));
        assertFalse(MlsCooldownRecord.attemptedAt(77L).sameAs(MlsCooldownRecord.attemptedAt(78L)));
        assertFalse(MlsCooldownRecord.attemptedAt(0L).sameAs(MlsCooldownRecord.NONE));
        assertFalse(MlsCooldownRecord.NONE.sameAs(null));
        assertTrue(MlsCooldownRecord.NONE.sameAs(MlsCooldownRecord.decode(null)));
    }
}
