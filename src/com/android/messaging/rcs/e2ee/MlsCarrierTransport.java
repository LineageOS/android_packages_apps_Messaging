/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsEngineIdCheck;
import com.android.messaging.rcs.engine.mls.MlsGroupArtifacts;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsRecoveryPolicy;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;
import com.android.messaging.rcs.log.LogMask;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app-provided MLS {@link E2eeConversationTransport}: MLS in the shared engine, carried over
 * the app's own carrier CPM/MSRP session as {@code message/mls*} CPIM bodies (RCC.16 §7.9), without
 * the provider. See docs/rcs/carrier-transport.md.
 *
 * <p>1:1 only. The initiator enrols with the KDS, claims the peer's KeyPackage, creates the group
 * at era 1 and sends the Welcome as a {@code message/mls-rcs-server} control body; application text
 * goes as {@code message/mls}. The responder joins on the Welcome and decrypts. Group state is
 * persisted per conversation. {@link Sink} sends a body over the held session and {@link Config}
 * supplies identity, KDS and network.
 */
public final class MlsCarrierTransport implements E2eeConversationTransport {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsCarrierTransport: ";
    private static final String PREFS = "mls_carrier_conv";
    /** Preference suffix for the RCC.16 §7.11.12.1 continuity token a Welcome carried. */
    private static final String KEY_CONTINUITY = ".continuity";
    private static final int KP_POOL = 11;   // KeyPackages published per batch
    /** RCC.16 §7.11.1.1 era value of a fresh group; not the 0xF001 extension type. */
    private static final long ERA_INITIAL = 1L;

    /**
     * Delivers an MLS body on the carrier control plane (today a CPIM body over MSRP).
     *
     * <p>This is the one place that decides where an MLS body goes: callers choose only a content
     * type ({@code CT_MLS} for application ciphertext, {@code CT_MLS_RCS_SERVER} for a Welcome).
     * The plane could turn out to be SIP rather than MSRP; keep callers off MSRP primitives and
     * keep this the only content-type decision site, so such a change stays inside the
     * implementation.
     */
    public interface Sink {
        void sendMlsBody(String toUri, String contentType, byte[] wire, String eraId,
                String epochAuthB64, String messageId);
    }

    /** Self identity, KDS endpoint, network and the ACS configuration values. */
    public interface Config {
        String selfE164(int subId);
        String kdsBaseUrl();
        Network cellularNetwork();

        /**
         * The base64 {@code SignedEncryptionIdentityProof} from the ACS configuration ({@code
         * openrcs-encryption-identity-proof}), or empty. It becomes the {@code .5} extension,
         * mandatory under RCC.16 A.3.8.10.
         */
        default String acsEncryptionIdentityProof() { return ""; }

        /**
         * The ACS configuration's {@code openrcs-trust-anchors-{uri,generation,signer}}. Only this
         * app receives the document; the values are handed to the provider, which fetches and
         * verifies the list. Empty or zero is the normal unprovisioned state.
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

    // Provisioned lazily; one per identity and process.
    private MlsSession mSelf;
    private String mSelfE164;

    /** Per-conversation group state, cached over the persisted preferences. */
    private static final class Group {
        byte[] groupId;
        String peerE164;
        long era = 1;
        byte[] epochAuth;      // epoch authenticator of the current epoch
        /**
         * RCC.16 §7.11.12.1 continuity token from the Welcome that admitted us, or null: a group we
         * founded, an external-commit join and a peer without continuity all have none.
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

    private volatile boolean mProvisionKicked;

    /**
     * Provision once, off-thread: enrol, start the session and upload a fresh KeyPackage pool. Call
     * at transport wire-up so peers claim this session's packages, not stale ones from an earlier
     * session.
     */
    public void provisionAsync(final int subId) {
        if (mProvisionKicked) {
            return;
        }
        mProvisionKicked = true;
        new Thread(() -> {
            try { ensureProvisioned(subId); } catch (final Throwable t) { Log.w(TAG, P
                    + "provisionAsync", t); }
        }, "mls-provision").start();
    }

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
        final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(),
                mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
        final String proof = mConfig.acsEncryptionIdentityProof();
        if (proof == null || proof.isEmpty()) {
            // Enrol without the proof when there is none, and say so: a KDS that requires it
            // refuses.
            Log.w(TAG, P + "no ACS encryption-identity proof in the config document — enrolling "
                    + "WITHOUT one. The KDS accepts that until the .5 cutover and refuses it after; "
                    + "the proof arrives as openrcs-encryption-identity-proof in SERVICEPROVIDEREXT.");
        } else {
            Log.i(TAG, P + "ACS encryption-identity proof present (" + proof.length()
                    + " b64 chars) — enrolling WITH it; the KDS embeds it as the .5 extension");
        }
        final MlsIdentity id = kds.enroll(e164, proof);
        if (id == null) {
            Log.e(TAG, P + "enroll failed for " + LogMask.number(e164));
            return false;
        }
        final MlsSession s = mEngine.startSession(MlsHostPorts.storageRoot(mCtx), id,
                MlsHostPorts.forApp(mCtx));
        if (s == null) {
            Log.e(TAG, P + "startSession failed (leaf rejected?)");
            return false;
        }
        // Publish our pool so peers can claim us.
        final List<byte[]> pool = OpenMlsSession.splitLenPrefixed(s.generateKeyPackages(KP_POOL));
        final byte[] lastResort = s.generateLastResortKeyPackage();
        if (!pool.isEmpty() && lastResort != null) {
            kds.uploadKeyPackages(e164, pool, lastResort);
        }
        mSelf = s;
        mSelfE164 = e164;
        Log.i(TAG, P + "provisioned self " + LogMask.number(e164) + " (leaf " + id.leafDer.length
                + "B, KPs published)");
        return true;
    }

