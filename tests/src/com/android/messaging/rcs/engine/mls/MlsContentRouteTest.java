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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Rework item 6.1 — inbound content-type routing, and the unknown-type throw. */
public class MlsContentRouteTest {

    // -- layer 1 vs layer 2, kept separate ---------------------------------------------------------

    @Test public void nonMlsTypesAreNotOnThePlaneAtAll() {
        // These must fall through to the plaintext chain SILENTLY. Merging this question with the
        // routing question is what turned a routing bug into a silent drop.
        assertFalse(MlsContentRoute.isMlsContentType("text/plain"));
        assertFalse(MlsContentRoute.isMlsContentType("message/cpim"));
        assertFalse(MlsContentRoute.isMlsContentType("application/vnd.gsma.rcs-ft-http+xml"));
        assertFalse(MlsContentRoute.isMlsContentType(null));
        assertFalse(MlsContentRoute.isMlsContentType(""));
    }

    @Test public void anUnknownTypeAtTheDispatcherThrows() {
        // The item's whole point: a body that reached the MLS dispatcher with a type we have no arm
        // for is a routing DEFECT, not a message to ignore. A silent ignore makes it invisible.
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

    // -- the routes --------------------------------------------------------------------------------

    @Test public void encryptedApplicationBodiesGoRaw() {
        assertEquals(MlsContentRoute.RAW, MlsContentRoute.of(MlsContentRoute.CT_MLS));
        // file-info arrives ENCRYPTED like any other application body; we currently only recognise
        // it as the INNER type of an already-decrypted one, so this outer leg is new.
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
        // Host-internal per the spec and never a dispatcher input, yet it is in our inbound
        // accept-set. Routing it to REJECTED keeps it visible so the set can be narrowed with
        // evidence rather than by guess.
        assertTrue("still recognised, so it does not fall through as plaintext",
                MlsContentRoute.isMlsContentType(MlsContentRoute.CT_MLS_RCS_SERVER_KICK));
        assertEquals(MlsContentRoute.REJECTED,
                MlsContentRoute.of(MlsContentRoute.CT_MLS_RCS_SERVER_KICK));
    }

    @Test public void everyAcceptedTypeHasARoute() {
        // No type may be accepted at layer 1 and then throw at layer 2 — that combination is a
        // dispatcher that admits bodies it cannot place.
        for (final String ct : new String[] {
                MlsContentRoute.CT_MLS, MlsContentRoute.CT_MLS_RCS_CLIENT,
                MlsContentRoute.CT_MLS_RCS_SERVER, MlsContentRoute.CT_MLS_FT,
                MlsContentRoute.CT_MLS_RCS_FILE_INFO, MlsContentRoute.CT_MLS_RCS_SERVER_KICK}) {
            assertTrue(ct, MlsContentRoute.isMlsContentType(ct));
            MlsContentRoute.of(ct);   // must not throw
        }
    }

    // -- normalisation -----------------------------------------------------------------------------

    @Test public void parametersAndCaseDoNotChangeTheRoute() {
        // A routing decision that turned on a charset parameter would drop a perfectly good body,
        // and RFC 2045 makes content types case-insensitive.
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
