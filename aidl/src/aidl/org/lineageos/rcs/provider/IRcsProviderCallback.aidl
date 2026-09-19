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

    // ================================================================
    // MLS (RFC 9420 / GSMA RCC.16).
    //
    // APPENDED AS A BLOCK, and it must stay one. aidl numbers each method
    // FIRST_CALL_TRANSACTION + n in DECLARATION order, so everything here sits
    // after every method above it. A new MLS method goes at the END of this
    // section -- never in the middle of it, and never above it.
    //
    // The provider carries MLS envelopes and does not parse them: it owns the
    // transport, the app owns the group state and the keys. Nothing below
    // decrypts anything.
    // ================================================================


    /**
     * An ENCRYPTED group subject arrived (RCC.16 §9.7.1.5, contract v39).
     *
     * <p>A plain rename surfaces through {@link #onGroupEvent}'s {@code name}. An encrypted one
     * cannot: the profile carries an inline {content_type, ciphertext} pair and the key
     * travels separately, in the commit's private message. So this hands the app the ciphertext and
     * the app decrypts it with the key it stored from the §7.8.1 FileInfo.
     *
     * <p>The provider deliberately does NOT decrypt — it owns the transport, not MLS, and it holds
     * no key.
     *
     * @param contentType the Annex C.2 encrypted MIME (Google Messages pins {@code message/mls-ft})
     * @param ciphertext  the encrypted subject as it appeared on the wire
     */
    void onEncryptedGroupSubject(int subId, @nullable String groupId, @nullable String fromE164,
            @nullable String contentType, in byte[] ciphertext);

    /**
     * An ENCRYPTED group ICON reference arrived (RCC.16 §9.7.1.4, contract v40).
     *
     * <p>Unlike the subject, an icon is not carried inline: the profile holds a REFERENCE of TWO
     * strings and the ciphertext itself lives on the File Transfer Server. The key arrives
     * separately, exactly as for the subject.
     *
     * <p><b>The order is PINNED since 2026-09-13: {@code first} is the reference's field 1 = the
     * CONTENT TYPE ({@code message/mls-ft}), {@code second} is field 2 = the URL.</b> This javadoc
     * said the opposite while it was unpinned — "an upload URL and a content-type/algorithm
     * descriptor" — and that reading was wrong. The provider's {@code EncryptedProfileFields}
     * records the pin; read it before changing either side, because the send path builds from the
     * same pin and a swap is SILENT on the wire.
     *
     * <p>The parameters stay POSITIONAL rather than being renamed to {@code contentType}/{@code url},
     * deliberately: the boundary should carry what was observed, not our labelling of it. The
     * provider logs a warning if a peer ever puts a URL in the content-type slot, so a disagreement
     * arrives as a line rather than as silence.
     */
    void onEncryptedGroupIcon(int subId, @nullable String groupId, @nullable String fromE164,
            @nullable String first, @nullable String second);

    /**
     * Inbound MLS control messages that MUST be applied IN ORDER (contract v42).
     *
     * <p>A metadata commit is two messages — the commit, and a private message delivering the key
     * its commitment commits to, encrypted at the POST-commit epoch. Delivering them as separate
     * {@link #onMlsControl} calls let the app dispatch each on its own thread, so the key routinely
     * raced the commit it depends on: both failed "future epoch", nothing drained, and the receiver's
     * own next commit was then built at a stale epoch and refused by its engine.
     *
     * <p>Serialising the apply was necessary but not sufficient — it prevents overlap, not
     * reordering. This carries the whole bundle in ONE call so order is a property of the payload
     * rather than of thread scheduling.
     *
     * <h2>The RCC.16 outer-envelope headers (contract-v47, rework 6.2)</h2>
     *
     * <p>{@code eraId} ({@code -1} when absent), {@code epochAuthenticator} and
     * {@code originalMessageId} are the {@code http://www.gsma.com/rcs/mls} CPIM headers read off the
     * OUTER envelope. They ride outside the ciphertext by construction, because their purpose is to
     * be readable BEFORE decryption — a receiver has to be able to tell "wrong era" from "corrupt"
     * without holding the key.
     *
     * <p>Until this existed, invariant 51's "missing headers ⇒ silent drop" had no subject on the
     * TACHYON leg — our PRIMARY transport — so every inbound MLS body was accepted unframed with no
     * epoch binding check at all. (The carrier CPM/MSRP leg has always been compliant.)
     *
     * <p>Observed on the wire 2026-08-01: an inbound Tachygram carried {@code ns=true
     * Era-ID=1}, so the server DOES relay these triples. Nothing is required or dropped here yet —
     * that observation was of a message from our own implementation, and requiring a header we have
     * not yet seen Google Messages populate would drop Google Messages traffic.
     *
     * <p><b>Why this interface changes a signature in place while {@code IRcsProvider} appends.</b>
     * {@code onMlsControl} already gained {@code groupId} the same way at v26; the callback is
     * implemented by the app and dispatched by the provider, so the provider is the side that knows
     * both versions and can gate. {@code IRcsProvider} is the reverse and must append.
     *
     * @param packedMessages the bare MLS messages, each a {@code [u32 BE len][bytes]} record, in the
     *                       order they must be applied
     */
    void onMlsControlBundle(int subId, @nullable String fromE164, @nullable String messageId,
            in byte[] packedMessages, boolean convergenceAck, @nullable String groupId,
            long eraId, in @nullable byte[] epochAuthenticator,
            @nullable String originalMessageId);

    /**
     * A peer reported that OUR message failed on THEIR side — RCC.16 §7.7.2.2
     * client-generated Negative-Delivery IMDN (contract v43).
     *
     * <p>This is the signal that resolves the divergence deadlock. Peer state is not
     * queryable, so a healthy member cannot detect a diverged one by looking; but the
     * diverged member CAN say so, and this is how. The member that knows emits, the
     * member that can act receives.
     *
     * <p>{@code failureReason} is one of the {@code MLS_FAIL_*} constants. The one that
     * matters most is {@link #MLS_FAIL_FAILED_TO_DECRYPT}, whose remedy the spec states
     * outright: the sender advances to the latest epoch and resends the message.
     *
     * <p>Note this is NOT the §10.2 FTD message, which is a separate heavier flow (an
     * MSRP Private-IM carrying a ResentMessage struct under one-to-one HPKE). This is
     * the IMDN, which is what a peer sends when it wants us to fix the group state.
     *
     * @param messageId     the message of OURS that failed on the peer
     * @param fromUri       the peer reporting the failure (the diverged member)
     * @param groupId       the group the failure happened in, or null for 1:1
     * @param failureReason one of MLS_FAIL_*
     */
    void onMlsNegativeDelivery(int subId, String messageId, @nullable String fromUri,
            @nullable String groupId, int failureReason);

    /**
     * An inbound MLS control message arrived (contract v21).
     *
     * <p>{@code payload} is the raw control payload as the transport delivered it — the app's MLS
     * engine unwraps and applies it. The provider does not parse MLS; it only recognises that this
     * inbound item is control traffic rather than an application message and routes it here.
     *
     * <p>Delivered on the provider's callback thread. The app must not block it — apply asynchronously.
     */
    void onMlsControl(int subId, String fromE164, String messageId, in byte[] payload,
            @nullable String groupId);

    /**
     * An inbound MLS APPLICATION message, still encrypted (contract v25).
     *
     * <p>The counterpart to {@link #onMlsControl}. Once the app owns the MLS engine the provider
     * cannot decrypt application traffic either, so the ciphertext is handed over intact and the app
     * decrypts, unframes and inserts it.
     *
     * <p>Delivered ONLY when the provider has no MLS session of its own — i.e. when the app really is
     * the owner. Before this existed, an inbound {@code message/mls} on a migrated device was dropped
     * while the peer was told it had been delivered (device-observed 2026-07-27).
     *
     * <p>Delivered on the provider's callback thread. The app must not block it — decrypt off-thread.
     *
     * <p>Carries the same contract-v47 RCC.16 outer-envelope headers as {@link #onMlsControl}, and
     * they matter more here: this is the arm where {@code originalMessageId} identifies a RESEND, and
     * Google Messages recognises one BEFORE decryption precisely because that header rides outside.
     */
    void onMlsCiphertext(int subId, String fromE164, String messageId, in byte[] ciphertext,
            @nullable String groupId, long eraId, in @nullable byte[] epochAuthenticator,
            @nullable String originalMessageId);

    /**
     * Our KDS MLS identity changed (contract v35).
     *
     * <p>Fired when the leaf certificate this device presents as its MLS credential is replaced —
     * a re-mint at the refresh window, or a Tachyon registration-id rotation, which invalidates the
     * held certificate outright because the certificate's CN binds it.
     *
     * <p>The app must care because the KeyPackages it has already PUBLISHED embed the OLD credential.
     * They stay claimable, so peers keep adding us on a certificate we no longer hold; the group is
     * then built on a stale leaf and a conformant peer rejects it. Nothing in either process notices
     * — which is why this is a push rather than something the app is left to discover.
     *
     * <p>On receipt the app re-reads the identity and republishes its pool. Delivered on the
     * provider's callback thread; do the work off it.
     *
     * <p>Contract-v35, APPENDED (ABI-stable; a v1..v34 app never sees it).
     *
     * @param reason short, loggable cause — e.g. {@code "reg-id-rotated"} or {@code "cert-reminted"}
     */
    void onMlsIdentityChanged(int subId, String reason);

    /**
     * The DOWNLOADED ciphertext behind an encrypted group ICON reference (contract v64, RCC.16
     * §9.7.1.4).
     *
     * <p>{@link #onEncryptedGroupIcon} reports the REFERENCE as it appeared on the
     * wire. This reports the bytes it points at, after the provider has fetched them. Both fire:
     * the reference always, the content only when the fetch succeeded — so an icon we could not
     * retrieve is visible as the first callback arriving without the second, rather than as
     * silence.
     *
     * <p><b>Why the provider fetches instead of handing over the URL.</b> The same reason it
     * uploads on the send side: the FT session credential and the per-SIM content-server config
     * live in the provider, and the app owns MLS. The app receives bytes and decrypts them, exactly
     * as it already does for the INLINE subject, which keeps the two receive
     * paths one shape instead of two.
     *
     * <p>{@code contentType} is the reference's field 1 — the literal {@code message/mls-ft}, NOT
     * the image's MIME. The real media type travels in the §7.8.1 {@code FileInfo} that carries the
     * key, so a receiver learns what it actually has from there.
     *
     * <p>Size-capped provider-side ({@code debug.rcs.mls_icon_max_bytes}, 256 KB) before it
     * crosses the Binder; an oversize icon is dropped with a log line rather than thrown as a
     * TransactionTooLargeException on the inbound path.
     *
     * <p>Contract-v64, APPENDED (ABI-stable; a v1..v63 app never sees it).
     */
    void onEncryptedGroupIconContent(int subId, @nullable String groupId,
            @nullable String fromE164, @nullable String contentType, in byte[] ciphertext);
}
