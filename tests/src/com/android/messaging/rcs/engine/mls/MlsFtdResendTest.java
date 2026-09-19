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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Rework item 5.2 — the FTD resend count is DURABLE and BOUNDED (§7.6, §10.3).
 *
 * <p>The bug this closes: the count lived in an in-memory HashMap, so a process restart reset
 * §10.3's cap and a permanently-undecryptable message got an unlimited chain of reports to the peer.
 */
public class MlsFtdResendTest {

    @Test public void theCountSurvivesARoundTrip() {
        // The entire point: it has to still be there after the restart that used to erase it.
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
        // §4.8 shape-rule 1 writes the record WHOLE, so an unbounded map would grow the blob on
        // every undecryptable message forever.
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
        // Bumping a count must never cost another message id its entry — otherwise a single message
        // failing repeatedly would flush the whole table.
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
        // Deliberate: the id being bumped is the one approaching its cap, so LRU would keep it
        // forever while evicting ids that had only just started failing.
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
        // The record is written WHOLE; a caller mutating a handed-out map would be a partial update
        // by another name.
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .putFtdResendCount("mid", 1).build();
        try {
            rec.ftdResendCounts.put("sneaky", 1);
            org.junit.Assert.fail("expected an unmodifiable map");
        } catch (final UnsupportedOperationException expected) { /* expected */ }
    }

    @Test public void anOldRecordWithoutTheFieldStillDecodes() {
        // Every record on every device today predates this field.
        final MlsConversationRecord old = MlsConversationRecord.builder()
                .peerE164("+15551234567").build();
        final MlsConversationRecord back = MlsConversationRecord.decode(old.encode());
        assertNotNull(back);
        assertTrue(back.ftdResendCounts.isEmpty());
        assertFalse(back.ftdResendCounts.containsKey(""));
    }
}
