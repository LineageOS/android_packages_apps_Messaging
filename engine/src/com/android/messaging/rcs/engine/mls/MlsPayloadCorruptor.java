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

/**
 * Deliberately malforms a framed MLS application payload — an <b>INSTRUMENT</b>, never a send path.
 *
 * <h2>WHY THIS EXISTS: we have never seen a real negative delivery receipt</h2>
 *
 * <p>Our §7.7.2.2 receive half is written from the spec and tested against our own emitter, which
 * means it is tested against our own reading of the spec. The one artefact that could check that
 * reading — a negative receipt produced by Google Messages — we have never obtained, because nothing we did
 * made a Google Messages peer emit one. The predicate is recoverable from the shipping app, and it is precisely
 * a payload this class can build:
 *
 * <ol>
 *   <li>the failed part's first content type is exactly {@code message/mls} — our normal MLS send;</li>
 *   <li>the message decrypts CLEANLY at the MLS layer, and the failure is in the INNER custom
 *       payload — so the corruption must be applied to the framed body BEFORE encryption, never to
 *       the MLS framing (that path yields CANNOT_PARSE_MESSAGE(16), which does NOT emit);</li>
 *   <li>the message carries no {@code mls_health_status}, which takes Google Messages' first branch
 *       straight to FAIL_NO_RETRY, and FAIL_NO_RETRY with an emit-eligible reason IS the emit gate.</li>
 * </ol>
 *
 * <p><b>The corruption is aimed, not random.</b> mls-rs-codec decodes a vector as a VarInt BYTE
 * BUDGET rather than an item count (mls-rs-codec 0.55) — so a length prefix that disagrees
 * with the bytes that follow is the most direct route to a deserialise failure, and {@link
 * Mode#LENGTH_OVERRUN} is the default for that reason. Random byte flipping would mostly produce a
 * payload that still parses, or one that fails at the wrong layer.
 *
 * <p><b>What is NOT established, and it is the gap that decides the probe.</b> The Java
 * dispatch is readable — given reason R, does Google Messages emit — but not the native
 * {@code wire-bytes → reason} mapping, which lives in its native engine. So "a malformed inner
 * payload yields 51" is an
 * expectation, not a fact. If a probe comes back as 16 instead, that is the reason, and 16 does not
 * emit: try another mode rather than concluding the recipe is wrong.
 */
public final class MlsPayloadCorruptor {

    private MlsPayloadCorruptor() {}

    /** How to malform the payload. Each targets a different layer of the decode. */
    public enum Mode {
        /**
         * Declare more content than follows. The decoder is handed a byte budget it cannot satisfy,
         * which is the failure mls-rs-codec's Vec decode produces most directly.
         */
        LENGTH_OVERRUN,
        /**
         * Declare less content than follows, leaving trailing bytes. Some decoders accept this and
         * ignore the tail, which is exactly why it is worth trying second: if it emits, the decoder
         * is stricter than the overrun case suggests.
         */
        LENGTH_UNDERRUN,
        /**
         * Keep the length honest and cut the content short. Structurally valid framing around a body
         * that ends mid-field.
         */
        TRUNCATE_BODY,
        /**
         * Corrupt the fixed 8-byte prefix. A CONTROL, and expected NOT to emit: this breaks the
         * container itself rather than its contents, so it should read as CANNOT_PARSE_MESSAGE(16),
         * which fails the emit gate. Running it is how we learn whether the two are distinguishable
         * from outside at all.
         */
        BREAK_CONTAINER,
    }

    /** The fixed prefix {@code RccMlsBody} writes ahead of the length var-int. */
    private static final int HEADER_LEN = 8;

