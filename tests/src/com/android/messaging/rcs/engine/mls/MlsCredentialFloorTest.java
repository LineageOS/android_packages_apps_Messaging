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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The band the client could not see.
 *
 * <p>The device measurement these tests are built on, {@code deviceB} 2026-09-10, group
 * {@code 5B8905CD-DEF5-414C-BC3F-5343069C256D}: four members at 29/25/25/25 days remaining, every
 * membership Commit refused by the server, and {@code expiredMemberCount} reporting <b>zero</b>.
 */
public class MlsCredentialFloorTest {

    private static final long DAY = 86400L;
    /** 2026-09-10T00:00:00Z. */
    private static final long NOW = 1789084800L;
    private static final long FLOOR = MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS;

    private static Map<Integer, long[]> roster(final long... notAfters) {
        final Map<Integer, long[]> m = new LinkedHashMap<>();
        for (int i = 0; i < notAfters.length; i++) {
            m.put(i, new long[] { NOW - 60 * DAY, notAfters[i] });
        }
        return m;
    }

    /**
     * THE MEASUREMENT, replayed. Four members inside the floor and none expired: the old predicate
     * ({@code na <= now}) answers 0, and this one answers 4.
     */
    @Test
    public void theBandIsCountedWhereActualExpiryCountsNothing() {
        final Map<Integer, long[]> v =
                roster(NOW + 29 * DAY, NOW + 25 * DAY, NOW + 25 * DAY, NOW + 25 * DAY);
        int oldPredicate = 0;
        for (final long[] w : v.values()) if (w[1] <= NOW) oldPredicate++;
        assertEquals("the predicate this replaces sees nothing", 0, oldPredicate);

        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, names(), NOW, FLOOR);
        assertEquals("all four are inside the 30-day floor", 4, r.insideFloor);
        assertEquals("and none has actually lapsed", 0, r.expired);
        assertEquals(4, r.total);
        assertTrue("so the server refuses membership changes on this group",
                r.membershipChangesWouldBeRefused());
    }

    /** The member is NAMED. The refusal names an MSISDN; a report that cannot is half a diagnosis. */
    @Test
    public void theReportNamesTheMemberAndTheDaysLeft() {
        final Map<Integer, long[]> v = roster(NOW + 200 * DAY, NOW + 25 * DAY);
        final Map<Integer, String> n = new LinkedHashMap<>();
        n.put(0, "+15715550107");
        n.put(1, "+15715550104");
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, n, NOW, FLOOR);
        assertEquals(1, r.below.size());
        assertEquals("+15715550104 groupLeaf=25d", r.below.get(0));
    }

    /** With no names available it still reports, by leaf index. Silence is the thing being fixed. */
    @Test
    public void anUnnamedRosterIsStillReportedByLeafIndex() {
        final MlsCredentialFloor.Report r =
                MlsCredentialFloor.classify(roster(NOW + 25 * DAY), names(), NOW, FLOOR);
        assertEquals(1, r.below.size());
        assertEquals("leaf=0 groupLeaf=25d", r.below.get(0));
    }

    /**
     * THE ARTEFACT LABEL IS PART OF THE CONTRACT, not formatting.
     *
     * <p>Unlabelled, this list is byte-identical to {@code MlsFloorRebuild.Preflight
     * #notRepublished}, which measures the KDS POOL rather than the group's ratchet tree — the
     * same MSISDN was recorded as {@code 41d} there and {@code 24d} here, and the roster reading
     * was then quoted onward as a statement about the fleet's certificates. A bare "N days" in
     * this area is not a measurement, because it does not say what was measured.
     */
    @Test
    public void everyBelowEntryNamesWhichClockItMeasured() {
        final Map<Integer, long[]> v = roster(NOW + 200 * DAY, NOW + 25 * DAY, NOW - DAY);
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, names(), NOW, FLOOR);
        assertFalse("the report has entries to check", r.below.isEmpty());
        for (final String entry : r.below) {
            assertTrue("every below= entry names its artefact, got: " + entry,
                    entry.contains("groupLeaf="));
        }
    }

    /** Exactly 30 days is NOT inside the floor; 29 days 23 h is. The boundary is {@code <}. */
    @Test
    public void theBoundaryIsExclusive() {
        assertFalse("exactly 30 days left is above the floor",
                MlsCredentialFloor.insideFloor(NOW + 30 * DAY, NOW, FLOOR));
        assertTrue("one second under is inside it",
                MlsCredentialFloor.insideFloor(NOW + 30 * DAY - 1, NOW, FLOOR));
    }

    /**
     * UNREADABLE IS NOT EXPIRED AND NOT INSIDE THE FLOOR — the rule {@code expiredMemberCount}
     * already keeps and this one must not lose. Folding "we could not parse this leaf" into either
     * count would let a credential we merely do not understand refuse a membership change.
     */
    @Test
    public void anUnreadableLeafIsItsOwnAnswer() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { 0L, 0L });
        v.put(1, new long[] { NOW - DAY, NOW + 200 * DAY });
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, names(), NOW, FLOOR);
        assertEquals(1, r.unreadable);
        assertEquals(0, r.insideFloor);
        assertEquals(0, r.expired);
        assertFalse("an unreadable member must not block a membership change on its own",
                r.membershipChangesWouldBeRefused());
        assertEquals(MlsCredentialFloor.Standing.UNREADABLE, r.standings.get(0));
        assertEquals(MlsCredentialFloor.Standing.OK, r.standings.get(1));
    }

    /** An expired member still counts as expired, and separately from the band. */
    @Test
    public void expiryAndTheBandAreCountedApart() {
        final Map<Integer, long[]> v = roster(NOW - DAY, NOW + 10 * DAY, NOW + 90 * DAY);
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, names(), NOW, FLOOR);
        assertEquals(1, r.expired);
        assertEquals(1, r.insideFloor);
        assertEquals(MlsCredentialFloor.Standing.EXPIRED, r.standings.get(0));
        assertEquals(MlsCredentialFloor.Standing.INSIDE_FLOOR, r.standings.get(1));
        assertEquals(MlsCredentialFloor.Standing.OK, r.standings.get(2));
    }

    /**
     * A NOT-YET-VALID credential is reported as its own standing. Both it and EXPIRED mean "the
     * window does not cover now", but the remedy differs — a clock or an issuance problem, not an
     * ageing one — so collapsing them would send a reader after the wrong thing.
     */
    @Test
    public void aNotYetValidCredentialIsNamedAsSuch() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW + 10 * DAY, NOW + 100 * DAY });
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(v, names(), NOW, FLOOR);
        assertEquals(MlsCredentialFloor.Standing.NOT_YET_VALID, r.standings.get(0));
        assertEquals("it counts against us like an expiry does", 1, r.expired);
        assertTrue(r.membershipChangesWouldBeRefused());
    }

    /** An empty or null roster is a report of zeroes, never a null and never a refusal. */
    @Test
    public void anEmptyRosterIsNotARefusal() {
        final MlsCredentialFloor.Report r = MlsCredentialFloor.classify(null, null, NOW, FLOOR);
        assertEquals(0, r.total);
        assertFalse(r.membershipChangesWouldBeRefused());
    }

    /** {@code remainingDays} floors toward the past, so a part-expired credential reads negative. */
    @Test
    public void remainingDaysFloorsRatherThanTruncating() {
        assertEquals(25L, MlsCredentialFloor.remainingDays(NOW + 25 * DAY + 3600L, NOW));
        assertEquals(-1L, MlsCredentialFloor.remainingDays(NOW - 3600L, NOW));
    }

    /** An unknown {@code notAfter} is NOT inside the floor — the predicate gates refusals. */
    @Test
    public void anUnknownNotAfterDoesNotRefuse() {
        assertFalse(MlsCredentialFloor.insideFloor(0L, NOW, FLOOR));
        assertFalse(MlsCredentialFloor.insideFloor(-1L, NOW, FLOOR));
    }

    private static Map<Integer, String> names() {
        return new LinkedHashMap<>();
    }
}
