/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipMessage;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SIP signalling for a CPM 1:1 session on the SR path, as framework {@link SipMessage}s: INVITE
 * with the SDP offer, ACK, BYE, and responses to inbound requests. The framework rewrites the
 * routing headers from the registration. One Call-ID and From-tag per dialog; the To-tag comes
 * from the 200; ACK reuses the INVITE's CSeq and BYE takes the next (RFC 3261 §12, §13).
 * Request URIs are {@code sip:+E164@domain;user=phone}, and every start line ends in CRLF.
 */
public final class CpmSessionSipBuilder {

    /** The ICSI in its literal form, for P-Preferred-Service, which is not matched as a tag. */
    static final String ICSI_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session";
    /**
     * The URL-encoded ICSI. The framework's outgoing-message check compares Accept-Contact and
     * Contact tags verbatim against the registered {@code DelegateRequest} tags, and rejects the
     * literal form.
     */
    static final String ICSI_CPM_SESSION_ENC =
            "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session";
    /** The tag exactly as registered. */
    static final String FEATURE_TAG_CPM_SESSION =
            "+g.3gpp.icsi-ref=\"" + ICSI_CPM_SESSION_ENC + "\"";
    static final String ACCEPT_CONTACT_CPM_SESSION =
            "*;" + FEATURE_TAG_CPM_SESSION + ";require;explicit";

    static final int SESSION_EXPIRES_SEC = 1800;
    static final int MIN_SE_SEC = 90;

    private CpmSessionSipBuilder() {}

    public static final class Dialog {
        public final String homeDomain;
        public final String fromTel;
        public final String toTel;
        public final String contactUser;
        public final String fromSipUri;     // sip:contactUser@homeDomain
        public final String toSipUri;       // see buildRequestUri
        public final String fromTelUri;
        public final String callId;
        public final String fromTag;
        public final String contributionId;
        public final String conversationId;
        // From the registration. An out-of-dialog request must carry the Service-Route as its Route
        // (3GPP TS 24.229) or the modem refuses it.
        public final String serviceRoute;  // the Route value
        public final String pathHeader;
        public final String pani;           // P-Access-Network-Info
        public final String plani;
        public final String cni;
        public final String securityVerify;
        public final String associatedUri;  // P-Associated-URI
        // Via sent-by and Contact name our registered address or GRUU, not the home domain
        // (RFC 3261 §8.1.1.7, §20.42).
        public final String viaSentBy;      // "<localIP>:<port>", else homeDomain
        public final String viaTransport;
        public final String contactUri;     // the GRUU if present, else sip:user@sentBy
        public final String instanceId;     // +sip.instance, urn:gsma:imei:...
        public volatile String toTag;       // from the 200
        public final AtomicInteger cseq = new AtomicInteger(1);

        Dialog(SipDelegateConfiguration cfg, String homeDomain, String fromTel,
                String toTel, String contactUser) {
            this.homeDomain = homeDomain;
            this.fromTel = fromTel;
            this.toTel = toTel;
            this.contactUser = contactUser;
            this.fromSipUri = "sip:" + contactUser + "@" + homeDomain;
            this.toSipUri = buildRequestUri(toTel, homeDomain);
            this.fromTelUri = "tel:" + fromTel;
            this.callId = UUID.randomUUID().toString() + "@" + homeDomain;
            this.fromTag = randHex(16);
            this.contributionId = UUID.randomUUID().toString();
            this.conversationId = UUID.randomUUID().toString();
            this.serviceRoute = cfgStr(cfg, "getSipServiceRouteHeader");
            this.pathHeader = cfgStr(cfg, "getSipPathHeader");
            this.pani = cfgStr(cfg, "getSipPaniHeader");
            this.plani = cfgStr(cfg, "getSipPlaniHeader");
            this.cni = cfgStr(cfg, "getSipCniHeader");
            this.securityVerify = cfgStr(cfg, "getSipSecurityVerifyHeader");
            this.associatedUri = cfgStr(cfg, "getSipAssociatedUriHeader");
            this.viaTransport = transportName(cfg);
            this.viaSentBy = localSentBy(cfg, homeDomain);
            this.instanceId = instanceId(cfg);
            this.contactUri = contactUri(cfg, contactUser, this.viaSentBy);
        }
    }

