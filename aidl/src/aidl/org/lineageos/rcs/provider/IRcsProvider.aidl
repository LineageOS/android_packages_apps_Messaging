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
// IRcsProvider.aidl -- the bound-service entry point exposed by the RCS
// provider APK and consumed by the main app. v1 scope: 1-1 text + IMDN +
// typing + submitOtp. No proto types cross this boundary.
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

interface IRcsProvider {
    // ---- lookupRcsCapability() return codes ----
    /** Capability not yet known (no cache entry, lookup failed, or in flight).
     *  Callers keep today's optimistic behavior on UNKNOWN. */
    const int CAP_UNKNOWN  = -1;
    /** Destination is reachable on SMS only (not registered on RCS/Tachygram). */
    const int CAP_SMS_ONLY = 0;
    /** Destination is RCS-capable (registered on the Tachygram app inbox). */
    const int CAP_RCS      = 1;

    // ---- canServeSub() return codes (contract-v17) ----
    /** This transport does not serve this line's carrier. */
    const int LINE_INELIGIBLE = 0;
    /** This transport can serve this line. */
    const int LINE_ELIGIBLE   = 1;
    /** Possibly serviceable -- only the side-effecting startForSub can confirm
     *  (e.g. carrier-IMS eligibility depends on live modem/IMS state). */
    const int LINE_MAYBE      = 2;
    /** Eligibility not yet known. */
    const int LINE_UNKNOWN    = -1;

    /**
     * The contract revision this provider implements.
     *
     * <p>A LABEL, not the compatibility check. It tells a log line which revision each side
     * believes it has. What decides whether a pairing is SAFE is the runtime comparison of the
     * two DERIVED transaction layouts, taken from each side's own generated stub — see
     * {@code RcsContractLayout}. That cannot go stale, because nobody maintains it by hand.
     *
     * <p><b>Appending a method at the END of this interface does not renumber anything before
     * it</b>, so a peer built against an older revision keeps working for everything it already
     * knew and fails only the call it does not have. Inserting or reordering renumbers every
     * method after the change and silently re-points the peer at the wrong one. Add at the end.
     *
     * <p>This is the FIRST published revision. The interface has prior unpublished history; a
     * changelog of transaction layouts that were never visible outside one project is not
     * provenance, so the numbering restarts here.
     */
    int getContractVersion();

    /**
     * Static description of what this provider can do, independent of any
     * SIM: provider label, contract version, supported transports.
     */
    RcsProviderCaps getProviderCaps();

    /**
     * Register/attach a callback sink. Returns an opaque clientToken the
     * main app passes back on later calls so the provider can scope state
     * per bound client. Returns null if desiredContractVersion is
     * incompatible.
     */
    @nullable String attach(in IRcsProviderCallback cb, int desiredContractVersion);

    /** Release a client session previously returned by attach(). Idempotent. */
    void detach(String clientToken);

    /**
     * Begin (or resume) provisioning + registration for a SIM/sub. Async:
     * progress + terminal result arrive on the callback's
     * onProvisioningStateChanged / onRegistrationStateChanged.
     * Maps to: Pev3 bootstrap + Tachyon register + bind-stream start.
     */
    void startForSub(String clientToken, in RcsSubInfo sub);

    /**
     * Tear down registration + bind stream for a sub. Idempotent.
     * Maps to: TachyonTransport.stop() (+ optional Unregister).
     */
    void stopForSub(String clientToken, int subId);

    /**
     * Current per-sub capability/availability the provider has negotiated
     * (is RCS up on this MSISDN, what features). Maps to Pev3 Configuration
     * + LookupRegistered. May return null if the sub is unknown/not started.
     */
    @nullable RcsProviderCaps getCapabilitiesForSub(int subId);

    /**
     * Synchronous per-destination RCS capability lookup. The client calls
     * this OFF the main thread (it can hit the network: a Tachyon
     * LookupRegistered RPC). The provider caches results so repeat calls for
     * the same phoneE164 are cheap. Returns one of CAP_UNKNOWN /
     * CAP_SMS_ONLY / CAP_RCS. On CAP_UNKNOWN the caller keeps today's
     * optimistic send behavior. phoneE164 is the destination in E.164 form
     * ("+15551234567").
     */
    int lookupRcsCapability(String clientToken, int subId, String phoneE164);

