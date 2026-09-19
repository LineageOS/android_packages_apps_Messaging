/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * What an MLS engine call was asked to do, and on whose behalf. {@link #correlationId} is suffixed
 * per re-drive pass so a pass can tell its results from its parent's; {@link #messageId} is never
 * suffixed, because the AAD binds it and the peer echoes it. Any envelope is stored with its
 * payload zeroed, since a context is logged and persisted. The base id is capped before suffixing,
 * so a truncated suffix cannot make two children compare equal.
 */
public final class MlsRequestContext {

    /** Cap on the correlation base id, applied before the suffix is appended. */
    public static final int MAX_BASE_ID_CHARS = 500;

    /** Why the engine is being called. */
    public enum Cause {
        UNKNOWN(0),
        /** An inbound message is being processed. */
        PROCESS_MESSAGE(1),
        /** A host-initiated send. */
        SEND_MESSAGE(2),
        /** Recovery: epoch or era advancement. */
        SELF_HEAL(3),
        /** Membership change. */
        MEMBERSHIP(4),
        /** Key-package or certificate refresh. */
        REFRESH(5),
        /** Downgrade out of MLS. */
        END_MLS(6);

        public final int wire;

        Cause(final int wire) { this.wire = wire; }

        public static Cause fromWire(final int w) {
            for (final Cause c : values()) if (c.wire == w) return c;
            return UNKNOWN;
        }
    }

    /** Unique per pass. */
    public final String correlationId;

    /** The RCS message id; never suffixed. */
    public final String messageId;

    public final Cause cause;

    /** The MLS group, or {@code null} when the call is not group-scoped. */
    private final byte[] mGroupId;

    /** The inbound envelope with its payload zeroed; empty when there is none. */
    private final byte[] mRedactedEnvelope;

    private MlsRequestContext(final String correlationId, final String messageId,
            final Cause cause, final byte[] groupId, final byte[] redactedEnvelope) {
        this.correlationId = correlationId == null ? "" : correlationId;
        this.messageId = messageId == null ? "" : messageId;
        this.cause = cause == null ? Cause.UNKNOWN : cause;
        mGroupId = copy(groupId);
        mRedactedEnvelope = redactedEnvelope == null ? new byte[0] : copy(redactedEnvelope);
    }

    /** @param baseId truncated to {@link #MAX_BASE_ID_CHARS} */
    public static MlsRequestContext root(final String baseId, final String messageId,
            final Cause cause, final byte[] groupId) {
        return new MlsRequestContext(truncateBase(baseId), messageId, cause, groupId, null);
    }

    /**
     * A child context for a re-drive pass: the correlation id gains {@code "_" + label + "_" +
     * nowMs}. The clock is passed in so a test can assert on the id.
     */
    public MlsRequestContext child(final String label, final long nowMs) {
        final String suffixed = truncateBase(correlationId) + "_"
                + (label == null ? "" : label) + "_" + nowMs;
        return new MlsRequestContext(suffixed, messageId, cause, mGroupId, mRedactedEnvelope);
    }

    /**
     * Attaches an inbound envelope with its payload zeroed. Takes the payload span rather than a
     * pre-redacted blob, so a caller cannot pass the ciphertext through by forgetting to strip it.
     */
    public MlsRequestContext withRedactedEnvelope(final byte[] envelope, final int payloadStart,
            final int payloadLen) {
        return new MlsRequestContext(correlationId, messageId, cause, mGroupId,
                redactPayload(envelope, payloadStart, payloadLen));
    }

    /** A copy with the payload span zeroed, not removed, so other offsets stay valid. */
    public static byte[] redactPayload(final byte[] envelope, final int payloadStart,
            final int payloadLen) {
        if (envelope == null) return new byte[0];
        final byte[] out = copy(envelope);
        if (payloadStart < 0 || payloadLen <= 0 || payloadStart >= out.length) return out;
        final int end = (int) Math.min((long) payloadStart + payloadLen, out.length);
        for (int i = payloadStart; i < end; i++) out[i] = 0;
        return out;
    }

    public byte[] groupId() { return copy(mGroupId); }

    public byte[] redactedEnvelope() { return copy(mRedactedEnvelope); }

    /** Truncates a correlation base to {@link #MAX_BASE_ID_CHARS}. */
    public static String truncateBase(final String base) {
        if (base == null) return "";
        return base.length() <= MAX_BASE_ID_CHARS ? base : base.substring(0, MAX_BASE_ID_CHARS);
    }

    /** Prints the envelope length only; its unredacted parts are routing metadata. */
    @Override public String toString() {
        return "ctx{" + correlationId + " msg=" + MlsMessageId.forLog(messageId) + " cause=" + cause
                + " gid=" + (mGroupId == null ? "null" : mGroupId.length + "B")
                + " env=" + mRedactedEnvelope.length + "B}";
    }

    private static byte[] copy(final byte[] b) {
        if (b == null) return null;
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }
}