    /** The Via protocol token: UDP for transport 0, TLS otherwise. */
    private static String transportName(SipDelegateConfiguration cfg) {
        try {
            int t = cfg.getTransportType();
            return t == 0 ? "UDP" : "TLS";
        } catch (Throwable ignore) {
            return "TLS";
        }
    }

    /** The registered local address and port, else the home domain. */
    private static String localSentBy(SipDelegateConfiguration cfg, String homeDomain) {
        try {
            java.net.InetSocketAddress a = cfg.getLocalAddress();
            if (a != null && a.getAddress() != null) {
                String ip = a.getAddress().getHostAddress();
                // IPv6 literals are bracketed.
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

    /** The registered IMEI URN. */
    private static String instanceId(SipDelegateConfiguration cfg) {
        try {
            String imei = cfg.getImei();
            if (imei != null && !imei.isEmpty()) {
                return "urn:gsma:imei:" + imei;
            }
        } catch (Throwable ignore) {
        }
        return "urn:gsma:imei:000000-000000-0";
    }

    /** The registered GRUU (RFC 5627), else {@code sip:user@sentBy}. */
    private static String contactUri(SipDelegateConfiguration cfg, String contactUser,
            String sentBy) {
        try {
            android.net.Uri gruu = cfg.getPublicGruuUri();
            if (gruu != null) {
                String s = gruu.toString();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
        }
        return "sip:" + contactUser + "@" + sentBy;
    }

    /** The IMS domain derived from the SIM's MCC and MNC. */
    private static String imsNetworkDomain() {
        String mccmnc = sysprop("gsm.sim.operator.numeric");
        // Several SIMs are comma-separated.
        if (mccmnc != null && mccmnc.contains(",")) {
            String[] parts = mccmnc.split(",");
            mccmnc = parts.length > 1 ? parts[parts.length - 1] : parts[0];
        }
        if (mccmnc == null || mccmnc.length() < 5) {
            return "ims.mnc260.mcc310.3gppnetwork.org";
        }
        String mcc = mccmnc.substring(0, 3);
        String mnc = mccmnc.substring(3);
        if (mnc.length() == 2) mnc = "0" + mnc;
        return "ims.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    /**
     * The recipient request URI, chosen by {@code persist.rcs.cpm.requri}: {@code sip-home}
     * (default, {@code sip:+E164@homeDomain;user=phone}), {@code sip-ims} (the SIM's IMS domain)
     * or {@code tel}.
     */
    private static String buildRequestUri(String toTel, String homeDomain) {
        String form = sysprop("persist.rcs.cpm.requri");
        if ("tel".equals(form)) {
            return "tel:" + toTel;
        }
        if ("sip-ims".equals(form)) {
            return "sip:" + toTel + "@" + imsNetworkDomain() + ";user=phone";
        }
        // "sip-home", the default
        return "sip:" + toTel + "@" + homeDomain + ";user=phone";
    }

    private static String sysprop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            String s = v == null ? null : v.toString();
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cfgStr(SipDelegateConfiguration cfg, String getter) {
        try {
            java.lang.reflect.Method m = cfg.getClass().getMethod(getter);
            Object v = m.invoke(cfg);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The message and its branch, the transaction id. */
    public static final class Built {
        public final SipMessage sipMessage;
        public final String branch;
        public final int cseq;

        Built(SipMessage m, String branch, int cseq) {
            this.sipMessage = m;
            this.branch = branch;
            this.cseq = cseq;
        }
    }

    public static Dialog newDialog(SipDelegateConfiguration cfg, String fromTel, String toTel) {
        return new Dialog(cfg, homeDomain(cfg), fromTel, toTel, contactUser(cfg, fromTel));
    }

    /** The network-provided headers, for logging. */
    public static String dumpNetworkHeaders(Dialog d) {
        return "serviceRoute=" + d.serviceRoute
                + " | path=" + d.pathHeader
                + " | pani=" + d.pani
                + " | plani=" + d.plani
                + " | cni=" + d.cni
                + " | securityVerify=" + d.securityVerify
                + " | associatedUri=" + d.associatedUri;
    }

    public static Built buildInvite(Dialog d, byte[] sdpOffer) {
        final int cseq = d.cseq.getAndIncrement();
        final String branch = newBranch();
        // The framework encodes start line, headers and body with no separator of its own, so the
        // start line carries its CRLF; without it the modem rejects the request.
        final String startLine = "INVITE " + d.toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        via(h, d, branch);
        h.append("From: <").append(d.fromSipUri).append(">;tag=").append(d.fromTag).append("\r\n");
        h.append("To: <").append(d.toSipUri).append(">\r\n");
        h.append("Call-ID: ").append(d.callId).append("\r\n");
        h.append("CSeq: ").append(cseq).append(" INVITE\r\n");
        h.append("Max-Forwards: 70\r\n");
        // 3GPP TS 24.229 §5.1.2A: an out-of-dialog request carries the Service-Route as Route.
        if (notEmpty(d.serviceRoute)) {
            h.append("Route: ").append(d.serviceRoute).append("\r\n");
        }
        // Contact carries the CPM session tag so the in-dialog setup routes back to us.
        h.append("Contact: <").append(d.contactUri).append(">")
                .append(";").append(FEATURE_TAG_CPM_SESSION)
                .append(";+sip.instance=\"<").append(d.instanceId).append(">\"\r\n");
        h.append("P-Preferred-Identity: <").append(d.fromTelUri).append(">\r\n");
        h.append("Accept-Contact: ").append(ACCEPT_CONTACT_CPM_SESSION).append("\r\n");
        h.append("P-Preferred-Service: ").append(ICSI_CPM_SESSION).append("\r\n");
        h.append("Contribution-ID: ").append(d.contributionId).append("\r\n");
        h.append("Conversation-ID: ").append(d.conversationId).append("\r\n");
        h.append("Supported: timer, 100rel\r\n");
        h.append("Session-Expires: ").append(SESSION_EXPIRES_SEC).append(";refresher=uac\r\n");
        h.append("Min-SE: ").append(MIN_SE_SEC).append("\r\n");
        h.append("Allow: INVITE, ACK, BYE, CANCEL, UPDATE, MESSAGE, OPTIONS\r\n");
        // Echo the registration's access-network and security headers.
        if (notEmpty(d.pani)) {
            h.append("P-Access-Network-Info: ").append(d.pani).append("\r\n");
        }
        if (notEmpty(d.plani)) {
            h.append("P-Last-Access-Network-Info: ").append(d.plani).append("\r\n");
        }
        if (notEmpty(d.cni)) {
            h.append("Cellular-Network-Info: ").append(d.cni).append("\r\n");
        }
        if (notEmpty(d.securityVerify)) {
            h.append("Security-Verify: ").append(d.securityVerify).append("\r\n");
        }
        h.append("Accept: application/sdp\r\n");
        h.append("Content-Type: application/sdp\r\n");
        h.append("Content-Length: ").append(sdpOffer.length).append("\r\n");

        return new Built(new SipMessage(startLine, h.toString(), sdpOffer), branch, cseq);
    }

    // In-dialog, with the INVITE's CSeq (RFC 3261 §13.2.2.4) and the learned To-tag.
    public static Built buildAck(Dialog d, int inviteCseq) {
        final String branch = newBranch();
        final String startLine = "ACK " + d.toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        via(h, d, branch);
        h.append("From: <").append(d.fromSipUri).append(">;tag=").append(d.fromTag).append("\r\n");
        appendTo(h, d);
        h.append("Call-ID: ").append(d.callId).append("\r\n");
        h.append("CSeq: ").append(inviteCseq).append(" ACK\r\n");
        h.append("Max-Forwards: 70\r\n");
        if (notEmpty(d.serviceRoute)) {
            h.append("Route: ").append(d.serviceRoute).append("\r\n");
        }
        h.append("Content-Length: 0\r\n");

        return new Built(new SipMessage(startLine, h.toString(), new byte[0]), branch, inviteCseq);
    }

    // In-dialog, next CSeq.
    public static Built buildBye(Dialog d) {
        final int cseq = d.cseq.getAndIncrement();
        final String branch = newBranch();
        final String startLine = "BYE " + d.toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        via(h, d, branch);
        h.append("From: <").append(d.fromSipUri).append(">;tag=").append(d.fromTag).append("\r\n");
        appendTo(h, d);
        h.append("Call-ID: ").append(d.callId).append("\r\n");
        h.append("CSeq: ").append(cseq).append(" BYE\r\n");
        h.append("Max-Forwards: 70\r\n");
        if (notEmpty(d.serviceRoute)) {
            h.append("Route: ").append(d.serviceRoute).append("\r\n");
        }
        h.append("Content-Length: 0\r\n");

        return new Built(new SipMessage(startLine, h.toString(), new byte[0]), branch, cseq);
    }

    private static void via(StringBuilder h, Dialog d, String branch) {
        // RFC 3261 §8.1.1.7: sent-by is our registered address; branch is the transaction id.
        h.append("Via: SIP/2.0/").append(d.viaTransport).append(" ")
                .append(d.viaSentBy).append(";rport;branch=").append(branch).append("\r\n");
    }

    private static void appendTo(StringBuilder h, Dialog d) {
        h.append("To: <").append(d.toSipUri).append(">");
        if (d.toTag != null && !d.toTag.isEmpty()) {
            h.append(";tag=").append(d.toTag);
        }
        h.append("\r\n");
    }

    static String newBranch() {
        return "z9hG4bK" + randHex(24);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** The To-tag of a received response. */
    public static String parseToTag(String headerSection) {
        if (headerSection == null) return null;
        for (String line : headerSection.split("\r\n|\n")) {
            String l = line.trim();
            String low = l.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("to:") || low.startsWith("t:")) {
                int t = low.indexOf(";tag=");
                if (t < 0) return null;
                int start = t + ";tag=".length();
                int end = start;
                while (end < l.length()) {
                    char c = l.charAt(end);
                    if (c == ';' || c == '>' || c == ' ' || c == ',') break;
                    end++;
                }
                return l.substring(start, end);
            }
        }
        return null;
    }

    /** The code of a response start line, or -1 for a request. */
    public static int parseStatusCode(String startLine) {
        if (startLine == null) return -1;
        String s = startLine.trim();
        if (!s.startsWith("SIP/2.0")) return -1;
        String rest = s.substring("SIP/2.0".length()).trim();
        int sp = rest.indexOf(' ');
        String code = sp < 0 ? rest : rest.substring(0, sp);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The method of a request start line. */
    public static String parseMethod(String startLine) {
        if (startLine == null) return null;
        String s = startLine.trim();
        int sp = s.indexOf(' ');
        return sp < 0 ? null : s.substring(0, sp);
    }

    // Answering side: parse a peer's INVITE or BYE and build the response. The app owns CPM
    // signalling; the framework only routes it.

    /** What a response must echo (RFC 3261 §8.2.6.2). */
    public static final class IncomingRequest {
        public final String method;
        public final java.util.List<String> viaHeaders;  // every Via, in order
        public final String from;         // with its tag
        public final String to;           // without a tag
        public final String callId;
        public final String cseq;
        public final String fromTelUri;   // best effort
        public final String fromE164;     // best effort
        public final byte[] body;         // the SDP offer, for an INVITE

        IncomingRequest(String method, java.util.List<String> via, String from, String to,
                String callId, String cseq, String fromTelUri, String fromE164, byte[] body) {
            this.method = method;
            this.viaHeaders = via;
            this.from = from;
            this.to = to;
            this.callId = callId;
            this.cseq = cseq;
            this.fromTelUri = fromTelUri;
            this.fromE164 = fromE164;
            this.body = body;
        }
    }

    public static IncomingRequest parseRequest(SipMessage message) {
        final String startLine = message.getStartLine();
        final String hdr = message.getHeaderSection();
        final String method = parseMethod(startLine);
        final java.util.List<String> via = new java.util.ArrayList<>();
        String from = null, to = null, callId = null, cseq = null;
        if (hdr != null) {
            for (String raw : hdr.split("\r\n|\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                String low = line.toLowerCase(java.util.Locale.ROOT);
                if (low.startsWith("via:") || low.startsWith("v:")) {
                    via.add(afterColon(line));
                } else if (from == null && (low.startsWith("from:") || low.startsWith("f:"))) {
                    from = afterColon(line);
                } else if (to == null && (low.startsWith("to:") || low.startsWith("t:"))) {
                    to = afterColon(line);
                } else if (callId == null
                        && (low.startsWith("call-id:") || low.startsWith("i:"))) {
                    callId = afterColon(line);
                } else if (cseq == null && low.startsWith("cseq:")) {
                    cseq = afterColon(line);
                }
            }
        }
        final String fromTelUri = extractUri(from);
        final String fromE164 = telFromUri(fromTelUri);
        return new IncomingRequest(method, via, from, to, callId, cseq,
                fromTelUri, fromE164, message.getContent());
    }

    /**
     * Echoes every Via, From, Call-ID and CSeq, and adds our To-tag (RFC 3261 §8.2.6.2).
     *
     * @param contactUri our Contact, or null to omit, as for a final non-2xx
     * @param contentType ignored when {@code body} is empty
     */
    public static SipMessage buildResponse(IncomingRequest req, int statusCode, String reason,
            String toTag, String contactUri, byte[] body, String contentType) {
        final String startLine = "SIP/2.0 " + statusCode + " " + reason + "\r\n";
        final StringBuilder h = new StringBuilder();
        for (String v : req.viaHeaders) {
            h.append("Via: ").append(v).append("\r\n");
        }
        if (req.from != null) {
            h.append("From: ").append(req.from).append("\r\n");
        }
        // Our tag, unless the request's To already has one.
        String to = req.to != null ? req.to : "<sip:anonymous@anonymous.invalid>";
        if (toTag != null && !toTag.isEmpty()
                && to.toLowerCase(java.util.Locale.ROOT).indexOf(";tag=") < 0) {
            to = to + ";tag=" + toTag;
        }
        h.append("To: ").append(to).append("\r\n");
        if (req.callId != null) {
            h.append("Call-ID: ").append(req.callId).append("\r\n");
        }
        if (req.cseq != null) {
            h.append("CSeq: ").append(req.cseq).append("\r\n");
        }
        if (contactUri != null && !contactUri.isEmpty()) {
            h.append("Contact: <").append(contactUri).append(">")
                    .append(";").append(FEATURE_TAG_CPM_SESSION).append("\r\n");
        }
        final byte[] payload = body != null ? body : new byte[0];
        if (payload.length > 0 && contentType != null) {
            h.append("Content-Type: ").append(contentType).append("\r\n");
        }
        h.append("Content-Length: ").append(payload.length).append("\r\n");
        return new SipMessage(startLine, h.toString(), payload);
    }

    private static String afterColon(String headerLine) {
        int i = headerLine.indexOf(':');
        return i < 0 ? headerLine.trim() : headerLine.substring(i + 1).trim();
    }

    /** The URI of a name-addr, or of a bare URI up to its parameters. */
    private static String extractUri(String headerValue) {
        if (headerValue == null) return null;
        int lt = headerValue.indexOf('<');
        if (lt >= 0) {
            int gt = headerValue.indexOf('>', lt);
            if (gt > lt) {
                return headerValue.substring(lt + 1, gt).trim();
            }
        }
        int semi = headerValue.indexOf(';');
        String s = semi >= 0 ? headerValue.substring(0, semi) : headerValue;
        return s.trim();
    }

    /** {@code +E164} from a {@code tel:} or {@code sip:} URI, or null. */
    private static String telFromUri(String uri) {
        if (uri == null) return null;
        String s = uri;
        if (s.startsWith("tel:")) {
            s = s.substring(4);
        } else if (s.startsWith("sip:") || s.startsWith("sips:")) {
            s = s.substring(s.indexOf(':') + 1);
            int at = s.indexOf('@');
            if (at > 0) s = s.substring(0, at);
        }
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        s = s.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' && b.length() == 0) b.append(c);
            else if (c >= '0' && c <= '9') b.append(c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static String homeDomain(SipDelegateConfiguration cfg) {
        try {
            String dom = cfg.getHomeDomain();
            if (dom != null && !dom.isEmpty()) {
                return dom;
            }
        } catch (Throwable ignore) {
        }
        return "rcs.mnc000.mcc000.3gppnetwork.org";
    }

    private static String contactUser(SipDelegateConfiguration cfg, String fromTel) {
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

    static String randHex(int n) {
        final char[] hex = "0123456789abcdef".toCharArray();
        final StringBuilder sb = new StringBuilder(n);
        final Random r = new Random();
        for (int i = 0; i < n; i++) {
            sb.append(hex[r.nextInt(16)]);
        }
        return sb.toString();
    }

    /** CPIM From and To of a 1:1 session are anonymous; the real identities ride the SIP layer. */
    static final String CPIM_ANON = "sip:anonymous@anonymous.invalid";

    public static byte[] buildCpim(String fromTelUri, String toTelUri,
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
        c.append("\r\n");
        c.append("Content-Type: text/plain;charset=UTF-8\r\n");
        c.append("Content-Length: ").append(payload.length).append("\r\n");
        c.append("\r\n");
        final byte[] head = c.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    private static String rfc3339Now() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }
}