    /**
     * Send a 1-1 text message. Returns a synchronous ACCEPTED/REJECTED with
     * a reason so the main app can fall back to SMS immediately; the
     * terminal status (SENT/DELIVERED/DISPLAYED/FAILED) arrives async via
     * onMessageStatus(). Maps to: SendMessage RPC.
     */
    RcsSendResult sendMessage(String clientToken, in RcsOutgoingMessage msg);

    /**
     * Send an IMDN delivered/displayed receipt for an inbound message.
     * imdnType is one of IRcsProviderCallback.IMDN_DELIVERED /
     * IMDN_DISPLAYED. Critical: Google downgrades a thread to SMS if the
     * displayed-IMDN isn't returned within ~30s.
     */
    void sendImdn(String clientToken, String originalMessageId,
                  String toUri, int imdnType);

    /**
     * Send a typing indicator (isComposing active/idle). Optional; the
     * provider may no-op.
     */
    void sendTyping(String clientToken, String toUri, boolean active);

    /**
     * Feed back a one-time-password the user typed, in response to a prior
     * onOtpRequired() callback. The OTP is captured by the main app (which
     * holds the default-SMS role) and forwarded to the provider, which owns
     * the Pev3/Tachyon verify flow. subId scopes which provisioning attempt
     * the OTP belongs to.
     */
    void submitOtp(String clientToken, int subId, String otp);

    /**
     * Feed back the user's ToS decision in response to a prior
     * onCarrierTosStateChanged(TOS_REQUIRED) callback. accept=true sets the
     * local consent latch and lets the provider consume the already-persisted
     * config to reach CONFIGURED -- with NO fresh ACS request (the auth token
     * already rode in the mType=1 config; carrier-ToS acceptance is a purely
     * local SM-progression latch). accept=false (or a later withdrawal) leaves
     * or returns the sub to SMS-only. subId scopes which provisioning attempt
     * the decision belongs to. Idempotent; a no-op for unknown clients (v1
     * compatibility). Contract-v2 addition.
     */
    void submitTosConsent(String clientToken, int subId, boolean accept);

    /**
     * Current ToS state for a sub, one of IRcsProviderCallback.TOS_* (NONE /
     * REQUIRED / ACCEPTED / DECLINED). Lets the settings row render without
     * re-crossing the prompt path. Contract-v2 addition.
     */
    int getTosState(int subId);

    // ================================================================
    // FLOW4b group surface (contract-v3 additions). All synchronous calls
    // hit the network (a Group/* gRPC RPC) and MUST be invoked off the main
    // thread. A group is addressed by the opaque GROUP_ID string the provider
    // returns from createGroup(); the server fans out to members (the main app
    // never enumerates the per-member wire). Inbound lifecycle pushes arrive
    // async on IRcsProviderCallback.onGroupEvent.
    // ================================================================

    /**
     * Create a Tachygram group (Group/CreateGroup). desiredGroupId is a
     * client-chosen opaque GROUP_ID; the server returns the authoritative
     * RcsGroupInfo (with the durable groupId + conferenceUri + members), or
     * null on failure. memberE164s are the initial members in E.164 form.
     * groupType is the wire GroupType enum (0 = default). subId scopes the
     * registration. Blocking; call off the main thread.
     */
    @nullable RcsGroupInfo createGroup(String clientToken, int subId,
            String desiredGroupId, String groupName,
            in List<String> memberE164s, int groupType);

    /**
     * Fetch the current RcsGroupInfo for a group (Group/GetGroupInfo). Returns
     * null on failure. groupId is the opaque GROUP_ID from a prior
     * createGroup()/onGroupEvent(). Blocking; call off the main thread.
     */
    @nullable RcsGroupInfo getGroupInfo(String clientToken, int subId,
            String groupId);

    /**
     * Which groups the Group service associates with THIS client (contract v49).
     *
     * <p>The MEMBERSHIP question, and deliberately distinct from getGroupInfo's per-id EXISTENCE
     * question. A group that EXISTS but does not list us is a different diagnosis, with a different
     * fix, from a group the service has no record of — and a FAILED_PRECONDITION on a mutation
     * could be either. Running both turns one bit into a 2x2.
     *
     * <p>The underlying request carries only the header — no group id, no filter — which is what
     * makes it membership-scoped by construction rather than by inference.
     *
     * <p>Returns the ids newline-separated, or null on failure. A flat string rather than a
     * parcelable list because the only consumer is a diagnostic and the ids are opaque here; the
     * provider logs the full typed entries. Blocking; call off the main thread.
     */
    @nullable String getGroupIds(String clientToken, int subId);

