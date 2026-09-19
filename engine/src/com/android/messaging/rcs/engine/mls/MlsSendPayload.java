/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;

/**
 * A send-ready MLS payload: the CPIM content type, the MLSMessage bytes and the CPIM headers
 * (Era-ID, Epoch-Authenticator; nullable). Mirrors the app's transport payload field for field.
 */
public final class MlsSendPayload {
    public final String contentType;
    public final byte[] body;
    public final Map<String, String> cpimHeaders;

    public MlsSendPayload(final String contentType, final byte[] body,
            final Map<String, String> cpimHeaders) {
        this.contentType = contentType;
        this.body = body;
        this.cpimHeaders = cpimHeaders;
    }
}
