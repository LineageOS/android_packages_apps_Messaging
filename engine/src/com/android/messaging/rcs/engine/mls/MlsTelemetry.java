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
 * The metrics port — one of the six the engine is allowed to reach the host through.
 *
 * <p>Rework items 13.3 and 1.1. Deliberately two methods over a string and an int: a metrics plane
 * that can only count is a metrics plane the engine cannot use to smuggle out state, and the whole
 * point of the six-port rule is that each port grants exactly one capability.
 *
 * <p>The names in {@link MlsMetrics} are Google Messages', verbatim, {@code Bugle.Mls.} prefix included.
 * That is not deference: keeping the names identical is what makes a counter of ours directly
 * comparable with a Google Messages trace, which is the only way we have ever settled a behavioural
 * disagreement. Renaming them would throw that away for cosmetics.
 */
public interface MlsTelemetry {

    /** Increment {@code metric} by one. */
    void count(String metric);

    /**
     * Record {@code value} against {@code metric}.
     *
     * <p>For the enumerated metrics ({@code *.Reason}, {@code *.Result}) the value IS the bucket —
     * a reason code, not a magnitude. For {@link MlsMetrics#ZINNIA_STATE_SIZE} it is a log2 bucket
     * from {@link MlsMetrics#log2Bucket}. Nothing here averages anything.
     */
    void count(String metric, int value);

    /**
     * Discards everything.
     *
     * <p>The default wherever a sink has not been injected, so that a missing metrics plane can
     * never be the reason an operation fails — a counter is an observation, and an observation that
     * can break the thing it observes is worse than no observation.
     */
    /**
     * A health transition, as a typed record (rework 13.4).
     *
     * <p>Separate from {@link #count} because a transition is a structured event, not a scalar — and
     * because the only way to assert on a SEQUENCE of them is to receive them as values. Default
     * no-op so existing implementations need no change.
     */
    default void transition(MlsStateTransition t) { }

    MlsTelemetry NONE = new MlsTelemetry() {
        @Override public void count(final String metric) {}
        @Override public void count(final String metric, final int value) {}
        @Override public void transition(final MlsStateTransition t) {}
    };
}
