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
 * WHICH kind of self-heal is running — §5.1's {@code SelfHealState}, a separate axis from health.
 *
 * <p>Rework item 2.3, and the item's whole point is the word <b>separate</b>: this is <b>not
 * derivable from {@code health_status}</b>, and §5.1 says so explicitly. When the status reads
 * {@code OngoingEraAdvancement} you still cannot tell an ordinary era advance from a Phoenix-mode
 * one, and those two have different success conditions and different failure ladders. Collapsing
 * the two fields into one is the mistake this type exists to prevent.
 *
 * <p>Related errors that consume it: {@code InvalidSelfHealStatus},
 * {@code ExpectedSelfHealToBeOngoing}, {@code ExpectedSelfHealToBeRequested},
 * {@code IncorrectExpectedCommitKind}.
 *
 * <p><b>Not to be confused with {@code MlsProviderTransport.Health}</b>, which is a different axis
 * again: a live local-vs-server comparison recomputed by a server round trip
 * ({@code stateMatchesServer}). That is a question about the world right now; this is persisted
 * lifecycle state. The rework plan flags the name collision explicitly — neither may grow into the
 * other.
 */
public enum MlsSelfHealKind {
    /** No heal running. Persisted as absent, not as a state. */
    NONE(0),
    /** In-place: epoch++, same era, same MLS group. */
    EPOCH_ADVANCEMENT(1),
    /** New era = a new MLS group under the same RCS group id. */
    ERA_ADVANCEMENT(2),
    /** Downgrade out of MLS. */
    END_MLS(3),
    /** Era advancement used as a DOWNGRADE mechanism, not as a repair. */
    ERA_ADVANCEMENT_FOR_PHOENIX_MODE(4);

    /** Stable persisted number. Never renumber — a stored record outlives the build that wrote it. */
    public final int wire;

    MlsSelfHealKind(final int wire) { this.wire = wire; }

    /** {@link #NONE} for an unrecognised number: a record from a newer build must still load. */
    public static MlsSelfHealKind fromWire(final int wire) {
        for (final MlsSelfHealKind k : values()) {
            if (k.wire == wire) return k;
        }
        return NONE;
    }

    /** True for the three states that mean a heal is actually in flight. */
    public boolean isHealing() { return this != NONE; }
}
