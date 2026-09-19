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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * RCC.16 Annex C.1 commitment construction.
 *
 * <p>These assert the SHAPE the spec fixes — {@code SHA256(varint(len L) ‖ L ‖ varint(len V) ‖ V)} —
 * by recomputing it independently rather than pinning a golden digest we produced ourselves. A golden
 * value from our own implementation would pass even if the construction were wrong; an independent
 * recomputation catches a reordering, a missing length prefix, or a label-framing change.
 */
public final class RccCommitmentTest {

    /** Independent restatement of C.1, written from the spec text rather than from the source. */
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

    /** The label is part of the hashed content, so icon and subject must never collide on one value. */
    @Test
    public void labelSeparatesTheTwoCommitments() {
        final byte[] v = new byte[] {9, 9, 9};
        assertFalse(java.util.Arrays.equals(
                RccCommitment.iconCommitment(v), RccCommitment.subjectCommitment(v)));
    }

    /**
     * The length prefixes are what stop label/value concatenation from being ambiguous. Without them
     * {@code "ab"+"c"} and {@code "a"+"bc"} would hash identically.
     */
    @Test
    public void lengthPrefixesRemoveConcatenationAmbiguity() {
        final byte[] bc = "bc".getBytes(StandardCharsets.US_ASCII);
        final byte[] c = "c".getBytes(StandardCharsets.US_ASCII);
        assertFalse(java.util.Arrays.equals(
                RccCommitment.commit("a", bc), RccCommitment.commit("ab", c)));
    }

    /** A value crossing the 1-byte varint boundary (63/64) must still round-trip. */
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
        // wrong value, wrong label, wrong length, and null all fail
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
