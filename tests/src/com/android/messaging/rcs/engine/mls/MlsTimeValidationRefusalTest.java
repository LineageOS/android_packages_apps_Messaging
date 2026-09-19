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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Half two — the refusal names an MSISDN and the remedy did not read it.
 *
 * <p>Both samples below are VERBATIM from {@code deviceB}, 2026-09-10, three minutes apart,
 * through the real UI. They are the whole evidence base for the finding that the
 * server validates member credentials at {@code now + 30 days}, so they are pinned here rather than
 * paraphrased.
 */
public class MlsTimeValidationRefusalTest {

    /** Sample 1 — a REMOVE. Device instant 1789020709; server validated at 1791612709. */
    private static final String REMOVE_REFUSAL =
            "FAIL PERMISSION_DENIED: Time-related validation error: client "
            + "\"42274bf1-064b-4678-8a6a-c24f65829b8b\" with MSISDN \"+15715550104\" error: "
            + "Validation error: Validity { not_before: 2026-07-23 04:25:14 (1784780714), "
            + "not_after: 2026-10-06 03:25:14 (1791257114) } with participant signature validity "
            + "Some(Validity { not_before: 2026-07-23 03:25:16, not_after: 2026-10-06 15:25:16 }) "
            + "is not valid at time: LoggedMlsTime { epoch_seconds: 1791612709, date: "
            + "\"2026-10-10 06:11:49\" }";

    /** The device's own clock at sample 1. */
    private static final long DEVICE_NOW_1 = 1789020709L;

    @Test
    public void itReadsTheMsisdnTheServerNamed() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertEquals("+15715550104", r.msisdn);
        assertEquals("42274bf1-064b-4678-8a6a-c24f65829b8b", r.clientId);
        assertEquals(1791257114L, r.notAfterSecs);
        assertEquals(1791612709L, r.validatedAtSecs);
    }

    /**
     * <b>THE ARITHMETIC, as a check rather than a note.</b> 1791612709 - 1789020709 =
     * 2,592,000 s = exactly 30 days. The device clock was not wrong ({@code auto_time=1}, and two
     * other attached devices read the same wall clock): the server validates at {@code now + 30d}
     * because that is how it enforces a REMAINING-lifetime rule with an ABSOLUTE comparison.
     */
    @Test
    public void theServerValidatesExactlyThirtyDaysAhead() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertEquals(2592000L, r.validationSkewSecs(DEVICE_NOW_1));
        assertEquals(30L, r.validationSkewSecs(DEVICE_NOW_1) / 86400L);
        assertTrue("and it matches the floor we enforce on peers",
                r.skewMatchesFloor(DEVICE_NOW_1, MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS));
        assertFalse("...and is NOT some other number",
                r.skewMatchesFloor(DEVICE_NOW_1, 45L));
    }

    /**
     * THE DEFECT. On 010T the number named was a PEER's while our own certificate had 73.7 days
     * left, and the remedy re-minted ours anyway.
     */
    @Test
    public void itDistinguishesOurNumberFromAPeers() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertFalse("this refusal is about a PEER, not about 010T",
                r.namesUs("+15715550107"));
        assertTrue(r.namesUs("+15715550104"));
        assertTrue("a bare national number is the same number", r.namesUs("5715550104"));
        assertTrue("and so is one written without the plus", r.namesUs("15715550104"));
    }

    /** No MSISDN extracted must never read as "it is ours" — the safe direction. */
    @Test
    public void anUnattributableRefusalDoesNotNameUs() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(
                "FAIL PERMISSION_DENIED: Time-related validation error: the certificate is not "
                + "valid at this time");
        assertNotNull("the marker phrase alone is enough to recognise it", r);
        assertEquals("", r.msisdn);
        assertFalse(r.namesUs("+15715550107"));
        assertFalse("nor may it claim to name anybody else", r.namesUs(""));
        assertEquals("an absent instant is 0, not a parse failure", 0L, r.validatedAtSecs);
        assertEquals("and there is no skew to report", 0L, r.validationSkewSecs(DEVICE_NOW_1));
    }

    /** Anything that is not this refusal parses to null — most refusals are something else. */
    @Test
    public void otherRefusalsAreNotThisOne() {
        assertNull(MlsTimeValidationRefusal.parse(null));
        assertNull(MlsTimeValidationRefusal.parse(""));
        assertNull(MlsTimeValidationRefusal.parse(
                "FAIL: mlsError={1=2 1001{1=6 2=5 3=1 }} Commit was from era 6 epoch 5"));
        assertNull(MlsTimeValidationRefusal.parse("Era changed from 6 to 5"));
    }

    /**
     * The prose past the MSISDN is the SERVER's and has been reworded before, so a reworded sentence
     * must still yield the number. This is the reason the fields are extracted independently rather
     * than by one whole-sentence pattern.
     */
    @Test
    public void aRewordedRefusalStillYieldsTheNumber() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(
                "Time-related validation error: MSISDN \"+15715550104\" is outside its validity");
        assertNotNull(r);
        assertEquals("+15715550104", r.msisdn);
        assertEquals("nothing was claimed about a window that was not stated", 0L, r.notAfterSecs);
    }

    /** {@code namesUs} against an empty self-number must be false, not a match on everything. */
    @Test
    public void anUnknownSelfNumberMatchesNobody() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertFalse(r.namesUs(""));
        assertFalse(r.namesUs(null));
    }
}
