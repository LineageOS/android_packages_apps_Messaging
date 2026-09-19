/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link MlsGroupDeliveryLedger}: a group message's replay material is released only when every
 * current member has confirmed it.
 */
public class MlsGroupDeliveryLedgerTest {

    private static final String M = "mls-grp-abc-1";
    private static final List<String> ROSTER =
            Arrays.asList("+15715550107", "+15715550104", "+15715550103");

    /**
     * Releasing on one receipt would drop the replay material while another member could still
     * fail.
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
     * "Nobody left to hear from" and "could not read the roster" must not both release the bytes.
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

    /** A duplicate or replayed IMDN from one member does not advance coverage. */
    @Test
    public void aDuplicateReceiptFromOneMemberDoesNotAdvanceCoverage() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        assertTrue("first is new information", l.record(M, "+15715550107"));
        assertFalse("the replay is not", l.record(M, "+15715550107"));
        assertFalse(l.record(M, "+15715550107"));
        assertEquals(1, l.confirmedCount(M));
        assertFalse(l.isCovered(M, ROSTER));
    }

    /**
     * The roster and the IMDN sender need not agree on format; a literal compare would leave a
     * message one member short forever, and the release would silently fall back to the retention
     * window.
     */
    @Test
    public void membersMatchOnDigitsRegardlessOfPresentation() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        l.record(M, "+15715550107");             // E.164
        l.record(M, "15715550104");              // bare digits
        l.record(M, "tel:+1-571-555-0103");      // URI, punctuated
        assertTrue("all three are the same members as the roster", l.isCovered(M, ROSTER));
    }

    /**
     * Coverage is judged against the roster passed in, so a member removed after the send is not
     * awaited.
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

    /** A member added after the send does not confirm a message the old roster covered. */
    @Test
    public void coverageIsAlwaysRelativeToTheRosterGiven() {
        final MlsGroupDeliveryLedger l = new MlsGroupDeliveryLedger();
        for (final String m : ROSTER) l.record(M, m);
        assertTrue(l.isCovered(M, ROSTER));
        final List<String> bigger = Arrays.asList("+15715550107", "+15715550104",
                "+15715550103", "+15550001111");
        assertFalse("a newly added member has not confirmed", l.isCovered(M, bigger));
    }

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
     * An evicted entry can never reach coverage and waits the full retention window, so eviction is
     * oldest-first and reported.
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
