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
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.session.CarrierMsrpSessionManager;
import com.android.messaging.rcs.carrier.msrp.session.CarrierMsrpSessionReceiver;
import com.android.messaging.rcs.carrier.msrp.session.CarrierMsrpSessionSender;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.carrier.sip.CpimDateTime;
import com.android.messaging.rcs.carrier.sip.ImdnNotification;
import com.android.messaging.rcs.carrier.sip.IsComposingNotification;
import com.android.messaging.rcs.carrier.sip.CarrierMessageReceiver;
import com.android.messaging.rcs.carrier.sip.CarrierMessageSender;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;
import javax.sip.ClientTransaction;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.message.Request;

/**
 * {@link Transport} implementation for the GSMA UP 2.4 carrier-RCS path. Glue
 * layer that composes:
 *
 * <ul>
 *   <li>{@link CarrierSipRegistrar} — REGISTER state machine driving the
 *       transport's {@link RegistrationState}.</li>
 *   <li>{@link CarrierMessageSender} — outbound RFC 3428 SIP MESSAGE
 *       (pager mode) carrying CPIM-wrapped chat text.</li>
 *   <li>{@link CarrierMessageReceiver} — inbound MESSAGE → CPIM unwrap →
 *       {@link Transport.Listener#onIncomingMessage} dispatch, plus IMDN
 *       parsing → {@link Transport.Listener#onMessageStatus} dispatch.</li>
 *   <li>{@link CarrierTransportBridge} — pure-Java state/listener glue,
 *       carved out so the wiring is host-unit-testable without standing
 *       up a JAIN-SIP stack.</li>
 * </ul>
 *
 * <p>Message-id correlation: the {@code messageId} the UI hands us in
 * {@link #sendMessage} is propagated through the CPIM body as
 * {@code imdn.Message-ID} (via {@code CpimMessage.newText}). When a peer
 * sends back an IMDN report referencing the same id, the receiver looks the
 * id up and dispatches {@code onMessageStatus(id, DELIVERED|DISPLAYED, ...)}.
 * The bridge keeps a small in-memory registry of outgoing ids so terminal
 * statuses prune correctly — but the receiver also forwards reports for
 * unknown ids (Google Messages does this; the UI side de-dupes).
 *
 * <p>Routing: today this is pager-mode only. The MSRP-session path for
 * messages larger than {@code mSwitchoverSize} (1300 B on T-Mobile) and for
 * file transfer is not wired up yet. {@code sendMessage} checks the
 * threshold and emits a clear warning when the body would have triggered the
 * MSRP path, then falls back to pager mode so the user-visible chat still
 * works (best-effort).
 *
 * <p>Selection: {@link com.android.messaging.rcs.carrier.TransportRegistry} picks
 * this transport when {@code transport_mode == CARRIER_IMS} AND a valid
 * {@code RcsImsConfig} is available in prefs (Pev3-provisioned). When the
 * config isn't there yet, {@link #start} drops to FAILED rather than
 * crashing — same shape as {@code OttTransport} when SIP creds are missing.
 *
 * <p>Threading: lifecycle methods enqueue onto the registrar's sipThread
 * (the {@link CarrierSipRegistrar#setRequestForwarder} contract calls us
 * back on that same thread). Listener callbacks may fire on the SIP thread.
 */
public final class CarrierRcsTransport implements Transport {

    private static final String TAG = "CarrierRcsTransport";

    /** The per-carrier switchover-size constant. See
     *  {@link CarrierTransportBridge#MSRP_SWITCHOVER_SIZE_BYTES}. */
    public static final int MSRP_SWITCHOVER_SIZE_BYTES =
            CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES;

    private final Context appContext;
    private final RcsImsConfig config;
    private final CarrierSipRegistrar registrar;
    private final CarrierTransportBridge bridge = new CarrierTransportBridge();

    /** Built on first REGISTERED state transition; nulled on stop(). */
    private CarrierMessageSender sender;
    private CarrierMessageReceiver receiver;
    private CarrierMsrpSessionManager msrpManager;

    /** Per-toUri MSRP-session orchestration: holds the queued messages
     *  waiting for SESSION_ESTABLISHED, plus the sender/receiver pair that
     *  takes over once we're up. Key is the recipient URI (the conversation
     *  partner). Today we hold one chat session per peer; group chat will
     *  re-key this map on the conference URI (P3 follow-up). */
    private final Map<String, MsrpSessionDispatch> msrpDispatchByPeer = new HashMap<>();

    private volatile Listener listener;
    private volatile RegistrationState state = RegistrationState.UNREGISTERED;
    private volatile boolean started;

    // MLS E2EE: the app-provided-MLS transport, co-located with the MSRP transport in
    // :ims. Lazily created; wired as the receiver's inbound MLS handler + used by sendMls().
    private static final String MLS_KDS_URL = "https://kds.rcs.mnc001.mcc001.pub.3gppnetwork.org";
    private volatile com.android.messaging.rcs.e2ee.MlsCarrierTransport mMls;
    private volatile android.net.Network mCellNetwork;

