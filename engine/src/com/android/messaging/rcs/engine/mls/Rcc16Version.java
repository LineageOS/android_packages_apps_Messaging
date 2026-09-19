/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The RCC.16 revision the deployment speaks, where v3.0 and v4.0 put different bytes on the wire
 * (for example the {@code end_mls} extension data). Nothing on the wire announces the revision, so
 * it is local configuration and is never inferred from what a peer sends. At each divergent site
 * decode both forms and encode the configured one. See docs/mls/rcc16-map.md.
 */
public enum Rcc16Version {
    /** RCC.16 v3.0, the default. */
    V3_0(30),
    /** RCC.16 v4.0. */
    V4_0(40);

    /** The value the engine's C ABI takes ({@code rcs_mls_set_rcc16_version}). */
    public final int wire;

    Rcc16Version(final int wire) { this.wire = wire; }

    /** True iff {@code wire} names a revision this build knows. */
    public static boolean isKnownWire(final int wire) {
        for (final Rcc16Version v : values()) { if (v.wire == wire) return true; }
        return false;
    }

    /**
     * Resolves {@code 30}/{@code 40}; anything else is {@link #V3_0}, so a mistyped setting cannot
     * change what is sent. Callers that want to report the mistake check {@link #isKnownWire}
     * first.
     */
    public static Rcc16Version fromWire(final int wire) {
        for (final Rcc16Version v : values()) { if (v.wire == wire) return v; }
        return V3_0;
    }
}
