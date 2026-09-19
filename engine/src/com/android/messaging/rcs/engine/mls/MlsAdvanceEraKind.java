/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * What an era advance does to the {@code end_mls} extension ({@code 0xF002}): carry, remove or
 * install it. See docs/mls/downgrade.md.
 */
public enum MlsAdvanceEraKind {

    /** Mode 0: carries {@code 0xF002} forward, so a downgraded group stays downgraded. */
    NORMAL(0),

    /** Mode 1: removes {@code 0xF002}; the only other removal site is the in-place revive. */
    REVIVAL(1),

    /** Mode 2: installs {@code 0xF002}, so the new era is born downgraded; no key packages. */
    PHOENIX_DOWNGRADE(2);

    /** The mode byte handed to the engine. Persisted and crosses the FFI; never renumber. */
    public final int mode;

    MlsAdvanceEraKind(final int mode) { this.mode = mode; }

    public static MlsAdvanceEraKind fromMode(final int mode) {
        for (final MlsAdvanceEraKind k : values()) {
            if (k.mode == mode) return k;
        }
        // An unknown mode carries the extension forward, the safe default.
        return NORMAL;
    }

    public boolean mayRemoveEndMls() { return this == REVIVAL; }

    public boolean installsEndMls() { return this == PHOENIX_DOWNGRADE; }

    /** Neither removes nor installs {@code 0xF002}: it is carried as it was. */
    public boolean carriesEndMls() { return !mayRemoveEndMls() && !installsEndMls(); }

    /**
     * Whether {@code 0xF002} must be present in the new era built by an advance of this kind,
     * given whether it was present before. A built group that disagrees was built at some other
     * kind, e.g. by an engine that decoded an unknown mode as {@link #NORMAL}.
     */
    public boolean endMlsAfter(final boolean presentBefore) {
        if (installsEndMls()) return true;
        if (mayRemoveEndMls()) return false;
        return presentBefore;
    }
}
