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
    const int TOS_ACCEPTED = 2;    const int TOS_DECLINED = 3;  // the line stays SMS-only

    // onGroupEvent() op: the wire operation numbers 7 to 12.
    const int GROUP_OP_CREATE         = 7;
    const int GROUP_OP_ADD_USERS      = 8;
    const int GROUP_OP_KICK_USERS     = 9;
    const int GROUP_OP_CHANGE_PROFILE = 10;
    const int GROUP_OP_CHANGE_ROLE    = 11;  // reported by op only
    const int GROUP_OP_CHANGE_INFO    = 12;

    /** An inbound 1:1 or group message. */
    void onIncomingMessage(in RcsIncomingMessage msg);

    /**
     * Status of one of our messages, one of STATUS_*. {@code errorReason} is free text on
     * STATUS_FAILED, null otherwise. {@code e2eeSchemeId} is the scheme the provider applied on the
     * wire, meaningful on STATUS_SENT; null is plaintext or unknown. The app stamps the padlock
     * from it. A caller that sends one argument fewer is read as null, which can only under-claim.
     */
    void onMessageStatus(int subId, String messageId, int status,
                         @nullable String errorReason, @nullable String e2eeSchemeId);

    /** A receipt for one of our messages; imdnType is IMDN_DELIVERED or IMDN_DISPLAYED. */
    void onImdnReceipt(int subId, String messageId, int imdnType);

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
     * added or removed numbers for ADD/KICK; neither is null. See docs/rcs/groups.md.
     */
    void onGroupEvent(int subId, int op, @nullable String groupId,
            @nullable String name, @nullable String conferenceUri,
            @nullable String requester,
            in List<String> members, in List<String> affectedMembers);

    /** Typing in a group; {@code fromUri} is the member who is typing. */
    void onGroupTyping(int subId, String groupId, String fromUri, boolean active);

    /**
     * A receipt from one member for one of our group messages; each member sends its own, so
     * {@code fromUri} names the member.
     */
    void onGroupImdnReceipt(int subId, String rcsMessageId, String fromUri,
            int imdnType);

    /**
     * An inbound file, already downloaded by the provider and exposed as a content URI and/or
     * descriptor. With a null {@link RcsIncomingFile#contentUri} and a thumbnail it is a preview
     * awaiting the user's accept; a second call carries the file.
     */
    void onIncomingMedia(in RcsIncomingFile file);

    /**
     * An emoji reaction on {@code targetMessageId}; {@code add} false retracts it.
     * {@code groupId} is set for a group reaction, null for 1:1. Not also delivered as a message.
     */
    void onIncomingReaction(int subId, String targetMessageId, String fromUri,
            String emoji, boolean add, @nullable String groupId);

    /**
     * A business-messaging message; the sender is an agent id, not a number, and the JSON is
     * unparsed. Not also delivered as a message, and the provider sends no receipt for it.
     */
    void onIncomingBotMessage(in RcsIncomingBotMessage msg);

    /** Provider-layer encryption state or availability changed. */
    void onE2eeStateChanged(int subId, in RcsE2eeInfo info);

}
