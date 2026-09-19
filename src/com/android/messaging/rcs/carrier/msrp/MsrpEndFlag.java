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
 * MSRP chunk end-line flag (RFC 4975 §3.1, §5.1):
 * <pre>
 *   end-line = "-------" transact-id continuation-flag CRLF
 *   continuation-flag = "+" / "$" / "#"
 * </pre>
 *
 * <ul>
 *   <li>{@link #COMPLETE} ({@code $}) — final chunk of the message.</li>
 *   <li>{@link #CONTINUATION} ({@code +}) — more chunks for this Message-ID coming.</li>
 *   <li>{@link #ABORT} ({@code #}) — sender is aborting the in-flight message.</li>
 * </ul>
 *
 * <p>Aligns with the byte values Google Messages uses
 * (0x24 / 0x2b / 0x23).
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
