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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the SIP signaling for a CPM 1-1 SESSION-mode chat over the
 * {@link android.telephony.ims.SipDelegateConnection}: INVITE (with the SDP
 * offer), ACK to the 200, and BYE. The SipDelegate-native analog of Google
 * Messages' INVITE/ACK factories — but emitting
 * {@link SipMessage} (startLine + header section + body) instead of a JAIN-SIP
 * message object, because the single-registration transport takes a
 * {@code SipMessage} and rewrites the routing headers (Route, real Via sent-by,
 * P-CSCF path) from the registered session.
 *
 * <p>Hard-won wire rules from the decisive 2026-06-02 run:
 * <ul>
 *   <li>The {@link SipMessage} ctor REQUIRES a {@code Via:} header with a
 *       {@code branch=} param — it is the transaction id.</li>
 *   <li>The request-URI + To MUST be a routable {@code sip:+E164@homeDomain;
 *       user=phone}; a bare {@code tel:+E164} serialized to a truncated
 *       start-line in the modem ("Start line is INVALID").</li>
 * </ul>
 *
 * <p>All requests in a dialog share one Call-ID and From-tag. The To-tag is
 * learned from the 200 OK and echoed on ACK/BYE. CSeq increments per request
 * in the dialog (INVITE=n, ACK reuses n, BYE=n+1, per RFC 3261 §12-13).
 */
public final class CpmSessionSipBuilder {

    /** UP 2.4 §A.1.4 — P-Preferred-Service ICSI (the human-readable colon form
     *  goes in P-Preferred-Service / P-Preferred-Service is not feature-tag
     *  matched). */
    static final String ICSI_CPM_SESSION =
            "urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session";
    /**
     * The icsi-ref URN in the URL-ENCODED form, which is what the framework's
     * {@code SipTransportController.verifyOutgoingMessage} matches the
     * Accept-Contact / Contact feature tag against — it must equal the
     * registered {@code DelegateRequest} tag verbatim
     * ({@code urn%3Aurn-7%3A...}). The colon form is REJECTED
     * (restrictedReason=6 "No Accept-Contact feature tags are in accepted
     * feature tag list") — pinned on device 2026-06-02.
     */
    static final String ICSI_CPM_SESSION_ENC =
            "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session";
    /** Feature tag exactly as registered (what the validator compares to). */
    static final String FEATURE_TAG_CPM_SESSION =
            "+g.3gpp.icsi-ref=\"" + ICSI_CPM_SESSION_ENC + "\"";
    static final String ACCEPT_CONTACT_CPM_SESSION =
            "*;" + FEATURE_TAG_CPM_SESSION + ";require;explicit";

    static final int SESSION_EXPIRES_SEC = 1800;
    static final int MIN_SE_SEC = 90;

    private CpmSessionSipBuilder() {}

    /** Immutable per-dialog identity for a CPM chat session. */
    public static final class Dialog {
        public final String homeDomain;
        public final String fromTel;        // +E164
        public final String toTel;          // +E164
        public final String contactUser;    // sip contact user param
        public final String fromSipUri;     // sip:contactUser@homeDomain
        public final String toSipUri;       // sip:+E164@homeDomain;user=phone
        public final String fromTelUri;     // tel:+E164
        public final String callId;
        public final String fromTag;
        public final String contributionId;
        public final String conversationId;
        // Network-provided headers from the registered SipDelegateConfiguration.
        // An originating out-of-dialog request MUST carry the Route set derived
        // from the registration Service-Route (3GPP TS 24.229) or the modem
        // refuses it (INVALID_START_LINE / dialog establishment failure).
        public final String serviceRoute;  // Route value
        public final String pathHeader;
        public final String pani;           // P-Access-Network-Info
        public final String plani;
        public final String cni;
        public final String securityVerify;
        public final String associatedUri;  // P-Associated-URI / our IMPU
        // Registered transport identity — the Via sent-by + Contact for an
        // app-delegate-originated request must reference OUR registered
        // address/GRUU, not the home domain (RFC 3261 §8.1.1.7 / §20.42).
        public final String viaSentBy;      // "<localIP>:<port>" or homeDomain
        public final String viaTransport;   // TLS | TCP | UDP
        public final String contactUri;     // GRUU if present, else sip:user@localIP:port
        public final String instanceId;     // +sip.instance value (urn:gsma:imei:...)
        public volatile String toTag;       // learned from the 200 OK
        public final AtomicInteger cseq = new AtomicInteger(1);

