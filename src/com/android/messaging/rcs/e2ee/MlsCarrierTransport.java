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
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.messaging.rcs.carrier.sip.CpimMessage;
import com.android.messaging.rcs.engine.mls.MlsAppMessage;
import com.android.messaging.rcs.engine.mls.MlsContentRoute;
import com.android.messaging.rcs.engine.mls.MlsContinuityToken;
import com.android.messaging.rcs.engine.mls.MlsGroupArtifacts;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsRecoveryPolicy;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>app-provided-MLS</b> {@link E2eeConversationTransport}: MLS crypto in the shared
 * {@code OpenMlsSession} engine, carried over messaging2's OWN carrier CPM/MSRP session as
 * {@code message/mls[-rcs-server]} CPIM bodies (RCC.16 §7.9). No the RCS provider app involvement.
 *
 * <p>1:1 flow. The INITIATOR: enrol (KDS) → claim the peer's KeyPackage → {@code createGroup(era=1)}
 * (the GroupContext Era extension type is {@code 0xF001}; the initial Era <em>value</em> is 1)
 * → deliver the Welcome as a {@code message/mls-rcs-server} control body over MSRP → encrypt application
 * text as {@code message/mls}. The RESPONDER: on the inbound Welcome, {@code join}; on inbound
 * {@code message/mls}, {@code process} → plaintext. Group state (Era/epoch/groupId) is persisted per
 * conversation so it survives process restarts.
 *
 * <p>Dependencies are injected so the transport is decoupled from the carrier internals and testable:
 * {@link Sink} sends a CPIM MLS body over the held MSRP session (implemented by {@code CarrierRcsTransport});
 * {@link Config} supplies the self MSISDN + KDS URL + cellular {@link Network}.
 */
public final class MlsCarrierTransport implements E2eeConversationTransport {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    /** Origin prefix: this class used to BE the tag (rework 14.3 collapsed the tag, not the origin). */
    private static final String P = "MlsCarrierTransport: ";
    private static final String PREFS = "mls_carrier_conv";
    /** Prefs suffix for the §7.11.12.1 Welcome-borne continuity token. */
    private static final String KEY_CONTINUITY = ".continuity";
    private static final int KP_POOL = 11;   // Google Messages ships ~11 claimable KeyPackages
    /** RCC.16 §7.11.1.1 Era ordinal for a fresh group (uint32, starts at 1) — NOT the 0xF001 ext type. */
    private static final long ERA_INITIAL = 1L;

    /**
     * Delivers an MLS body on the carrier control plane. Today's implementation is a CPIM body over
     * the MSRP session (INVITE-on-first, keep-warm).
     *
     * <p><b>THE PLANE IS NOT SETTLED, AND THIS SEAM IS WHY THAT IS SURVIVABLE.</b> An iPhone RCS
     * capture shows MLS riding the SIP
     * CONTROL plane rather than MSRP: an {@code MLS-Opaque-Token} header on INVITE/NOTIFY/200, a
     * {@code Recv-Info: MLS-Group-Info-Pull, MLS-Enhanced-Group-Info-Pull} advertisement, and a full
     * RFC 9420 GroupInfo base64'd into {@code <mls-group-info>} inside the conference-info+xml
     * NOTIFY. In that capture the MSRP session carried NINE sends, every one
     * {@code Byte-Range: 1-0/0} — empty establishment probes with zero payload bytes.
     *
     * <p><b>Do not read that as an answer.</b> The captured group is WEDGED on that device, so its
     * SDP negotiation is not a sample of what Google Messages advertises when MLS is healthy; and MLS
     * demonstrably rode another plane there, so {@code message/mls*} being absent from MSRP says
     * nothing about whether it ever appears. A negative drawn from a sample that could not have
     * contained the positive is not a negative.
     *
     * <p>What this interface must therefore keep is its SHAPE: one method, one place that decides
     * where an MLS body goes. The two callers pick only a CONTENT TYPE ({@code CT_MLS} for
     * application ciphertext, {@code CT_MLS_RCS_SERVER} for the Welcome); neither names a transport.
     * So if the plane turns out to be SIP INFO or a conference-info NOTIFY, the change is this
     * interface's implementation and not this class. Keep it that way — do not let a caller reach
     * past it to an MSRP primitive, and do not add a second content-type decision site.
     */
    public interface Sink {
        void sendMlsBody(String toUri, String contentType, byte[] wire, String eraId,
                String epochAuthB64, String messageId);
    }

