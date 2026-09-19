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

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsHeaderGate.Verdict;

import java.util.HashMap;
import java.util.Map;

/** Rework item 6.2 — the required inbound MLS headers, their order, and their encodings. */
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
        // Order is Google Messages'. With BOTH absent the verdict must name Era-ID — checking them in the
        // other order, or collapsing both into one message, makes a header regression undiffable
        // against a Google Messages trace, which is the whole reason to mirror the strings.
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
        // Fail CLOSED. A message with no header map at all is the case the gate exists for.
        assertFalse(MlsHeaderGate.check(null).accepted());
    }

    @Test public void aMalformedEraIsDroppedNotGuessed() {
        assertEquals(Verdict.MALFORMED,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_ERA_ID, "not-a-number")));
        assertEquals(Verdict.MALFORMED,
                MlsHeaderGate.check(complete().put(MlsHeaderGate.HDR_ERA_ID, "-1")));
    }

    @Test public void theEraIsDecimalAsciiNotAVarint() {
        // The silent mistake this pins: nearly every other integer on this wire is varint-coded, so
        // a varint decoder is the natural reach — and a varint read of "19" yields 0x31, a plausible
        // era that is simply wrong.
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
        // A log line must not be the thing that crashes the inbound path.
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
        // Paraphrasing breaks the §20.3 trace diff exactly when a header regression is what you are
        // trying to find.
        assertEquals("Received an MLS message without Era-ID header. Dropping message.",
                Verdict.MISSING_ERA_ID.logLine());
        assertEquals("Received an MLS message without Epoch-Authenticator header. "
                + "Dropping message.", Verdict.MISSING_EPOCH_AUTHENTICATOR.logLine());
    }

    @Test public void theControlPlaneHasItsOwnWording() {
        // Google Messages emits TWO strings for this verdict, from two processors, and we shipped one of
        // them on both arms:
        //   CONTROL      "...an MLS CONTROL MESSAGE without Era-ID..."
        //   APPLICATION  "...an MLS message without Era-ID..."
        // A control-plane header drop therefore read as an application-plane one in any diff
        // against a Google Messages trace — the exact comparison MlsHeaderGate exists to keep honest.
        assertEquals("Received an MLS CONTROL MESSAGE without Era-ID header. Dropping message.",
                Verdict.MISSING_ERA_ID.logLine(/*control=*/ true));
        assertEquals("Received an MLS CONTROL MESSAGE without Epoch-Authenticator header. "
                + "Dropping message.",
                Verdict.MISSING_EPOCH_AUTHENTICATOR.logLine(/*control=*/ true));
        // The no-arg overload stays the APPLICATION wording — the majority caller, and the carrier
        // leg passes content-typed bodies through it.
        for (final Verdict v : Verdict.values()) {
            assertEquals(v.logLine(), v.logLine(/*control=*/ false));
        }
        // ACCEPT has no line on either plane; "" must not become "Received an MLS CONTROL...".
        assertEquals("", Verdict.ACCEPT.logLine(/*control=*/ true));
    }

    @Test public void theNamespaceIsTheGsmaMlsOne() {
        // MLS is one namespace among many in a GENERIC header map shared with reactions, replies and
        // the rest — not a container of its own. Getting this string wrong finds nothing, silently.
        assertEquals("http://www.gsma.com/rcs/mls", MlsHeaderGate.MLS_NAMESPACE);
    }

    @Test public void aHeaderInTheWrongNamespaceDoesNotCount() {
        final MlsHeaderGate.Headers wrongNs = (ns, name) ->
                "urn:rcs:message:reactions:".equals(ns) ? "19" : null;
        assertEquals(Verdict.MISSING_ERA_ID, MlsHeaderGate.check(wrongNs));
    }
}