        Dialog(SipDelegateConfiguration cfg, String homeDomain, String fromTel,
                String toTel, String contactUser) {
            this.homeDomain = homeDomain;
            this.fromTel = fromTel;
            this.toTel = toTel;
            this.contactUser = contactUser;
            this.fromSipUri = "sip:" + contactUser + "@" + homeDomain;
            // Recipient request-URI form — selectable at runtime for wire
            // experimentation via `setprop persist.rcs.cpm.requri <form>`:
            //   sip-home (default) : sip:+E164@<homeDomain>;user=phone
            //   sip-ims            : sip:+E164@<ims.mnc/mcc network domain>;user=phone
            //   tel                : tel:+E164
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
            // Real registered transport identity for Via + Contact.
            this.viaTransport = transportName(cfg);
            this.viaSentBy = localSentBy(cfg, homeDomain);
            this.instanceId = instanceId(cfg);
            this.contactUri = contactUri(cfg, contactUser, this.viaSentBy);
        }
    }

    /** Registered transport ("TLS"/"TCP"/"UDP") for the Via protocol token. */
    private static String transportName(SipDelegateConfiguration cfg) {
        try {
            int t = cfg.getTransportType();
            // SipDelegateConfiguration.SIP_TRANSPORT_TCP=1 (default for SR);
            // UDP=0. RCS single-reg on TMo is SIP-over-TLS.
            return t == 0 ? "UDP" : "TLS";
        } catch (Throwable ignore) {
            return "TLS";
        }
    }

