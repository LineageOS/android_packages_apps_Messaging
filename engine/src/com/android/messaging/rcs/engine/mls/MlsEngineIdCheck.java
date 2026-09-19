/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.charset.StandardCharsets;

/**
 * The RCC.16 §7.5.3.1 message-id check, decided by the engine: both inbound legs decrypt through
 * {@link #process} and refuse on {@link Processed#idMismatch}. The engine takes any AAD version,
 * compares raw bytes and needs the era; an AAD it cannot parse, or one with no AAD, passes. See
 * docs/mls/rcc16-map.md.
 */
public final class MlsEngineIdCheck {
    private MlsEngineIdCheck() {}

    /** One {@code process} call with the engine's check armed, and what the engine concluded. */
    public static final class Processed {
        /** The decrypted payload, or null. */
        public final byte[] plain;
        /** The engine found an AAD id other than the one armed: refuse the message. */
        public final boolean idMismatch;
        /** The engine's {@code expected\0actual}, or null when it found no mismatch. */
        public final byte[] detail;

        Processed(final byte[] plain, final boolean idMismatch, final byte[] detail) {
            this.plain = plain;
            this.idMismatch = idMismatch;
            this.detail = detail;
        }

        /** The id the AAD named, from {@link #detail}, as US-ASCII; null when there is none. */
        public String aadMessageId() {
            final byte[][] pair = splitDetail(detail);
            return pair == null ? null : new String(pair[1], StandardCharsets.US_ASCII);
        }
    }

    /**
     * Arms the engine's check with {@code messageId}, processes, reads the verdict on the same
     * thread, and disarms. An absent id is armed as empty, so an AAD that names one is a mismatch.
     * The engine's armed id is sticky per thread, so it is cleared in a {@code finally}: a pooled
     * thread must not carry it into an unrelated call.
     */
    public static Processed process(final MlsSession session, final byte[] groupId,
            final byte[] ciphertext, final String messageId) {
        session.setRequestMessageId(messageId == null
                ? new byte[0] : messageId.getBytes(StandardCharsets.UTF_8));
        try {
            final byte[] plain = session.process(groupId, ciphertext);
            final boolean mismatch = session.lastMessageIdMismatch();
            return new Processed(plain, mismatch,
                    mismatch ? session.lastMessageIdMismatchDetail() : null);
        } finally {
            session.setRequestMessageId(null);
        }
    }

    /** {@code expected\0actual} split at the first NUL, or null. */
    static byte[][] splitDetail(final byte[] detail) {
        if (detail == null || detail.length == 0) return null;
        for (int i = 0; i < detail.length; i++) {
            if (detail[i] == 0) {
                return new byte[][] {java.util.Arrays.copyOfRange(detail, 0, i),
                        java.util.Arrays.copyOfRange(detail, i + 1, detail.length)};
            }
        }
        return null;
    }
}
