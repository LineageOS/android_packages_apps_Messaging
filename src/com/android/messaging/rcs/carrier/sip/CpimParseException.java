/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

/**
 * {@link CpimMessage#parse(byte[])} could not read the bytes as a CPIM frame. Checked so the
 * receive path drops the message rather than crashing its worker.
 */
public final class CpimParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public CpimParseException(String message) {
        super(message);
    }

    public CpimParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
