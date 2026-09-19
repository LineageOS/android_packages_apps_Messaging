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
package com.android.messaging.rcs.carrier.sip;

/**
 * Thrown when {@link CpimMessage#parse(byte[])} cannot interpret a byte
 * sequence as a CPIM frame: missing header/payload separator, malformed
 * header line, or no colon in a header.
 *
 * <p>Checked, not runtime, so callers in the SIP MESSAGE / MSRP receive
 * path are forced to handle it (a bad CPIM body should not crash the
 * receive worker; we just drop the message and 4xx the transport layer).
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
