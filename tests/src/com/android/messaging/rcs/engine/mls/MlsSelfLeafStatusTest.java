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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The record the §9.5.3 certificate update is decided from.
 *
 * <p>The numbers are 010T's, 2026-09-10: the device certificate was re-minted 2026-09-09 with
 * {@code notAfter} 2026-11-23 07:17:20Z ({@code 1795418240}) while its leaf in group
 * {@code 5B8905CD-…} still certified a 2026-07-23 mint expiring {@code 1791257114} — seven weeks of
 * drift, and the group's copy is the one the server validates.
 */
public class MlsSelfLeafStatusTest {

    private static final long GROUP_NB = 1784780714L;   // 2026-07-23 04:25:14Z
    private static final long GROUP_NA = 1791257114L;   // 2026-10-06 03:25:14Z
    private static final long CLIENT_NB = 1788941839L;  // 2026-09-09 08:17:19Z
    private static final long CLIENT_NA = 1795418240L;  // 2026-11-23 07:17:20Z
    /** 2026-09-10T00:00:00Z. */
    private static final long NOW = 1789084800L;

    private static byte[] wire(final int idx, final long gnb, final long gna, final long cnb,
            final long cna, final boolean stale) {
        final byte[] b = new byte[MlsSelfLeafStatus.WIRE_BYTES];
        for (int i = 0; i < 4; i++) b[3 - i] = (byte) (idx >>> (8 * i));
        put64(b, 4, gnb);
        put64(b, 12, gna);
        put64(b, 20, cnb);
        put64(b, 28, cna);
        b[36] = (byte) (stale ? 1 : 0);
        return b;
    }

    private static void put64(final byte[] b, final int off, final long v) {
        for (int i = 0; i < 8; i++) b[off + 7 - i] = (byte) (v >>> (8 * i));
    }

    /** The 010T measurement, decoded. */
    @Test
    public void itDecodesTheEnginesRecord() {
        final MlsSelfLeafStatus s = MlsSelfLeafStatus.parse(
                wire(1, GROUP_NB, GROUP_NA, CLIENT_NB, CLIENT_NA, true));
        assertEquals(1, s.leafIndex);
        assertEquals(GROUP_NB, s.groupNotBefore);
        assertEquals(GROUP_NA, s.groupNotAfter);
        assertEquals(CLIENT_NB, s.clientNotBefore);
        assertEquals(CLIENT_NA, s.clientNotAfter);
        assertTrue(s.stale);
    }

    /**
     * THE DRIFT, in the units the rule is stated in. The group's copy has 25 days left — inside
     * RCC.16's floor — while the certificate the device holds has 73. Reading the device's store
     * answers 73 and the server answers 25.
     */
    @Test
    public void theGroupsCopyAndTheDevicesCertificateAreDifferentNumbers() {
        final MlsSelfLeafStatus s = MlsSelfLeafStatus.parse(
                wire(1, GROUP_NB, GROUP_NA, CLIENT_NB, CLIENT_NA, true));
        assertEquals(25L, s.groupRemainingDays(NOW));
        assertEquals(73L, s.clientRemainingDays(NOW));
        assertTrue("the group's copy is inside the floor",
                MlsCredentialFloor.insideFloor(s.groupNotAfter, NOW,
                        MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS));
        assertFalse("and the one we hold is not, which is why re-minting kept not helping",
                MlsCredentialFloor.insideFloor(s.clientNotAfter, NOW,
                        MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS));
    }

    /**
     * An UNREADABLE window reports {@link Long#MIN_VALUE}, not a very negative day count — a caller
     * must not be able to treat "we could not look" as "very expired".
     */
    @Test
    public void anUnreadableWindowIsNotAVeryOldOne() {
        final MlsSelfLeafStatus s = MlsSelfLeafStatus.parse(wire(0, 0L, 0L, 0L, 0L, false));
        assertEquals(Long.MIN_VALUE, s.groupRemainingDays(NOW));
        assertEquals(Long.MIN_VALUE, s.clientRemainingDays(NOW));
    }

    /**
     * A SHORT record is null rather than a partly-filled object. Every field feeds a decision to
     * issue a Commit, and a zero from a truncated buffer is indistinguishable from a zero meaning
     * "unreadable" once it is inside the object.
     */
    @Test
    public void aTruncatedRecordIsRefused() {
        assertNull(MlsSelfLeafStatus.parse(null));
        assertNull(MlsSelfLeafStatus.parse(new byte[0]));
        assertNull(MlsSelfLeafStatus.parse(new byte[MlsSelfLeafStatus.WIRE_BYTES - 1]));
        assertNull("and so is a LONG one — the engine's record is fixed width",
                MlsSelfLeafStatus.parse(new byte[MlsSelfLeafStatus.WIRE_BYTES + 1]));
    }

    /** A leaf index near the unsigned ceiling decodes as the engine wrote it. */
    @Test
    public void aLargeLeafIndexSurvivesTheDecode() {
        final MlsSelfLeafStatus s = MlsSelfLeafStatus.parse(
                wire(0x7FFFFFFF, GROUP_NB, GROUP_NA, CLIENT_NB, CLIENT_NA, false));
        assertEquals(0x7FFFFFFF, s.leafIndex);
        assertFalse(s.stale);
    }

    /** Any non-zero stale byte is stale. The engine writes 1; a decoder that demanded it would be
     *  a spelling check rather than a meaning one. */
    @Test
    public void anyNonZeroStaleByteIsStale() {
        final byte[] b = wire(0, GROUP_NB, GROUP_NA, CLIENT_NB, CLIENT_NA, false);
        b[36] = (byte) 0xFF;
        assertTrue(MlsSelfLeafStatus.parse(b).stale);
    }
}