    /**
     * Add members to a group (Group/AddGroupUsers). The server fans out an
     * add push to members. Returns true if the RPC was accepted. Blocking;
     * call off the main thread.
     */
    boolean addGroupUsers(String clientToken, int subId, String groupId,
            in List<String> memberE164s);

    /**
     * Remove members from a group (Group/KickGroupUsers). Returns true if the
     * RPC was accepted. Blocking; call off the main thread.
     */
    boolean removeGroupUsers(String clientToken, int subId, String groupId,
            in List<String> memberE164s);

    /**
     * Rename / change the profile of a group (Group/ChangeGroupProfile).
     * Returns true if the RPC was accepted. Note: the profile-update
     * payload is not fully byte-verified yet, so a plain name change
     * is best-effort. Blocking; call off the main thread.
     */
    boolean renameGroup(String clientToken, int subId, String groupId,
            String newName);

    /**
     * Send a text message to a group (one Messaging/SendMessage to the single
     * GROUP_ID destination; the server fans out). Returns an
     * ACCEPTED/REJECTED RcsSendResult like sendMessage(); terminal status
     * arrives async via onMessageStatus(). clientMessageId correlates IMDN
     * receipts. Blocking; call off the main thread.
     */
    RcsSendResult sendGroupMessage(String clientToken, int subId,
            String groupId, String body, String clientMessageId);

    /**
     * Send an RCS location share (geopush, contract-v15). A kind=36
     * SendMessage carrying the GSMA application/vnd.gsma.rcspushlocation+xml body.
     * {@code recipient} is the E.164 (1-1) or the 32-hex GROUP_ID (when
     * {@code isGroup}). {@code accuracyMeters} &lt;= 0 emits a bare point, else a
     * circle. {@code label} is an optional place name (nullable). Returns an
     * ACCEPTED/REJECTED RcsSendResult like sendMessage(); terminal status arrives
     * async via onMessageStatus(); {@code clientMessageId} correlates IMDN
     * receipts. Blocking; call off the main thread.
     */
    RcsSendResult sendLocation(String clientToken, int subId, String recipient,
            boolean isGroup, double latitude, double longitude,
            double accuracyMeters, String label, String clientMessageId);

    // ================================================================
    // FLOW4c FT-HTTP media surface (contract-v4 addition). Synchronous;
    // hits the network (a Cronet resumable upload POST + a kind=36
    // SendMessage RPC) and MUST be invoked off the main thread.
    // ================================================================

    /**
     * Send a 1-1 FILE (media / attachment) over RCS FT-HTTP. The provider:
     *   1. reads the file's {@code ParcelFileDescriptor} into provider-local
     *      memory (the file is carried as a content URI / FD, NOT inline bytes
     *      — Binder has a ~1 MiB cap and media can be up to 100 MiB),
     *   2. uploads it via a Cronet resumable POST to the per-SIM copper
     *      {@code mFtHttpContentServerUri} (auth = the held register
     *      auth_token_payload — no new fetch),
     *   3. sends the resulting download URL as a kind=36 TACHYGRAM
     *      {@code SendMessage} whose body is the rcs-ft-http+xml descriptor.
     *
     * Returns a synchronous ACCEPTED/REJECTED {@link RcsSendResult} like
     * {@code sendMessage()}; terminal status (SENT/DELIVERED/DISPLAYED/FAILED)
     * arrives async via {@code onMessageStatus()}. The provider owns closing
     * the file's descriptor. Blocking; call off the main thread.
     */
    RcsSendResult sendFile(String clientToken, in RcsOutgoingFile file);

    /**
     * Accept an inbound FT-HTTP file the user chose to download (contract-v6).
     * On an inbound media push the provider eagerly fetches only the THUMBNAIL
     * (or, on a no-thumbnail line, delivers a placeholder); the main FILE blob is
     * downloaded ONLY when the user accepts (brief §4e). The main app calls this
     * with the {@code messageId} from the prior {@code onIncomingMedia()}; the
     * provider looks up the pending transport-internal descriptor (the pre-signed
     * URL NEVER crossed the AIDL), downloads the blob, and re-delivers the message
     * via {@code onIncomingMedia()} with the file {@code contentUri}/{@code fd} now
     * resolved. Idempotent / no-op for an unknown or already-downloaded messageId.
     * Async (network); returns immediately.
     */
    void acceptIncomingFile(String clientToken, int subId, String messageId);

