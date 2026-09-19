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

/**
 * The thin internal conduit contract between the main process (where {@link
 * CarrierImsTransport} lives and feeds the shared {@code RcsCallbackRouter}) and
 * the isolated {@code :ims} process (where {@link CarrierImsService} hosts the
 * crash-prone JAIN-SIP/MSRP + {@code SipDelegate} engine -- design §6.4).
 *
 * <p>v1 uses a plain {@link android.os.Messenger} pair (control: main-&gt;:ims;
 * events: :ims-&gt;main) carrying {@link android.os.Bundle}s of the existing
 * {@code RcsOutgoingMessage} / {@code RcsIncomingMessage} / {@code RcsProviderCaps}
 * parcelables. This is the "minimal working conduit" the design calls for; a
 * richer typed surface is deferred.
 *
 * TODO(rcs-framework): replace the Messenger+Bundle seam with a proper internal
 * AIDL ({@code ICarrierImsControl} / {@code ICarrierImsEvents}) once the seam
 * stabilises. Messenger is dispatch-serialised on one Handler thread and has no
 * synchronous return, which is why {@link CarrierImsTransport#sendMessage} has to
 * answer pessimistically-cold locally (design §9) instead of round-tripping. A
 * two-way AIDL would let {@code getCapabilitiesForSub} / a warm-session probe be
 * synchronous. Also: Messenger cannot carry a {@link android.os.ParcelFileDescriptor}
 * cleanly for FT -- the AIDL rev is required before MSRP file transfer crosses
 * this seam.
 */
public final class CarrierImsSeam {
    private CarrierImsSeam() {}

    // ---- control messages: main process -> :ims process ----
    /** arg: replyTo = the main-process events Messenger. Sent once on bind. */
    public static final int MSG_REGISTER_EVENTS = 1;
    /** data: KEY_SUB_INFO (RcsSubInfo). Begin/resume provisioning + registration. */
    public static final int MSG_START_FOR_SUB = 2;
    /** data: KEY_SUB_ID. Tear down registration for a sub. */
    public static final int MSG_STOP_FOR_SUB = 3;
    /** data: KEY_OUT_MSG (RcsOutgoingMessage). Send a 1-1 text over a warm session. */
    public static final int MSG_SEND_MESSAGE = 4;
    /** data: KEY_SUB_ID + KEY_TO_URI. Pre-establish (keep-warm) a session to a peer. */
    public static final int MSG_WARM_PEER = 5;
    /** data: KEY_SUB_ID + KEY_MESSAGE_ID + KEY_TO_URI + KEY_IMDN_TYPE. */
    public static final int MSG_SEND_IMDN = 6;
    /** data: KEY_SUB_ID + KEY_TO_URI + KEY_TYPING_ACTIVE. */
    public static final int MSG_SEND_TYPING = 7;
    /** data: KEY_SUB_ID + KEY_OTP. No-op for carrier IMS (design §7: no OTP path). */
    public static final int MSG_SUBMIT_OTP = 8;
    /** data: KEY_SUB_ID + KEY_MESSAGE_ID. Decline an inbound MSRP FT (SIP 603). */
    public static final int MSG_REJECT_FILE = 9;
    /**
     * RESERVED (not yet wired) — outbound MSRP file transfer. data: KEY_SUB_ID +
     * KEY_MESSAGE_ID + KEY_TO_URI + KEY_FILE_FD + KEY_MIME_TYPE + KEY_FILE_NAME +
     * KEY_FILE_SIZE (+ KEY_GROUP_ID for a group send).
     * <p>The numeric code is frozen now so the two Implement agents never renumber
     * the seam; the FD path is a placeholder.
     * RIG-VERIFY(rcs-framework): confirm a {@link android.os.ParcelFileDescriptor}
     * survives a {@link android.os.Messenger} Bundle round-trip to :ims (the class
     * doc's open question), or move FT to the internal AIDL before enabling this.
     */
    public static final int MSG_SEND_FILE = 10;
    /**
     * RESERVED (not yet wired) — accept/download an inbound MSRP FT. data:
     * KEY_SUB_ID + KEY_MESSAGE_ID. Frozen code; enabled with {@link #MSG_SEND_FILE}.
     * RIG-VERIFY(rcs-framework): FD marshalling as above.
     */
    public static final int MSG_ACCEPT_FILE = 11;
    /** DEBUG (eng/userdebug only): data KEY_SUB_ID + KEY_FT_PATH. Upload the file at
     *  the given device path to the ACS FT content server (HTTP-FT upload-leg test). */
    public static final int MSG_DEBUG_FT_UPLOAD = 12;
    /** DEBUG (eng/userdebug only): data KEY_SUB_ID + KEY_TO_URI + KEY_PLAIN_TEXT
     *  + KEY_MESSAGE_ID. Send a PLAIN (non-CPM) text/plain MESSAGE to the peer to
     *  exercise the pure IMS terminating path (no oma.cpm iFC match). */
    public static final int MSG_DEBUG_PLAIN_MESSAGE = 13;

