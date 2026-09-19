/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The record the RCC.16 §9.5.3 certificate update is decided from. The fixture has the device's
 * certificate re-minted while its leaf in the group still certifies an older one; the group's copy
 * is the one the server validates. See docs/mls/credentials.md.
 */
public class MlsSelfLeafStatusTest {

    private static final long GROUP_NB = 1784780714L;
    private static final long GROUP_NA = 1791257114L;
    private static final long CLIENT_NB = 1788941839L;
    private static final long CLIENT_NA = 1795418240L;
    /** The group's copy has 25 days left at this instant, the device's certificate 73. */
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
     * The drift in days: the group's copy is inside the RCC.16 floor while the device's certificate
     * is not, so reading the device's store gives the wrong answer.
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
     * An unreadable window reports {@link Long#MIN_VALUE}, so "could not look" is not "very
     * expired".
     */
    @Test
    public void anUnreadableWindowIsNotAVeryOldOne() {
        final MlsSelfLeafStatus s = MlsSelfLeafStatus.parse(wire(0, 0L, 0L, 0L, 0L, false));
        assertEquals(Long.MIN_VALUE, s.groupRemainingDays(NOW));
        assertEquals(Long.MIN_VALUE, s.clientRemainingDays(NOW));
    }

    /**
     * A short record is null rather than partly filled: every field feeds a commit decision, and a
     * zero from truncation would read as "unreadable".
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

    /** Any non-zero stale byte is stale; the engine writes 1. */
    @Test
    public void anyNonZeroStaleByteIsStale() {
        final byte[] b = wire(0, GROUP_NB, GROUP_NA, CLIENT_NB, CLIENT_NA, false);
        b[36] = (byte) 0xFF;
        assertTrue(MlsSelfLeafStatus.parse(b).stale);
    }
}
