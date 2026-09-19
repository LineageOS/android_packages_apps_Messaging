/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

/** A 1:1 message transport, independent of how the line registers. */
public interface Transport {

    enum RegistrationState { UNREGISTERED, REGISTERING, REGISTERED, FAILED }

    interface Listener {
        /** {@code messageId} may be null. */
        void onIncomingMessage(String fromUri, String body, String messageId);

        /**
         * As the three-argument form, plus the E2EE scheme the body arrived under, or null for
         * plaintext. The carrier hops override it so the scheme reaches
         * {@code RcsIncomingMessage}.
         */
        default void onIncomingMessage(String fromUri, String body, String messageId,
                String e2eeSchemeId) {
            onIncomingMessage(fromUri, body, messageId);
        }

        /**
         * A still-wrapped {@code message/cpim} envelope as bytes, which may carry a binary inner
         * type. The default decodes it as UTF-8; the carrier transport
         * overrides it.
         */
        default void onIncomingBytes(String fromUri, byte[] body, String messageId) {
            onIncomingMessage(fromUri,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId);
        }

        /**
         * The final content of one message and its real, already unframed content type; the only
         * inbound form that can carry bytes that are not UTF-8 text. The default is the lossy
         * String form, for text-only listeners; every hop on the carrier inbound chain must
         * override it.
         */
        default void onIncomingContent(String fromUri, byte[] body, String contentType,
                String messageId, String e2eeSchemeId) {
            onIncomingMessage(fromUri,
                    body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    messageId, e2eeSchemeId);
        }

        /** {@code errorReason} is a diagnostic when the status is {@code FAILED}, else null. */
        void onMessageStatus(String messageId,
                com.android.messaging.rcs.carrier.Message.Status status,
                String errorReason);

        void onRegistrationStateChanged(RegistrationState state, String reason);
    }

    /** Idempotent. */
    void start();

    /** Idempotent. */
    void stop();

    /** {@code messageId} must be non-null and unique. */
    void sendMessage(String toUri, String body, String messageId);

    void setListener(Listener listener);

    RegistrationState getRegistrationState();
}
