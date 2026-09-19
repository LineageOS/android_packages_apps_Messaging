/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The one logcat tag every MLS line carries, from the host, the JNI bridge and the Rust engine.
 * This is the canonical copy; {@code TAG} in {@code jni/mls_openmls_bridge.c} and {@code LOG_TAG}
 * in {@code rust/rcs_mls_ffi/src/ffi.rs} must match it.
 */
public final class MlsLog {
    /** The MLS logcat tag. */
    public static final String TAG = "RcsMls";

    private MlsLog() {}
}