    /** Environment: self identity + KDS endpoint + data bearer + the ACS proof. */
    public interface Config {
        String selfE164(int subId);
        String kdsBaseUrl();
        Network cellularNetwork();

        /**
         * The base64 {@code SignedEncryptionIdentityProof} from the ACS config document
         * ({@code openrcs-encryption-identity-proof}), or empty.
         *
         * <p>RCC.16 A.3.8.10 makes the {@code .5} extension it becomes MANDATORY, and the KDS fills
         * that extension from this value. Empty until the ACS has one to give us, which is why the
         * old no-proof enrolment still exists.
         */
        default String acsEncryptionIdentityProof() { return ""; }

        /**
         * {@code openrcs-trust-anchors-{uri,generation,signer}} from the same ACS config document
         * — the pointers to the lab MLS trust-anchor list.
         *
         * <p>They live here for the same reason {@link #acsEncryptionIdentityProof} does: the
         * document is fetched by the MODEM and arrives in this app, so this app is the only one
         * that has them. They are handed OUT to the provider, which does the fetch and the
         * signature verification; the engine never reaches the network.
         *
         * <p>Empty/zero is the normal state on a device that has not yet been provisioned, and it
         * is NOT a refusal — the provider then falls through to whatever it holds, and below that
         * to the compiled-in floor.
         */
        default String trustAnchorsUri() { return ""; }
        default long trustAnchorsGeneration() { return 0L; }
        default String trustAnchorsSigner() { return ""; }
    }

    private final Context mCtx;
    private final OpenMlsEngine mEngine;
    private final Sink mSink;
    private final Config mConfig;
    private final SharedPreferences mPrefs;

    // Lazily provisioned self session (one per identity/process).
    private MlsSession mSelf;
    private String mSelfE164;

    /** Per-conversation group state (in-memory cache over the persisted prefs). */
    private static final class Group {
        byte[] groupId;
        String peerE164;
        long era = 1;
        byte[] epochAuth;      // 32-byte epoch authenticator of the current epoch
        /**
         * RCC.16 §7.11.12.1 continuity token carried by the Welcome that admitted us, or null.
         * Null for a group we FOUNDED (no Welcome), for an external-commit join (no Welcome), and
         * for a Welcome from a peer that does not do continuity — all three are ordinary.
         */
        byte[] continuityToken;
    }
    private final Map<String, Group> mGroups = new HashMap<>();

