/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

/** {@link ImdnNotification#parse(byte[])} could not read the body as an RFC 5438 IMDN. */
public final class ImdnParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public ImdnParseException(String message) {
        super(message);
    }

    public ImdnParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
