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
package com.android.messaging.rcs.e2ee;

import java.util.List;
import java.util.Map;

/**
 * The pluggable binding that <b>carries</b> a resolved E2EE scheme over a concrete transport — the
 * seam that lets the same negotiation ({@link E2eeSchemeGate}) drive any of the three provider kinds
 * the E2EE layer was designed for:
 *
 * <ol>
 *   <li><b>transport-provider</b> (e.g. Google Etouffee) — the provider owns the crypto; the binding
 *       is a thin adapter over the provider AIDL. Ciphertext never enters this process.</li>
 *   <li><b>transport-provided MLS</b> (e.g. Tachyon MLS) — MLS crypto in the shared engine, but the
 *       wire rides the provider's Tachyon transport (future binding).</li>
 *   <li><b>app-provided MLS</b> — MLS crypto in the shared {@code OpenMlsSession} engine, carried by
 *       messaging2's OWN carrier CPM/MSRP transport as {@code message/mls[-rcs-*]} CPIM bodies
 *       (RCC.16 §7.9). This is {@code MlsCarrierTransport}.</li>
 * </ol>
 *
 * <p>{@link E2eeSchemeGate} decides <i>which</i> scheme wins per conversation; {@link E2eeTransportRouter}
 * maps that resolved {@code schemeId} onto the binding that carries it. The crypto engine
 * ({@code com.android.messaging.rcs.engine.mls.MlsEngine}) is transport-agnostic and reused unchanged —
 * a binding only owns the <b>transport packing</b> (CPIM content-type, headers, session lifecycle).
 */
public interface E2eeConversationTransport {

    /** The opaque scheme this binding carries ({@link RcsE2eeScheme#MLS} / {@link RcsE2eeScheme#ETOUFFEE}). */
    String schemeId();

    /**
     * Ensure the E2EE session for {@code conversationId} is established and ready to encrypt/decrypt —
     * provisioning, peer key acquisition, group creation, and any transport session setup as the scheme
     * requires. Idempotent; may block on network. Returns {@code false} if the conversation cannot (yet)
     * be secured under this scheme (the caller should fall back per the gate).
     */
    boolean ensureReady(String conversationId, int subId, List<String> peerE164s);

    /** A wire payload to place in a CPIM body: the inner content-type + bytes + any CPIM headers. */
    final class Payload {
        public final String contentType;               // e.g. CpimMessage.CT_MLS
        public final byte[] body;                       // MLSMessage wire bytes
        public final Map<String, String> cpimHeaders;   // e.g. Era-ID / Epoch-Authenticator (nullable)
        public Payload(final String contentType, final byte[] body, final Map<String, String> cpimHeaders) {
            this.contentType = contentType;
            this.body = body;
            this.cpimHeaders = cpimHeaders;
        }
    }

    /**
     * Encrypt {@code plaintext} for {@code conversationId} into a send-ready {@link Payload}, or
     * {@code null} if the session is not ready (caller buffers or falls back). The binding owns the
     * content-type + header stamping.
     */
    Payload encryptForSend(String conversationId, byte[] plaintext);

    /** The result of processing an inbound MLS-plane CPIM body. */
    final class Inbound {
        /** Decrypted application plaintext, or {@code null} if this was pure control (consumed). */
        public final byte[] plaintext;
        /** True if the body was a control message fully handled (join/commit) with no plaintext. */
        public final boolean controlConsumed;
        private Inbound(final byte[] plaintext, final boolean controlConsumed) {
            this.plaintext = plaintext;
            this.controlConsumed = controlConsumed;
        }
        public static Inbound message(final byte[] plaintext) { return new Inbound(plaintext, false); }
        public static Inbound control() { return new Inbound(null, true); }
        public static Inbound ignored() { return new Inbound(null, false); }
    }

    /**
     * Route + process an inbound MLS-plane CPIM body ({@code contentType} one of the {@code message/mls*}
     * family). Control bodies (Welcome/Commit) drive the session (join/process) and return
     * {@link Inbound#control()}; an application body returns {@link Inbound#message(byte[])} with the
     * decrypted plaintext to surface (tagged {@code e2eeSchemeId=schemeId()} at the seam).
     */
    Inbound onInboundCpim(String conversationId, String senderE164, String contentType,
            byte[] payload, String envelopeMessageId);

    /** Release any per-conversation session resources. */
    void close(String conversationId);
}
