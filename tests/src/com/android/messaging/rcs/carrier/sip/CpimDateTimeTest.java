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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.junit.Test;

/** Unit tests for {@link CpimDateTime} RFC 3339 formatter. */
public class CpimDateTimeTest {

    @Test
    public void formatUtc_fixedEpoch_producesExpectedString() {
        // 2024-01-15T12:34:56.789Z = 1705322096789 ms since epoch.
        // Build a Date in UTC from a Calendar to avoid relying on TimeZone.default.
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2024, Calendar.JANUARY, 15, 12, 34, 56);
        c.set(Calendar.MILLISECOND, 789);
        long epoch = c.getTimeInMillis();
        String s = CpimDateTime.formatUtc(epoch);
        assertEquals("2024-01-15T12:34:56.789Z", s);
    }

    @Test
    public void format_nonUtcTimeZone_includesOffsetWithColon() {
        // 2024-01-15T12:34:56.000Z formatted in a UTC+05:30 zone =
        // 2024-01-15T18:04:56.000+05:30
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2024, Calendar.JANUARY, 15, 12, 34, 56);
        c.set(Calendar.MILLISECOND, 0);
        String s = CpimDateTime.format(new Date(c.getTimeInMillis()),
                TimeZone.getTimeZone("GMT+05:30"));
        // The trailing offset must include a colon per RFC 3339.
        assertEquals("2024-01-15T18:04:56.000+05:30", s);
    }

    @Test
    public void now_emitsRfc3339ShapedString() {
        String s = CpimDateTime.now();
        // YYYY-MM-DDTHH:MM:SS.sssZ — 24 chars total, ending in Z.
        assertEquals("length 24", 24, s.length());
        assertTrue("ends with Z", s.endsWith("Z"));
        assertEquals("date separator", '-', s.charAt(4));
        assertEquals("date separator", '-', s.charAt(7));
        assertEquals("date/time separator", 'T', s.charAt(10));
        assertEquals("time separator", ':', s.charAt(13));
        assertEquals("time separator", ':', s.charAt(16));
        assertEquals("ms separator",   '.', s.charAt(19));
    }
}
