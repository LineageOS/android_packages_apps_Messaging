/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import android.util.Log;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.carrier.RcsImsConfig;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;
import com.android.messaging.rcs.log.LogMask;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * The SIP side of DR MSRP chat sessions: INVITE with an SDP offer, the 200 and ACK, inbound
 * INVITE and BYE, and BYE on local close; sessions are tracked by Call-ID. Pairs with
 * {@link MsrpChatSession} for state and {@link MsrpTlsConnection} for the socket. SIP calls run on
 * the registrar's SIP thread; frames arrive on the connection's read thread and are passed on as
 * they are. {@code Session-Expires} is sent but never refreshed. See docs/rcs/carrier-transport.md.
 */
public final class CarrierMsrpSessionManager {

    private static final String TAG = "CarrierMsrpSession";

    /** The literal ICSI, as P-Preferred-Service carries it. */
    public static final String P_PREFERRED_SERVICE_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session";

    /** {@code require;explicit} (RFC 3841 §9) keeps the proxy from forking elsewhere. */
    public static final String ACCEPT_CONTACT_1_TO_1 =
            "*;+g.3gpp.icsi-ref=\"urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\""
                    + ";require;explicit";

    /** Same as the registrar's. */
    public static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /** RFC 4028, in seconds. */
    public static final int SESSION_EXPIRES_SEC = 1800;
    public static final int MIN_SE_SEC = 90;

    // From the registrar.
    private final SipProvider provider;
    private final MessageFactory messageFactory;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;

    private final RcsImsConfig config;
    private final String localIp;
    private final int localPort;
    private final String transportProto;
    /** The P-CSCF the registrar actually reached, which may be a PCO address. */
    private final String proxyHost;
    /** Injectable for tests. */
    private final MsrpTlsConnection.SocketFactory msrpSocketFactory;

    private final AtomicLong cseq = new AtomicLong(1);

    /** By Call-ID, which in-dialog requests carry, rather than JAIN-SIP's dialog cache. */
    private final Map<String, ActiveSession> sessions = new HashMap<>();

    public CarrierMsrpSessionManager(SipProvider provider,
            MessageFactory messageFactory, HeaderFactory headerFactory,
            AddressFactory addressFactory, RcsImsConfig config,
            String localIp, int localPort, String transportProto,
            String proxyHost, MsrpTlsConnection.SocketFactory msrpSocketFactory) {
        this.provider = provider;
        this.messageFactory = messageFactory;
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.config = config;
        this.localIp = localIp;
        this.localPort = localPort;
        this.transportProto = transportProto;
        this.proxyHost = (proxyHost != null && !proxyHost.isEmpty())
                ? proxyHost : config.pcscfAddress;
        this.msrpSocketFactory = msrpSocketFactory;
    }

    public MsrpChatSession getSession(String sipCallId) {
        ActiveSession a = sessions.get(sipCallId);
        return a == null ? null : a.session;
    }

    // Originating side.

