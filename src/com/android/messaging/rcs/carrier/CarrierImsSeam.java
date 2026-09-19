/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

/**
 * The {@link android.os.Messenger} contract between {@link CarrierImsTransport} in the main
 * process and {@link CarrierImsService} in {@code :ims}: message codes and {@code Bundle} keys
 * carrying the contract parcelables. See docs/rcs/carrier-transport.md.
 *
 * <p>TODO: replace with an internal AIDL, which would allow synchronous answers and file
 * descriptors for file transfer.
 */
public final class CarrierImsSeam {
    private CarrierImsSeam() {}

    // Control, main to :ims.
    /** {@code replyTo} is the main process's event Messenger. Sent once on bind. */
    public static final int MSG_REGISTER_EVENTS = 1;
    /** KEY_SUB_INFO. */
    public static final int MSG_START_FOR_SUB = 2;
    /** KEY_SUB_ID. */
    public static final int MSG_STOP_FOR_SUB = 3;
    /** KEY_OUT_MSG. */
    public static final int MSG_SEND_MESSAGE = 4;
    /** KEY_SUB_ID, KEY_TO_URI. Establishes a session to the peer ahead of a send. */
    public static final int MSG_WARM_PEER = 5;
    /** KEY_SUB_ID, KEY_MESSAGE_ID, KEY_TO_URI, KEY_IMDN_TYPE. */
    public static final int MSG_SEND_IMDN = 6;
    /** KEY_SUB_ID, KEY_TO_URI, KEY_TYPING_ACTIVE. */
    public static final int MSG_SEND_TYPING = 7;
    /** KEY_SUB_ID, KEY_OTP. Ignored: this transport has no OTP step. */
    public static final int MSG_SUBMIT_OTP = 8;
    /** KEY_SUB_ID, KEY_MESSAGE_ID. Declines an inbound file (SIP 603). */
    public static final int MSG_REJECT_FILE = 9;
    /**
     * Reserved, not wired: outbound file. KEY_SUB_ID, KEY_MESSAGE_ID, KEY_TO_URI, KEY_FILE_FD,
     * KEY_MIME_TYPE, KEY_FILE_NAME, KEY_FILE_SIZE, and KEY_GROUP_ID for a group.
     *
     * <p>TODO: confirm a {@link android.os.ParcelFileDescriptor} survives the Bundle, or move file
     * transfer to AIDL, before wiring this.
     */
    public static final int MSG_SEND_FILE = 10;
    /** Reserved, not wired: accept an inbound file. KEY_SUB_ID, KEY_MESSAGE_ID. */
    public static final int MSG_ACCEPT_FILE = 11;
    /**
     * Debuggable builds: KEY_SUB_ID, KEY_FT_PATH. Uploads that file to the content server and logs
     * the descriptor.
     */
    public static final int MSG_DEBUG_FT_UPLOAD = 12;
    /**
     * Debuggable builds: KEY_SUB_ID, KEY_TO_URI, KEY_PLAIN_TEXT, KEY_MESSAGE_ID. Sends a non-CPM
     * {@code text/plain} SIP message.
     */
    public static final int MSG_DEBUG_PLAIN_MESSAGE = 13;

    /**
     * KEY_OUT_MSG, as for {@link #MSG_SEND_MESSAGE}. {@code body} is the RCC.16-framed entity, so
     * the inner type rides inside it, and {@code contentType} is {@code message/mls}.
     */
    public static final int MSG_SEND_MLS = 14;

    // Events, :ims to main.
    /** KEY_SUB_ID, KEY_STATE ({@code PROV_*}), optional KEY_CAPS. */
    public static final int EVT_PROV_STATE = 101;
    /** KEY_SUB_ID, KEY_STATE ({@code REG_*}), optional KEY_REASON. */
    public static final int EVT_REG_STATE = 102;
    /** KEY_IN_MSG. */
    public static final int EVT_INCOMING_MESSAGE = 103;
    /** KEY_SUB_ID, KEY_MESSAGE_ID, KEY_STATUS ({@code STATUS_*}), KEY_REASON. */
    public static final int EVT_MESSAGE_STATUS = 104;
    /** KEY_SUB_ID, KEY_STATE ({@code TOS_*}), optional KEY_TOS_PROMPT. */
    public static final int EVT_TOS_STATE = 105;
    /** KEY_SUB_ID, KEY_TO_URI. A session to the peer is up; session-sized sends are accepted. */
    public static final int EVT_SESSION_WARM = 106;
    /** KEY_SUB_ID, KEY_TO_URI. The session to the peer closed. */
    public static final int EVT_SESSION_COLD = 107;
    /**
     * Reserved, not wired: inbound file. KEY_SUB_ID, KEY_MESSAGE_ID, KEY_TO_URI, KEY_FILE_FD,
     * KEY_MIME_TYPE, KEY_FILE_NAME, KEY_FILE_SIZE, optional KEY_THUMB_FD.
     */
    public static final int EVT_INCOMING_MEDIA = 108;

    // Bundle keys.
    public static final String KEY_SUB_ID = "sub_id";
    public static final String KEY_SUB_INFO = "sub_info";       // RcsSubInfo
    public static final String KEY_OUT_MSG = "out_msg";         // RcsOutgoingMessage
    public static final String KEY_IN_MSG = "in_msg";           // RcsIncomingMessage
    public static final String KEY_CAPS = "caps";               // RcsProviderCaps
    public static final String KEY_TOS_PROMPT = "tos_prompt";   // RcsTosPrompt
    public static final String KEY_MESSAGE_ID = "message_id";
    public static final String KEY_TO_URI = "to_uri";
    public static final String KEY_IMDN_TYPE = "imdn_type";
    public static final String KEY_TYPING_ACTIVE = "typing_active";
    public static final String KEY_STATE = "state";
    public static final String KEY_STATUS = "status";
    public static final String KEY_REASON = "reason";
    public static final String KEY_OTP = "otp";

    // Debug and file-transfer keys; the file-descriptor keys are reserved and not wired.
    public static final String KEY_FT_PATH = "ft_path";
    public static final String KEY_PLAIN_TEXT = "plain_text";
    public static final String KEY_FILE_FD = "file_fd";
    public static final String KEY_THUMB_FD = "thumb_fd";
    public static final String KEY_MIME_TYPE = "mime_type";
    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_FILE_SIZE = "file_size";
    public static final String KEY_GROUP_ID = "group_id";
}
