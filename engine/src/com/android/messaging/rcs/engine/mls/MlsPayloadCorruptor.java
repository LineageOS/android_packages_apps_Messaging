/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Debug instruments for failure-report testing: malforms a framed MLS application payload before it
 * is sealed, so a peer decrypts cleanly and fails only on the inner payload (the RCC.16 §7.7.2.2
 * negative-receipt condition), plus one-shot overrides for the send and receive paths. Never a
 * normal send path.
 */
public final class MlsPayloadCorruptor {

    private MlsPayloadCorruptor() {}

    /** How to malform the payload. Each targets a different layer of the decode. */
    public enum Mode {
        /** Declare more content than follows; mls-rs-codec reads a length as a byte budget. */
        LENGTH_OVERRUN,
        /** Declare less content than follows, leaving trailing bytes. */
        LENGTH_UNDERRUN,
        /** Keep the length honest and cut the content short. */
        TRUNCATE_BODY,
        /**
         * Corrupt the fixed 8-byte prefix. A control expected not to emit: it breaks the container,
         * not its contents.
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
     * @throws IllegalArgumentException if the input is too short to be a framed payload
     */
    public static byte[] corrupt(final byte[] framed, final Mode mode) {
        if (framed == null || framed.length <= HEADER_LEN + 1) {
            throw new IllegalArgumentException("not a framed payload: "
                    + (framed == null ? "null" : framed.length + " bytes"));
        }
        final byte[] out = framed.clone();
        switch (mode) {
            case BREAK_CONTAINER:
                // The last header byte: the first ones identify the container as a custom payload.
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

    /** Rewrite the length var-int in place, keeping its encoded width so only the length moves. */
    private static byte[] withRewrittenLength(final byte[] out, final boolean larger) {
        final int b0 = out[HEADER_LEN] & 0xFF;
        final int width = (b0 >> 6) == 0 ? 1 : (b0 >> 6) == 1 ? 2 : 4;
        // Small enough to stay plausible past a bounds check.
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
        if ("over".equalsIgnoreCase(name)) return Mode.LENGTH_OVERRUN;
        if ("under".equalsIgnoreCase(name)) return Mode.LENGTH_UNDERRUN;
        if ("trunc".equalsIgnoreCase(name)) return Mode.TRUNCATE_BODY;
        if ("container".equalsIgnoreCase(name)) return Mode.BREAK_CONTAINER;
        return Mode.LENGTH_OVERRUN;
    }
}
