/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Backend-neutral MLS crypto engine. One instance per process; sessions per identity.
 *
 * <p>Takes no {@code Context}: the engine reaches the host only through {@link MlsPorts} and a
 * plain storage path, which keeps it host-testable. See docs/mls/overview.md.
 */
public interface MlsEngine {
    /** Stable id for logging and engine selection. */
    String id();

    /** True iff this engine's native lib loads on this device. */
    boolean available();

    /**
     * Start (or restore) a persistent session for one MLS identity; {@code null} on failure.
     *
     * @param storageDir absolute path this identity's engine state lives under, resolved by the
     *     caller
     * @param ports      the capabilities the engine may use; never null
     */
    MlsSession startSession(String storageDir, MlsIdentity identity, MlsPorts ports);
}
