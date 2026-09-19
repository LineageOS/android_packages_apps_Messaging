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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's <b>"Time-related validation error"</b>, read for the one thing that matters: <b>WHOSE
 * credential it is about</b>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The refusal names an MSISDN. The client's remedy did not read it — every
 * {@code <expired-credential/>} led to "re-mint OUR identity and republish the pool", which on
 * A device re-minted the one credential that was fine (73.7 days
 * remaining) while the number the server had named belonged to a PEER. The next attempt then failed
 * identically. A credential judgement made without reading which credential the server was talking
 * about is the whole defect, and this is the smallest thing that fixes it.
 *
 * <h2>The measured shape</h2>
 *
 * <pre>
 *   Time-related validation error: client "42274bf1-064b-4678-8a6a-c24f65829b8b" with MSISDN
 *   "+15715550104" error: Validation error: Validity { not_before: 2026-07-23 04:25:14
 *   (1784780714), not_after: 2026-10-06 03:25:14 (1791257114) } with participant signature validity
 *   Some(Validity { not_before: 2026-07-23 03:25:16, not_after: 2026-10-06 15:25:16 }) is not valid
 *   at time: LoggedMlsTime { epoch_seconds: 1791612709, date: "2026-10-10 06:11:49" }
 * </pre>
 *
 * <p><b>Everything past the MSISDN is optional here.</b> The prose is the server's and has been
 * reworded before; a parser that demands the whole sentence fails closed the day a field moves, and
 * failing closed on this one means going back to re-minting the wrong identity. So the MSISDN and
 * the validation instant are extracted independently, and an absent epoch is reported as {@code 0}
 * rather than failing the parse.
 *
 * <p>Pure Java and host-tested: it reads a String and produces values, with no Android in it.
 */
public final class MlsTimeValidationRefusal {

    /** The marker phrase. Present in both samples we hold, and in the RCC.16-conformant wording. */
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
    /** The instant the server validated AT, in epoch seconds, or 0 if not stated. */
    public final long validatedAtSecs;

    private MlsTimeValidationRefusal(final String msisdn, final String clientId,
            final long notAfterSecs, final long validatedAtSecs) {
        this.msisdn = msisdn;
        this.clientId = clientId;
        this.notAfterSecs = notAfterSecs;
        this.validatedAtSecs = validatedAtSecs;
    }

    /**
     * Parse a server refusal detail, or return {@code null} if it is not one of these.
     *
     * <p>Null is the ordinary answer — most refusals are something else entirely — so a caller must
     * treat it as "this was not a credential-validity refusal", never as "no MSISDN was named".
     */
    public static MlsTimeValidationRefusal parse(final String detail) {
        if (detail == null || !detail.contains(MARKER)) return null;
        return new MlsTimeValidationRefusal(group(MSISDN, detail), group(CLIENT_ID, detail),
                number(NOT_AFTER, detail), number(AT_TIME, detail));
    }

    /**
     * Did the server name OUR number?
     *
     * <p>{@code false} when no MSISDN was extracted, which is the safe direction: it routes an
     * un-attributable refusal to "we do not know whose credential this is" rather than to a re-mint
     * of ours. Comparison is on the trailing digits so that {@code +1571…} and {@code 1571…} — both
     * of which the fleet produces — are the same number.
     */
    public boolean namesUs(final String ourE164) {
        return !msisdn.isEmpty() && sameNumber(msisdn, ourE164);
    }

    /**
     * How far ahead of {@code deviceNowSecs} the server validated —
     * measured at exactly 2,592,000 s (30 days) on two independent samples.
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

    /** Digits-only equality on the last 10 digits — tolerant of a leading {@code +} or country code. */
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
        return "timeValidationRefusal[msisdn=" + (msisdn.isEmpty() ? "?" : msisdn)
                + " client=" + (clientId.isEmpty() ? "?" : clientId)
                + " notAfter=" + notAfterSecs + " validatedAt=" + validatedAtSecs + "]";
    }
}
