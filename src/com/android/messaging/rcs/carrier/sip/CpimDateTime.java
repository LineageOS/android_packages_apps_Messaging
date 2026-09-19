/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RFC 3339 timestamps for the CPIM {@code DateTime} header and the IMDN {@code <datetime>}
 * element: {@code Z} for UTC, {@code ±hh:mm} otherwise. A new {@code SimpleDateFormat} per call,
 * since it is not thread-safe.
 */
public final class CpimDateTime {

    private CpimDateTime() {}

    /** The current time in UTC. */
    public static String now() {
        return format(new Date(), TimeZone.getTimeZone("UTC"));
    }

    public static String formatUtc(long epochMillis) {
        return format(new Date(epochMillis), TimeZone.getTimeZone("UTC"));
    }

    /** Formats {@code date} in {@code tz}; the offset carries a colon, as RFC 3339 requires. */
    public static String format(Date date, TimeZone tz) {
        SimpleDateFormat sdf = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        sdf.setTimeZone(tz);
        return sdf.format(date);
    }
}
