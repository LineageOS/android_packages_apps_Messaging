/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A credential-validity refusal names an MSISDN, which may be a peer's. The samples are verbatim
 * server refusals. See docs/mls/credentials.md.
 */
public class MlsTimeValidationRefusalTest {

    /** A remove refused; device clock 1789020709, server validated at 1791612709. */
    private static final String REMOVE_REFUSAL =
            "FAIL PERMISSION_DENIED: Time-related validation error: client "
            + "\"42274bf1-064b-4678-8a6a-c24f65829b8b\" with MSISDN \"+15715550104\" error: "
            + "Validation error: Validity { not_before: 2026-07-23 04:25:14 (1784780714), "
            + "not_after: 2026-10-06 03:25:14 (1791257114) } with participant signature validity "
            + "Some(Validity { not_before: 2026-07-23 03:25:16, not_after: 2026-10-06 15:25:16 }) "
            + "is not valid at time: LoggedMlsTime { epoch_seconds: 1791612709, date: "
            + "\"2026-10-10 06:11:49\" }";

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

    /** The server enforces a remaining-lifetime rule by validating at {@code now + 30 d}. */
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

    /** A refusal naming a peer must not trigger a re-mint of our own certificate. */
    @Test
    public void itDistinguishesOurNumberFromAPeers() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertFalse("this refusal is about a PEER, not about us",
                r.namesUs("+15715550107"));
        assertTrue(r.namesUs("+15715550104"));
        assertTrue("a bare national number is the same number", r.namesUs("5715550104"));
        assertTrue("and so is one written without the plus", r.namesUs("15715550104"));
    }

    /** No MSISDN extracted never reads as ours. */
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

    @Test
    public void otherRefusalsAreNotThisOne() {
        assertNull(MlsTimeValidationRefusal.parse(null));
        assertNull(MlsTimeValidationRefusal.parse(""));
        assertNull(MlsTimeValidationRefusal.parse(
                "FAIL: mlsError={1=2 1001{1=6 2=5 3=1 }} Commit was from era 6 epoch 5"));
        assertNull(MlsTimeValidationRefusal.parse("Era changed from 6 to 5"));
    }

    /** Fields are extracted independently, so reworded server prose still yields the number. */
    @Test
    public void aRewordedRefusalStillYieldsTheNumber() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(
                "Time-related validation error: MSISDN \"+15715550104\" is outside its validity");
        assertNotNull(r);
        assertEquals("+15715550104", r.msisdn);
        assertEquals("nothing was claimed about a window that was not stated", 0L, r.notAfterSecs);
    }

    @Test
    public void anUnknownSelfNumberMatchesNobody() {
        final MlsTimeValidationRefusal r = MlsTimeValidationRefusal.parse(REMOVE_REFUSAL);
        assertNotNull(r);
        assertFalse(r.namesUs(""));
        assertFalse(r.namesUs(null));
    }
}
