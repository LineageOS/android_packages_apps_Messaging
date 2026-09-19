/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.carrier.msrp;

/**
 * Canonical MSRP header names per RFC 4975 §9 ABNF.
 *
 * <p>Header names in MSRP are case-insensitive on the wire (per RFC 4975
 * §3.1 "Header field names are case-insensitive"), but every Google Messages /
 * CarrierServices emitter we have observed uses the canonical forms below;
 * we serialize using these and accept any case on parse.
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
    public static final String USE_PATH         = "Use-Path"; // RFC 4976; AUTH only

    /** Status header namespace for the standard MSRP code-space. RFC 4975 §7.1. */
    public static final String STATUS_NAMESPACE_MSRP = "000";

    /** Failure-Report values per RFC 4975 §7.1.1. */
    public static final String FAILURE_REPORT_YES     = "yes";
    public static final String FAILURE_REPORT_NO      = "no";
    public static final String FAILURE_REPORT_PARTIAL = "partial";

    /** Success-Report values per RFC 4975 §7.1.1. */
    public static final String SUCCESS_REPORT_YES = "yes";
    public static final String SUCCESS_REPORT_NO  = "no";

    private MsrpHeaders() {}
}
