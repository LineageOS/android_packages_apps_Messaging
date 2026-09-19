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

/**
 * One health-status transition, as a TYPED record rather than a log line (rework item 13.4).
 *
 * <p>The point is what it makes testable. Our host tests can assert that {@code decide()} returns
 * the right edge for one pair, but nothing can assert on a SEQUENCE — that a self-heal went
 * {@code Unknown → EpochAdvancementRequested → EraAdvancementRequested} and not some other route to
 * the same destination. Retrofitting that by parsing log text is precisely the trap the plan warns
 * about: the assertion then breaks whenever someone rewords a message, so it gets deleted.
 *
 * <p>Emitted ALONGSIDE the log line, not instead of it. The log is for a human reading a device; this
 * is for a test or a trace consumer, and neither substitutes for the other.
 *
 * <p>Immutable and value-comparable, because the whole use is
 * {@code assertEquals(expectedSequence, recorded)}.
 */
public final class MlsStateTransition {

    /** Health status before, as an {@link MlsHealthStates} ordinal. */
    public final int from;
    /** Health status after. */
    public final int to;
    /**
     * The edge's wire name, or {@code ""} for a transition that took the documented
     * table-bypassing escape (§21.4-1).
     */
    public final String edge;
    /**
     * The edge's TELEMETRY number — which is NOT its ordinal.
     *
     * <p>{@link MlsHealthEdge} keeps the two separate deliberately; recording the ordinal here would
     * make our traces incomparable with a real one, which is the only reason to mirror the
     * numbering at all.
     */
    public final int telemetryValue;
    /** Free-text cause, for the human-readable half. Never null. */
    public final String cause;

    public MlsStateTransition(final int from, final int to, final String edge,
            final int telemetryValue, final String cause) {
        this.from = from;
        this.to = to;
        this.edge = edge == null ? "" : edge;
        this.telemetryValue = telemetryValue;
        this.cause = cause == null ? "" : cause;
    }

    /** Build from a decided edge. */
    public static MlsStateTransition of(final int from, final int to, final MlsHealthEdge edge,
            final String cause) {
        return new MlsStateTransition(from, to,
                edge == null ? "" : edge.wireName,
                edge == null ? -1 : edge.telemetryValue,
                cause);
    }

    /** {@code From→To} — the form a sequence assertion reads best in. */
    public String pair() {
        return MlsHealthStates.name(from) + "→" + MlsHealthStates.name(to);
    }

    @Override public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof MlsStateTransition)) return false;
        final MlsStateTransition t = (MlsStateTransition) o;
        // Cause is deliberately EXCLUDED from equality: it is human-readable prose that changes with
        // wording, and a sequence assertion that broke on a reworded reason is the text-parsing trap
        // this type exists to avoid, reintroduced through the back door.
        return from == t.from && to == t.to && telemetryValue == t.telemetryValue
                && edge.equals(t.edge);
    }

    @Override public int hashCode() {
        return ((from * 31 + to) * 31 + telemetryValue) * 31 + edge.hashCode();
    }

    @Override public String toString() {
        return "transition{" + pair() + " on " + (edge.isEmpty() ? "(forced)" : edge)
                + " telemetry=" + telemetryValue + (cause.isEmpty() ? "" : " — " + cause) + "}";
    }
}
