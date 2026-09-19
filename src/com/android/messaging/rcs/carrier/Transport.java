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
package com.android.messaging.rcs.carrier;

/**
 * Mode-agnostic message transport. The OTT path (self-hosted SIP) and the
 * carrier-IMS path (own ImsService or vendor SipDelegate) both implement this
 * so the UI / storage / codec layers don't have to know which one is active.
 */
public interface Transport {

    enum RegistrationState { UNREGISTERED, REGISTERING, REGISTERED, FAILED }

    interface Listener {
        /** Inbound message arrived. {@code messageId} may be null. */
        void onIncomingMessage(String fromUri, String body, String messageId);

        /**
         * Inbound message with an E2EE provenance tag — {@code e2eeSchemeId} is the opaque
         * scheme it was decrypted under (e.g. {@code gsma.rcs-e2ee.mls}), or {@code null} for plaintext.
         * Default drops the tag to the 3-arg form; the carrier hops override it to carry the schemeId
         * all the way to {@code RcsIncomingMessage.e2eeSchemeId} (which lights the per-message padlock).
         */
        default void onIncomingMessage(String fromUri, String body, String messageId,
                String e2eeSchemeId) {
            onIncomingMessage(fromUri, body, messageId);
        }

        /**
         * Inbound message arrived as RAW BYTES — binary-safe. Used for MSRP-session
         * CPIM bodies, which may wrap a BINARY inner content-type ({@code message/mls} MLSMessage
         * ciphertext/Welcome) that a UTF-8 String round-trip would corrupt. Default routes to the
         * String form (UTF-8) for legacy consumers; the carrier transport overrides it to keep bytes.
         *
         * <p>This is the TRANSPORT ENVELOPE hop — {@code body} is a still-wrapped
         * {@code message/cpim} payload that the consumer parses. It is NOT
         * {@link #onIncomingContent}, which carries the final content of one message.
         */
        default void onIncomingBytes(String fromUri, byte[] body, String messageId) {
            onIncomingMessage(fromUri,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId);
        }

        /**
         * Inbound message CONTENT — the final bytes of one message plus the content type that says
         * how to render them. This is the binary-safe counterpart of
         * {@link #onIncomingMessage(String, String, String, String)}, and the only inbound form that
         * can carry anything that is not valid UTF-8 text.
         *
         * <p><b>Why it exists.</b> The MSRP legs already carry the CIPHERTEXT as bytes
         * ({@link #onIncomingBytes}) precisely because a String round trip corrupts an MLS
         * Welcome — and then the carrier leg re-introduced exactly that corruption one hop later,
         * on the DECRYPTED PLAINTEXT, because this interface had no way to carry bytes or a content
         * type past the decrypt. {@code new String(p, UTF_8)} replaces every byte sequence that is
         * not valid UTF-8 with U+FFFD and the original bytes never come back, so inline media over
         * carrier MLS was impossible and even a text frame's QUIC var-int length prefix could carry
         * a byte &gt; 0x7F and be mangled before anything parsed it.
         *
         * <p><b>{@code contentType} is the REAL inner type</b> ({@code text/plain}, {@code
         * image/jpeg}, {@code message/imdn+xml} …), already unframed by whoever produced it — see
         * the ownership rule on {@code ReceiveRcsMessageAction}. Nothing
         * downstream re-derives it.
         *
         * <p>The default below is DELIBERATELY the lossy String form: it keeps every existing
         * implementer (tests, legacy listeners) compiling, and it is correct for the text-only
         * consumers that never see anything else. Every hop on the carrier inbound chain overrides
         * it — a hop that forgets to is a hop that silently corrupts binary again.
         */
        default void onIncomingContent(String fromUri, byte[] body, String contentType,
                String messageId, String e2eeSchemeId) {
            onIncomingMessage(fromUri,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId, e2eeSchemeId);
        }

        /** Outbound message status change. {@code errorReason} carries a
         *  free-form diagnostic when {@code status==FAILED} (e.g. gRPC
         *  code + tachyonerror name); null otherwise. */
        void onMessageStatus(String messageId,
                com.android.messaging.rcs.carrier.Message.Status status,
                String errorReason);

        /** Registration / connection state change. */
        void onRegistrationStateChanged(RegistrationState state, String reason);
    }

    /** Attempt to register / connect. Idempotent. */
    void start();

    /** Tear down. Idempotent. */
    void stop();

    /** Send a 1-1 message. {@code messageId} must be non-null and unique. */
    void sendMessage(String toUri, String body, String messageId);

    void setListener(Listener listener);

    RegistrationState getRegistrationState();
}
