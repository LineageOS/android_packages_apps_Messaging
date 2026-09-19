/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * What the receiver does with a message it decrypted and refused. A refusal is not a decrypt
 * failure: it puts nothing on the wire, repairs nothing and counts toward nothing, for every
 * {@link Reason}. An FTD would be false and, for impersonation, addressed to the victim; a heal or
 * rebuild credit would let any member force repairs with forged envelopes. The decrypt path returns
 * an internal marker content type instead of null, which the router classifies
 * {@link RccContentDisposition#DROP_CONTROL}.
 */
public final class MlsInboundRefusal {

    private MlsInboundRefusal() { }

    /** Why an inbound message that decrypted was refused. */
    public enum Reason {
        /**
         * The AAD's {@code message_id} does not equal the transport's (RCC.16 §7.5.3.1). Most
         * likely a framing fault; at worst a replay.
         */
        AAD_MESSAGE_ID_MISBINDING,

        /**
         * The envelope names one sender and the signing leaf is certified as another: an
         * authenticated member lying about who it is.
         */
        SENDER_IMPERSONATION
    }

    // Exhaustive switches, so a new Reason without a decision throws and the tests over values()
    // name it.

    /** Whether this refusal may emit an RCC.16 §7.7.2.2 negative delivery report; never. */
    public static boolean reportsToSender(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /** Whether this refusal may self-heal; never, since the decrypt shows our state is in step. */
    public static boolean healsGroup(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * Whether this refusal advances the non-convergence run that ends in a rebuild; never, or any
     * member could force a rebuild with three envelopes in another's name.
     */
    public static boolean countsTowardRebuild(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return false;
            case SENDER_IMPERSONATION:      return false;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * Whether the decision is stored for replay on redelivery; always. Without a row, a redelivery
     * re-enters the ratchet-advancing decrypt and lands on the failure path. Legitimate reuse of a
     * refused id is not reachable: ids are client-generated UUIDs.
     */
    public static boolean replayable(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return true;
            case SENDER_IMPERSONATION:      return true;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /**
     * The internal content-type marker substituted for the refused body; never on the wire. The
     * constants live in {@link RccContentDisposition}, whose {@code classify} routes them.
     */
    public static String marker(final Reason r) {
        switch (r) {
            case AAD_MESSAGE_ID_MISBINDING: return RccContentDisposition.REFUSED_AAD_MISBINDING;
            case SENDER_IMPERSONATION:      return RccContentDisposition.REFUSED_IMPERSONATION;
            default: throw new IllegalArgumentException("unclassified refusal: " + r);
        }
    }

    /** The reason a marker content type stands for, or null if this is not a refusal marker. */
    public static Reason forMarker(final String contentType) {
        if (contentType == null) return null;
        final String ct = contentType.trim();
        for (final Reason r : Reason.values()) {
            if (marker(r).equals(ct)) return r;
        }
        return null;
    }

    /** True when this content type is one of the internal refusal markers. */
    public static boolean isRefusal(final String contentType) {
        return forMarker(contentType) != null;
    }

    // Log markers: MlsInvariantScan searches for these exact constants.

    /**
     * The RCC.16 §7.5.3.1 check fired with no evidence the named message reached us. Suspicious,
     * not never: a peer with a framing bug reaches it honestly.
     */
    public static final String MARKER_MISBINDING = "REFUSED: AAD MESSAGE-ID MIS-BINDING";

    /**
     * The same check fired and we provably processed the named id from this peer: a replay under a
     * new transport id. Emitted instead of {@link #MARKER_MISBINDING}, never alongside it. A
     * rendezvous miss proves nothing, so it falls back to the weaker marker.
     */
    public static final String MARKER_REPLAY =
            "REFUSED: CIPHERTEXT REPLAYED UNDER A NEW MESSAGE-ID";

    /** Impersonation: a member sent a message under another member's name. Scanned as never. */
    public static final String MARKER_IMPERSONATION = "is impersonating another one";

    /**
     * The RCC.16 §7.5.3.1 refusal line.
     *
     * @param provenReplay the AAD's id has a stored decrypt result from this peer
     */
    public static String misbindingLine(final String messageId, final String fromE164,
            final String aadMessageId, final boolean provenReplay) {
        return "REFUSING " + MlsMessageId.forLog(messageId) + " from " + LogMask.number(fromE164)
                + " — "
                + (provenReplay ? MARKER_REPLAY : MARKER_MISBINDING)
                + ". Its AAD names message_id '" + MlsMessageId.forLog(aadMessageId)
                + "' (§7.5.3.1 requires equality with the transport's)"
                + (provenReplay
                        ? ", and we have ALREADY PROCESSED a message under that id from this peer, so"
                          + " these bytes are being re-presented to us under a new id"
                        : "; we hold no stored result for that id, so this is a mis-binding or a"
                          + " replay we cannot prove")
                + ". THE MESSAGE DECRYPTED — this is a REFUSAL, not a crypto failure. Nothing goes "
                + "out: no FTD, no receipt, no §10 recovery, no rebuild credit.";
    }

    /** The impersonation refusal line, naming the envelope identity and the certified signer. */
    public static String impersonationLine(final String messageId, final String fromE164,
            final String signerMsisdn) {
        return "REFUSING " + MlsMessageId.forLog(messageId) + " — the envelope says it is from "
                + LogMask.number(fromE164) + " but the MLS leaf that SIGNED it is certified as "
                + LogMask.number(signerMsisdn)
                + ". THE MESSAGE DECRYPTED, so the signer really is a member of this group — it "
                + MARKER_IMPERSONATION + ". Attributing it to " + LogMask.number(fromE164)
                + " is exactly the attack "
                + "this check exists to stop. Nothing goes out: reporting would send a "
                + "'could not decrypt' to " + LogMask.number(fromE164)
                + ", who never sent this and cannot resend "
                + "it, and healing would spend the repair budget on a group that is demonstrably in "
                + "step.";
    }
}