    /**
     * Replenish our KeyPackage pool (RCC.16 §5.1): generate a fresh batch and a last-resort
     * package from the current session and upload them. There is no server depletion notice, so
     * this runs on (re)registration. Off-thread; a no-op until provisioned.
     */
    public void replenishKeyPackagesAsync() {
        new Thread(() -> {
            final MlsSession s;
            final String e164;
            synchronized (this) { s = mSelf; e164 = mSelfE164; }
            if (s == null || TextUtils.isEmpty(e164)) {
                return;   // not provisioned; provisionAsync does the first upload
            }
            try {
                final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(),
                        mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
                final List<byte[]> pool =
                        OpenMlsSession.splitLenPrefixed(s.generateKeyPackages(KP_POOL));
                final byte[] lastResort = s.generateLastResortKeyPackage();
                if (!pool.isEmpty() && lastResort != null) {
                    final int stored = kds.uploadKeyPackages(e164, pool, lastResort);
                    Log.i(TAG, P + "replenishKeyPackages " + LogMask.number(e164) + ": re-uploaded "
                            + pool.size()
                            + " + last-resort (stored=" + stored + ")");
                }
            } catch (final Throwable t) {
                Log.w(TAG, P + "replenishKeyPackages failed", t);
            }
        }, "mls-replenish").start();
    }

    @Override
    public boolean ensureReady(final String conversationId, final int subId,
            final List<String> peers) {
        if (!ensureProvisioned(subId)) {
            return false;
        }
        if (peers == null || peers.size() != 1) {
            Log.w(TAG, P + "ensureReady: only 1:1 supported (peers="
                    + (peers == null ? 0 : peers.size()) + ")");
            return false;
        }
        final String peer = peers.get(0);
        if (getGroup(conversationId) != null) {
            return true;   // already established
        }
        // Initiator: claim the peer's KeyPackage and create the group.
        final RcsKdsClient kds = new RcsKdsClient(mCtx, mConfig.kdsBaseUrl(),
                mConfig.cellularNetwork())
                        .withTrustAnchorPointers(mConfig.trustAnchorsUri(),
                                mConfig.trustAnchorsGeneration(), mConfig.trustAnchorsSigner());
        final RcsKdsClient.ClaimedKeyPackage claimed = kds.claimKeyPackage(peer);
        if (claimed == null) {
            Log.w(TAG, P + "ensureReady: no KeyPackage for " + LogMask.number(peer)
                    + " (not MLS-enrolled yet?)");
            return false;
        }
        // The era value (1), which the engine writes into the 0xF001 extension; the Era-ID header
        // carries the same value.
        final MlsGroupArtifacts art = mSelf.createGroup(ERA_INITIAL, claimed.keyPackage);
        if (art == null || art.groupId == null || art.welcome == null) {
            Log.e(TAG, P + "ensureReady: createGroup failed for " + LogMask.number(peer));
            return false;
        }
        final Group g = new Group();
        g.groupId = art.groupId;
        g.peerE164 = peer;
        g.era = ERA_INITIAL;
        g.epochAuth = art.tag;
        putGroup(conversationId, g);
        Log.i(TAG, P + "ensureReady: group established with " + LogMask.number(peer) + " (gid "
                + art.groupId.length
                + "B) — delivering Welcome");
        // The Welcome goes as a control body so the peer can join.
        mSink.sendMlsBody(toUri(peer), CpimMessage.CT_MLS_RCS_SERVER, art.welcome,
                Long.toString(g.era), b64(g.epochAuth), "mls-welcome-" + conversationId);
        return true;
    }

