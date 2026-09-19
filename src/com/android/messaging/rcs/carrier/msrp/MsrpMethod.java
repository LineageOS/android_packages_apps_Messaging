/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * MSRP request methods (RFC 4975 §6). {@link #REPORT} is a request, not a response
 * (RFC 4975 §7.1.2). {@link #AUTH} (RFC 4976) is parsed but unused: the 1:1 path does not
 * authenticate to relays. Responses carry a status code instead; see
 * {@link MsrpMessage#getStatusCode()}.
 */
public enum MsrpMethod {
    SEND,
    REPORT,
    AUTH;

    /** Parses a method token, case-sensitively. */
    public static MsrpMethod parse(String token) throws MsrpException {
        if (token == null) {
            throw new MsrpException("MSRP method missing");
        }
        switch (token) {
            case "SEND":   return SEND;
            case "REPORT": return REPORT;
            case "AUTH":   return AUTH;
            default:
                throw new MsrpException("Unknown MSRP method: " + token);
        }
    }
}
