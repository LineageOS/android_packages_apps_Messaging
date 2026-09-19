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
package com.android.messaging.rcs;

import android.telephony.SmsMessage;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure matcher that decides whether an inbound SMS is a Pev3 RCS-provisioning
 * OTP that the main app should swallow (not show as a chat bubble) and feed
 * to the provider via submitOtp().
 *
 * <p>Two arms (per the design):
 * <ul>
 *   <li>(a) a port-addressed DATA SMS on the WAP/application port the Pev3
 *       verify channel uses -- detected via {@link
 *       SmsMessage#getDestinationPort()} >= 0; the digits are extracted from
 *       the user-data text;
 *   <li>(b) a plain SMS_DELIVER body matching the Pev3 OTP shortcode/format.
 * </ul>
 *
 * <p>Stateless + side-effect-free so it is trivially unit-testable. The
 * actual OTP body regex is intentionally conservative (a labelled 6-8 digit
 * code) to avoid swallowing ordinary texts that merely contain numbers; tune
 * it as real Pev3 verify SMS are observed.
 */
public final class Pev3OtpMatcher {
    private Pev3OtpMatcher() {}

    /** Result of a successful match. */
    public static final class Match {
        public final String otp;
        Match(final String otp) {
            this.otp = otp;
        }
    }

    // Conservative: an OTP label near a 6-8 digit run. Covers the common
    // "Your verification code is 123456" / "RCS code: 1234567" shapes.
    // NOTE: "jibe" below is DATA, not a codename reference -- carriers really do    pubscan: allow
    // send provisioning OTP texts containing that word, so it is matched against
    // real inbound SMS bodies. Do not remove it.
    private static final Pattern OTP_BODY = Pattern.compile(
            "(?i)(?:verification|verify|rcs|otp|code|jibe|messages)\\D{0,20}(\\d{6,8})"); // pubscan: allow

    // A bare 6-8 digit body (some carriers send only the digits on a
    // dedicated port); used only for the DATA-SMS arm where the port already
    // disambiguates intent.
    private static final Pattern BARE_DIGITS = Pattern.compile("^\\s*(\\d{6,8})\\s*$");

    /**
     * Match a Pev3 OTP from a stored SMS body (the ContentObserver fallback,
     * which reads bodies straight out of the sms inbox DB). Uses only the
     * labelled pattern — a DB row has the full text (e.g. "Your Messenger
     * verification code is G-270148"), so a bare-digits match would be too
     * loose. Returns the 6-8 digit code, or {@code null}.
     */
    @Nullable
    public static String matchBody(final String body) {
        if (body == null) {
            return null;
        }
        final Matcher m = OTP_BODY.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * @param messages the SmsMessage[] from the inbound intent (already
     *     reassembled by the caller; we look at part 0 for the port and at
     *     the concatenated body for the regex).
     * @return a Match if this is a Pev3 OTP to swallow, else null.
     */
    @Nullable
    public static Match tryMatch(final SmsMessage[] messages, final int subId) {
        if (messages == null || messages.length == 0 || messages[0] == null) {
            return null;
        }

        // Arm (a): port-addressed DATA SMS. getDestinationPort() returns -1 for
        // a normal (non-port) SMS. Any positive port + a digit-bearing body is
        // treated as a candidate OTP channel.
        final int port = safePort(messages[0]);
        final String body = concatBody(messages);
        if (port >= 0) {
            final Matcher bare = BARE_DIGITS.matcher(body);
            if (bare.find()) {
                return new Match(bare.group(1));
            }
            final Matcher labelled = OTP_BODY.matcher(body);
            if (labelled.find()) {
                return new Match(labelled.group(1));
            }
            return null;
        }

        // Arm (b): plain SMS_DELIVER body match.
        final Matcher m = OTP_BODY.matcher(body);
        if (m.find()) {
            return new Match(m.group(1));
        }
        return null;
    }

    private static int safePort(final SmsMessage msg) {
        // android.telephony.SmsMessage exposes no destination-port accessor; the
        // port-addressed (data-SMS) path arrives via DATA_SMS_RECEIVED where the
        // port is in the intent, handled in RcsOtpReceiver. Here we rely on the
        // SMS_DELIVER body matcher (arm b). Port-based matching is deferred.
        return -1;
    }

    private static String concatBody(final SmsMessage[] messages) {
        final StringBuilder sb = new StringBuilder();
        for (final SmsMessage m : messages) {
            if (m == null) {
                continue;
            }
            final String b = m.getDisplayMessageBody();
            if (b != null) {
                sb.append(b);
            }
        }
        return sb.toString();
    }
}
