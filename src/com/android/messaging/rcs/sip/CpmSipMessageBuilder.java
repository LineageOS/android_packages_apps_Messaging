/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipMessage;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds pager-mode CPM 1:1 SIP requests as framework {@link SipMessage}s for the SR path. The
 * framework rewrites the routing headers (Route, Contact, the Via sent-by) from the registration;
 * we supply the request line, identity and CPM headers and the CPIM body, using the delegate's
 * {@link SipDelegateConfiguration} so they match the registered identity. Dialogless: To has no
 * tag and the request carries no Contact.
 */
public final class CpmSipMessageBuilder {

    private static final String CT_MESSAGE_CPIM = "message/cpim";
    private static final String CT_TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";
    private static final String CT_IMDN_XML = "message/imdn+xml";
    private static final String CT_ISCOMPOSING_XML = "application/im-iscomposing+xml";

    /**
     * The on-wire Accept-Contact uses the literal URN, unlike the URL-encoded form in the
     * {@code DelegateRequest} tag set.
     */
    private static final String ACCEPT_CONTACT_CPM_SESSION =
            "*;+g.3gpp.icsi-ref=\"urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\""
                    + ";require;explicit";

    private static final AtomicInteger CSEQ = new AtomicInteger(1);

    private CpmSipMessageBuilder() {}

    /** The message plus the ids minted for it. */
    public static final class Built {
        public final SipMessage sipMessage;
        public final String callId;
        public final String imdnMessageId;
        public final String contributionId;

        Built(SipMessage m, String callId, String imdnMessageId, String contributionId) {
            this.sipMessage = m;
            this.callId = callId;
            this.imdnMessageId = imdnMessageId;
            this.contributionId = contributionId;
        }
    }

    /**
     * @param fromTel our line as {@code +E164}, without a scheme
     * @param toTel the recipient as {@code +E164}, without a scheme
     */
    public static Built buildTextMessage(SipDelegateConfiguration cfg,
            String fromTel, String toTel, String text) {
        final String homeDomain = homeDomain(cfg);
        final String contactUser = contactUser(cfg, fromTel);

        final String fromTelUri = "tel:" + fromTel;
        final String toTelUri = "tel:" + toTel;
        final String fromSipUri = "sip:" + contactUser + "@" + homeDomain;
        // The request URI must be sip:+E164@domain;user=phone: the modem's serializer does not
        // carry a bare tel: request URI.
        final String toSipUri = "sip:" + toTel + "@" + homeDomain + ";user=phone";

        final String fromTag = randHex(16);
        final String callId = UUID.randomUUID().toString() + "@" + homeDomain;
        final int cseq = CSEQ.getAndIncrement();
        final String imdnMessageId = UUID.randomUUID().toString();
        final String contributionId = UUID.randomUUID().toString();
        // The framework's SipMessage requires a Via branch, the transaction id (RFC 3261); the
        // transport rewrites the sent-by but keeps it.
        final String branch = "z9hG4bK" + randHex(24);

        final byte[] cpim = buildCpim(fromTelUri, toTelUri, imdnMessageId, text);

        // The start line must end in CRLF: the framework serializes start line, headers and body
        // with no separator of its own, and the modem rejects the result otherwise.
        final String startLine = "MESSAGE " + toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        // RFC 3261 §8.1.1.7: sent-by is our registered local address, not the home domain.
        final String viaTransport = transportName(cfg);
        final String viaSentBy = localSentBy(cfg, homeDomain);
        h.append("Via: SIP/2.0/").append(viaTransport).append(" ").append(viaSentBy)
                .append(";rport;branch=").append(branch).append("\r\n");
        h.append("From: <").append(fromSipUri).append(">;tag=").append(fromTag).append("\r\n");
        // no tag: dialogless
        h.append("To: <").append(toSipUri).append(">\r\n");
        h.append("Call-ID: ").append(callId).append("\r\n");
        h.append("CSeq: ").append(cseq).append(" MESSAGE\r\n");
        h.append("Max-Forwards: 70\r\n");
        h.append("P-Preferred-Identity: <").append(fromTelUri).append(">\r\n");
        h.append("Accept-Contact: ").append(ACCEPT_CONTACT_CPM_SESSION).append("\r\n");
        h.append("P-Preferred-Service: ")
                .append("urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session").append("\r\n");
        h.append("Contribution-ID: ").append(contributionId).append("\r\n");
        h.append("Conversation-ID: ").append(UUID.randomUUID().toString()).append("\r\n");
        h.append("Content-Type: ").append(CT_MESSAGE_CPIM).append("\r\n");
        h.append("Content-Length: ").append(cpim.length).append("\r\n");

        final SipMessage sip = new SipMessage(startLine, h.toString(), cpim);
        return new Built(sip, callId, imdnMessageId, contributionId);
    }

