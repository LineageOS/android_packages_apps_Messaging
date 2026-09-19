/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * The Status header of an MSRP REPORT (RFC 4975 §7.1.2): a namespace, a code and an optional
 * comment, e.g. {@code 000 200 OK}. A 2xx says the message was delivered; any other code, or a
 * header that does not parse, says it was not.
 */
public final class MsrpReportStatus {
    private MsrpReportStatus() {}

    /** The code from a {@code "<namespace> <code> [comment]"} Status value, or -1. */
    public static int code(String statusHeader) {
        if (statusHeader == null) return -1;
        String s = statusHeader.trim();
        int sp1 = s.indexOf(' ');
        if (sp1 < 0) return -1;
        int sp2 = s.indexOf(' ', sp1 + 1);
        String codeStr = sp2 < 0 ? s.substring(sp1 + 1) : s.substring(sp1 + 1, sp2);
        try {
            return Integer.parseInt(codeStr.trim());
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    /** Whether a REPORT with this Status reports a delivery: a 2xx code. */
    public static boolean delivered(String statusHeader) {
        final int code = code(statusHeader);
        return code >= 200 && code < 300;
    }
}
