/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
// The bound-service interface an RCS provider exposes to this app. See
// docs/rcs/provider-contract.md.
package org.lineageos.rcs.provider;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsSubInfo;
import org.lineageos.rcs.provider.RcsOutgoingMessage;
import org.lineageos.rcs.provider.RcsOutgoingFile;
import org.lineageos.rcs.provider.RcsSendResult;
import org.lineageos.rcs.provider.RcsGroupInfo;
import org.lineageos.rcs.provider.RcsBotBrand;
import org.lineageos.rcs.provider.RcsE2eeInfo;

// Transaction codes follow declaration order: append new methods at the end, never insert or
// reorder. See docs/rcs/provider-contract.md.
interface IRcsProvider {
    // lookupRcsCapability() results.
    /** Not known (no cache entry, lookup failed or in flight); the caller sends optimistically. */
    const int CAP_UNKNOWN  = -1;
    /** Reachable on SMS only. */
    const int CAP_SMS_ONLY = 0;
    /** RCS-capable. */
    const int CAP_RCS      = 1;

    // canServeSub() results.
    /** This transport does not serve this line's carrier. */
    const int LINE_INELIGIBLE = 0;
    /** This transport can serve this line. */
    const int LINE_ELIGIBLE   = 1;
    /** Only startForSub can tell, e.g. when eligibility depends on live modem or IMS state. */
    const int LINE_MAYBE      = 2;
    /** Eligibility not yet known. */
    const int LINE_UNKNOWN    = -1;

    /**
     * The contract revision this provider implements. A label for logs, not the compatibility
     * check: pairing is decided by comparing transaction layouts ({@code RcsContractLayout}).
     */
    int getContractVersion();

    /** What this provider can do independent of any SIM: label, contract version, transports. */
    RcsProviderCaps getProviderCaps();

    /**
     * Registers a callback sink and returns an opaque client token for later calls, or null when
     * the provider refuses the pairing.
     */
    @nullable String attach(in IRcsProviderCallback cb, int desiredContractVersion);

    /** Releases a session returned by attach(). Idempotent. */
    void detach(String clientToken);

    /**
     * Begins or resumes provisioning and registration for a line. Asynchronous; progress arrives
     * through onProvisioningStateChanged and onRegistrationStateChanged.
     */
    void startForSub(String clientToken, in RcsSubInfo sub);

    /** Tears down registration for a line. Idempotent. */
    void stopForSub(String clientToken, int subId);

    /** The per-line capabilities, or null if the line is unknown or not started. */
    @nullable RcsProviderCaps getCapabilitiesForSub(int subId);

    /**
     * RCS capability of an E.164 destination: one of CAP_*. Cached by the provider; may hit the
     * network, so call off the main thread.
     */
    int lookupRcsCapability(String clientToken, int subId, String phoneE164);

    /**
     * Sends a 1:1 text message. The synchronous result lets the caller fall back to SMS at once;
     * the terminal status arrives through onMessageStatus().
     */
    RcsSendResult sendMessage(String clientToken, in RcsOutgoingMessage msg);

    /**
     * Sends a delivered or displayed receipt for an inbound message; imdnType is
     * IRcsProviderCallback.IMDN_DELIVERED or IMDN_DISPLAYED. Send displayed receipts promptly:
     * peers may fall back to SMS when one is late.
     */
    void sendImdn(String clientToken, String originalMessageId,
                  String toUri, int imdnType);

    /** Sends a typing indicator. The provider may ignore it. */
    void sendTyping(String clientToken, String toUri, boolean active);

    /** Forwards a provisioning one-time code the app captured, answering onOtpRequired(). */
    void submitOtp(String clientToken, int subId, String otp);

    /**
     * The user's answer to onCarrierTosStateChanged(TOS_REQUIRED). Accepting completes
     * provisioning from the stored configuration without a new request; declining (or a later
     * withdrawal) leaves the line SMS-only. Idempotent.
     */
    void submitTosConsent(String clientToken, int subId, boolean accept);

    /** The current IRcsProviderCallback.TOS_* state of a line. */
    int getTosState(int subId);

    // Groups. A group is addressed by the opaque id createGroup() returns; the server fans out to
    // members, and lifecycle events arrive through onGroupEvent. Blocking; call off the main
    // thread.

    /**
     * Creates a group with a client-chosen id. Returns the authoritative group, or null on
     * failure. groupType is the wire group type (0 = default).
     */
    @nullable RcsGroupInfo createGroup(String clientToken, int subId,
            String desiredGroupId, String groupName,
            in List<String> memberE164s, int groupType);

