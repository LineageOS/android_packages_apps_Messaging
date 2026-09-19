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

public final class MlsHexTest {


    @Test
    public void hexIsLowercasePairsAndNullIsEmpty() {
        assertEquals("00ff7a", MlsHex.hex(new byte[] {0, (byte) 0xff, 0x7a}));
        assertEquals("", MlsHex.hex(new byte[0]));
        assertEquals("", MlsHex.hex(null));
    }


    @Test
    public void hexToBytesIgnoresSeparatorsAndDropsATrailingNibble() {
        assertArrayEquals(new byte[] {0x0a, (byte) 0xbc}, MlsHex.hexToBytes("0a:BC"));
        assertArrayEquals(new byte[] {0x12}, MlsHex.hexToBytes("123"));
        assertArrayEquals(new byte[0], MlsHex.hexToBytes("z"));
        assertArrayEquals(new byte[0], MlsHex.hexToBytes(null));
    }


    @Test
    public void hexPrefixShowsEightBytesAndTheLength() {
        final byte[] ten = new byte[10];
        ten[0] = 1;
        assertEquals("0100000000000000/10B", MlsHex.hexPrefix(ten));
        assertEquals("ab/1B", MlsHex.hexPrefix(new byte[] {(byte) 0xab}));
        assertEquals("null", MlsHex.hexPrefix(null));
    }


    @Test
    public void hexDumpMatchesHexExceptThatNullIsNamed() {
        final byte[] b = {0, 1, (byte) 0xfe};
        assertEquals(MlsHex.hex(b), MlsHex.hexDump(b));
        assertEquals("null", MlsHex.hexDump(null));
    }


    @Test
    public void groupIdHexIsHexIncludingNull() {
        final byte[] b = {(byte) 0x80, 0x7f};
        assertEquals(MlsHex.hex(b), MlsHex.groupIdHex(b));
        assertEquals(MlsHex.hex(null), MlsHex.groupIdHex(null));
    }
}
