/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import java.util.List;
import java.util.Map;

/**
 * Carries a resolved E2EE scheme over a concrete transport. {@link E2eeSchemeGate} decides which
 * scheme a conversation uses; a binding owns only the transport packing (CPIM content type,
 * headers, session lifecycle), while the engine stays transport-neutral. The MLS bindings are
 * {@code MlsProviderTransport} and {@code MlsCarrierTransport} (RCC.16 §7.9 bodies). See
 * docs/mls/overview.md.
 */
public interface E2eeConversationTransport {

    /**
     * Make the conversation ready to encrypt and decrypt under this scheme. Idempotent; may block
     * on the network. {@code false} means it cannot be secured yet and the caller falls back per
     * the gate.
     */
    boolean ensureReady(String conversationId, int subId, List<String> peerE164s);

    /** A payload for a CPIM body: inner content type, bytes and any CPIM headers. */
    final class Payload {
        public final String contentType;               // e.g. CpimMessage.CT_MLS
        public final byte[] body;                       // MLSMessage wire bytes
        public final Map<String, String> cpimHeaders;   // Era-ID, Epoch-Authenticator; nullable
        public Payload(final String contentType, final byte[] body,
                final Map<String, String> cpimHeaders) {
            this.contentType = contentType;
            this.body = body;
            this.cpimHeaders = cpimHeaders;
        }
    }

    /** The result of processing an inbound MLS-plane CPIM body. */
    final class Inbound {
        /** Decrypted plaintext, or {@code null} for a control body. */
        public final byte[] plaintext;
        /** True if the body was a control message (join, commit) that was fully handled. */
        public final boolean controlConsumed;
        private Inbound(final byte[] plaintext, final boolean controlConsumed) {
            this.plaintext = plaintext;
            this.controlConsumed = controlConsumed;
        }
        public static Inbound message(final byte[] plaintext) { return new Inbound(plaintext,
                false); }
        public static Inbound control() { return new Inbound(null, true); }
        public static Inbound ignored() { return new Inbound(null, false); }
    }

    /**
     * Process an inbound {@code message/mls*} CPIM body. A control body drives the session and
     * returns {@link Inbound#control()}; an application body returns {@link
     * Inbound#message(byte[])}.
     */
    Inbound onInboundCpim(String conversationId, String senderE164, String contentType,
            byte[] payload, String envelopeMessageId);
}
