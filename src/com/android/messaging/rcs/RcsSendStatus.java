/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

/**
 * Which {@code message_status} an RCS row may hold, and from what evidence. See
 * docs/rcs/architecture.md.
 *
 * <p>Pure Java, with the status values copied ({@code RcsSendStatusMirrorTest} checks them).
 */
public final class RcsSendStatus {

    private RcsSendStatus() {}

    // Mirror of MessageData.BUGLE_STATUS_OUTGOING_*.
    /** Sent: the transport took it. */
    public static final int BUGLE_STATUS_OUTGOING_COMPLETE    = 1;
    /** Queued for the SMS/MMS send queue. See {@link #strandedOnRcsTransport(int)}. */
    public static final int BUGLE_STATUS_OUTGOING_YET_TO_SEND = 4;
    /** Queued for a delayed SMS/MMS retry; strands an RCS row like 4. */
    public static final int BUGLE_STATUS_OUTGOING_AWAITING_RETRY = 7;
    /** Terminal failure. The only status from which the UI offers a resend. */
    public static final int BUGLE_STATUS_OUTGOING_FAILED      = 8;

    // Mirror of RcsConstants.RCS_STATUS_NONE and IRcsProviderCallback.STATUS_*.
    public static final int RCS_STATUS_NONE   = 0;
    public static final int RCS_STATUS_SENT   = 1;
    public static final int RCS_STATUS_FAILED = 4;

    /**
     * The {@code message_status} for a send whose outcome is known: the mapping {@code
     * UpdateRcsMessageStatusAction.mapBugleStatus} applies. A claim about the send, not delivery.
     */
    public static int bugleStatusForMeasuredHandoff(final boolean accepted) {
        return accepted ? BUGLE_STATUS_OUTGOING_COMPLETE : BUGLE_STATUS_OUTGOING_FAILED;
    }

    /**
     * The matching {@code rcs_status}, so the row looks as if {@code onMessageStatus} had updated
     * it.
     */
    public static int rcsStatusForMeasuredHandoff(final boolean accepted) {
        return accepted ? RCS_STATUS_SENT : RCS_STATUS_FAILED;
    }

    /**
     * True for a status nothing moves on a {@code TRANSPORT_RCS} row: the pair the pending-message
     * queue selects while excluding RCS rows. {@code RcsSendStatusGuardTest} pins that query.
     */
    public static boolean strandedOnRcsTransport(final int bugleStatus) {
        return bugleStatus == BUGLE_STATUS_OUTGOING_YET_TO_SEND
                || bugleStatus == BUGLE_STATUS_OUTGOING_AWAITING_RETRY;
    }
}