    /** data: KEY_OUT_MSG (RcsOutgoingMessage) — the SAME carrier {@link #MSG_SEND} uses.
     *  Send an MLS-E2EE (message/mls) chat message to the peer over the carrier CPM/MSRP session —
     *  the app-provided-MLS path: enrol/claim/group + Welcome + encrypt, all in :ims.
     *
     *  <p><b>Was KEY_PLAIN_TEXT — a DEBUG key shared with {@link #MSG_DEBUG_PLAIN_MESSAGE}.</b>
     *  That is why the carrier MLS leg could carry nothing but UTF-8 text. It now takes
     *  {@link #KEY_OUT_MSG}, which already had exactly the right shape
     *  ({@code {subId, messageId, toUri, contentType, byte[] body, e2eeSchemeId, groupId}}) and is
     *  already crossing this Messenger for plaintext sends — so the two send paths are symmetric
     *  rather than one of them being a debug shortcut.
     *
     *  <p>{@code body} is an RCC.16-framed MIME entity built by the caller, so the INNER content
     *  type rides inside the frame; {@code contentType} carries the OUTER type
     *  ({@code message/mls}), exactly as the Tachyon leg's own {@code RcsOutgoingMessage} does. */
    public static final int MSG_SEND_MLS = 14;

    // ---- event messages: :ims process -> main process ----
    /** data: KEY_SUB_ID + KEY_STATE (IRcsProviderCallback.PROV_*) + optional KEY_CAPS. */
    public static final int EVT_PROV_STATE = 101;
    /** data: KEY_SUB_ID + KEY_STATE (IRcsProviderCallback.REG_*) + optional KEY_REASON. */
    public static final int EVT_REG_STATE = 102;
    /** data: KEY_IN_MSG (RcsIncomingMessage). */
    public static final int EVT_INCOMING_MESSAGE = 103;
    /** data: KEY_SUB_ID + KEY_MESSAGE_ID + KEY_STATUS (IRcsProviderCallback.STATUS_*) + KEY_REASON. */
    public static final int EVT_MESSAGE_STATUS = 104;
    /** data: KEY_SUB_ID + KEY_STATE (IRcsProviderCallback.TOS_*) + optional KEY_TOS_PROMPT. */
    public static final int EVT_TOS_STATE = 105;
    /** data: KEY_SUB_ID + KEY_TO_URI. A per-peer session became warm (fast-accept sends now). */
    public static final int EVT_SESSION_WARM = 106;
    /** data: KEY_SUB_ID + KEY_TO_URI. A per-peer session went cold (tear-down/idle). */
    public static final int EVT_SESSION_COLD = 107;
    /**
     * RESERVED (not yet wired) — inbound MSRP file, resolved by :ims. data:
     * KEY_SUB_ID + KEY_MESSAGE_ID + KEY_TO_URI + KEY_FILE_FD + KEY_MIME_TYPE +
     * KEY_FILE_NAME + KEY_FILE_SIZE (+ KEY_THUMB_FD). Frozen code; paired with
     * {@link #MSG_SEND_FILE}. RIG-VERIFY(rcs-framework): FD marshalling as above.
     */
    public static final int EVT_INCOMING_MEDIA = 108;

    // ---- bundle keys ----
    public static final String KEY_SUB_ID = "sub_id";
    public static final String KEY_SUB_INFO = "sub_info";       // RcsSubInfo
    public static final String KEY_OUT_MSG = "out_msg";         // RcsOutgoingMessage
    public static final String KEY_IN_MSG = "in_msg";           // RcsIncomingMessage
    public static final String KEY_CAPS = "caps";               // RcsProviderCaps
    public static final String KEY_TOS_PROMPT = "tos_prompt";   // RcsTosPrompt (EVT_TOS_STATE)
    public static final String KEY_MESSAGE_ID = "message_id";
    public static final String KEY_TO_URI = "to_uri";
    public static final String KEY_IMDN_TYPE = "imdn_type";
    public static final String KEY_TYPING_ACTIVE = "typing_active";
    public static final String KEY_STATE = "state";
    public static final String KEY_STATUS = "status";
    public static final String KEY_REASON = "reason";
    public static final String KEY_OTP = "otp";

    // ---- FT (file-transfer) keys — RESERVED placeholders, not yet wired ----
    // Defined now so the seam is frozen and the two Implement agents never edit it
    // in parallel; the FD path itself is deferred until the MSRP-FT rig work.
    // RIG-VERIFY(rcs-framework): confirm a ParcelFileDescriptor round-trips through
    // a Messenger Bundle to/from :ims, or route FT over the internal AIDL instead.
    public static final String KEY_FT_PATH = "ft_path";         // device file path (debug FT upload)
    public static final String KEY_PLAIN_TEXT = "plain_text";   // body for debug plain MESSAGE
    public static final String KEY_FILE_FD = "file_fd";         // ParcelFileDescriptor (blob)
    public static final String KEY_THUMB_FD = "thumb_fd";       // ParcelFileDescriptor (thumbnail)
    public static final String KEY_MIME_TYPE = "mime_type";
    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_FILE_SIZE = "file_size";
    public static final String KEY_GROUP_ID = "group_id";
}
