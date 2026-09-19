/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The typed health transition record, which lets a test assert on an order of transitions. */
public class MlsStateTransitionTest {

    private static final class Recording implements MlsTelemetry {
        final List<MlsStateTransition> seen = new ArrayList<>();
        @Override public void count(final String m) { }
        @Override public void count(final String m, final int v) { }
        @Override public void transition(final MlsStateTransition t) { seen.add(t); }
    }

    @Test public void aSequenceIsAssertableAsValues() {
        final Recording tel = new Recording();
        tel.transition(new MlsStateTransition(MlsHealthStates.UNKNOWN,
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal", 7, "budget"));
        tel.transition(new MlsStateTransition(MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                MlsHealthStates.ERAADVANCEMENTREQUESTED, "SelfHealFailed", 9, "exhausted"));

        assertEquals(Arrays.asList(
                new MlsStateTransition(MlsHealthStates.UNKNOWN,
                        MlsHealthStates.EPOCHADVANCEMENTREQUESTED, "RequestedSelfHeal", 7, ""),
                new MlsStateTransition(MlsHealthStates.EPOCHADVANCEMENTREQUESTED,
                        MlsHealthStates.ERAADVANCEMENTREQUESTED, "SelfHealFailed", 9, "")),
                tel.seen);
    }

    @Test public void causeIsExcludedFromEquality() {
        // The cause is prose; a sequence assertion must not break on a reworded reason.
        assertEquals(new MlsStateTransition(1, 2, "E", 5, "one wording"),
                new MlsStateTransition(1, 2, "E", 5, "a completely different wording"));
    }

    @Test public void theStructuralFieldsAllParticipateInEquality() {
        final MlsStateTransition base = new MlsStateTransition(1, 2, "E", 5, "c");
        assertNotEquals(base, new MlsStateTransition(9, 2, "E", 5, "c"));
        assertNotEquals(base, new MlsStateTransition(1, 9, "E", 5, "c"));
        assertNotEquals(base, new MlsStateTransition(1, 2, "OTHER", 5, "c"));
        assertNotEquals("telemetry value is NOT the ordinal and must be compared",
                base, new MlsStateTransition(1, 2, "E", 9, "c"));
    }

    @Test public void equalRecordsHashEqually() {
        assertEquals(new MlsStateTransition(1, 2, "E", 5, "x").hashCode(),
                new MlsStateTransition(1, 2, "E", 5, "y").hashCode());
    }

    @Test public void ofCarriesTheEdgesTelemetryValueNotItsOrdinal() {
        // The telemetry number, not the ordinal, keeps traces comparable with other clients'.
        final MlsHealthEdge e = MlsHealthEdge.values()[0];
        final MlsStateTransition t = MlsStateTransition.of(1, 2, e, "cause");
        assertEquals(e.telemetryValue, t.telemetryValue);
        assertEquals(e.wireName, t.edge);
    }

    @Test public void aForcedTransitionHasNoEdgeAndSaysSo() {
        // A forced move bypasses the transition table and has no edge.
        final MlsStateTransition t = MlsStateTransition.of(1, 2, null, "forced");
        assertEquals("", t.edge);
        assertEquals(-1, t.telemetryValue);
        assertTrue(t.toString(), t.toString().contains("(forced)"));
    }

    @Test public void pairReadsAsNamesNotNumbers() {
        final String p = new MlsStateTransition(MlsHealthStates.UNKNOWN,
                MlsHealthStates.HEALTHY, "E", 1, "c").pair();
        assertEquals(MlsHealthStates.name(MlsHealthStates.UNKNOWN) + "→"
                + MlsHealthStates.name(MlsHealthStates.HEALTHY), p);
    }

    @Test public void nullsBecomeEmptyNotCrashes() {
        final MlsStateTransition t = new MlsStateTransition(1, 2, null, 3, null);
        assertEquals("", t.edge);
        assertEquals("", t.cause);
    }

    @Test public void theDefaultSinkAcceptsTransitionsSilently() {
        MlsTelemetry.NONE.transition(new MlsStateTransition(1, 2, "E", 3, "c"));
    }
}
