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

    /** One-shot post-seal ciphertext corruption for the next send, on either path; process-wide. */
    private static final java.util.concurrent.atomic.AtomicBoolean sCorruptNextCt =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Arm the post-seal ciphertext corruption for the next send. */
    public static void armCorruptNextCt() {
        sCorruptNextCt.set(true);
    }

    /** Take the armed corruption: true exactly once per arming. */
    public static boolean takeCorruptNextCt() {
        return sCorruptNextCt.getAndSet(false);
    }

    /**
     * One-shot failure-reason override for the next emitted negative receipt; 0 means none.
     * Consumed by the emit, so it cannot misreport a later real failure.
     */
    private static final java.util.concurrent.atomic.AtomicInteger sFtdReasonOverride =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Arm the failure-reason override for the next report only. */
    public static void armFtdReasonOverride(final int code) {
        sFtdReasonOverride.set(code);
    }

    /** Take the armed override: the code exactly once per arming, else 0 (no override). */
    public static int takeFtdReasonOverride() {
        return sFtdReasonOverride.getAndSet(0);
    }

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

    /**
     * Receive-side one-shot: fail the next inbound decrypt. Persisted, because the process often
     * restarts between arming and the message arriving; cleared when it fires.
     */
    public static final String PREF_FAIL_NEXT_DECRYPT = "fail_next_decrypt";

    /** Consume the one-shot: true exactly once per arming, then cleared durably. */
    public static boolean consumeFailNextDecrypt(final MlsShellPort shell) {
        try {
            final MlsPrefs p = shell.prefs();
            if (!p.getBoolean(PREF_FAIL_NEXT_DECRYPT, false)) return false;
            // commit(): a crash before the clear persists would fire it on an unrelated message.
            p.edit().putBoolean(PREF_FAIL_NEXT_DECRYPT, false).commit();
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * The message-id bytes the engine builds the application AAD from, one implementation for both
     * send paths. When {@code debug.rcs.mls_resend_tag >= 0} it also stages a present RCC.16 §10.3
     * resent-message component (64 bytes of filler) so a peer's error shows whether the tag and the
     * prefix width ({@code debug.rcs.mls_resend_prefix}) are right. Debug builds only, since every
     * message then fails at the peer. See docs/testing.md.
     */
    public static byte[] buildAadWithProbe(final MlsShellPort shell, final MlsLogSink log,
            final String messageId, final int era) {
        // era is unused: the engine reads the real one from 0xF001.
        final byte[] idBytes = messageId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final int probeTag = shell.sysprops().debuggableBuild()
                ? shell.sysprops().getInt("debug.rcs.mls_resend_tag", -1) : -1;
        if (probeTag < 0) {
            shell.session().setNextResentComponent(null);   // ordinary: component absent
            return idBytes;
        }
        final String w = shell.sysprops().get("debug.rcs.mls_resend_prefix", "u8");
        final MlsResentMessage.PrefixWidth width = "u16".equalsIgnoreCase(w)
                ? MlsResentMessage.PrefixWidth.U16
                : "varint".equalsIgnoreCase(w) ? MlsResentMessage.PrefixWidth.VARINT
                                               : MlsResentMessage.PrefixWidth.U8;
        final byte[] filler = new byte[MlsResentMessage.HMAC_FIELD_LEN];
        java.util.Arrays.fill(filler, (byte) 0x5A);
        final byte[] component = MlsResentMessage.encode(probeTag, filler, width);
        log.w("MlsPayloadCorruptor: RESEND-COMPONENT PROBE ACTIVE — emitting tag=0x"
                + Integer.toHexString(probeTag) + " prefix=" + width + " payload=" + filler.length
                + "B in the AAD trailing slot instead of the absent 0x00. This message is a "
                + "DIAGNOSTIC and the peer is expected to REJECT it; read WHICH error it "
                + "gives. Clear debug.rcs.mls_resend_tag when done.");
        shell.session().setNextResentComponent(component);
        return idBytes;
    }

    /**
     * Send a group message whose inner payload is malformed while its MLS encryption is normal, so
     * the peer decrypts it and fails on the payload. For a group we own on both ends.
     */
    public static boolean sendMalformedPayloadToGroup(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String text,
            final MlsPayloadCorruptor.Mode mode) {
        final byte[] good = RccMlsBody.frameText(text);
        final byte[] bad;
        try {
            bad = MlsPayloadCorruptor.corrupt(good, mode);
        } catch (final IllegalArgumentException e) {
            // Refuse rather than send a valid payload the operator believes is malformed.
            log.e("MlsPayloadCorruptor: cannot malform this payload — NOT sending, "
                    + "because a probe that quietly sends a VALID message produces a negative "
                    + "result about nothing", e);
            return false;
        }
        log.w("MlsPayloadCorruptor: sending a DELIBERATELY MALFORMED payload to "
                + rcsGroupId + " (" + mode + ", " + good.length + "B → " + bad.length
                + "B). The MLS "
                + "layer is untouched, so the peer decrypts it and fails on the inner payload. "
                + "Expect the peer to show nothing and, if that predicate holds, to send us a "
                + "negative delivery receipt — the reference artefact we have never had.");
        return shell.sendFramedToGroup(rcsGroupId, bad, "mls-grp", /*rcsMessageId=*/ null);
    }

    /** The reason for the report being emitted now: the armed override, else RCC.16 §7.7.2.2 4. */
    public static int takeFtdReasonOverride(final MlsLogSink log) {
        final int override = MlsPayloadCorruptor.takeFtdReasonOverride();
        if (override <= 0) return RccNegativeDeliveryImdn.Reason.FAILED_TO_DECRYPT.code();
        log.w("MlsPayloadCorruptor: FAILURE-REASON OVERRIDE ACTIVE — reporting " + override
                + " instead of 4 (failed-to-decrypt). This receipt DOES NOT describe what actually "
                + "happened; it is an experiment. One-shot: the next report reverts to 4.");
        return override;
    }
}
