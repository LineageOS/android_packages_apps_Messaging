/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A resend is a new row with a new id, and its count is derived from the chain. These cover the
 * arithmetic that drives the escalation ladder; the SQL half is {@code MlsResendLedger}.
 */
public class MlsResendRecordTest {

    private static MlsResendRecord at(final int count) {
        return new MlsResendRecord("id-" + count, "root", "parent", "+15550001", "", count, 0L);
    }

    @Test public void theFirstResendIsOne() {
        assertEquals(1, MlsResendRecord.nextFtdResendCount(null));
        assertEquals(1, MlsResendRecord.nextFtdResendCount(new ArrayList<MlsResendRecord>()));
    }

    @Test public void theCountClimbsWithEachSibling() {
        final List<MlsResendRecord> chain = new ArrayList<>();
        for (int expected = 1; expected <= 5; expected++) {
            assertEquals(expected, MlsResendRecord.nextFtdResendCount(chain));
            chain.add(at(expected));
        }
    }

    @Test public void itIsMaxPlusOneNotSizePlusOne() {
        // Once a row goes missing, size+1 would dip and send a conversation that failed five times
        // back to the bottom rung.
        final List<MlsResendRecord> withAGap = Arrays.asList(at(1), at(4));
        assertEquals(2, withAGap.size());
        assertEquals(5, MlsResendRecord.nextFtdResendCount(withAGap));
    }

    @Test public void aNullSiblingDoesNotStopTheCount() {
        // A cursor that yielded a bad row does not reset the ladder.
        final List<MlsResendRecord> withNull = Arrays.asList(at(3), null, at(2));
        assertEquals(4, MlsResendRecord.nextFtdResendCount(withNull));
    }

    @Test public void everyFieldSurvivesConstruction() {
        final MlsResendRecord r = new MlsResendRecord("new-id", "root-id", "replaced-id",
                "+15550001", "client-7", 2, 1234L);
        assertEquals("new-id", r.rcsMessageId);
        assertEquals("root-id", r.originalRcsMessageId);
        assertEquals("replaced-id", r.manualResendOfRcsMessage);
        assertEquals("+15550001", r.resendRecipientAddress);
        assertEquals("client-7", r.resendRecipientClientId);
        assertEquals(2, r.ftdResendCount);
        assertEquals(1234L, r.resendTimestampMs);
    }

    @Test public void nullsBecomeEmptyNeverNpe() {
        // Values come off a cursor, where any TEXT column can be NULL.
        final MlsResendRecord r = new MlsResendRecord(null, null, null, null, null, -5, 0L);
        assertEquals("", r.rcsMessageId);
        assertEquals("", r.originalRcsMessageId);
        assertEquals("", r.manualResendOfRcsMessage);
        assertEquals("", r.resendRecipientAddress);
        assertEquals("", r.resendRecipientClientId);
        // A negative count is clamped: it would make max+1 go backwards.
        assertEquals(0, r.ftdResendCount);
    }

    @Test public void targetingMatchesOnAddress() {
        final MlsResendRecord r = at(1);
        assertTrue(r.targets("+15550001", null));
        assertTrue(r.targets("+15550001", ""));
        assertFalse(r.targets("+15550002", null));
        assertFalse(r.targets(null, null));
    }

    /** A chain: root "root", then two resends of it. */
    private static List<MlsResendRecord> chainOfTwo() {
        return Arrays.asList(
                new MlsResendRecord("resend-1", "root", "root", "+15550001", "", 1, 0L),
                new MlsResendRecord("resend-2", "root", "resend-1", "+15550001", "", 2, 0L));
    }

    /** A delivery releases the whole chain: no receipt will quote the other attempts' ids again. */
    @Test public void aDeliveryReleasesTheWholeChainWhicheverRungItNames() {
        final Set<String> onResend = MlsResendRecord.materialToRelease(
                "resend-2", "root", chainOfTwo(), /*delivered=*/ true);
        assertTrue(onResend.contains("root"));
        assertTrue(onResend.contains("resend-1"));
        assertTrue(onResend.contains("resend-2"));
        assertEquals(3, onResend.size());

        // Same set when the receipt names the root.
        assertEquals(onResend, MlsResendRecord.materialToRelease(
                "root", "root", chainOfTwo(), /*delivered=*/ true));
    }

