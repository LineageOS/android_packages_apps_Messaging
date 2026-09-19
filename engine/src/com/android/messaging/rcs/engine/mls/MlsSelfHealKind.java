/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Which kind of self-heal is running; persisted separately from the health status, which cannot
 * tell an ordinary era advance from a Phoenix-mode one. Not the live local-versus-server
 * comparison, which is recomputed by a server round trip.
 */
public enum MlsSelfHealKind {
    /** No heal running; persisted as absent. */
    NONE(0),
    /** In place: epoch + 1, same era, same MLS group. */
    EPOCH_ADVANCEMENT(1),
    /** A new era: a new MLS group under the same RCS group id. */
    ERA_ADVANCEMENT(2),
    /** Downgrade out of MLS. */
    END_MLS(3),
    /** Era advancement used to downgrade, not to repair. */
    ERA_ADVANCEMENT_FOR_PHOENIX_MODE(4);

    /** Stable persisted number; never renumber. */
    public final int wire;

    MlsSelfHealKind(final int wire) { this.wire = wire; }

    /** {@link #NONE} for an unrecognised number, so a record from a newer build still loads. */
    public static MlsSelfHealKind fromWire(final int wire) {
        for (final MlsSelfHealKind k : values()) {
            if (k.wire == wire) return k;
        }
        return NONE;
    }

    /** Whether a heal is in flight: any value but {@link #NONE}. */
    public boolean isHealing() { return this != NONE; }
}
