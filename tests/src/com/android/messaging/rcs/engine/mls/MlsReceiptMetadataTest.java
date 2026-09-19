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
 * Outbound positive/display receipts — §12.11, rework {@code 10.5}.
 *
 * <p>Every assertion here is about a byte shape, because §12.7 makes the header set a <b>hard
 * admission gate</b>: a receipt with the wrong headers is not degraded by a Google Messages peer, it is
 * discarded before validation. That failure is invisible from our side — the sender simply never
 * sees a receipt — so it cannot be found by testing against ourselves, only by pinning the shape.
 */
public class MlsReceiptMetadataTest {

    private static List<MlsReceiptMetadata.Header> hdrs(final String sig, final long era,
            final String mid, final String orig) {
        return MlsReceiptMetadata.headers(sig, era, orig);
    }

    // ---- the header set ------------------------------------------------------------------------

    /** <b>The order is signature, era, original-id.</b> */
    /**
     * A RECEIPT NEVER CARRIES Original-Message-ID — wire-proven 2026-08-08, and this test used to
     * assert the opposite.
     *
     * <p>Google Messages' own captured receipt carries exactly two headers (signature, era). And the
     * A Google Messages-to-Google Messages oracle shows a receipt WITHOUT the header rated
     * {@code NO_OP -> SUCCESS}, while ours WITH it was rated {@code FAILED_MESSAGE -> FAIL_NO_RETRY}
     * on the same path. A positive control plus a negative — which is why the wire overrides the
     * §12.11 header reading here.
     *
     * <p>The two HELPERS still differ as documented; what changed is that a receipt is not a message
     * class that takes the header at all, so {@code headers()} no longer consults them.
     */
    @Test
    public void aReceiptNeverCarriesOriginalMessageId() {
        assertEquals("a receipt is signature + era, nothing else",
                2, hdrs("SIG", 1, "m1", "m0").size());
        assertEquals(2, hdrs("SIG", 1, "m1", "same").size());
        // The helpers are unchanged and still describe their own rules.
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("same", "same"));
        assertTrue(MlsReceiptMetadata.includeOriginalMessageIdHeader("same"));
    }

    /** ...and when it is absent. */
    @Test
    public void originalMessageIdIsOmittedWhenEmptyOrNull() {
        assertEquals(2, hdrs("SIG", 1, "m1", null).size());
        assertEquals(2, hdrs("SIG", 1, "m1", "").size());
    }

    /** The two omit rules, side by side — they differ on exactly one input. */
    @Test
    public void theTwoOmitRulesDifferOnEqualIds() {
        // PROTO: present AND different.
        assertTrue(MlsReceiptMetadata.includeOriginalMessageId("m1", "m0"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", "m1"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", null));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageId("m1", ""));
        // HEADER: non-empty only.
        assertTrue(MlsReceiptMetadata.includeOriginalMessageIdHeader("m0"));
        assertTrue("THE difference", MlsReceiptMetadata.includeOriginalMessageIdHeader("m1"));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageIdHeader(null));
        assertFalse(MlsReceiptMetadata.includeOriginalMessageIdHeader(""));
    }

    /**
     * No signature ⇒ NO headers at all, rather than a half-tagged report.
     *
     * <p>A receipt without the signature is refused by a Google Messages peer anyway; emitting the era alone
     * produces something that looks MLS-tagged and is undeliverable, which is strictly harder to
     * diagnose than an untagged report.
     */
    @Test
    public void noSignatureYieldsNoHeaders() {
        assertTrue(hdrs(null, 1, "m1", "m0").isEmpty());
        assertTrue(hdrs("", 1, "m1", "m0").isEmpty());
    }

    // ---- MERGE, not substitute ----------------------------------------------------------------

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

    /** A generated header REPLACES an existing one of the same name rather than duplicating it. */
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

    // ---- the receipt-type dispatch (ordinals 0..3) --------------------------------------------

    @Test
    public void theOrdinalsMatchTheReferenceClient() {
        assertEquals(0, MlsReceiptMetadata.ReceiptType.UNKNOWN_RECEIPT_TYPE.ordinalValue);
        assertEquals(1, MlsReceiptMetadata.ReceiptType.DELIVERY.ordinalValue);
        assertEquals(2, MlsReceiptMetadata.ReceiptType.DISPLAYED.ordinalValue);
        assertEquals(3, MlsReceiptMetadata.ReceiptType.DELIVERY_FAILED.ordinalValue);
    }

    /**
     * <b>{@code DELIVERY_FAILED} shares {@code DELIVERY}'s handler</b> — §12.11 calls it out
     * ("the SAME handler — note this"). Only DISPLAYED uses the display verb.
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

    // ---- the metadata protos (observed field numbers) -----------------------------------------

    /**
     * {@code DeliveryReceiptMetadata { 1 version, 2 status, 3 message_id, 6 original_message_id }},
     * byte for byte.
     */
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
     * <b>{@code original_message_id} is field 6, not 4</b> — fields 4 and 5 are a failure-reason
     * oneof the outbound path never populates. Encoding it at 4 would parse into that oneof.
     */
    @Test
    public void deliveryMetadataPutsOriginalIdAtSixNotFour() {
        final byte[] got = MlsReceiptMetadata.deliveryReceiptMetadata(1, "m1", "m0");
        // 0x32 = (6 << 3) | 2. 0x22 would be (4 << 3) | 2.
        assertTrue("field 6", contains(got, new byte[] { 0x32, 0x02, 'm', '0' }));
        assertFalse("NOT field 4", contains(got, new byte[] { 0x22, 0x02, 'm', '0' }));
    }

    /**
     * ...and the DISPLAY metadata puts it at <b>4</b>. The two protos are not the same shape, and
     * copying the delivery numbering across is the mistake available here.
     */
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

    /** The failed status is 2, and only the DELIVERY metadata can carry it. */
    @Test
    public void theFailedStatusIsTwo() {
        assertEquals(2, MlsReceiptMetadata.STATUS_FAILED);
        final byte[] got = MlsReceiptMetadata.deliveryReceiptMetadata(
                MlsReceiptMetadata.STATUS_FAILED, "m1", null);
        assertTrue(contains(got, new byte[] { 0x10, 0x02 }));
        // The display metadata has no failure arm at all — it always writes status 1.
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
