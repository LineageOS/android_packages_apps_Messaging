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
package com.android.messaging.rcs.carrier.msrp.session;

import android.util.Log;

import com.android.messaging.rcs.carrier.RcsImsConfig;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;
import com.android.messaging.rcs.carrier.msrp.MsrpTransactionId;

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
 * SIP plane for MSRP chat sessions: builds INVITE requests with SDP offers,
 * parses 200 OK responses with SDP answers, sends ACK, handles inbound BYE.
 * Companion to {@link MsrpChatSession} (state machine, pure Java) and
 * {@link MsrpTlsConnection} (TCP/TLS socket).
 *
 * <p>This class is the "carrier-RCS" analog of the OTT path's
 * {@code com.android.messaging.rcs.carrier.sip.CarrierMessageSender}, but
 * for INVITE / BYE instead of MESSAGE. It owns the SIP dialog for an
 * active MSRP chat session.
 *
 * <p>Out of scope (tracked separately):
 * <ul>
 *   <li>Group chat INVITE with conference URIs (P3).</li>
 *   <li>MSRP-FT (file transfer) m=image lines.</li>
 *   <li>Session refresh / Session-Expires (per RFC 4028) — we set
 *       {@code Session-Expires: 1800} on the INVITE but do NOT yet handle
 *       in-dialog UPDATE/refresher; filed as a P3 follow-up.</li>
 *   <li>Re-INVITE on network change.</li>
 *   <li>Multi-conversation tracking (we hold one session at a time today).</li>
 * </ul>
 *
 * <p>Threading: all SIP API calls happen on the registrar's sipThread (the
 * caller drives us from there). State machine transitions are synchronous
 * within each event handler. The {@link MsrpTlsConnection} read thread fires
 * MSRP frame callbacks on its own thread; we forward to the session's
 * listener verbatim — downstream code (the transport bridge) is expected
 * to dispatch back to its own thread if needed.
 */
public final class CarrierMsrpSessionManager {

    private static final String TAG = "CarrierMsrpSession";

    /** UP 2.4 §A.1.4 P-Preferred-Service for 1:1 CPM chat session. */
    public static final String P_PREFERRED_SERVICE_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session";

    /** UP 2.4 §A.1.4 Accept-Contact for 1:1 CPM chat session.
     *  RFC 3841 §9 ;require;explicit tells the proxy not to fork. */
    public static final String ACCEPT_CONTACT_1_TO_1 =
            "*;+g.3gpp.icsi-ref=\"urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\""
                    + ";require;explicit";

    /** UA banner. Mirrors {@code CarrierSipRegistrar}. */
    public static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /** RFC 4028 default session-expires (seconds). Google Messages uses 1800. */
    public static final int SESSION_EXPIRES_SEC = 1800;
    public static final int MIN_SE_SEC = 90;

    // JAIN-SIP factories (immutable; provided by the registrar).
    private final SipProvider provider;
    private final MessageFactory messageFactory;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;

    private final RcsImsConfig config;
    private final String localIp;
    private final int localPort;
    private final String transportProto;
    /** Effective P-CSCF host for the INVITE Route — the PCO-signalled address the
     *  registrar reached, which may differ from {@code config.pcscfAddress} (ACS
     *  FQDN the UE can't DNS-resolve). */
    private final String proxyHost;
    /** Strategy used to mint MSRP TLS connections; injectable for tests. */
    private final MsrpTlsConnection.SocketFactory msrpSocketFactory;

    private final AtomicLong cseq = new AtomicLong(1);

    /** Active sessions keyed by SIP Call-ID. We index by Call-ID rather than
     *  Dialog because in-dialog requests (BYE) carry the Call-ID in their
     *  header and we'd rather not depend on JAIN-SIP's Dialog cache for
     *  routing. */
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

    /** Number of currently-tracked sessions (for diagnostics / tests). */
    public int sessionCount() { return sessions.size(); }

