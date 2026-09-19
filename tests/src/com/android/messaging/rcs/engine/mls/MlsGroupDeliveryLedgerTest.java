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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Per-member group delivery coverage. */
public class MlsGroupDeliveryLedgerTest {

    private static final String M = "mls-grp-abc-1";
    private static final List<String> ROSTER =
            Arrays.asList("+15715550107", "+15715550104", "+15715550103");

    // ==================== THE ONE THAT MATTERS ====================

    /**
     * <b>Coverage needs EVERY member, not any member.</b> This is the whole rule: releasing on one
     * receipt is what threw the replay material away while another member could still report a
     * failure.
     */
    @Test
    public void coverageRequiresEveryMember() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        l.record(M, "+15715550107");
        assertFalse("one member is not coverage", l.isCovered(M, ROSTER));
        l.record(M, "+15715550104");
        assertFalse("two of three is not coverage", l.isCovered(M, ROSTER));
        l.record(M, "+15715550103");
        assertTrue("all three IS coverage", l.isCovered(M, ROSTER));
    }

    /**
     * <b>An unreadable roster is NOT coverage.</b> "Nobody left to hear from" and "we could not read
     * the roster" are indistinguishable from inside the ledger, and treating the second as the first
     * releases the bytes of a message nobody confirmed.
     */
    @Test
    public void anEmptyOrNullRosterIsNeverCovered() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        l.record(M, "+15715550107");
        assertFalse(l.isCovered(M, Collections.<String>emptyList()));
        assertFalse(l.isCovered(M, null));
    }

    /** A message nobody has confirmed is not covered, whatever the roster says. */
    @Test
    public void anUnknownMessageIsNotCovered() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        assertFalse(l.isCovered("never-seen", ROSTER));
        assertEquals(0, l.confirmedCount("never-seen"));
    }

    // ==================== idempotence ====================

    /**
     * A duplicate or replayed IMDN from one member must not advance coverage — the same property
     * needed when it moved from counting KeyPackages to crossing them off by ref.
     */
    @Test
    public void aDuplicateReceiptFromOneMemberDoesNotAdvanceCoverage() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        assertTrue("first is new information", l.record(M, "+15715550107"));
        assertFalse("the replay is not", l.record(M, "+15715550107"));
        assertFalse(l.record(M, "+15715550107"));
        assertEquals(1, l.confirmedCount(M));
        assertFalse(l.isCovered(M, ROSTER));
    }

    // ==================== presentation ====================

    /**
     * THE ROSTER AND THE IMDN SENDER NEED NOT AGREE ON FORMAT, and a literal compare would leave a
     * message permanently one member short — silently, because the release simply never fires and
     * the 24h path takes over, which looks identical to this class not being wired up.
     */
    @Test
    public void membersMatchOnDigitsRegardlessOfPresentation() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        l.record(M, "+15715550107");             // E.164
        l.record(M, "15715550104");              // bare digits
        l.record(M, "tel:+1-571-555-0103");      // URI, punctuated
        assertTrue("all three are the same members as the roster", l.isCovered(M, ROSTER));
    }

    // ==================== roster changes under us ====================

    /**
     * Coverage is judged against the roster passed in, so a member REMOVED after the send stops
     * being waited on — the ledger holds no roster of its own precisely so this works.
     */
    @Test
    public void aRemovedMemberIsNoLongerWaitedOn() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        l.record(M, "+15715550107");
        l.record(M, "+15715550104");
        assertFalse(l.isCovered(M, ROSTER));
        final List<String> smaller = Arrays.asList("+15715550107", "+15715550104");
        assertTrue("the third member left; the message is covered", l.isCovered(M, smaller));
    }

    /** …and a member ADDED after the send does not un-cover a message the old roster covered. */
    @Test
    public void coverageIsAlwaysRelativeToTheRosterGiven() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        for (final String m : ROSTER) l.record(M, m);
        assertTrue(l.isCovered(M, ROSTER));
        final List<String> bigger = Arrays.asList("+15715550107", "+15715550104",
                "+15715550103", "+15550001111");
        assertFalse("a newly added member has not confirmed", l.isCovered(M, bigger));
    }

    // ==================== bookkeeping ====================

    @Test
    public void forgetStopsTracking() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        for (final String m : ROSTER) l.record(M, m);
        assertTrue(l.isCovered(M, ROSTER));
        l.forget(M);
        assertEquals(0, l.size());
        assertFalse(l.isCovered(M, ROSTER));
    }

    @Test
    public void blankInputsAreIgnoredRatherThanTracked() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        assertFalse(l.record(null, "+1555"));
        assertFalse(l.record(M, null));
        assertFalse(l.record("", ""));
        assertEquals(0, l.size());
    }

    /**
     * THE CAP EVICTS OLDEST-FIRST AND REPORTS IT. An entry dropped here can never reach coverage
     * afterwards, so its message silently reverts to waiting the full retention window — the exact
     * behaviour this class exists to remove, and invisible without the report.
     */
    @Test
    public void theCapEvictsOldestFirstAndNamesWhatItDropped() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        for (int i = 0; i < MlsGroupDeliveryLedger.MAX_MESSAGES; i++) {
            l.record("m" + i, "+15715550107");
        }
        assertEquals(MlsGroupDeliveryLedger.MAX_MESSAGES, l.size());
        assertTrue("nothing evicted yet", l.takeEvicted().isEmpty());

        l.record("one-more", "+15715550107");
        assertEquals("stays at the cap", MlsGroupDeliveryLedger.MAX_MESSAGES, l.size());
        final List<String> evicted = l.takeEvicted();
        assertEquals(1, evicted.size());
        assertEquals("the OLDEST goes", "m0", evicted.get(0));
        assertEquals(0, l.confirmedCount("m0"));
        assertEquals(1, l.confirmedCount("one-more"));
        assertTrue("takeEvicted clears", l.takeEvicted().isEmpty());
    }
}