    public CarrierRcsTransport(Context ctx, RcsImsConfig config) {
        if (config == null) throw new IllegalArgumentException("null RcsImsConfig");
        this.appContext = ctx.getApplicationContext();
        this.config = config;
        this.registrar = new CarrierSipRegistrar(this.appContext, config);
        registrar.setListener(this::onRegistrarStateChanged);
        registrar.setRequestForwarder(this::onIncomingRequest);
        registrar.setResponseForwarder(this::onResponse);
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    public RegistrationState getRegistrationState() {
        return state;
    }

    @Override
    public void start() {
        if (started) return;
        if (!CarrierTransportBridge.isConfigUsable(config)) {
            // No SIP creds yet — Pev3 hasn't completed. Behave like
            // OttTransport: surface FAILED with a clear reason.
            Log.w(TAG, "RcsImsConfig incomplete; staying UNREGISTERED -> FAILED");
            notifyState(RegistrationState.FAILED, "RcsImsConfig incomplete");
            return;
        }
        started = true;
        notifyState(RegistrationState.REGISTERING, null);
        registrar.start();
    }

    @Override
    public void stop() {
        if (!started) {
            // Still flip back to UNREGISTERED so the public state matches the
            // registrar's view of the world.
            if (state != RegistrationState.UNREGISTERED) {
                notifyState(RegistrationState.UNREGISTERED, null);
            }
            return;
        }
        started = false;
        sender = null;
        if (receiver != null) {
            receiver.setListener(null);
            receiver = null;
        }
        // Fail any messages still queued or in-flight on an MSRP session.
        for (MsrpSessionDispatch d : new ArrayList<>(msrpDispatchByPeer.values())) {
            d.failAll("transport stopped");
            if (d.msrpSender != null) {
                d.msrpSender.onSessionClosed("transport stopped");
            }
        }
        msrpDispatchByPeer.clear();
        msrpManager = null;
        bridge.clearTracking();
        registrar.stop();
        // Note: registrar will call back UNREGISTERED via onRegistrarStateChanged.
        // We don't need to flip state here.
    }

    @Override
    public void sendMessage(String toUri, String body, String messageId) {
        final Listener l = this.listener;
        if (state != RegistrationState.REGISTERED) {
            Log.w(TAG, "sendMessage but not REGISTERED; dropping (state=" + state + ")");
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.FAILED,
                        "transport not REGISTERED (state=" + state + ")");
            }
            return;
        }
        final CarrierMessageSender s = this.sender;
        if (s == null) {
            // Should be impossible — sender is built on the same SIP-thread
            // transition that flips state to REGISTERED.
            Log.w(TAG, "sendMessage: sender is null despite REGISTERED state");
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.FAILED,
                        "sender not initialized");
            }
            return;
        }
        int bodyLen = body == null ? 0
                : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (CarrierTransportBridge.shouldUseMsrpSession(bodyLen)) {
            // Large bodies travel over an established
            // MSRP chat session. We invite (or reuse), queue the body until
            // SESSION_ESTABLISHED, then dispatch through
            // CarrierMsrpSessionSender. The sender's per-chunk MSRP-200 /
            // REPORT correlation drives the SENDING -> SENT transitions on
            // the listener.
            dispatchViaMsrpSession(toUri, body, messageId);
            return;
        }
        try {
            ClientTransaction tx = s.sendText(toUri, body, messageId);
            bridge.trackOutgoing(messageId);
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.SENDING, null);
            }
            Log.i(TAG, "MESSAGE queued -> " + toUri + " id=" + messageId
                    + " tx=" + (tx != null ? tx.getBranchId() : "null"));
        } catch (Exception e) {
            Log.e(TAG, "sendMessage failed", e);
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.FAILED,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Send an isComposing typing indicator (active/idle) over the pager-mode
     * MESSAGE plane. Best-effort: silently dropped if not REGISTERED. Driven from
     * {@link CarrierStackDrModeDriver#sendTyping}.
     */
    public void sendTyping(final String toUri, final boolean active) {
        final CarrierMessageSender s = this.sender;
        if (state != RegistrationState.REGISTERED || s == null) {
            Log.w(TAG, "sendTyping dropped: state=" + state + " sender=" + (s != null));
            return;
        }
        try {
            final IsComposingNotification n = active
                    ? IsComposingNotification.active() : IsComposingNotification.idle();
            s.sendIsComposing(toUri, n, java.util.UUID.randomUUID().toString());
            Log.i(TAG, "isComposing " + (active ? "active" : "idle") + " -> " + toUri);
        } catch (Exception e) {
            Log.w(TAG, "sendTyping failed", e);
        }
    }

    /**
     * Send a display/read IMDN (disposition=display) for a previously-received
     * message back to its sender. Driven from {@link CarrierStackDrModeDriver#sendImdn}
     * when the user reads the message. Delivery IMDNs are auto-sent by the receiver;
     * this is the explicit display leg.
     */
    /**
     * DEBUG: send a PLAIN (non-CPM) {@code text/plain} SIP MESSAGE to {@code
     * toUri} via the registered SIP flow. No CPM feature tags, so it does NOT
     * match the oma.cpm iFC and exercises the pure IMS terminating path — used
     * to trace/validate the terminating-path rearchitecture (open5gs Phase 1).
     */
    public void sendPlainDebug(final String toUri, final String text,
            final String messageId) {
        final CarrierMessageSender s = this.sender;
        if (state != RegistrationState.REGISTERED || s == null) {
            Log.w(TAG, "sendPlainDebug dropped: state=" + state + " sender=" + (s != null));
            return;
        }
        try {
            s.sendPlainText(toUri, text, messageId);
            Log.i(TAG, "PLAIN (non-CPM) MESSAGE -> " + toUri + " mid=" + messageId);
        } catch (Exception e) {
            Log.w(TAG, "sendPlainDebug failed", e);
        }
    }

    public void sendImdnDisplay(final String toUri, final String originalMessageId) {
        final CarrierMessageSender s = this.sender;
        if (state != RegistrationState.REGISTERED || s == null) {
            Log.w(TAG, "sendImdnDisplay dropped: state=" + state + " sender=" + (s != null));
            return;
        }
        try {
            final ImdnNotification n = ImdnNotification.newDisplayed(
                    originalMessageId, com.android.messaging.rcs.carrier.sip.CpimDateTime.now());
            s.sendImdnReport(toUri, n, java.util.UUID.randomUUID().toString());
            Log.i(TAG, "IMDN display -> " + toUri + " for mid=" + originalMessageId);
        } catch (Exception e) {
            Log.w(TAG, "sendImdnDisplay failed", e);
        }
    }

    /**
     * DEBUG: upload a file at {@code path} to the ACS-provisioned FT content server
     * (HTTP Digest) and log the returned rcs-ft-http+xml descriptor. Runs on the
     * :ims process (bound to the IMS APN), so the ft.rcs FQDN resolves + egresses
     * the IMS bearer. Exercises the upload leg in isolation (the file-info MESSAGE
     * send is a follow-up). Returns the descriptor XML, or null on failure.
     */
    @androidx.annotation.Nullable
    public byte[] uploadFileDebug(String path, @androidx.annotation.Nullable String toUri) {
        if (config.ftContentServerUri == null || config.ftContentServerUri.isEmpty()) {
            Log.w(TAG, "uploadFileDebug: no ftContentServerUri in config");
            return null;
        }
        final java.io.File f = new java.io.File(path);
        final byte[] bytes;
        try {
            bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "uploadFileDebug: cannot read " + path + ": " + e);
            return null;
        }
        // debug.rcs.dr.ftip: connect the FT HTTP leg to this IP while keeping the
        // ftHTTPCSURI FQDN for Host/SNI/cert (the ft.rcs FQDN isn't resolvable on the
        // IMS bearer; mirrors using the PCO P-CSCF IP). Empty => resolve normally.
        final String ftIp = android.os.SystemProperties.get("debug.rcs.dr.ftip", "");
        Log.i(TAG, "uploadFileDebug: POST " + config.ftContentServerUri + " file="
                + f.getName() + " (" + bytes.length + "B) user=" + config.ftCsUser
                + (ftIp.isEmpty() ? "" : " via " + ftIp));
        // FT server + its DNS live on the DATA APN, not the IMS APN this process is
        // bound to — acquire the internet network so the HTTP leg egresses it.
        final android.net.ConnectivityManager.NetworkCallback[] cbHolder =
                new android.net.ConnectivityManager.NetworkCallback[1];
        final android.net.Network inet =
                CarrierFtHttpUploader.acquireInternetNetwork(appContext, cbHolder);
        if (inet == null) {
            Log.w(TAG, "uploadFileDebug: no internet network for the FT leg");
        }
        try {
            final CarrierFtHttpUploader up = new CarrierFtHttpUploader(
                    config.ftContentServerUri, config.ftCsUser, config.ftCsPassword,
                    ftIp.isEmpty() ? null : ftIp, inet);
            final CarrierFtHttpUploader.Result r = up.upload(
                    bytes, f.getName(), "application/octet-stream");
            if (r == null) {
                Log.w(TAG, "uploadFileDebug: upload returned null");
                return null;
            }
            final String desc = new String(r.descriptorXml,
                    java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "uploadFileDebug: descriptor (" + r.contentType + "):\n" + desc);
            // Round-trip: download the just-uploaded file back and verify the bytes,
            // proving the download leg (GET + Digest + data-APN) independent of the
            // (MTU-blocked) file-info MESSAGE to a peer.
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<data url=\"([^\"]+)\"").matcher(desc);
            if (m.find()) {
                String url = m.group(1).replace("&amp;", "&");
                final byte[] dl = up.download(url);
                if (dl != null && dl.length == bytes.length
                        && java.util.Arrays.equals(dl, bytes)) {
                    Log.i(TAG, "uploadFileDebug: ROUND-TRIP OK — downloaded "
                            + dl.length + "B matches upload");
                } else {
                    Log.w(TAG, "uploadFileDebug: round-trip mismatch — dl="
                            + (dl == null ? "null" : dl.length + "B") + " vs " + bytes.length + "B");
                }
            }
            // If a peer was given, send the descriptor as an FT-HTTP file-info CPIM
            // MESSAGE so the peer fetches the media (the full A->B FT chain).
            final CarrierMessageSender s = this.sender;
            if (toUri != null && !toUri.isEmpty() && s != null) {
                try {
                    final String mid = UUID.randomUUID().toString();
                    s.sendFileInfo(toUri, r.descriptorXml, mid);
                    Log.i(TAG, "uploadFileDebug: sent FT file-info -> " + toUri
                            + " mid=" + mid);
                } catch (Exception e) {
                    Log.w(TAG, "uploadFileDebug: sendFileInfo failed", e);
                }
            }
            return r.descriptorXml;
        } finally {
            if (cbHolder[0] != null) {
                try {
                    final android.net.ConnectivityManager cm =
                            (android.net.ConnectivityManager) appContext.getSystemService(
                                    android.content.Context.CONNECTIVITY_SERVICE);
                    if (cm != null) cm.unregisterNetworkCallback(cbHolder[0]);
                } catch (final Throwable ignored) { }
            }
        }
    }

    // ---------- Registrar -> Transport bridge ----------

    private void onRegistrarStateChanged(CarrierSipRegistrarState s, String reason) {
        RegistrationState mapped = CarrierTransportBridge.mapRegistrarState(s);
        if (mapped == RegistrationState.REGISTERED) {
            try {
                buildSenderReceiver();
            } catch (Exception e) {
                Log.e(TAG, "failed to build sender/receiver", e);
                notifyState(RegistrationState.FAILED, "build sender: " + e.getMessage());
                return;
            }
        }
        notifyState(mapped, reason);
    }

    private void buildSenderReceiver() {
        if (sender != null && receiver != null && msrpManager != null) return;
        if (registrar.getSipProvider() == null) {
            // Registrar reported REGISTERED but its stack is gone — degenerate
            // path. Treat as failure.
            throw new IllegalStateException("registrar has no SipProvider");
        }
        sender = new CarrierMessageSender(
                registrar.getSipProvider(),
                registrar.getMessageFactory(),
                registrar.getHeaderFactory(),
                registrar.getAddressFactory(),
                config,
                registrar.getLocalIp(),
                registrar.getLocalPort(),
                registrar.getTransportProto(),
                registrar.getPcscfHost());
        receiver = new CarrierMessageReceiver(
                registrar.getSipProvider(),
                registrar.getMessageFactory(),
                sender);
        msrpManager = new CarrierMsrpSessionManager(
                registrar.getSipProvider(),
                registrar.getMessageFactory(),
                registrar.getHeaderFactory(),
                registrar.getAddressFactory(),
                config,
                registrar.getLocalIp(),
                registrar.getLocalPort(),
                registrar.getTransportProto(),
                registrar.getPcscfHost(),
                defaultMsrpSocketFactory());
        // Wrap the listener with the bridge so we get outgoing-id tracking.
        receiver.setListener(bridge.wrap(new Transport.Listener() {
            @Override
            public void onIncomingMessage(String fromUri, String body, String messageId) {
                Listener l = listener;
                if (l != null) l.onIncomingMessage(fromUri, body, messageId);
            }
            @Override
            public void onIncomingMessage(String fromUri, String body, String messageId,
                    String e2eeSchemeId) {
                Listener l = listener;
                if (l != null) l.onIncomingMessage(fromUri, body, messageId, e2eeSchemeId);
            }
            @Override
            public void onIncomingContent(String fromUri, byte[] body, String contentType,
                    String messageId, String e2eeSchemeId) {
                // Decrypted MLS content travels as BYTES + its real content type from
                // here to the RcsIncomingMessage. Inheriting the String default would put the
                // corruption back exactly where it was.
                Listener l = listener;
                if (l != null) {
                    l.onIncomingContent(fromUri, body, contentType, messageId, e2eeSchemeId);
                }
            }
            @Override
            public void onMessageStatus(String messageId, Message.Status status,
                    String errorReason) {
                Listener l = listener;
                if (l != null) l.onMessageStatus(messageId, status, errorReason);
            }
            @Override
            public void onRegistrationStateChanged(RegistrationState s, String reason) {
                Listener l = listener;
                if (l != null) l.onRegistrationStateChanged(s, reason);
            }
        }));
        // Surface inbound typing indicators. The Transport.Listener has no typing
        // channel yet (UI dispatch is a follow-up), so log the received state — this
        // proves the isComposing receive leg end-to-end for the feature tests.
        receiver.setIsComposingHandler((fromUri, n) ->
                Log.i(TAG, "incoming isComposing from " + fromUri
                        + " state=" + n.getState()));
        // On an inbound FT-HTTP descriptor, fetch the media from the <data url>
        // (the whole point of FT). Runs off the SIP thread (download blocks).
        receiver.setFtHttpHandler((fromUri, msgId, descriptorXml) ->
                new Thread(() -> receiveFtDescriptor(fromUri, descriptorXml),
                        "carrier-ft-dl").start());
        // MLS E2EE: wire the inbound message/mls handler onto this fresh receiver so a
        // peer's Welcome/ciphertext routes to the app-provided-MLS engine (join/process -> plaintext).
        ensureMls();
    }

    /** Handle an inbound FT-HTTP descriptor: extract the {@code <data url>} and
     *  download the media over the data APN (same Digest/SNI/IP path as upload). */
    private void receiveFtDescriptor(String fromUri, byte[] descriptorXml) {
        if (descriptorXml == null || config.ftContentServerUri == null) {
            return;
        }
        final String desc = new String(descriptorXml,
                java.nio.charset.StandardCharsets.UTF_8);
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<data url=\"([^\"]+)\"").matcher(desc);
        if (!m.find()) {
            Log.w(TAG, "inbound FT descriptor has no <data url>");
            return;
        }
        final String url = m.group(1).replace("&amp;", "&");
        final String ftIp = android.os.SystemProperties.get("debug.rcs.dr.ftip", "");
        final android.net.ConnectivityManager.NetworkCallback[] cbHolder =
                new android.net.ConnectivityManager.NetworkCallback[1];
        final android.net.Network inet =
                CarrierFtHttpUploader.acquireInternetNetwork(appContext, cbHolder);
        try {
            final CarrierFtHttpUploader dl = new CarrierFtHttpUploader(
                    config.ftContentServerUri, config.ftCsUser, config.ftCsPassword,
                    ftIp.isEmpty() ? null : ftIp, inet);
            final byte[] media = dl.download(url);
            Log.i(TAG, "inbound FT-HTTP: downloaded "
                    + (media == null ? "FAILED" : media.length + "B") + " from " + fromUri);
        } finally {
            if (cbHolder[0] != null) {
                try {
                    final android.net.ConnectivityManager cm =
                            (android.net.ConnectivityManager) appContext.getSystemService(
                                    android.content.Context.CONNECTIVITY_SERVICE);
                    if (cm != null) cm.unregisterNetworkCallback(cbHolder[0]);
                } catch (final Throwable ignored) { }
            }
        }
    }

    /** Called by the registrar's SipListener for every inbound SIP request.
     *  We dispatch MESSAGE to the carrier receiver; everything else (INVITE,
     *  OPTIONS, etc.) is ignored today and will be routed later. */
    private void onIncomingRequest(RequestEvent event) {
        CarrierMessageReceiver r = this.receiver;
        if (r == null) {
            // Not yet REGISTERED — drop on the floor. The peer will retry per
            // SIP transaction rules.
            Log.w(TAG, "request before receiver wired: "
                    + event.getRequest().getMethod());
            return;
        }
        String method = event.getRequest().getMethod();
        if (Request.MESSAGE.equals(method)) {
            r.handle(event);
        } else if (Request.INVITE.equals(method)) {
            // Terminating-side: the CPM AS forked an MSRP-session INVITE to us.
            // Accept it (200 OK + SDP answer, establish MSRP, deliver inbound SENDs).
            final CarrierMsrpSessionManager m = this.msrpManager;
            if (m == null) {
                Log.w(TAG, "inbound INVITE but msrpManager not ready");
                return;
            }
            try {
                final int localMsrpPort = registrar.getLocalPort() + 2;
                // Holder so the (later-invoked) inbound listener can reference the
                // very session this INVITE establishes, to send in-session IMDNs
                // back over it.
                final MsrpChatSession[] sessionHolder = new MsrpChatSession[1];
                MsrpChatSession termSession = m.onIncomingInvite(event, localMsrpPort,
                        new Transport.Listener() {
                    @Override public void onIncomingMessage(String fromUri, String body,
                            String messageId) {
                        // Legacy text path — delegate to the binary-safe path (dispatchInbound now
                        // calls onIncomingBytes directly; kept for interface compliance).
                        onIncomingBytes(fromUri, body == null ? null
                                : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), messageId);
                    }
                    @Override public void onIncomingBytes(String fromUri, byte[] body,
                            String messageId) {
                        // The MSRP-session body is a message/cpim payload (CPIM-wrapped, relayed by
                        // the AS), which may wrap a BINARY inner type (message/mls) — route the RAW
                        // BYTES through the CPIM parse+dispatch path (NEVER via a String round-trip,
                        // which corrupts the binary MLS Welcome/ciphertext). Empty bind/keepalive
                        // SENDs (0 bytes) carry no CPIM and are skipped.
                        int len = body == null ? 0 : body.length;
                        Log.i(TAG, "MSRP-session inbound message from " + fromUri
                                + " (" + len + " bytes)"
                                + (len > 0 ? " -> CPIM unwrap" : " (empty; skipped)"));
                        CarrierMessageReceiver r = receiver;
                        if (r != null && len > 0) {
                            r.handleInboundSessionCpim(fromUri,
                                    body,
                                    // In-session IMDN router: build the imdn+xml CPIM
                                    // and send it back over THIS MSRP session leg
                                    // (RFC 5438 / RCC.07), not a pager MESSAGE.
                                    (toUri, imdn, reportId) -> {
                                        CarrierMsrpSessionManager mm = msrpManager;
                                        MsrpChatSession s = sessionHolder[0];
                                        if (mm == null || s == null) return;
                                        try {
                                            byte[] imdnCpim =
                                                com.android.messaging.rcs.carrier.sip.CpimMessage
                                                    .newImdnReport(config.publicIdentity,
                                                        toUri, reportId,
                                                        com.android.messaging.rcs.carrier.sip
                                                            .CpimDateTime.now(),
                                                        imdn.toXml())
                                                    .encode();
                                            boolean ok = mm.sendCpimOverSession(
                                                    s, imdnCpim, reportId);
                                            Log.i(TAG, "in-session IMDN " + reportId
                                                    + " -> " + toUri + " sent=" + ok);
                                        } catch (Exception e) {
                                            Log.w(TAG, "in-session IMDN send failed", e);
                                        }
                                    });
                        }
                    }
                    @Override public void onMessageStatus(String messageId,
                            Message.Status status, String errorReason) {
                        Listener l = listener;
                        if (l != null) l.onMessageStatus(messageId, status, errorReason);
                    }
                    @Override public void onRegistrationStateChanged(RegistrationState s,
                            String reason) { }
                });
                sessionHolder[0] = termSession;
            } catch (Exception e) {
                Log.e(TAG, "terminating INVITE accept failed", e);
            }
        } else if (Request.BYE.equals(method)) {
            CarrierMsrpSessionManager m = this.msrpManager;
            if (m != null) m.onBye(event);
        } else {
            // OPTIONS / NOTIFY etc. — not handled today.
            Log.i(TAG, "ignoring inbound " + method);
        }
    }

    /** Inbound non-REGISTER responses (e.g. 200 OK to our MSRP-session INVITE),
     *  routed here by {@link CarrierSipRegistrar}'s ResponseForwarder so the MSRP
     *  session manager can complete the dialog and drain the queued large body. */
    private void onResponse(ResponseEvent event) {
        CarrierMsrpSessionManager m = this.msrpManager;
        if (m == null) return;
        javax.sip.header.CSeqHeader cs = (javax.sip.header.CSeqHeader)
                event.getResponse().getHeader(javax.sip.header.CSeqHeader.NAME);
        if (cs != null && Request.INVITE.equals(cs.getMethod())) {
            m.onInviteResponse(event);
        }
    }

    /**
     * Send a large body (>= MSRP_SWITCHOVER_SIZE_BYTES) over an MSRP chat
     * session. If a session is already up to {@code toUri}, dispatch directly
     * through its sender. Otherwise issue an INVITE and queue the body until
     * SESSION_ESTABLISHED, after which the queued bodies are flushed.
     *
     * <p>Failure modes — each surfaced on {@code Listener.onMessageStatus}
     * as FAILED with a diagnostic:
     * <ul>
     *   <li>MSRP manager not yet wired up (REGISTERED state without an
     *       msrpManager — shouldn't happen but defensible).</li>
     *   <li>INVITE build / send failure (ParseException / SipException).</li>
     *   <li>Session 4xx/5xx response → INVITE_REJECTED reason.</li>
     *   <li>TLS handshake failure / fingerprint mismatch → TLS_ERROR reason.</li>
     *   <li>Per-chunk MSRP-200 4xx / 5xx → from {@link CarrierMsrpSessionSender}.</li>
     *   <li>Mid-send session drop → from {@link CarrierMsrpSessionSender}.</li>
     * </ul>
     */
    private void dispatchViaMsrpSession(String toUri, String body, String messageId) {
        // The MSRP SEND declares Content-Type: message/cpim, so the body MUST be a real CPIM message,
        // not raw text (raw text made open5gs's CPIMPayload.decode raise CPIMParserError -> silent
        // drop). Wrap as the pager path does (CpimMessage.newText), then enqueue over the session.
        final byte[] payload;
        if (body == null || body.isEmpty()) {
            payload = new byte[0];
        } else {
            com.android.messaging.rcs.carrier.sip.CpimMessage cpim =
                    com.android.messaging.rcs.carrier.sip.CpimMessage.newText(
                            config.publicIdentity, toUri, messageId,
                            com.android.messaging.rcs.carrier.sip.CpimDateTime.now(),
                            body);
            payload = cpim.encode();
        }
        enqueueCpimPayload(toUri, payload, messageId);
    }

    /**
     * Send an MLS-plane body over the held CPM/MSRP session to {@code toUri}: a CPIM body
     * with inner content-type {@code contentType} (one of {@code CpimMessage.CT_MLS[-rcs-*]}) carrying
     * the raw MLSMessage {@code wire} bytes + the {@code mls.Era-ID}/{@code mls.Epoch-Authenticator}
     * sub-headers. Reuses the same per-peer MSRP dispatch as text (INVITE-on-first, keep-warm). This is
     * how {@code MlsCarrierTransport} delivers Welcome/Commit control + application ciphertext.
     */
    public void sendMlsBody(String toUri, String contentType, byte[] wire, String eraId,
            String epochAuthB64, String messageId) {
        final com.android.messaging.rcs.carrier.sip.CpimMessage cpim =
                com.android.messaging.rcs.carrier.sip.CpimMessage.newMls(
                        config.publicIdentity, toUri, messageId,
                        com.android.messaging.rcs.carrier.sip.CpimDateTime.now(),
                        contentType, wire, eraId, epochAuthB64);
        enqueueCpimPayload(toUri, cpim.encode(), messageId);
    }

    /**
     * Send an MLS-E2EE chat message to {@code peerE164} over the held CPM/MSRP session:
     * ensure the group (enrol/claim/createGroup + deliver the Welcome), encrypt, and dispatch the
     * {@code message/mls} body. Runs in the :ims process co-located with the transport.
     *
     * <p><b>{@code framedBody} is an RCC.16 MIME entity, not text.</b> This took a
     * {@code String} and did {@code text.getBytes(UTF_8)} here, which meant the carrier leg could
     * send nothing but UTF-8 text and never framed — so a real Google or Apple peer, which
     * expects a self-describing entity, could not read it, and A&lt;-&gt;B survived only because
     * {@code RccMlsBody.parse} returns a frameless payload verbatim. Framing is the caller's job
     * ({@code InsertNewMessageAction}); stamping the generation is {@code encryptForSend}'s.
     */
    public void sendMls(int subId, String toUri, String peerE164, byte[] framedBody,
            String messageId) {
        final com.android.messaging.rcs.e2ee.MlsCarrierTransport mls = ensureMls();
        if (!mls.ensureReady(peerE164, subId, java.util.Collections.singletonList(peerE164))) {
            Log.w(TAG, "sendMls: ensureReady failed for " + peerE164);
            if (listener != null) {
                listener.onMessageStatus(messageId, Message.Status.FAILED, "MLS not ready");
            }
            return;
        }
        // PASS THE ENVELOPE'S ID INTO THE SEAL. sendMlsBody below puts `messageId`
        // on the wire as the CPIM Message-ID; RCC.16 §7.5.3.1 requires the AAD to bind THAT id,
        // and a Google Messages peer drops a mismatch. This call used to omit it, so the seal bound a
        // synthesised "mls-<peer>-<era>" while the envelope said the app's row UUID — two
        // different ids on every message, reconciled nowhere.
        final com.android.messaging.rcs.e2ee.E2eeConversationTransport.Payload p =
                mls.encryptForSend(peerE164, framedBody, messageId);
        if (p == null) {
            Log.w(TAG, "sendMls: encrypt failed for " + peerE164);
            if (listener != null) {
                listener.onMessageStatus(messageId, Message.Status.FAILED, "MLS encrypt failed");
            }
            return;
        }
        sendMlsBody(toUri, p.contentType, p.body,
                p.cpimHeaders.get(com.android.messaging.rcs.carrier.sip.CpimMessage.HDR_MLS_ERA_ID),
                p.cpimHeaders.get(com.android.messaging.rcs.carrier.sip.CpimMessage.HDR_MLS_EPOCH_AUTH),
                messageId);
        Log.i(TAG, "sendMls: dispatched message/mls (" + p.body.length + "B) to " + peerE164);
    }

    /** Lazily create + wire the MLS carrier transport (idempotent). */
    private synchronized com.android.messaging.rcs.e2ee.MlsCarrierTransport ensureMls() {
        if (mMls == null) {
            final com.android.messaging.rcs.e2ee.MlsCarrierTransport.Config cfg =
                    new com.android.messaging.rcs.e2ee.MlsCarrierTransport.Config() {
                        @Override public String selfE164(int subId) { return mlsTelOnly(config.publicIdentity); }
                        @Override public String kdsBaseUrl() {
                            // The ACS gets to say where its KDS is; the constant is the lab default
                            // for when it does not (or has not been fetched yet).
                            return config.kdsUri.isEmpty() ? MLS_KDS_URL : config.kdsUri;
                        }
                        @Override public String acsEncryptionIdentityProof() {
                            return config.acsEncryptionIdentityProof;
                        }
                        // The ACS names the lab trust-anchor list, its generation and
                        // the key that signs it. Handed to the provider, which fetches + verifies.
                        @Override public String trustAnchorsUri() { return config.trustAnchorsUri; }
                        @Override public long trustAnchorsGeneration() {
                            return config.trustAnchorsGeneration;
                        }
                        @Override public String trustAnchorsSigner() {
                            return config.trustAnchorsSigner;
                        }
                        @Override public android.net.Network cellularNetwork() { return acquireCellNetwork(); }
                    };
            mMls = new com.android.messaging.rcs.e2ee.MlsCarrierTransport(
                    appContext, new com.android.messaging.rcs.engine.mls.OpenMlsEngine(
                            // LAB_RCC16: the carrier path clamps KP lifetime to the client cert per
                            // RCC.16 5.1. The Tachyon profile's 365-day KP is what Google's KDS wants
                            // and what the lab KDS REJECTS — see OpenMlsEngine.Profile.
                            com.android.messaging.rcs.engine.mls.OpenMlsEngine.Profile.LAB_RCC16),
                    this::sendMlsBody, cfg);
        }
        // Wire (or re-wire) the inbound MLS handler onto the current receiver.
        final CarrierMessageReceiver r = this.receiver;
        if (r != null) {
            final com.android.messaging.rcs.e2ee.MlsCarrierTransport t = mMls;
            r.setMlsInboundHandler((sender, ct, payload, envelopeMessageId) -> {
                final com.android.messaging.rcs.e2ee.E2eeConversationTransport.Inbound in =
                        t.onInboundCpim(sender, sender, ct, payload, envelopeMessageId);
                return in.plaintext;
            });
            // Provision (upload a fresh KP pool) off-thread so a peer can claim THIS session's KP.
            t.provisionAsync(android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId());
        }
        return mMls;
    }

    /** Acquire (and cache) the cellular {@link android.net.Network} for the KDS (internet PDN). */
    private android.net.Network acquireCellNetwork() {
        if (mCellNetwork != null) {
            return mCellNetwork;
        }
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final java.util.concurrent.atomic.AtomicReference<android.net.Network> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.net.ConnectivityManager.NetworkCallback cb =
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(final android.net.Network n) {
                            ref.set(n); latch.countDown();
                        }
                    };
            cm.requestNetwork(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb);
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            mCellNetwork = ref.get();
        } catch (final Throwable t) {
            Log.w(TAG, "acquireCellNetwork failed", t);
        }
        return mCellNetwork;
    }

    /** Bare {@code +E164} from a {@code tel:}/{@code sip:+E164@domain} identity. */
    private static String mlsTelOnly(String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        if (u.startsWith("tel:")) u = u.substring(4);
        else if (u.startsWith("sip:")) { u = u.substring(4); int at = u.indexOf('@'); if (at > 0) u = u.substring(0, at); }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi);
        return u.trim();
    }

    /** Enqueue a pre-encoded CPIM payload over the per-peer MSRP session, firing the INVITE on first use. */
    private void enqueueCpimPayload(String toUri, byte[] payload, String messageId) {
        final Listener l = this.listener;
        CarrierMsrpSessionManager m = this.msrpManager;
        if (m == null) {
            Log.w(TAG, "enqueueCpimPayload: msrpManager not initialized");
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.FAILED,
                        "MSRP manager not initialized");
            }
            return;
        }
        MsrpSessionDispatch d = msrpDispatchByPeer.get(toUri);
        if (d == null) {
            d = new MsrpSessionDispatch(toUri);
            msrpDispatchByPeer.put(toUri, d);
        }
        d.enqueue(messageId, payload);
        if (d.session == null) {
            // First message for this peer — fire the INVITE; the dispatch drains on ESTABLISHED.
            try {
                int localMsrpPort = registrar.getLocalPort() + 1;
                MsrpChatSession session = m.invite(toUri, localMsrpPort,
                        SdpOffer.SetupRole.ACTPASS,
                        /*localFingerprintAlg=*/ null,
                        /*localFingerprintHex=*/ null,
                        /*contributionId=*/ null);
                d.attachSession(session);
                Log.i(TAG, "MSRP session INVITE dispatched to " + toUri
                        + " state=" + session.getState());
            } catch (Exception e) {
                Log.w(TAG, "MSRP INVITE failed for " + toUri, e);
                String reason = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                d.failAll("INVITE failed: " + reason);
                msrpDispatchByPeer.remove(toUri);
            }
        } else {
            d.drainIfEstablished();
        }
    }

    /**
     * One per-peer MSRP-session orchestration unit. Owns the queue of
     * messages waiting for ESTABLISHED, the {@link CarrierMsrpSessionSender}
     * (created at ESTABLISHED), and the {@link CarrierMsrpSessionReceiver}
     * for inbound SENDs over the same session.
     *
     * <p>The dispatch instance also fronts the
     * {@link MsrpChatSession.Listener} contract: state changes drain the
     * queue (on ESTABLISHED) or fail it (on terminal close), and frame
     * dispatches are routed to the sender (for RESPONSE / REPORT correlation)
     * or the receiver (for SEND reassembly).
     */
    private final class MsrpSessionDispatch implements MsrpChatSession.Listener {
        final String toUri;
        MsrpChatSession session;
        CarrierMsrpSessionSender msrpSender;
        CarrierMsrpSessionReceiver msrpReceiver;
        /** (uiMessageId, body) pairs queued before ESTABLISHED. */
        final List<QueuedMsg> queue = new ArrayList<>();

        MsrpSessionDispatch(String toUri) {
            this.toUri = toUri;
        }

        void enqueue(String messageId, byte[] body) {
            queue.add(new QueuedMsg(messageId, body));
        }

        void attachSession(MsrpChatSession session) {
            this.session = session;
            session.setListener(this);
            // If the session went ESTABLISHED synchronously (test harness
            // path / unusual production timing), we still need to wire the
            // sender/receiver and drain.
            if (session.getState() == MsrpChatSession.State.ESTABLISHED) {
                onEstablishedLocked();
            }
        }

        @Override
        public void onStateChanged(MsrpChatSession.State newState,
                MsrpChatSession.CloseReason reason, String detail) {
            switch (newState) {
                case ESTABLISHED:
                    onEstablishedLocked();
                    break;
                case CLOSING:
                case CLOSED:
                    onClosedLocked(reason, detail);
                    break;
                case INVITING:
                case IDLE:
                default:
                    // No-op — we're still pre-ESTABLISHED.
                    break;
            }
        }

        @Override
        public void onMsrpMessage(MsrpMessage message) {
            if (message == null) return;
            // DIAG: mirror the terminating leg's inbound-frame log so
            // we can see frames the AS relays onto this originating leg (e.g. the
            // peer's in-session delivered/displayed IMDN).
            try {
                int blen = message.getBody() == null ? 0 : message.getBody().length;
                Log.i(TAG, "inbound MSRP frame (orig-leg) kind=" + message.getKind()
                        + " method=" + message.getMethod()
                        + " msgId=" + message.getMessageId() + " bodyLen=" + blen);
            } catch (Throwable ignore) { /* best-effort diag */ }
            if (message.getKind() == MsrpMessage.Kind.RESPONSE) {
                if (msrpSender != null) msrpSender.onResponse(message);
                return;
            }
            if (message.getMethod() == MsrpMethod.REPORT) {
                if (msrpSender != null) msrpSender.onReport(message);
                return;
            }
            if (message.getMethod() == MsrpMethod.SEND) {
                if (msrpReceiver != null) msrpReceiver.onSendChunk(message);
            }
            // AUTH and other methods are not handled today.
        }

        private void onEstablishedLocked() {
            if (msrpSender != null) return; // already drained
            CarrierMsrpSessionManager m = msrpManager;
            if (m == null) {
                failAll("MSRP manager went away pre-ESTABLISHED");
                return;
            }
            // Bind a writer that funnels frames back through the manager (and
            // therefore the MsrpTlsConnection it owns). The transport's
            // Listener — wrapped via the bridge — is the dispatch target.
            final MsrpChatSession capturedSession = session;
            CarrierMsrpSessionSender.FrameWriter writer = new CarrierMsrpSessionSender.FrameWriter() {
                @Override
                public void write(MsrpMessage frame) throws IOException {
                    m.send(capturedSession, frame);
                }
            };
            Listener downstream = wrapForBridge();
            msrpSender = new CarrierMsrpSessionSender(session, writer, downstream);
            msrpReceiver = new CarrierMsrpSessionReceiver(session, writer, downstream);

            drainQueue();
        }

        /** Flush all queued messages over the (established) session's sender. */
        private void drainQueue() {
            if (msrpSender == null) return;
            List<QueuedMsg> snap = new ArrayList<>(queue);
            queue.clear();
            if (snap.isEmpty()) return;
            Log.i(TAG, "MSRP draining " + snap.size() + " queued msg(s)");
            for (QueuedMsg q : snap) {
                bridge.trackOutgoing(q.messageId);
                try {
                    msrpSender.send(q.messageId, /*contentType=*/ null, q.body,
                            /*wantsSuccessReport=*/ true);
                    Log.i(TAG, "MSRP SEND emitted id=" + q.messageId
                            + " (" + (q.body == null ? 0 : q.body.length) + "B)");
                } catch (Throwable t) {
                    Log.w(TAG, "MSRP SEND failed id=" + q.messageId, t);
                }
            }
        }

        /**
         * Drain immediately if the session is ALREADY established. onEstablished
         * fires only on the pre→ESTABLISHED transition, so the 2nd+ message on a
         * warm (reused) session would otherwise sit in the queue forever.
         */
        void drainIfEstablished() {
            if (msrpSender != null && session != null
                    && session.getState() == MsrpChatSession.State.ESTABLISHED) {
                drainQueue();
            }
        }

        private void onClosedLocked(MsrpChatSession.CloseReason reason, String detail) {
            String msg = (reason == null ? "session closed"
                    : reason.name()) + (detail == null ? "" : ": " + detail);
            // Fail anything still in flight on the sender.
            if (msrpSender != null) {
                msrpSender.onSessionClosed(msg);
            }
            // Anything still queued never got to the sender — fail them
            // explicitly so the listener sees a terminal status.
            failAll(msg);
            msrpDispatchByPeer.remove(toUri);
        }

        void failAll(String reason) {
            Listener downstream = wrapForBridge();
            List<QueuedMsg> snap = new ArrayList<>(queue);
            queue.clear();
            for (QueuedMsg q : snap) {
                if (downstream != null) {
                    downstream.onMessageStatus(q.messageId,
                            Message.Status.FAILED, reason);
                }
            }
        }

        private Listener wrapForBridge() {
            return bridge.wrap(new Listener() {
                @Override
                public void onIncomingMessage(String fromUri, String body,
                        String messageId) {
                    // Legacy text path — delegate to the binary-safe path so an
                    // MLS ciphertext/Welcome arriving on THIS (orig) leg isn't
                    // corrupted by a UTF-8 String round-trip. Mirrors the
                    // terminating leg (see onIncomingInvite listener above).
                    onIncomingBytes(fromUri, body == null ? null
                            : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), messageId);
                }
                @Override
                public void onIncomingBytes(String fromUri, byte[] body,
                        String messageId) {
                    // The MSRP-session body is a message/cpim payload (text, the
                    // peer's in-session IMDN, OR a BINARY message/mls inner type),
                    // so CPIM-unwrap the RAW BYTES here. An inbound
                    // delivered/displayed IMDN flows to the imdn+xml branch ->
                    // onMessageStatus; an inbound TEXT auto-replies with an
                    // in-session IMDN over THIS leg.
                    int len = body == null ? 0 : body.length;
                    Log.i(TAG, "MSRP-session inbound message (orig-leg) from "
                            + fromUri + " (" + len + " bytes)"
                            + (len > 0 ? " -> CPIM unwrap" : " (empty; skipped)"));
                    CarrierMessageReceiver r = receiver;
                    if (r != null && len > 0) {
                        r.handleInboundSessionCpim(fromUri,
                                body,
                                (toUri, imdn, reportId) -> {
                                    CarrierMsrpSessionSender snd = msrpSender;
                                    if (snd == null) return;
                                    try {
                                        byte[] imdnCpim =
                                            com.android.messaging.rcs.carrier.sip.CpimMessage
                                                .newImdnReport(config.publicIdentity,
                                                    toUri, reportId,
                                                    com.android.messaging.rcs.carrier.sip
                                                        .CpimDateTime.now(),
                                                    imdn.toXml())
                                                .encode();
                                        boolean ok = snd.send(reportId, "message/cpim",
                                                imdnCpim, /*wantsSuccessReport=*/ false);
                                        Log.i(TAG, "in-session IMDN (orig-leg) "
                                                + reportId + " -> " + toUri
                                                + " sent=" + ok);
                                    } catch (Exception e) {
                                        Log.w(TAG, "in-session IMDN (orig-leg) failed", e);
                                    }
                                });
                    }
                }
                @Override
                public void onMessageStatus(String messageId,
                        Message.Status status, String errorReason) {
                    Listener l = listener;
                    if (l != null) l.onMessageStatus(messageId, status, errorReason);
                }
                @Override
                public void onRegistrationStateChanged(RegistrationState s,
                        String reason) {
                    Listener l = listener;
                    if (l != null) l.onRegistrationStateChanged(s, reason);
                }
            });
        }
    }

    private static final class QueuedMsg {
        final String messageId;
        final byte[] body;
        QueuedMsg(String id, byte[] b) { messageId = id; body = b; }
    }

    /** Default MSRP socket factory: returns TLS sockets via the JVM's
     *  default {@link SSLSocketFactory}. The factory completes the TLS
     *  handshake synchronously so the connection can read out the peer
     *  cert for RFC 4572 fingerprint validation.
     *
     *  <p>TODO: production-grade trust store + (optional) cert
     *  pinning. RFC 4572 SDP-fingerprint binding is sufficient per spec
     *  but a follow-up may want pinned roots for additional
     *  hardening. */
    private static MsrpTlsConnection.SocketFactory defaultMsrpSocketFactory() {
        return new MsrpTlsConnection.SocketFactory() {
            @Override
            public Socket connect(String host, int port) throws IOException {
                // First-light MSRP is PLAIN TCP (our SDP offer is 'm=message TCP/MSRP'
                // with no a=fingerprint, and the lab CPM AS's MSRP relay is plain TCP).
                // The old default forced SSLSocket+startHandshake regardless, so a
                // plain-TCP session still attempted TLS. Honor the negotiated plain
                // transport; MSRP-over-TLS (a=fingerprint) is a later refinement
                // (bd-10m) that will select a TLS factory by the offer's proto.
                // debug.rcs.dr.msrptls=true forces TLS for that testing.
                if (SystemProperties.getBoolean("debug.rcs.dr.msrptls", false)) {
                    SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    Socket s = sf.createSocket(host, port);
                    if (s instanceof javax.net.ssl.SSLSocket) {
                        ((javax.net.ssl.SSLSocket) s).startHandshake();
                    }
                    return s;
                }
                final Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(host, port), 20000);
                return s;
            }
        };
    }

    private void notifyState(RegistrationState s, String reason) {
        this.state = s;
        Log.i(TAG, "state=" + s + (reason != null ? " (" + reason + ")" : ""));
        Listener l = listener;
        if (l != null) l.onRegistrationStateChanged(s, reason);
        // MLS KeyPackage replenishment: re-upload a fresh pool on each (re)REGISTER — the
        // registration-change cadence (no server depletion-notify for MLS; last-resort covers gaps).
        if (s == RegistrationState.REGISTERED && mMls != null) {
            mMls.replenishKeyPackagesAsync();
        }
    }

    // ---------- public for tests / TransportRegistry ----------

    /**
     * Returns true iff the supplied config has the minimum fields a carrier
     * REGISTER needs. See {@link CarrierTransportBridge#isConfigUsable}.
     */
    public static boolean isConfigUsable(RcsImsConfig c) {
        return CarrierTransportBridge.isConfigUsable(c);
    }

    /** Visible-for-test: did the transport accept this messageId as in-flight? */
    boolean hasOutgoingId(String messageId) {
        return bridge.isTrackingOutgoing(messageId);
    }

    /** Visible-for-test: generate a fresh CPIM-compatible message id. */
    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
