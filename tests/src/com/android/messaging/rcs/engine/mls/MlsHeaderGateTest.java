/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsHeaderGate.Verdict;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link MlsHeaderGate}: the required inbound MLS headers, their check order and their encodings.
 */
public class MlsHeaderGateTest {

    private static final class MapHeaders implements MlsHeaderGate.Headers {
        final Map<String, String> m = new HashMap<>();
        MapHeaders put(final String name, final String value) {
            m.put(MlsHeaderGate.MLS_NAMESPACE + "|" + name, value);
            return this;
        }
        @Override public String get(final String ns, final String name) {
            return m.get(ns + "|" + name);
        }
    }

    private static MapHeaders complete() {
        return new MapHeaders()
                .put(MlsHeaderGate.HDR_ERA_ID, "19")
                .put(MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR, "AAAAAAAAAAAAAAAAAAAAAA==");
    }

    @Test public void bothPresentIsAccepted() {
        assertEquals(Verdict.ACCEPT, MlsHeaderGate.check(complete()));
        assertTrue(Verdict.ACCEPT.accepted());
    }

    @Test public void eraIdIsCheckedBeforeEpochAuthenticator() {
        // Era-ID is checked first, as other clients do, so a drop line with both absent can be
        // diffed against their traces.
        final MapHeaders neither = new MapHeaders();
        assertEquals(Verdict.MISSING_ERA_ID, MlsHeaderGate.check(neither));
    }

    @Test public void eachMissingHeaderHasItsOwnVerdict() {
        final MapHeaders noEra = complete();
        noEra.m.remove(MlsHeaderGate.MLS_NAMESPACE + "|" + MlsHeaderGate.HDR_ERA_ID);
        assertEquals(Verdict.MISSING_ERA_ID, MlsHeaderGate.check(noEra));

        final MapHeaders noAuth = complete();
        noAuth.m.remove(MlsHeaderGate.MLS_NAMESPACE + "|"
                + MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR);
        assertEquals(Verdict.MISSING_EPOCH_AUTHENTICATOR, MlsHeaderGate.check(noAuth));
    }

    @Test public void anEmptyValueCountsAsAbsent() {
        assertEquals(Verdict.MISSING_ERA_ID,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_ERA_ID, "")));
        assertEquals(Verdict.MISSING_EPOCH_AUTHENTICATOR,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR, "")));
    }

    @Test public void nullHeadersAreNotAccepted() {
        // Fail closed: a message with no header map is the case the gate exists for.
        assertFalse(MlsHeaderGate.check(null).accepted());
    }

    @Test public void aMalformedEraIsDroppedNotGuessed() {
        assertEquals(Verdict.MALFORMED,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_ERA_ID, "not-a-number")));
        assertEquals(Verdict.MALFORMED,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_ERA_ID, "-1")));
    }

    @Test public void theEraIsDecimalAsciiNotAVarint() {
        // Most integers on this wire are varints; a varint read of "19" yields 0x31, a plausible
        // wrong era.
        assertEquals(19, MlsHeaderGate.parseEraId("19"));
        assertEquals(0, MlsHeaderGate.parseEraId("0"));
        assertEquals(1234567, MlsHeaderGate.parseEraId("1234567"));
        assertEquals("0x31 would be the varint misreading of \"19\"", 19,
                MlsHeaderGate.parseEraId("19"));
    }

    @Test public void eraParsingToleratesSurroundingWhitespace() {
        assertEquals(19, MlsHeaderGate.parseEraId("  19 "));
    }

    @Test public void eraParsingRejectsRatherThanThrows() {
        // Parsing a header must not crash the inbound path.
        assertEquals(-1, MlsHeaderGate.parseEraId(null));
        assertEquals(-1, MlsHeaderGate.parseEraId(""));
        assertEquals(-1, MlsHeaderGate.parseEraId("nineteen"));
        assertEquals(-1, MlsHeaderGate.parseEraId("19.5"));
        assertEquals(-1, MlsHeaderGate.parseEraId("99999999999999999999"));
    }

    @Test public void everyRejectingVerdictHasALogLineAndAcceptHasNone() {
        for (final Verdict v : Verdict.values()) {
            if (v == Verdict.ACCEPT) {
                assertEquals("", v.logLine());
                assertTrue(v.accepted());
            } else {
                assertFalse(v.accepted());
                assertTrue(v.name(), v.logLine().contains("Dropping message."));
            }
        }
    }

    @Test public void theDropLinesMatchTheReferenceClientVerbatim() {
        // The drop lines match other clients' wording so a trace diff finds header regressions.
        assertEquals("Received an MLS message without Era-ID header. Dropping message.",
                Verdict.MISSING_ERA_ID.logLine());
        assertEquals("Received an MLS message without Epoch-Authenticator header. "
                + "Dropping message.", Verdict.MISSING_EPOCH_AUTHENTICATOR.logLine());
    }

    @Test public void theControlPlaneHasItsOwnWording() {
        // Other clients log different strings for the control and application planes.
        assertEquals("Received an MLS CONTROL MESSAGE without Era-ID header. Dropping message.",
                Verdict.MISSING_ERA_ID.logLine(/*control=*/ true));
        assertEquals("Received an MLS CONTROL MESSAGE without Epoch-Authenticator header. "
                + "Dropping message.",
                Verdict.MISSING_EPOCH_AUTHENTICATOR.logLine(/*control=*/ true));
        // The no-argument overload keeps the application wording.
        for (final Verdict v : Verdict.values()) {
            assertEquals(v.logLine(), v.logLine(/*control=*/ false));
        }
        // ACCEPT has no line on either plane.
        assertEquals("", Verdict.ACCEPT.logLine(/*control=*/ true));
    }

    @Test public void theNamespaceIsTheGsmaMlsOne() {
        // MLS is one namespace in a header map shared with reactions, replies and others.
        assertEquals("http://www.gsma.com/rcs/mls", MlsHeaderGate.MLS_NAMESPACE);
    }

    @Test public void aHeaderInTheWrongNamespaceDoesNotCount() {
        final MlsHeaderGate.Headers wrongNs = (ns, name) ->
                "urn:rcs:message:reactions:".equals(ns) ? "19" : null;
        assertEquals(Verdict.MISSING_ERA_ID, MlsHeaderGate.check(wrongNs));
    }
}
