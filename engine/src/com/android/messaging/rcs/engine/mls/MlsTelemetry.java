/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The metrics port: counts only, so the engine cannot use it to carry state out.
 *
 * <p>Metric names in {@link MlsMetrics} match other clients' names so counters stay comparable
 * across traces.
 */
public interface MlsTelemetry {

    /** Increment {@code metric} by one. */
    void count(String metric);

    /**
     * Record {@code value} against {@code metric}. For enumerated metrics ({@code *.Reason},
     * {@code *.Result}) the value is the bucket; for {@link MlsMetrics#ZINNIA_STATE_SIZE} it is a
     * log2 bucket from {@link MlsMetrics#log2Bucket}.
     */
    void count(String metric, int value);

    /** A health transition, as a structured event rather than a scalar. */
    default void transition(MlsStateTransition t) { }

    /** Discards everything; the default where no sink is injected. */
    MlsTelemetry NONE = new MlsTelemetry() {
        @Override public void count(final String metric) {}
        @Override public void count(final String metric, final int value) {}
        @Override public void transition(final MlsStateTransition t) {}
    };
}
