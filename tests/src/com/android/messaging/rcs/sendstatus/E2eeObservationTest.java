/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsEarlyStatusPark;
import com.android.messaging.rcs.e2ee.E2eeObservation;
import com.android.messaging.rcs.e2ee.E2eeObservation.BitChange;
import com.android.messaging.rcs.e2ee.E2eeObservation.Outcome;
import com.android.messaging.rcs.e2ee.E2eeObservation.Source;
import com.android.messaging.rcs.e2ee.EncryptionProtocolBits;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;

import org.junit.Test;

/** The padlock records what the provider reported applying, never a prediction. */
public class E2eeObservationTest {
    private static final String ETOUFFEE = RcsE2eeScheme.ETOUFFEE;

    @Test
    public void providerSentWithEtouffee_stampsAndSets() {
        final Outcome o =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, false, null);
        assertTrue(o.rewriteRow);
        assertEquals(ETOUFFEE, o.rowScheme);
        assertSame(BitChange.SET, o.scytale);
    }

    @Test
    public void providerSentPlaintext_on1to1_clearsStampAndBit() {
        final Outcome o =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, null, false, ETOUFFEE);
        assertTrue(o.rewriteRow);
        assertNull(o.rowScheme);
        assertSame(BitChange.CLEAR, o.scytale);
    }

    @Test
    public void providerSentPlaintext_unstampedRow_clearsBitOnly() {
        final Outcome o = E2eeObservation.forSentStatus(Source.PROVIDER, true, null, false, null);
        assertFalse(o.rewriteRow);
        assertSame(BitChange.CLEAR, o.scytale);
    }

    @Test
    public void carrierStatus_changesNothing() {
        for (final String scheme : new String[] {null, ETOUFFEE}) {
            for (final String row : new String[] {null, ETOUFFEE}) {
                assertSame(Outcome.NOTHING,
                        E2eeObservation.forSentStatus(Source.CARRIER, true, scheme, false, row));
            }
        }
    }

    @Test
    public void group_neverChangesTheBit() {
        assertSame(BitChange.NONE,
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, true, null).scytale);
        assertSame(BitChange.NONE,
                E2eeObservation.forSentStatus(Source.PROVIDER, true, null, true, ETOUFFEE).scytale);
        assertSame(BitChange.NONE, E2eeObservation.forInbound(ETOUFFEE, true).scytale);
    }

    @Test
    public void notSent_changesNothing() {
        assertSame(Outcome.NOTHING,
                E2eeObservation.forSentStatus(Source.PROVIDER, false, ETOUFFEE, false, null));
    }

    @Test
    public void inbound_etouffee1to1_sets_plaintextNothing() {
        assertSame(BitChange.SET, E2eeObservation.forInbound(ETOUFFEE, false).scytale);
        assertSame(Outcome.NOTHING, E2eeObservation.forInbound(null, false));
    }

    @Test
    public void apply_setAndClear() {
        final Outcome clear =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, null, false, null);
        assertEquals(EncryptionProtocolBits.NONE,
                E2eeObservation.apply(new EncryptionProtocolBits(true), clear));
        final Outcome set =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, false, null);
        assertEquals(new EncryptionProtocolBits(true),
                E2eeObservation.apply(EncryptionProtocolBits.NONE, set));
    }

    @Test
    public void bits_clearAndColumnForm() {
        assertEquals(EncryptionProtocolBits.NONE,
                new EncryptionProtocolBits(true).withScytaleCleared());
        assertEquals(1, EncryptionProtocolBits.fromColumnValue(1).toColumnValue());
        assertEquals(0, EncryptionProtocolBits.fromColumnValue(0).toColumnValue());
    }

    @Test
    public void park_takeOnce_keepsSentScheme_andExpires() {
        final RcsEarlyStatusPark park = new RcsEarlyStatusPark();
        park.park("a", 1, true, ETOUFFEE, "PROVIDER", 0L);
        park.park("a", 2, false, null, "PROVIDER", 1L);
        final RcsEarlyStatusPark.Entry e = park.take("a", 2L);
        assertEquals(2, e.status);
        assertTrue(e.sent);
        assertEquals(ETOUFFEE, e.scheme);
        assertNull(park.take("a", 3L));
        park.park("b", 1, true, null, "PROVIDER", 0L);
        assertNull(park.take("b", RcsEarlyStatusPark.MAX_AGE_MS + 1));
    }

    @Test
    public void park_isBounded() {
        final RcsEarlyStatusPark park = new RcsEarlyStatusPark();
        for (int i = 0; i <= RcsEarlyStatusPark.MAX_ENTRIES; i++) {
            park.park("id" + i, 1, true, null, "PROVIDER", 0L);
        }
        assertEquals(RcsEarlyStatusPark.MAX_ENTRIES, park.size());
        assertNull(park.take("id0", 0L));
    }
}
