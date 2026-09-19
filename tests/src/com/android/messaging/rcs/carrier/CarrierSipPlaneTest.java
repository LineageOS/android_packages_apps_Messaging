/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The carrier SIP network plane chosen from its configured string. Covers the decision only; that
 * {@link CarrierSipRegistrar} consults it for the network request and the P-CSCF is not tested
 * here. See docs/rcs/carrier-transport.md.
 */
public class CarrierSipPlaneTest {

    @Test
    public void theImsPdnPlaneBindsTheImsPdnAndTrustsPco() {
        assertTrue(CarrierSipPlane.IMS_PDN.usesImsPdn());
        assertTrue(CarrierSipPlane.IMS_PDN.pcoPcscfIsAuthoritative());
    }

    /** Without an IMS PDN, a PCO address left by another bearer is the wrong proxy. */
    @Test
    public void theCarrierPlaneUsesTheDefaultBearerAndRefusesPco() {
        assertFalse(CarrierSipPlane.CARRIER_DEFAULT_BEARER.usesImsPdn());
        assertFalse(CarrierSipPlane.CARRIER_DEFAULT_BEARER.pcoPcscfIsAuthoritative());
    }

    @Test
    public void theTwoPlanesDisagreeOnBothQuestions() {
        assertFalse("a plane that skips the IMS PDN cannot trust a PCO address from it",
                CarrierSipPlane.IMS_PDN.usesImsPdn()
                        == CarrierSipPlane.CARRIER_DEFAULT_BEARER.usesImsPdn());
        assertFalse(CarrierSipPlane.IMS_PDN.pcoPcscfIsAuthoritative()
                == CarrierSipPlane.CARRIER_DEFAULT_BEARER.pcoPcscfIsAuthoritative());
    }

    @Test
    public void theCarrierPlaneIsSelectedOnlyByItsOwnName() {
        for (final String v : new String[] {"carrier", "CARRIER", "Carrier", " carrier ",
                "\tcarrier\n"}) {
            assertEquals("case and surrounding whitespace must not defeat the selection: '" + v
                    + "'", CarrierSipPlane.CARRIER_DEFAULT_BEARER, CarrierSipPlane.fromString(v));
        }
    }

    /** The documented default value selects the IMS PDN plane. */
    @Test
    public void theImsValueSelectsTheImsPdn() {
        for (final String v : new String[] {"ims", "IMS", " ims "}) {
            assertEquals(v, CarrierSipPlane.IMS_PDN, CarrierSipPlane.fromString(v));
        }
    }

    /**
     * An unrecognised value selects the default plane, so a typo cannot move a device onto the
     * other, and a device still holding an earlier plane name keeps the default.
     */
    @Test
    public void anythingElseIsTheShippedPlane() {
        for (final String v : new String[] {
                null, "", "   ", "imss", "carriers", "carr", "default", "true", "1"}) {
            assertEquals("unrecognised '" + v + "' must not select the newer plane",
                    CarrierSipPlane.IMS_PDN, CarrierSipPlane.fromString(v));
        }
    }
}
