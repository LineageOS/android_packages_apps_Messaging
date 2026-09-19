/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Outbound positive and display receipts. The assertions pin byte shapes because peers use the
 * header set as an admission gate: a receipt with the wrong headers is discarded before validation,
 * which the sender never sees.
 */
public class MlsReceiptMetadataTest {

    private static List<MlsReceiptMetadata.Header> hdrs(final String sig, final long era,
            final String mid, final String orig) {
        return MlsReceiptMetadata.headers(sig, era, orig);
    }

    /**
     * A receipt never carries Original-Message-ID: peers send exactly two headers (signature, era)
     * and reject a receipt that carries the third. {@code headers()} does not consult the two
     * original-id helpers, which keep their own rules.
     */
    @Test
    public void aReceiptNeverCarriesOriginalMessageId() {
        assertEquals("a receipt is signature + era, nothing else",
                2, hdrs("SIG", 1, "m1", "m0").size());
        assertEquals(2, hdrs("SIG", 1, "m1", "same").size());
        // The helpers keep their own rules.
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("same", "same"));
        assertTrue(MlsReceiptMetadata.includeOriginalMessageIdHeader("same"));
    }

    /** An empty or null original id also yields two headers. */
    @Test
    public void originalMessageIdIsOmittedWhenEmptyOrNull() {
        assertEquals(2, hdrs("SIG", 1, "m1", null).size());
        assertEquals(2, hdrs("SIG", 1, "m1", "").size());
    }

    /** The two omit rules differ on exactly one input. */
    @Test
    public void theTwoOmitRulesDifferOnEqualIds() {
        // Proto: present and different.
        assertTrue(MlsReceiptMetadata.includeOriginalMessageId("m1", "m0"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", "m1"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", null));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", ""));
        // Header: non-empty only.
        assertTrue(MlsReceiptMetadata.includeOriginalMessageIdHeader("m0"));
        assertTrue("THE difference", MlsReceiptMetadata.includeOriginalMessageIdHeader("m1"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageIdHeader(null));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageIdHeader(""));
    }

    /**
     * No signature means no headers at all: the era alone would look MLS-tagged and still be
     * refused, which is harder to diagnose than an untagged report.
     */
    @Test
    public void noSignatureYieldsNoHeaders() {
        assertTrue(hdrs(null, 1, "m1", "m0").isEmpty());
        assertTrue(hdrs("", 1, "m1", "m0").isEmpty());
    }

    @Test
    public void mergeKeepsExistingCustomHeaders() {
        final List<MlsReceiptMetadata.Header> existing = new ArrayList<>();
        existing.add(new MlsReceiptMetadata.Header("urn:x", "X-Custom", "keep"));
        final List<MlsReceiptMetadata.Header> merged =
                MlsReceiptMetadata.merge(existing, hdrs("SIG", 2, "m1", null));
        assertEquals(3, merged.size());
        assertEquals("X-Custom", merged.get(0).name);
        assertEquals("keep", merged.get(0).value);
        assertEquals(MlsReceiptMetadata.HDR_SIGNATURE, merged.get(1).name);
    }

    /** A generated header replaces an existing one of the same name rather than duplicating it. */
    @Test
    public void mergeReplacesRatherThanDuplicating() {
        final List<MlsReceiptMetadata.Header> existing = new ArrayList<>();
        existing.add(new MlsReceiptMetadata.Header(
                MlsReceiptMetadata.NS, MlsReceiptMetadata.HDR_ERA_ID, "STALE"));
        final List<MlsReceiptMetadata.Header> merged =
                MlsReceiptMetadata.merge(existing, hdrs("SIG", 9, "m1", null));
        assertEquals(2, merged.size());
        for (final MlsReceiptMetadata.Header h : merged) {
            if (MlsReceiptMetadata.HDR_ERA_ID.equals(h.name)) {
                assertEquals("9", h.value);
            }
        }
    }

    @Test
    public void mergeToleratesNulls() {
        assertEquals(2, MlsReceiptMetadata.merge(null, hdrs("SIG", 1, "m", null)).size());
        assertTrue(MlsReceiptMetadata.merge(null, null).isEmpty());
    }

    @Test
    public void theOrdinalsMatchTheReferenceClient() {
        assertEquals(0, MlsReceiptMetadata.ReceiptType.UNKNOWN_RECEIPT_TYPE.ordinalValue);
        assertEquals(1, MlsReceiptMetadata.ReceiptType.DELIVERY.ordinalValue);
        assertEquals(2, MlsReceiptMetadata.ReceiptType.DISPLAYED.ordinalValue);
        assertEquals(3, MlsReceiptMetadata.ReceiptType.DELIVERY_FAILED.ordinalValue);
    }

    /**
     * {@code DELIVERY_FAILED} shares {@code DELIVERY}'s handler; only DISPLAYED uses the display
     * verb.
     */
    @Test
    public void deliveryFailedUsesTheDeliveryVerbNotItsOwn() {
        assertFalse(MlsReceiptMetadata.ReceiptType.DELIVERY.usesDisplayVerb());
        assertFalse("ordinal 3 routes to the DELIVERY handler",
                MlsReceiptMetadata.ReceiptType.DELIVERY_FAILED.usesDisplayVerb());
        assertTrue(MlsReceiptMetadata.ReceiptType.DISPLAYED.usesDisplayVerb());
    }

    @Test
    public void theZeroOrdinalThrowsWithTheReferenceClientsText() {
        try {
            MlsReceiptMetadata.requireSignableReceiptType(
                    MlsReceiptMetadata.ReceiptType.UNKNOWN_RECEIPT_TYPE);
            fail("expected a throw");
        } catch (final IllegalArgumentException e) {
            assertEquals("Unsupported receipt type: UNKNOWN_RECEIPT_TYPE", e.getMessage());
        }
        // The other three do not throw.
        MlsReceiptMetadata.requireSignableReceiptType(MlsReceiptMetadata.ReceiptType.DELIVERY);
        MlsReceiptMetadata.requireSignableReceiptType(MlsReceiptMetadata.ReceiptType.DISPLAYED);
        MlsReceiptMetadata.requireSignableReceiptType(
                MlsReceiptMetadata.ReceiptType.DELIVERY_FAILED);
    }

    @Test
    public void fromOrdinalRoundTripsAndDefaultsToUnknown() {
        for (final MlsReceiptMetadata.ReceiptType t : MlsReceiptMetadata.ReceiptType.values()) {
            assertEquals(t, MlsReceiptMetadata.ReceiptType.fromOrdinal(t.ordinalValue));
        }
        assertEquals(MlsReceiptMetadata.ReceiptType.UNKNOWN_RECEIPT_TYPE,
                MlsReceiptMetadata.ReceiptType.fromOrdinal(99));
    }

    /** DeliveryReceiptMetadata: 1 version, 2 status, 3 message_id, 6 original_message_id. */
    @Test
    public void deliveryMetadataEncodesTheProvenFieldNumbers() {
        final byte[] got = MlsReceiptMetadata.deliveryReceiptMetadata(
                MlsReceiptMetadata.STATUS_DELIVERED, "m1", "m0");
        final byte[] want = new byte[] {
                0x08, 0x01,                       // field 1 varint = 1 (version)
                0x10, 0x01,                       // field 2 varint = 1 (delivered)
                0x1a, 0x02, 'm', '1',             // field 3 bytes  = "m1"
                0x32, 0x02, 'm', '0',             // field 6 bytes  = "m0"
        };
        assertArrayEquals(want, got);
    }

    /**
     * {@code original_message_id} is field 6: fields 4 and 5 are a failure-reason oneof the
     * outbound path never populates.
     */
    @Test
    public void deliveryMetadataPutsOriginalIdAtSixNotFour() {
        final byte[] got = MlsReceiptMetadata.deliveryReceiptMetadata(1, "m1", "m0");
        // 0x32 = (6 << 3) | 2. 0x22 would be (4 << 3) | 2.
        assertTrue("field 6", contains(got, new byte[] { 0x32, 0x02, 'm', '0' }));
        assertFalse("NOT field 4", contains(got, new byte[] { 0x22, 0x02, 'm', '0' }));
    }

    /** The display metadata puts it at field 4; the two protos differ in shape. */
    @Test
    public void displayMetadataPutsOriginalIdAtFourNotSix() {
        final byte[] got = MlsReceiptMetadata.displayReceiptMetadata("m1", "m0");
        final byte[] want = new byte[] {
                0x08, 0x01,                       // version
                0x10, 0x01,                       // status = 1
                0x1a, 0x02, 'm', '1',             // message_id
                0x22, 0x02, 'm', '0',             // field 4 bytes = "m0"
        };
        assertArrayEquals(want, got);
        assertFalse("NOT field 6", contains(got, new byte[] { 0x32, 0x02, 'm', '0' }));
    }

    @Test
    public void bothMetadataProtosOmitTheOriginalIdWhenEqual() {
        assertArrayEquals(new byte[] { 0x08, 0x01, 0x10, 0x01, 0x1a, 0x02, 'm', '1' },
                MlsReceiptMetadata.deliveryReceiptMetadata(1, "m1", "m1"));
        assertArrayEquals(new byte[] { 0x08, 0x01, 0x10, 0x01, 0x1a, 0x02, 'm', '1' },
                MlsReceiptMetadata.displayReceiptMetadata("m1", "m1"));
    }

    /** The failed status is 2, and only the delivery metadata carries it. */
    @Test
    public void theFailedStatusIsTwo() {
        assertEquals(2, MlsReceiptMetadata.STATUS_FAILED);
        final byte[] got = MlsReceiptMetadata.deliveryReceiptMetadata(
                MlsReceiptMetadata.STATUS_FAILED, "m1", null);
        assertTrue(contains(got, new byte[] { 0x10, 0x02 }));
        // The display metadata has no failure arm; it always writes status 1.
        assertTrue(contains(MlsReceiptMetadata.displayReceiptMetadata("m1", null),
                new byte[] { 0x10, 0x01 }));
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
