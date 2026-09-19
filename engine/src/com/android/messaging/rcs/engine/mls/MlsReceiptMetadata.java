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

import java.util.ArrayList;
import java.util.List;

/**
 * Outbound positive-delivery and display receipts — §12.11. Rework item {@code 10.5}.
 *
 * <h2>Why this is an interop break and not a gap</h2>
 *
 * <p>§12.7 makes the {@code MLS-Derived-Content-Signature} header a <b>hard admission gate</b>: a
 * Google Messages peer's reader returns null before the receipt reaches validation. §12.7 is permissive for
 * negative-delivery <i>only</i> — the positive and display arms are strict. So a receipt with the
 * wrong header set is not degraded, it is <b>discarded</b>, and silently: the sender sees no receipt
 * and concludes the message was never delivered.
 *
 * <h2>The three header rules, and all three were wrong</h2>
 *
 * <ol>
 *   <li><b>{@code Epoch-Authenticator} is NEVER emitted on a receipt.</b> §12.11 states it flatly. We
 *       emitted it on every signed receipt, because the send path reused the CONTENT send's header
 *       builder — where it is required. A receipt is not a content send.</li>
 *   <li><b>The ORDER is signature, era, original-id.</b> We emitted era, epoch-auth, signature.</li>
 *   <li><b>{@code Original-Message-ID} is emitted only when present AND DIFFERENT</b> from the
 *       receipt's own message id. We never emitted it on the positive path at all.</li>
 * </ol>
 *
 * <p>The generated set is <b>MERGED</b> into the report's existing custom headers, not substituted —
 * a receipt on a conversation that already carries custom headers must keep them.
 *
 * <h2>What is NOT built here, and why</h2>
 *
 * <p>Google Messages mints the signature with two dedicated FFI verbs
 * ({@code generate_{delivery,display}_receipt_mls_message}) over the metadata protos below.
 * <b>What the signature actually covers is not statically recoverable</b> — not the signed input, not
 * the label, nothing beyond "base64 of the blob at field 1" (NEEDS-CAPTURE §22.1-41).
 *
 * <p>So the protos are built here because their field numbers ARE proven and a capture will need
 * something to compare against, while the signature itself continues to come from our RCC.16 §7.6.3
 * {@link VerifiableDerivedContent} construction. That is a spec-derived signature over spec-derived
 * content; it is not a guess at Google Messages'. Stating the difference matters: if a capture shows the
 * derivations differ, what changes is the signer, not this class.
 *
 * <h2>What is settled, and what is still open</h2>
 *
 * <p><b>The signed INPUT is settled. The TRANSFORM is not.</b> That split is the whole state of
 * §22.1-41 now, and conflating them would make this look finished when it is not.
 *
 * <p><b>SETTLED — the input.</b> The Java hands the FFI <b>one buffer</b>: the serialized
 * {@code GenerateDeliveryReceiptRequest{1: RequestContext, 2: metadata}}, with <b>nothing wrapped
 * around it</b> and no second argument. The {@code RequestContext} is itself
 * {@code {serialized MlsContext, group_id, message_id, + one optional extra binding behind a flag}}.
 * Recorded here because whoever implements the generate verb needs it and it is not in §12.11.
 *
 * <p><b>SETTLED — our field numbers, all of them.</b> The delivery metadata decodes
 * to exactly what is built below: {@code 1 version, 2 status, 3 message_id, 4|5 the failure-reason
 * oneof, 6 original_message_id}. So {@code original_message_id} at <b>6</b> on the delivery side is
 * right, and the reading of 4/5 as the oneof is right.
 *
 * <p><b>One refinement:</b> fields 3 and 6 are declared <b>bytes</b>, not string. We are already
 * correct — {@link #deliveryReceiptMetadata} writes raw UTF-8 at wire type 2, which <i>is</i>
 * {@code bytes} — and the distinction is wire-identical for UTF-8 anyway. It is noted because a
 * future move to generated code would have to choose, and choosing {@code string} there would be
 * wrong even though it round-trips today.
 *
 * <p><b>STILL OPEN — the transform.</b> Whether the engine prepends a label or context string before
 * signing, and whether it signs the metadata's serialized bytes or re-derives from its fields,
 * happens <b>entirely inside</b> the native verb. There is no label literal on the Java side and no
 * second buffer, so statics cannot answer it. <b>The one-receipt empirical test is now the right
 * call</b>.
 *
 * <h2>The signer requires a group; the validator tolerates its absence</h2>
 *
 * <p>A real asymmetry, and a design fact rather than an accident. In Google Messages' signing op the group
 * is written <b>unconditionally</b> — a null group would NPE — while the inbound <i>validating</i>
 * op guards it with a null check. So <b>Google Messages' outbound receipt signer structurally cannot
 * produce a receipt without a group</b>, while its validator accepts one that arrives without.
 *
 * <p>Our posture already matches: {@code imdnStampsFor} returns null when there is no MLS group and
 * explicitly refuses to fall back to the 1:1 for a group receipt. Do not
 * "improve" that into a best-effort unbound receipt — it would be a receipt Google Messages' own signer
 * could not have produced.
 */
