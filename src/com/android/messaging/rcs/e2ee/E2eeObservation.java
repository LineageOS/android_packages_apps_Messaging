/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * Decides what one observed message changes: the row's scheme stamp and the conversation's
 * Scytale bit. The provider-layer padlock follows traffic the provider reports, never a
 * prediction. Pure, so it is host-tested.
 */
public final class E2eeObservation {
    private E2eeObservation() {}

    /** Which transport produced a send status. */
    public enum Source { PROVIDER, CARRIER }

    /** A change to one conversation bit. */
    public enum BitChange { NONE, SET, CLEAR }

    /** What to write. {@code rowScheme} applies only when {@code rewriteRow} is true. */
    public static final class Outcome {
        public static final Outcome NOTHING =
                new Outcome(false, null, BitChange.NONE, false);

        public final boolean rewriteRow;
        public final String rowScheme;
        public final BitChange scytale;
        public final boolean setMls;

        Outcome(final boolean rewriteRow, final String rowScheme, final BitChange scytale,
                final boolean setMls) {
            this.rewriteRow = rewriteRow;
            this.rowScheme = rowScheme;
            this.scytale = scytale;
            this.setMls = setMls;
        }
    }

    /**
     * A terminal status for one of our sends.
     *
     * @param sent      the status is STATUS_SENT; the scheme means nothing on any other status
     * @param scheme    the scheme the provider applied on the wire, null for plaintext or unknown
     * @param rowScheme the row's current stamp; an MLS stamp records a sealed send and is kept
     */
    public static Outcome forSentStatus(final Source source, final boolean sent,
            final String scheme, final boolean isGroup, final String rowScheme) {
        if (!sent || source != Source.PROVIDER) return Outcome.NOTHING;
        if (RcsE2eeScheme.MLS.equals(rowScheme) || RcsE2eeScheme.MLS.equals(scheme)) {
            return Outcome.NOTHING;
        }
        if (scheme != null) {
            final BitChange bit = !isGroup && RcsE2eeScheme.ETOUFFEE.equals(scheme)
                    ? BitChange.SET : BitChange.NONE;
            return new Outcome(!scheme.equals(rowScheme), scheme, bit, false);
        }
        return new Outcome(rowScheme != null, null,
                isGroup ? BitChange.NONE : BitChange.CLEAR, false);
    }

    /** An inbound message tagged with the scheme it was decrypted under (null for plaintext). */
    public static Outcome forInbound(final String scheme, final boolean isGroup) {
        final BitChange bit = !isGroup && RcsE2eeScheme.ETOUFFEE.equals(scheme)
                ? BitChange.SET : BitChange.NONE;
        final boolean mls = RcsE2eeScheme.MLS.equals(scheme);
        if (bit == BitChange.NONE && !mls) return Outcome.NOTHING;
        return new Outcome(false, null, bit, mls);
    }

    /** {@code bits} with {@code o}'s conversation changes applied. */
    public static EncryptionProtocolBits apply(final EncryptionProtocolBits bits,
            final Outcome o) {
        EncryptionProtocolBits out = bits;
        if (o.scytale == BitChange.SET) out = out.accumulate(true, false);
        if (o.scytale == BitChange.CLEAR) out = out.withScytaleCleared();
        if (o.setMls) out = out.accumulate(false, true);
        return out;
    }
}