    /**
     * A permanent failure releases only the failed attempt: the root's body is the only one any
     * rung can use, and RCC.16 §10.3 still needs it.
     */
    @Test public void aFailedResendReleasesOnlyItself() {
        final Set<String> released = MlsResendRecord.materialToRelease(
                "resend-2", "root", chainOfTwo(), /*delivered=*/ false);
        assertEquals(1, released.size());
        assertTrue(released.contains("resend-2"));
        assertFalse("the root holds the only body a resend can replay", released.contains("root"));
        assertFalse(released.contains("resend-1"));
    }

    /** A failure naming the root of a live chain is contradicted by it and releases nothing. */
    @Test public void aFailureNamingTheRootOfALiveChainKeepsEverything() {
        assertTrue(MlsResendRecord.materialToRelease(
                "root", "root", chainOfTwo(), /*delivered=*/ false).isEmpty());
    }

    /** An ordinary failed send with no chain is released, or it would pin a sealed-store slot. */
    @Test public void aFailedSendWithNoChainIsStillReleased() {
        final Set<String> released = MlsResendRecord.materialToRelease(
                "plain-send", "plain-send", null, /*delivered=*/ false);
        assertEquals(1, released.size());
        assertTrue(released.contains("plain-send"));

        assertEquals(released, MlsResendRecord.materialToRelease("plain-send", "plain-send",
                new ArrayList<MlsResendRecord>(), /*delivered=*/ false));
    }

    /** A ledger read that returned nothing does not turn a terminal into a no-op. */
    @Test public void anEmptyLedgerStillReleasesTheIdWeWereHanded() {
        for (final boolean delivered : new boolean[] { true, false }) {
            final Set<String> released = MlsResendRecord.materialToRelease(
                    "some-id", "some-id", null, delivered);
            assertEquals("delivered=" + delivered, 1, released.size());
            assertTrue(released.contains("some-id"));
        }
    }

    /** Degenerate inputs yield an empty set; a terminal never throws on the callback thread. */
    @Test public void degenerateInputsAreEmptyNotAnNpe() {
        assertTrue(MlsResendRecord.materialToRelease(null, "root", chainOfTwo(), true).isEmpty());
        assertTrue(MlsResendRecord.materialToRelease("", "root", chainOfTwo(), false).isEmpty());
        // A null root (a cursor can yield one) degrades to the id.
        final Set<String> released =
                MlsResendRecord.materialToRelease("id", null, null, /*delivered=*/ true);
        assertEquals(1, released.size());
        assertTrue(released.contains("id"));
        // A sibling row with no id does not become an empty-string key.
        final Set<String> withBadRow = MlsResendRecord.materialToRelease("root", "root",
                Arrays.asList((MlsResendRecord) null,
                        new MlsResendRecord(null, "root", "root", "+1", "", 1, 0L)),
                /*delivered=*/ true);
        assertEquals(1, withBadRow.size());
        assertTrue(withBadRow.contains("root"));
        // The same unusable rows do not count as a live chain on the failure path.
        assertEquals(withBadRow, MlsResendRecord.materialToRelease("root", "root",
                Arrays.asList((MlsResendRecord) null,
                        new MlsResendRecord(null, "root", "root", "+1", "", 1, 0L)),
                /*delivered=*/ false));
    }

    @Test public void anUnknownClientIdStillAccumulates() {
        // The record has no client id; an exact match would start a fresh chain on every attempt.
        final MlsResendRecord noClient = at(1);
        assertTrue(noClient.targets("+15550001", "client-9"));

        // When both sides name a client, a different client is a different target.
        final MlsResendRecord withClient = new MlsResendRecord("id", "root", "parent",
                "+15550001", "client-7", 1, 0L);
        assertTrue(withClient.targets("+15550001", "client-7"));
        assertFalse(withClient.targets("+15550001", "client-9"));
        assertTrue(withClient.targets("+15550001", null));
    }
}
