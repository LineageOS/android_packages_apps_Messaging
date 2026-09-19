/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.telephony.SmsMessage;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an inbound SMS is an RCS provisioning OTP that the app should consume (not show)
 * and pass to the provider with {@code submitOtp}. Two arms: a port-addressed data SMS, and a plain
 * SMS whose body matches the labelled pattern. The pattern is conservative (a labelled 6-8 digit
 * code) so ordinary texts containing numbers are not swallowed. Stateless.
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

    // An OTP label near a 6-8 digit run. The keywords are matched against real provisioning texts;
    // each one occurs in some carrier's message.
    private static final Pattern OTP_BODY = Pattern.compile(
            "(?i)(?:verification|verify|rcs|otp|code|jibe|messages)\\D{0,20}(\\d{6,8})"); // pubscan: allow

    // A bare 6-8 digit body, only for the data-SMS arm, where the port already shows intent.
    private static final Pattern BARE_DIGITS = Pattern.compile("^\\s*(\\d{6,8})\\s*$");

    /**
     * Matches an OTP in a stored SMS body (the inbox observer fallback). Labelled pattern only,
     * since a stored row has the full text. Returns the 6-8 digit code, or {@code null}.
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
     * @param messages the reassembled parts of the inbound SMS
     * @return a Match if this is a provisioning OTP to consume, else null
     */
    @Nullable
    public static Match tryMatch(final SmsMessage[] messages, final int subId) {
        if (messages == null || messages.length == 0 || messages[0] == null) {
            return null;
        }

        // Arm (a): a port-addressed data SMS (port >= 0).
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

        // Arm (b): plain SMS body match.
        final Matcher m = OTP_BODY.matcher(body);
        if (m.find()) {
            return new Match(m.group(1));
        }
        return null;
    }

    private static int safePort(final SmsMessage msg) {
        // SmsMessage exposes no destination port; the data-SMS arm arrives through
        // DATA_SMS_RECEIVED, handled in RcsOtpReceiver, so arm (a) is never taken here.
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
