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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * RCC.16 <b>§7.10.4</b> — {@code EpochIdentifier}, the three-part name for a point in a group's
 * history.
 *
 * <pre>
 *   message EpochIdentifier {
 *     uint32 era_id             = 1;
 *     uint64 epoch_id           = 2;
 *     bytes  epoch_authenticator = 3;
 *   }
 * </pre>
 *
 * <h2>Why all three fields, when two of them look redundant</h2>
 *
 * An Era advance creates a NEW MLS group, so {@code epoch_id} restarts and is only meaningful
 * within an era — {@code (era_id, epoch_id)} is the coordinate. The {@code epoch_authenticator} is
 * the part that cannot be forged or guessed, and it is the one the protocol actually keys on: this
 * project has already learned the hard way that stamping OUR era where the
 * server's authenticator was wanted produces an accepted-looking message the peer rejects. So all
 * three travel together and the authenticator is never dropped as "derivable".
 *
 * <p>This is also the request body for the Enhanced GroupInfo pull (§7.10.2), where it names the
 * epoch the client is asking to catch up FROM — and §7.10.3 gates the whole
 * {@code committed_control_messages} list on it being present, known to the CF, and one the
 * requester was a participant throughout. That gating is the likely explanation for our own
 * always-empty backfill list (see {@code MlsProviderTransport}'s retracted reading): we never sent
 * an epoch identifier, so the server had nothing to key the backfill on.
 */
public final class RccEpochIdentifier {

    /** §7.10.2 — the content type of the Enhanced pull's request body. NOT {@code -client}. */
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

    /** Serialise. Zero-valued scalars are OMITTED, which is proto3's own encoding rule. */
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
     * Do two identifiers name the SAME epoch?
     *
     * <p>Compares the authenticator when both sides carry one, and falls back to
     * {@code (era, epoch)} only when they do not. The fallback exists because our own transport has
     * historically supplied the flat pair without an authenticator, and refusing to compare at all
     * would make the enhanced self-heal unable to recognise "already up to date" — but the
     * authenticator WINS whenever it is available, because it is the value the protocol keys on.
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
