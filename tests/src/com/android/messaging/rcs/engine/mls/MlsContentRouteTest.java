/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link MlsContentRoute}: inbound content-type routing, and the throw for an MLS type with no
 * route. See docs/mls/overview.md.
 */
public class MlsContentRouteTest {

    @Test public void nonMlsTypesAreNotOnThePlaneAtAll() {
        // These fall through to the plaintext chain; whether a type is MLS is a separate question
        // from how it is routed.
        assertFalse(MlsContentRoute.isMlsContentType("text/plain"));
        assertFalse(MlsContentRoute.isMlsContentType("message/cpim"));
        assertFalse(MlsContentRoute.isMlsContentType("application/vnd.gsma.rcs-ft-http+xml"));
        assertFalse(MlsContentRoute.isMlsContentType(null));
        assertFalse(MlsContentRoute.isMlsContentType(""));
    }

    @Test public void anUnknownTypeAtTheDispatcherThrows() {
        // A body that reached the MLS dispatcher with a type that has no arm is a routing defect
        // and throws rather than being ignored.
        for (final String bogus : new String[] {"text/plain", "message/mls-rcs-future", ""}) {
            try {
                MlsContentRoute.of(bogus);
                fail("expected a throw for " + bogus);
            } catch (final IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage()
                        .startsWith("Invalid content type "));
            }
        }
    }

    @Test public void encryptedApplicationBodiesGoRaw() {
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of(MlsContentRoute.CT_MLS));
        // file-info arrives encrypted like any other application body.
        assertEquals(MlsContentRoute.RAW,
                MlsContentRoute.of(MlsContentRoute.CT_MLS_RCS_FILE_INFO));
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of(MlsContentRoute.CT_MLS_FT));
    }

    @Test public void serverAndClientControlAreDifferentArms() {
        assertEquals(MlsContentRoute.SERVER,
                MlsContentRoute.of(MlsContentRoute.CT_MLS_RCS_SERVER));
        assertEquals(MlsContentRoute.CONTROL,
                MlsContentRoute.of(MlsContentRoute.CT_MLS_RCS_CLIENT));
    }

    @Test public void serverKickIsRejectedNotAccepted() {
        // Host-internal per the spec and never a dispatcher input, yet in the inbound accept-set;
        // routing it to REJECTED keeps it visible.
        assertTrue("still recognised, so it does not fall through as plaintext",
                MlsContentRoute.isMlsContentType(MlsContentRoute.CT_MLS_RCS_SERVER_KICK));
        assertEquals(MlsContentRoute.REJECTED,
                MlsContentRoute.of(MlsContentRoute.CT_MLS_RCS_SERVER_KICK));
    }

    @Test public void everyAcceptedTypeHasARoute() {
        // No type is accepted at layer 1 and then throws at layer 2.
        for (final String ct : new String[] {
                MlsContentRoute.CT_MLS, MlsContentRoute.CT_MLS_RCS_CLIENT,
                MlsContentRoute.CT_MLS_RCS_SERVER, MlsContentRoute.CT_MLS_FT,
                MlsContentRoute.CT_MLS_RCS_FILE_INFO, MlsContentRoute.CT_MLS_RCS_SERVER_KICK}) {
            assertTrue(ct, MlsContentRoute.isMlsContentType(ct));
            MlsContentRoute.of(ct);   // must not throw
        }
    }

    @Test public void parametersAndCaseDoNotChangeTheRoute() {
        // A charset parameter does not change the route, and RFC 2045 content types are
        // case-insensitive.
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of("message/mls; charset=utf-8"));
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of("MESSAGE/MLS"));
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of("  message/mls  "));
        assertEquals(MlsContentRoute.SERVER,
                MlsContentRoute.of("Message/MLS-RCS-Server;boundary=x"));
        assertTrue(MlsContentRoute.isMlsContentType("message/mls;charset=utf-8"));
    }

    @Test public void normalizeIsNullSafe() {
        assertEquals("", MlsContentRoute.normalize(null));
        assertEquals("", MlsContentRoute.normalize("   "));
        assertEquals("message/mls", MlsContentRoute.normalize("message/mls;"));
    }
}
