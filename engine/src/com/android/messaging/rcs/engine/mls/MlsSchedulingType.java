/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * How the work in flight was scheduled; stops a retry from scheduling another retry. Three-valued
 * because two guards read it: a scheduler declines on {@link #RETRY_FLOW}, and the transport
 * throws on {@link #NOTIFICATION_DRIVEN}. It must be stamped before results reach the
 * postprocessors, or the first one schedules a retry and the loop begins.
 */
public enum MlsSchedulingType {

    /** Ordinary work; may schedule a follow-up. The zero value, so unstamped work is ordinary. */
    NORMAL(0),

    /** Already part of a retry or drain; must not schedule more. */
    RETRY_FLOW(1),

    /** Driven by an inbound notification; must never reach the transport. */
    NOTIFICATION_DRIVEN(2);

    /** Persisted and logged; not {@link #ordinal()}. */
    public final int wire;

    MlsSchedulingType(final int wire) { this.wire = wire; }

    public boolean allowsScheduling() { return this == NORMAL; }

    /** @throws IllegalArgumentException if this is {@link #NOTIFICATION_DRIVEN} */
    public void requireTransportAllowed(final String entryPoint) {
        if (this == NOTIFICATION_DRIVEN) {
            throw new IllegalArgumentException("Failed requirement: notification-driven MLS work "
                    + "reached the transport entry point '" + entryPoint + "'. This path should be "
                    + "unreachable — the work was built for a different context and sending it "
                    + "would put the wrong thing on the wire.");
        }
    }

    /** Anything unrecognised decodes as {@link #NORMAL}. */
    public static MlsSchedulingType fromWire(final int wire) {
        for (final MlsSchedulingType t : values()) {
            if (t.wire == wire) return t;
        }
        return NORMAL;
    }
}
