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
 * What an MLS engine call was asked to do, and on whose behalf (rework item 6.5, §10.3).
 *
 * <p>We have no context object at all today: the engine entry point takes {@code (groupId, wire)},
 * so there is nowhere to put a correlation id, an operation cause, or the message id the AAD is
 * supposed to be cross-checked against.
 *
 * <h2>The ciphertext is stripped, and the rule is built in from the start</h2>
 *
 * <p>The context carries a REDACTED copy of the inbound envelope — every payload part replaced by
 * empty bytes. Right now we pass no envelope at all, so "strip the ciphertext" is not yet a hazard;
 * the rule is here anyway because the moment an envelope is added, the natural implementation copies
 * it whole. A context is logged, echoed through results, and kept alongside a pending operation, so
 * a ciphertext inside one is a ciphertext in the logs and in persisted state — and it is the one
 * thing in the envelope that has no business being there.
 *
 * <p>{@link #redactPayload} is therefore the only way to put payload-shaped bytes in, and it does
 * not keep them.
 *
 * <h2>The id is carried TWICE, and the asymmetry is load-bearing</h2>
 *
 * <p>{@link #correlationId} is suffixed ({@code base + "_" + label + "_" + epochMillis}) and is what
 * a re-drive uses to tell a child pass from its parent. {@link #messageId} is NOT suffixed — it is
 * the id the AAD binds and the peer echoes, so any decoration makes it stop matching.
 *
 * <p>§10.3 points 1–3 turn on that distinction, and 6.6's re-drive semantics cannot be re-derived
 * later if the two are collapsed into one field now. Collapsing them is the obvious simplification
 * and it is the mistake: suffix the message id and the AAD cross-check fails against every real
 * peer; leave the correlation id unsuffixed and a re-drive cannot distinguish its own results from
 * the ones that produced it.
 *
 * <h2>The 500-character cap</h2>
 *
 * <p>Applied to the BASE before suffixing, so the suffix is never what gets truncated — a truncated
 * suffix would produce two child contexts that compare equal, which is exactly the confusion the
 * suffix exists to prevent.
 */
public final class MlsRequestContext {

    /** §10.3's base-id cap, applied before the suffix is appended. */
    public static final int MAX_BASE_ID_CHARS = 500;

    /** Why the engine is being called. Drives the death remedy and the re-drive arm. */
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

    /**
     * The suffixed id — unique per pass, so a re-drive can tell its own results from its parent's.
     */
    public final String correlationId;

    /**
     * The RCS message id. <b>Never suffixed</b> — the AAD binds this and the peer echoes it.
     */
    public final String messageId;

    public final Cause cause;

    /** The MLS group, or {@code null} when the call is not group-scoped. */
    private final byte[] mGroupId;

    /**
     * The inbound envelope with every payload part emptied. Never null; empty when there is none.
     */
    private final byte[] mRedactedEnvelope;

    private MlsRequestContext(final String correlationId, final String messageId,
            final Cause cause, final byte[] groupId, final byte[] redactedEnvelope) {
        this.correlationId = correlationId == null ? "" : correlationId;
        this.messageId = messageId == null ? "" : messageId;
        this.cause = cause == null ? Cause.UNKNOWN : cause;
        mGroupId = copy(groupId);
        mRedactedEnvelope = redactedEnvelope == null ? new byte[0] : copy(redactedEnvelope);
    }

    /**
     * A root context.
     *
     * @param baseId    the correlation base; truncated to {@link #MAX_BASE_ID_CHARS}
     * @param messageId the RCS message id, stored verbatim
     */
    public static MlsRequestContext root(final String baseId, final String messageId,
            final Cause cause, final byte[] groupId) {
        return new MlsRequestContext(truncateBase(baseId), messageId, cause, groupId, null);
    }

    /**
     * A CHILD context for a re-drive pass.
     *
     * <p>The correlation id gains {@code "_" + label + "_" + nowMs}; the message id does not move.
     * The clock is passed in rather than read, because §19.2-10 keeps clock reads off this path and
     * because a test that cannot fix the time cannot assert on the id.
     */
    public MlsRequestContext child(final String label, final long nowMs) {
        final String suffixed = truncateBase(correlationId) + "_"
                + (label == null ? "" : label) + "_" + nowMs;
        return new MlsRequestContext(suffixed, messageId, cause, mGroupId, mRedactedEnvelope);
    }

    /**
     * Attach an inbound envelope with its payload removed.
     *
     * <p>Takes the envelope and the payload's span rather than a pre-redacted blob, so a caller
     * cannot pass the ciphertext through by forgetting to strip it.
     *
     * @param envelope     the whole inbound envelope
     * @param payloadStart offset of the payload within it
     * @param payloadLen   length of the payload; zeroed in the stored copy
     */
    public MlsRequestContext withRedactedEnvelope(final byte[] envelope, final int payloadStart,
            final int payloadLen) {
        return new MlsRequestContext(correlationId, messageId, cause, mGroupId,
                redactPayload(envelope, payloadStart, payloadLen));
    }

    /**
     * Return {@code envelope} with {@code [payloadStart, payloadStart+payloadLen)} zeroed.
     *
     * <p>Zeroed rather than removed so offsets elsewhere in the envelope stay valid — a redaction
     * that shifts the bytes would corrupt anything that indexes into it.
     */
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

    /** Truncate a correlation BASE to the cap. Never applied after suffixing — see the class doc. */
    public static String truncateBase(final String base) {
        if (base == null) return "";
        return base.length() <= MAX_BASE_ID_CHARS ? base : base.substring(0, MAX_BASE_ID_CHARS);
    }

    /**
     * Rendering for logs. Prints the ENVELOPE LENGTH only, never its bytes — the envelope is
     * redacted, not empty, and the un-redacted parts are still routing metadata.
     */
    @Override public String toString() {
        return "ctx{" + correlationId + " msg=" + messageId + " cause=" + cause
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