public final class MlsReceiptMetadata {

    private MlsReceiptMetadata() {}

    /** The MLS CPIM header namespace — the same one {@link MlsHeaderGate} admits on. */
    public static final String NS = MlsHeaderGate.MLS_NAMESPACE;

    public static final String HDR_SIGNATURE = MlsHeaderGate.HDR_DERIVED_CONTENT_SIGNATURE;
    public static final String HDR_ERA_ID = MlsHeaderGate.HDR_ERA_ID;
    public static final String HDR_ORIGINAL_MESSAGE_ID = MlsHeaderGate.HDR_ORIGINAL_MESSAGE_ID;
    /** Present for the assertion that it is NEVER emitted on a receipt. */
    public static final String HDR_EPOCH_AUTHENTICATOR = MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR;

    /**
     * The receipt kinds, by Google Messages' ordinal (0..3).
     *
     * <p><b>{@link #DELIVERY_FAILED} shares {@link #DELIVERY}'s handler.</b> §12.11 flags it
     * explicitly ("the SAME handler — note this"), and it is the arm most likely to be given its own
     * branch by someone reading the enum rather than the dispatch.
     */
    public enum ReceiptType {
        /** Ordinal 0. Google Messages THROWS on it — it is not a receipt kind, it is the absence of one. */
        UNKNOWN_RECEIPT_TYPE(0),
        /** Ordinal 1 — the delivery verb. */
        DELIVERY(1),
        /** Ordinal 2 — the display verb. */
        DISPLAYED(2),
        /** Ordinal 3 — routed to {@link #DELIVERY}'s handler, not to one of its own. */
        DELIVERY_FAILED(3);

        public final int ordinalValue;

        ReceiptType(final int ordinalValue) { this.ordinalValue = ordinalValue; }

        /** Whether this kind is signed by the DISPLAY verb (as opposed to the delivery one). */
        public boolean usesDisplayVerb() { return this == DISPLAYED; }

        public static ReceiptType fromOrdinal(final int o) {
            for (final ReceiptType t : values()) {
                if (t.ordinalValue == o) return t;
            }
            return UNKNOWN_RECEIPT_TYPE;
        }
    }

    /**
     * Google Messages' verbatim refusal for the zero ordinal.
     *
     * @throws IllegalArgumentException always, for {@link ReceiptType#UNKNOWN_RECEIPT_TYPE}
     */
    public static void requireSignableReceiptType(final ReceiptType t) {
        if (t == null || t == ReceiptType.UNKNOWN_RECEIPT_TYPE) {
            throw new IllegalArgumentException("Unsupported receipt type: " + t);
        }
    }

    // ---- the metadata protos (field numbers per §12.11) --------------------------------------

    /** {@code DeliveryReceiptMetadata.status}: delivered. */
    public static final int STATUS_DELIVERED = 1;
    /** {@code DeliveryReceiptMetadata.status}: failed. */
    public static final int STATUS_FAILED = 2;
    /** Both metadata protos write {@code version = 1}. */
    public static final int VERSION = 1;

