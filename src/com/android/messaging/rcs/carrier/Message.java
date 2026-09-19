/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

public final class Message {
    public enum Direction { OUTGOING, INCOMING }

    public enum Status {
        PENDING,    // queued locally
        SENDING,    // SIP transaction in flight
        SENT,       // accepted by the server, not yet delivered
        DELIVERED,  // delivery IMDN received
        DISPLAYED,  // display IMDN received
        FAILED,
        RECEIVED
    }

    public final long id;
    public final long conversationId;
    public final String body;
    public final long timestamp;
    public final Direction direction;
    public final Status status;
    /** Message-ID used to correlate IMDNs. */
    public final String messageId;
    /** Transport error text when the status is {@code FAILED}, else null. */
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
