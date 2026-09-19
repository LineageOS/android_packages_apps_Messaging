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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Rework item 7.4 — a resend is a NEW row with a NEW id, and its count is DERIVED (§11.1,
 * invariant 63).
 *
 * <p>These cover the arithmetic only. The SQL half lives in {@code MlsResendLedger}, which needs a
 * device; what can go wrong up here is the counter, and the counter is what drives the escalation
 * ladder.
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
        // The two agree until a row goes missing, and then they disagree in the direction that
        // matters: size+1 would DIP, handing a conversation that has failed five times back to the
        // bottom rung of an escalation ladder it had nearly climbed.
        final List<MlsResendRecord> withAGap = Arrays.asList(at(1), at(4));
        assertEquals(2, withAGap.size());
        assertEquals(5, MlsResendRecord.nextFtdResendCount(withAGap));
    }

    @Test public void aNullSiblingDoesNotStopTheCount() {
        // A cursor that yielded a bad row must not silently reset the ladder.
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
        // These come off a cursor, where any TEXT column can be NULL.
        final MlsResendRecord r = new MlsResendRecord(null, null, null, null, null, -5, 0L);
        assertEquals("", r.rcsMessageId);
        assertEquals("", r.originalRcsMessageId);
        assertEquals("", r.manualResendOfRcsMessage);
        assertEquals("", r.resendRecipientAddress);
        assertEquals("", r.resendRecipientClientId);
        // A negative count is clamped rather than propagated: it would make max+1 go BACKWARDS.
        assertEquals(0, r.ftdResendCount);
    }

    @Test public void targetingMatchesOnAddress() {
        final MlsResendRecord r = at(1);
        assertTrue(r.targets("+15550001", null));
        assertTrue(r.targets("+15550001", ""));
        assertFalse(r.targets("+15550002", null));
        assertFalse(r.targets(null, null));
    }

    // ---- what a TERMINAL may release -------------------------------------------------------

    /** A chain: root "root", then two resends of it. */
    private static List<MlsResendRecord> chainOfTwo() {
        return Arrays.asList(
                new MlsResendRecord("resend-1", "root", "root", "+15550001", "", 1, 0L),
                new MlsResendRecord("resend-2", "root", "resend-1", "+15550001", "", 2, 0L));
    }

    /**
     * A DELIVERY releases the whole chain.
     *
     * <p>The receipt names whichever attempt got through; the bytes are held under the attempts
     * that did not, and no receipt will ever quote those ids again.
     */
    @Test public void aDeliveryReleasesTheWholeChainWhicheverRungItNames() {
        final Set<String> onResend = MlsResendRecord.materialToRelease(
                "resend-2", "root", chainOfTwo(), /*delivered=*/ true);
        assertTrue(onResend.contains("root"));
        assertTrue(onResend.contains("resend-1"));
        assertTrue(onResend.contains("resend-2"));
        assertEquals(3, onResend.size());

        // Same set when the receipt names the root instead.
        assertEquals(onResend, MlsResendRecord.materialToRelease(
                "root", "root", chainOfTwo(), /*delivered=*/ true));
    }

    /**
     * THE BUG: a permanent FAILURE used to release the whole chain too.
     *
     * <p>A failure means the message did not arrive, so the root's body is exactly what §10.3 still
     * needs — and it is the ONLY body any rung can use, because {@code resendOriginal} resolves
     * root-ward before looking one up. Releasing it because a later rung's transport send failed
     * loses a recoverable message.
     */
    @Test public void aFailedResendReleasesOnlyItself() {
        final Set<String> released = MlsResendRecord.materialToRelease(
                "resend-2", "root", chainOfTwo(), /*delivered=*/ false);
        assertEquals(1, released.size());
        assertTrue(released.contains("resend-2"));
        assertFalse("the root holds the only body a resend can replay", released.contains("root"));
        assertFalse(released.contains("resend-1"));
    }

    /**
     * A failure naming the ROOT of a chain that already has resends releases NOTHING.
     *
     * <p>A resend row exists only because a peer received the root and reported it, so "the
     * provider permanently failed to send it" and "a peer is asking us to resend it" cannot both be
     * true. Between a contradicted failure report and the only bytes that can still repair the
     * message, the bytes win; the retention sweep still bounds them.
     */
    @Test public void aFailureNamingTheRootOfALiveChainKeepsEverything() {
        assertTrue(MlsResendRecord.materialToRelease(
                "root", "root", chainOfTwo(), /*delivered=*/ false).isEmpty());
    }

    /**
     * …but an ordinary send that failed with no chain behind it IS released.
     *
     * <p>This is the case the release exists for, and the fix must not take it away: a permanently
     * failed message with nothing to recover it would otherwise pin one of the store's slots, and at
     * the cap the store stops storing and retries resume burning generations (invariant 62).
     */
    @Test public void aFailedSendWithNoChainIsStillReleased() {
        final Set<String> released = MlsResendRecord.materialToRelease(
                "plain-send", "plain-send", null, /*delivered=*/ false);
        assertEquals(1, released.size());
        assertTrue(released.contains("plain-send"));

        assertEquals(released, MlsResendRecord.materialToRelease("plain-send", "plain-send",
                new ArrayList<MlsResendRecord>(), /*delivered=*/ false));
    }

    /** A ledger read that returned nothing must not turn a terminal into a no-op. */
    @Test public void anEmptyLedgerStillReleasesTheIdWeWereHanded() {
        for (final boolean delivered : new boolean[] { true, false }) {
            final Set<String> released = MlsResendRecord.materialToRelease(
                    "some-id", "some-id", null, delivered);
            assertEquals("delivered=" + delivered, 1, released.size());
            assertTrue(released.contains("some-id"));
        }
    }

    /** Null and empty inputs are survivable: a terminal must never throw on the callback thread. */
    @Test public void degenerateInputsAreEmptyNotAnNpe() {
        assertTrue(MlsResendRecord.materialToRelease(null, "root", chainOfTwo(), true).isEmpty());
        assertTrue(MlsResendRecord.materialToRelease("", "root", chainOfTwo(), false).isEmpty());
        // A null root (never produced by rootOf, but a cursor can yield one) degrades to the id.
        final Set<String> released =
                MlsResendRecord.materialToRelease("id", null, null, /*delivered=*/ true);
        assertEquals(1, released.size());
        assertTrue(released.contains("id"));
        // A sibling row with no id must not become an empty-string key in the release set.
        final Set<String> withBadRow = MlsResendRecord.materialToRelease("root", "root",
                Arrays.asList((MlsResendRecord) null,
                        new MlsResendRecord(null, "root", "root", "+1", "", 1, 0L)),
                /*delivered=*/ true);
        assertEquals(1, withBadRow.size());
        assertTrue(withBadRow.contains("root"));
        // …and the same unusable rows do not count as a "live chain" on the failure path either,
        // which would silently stop releasing anything at all.
        assertEquals(withBadRow, MlsResendRecord.materialToRelease("root", "root",
                Arrays.asList((MlsResendRecord) null,
                        new MlsResendRecord(null, "root", "root", "+1", "", 1, 0L)),
                /*delivered=*/ false));
    }

    @Test public void anUnknownClientIdStillAccumulates() {
        // The record has no client id. Requiring an exact match would start a fresh chain on every
        // attempt and pin the ladder at rung one forever, which is the failure this guards.
        final MlsResendRecord noClient = at(1);
        assertTrue(noClient.targets("+15550001", "client-9"));

        // But when BOTH sides name a client, a different client is a different target.
        final MlsResendRecord withClient = new MlsResendRecord("id", "root", "parent",
                "+15550001", "client-7", 1, 0L);
        assertTrue(withClient.targets("+15550001", "client-7"));
        assertFalse(withClient.targets("+15550001", "client-9"));
        assertTrue(withClient.targets("+15550001", null));
    }
}
