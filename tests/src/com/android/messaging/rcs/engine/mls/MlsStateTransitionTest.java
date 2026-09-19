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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rework item 13.4 — the typed transition record, and the sequence assertion it enables. */
public class MlsStateTransitionTest {

    /** The thing this type exists for: a sink that lets a test assert on an ORDER of transitions. */
    private static final class Recording implements MlsTelemetry {
        final List<MlsStateTransition> seen = new ArrayList<>();
        @Override public void count(final String m) { }
        @Override public void count(final String m, final int v) { }
        @Override public void transition(final MlsStateTransition t) { seen.add(t); }
    }

    @Test public void aSequenceIsAssertableAsValues() {
        // The whole point. Before this, "did the self-heal ladder go Unknown -> EpochAdvancement ->
        // EraAdvancement, or some other route to the same place?" could only be answered by parsing
        // log text — an assertion that breaks whenever someone rewords a message, so it gets deleted.
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
        // Deliberate. The cause is human-readable prose; including it would make a sequence
        // assertion break on a reworded reason — the text-parsing trap reintroduced through the
        // back door.
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
        // MlsHealthEdge keeps ordinal and telemetry number separate on purpose; recording the
        // ordinal would make our traces incomparable with a real one, which is the only reason
        // to mirror the numbering at all.
        final MlsHealthEdge e = MlsHealthEdge.values()[0];
        final MlsStateTransition t = MlsStateTransition.of(1, 2, e, "cause");
        assertEquals(e.telemetryValue, t.telemetryValue);
        assertEquals(e.wireName, t.edge);
    }

    @Test public void aForcedTransitionHasNoEdgeAndSaysSo() {
        // The documented table-bypassing escape (§21.4-1) legitimately has no edge.
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
        // The default is a no-op so existing MlsTelemetry implementations need no change.
        MlsTelemetry.NONE.transition(new MlsStateTransition(1, 2, "E", 3, "c"));
    }
}
