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
import org.lineageos.rcs.provider.RcsMlsPeerCaps;
import org.lineageos.rcs.provider.RcsMlsIdentity;
import org.lineageos.rcs.provider.RcsMlsControlResult;
import org.lineageos.rcs.provider.RcsMlsClaimResult;
import org.lineageos.rcs.provider.RcsMlsTransportProfile;

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
     * Provider-layer encryption state for a line; app-layer MLS is composed with it above this
     * call. Never blocks.
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


    // MLS (RFC 9420, RCC.16). This block stays last: new MLS methods go at its end. The provider
    // carries app-built artifacts verbatim and never parses, reorders or decrypts them.


    /**
     * A peer's advertised MLS feature tags, verbatim; the eligibility decision is the app's. An
     * empty result means nothing is known, not "not capable". Blocking; call off the main thread.
     */
    RcsMlsPeerCaps lookupPeerMlsCaps(String clientToken, int subId, String phoneE164);

    /**
     * True if this line holds a current MLS certificate and has published KeyPackages: the same
     * predicate that decides whether MLS is advertised at registration. Reads cached state.
     */
    boolean isMlsReady(String clientToken, int subId);

    /**
     * Establishes a 1:1 MLS conversation from app-built artifacts. A group id the backend will not
     * accept comes back as {@code VERDICT_GROUP_ID_CHANGED}.
     */
    RcsMlsControlResult createMlsConversation(String clientToken, int subId, String peerE164,
            in byte[] mlsGroupId, in byte[] welcome, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] ratchetTree, int era, String contextId,
            @nullable String rcsGroupId);

    /**
     * Delivers a commit for an existing conversation. A member's external commit returns
     * {@code VERDICT_EXTERNAL_COMMIT_REFUSED} (RFC 9420 §12.4.3.2).
     */
    RcsMlsControlResult applyMlsControl(String clientToken, int subId, String peerE164,
            String controlMsgId, in byte[] groupInfo, in byte[] commit, in byte[] epochAuth,
            in byte[] ratchetTree, in byte[] baseEpochAuth, @nullable String rcsGroupId);

    /** The server's GroupInfo for a peer's conversation, as opaque bytes the app parses. */
    RcsMlsControlResult getMlsGroupInfo(String clientToken, int subId, String peerE164);

    /**
     * Deprecated; use {@link #claimPeerKeyPackagesWithOutcome}. Returns one package and null for
     * every failure, and discards the rest of a multi-device peer's claimed packages. Kept because
     * removing a method renumbers every method after it.
     */
    byte[] claimPeerKeyPackage(String clientToken, int subId, String peerE164);

    /**
     * All of a peer's KeyPackages, one per device, length-prefixed. Null or empty when the peer
     * has none.
     */
    byte[] claimPeerKeyPackages(String clientToken, int subId, String peerE164);

    /**
     * {@link #claimPeerKeyPackages} with an {@code RcsMlsClaimResult.OUTCOME_*} that separates
     * "the peer has none" from "the service refused us". Prefer it wherever the result is reported
     * or recorded. Never returns null.
     */
    RcsMlsClaimResult claimPeerKeyPackagesWithOutcome(String clientToken, int subId,
            String peerE164);

    /**
     * Drops the provider's peer-to-group record for a 1:1 conversation, so the next send creates a
     * group instead of advancing the era of the old one. Local only: releases nothing server-side.
     */
    void mlsForgetConversation(String clientToken, int subId, String peerE164);

    /**
     * Drops the provider's record for a group conversation (the 1:1 form does not resolve
     * groups). Local only.
     *
     * @return true if the provider holds no record for this group afterwards, including when it
     *     held none before
     */
    boolean mlsForgetGroupConversation(String clientToken, int subId, String rcsGroupId);

    /**
     * Publishes KeyPackages the app generated; their private keys must live in the engine that
     * will open a Welcome addressed to them.
     *
     * @param keyPackages length-prefixed claimable KeyPackages
     * @param lastResort  the last-resort KeyPackage (with the last_resort extension, 0x000A); a
     *                    plain KeyPackage is rejected here
     * @return true if the upload was accepted
     */
    boolean uploadKeyPackages(String clientToken, int subId, in byte[] keyPackages,
            in byte[] lastResort);

    /**
     * The server's {@code [era, epoch]} for a conversation, or null if it has no group or the call
     * failed. What a divergence means is the app's decision.
     */
    long[] getMlsServerEraEpoch(String clientToken, int subId, String peerE164,
            @nullable String rcsGroupId);

    /**
     * The MLS identity: leaf certificate, chain, private key and trust roots. A permanent channel,
     * because the provider refreshes the certificate and the app must pick up the new one. Returns
     * key material, so the bind permission must stay {@code signature|privileged}.
     */
    RcsMlsIdentity exportMlsIdentity(String clientToken, int subId);

    /**
     * What this transport instance arbitrates. Never null; an implementation that has not
     * characterised itself returns {@link RcsMlsTransportProfile#conservativeDefault()}.
     */
    RcsMlsTransportProfile getMlsTransportProfile(String clientToken, int subId);

    /**
     * Sends an app-sealed 1:1 application message verbatim, with no re-framing, re-stamping or
     * re-encryption. {@code messageId} must be the id bound into the AAD, and {@code era} and
     * {@code epochAuth} must come from the group state that sealed the message.
     */
    RcsSendResult sendMlsCiphertext(String clientToken, int subId, String peerE164,
            in byte[] ciphertext, String messageId, int era, in byte[] epochAuth);

    /** The group form of {@link #sendMlsCiphertext}, addressed to the group for server fan-out. */
    RcsSendResult sendGroupMlsCiphertext(String clientToken, int subId, String rcsGroupId,
            in byte[] ciphertext, String messageId, int era, in byte[] epochAuth);

    /**
     * The MLS group id the provider holds for {@code peerE164}, or null when none exists. Lets
     * the app adopt the existing group rather than create a second one.
     */
    byte[] getMlsGroupIdForPeer(String clientToken, int subId, String peerE164);

    /**
     * Sends a plaintext delivery receipt when a peer believes an MLS conversation exists that we
     * hold no group for; the peer accepts it unsigned and downgrades. Never encrypted, unlike
     * {@link #sendImdn}.
     */
    void sendReconciliationReceipt(String clientToken, String originalMessageId, String toUri);

    /**
     * Adds members to an MLS group with the Add commit and Welcome in the same request; on an MLS
     * group the plain membership call is refused, and neither half can go first. The app has
     * already applied the commit, so a non-OK verdict means restore the pre-commit snapshot.
     *
     * @param epochAuth     the post-commit epoch authenticator
     * @param baseEpochAuth the pre-commit epoch authenticator, which the server checks against its
     *                      current epoch
     * @param controlMsgId  the id bound into the commit's AAD; never minted by the provider
     */
    RcsMlsControlResult addGroupUsersMls(String clientToken, int subId, String rcsGroupId,
            in List<String> memberE164s, in byte[] welcome, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            String controlMsgId);

    /**
     * Removes members from an MLS group with the Remove commit in the same request. No Welcome.
     * A non-OK verdict means restore the pre-commit snapshot.
     *
     * @param epochAuth     the post-commit epoch authenticator
     * @param prevEpochAuth the pre-commit epoch authenticator
     * @param controlMsgId  the id bound into the commit's AAD
     */
    RcsMlsControlResult removeGroupUsersMls(String clientToken, int subId, String rcsGroupId,
            in List<String> memberE164s, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] prevEpochAuth, String controlMsgId);

    /**
     * Leaves an MLS group by sending a SelfRemove proposal (RCC.16 §7.11.8.1) for a remaining
     * member to commit. A cached SelfRemove blocks further sends on the conversation and persists,
     * so on a refusal the caller must restore the pre-proposal snapshot.
     *
     * @param proposal      the TLS-serialized SelfRemove proposal
     * @param controlMsgId  the id bound into the proposal's AAD
     */
    RcsMlsControlResult selfLeaveGroupMls(String clientToken, int subId, String rcsGroupId,
            in byte[] proposal, in byte[] prevEpochAuth, String controlMsgId);

    /**
     * The commits after our epoch, so a member that fell behind can catch up without a peer
     * re-Welcoming it. The provider returns them opaquely and never applies them.
     *
     * @param epochAuthenticator our current 32-byte epoch authenticator
     * @return {@code [u32 BE len][bytes]} records in order; empty when current, null on failure
     */
    byte[] fetchMissedCommits(String clientToken, int subId, String peerE164, String rcsGroupId,
            long era, in byte[] epochAuthenticator);

    /**
     * The server's current 32-byte epoch authenticator, or null. Era and epoch numbers can match
     * while the state differs; only this value tells.
     */
    byte[] fetchServerEpochAuthenticator(String clientToken, int subId, String peerE164,
            String rcsGroupId);

    /**
     * Changes an encrypted group subject (RCC.16 §9.7.1.5): the ciphertext, its commit and the
     * key delivery travel in one request, and splitting any of them off is refused. A non-OK
     * verdict means restore the pre-commit snapshot. Its icon sibling is
     * {@link #changeGroupIconMls}; a change to one usually belongs in both.
     *
     * @param contentType     the literal {@code message/mls-ft}, not the subject's own type
     * @param ciphertext      the RCC.16 Annex C.2 encrypted subject
     * @param privateMessages a PrivateMessage at the post-commit epoch carrying the subject key the
     *                        commit commits to; required by the server
     * @param controlMsgId    must equal the message id bound into {@code privateMessages}' AAD
     */
    RcsMlsControlResult changeGroupSubjectMls(String clientToken, int subId, String rcsGroupId,
            String contentType, in byte[] ciphertext, in byte[] groupInfo, in byte[] commit,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            in byte[] privateMessages, String controlMsgId);

    /**
     * Tells a peer that its message failed on our side (RCC.16 §7.7.2.2). The app calls it only
     * once self-heal has failed to recover the message (RCC.16 §10.2).
     *
     * @param failureReason        one of {@code IRcsProviderCallback.MLS_FAIL_*}
     * @param toUri                the sender of the original message
     * @param groupId              non-null when the original arrived in a group
     * @param eraId                the group's era; peers cannot act on a report without it
     * @param derivedContentSigB64 the RCC.16 §7.6.3.2 derived-content signature; peers reject an
     *                             unsigned report
     */
    void sendMlsNegativeDeliveryImdn(String clientToken, int subId, String originalMessageId,
            String toUri, @nullable String groupId, int failureReason,
            long eraId, @nullable String epochAuthB64, @nullable String derivedContentSigB64,
            @nullable String receiptMessageId);

    /**
     * Sends a delivered or displayed receipt on an MLS conversation with the RCC.16 §12.1 headers;
     * peers drop a positive receipt without them. Era, epoch authenticator and signature must all
     * come from one group state. {@code signatureB64} is the derived-content signature
     * (RCC.16 §7.6.3); a null one is sent anyway and logged.
     */
    void sendMlsImdn(String clientToken, int subId, String toUri, String originalMessageId,
            int imdnType, long eraId, String epochAuthB64, String signatureB64,
            String receiptMessageId);

    /**
     * {@link #sendMlsImdn} for a message received in a group. A non-null {@code rcsGroupId} routes
     * the receipt to the group and stamps it from the group's state; null behaves as sendMlsImdn.
     * A separate method because widening sendMlsImdn would break older callers.
     */
    void sendMlsGroupImdn(String clientToken, int subId, String toUri, @nullable String rcsGroupId,
            String originalMessageId, int imdnType, long eraId, String epochAuthB64,
            String signatureB64, String receiptMessageId);

    /**
     * The server's GroupInfo addressed by group id, as opaque bytes. Unlike the one returned by
     * fetchMissedCommits it carries the RFC 9420 {@code external_pub} extension, which a resync
     * external commit needs.
     */
    RcsMlsControlResult getMlsGroupInfoForGroup(String clientToken, int subId,
            @nullable String peerE164, String rcsGroupId);

    /**
     * Changes an encrypted group icon: the icon sibling of {@link #changeGroupSubjectMls}. An icon
     * is carried by reference, so the provider uploads the ciphertext first and then sends the
     * reference, commit and key delivery in one request; an upload failure aborts the change. A
     * non-OK verdict means restore the pre-commit snapshot. Blocking; call off the main thread.
     *
     * @param contentType  the literal {@code message/mls-ft}, not the image's type
     * @param ciphertext   the RCC.16 Annex C.2 encrypted icon, which is what gets uploaded
     * @param controlMsgId the id bound into the key delivery's AAD; never minted by the provider
     */
    RcsMlsControlResult changeGroupIconMls(String clientToken, int subId, String rcsGroupId,
            String contentType, in byte[] ciphertext, in byte[] groupInfo, in byte[] commit,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            in byte[] privateMessages, String controlMsgId);

    /**
     * A second, separately fetched and verified MLS trust-anchor set, for the carrier key
     * service's domain; the roots in {@link #exportMlsIdentity} are the other set, and the two
     * are never merged. The arguments point at the anchor list and may be null or 0 to use what
     * the provider holds. Returns length-prefixed roots that replace the caller's set of this
     * kind, or null. May hit the network; call off the main thread.
     *
     * @param signerSpkiB64 base64 SPKI of the list signer; without one no remote list is installed
     *     and the provider falls back to its cache or bundled set
     */
    @nullable byte[] getMlsTrustAnchors(String clientToken, int subId, @nullable String uri,
            long generation, @nullable String signerSpkiB64);
}
