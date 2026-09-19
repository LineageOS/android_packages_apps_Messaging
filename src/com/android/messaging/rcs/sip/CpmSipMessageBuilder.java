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
 * Builds a CPM pager-mode 1-1 SIP {@code MESSAGE} as an
 * {@link android.telephony.ims.SipMessage}, mirroring what Google Messages puts
 * on the wire: its SIP MESSAGE builder, its CPIM body builder and its
 * {@link SipMessage} serializer.
 *
 * <p>This is the OUTBOUND half of the SipDelegate ride. The framework /
 * Shannon's single-reg SipTransport fills in / rewrites the routing headers
 * (Route, Contact, the Via sent-by, P-CSCF path) from the registered session;
 * we supply the request line + From/To/Call-ID/CSeq/P-Preferred-Identity +
 * the CPM Accept-Contact + the CPIM body. The delegate's
 * {@link SipDelegateConfiguration} gives us the home domain, contact user
 * param, and IMEI so our From/Via sent-by match the registered identity.
 *
 * <p>Pager-mode MESSAGE is dialogless: To carries no tag, there is no Contact
 * header on the request, and the transaction completes with a single
 * 200/202 (or a 4xx/5xx) — no ACK.
 */
public final class CpmSipMessageBuilder {

    /** message/cpim is the CPM 1-1 content type (smapi-msrp-wire §3 line 250). */
    private static final String CT_MESSAGE_CPIM = "message/cpim";
    private static final String CT_TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";
    /** RFC 5438 IMDN disposition-notification payload type. */
    private static final String CT_IMDN_XML = "message/imdn+xml";
    /** RFC 3994 "is-composing" (typing) indication payload type. */
    private static final String CT_ISCOMPOSING_XML = "application/im-iscomposing+xml";

    /**
     * Accept-Contact gate for a CPM 1-1 session, in the human-readable
     * (non-URL-encoded) form Google Messages emits on the wire.
     * Note: this is the {@code urn:...} (colon) form, NOT the URL-encoded
     * {@code urn%3A...} form used in the {@code DelegateRequest} feature-tag
     * set — the request-set form is for capability matching; the on-wire
     * Accept-Contact uses the literal URN.
     */
    private static final String ACCEPT_CONTACT_CPM_SESSION =
            "*;+g.3gpp.icsi-ref=\"urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\""
                    + ";require;explicit";

    /** Monotonic CSeq source — pager-mode each MESSAGE is its own transaction. */
    private static final AtomicInteger CSEQ = new AtomicInteger(1);

    private CpmSipMessageBuilder() {}

