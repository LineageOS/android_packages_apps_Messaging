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
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host tests for the carrier SIP network-plane profile.
 *
 * <p>{@link CarrierSipRegistrar} itself is not host-testable — JAIN-SIP, {@code ConnectivityManager}
 * and {@code SystemProperties} all the way down. What IS testable is the DECISION the registrar
 * makes from a configured string, which is why {@link CarrierSipPlane#fromString} takes a String
 * instead of reading the sysprop itself. Stated plainly rather than implied: <b>these tests cover
 * the profile's semantics, not its wiring.</b> That the registrar consults the plane at the two
 * places it must — the network request and the P-CSCF accessor — is not asserted here and needs a
 * device.
 */
public class CarrierSipPlaneTest {

    @Test
    public void labBindsTheImsPdnAndTrustsPco() {
        assertTrue(CarrierSipPlane.LAB_IMS_PDN.usesImsPdn());
        assertTrue(CarrierSipPlane.LAB_IMS_PDN.pcoPcscfIsAuthoritative());
    }

    /**
     * On the carrier-default plane there is no IMS PDN, so a PCO address
     * — if some other bearer left one behind — is the WRONG proxy, not a better one.
     */
    @Test
    public void theCarrierPlaneUsesTheDefaultBearerAndRefusesPco() {
        assertFalse(CarrierSipPlane.CARRIER_DEFAULT_BEARER.usesImsPdn());
        assertFalse(CarrierSipPlane.CARRIER_DEFAULT_BEARER.pcoPcscfIsAuthoritative());
    }

    @Test
    public void theTwoPlanesDisagreeOnBothQuestions() {
        assertFalse("a plane that skips the IMS PDN cannot trust a PCO address from it",
                CarrierSipPlane.LAB_IMS_PDN.usesImsPdn()
                        == CarrierSipPlane.CARRIER_DEFAULT_BEARER.usesImsPdn());
        assertFalse(CarrierSipPlane.LAB_IMS_PDN.pcoPcscfIsAuthoritative()
                == CarrierSipPlane.CARRIER_DEFAULT_BEARER.pcoPcscfIsAuthoritative());
    }

    @Test
    public void theCarrierPlaneIsSelectedOnlyByItsOwnName() {
        for (final String v : new String[] {"carrier", "CARRIER", "Carrier", " carrier ", "\tcarrier\n"}) {
            assertEquals("case and surrounding whitespace must not defeat the selection: '" + v + "'",
                    CarrierSipPlane.CARRIER_DEFAULT_BEARER, CarrierSipPlane.fromString(v));
        }
    }

    /**
     * <b>The rule that matters, and the reason the parse is pure.</b> An unrecognised value falls
     * back to the SHIPPED plane, not the newer one — so a typo, a stale sysprop or an empty read
     * cannot silently move a device onto a plane nobody chose. Asserting it here makes it a test
     * rather than a sentence in a javadoc, which is the distinction this project has spent the day
     * on.
     */
    @Test
    public void anythingElseIsTheShippedPlane() {
        for (final String v : new String[] {
                null, "", "   ", "lab", "LAB", "carriers", "carr", "default", "ims", "true", "1"}) {
            assertEquals("unrecognised '" + v + "' must not select the newer plane",
                    CarrierSipPlane.LAB_IMS_PDN, CarrierSipPlane.fromString(v));
        }
    }
}
