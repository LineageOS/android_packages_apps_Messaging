/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rebuild window survives a restart, is not refilled by a clock move, and adopts a legacy
 * wall-clock record without handing the allowance back. Every charge crosses an encode/decode
 * boundary, and every reading a rebooted device can produce is swept. See docs/mls/budgets.md.
 */
public final class MlsRebuildWindowRecordTest {

    private static final long WINDOW = 6L * 60 * 60 * 1000;
    private static final int MAX = 3;

    /** One claim: roll, refuse if spent, otherwise charge, always through the store. */
    private static String claim(final String stored, final long nowElapsedMs,
            final boolean[] allowed) {
        final MlsRebuildWindowRecord dec = MlsRebuildWindowRecord.decode(stored);
        assertNotNull("readable record read as unreadable: " + stored, dec);
        final MlsRebuildWindowRecord rolled = dec.rolled(WINDOW, nowElapsedMs);
        if (rolled.spent(MAX)) {
            allowed[0] = false;
            return rolled.encode();
        }
        allowed[0] = true;
        return rolled.charged().encode();
    }

    @Test
    public void absentAndUnreadableAreDifferentAnswers() {
        assertEquals(MlsRebuildWindowRecord.EMPTY, MlsRebuildWindowRecord.decode(null));
        assertEquals(MlsRebuildWindowRecord.EMPTY, MlsRebuildWindowRecord.decode(""));
        // Stored and unreadable does not decode to "nothing recorded", which would hand back a full
        // allowance on a parse failure.
        assertNull(MlsRebuildWindowRecord.decode("2|not-a-number|5"));
        assertNull(MlsRebuildWindowRecord.decode("1|x|5"));
        assertNull(MlsRebuildWindowRecord.decode("1|2"));
        assertNull(MlsRebuildWindowRecord.decode("1|2|-9"));
        assertNull(MlsRebuildWindowRecord.decode("1|-2|9"));
        assertNull(MlsRebuildWindowRecord.decode("garbage"));
        assertNull(MlsRebuildWindowRecord.decode(":5"));
    }

    @Test
    public void theStoredFormRoundTrips() {
        final MlsRebuildWindowRecord r =
                MlsRebuildWindowRecord.EMPTY.rolled(WINDOW, 90_000L).charged().charged();
        final MlsRebuildWindowRecord back = MlsRebuildWindowRecord.decode(r.encode());
        assertNotNull(back);
        assertEquals(2, back.count());
        assertEquals(90_000L, back.windowStartElapsedMs());
        assertFalse(back.isLegacyWallClock());
    }

    /**
     * An unstarted window is not written as "opened at boot": 0 is a valid elapsedRealtime reading,
     * and would age out immediately and refill the allowance.
     */
    @Test
    public void anUnstartedWindowRoundTripsAsUnstartedNotAsZero() {
        final String raw = MlsRebuildWindowRecord.EMPTY.encode();
        final MlsRebuildWindowRecord back = MlsRebuildWindowRecord.decode(raw);
        assertNotNull(back);
        assertEquals(MlsRebuildWindowRecord.UNSTARTED, back.windowStartElapsedMs());
        assertEquals(WINDOW, back.remainingMs(WINDOW, 10_000_000L));
        // It opens at the reading it is rolled with, not at 0.
        assertEquals(10_000_000L, back.rolled(WINDOW, 10_000_000L).windowStartElapsedMs());
    }

    @Test
    public void theAllowanceRunsOutAcrossRestarts() {
        String stored = null;
        final boolean[] allowed = new boolean[1];
        long now = 1_000L;
        for (int i = 0; i < MAX; i++) {
            stored = claim(stored, now, allowed);
            assertTrue("charge " + i + " was refused inside the window", allowed[0]);
            now += 60_000L;                     // a minute apart, well inside the window
        }
        stored = claim(stored, now, allowed);
        assertFalse("a fourth rebuild was allowed inside the window", allowed[0]);
    }

    /** A rate: once the window is left behind the allowance comes back. */
    @Test
    public void theAllowanceRefillsOnceTheWindowHasPassedInUptime() {
        String stored = null;
        final boolean[] allowed = new boolean[1];
        for (int i = 0; i < MAX; i++) stored = claim(stored, 1_000L, allowed);
        assertFalse(claimAllowed(stored, 1_000L + WINDOW - 1));
        assertTrue(claimAllowed(stored, 1_000L + WINDOW));
    }

    private static boolean claimAllowed(final String stored, final long nowElapsedMs) {
        final boolean[] allowed = new boolean[1];
        claim(stored, nowElapsedMs, allowed);
        return allowed[0];
    }

