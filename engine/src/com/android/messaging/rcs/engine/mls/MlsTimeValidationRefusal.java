/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's "Time-related validation error", parsed for whose credential it names. Each field is
 * extracted independently and an absent one reads as empty or {@code 0}, so a reworded refusal
 * still yields the MSISDN. See docs/mls/credentials.md.
 */
public final class MlsTimeValidationRefusal {

    private static final String MARKER = "Time-related validation error";

    private static final Pattern MSISDN =
            Pattern.compile("MSISDN\\s+\"(\\+?[0-9]{4,20})\"");
    private static final Pattern CLIENT_ID =
            Pattern.compile("client\\s+\"([0-9a-fA-F-]{8,64})\"");
    private static final Pattern NOT_AFTER =
            Pattern.compile("not_after:[^(]*\\((\\d{9,12})\\)");
    private static final Pattern AT_TIME =
            Pattern.compile("epoch_seconds:\\s*(\\d{9,12})");

    /** The MSISDN the server named, or "" if the sentence did not carry one. */
    public final String msisdn;
    /** The client (registration) UUID the server named, or "". */
    public final String clientId;
    /** The refused credential's {@code notAfter} in epoch seconds, or 0 if not stated. */
    public final long notAfterSecs;
    /** The instant the server validated at, in epoch seconds, or 0 if not stated. */
    public final long validatedAtSecs;

    private MlsTimeValidationRefusal(final String msisdn, final String clientId,
            final long notAfterSecs, final long validatedAtSecs) {
        this.msisdn = msisdn;
        this.clientId = clientId;
        this.notAfterSecs = notAfterSecs;
        this.validatedAtSecs = validatedAtSecs;
    }

    /**
     * Parse a server refusal detail. {@code null} means "not a credential-validity refusal", never
     * "no MSISDN was named".
     */
    public static MlsTimeValidationRefusal parse(final String detail) {
        if (detail == null || !detail.contains(MARKER)) return null;
        return new MlsTimeValidationRefusal(group(MSISDN, detail), group(CLIENT_ID, detail),
                number(NOT_AFTER, detail), number(AT_TIME, detail));
    }

    /**
     * Whether the server named our number; {@code false} when no MSISDN was extracted, so an
     * unattributable refusal never triggers a re-mint of ours. Compared on trailing digits.
     */
    public boolean namesUs(final String ourE164) {
        return !msisdn.isEmpty() && sameNumber(msisdn, ourE164);
    }

    /**
     * How far ahead of {@code deviceNowSecs} the server validated.
     *
     * @return the skew in seconds, or 0 when the refusal did not state an instant
     */
    public long validationSkewSecs(final long deviceNowSecs) {
        return validatedAtSecs == 0L ? 0L : validatedAtSecs - deviceNowSecs;
    }

    /** True when {@link #validationSkewSecs} is within a minute of the stated floor. */
    public boolean skewMatchesFloor(final long deviceNowSecs, final long floorDays) {
        if (validatedAtSecs == 0L) return false;
        return Math.abs(validationSkewSecs(deviceNowSecs) - floorDays * 86400L) <= 60L;
    }

    /**
     * Digits-only equality on the last 10 digits, tolerant of a leading {@code +} or country code.
     */
    static boolean sameNumber(final String a, final String b) {
        final String x = digits(a);
        final String y = digits(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        final int n = Math.min(10, Math.min(x.length(), y.length()));
        return x.regionMatches(x.length() - n, y, y.length() - n, n);
    }

    private static String digits(final String s) {
        if (s == null) return "";
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static String group(final Pattern p, final String s) {
        final Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static long number(final Pattern p, final String s) {
        final Matcher m = p.matcher(s);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (final NumberFormatException e) {
            return 0L;
        }
    }

    @Override public String toString() {
        return "timeValidationRefusal[msisdn=" + (msisdn.isEmpty() ? "?" : LogMask.number(msisdn))
                + " client=" + (clientId.isEmpty() ? "?" : clientId)
                + " notAfter=" + notAfterSecs + " validatedAt=" + validatedAtSecs + "]";
    }
}
