/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;

/**
 * The receiver's ordering for an RCC.16 §10.3 resent message: parse the AAD, find the
 * resent-message component (absent means an ordinary message), check the HMAC field length,
 * evaluate the HMAC, and only then unwrap the inner ciphertext. Only a failed inner unwrap may
 * report; every other outcome is a silent drop, so one resend cannot draw a receipt from every
 * member. Two inputs are not yet known and are seams returning null: where the 64-byte HMAC field
 * sits in the AAD ({@link #resentSelectorField}) and the inner layout ({@link #resentInnerUnwrap}).
 * See docs/mls/health-and-recovery.md.
 */
public final class MlsResendReceive {

    private MlsResendReceive() {}

    /** What the receiver does with a message. */
    public enum Disposition {
        /** No resent-message component: an ordinary application message. */
        NOT_A_RESEND,

        /** No candidate MAC input matched. Silent. */
        NOT_FOR_ME,

        /** The component tag is present but its payload does not parse. Silent. */
        MALFORMED_COMPONENT,

        /** The HMAC field is empty. Silent. */
        EMPTY_HMAC,

        /** The HMAC field is not 64 bytes. Silent. */
        INVALID_HMAC_LENGTH,

        /** The HMAC field's location is unknown, so it cannot be evaluated. Our gap; silent. */
        SELECTOR_UNAVAILABLE,

        /** The MAC verified and we hold no {@link InnerUnwrap}. Our gap; silent. */
        FOR_ME_UNWRAP_UNAVAILABLE,

        /** The MAC verified and the inner unwrap failed; the only disposition that reports. */
        FOR_ME_UNWRAP_FAILED,

        /** The MAC verified and the inner unwrap produced the original plaintext. */
        FOR_ME
    }

    /** Deliver; for {@code FOR_ME} deliver {@link Outcome#inner}, not the wrapper. */
    public static boolean deliver(final Disposition d) {
        return d == Disposition.NOT_A_RESEND || d == Disposition.FOR_ME;
    }

    /**
     * Drop with no wire effect: no receipt, no FTD, no recovery, no health transition. Structural
     * failures are silent too, since every member would reach them; a lost resend resurfaces
     * through the ordinary FTD path.
     */
    public static boolean silentDrop(final Disposition d) {
        return d == Disposition.NOT_FOR_ME
                || d == Disposition.MALFORMED_COMPONENT
                || d == Disposition.EMPTY_HMAC
                || d == Disposition.INVALID_HMAC_LENGTH
                || d == Disposition.SELECTOR_UNAVAILABLE
                || d == Disposition.FOR_ME_UNWRAP_UNAVAILABLE;
    }

    /**
     * May emit a §7.7.2.2 negative receipt; true for exactly one disposition, and a test pins that.
     * The token sent is {@code Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT.forEmit()}, i.e.
     * {@code failed-to-decrypt(4)}: other clients drop a receipt carrying code 6.
     */
    public static boolean reports(final Disposition d) {
        return d == Disposition.FOR_ME_UNWRAP_FAILED;
    }

    /**
     * Unwraps the inner resent message. The 64-byte field is {@code key(32) || tag(32)} with the
     * key in the clear and no key derivation; the inner ciphertext nests the original sender's
     * message.
     */
    public interface InnerUnwrap {
        /**
         * @param selectorField the 64-byte field passed to {@link #evaluate}, not the AAD
         *     component's opaque
         * @return the original message's plaintext, or {@code null} if it cannot be read
         */
        byte[] unwrap(byte[] outerPlaintext, byte[] selectorField);
    }

    /** The decision, plus what is worth logging about how it was reached. */
    public static final class Outcome {
        public final Disposition disposition;
        /** The decoded component, or null. */
        public final MlsResentMessage.Parsed component;
        /** Which named MAC input matched, or null. */
        public final String matchedCandidate;
        /** The original plaintext on {@link Disposition#FOR_ME}; null otherwise. */
        public final byte[] inner;

        Outcome(final Disposition d, final MlsResentMessage.Parsed c, final String cand,
                final byte[] inner) {
            this.disposition = d;
            this.component = c;
            this.matchedCandidate = cand;
            this.inner = inner;
        }

        /** Whether the component was present. */
        public boolean isResend() { return disposition != Disposition.NOT_A_RESEND; }

        @Override public String toString() {
            return disposition
                    + (component == null ? "" : " " + component)
                    + (matchedCandidate == null ? "" : " via '" + matchedCandidate + "'")
                    + (inner == null ? "" : " inner=" + inner.length + "B");
        }
    }

