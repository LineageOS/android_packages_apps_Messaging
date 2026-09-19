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
package com.android.messaging.rcs.carrier;

public final class Message {
    public enum Direction { OUTGOING, INCOMING }

    public enum Status {
        PENDING,    // queued locally, not yet sent
        SENDING,    // SIP transaction in flight
        SENT,       // 200 OK from server (server-acknowledged, not delivered)
        DELIVERED,  // IMDN delivery notification received
        DISPLAYED,  // IMDN display notification received
        FAILED,
        RECEIVED    // applicable to incoming
    }

    public final long id;
    public final long conversationId;
    public final String body;
    public final long timestamp;
    public final Direction direction;
    public final Status status;
    /** UP-style Message-ID (used for IMDN reporting). */
    public final String messageId;
    /** Free-form diagnostic string set when status==FAILED. Carries the
     *  underlying transport error (e.g. {@code "INVALID_ARGUMENT:
     *  tachyonerror=39 NEEDS_TACHYGRAM_REGISTER"}) so failures stay
     *  diagnosable after the logcat buffer rolls. Null for non-failed
     *  messages. */
    public final String errorReason;

    public Message(long id, long conversationId, String body, long timestamp,
            Direction direction, Status status, String messageId) {
        this(id, conversationId, body, timestamp, direction, status, messageId, null);
    }

    public Message(long id, long conversationId, String body, long timestamp,
            Direction direction, Status status, String messageId, String errorReason) {
        this.id = id;
        this.conversationId = conversationId;
        this.body = body;
        this.timestamp = timestamp;
        this.direction = direction;
        this.status = status;
        this.messageId = messageId;
        this.errorReason = errorReason;
    }
}
