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
 * G4's peer-health streak — durable, and bounded by the evidence that
 * supports it.
 */
public final class MlsPeerHealthRecordTest {

    private static final long HOUR = MlsEraBudgetRecord.HOUR_MS;
    private static final int MAX = MlsPeerHealthRecord.MAX_CONSECUTIVE_FAILURES;

    private static MlsPeerHealthRecord restart(final MlsPeerHealthRecord rec) {
        final MlsPeerHealthRecord back = MlsPeerHealthRecord.decode(rec.encode());
        assertNotNull("the stored form must be readable by the next process", back);
        return back;
    }

    @Test
    public void aStreakSurvivesAProcessRestart() {
        final long t = 3 * HOUR;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 1; i <= MAX; i++) {
            rec = restart(rec.withFailureAt(t + i * 60_000L));
            assertEquals(i, rec.streakAt(t + i * 60_000L));
        }
        assertTrue("the peer is wedged and a restart did not declare it healthy",
                restart(rec).tripped(t + MAX * 60_000L));
    }

    /** No clock movement can age the evidence out early — the same sweep the era budget gets. */
    @Test
    public void aClockMovingBackwardsDoesNotClearTheStreak() {
        final long charged = 40 * HOUR;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 0; i < MAX; i++) {
            rec = rec.withFailureAt(charged + i * MlsPeerHealthRecord.STAMP_COALESCE_MS);
        }
        final long last = charged + (MAX - 1) * MlsPeerHealthRecord.STAMP_COALESCE_MS;
        assertTrue(rec.tripped(last));
        final MlsPeerHealthRecord back = restart(rec);
        for (long now = 0L; now < MlsPeerHealthRecord.EVIDENCE_MS; now += HOUR) {
            assertTrue("a reading of " + now + " is below the stamp, i.e. a reboot — the streak must "
                    + "not expire before the evidence window has passed in uptime", back.tripped(now));
        }
    }

    /**
     * And it is not a life sentence. The window is the half of the rule that persistence
     * forces: without it, making the streak durable would leave a conversation whose last rung of
     * automatic repair is refused forever.
     */
    @Test
    public void theStreakExpiresWithTheEvidenceThatSupportsIt() {
        final long t = 2 * MlsPeerHealthRecord.EVIDENCE_MS;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 0; i < MAX; i++) {
            rec = rec.withFailureAt(t + i * MlsPeerHealthRecord.STAMP_COALESCE_MS);
        }
        final long last = t + (MAX - 1) * MlsPeerHealthRecord.STAMP_COALESCE_MS;
        assertTrue(rec.tripped(last + MlsPeerHealthRecord.EVIDENCE_MS));
        assertFalse("one millisecond past the window the claim has no evidence left",
                rec.tripped(last + MlsPeerHealthRecord.EVIDENCE_MS + 1));
        assertTrue(rec.stale(last + MlsPeerHealthRecord.EVIDENCE_MS + 1));
        assertEquals(0, rec.streakAt(last + MlsPeerHealthRecord.EVIDENCE_MS + 1));
    }

    /** A failure after the window starts a NEW run rather than resuming an expired one. */
    @Test
    public void aStaleRecordRestartsTheRun() {
        final long t = 10 * HOUR;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 0; i < MAX; i++) {
            rec = rec.withFailureAt(t + i * MlsPeerHealthRecord.STAMP_COALESCE_MS);
        }
        final long later = t + 3 * MlsPeerHealthRecord.EVIDENCE_MS;
        final MlsPeerHealthRecord next = rec.withFailureAt(later);
        assertEquals(1, next.streakAt(later));
        assertFalse(next.tripped(later));
    }

    /**
     * Past the trip point a burst of reports must not become a burst of synchronous disk writes.
     * The record answers "nothing changed" so the guard can skip the commit.
     */
    @Test
    public void aSaturatedStreakCoalescesItsEvidenceStamp() {
        final long t = 7 * HOUR;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 0; i < MAX; i++) rec = rec.withFailureAt(t);
        assertTrue(rec.tripped(t));
        assertSame("a report in the same burst changes nothing", rec, rec.withFailureAt(t + 1_000L));
        assertTrue(rec.sameAs(rec.withFailureAt(t + 1_000L)));
        final MlsPeerHealthRecord refreshed =
                rec.withFailureAt(t + MlsPeerHealthRecord.STAMP_COALESCE_MS + 1);
        assertFalse("past the coalescing window the evidence is refreshed", rec.sameAs(refreshed));
        assertTrue(refreshed.tripped(t + MlsPeerHealthRecord.STAMP_COALESCE_MS + 1));
        assertEquals("and the count still saturates", MAX,
                refreshed.streakAt(t + MlsPeerHealthRecord.STAMP_COALESCE_MS + 1));
    }

    @Test
    public void unreadableIsNotAbsent() {
        assertSame("nothing stored is a peer with no failures",
                MlsPeerHealthRecord.NONE, MlsPeerHealthRecord.decode(null));
        assertSame(MlsPeerHealthRecord.NONE, MlsPeerHealthRecord.decode(""));
        assertSame("an explicit zero is the same as absent",
                MlsPeerHealthRecord.NONE, MlsPeerHealthRecord.decode("1|0@0"));
        assertNull("no version marker", MlsPeerHealthRecord.decode("2@123"));
        assertNull("an unknown version", MlsPeerHealthRecord.decode("2|1@123"));
        assertNull("no stamp separator", MlsPeerHealthRecord.decode("1|2"));
        assertNull("a non-numeric streak", MlsPeerHealthRecord.decode("1|x@123"));
        assertNull("a streak above the trip point cannot have been written by us",
                MlsPeerHealthRecord.decode("1|9@123"));
        assertNull("a negative stamp", MlsPeerHealthRecord.decode("1|2@-1"));
    }

    @Test
    public void theStoredFormRoundTrips() {
        final long t = 11 * HOUR;
        MlsPeerHealthRecord rec = MlsPeerHealthRecord.NONE;
        for (int i = 0; i < MAX; i++) {
            rec = rec.withFailureAt(t + i * MlsPeerHealthRecord.STAMP_COALESCE_MS);
        }
        final MlsPeerHealthRecord back = restart(rec);
        assertTrue(back.sameAs(rec));
        assertEquals(rec.encode(), back.encode());
    }
}
