/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The failed-to-decrypt resend count is durable and bounded (RCC.16 §10.3): an in-memory count
 * would reset the chain cap on every restart. See docs/mls/health-and-recovery.md.
 */
public class MlsFtdResendTest {

    @Test public void theCountSurvivesARoundTrip() {
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .putFtdResendCount("mid-a", 3)
                .putFtdResendCount("mid-b", 1)
                .build();
        final MlsConversationRecord back = MlsConversationRecord.decode(rec.encode());
        assertNotNull(back);
        assertEquals(Integer.valueOf(3), back.ftdResendCounts.get("mid-a"));
        assertEquals(Integer.valueOf(1), back.ftdResendCounts.get("mid-b"));
    }

    @Test public void anAbsentCountIsAbsentNotZero() {
        final MlsConversationRecord rec = MlsConversationRecord.builder().build();
        assertTrue(rec.ftdResendCounts.isEmpty());
        assertNull(rec.ftdResendCounts.get("never-seen"));
    }

    @Test public void theMapIsBounded() {
        // The record is written whole, so an unbounded map would grow on every undecryptable
        // message.
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder();
        for (int i = 0; i < MlsConversationRecord.MAX_FTD_RESEND_ENTRIES * 3; i++) {
            b.putFtdResendCount("mid-" + i, i);
        }
        final MlsConversationRecord rec = b.build();
        assertEquals(MlsConversationRecord.MAX_FTD_RESEND_ENTRIES, rec.ftdResendCounts.size());
    }

    @Test public void evictionIsOldestFirst() {
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder();
        for (int i = 0; i < MlsConversationRecord.MAX_FTD_RESEND_ENTRIES + 2; i++) {
            b.putFtdResendCount("mid-" + i, 1);
        }
        final MlsConversationRecord rec = b.build();
        assertNull("the first two inserted must have been evicted",
                rec.ftdResendCounts.get("mid-0"));
        assertNull(rec.ftdResendCounts.get("mid-1"));
        assertNotNull("the newest must survive", rec.ftdResendCounts.get(
                "mid-" + (MlsConversationRecord.MAX_FTD_RESEND_ENTRIES + 1)));
    }

    @Test public void updatingAnExistingIdDoesNotEvictAnything() {
        // A bump must not evict another id, or one repeatedly failing message would flush the
        // table.
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder();
        for (int i = 0; i < MlsConversationRecord.MAX_FTD_RESEND_ENTRIES; i++) {
            b.putFtdResendCount("mid-" + i, 1);
        }
        for (int n = 2; n < 20; n++) {
            b.putFtdResendCount("mid-0", n);
        }
        final MlsConversationRecord rec = b.build();
        assertEquals(MlsConversationRecord.MAX_FTD_RESEND_ENTRIES, rec.ftdResendCounts.size());
        assertEquals(Integer.valueOf(19), rec.ftdResendCounts.get("mid-0"));
        assertNotNull("no sibling may be evicted by an in-place bump",
                rec.ftdResendCounts.get("mid-1"));
    }

    @Test public void evictionOrderIsFirstSeenNotLastTouched() {
        // The id being bumped is the one nearing its cap; LRU would keep it while evicting ids that
        // had only started failing.
        final MlsConversationRecord.Builder b = MlsConversationRecord.builder();
        for (int i = 0; i < MlsConversationRecord.MAX_FTD_RESEND_ENTRIES; i++) {
            b.putFtdResendCount("mid-" + i, 1);
        }
        b.putFtdResendCount("mid-0", 99);          // touch the oldest ...
        b.putFtdResendCount("fresh", 1);           // ... then force one eviction
        final MlsConversationRecord rec = b.build();
        assertNull("touching must not have rescued the oldest", rec.ftdResendCounts.get("mid-0"));
        assertNotNull(rec.ftdResendCounts.get("fresh"));
    }

    @Test public void aNegativeCountIsClampedNotStored() {
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .putFtdResendCount("mid", -5).build();
        assertEquals(Integer.valueOf(0), rec.ftdResendCounts.get("mid"));
    }

    @Test public void theMapIsUnmodifiable() {
        // The record is written whole; mutating a handed-out map would be a partial update.
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .putFtdResendCount("mid", 1).build();
        try {
            rec.ftdResendCounts.put("sneaky", 1);
            org.junit.Assert.fail("expected an unmodifiable map");
        } catch (final UnsupportedOperationException expected) { /* expected */ }
    }

    @Test public void anOldRecordWithoutTheFieldStillDecodes() {
        final MlsConversationRecord old = MlsConversationRecord.builder()
                .peerE164("+15551234567").build();
        final MlsConversationRecord back = MlsConversationRecord.decode(old.encode());
        assertNotNull(back);
        assertTrue(back.ftdResendCounts.isEmpty());
        assertFalse(back.ftdResendCounts.containsKey(""));
    }
}
