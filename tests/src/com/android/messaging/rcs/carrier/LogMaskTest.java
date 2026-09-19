/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.log.LogMask;

import org.junit.Test;

import java.util.Arrays;

/** A masked number keeps its last four digits and nothing else. */
public class LogMaskTest {

    @Test
    public void keepsTheLastFourDigits() {
        assertEquals("***4567", LogMask.number("+15551234567"));
        assertEquals("***4567", LogMask.number("tel:+1-555-123-4567"));
        assertEquals("***4567", LogMask.number("sip:+15551234567@ims.example;user=phone"));
        assertEquals("***4567", LogMask.number("p:+15551234567"));
    }

    @Test
    public void aShortNumberIsHiddenWhole() {
        assertEquals("***", LogMask.number("3538"));
        assertEquals("***", LogMask.number("12"));
        assertEquals("***", LogMask.number("unknown"));
    }

    @Test
    public void nullAndEmptyAreShownAsThemselves() {
        assertEquals("null", LogMask.number(null));
        assertEquals("", LogMask.number(""));
    }

    @Test
    public void aListMasksEachNumber() {
        assertEquals("[***4567, ***0123]",
                LogMask.numbers(Arrays.asList("+15551234567", "+15559870123")));
        assertEquals("[]", LogMask.numbers(Arrays.asList()));
        assertEquals("null", LogMask.numbers(null));
    }
}
