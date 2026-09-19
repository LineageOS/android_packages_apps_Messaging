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

/**
 * The inbound MLS header contract — Era-ID and Epoch-Authenticator are REQUIRED (rework item 6.2,
 * invariant 51).
 *
 * <p>Google Messages drops an MLS message that is missing either, silently: a log line, no receipt, no
 * recovery. Our carrier leg does this; our <b>Tachyon leg — the primary transport — does not</b>, so
 * every inbound MLS body there is currently accepted unframed.
 *
 * <h2>Where the values live, because it is not where you would guess</h2>
 *
 * <p>Google Messages does NOT read these off dedicated MLS wire fields. They arrive in a <b>generic
 * namespaced header map</b> — the same structure that carries reactions, replies, captions, edits
 * and profile updates — under the namespace {@code http://www.gsma.com/rcs/mls}. MLS is one more
 * namespace in that set, not a container of its own.
 *
 * <p>That is worth stating plainly because it is why searching for a sibling field of
 * {@code inner_payload} finds nothing: the values were never there — established from Google
 * Messages' own map types and namespace enumeration.
 *
 * <h2>Order matters and is Google Messages'</h2>
 *
 * <p>Content-type gate, then <b>Era-ID, then Epoch-Authenticator</b>. Each failure has its own
 * message. Checking them in the other order, or reporting one message for both, makes a header
 * regression undiffable against a Google Messages trace — which is the whole reason to mirror the strings.
 *
 * <h2>Encodings</h2>
 *
 * <p>The map is {@code map<string, string>}, so there is no raw-bytes option at this layer:
 * <b>Era-ID is DECIMAL ASCII</b> (not a varint — an easy and silent mistake, since it is a varint
 * almost everywhere else in this codebase) and <b>Epoch-Authenticator is BASE64</b> of the 32-byte
 * value.
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
         * Google Messages' log line for this verdict on the APPLICATION plane, verbatim.
         *
         * <p>Mirrored deliberately: the §20.3 trace diff compares our logcat against a real
         * capture, and a paraphrase breaks the comparison exactly when a header regression is what
         * you are trying to find.
         *
         * @deprecated for CONTROL-plane callers — use {@link #logLine(boolean)}. Kept unqualified
         *     because the application plane is the majority caller and this is Google Messages' wording
         *     there; see that method for why the distinction is not cosmetic.
         */
        public String logLine() { return logLine(/*control=*/ false); }

        /**
         * Google Messages' log line for this verdict, verbatim — and there are TWO of them.
         *
         * <p><b>Google Messages emits a DIFFERENT string on each plane</b>, from two different processors,
         * and this class shipped only one of them. Read off the shipping app, both versions:
         *
         * <pre>
         *   CONTROL      "Received an MLS CONTROL MESSAGE without Era-ID header.
         *                 Dropping message."
         *   APPLICATION  "Received an MLS message without Era-ID header.
         *                 Dropping message."
         * </pre>
         *
         * <p>That correction matters twice over. It retires the earlier reading that the
         * drop guards are control-only — the application-path guard is real, so the failure class is
         * live on both planes. And it means our control arm was emitting the APPLICATION wording:
         * a control-plane header drop read, in a diff against a Google Messages trace, as an
         * application-plane one. Which is precisely the comparison this class exists to keep honest.
         *
         * <p>The two processors also differ in what they GATE ON, and a caller should know which it
         * is: the APPLICATION processor runs only for {@code message/mls} and
         * {@code message/mls-rcs-server}
         * (notably NOT {@code message/mls-rcs-server-kick}, which takes another processor entirely),
         * and reads CPIM headers; the CONTROL processor runs on the inbox control plane, which
         * carries no
         * content type, and reads the era and epoch authenticator out of the
         * {@code ApplyMlsControlMessage} envelope instead.
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
     * Apply the gate.
     *
     * <p>Order is Google Messages': Era-ID first. A caller must DROP on anything but
     * {@link Verdict#ACCEPT} — no receipt, no FTD, no health request. The silence is the contract:
     * a message we cannot place an epoch for is one we cannot answer about either.
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
     * Parse an Era-ID.
     *
     * <p><b>Decimal ASCII, not a varint.</b> Nearly every other integer on this wire is varint-coded,
     * so reaching for a varint decoder here is the natural mistake and it fails silently — a varint
     * read of {@code "19"} yields the byte {@code 0x31}, a plausible-looking era that is wrong.
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