    /**
     * Sends an INVITE with an SDP offer to {@code toUri} and returns the session in
     * {@link MsrpChatSession.State#INVITING}; the caller listens for established or closed.
     *
     * @param localMsrpListenPort advertised in the SDP; used only if we end up passive
     * @param localFingerprintAlg null for a cleartext {@code TCP/MSRP} offer
     * @param contributionId null to mint one
     */
    public MsrpChatSession invite(String toUri, int localMsrpListenPort,
            SdpOffer.SetupRole offerSetup,
            String localFingerprintAlg, String localFingerprintHex,
            String contributionId)
            throws ParseException, SipException {
        if (offerSetup == null) {
            throw new IllegalArgumentException("offerSetup");
        }
        String sessionId = MsrpTransactionId.next();
        String localMsrpUri = msrpUri(localIp, localMsrpListenPort, sessionId,
                localFingerprintAlg != null);
        String cid = contributionId == null ? UUID.randomUUID().toString() : contributionId;
        SdpOffer offer = SdpOffer.builder()
                .sessionId(sessionId)
                .sessionVersion(System.currentTimeMillis() / 1000L)
                .host(localIp)
                .port(localMsrpListenPort)
                .mediaProto(localFingerprintAlg != null
                        ? SdpOffer.MSRP_PROTO_TLS : SdpOffer.MSRP_PROTO_TCP)
                .msrpPath(localMsrpUri)
                .setup(offerSetup)
                .connectionNew(true)
                .msrpCema(true)
                .direction(SdpOffer.Direction.SENDRECV)
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .fingerprint(localFingerprintAlg, localFingerprintHex)
                .build();

        Request invite = buildInvite(toUri, cid, offer);
        CallIdHeader callIdHdr =
                (CallIdHeader) invite.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr.getCallId();

        MsrpChatSession session = new MsrpChatSession(callId);
        session.inviting();

        ActiveSession active = new ActiveSession();
        active.session = session;
        active.callId = callId;
        active.role = Role.ORIGINATING;
        active.contributionId = cid;
        active.localOffer = offer;
        active.toUri = toUri;
        sessions.put(callId, active);

        ClientTransaction tx = provider.getNewClientTransaction(invite);
        active.dialog = tx.getDialog();
        active.clientTx = tx;
        // Debuggable builds: log the full INVITE, SDP included.
        if (RcsDebug.isDebugBuild()) {
            Log.i(TAG, "INVITE wire >>>\n" + invite.toString() + "\n<<< INVITE wire");
        }
        tx.sendRequest();
        Log.i(TAG, "INVITE -> " + LogMask.number(toUri) + " call-id=" + callId
                + " contrib=" + cid);
        return session;
    }