    /**
     * Encrypts an application message, binding the app's {@code rcs_message_id} into the AAD. The
     * id bound into the AAD must equal the envelope id (RCC.16 §7.5.3.1), so pass the id the
     * envelope carries. Production always has one; the synthesised id below is only a fallback.
     */
    public Payload encryptForSend(final String conversationId, final byte[] framedBody,
            final String rcsMessageId) {
        final Group g = getGroup(conversationId);
        if (g == null || mSelf == null) {
            Log.w(TAG, P + "encryptForSend: no group for "
                    + MlsConversationKey.forLog(conversationId));
            return null;
        }
        // Stamp the generation: the RCC.16 body header's uint32 at bytes 4..7 must equal
        // sender_data.generation, and only the encrypt path knows it. Identity on a body that is
        // not RCC.16-framed and on a negative generation.
        final int nextGen = mSelf.nextAppGen(g.groupId);
        final byte[] stamped = MlsRecoveryPolicy.stampBodyGeneration(framedBody, nextGen);
        if (nextGen < 0) {
            Log.w(TAG, P + "encryptForSend: nextAppGen unavailable for "
                    + MlsConversationKey.forLog(conversationId)
                    + " — sending with the framing counter UNSTAMPED. A peer that cross-checks it "
                    + "against sender_data.generation will refuse this message.");
        }
        // Bind the AAD; sender and receiver must bind identical values or the AEAD fails. The
        // caller's id wins, since it is the envelope id. The fallback includes era, epoch and
        // generation so it differs per message: an id constant within an era would make the AAD
        // identical for every message in it.
        final String messageId = (rcsMessageId == null || rcsMessageId.isEmpty())
                ? "mls-" + conversationId + "-e" + g.era
                        + "p" + MlsAppMessage.epochFrom(mSelf.eraEpoch(g.groupId)) + "-" + nextGen
                : rcsMessageId;
        // The engine builds the AAD from this id and the group's 0xF001 era, as on the provider
        // path.
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
            // A peer reached us before we provisioned; bring the session up to join or decrypt.
            if (!ensureProvisioned(/*subId=*/ 0)) {
                Log.w(TAG, P + "onInboundCpim: not provisioned, dropping");
                return Inbound.ignored();
            }
        }
        final String ct = MlsContentRoute.normalize(contentType);
        // One shared router for both transports. A non-MLS type falls through; an MLS type with no
        // arm throws, so a routing defect is loud.
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
                // Control: a Welcome to join (1:1). Commits and proposals are not handled on this
                // leg.
                final byte[] gid = mSelf.join(payload);
                if (gid == null || gid.length == 0) {
                    Log.e(TAG, P + "onInboundCpim: join returned empty");
                    return Inbound.ignored();
                }
                final Group g = new Group();
                g.groupId = gid;
                g.peerE164 = senderE164;
                g.era = ERA_INITIAL;
                // join() does not return the epoch authenticator, and our outbound bodies need it
                // (RCC.16 §7.9).
                g.epochAuth = mSelf.epochAuth(gid);
                // RCC.16 §7.11.12.1: the token is readable only during the native join and the
                // engine's hand-off queue evicts, so collect it here, before putGroup writes the
                // whole tuple.
                g.continuityToken = takeWelcomeContinuityToken(conversationId, gid);
                putGroup(conversationId, g);
                Log.i(TAG, P + "onInboundCpim: JOINED group from " + LogMask.number(senderE164)
                        + " (gid "
                        + gid.length + "B)");
                return Inbound.control();
            }
            if (route == MlsContentRoute.RAW) {
                final Group g = getGroup(conversationId);
                if (g == null) {
                    Log.w(TAG, P + "onInboundCpim: message/mls but no joined group for "
                            + MlsConversationKey.forLog(conversationId));
                    return Inbound.ignored();
                }
                // The engine decides RCC.16 §7.5.3.1; its verdict is acted on below.
                final MlsEngineIdCheck.Processed processed =
                        MlsEngineIdCheck.process(mSelf, g.groupId, payload, envelopeMessageId);
                final byte[] pt = processed.plain;
                if (pt == null) {
                    Log.w(TAG, P
                            + "onInboundCpim: process returned null (non-app or decrypt fail)");
                    return Inbound.control();
                }
                // RCC.16 §7.5.3.1: the AAD's message id must match the envelope's. RFC 9420 gives
                // the AAD no meaning, so the library decrypts regardless, and nothing upstream
                // deduplicates on this path. Our own sends use one id for both. Fail-open on an
                // absent or unparseable AAD, as the provider path does: only a peer that binds a
                // different id is dropped.
                if (processed.idMismatch) {
                    Log.w(TAG, P + "onInboundCpim: §7.5.3.1 MISBINDING — the AAD's message id does "
                            + "not match the envelope's (" + MlsMessageId.forLog(envelopeMessageId)
                            + ") from "
                            + LogMask.number(senderE164)
                            + ". DROPPING: this decrypted, so it is not a crypto "
                            + "failure, but attributing it to this envelope's id would corrupt "
                            + "every ledger keyed on message id.");
                    return Inbound.ignored();
                }
                Log.i(TAG, P + "onInboundCpim: DECRYPTED " + pt.length + "B from "
                        + LogMask.number(senderE164));
                return Inbound.message(pt);
            }
        } catch (final Throwable t) {
            Log.e(TAG, P + "onInboundCpim: exception for ct=" + ct, t);
        }
        return Inbound.ignored();
    }

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
            // Written only when present: a later putGroup without a token must not clear one a
            // Welcome stored, since it can never be offered again and an era advance reuses the
            // conversation key.
            e.putString(conversationId + KEY_CONTINUITY, b64(g.continuityToken));
        }
        e.apply();
    }

    /**
     * Collect the RCC.16 §7.11.12.1 continuity token from the Welcome that just admitted us. The
     * Welcome's GroupInfo is readable only during the native join, so the engine decodes the 0xF010
     * extension there and parks it in an evicting hand-off queue this drains. Empty is the ordinary
     * answer. A value over {@link MlsContinuityToken#MAX_STORED_TOKEN_BYTES} (RCC.16 §8.3.1.1 says
     * 32) is refused and logged as {@code OVERSIZE}: the invariant scan reserves "continuity
     * dropped" for states no peer can provoke, and an oversize value is peer-chosen.
     *
     * @return the token, or {@code null} when there is none to keep
     */
    private byte[] takeWelcomeContinuityToken(final String conversationId, final byte[] gid) {
        if (mSelf == null || gid == null || gid.length == 0) return null;
        final byte[] token;
        try {
            token = mSelf.takeWelcomeContinuityToken(gid);
        } catch (final Throwable t) {
            // A read failure must not undo a join that succeeded.
            Log.w(TAG, P + "reading the Welcome's continuity token threw for "
                    + MlsConversationKey.forLog(conversationId)
                    + " — the join stands and we simply hold no token", t);
            return null;
        }
        if (token == null || token.length == 0) return null;
        if (token.length > MlsContinuityToken.MAX_STORED_TOKEN_BYTES) {
            Log.w(TAG, P + "continuity OVERSIZE — the Welcome for "
                    + MlsConversationKey.forLog(conversationId) + " offered a "
                    + token.length + "B token, over the "
                    + MlsContinuityToken.MAX_STORED_TOKEN_BYTES
                    + "B bound (§8.3.1.1 says 32). Refused.");
            return null;
        }
        Log.i(TAG, P + "continuity FIRST — stored the " + token.length
                + "B §7.11.12.1 token the Welcome carried for "
                + MlsConversationKey.forLog(conversationId));
        return token;
    }

    /**
     * The RCC.16 §7.11.12.1 continuity token this conversation's Welcome carried, or {@code null}.
     * Nothing reads it yet: the token is offered once, so it is stored now and its consumer
     * (RCC.16 §10.5.3 and §10.5.4 continuity on this transport) comes later. It is not dead code.
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

    /** Tear down the self identity (process exit, subscription change). */
    public synchronized void shutdown() {
        if (mSelf != null) {
            try { mSelf.close(); } catch (final Throwable ignore) { }
            mSelf = null;
        }
        mGroups.clear();
    }
}
