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
 * Thrown when {@link ImdnNotification#parse(byte[])} cannot interpret a byte
 * sequence as a valid RFC 5438 IMDN body: malformed XML, missing required
 * elements ({@code <message-id>}, {@code <datetime>}, notification class,
 * status token), or an unknown notification class.
 */
public final class ImdnParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public ImdnParseException(String message) {
        super(message);
    }

    public ImdnParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
