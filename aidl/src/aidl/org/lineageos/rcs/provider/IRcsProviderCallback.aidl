/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
// The provider-to-app callback registered through IRcsProvider.attach(). See
// docs/rcs/provider-contract.md.
package org.lineageos.rcs.provider;

import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsIncomingFile;
import org.lineageos.rcs.provider.RcsIncomingBotMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsTosPrompt;
import org.lineageos.rcs.provider.RcsE2eeInfo;

/**
 * Every method is one-way: a synchronous transaction to a frozen app process kills it, while an
 * asynchronous one is queued. A {@code RemoteException} therefore means "could not be queued", not
 * "the app failed to handle it". Transaction codes follow declaration order: append only.
 *
 * <p>An app that attaches at contract revision {@link IRcsProvider#CONTRACT_CONFIRMS_STORED} or
 * later confirms every content-bearing callback through {@link IRcsProvider#ackInboundMessages}
 * once its effect is stored, and the provider acknowledges the inbound message upstream only then.
 * A callback that carries a message confirms its {@code messageId}; the others carry a
 * {@code confirmId}, null when the provider wants no confirmation.
 */
oneway interface IRcsProviderCallback {
    // onMessageStatus() status.
    const int STATUS_SENT      = 1;
    const int STATUS_DELIVERED = 2;
    const int STATUS_DISPLAYED = 3;
    const int STATUS_FAILED    = 4;

    // imdnType, shared by sendImdn() and onImdnReceipt().
    const int IMDN_DELIVERED = 1;
    const int IMDN_DISPLAYED = 2;

    // MLS client failure reasons: the binary codes of RCC.16 §7.6.3.2 for the values of
    // RCC.16 §7.7.2.3. Code 4 is spelled <failed-to-decrypt> in XML but failure_to_decrypt in the
    // binary enum; mixing the two produces a report peers ignore.
    const int MLS_FAIL_UNKNOWN                  = 0;
    const int MLS_FAIL_MESSAGE_FROM_NON_MEMBER  = 1;
    const int MLS_FAIL_INVALID_CREDENTIAL       = 2;
    const int MLS_FAIL_INVALID_COMMIT           = 3;
    const int MLS_FAIL_FAILED_TO_DECRYPT        = 4;
    const int MLS_FAIL_COMMIT_IN_PRIVATEMESSAGE = 5;

    // onRegistrationStateChanged() state.
    const int REG_UNREGISTERED = 0;
    const int REG_REGISTERING  = 1;
    const int REG_REGISTERED   = 2;
    const int REG_FAILED       = 3;

    // onProvisioningStateChanged() provState.
    const int PROV_NOT_PROVISIONED      = 0;
    const int PROV_IN_PROGRESS          = 1;
    const int PROV_WAITING_FOR_OTP      = 2;
    const int PROV_CONFIGURED           = 3;
    const int PROV_DISABLED_BY_CARRIER  = 4;
    const int PROV_NEEDS_REPROVISION    = 5;
    const int PROV_WAITING_FOR_TOS      = 6;

    // onCarrierTosStateChanged() tosState.
    const int TOS_NONE     = 0;  // no terms to accept
    const int TOS_REQUIRED = 1;  // provisioning waits for consent
    const int TOS_ACCEPTED = 2;
    const int TOS_DECLINED = 3;  // the line stays SMS-only

    // onGroupEvent() op: the wire operation numbers 7 to 12.
    const int GROUP_OP_CREATE         = 7;
    const int GROUP_OP_ADD_USERS      = 8;
    const int GROUP_OP_KICK_USERS     = 9;
    const int GROUP_OP_CHANGE_PROFILE = 10;
    const int GROUP_OP_CHANGE_ROLE    = 11;  // reported by op only
    const int GROUP_OP_CHANGE_INFO    = 12;

    /** An inbound 1:1 or group message. Confirmed by {@code msg.messageId} once stored. */
    void onIncomingMessage(in RcsIncomingMessage msg);

    /**
     * Status of one of our messages, one of STATUS_*. {@code errorReason} is free text on
     * STATUS_FAILED, null otherwise. {@code e2eeSchemeId} is the scheme the provider applied on the
     * wire, meaningful on STATUS_SENT; null is plaintext or unknown. The app stamps the padlock
     * from it. A caller that sends one argument fewer is read as null, which can only under-claim.
     */
    void onMessageStatus(int subId, String messageId, int status,
                         @nullable String errorReason, @nullable String e2eeSchemeId);

    /**
     * A receipt for one of our messages; imdnType is IMDN_DELIVERED or IMDN_DISPLAYED.
     * {@code confirmId}, when non-null, is confirmed once the receipt is applied.
     */
    void onImdnReceipt(int subId, String messageId, int imdnType, @nullable String confirmId);

    /**
     * A thread-wide receipt naming no message: it covers all our outstanding messages to
     * {@code fromUri}.
     */
    void onImdnReceiptForPeer(int subId, String fromUri, int imdnType);

    /** Registration state, one of REG_*. */
    void onRegistrationStateChanged(int subId, int state, @nullable String reason);

    /**
     * Provisioning state, one of PROV_*. {@code caps} is the latest per-line snapshot and may be
     * null until PROV_CONFIGURED.
     */
    void onProvisioningStateChanged(int subId, int provState,
                                    in @nullable RcsProviderCaps caps);

    /**
     * The provider needs a one-time code; the app answers with IRcsProvider.submitOtp().
     * {@code hint} is optional text for the UI, such as the sender.
     */
    void onOtpRequired(int subId, @nullable String hint);

    /** A peer's typing indicator. */
    void onTyping(int subId, String fromUri, boolean active);

    /**
     * Terms-of-service state, one of TOS_*. With TOS_REQUIRED the provider waits for consent and
     * {@code prompt} carries the dialog; otherwise it is informational and {@code prompt} may be
     * null.
     */
    void onCarrierTosStateChanged(int subId, int tosState, in @nullable RcsTosPrompt prompt);

    /**
     * A group lifecycle event; op is one of GROUP_OP_*. {@code groupId} may be null for an
     * unresolved op. {@code members} is the current membership and {@code affectedMembers} the
     * added or removed numbers for ADD/KICK; neither is null. {@code confirmId}, when non-null, is
     * confirmed once the event is applied. See docs/rcs/groups.md.
     */
    void onGroupEvent(int subId, int op, @nullable String groupId,
            @nullable String name, @nullable String conferenceUri,
            @nullable String requester,
            in List<String> members, in List<String> affectedMembers,
            @nullable String confirmId);

    /** Typing in a group; {@code fromUri} is the member who is typing. */
    void onGroupTyping(int subId, String groupId, String fromUri, boolean active);

    /**
     * A receipt from one member for one of our group messages; each member sends its own, so
     * {@code fromUri} names the member. {@code confirmId}, when non-null, is confirmed once the
     * receipt is applied.
     */
    void onGroupImdnReceipt(int subId, String rcsMessageId, String fromUri,
            int imdnType, @nullable String confirmId);

    /**
     * An inbound file, already downloaded by the provider and exposed as a content URI and/or
     * descriptor. With a null {@link RcsIncomingFile#contentUri} and a thumbnail it is a preview
     * awaiting the user's accept; a second call carries the file. Confirmed by
     * {@code file.messageId} once stored.
     */
    void onIncomingMedia(in RcsIncomingFile file);

    /**
     * An emoji reaction on {@code targetMessageId}; {@code add} false retracts it.
     * {@code groupId} is set for a group reaction, null for 1:1. Not also delivered as a message.
     * {@code confirmId}, when non-null, is confirmed once the reaction is stored.
     */
    void onIncomingReaction(int subId, String targetMessageId, String fromUri,
            String emoji, boolean add, @nullable String groupId, @nullable String confirmId);

    /**
     * A business-messaging message; the sender is an agent id, not a number, and the JSON is
     * unparsed. Not also delivered as a message, and the provider sends no receipt for it.
     * Confirmed by {@code msg.messageId} once stored.
     */
    void onIncomingBotMessage(in RcsIncomingBotMessage msg);

    /** Provider-layer encryption state or availability changed. */
    void onE2eeStateChanged(int subId, in RcsE2eeInfo info);

    // MLS (RFC 9420, RCC.16). This block stays last: new MLS callbacks go at its end. The provider
    // delivers MLS payloads verbatim and decrypts nothing.


    /**
     * An encrypted group subject (RCC.16 §9.7.1.5); the app decrypts it with the key from the
     * commit's key delivery.
     *
     * @param contentType the RCC.16 Annex C.2 encrypted type, {@code message/mls-ft}
     */
    void onEncryptedGroupSubject(int subId, @nullable String groupId, @nullable String fromE164,
            @nullable String contentType, in byte[] ciphertext);

    /**
     * An encrypted group icon reference (RCC.16 §9.7.1.4): {@code first} is the content type
     * ({@code message/mls-ft}), {@code second} the URL. The send side builds the same order, and a
     * swap is silent on the wire.
     */
    void onEncryptedGroupIcon(int subId, @nullable String groupId, @nullable String fromE164,
            @nullable String first, @nullable String second);

    /**
     * MLS messages that must be applied in the given order, such as a metadata commit and the key
     * delivery encrypted at its post-commit epoch. One call, so order does not depend on thread
     * scheduling. {@code eraId} ({@code -1} when absent), {@code epochAuthenticator} and
     * {@code originalMessageId} are the outer-envelope headers (RCC.16 §7.9), readable before
     * decryption.
     *
     * @param packedMessages {@code [u32 BE len][bytes]} records in application order
     */
    void onMlsControlBundle(int subId, @nullable String fromE164, @nullable String messageId,
            in byte[] packedMessages, boolean convergenceAck, @nullable String groupId,
            long eraId, in @nullable byte[] epochAuthenticator,
            @nullable String originalMessageId);

    /**
     * A peer reports that one of our messages failed on its side (RCC.16 §7.7.2.2). For
     * {@link #MLS_FAIL_FAILED_TO_DECRYPT} the remedy is to advance to the latest epoch and resend.
     *
     * @param messageId     our message that failed
     * @param groupId       the group, or null for 1:1
     * @param failureReason one of MLS_FAIL_*
     */
    void onMlsNegativeDelivery(int subId, String messageId, @nullable String fromUri,
            @nullable String groupId, int failureReason);

    /**
     * One inbound MLS control payload, as the transport delivered it. The app must not block the
     * calling thread.
     */
    void onMlsControl(int subId, String fromE164, String messageId, in byte[] payload,
            @nullable String groupId);

    /**
     * An inbound MLS application message, still sealed, with the outer-envelope headers of
     * {@link #onMlsControlBundle}; {@code originalMessageId} marks a resend. The app must not block
     * the calling thread.
     */
    void onMlsCiphertext(int subId, String fromE164, String messageId, in byte[] ciphertext,
            @nullable String groupId, long eraId, in @nullable byte[] epochAuthenticator,
            @nullable String originalMessageId);

    /**
     * The provider replaced this line's MLS credential, so published KeyPackages carry a stale one.
     * The app re-reads the identity and republishes, off the calling thread.
     *
     * @param reason a short loggable cause
     */
    void onMlsIdentityChanged(int subId, String reason);

    /**
     * The downloaded ciphertext behind an {@link #onEncryptedGroupIcon} reference, sent only when
     * the fetch succeeded and the content is within the provider's size cap.
     *
     * @param contentType the literal {@code message/mls-ft}; the image type is in the key
     *     delivery's RCC.16 §7.8.1 FileInfo
     */
    void onEncryptedGroupIconContent(int subId, @nullable String groupId,
            @nullable String fromE164, @nullable String contentType, in byte[] ciphertext);
}