    /**
     * Answers an inbound session INVITE: 200 with a plain {@code TCP/MSRP} SDP answer and, when we
     * are the active end (RFC 6135), connects to the offer's path and sends the bodiless first
     * SEND. Returns the established session, wired to {@code downstream} for inbound SENDs,
     * receipts and responses.
     */
    public MsrpChatSession onIncomingInvite(RequestEvent event, int localMsrpPort,
            Transport.Listener downstream)
            throws ParseException, SipException, IOException, SdpParseException {
        Request invite = event.getRequest();
        CallIdHeader callIdHdr = (CallIdHeader) invite.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr.getCallId();
        FromHeader fromHdr = (FromHeader) invite.getHeader(FromHeader.NAME);
        String fromUri = fromHdr != null ? fromHdr.getAddress().getURI().toString() : null;
        javax.sip.header.Header cidHdr = invite.getHeader("Contribution-ID");
        String cid = cidHdr != null ? cidHdr.toString().replaceFirst("(?i)Contribution-ID:\\s*", "")
                : UUID.randomUUID().toString();

        byte[] body = invite.getRawContent();
        if (body == null) {
            throw new SipException("terminating INVITE without SDP offer");
        }
        SdpOffer offer = SdpOffer.parse(body);

        // RFC 6135: an active offer makes us passive; passive or actpass makes us active.
        final boolean localActive = offer.getSetup() != SdpOffer.SetupRole.ACTIVE;
        final SdpOffer.SetupRole myRole = localActive
                ? SdpOffer.SetupRole.ACTIVE : SdpOffer.SetupRole.PASSIVE;

        String sessionId = MsrpTransactionId.next();
        String localMsrpUri = msrpUri(localIp, localMsrpPort, sessionId, false);
        SdpOffer answer = SdpOffer.builder()
                .sessionId(sessionId)
                .sessionVersion(System.currentTimeMillis() / 1000L)
                .host(localIp).port(localMsrpPort)
                .mediaProto(SdpOffer.MSRP_PROTO_TCP)
                .msrpPath(localMsrpUri)
                .setup(myRole)
                .connectionNew(true)
                .msrpCema(true)
                .direction(SdpOffer.Direction.SENDRECV)
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .build();

        // Debuggable builds: log the full SDP answer, as the INVITE above.
        if (RcsDebug.isDebugBuild()) {
            Log.i(TAG, "terminating 200-OK SDP ANSWER >>>\n"
                    + new String(answer.encode(), java.nio.charset.StandardCharsets.UTF_8)
                    + "<<< SDP ANSWER");
        }
        ServerTransaction st = event.getServerTransaction();
        if (st == null) {
            st = provider.getNewServerTransaction(invite);
        }
        Response ok = messageFactory.createResponse(Response.OK, invite);
        ToHeader toH = (ToHeader) ok.getHeader(ToHeader.NAME);
        if (toH != null && toH.getTag() == null) {
            toH.setTag("tag-" + System.nanoTime());
        }
        final String contact = "<sip:" + config.userName + "@" + localIp + ":" + localPort
                + ";transport=" + transportProto + ">"
                + ";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"";
        ok.addHeader(headerFactory.createHeader("Contact", contact));
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        ok.setContent(answer.encode(), ct);
        st.sendResponse(ok);

        MsrpChatSession session = new MsrpChatSession(callId);
        session.inviting();
        ActiveSession active = new ActiveSession();
        active.session = session;
        active.callId = callId;
        active.role = Role.TERMINATING;
        active.contributionId = cid;
        active.localOffer = answer;
        active.toUri = fromUri;
        active.dialog = st.getDialog();
        sessions.put(callId, active);

        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .sipCallId(callId)
                .contributionId(cid)
                .localMsrpUri(localMsrpUri)
                .remoteMsrpUri(offer.getMsrpPath())
                .acceptTypes(intersect(answer.getAcceptTypes(), offer.getAcceptTypes()))
                .localRole(myRole)
                .addrType(offer.getAddrType())
                .remoteHost(offer.getHost())
                .remotePort(offer.getPort())
                .build();
        Log.i(TAG, "terminating INVITE accepted call-id=" + callId + " from="
                + LogMask.number(fromUri)
                + " localRole=" + myRole + " remoteMsrp=" + offer.getHost() + ":" + offer.getPort()
                + " (200 OK sent)");

        // Wire delivery before connecting, so no relayed frame is lost: responses and REPORTs go to
        // the sender, SENDs to the receiver.
        final ActiveSession activeRef = active;
        final CarrierMsrpSessionSender.FrameWriter writer = frame -> send(active.session, frame);
        final CarrierMsrpSessionSender msrpSender =
                new CarrierMsrpSessionSender(session, writer, downstream);
        // Kept so a receipt can go back over this session.
        active.sender = msrpSender;
        final CarrierMsrpSessionReceiver msrpReceiver =
                new CarrierMsrpSessionReceiver(session, writer, downstream);
        session.setListener(new MsrpChatSession.Listener() {
            @Override public void onStateChanged(MsrpChatSession.State s,
                    MsrpChatSession.CloseReason reason, String detail) { }
            @Override public void onMsrpMessage(MsrpMessage message) {
                if (message == null) return;
                try {
                    int blen = message.getBody() == null ? 0 : message.getBody().length;
                    Log.i(TAG, "inbound MSRP frame kind=" + message.getKind()
                            + " method=" + message.getMethod()
                            + " msgId=" + message.getMessageId()
                            + " bodyLen=" + blen
                            + " byteRange=" + message.getByteRange()
                            + " endFlag=" + message.getEndFlag());
                } catch (Throwable ignore) { /* logging only */ }
                if (message.getKind() == MsrpMessage.Kind.RESPONSE) {
                    msrpSender.onResponse(message);
                } else if (message.getMethod() == MsrpMethod.REPORT) {
                    msrpSender.onReport(message);
                } else if (message.getMethod() == MsrpMethod.SEND) {
                    msrpReceiver.onSendChunk(message);
                }
            }
        });

        if (myRole == SdpOffer.SetupRole.ACTIVE) {
            MsrpTlsConnection conn = new MsrpTlsConnection(
                    msrpSocketFactory, new ConnListener(active),
                    info.getRemoteHost(), info.getRemotePort(), null, null);
            active.connection = conn;
            conn.start();
            Log.i(TAG, "terminating MSRP TCP connected -> "
                    + info.getRemoteHost() + ":" + info.getRemotePort());
            // RFC 4975 §4.2.2: the active end binds the session with the first frame; the recipient
            // has nothing to send yet, so it sends a bodiless SEND, without which the relay drops
            // the media.
            try {
                MsrpMessage bind = MsrpMessage.newSend()
                        .transactionId(MsrpTransactionId.next())
                        .toPath(info.getRemoteMsrpUri())
                        .fromPath(info.getLocalMsrpUri())
                        .messageId(UUID.randomUUID().toString())
                        .endFlag(com.android.messaging.rcs.carrier.msrp.MsrpEndFlag.COMPLETE)
                        .build();
                active.connection.send(bind);
                Log.i(TAG, "terminating: sent bodiless MSRP bind-SEND (bind B-leg)");
            } catch (Exception e) {
                Log.w(TAG, "terminating bind-SEND failed", e);
            }
        }
        session.established(info);
        Log.i(TAG, "terminating session ESTABLISHED call-id=" + callId);
        return session;
    }