    public MlsCarrierTransport(final Context ctx, final OpenMlsEngine engine, final Sink sink,
            final Config config) {
        mCtx = ctx.getApplicationContext();
        mEngine = engine;
        mSink = sink;
        mConfig = config;
        mPrefs = mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public String schemeId() { return RcsE2eeScheme.MLS; }

    private volatile boolean mProvisionKicked;

    /**
     * Provision (enrol + start session + upload a FRESH KeyPackage pool) off-thread, once. Call on
     * transport wire-up so a peer can claim THIS session's current KP (the KDS may hold stale KPs
     * from prior sessions that this session's private keys no longer match). Idempotent.
     */
    public void provisionAsync(final int subId) {
        if (mProvisionKicked) {
            return;
        }
        mProvisionKicked = true;
        new Thread(() -> {
            try { ensureProvisioned(subId); } catch (final Throwable t) { Log.w(TAG, P + "provisionAsync", t); }
        }, "mls-provision").start();
    }

    // ---- provisioning (enrol + start session + publish KPs), once ----

    private synchronized boolean ensureProvisioned(final int subId) {
        if (mSelf != null) {
            return true;
        }
        if (!mEngine.available()) {
            Log.e(TAG, P + "engine (libmlsengine) not available");
            return false;
        }
        final String e164 = mConfig.selfE164(subId);
        if (TextUtils.isEmpty(e164)) {
            Log.e(TAG, P + "no self MSISDN for subId " + subId);
            return false;
        }
        final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(), mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
        final String proof = mConfig.acsEncryptionIdentityProof();
        if (proof == null || proof.isEmpty()) {
            // Enrol anyway: the KDS still accepts a proofless request until the coordinated cutover.
            // Say so explicitly rather than let an empty field pass silently — after the cutover this
            // is the line that explains a rejected enrolment.
            Log.w(TAG, P + "no ACS encryption-identity proof in the config document — enrolling "
                    + "WITHOUT one. The KDS accepts that until the .5 cutover and refuses it after; "
                    + "the proof arrives as openrcs-encryption-identity-proof in SERVICEPROVIDEREXT.");
        } else {
            Log.i(TAG, P + "ACS encryption-identity proof present (" + proof.length()
                    + " b64 chars) — enrolling WITH it; the KDS embeds it as the .5 extension");
        }
        final MlsIdentity id = kds.enroll(e164, proof);
        if (id == null) {
            Log.e(TAG, P + "enroll failed for " + e164);
            return false;
        }
        final MlsSession s = mEngine.startSession(MlsHostPorts.storageRoot(mCtx), id,
                MlsHostPorts.forApp(mCtx));
        if (s == null) {
            Log.e(TAG, P + "startSession failed (leaf rejected?)");
            return false;
        }
        // Publish our KeyPackage pool so peers can claim us.
        final List<byte[]> pool = OpenMlsSession.splitLenPrefixed(s.generateKeyPackages(KP_POOL));
        final byte[] lastResort = s.generateLastResortKeyPackage();
        if (!pool.isEmpty() && lastResort != null) {
            kds.uploadKeyPackages(e164, pool, lastResort);
        }
        mSelf = s;
        mSelfE164 = e164;
        Log.i(TAG, P + "provisioned self " + e164 + " (leaf " + id.leafDer.length + "B, KPs published)");
        return true;
    }

    /**
     * Replenish our KeyPackage pool (RCC.16 §7.4 / §5.1): regenerate a fresh batch + last-resort from
     * the CURRENT session and re-upload. MLS has NO server depletion-notify (unlike Etouffee prekeys) —
     * Google Messages re-uploads a fresh fixed-size batch on a cert-/registration-coupled cadence, and the
     * last-resort KP covers the gap between drains. We drive this off (re)registration. Off-thread; no-op
     * until provisioned.
     */
    public void replenishKeyPackagesAsync() {
        new Thread(() -> {
            final MlsSession s;
            final String e164;
            synchronized (this) { s = mSelf; e164 = mSelfE164; }
            if (s == null || TextUtils.isEmpty(e164)) {
                return;   // not provisioned yet — provisionAsync does the first upload
            }
            try {
                final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(), mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
                final List<byte[]> pool = OpenMlsSession.splitLenPrefixed(s.generateKeyPackages(KP_POOL));
                final byte[] lastResort = s.generateLastResortKeyPackage();
                if (!pool.isEmpty() && lastResort != null) {
                    final int stored = kds.uploadKeyPackages(e164, pool, lastResort);
                    Log.i(TAG, P + "replenishKeyPackages " + e164 + ": re-uploaded " + pool.size()
                            + " + last-resort (stored=" + stored + ")");
                }
            } catch (final Throwable t) {
                Log.w(TAG, P + "replenishKeyPackages failed", t);
            }
        }, "mls-replenish").start();
    }

    // ---- E2eeConversationTransport ----

    @Override
    public boolean ensureReady(final String conversationId, final int subId, final List<String> peers) {
        if (!ensureProvisioned(subId)) {
            return false;
        }
        // 1:1 only for now: exactly one peer.
        if (peers == null || peers.size() != 1) {
            Log.w(TAG, P + "ensureReady: only 1:1 supported (peers=" + (peers == null ? 0 : peers.size()) + ")");
            return false;
        }
        final String peer = peers.get(0);
        if (getGroup(conversationId) != null) {
            return true;   // already established (founder or joiner)
        }
        // Initiator path: claim the peer's KP and create the group.
        final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(), mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
        final RcsKdsClient.ClaimedKeyPackage claimed = kds.claimKeyPackage(peer);
        if (claimed == null) {
            Log.w(TAG, P + "ensureReady: no KeyPackage for " + peer + " (not MLS-enrolled yet?)");
            return false;
        }
        // Era VALUE = the ordinal 1 (the engine writes it into the 0xF001 GroupContext ext); passing
        // 0xF001 here would put 61441 in the ext, inconsistent with the mls.Era-ID header + Google Messages.
        final MlsGroupArtifacts art = mSelf.createGroup(ERA_INITIAL, claimed.keyPackage);
        if (art == null || art.groupId == null || art.welcome == null) {
            Log.e(TAG, P + "ensureReady: createGroup failed for " + peer);
            return false;
        }
        final Group g = new Group();
        g.groupId = art.groupId;
        g.peerE164 = peer;
        g.era = ERA_INITIAL;
        g.epochAuth = art.tag;
        putGroup(conversationId, g);
        Log.i(TAG, P + "ensureReady: group established with " + peer + " (gid " + art.groupId.length
                + "B) — delivering Welcome");
        // Deliver the Welcome as a control body so the peer can join.
        mSink.sendMlsBody(toUri(peer), CpimMessage.CT_MLS_RCS_SERVER, art.welcome,
                Long.toString(g.era), b64(g.epochAuth), "mls-welcome-" + conversationId);
        return true;
    }

    @Override
    public Payload encryptForSend(final String conversationId, final byte[] framedBody) {
        return encryptForSend(conversationId, framedBody, /*rcsMessageId=*/ null);
    }

    /**
     * As above, binding the APP's {@code rcs_message_id} into the AAD.
     *
     * <p><b>THE ENVELOPE ID MUST EQUAL THE ID BOUND INTO THE AAD.</b> That rule is stated at
     * {@code MlsProviderTransport}'s own seal site and it is RCC.16 §7.5.3.1: a Google Messages peer
     * cross-checks the two and drops a mismatch. This leg broke it in both directions at once —
     * it sealed against a synthesised id while {@code CarrierRcsTransport.sendMls} put the app's
     * row id on the wire, so every message it sent carried two different ids and nothing
     * reconciled them.
     *
     * <p><b>It was invisible for a reason worth stating, because it is not the one that was
     * assumed.</b> The obvious explanation would be that our receive side re-synthesises the same
     * string, making two of our own clients self-consistent. It does not: {@code onInboundCpim}
     * calls the NO-AAD {@code process()} entry, and under RFC 9420 the AAD travels inside the
     * {@code PrivateMessage} and the receiver READS it rather than recomputing it — so decryption
     * cannot fail on a mismatch here however wrong the id is. The binding check is simply absent
     * on this leg: the host-side one ({@code MlsAppMessage.aadMessageIdMatches}) is called only by
     * the provider, and the engine-side one is armed by {@code MlsSession.setRequestMessageId},
     * which has NO production caller anywhere in the tree. <b>Nothing was agreeing; nothing was
     * looking.</b>
     *
     * <p>Production always has a real id — {@code InsertNewMessageAction} mints a row UUID and
     * threads it through {@code sendMls} — so binding it here fixes the mismatch AND the collision
     * in one move, and the synthesised form below becomes the fallback arm that production never
     * takes. That is exactly the shape the provider leg already has.
     */
    public Payload encryptForSend(final String conversationId, final byte[] framedBody,
            final String rcsMessageId) {
        final Group g = getGroup(conversationId);
        if (g == null || mSelf == null) {
            Log.w(TAG, P + "encryptForSend: no group for " + conversationId);
            return null;
        }
        // STAMP THE GENERATION. The RCC.16 body header's uint32 at bytes 4..7 must equal
        // sender_data.generation — Google Messages increments the two in lockstep, and a peer that
        // cross-checks them rejects a mismatch. messaging2 frames with a PLACEHOLDER 0 because framing cannot know the
        // generation; the encrypt path is the only place that can.
        //
        // THIS LEG HAD NO GENERATION HANDLING AT ALL, and that was survivable ONLY because nothing
        // framed here: RccMlsBody.parse returns a frameless payload verbatim, so raw text worked
        // between two of our own clients. The moment this leg frames, an unstamped body means the
        // FIRST message of an epoch decrypts and every one after it fails — a silent, delayed
        // failure that looks like anything but its cause. So the stamp lands WITH the framing, not
        // after it.
        //
        // stampBodyGeneration is identity on a body that is not RCC.16-framed (it checks the
        // 00 01 00 01 magic) and on a negative generation, so this is inert until the caller frames
        // and safe if nextAppGen cannot answer.
        final int nextGen = mSelf.nextAppGen(g.groupId);
        final byte[] stamped = MlsRecoveryPolicy.stampBodyGeneration(framedBody, nextGen);
        if (nextGen < 0) {
            Log.w(TAG, P + "encryptForSend: nextAppGen unavailable for " + conversationId
                    + " — sending with the framing counter UNSTAMPED. A peer that cross-checks it "
                    + "against sender_data.generation will refuse this message.");
        }
        // BIND THE AAD. RCC.16 feeds an AuthenticatedData{version, message_id, era} into the content
        // AEAD, so the sender and receiver must bind byte-identical values or the AEAD auth-fails —
        // surfacing to the user as KEY_GENERATION_MISMATCH rather than as anything resembling the
        // real cause. This path called the NO-AAD overload, so it bound nothing at all while the
        // provider path binds a real one: two encoders of one contract, disagreeing.
        //
        // THE CALLER'S ID WINS. It is the id on the envelope, so binding anything
        // else is the §7.5.3.1 mismatch described in this method's javadoc.
        //
        // THE SYNTHESISED FALLBACK NOW CARRIES ERA, EPOCH *AND* GENERATION, because era alone is
        // not an identifier — it was a per-(peer, era) CONSTANT, shared by every message this leg
        // sealed in that era. The provider leg paid for this exact lesson twice, on device, and
        // the record of both is in MlsProviderTransport's seal site rather than here, which is
        // why it never propagated:
        //
        //   pre 2026-07-30   mls-<conv>-<gen>                  gen restarts each EPOCH
        //   after 07-30      mls-<conv>-e<era>-<gen>            still collided within an era
        //   after 08-08      mls-<conv>-e<era>p<epoch>-<gen>    the form adopted below
        //
        // WHY THE COLLISION IS A DEFECT *ON THIS LEG*, stated separately from why the FORM was
        // chosen — because the two have different evidence and conflating them is how a
        // provider-leg fact becomes a carrier-leg belief.
        //
        // HERE: the id is what the AAD binds. An id that is a per-(peer, era) constant makes the
        // AuthenticatedData identical for every message in that era, so the §7.5.3.1 binding
        // stops distinguishing messages at all — it degenerates into a per-era tag. That is true
        // of this leg from the shape of the code and needs no wire observation.
        //
        // THE FORM above is the provider's, adopted because that leg had already converged on it;
        // the device evidence behind it (2026-08-08: two of three messages in a controlled
        // experiment vanished, the transport having deduped reissued ids) is a TACHYON
        // observation. **It does not transfer.** This leg is SIP/MSRP, there is no bind/pull, and
        // "the server dedupes on message id" is not established even for Tachyon — the one run
        // behind it is equally well explained by receiver-side dedup. Cited here as the
        // reason the form is what it is, NOT as a claim about what happens on this wire.
        final String messageId = (rcsMessageId == null || rcsMessageId.isEmpty())
                ? "mls-" + conversationId + "-e" + g.era
                        + "p" + MlsAppMessage.epochFrom(mSelf.eraEpoch(g.groupId)) + "-" + nextGen
                : rcsMessageId;
        // The ENGINE builds the AAD now, from this id plus the group's own 0xF001 era.
        // That also removes the last place where this path and the provider path could encode
        // the same contract differently — the very failure this comment block describes.
        final byte[] aad = messageId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final byte[] ct = mSelf.encryptWithAad(g.groupId, stamped, aad);
        if (ct == null || ct.length == 0) {
            Log.e(TAG, P + "encryptForSend: encrypt returned empty");
            return null;
        }
        final Map<String, String> headers = new HashMap<>();
        headers.put(CpimMessage.HDR_MLS_ERA_ID, Long.toString(g.era));
        headers.put(CpimMessage.HDR_MLS_EPOCH_AUTH, b64(g.epochAuth));
        return new Payload(CpimMessage.CT_MLS, ct, headers);
    }

    @Override
    public Inbound onInboundCpim(final String conversationId, final String senderE164,
            final String contentType, final byte[] payload, final String envelopeMessageId) {
        if (mSelf == null) {
            // A peer reached us before we provisioned — bring up our session so we can join/process.
            if (!ensureProvisioned(/*subId=*/ 0)) {
                Log.w(TAG, P + "onInboundCpim: not provisioned, dropping");
                return Inbound.ignored();
            }
        }
        final String ct = MlsContentRoute.normalize(contentType);
        // Rework 6.1: route through the ONE shared router. The two legs used to answer "which
        // content types are MLS?" differently — this leg matched a six-type accept-set, the Tachyon
        // leg matched two and fell through for the rest — so the answer depended on which transport
        // a message arrived on.
        //
        // Layer 1 and layer 2 are asked SEPARATELY and that separation is the fix: a non-MLS type
        // falls through silently (it is not ours), while an MLS type we cannot place THROWS. Both
        // used to be the same silent ignore, which made a routing bug invisible.
        final MlsContentRoute route;
        try {
            route = MlsContentRoute.of(ct);
        } catch (final IllegalStateException badRoute) {
            Log.e(TAG, P + "onInboundCpim: " + badRoute.getMessage() + " — this body reached the "
                    + "MLS dispatcher with a type it has no arm for. That is a ROUTING DEFECT, not "
                    + "a message to ignore; add the arm.", badRoute);
            return Inbound.ignored();
        }
        if (route == MlsContentRoute.REJECTED) {
            Log.w(TAG, P + "onInboundCpim: " + ct + " is host-internal and is never a legitimate "
                    + "dispatcher input — dropped");
            return Inbound.ignored();
        }
        try {
            if (route == MlsContentRoute.SERVER || route == MlsContentRoute.CONTROL) {
                // Control: a Welcome (join) — 1:1. (Commit/Proposal maintenance = later group work.)
                final byte[] gid = mSelf.join(payload);
                if (gid == null || gid.length == 0) {
                    Log.e(TAG, P + "onInboundCpim: join returned empty");
                    return Inbound.ignored();
                }
                final Group g = new Group();
                g.groupId = gid;
                g.peerE164 = senderE164;
                g.era = ERA_INITIAL;
                // Capture the post-join epoch authenticator so THIS side can stamp mls.Epoch-Authenticator
                // on its own outbound bodies (join() doesn't return it; §7.11).
                g.epochAuth = mSelf.epochAuth(gid);
                // RCC.16 §7.11.12.1 — and it has to happen HERE, between join() and putGroup(),
                // for two independent reasons.
                //
                // TIMING: RFC 9420 encrypts a Welcome's GroupInfo under the joiner secret, so its
                // extension list is readable for exactly the length of the native join. The engine
                // decodes the 0xF010 there and parks it in a bounded hand-off queue; takeWelcome-
                // ContinuityToken is the only way to reach it, and the queue evicts. Nothing later
                // in this method — or in any method — can go back for it.
                //
                // PLACEMENT: putGroup writes the whole prefs tuple, so filling the field before the
                // write costs one store instead of two and cannot leave a half-written group.
                g.continuityToken = takeWelcomeContinuityToken(conversationId, gid);
                putGroup(conversationId, g);
                Log.i(TAG, P + "onInboundCpim: JOINED group from " + senderE164 + " (gid " + gid.length + "B)");
                return Inbound.control();
            }
            if (route == MlsContentRoute.RAW) {
                final Group g = getGroup(conversationId);
                if (g == null) {
                    Log.w(TAG, P + "onInboundCpim: message/mls but no joined group for " + conversationId);
                    return Inbound.ignored();
                }
                final byte[] pt = mSelf.process(g.groupId, payload);
                if (pt == null) {
                    Log.w(TAG, P + "onInboundCpim: process returned null (non-app or decrypt fail)");
                    return Inbound.control();
                }
                // RCC.16 §7.5.3.1 — THE CROSS-CHECK THIS LEG HAD NO WAY TO MAKE.
                //
                // RFC 9420 authenticates the AAD but assigns it no meaning, so mls-rs decrypts
                // happily when the AAD's message id and the envelope's disagree. Without this, a
                // ciphertext re-offered under a DIFFERENT envelope id is accepted and attributed to
                // the new id, and every ledger keyed on message id then points at the wrong message.
                //
                // THE SERVER CANNOT COVER THIS ONE, and it is why the gap mattered. Tachyon dedupes
                // on message_id — but that closes the SAME-id
                // replay, and a changed id is a new message to any deduper by construction. This leg
                // is not even behind Tachyon: it is CPIM over MSRP, so nothing upstream dedupes at all.
                //
                // SAFE BY CONSTRUCTION FOR OUR OWN TRAFFIC: CarrierRcsTransport.sendMlsBody puts ONE
                // messageId into CpimMessage.newMls and passes that SAME variable to
                // encryptForSend, so a message of ours cannot fail this.
                //
                // FAIL-OPEN on an absent or unparseable AAD, matching the provider leg exactly:
                // aadMessageIdMatches returns true when nothing was asserted. A peer that binds no id
                // is not accused of anything — only a peer that binds a DIFFERENT one is.
                final byte[] inboundAad = mSelf.lastInboundAad();
                if (!MlsAppMessage.aadMessageIdMatches(inboundAad, envelopeMessageId)) {
                    Log.w(TAG, P + "onInboundCpim: §7.5.3.1 MISBINDING — the AAD's message id does "
                            + "not match the envelope's (" + envelopeMessageId + ") from "
                            + senderE164 + ". DROPPING: this decrypted, so it is not a crypto "
                            + "failure, but attributing it to this envelope's id would corrupt "
                            + "every ledger keyed on message id.");
                    return Inbound.ignored();
                }
                Log.i(TAG, P + "onInboundCpim: DECRYPTED " + pt.length + "B from " + senderE164);
                return Inbound.message(pt);
            }
        } catch (final Throwable t) {
            Log.e(TAG, P + "onInboundCpim: exception for ct=" + ct, t);
        }
        return Inbound.ignored();
    }

    @Override
    public void close(final String conversationId) {
        mGroups.remove(conversationId);
        // The self session persists (shared across conversations); closed on transport teardown.
    }

    // ---- state ----

    private Group getGroup(final String conversationId) {
        Group g = mGroups.get(conversationId);
        if (g == null) {
            final String gid = mPrefs.getString(conversationId + ".gid", null);
            if (gid != null) {
                g = new Group();
                g.groupId = Base64.decode(gid, Base64.NO_WRAP);
                g.peerE164 = mPrefs.getString(conversationId + ".peer", null);
                g.era = mPrefs.getLong(conversationId + ".era", 1);
                final String ea = mPrefs.getString(conversationId + ".epoch", null);
                g.epochAuth = ea == null ? null : Base64.decode(ea, Base64.NO_WRAP);
                final String ct = mPrefs.getString(conversationId + KEY_CONTINUITY, null);
                g.continuityToken = ct == null ? null : Base64.decode(ct, Base64.NO_WRAP);
                mGroups.put(conversationId, g);
            }
        }
        return g;
    }

    private void putGroup(final String conversationId, final Group g) {
        mGroups.put(conversationId, g);
        final SharedPreferences.Editor e = mPrefs.edit()
                .putString(conversationId + ".gid", b64(g.groupId))
                .putString(conversationId + ".peer", g.peerE164)
                .putLong(conversationId + ".era", g.era);
        if (g.epochAuth != null) {
            e.putString(conversationId + ".epoch", b64(g.epochAuth));
        }
        if (g.continuityToken != null && g.continuityToken.length > 0) {
            // Written only when we HAVE one. A putGroup for a group with no token must not clear a
            // token a previous Welcome stored under the same conversation: the token is the thing
            // we can never be offered again, and an era advance reuses the conversation key.
            e.putString(conversationId + KEY_CONTINUITY, b64(g.continuityToken));
        }
        e.apply();
    }

    /**
     * RCC.16 <b>§7.11.12.1</b> — collect the continuity token the Welcome that just admitted us
     * carried.
     *
     * <h2>Why this is a separate read and not a look at bytes we already hold</h2>
     *
     * <p>RFC 9420 ships a Welcome's GroupInfo encrypted under the joiner secret, so its extension
     * list is in the clear for exactly the length of the native join. Nothing on this side of the
     * JNI can reach that instant. The engine decodes the 0xF010 during the join, logs
     * {@code WELCOME-CONTINUITY … CAPTURED}, and parks the value in a bounded hand-off queue that
     * {@code takeWelcomeContinuityToken} drains; the queue evicts, so an uncollected token is gone.
     *
     * <h2>Empty is the ordinary answer</h2>
     *
     * <p>A group we founded returns empty (no Welcome), an external-commit join returns empty (no
     * Welcome), and a peer that does not do continuity returns empty. None of those is a problem
     * and none is logged as one — the engine already logs the distinction per join.
     *
     * <h2>The bound, and why it is the same constant the provider path uses</h2>
     *
     * <p>{@link MlsContinuityToken#MAX_STORED_TOKEN_BYTES}. §8.3.1.1 says 32 bytes; the stored bound
     * is looser so a longer-but-sane peer value survives, and refuses beyond it because the value
     * is entirely peer-chosen. Refusing logs <b>OVERSIZE</b> and deliberately not "DROPPED":
     * {@code continuity DROPPED} is a {@code NEVER} marker in {@link
     * com.android.messaging.rcs.engine.mls.MlsInvariantScan}, and the argument for NEVER is that no
     * peer can provoke it. An oversize extension is exactly a peer provoking it.
     *
     * @return the token, or {@code null} when there is none to keep
     */
    private byte[] takeWelcomeContinuityToken(final String conversationId, final byte[] gid) {
        if (mSelf == null || gid == null || gid.length == 0) return null;
        final byte[] token;
        try {
            token = mSelf.takeWelcomeContinuityToken(gid);
        } catch (final Throwable t) {
            // A join that has already succeeded must not be undone by a bookkeeping read.
            Log.w(TAG, P + "reading the Welcome's continuity token threw for " + conversationId
                    + " — the join stands and we simply hold no token", t);
            return null;
        }
        if (token == null || token.length == 0) return null;
        if (token.length > MlsContinuityToken.MAX_STORED_TOKEN_BYTES) {
            Log.w(TAG, P + "continuity OVERSIZE — the Welcome for " + conversationId + " offered a "
                    + token.length + "B token, over the " + MlsContinuityToken.MAX_STORED_TOKEN_BYTES
                    + "B bound (§8.3.1.1 says 32). Refused.");
            return null;
        }
        Log.i(TAG, P + "continuity FIRST — stored the " + token.length
                + "B §7.11.12.1 token the Welcome carried for " + conversationId);
        return token;
    }

    /**
     * The §7.11.12.1 continuity token this conversation's Welcome carried, or {@code null}.
     *
     * <p><b>Nothing reads this yet, and that is deliberate.</b> The token is offered exactly once —
     * it lives in a Welcome's encrypted GroupInfo and there is no way to ask for it again — so it is
     * captured and persisted now and the CONSUMPTION is gated separately. Deleting this accessor as
     * dead code would turn the storage back into a silent drop.
     * The consumer is §10.5.3/§10.5.4 continuity emission on this leg, which this transport does not
     * do yet.
     */
    public byte[] continuityTokenFor(final String conversationId) {
        final Group g = getGroup(conversationId);
        return (g == null) ? null : g.continuityToken;
    }

    private static String toUri(final String e164) {
        return e164 != null && e164.startsWith("tel:") ? e164 : "tel:" + e164;
    }

    private static String b64(final byte[] b) {
        return b == null ? null : Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /** For a single self-identity teardown (process exit / sub change). */
    public synchronized void shutdown() {
        if (mSelf != null) {
            try { mSelf.close(); } catch (final Throwable ignore) { }
            mSelf = null;
        }
        mGroups.clear();
    }
}
