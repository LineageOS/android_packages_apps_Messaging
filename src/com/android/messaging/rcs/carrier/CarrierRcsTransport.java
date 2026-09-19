/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import com.android.messaging.rcs.RcsDebug;
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
import com.android.messaging.rcs.log.LogMask;

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
 * The DR {@link Transport}: composes {@link CarrierSipRegistrar} (registration),
 * {@link CarrierMessageSender} and {@link CarrierMessageReceiver} (pager mode),
 * {@link CarrierMsrpSessionManager} (sessions for bodies of at least
 * {@link #MSRP_SWITCHOVER_SIZE_BYTES}) and the app's MLS layer. The app's message id is the CPIM
 * {@code imdn.Message-ID}, so IMDNs correlate. SIP work runs on the registrar's thread; listener
 * callbacks may arrive there. See docs/rcs/carrier-transport.md.
 */
public final class CarrierRcsTransport implements Transport {

    private static final String TAG = "CarrierRcsTransport";

    public static final int MSRP_SWITCHOVER_SIZE_BYTES =
            CarrierTransportBridge.MSRP_SWITCHOVER_SIZE_BYTES;

    private final Context appContext;
    private final RcsImsConfig config;
    private final CarrierSipRegistrar registrar;
    private final CarrierTransportBridge bridge = new CarrierTransportBridge();

    /** Built on the first transition to REGISTERED; cleared by stop(). */
    private CarrierMessageSender sender;
    private CarrierMessageReceiver receiver;
    private CarrierMsrpSessionManager msrpManager;

    /**
     * Per peer URI: messages queued until the session is established, and the session's sender and
     * receiver afterwards. One chat session per peer.
     */
    private final Map<String, MsrpSessionDispatch> msrpDispatchByPeer = new HashMap<>();

    private volatile Listener listener;
    private volatile RegistrationState state = RegistrationState.UNREGISTERED;
    private volatile boolean started;

    // The app's MLS layer for this transport, created lazily; handles inbound MLS and sendMls().
    // Stays null while carrier-path MLS is off (see ensureMls).
    private volatile com.android.messaging.rcs.e2ee.MlsCarrierTransport mMls;
    private volatile boolean mMlsOffLogged;
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
            // No usable configuration yet: report FAILED.
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
            // Keep the public state in line with the registrar's.
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
        // Fail everything queued or in flight on a session.
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
        // The registrar reports UNREGISTERED through onRegistrarStateChanged.
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
            // The sender is built on the same transition that sets REGISTERED.
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
            // A session: invite or reuse, queue until established, then CarrierMsrpSessionSender
            // reports the statuses.
            dispatchViaMsrpSession(toUri, body, messageId);
            return;
        }
        try {
            ClientTransaction tx = s.sendText(toUri, body, messageId);
            bridge.trackOutgoing(messageId);
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.SENDING, null);
            }
            Log.i(TAG, "MESSAGE queued -> " + LogMask.number(toUri) + " id=" + messageId
                    + " tx=" + (tx != null ? tx.getBranchId() : "null"));
        } catch (Exception e) {
            Log.e(TAG, "sendMessage failed", e);
            if (l != null) {
                l.onMessageStatus(messageId, Message.Status.FAILED,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** A pager-mode typing indicator; best effort, dropped unless registered. */
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
            Log.i(TAG, "isComposing " + (active ? "active" : "idle") + " -> "
                    + LogMask.number(toUri));
        } catch (Exception e) {
            Log.w(TAG, "sendTyping failed", e);
        }
    }

    /**
     * Debug: a plain {@code text/plain} SIP message with no CPM tags, so it does not match the CPM
     * filter criteria and exercises the bare IMS terminating path.
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
            Log.i(TAG, "PLAIN (non-CPM) MESSAGE -> " + LogMask.number(toUri) + " mid=" + messageId);
        } catch (Exception e) {
            Log.w(TAG, "sendPlainDebug failed", e);
        }
    }

    /**
     * A display receipt for a received message, sent in pager mode. Delivered receipts are sent
     * automatically by the receiver.
     */
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
            Log.i(TAG, "IMDN display -> " + LogMask.number(toUri) + " for mid="
                    + originalMessageId);
        } catch (Exception e) {
            Log.w(TAG, "sendImdnDisplay failed", e);
        }
    }

    /**
     * Debug: uploads the file at {@code path} to the content server, downloads it back to compare,
     * and sends the descriptor to {@code toUri} if given. Returns the descriptor, or null.
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
        // debug.rcs.dr.ftip (debug builds): connect to this address while keeping the host name
        // for Host, SNI and certificate checks, for a content server whose name does not resolve on
        // the IMS bearer.
        final String ftIp = RcsDebug.isDebugBuild()
                ? android.os.SystemProperties.get("debug.rcs.dr.ftip", "") : "";
        Log.i(TAG, "uploadFileDebug: POST " + config.ftContentServerUri + " file="
                + f.getName() + " (" + bytes.length + "B) user=" + config.ftCsUser
                + (ftIp.isEmpty() ? "" : " via " + ftIp));
        // The content server is on the data network, while this process is bound to the IMS one.
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
            // Download it back and compare, to check the download path on its own.
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
                            + (dl == null ? "null" : dl.length + "B") + " vs " + bytes.length
                            + "B");
                }
            }
            // Send the descriptor so the peer fetches the file.
            final CarrierMessageSender s = this.sender;
            if (toUri != null && !toUri.isEmpty() && s != null) {
                try {
                    final String mid = UUID.randomUUID().toString();
                    s.sendFileInfo(toUri, r.descriptorXml, mid);
                    Log.i(TAG, "uploadFileDebug: sent FT file-info -> " + LogMask.number(toUri)
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

    // Registrar to transport.

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
            // Registered but no SIP stack: treat as a failure.
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
        // The bridge wrapper tracks outgoing ids.
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
                // Decrypted MLS content stays bytes with its real type; the String default would
                // corrupt it.
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
        // The Transport.Listener has no typing callback, so inbound typing is only logged.
        receiver.setIsComposingHandler((fromUri, n) ->
                Log.i(TAG, "incoming isComposing from " + LogMask.number(fromUri)
                        + " state=" + n.getState()));
        // Fetch the file named by an inbound descriptor, off the SIP thread.
        receiver.setFtHttpHandler((fromUri, msgId, descriptorXml) ->
                new Thread(() -> receiveFtDescriptor(fromUri, descriptorXml),
                        "carrier-ft-dl").start());
        // Attach the inbound MLS handler to this receiver.
        ensureMls();
    }

    /** Downloads the {@code <data url>} of an inbound descriptor over the data network. */
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
        final String ftIp = RcsDebug.isDebugBuild()
                ? android.os.SystemProperties.get("debug.rcs.dr.ftip", "") : "";
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
                    + (media == null ? "FAILED" : media.length + "B") + " from "
                    + LogMask.number(fromUri));
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

    /**
     * Every inbound request, from the registrar: pager messages to the receiver, INVITE and BYE to
     * the session manager; anything else is ignored.
     */
    private void onIncomingRequest(RequestEvent event) {
        CarrierMessageReceiver r = this.receiver;
        if (r == null) {
            // Not registered yet: drop; the peer retransmits.
            Log.w(TAG, "request before receiver wired: "
                    + event.getRequest().getMethod());
            return;
        }
        String method = event.getRequest().getMethod();
        if (Request.MESSAGE.equals(method)) {
            r.handle(event);
        } else if (Request.INVITE.equals(method)) {
            // A session to us: answer, connect MSRP and deliver the SENDs.
            final CarrierMsrpSessionManager m = this.msrpManager;
            if (m == null) {
                Log.w(TAG, "inbound INVITE but msrpManager not ready");
                return;
            }
            try {
                final int localMsrpPort = registrar.getLocalPort() + 2;
                // Lets the listener send receipts over the session this INVITE establishes.
                final MsrpChatSession[] sessionHolder = new MsrpChatSession[1];
                MsrpChatSession termSession = m.onIncomingInvite(event, localMsrpPort,
                        new Transport.Listener() {
                    @Override public void onIncomingMessage(String fromUri, String body,
                            String messageId) {
                        // Not used by the session receiver, which delivers bytes; kept for the
                        // interface.
                        onIncomingBytes(fromUri, body == null ? null
                                : body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                messageId);
                    }
                    @Override public void onIncomingBytes(String fromUri, byte[] body,
                            String messageId) {
                        // A message/cpim body that may wrap a binary inner type: unwrap it from the
                        // bytes. Empty SENDs carry no CPIM and are skipped.
                        int len = body == null ? 0 : body.length;
                        Log.i(TAG, "MSRP-session inbound message from " + LogMask.number(fromUri)
                                + " (" + len + " bytes)"
                                + (len > 0 ? " -> CPIM unwrap" : " (empty; skipped)"));
                        CarrierMessageReceiver r = receiver;
                        if (r != null && len > 0) {
                            r.handleInboundSessionCpim(fromUri,
                                    body,
                                    // Receipts go back over this session (RFC 5438, GSMA RCC.07).
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
                                                    + " -> " + LogMask.number(toUri) + " sent="
                                                    + ok);
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
            Log.i(TAG, "ignoring inbound " + method);
        }
    }

    /**
     * Responses to anything but REGISTER, from the registrar; INVITE responses complete sessions.
     */
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
     * A body of at least {@link #MSRP_SWITCHOVER_SIZE_BYTES}, wrapped in CPIM, over the peer's
     * session. Failures reach {@code onMessageStatus} as {@code FAILED}.
     */
    private void dispatchViaMsrpSession(String toUri, String body, String messageId) {
        // The SEND is declared message/cpim, so the body must be CPIM.
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
     * An MLS body over the peer's session: CPIM with an inner {@code message/mls*} type and the
     * {@code mls.Era-ID} and {@code mls.Epoch-Authenticator} headers. How
     * {@code MlsCarrierTransport} sends control and application messages.
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
     * Seals and sends an MLS chat message to {@code peerE164}, setting the group up first if
     * needed. {@code framedBody} is an RCC.16 MIME entity framed by the caller, not text.
     */
    public void sendMls(int subId, String toUri, String peerE164, byte[] framedBody,
            String messageId) {
        final com.android.messaging.rcs.e2ee.MlsCarrierTransport mls = ensureMls();
        if (mls == null) {
            // The app does not route MLS here while it is off; refuse rather than send clear.
            Log.w(TAG, "sendMls: carrier-path MLS is off on this build");
            if (listener != null) {
                listener.onMessageStatus(messageId, Message.Status.FAILED, "MLS off");
            }
            return;
        }
        if (!mls.ensureReady(peerE164, subId, java.util.Collections.singletonList(peerE164))) {
            Log.w(TAG, "sendMls: ensureReady failed for " + LogMask.number(peerE164));
            if (listener != null) {
                listener.onMessageStatus(messageId, Message.Status.FAILED, "MLS not ready");
            }
            return;
        }
        // RCC.16 §7.5.3.1: the AAD binds the same id sendMlsBody puts on the envelope.
        final com.android.messaging.rcs.e2ee.E2eeConversationTransport.Payload p =
                mls.encryptForSend(peerE164, framedBody, messageId);
        if (p == null) {
            Log.w(TAG, "sendMls: encrypt failed for " + LogMask.number(peerE164));
            if (listener != null) {
                listener.onMessageStatus(messageId, Message.Status.FAILED, "MLS encrypt failed");
            }
            return;
        }
        sendMlsBody(toUri, p.contentType, p.body,
                p.cpimHeaders.get(com.android.messaging.rcs.carrier.sip.CpimMessage.HDR_MLS_ERA_ID),
                p.cpimHeaders.get(
                        com.android.messaging.rcs.carrier.sip.CpimMessage.HDR_MLS_EPOCH_AUTH),
                messageId);
        Log.i(TAG, "sendMls: dispatched message/mls (" + p.body.length + "B) to "
                + LogMask.number(peerE164));
    }

    /**
     * Creates the MLS layer once and attaches its inbound handler to the current receiver. Returns
     * null, and creates nothing, while carrier-path MLS is off: no trust anchor or no KDS, which is
     * a user build without the provider's verified anchors ({@link
     * com.android.messaging.rcs.e2ee.MlsCarrierTrust}). Nothing is enrolled or published then, and
     * the receiver drops inbound MLS bodies.
     */
    private synchronized com.android.messaging.rcs.e2ee.MlsCarrierTransport ensureMls() {
        if (mMls == null) {
            // The configuration's KDS, else, on a debug build only, the test network's.
            final String kds = com.android.messaging.rcs.e2ee.MlsCarrierTrust.kdsBaseUrl(
                    config.kdsUri, RcsDebug.isDebugBuild(),
                    com.android.messaging.rcs.e2ee.MlsTrustAnchors::compiledInKdsUrl);
            final List<byte[]> anchors = com.android.messaging.rcs.e2ee.MlsTrustAnchors.roots(
                    appContext, config.trustAnchorsUri, config.trustAnchorsGeneration,
                    config.trustAnchorsSigner);
            if (!com.android.messaging.rcs.e2ee.MlsCarrierTrust.enabled(anchors, kds)) {
                if (!mMlsOffLogged) {
                    mMlsOffLogged = true;
                    Log.w(TAG, "carrier-path MLS is off: anchors=" + anchors.size() + " kds="
                            + (kds != null) + " debugBuild=" + RcsDebug.isDebugBuild());
                }
                return null;
            }
            final com.android.messaging.rcs.e2ee.MlsCarrierTransport.Config cfg =
                    new com.android.messaging.rcs.e2ee.MlsCarrierTransport.Config() {
                        @Override public String selfE164(int subId) { return mlsTelOnly(
                                config.publicIdentity); }
                        @Override public String kdsBaseUrl() { return kds; }
                        @Override public String acsEncryptionIdentityProof() {
                            return config.acsEncryptionIdentityProof;
                        }
                        // The trust-anchor list, its generation and its signer, from the
                        // configuration.
                        @Override public String trustAnchorsUri() { return config.trustAnchorsUri; }
                        @Override public long trustAnchorsGeneration() {
                            return config.trustAnchorsGeneration;
                        }
                        @Override public String trustAnchorsSigner() {
                            return config.trustAnchorsSigner;
                        }
                        @Override public android.net.Network cellularNetwork() {
                            return acquireCellNetwork();
                        }
                    };
            mMls = new com.android.messaging.rcs.e2ee.MlsCarrierTransport(
                    appContext, new com.android.messaging.rcs.engine.mls.OpenMlsEngine(
                            // Clamps KeyPackage lifetime to the client certificate (RCC.16 §5.1).
                            com.android.messaging.rcs.engine.mls.OpenMlsEngine
                                    .KeyPackageLifetime.WITHIN_CERTIFICATE,
                            com.android.messaging.rcs.engine.mls.OpenMlsEngine
                                    .PeerCertificatePolicy.RCC16_STRICT),
                    this::sendMlsBody, cfg);
        }
        final CarrierMessageReceiver r = this.receiver;
        if (r != null) {
            final com.android.messaging.rcs.e2ee.MlsCarrierTransport t = mMls;
            r.setMlsInboundHandler((sender, ct, payload, envelopeMessageId) -> {
                final com.android.messaging.rcs.e2ee.E2eeConversationTransport.Inbound in =
                        t.onInboundCpim(sender, sender, ct, payload, envelopeMessageId);
                return in.plaintext;
            });
            // Publish a fresh KeyPackage pool off-thread.
            t.provisionAsync(android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId());
        }
        return mMls;
    }

    /** The cellular internet network, for the KDS; cached. */
    private android.net.Network acquireCellNetwork() {
        if (mCellNetwork != null) {
            return mCellNetwork;
        }
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final java.util.concurrent.atomic.AtomicReference<android.net.Network> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final android.net.ConnectivityManager.NetworkCallback cb =
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(final android.net.Network n) {
                            ref.set(n); latch.countDown();
                        }
                    };
            cm.requestNetwork(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    cb);
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            mCellNetwork = ref.get();
        } catch (final Throwable t) {
            Log.w(TAG, "acquireCellNetwork failed", t);
        }
        return mCellNetwork;
    }

    /** The bare {@code +E164} of a {@code tel:} or {@code sip:} identity. */
    private static String mlsTelOnly(String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        if (u.startsWith("tel:")) u = u.substring(4);
        else if (u.startsWith("sip:")) { u = u.substring(4); int at =
                u.indexOf('@'); if (at > 0) u = u.substring(0, at); }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi);
        return u.trim();
    }

    /** Queues an encoded CPIM body on the peer's session, sending the INVITE on first use. */
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
            // First message to this peer: invite; the queue drains once established.
            try {
                int localMsrpPort = registrar.getLocalPort() + 1;
                MsrpChatSession session = m.invite(toUri, localMsrpPort,
                        SdpOffer.SetupRole.ACTPASS,
                        /*localFingerprintAlg=*/ null,
                        /*localFingerprintHex=*/ null,
                        /*contributionId=*/ null);
                d.attachSession(session);
                Log.i(TAG, "MSRP session INVITE dispatched to " + LogMask.number(toUri)
                        + " state=" + session.getState());
            } catch (Exception e) {
                Log.w(TAG, "MSRP INVITE failed for " + LogMask.number(toUri), e);
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
     * One peer's session: messages queued until established, then its
     * {@link CarrierMsrpSessionSender} and {@link CarrierMsrpSessionReceiver}. As the session's
     * listener it drains or fails the queue and routes responses and REPORTs to the sender and
     * SENDs to the receiver.
     */
    private final class MsrpSessionDispatch implements MsrpChatSession.Listener {
        final String toUri;
        MsrpChatSession session;
        CarrierMsrpSessionSender msrpSender;
        CarrierMsrpSessionReceiver msrpReceiver;
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
            // The session may already be established.
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
                    break;
            }
        }

        @Override
        public void onMsrpMessage(MsrpMessage message) {
            if (message == null) return;
            try {
                int blen = message.getBody() == null ? 0 : message.getBody().length;
                Log.i(TAG, "inbound MSRP frame (orig-leg) kind=" + message.getKind()
                        + " method=" + message.getMethod()
                        + " msgId=" + message.getMessageId() + " bodyLen=" + blen);
            } catch (Throwable ignore) { /* logging only */ }
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
            // other methods are ignored
        }

        private void onEstablishedLocked() {
            if (msrpSender != null) return; // already drained
            CarrierMsrpSessionManager m = msrpManager;
            if (m == null) {
                failAll("MSRP manager went away pre-ESTABLISHED");
                return;
            }
            // Frames go out through the manager's connection; the bridge-wrapped listener receives
            // events.
            final MsrpChatSession capturedSession = session;
            CarrierMsrpSessionSender.FrameWriter writer =
                    new CarrierMsrpSessionSender.FrameWriter() {
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

        /** onEstablished fires once, so later messages on a held session drain here. */
        void drainIfEstablished() {
            if (msrpSender != null && session != null
                    && session.getState() == MsrpChatSession.State.ESTABLISHED) {
                drainQueue();
            }
        }

        private void onClosedLocked(MsrpChatSession.CloseReason reason, String detail) {
            String msg = (reason == null ? "session closed"
                    : reason.name()) + (detail == null ? "" : ": " + detail);
            if (msrpSender != null) {
                msrpSender.onSessionClosed(msg);
            }
            // Queued messages never reached the sender; fail them too.
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
                    // Route through the bytes path, as on the answering side.
                    onIncomingBytes(fromUri, body == null ? null
                            : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), messageId);
                }
                @Override
                public void onIncomingBytes(String fromUri, byte[] body,
                        String messageId) {
                    // A message/cpim body: text, a receipt, or a binary message/mls. Receipts
                    // become statuses, and a text is acknowledged over this session.
                    int len = body == null ? 0 : body.length;
                    Log.i(TAG, "MSRP-session inbound message (orig-leg) from "
                            + LogMask.number(fromUri) + " (" + len + " bytes)"
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
                                                + reportId + " -> " + LogMask.number(toUri)
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

    /**
     * Plain TCP, as the DR offer is {@code TCP/MSRP} without a fingerprint;
     * {@code debug.rcs.dr.msrptls} (debug builds) forces TLS with the platform defaults,
     * handshaking before return.
     */
    private static MsrpTlsConnection.SocketFactory defaultMsrpSocketFactory() {
        return new MsrpTlsConnection.SocketFactory() {
            @Override
            public Socket connect(String host, int port) throws IOException {
                if (RcsDebug.isDebugBuild()
                        && SystemProperties.getBoolean("debug.rcs.dr.msrptls", false)) {
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
        // Publish fresh KeyPackages on each registration.
        if (s == RegistrationState.REGISTERED && mMls != null) {
            mMls.replenishKeyPackagesAsync();
        }
    }

    /** See {@link CarrierTransportBridge#isConfigUsable}. */
    public static boolean isConfigUsable(RcsImsConfig c) {
        return CarrierTransportBridge.isConfigUsable(c);
    }
}