    // Driven by CarrierRcsTransport's request and response routing.

    /** A response to one of our INVITEs. */
    public void onInviteResponse(ResponseEvent event) {
        Response resp = event.getResponse();
        int status = resp.getStatusCode();
        CallIdHeader callIdHdr =
                (CallIdHeader) resp.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr == null ? null : callIdHdr.getCallId();
        ActiveSession active = callId == null ? null : sessions.get(callId);
        if (active == null) return;

        if (status >= 100 && status < 200) {
            // provisional
            return;
        }
        if (status >= 200 && status < 300) {
            try {
                handleInvite2xx(active, event);
            } catch (Exception e) {
                Log.e(TAG, "INVITE 2xx handling failed", e);
                active.session.closeError(
                        MsrpChatSession.CloseReason.TLS_ERROR,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                sessions.remove(callId);
            }
        } else {
            Log.w(TAG, "INVITE got " + status + " " + resp.getReasonPhrase()
                    + " call-id=" + callId);
            active.session.inviteRejected(status + " " + resp.getReasonPhrase());
            sessions.remove(callId);
        }
    }

    private void handleInvite2xx(ActiveSession active, ResponseEvent event)
            throws ParseException, SipException, SdpParseException, IOException {
        Response resp = event.getResponse();
        byte[] body = resp.getRawContent();
        if (body == null) {
            throw new SipException("INVITE 200 without SDP body");
        }
        SdpOffer answer = SdpOffer.parse(body);
        if (!answer.isMsrpTls() && active.localOffer.isMsrpTls()) {
            throw new SipException("answer downgraded TLS->TCP (refused)");
        }
        SdpOffer.SetupRole peerSetup = answer.getSetup();
        SdpOffer.SetupRole localRole = derivedActiveRole(
                active.localOffer.getSetup(), peerSetup);

        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .sipCallId(active.callId)
                .contributionId(active.contributionId)
                .localMsrpUri(active.localOffer.getMsrpPath())
                .remoteMsrpUri(answer.getMsrpPath())
                .localFingerprint(active.localOffer.getFingerprintAlg(),
                        active.localOffer.getFingerprintHex())
                .remoteFingerprint(answer.getFingerprintAlg(),
                        answer.getFingerprintHex())
                .acceptTypes(intersect(
                        active.localOffer.getAcceptTypes(), answer.getAcceptTypes()))
                .acceptWrappedTypes(intersect(
                        active.localOffer.getAcceptWrappedTypes(),
                        answer.getAcceptWrappedTypes()))
                .localRole(localRole)
                .addrType(answer.getAddrType())
                .remoteHost(answer.getHost())
                .remotePort(answer.getPort())
                .build();

        // ACK before any MSRP traffic.
        Dialog d = active.dialog;
        if (d == null && event.getClientTransaction() != null) {
            d = event.getClientTransaction().getDialog();
            active.dialog = d;
        }
        if (d != null) {
            CSeqHeader cs = (CSeqHeader) resp.getHeader(CSeqHeader.NAME);
            Request ack = d.createAck(cs.getSeqNumber());
            d.sendAck(ack);
        }

        Log.i(TAG, "INVITE 2xx: localRole=" + localRole + " peerSetup=" + peerSetup
                + " remoteMsrp=" + info.getRemoteHost() + ":" + info.getRemotePort()
                + " (ACK sent)");
        if (localRole == SdpOffer.SetupRole.ACTIVE) {
            MsrpTlsConnection conn = new MsrpTlsConnection(
                    msrpSocketFactory,
                    new ConnListener(active),
                    info.getRemoteHost(), info.getRemotePort(),
                    info.getRemoteFingerprintAlg(),
                    info.getRemoteFingerprintHex());
            active.connection = conn;
            try {
                conn.start();
                Log.i(TAG, "INVITE 2xx: MSRP TCP connected -> "
                        + info.getRemoteHost() + ":" + info.getRemotePort());
                // The active end binds the session with a bodiless first SEND before any data.
                MsrpMessage bind = MsrpMessage.newSend()
                        .transactionId(MsrpTransactionId.next())
                        .toPath(info.getRemoteMsrpUri())
                        .fromPath(info.getLocalMsrpUri())
                        .messageId(UUID.randomUUID().toString())
                        .endFlag(com.android.messaging.rcs.carrier.msrp.MsrpEndFlag.COMPLETE)
                        .build();
                conn.send(bind);
                Log.i(TAG, "INVITE 2xx: sent bodiless MSRP bind-SEND (bind A-leg)");
            } catch (IOException ioe) {
                throw new IOException("MSRP TLS connect: " + ioe.getMessage(), ioe);
            }
        } else {
            Log.w(TAG, "INVITE 2xx: localRole=" + localRole + " (PASSIVE) — waiting for"
                    + " inbound MSRP accept, but no per-session accept loop is wired +"
                    + " our a=path advertises the SIP port. Session will stall.");
        }
        // A passive session has no accept loop; see onMsrpInboundAccepted.
        active.session.established(info);
        Log.i(TAG, "INVITE 2xx: session.established() -> ESTABLISHED; draining queued body");
    }

    /** Binds a socket accepted on our advertised port to its session, for the passive role. */
    public void onMsrpInboundAccepted(String callId, java.net.Socket socket)
            throws IOException {
        ActiveSession active = sessions.get(callId);
        if (active == null) {
            socket.close();
            throw new IOException("no active session for call-id=" + callId);
        }
        MsrpSessionInfo info = active.session.getSessionInfo();
        if (info == null) {
            socket.close();
            throw new IOException("session not yet ESTABLISHED for call-id=" + callId);
        }
        MsrpTlsConnection conn = new MsrpTlsConnection(
                new AdoptingFactory(),
                new ConnListener(active),
                info.getRemoteHost(), info.getRemotePort(),
                info.getRemoteFingerprintAlg(),
                info.getRemoteFingerprintHex());
        active.connection = conn;
        conn.adopt(socket);
    }

    /** A peer BYE: answered 200, then the session is closed. */
    public void onBye(RequestEvent event) {
        javax.sip.message.Request byeReq = event.getRequest();
        CallIdHeader callIdHdr =
                (CallIdHeader) byeReq.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr == null ? null : callIdHdr.getCallId();
        // Log who tore the session down: top Via and any Reason header.
        try {
            javax.sip.header.Header reason = byeReq.getHeader("Reason");
            javax.sip.header.ViaHeader topVia =
                    (javax.sip.header.ViaHeader) byeReq.getHeader(
                            javax.sip.header.ViaHeader.NAME);
            Log.w(TAG, "onBye DIAG call-id=" + callId
                    + " topVia=" + (topVia == null ? "(none)" : topVia.toString())
                    + " reason=" + (reason == null ? "(none)" : reason.toString()));
        } catch (Throwable ignore) { /* logging only */ }
        if (RcsDebug.isDebugBuild()) {
            // The raw request carries the parties' addresses.
            Log.w(TAG, "onBye RAW BYE >>>\n" + byeReq + "<<< onBye RAW BYE");
        }
        ActiveSession active = callId == null ? null : sessions.get(callId);
        if (active == null) {
            Log.w(TAG, "BYE for unknown session call-id=" + callId);
            return;
        }
        try {
            ServerTransaction st = event.getServerTransaction();
            if (st == null) {
                st = provider.getNewServerTransaction(event.getRequest());
            }
            Response ok = messageFactory.createResponse(Response.OK, event.getRequest());
            st.sendResponse(ok);
        } catch (Exception e) {
            Log.w(TAG, "failed to 200 the BYE", e);
        }
        active.session.closeRemote("peer BYE");
        teardown(active, MsrpChatSession.CloseReason.REMOTE_BYE, "peer BYE");
    }

    /** Sends BYE and closes the socket; the session ends closed. */
    public void closeLocal(MsrpChatSession session, String reason) {
        if (session == null) return;
        ActiveSession active = sessions.get(session.getSessionTag());
        if (active == null) {
            // Not tracked, perhaps a stale reference.
            session.closeLocal(reason);
            session.closed();
            return;
        }
        try {
            if (active.dialog != null) {
                Request bye = active.dialog.createRequest(Request.BYE);
                ClientTransaction tx = provider.getNewClientTransaction(bye);
                active.dialog.sendRequest(tx);
            }
        } catch (Exception e) {
            Log.w(TAG, "BYE send failed", e);
        }
        session.closeLocal(reason);
        teardown(active, MsrpChatSession.CloseReason.LOCAL_BYE, reason);
    }

    private void teardown(ActiveSession active,
            MsrpChatSession.CloseReason reason, String detail) {
        if (active.connection != null) {
            active.connection.close(reason, detail);
        }
        active.session.closed();
        sessions.remove(active.callId);
    }

    /** Throws if the session has no connection. */
    public void send(MsrpChatSession session, MsrpMessage frame) throws IOException {
        if (session == null) throw new IllegalArgumentException("session");
        ActiveSession active = sessions.get(session.getSessionTag());
        if (active == null || active.connection == null) {
            throw new IOException("session not connected");
        }
        active.connection.send(frame);
    }

    /**
     * Sends a built {@code message/cpim} body, such as a receipt, over the session through its
     * {@link CarrierMsrpSessionSender}, so a receipt uses the transport of the message it answers
     * (RFC 5438, GSMA RCC.07). False if the session has no sender.
     */
    public boolean sendCpimOverSession(MsrpChatSession session, byte[] cpimBytes,
            String messageId) {
        if (session == null) return false;
        ActiveSession active = sessions.get(session.getSessionTag());
        if (active == null || active.sender == null) {
            Log.w(TAG, "sendCpimOverSession: no sender for session "
                    + (session.getSessionTag()));
            return false;
        }
        return active.sender.send(messageId, "message/cpim", cpimBytes,
                /*wantsSuccessReport=*/ false);
    }

    Request buildInvite(String toUri, String contributionId, SdpOffer offer)
            throws ParseException, SipException {
        // A tel: target becomes sip:<number>@<home domain>: the dialog's remote target is echoed
        // into the ACK, and peers reject a tel: dialog target.
        final String sipTarget = telToSip(toUri);
        URI requestUri = addressFactory.createURI(sipTarget);
        Address fromAddress = addressFactory.createAddress(
                addressFactory.createURI(config.publicIdentity));
        Address toAddress = addressFactory.createAddress(requestUri);

        FromHeader from = headerFactory.createFromHeader(
                fromAddress, "tag-" + System.nanoTime());
        ToHeader to = headerFactory.createToHeader(toAddress, null);

        ArrayList<ViaHeader> vias = new ArrayList<>();
        ViaHeader via = headerFactory.createViaHeader(
                localIp, localPort, transportProto, null);
        via.setRPort();
        vias.add(via);

        CSeqHeader cs = headerFactory.createCSeqHeader(
                cseq.getAndIncrement(), Request.INVITE);
        MaxForwardsHeader mf = headerFactory.createMaxForwardsHeader(70);

        SipURI proxyUri = addressFactory.createSipURI(null, proxyHost);
        boolean tls = "tls".equals(transportProto);
        int pcscfPort = config.pcscfPort > 0
                ? config.pcscfPort : (tls ? 5061 : 5060);
        proxyUri.setPort(pcscfPort);
        proxyUri.setTransportParam(transportProto);
        proxyUri.setLrParam();
        RouteHeader route = headerFactory.createRouteHeader(
                addressFactory.createAddress(proxyUri));

        Request req = messageFactory.createRequest(requestUri, Request.INVITE,
                provider.getNewCallId(), cs, from, to, vias, mf);
        req.addHeader(route);

        if (config.publicIdentity != null) {
            req.addHeader(headerFactory.createHeader(
                    "P-Preferred-Identity", "<" + config.publicIdentity + ">"));
        }
        req.addHeader(headerFactory.createHeader(
                "P-Preferred-Service", P_PREFERRED_SERVICE_CPM_SESSION));
        req.addHeader(headerFactory.createHeader(
                "Accept-Contact", ACCEPT_CONTACT_1_TO_1));
        req.addHeader(headerFactory.createHeader(
                "Contribution-ID", contributionId));
        // RFC 4028 timers. JAIN-SIP allows one option tag per Supported header.
        for (final String optionTag : new String[] {"timer", "100rel", "gruu"}) {
            req.addHeader(headerFactory.createSupportedHeader(optionTag));
        }
        req.addHeader(headerFactory.createHeader(
                "Session-Expires", SESSION_EXPIRES_SEC + ";refresher=uac"));
        req.addHeader(headerFactory.createHeader(
                "Min-SE", Integer.toString(MIN_SE_SEC)));
        req.addHeader(headerFactory.createHeader("Accept", "application/sdp"));
        UserAgentHeader ua = headerFactory.createUserAgentHeader(
                java.util.Collections.singletonList(USER_AGENT));
        req.addHeader(ua);

        // Contact, required on a dialog-forming INVITE, raw like the registrar's and tagged with
        // the session ICSI.
        final String contact = "<sip:" + config.userName + "@" + localIp + ":"
                + localPort + ";transport=" + transportProto + ">"
                + ";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"";
        req.addHeader(headerFactory.createHeader("Contact", contact));

        ContentTypeHeader ct = headerFactory.createContentTypeHeader(
                "application", "sdp");
        req.setContent(offer.encode(), ct);
        return req;
    }

    /** {@code tel:<number>} to {@code sip:<number>@<home domain>}; anything else unchanged. */
    private String telToSip(final String toUri) {
        if (toUri == null || !toUri.startsWith("tel:")) {
            return toUri;
        }
        final String num = toUri.substring(4);
        final String domain = (config.domain != null && !config.domain.isEmpty())
                ? config.domain : "ims.mnc001.mcc001.3gppnetwork.org";
        return "sip:" + num + "@" + domain;
    }

    private static String msrpUri(String host, int port, String sessionId,
            boolean tls) {
        return (tls ? "msrps://" : "msrp://")
                + host + ":" + port + "/" + sessionId + ";tcp";
    }

    /** Our connection role from our offered role and the peer's answer (RFC 6135 §5.2). */
    static SdpOffer.SetupRole derivedActiveRole(SdpOffer.SetupRole localOfferSetup,
            SdpOffer.SetupRole peerSetup) {
        if (localOfferSetup == SdpOffer.SetupRole.ACTPASS) {
            return SdpAnswer.complementarySetup(peerSetup);
        }
        return localOfferSetup == null ? SdpOffer.SetupRole.ACTIVE : localOfferSetup;
    }

    private static List<String> intersect(List<String> a, List<String> b) {
        return SdpAnswer.intersect(a, b);
    }

    private enum Role { ORIGINATING, TERMINATING }

    private static final class ActiveSession {
        MsrpChatSession session;
        String callId;
        String contributionId;
        Role role;
        SdpOffer localOffer;
        Dialog dialog;
        ClientTransaction clientTx;
        MsrpTlsConnection connection;
        String toUri;
        // For receipts sent back over this session.
        CarrierMsrpSessionSender sender;
    }

    /** Passes frames to the session and closes it, and forgets it, when the socket closes. */
    private final class ConnListener implements MsrpTlsConnection.Listener {
        private final ActiveSession active;

        ConnListener(ActiveSession a) { this.active = a; }

        @Override
        public void onFrame(MsrpMessage frame) {
            active.session.onMsrpFrame(frame);
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            if (active.session.getState() == MsrpChatSession.State.ESTABLISHED
                    || active.session.getState() == MsrpChatSession.State.INVITING) {
                if (reason == MsrpChatSession.CloseReason.REMOTE_BYE) {
                    active.session.closeRemote(detail);
                } else if (reason == MsrpChatSession.CloseReason.LOCAL_BYE) {
                    active.session.closeLocal(detail);
                } else {
                    active.session.closeError(reason, detail);
                }
            }
            active.session.closed();
            sessions.remove(active.callId);
        }
    }

    /** For an accepted socket passed to {@link MsrpTlsConnection#adopt}; never connects. */
    private static final class AdoptingFactory implements MsrpTlsConnection.SocketFactory {
        @Override
        public java.net.Socket connect(String host, int port) throws IOException {
            throw new IOException("AdoptingFactory: this connection is passive; use adopt()");
        }
    }
}
