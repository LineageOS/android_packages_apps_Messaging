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
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a resolved E2EE {@code schemeId} (from {@link E2eeSchemeGate}) onto the
 * {@link E2eeConversationTransport} that carries it. The one place scheme→transport binding lives, so
 * a new scheme/engine plugs in with a single {@link #register} call and no change to the send/receive
 * path. Mirrors Google Messages' content-type router, which falls back on a miss.
 *
 * <p>Not thread-safe for concurrent {@link #register}; register all bindings at init, then read.
 */
public final class E2eeTransportRouter {

    private final Map<String, E2eeConversationTransport> mByScheme = new HashMap<>();

    /** Register a binding for its {@link E2eeConversationTransport#schemeId()}. */
    public void register(final E2eeConversationTransport transport) {
        if (transport != null && !TextUtils.isEmpty(transport.schemeId())) {
            mByScheme.put(transport.schemeId(), transport);
        }
    }

    /** The binding for a resolved schemeId, or {@code null} for plaintext / an unregistered scheme. */
    public E2eeConversationTransport forScheme(final String schemeId) {
        return TextUtils.isEmpty(schemeId) ? null : mByScheme.get(schemeId);
    }

    /**
     * Route an inbound MLS-plane CPIM body to the MLS binding (the only scheme whose ciphertext rides
     * messaging2's own transport). Etouffee is decrypted inside the provider and never reaches here.
     * Returns {@code null} if MLS isn't registered.
     */
    public E2eeConversationTransport forInboundMlsBody() {
        return mByScheme.get(RcsE2eeScheme.MLS);
    }
}