    /** The registered local IP[:port] for the Via sent-by; falls back to the
     *  home domain (the prior placeholder) if the config doesn't expose it. */
    private static String localSentBy(SipDelegateConfiguration cfg, String homeDomain) {
        try {
            java.net.InetSocketAddress a = cfg.getLocalAddress();
            if (a != null && a.getAddress() != null) {
                String ip = a.getAddress().getHostAddress();
                // IPv6 literals must be bracketed in a SIP host:port.
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

    /** +sip.instance value: prefer the registered IMEI URN. */
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

    /** Contact URI: the registered GRUU if present (RFC 5627), else
     *  sip:user@<localSentBy> (our registered transport address). */
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

    /** IMS network home domain derived from the SIM (mnc/mcc). */
    private static String imsNetworkDomain() {
        String mccmnc = sysprop("gsm.sim.operator.numeric");
        // Take slot-1's value if comma-separated.
        if (mccmnc != null && mccmnc.contains(",")) {
            String[] parts = mccmnc.split(",");
            mccmnc = parts.length > 1 ? parts[parts.length - 1] : parts[0];
        }
        if (mccmnc == null || mccmnc.length() < 5) {
            return "ims.mnc260.mcc310.3gppnetwork.org"; // TMo fallback
        }
        String mcc = mccmnc.substring(0, 3);
        String mnc = mccmnc.substring(3);
        if (mnc.length() == 2) mnc = "0" + mnc;
        return "ims.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    private static String buildRequestUri(String toTel, String homeDomain) {
        String form = sysprop("persist.rcs.cpm.requri");
        if ("tel".equals(form)) {
            return "tel:" + toTel;
        }
        if ("sip-ims".equals(form)) {
            return "sip:" + toTel + "@" + imsNetworkDomain() + ";user=phone";
        }
        // default sip-home
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

    /** A built SipMessage plus the branch (transaction id) the framework wants. */
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

    /** Human-readable dump of the network-provided headers (for diagnostics). */
    public static String dumpNetworkHeaders(Dialog d) {
        return "serviceRoute=" + d.serviceRoute
                + " | path=" + d.pathHeader
                + " | pani=" + d.pani
                + " | plani=" + d.plani
                + " | cni=" + d.cni
                + " | securityVerify=" + d.securityVerify
                + " | associatedUri=" + d.associatedUri;
    }

    // ------------------------------------------------------------------
    // INVITE — carries the SDP offer (application/sdp).
    // ------------------------------------------------------------------
    public static Built buildInvite(Dialog d, byte[] sdpOffer) {
        final int cseq = d.cseq.getAndIncrement();
        final String branch = newBranch();
        // DECISIVE WIRE RULE (2026-06-02): the framework's canonical
        // SipMessage.toEncodedMessage() is startLine + headerSection + CRLF +
        // body — it does NOT insert a CRLF between the start line and the
        // header section, and Shannon's SipDelegateAdaptor.getEncodedMessage()
        // glues them the same way. The start line therefore MUST carry its own
        // trailing CRLF, or the modem sees "INVITE ... SIP/2.0Via: ..." as one
        // malformed start line and rejects with mStatusCode=3
        // ("Start line is INVALID" / dialog establishment failure). Verified on
        // device: mStartLine field = 62B (our line, no CRLF) glued straight to
        // the Via header.
        final String startLine = "INVITE " + d.toSipUri + " SIP/2.0\r\n";

        final StringBuilder h = new StringBuilder();
        via(h, d, branch);
        h.append("From: <").append(d.fromSipUri).append(">;tag=").append(d.fromTag).append("\r\n");
        h.append("To: <").append(d.toSipUri).append(">\r\n");
        h.append("Call-ID: ").append(d.callId).append("\r\n");
        h.append("CSeq: ").append(cseq).append(" INVITE\r\n");
        h.append("Max-Forwards: 70\r\n");
        // Route set from the registration Service-Route — REQUIRED for an
        // originating out-of-dialog request to route through the P-CSCF
        // (3GPP TS 24.229 §5.1.2A). Without it the modem rejects the request
        // (INVALID_START_LINE / dialog establishment failure).
        if (notEmpty(d.serviceRoute)) {
            h.append("Route: ").append(d.serviceRoute).append("\r\n");
        }
        // Contact must carry the CPM session feature tags so the peer/relay
        // routes the in-dialog MSRP setup back to us, as Google Messages does.
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
        // Network-provided headers (the modem expects originating requests to
        // echo the registration's access-network info + security association).
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

    // ------------------------------------------------------------------
    // ACK — in-dialog, reuses the INVITE CSeq number (RFC 3261 §13.2.2.4),
    // echoes the learned To-tag, request-URI = the dialog remote target.
    // ------------------------------------------------------------------
    public static Built buildAck(Dialog d, int inviteCseq) {
        final String branch = newBranch();
        // Start line MUST end in CRLF (framework toEncodedMessage contract).
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

    // ------------------------------------------------------------------
    // BYE — in-dialog teardown, fresh CSeq.
    // ------------------------------------------------------------------
    public static Built buildBye(Dialog d) {
        final int cseq = d.cseq.getAndIncrement();
        final String branch = newBranch();
        // Start line MUST end in CRLF (framework toEncodedMessage contract).
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

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void via(StringBuilder h, Dialog d, String branch) {
        // Via sent-by = OUR registered local transport address (RFC 3261
        // §8.1.1.7). The single-reg modem owns the actual security association,
        // but the Via must still reference our registered identity, not the
        // home domain. branch= is the mandatory transaction id.
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

    /** Parse the To-tag from a received 200 OK header section. */
    public static String parseToTag(String headerSection) {
        if (headerSection == null) return null;
        // Find a To:/t: header line, then ;tag=...
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

    /** Extract the status code from a SipMessage start line ("SIP/2.0 200 OK"). */
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

    /** The CSeq method on a request start line ("INVITE sip:... SIP/2.0" → INVITE). */
    public static String parseMethod(String startLine) {
        if (startLine == null) return null;
        String s = startLine.trim();
        int sp = s.indexOf(' ');
        return sp < 0 ? null : s.substring(0, sp);
    }

    // ------------------------------------------------------------------
    // Inbound (UAS) side — parse a received request + build a SIP response.
    // Used by CpmIncomingSessionEngine: the SipDelegate hands us peer-originated
    // INVITE/BYE requests on our registered CPM tags and we must answer them
    // (the app owns the CPM signalling; the framework only routes it).
    // ------------------------------------------------------------------

    /** The load-bearing fields parsed out of an inbound SIP request so a
     *  response can echo them (RFC 3261 §8.2.6.2: a response copies Via, From,
     *  Call-ID, CSeq, and To verbatim, adding a To-tag). */
    public static final class IncomingRequest {
        public final String method;
        public final java.util.List<String> viaHeaders;  // every Via, in order
        public final String from;         // full From header value (incl. tag)
        public final String to;           // full To header value (no tag yet)
        public final String callId;
        public final String cseq;         // "<n> <METHOD>"
        public final String fromTelUri;   // tel:+E164 of the peer (best-effort)
        public final String fromE164;     // +E164 of the peer (best-effort)
        public final byte[] body;         // SDP offer (for INVITE)

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

    /** Parse an inbound request SipMessage into the fields needed to answer it. */
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
     * Build a SIP response to an inbound request. Echoes every Via, From,
     * Call-ID and CSeq verbatim; adds a To-tag (per RFC 3261 §8.2.6.2) and, for
     * a 2xx to an INVITE, our Contact + the SDP answer body. Emits the framework
     * {@link SipMessage} (startLine ends CRLF — the same decisive wire rule as
     * the request builders).
     *
     * @param req         the parsed inbound request.
     * @param statusCode  e.g. 200, 486, 603.
     * @param reason      reason phrase, e.g. "OK", "Decline".
     * @param toTag       our locally-minted To-tag (dialog-establishing).
     * @param contactUri  our Contact (null to omit — appropriate for a final
     *                    non-2xx like 603).
     * @param body        response body (SDP answer for a 200 to INVITE); null/empty
     *                    for a bare status.
     * @param contentType body MIME (e.g. "application/sdp"); ignored when body empty.
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
        // To gets our tag appended (unless the request already carried one).
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

    /** Extract the first {@code <uri>} (or bare uri) from a name-addr header. */
    private static String extractUri(String headerValue) {
        if (headerValue == null) return null;
        int lt = headerValue.indexOf('<');
        if (lt >= 0) {
            int gt = headerValue.indexOf('>', lt);
            if (gt > lt) {
                return headerValue.substring(lt + 1, gt).trim();
            }
        }
        // bare uri form: take up to the first ';' (params) or ','
        int semi = headerValue.indexOf(';');
        String s = semi >= 0 ? headerValue.substring(0, semi) : headerValue;
        return s.trim();
    }

    /** Best-effort +E164 out of a {@code tel:+…} or {@code sip:+…@…} URI. */
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
        // Keep only leading '+' and digits.
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

    /** CPIM body builder for the MSRP SEND payload (text/plain wrapped). For a
     *  1:1 session Google Messages' CPIM From/To are both anonymized
     *  ({@code sip:anonymous@anonymous.invalid}); the real identities are in the
     *  SIP layer. */
    static final String CPIM_ANON = "sip:anonymous@anonymous.invalid";

    public static byte[] buildCpim(String fromTelUri, String toTelUri,
            String imdnMessageId, String text) {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        // Header order per Google Messages: NS first, then namespaced headers.
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
