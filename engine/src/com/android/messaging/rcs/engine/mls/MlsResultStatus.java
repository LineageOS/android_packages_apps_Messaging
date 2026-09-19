/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The outcome every MLS host action carries. {@link #NO_OP} is a value, not an absence: a drive
 * loop detects convergence by finding it, so "nothing to do" must never be spelled as null or
 * false. Anything unknown reports {@link #PENDING}, which costs one extra pass, never
 * {@link #NO_OP}, which would drop in-flight work. {@link #wire} is explicit; do not use
 * {@code ordinal()}.
 */
public enum MlsResultStatus {

    /** Nothing to do; the only status that stops a drive loop without an effect. */
    NO_OP(0),

    /** Work is in flight; nothing may be concluded. Also the default for an unrecognised status. */
    PENDING(1),

    /** The operation completed and its effect is applied. */
    SUCCESS(2),

    /** Failed transiently; may be re-driven. */
    FAIL_RETRY(3),

    /** Failed terminally; not re-driven. */
    FAIL_NO_RETRY(4);

    /** The transmitted number. */
    public final int wire;

    MlsResultStatus(final int wire) { this.wire = wire; }

    /** Whether a drive loop stops on this status; {@link #PENDING} does not. */
    public boolean stopsLoop() {
        return this == NO_OP || this == SUCCESS || this == FAIL_NO_RETRY;
    }

    public boolean mayRetry() {
        return this == PENDING || this == FAIL_RETRY;
    }

    public boolean isFailure() {
        return this == FAIL_RETRY || this == FAIL_NO_RETRY;
    }

    /** @return the matching status, or {@link #PENDING} for anything unrecognised */
    public static MlsResultStatus fromWire(final int wire) {
        for (final MlsResultStatus s : values()) {
            if (s.wire == wire) return s;
        }
        return PENDING;
    }
}