    /**
     * Return a malformed copy of {@code framed}.
     *
     * @param framed a well-formed framed body (fixed header, length var-int, content)
     * @return a new array; {@code framed} is never modified
     * @throws IllegalArgumentException if the input is too short to be a framed payload, because a
     *         corruptor that silently returns something well-formed would make the probe report a
     *         negative result it never actually tested
     */
    public static byte[] corrupt(final byte[] framed, final Mode mode) {
        if (framed == null || framed.length <= HEADER_LEN + 1) {
            throw new IllegalArgumentException("not a framed payload: "
                    + (framed == null ? "null" : framed.length + " bytes"));
        }
        final byte[] out = framed.clone();
        switch (mode) {
            case BREAK_CONTAINER:
                // Flip the LAST header byte rather than the first. The first bytes are what a reader
                // is most likely to use to recognise the container at all; corrupting those risks
                // the payload not being treated as a custom payload in the first place, which tests
                // nothing.
                out[HEADER_LEN - 1] ^= 0xFF;
                return out;
            case TRUNCATE_BODY: {
                // Keep the header and the length, drop the last quarter of the content.
                final int keep = HEADER_LEN + 1 + ((framed.length - HEADER_LEN - 1) * 3 / 4);
                final byte[] cut = new byte[Math.max(HEADER_LEN + 2, keep)];
                System.arraycopy(framed, 0, cut, 0, cut.length);
                return cut;
            }
            case LENGTH_OVERRUN:
            case LENGTH_UNDERRUN:
            default:
                return withRewrittenLength(out, mode == Mode.LENGTH_OVERRUN);
        }
    }

    /**
     * Rewrite the length var-int in place, keeping its ENCODED WIDTH the same.
     *
     * <p>Keeping the width matters: a var-int whose leading bits say "4 bytes" where the original
     * said "1 byte" shifts every following byte, which corrupts the content as well and confuses
     * which layer actually failed. The whole point is to change one thing.
     */
    private static byte[] withRewrittenLength(final byte[] out, final boolean larger) {
        final int b0 = out[HEADER_LEN] & 0xFF;
        final int width = (b0 >> 6) == 0 ? 1 : (b0 >> 6) == 1 ? 2 : 4;
        // The delta is deliberately small — a length that is wrong by a few bytes stays plausible,
        // where a wildly wrong one might be rejected by a bounds check before any decode happens.
        final int delta = larger ? 7 : -7;
        switch (width) {
            case 1: {
                final int v = b0 & 0x3F;
                final int n = clamp(v + delta, 0, 0x3F);
                out[HEADER_LEN] = (byte) n;
                return out;
            }
            case 2: {
                final int v = ((b0 & 0x3F) << 8) | (out[HEADER_LEN + 1] & 0xFF);
                final int n = clamp(v + delta, 0, 0x3FFF);
                out[HEADER_LEN] = (byte) (0x40 | (n >> 8));
                out[HEADER_LEN + 1] = (byte) (n & 0xFF);
                return out;
            }
            default: {
                final int v = ((b0 & 0x3F) << 24) | ((out[HEADER_LEN + 1] & 0xFF) << 16)
                        | ((out[HEADER_LEN + 2] & 0xFF) << 8) | (out[HEADER_LEN + 3] & 0xFF);
                final int n = clamp(v + delta, 0, 0x3FFFFFFF);
                out[HEADER_LEN] = (byte) (0x80 | (n >> 24));
                out[HEADER_LEN + 1] = (byte) ((n >> 16) & 0xFF);
                out[HEADER_LEN + 2] = (byte) ((n >> 8) & 0xFF);
                out[HEADER_LEN + 3] = (byte) (n & 0xFF);
                return out;
            }
        }
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Parse a mode name leniently, for a debug lever's string extra. */
    public static Mode modeOf(final String name) {
        if (name == null || name.isEmpty()) return Mode.LENGTH_OVERRUN;
        for (final Mode m : Mode.values()) {
            if (m.name().equalsIgnoreCase(name)) return m;
        }
        // Short aliases, because these are typed on an adb command line under time pressure.
        if ("over".equalsIgnoreCase(name)) return Mode.LENGTH_OVERRUN;
        if ("under".equalsIgnoreCase(name)) return Mode.LENGTH_UNDERRUN;
        if ("trunc".equalsIgnoreCase(name)) return Mode.TRUNCATE_BODY;
        if ("container".equalsIgnoreCase(name)) return Mode.BREAK_CONTAINER;
        return Mode.LENGTH_OVERRUN;
    }
}
