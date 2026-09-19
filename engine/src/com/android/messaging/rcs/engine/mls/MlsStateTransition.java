/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * One health-status transition as a value, so tests can assert on a sequence of transitions
 * without parsing log text. Emitted alongside the log line, not instead of it.
 */
public final class MlsStateTransition {

    /** Health status before, as an {@link MlsHealthStates} ordinal. */
    public final int from;
    /** Health status after. */
    public final int to;
    /** The edge's wire name, or {@code ""} for a transition that bypassed the edge table. */
    public final String edge;
    /**
     * The edge's telemetry number, which is not its ordinal (see {@link MlsHealthEdge}); traces
     * compare against other clients' numbering.
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

    public static MlsStateTransition of(final int from, final int to, final MlsHealthEdge edge,
            final String cause) {
        return new MlsStateTransition(from, to,
                edge == null ? "" : edge.wireName,
                edge == null ? -1 : edge.telemetryValue,
                cause);
    }

    /** {@code From→To}, the form sequence assertions read best in. */
    public String pair() {
        return MlsHealthStates.name(from) + "→" + MlsHealthStates.name(to);
    }

    @Override public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof MlsStateTransition)) return false;
        final MlsStateTransition t = (MlsStateTransition) o;
        // Cause is excluded from equality so rewording a reason does not break sequence assertions.
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