    /**
     * An IMDN receipt (RFC 5438) in CPIM, for the message the peer sent as
     * {@code originalMessageId}. {@code imdnType} is {@code IMDN_DELIVERED} (1) or
     * {@code IMDN_DISPLAYED} (2).
     */
    public static Built buildImdn(SipDelegateConfiguration cfg, String fromTel,
            String toTel, String originalMessageId, int imdnType) {
        final boolean displayed = imdnType == 2; // IMDN_DISPLAYED
        final String status = displayed ? "displayed" : "delivered";
        final String element = displayed ? "display-notification" : "delivery-notification";
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\r\n"
                + "<message-id>" + xmlEscape(originalMessageId) + "</message-id>\r\n"
                + "<datetime>" + rfc3339Now() + "</datetime>\r\n"
                + "<" + element + "><status><" + status + "/></status></" + element + ">\r\n"
                + "</imdn>\r\n";
        // RFC 5438 §7.1: the inner part carries Content-Disposition: notification.
        final String extraCpim = "imdn.Disposition-Notification: negative-delivery, "
                + "positive-delivery, display\r\n";
        final byte[] cpim = buildWrappedCpim(fromTel, toTel, UUID.randomUUID().toString(),
                CT_IMDN_XML, xml.getBytes(StandardCharsets.UTF_8),
                "Content-Disposition: notification\r\n", extraCpim);
        return assemblePagerMessage(cfg, fromTel, toTel, cpim);
    }

    /** An isComposing indication (RFC 3994) in CPIM, {@code active} or idle. */
    public static Built buildIsComposing(SipDelegateConfiguration cfg, String fromTel,
            String toTel, boolean active) {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\"\r\n"
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
                + "<state>" + (active ? "active" : "idle") + "</state>\r\n"
                + "<contenttype>text/plain</contenttype>\r\n"
                + (active ? "<refresh>60</refresh>\r\n" : "")
                + "</isComposing>\r\n";
        final byte[] cpim = buildWrappedCpim(fromTel, toTel, UUID.randomUUID().toString(),
                CT_ISCOMPOSING_XML, xml.getBytes(StandardCharsets.UTF_8), null, null);
        return assemblePagerMessage(cfg, fromTel, toTel, cpim);
    }

    /**
     * The pager-mode request around a built CPIM body; the header block of
     * {@link #buildTextMessage}.
     */
    private static Built assemblePagerMessage(SipDelegateConfiguration cfg,
            String fromTel, String toTel, byte[] cpim) {
        final String homeDomain = homeDomain(cfg);
        final String contactUser = contactUser(cfg, fromTel);
        final String fromTelUri = "tel:" + fromTel;
        final String fromSipUri = "sip:" + contactUser + "@" + homeDomain;
        final String toSipUri = "sip:" + toTel + "@" + homeDomain + ";user=phone";
        final String fromTag = randHex(16);
        final String callId = UUID.randomUUID().toString() + "@" + homeDomain;
        final int cseq = CSEQ.getAndIncrement();
        final String contributionId = UUID.randomUUID().toString();
        final String branch = "z9hG4bK" + randHex(24);
        final String startLine = "MESSAGE " + toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        final String viaTransport = transportName(cfg);
        final String viaSentBy = localSentBy(cfg, homeDomain);
        h.append("Via: SIP/2.0/").append(viaTransport).append(" ").append(viaSentBy)
                .append(";rport;branch=").append(branch).append("\r\n");
        h.append("From: <").append(fromSipUri).append(">;tag=").append(fromTag).append("\r\n");
        h.append("To: <").append(toSipUri).append(">\r\n");
        h.append("Call-ID: ").append(callId).append("\r\n");
        h.append("CSeq: ").append(cseq).append(" MESSAGE\r\n");
        h.append("Max-Forwards: 70\r\n");
        h.append("P-Preferred-Identity: <").append(fromTelUri).append(">\r\n");
        h.append("Accept-Contact: ").append(ACCEPT_CONTACT_CPM_SESSION).append("\r\n");
        h.append("P-Preferred-Service: ")
                .append("urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session").append("\r\n");
        h.append("Contribution-ID: ").append(contributionId).append("\r\n");
        h.append("Conversation-ID: ").append(UUID.randomUUID().toString()).append("\r\n");
        h.append("Content-Type: ").append(CT_MESSAGE_CPIM).append("\r\n");
        h.append("Content-Length: ").append(cpim.length).append("\r\n");

        final SipMessage sip = new SipMessage(startLine, h.toString(), cpim);
        return new Built(sip, callId, UUID.randomUUID().toString(), contributionId);
    }