    /** The group's current name, conference URI and members, or null on failure. */
    @nullable RcsGroupInfo getGroupInfo(String clientToken, int subId,
            String groupId);

    /**
     * Newline-separated ids of the groups that list this client, or null on failure. Answers
     * membership, where getGroupInfo answers existence; the two together tell "not a member"
     * from "no such group".
     */
    @nullable String getGroupIds(String clientToken, int subId);

    /** Adds members. Returns true if the request was accepted. */
    boolean addGroupUsers(String clientToken, int subId, String groupId,
            in List<String> memberE164s);

    /** Removes members. Returns true if the request was accepted. */
    boolean removeGroupUsers(String clientToken, int subId, String groupId,
            in List<String> memberE164s);

    /** Renames the group. Returns true if the request was accepted. */
    boolean renameGroup(String clientToken, int subId, String groupId,
            String newName);

    /**
     * Sends text to a group. Result and status as for sendMessage(); clientMessageId correlates
     * receipts.
     */
    RcsSendResult sendGroupMessage(String clientToken, int subId,
            String groupId, String body, String clientMessageId);

    /**
     * Shares a location (application/vnd.gsma.rcspushlocation+xml). {@code recipient} is an E.164,
     * or a group id when {@code isGroup}. {@code accuracyMeters} &lt;= 0 sends a point, otherwise a
     * circle; {@code label} is an optional place name. Result and status as for sendMessage().
     */
    RcsSendResult sendLocation(String clientToken, int subId, String recipient,
            boolean isGroup, double latitude, double longitude,
            double accuracyMeters, String label, String clientMessageId);

    /**
     * Sends a file. The file crosses as a descriptor, which the provider closes. Result and status
     * as for sendMessage(). Blocking; call off the main thread.
     */
    RcsSendResult sendFile(String clientToken, in RcsOutgoingFile file);

    /**
     * Downloads a file the user accepted, named by the messageId of an earlier onIncomingMedia();
     * the result is re-delivered through onIncomingMedia(). Idempotent for an unknown or
     * already-downloaded id. Returns immediately.
     */
    void acceptIncomingFile(String clientToken, int subId, String messageId);

    /** Sends a typing indicator to a group. The provider may ignore it. */
    void sendGroupTyping(String clientToken, String groupId, boolean active);

    /**
     * Adds ({@code add}) or removes an emoji reaction on {@code targetMessageId}; a replace is a
     * remove then an add. {@code toUri} is the peer, or the group id when {@code groupId} is
     * non-null (the two are then equal). The app validates {@code emoji} as one emoji sequence.
     * There is no reaction-specific receipt. Blocking; call off the main thread.
     */
    RcsSendResult sendReaction(String clientToken, int subId,
            String targetMessageId, String toUri, String emoji, boolean add,
            @nullable String groupId);

    /**
     * The brand of a business-messaging agent, for the conversation header, or null if unknown.
     * Cached by the provider; the image URLs are public. May hit the network; call off the main
     * thread.
     */
    @nullable RcsBotBrand getBotBrand(String clientToken, int subId, String botId);

    /**
     * Sends a suggestion-chip response to an agent. {@code messageId} may be null, in which case
     * the provider mints one. No receipt is raised.
     */
    RcsSendResult sendBotPostback(String clientToken, int subId, String botId,
            String contentType, String jsonBody, @nullable String messageId);

    /**
     * Turns business messaging on or off. The provider persists the choice and re-registers
     * asynchronously; returns once the choice is persisted. Idempotent.
     */
    void setChatbotEnabled(String clientToken, boolean enabled);

    /**
     * Provider-layer encryption state for a line. Never blocks.
     */
    RcsE2eeInfo getE2eeInfo(int subId);

    /**
     * Sets the provider-layer encryption toggle for a line and fires
     * {@link IRcsProviderCallback#onE2eeStateChanged} to all clients. No-op when unavailable.
     */
    void setE2eeEnabled(String clientToken, int subId, boolean enabled);

    /** Fast, side-effect free pre-flight: would this transport serve the line? One of LINE_*. */
    int canServeSub(in RcsSubInfo sub);

    /**
     * Declines a file the user chose not to download; a session transport sends a SIP 603.
     * Idempotent for an unknown or already-resolved id. Returns immediately.
     */
    void rejectIncomingFile(String clientToken, int subId, String messageId);

    /**
     * Confirms inbound messages were applied, so the provider may acknowledge them upstream.
     * Unknown or already-confirmed ids are ignored. Returns immediately. See "Delivery
     * confirmation" in docs/rcs/provider-contract.md.
     */
    void ackInboundMessages(String clientToken, int subId, in String[] messageIds);


}
