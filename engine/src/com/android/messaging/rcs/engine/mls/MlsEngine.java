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
 * Backend-neutral MLS crypto engine. One instance per process; sessions per identity.
 *
 * <p><b>No {@code android.content.Context}</b>, deliberately — rework item 1.1. It used to be the
 * first parameter of {@link #startSession}, which is the type-level <em>opposite</em> of the rule
 * this interface exists to enforce: a Context is unbounded ambient authority, network included, and
 * nothing about the signature said so. It also meant the engine interface could not be exercised
 * from a host test, which is the argument our whole placement decision rests on.
 *
 * <p>What replaces it is narrower on both axes: an {@link MlsPorts} bundle for what the engine may
 * reach the host through, and a plain storage path for where its own state lives. The engine needs
 * a directory, not a way of asking the platform for one.
 */
public interface MlsEngine {
    /** Stable id for logging/selection: {@code "zinnia"} | {@code "openmls"}. */
    String id();

    /** True iff this engine's native lib loads on this device. */
    boolean available();

    /**
     * Start (or restore) a persistent session for one MLS identity; {@code null} on failure.
     *
     * @param storageDir absolute path this identity's engine state lives under. The caller resolves
     *                   it — that is a host concern, and asking the engine to derive it from a
     *                   Context is how the Context got into this signature in the first place.
     * @param ports      the six capabilities the engine may use. Never null.
     */
    MlsSession startSession(String storageDir, MlsIdentity identity, MlsPorts ports);
}
