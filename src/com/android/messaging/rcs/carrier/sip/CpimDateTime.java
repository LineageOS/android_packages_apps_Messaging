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
package com.android.messaging.rcs.carrier.sip;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RFC 3339 date-time formatter used by CPIM {@code DateTime} headers and
 * IMDN {@code <datetime>} XML elements. Format:
 *
 * <pre>
 *   YYYY-MM-DDTHH:MM:SS.sssZ           (UTC)
 *   YYYY-MM-DDTHH:MM:SS.sss+HH:MM      (offset)
 * </pre>
 *
 * <p>Google Messages emits the {@code Z} form for
 * UTC and the {@code ±HH:MM} form for local-time stamps. We use UTC by
 * default since carrier interop expects timestamps to be unambiguous;
 * callers needing a local-offset stamp can supply their own {@link Date}
 * and {@link TimeZone}.
 *
 * <p>Pure Java — no Android, no Java 8 java.time (we still target API 21
 * for the host-side build via {@code SimpleDateFormat}, which is available
 * everywhere). Not thread-safe in the same sense {@code SimpleDateFormat}
 * isn't; we synthesize a new instance per call instead of caching.
 */
public final class CpimDateTime {

    private CpimDateTime() {}

    /** Current wall-clock time in UTC, formatted per RFC 3339. */
    public static String now() {
        return format(new Date(), TimeZone.getTimeZone("UTC"));
    }

    /** Convenience: format the supplied epoch-millis as UTC. */
    public static String formatUtc(long epochMillis) {
        return format(new Date(epochMillis), TimeZone.getTimeZone("UTC"));
    }

    /**
     * Format a {@link Date} using the supplied {@link TimeZone}. Emits the
     * canonical RFC 3339 {@code YYYY-MM-DDTHH:MM:SS.sssZ} form for UTC and
     * {@code …±HH:MM} for other zones (note the colon — RFC 3339 differs
     * from RFC 822/ISO 8601 basic which uses no colon).
     */
    public static String format(Date date, TimeZone tz) {
        SimpleDateFormat sdf = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        sdf.setTimeZone(tz);
        return sdf.format(date);
    }
}
