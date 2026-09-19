/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsSendPayload;

/**
 * {@link E2eeConversationTransport.Payload} to and from the engine's {@link MlsSendPayload}: the
 * same three fields, the same references (no copy). Null maps to null both ways.
 */
final class MlsSendPayloadBinding {
    private MlsSendPayloadBinding() {}

    static MlsSendPayload mirror(final E2eeConversationTransport.Payload p) {
        return p == null ? null : new MlsSendPayload(p.contentType, p.body, p.cpimHeaders);
    }

    static E2eeConversationTransport.Payload payload(final MlsSendPayload m) {
        return m == null ? null
                : new E2eeConversationTransport.Payload(m.contentType, m.body, m.cpimHeaders);
    }
}
