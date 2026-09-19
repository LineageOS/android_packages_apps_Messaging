/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * A structurally invalid MSRP frame. Checked so the transport handles peer-induced framing
 * errors at the I/O boundary, usually by aborting the chunk and closing the session.
 */
public class MsrpException extends Exception {

    private static final long serialVersionUID = 1L;

    public MsrpException(String message) {
        super(message);
    }

    public MsrpException(String message, Throwable cause) {
        super(message, cause);
    }
}
