/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;

import java.io.IOException;
import org.junit.Test;

/**
 * A 401 carries WWW-Authenticate and is answered with Authorization; a proxy's 407 carries
 * Proxy-Authenticate and is answered with Proxy-Authorization (RFC 3261 §22.3). A proxy does not
 * read Authorization, so a 407 answered with it is challenged again until the retries run out.
 * {@code CarrierSipRegistrar} is JAIN-SIP and Android bound, so its half is a source scan.
 */
public final class CarrierSipChallengeTest {

    private static final String REGISTRAR =
            "src/com/android/messaging/rcs/carrier/CarrierSipRegistrar.java";

    private static String registrar() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(REGISTRAR));
        assertTrue("CarrierSipRegistrar not found or empty — this guard is scanning nothing",
                code.contains("class CarrierSipRegistrar"));
        return code;
    }

    @Test
    public void theStatusNamesTheChallengeHeader() {
        assertFalse(SipDigestAuth.isProxyChallenge(401));
        assertEquals("WWW-Authenticate", SipDigestAuth.challengeHeaderName(401));
        assertTrue(SipDigestAuth.isProxyChallenge(407));
        assertEquals("Proxy-Authenticate", SipDigestAuth.challengeHeaderName(407));
    }

    @Test
    public void aProxyChallengeIsAnsweredWithProxyAuthorization() throws IOException {
        assertTrue("CarrierSipRegistrar never creates a Proxy-Authorization header, so a 407 is "
                + "answered with Authorization, which the proxy does not read",
                SourceScan.count(registrar(), "createProxyAuthorizationHeader(") >= 1);
    }

    @Test
    public void theChallengeHeaderIsChosenByTheStatus() throws IOException {
        final String body = SourceScan.bodyOf(registrar(), "handleResponse");
        assertTrue("CarrierSipRegistrar.handleResponse not found — this guard is scanning nothing",
                body.length() > 0);
        assertTrue("handleResponse does not pick the challenge header by status through "
                + "SipDigestAuth.challengeHeaderName, whose choice is tested here",
                body.contains("SipDigestAuth.challengeHeaderName("));
        assertTrue("handleResponse does not remember whether the challenge was a proxy's, so the "
                + "de-REGISTER that reuses it cannot answer in the right header",
                body.contains("SipDigestAuth.isProxyChallenge("));
    }

    /** Both the REGISTER and the de-REGISTER answer through the one status-keyed choice. */
    @Test
    public void bothRegistrationsAnswerThroughTheChoice() throws IOException {
        final String code = registrar();
        final String choice = SourceScan.bodyOf(code, "credentialsHeader");
        assertTrue("credentialsHeader not found — this guard is scanning nothing",
                choice.length() > 0);
        assertTrue(choice.contains("createProxyAuthorizationHeader(")
                && choice.contains("createAuthorizationHeader("));
        for (final String m : new String[] {"Request buildRegister(", "Request buildDeregister("}) {
            final String body = SourceScan.bodyOfDeclaredAs(code, m);
            assertTrue(m + " not found — this guard is scanning nothing", body.length() > 0);
            assertTrue(m + " answers a challenge without credentialsHeader, so its header does "
                    + "not follow the challenge's status", body.contains("credentialsHeader("));
        }
    }
}
