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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * G2's era budget, and the two properties the durability rule exists for.
 *
 * <p>Asserted as properties, not as a worked example: <b>a charge survives a process restart</b>
 * (the restart being the encode/decode boundary — nothing else crosses it) and <b>no clock movement
 * refills a spent budget</b> (structurally, because nothing here reads a wall clock at all).
 */
public final class MlsEraBudgetRecordTest {

    private static final long HOUR = MlsEraBudgetRecord.HOUR_MS;
    private static final long DAY = MlsEraBudgetRecord.DAY_MS;

    /** A restart: everything that crosses it is the encoded string, and nothing else. */
    private static MlsEraBudgetRecord restart(final MlsEraBudgetRecord rec) {
        final MlsEraBudgetRecord back = MlsEraBudgetRecord.decode(rec.encode());
        assertNotNull("the stored form must be readable by the next process", back);
        return back;
    }

    @Test
    public void aChargeSurvivesAProcessRestart() {
        final long t = 10 * HOUR;
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        for (int i = 0; i < MlsEraBudgetRecord.MAX_PER_HOUR; i++) {
            assertFalse("charge " + i + " must be inside the hourly allowance", rec.spent(t));
            rec = rec.charged(t);
            rec = restart(rec);              // the process dies after every single charge
        }
        assertTrue("the hourly allowance is spent and the restarts did not give it back",
                rec.spent(t));
        assertEquals(MlsEraBudgetRecord.MAX_PER_HOUR, rec.countWithin(HOUR, t));
    }

    /**
     * The daily bound is the one the incident needed: ~17 advances over three weeks, i.e. across
     * many process lifetimes, which an in-memory day window could not have counted.
     */
    @Test
    public void theDailyAllowanceSurvivesRestartsAcrossTheWholeDay() {
        final long start = 24 * HOUR;
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        long t = start;
        for (int i = 0; i < MlsEraBudgetRecord.MAX_PER_DAY; i++) {
            t = start + i * (2 * HOUR);      // spread out, so only the daily bound can bite
            assertFalse("charge " + i + " is within the daily allowance", rec.spent(t));
            rec = restart(rec.charged(t));
        }
        assertTrue("the daily allowance is spent", rec.spent(t));
        assertEquals(MlsEraBudgetRecord.MAX_PER_DAY, rec.countWithin(DAY, t));
        // Still spent 23 hours later, still across a restart.
        assertTrue(restart(rec).spent(start + 23 * HOUR));
    }

    /**
     * <b>The clock cannot refill it.</b> A spent budget is re-examined at every reading a rebooted
     * device can produce — including readings far below the ones the charges carry, which is what a
     * wall clock moving backwards would look like to a budget aged by one — and stays spent for as
     * long as the window has not elapsed in uptime.
     */
    @Test
    public void aClockMovingBackwardsDoesNotRefillTheBudget() {
        final long charged = 40 * HOUR;                  // late in a long-running boot
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        for (int i = 0; i < MlsEraBudgetRecord.MAX_PER_HOUR; i++) rec = rec.charged(charged);
        assertTrue(rec.spent(charged));

        final MlsEraBudgetRecord afterRestart = restart(rec);
        for (long now = 0L; now < HOUR; now += 60_000L) {
            assertTrue("a reading of " + now + " is BELOW the charge stamps — the only honest "
                    + "reading of that is a reboot, and the budget must not refill until the window "
                    + "has passed in uptime", afterRestart.spent(now));
        }
        // And it does come back — this is a rate, not a total.
        assertFalse("an hour of uptime refills the hourly allowance",
                afterRestart.spent(HOUR + 1_000L));
    }

    /** Time genuinely passing, within one boot, refills both windows in order. */
    @Test
    public void theWindowsRoll() {
        final long t0 = 5 * HOUR;
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        for (int i = 0; i < MlsEraBudgetRecord.MAX_PER_HOUR; i++) rec = rec.charged(t0);
        assertTrue(rec.spent(t0));
        assertFalse("past the hour the hourly allowance is back", rec.spent(t0 + HOUR + 1));
        assertEquals("but the charges still count against the day",
                MlsEraBudgetRecord.MAX_PER_HOUR, rec.countWithin(DAY, t0 + HOUR + 1));
        assertEquals("past the day they are gone", 0, rec.countWithin(DAY, t0 + DAY + 1));
        assertTrue("and are pruned out of the stored form", rec.pruned(t0 + DAY + 1).isEmpty());
    }

    /** A boundary is a boundary: exactly at the window the charge still counts. */
    @Test
    public void theWindowBoundaryIsInclusive() {
        final MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY.charged(HOUR);
        assertEquals(1, rec.countWithin(HOUR, HOUR + HOUR));
        assertEquals(0, rec.countWithin(HOUR, HOUR + HOUR + 1));
    }

    /**
     * UNREADABLE must not be spelled the same way as ABSENT. An unreadable record read as an empty
     * one hands back the full allowance on the strength of a parse failure.
     */
    @Test
    public void unreadableIsNotAbsent() {
        assertEquals("nothing stored is an empty budget",
                MlsEraBudgetRecord.EMPTY.encode(), MlsEraBudgetRecord.decode(null).encode());
        assertTrue(MlsEraBudgetRecord.decode("").isEmpty());
        assertTrue("a version with no entries is a real, empty record",
                MlsEraBudgetRecord.decode("1|").isEmpty());
        assertNull("no version marker", MlsEraBudgetRecord.decode("123,456"));
        assertNull("an unknown version", MlsEraBudgetRecord.decode("2|123"));
        assertNull("a non-numeric stamp", MlsEraBudgetRecord.decode("1|123,abc"));
        assertNull("a negative stamp", MlsEraBudgetRecord.decode("1|-5"));
        final StringBuilder tooMany = new StringBuilder("1|0");
        for (int i = 0; i < 200; i++) tooMany.append(",").append(i);
        assertNull("an implausible number of entries", MlsEraBudgetRecord.decode(tooMany.toString()));
    }

    /** The stored form round-trips exactly, which is the only thing "durable" can mean here. */
    @Test
    public void theStoredFormRoundTrips() {
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        for (int i = 0; i < MlsEraBudgetRecord.MAX_PER_DAY; i++) rec = rec.charged(i * 3 * HOUR);
        final MlsEraBudgetRecord back = restart(rec);
        assertEquals(rec.size(), back.size());
        assertEquals(rec.encode(), back.encode());
        final long now = MlsEraBudgetRecord.MAX_PER_DAY * 3 * HOUR;
        assertEquals(rec.countWithin(DAY, now), back.countWithin(DAY, now));
    }

    /** A record cannot grow without bound even if one arrives oversized. */
    @Test
    public void theRecordIsBoundedBySizeAsWellAsByTime() {
        MlsEraBudgetRecord rec = MlsEraBudgetRecord.EMPTY;
        for (int i = 0; i < 20; i++) rec = rec.charged(HOUR);   // ignoring spent(), on purpose
        assertEquals(MlsEraBudgetRecord.MAX_PER_DAY, rec.size());
    }
}
