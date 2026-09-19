/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import android.util.Log;

import com.android.messaging.rcs.carrier.RcsImsConfig;
import com.android.messaging.rcs.log.LogMask;

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
 * RFC 3428 pager-mode SIP {@code MESSAGE} sender with CPIM bodies, on a registered
 * {@link com.android.messaging.rcs.carrier.CarrierSipRegistrar}'s provider. To carries no tag,
 * since pager mode is dialogless. Call it on the registrar's SIP thread. Header set: see
 * docs/rcs/carrier-transport.md.
 */
public final class CarrierMessageSender {

    private static final String TAG = "CarrierMessageTx";

    /** The pager-mode ICSI ({@code oma.cpm.msg}), despite the constant's name. */
    public static final String P_PREFERRED_SERVICE_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.msg";

    /** Same as the registrar's. */
    public static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /**
     * Pager mode uses the {@code oma.cpm.msg} ICSI ({@code .session} is MSRP session mode); the URN
     * is percent-escaped and quoted so the S-CSCF's filter criteria parse it.
     */
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
    /**
     * The P-CSCF host the registrar actually reached, which may be a PCO address where the
     * configuration named a host the device cannot resolve.
     */
    private final String proxyHost;

    private final AtomicLong cseq = new AtomicLong(1);
    /** One per sender instance rather than per conversation. */
    private final String contributionId = UUID.randomUUID().toString();
    /** Both CPM ids must be RFC 4122 UUIDs; a CPM application server answers 400 otherwise. */
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
     * A 1:1 {@code text/plain;charset=UTF-8} chat in CPIM.
     *
     * @param messageId the CPIM Message-ID that receipts will reference
     */
    public ClientTransaction sendText(String toUri, String text, String messageId)
            throws ParseException, SipException {
        String dt = CpimDateTime.now();
        CpimMessage cpim = CpimMessage.newText(
                config.publicIdentity, toUri, messageId, dt, text);
        return sendCpim(toUri, cpim, /*expectDisposition=*/ true);
    }

    /**
     * An IMDN in CPIM. {@code reportMessageId} is the report's own id; the original is in the XML.
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
     * A file-transfer descriptor ({@code application/vnd.gsma.rcs-ft-http+xml}) in CPIM, requesting
     * receipts. The file itself is already on the content server.
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

    /** A typing indicator in pager mode, which RFC 3994 permits when no session is up. */
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
     * Debug: a plain {@code text/plain} SIP message with no CPM headers or CPIM wrapper, so it does
     * not match the CPM filter criteria and exercises the bare IMS terminating path.
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
        // Identity only: no CPM service headers or ids.
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
        Log.i(TAG, "PLAIN MESSAGE wire: " + wireSummary(req));
        tx.sendRequest();
        Log.i(TAG, "PLAIN MESSAGE -> " + LogMask.number(toUri) + " mid=" + messageId
                + " ct=text/plain (non-CPM)");
        return tx;
    }

    /**
     * Sends a built CPIM message. {@code expectDisposition} is only logged; the CPIM
     * {@code imdn.Disposition-Notification} header is what requests receipts.
     */
    /** Method, Call-ID and body size of an outgoing request; never its body, the user's text. */
    private static String wireSummary(Request req) {
        final javax.sip.header.CallIdHeader callId =
                (javax.sip.header.CallIdHeader) req.getHeader(javax.sip.header.CallIdHeader.NAME);
        final byte[] content = req.getRawContent();
        return req.getMethod() + " call-id=" + (callId == null ? "?" : callId.getCallId())
                + " contentLen=" + (content == null ? 0 : content.length);
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

        // Route through the P-CSCF the registrar actually reached.
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
        Log.i(TAG, "MESSAGE wire: " + wireSummary(req));
        tx.sendRequest();
        Log.i(TAG, "MESSAGE -> " + LogMask.number(toUri) + " mid=" + cpim.getMessageId()
                + " ct=" + cpim.getContentType()
                + " disp=" + expectDisposition);
        return tx;
    }

    /**
     * Removes one pair of surrounding angle brackets. A CPIM {@code From} is a bracketed name-addr,
     * and passing it on unchanged would produce {@code To: <<sip:...>>}.
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
