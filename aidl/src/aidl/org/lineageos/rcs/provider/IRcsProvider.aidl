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
import org.lineageos.rcs.provider.RcsMlsPeerCaps;
import org.lineageos.rcs.provider.RcsMlsIdentity;
import org.lineageos.rcs.provider.RcsMlsControlResult;
import org.lineageos.rcs.provider.RcsMlsClaimResult;
import org.lineageos.rcs.provider.RcsMlsTransportProfile;

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
     * Look up a peer's advertised <b>MLS capability feature-tags</b> (contract v18).
     *
     * <p>Companion to {@link #lookupRcsCapability}, which answers only "is this destination on RCS?".
     * This answers "what does it advertise about MLS?" and hands back the tags <b>verbatim</b> as an
     * open name→value map ({@link RcsMlsPeerCaps}) — the provider does NOT decide eligibility.
     *
     * <p><b>Why the decision must be the app's.</b> The eligibility policy has been wrong in both
     * directions already: the provider once required {@code mls-version} alone and so refused Google Messages
     * Google peers (which do not advertise it — it is Phenotype-gated on their side), while the app's
     * own tree hard-gated on tags that a Google peer likewise fails in 1:1. Keeping the raw tags on the
     * wire and the policy in one place stops those two copies drifting apart again.
     *
     * <p>Blocking; call off the main thread. Returns an <b>empty</b> {@link RcsMlsPeerCaps} when the
     * peer advertises nothing or the lookup fails — which means "nothing known", NOT "not capable".
     * Callers must not treat empty as a negative capability assertion.
     *
     * @param phoneE164 destination in E.164 form ("+15551234567")
     */
    RcsMlsPeerCaps lookupPeerMlsCaps(String clientToken, int subId, String phoneE164);

    /**
     * Is THIS provider provisioned to carry MLS for {@code subId} right now (contract v18)?
     *
     * <p>True iff the provider holds a fresh KDS certificate AND has uploaded KeyPackages — the same
     * predicate that gates whether it advertises the {@code +g.gsma.rcs.mls.*} feature-tags at
     * registration. That equivalence is deliberate: advertising MLS while unprovisioned promises a
     * peer it can claim KeyPackages that do not exist, which dead-ends its handshake AND gets the
     * registration rejected. So "would we advertise?" and "can we actually do MLS?" must be one
     * answer, not two.
     *
     * <p>Non-blocking; reads cached provisioning state. The peer-side half of the decision is
     * {@link #lookupPeerMlsCaps}; this is the self-side half. The app combines them — neither side
     * alone is sufficient.
     */
    boolean isMlsReady(String clientToken, int subId);

    /**
     * Create an MLS conversation from app-built artifacts (contract v21).
     *
     * <p>Every {@code byte[]} here is an <b>opaque MLS artifact the app produced</b>. The provider
     * places them in the right transport envelope fields and dials; it does not parse, validate or
     * reorder them. Maps to Tachyon {@code CreateMlsConversation} today, to a SIP INVITE carrying a
     * {@code WelcomeCommitBundle} on a carrier transport.
     *
     * @param mlsGroupId the app's chosen group id (the backend MAY constrain its format — a rejection
     *                   surfaces as {@code VERDICT_GROUP_ID_CHANGED}, not as a parse error here)
     */
    RcsMlsControlResult createMlsConversation(String clientToken, int subId, String peerE164,
            in byte[] mlsGroupId, in byte[] welcome, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] ratchetTree, int era, String contextId,
            @nullable String rcsGroupId);

    /**
     * Apply an MLS control message (commit) to an existing conversation (contract v21).
     *
     * <p>Opaque artifacts again. Maps to Tachyon {@code ApplyMlsControlMessage}. A member attempting
     * an external commit comes back as {@code VERDICT_EXTERNAL_COMMIT_REFUSED} — a spec-level fact
     * (RFC 9420 §12.4.3.2 restricts {@code ExternalInit} to non-members), not a backend string.
     */
    RcsMlsControlResult applyMlsControl(String clientToken, int subId, String peerE164,
            String controlMsgId, in byte[] groupInfo, in byte[] commit, in byte[] epochAuth,
            in byte[] ratchetTree, in byte[] baseEpochAuth, @nullable String rcsGroupId);

    /**
     * Fetch the server's view of a conversation's GroupInfo (contract v21).
     *
     * <p>Used by the app's recovery path to detect era/epoch divergence. The response is opaque
     * transport bytes the app parses; the provider does not interpret it.
     */
    RcsMlsControlResult getMlsGroupInfo(String clientToken, int subId, String peerE164);

    /**
     * Claim one KeyPackage for a peer from the key-delivery service (contract v21).
     *
     * <p>KDS is Google-service specific and stays provider-side; the app receives only the resulting
     * KeyPackage bytes. Returns null when none is available — which is a legitimate outcome (peer not
     * enrolled, pool exhausted), not necessarily an error.
     */
    /**
     * <b>@deprecated since contract v60 — do not wire new callers.</b> Superseded by
     * {@link #claimPeerKeyPackagesWithOutcome}.
     *
     * <p>Two faults, and they compound. It returns {@code null} for every unhappy path, so a caller
     * cannot tell "this peer has published nothing" from "the KDS refused US" — and one that reports
     * the first when the second is true names an innocent device. And it discards everything past
     * index 0 AFTER the claim has already spent the peer's pool, so a multi-device peer loses every
     * leaf but one at no saving.
     *
     * <p><b>It stays in the contract on purpose.</b> {@code ProviderTransport} still calls it as its
     * documented pre-v48 fallback, and that is the one legitimate remaining use; the app side has
     * otherwise RETIRED the spelling and guards it at zero
     * ({@code MlsClaimLedger.RETIRED_AIDL_SPELLINGS}). Removing it here would break a provider/app
     * version skew rather than prevent anything — this javadoc is the tripwire at the contract layer,
     * matching theirs at the source layer.
     */
    byte[] claimPeerKeyPackage(String clientToken, int subId, String peerE164);

    /**
     * Claim ALL of a peer's KeyPackages — one per DEVICE (contract v48, rework 9.1).
     *
     * <p>{@link #claimPeerKeyPackage} returns one, which meant a participant with two devices could
     * only ever get one leaf: the second device was silently absent from the group and could not
     * decrypt anything sent to it. The claimer has carried a per-participant LIST all along; the
     * contract was what collapsed it.
     *
     * <p>Returned <b>length-prefixed</b> ({@code [u32 BE length][bytes]} repeated), the same packing
     * {@code createGroupMulti} already uses across this boundary — AIDL's array-of-arrays support is
     * poor enough that a second convention would be worse than an explicit one.
     *
     * <p>Null or empty when the peer has none, which is a legitimate outcome (not enrolled, pool
     * exhausted) rather than an error.
     */
    byte[] claimPeerKeyPackages(String clientToken, int subId, String peerE164);

    /**
     * {@link #claimPeerKeyPackages}, but saying WHY — <b>contract v60</b>.
     *
     * <p><b>Prefer this wherever the result is reported to a human or recorded in a ledger.</b> The
     * {@code byte[]} form above returns {@code null} for every unhappy path, so a caller cannot
     * tell "this peer has published nothing" from "the KDS would not talk to US" — and one that
     * reports the first when the second is true <b>names an innocent device</b>. Device-measured
     * 2026-09-10: a claim that returned grpc-16 UNAUTHENTICATED, because OUR register token had
     * expired, was recorded as a fact about the peer's pool; the peer's pool was full and another
     * line claimed from it successfully seconds later.
     *
     * <p>The provider translates its transport status into {@code RcsMlsClaimResult.OUTCOME_*}
     * before it crosses — the same vocabulary firewall {@code RcsMlsControlResult} enforces. No
     * gRPC status or {@code tachyonerror} reaches this side.
     *
     * <p><b>{@code OUTCOME_SERVED} is a statement about the CLAIM, not about the packages.</b> A
     * pool can answer perfectly with a leaf certificate five weeks stale —
     * status OK, defect entirely in the bytes. Inspect the returned KeyPackage's certificate window
     * if you need to know they are usable; no outcome here can tell you.
     *
     * <p>Never returns null: an unhappy path is an outcome, which is the entire point.
     */
    RcsMlsClaimResult claimPeerKeyPackagesWithOutcome(String clientToken, int subId,
            String peerE164);

    /**
     * Drop the PROVIDER's peer&rarr;group record for a 1:1 conversation (contract v55).
     *
     * <p><b>Why the app cannot do this itself, and why it needs to.</b> The app's own
     * {@code forgetGroup} clears the ENGINE half only; the provider keeps its mapping, so the next
     * send re-establishes by REUSING the group id via an era advance. When the server is silently
     * refusing that advance — verdict OK, era never moves — re-entering the same path is the one
     * thing guaranteed not to help. Clearing BOTH halves makes the next send take the CREATE path,
     * which reclaims at {@code server_era + 1} and works; that recovery is device-proven, and the
     * peer's cost was measured to be nil beyond an ordinary era advance (it re-joins by Welcome on
     * the same group id and resumes, receipts included).
     *
     * <p>LOCAL ONLY. This does not tell the server to release anything and cannot un-burn an era —
     * it removes our record so re-establishment can discover the server's state afresh.
     */
    void mlsForgetConversation(String clientToken, int subId, String peerE164);

    /**
     * Drop the PROVIDER's record for a GROUP conversation (contract v59).
     *
     * <p><b>The hole this closes.</b> {@link #mlsForgetConversation} is 1:1 ONLY — it resolves
     * through {@code get1to1ByPeer}, which deliberately returns nothing for a GROUP record. The
     * app's automatic rebuild called it for every conversation shape and read the {@code void}
     * return as success, so a group rebuild dropped the ENGINE half, left the PROVIDER half
     * standing, and the guard written to catch exactly that ("the provider did not drop its
     * peer&rarr;group record") could never fire. The next establish then reused the group id via an
     * era advance — the state the rebuild exists to escape. Device-observed 2026-09-08 on group
     * {@code 32725c4d…}: {@code FALLBACK result rebuilt=false server era now=1}.
     *
     * <p><b>Returns the POSTCONDITION</b> — "the provider holds no record for this group" — which
     * the 1:1 method cannot: its caller-side boolean only ever reported that the binder call did not
     * throw. A caller that must not proceed on a half-cleared conversation needs the real answer.
     * "There was nothing to delete" is a true postcondition, so a join-only group (one we were
     * Welcomed into and never created) is not mistaken for a failed clear.
     *
     * <p>LOCAL ONLY, exactly as the 1:1 form: it releases nothing server-side and cannot un-burn an
     * era. It removes our record so re-establishment discovers the server's state afresh.
     *
     * @return true if the provider holds no record for this group afterwards.
     */
    boolean mlsForgetGroupConversation(String clientToken, int subId, String rcsGroupId);

    /**
     * Publish KeyPackages the APP generated to the key-delivery service (contract v27).
     *
     * <p>Generation must happen app-side because the KeyPackage PRIVATE keys have to live in the
     * engine that will later open a Welcome addressed to them. While the provider still generated
     * them, an inbound group Welcome failed {@code WelcomeKeyPackageNotFound} — the app simply did not
     * hold the private half (device-observed 2026-07-27). Building the KDS request and the upload
     * itself stay here: that is credentials and sockets, not MLS.
     *
     * @param keyPackages length-prefixed claimable KeyPackages, as the engine emits them
     * @param lastResort  the dedicated last-resort KeyPackage (carries the RFC-9420 0x000A ext); the
     *                    KDS slot rejects a plain KeyPackage here
     * @return true if the KDS accepted the upload
     */
    boolean uploadKeyPackages(String clientToken, int subId, in byte[] keyPackages,
            in byte[] lastResort);

    /**
     * The SERVER's era and epoch for a conversation (contract v28).
     *
     * <p>Returns {@code [era, epoch]}, or null if the server has no group / the call failed. The
     * provider parses the GroupInfo response because that is Tachyon proto shape; deciding what a
     * divergence MEANS (behind / ahead / era gap) is MLS semantics and belongs to the app.
     */
    long[] getMlsServerEraEpoch(String clientToken, int subId, String peerE164,
            @nullable String rcsGroupId);

    /**
     * The MLS enrolment identity: leaf certificate, chain and private key (contract v31).
     *
     * <p><b>Not migration scaffolding — a PERMANENT channel.</b> It was removed once as such and had
     * to come back: the KDS leaf is minted and REFRESHED provider-side (a ~75-day window, re-minted
     * before expiry by MlsRefreshWorker), while the engine that must use it lives in the app. Without
     * a way to pick up a refreshed identity the app would keep a snapshot until it expired and MLS
     * would simply stop, roughly two and a half months later, with nothing to point at.
     *
     * <p>Returns key material, so it is gated — like every method here — behind
     * {@code BIND_RCS_PROVIDER} at {@code signature|privileged}. Do not weaken that.
     */
    RcsMlsIdentity exportMlsIdentity(String clientToken, int subId);

    /**
     * What THIS transport does about MLS arbitration (contract v22).
     *
     * <p>The app's recovery machinery — send-gate, era tie-break, resync ladder — was tuned against
     * one backend. These flags let it adapt instead of assuming. Notably, a transport that reports
     * {@code requiresConvergenceAck == false} must never have a send-gate opened against it: there is
     * no signal that could ever close it, so the conversation would stall forever.
     *
     * <p>Describes an INSTANCE, not a transport family — see {@link RcsMlsTransportProfile}. Never
     * returns null; an implementation that has not characterised itself returns the conservative
     * default (nothing arbitrated, nothing awaited), which is chosen so a missing profile cannot hang
     * a send.
     */
    RcsMlsTransportProfile getMlsTransportProfile(String clientToken, int subId);

    /**
     * Send an ALREADY-ENCRYPTED MLS application message verbatim (contract v23).
     *
     * <p>The completion of the ownership flip. {@code sendMessage} with an MLS scheme has the PROVIDER
     * encrypt; this method has it send bytes the app already sealed with its own engine, touching
     * nothing but the envelope.
     *
     * <p>The provider MUST NOT re-frame, re-stamp or re-encrypt {@code ciphertext}. Doing any of those
     * would reintroduce exactly the split this migration removed: the generation counter and the
     * AuthenticatedData are bound together at seal time by whoever owns the ratchet, and a second
     * party touching either produces a message that authenticates on one route and silently fails on
     * the other.
     *
     * <p>{@code messageId} MUST be the same id the app bound into the AAD — a Google Messages peer
     * cross-checks the envelope id against the AAD id and drops a mismatch.
     *
     * <p>{@code era} and {@code epochAuth} MUST come from the engine that SEALED the ciphertext. The
     * provider used to derive both from its own MLS session; with the app owning the engine that
     * session does not exist, and deriving locally would either omit the stamps (→ INVALID_ARGUMENT
     * incorrect-epoch-authenticator) or stamp a stale value that disagrees with the payload.
     */
    RcsSendResult sendMlsCiphertext(String clientToken, int subId, String peerE164,
            in byte[] ciphertext, String messageId, int era, in byte[] epochAuth);

    /**
     * Send app-sealed MLS ciphertext to an RCS GROUP (contract v32).
     *
     * <p>The group counterpart of {@link #sendMlsCiphertext}. Addressed to the group endpoint so the
     * server fans it out; the app supplies era + epoch-authenticator exactly as for 1:1. Without this
     * the app can join a group and follow its commits but cannot speak in it.
     */
    RcsSendResult sendGroupMlsCiphertext(String clientToken, int subId, String rcsGroupId,
            in byte[] ciphertext, String messageId, int era, in byte[] epochAuth);

    /**
     * The MLS group id this provider already holds for {@code peerE164} (contract v24).
     *
     * <p>The missing half of state migration: step 3b moves the engine state, but the app still needs
     * to know WHICH group id belongs to a peer in order to adopt it. Without this it creates a second
     * group and the server refuses ("Era changed from N to 1"), burning an era for nothing.
     *
     * @return the group id, or null when no conversation exists for that peer
     */
    byte[] getMlsGroupIdForPeer(String clientToken, int subId, String peerE164);

    /**
     * Send a FORCED-PLAINTEXT delivery receipt for MLS reconciliation (contract v58).
     *
     * <p>When a peer that Google Messages believes is MLS-established sends us a message we hold no group
     * for (peer reinstalled and lost its group, Google Messages' encryption_protocol bit still set), we
     * cannot sign an MLS FTD — Google Messages rejects an unsigned one at CANNOT_PARSE_MESSAGE. A PLAINTEXT
     * delivery IMDN on the RCS report channel is a protocol mismatch that Google Messages accepts unsigned
     * and DOWNGRADES on, clearing the stale belief and re-Welcoming (device-proven).
     * Unlike {@link #sendImdn}, this NEVER Etouffee-encrypts — the reconciliation
     * only travels the plaintext channel, and a re-provisioned peer's Etouffee may itself be
     * desynced.
     */
    void sendReconciliationReceipt(String clientToken, String originalMessageId, String toUri);

    /**
     * Add members to an <b>MLS</b> group, carrying the Add commit in the SAME request
     * (contract v51).
     *
     * <h2>Why this cannot be the plain {@link #addGroupUsers} plus a separate commit</h2>
     *
     * <p>Device-proven 2026-08-05, single variable, one group, two minutes apart:
     * {@code AddGroupUsers} SUCCEEDS on a plaintext conversation and returns
     * {@code FAILED_PRECONDITION} on the same conversation once it is MLS, with the request bytes
     * identical in shape. Tachyon requires the commit in the {@code AddGroupUsers} request's
     * field 6 for an MLS conversation.
     *
     * <p>And the two halves cannot be ordered: RCS-add-first was established because
     * Tachyon refuses a membership commit for someone who is not yet an RCS participant, while this
     * result shows the RCS add is refused without the commit. <b>Neither can go first</b>, which is
     * what field 6 exists for — the same way {@link #changeGroupSubjectMls} carries a rename and its
     * commit together.
     *
     * <p>Every {@code byte[]} is an opaque artifact the app's engine produced by building (and
     * locally applying) an Add commit. The provider frames them and dials; it does not parse or
     * reorder them. <b>The app must be able to roll back</b>: it has already advanced its epoch by
     * the time this is called, so a non-OK verdict means restore the pre-commit snapshot.
     *
     * @param welcome       the Welcome for the newly added member(s)
     * @param commit        the Commit the existing members apply
     * @param groupInfo     the post-commit GroupInfo
     * @param epochAuth     the POST-commit epoch authenticator (the confirmation tag)
     * @param ratchetTree   the exported post-commit ratchet tree
     * @param baseEpochAuth the PRE-commit epoch authenticator — the server matches it against its
     *                      current epoch to validate the commit's base; post-commit is rejected
     * @param controlMsgId  the control-message id bound into the commit's MLS AAD. The server
     *                      cross-checks the two and names a mismatch, so this MUST be the id the
     *                      app used when it built the AAD — never freshly minted here
     */
    RcsMlsControlResult addGroupUsersMls(String clientToken, int subId, String rcsGroupId,
            in List<String> memberE164s, in byte[] welcome, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            String controlMsgId);

    /**
     * Remove members from an <b>MLS</b> group, carrying the Remove commit in the SAME request
     * (contract v52 — the mirror of {@link #addGroupUsersMls}).
     *
     * <p>Same reason as the add: on an MLS conversation the bare membership RPC is refused, and the
     * two halves cannot be ordered because each is a precondition of the other. One request carries
     * both.
     *
     * <p><b>The wire shape is NOT symmetric with the add.</b> Three group operations select three
     * different arms of the request's control-message union:
     *
     * <pre>
     *   ADD / CREATE  → arm 2             commit + welcome
     *   KICK OTHERS   → arm 3             commit, no welcome    ← this method
     *   SELF-LEAVE    → arm 1 (raw bytes) a PROPOSAL, not a commit
     * </pre>
     *
     * <p>A removal produces no Welcome, which is why it uses the apply-shaped arm — hence no
     * {@code welcome} parameter here. Building it on the add's arm "for symmetry" would not fail
     * loudly: both arms are structurally valid, so it would mis-route and look like it worked.
     *
     * <p>The commit also rides at the {@code KickGroupUsers} request's field <b>9</b>, not field 6
     * — each Group RPC puts the control message at a different number, so the add's number must
     * not be carried across.
     *
     * @param commit        the Remove commit the remaining members apply
     * @param groupInfo     the post-commit GroupInfo
     * @param epochAuth     the POST-commit epoch authenticator
     * @param prevEpochAuth the PRE-commit epoch authenticator
     * @param controlMsgId  the id bound into the commit's MLS AAD — never freshly minted here
     */
    RcsMlsControlResult removeGroupUsersMls(String clientToken, int subId, String rcsGroupId,
            in List<String> memberE164s, in byte[] commit, in byte[] groupInfo,
            in byte[] epochAuth, in byte[] prevEpochAuth, String controlMsgId);

    /**
     * LEAVE an MLS group ourselves — {@code KickGroupUsers} carrying a by-reference SelfRemove
     * PROPOSAL (contract v53). The third of the three group shapes.
     *
     * <p>A self-leave is not a commit: MLS forbids committing your own removal, so it is a proposal
     * a REMAINING member commits. Google Messages ships it on the {@code KickGroupUsers} request's
     * field 9 through <b>arm 1</b> of that union — raw bytes, no submessage — where the add uses
     * arm 2 and the kick uses arm 3.
     *
     * <p><b>This supersedes our previous outright refusal.</b> We had concluded self-leave was
     * inexpressible because {@code ApplyMlsControlMessage} is commit-only, which is true and about
     * the wrong RPC: the proposal's carrier is the Group service, not the Mls service.
     *
     * <p><b>The caller must be able to roll back.</b> Caching a SelfRemove sets
     * {@code commit_required()}, which blocks every subsequent send on that conversation and
     * persists across restarts — host-proven. So a refusal here must restore the pre-proposal
     * snapshot, or one tap permanently silences the conversation.
     *
     * @param proposal      the TLS-serialized SelfRemove proposal
     * @param prevEpochAuth the epoch authenticator
     * @param controlMsgId  the id bound into the proposal's MLS AAD
     */
    RcsMlsControlResult selfLeaveGroupMls(String clientToken, int subId, String rcsGroupId,
            in byte[] proposal, in byte[] prevEpochAuth, String controlMsgId);

    /**
     * Change an ENCRYPTED group subject and commit its RCC.16 commitment ATOMICALLY
     * (contract v36).
     *
     * <p>RCC.16 §9.7.1.5 reads like two steps — set the content, commit the commitment — but on
     * Tachyon they are ONE request: the encrypted subject and the commit travel together in a single
     * ChangeGroupProfile, and the server validates the commitment against the encrypted-content
     * field in that same request. Doing them separately (rename, then ApplyMlsControlMessage) is
     * refused {@code mlsError 5 mismatched-rcs-group-state} — device-proven 2026-07-28, and Google Messages'
     * own guard names the pairing ("subject is not encrypted, but the request already has a
     * MlsControlMessage").
     *
     * <p>The app supplies the MLS artifacts because the app owns the engine; the provider owns only
     * the request framing and the socket.
     *
     * <p><b>The key travels IN THIS REQUEST (contract v37).</b> {@code privateMessages} is the
     * commit bundle's field-2 slot, and for a metadata commit it is MANDATORY, not optional:
     * Google Messages' own validator throws {@code "commitBundle missing privateMessages"} without it. The commit at
     * field 1 carries a {@code subject_commitment} (0xF006) over a KEY, and this field is where that
     * key is actually delivered — so a request with the commit but not the delivery commits to a key
     * it never ships. That earns {@code INVALID_ARGUMENT tachyonerror=1}, and it is why every
     * byte-level fix to the CIPHERTEXT failed to move the error: the error was never about the blob.
     * Google Messages' plain-commit builder correctly leaves field 2 unset, because a membership
     * commit delivers no key.
     *
     * <p>We previously sent the key as a SEPARATE application message after this call returned —
     * out-of-band, where no Google Messages peer looks, and non-atomic besides (a failed second send left
     * peers holding a subject they could not decrypt). That leg is gone.
     *
     * @param contentType the Annex C.2 encrypted MIME — NOT text/plain
     * @param ciphertext  the Annex C.2 encrypted subject
     * @param privateMessages an MLS PrivateMessage, encrypted at the POST-COMMIT epoch, carrying the
     *                        subject key the commit's commitment commits to. Members decrypt it
     *                        after applying the commit. Empty is accepted by this interface but the
     *                        server is expected to refuse it.
     * @param controlMsgId    the control-message id — and it MUST equal the message id bound
     *                        into {@code privateMessages}' MLS AAD (contract v38). The server
     *                        cross-checks the two and rejects a mismatch by name:
     *                        {@code "Invalid AAD: expected/received: message ID <ctrl>/<aad>"}.
     *                        That is why the app supplies it instead of the provider minting a
     *                        UUID: only the app can bind it into the AAD at encrypt time, and an id
     *                        generated on this side could never match.
     * @return the spec-level verdict, as for the other MLS control calls
     */
    /**
     * Fetch the commits we MISSED, so a conversation that fell behind can catch itself up
     * (contract v41) — Google Messages' ENHANCED SELF-HEAL.
     *
     * <p>A member that missed commit N cannot apply N+1: MLS forbids skipping intermediates. Until
     * now our only recovery was a peer re-Welcoming us, which the stranded member cannot ask for.
     * Google Messages instead FETCHES the missed commits and replays them, keeping its own leaf and tree
     * position — self-initiated, no peer action.
     *
     * <p>The request anchors at our current epoch (era + epoch authenticator);
     * the server answers with the paginated set of commits we are behind by. The provider returns
     * them opaquely and never applies them — the app owns the engine.
     *
     * @param epochAuthenticator our current 32-byte epoch authenticator (the same value the apply
     *                           path sends); without it the server cannot tell where we are
     * @return the missed commits, each a {@code [u32 BE len][bytes]} record, IN ORDER — replaying
     *         them out of order cannot work. Empty means we are already current; null means the
     *         fetch failed.
     */
    byte[] fetchMissedCommits(String clientToken, int subId, String peerE164, String rcsGroupId,
            long era, in byte[] epochAuthenticator);

    /**
     * The server's current epoch AUTHENTICATOR for a conversation (contract v42).
     *
     * <p>The only value that answers "am I in the same group state as the server". Era and epoch
     * NUMBERS can match while the state differs — device-proven — so every check built on numbers
     * is blind to a divergent branch.
     *
     * @return the 32-byte authenticator, or null if it could not be fetched
     */
    byte[] fetchServerEpochAuthenticator(String clientToken, int subId, String peerE164,
            String rcsGroupId);

    /**
     * An ENCRYPTED group SUBJECT and its RCC.16 commitment, in ONE {@code ChangeGroupProfile}
     * (contract v36/v37/v38). The ciphertext, the commit and the key delivery all ride in that one
     * request — splitting any of them off is refused.
     *
     * <p>{@code contentType} is the literal {@code message/mls-ft}, not the subject's own type.
     * {@code controlMsgId} MUST be the id the app bound into the private message's MLS AAD; the
     * server cross-checks the two and names a mismatch.
     *
     * <p>Its ICON sibling is {@link #changeGroupIconMls} — the two are the same flow over two
     * different fields of the same request, so a change to one usually belongs in both.
     */
    RcsMlsControlResult changeGroupSubjectMls(String clientToken, int subId, String rcsGroupId,
            String contentType, in byte[] ciphertext, in byte[] groupInfo, in byte[] commit,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            in byte[] privateMessages, String controlMsgId);

    /**
     * Tell a peer that ITS message failed on OUR side — RCC.16 §7.7.2.2 client-generated
     * Negative-Delivery IMDN (contract v43).
     *
     * <p>The app calls this, not the provider, and that split is deliberate: only the MLS
     * owner knows whether a failure PERSISTED. The spec does not want a report on first
     * failure — §10.2 sends the report after the Self-Heal procedure completes, for the
     * messages that still could not be decrypted, and §6.2 likewise says self-heal first
     * and report only "if the validation failure persists". A provider that reported
     * eagerly would tell every peer we are broken during a recovery that was about to
     * succeed.
     *
     * <p>Replaces a worse behaviour: the provider used to answer an undecryptable MLS
     * message with a plaintext DELIVERED IMDN, which told the sender everything was fine
     * and guaranteed the message was never resent.
     *
     * <p><b>{@code eraId} is REQUIRED for a Google Messages peer to act on this.</b> Proven
     * 2026-07-31: a MISSING Era-ID defaults to 1, the native group-state lookup at
     * era 1 misses on a group at any other era, and the report terminates FAIL_NO_RETRY before a
     * resend is considered. Pass {@code -1} only to reproduce the old behaviour.
     *
     * <p>{@code failureReason} is one of the {@code IRcsProviderCallback.MLS_FAIL_*}
     * constants. {@code toUri} is the sender of the original message; {@code groupId} is
     * non-null when the original arrived in a group.
     */
    /**
     * <p><b>Contract v57</b> adds {@code derivedContentSigB64} — the §7.6.3.2
     * {@code MLS-Derived-Content-Signature} for the NEGATIVE receipt.
     *
     * <p>It is the app's to compute because the app owns the engine, and it has to cross this
     * interface because the provider is what frames and sends the IMDN. We shipped unsigned
     * negatives for a long time on the reading that §12.7 tolerates them — the spec does, and
     * Google Messages does not: Google Messages has a dedicated negative-receipt signer and always signs, and its
     * host admits an unsigned one only because the negative arm lacks the null-check its display and
     * positive arms have. Ours were therefore accepted and then rejected
     * ({@code CANNOT_PARSE_MESSAGE}) after five other candidates had been eliminated on the wire.
     * Pass null to reproduce the old unsigned behaviour.
     */
    void sendMlsNegativeDeliveryImdn(String clientToken, int subId, String originalMessageId,
            String toUri, @nullable String groupId, int failureReason,
            long eraId, @nullable String epochAuthB64, @nullable String derivedContentSigB64,
            @nullable String receiptMessageId);

    /**
     * Send a POSITIVE-delivery or DISPLAY IMDN on an MLS conversation, carrying the RCC.16 §12.1 MLS
     * header sidecar (contract-v45).
     *
     * <p>The ordinary {@link #sendImdn} cannot serve this. A Google Messages receiver routes receipts through
     * §12.7 and does NOT treat the three shapes alike: the negative-delivery arm is deliberately
     * permissive, while positive and display are strict — missing
     * {@code MLS-Derived-Content-Signature} and the peer's reader returns null before the receipt
     * reaches validation. Ours went out on the ordinary path carrying none of the headers, so every
     * positive/display receipt we have ever sent on an MLS conversation was silently dropped.
     *
     * <p>The app supplies the stamps because the app owns the engine: the era, the epoch
     * authenticator and the signature must all come from the SAME group state, and the provider has
     * no session to derive them from. {@code signatureB64} is base64 of the {@code rcs_signature}
     * PublicMessage over the §7.6.3 VerifiableDerivedContent; a null one is sent anyway and logged,
     * because a receipt a peer drops is still better diagnostics than no receipt at all.
     *
     * <p>Contract-v45 addition, APPENDED at the end so every existing method keeps its transaction
     * code (ABI-stable).
     */
    void sendMlsImdn(String clientToken, int subId, String toUri, String originalMessageId,
            int imdnType, long eraId, String epochAuthB64, String signatureB64,
            String receiptMessageId);

    /**
     * {@link #sendMlsImdn} for a receipt whose original message arrived in a GROUP
     * (contract-v46).
     *
     * <p><b>Why a second method rather than a parameter on the first.</b> {@code sendMlsImdn} has a
     * transaction code that a v45 main app already calls; widening its parameter list would leave
     * such an app marshalling a shorter payload into a reader expecting a longer one. Appending is
     * the discipline the rest of this file follows for the same reason.
     *
     * <p><b>The bug this exists to fix.</b> There was no group id ANYWHERE on the positive-receipt
     * path — not in {@code RcsTransport.sendImdn}, not in {@code sendMlsImdn}, not in the transport
     * call. So a receipt for a message received in a group resolved its conversation BY PEER, found
     * our 1:1 with that peer, and minted the era, the epoch authenticator and the signature from it,
     * after which the provider USER-routed the result.
     *
     * <p>Device-proven against Google Messages on 2026-08-01: our receipt bound MLS group
     * {@code 7a41c509-…} (the 1:1) and era 4 (the 1:1's era) for a message Google Messages had sent one
     * second earlier in group {@code 65cb80d6…} at era 1. Google Messages answered
     * {@code ZINNIA_FAILURE_GROUP_ID_MISMATCH} → {@code FAIL_NO_RETRY}, and the follow-on lookup then
     * ran with an EMPTY group id, so the mismatch poisons the rest of that operation. Two independent
     * values — the group id and the era — both naming the wrong conversation is what ruled out a
     * one-field slip.
     *
     * <p>Same defect class as the negative-delivery IMDN, which already carries
     * its {@code groupId}. The positive path never got the same treatment, and nothing connected them
     * because they are different send sites. §12.7 is why only this one surfaced as a hard failure:
     * the positive leg is the STRICT one — it drops on a missing signature and on an unresolved
     * group, where the negative leg tolerates both.
     *
     * <p>{@code rcsGroupId} non-null means route the report to the GROUP destination
     * ({@code IdType=GROUP_ID}) and stamp it from the GROUP's state. Null behaves exactly like
     * {@link #sendMlsImdn}.
     */
    void sendMlsGroupImdn(String clientToken, int subId, String toUri, @nullable String rcsGroupId,
            String originalMessageId, int imdnType, long eraId, String epochAuthB64,
            String signatureB64, String receiptMessageId);

    /**
     * The server's view of a GROUP's GroupInfo (contract v44) — the group-addressable form of
     * {@link #getMlsGroupInfo}, which only ever took a peer.
     *
     * <p><b>Why this is not a cosmetic widening.</b> The GroupInfo reachable through the SELF-HEAL
     * fetch ({@code fetchMissedCommits} slot 0) and the one this RPC returns are <em>different
     * objects</em>, and the difference blocks a repair. A resync EXTERNAL COMMIT requires the
     * GroupInfo to carry the RFC 9420 {@code external_pub} extension — without it the engine cannot
     * derive the init secret and refuses to build, {@code MissingExternalPubExtension}. The self-heal
     * fetch's GroupInfo does not carry it (device-measured 2026-08-16 on group B
     * {@code 3256689f…}: 235B, extensions {@code 0xF001} only), so the only way to build a resync is
     * to ask for the group's GroupInfo directly.
     *
     * <p>This is the concrete form of a divergence that had been recorded but never checked — the
     * note in {@code groupInfoHasEndMls} says outright that "nobody had checked those return the
     * same object". They do not.
     *
     * <p>The provider side has been group-addressable since contract v33 (the transport RPC takes a
     * group id and {@code MlsControlClient.getGroupInfo} already passes it); only this binding
     * dropped it.
     * Response is opaque transport bytes the app parses; the provider does not interpret it.
     */
    RcsMlsControlResult getMlsGroupInfoForGroup(String clientToken, int subId,
            @nullable String peerE164, String rcsGroupId);

    /**
     * An ENCRYPTED group ICON and its RCC.16 commitment, in ONE {@code ChangeGroupProfile}
     * (contract v62) — the icon sibling of {@link #changeGroupSubjectMls}.
     *
     * <p><b>APPENDED, deliberately.</b> Inserting it next to its sibling would have renumbered
     * every transaction below it, which is exactly the provider/app skew this ordering avoids.
     *
     * <p><b>Why the app does not pass a URL.</b> A subject is carried INLINE in the request; an
     * icon is carried BY REFERENCE, so its ciphertext must already be on the
     * File Transfer Server. The provider performs that upload as part of this call: it owns the FT
     * session token, and a URL round-tripped through the app would let the referenced blob and the
     * committed one come from different uploads. The app still owns everything cryptographic — it
     * mints the key, encrypts, commits {@code icon_key}/{@code icon_commitment} and builds the
     * §7.8.1 key delivery — and hands over opaque artifacts, as everywhere else on this boundary.
     *
     * <p><b>The upload happens BEFORE the dial and a failure aborts the whole change.</b> Emitting
     * the commit with no reachable content would publish a commitment to an icon no peer can
     * fetch — silent on the wire, invisible in logs, and the exact failure class this ordering
     * exists to avoid. The caller has already applied its commit locally, so a non-OK verdict means
     * restore the pre-commit snapshot, same as the subject.
     *
     * @param contentType goes into the file-reference's field 1; the literal
     *                    {@code message/mls-ft}, NOT the image's MIME — that belongs in the
     *                    {@code FileInfo}. That field order is pinned (content type at 1, URL at
     *                    2) and was
     *                    recorded backwards here until 2026-09-13; see the provider's
     *                    {@code EncryptedProfileFields} before changing it.
     * @param ciphertext  the Annex C.2 encrypted icon — what gets uploaded, not the image
     * @param controlMsgId the id bound into the key delivery's MLS AAD — never minted here
     *
     * <p>BLOCKING, and it performs an HTTP upload before it dials. Call it off the main thread.
     */
    RcsMlsControlResult changeGroupIconMls(String clientToken, int subId, String rcsGroupId,
            String contentType, in byte[] ciphertext, in byte[] groupInfo, in byte[] commit,
            in byte[] epochAuth, in byte[] ratchetTree, in byte[] baseEpochAuth,
            in byte[] privateMessages, String controlMsgId);

    /**
     * The <b>LAB (carrier/open5gs) MLS trust anchors</b>, fetched and signature-verified by the
     * provider (contract v63).
     *
     * <p><b>Not the Tachyon anchor set, and the two must never merge.</b> A leaf minted by the lab
     * KDS must not validate on the Tachyon path or vice versa. The Tachyon roots cross this same
     * boundary inside {@link #exportMlsIdentity}'s {@code RcsMlsIdentity.roots}; this is the lab
     * domain's separate set, in the SAME framing, deliberately through a SEPARATE call so nothing
     * can hand one where the other is expected.
     *
     * <p><b>Why the app passes the pointers in.</b> On the lab DR path the ACS config document is
     * fetched by the MODEM and delivered to the app, so the APP holds
     * {@code openrcs-trust-anchors-{uri,generation,signer}} and the provider may never have seen a
     * document. Fetching and verifying is the provider's job (the engine does not reach the
     * network), so one call carries pointers out and anchors back rather than two apps trying to
     * keep a store in sync. Any argument may be null/0 to fall through to whatever the provider
     * itself holds; {@code debug.rcs.lab_trust_*} outranks both, because a bench override must
     * not be silently outvoted by a caller.
     *
     * <p>Returns the roots as a length-prefixed concatenation (4-byte big-endian length per
     * record) — {@code OpenMlsSession.joinLenPrefixed}/{@code splitLenPrefixed}, the codec both
     * APKs already compile, so no new framing is introduced. Returns {@code null} when the
     * provider could not produce an anchor set at all. <b>A returned set REPLACES the caller's
     * lab anchors; it is never merged with them</b>, or a withdrawn anchor could never be
     * withdrawn.
     *
     * <p>Blocking, and it can hit the network — call it off the main thread.
     *
     * @param signerSpkiB64 base64 SPKI DER of the trust-list signer. Without one, no remote list
     *     is installed and the provider falls back to its cache or its bundled floor: an anchor
     *     list can only be installed against a known signer.
     */
    @nullable byte[] getMlsTrustAnchors(String clientToken, int subId, @nullable String uri,
            long generation, @nullable String signerSpkiB64);
}
