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
// IRcsProviderCallback.aidl -- provider -> main-app channel. Registered via
// IRcsProvider.attach(). v1 scope: 1-1 text + IMDN + typing + OTP request.
package org.lineageos.rcs.provider;

import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsIncomingFile;
import org.lineageos.rcs.provider.RcsIncomingBotMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsTosPrompt;
import org.lineageos.rcs.provider.RcsE2eeInfo;

/**
 * <b>ONEWAY.</b> Every method is asynchronous, and that is load-bearing rather than a
 * performance choice.
 *
 * <p>Android's cached-app freezer KILLS a frozen process that is the target of a
 * SYNCHRONOUS binder transaction — {@code am_kill: Sync transaction while frozen}. The
 * provider holds a raw binder handle obtained at attach() and calls straight through it,
 * so nothing in the framework knows a delivery is pending and nothing unfreezes the
 * target first (unlike a broadcast or a service start, which AMS mediates). A backgrounded
 * main app was therefore killed rather than delivered to — device-proven three times on
 * 2026-07-30, losing an MLS Welcome permanently each time.
 *
 * <p>Async transactions are QUEUED by the binder driver for a frozen process instead of
 * killing it. The queue is finite and shared per-process, though, and our control bundles
 * run to several KB, so this alone is not a durability guarantee — it is paired with the
 * contract-v44 {@code ackInboundMessages} handshake, which is what actually decides when
 * the server may stop redelivering.
 *
 * <p>Consequence for implementers: no method may return a value or throw usefully. A
 * {@code RemoteException} here means "could not be queued" (dead process, or async buffer
 * exhausted), NOT "the app failed to handle it".
 */
