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
package com.android.messaging.rcs.carrier.sip;

import android.util.Log;

import com.android.messaging.rcs.carrier.RcsImsConfig;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sip.ClientTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CSeqHeader;
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

/**
 * RFC 3428 SIP MESSAGE sender for the carrier-RCS pager-mode path. Sits on
 * top of an already-registered {@link com.android.messaging.rcs.carrier.CarrierSipRegistrar}'s
 * {@link SipProvider} / {@link RcsImsConfig} and emits MESSAGE requests
 * carrying CPIM-wrapped payloads.
 *
 * <p>Header set per Google Messages:
 *
 * <ul>
 *   <li>Request-line: {@code MESSAGE &lt;tel:+E164&gt; SIP/2.0}</li>
 *   <li>From/To: caller / recipient URIs; To has no tag (pager-mode is
 *       dialogless)</li>
 *   <li>Via: stack-injected branch {@code z9hG4bK...}</li>
 *   <li>Call-ID: fresh per message</li>
 *   <li>CSeq: monotonically incrementing</li>
 *   <li>Max-Forwards: 70</li>
 *   <li>P-Preferred-Identity: {@code <tel:+E164>}</li>
 *   <li>Contribution-ID: per-conversation UUID</li>
 *   <li>Accept-Contact: {@code *;+g.3gpp.icsi-ref="..."} for the
 *       1:1 CPM session type</li>
 *   <li>P-Preferred-Service: {@code urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session}</li>
 *   <li>Content-Type: {@code message/cpim} (for chat) or
 *       {@code application/im-iscomposing+xml} (for typing-as-pager)</li>
 *   <li>Content-Length + body</li>
 * </ul>
 *
 * <p>Threading: this class is stateless except for the CSeq counter and the
 * SIP transaction registry (which JAIN-SIP owns). Callers should serialize
 * outgoing sends on the same {@code sipThread} that owns the provider —
 * matches the pattern in {@link com.android.messaging.rcs.carrier.CarrierSipRegistrar}.
 */
public final class CarrierMessageSender {

    private static final String TAG = "CarrierMessageTx";

    /** UP 2.4 §A.1.4 P-Preferred-Service for 1:1 CPM session. */
    public static final String P_PREFERRED_SERVICE_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.msg";

    /** UA banner. Matches {@link com.android.messaging.rcs.carrier.CarrierSipRegistrar}. */
    public static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /** Accept-Contact feature-tag value advertising 1:1 CPM pager-mode (standalone
     *  MESSAGE) capability. Pager mode uses the oma.cpm.MSG ICSI (not .session,
     *  which is MSRP session mode), and the urn value is %-escaped + quoted so the
     *  S-CSCF iFC (Kamailio) parses it and matches the oma.cpm route to the CPM AS
     *  (open5gs, 2026-07-18). */
    public static final String ACCEPT_CONTACT_1_TO_1 =
            "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"";

    private final SipProvider provider;
    private final MessageFactory messageFactory;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final RcsImsConfig config;
    private final String localIp;
    private final int localPort;
    private final String transportProto;
    /** Effective P-CSCF host for the Route header — the PCO-signalled address the
     *  registrar actually reached, which may differ from {@code config.pcscfAddress}
     *  (e.g. the ACS doc carries an FQDN the UE can't DNS-resolve; PCO gave us the IP). */
    private final String proxyHost;

    private final AtomicLong cseq = new AtomicLong(1);
    /** Per-process conversation contribution-id. One per session is fine — Google
     *  Messages uses a per-conversation UUID; for our 1:1 case we keep one per sender
     *  instance until we wire up a conversation-id store. */
    private final String contributionId = UUID.randomUUID().toString();
    /** Per-conversation CPM Conversation-ID. RCS/CPM (and the open5gs CPM AS's
     *  parse_id) require both Conversation-ID and Contribution-ID to be RFC 4122
     *  UUIDs — a non-UUID or missing Conversation-ID yields 400 at the CPM AS. */
    private final String conversationId = UUID.randomUUID().toString();

    public CarrierMessageSender(SipProvider provider,
            MessageFactory messageFactory, HeaderFactory headerFactory,
            AddressFactory addressFactory, RcsImsConfig config,
            String localIp, int localPort, String transportProto, String proxyHost) {
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
    }

    /**
     * Send a 1:1 plain-text chat as a SIP MESSAGE with a CPIM-wrapped
     * {@code text/plain;charset=UTF-8} body.
     *
     * @param toUri     recipient URI (e.g. {@code tel:+15551234567})
     * @param text      message body
     * @param messageId stable CPIM Message-ID; IMDN reports will reference this id
     * @return the {@link ClientTransaction} that the caller can monitor for
     *         response status, never null
     */
    public ClientTransaction sendText(String toUri, String text, String messageId)
            throws ParseException, SipException {
        String dt = CpimDateTime.now();
        CpimMessage cpim = CpimMessage.newText(
                config.publicIdentity, toUri, messageId, dt, text);
        return sendCpim(toUri, cpim, /*expectDisposition=*/ true);
    }