    /** Result holder: the framework SipMessage plus the Call-ID we minted. */
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
     * Build a 1-1 text CPM MESSAGE.
     *
     * @param cfg       the delegate's registered config (home domain, contact
     *                  user param) — must not be null.
     * @param fromTel   our own line in {@code +E164} form (no scheme).
     * @param toTel     the recipient in {@code +E164} form (no scheme).
     * @param text      UTF-8 message body.
     */
    public static Built buildTextMessage(SipDelegateConfiguration cfg,
            String fromTel, String toTel, String text) {
        final String homeDomain = homeDomain(cfg);
        final String contactUser = contactUser(cfg, fromTel);

        final String fromTelUri = "tel:" + fromTel;
        final String toTelUri = "tel:" + toTel;
        final String fromSipUri = "sip:" + contactUser + "@" + homeDomain;
        // Request-URI + To must be a routable sip: URI (Shannon's modem-side
        // serializer truncated a bare tel: request-URI to [0x20 0x00] →
        // "Start line is INVALID"). Use sip:+E164@homeDomain;user=phone, the
        // form Shannon's UCE publish entity uses.
        final String toSipUri = "sip:" + toTel + "@" + homeDomain + ";user=phone";

        final String fromTag = randHex(16);
        final String callId = UUID.randomUUID().toString() + "@" + homeDomain;
        final int cseq = CSEQ.getAndIncrement();
        final String imdnMessageId = UUID.randomUUID().toString();
        final String contributionId = UUID.randomUUID().toString();
        // RFC 3261 magic-cookie branch; the framework SipMessage ctor requires a
        // Via header carrying a branch= param (it's the transaction id), and the
        // single-reg transport rewrites the sent-by but keeps the branch.
        final String branch = "z9hG4bK" + randHex(24);

        // ---- CPIM body (RFC 3862 + IMDN), in Google Messages' header order ----
        final byte[] cpim = buildCpim(fromTelUri, toTelUri, imdnMessageId, text);

        // ---- request line + headers, in Google Messages' order ----
        // DECISIVE WIRE RULE (2026-06-02): the start line MUST carry a trailing
        // CRLF. The framework's SipMessage.toEncodedMessage() and Shannon's
        // SipDelegateAdaptor.getEncodedMessage() both serialize as
        // startLine + headerSection + CRLF + body with NO separator inserted
        // between the start line and the first header. Omitting the CRLF glues
        // "MESSAGE ... SIP/2.0Via: ..." into one malformed start line → the
        // modem rejects with mStatusCode=3 ("Start line is INVALID"). This was
        // the shared defect behind every code-3 (MESSAGE and INVITE).
        final String startLine = "MESSAGE " + toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        // Via sent-by = OUR registered local transport address (RFC 3261
        // §8.1.1.7), not the home domain. branch= is the mandatory txn id.
        final String viaTransport = transportName(cfg);
        final String viaSentBy = localSentBy(cfg, homeDomain);
        h.append("Via: SIP/2.0/").append(viaTransport).append(" ").append(viaSentBy)
                .append(";rport;branch=").append(branch).append("\r\n");
        // From: pager-mode uses our SIP public identity with a tag.
        h.append("From: <").append(fromSipUri).append(">;tag=").append(fromTag).append("\r\n");
        // To: the recipient, no tag (dialogless).
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
     * Build a CPM 1-1 IMDN receipt as a pager-mode {@code MESSAGE} whose CPIM
     * body wraps an {@code message/imdn+xml} disposition notification (RFC 5438
     * §3.2, GSMA RCS UP §3.2.5). This is the {@code sendImdn} wire form
     * (spec §7.1: {@code sendImdn -> CPIM + message/imdn+xml}). The client drives
     * IMDN explicitly (spec §5.5), so this is fire-and-forget from :ims.
     *
     * @param cfg               registered delegate config.
     * @param fromTel           our own line ({@code +E164}, no scheme).
     * @param toTel             the peer that sent the original ({@code +E164}).
     * @param originalMessageId the {@code imdn.Message-ID} of the message being
     *                          acked (the id we surfaced as
     *                          {@code RcsIncomingMessage.messageId}).
     * @param imdnType          {@code IRcsProviderCallback.IMDN_DELIVERED (1)} or
     *                          {@code IMDN_DISPLAYED (2)}.
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
        // CPIM for IMDN carries Content-Disposition: notification (RFC 5438 §7.1).
        final String extraCpim = "imdn.Disposition-Notification: negative-delivery, "
                + "positive-delivery, display\r\n";
        final byte[] cpim = buildWrappedCpim(fromTel, toTel, UUID.randomUUID().toString(),
                CT_IMDN_XML, xml.getBytes(StandardCharsets.UTF_8),
                "Content-Disposition: notification\r\n", extraCpim);
        return assemblePagerMessage(cfg, fromTel, toTel, cpim);
    }

    /**
     * Build a CPM 1-1 "is-composing" (typing) indication as a pager-mode
     * {@code MESSAGE} whose CPIM body wraps an
     * {@code application/im-iscomposing+xml} document (RFC 3994, GSMA RCS UP
     * §3.2.6). This is the {@code sendTyping} wire form (spec §7.1). Best-effort,
     * fire-and-forget.
     *
     * @param active {@code true} => {@code <state>active</state>};
     *               {@code false} => {@code <state>idle</state>}.
     */
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
     * Assemble a pager-mode {@code MESSAGE} around an already-built CPIM body.
     * Shares the exact header block (and the decisive start-line CRLF rule) with
     * {@link #buildTextMessage}; only the CPIM payload differs.
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
     * Generic CPIM wrapper (RFC 3862) for a non-text inner payload (IMDN /
     * is-composing). Mirrors {@link #buildCpim} header order but takes the inner
     * Content-Type + optional inner Content-Disposition + optional extra
     * namespaced CPIM headers so IMDN's {@code Content-Disposition: notification}
     * and disposition-notification header land in the right place.
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

    /** CPIM body builder — mirrors Google Messages' header order.
     *  For a 1:1 outgoing message Google Messages puts the
     *  ANONYMIZED address in both CPIM From and To
     *  ({@code sip:anonymous@anonymous.invalid}); the real tel: identities ride
     *  the SIP layer (P-Preferred-Identity + request-URI), not the CPIM
     *  envelope. (Real tel: in CPIM From/To is the group/{@code c()==2} path.) */
    private static final String CPIM_ANON = "sip:anonymous@anonymous.invalid";

    private static byte[] buildCpim(String fromUri, String toUri,
            String imdnMessageId, String text) {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        // Header order per Google Messages: NS lines first, then the From/To/
        // DateTime/imdn.* namespaced headers.
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
            // getHomeDomain absent on some API levels; fall through.
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
        // Prefer the registered contact user parameter (what the modem put in
        // its Contact on REGISTER); fall back to the public user identifier;
        // last resort the raw tel.
        try {
            String c = cfg.getSipContactUserParameter();
            if (c != null && !c.isEmpty()) {
                return c;
            }
        } catch (Throwable ignore) {
            // not available.
        }
        try {
            String u = cfg.getPublicUserIdentifier();
            if (u != null && !u.isEmpty()) {
                return stripScheme(u);
            }
        } catch (Throwable ignore) {
            // not available; use the tel.
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
