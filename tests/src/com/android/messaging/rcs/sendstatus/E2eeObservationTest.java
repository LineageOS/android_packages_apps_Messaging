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
import com.android.messaging.rcs.e2ee.E2eeSchemeGate;
import com.android.messaging.rcs.e2ee.EncryptionProtocolBits;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The padlock records what the provider reported applying, never a prediction. */
public class E2eeObservationTest {
    private static final String ETOUFFEE = RcsE2eeScheme.ETOUFFEE;
    private static final String MLS = RcsE2eeScheme.MLS;

    @Test
    public void providerSentWithEtouffee_stampsAndSets() {
        final Outcome o =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, false, null);
        assertTrue(o.rewriteRow);
        assertEquals(ETOUFFEE, o.rowScheme);
        assertSame(BitChange.SET, o.scytale);
        assertFalse(o.setMls);
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
    public void mlsStampedRow_isNeverTouched() {
        assertSame(Outcome.NOTHING,
                E2eeObservation.forSentStatus(Source.PROVIDER, true, null, false, MLS));
        assertSame(Outcome.NOTHING,
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, false, MLS));
    }

    @Test
    public void inbound_etouffee1to1_sets_mlsSetsMls_plaintextNothing() {
        assertSame(BitChange.SET, E2eeObservation.forInbound(ETOUFFEE, false).scytale);
        assertTrue(E2eeObservation.forInbound(MLS, true).setMls);
        assertSame(Outcome.NOTHING, E2eeObservation.forInbound(null, false));
    }

    @Test
    public void apply_setAndClearTouchOnlyScytale() {
        final EncryptionProtocolBits both = new EncryptionProtocolBits(true, true);
        final Outcome clear =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, null, false, null);
        assertEquals(new EncryptionProtocolBits(false, true), E2eeObservation.apply(both, clear));
        final Outcome set =
                E2eeObservation.forSentStatus(Source.PROVIDER, true, ETOUFFEE, false, null);
        assertEquals(new EncryptionProtocolBits(true, false),
                E2eeObservation.apply(EncryptionProtocolBits.NONE, set));
    }

    @Test
    public void bits_resolveAndColumnForm() {
        assertEquals(ETOUFFEE, new EncryptionProtocolBits(true, false).resolvedSchemeId());
        assertEquals(MLS, new EncryptionProtocolBits(true, true).resolvedSchemeId());
        assertNull(EncryptionProtocolBits.NONE.resolvedSchemeId());
        assertEquals(new EncryptionProtocolBits(true, false),
                new EncryptionProtocolBits(true, true).withMlsCleared());
        assertEquals(new EncryptionProtocolBits(false, true),
                new EncryptionProtocolBits(true, true).withScytaleCleared());
        for (int v = 0; v < 4; v++) {
            assertEquals(v, EncryptionProtocolBits.fromColumnValue(v).toColumnValue());
        }
    }

    @Test
    public void gate_neverLatchesScytale() {
        final MemStore store = new MemStore();
        final E2eeSchemeGate gate = new E2eeSchemeGate(null, store);
        assertNull(gate.selectScheme("c", 1, Collections.emptyList(), false));
        assertEquals(EncryptionProtocolBits.NONE, store.load("c"));
    }

    @Test
    public void gate_reportsAnObservedScytaleBit_andKeepsIt() {
        final MemStore store = new MemStore();
        store.store("c", new EncryptionProtocolBits(true, false));
        final E2eeSchemeGate gate = new E2eeSchemeGate(null, store);
        assertEquals(ETOUFFEE, gate.selectScheme("c", 1, Collections.emptyList(), false));
        assertEquals(new EncryptionProtocolBits(true, false), store.load("c"));
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

    private static final class MemStore implements E2eeSchemeGate.BitsStore {
        private final Map<String, EncryptionProtocolBits> mMap = new HashMap<>();

        @Override
        public EncryptionProtocolBits load(final String id) {
            final EncryptionProtocolBits b = mMap.get(id);
            return b == null ? EncryptionProtocolBits.NONE : b;
        }

        @Override
        public void store(final String id, final EncryptionProtocolBits bits) {
            mMap.put(id, bits);
        }
    }
}