    /**
     * Send a typing indicator (is-composing active/idle) to a GROUP (Wave D).
     * Identical wire to the 1:1 sendTyping (one Messaging/SendMessage with
     * content_type application/im-iscomposing+xml + routing EPHEMERAL), but the
     * single destination is the GROUP_ID instead of a phone; the server fans
     * out to members. Optional; the provider may no-op. Contract-v4 addition;
     * APPENDED at the end of the interface so existing methods keep their
     * transaction codes (ABI-stable).
     */
    void sendGroupTyping(String clientToken, String groupId, boolean active);

    /**
     * Send an emoji REACTION (tapback) to a prior message (contract-v9). The
     * reaction rides the ordinary kind=36 Tachygram SendMessage plane — NO new
     * RPC, NO new content-type: the payload is a text/plain content
     * part (the emoji wrapped in U+200A hair-spaces) plus custom headers
     * (namespace "urn:rcs:message:reactions:") carrying Message-Reply-Type
     * (message-reaction-add / -remove) and In-Reply-To-Message-Id (the TARGET
     * message's rcs message-id — the SAME id space as sendImdn()).
     *
     * targetMessageId is the rcs message-id of the message being reacted to.
     * toUri is the single SendMessage destination: a peer URI for 1:1, or the
     * 32-hex GROUP_ID for a group (the server fans out). groupId is non-null
     * iff this is a group reaction (the provider routes via the group path and
     * the value equals toUri); null for 1:1. emoji is the raw glyph string
     * (one EmojiCompat-valid sequence; the main app validates before calling).
     * add=true => message-reaction-add; add=false => message-reaction-remove
     * (retract). A REPLACE is the caller sending remove then add. Returns a
     * synchronous ACCEPTED/REJECTED RcsSendResult like sendMessage(); the
     * reaction message itself is hidden and renders as a chip on the target,
     * with no reaction-specific IMDN. Blocking; call off the main thread.
     *
     * <p>Contract-v9 addition, APPENDED at the end of the interface so every
     * existing method keeps its transaction code (ABI-stable). A v1..v8 main
     * app simply never calls it.
     */
    RcsSendResult sendReaction(String clientToken, int subId,
            String targetMessageId, String toUri, String emoji, boolean add,
            @nullable String groupId);

    /**
     * Resolve the BRAND identity for an RBM agent ({@code <agent>@rbm.goog}),
     * for rendering the bot conversation's header (name + logo + ✓ verified
     * badge + hero/color). The provider fetches the PUBLIC unauthenticated
     * {@code rbm.goog/bot?id=sip:<botId>&...&ho=<simMccMnc>} endpoint, parses the
     * GSMA PCC body, and caches by botId (TTL). The returned image URLs are
     * PUBLIC storage.googleapis.com links (no auth), so the main app fetches the
     * images directly. Returns null if the bot is unknown or the fetch failed.
     * Blocking (hits the network on a cache miss); call off the main thread.
     *
     * <p>Contract-v11 addition (RBM), APPENDED at the end of
     * the interface so every existing transaction code is unchanged. A v1..v10
     * main app simply never calls it.
     */
    @nullable RcsBotBrand getBotBrand(String clientToken, int subId, String botId);

    /**
     * Send an RBM postback to a bot ({@code <agent>@rbm.goog}) — the outbound side
     * of a suggestion-chip tap. The provider sends a Tachygram
     * SendMessage to the {@code RCS_BOT} address with the given {@code contentType}
     * (e.g. {@code application/vnd.gsma.botsuggestion.response.v1.0+json}) and
     * {@code jsonBody} (e.g. {@code {response:{reply:{displayText,postback:{data}}}}}).
     * {@code messageId} may be null (the provider mints one). No IMDN is raised.
     *
     * <p>Contract-v12 addition (RBM Phase 5), APPENDED at the end so every existing
     * transaction code is unchanged. A v1..v11 main app simply never calls it.
     */
    RcsSendResult sendBotPostback(String clientToken, int subId, String botId,
            String contentType, String jsonBody, @nullable String messageId);

    /**
     * RBM on/off. The main app's "RCS Business Messaging"
     * setting (default enabled) calls this; the provider persists it and
     * re-registers (registerTachygram, reusing the cached Pev3 token — no cold/OTP)
     * WITH or WITHOUT the chatbot capability tuples, so the provider starts/stops routing
     * RBM agent messages to us. Idempotent; returns after the persist (the
     * re-register runs async).
     *
     * <p>Contract-v13 addition, APPENDED at the end (ABI-stable; a v1..v12 main app
     * never calls it — its registration keeps the prior default).
     */
    void setChatbotEnabled(String clientToken, boolean enabled);

