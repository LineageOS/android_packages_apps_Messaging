/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

/**
 * A structurally invalid SDP body (RFC 4566). Checked so the SIP layer must answer a malformed
 * offer, typically with 488.
 */
public class SdpParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public SdpParseException(String message) {
        super(message);
    }

    public SdpParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