    /**
     * {@code DeliveryReceiptMetadata { 1 version, 2 status, 3 message_id, 6 original_message_id }}.
     *
     * <p><b>Fields 4 and 5 are a failure-reason oneof the OUTBOUND path never populates</b> — which
     * is why {@code original_message_id} is 6 and not 4. Encoding it at 4 would produce a message
     * that parses into the failure oneof.
     */
    public static byte[] deliveryReceiptMetadata(final int status, final String messageId,
            final String originalMessageId) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeVarintField(out, 1, VERSION);
        writeVarintField(out, 2, status);
        writeBytesField(out, 3, messageId);
        if (includeOriginalMessageId(messageId, originalMessageId)) {
            writeBytesField(out, 6, originalMessageId);
        }
        return out.toByteArray();
    }

    /**
     * {@code DisplayReceiptMetadata { 1 version, 2 status, 3 message_id, 4 original_message_id }}.
     *
     * <p>{@code original_message_id} is <b>4</b> here and <b>6</b> in the delivery metadata. The two
     * protos are not the same shape and there is no failure arm on this one — copying the delivery
     * numbering across is the mistake available here.
     */
    public static byte[] displayReceiptMetadata(final String messageId,
            final String originalMessageId) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeVarintField(out, 1, VERSION);
        writeVarintField(out, 2, STATUS_DELIVERED);   // §12.11: display metadata writes status 1
        writeBytesField(out, 3, messageId);
        if (includeOriginalMessageId(messageId, originalMessageId)) {
            writeBytesField(out, 4, originalMessageId);
        }
        return out.toByteArray();
    }

    /**
     * The <b>PROTO</b> omit rule: {@code original_message_id} is set only when present <b>and
     * different from the receipt's own message id</b>.
     *
     * <p>⚠ <b>This is NOT the header rule, and conflating them is a mistake I made writing this
     * class.</b> §12.11 states two different conditions in two different places:
     *
     * <ul>
     *   <li><b>metadata proto</b> — "set only when present <i>and different from the receipt's own
     *       message id</i>";</li>
     *   <li><b>CPIM header</b> — "UTF-8, <i>only when non-empty</i>". No equality test.</li>
     * </ul>
     *
     * <p>They are genuinely different: the proto is signed content where a self-referential id is
     * meaningless, while the header is a routing hint the peer reads pre-decrypt to resolve the
     * original message. Applying the proto's stricter rule to the header suppresses it exactly when
     * a receipt's own id happens to equal the id it acknowledges — which is the ordinary case on our
     * receipt path, where the receipt carries no separate id of its own. Doing that would have
     * removed the header entirely while looking like it implemented it.
     *
     * @see #includeOriginalMessageIdHeader
     */
    public static boolean includeOriginalMessageId(final String messageId,
            final String originalMessageId) {
        return originalMessageId != null
                && !originalMessageId.isEmpty()
                && !originalMessageId.equals(messageId);
    }

    /** The <b>HEADER</b> omit rule: non-empty. No equality test. See {@link #includeOriginalMessageId}. */
    public static boolean includeOriginalMessageIdHeader(final String originalMessageId) {
        return originalMessageId != null && !originalMessageId.isEmpty();
    }

    // ---- the header set -----------------------------------------------------------------------

    /** One CPIM header: namespace, name, value. */
    public static final class Header {
        public final String namespace;
        public final String name;
        public final String value;

        public Header(final String namespace, final String name, final String value) {
            this.namespace = namespace;
            this.name = name;
            this.value = value;
        }

        @Override public boolean equals(final Object o) {
            if (!(o instanceof Header)) return false;
            final Header h = (Header) o;
            return namespace.equals(h.namespace) && name.equals(h.name) && value.equals(h.value);
        }

        @Override public int hashCode() {
            return (namespace + '\0' + name + '\0' + value).hashCode();
        }

        @Override public String toString() { return namespace + ' ' + name + '=' + value; }
    }

    /**
     * The receipt's MLS headers, <b>in Google Messages' order</b>: signature, era, original-id.
     *
     * <p>Returns an empty list when there is no signature — a receipt without one is refused by a
     * Google Messages peer anyway, and emitting the other two would produce a report that looks MLS-tagged
     * while being undeliverable. The caller decides whether to send it unsigned; this does not
     * decide for it by half-tagging.
     *
     * <p><b>{@code Epoch-Authenticator} is deliberately absent and there is no parameter for it.</b>
     * §12.11 says it is never emitted on a receipt. Not having the parameter is the point: the bug
     * this replaced came from reusing the CONTENT send's builder, where it is mandatory.
     *
     * <p>{@code Original-Message-ID} uses the HEADER rule — <b>non-empty, with no equality test</b>
     * — not the proto's stricter one. See {@link #includeOriginalMessageId}.
     *
     * @param signatureB64 base64 of the signature blob; null/empty yields an empty list
     * @param eraId        the era, as a decimal string
     */
    public static List<Header> headers(final String signatureB64, final long eraId,
            final String originalMessageId) {
        final List<Header> out = new ArrayList<>(3);
        if (signatureB64 == null || signatureB64.isEmpty()) return out;
        out.add(new Header(NS, HDR_SIGNATURE, signatureB64));
        out.add(new Header(NS, HDR_ERA_ID, Long.toString(eraId)));
        // ORIGINAL-MESSAGE-ID IS NOT EMITTED ON A RECEIPT — confirmed on the wire.
        //
        // The reading below (header rule = "only when non-empty", no equality test) is a faithful
        // read of §12.11 and it is WRONG for this message class. Two observations settled it:
        //
        //   A real RECEIPT, captured: two headers only — MLS-Derived-Content-Signature
        //     and Era-ID. No Original-Message-ID. The id it acknowledges rides in the IMDN XML body.
        //   TWO GOOGLE MESSAGES PEERS TALKING TO EACH OTHER: the receiving side's receipt logs
        //     original_rcs_message_id="RcsMessageIdType:null" and the sender rates it
        //     'IMDN process result: NO_OP' -> 'PostProcess positive delivery receipt result is
        //     SUCCESS'. OUR receipt, on the same path with this header present, logs a populated
        //     original_rcs_message_id and is rated FAILED_MESSAGE -> FAIL_NO_RETRY.
        //
        // So the header is not merely redundant here — its presence puts Google Messages on a different and
        // failing branch. That is a positive control (a working receipt on the same code path) plus
        // a negative, which is stronger than either alone and is why this overrides the spec read.
        //
        // §7.2.3 supports the narrower scope independently: Original-Message-Id is "the id of the
        // message a RESEND/DERIVED message refers to". A delivery receipt is neither.
        //
        // The helpers are KEPT: includeOriginalMessageId (the proto rule, with its equality test) is
        // still correct for the signed content, and the header helper still describes §12.11 for any
        // class that genuinely takes it. What changed is that a RECEIPT is not such a class.
        return out;
    }

    /**
     * <b>MERGE</b> the generated headers into a report's existing custom headers — §12.11, not a
     * substitution.
     *
     * <p>Existing headers are kept and come FIRST; a generated header replaces an existing one with
     * the same {@code (namespace, name)} rather than duplicating it, because two headers with one
     * name is a shape the reader has no rule for.
     */
    public static List<Header> merge(final List<Header> existing, final List<Header> generated) {
        final List<Header> out = new ArrayList<>();
        if (existing != null) {
            for (final Header e : existing) {
                boolean replaced = false;
                if (generated != null) {
                    for (final Header g : generated) {
                        if (g.namespace.equals(e.namespace) && g.name.equals(e.name)) {
                            replaced = true;
                            break;
                        }
                    }
                }
                if (!replaced) out.add(e);
            }
        }
        if (generated != null) out.addAll(generated);
        return out;
    }

    // ---- minimal protobuf writers -------------------------------------------------------------

    private static void writeVarintField(final java.io.ByteArrayOutputStream out, final int field,
            final long value) {
        writeVarint(out, ((long) field << 3));       // wire type 0
        writeVarint(out, value);
    }

    private static void writeBytesField(final java.io.ByteArrayOutputStream out, final int field,
            final String utf8) {
        final byte[] b = utf8 == null ? new byte[0]
                : utf8.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(out, ((long) field << 3) | 2);   // wire type 2
        writeVarint(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeVarint(final java.io.ByteArrayOutputStream out, final long v) {
        long x = v;
        while ((x & ~0x7FL) != 0) {
            out.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
        out.write((int) x);
    }
}