    /**
     * CPIM (RFC 3862) around a non-text payload, with an optional inner Content-Disposition and
     * extra CPIM headers. {@code fromTel} and {@code toTel} are unused: the addresses are
     * anonymous.
     */
    private static byte[] buildWrappedCpim(String fromTel, String toTel,
            String cpimMessageId, String innerContentType, byte[] innerBody,
            String innerContentDisposition, String extraCpimHeaders) {
        final StringBuilder c = new StringBuilder();
        c.append("NS: imdn <urn:ietf:params:imdn>\r\n");
        c.append("From: <").append(CPIM_ANON).append(">\r\n");
        c.append("To: <").append(CPIM_ANON).append(">\r\n");
        c.append("DateTime: ").append(rfc3339Now()).append("\r\n");
        c.append("imdn.Message-ID: ").append(cpimMessageId).append("\r\n");
        if (extraCpimHeaders != null) {
            c.append(extraCpimHeaders);
        }
        c.append("\r\n"); // end of CPIM headers
        c.append("Content-Type: ").append(innerContentType).append("\r\n");
        if (innerContentDisposition != null) {
            c.append(innerContentDisposition);
        }
        c.append("Content-Length: ").append(innerBody.length).append("\r\n");
        c.append("\r\n"); // end of inner MIME headers

        final byte[] head = c.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[head.length + innerBody.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(innerBody, 0, out, head.length, innerBody.length);
        return out;
    }

    private static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * CPIM From and To of a 1:1 message are anonymous; the real identities ride the SIP layer
     * (P-Preferred-Identity and the request URI).
     */
    private static final String CPIM_ANON = "sip:anonymous@anonymous.invalid";

    private static byte[] buildCpim(String fromUri, String toUri,
            String imdnMessageId, String text) {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        // NS first, then the From, To, DateTime and imdn.* headers.
        final StringBuilder c = new StringBuilder();
        c.append("NS: imdn <urn:ietf:params:imdn>\r\n");
        c.append("From: <").append(CPIM_ANON).append(">\r\n");
        c.append("To: <").append(CPIM_ANON).append(">\r\n");
        c.append("DateTime: ").append(rfc3339Now()).append("\r\n");
        c.append("imdn.Message-ID: ").append(imdnMessageId).append("\r\n");
        c.append("imdn.Disposition-Notification: positive-delivery, display\r\n");
        c.append("\r\n"); // end of CPIM headers
        c.append("Content-Type: ").append(CT_TEXT_PLAIN_UTF8).append("\r\n");
        c.append("Content-Length: ").append(payload.length).append("\r\n");
        c.append("\r\n"); // end of inner MIME headers

        final byte[] head = c.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    private static String homeDomain(SipDelegateConfiguration cfg) {
        try {
            String d = cfg.getHomeDomain();
            if (d != null && !d.isEmpty()) {
                return d;
            }
        } catch (Throwable ignore) {
        }
        return "rcs.mnc000.mcc000.3gppnetwork.org";
    }

    private static String transportName(SipDelegateConfiguration cfg) {
        try {
            return cfg.getTransportType() == 0 ? "UDP" : "TLS";
        } catch (Throwable ignore) {
            return "TLS";
        }
    }

    private static String localSentBy(SipDelegateConfiguration cfg, String homeDomain) {
        try {
            java.net.InetSocketAddress a = cfg.getLocalAddress();
            if (a != null && a.getAddress() != null) {
                String ip = a.getAddress().getHostAddress();
                if (ip.indexOf(':') >= 0) {
                    ip = "[" + ip + "]";
                }
                int port = a.getPort();
                return port > 0 ? ip + ":" + port : ip;
            }
        } catch (Throwable ignore) {
        }
        return homeDomain;
    }

    private static String contactUser(SipDelegateConfiguration cfg, String fromTel) {
        // The registered contact user, else the public user identifier, else the raw number.
        try {
            String c = cfg.getSipContactUserParameter();
            if (c != null && !c.isEmpty()) {
                return c;
            }
        } catch (Throwable ignore) {
        }
        try {
            String u = cfg.getPublicUserIdentifier();
            if (u != null && !u.isEmpty()) {
                return stripScheme(u);
            }
        } catch (Throwable ignore) {
        }
        return fromTel;
    }

    private static String stripScheme(String uri) {
        int i = uri.indexOf(':');
        if (i >= 0 && i + 1 < uri.length()) {
            String rest = uri.substring(i + 1);
            int at = rest.indexOf('@');
            return at > 0 ? rest.substring(0, at) : rest;
        }
        return uri;
    }

    private static String rfc3339Now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String randHex(int n) {
        final char[] hex = "0123456789abcdef".toCharArray();
        final StringBuilder sb = new StringBuilder(n);
        final java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) {
            sb.append(hex[r.nextInt(16)]);
        }
        return sb.toString();
    }
}