    /**
     * A charge inside the window does not move its start, or the refill would trail the last
     * rebuild.
     */
    @Test
    public void aChargeDoesNotExtendTheWindow() {
        String stored = null;
        final boolean[] allowed = new boolean[1];
        stored = claim(stored, 1_000L, allowed);
        stored = claim(stored, 1_000L + WINDOW / 2, allowed);
        final MlsRebuildWindowRecord r = MlsRebuildWindowRecord.decode(stored);
        assertNotNull(r);
        assertEquals(1_000L, r.windowStartElapsedMs());
    }

    /**
     * Swept over every reboot reading below the stamp: the budget stays spent for the whole window
     * in uptime.
     */
    @Test
    public void noClockReadingRefillsASpentWindow() {
        String stored = null;
        final boolean[] allowed = new boolean[1];
        // The window opened more than a window-length into the previous boot, so every reading
        // swept below it, the last included, is one only a rebooted device can produce.
        final long charged = WINDOW + 5_000_000L;
        for (int i = 0; i < MAX; i++) stored = claim(stored, charged, allowed);
        for (long now = 0L; now < WINDOW; now += 97_000L) {
            assertFalse("a rebooted device with uptime " + now + " refilled a spent window",
                    claimAllowed(stored, now));
        }
        // It refills once the window has passed in uptime.
        assertTrue(claimAllowed(stored, WINDOW));
        // Same boot: the window counts from where it opened.
        assertFalse(claimAllowed(stored, charged + WINDOW - 1));
        assertTrue(claimAllowed(stored, charged + WINDOW));
    }

    /**
     * The legacy form is adopted, not discarded: discarding would refill the allowance on upgrade;
     * adopting keeps the count and opens a fresh window.
     */
    @Test
    public void aLegacyWallClockRecordKeepsItsCountAndOpensAFreshWindow() {
        final String legacy = "3:1786000000000";     // count 3, a wall stamp
        final MlsRebuildWindowRecord dec = MlsRebuildWindowRecord.decode(legacy);
        assertNotNull("the legacy form must be readable, not unreadable", dec);
        assertTrue(dec.isLegacyWallClock());
        assertEquals(3, dec.count());
        assertEquals(MlsRebuildWindowRecord.UNSTARTED, dec.windowStartElapsedMs());
        assertFalse("an upgrade must not hand the allowance back", claimAllowed(legacy, 120_000L));

        final MlsRebuildWindowRecord adopted = dec.rolled(WINDOW, 120_000L);
        assertFalse("the adopted record must not still be legacy, or it re-adopts forever",
                adopted.isLegacyWallClock());
        assertEquals(120_000L, adopted.windowStartElapsedMs());
        assertEquals(3, adopted.count());
        // Persisting the adoption stops the window restarting on every attempt.
        assertTrue(claimAllowed(adopted.encode(), 120_000L + WINDOW));
    }

    /** A legacy record below the limit is adopted and charged in one step, in the new format. */
    @Test
    public void aLegacyRecordBelowTheLimitIsAdoptedAndCharged() {
        final boolean[] allowed = new boolean[1];
        final String after = claim("1:1786000000000", 300_000L, allowed);
        assertTrue(allowed[0]);
        final MlsRebuildWindowRecord r = MlsRebuildWindowRecord.decode(after);
        assertNotNull(r);
        assertFalse(r.isLegacyWallClock());
        assertEquals(2, r.count());
        assertEquals(300_000L, r.windowStartElapsedMs());
    }

    /** {@code remainingMs} may overstate the wait, never understate it. */
    @Test
    public void remainingIsNeverAnUnderstatement() {
        final MlsRebuildWindowRecord r = MlsRebuildWindowRecord.EMPTY.rolled(WINDOW, 1_000_000L);
        assertEquals(WINDOW, r.remainingMs(WINDOW, 1_000_000L));
        assertEquals(WINDOW - 500L, r.remainingMs(WINDOW, 1_000_500L));
        assertEquals(0L, r.remainingMs(WINDOW, 1_000_000L + WINDOW));
        // Rebooted: the age is the uptime, so the remaining time is the larger answer.
        assertEquals(WINDOW - 5_000L, r.remainingMs(WINDOW, 5_000L));
    }

    /** A corrupt count is refused rather than accepted as a number no comparison can bound. */
    @Test
    public void anAbsurdCountIsUnreadableRatherThanAuthoritative() {
        assertNull(MlsRebuildWindowRecord.decode("1|999999999|5"));
        assertNull(MlsRebuildWindowRecord.decode("999999999:1786000000000"));
    }
}
