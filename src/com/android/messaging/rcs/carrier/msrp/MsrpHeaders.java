/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * Canonical MSRP header names (RFC 4975 §9). Names are case-insensitive on the wire; these forms
 * are written and any case is accepted on parse.
 */
public final class MsrpHeaders {

    public static final String TO_PATH          = "To-Path";
    public static final String FROM_PATH        = "From-Path";
    public static final String MESSAGE_ID       = "Message-ID";
    public static final String BYTE_RANGE       = "Byte-Range";
    public static final String CONTENT_TYPE     = "Content-Type";
    public static final String SUCCESS_REPORT   = "Success-Report";
    public static final String FAILURE_REPORT   = "Failure-Report";
    public static final String STATUS           = "Status";
    public static final String USE_PATH         = "Use-Path"; // RFC 4976, AUTH only

    /** RFC 4975 §7.1: the standard MSRP status namespace. */
    public static final String STATUS_NAMESPACE_MSRP = "000";

    /** RFC 4975 §7.1.1. */
    public static final String FAILURE_REPORT_YES     = "yes";
    public static final String FAILURE_REPORT_NO      = "no";
    public static final String FAILURE_REPORT_PARTIAL = "partial";

    /** RFC 4975 §7.1.1. */
    public static final String SUCCESS_REPORT_YES = "yes";
    public static final String SUCCESS_REPORT_NO  = "no";

    private MsrpHeaders() {}
}
