/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.junit.Test;

/** {@link CpimDateTime}, the RFC 3339 formatter. */
public class CpimDateTimeTest {

    @Test
    public void formatUtc_fixedEpoch_producesExpectedString() {
        // Built in UTC so the default time zone does not matter.
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
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2024, Calendar.JANUARY, 15, 12, 34, 56);
        c.set(Calendar.MILLISECOND, 0);
        String s = CpimDateTime.format(new Date(c.getTimeInMillis()),
                TimeZone.getTimeZone("GMT+05:30"));
        // RFC 3339: the offset has a colon.
        assertEquals("2024-01-15T18:04:56.000+05:30", s);
    }

    @Test
    public void now_emitsRfc3339ShapedString() {
        String s = CpimDateTime.now();
        // Millisecond precision in UTC is 24 characters ending in Z.
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
