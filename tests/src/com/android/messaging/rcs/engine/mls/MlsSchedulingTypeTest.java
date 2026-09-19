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

/** The three-valued re-entrancy marker and its two independent consumers. */
public class MlsSchedulingTypeTest {

    @Test public void onlyNormalMaySchedule() {
        // Both non-normal values mean something already drives this conversation; scheduling while
        // draining would race two schedulers.
        assertTrue(MlsSchedulingType.NORMAL.allowsScheduling());
        assertFalse(MlsSchedulingType.RETRY_FLOW.allowsScheduling());
        assertFalse(MlsSchedulingType.NOTIFICATION_DRIVEN.allowsScheduling());
    }

    @Test public void theTransportGuardThrowsRatherThanDeclining() {
        // Collapsing the marker to a boolean would turn this hard precondition into a soft decline.
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
        // RETRY_FLOW blocks scheduling, not sending.
        MlsSchedulingType.NORMAL.requireTransportAllowed("send");
        MlsSchedulingType.RETRY_FLOW.requireTransportAllowed("send");
    }

    @Test public void theTwoGuardsAreIndependent() {
        // RETRY_FLOW is blocked from scheduling only; NOTIFICATION_DRIVEN from both. A boolean
        // cannot express that.
        assertFalse(MlsSchedulingType.RETRY_FLOW.allowsScheduling());
        MlsSchedulingType.RETRY_FLOW.requireTransportAllowed("x");
        assertFalse(MlsSchedulingType.NOTIFICATION_DRIVEN.allowsScheduling());
        try {
            MlsSchedulingType.NOTIFICATION_DRIVEN.requireTransportAllowed("x");
            fail("expected the transport guard to fire");
        } catch (final IllegalArgumentException expected) { /* expected */ }
    }

    @Test public void unstampedIsOrdinaryWork() {
        // An unstamped context behaves as ordinary work, not as privileged work that skips the
        // guards.
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
