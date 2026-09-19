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

/** Rework item 5.5 — the three-valued re-entrancy marker and its TWO independent consumers. */
public class MlsSchedulingTypeTest {

    @Test public void onlyNormalMaySchedule() {
        // The guard itself. Both non-normal values mean something is already driving this
        // conversation, and a pass that schedules while draining produces two schedulers racing.
        assertTrue(MlsSchedulingType.NORMAL.allowsScheduling());
        assertFalse(MlsSchedulingType.RETRY_FLOW.allowsScheduling());
        assertFalse(MlsSchedulingType.NOTIFICATION_DRIVEN.allowsScheduling());
    }

    @Test public void theTransportGuardThrowsRatherThanDeclining() {
        // This is why the marker is three-valued rather than boolean: collapsing the values would
        // turn this hard precondition into a soft decline, and reaching it means the work arrived
        // by a route that should not exist.
        try {
            MlsSchedulingType.NOTIFICATION_DRIVEN.requireTransportAllowed("sendMlsCiphertext");
            fail("notification-driven work must not reach a transport entry point");
        } catch (final IllegalArgumentException expected) {
            final String m = expected.getMessage();
            assertTrue(m, m.contains("Failed requirement"));
            assertTrue("must name the entry point", m.contains("sendMlsCiphertext"));
        }
    }

    @Test public void theTransportGuardPassesTheOtherTwo() {
        // RETRY_FLOW blocks SCHEDULING, not sending — a retry that could not send would be useless.
        MlsSchedulingType.NORMAL.requireTransportAllowed("send");
        MlsSchedulingType.RETRY_FLOW.requireTransportAllowed("send");
    }

    @Test public void theTwoGuardsAreIndependent() {
        // RETRY_FLOW: blocked from scheduling, allowed on transport. NOTIFICATION_DRIVEN: blocked
        // from both. A boolean cannot express that difference, which is the whole finding.
        assertFalse(MlsSchedulingType.RETRY_FLOW.allowsScheduling());
        MlsSchedulingType.RETRY_FLOW.requireTransportAllowed("x");
        assertFalse(MlsSchedulingType.NOTIFICATION_DRIVEN.allowsScheduling());
        try {
            MlsSchedulingType.NOTIFICATION_DRIVEN.requireTransportAllowed("x");
            fail("expected the transport guard to fire");
        } catch (final IllegalArgumentException expected) { /* expected */ }
    }

    @Test public void unstampedIsOrdinaryWork() {
        // The zero value must be NORMAL: an unstamped context has to behave like ordinary work, not
        // like privileged work that skips every guard.
        assertEquals(0, MlsSchedulingType.NORMAL.wire);
        assertEquals(MlsSchedulingType.NORMAL, MlsSchedulingType.fromWire(0));
        assertEquals(MlsSchedulingType.NORMAL, MlsSchedulingType.fromWire(99));
        assertEquals(MlsSchedulingType.NORMAL, MlsSchedulingType.fromWire(-1));
    }

    @Test public void wireNumbersRoundTripAndAreDistinct() {
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (final MlsSchedulingType t : MlsSchedulingType.values()) {
            assertEquals(t, MlsSchedulingType.fromWire(t.wire));
            assertTrue("duplicate wire " + t.wire, seen.add(t.wire));
        }
        assertEquals("three values, not two", 3, MlsSchedulingType.values().length);
    }
}
