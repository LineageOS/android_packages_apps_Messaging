/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * MSRP end-line continuation flag (RFC 4975 §3.1): {@code $} last chunk, {@code +} more
 * chunks follow, {@code #} the sender aborted the message.
 */
public enum MsrpEndFlag {
    COMPLETE('$'),
    CONTINUATION('+'),
    ABORT('#');

    private final char ch;

    MsrpEndFlag(char ch) {
        this.ch = ch;
    }

    public char asChar() {
        return ch;
    }

    public static MsrpEndFlag fromChar(char c) throws MsrpException {
        switch (c) {
            case '$': return COMPLETE;
            case '+': return CONTINUATION;
            case '#': return ABORT;
            default:
                throw new MsrpException("Unknown MSRP end-flag char: 0x"
                        + Integer.toHexString(c));
        }
    }
}
