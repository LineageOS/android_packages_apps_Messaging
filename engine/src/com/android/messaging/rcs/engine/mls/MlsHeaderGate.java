/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The inbound MLS header contract: Era-ID and Epoch-Authenticator are required, and a message
 * missing either is dropped silently. The values arrive in the generic namespaced header map under
 * {@link #MLS_NAMESPACE}, not in dedicated MLS fields. Era-ID is decimal ASCII (not a varint);
 * Epoch-Authenticator is Base64 of the 32-byte value.
 */
public final class MlsHeaderGate {

    /** The namespace both headers live under. */
    public static final String MLS_NAMESPACE = "http://www.gsma.com/rcs/mls";

    public static final String HDR_ERA_ID = "Era-ID";
    public static final String HDR_EPOCH_AUTHENTICATOR = "Epoch-Authenticator";
    public static final String HDR_ORIGINAL_MESSAGE_ID = "Original-Message-ID";
    public static final String HDR_DERIVED_CONTENT_SIGNATURE = "MLS-Derived-Content-Signature";

    /** The verdict of the gate. */
    public enum Verdict {
        /** Both required headers present and well-formed. Proceed. */
        ACCEPT,
        /** Era-ID absent. Drop silently — no receipt. */
        MISSING_ERA_ID,
        /** Epoch-Authenticator absent. Drop silently — no receipt. */
        MISSING_EPOCH_AUTHENTICATOR,
        /** A required header is present but unparseable. Drop; a malformed value is not a value. */
        MALFORMED;

        public boolean accepted() { return this == ACCEPT; }

        /**
         * The application-plane log line for this verdict, worded as other clients log it so
         * traces can be compared.
         *
         * @deprecated for control-plane callers; use {@link #logLine(boolean)}.
         */
        public String logLine() { return logLine(/*control=*/ false); }

        /**
         * The log line for this verdict on either plane; the control and application processors
         * word it differently. The application plane covers content-typed bodies
         * ({@code message/mls}, {@code message/mls-rcs-server}); the control plane carries no
         * content type and reads the era and authenticator from the control envelope.
         *
         * @param control true for the inbox control plane, false for a content-typed body
         */
        public String logLine(final boolean control) {
            final String subject = control ? "an MLS CONTROL MESSAGE" : "an MLS message";
            switch (this) {
                case MISSING_ERA_ID:
                    return "Received " + subject + " without Era-ID header. Dropping message.";
                case MISSING_EPOCH_AUTHENTICATOR:
                    return "Received " + subject + " without Epoch-Authenticator header. "
                            + "Dropping message.";
                case MALFORMED:
                    return "Received " + subject + " with a malformed required header. "
                            + "Dropping message.";
                case ACCEPT:
                default:
                    return "";
            }
        }
    }

    /** How the caller supplies the namespaced header map, without this module knowing its type. */
    public interface Headers {
        /** @return the value for {@code name} in {@code namespace}, or {@code null} */
        String get(String namespace, String name);
    }

    /**
     * Applies the gate, Era-ID first. On anything but {@link Verdict#ACCEPT} the caller drops the
     * message with no receipt, FTD or health request.
     */
    public static Verdict check(final Headers headers) {
        if (headers == null) return Verdict.MISSING_ERA_ID;
        final String era = headers.get(MLS_NAMESPACE, HDR_ERA_ID);
        if (era == null || era.isEmpty()) return Verdict.MISSING_ERA_ID;
        final String epochAuth = headers.get(MLS_NAMESPACE, HDR_EPOCH_AUTHENTICATOR);
        if (epochAuth == null || epochAuth.isEmpty()) return Verdict.MISSING_EPOCH_AUTHENTICATOR;
        if (parseEraId(era) < 0) return Verdict.MALFORMED;
        return Verdict.ACCEPT;
    }

    /**
     * Parses an Era-ID as decimal ASCII; a varint read of {@code "19"} would yield a plausible
     * wrong era.
     *
     * @return the era, or {@code -1} if it does not parse
     */
    public static int parseEraId(final String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            final int era = Integer.parseInt(value.trim());
            return era < 0 ? -1 : era;
        } catch (final NumberFormatException notANumber) {
            return -1;
        }
    }

    private MlsHeaderGate() {}
}
