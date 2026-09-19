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
package com.android.messaging.rcs.carrier;

/**
 * Lifecycle state of {@link CarrierSipRegistrar}. Top-level so it can be
 * shared with {@link CarrierTransportBridge} (which is host-unit-tested)
 * without pulling the registrar's Android dependencies onto the host
 * classpath. {@code CarrierSipRegistrar.State} is a type alias for this
 * enum so callers can keep using the dotted form.
 *
 * <p>Transitions:
 * <pre>
 *   UNREGISTERED ──start──&gt; REGISTERING ──200 OK──&gt; REGISTERED
 *                              │
 *                              ├──4xx/5xx (non-auth)──&gt; FAILED
 *                              └──auth-retries-exhausted──&gt; FAILED
 *
 *   REGISTERED   ──stop──&gt; UNREGISTERED
 *   any          ──IOException/timeout──&gt; FAILED
 * </pre>
 */
public enum CarrierSipRegistrarState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}