    /**
     * Send an IMDN report (delivered / displayed / processed / failed) as a
     * SIP MESSAGE with a CPIM body wrapping the {@code message/imdn+xml}.
     * The {@code messageId} of the report is freshly minted; the original
     * message id is carried inside the IMDN XML.
     *
     * @param toUri        recipient (the sender of the original message)
     * @param notification the IMDN status to deliver
     * @param reportMessageId fresh CPIM Message-ID for the report frame itself
     */
    public ClientTransaction sendImdnReport(String toUri,
            ImdnNotification notification, String reportMessageId)
            throws ParseException, SipException {
        String dt = CpimDateTime.now();
        CpimMessage cpim = CpimMessage.newImdnReport(
                config.publicIdentity, toUri, reportMessageId, dt, notification.toXml());
        return sendCpim(toUri, cpim, /*expectDisposition=*/ false);
    }

    /**
     * Send an FT-HTTP file-info descriptor (application/vnd.gsma.rcs-ft-http+xml,
     * the {@code <data url>} the recipient downloads from) as a pager-mode CPIM
     * MESSAGE. The media itself already rode the HTTP content server; this MESSAGE
     * only carries the small descriptor, so it stays a normal pager MESSAGE.
     * Requests delivery+display IMDNs (FT wants receipts).
     */
    public ClientTransaction sendFileInfo(String toUri, byte[] ftHttpXmlUtf8,
            String messageId) throws ParseException, SipException {
        String dt = CpimDateTime.now();
        CpimMessage cpim = CpimMessage.newBuilder()
                .from(config.publicIdentity)
                .to(toUri)
                .addImdnNamespace()
                .messageId(messageId)
                .dateTime(dt)
                .contentType(CpimMessage.CT_FT_HTTP_XML)
                .payload(ftHttpXmlUtf8)
                .build();
        return sendCpim(toUri, cpim, /*expectDisposition=*/ true);
    }

    /**
     * Send an iscomposing typing indicator over the pager-mode SIP MESSAGE
     * plane. Google Messages prefers MSRP for this, but RFC 3428 + RFC 3994 permit
     * pager-mode, and some carriers route it that way when no chat session
     * is up.
     */
    public ClientTransaction sendIsComposing(String toUri,
            IsComposingNotification notification, String messageId)
            throws ParseException, SipException {
        String dt = CpimDateTime.now();
        CpimMessage cpim = CpimMessage.newBuilder()
                .from(config.publicIdentity)
                .to(toUri)
                .addImdnNamespace()
                .messageId(messageId)
                .dateTime(dt)
                .contentType(CpimMessage.CT_ISCOMPOSING_XML)
                .payload(notification.toXmlBytes())
                .build();
        return sendCpim(toUri, cpim, /*expectDisposition=*/ false);
    }

    /**
     * Low-level entry point: package a pre-built CPIM message into a SIP
     * MESSAGE and dispatch via the provider. Most callers should use one of
     * the typed helpers above.
     *
     * @param expectDisposition if true the CPIM body's
     *     {@code imdn.Disposition-Notification} header is honored — this is
     *     only meaningful for outbound chat text, NOT for outbound IMDN
     *     reports (we don't ask for receipts of our receipts) or typing
     *     indicators (which are transient by nature).
     */
    /**
     * DEBUG / diagnostics: send a PLAIN, non-CPM {@code text/plain} SIP MESSAGE
     * to {@code toUri}. Deliberately omits ALL CPM machinery — no
     * {@code Accept-Contact} / {@code +g.3gpp.icsi-ref}, no
     * {@code P-Preferred-Service}, no Conversation/Contribution-ID, no CPIM
     * wrapper — so it does NOT match the oma.cpm iFC and therefore exercises the
     * pure IMS terminating path (I-CSCF LIR → B's S-CSCF → Path → P-CSCF → B's
     * registered flow) with no CPM AS involvement. Used to trace/validate the
     * terminating-path rearchitecture (open5gs Phase 1). Keeps only the standard
     * routing headers + P-Preferred-Identity.
     */
    public ClientTransaction sendPlainText(String toUri, String text, String messageId)
            throws ParseException, SipException {
        URI requestUri = addressFactory.createURI(stripAngleBrackets(toUri));
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
                cseq.getAndIncrement(), Request.MESSAGE);
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

