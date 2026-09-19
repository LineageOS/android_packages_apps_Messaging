/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 Annex C.1 commitment, {@code SHA-256(varint(len L) ‖ L ‖ varint(len V) ‖ V)}, recomputed
 * from the spec text rather than pinned to a digest our own implementation produced.
 */
public final class RccCommitmentTest {

    /** Annex C.1, restated from the spec text. */
    private static byte[] expected(final String label, final byte[] value) throws Exception {
        final byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(MlsAppMessage.mlsVarint(l.length));
        o.write(l);
        o.write(MlsAppMessage.mlsVarint(value.length));
        o.write(value);
        return MessageDigest.getInstance("SHA-256").digest(o.toByteArray());
    }

    @Test
    public void matchesTheSpecConstruction() throws Exception {
        final byte[] v = "an encrypted group icon".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected(RccCommitment.LABEL_ICON, v), RccCommitment.iconCommitment(v));
        assertArrayEquals(expected(RccCommitment.LABEL_SUBJECT, v),
                RccCommitment.subjectCommitment(v));
    }

    @Test
    public void isThirtyTwoBytes() {
        assertEquals(32, RccCommitment.iconCommitment(new byte[] {1, 2, 3}).length);
    }

    /** The label is hashed, so icon and subject never collide on one value. */
    @Test
    public void labelSeparatesTheTwoCommitments() {
        final byte[] v = new byte[] {9, 9, 9};
        assertFalse(java.util.Arrays.equals(
                RccCommitment.iconCommitment(v), RccCommitment.subjectCommitment(v)));
    }

    /** Without length prefixes {@code "ab"+"c"} and {@code "a"+"bc"} would hash identically. */
    @Test
    public void lengthPrefixesRemoveConcatenationAmbiguity() {
        final byte[] bc = "bc".getBytes(StandardCharsets.US_ASCII);
        final byte[] c = "c".getBytes(StandardCharsets.US_ASCII);
        assertFalse(java.util.Arrays.equals(
                RccCommitment.commit("a", bc), RccCommitment.commit("ab", c)));
    }

    /** Values across the varint length boundaries. */
    @Test
    public void handlesTheVarintBoundary() throws Exception {
        for (final int n : new int[] {0, 63, 64, 16383, 16384}) {
            final byte[] v = new byte[n];
            assertArrayEquals("len " + n, expected(RccCommitment.LABEL_ICON, v),
                    RccCommitment.iconCommitment(v));
        }
    }

    @Test
    public void verifyAcceptsOnlyTheRightValue() {
        final byte[] v = "subject".getBytes(StandardCharsets.UTF_8);
        final byte[] c = RccCommitment.subjectCommitment(v);
        assertNotNull(c);
        assertTrue(RccCommitment.verify(RccCommitment.LABEL_SUBJECT, v, c));
        assertFalse(RccCommitment.verify(RccCommitment.LABEL_SUBJECT, "other".getBytes(), c));
        assertFalse(RccCommitment.verify(RccCommitment.LABEL_ICON, v, c));
        assertFalse(RccCommitment.verify(RccCommitment.LABEL_SUBJECT, v, new byte[31]));
        assertFalse(RccCommitment.verify(RccCommitment.LABEL_SUBJECT, v, null));
    }

    @Test
    public void nullInputsDoNotThrow() {
        assertNull(RccCommitment.commit(null, new byte[0]));
        assertNull(RccCommitment.commit("x", null));
    }
}