oneway interface IRcsProviderCallback {
    // ---- onMessageStatus status codes ----
    const int STATUS_SENT      = 1;
    const int STATUS_DELIVERED = 2;
    const int STATUS_DISPLAYED = 3;
    const int STATUS_FAILED    = 4;

    // ---- imdnType codes (shared by sendImdn() and onImdnReceipt()) ----
    const int IMDN_DELIVERED = 1;
    const int IMDN_DISPLAYED = 2;

    // ---- MLS client failure reasons (contract v43) ----
    //
    // RCC.16 v3.0 §7.7.2.3 defines the <mls-client-failure-reason> element as a
    // choice of exactly FIVE values; §7.6.3.2 gives their binary encoding in the
    // signed VerifiableDeliveryImdn struct. These constants use the BINARY codes so
    // the AIDL value is the value that goes on the wire once signing lands.
    //
    // Careful: the XML spelling and the enum spelling DIFFER for code 4 — the
    // element is <failed-to-decrypt> but the binary enum is failure_to_decrypt.
    // Mixing them up produces an IMDN the peer silently ignores.
    const int MLS_FAIL_UNKNOWN                  = 0;
    const int MLS_FAIL_MESSAGE_FROM_NON_MEMBER  = 1;
    const int MLS_FAIL_INVALID_CREDENTIAL       = 2;
    const int MLS_FAIL_INVALID_COMMIT           = 3;
    const int MLS_FAIL_FAILED_TO_DECRYPT        = 4;
    const int MLS_FAIL_COMMIT_IN_PRIVATEMESSAGE = 5;

    // ---- onRegistrationStateChanged state codes ----
    const int REG_UNREGISTERED = 0;
    const int REG_REGISTERING  = 1;
    const int REG_REGISTERED   = 2;
    const int REG_FAILED       = 3;

    // ---- onProvisioningStateChanged provState codes (Pev3 lifecycle) ----
    const int PROV_NOT_PROVISIONED      = 0;
    const int PROV_IN_PROGRESS          = 1;
    const int PROV_WAITING_FOR_OTP      = 2;
    const int PROV_CONFIGURED           = 3;
    const int PROV_DISABLED_BY_CARRIER  = 4;
    const int PROV_NEEDS_REPROVISION    = 5;
    const int PROV_WAITING_FOR_TOS      = 6;

    // ---- onCarrierTosStateChanged tosState codes ----
    const int TOS_NONE     = 0;  // no ToS gate on this carrier / not required
    const int TOS_REQUIRED = 1;  // gating ServerMessage present; SM blocked on consent
    const int TOS_ACCEPTED = 2;  // local latch set; config consumed -> CONFIGURED
    const int TOS_DECLINED = 3;  // user rejected; sub stays SMS-only

    // ---- onGroupEvent op codes (FLOW4b, on-wire GroupEventOp 7..12) ----
    const int GROUP_OP_CREATE         = 7;
    const int GROUP_OP_ADD_USERS      = 8;
    const int GROUP_OP_KICK_USERS     = 9;
    const int GROUP_OP_CHANGE_PROFILE = 10;
    const int GROUP_OP_CHANGE_ROLE    = 11;  // dljl unresolved; op-only
    const int GROUP_OP_CHANGE_INFO    = 12;

    /**
     * Inbound chat message pulled/streamed from Tachyon and decoded by the
     * provider. Maps to: Transport.Listener.onIncomingMessage.
     */
    void onIncomingMessage(in RcsIncomingMessage msg);

    /**
     * Outbound status for one of OUR sent messages: STATUS_SENT /
     * _DELIVERED / _DISPLAYED / _FAILED. errorReason carries the
     * (free-form) gRPC code + tachyonerror name on FAILED, null otherwise.
     */
    void onMessageStatus(int subId, String messageId, int status,
                         @nullable String errorReason);

    /**
     * Inbound IMDN receipt for one of OUR sent messages. imdnType is
     * IMDN_DELIVERED or IMDN_DISPLAYED.
     */
    void onImdnReceipt(int subId, String messageId, int imdnType);

    /**
     * Thread-wide IMDN receipt: a proto-IMDN from a peer acknowledging ALL of
     * our outstanding outgoing messages to that peer (no specific messageId).
     * The main app promotes its still-SENT/-DELIVERED rows for fromUri.
     * imdnType is IMDN_DELIVERED or IMDN_DISPLAYED.
     */
    void onImdnReceiptForPeer(int subId, String fromUri, int imdnType);

    /**
     * Registration/connection state. state is one of the REG_* constants.
     */
    void onRegistrationStateChanged(int subId, int state, @nullable String reason);

    /**
     * Provisioning lifecycle (Pev3). provState is one of the PROV_*
     * constants. caps is the latest per-sub capability snapshot (may be
     * null until CONFIGURED).
     */
    void onProvisioningStateChanged(int subId, int provState,
                                    in @nullable RcsProviderCaps caps);

    /**
     * The provider needs an OTP the user must type (Pev3 SMS verify, or
     * Tachyon Verify RPC). The main app shows entry UI and feeds the digits
     * back via IRcsProvider.submitOtp(). hint is an optional human string
     * (e.g. sender shortcode) for the UI.
     */
    void onOtpRequired(int subId, @nullable String hint);

    /** Inbound typing indicator from a peer. */
    void onTyping(int subId, String fromUri, boolean active);

    /**
     * Carrier/Google RCS ToS gate. tosState is one of the TOS_* constants.
     * tosState == TOS_REQUIRED => the provider is blocked on user consent and
     * `prompt` carries the dialog text (title/message/buttons). The other
     * states (TOS_NONE / TOS_ACCEPTED / TOS_DECLINED) are informational for the
     * settings row; `prompt` may be null when tosState != TOS_REQUIRED.
     *
     * <p>Contract-v2 addition. A v1 main app never sees this because the
     * provider only fires it when a gating ServerMessage is present.
     */
    void onCarrierTosStateChanged(int subId, int tosState, in @nullable RcsTosPrompt prompt);

    /**
     * Inbound group lifecycle event (FLOW4b, kind=GROUP(5) push). op is one of
     * the GROUP_OP_* constants (on-wire GroupEventOp 7..12). groupId is the
     * opaque GROUP_ID string (may be null on an unresolved op). name /
     * conferenceUri / requester may be null. members is the current member
     * MSISDN list (never null; may be empty). affectedMembers lists the
     * added/removed MSISDNs for ADD/KICK (never null; empty otherwise).
     *
     * <p>Contract-v3 addition. A v1/v2 main app never sees this (the provider
     * only fires group events to clients that attached at v3).
     */
    void onGroupEvent(int subId, int op, @nullable String groupId,
            @nullable String name, @nullable String conferenceUri,
            @nullable String requester,
            in List<String> members, in List<String> affectedMembers);

    /**
     * Inbound GROUP typing indicator (FLOW4b / Wave D). Same wire as the 1:1
     * onTyping (an im-iscomposing+xml is-composing event), but the message was
     * fanned out to a GROUP_ID: groupId is the 32-char lowercase-hex GROUP_ID
     * the indicator was routed to (to_id with idType=GROUP_ID) and fromUri is
     * the individual member who is typing. The main app maintains a per-sender
     * typing model (each sender auto-expires ~10s) and renders the multi-name
     * "X, Y are typing..." row.
     *
     * <p>Contract-v4 addition, APPENDED at the end of the interface so existing
     * methods keep their transaction codes (ABI-stable). A v1/v2/v3 main app
     * never sees this (the provider only fires group typing to clients that
     * attached at v4); the provider keeps firing the 1:1 onTyping for non-group
     * composing, byte-unchanged.
     */
    void onGroupTyping(int subId, String groupId, String fromUri, boolean active);

    /**
     * Inbound GROUP IMDN receipt (FLOW4b / Wave E). A group member has
     * delivered/displayed one of OUR sent GROUP messages. Same wire as the 1:1
     * onImdnReceipt (a message/imdn+xml routed to the originator), but for the
     * group case the receipt is PER-MEMBER: each other member sends its own
     * DELIVERED/DISPLAYED IMDN directly back to the message originator (us), so
     * the main app must know WHICH member sent the receipt to aggregate
     * "Read by N of M". rcsMessageId is the echoed message-id of our sent group
     * message (== the client-minted rcs_message_id). fromUri is the individual
     * member (the IMDN sender, the inbound message's from_id) in MSISDN form.
     * imdnType is IMDN_DELIVERED or IMDN_DISPLAYED.
     *
     * <p>Contract-v5 addition, APPENDED at the end of the interface so existing
     * methods keep their transaction codes (ABI-stable). A v1..v4 main app never
     * sees this (the provider only fires group IMDN to clients that attached at
     * v5); the provider keeps firing the 1:1 onImdnReceipt for non-group
     * receipts, byte-unchanged.
     */
    void onGroupImdnReceipt(int subId, String rcsMessageId, String fromUri,
            int imdnType);

    /**
     * Inbound 1-1 MEDIA (file / attachment) over RCS FT-HTTP (FLOW4c). By the time
     * this fires, the provider has ALREADY done the entire FT-HTTP receive INSIDE
     * itself: parsed the inbound {@code application/vnd.gsma.rcs-ft-http+xml}
     * descriptor, plain-GET'd the pre-signed download URL(s) (NO bearer — auth is
     * baked into the URL), written the temp file, and EAGERLY downloaded the
     * thumbnail on push receipt (per the {@code FtThumbnailSupported} capability —
     * brief §4e/§5). The resolved blob (and any thumbnail) is exposed as a
     * FileProvider {@code content://} URI/FD with a READ grant to the main-app
     * package and handed over as a {@link RcsIncomingFile}.
     *
     * <p>TRANSPORT-NEUTRAL: {@link RcsIncomingFile} carries ONLY a resolved content
     * URI/FD + real MIME + filename + size + caption (+ eager thumbnail URI) — NO
     * copper / googleapis download URL, NO {@code Bearer}, NO {@code rcs-ft-http+xml}
     * crosses this boundary. A future {@code TRANSPORT_CARRIER_MSRP} provider fires
     * the identical callback with the identical descriptor.
     *
     * <p>{@link RcsIncomingFile#contentUri} may be {@code null} when only the
     * eager thumbnail is present (file awaiting an explicit user ACCEPT); the main
     * app renders the thumbnail preview and a second {@code onIncomingMedia} (with
     * the file {@code contentUri} populated) follows on accept.
     *
     * <p>Contract-v6 addition, APPENDED at the end of the interface so every
     * existing method keeps its transaction code (ABI-stable). A v1..v5 main app
     * never sees this — the provider only fires it to clients that attached at v6;
     * the text/typing/imdn path on {@link #onIncomingMessage} (which stays
     * media-free, RcsIncomingMessage unchanged) is byte-unchanged for older clients.
     */
    void onIncomingMedia(in RcsIncomingFile file);

    /**
     * Inbound emoji REACTION (tapback) for a message (contract-v9). The provider
     * detected a reaction on the kind=36 plane by parsing the payload into its
     * content part + custom headers and finding the reactions-namespace
     * In-Reply-To-Message-Id + Message-Reply-Type pair (it ALSO accepts the
     * "urn:rcs:message:reactions:emotify:" dialect with a Reaction-Emotify-Id
     * header, and the header-less structured form {emoji,target-id,action,
     * source}); all three are normalized to this one callback.
     *
     * targetMessageId is the rcs message-id of OUR (or a peer's) message being
     * reacted to (the In-Reply-To-Message-Id; the SAME id space as
     * onImdnReceipt()). fromUri is the reacting peer in MSISDN form. emoji is the
     * raw glyph string (already un-wrapped from its U+200A hair-spaces).
     * add=true => add; add=false => remove (retract). groupId is the 32-hex
     * GROUP_ID when the reaction was fanned out to a group (so the main app can
     * aggregate per-member count + who), null for a 1:1 reaction.
     *
     * <p>Contract-v9 addition, APPENDED at the end of the interface so every
     * existing method keeps its transaction code (ABI-stable). A v1..v8 main app
     * never sees it — the provider only fires it to clients that attached at v9;
     * the reaction message itself is hidden, so it is NOT also delivered via
     * onIncomingMessage().
     */
    void onIncomingReaction(int subId, String targetMessageId, String fromUri,
            String emoji, boolean add, @nullable String groupId);

    /**
     * Inbound RCS Business Messaging (RBM) agent message (contract-v10).
     * RBM rides our Tachygram stream as GSMA JSON: a kind=36
     * message from an RCS_BOT sender ({@code <agent>@rbm.goog}) whose primary part
     * is content-type {@code application/vnd.gsma.botmessage.v1.0+json} (the rich
     * card) or {@code text/plain} (an agent fallback / confirmation line).
     *
     * <p>This is a SEPARATE callback from {@link #onIncomingMessage} because the
     * sender is NOT an E.164 number — it is a bot id — and because the raw GSMA
     * JSON is carried UNPARSED (the parser lives in the main app, Phase 2; the
     * renderer in Phase 4). The provider does NOT auto-fire a delivered IMDN for a
     * bot (a phone-style receipt to a bot address fails server-side); the
     * user-driven response is the postback (Phase 5), not a receipt.
     *
     * <p>Contract-v10 addition, APPENDED at the end of the interface so every
     * existing method keeps its transaction code (ABI-stable). A v1..v9 main app
     * never sees it — the provider only fires it to clients that attached at v10;
     * such a bot message is NOT also delivered via onIncomingMessage().
     */
    void onIncomingBotMessage(in RcsIncomingBotMessage msg);

    /**
     * The generic E2EE state changed (contract-v16) — user toggled it, or the
     * scheme/availability changed (e.g. registration gained/lost the E2EE capability).
     * The UI updates its toggle + any conversation/lock indicator. Scheme-blind: the
     * client reads {@link RcsE2eeInfo#schemeLabel} for display.
     *
     * <p>Contract-v16, APPENDED (ABI-stable; a v1..v15 main app never sees it).
     */
    void onE2eeStateChanged(int subId, in RcsE2eeInfo info);

}