    /**
     * GENERIC end-to-end-encryption state for a sub (contract-v16). Provider-agnostic
     * AND scheme-opaque: the returned {@link RcsE2eeInfo} says whether E2EE is available,
     * the user-toggle state, an OPAQUE scheme id + human label (the contract enumerates
     * NO schemes — see RcsE2eeInfo). This reports the PROVIDER/TRANSPORT-layer engine
     * (e.g. Etouffee, "google.etouffee"); the generic GSMA RCS E2EE
     * (MLS) is app-layer and lives in the messaging app, which composes the two. The UI
     * renders the label + a toggle, scheme-blind. Never blocks (reads cached state).
     *
     * <p>Contract-v16, APPENDED (ABI-stable; a v1..v15 main app never calls it).
     */
    RcsE2eeInfo getE2eeInfo(int subId);

    /**
     * Set the user E2EE on/off toggle for a sub (contract-v16). The provider persists
     * it and re-applies (for Etouffee: re-register with/without the E2EE capability +
     * prekeys, reusing the cached token — no cold/OTP). Fires
     * {@link IRcsProviderCallback#onE2eeStateChanged} to all clients. No-op when E2EE
     * isn't available on this sub.
     */
    void setE2eeEnabled(String clientToken, int subId, boolean enabled);

    /**
     * Pre-flight, NON-side-effecting eligibility check for a self line
     * (contract-v17). Fast and synchronous: unlike startForSub (which commits
     * to a provisioning + registration run), this only answers "would this
     * transport serve this SIM?" so the registry can pick one transport per sub
     * without side effects. Returns one of LINE_INELIGIBLE / LINE_ELIGIBLE /
     * LINE_MAYBE / LINE_UNKNOWN. A transport whose eligibility isn't
     * synchronously knowable (e.g. carrier-IMS, which depends on live modem/IMS
     * state) returns LINE_MAYBE and lets startForSub be authoritative (design
     * D5). {@code sub} carries subId/msisdn/imsi/mccMnc/carrierPolicy.
     *
     * <p>Contract-v17 addition, APPENDED at the end so every existing method
     * keeps its transaction code (ABI-stable). A v1..v16 main app never calls it.
     */
    int canServeSub(in RcsSubInfo sub);

    /**
     * Decline an inbound file the user chose NOT to download (contract-v17).
     * The counterpart to acceptIncomingFile: for a session-oriented transport
     * (MSRP FT) a decline must send an actual SIP 603 Decline rather than
     * letting the INVITE time out; for FT-HTTP a provider may simply drop the
     * pending descriptor (never GET the URL). The main app calls this with the
     * {@code messageId} from the prior onIncomingMedia(). Idempotent / no-op for
     * an unknown or already-resolved messageId. Async; returns immediately.
     *
     * <p>Contract-v17 addition, APPENDED at the end so every existing method
     * keeps its transaction code (ABI-stable). A v1..v16 main app never calls it.
     */
    void rejectIncomingFile(String clientToken, int subId, String messageId);

    /**
     * CONFIRM that inbound messages have been APPLIED, so the provider may finally
     * acknowledge them to the server (contract-v44).
     *
     * <p><b>Why this exists.</b> The provider used to acknowledge an inbound message to
     * Tachyon as soon as it had handed it to a client, gated only on "is a client
     * attached". That gate is a stale predicate: binder death notification is
     * asynchronous, so a fan-out could fail with {@code DeadObjectException} and the
     * provider would still see {@code hasClients() == true} 49ms later and acknowledge
     * anyway — device-proven 2026-07-30, and the message is then gone from the server
     * inbox forever. An MLS Welcome lost that way cannot be recovered by any means
     * short of another era advance.
     *
     * <p>So delivery is now a two-phase handshake. The provider holds a pulled message
     * un-acknowledged until the main app says it has durably applied it; only then does
     * the server stop redelivering. An app that receives a callback and dies before
     * persisting simply never calls this, and the message comes back on the next pull.
     *
     * <p><b>Idempotent.</b> Unknown or already-acknowledged ids are ignored, so the app
     * may confirm the same id twice (a redelivery it had already applied) without harm.
     * Async; returns immediately.
     *
     * <p>Contract-v44 addition, APPENDED at the end so every existing method keeps its
     * transaction code (ABI-stable). A v1..v43 main app never calls it — for those the
     * provider falls back to acknowledging on successful hand-off, which is still
     * strictly better than the old presence gate.
     */
    void ackInboundMessages(String clientToken, int subId, in String[] messageIds);


}