    private static final Outcome ORDINARY =
            new Outcome(Disposition.NOT_A_RESEND, null, null, null);

    /**
     * Runs the receiver ordering over one successfully decrypted application message, before
     * anything else reads the plaintext. A resend's outer message decrypts for every member, so
     * this cannot live on the failure path.
     *
     * @param aadTrailing    {@link MlsAppMessage#aadTrailing} of the inbound AAD
     * @param selectorField  the 64-byte HMAC field, or {@code null} when its location is unknown
     *                       ({@link Disposition#SELECTOR_UNAVAILABLE}); never the component payload
     * @param macCandidates  named candidate MAC inputs; no match is {@link Disposition#NOT_FOR_ME}
     * @param unwrap         null while the inner layout is unknown
     */
    public static Outcome evaluate(final byte[] aadTrailing, final byte[] outerPlaintext,
            final byte[] selectorField, final Map<String, byte[]> macCandidates,
            final InnerUnwrap unwrap) {

        // An unparseable AAD is not evidence of a resend.
        if (aadTrailing == null || aadTrailing.length == 0) return ORDINARY;
        if (aadTrailing[0] == MlsResentMessage.TAG_ABSENT) return ORDINARY;

        final MlsResentMessage.Parsed component = MlsResentMessage.parse(aadTrailing);
        if (component == null || !component.present()) {
            // A non-zero tag whose payload does not decode under any candidate prefix width.
            return new Outcome(Disposition.MALFORMED_COMPONENT, component, null, null);
        }

        // Our own gap, checked before anything that reads as a judgement about the sender.
        if (selectorField == null) {
            return new Outcome(Disposition.SELECTOR_UNAVAILABLE, component, null, null);
        }

        // Length gate, then the MAC; select() enforces the order.
        final MlsResentMessage.Selection sel =
                MlsResentMessage.select(selectorField, macCandidates);
        switch (sel.verdict) {
            case EMPTY:
                return new Outcome(Disposition.EMPTY_HMAC, component, null, null);
            case INVALID_LENGTH:
                return new Outcome(Disposition.INVALID_HMAC_LENGTH, component, null, null);
            case NOT_FOR_ME:
                return new Outcome(Disposition.NOT_FOR_ME, component, null, null);
            case FOR_ME:
            default:
                break;
        }

        // Only now the inner unwrap.
        if (unwrap == null) {
            return new Outcome(Disposition.FOR_ME_UNWRAP_UNAVAILABLE, component, sel.candidate,
                    null);
        }
        byte[] inner = null;
        try {
            inner = unwrap.unwrap(outerPlaintext, selectorField);
        } catch (final RuntimeException e) {
            // A throwing unwrapper is a failed unwrap, not a crashed receive path.
            inner = null;
        }
        return inner == null
                ? new Outcome(Disposition.FOR_ME_UNWRAP_FAILED, component, sel.candidate, null)
                : new Outcome(Disposition.FOR_ME, component, sel.candidate, inner);
    }

    // Log markers: scans match these constants, so rewording moves emitter and scan together.

    /**
     * The {@code Original-Message-ID} header and the AAD component disagree: header says resend
     * while the component is absent (the tag byte {@link MlsResentMessage#TAG_RESENT} is wrong), or
     * the component's opaque is not the header's id. See {@link #crossCheckOriginalMessageId}.
     */
    public static final String MARKER_DISAGREEMENT = "RESEND MARKER DISAGREEMENT";

    /** Whether the component's opaque and the {@code Original-Message-ID} name one message. */
    public enum IdCrossCheck {
        /** No resent-message component in the AAD. */
        NO_COMPONENT,
        /** No {@code Original-Message-ID} header. */
        NO_HEADER,
        /** The opaque is not printable US-ASCII, so it is not an id. */
        PAYLOAD_NOT_TEXT,
        /** Both present and they name the same message. */
        AGREE,
        /** Both present and they name different messages. */
        DISAGREE
    }

    /** Marker for the three-way id log line. */
    public static final String MARKER_ID_CANDIDATES = "RESEND ID CANDIDATES";