    /** Look up the session associated with a SIP Call-ID. */
    public MsrpChatSession getSession(String sipCallId) {
        ActiveSession a = sessions.get(sipCallId);
        return a == null ? null : a.session;
    }

    // ---------- originating side ----------

    /**
     * Send an INVITE with an SDP offer for an MSRP chat session targeting
     * {@code toUri}. The returned {@link MsrpChatSession} is in state
     * {@link MsrpChatSession.State#INVITING}; the caller installs a
     * {@link MsrpChatSession.Listener} on it and waits for transition to
     * ESTABLISHED (200 OK + MSRP TLS up) or CLOSED (rejection).
     *
     * @param toUri recipient (e.g. {@code tel:+15551234567})
     * @param localMsrpListenPort the locally-bound MSRP listen port (we
     *     advertise this in the SDP and the peer will TCP-connect here when
     *     our offer asserts {@code a=setup:passive}; if we offer
     *     {@code a=setup:active} we connect outbound to the peer's port).
     * @param localFingerprintAlg local cert hash algorithm (SHA-256) — may
     *     be null to disable {@code a=fingerprint:} (cleartext MSRP).
     * @param localFingerprintHex local cert fingerprint hex form
     * @param contributionId per-conversation Contribution-ID (UUID); pass
     *     null to mint a fresh one
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
        // Log the full INVITE (incl. SDP offer) so the AS-side co-dev can diff the
        // exact bytes it parsed (debug builds only; the SDP is otherwise opaque).
        if ("eng".equals(android.os.Build.TYPE) || "userdebug".equals(android.os.Build.TYPE)) {
            Log.i(TAG, "INVITE wire >>>\n" + invite.toString() + "\n<<< INVITE wire");
        }
        tx.sendRequest();
        Log.i(TAG, "INVITE -> " + toUri + " call-id=" + callId
                + " contrib=" + cid);
        return session;
    }

    /**
     * TERMINATING side: accept an inbound MSRP-session INVITE the CPM AS forked to
     * us. Parses the SDP offer, answers 200 OK with our SDP answer, and (when we're
     * the active endpoint per RFC 6135) opens the MSRP TCP to the offer's a=path.
     * Returns the {@link MsrpChatSession} (ESTABLISHED) so the caller installs a
     * {@link MsrpChatSession.Listener} for inbound SEND delivery. Plain TCP MSRP.
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

        // RFC 6135 role: offer active -> we passive; offer passive/actpass -> we active.
        final boolean localActive = offer.getSetup() != SdpOffer.SetupRole.ACTIVE;
        final SdpOffer.SetupRole myRole = localActive
                ? SdpOffer.SetupRole.ACTIVE : SdpOffer.SetupRole.PASSIVE;

        // Our SDP answer (plain TCP MSRP).
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

        // DIAG (open5gs cause=406 "SDP offer/answer incomplete"): dump the exact SDP answer
        // B puts in its 200 OK so we can
        // verify it's well-formed (m=message MSRP/TCP, a=path, c=, a=setup,
        // a=accept-types) vs. the AS mis-tracking O/A completion on flush.
        Log.i(TAG, "terminating 200-OK SDP ANSWER >>>\n"
                + new String(answer.encode(), java.nio.charset.StandardCharsets.UTF_8)
                + "<<< SDP ANSWER");
        // 200 OK with the answer SDP.
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
        Log.i(TAG, "terminating INVITE accepted call-id=" + callId + " from=" + fromUri
                + " localRole=" + myRole + " remoteMsrp=" + offer.getHost() + ":" + offer.getPort()
                + " (200 OK sent)");

        // Wire inbound-SEND delivery BEFORE the media connects (so no relayed frame
        // is lost): a FrameWriter funnels MSRP responses back through the connection,
        // a receiver reassembles inbound SENDs -> downstream.onIncomingMessage, and a
        // session listener routes frames (RESPONSE/REPORT -> sender, SEND -> receiver).
        final ActiveSession activeRef = active;
        final CarrierMsrpSessionSender.FrameWriter writer = frame -> send(active.session, frame);
        final CarrierMsrpSessionSender msrpSender =
                new CarrierMsrpSessionSender(session, writer, downstream);
        // Remember the sender so an in-session IMDN can ride back over this leg.
        active.sender = msrpSender;
        final CarrierMsrpSessionReceiver msrpReceiver =
                new CarrierMsrpSessionReceiver(session, writer, downstream);
        session.setListener(new MsrpChatSession.Listener() {
            @Override public void onStateChanged(MsrpChatSession.State s,
                    MsrpChatSession.CloseReason reason, String detail) { }
            @Override public void onMsrpMessage(MsrpMessage message) {
                if (message == null) return;
                // DIAG: log every inbound MSRP frame so we can see
                // the AS's flushed data SEND actually land on this leg's socket
                // (the last hop: A -> AS buffer -> flush -> B). onSendChunk /
                // downstream.onIncomingMessage are otherwise silent.
                try {
                    int blen = message.getBody() == null ? 0 : message.getBody().length;
                    Log.i(TAG, "inbound MSRP frame kind=" + message.getKind()
                            + " method=" + message.getMethod()
                            + " msgId=" + message.getMessageId()
                            + " bodyLen=" + blen
                            + " byteRange=" + message.getByteRange()
                            + " endFlag=" + message.getEndFlag());
                } catch (Throwable ignore) { /* best-effort diag */ }
                if (message.getKind() == MsrpMessage.Kind.RESPONSE) {
                    msrpSender.onResponse(message);
                } else if (message.getMethod() == MsrpMethod.REPORT) {
                    msrpSender.onReport(message);
                } else if (message.getMethod() == MsrpMethod.SEND) {
                    msrpReceiver.onSendChunk(message);
                }
            }
        });

        // We're active -> open the MSRP TCP to the AS's advertised endpoint.
        if (myRole == SdpOffer.SetupRole.ACTIVE) {
            MsrpTlsConnection conn = new MsrpTlsConnection(
                    msrpSocketFactory, new ConnListener(active),
                    info.getRemoteHost(), info.getRemotePort(), null, null);
            active.connection = conn;
            conn.start();
            Log.i(TAG, "terminating MSRP TCP connected -> "
                    + info.getRemoteHost() + ":" + info.getRemotePort());
            // RFC 4975 §4.2.2: the ACTIVE endpoint binds the MSRP session by sending
            // the first frame. B is the recipient (nothing to send yet), so emit a
            // BODILESS bind-SEND — otherwise the AS's relay never binds this TCP to
            // the B-leg's MSRP session and times out the media (15s -> BYE).
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

    // ---------- handlers — driven by CarrierRcsTransport's request/response routing ----------

    /** Called for an inbound 200 OK to one of our INVITEs. */
    public void onInviteResponse(ResponseEvent event) {
        Response resp = event.getResponse();
        int status = resp.getStatusCode();
        CallIdHeader callIdHdr =
                (CallIdHeader) resp.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr == null ? null : callIdHdr.getCallId();
        ActiveSession active = callId == null ? null : sessions.get(callId);
        if (active == null) return;

        if (status >= 100 && status < 200) {
            // Provisional (100 Trying, 180 Ringing, 183 Session Progress) — no action.
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
        // Determine our local role for the TCP/TLS connection.
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

        // ACK the 200 first; the peer expects ACK before MSRP traffic.
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
        // Spin up MSRP socket if we're active.
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
                // The active endpoint must BIND the MSRP session with a first frame
                // before data flows — the AS's msrplib establishes the session context
                // on this bind, then correlates the data SEND (open5gs: it reads the
                // data SEND off the socket but won't surface it without the prior bind,
                // same as B's leg). Emit a bodiless bind-SEND ahead of the drain.
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
        // Passive side waits for the connection in onMsrpInboundAccepted.
        active.session.established(info);
        Log.i(TAG, "INVITE 2xx: session.established() -> ESTABLISHED; draining queued body");
    }

    /**
     * Inbound MSRP socket from the peer (passive role). The caller — typically
     * a per-session accept loop owned by the transport — invokes this when
     * it accepts a socket on our advertised port and wants to bind it to
     * the right active session.
     */
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

    /** Inbound BYE on an active session's dialog. */
    public void onBye(RequestEvent event) {
        javax.sip.message.Request byeReq = event.getRequest();
        CallIdHeader callIdHdr =
                (CallIdHeader) byeReq.getHeader(CallIdHeader.NAME);
        String callId = callIdHdr == null ? null : callIdHdr.getCallId();
        // DIAG (open5gs SIPSessionDidFail): dump the full
        // BYE so open5gs can see who tore down B's terminating leg — the top Via
        // (sent-by host:port), From, and any Reason header identify the sender
        // (AS 172.22.0.52 vs P-CSCF 172.22.0.21), correlated with their logs.
        try {
            javax.sip.header.Header reason = byeReq.getHeader("Reason");
            javax.sip.header.ViaHeader topVia =
                    (javax.sip.header.ViaHeader) byeReq.getHeader(
                            javax.sip.header.ViaHeader.NAME);
            Log.w(TAG, "onBye DIAG call-id=" + callId
                    + " topVia=" + (topVia == null ? "(none)" : topVia.toString())
                    + " reason=" + (reason == null ? "(none)" : reason.toString()));
        } catch (Throwable ignore) { /* best-effort diag */ }
        Log.w(TAG, "onBye RAW BYE >>>\n" + byeReq + "<<< onBye RAW BYE");
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

    /**
     * Local-initiated tear-down: send BYE and close the MSRP socket. After
     * this returns the session is in {@link MsrpChatSession.State#CLOSED}
     * (or CLOSING with an outstanding BYE — caller can ignore the response).
     */
    public void closeLocal(MsrpChatSession session, String reason) {
        if (session == null) return;
        ActiveSession active = sessions.get(session.getSessionTag());
        if (active == null) {
            // The session is unknown to the manager — caller may have a stale ref.
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

    /** Send a {@link MsrpMessage} over the session's connection. Caller error
     *  if not ESTABLISHED. */
    public void send(MsrpChatSession session, MsrpMessage frame) throws IOException {
        if (session == null) throw new IllegalArgumentException("session");
        ActiveSession active = sessions.get(session.getSessionTag());
        if (active == null || active.connection == null) {
            throw new IOException("session not connected");
        }
        active.connection.send(frame);
    }

    /**
     * Send a pre-built {@code message/cpim} payload (e.g. a
     * {@code message/imdn+xml} disposition notification) over an established
     * session as an MSRP SEND. Reuses the session's {@link
     * CarrierMsrpSessionSender} so chunking / REPORT correlation are handled.
     * Used to route in-session IMDNs back over the leg the original message
     * arrived on (RFC 5438 / RCC.07). Returns false if the session has no
     * sender or isn't ESTABLISHED.
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

    // ---------- INVITE builder ----------

    Request buildInvite(String toUri, String contributionId, SdpOffer offer)
            throws ParseException, SipException {
        // Session INVITEs use a SIP request-target, not a bare tel: — the dialog's
        // remote target (To) is echoed into the in-dialog ACK, and a tel: To makes
        // the terminating IMS's PJSIP throw 'Not a valid SIP URI' (it can't parse
        // tel: as a dialog target) and kill the session. Convert tel:<msisdn> ->
        // sip:<msisdn>@<home-domain> so the whole dialog (INVITE/200/ACK) is sip:.
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
        // RFC 4028 timers — Google Messages emits these on INVITE.
        // JAIN-SIP's SupportedHeader is a singleton (one option-tag each), so add a
        // separate Supported header per tag rather than a comma-joined value
        // (which throws ParseException "Only singleton allowed").
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

        // Contact — REQUIRED on a dialog-forming INVITE (the peer/AS routes in-dialog
        // requests here). Raw-string form like the registrar's, tagged with the CPM
        // session ICSI so the AS fans out to session-capable contacts.
        final String contact = "<sip:" + config.userName + "@" + localIp + ":"
                + localPort + ";transport=" + transportProto + ">"
                + ";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"";
        req.addHeader(headerFactory.createHeader("Contact", contact));

        ContentTypeHeader ct = headerFactory.createContentTypeHeader(
                "application", "sdp");
        req.setContent(offer.encode(), ct);
        return req;
    }

    /** Convert a {@code tel:<msisdn>} recipient to {@code sip:<msisdn>@<home-domain>}
     *  for a session request-target/To (see {@link #buildInvite}). Passes through
     *  anything that isn't a bare tel:. */
    private String telToSip(final String toUri) {
        if (toUri == null || !toUri.startsWith("tel:")) {
            return toUri;
        }
        final String num = toUri.substring(4);
        final String domain = (config.domain != null && !config.domain.isEmpty())
                ? config.domain : "ims.mnc001.mcc001.3gppnetwork.org";
        return "sip:" + num + "@" + domain;
    }

    /** Build the MSRP URI advertised in SDP. */
    private static String msrpUri(String host, int port, String sessionId,
            boolean tls) {
        return (tls ? "msrps://" : "msrp://")
                + host + ":" + port + "/" + sessionId + ";tcp";
    }

    /**
     * Translate the local-emitted setup role and the peer's setup role into
     * our local role for the underlying TCP/TLS connection per RFC 6135 §5.2.
     */
    static SdpOffer.SetupRole derivedActiveRole(SdpOffer.SetupRole localOfferSetup,
            SdpOffer.SetupRole peerSetup) {
        if (localOfferSetup == SdpOffer.SetupRole.ACTPASS) {
            // Peer chose; we adopt the complementary role.
            return SdpAnswer.complementarySetup(peerSetup);
        }
        // We offered a fixed role; we stick to it.
        return localOfferSetup == null ? SdpOffer.SetupRole.ACTIVE : localOfferSetup;
    }

    private static List<String> intersect(List<String> a, List<String> b) {
        return SdpAnswer.intersect(a, b);
    }

    // ---------- nested types ----------

    private enum Role { ORIGINATING, TERMINATING }

    /** Per-session JAIN-SIP / connection state, kept off the public surface. */
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
        // The sender wired for this session (set at establish) — lets us push
        // an in-session IMDN back over the same leg (RFC 5438 / RCC.07).
        CarrierMsrpSessionSender sender;
    }

    /**
     * MSRP TLS connection listener that forwards events into the session
     * state machine. Frames go to the session's listener; close events
     * drive the state machine and prune our session registry.
     */
    private final class ConnListener implements MsrpTlsConnection.Listener {
        private final ActiveSession active;

        ConnListener(ActiveSession a) { this.active = a; }

        @Override
        public void onFrame(MsrpMessage frame) {
            active.session.onMsrpFrame(frame);
        }

        @Override
        public void onClosed(MsrpChatSession.CloseReason reason, String detail) {
            // Drive the state machine and remove from the registry. If the
            // session is already CLOSED (e.g. local BYE drove us here), the
            // state machine swallows the redundant close.
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

    /**
     * MSRP socket factory used by passive (terminating) side: it never
     * "connects" — the caller already accepted a socket and called
     * {@link MsrpTlsConnection#adopt(java.net.Socket)}. This stub exists
     * to satisfy the connection's non-null factory contract.
     */
    private static final class AdoptingFactory implements MsrpTlsConnection.SocketFactory {
        @Override
        public java.net.Socket connect(String host, int port) throws IOException {
            throw new IOException("AdoptingFactory: this connection is passive; use adopt()");
        }
    }
}
