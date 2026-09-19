/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MlsWireScanPackedTest {


    private static byte[] rec(final byte[]... parts) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (final byte[] p : parts) {
            o.write(0); o.write(0); o.write(0); o.write(p.length);
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    @Test
    public void firstPackedIndexesLengthPrefixedRecordsAndRefusesATruncatedOne() {
        final byte[] packed = rec(new byte[] {1}, new byte[] {2, 3});
        assertArrayEquals(new byte[] {1}, MlsWireScan.firstPacked(packed, 0));
        assertArrayEquals(new byte[] {2, 3}, MlsWireScan.firstPacked(packed, 1));
        assertNull(MlsWireScan.firstPacked(packed, 2));
        assertNull(MlsWireScan.firstPacked(java.util.Arrays.copyOf(packed, packed.length - 1), 1));
        assertNull(MlsWireScan.firstPacked(null, 0));
    }
}