        Request req = messageFactory.createRequest(requestUri, Request.MESSAGE,
                provider.getNewCallId(), cs, from, to, vias, mf);
        req.addHeader(route);
        // Identity only — NO P-Preferred-Service / Accept-Contact / CPM IDs.
        if (config.publicIdentity != null) {
            req.addHeader(headerFactory.createHeader(
                    "P-Preferred-Identity", "<" + config.publicIdentity + ">"));
        }
        UserAgentHeader ua = headerFactory.createUserAgentHeader(
                java.util.Collections.singletonList(USER_AGENT));
        req.addHeader(ua);

        ContentTypeHeader ct = headerFactory.createContentTypeHeader(
                "text", "plain");
        ct.setParameter("charset", "UTF-8");
        req.setContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), ct);

        ClientTransaction tx = provider.getNewClientTransaction(req);
        Log.i(TAG, "PLAIN MESSAGE wire >>>\n" + req.toString() + "\n<<< PLAIN MESSAGE wire");
        tx.sendRequest();
        Log.i(TAG, "PLAIN MESSAGE -> " + toUri + " mid=" + messageId
                + " ct=text/plain (non-CPM)");
        return tx;
    }

    public ClientTransaction sendCpim(String toUri, CpimMessage cpim,
            boolean expectDisposition) throws ParseException, SipException {
        URI requestUri = addressFactory.createURI(stripAngleBrackets(toUri));
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
                cseq.getAndIncrement(), Request.MESSAGE);
        MaxForwardsHeader mf = headerFactory.createMaxForwardsHeader(70);

        // Route through the carrier P-CSCF the registrar actually reached (PCO IP
        // when the ACS doc's P-CSCF was an unresolvable FQDN), per CarrierSipRegistrar.
        SipURI proxyUri = addressFactory.createSipURI(null, proxyHost);
        boolean tls = "tls".equals(transportProto);
        int pcscfPort = config.pcscfPort > 0
                ? config.pcscfPort : (tls ? 5061 : 5060);
        proxyUri.setPort(pcscfPort);
        proxyUri.setTransportParam(transportProto);
        proxyUri.setLrParam();
        RouteHeader route = headerFactory.createRouteHeader(
                addressFactory.createAddress(proxyUri));

        Request req = messageFactory.createRequest(requestUri, Request.MESSAGE,
                provider.getNewCallId(), cs, from, to, vias, mf);
        req.addHeader(route);

        // Identity / service headers, as Google Messages emits them.
        if (config.publicIdentity != null) {
            req.addHeader(headerFactory.createHeader(
                    "P-Preferred-Identity", "<" + config.publicIdentity + ">"));
        }
        req.addHeader(headerFactory.createHeader(
                "P-Preferred-Service", P_PREFERRED_SERVICE_CPM_SESSION));
        req.addHeader(headerFactory.createHeader(
                "Accept-Contact", ACCEPT_CONTACT_1_TO_1));
        req.addHeader(headerFactory.createHeader(
                "Conversation-ID", conversationId));
        req.addHeader(headerFactory.createHeader(
                "Contribution-ID", contributionId));

        UserAgentHeader ua = headerFactory.createUserAgentHeader(
                java.util.Collections.singletonList(USER_AGENT));
        req.addHeader(ua);

        ContentTypeHeader ct = headerFactory.createContentTypeHeader(
                "message", "cpim");
        req.setContent(cpim.encode(), ct);

        ClientTransaction tx = provider.getNewClientTransaction(req);
        Log.i(TAG, "MESSAGE wire >>>\n" + req.toString() + "\n<<< MESSAGE wire");
        tx.sendRequest();
        Log.i(TAG, "MESSAGE -> " + toUri + " mid=" + cpim.getMessageId()
                + " ct=" + cpim.getContentType()
                + " disp=" + expectDisposition);
        return tx;
    }

    /**
     * Strip a single pair of surrounding SIP name-addr angle brackets from a
     * URI string. The auto-IMDN target comes from a received CPIM {@code From}
     * header, which is already bracketed (RFC 3862 name-addr, e.g.
     * {@code <sip:user@host>}); feeding that straight into
     * {@code AddressFactory.createURI} + {@code createToHeader} double-wrapped
     * it to {@code To: <<sip:...>>}, which strict IMS parsers (open5gs P-CSCF)
     * reject. Bare URIs (e.g. {@code tel:+1...}) pass through unchanged.
     */
    private static String stripAngleBrackets(final String uri) {
        if (uri == null) {
            return null;
        }
        final String s = uri.trim();
        if (s.length() >= 2 && s.charAt(0) == '<' && s.charAt(s.length() - 1) == '>') {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
