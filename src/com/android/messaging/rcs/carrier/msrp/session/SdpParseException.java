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
package com.android.messaging.rcs.carrier.msrp.session;

/**
 * Thrown for structural problems while parsing an SDP body per RFC 4566.
 * Checked so the SIP transaction layer is forced to react (typically: 488
 * Not Acceptable Here back to the INVITE peer when their offer is malformed
 * and 415-equivalent abort when our answer parses back as malformed).
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
