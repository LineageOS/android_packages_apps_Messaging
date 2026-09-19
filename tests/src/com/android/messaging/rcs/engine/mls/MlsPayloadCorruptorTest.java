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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The corruptor must corrupt exactly one layer, and the right one.
 *
 * <p>An instrument that quietly produces a WELL-FORMED payload is worse than no instrument: the
 * probe would report "Google Messages did not emit" for a message that never carried the defect it was
 * supposed to carry, and we would draw a conclusion about Google Messages from a test of nothing.
 */
public class MlsPayloadCorruptorTest {

    /** A framed payload shaped like RccMlsBody's: 8-byte header, 1-byte var-int, content. */
    private static byte[] framed(final int contentLen) {
        final byte[] b = new byte[8 + 1 + contentLen];
        b[0] = 0x00; b[1] = 0x01; b[2] = 0x00; b[3] = 0x01;
        b[8] = (byte) contentLen; // 1-byte var-int (< 0x40)
        for (int i = 0; i < contentLen; i++) b[9 + i] = (byte) (i & 0x7F);
        return b;
    }

    @Test
    public void lengthOverrunClaimsMoreThanFollowsAndTouchesNothingElse() {
        final byte[] in = framed(32);
        final byte[] out = MlsPayloadCorruptor.corrupt(in, MlsPayloadCorruptor.Mode.LENGTH_OVERRUN);
        assertEquals("same size — only the declared length moved", in.length, out.length);
        assertTrue("declared length must exceed the real content",
                (out[8] & 0x3F) > (in[8] & 0x3F));
        for (int i = 0; i < in.length; i++) {
            if (i == 8) continue;
            assertEquals("byte " + i + " must be untouched", in[i], out[i]);
        }
    }

    @Test
    public void lengthUnderrunClaimsLessThanFollows() {
        final byte[] in = framed(32);
        final byte[] out = MlsPayloadCorruptor.corrupt(in, MlsPayloadCorruptor.Mode.LENGTH_UNDERRUN);
        assertTrue((out[8] & 0x3F) < (in[8] & 0x3F));
    }

    /**
     * The var-int must keep its ENCODED WIDTH. A width change shifts every following byte, so the
     * probe would be corrupting the content too and we could not say which layer failed.
     */
    @Test
    public void rewritingTheLengthNeverChangesItsEncodedWidth() {
        for (final MlsPayloadCorruptor.Mode m : new MlsPayloadCorruptor.Mode[] {
                MlsPayloadCorruptor.Mode.LENGTH_OVERRUN, MlsPayloadCorruptor.Mode.LENGTH_UNDERRUN }) {
            final byte[] in = framed(32);
            final byte[] out = MlsPayloadCorruptor.corrupt(in, m);
            assertEquals(m + " changed the var-int width", (in[8] & 0xC0), (out[8] & 0xC0));
        }
    }

    /** Underrun near zero must not wrap around into a huge length — that is a different test. */
    @Test
    public void underrunClampsRatherThanWrapping() {
        final byte[] in = framed(2);
        final byte[] out = MlsPayloadCorruptor.corrupt(in, MlsPayloadCorruptor.Mode.LENGTH_UNDERRUN);
        assertTrue("must stay a small, non-negative length", (out[8] & 0x3F) >= 0);
        assertTrue((out[8] & 0x3F) <= 2);
    }

    @Test
    public void truncateKeepsTheHeaderAndShortensTheContent() {
        final byte[] in = framed(40);
        final byte[] out = MlsPayloadCorruptor.corrupt(in, MlsPayloadCorruptor.Mode.TRUNCATE_BODY);
        assertTrue("must actually be shorter", out.length < in.length);
        assertTrue("must keep header + length", out.length > 9);
        for (int i = 0; i < 9; i++) assertEquals(in[i], out[i]);
    }

    @Test
    public void breakContainerTouchesTheHeaderAndNothingAfterIt() {
        final byte[] in = framed(24);
        final byte[] out = MlsPayloadCorruptor.corrupt(in, MlsPayloadCorruptor.Mode.BREAK_CONTAINER);
        assertNotEquals(in[7], out[7]);
        assertEquals("the length must be left alone", in[8], out[8]);
        assertEquals(in.length, out.length);
    }

    /** The input must never be modified — a probe that corrupts its own source sends garbage twice. */
    @Test
    public void theInputIsNeverModified() {
        final byte[] in = framed(24);
        final byte[] copy = in.clone();
        for (final MlsPayloadCorruptor.Mode m : MlsPayloadCorruptor.Mode.values()) {
            MlsPayloadCorruptor.corrupt(in, m);
        }
        assertArrayEquals("corrupt() mutated its argument", copy, in);
    }

    /** Too short to be a framed payload must THROW, not silently return something sendable. */
    @Test
    public void aPayloadTooShortToCorruptIsRejectedLoudly() {
        for (final byte[] bad : new byte[][] { null, new byte[0], new byte[9] }) {
            try {
                MlsPayloadCorruptor.corrupt(bad, MlsPayloadCorruptor.Mode.LENGTH_OVERRUN);
                fail("must refuse a payload it cannot corrupt");
            } catch (final IllegalArgumentException expected) {
                // the point
            }
        }
    }

    @Test
    public void modeNamesAndTheirCommandLineAliasesResolve() {
        assertEquals(MlsPayloadCorruptor.Mode.LENGTH_OVERRUN, MlsPayloadCorruptor.modeOf("over"));
        assertEquals(MlsPayloadCorruptor.Mode.LENGTH_UNDERRUN, MlsPayloadCorruptor.modeOf("under"));
        assertEquals(MlsPayloadCorruptor.Mode.TRUNCATE_BODY, MlsPayloadCorruptor.modeOf("trunc"));
        assertEquals(MlsPayloadCorruptor.Mode.BREAK_CONTAINER,
                MlsPayloadCorruptor.modeOf("break_container"));
        assertEquals("an unknown mode falls back to the default rather than failing the probe",
                MlsPayloadCorruptor.Mode.LENGTH_OVERRUN, MlsPayloadCorruptor.modeOf("nonsense"));
        assertEquals(MlsPayloadCorruptor.Mode.LENGTH_OVERRUN, MlsPayloadCorruptor.modeOf(null));
    }

    /** Every mode must change SOMETHING — a no-op mode is the silent-negative failure again. */
    @Test
    public void everyModeChangesThePayload() {
        final byte[] in = framed(40);
        for (final MlsPayloadCorruptor.Mode m : MlsPayloadCorruptor.Mode.values()) {
            final byte[] out = MlsPayloadCorruptor.corrupt(in, m);
            assertFalse(m + " produced an identical payload", java.util.Arrays.equals(in, out));
        }
    }
}
