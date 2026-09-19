/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * RCC.16 §7.10.4 {@code EpochIdentifier}: {@code era_id = 1} (uint32), {@code epoch_id = 2}
 * (uint64), {@code epoch_authenticator = 3} (bytes). The epoch restarts with each era, and the
 * authenticator is the part peers key on, so all three always travel together. Also the request
 * body of the enhanced GroupInfo pull (RCC.16 §7.10.2).
 */
public final class RccEpochIdentifier {

    /** RCC.16 §7.10.2 request content type; not the {@code -client} variant. */
    public static final String CONTENT_TYPE = "message/mls-rcs";

    public final long eraId;
    public final long epochId;
    public final byte[] epochAuthenticator;

    public RccEpochIdentifier(final long eraId, final long epochId,
            final byte[] epochAuthenticator) {
        this.eraId = eraId;
        this.epochId = epochId;
        this.epochAuthenticator = epochAuthenticator == null
                ? new byte[0] : epochAuthenticator.clone();
    }

    /** Zero-valued fields are omitted, per proto3. */
    public byte[] encode() {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
            if (eraId != 0) RccProto.varint(out, 1, eraId);
            if (epochId != 0) RccProto.varint(out, 2, epochId);
            if (epochAuthenticator.length > 0) RccProto.bytes(out, 3, epochAuthenticator);
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Parse, or {@code null} if the bytes are unusable. An all-default message parses to zeros. */
    public static RccEpochIdentifier parse(final byte[] proto) {
        if (proto == null) return null;
        try {
            final byte[] auth = RccProto.field(proto, 3);
            return new RccEpochIdentifier(
                    RccProto.varintField(proto, 1),
                    RccProto.varintField(proto, 2),
                    auth == null ? new byte[0] : auth);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Compares authenticators when both carry one; otherwise falls back to {@code (era, epoch)},
     * which some callers supply without an authenticator.
     */
    public boolean sameEpochAs(final RccEpochIdentifier other) {
        if (other == null) return false;
        if (epochAuthenticator.length > 0 && other.epochAuthenticator.length > 0) {
            return Arrays.equals(epochAuthenticator, other.epochAuthenticator);
        }
        return eraId == other.eraId && epochId == other.epochId;
    }

    /** True iff this identifier names nothing — all fields default. */
    public boolean isEmpty() {
        return eraId == 0 && epochId == 0 && epochAuthenticator.length == 0;
    }

    @Override public String toString() {
        return "EpochIdentifier{era=" + eraId + " epoch=" + epochId
                + " auth=" + epochAuthenticator.length + "B}";
    }
}
