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
 * MSRP request methods per RFC 4975 §6.
 *
 * <p>RFC 4975 defines exactly three request methods:
 * <ul>
 *   <li>{@link #SEND} — carry message payload (chunked or not). §6.1.</li>
 *   <li>{@link #REPORT} — out-of-band delivery report for a previously-sent
 *       SEND. §7.1.2 — note that REPORT is a REQUEST, not a response; it
 *       carries a {@code Status:} header naming the namespace ({@code 000}
 *       for MSRP) and a status-code.</li>
 *   <li>{@link #AUTH} — solicit credentials from an MSRP relay (RFC 4976
 *       §2.1). We model it for completeness but the 1:1 carrier-RCS path
 *       does not use relays; relay support is filed as P3 follow-up.</li>
 * </ul>
 *
 * <p>Status lines on RESPONSES (e.g. {@code MSRP <tid> 200 OK}) do not use
 * this enum; see {@link MsrpMessage#getStatusCode()} on the response side.
 */
public enum MsrpMethod {
    SEND,
    REPORT,
    AUTH;

    /** Parse a method token (case-sensitive per RFC 4975) or throw. */
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