    /**
     * Logs three things that could each be called the original message id, labelled: the AAD's
     * {@code message_id}, the outer CPIM {@code Original-Message-ID} and the component's opaque. On
     * a resend the AAD id may be the resend's own new id (RCC.16 §11.1), so the
     * CPIM-versus-component pair is the one that discriminates.
     *
     * @return the line, or null when no id is present
     */
    public static String idCandidateLine(final Outcome o, final String cpimHeader,
            final String aadMessageId) {
        final String opaque = componentOpaqueAsText(o);
        if ((aadMessageId == null || aadMessageId.isEmpty())
                && (cpimHeader == null || cpimHeader.isEmpty())
                && opaque == null) {
            return null;
        }
        return MARKER_ID_CANDIDATES
                + " aad.message_id=" + show(aadMessageId)
                + " cpim.Original-Message-ID=" + show(cpimHeader)
                + " component.opaque=" + show(opaque)
                + " | aad-vs-cpim=" + agreement(aadMessageId, cpimHeader)
                + " aad-vs-component=" + agreement(aadMessageId, opaque)
                + " cpim-vs-component=" + agreement(cpimHeader, opaque)
                + " | NOTE these are THREE LAYERS, not one field; (1) may hold the RESEND's own id"
                + " on a resend (RCC.16 §11.1), so aad-vs-component DIFFER has TWO readings and"
                + " narrows without closing — cpim-vs-component is the discriminating pair."
                + " Field A not shown: body unparseable.";
    }

    /** The component's opaque as ASCII, or null when absent or binary. */
    private static String componentOpaqueAsText(final Outcome o) {
        if (o == null || o.component == null || !o.component.present()
                || o.component.payload == null || o.component.payload.length == 0) {
            return null;
        }
        for (final byte b : o.component.payload) {
            if (b < 0x20 || b > 0x7E) return null;
        }
        return new String(o.component.payload, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String show(final String s) {
        return (s == null || s.isEmpty()) ? "<absent>" : s;
    }

    /** Three-valued: "nothing to compare" does not share a value with "they agree". */
    private static String agreement(final String a, final String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return "N/A";
        return a.equals(b) ? "AGREE" : "DIFFER";
    }

    /**
     * Compares the component's opaque, as US-ASCII, with the outer {@code Original-Message-ID}. A
     * log instrument; {@link #evaluate} does not consult it.
     */
    public static IdCrossCheck crossCheckOriginalMessageId(final Outcome o, final String header) {
        if (o == null || o.component == null || !o.component.present()
                || o.component.payload == null || o.component.payload.length == 0) {
            return IdCrossCheck.NO_COMPONENT;
        }
        if (header == null || header.isEmpty()) return IdCrossCheck.NO_HEADER;
        for (final byte b : o.component.payload) {
            // One non-printable byte means the opaque is not a message id at all.
            if (b < 0x20 || b > 0x7E) return IdCrossCheck.PAYLOAD_NOT_TEXT;
        }
        final String asText =
                new String(o.component.payload, java.nio.charset.StandardCharsets.US_ASCII);
        return header.equals(asText) ? IdCrossCheck.AGREE : IdCrossCheck.DISAGREE;
    }

    /** The component's opaque for a log line: text when it is text, hex otherwise. */
    public static String componentPayloadForLog(final Outcome o) {
        if (o == null || o.component == null || o.component.payload == null) return "<none>";
        final byte[] p = o.component.payload;
        boolean text = p.length > 0;
        for (final byte b : p) if (b < 0x20 || b > 0x7E) { text = false; break; }
        if (text) {
            return "'" + new String(p, java.nio.charset.StandardCharsets.US_ASCII) + "'";
        }
        final StringBuilder sb = new StringBuilder(p.length * 2);
        for (final byte b : p) sb.append(String.format("%02x", b));
        return "0x" + sb;
    }

    /** A present component that does not decode under any candidate prefix width. */
    public static final String MARKER_MALFORMED = "RESEND COMPONENT MALFORMED";

    /** A present component arrived and the HMAC field could not be located; carries its bytes. */
    public static final String MARKER_SELECTOR_UNAVAILABLE = "CANNOT RUN THE SELECTOR";

    /** A resend's MAC matched one of our candidate inputs; identifies the MAC input. */
    public static final String MARKER_FOR_ME = "RESEND IS FOR US";

    /** The log line for a resend that is not ours, worded like other clients' so logs compare. */
    public static String notForMeLine(final String originalMessageId, final String groupId) {
        return "Resent message not for me with original message id "
                + (originalMessageId == null ? "<unknown>" : MlsMessageId.forLog(originalMessageId))
                + " fails HMAC verification, for group: " + (groupId == null ? "<none>" : groupId)
                + " — BENIGN. NOTE this line is downstream of an HMAC MISMATCH; field A only "
                + "disambiguates error-vs-drop and does NOT select (see MlsResentMessage). "
                + "No receipt, no FTD, no recovery, no state change. "
                + "How many members log this per resend is NOT established — N-1 is an "
                + "unproven inference; counting these lines on a real group is the "
                + "open question, so this line must not pre-announce its own answer.";
    }
}
