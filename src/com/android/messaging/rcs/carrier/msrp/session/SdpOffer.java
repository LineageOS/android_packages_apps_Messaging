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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable in-memory model of a single-media-line SDP body for an MSRP
 * chat session per RFC 4566 (SDP) + GSMA UP 2.4 §A.1.3.5 (MSRP profile) +
 * RFC 4975 §8 (MSRP a-lines) + RFC 4572 (cert fingerprints over SDP).
 *
 * <p>This class models BOTH the offer (sent in the INVITE body) and the
 * answer (returned in the 200 OK to that INVITE). The two are structurally
 * identical at the SDP level — the difference is only role-coupling
 * ({@code a=setup:active} ↔ {@code a=setup:passive}, {@code a=path:} pointing
 * at each endpoint's own MSRP URI). RFC 3264 §6 offer/answer rules apply at
 * the protocol layer but do not change the body's grammar.
 *
 * <p>What we model:
 *
 * <pre>
 *   v=0
 *   o=- {sessionId} {sessionVersion} IN IP{4|6} {host}
 *   s=-
 *   c=IN IP{4|6} {host}
 *   t=0 0
 *   m=message {port} TCP/TLS/MSRP *
 *   a=path:msrps://{host}:{port}/{msrp-session-id};tcp
 *   a=setup:{active|passive|actpass|holdconn}      ; RFC 6135
 *   a=connection:new                               ; RFC 6135
 *   a=msrp-cema                                    ; RFC 6135
 *   a=fingerprint:SHA-256 {hex-colon-cert-hash}    ; RFC 4572
 *   a=accept-types:{space-separated types}         ; RFC 4975 §8.6
 *   a=accept-wrapped-types:{space-separated types} ; RFC 4975 §8.6
 *   a=sendrecv                                     ; RFC 4566 §6
 * </pre>
 *
 * <p>What we deliberately do NOT model (out of scope for now, and tracked separately):
 *
 * <ul>
 *   <li>Multiple m-lines (ICE, RTP audio/video, FT alongside chat).
 *       MSRP chat-only sessions carry a single {@code m=message} line.</li>
 *   <li>ICE / candidate / rtcp-mux a-lines.</li>
 *   <li>{@code m=image} for IM-FT (deferred).</li>
 *   <li>Multipart SDP with embedded CPIM (legacy store-and-forward).</li>
 * </ul>
 *
 * <p>Pure Java, no Android / JAIN-SIP / org.json. Host-testable. Cross-refs:
 * RFC 4566; RFC 4975 §8; RFC 4572; RFC 6135.
 */
public final class SdpOffer {

    /** SDP MUST be UTF-8 per RFC 4566 §6 ("Lines of text"). */
    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /** RFC 4566 §5 mandates CRLF line endings; we tolerate bare LF on parse. */
    public static final String CRLF = "\r\n";

    /** GSMA UP 2.4 §A.1.3.5: transport proto token in the m= line. */
    public static final String MSRP_PROTO_TLS = "TCP/TLS/MSRP";

    /** RFC 4975 §8: transport proto token in the m= line, non-TLS. */
    public static final String MSRP_PROTO_TCP = "TCP/MSRP";

    /** Default fingerprint hash function per RFC 4572 §5. */
    public static final String FINGERPRINT_ALG_SHA256 = "SHA-256";

    /** {@code a=setup:} role values per RFC 6135 / RFC 4145 §4. */
    public enum SetupRole {
        ACTIVE("active"),
        PASSIVE("passive"),
        ACTPASS("actpass"),
        HOLDCONN("holdconn");

        public final String token;
        SetupRole(String t) { this.token = t; }

        public static SetupRole parse(String tok) {
            if (tok == null) return null;
            String t = tok.toLowerCase(Locale.ROOT).trim();
            for (SetupRole r : values()) {
                if (r.token.equals(t)) return r;
            }
            return null;
        }
    }

    /** {@code a=sendrecv} / {@code a=sendonly} / etc. RFC 4566 §6. */
    public enum Direction {
        SENDRECV("sendrecv"),
        SENDONLY("sendonly"),
        RECVONLY("recvonly"),
        INACTIVE("inactive");

        public final String token;
        Direction(String t) { this.token = t; }

        public static Direction parse(String tok) {
            if (tok == null) return null;
            String t = tok.toLowerCase(Locale.ROOT).trim();
            for (Direction d : values()) {
                if (d.token.equals(t)) return d;
            }
            return null;
        }
    }

    // -------- mandatory fields --------

    /** o= username; RFC 4566 §5.2. We default to "-", as Google Messages does. */
    private final String origUser;
    /** o= session id. */
    private final String sessionId;
    /** o= session version (incremented on re-INVITE per RFC 3264 §8). */
    private final long sessionVersion;
    /** "IP4" or "IP6"; used on c= and o=. */
    private final String addrType;
    /** Host/IP on c= and o=. */
    private final String host;
    /** Local listening port for MSRP socket. */
    private final int port;
    /** Transport proto token. {@link #MSRP_PROTO_TLS} or {@link #MSRP_PROTO_TCP}. */
    private final String mediaProto;
    /** {@code a=path:} value verbatim. e.g. {@code msrps://1.2.3.4:5678/abcd;tcp}. */
    private final String msrpPath;
    /** {@code a=setup:} role. */
    private final SetupRole setup;
    /** Optional fingerprint hash alg ("SHA-256"); null if no fingerprint. */
    private final String fingerprintAlg;
    /** Optional fingerprint hex (lowercase colon-separated, e.g. "ab:cd:..."); null if none. */
    private final String fingerprintHex;
    /** Whether {@code a=msrp-cema} was emitted (RFC 6135 connection establishment for media anchoring). */
    private final boolean msrpCema;
    /** Whether {@code a=connection:new} was emitted (RFC 6135). */
    private final boolean connectionNew;
    /** Direction attribute; defaults to SENDRECV. */
    private final Direction direction;
    /** RFC 4975 §8.6 accept-types. Insertion order preserved. */
    private final List<String> acceptTypes;
    /** RFC 4975 §8.6 accept-wrapped-types. Insertion order preserved. */
    private final List<String> acceptWrappedTypes;
    /** RFC 4566 §5.10 extra session-level a= attributes (other than the well-known ones above). */
    private final LinkedHashMap<String, String> extraAttributes;

    private SdpOffer(Builder b) {
        this.origUser = b.origUser;
        this.sessionId = b.sessionId;
        this.sessionVersion = b.sessionVersion;
        this.addrType = b.addrType;
        this.host = b.host;
        this.port = b.port;
        this.mediaProto = b.mediaProto;
        this.msrpPath = b.msrpPath;
        this.setup = b.setup;
        this.fingerprintAlg = b.fingerprintAlg;
        this.fingerprintHex = b.fingerprintHex;
        this.msrpCema = b.msrpCema;
        this.connectionNew = b.connectionNew;
        this.direction = b.direction;
        this.acceptTypes = Collections.unmodifiableList(new ArrayList<>(b.acceptTypes));
        this.acceptWrappedTypes = Collections.unmodifiableList(
                new ArrayList<>(b.acceptWrappedTypes));
        this.extraAttributes = new LinkedHashMap<>(b.extraAttributes);
    }

    // -------- accessors --------

    public String getOrigUser()              { return origUser; }
    public String getSessionId()             { return sessionId; }
    public long   getSessionVersion()        { return sessionVersion; }
    public String getAddrType()              { return addrType; }
    public String getHost()                  { return host; }
    public int    getPort()                  { return port; }
    public String getMediaProto()            { return mediaProto; }
    public String getMsrpPath()              { return msrpPath; }
    public SetupRole getSetup()              { return setup; }
    public String getFingerprintAlg()        { return fingerprintAlg; }
    public String getFingerprintHex()        { return fingerprintHex; }
    public boolean hasMsrpCema()             { return msrpCema; }
    public boolean hasConnectionNew()        { return connectionNew; }
    public Direction getDirection()          { return direction; }
    public List<String> getAcceptTypes()     { return acceptTypes; }
    public List<String> getAcceptWrappedTypes() { return acceptWrappedTypes; }
    public Map<String, String> getExtraAttributes() {
        return Collections.unmodifiableMap(extraAttributes);
    }

    /** True iff {@code a=fingerprint:} is present (i.e. peer is binding TLS auth via SDP, RFC 4572). */
    public boolean hasFingerprint() {
        return fingerprintAlg != null && fingerprintHex != null;
    }

    /** True iff the media line uses TLS ({@code TCP/TLS/MSRP}). */
    public boolean isMsrpTls() {
        return MSRP_PROTO_TLS.equalsIgnoreCase(mediaProto);
    }

    // -------- encode --------

    /**
     * Serialize the SDP body to wire bytes per RFC 4566 §5. Line endings are
     * CRLF. The output goes straight into a SIP INVITE / 200-OK
     * {@code application/sdp} body.
     *
     * <p>Line order matches RFC 4566 §5 and Google Messages' emit
     * order: {@code v / o / s / c / t / m /
     * a-attributes...}. The {@code a-} attributes are ordered as they appear
     * in Google Messages' SDP for byte-level diff-ability against captured traces:
     * {@code path → setup → connection → msrp-cema → fingerprint →
     * accept-types → accept-wrapped-types → direction → extras}.
     */
    public byte[] encode() {
        StringBuilder sb = new StringBuilder(256);
        // Protocol / session description (RFC 4566 §5.1-5.3)
        line(sb, "v=0");
        line(sb, "o=" + nonNull(origUser, "-") + " " + numericSessId() + " "
                + sessionVersion + " IN " + addrType + " " + host);
        line(sb, "s=-");
        // Connection / timing
        line(sb, "c=IN " + addrType + " " + host);
        line(sb, "t=0 0");
        // Media line (RFC 4566 §5.14)
        line(sb, "m=message " + port + " " + mediaProto + " *");
        // a-attributes
        if (msrpPath != null) {
            line(sb, "a=path:" + msrpPath);
        }
        if (setup != null) {
            line(sb, "a=setup:" + setup.token);
        }
        if (connectionNew) {
            line(sb, "a=connection:new");
        }
        if (msrpCema) {
            line(sb, "a=msrp-cema");
        }
        if (hasFingerprint()) {
            line(sb, "a=fingerprint:" + fingerprintAlg + " " + fingerprintHex);
        }
        if (!acceptTypes.isEmpty()) {
            line(sb, "a=accept-types:" + joinSpaces(acceptTypes));
        }
        if (!acceptWrappedTypes.isEmpty()) {
            line(sb, "a=accept-wrapped-types:" + joinSpaces(acceptWrappedTypes));
        }
        if (direction != null) {
            line(sb, "a=" + direction.token);
        }
        for (Map.Entry<String, String> e : extraAttributes.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) {
                line(sb, "a=" + e.getKey());
            } else {
                line(sb, "a=" + e.getKey() + ":" + v);
            }
        }
        return sb.toString().getBytes(UTF_8);
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append(CRLF);
    }

    /**
     * RFC 4566 §5.2: {@code <sess-id>} is a numeric string. Our MSRP session-id
     * ({@link #sessionId}) is a 32-char hex token (used verbatim in the {@code
     * a=path} MSRP URI, where hex is correct). Emitting that hex in the {@code
     * o=} line makes strict SDP parsers (PJMEDIA: {@code PJMEDIA_SDP_EINSDP})
     * reject the descriptor — intermittently, depending on whether the random
     * hex happens to start with a digit or a letter — which surfaced as a 400
     * on our INVITE offer and a cause=406 BYE on our 200-OK answer (open5gs AS,
     * 2026-07-19). So derive a stable positive-numeric sess-id for {@code o=}
     * here; the hex id stays in {@code a=path}.
     */
    private String numericSessId() {
        if (sessionId == null || sessionId.isEmpty()) {
            return "0";
        }
        boolean allDigits = true;
        for (int i = 0; i < sessionId.length(); i++) {
            if (!Character.isDigit(sessionId.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            // Already numeric (e.g. a sess-id parsed off the wire) — keep it.
            return sessionId;
        }
        // Deterministically fold the token into a positive 63-bit long.
        long v = 0;
        for (int i = 0; i < sessionId.length(); i++) {
            v = v * 31 + sessionId.charAt(i);
        }
        return Long.toString(v & 0x7fffffffffffffffL);
    }

    private static String joinSpaces(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String nonNull(String s, String fallback) {
        return s == null ? fallback : s;
    }

    // -------- parse --------

    /**
     * Parse an SDP body per RFC 4566 §5. Tolerant: accepts bare LF in
     * addition to CRLF (RFC 4566 §6 actually mandates CRLF but real peers
     * are sloppy and we mirror Google Messages' parser, which accepts
     * both).
     *
     * <p>Unknown a-lines are preserved in {@link #getExtraAttributes()};
     * unknown b-/i-/u-/e-/p-/k-/z-/r-/m-extension lines are silently
     * dropped. We do enforce that the body has a v= line and exactly one
     * m=message line (multi-media SDP is rejected — out of scope for chat).
     */
    public static SdpOffer parse(byte[] bytes) throws SdpParseException {
        if (bytes == null) {
            throw new SdpParseException("SDP parse: null buffer");
        }
        return parse(new String(bytes, UTF_8));
    }

    public static SdpOffer parse(String text) throws SdpParseException {
        if (text == null) {
            throw new SdpParseException("SDP parse: null text");
        }
        Builder b = new Builder();
        String[] lines = splitLines(text);
        boolean sawV = false;
        boolean sawM = false;
        int mCount = 0;
        for (String raw : lines) {
            if (raw.isEmpty()) continue;
            int eq = raw.indexOf('=');
            if (eq != 1) {
                // RFC 4566 requires "<letter>=<value>". Skip non-conformant
                // lines tolerantly; Google Messages does the same.
                continue;
            }
            char type = raw.charAt(0);
            String value = raw.substring(2);
            switch (type) {
                case 'v':
                    if (!"0".equals(value.trim())) {
                        throw new SdpParseException("SDP parse: v= must be 0, got " + value);
                    }
                    sawV = true;
                    break;
                case 'o':
                    parseOriginLine(value, b);
                    break;
                case 's':
                    /* session name — ignored */
                    break;
                case 'c':
                    parseConnectionLine(value, b);
                    break;
                case 't':
                    /* time — ignored (we always emit t=0 0) */
                    break;
                case 'm':
                    if (++mCount > 1) {
                        throw new SdpParseException(
                                "SDP parse: multiple m= lines unsupported (chat-only)");
                    }
                    parseMediaLine(value, b);
                    sawM = true;
                    break;
                case 'a':
                    parseAttributeLine(value, b);
                    break;
                default:
                    // i, u, e, p, b, z, r, k — ignored
            }
        }
        if (!sawV) {
            throw new SdpParseException("SDP parse: missing v= line");
        }
        if (!sawM) {
            throw new SdpParseException("SDP parse: missing m=message line");
        }
        // Sensible defaults if not specified
        if (b.direction == null) {
            b.direction = Direction.SENDRECV;
        }
        return b.build();
    }

    private static String[] splitLines(String text) {
        // Accept CRLF and bare LF/CR.
        return text.split("\r\n|\n|\r");
    }

    private static void parseOriginLine(String value, Builder b) throws SdpParseException {
        // o=<user> <sess-id> <sess-version> <nettype> <addrtype> <unicast-address>
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 6) {
            throw new SdpParseException("SDP parse: malformed o= line: " + value);
        }
        b.origUser = parts[0];
        b.sessionId = parts[1];
        try {
            b.sessionVersion = Long.parseLong(parts[2]);
        } catch (NumberFormatException nfe) {
            throw new SdpParseException("SDP parse: bad o= version: " + parts[2], nfe);
        }
        // parts[3] = nettype, must be "IN" per RFC 4566 §5.2
        if (!"IN".equalsIgnoreCase(parts[3])) {
            throw new SdpParseException("SDP parse: unsupported nettype: " + parts[3]);
        }
        b.addrType = parts[4]; // IP4 / IP6
        b.host = parts[5];
    }

    private static void parseConnectionLine(String value, Builder b) throws SdpParseException {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 3) {
            throw new SdpParseException("SDP parse: malformed c= line: " + value);
        }
        if (!"IN".equalsIgnoreCase(parts[0])) {
            throw new SdpParseException("SDP parse: unsupported c= nettype: " + parts[0]);
        }
        // c= may override the o= host (RFC 4566 §5.7); we trust c= for routing.
        b.addrType = parts[1];
        b.host = parts[2];
    }

    private static void parseMediaLine(String value, Builder b) throws SdpParseException {
        // m=<media> <port> <proto> <fmt>
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 4) {
            throw new SdpParseException("SDP parse: malformed m= line: " + value);
        }
        if (!"message".equalsIgnoreCase(parts[0])) {
            throw new SdpParseException("SDP parse: only m=message supported, got " + parts[0]);
        }
        try {
            b.port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException nfe) {
            throw new SdpParseException("SDP parse: bad m= port: " + parts[1], nfe);
        }
        b.mediaProto = parts[2];
        // parts[3..] = format list. For MSRP this is "*" per RFC 4975 §8.4.
        // We don't enforce that — peers may emit anything; we just preserve
        // the proto.
    }

    private static void parseAttributeLine(String value, Builder b) {
        // value is everything after "a=".
        int colon = value.indexOf(':');
        String key;
        String v;
        if (colon < 0) {
            key = value.trim().toLowerCase(Locale.ROOT);
            v = "";
        } else {
            key = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            v = value.substring(colon + 1).trim();
        }
        switch (key) {
            case "path":
                b.msrpPath = v;
                break;
            case "setup": {
                SetupRole r = SetupRole.parse(v);
                if (r != null) b.setup = r;
                break;
            }
            case "connection":
                if ("new".equalsIgnoreCase(v)) b.connectionNew = true;
                break;
            case "msrp-cema":
                b.msrpCema = true;
                break;
            case "fingerprint": {
                // value: "<alg> <hex>"
                int sp = v.indexOf(' ');
                if (sp > 0) {
                    b.fingerprintAlg = v.substring(0, sp).trim();
                    b.fingerprintHex = v.substring(sp + 1).trim();
                }
                break;
            }
            case "accept-types":
                b.acceptTypes.clear();
                for (String t : v.split("\\s+")) {
                    if (!t.isEmpty()) b.acceptTypes.add(t);
                }
                break;
            case "accept-wrapped-types":
                b.acceptWrappedTypes.clear();
                for (String t : v.split("\\s+")) {
                    if (!t.isEmpty()) b.acceptWrappedTypes.add(t);
                }
                break;
            case "sendrecv":
                b.direction = Direction.SENDRECV;
                break;
            case "sendonly":
                b.direction = Direction.SENDONLY;
                break;
            case "recvonly":
                b.direction = Direction.RECVONLY;
                break;
            case "inactive":
                b.direction = Direction.INACTIVE;
                break;
            default:
                b.extraAttributes.put(key, v);
        }
    }

    // -------- fingerprint validation --------

    /**
     * Validate {@code peerCertFingerprintHex} (lowercase colon-separated,
     * RFC 4572 §5 form) against the {@code a=fingerprint:} attribute in
     * this SDP. Used after TLS handshake completes: hash the server-presented
     * cert with the algorithm we advertised, format identically, compare
     * case-insensitively.
     *
     * <p>This is the spec-compliant MSRP-over-TLS auth path per RFC 4572.
     * Hostname verification is intentionally NOT used (RCS MSRP peer URIs
     * use opaque random session-ids on private hosts; hostname verification
     * would fail).
     *
     * @param peerCertFingerprintHex hex string in either colon-separated or
     *                               un-separated form; case-insensitive
     * @return true iff the supplied fingerprint matches
     */
    public boolean fingerprintMatches(String peerCertFingerprintHex) {
        if (!hasFingerprint() || peerCertFingerprintHex == null) return false;
        String a = stripFingerprint(fingerprintHex);
        String b = stripFingerprint(peerCertFingerprintHex);
        return a.equalsIgnoreCase(b);
    }

    private static String stripFingerprint(String s) {
        // Remove colons and whitespace for comparison.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && !Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Format a raw byte[] cert hash as the colon-separated lowercase hex
     * form RFC 4572 §5 mandates ({@code "ab:cd:ef:..."}).
     */
    public static String formatFingerprint(byte[] hash) {
        if (hash == null) return null;
        StringBuilder sb = new StringBuilder(hash.length * 3);
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) sb.append(':');
            int v = hash[i] & 0xFF;
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    // -------- toString --------

    @Override
    public String toString() {
        return "SdpOffer{" + host + ":" + port + " proto=" + mediaProto
                + " path=" + msrpPath + " setup=" + setup
                + " fp=" + (hasFingerprint() ? fingerprintAlg : "none")
                + " accept=" + acceptTypes
                + " wrapped=" + acceptWrappedTypes
                + "}";
    }

    // -------- Builder --------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String origUser = "-";
        private String sessionId;
        private long sessionVersion;
        private String addrType = "IP4";
        private String host;
        private int port;
        private String mediaProto = MSRP_PROTO_TLS;
        private String msrpPath;
        private SetupRole setup;
        private String fingerprintAlg;
        private String fingerprintHex;
        private boolean msrpCema;
        private boolean connectionNew;
        private Direction direction = Direction.SENDRECV;
        private final List<String> acceptTypes = new ArrayList<>();
        private final List<String> acceptWrappedTypes = new ArrayList<>();
        private final LinkedHashMap<String, String> extraAttributes = new LinkedHashMap<>();

        public Builder origUser(String s)         { this.origUser = s; return this; }
        public Builder sessionId(String s)        { this.sessionId = s; return this; }
        public Builder sessionVersion(long v)     { this.sessionVersion = v; return this; }
        public Builder ipv4()                     { this.addrType = "IP4"; return this; }
        public Builder ipv6()                     { this.addrType = "IP6"; return this; }
        public Builder addrType(String t)         { this.addrType = t; return this; }
        public Builder host(String h)             { this.host = h; return this; }
        public Builder port(int p)                { this.port = p; return this; }
        public Builder mediaProto(String p)       { this.mediaProto = p; return this; }
        public Builder msrpPath(String p)         { this.msrpPath = p; return this; }
        public Builder setup(SetupRole r)         { this.setup = r; return this; }
        public Builder msrpCema(boolean v)        { this.msrpCema = v; return this; }
        public Builder connectionNew(boolean v)   { this.connectionNew = v; return this; }
        public Builder direction(Direction d)     { this.direction = d; return this; }

        /** Set the SHA-256 fingerprint a la RFC 4572 §5. */
        public Builder fingerprint(String alg, String hex) {
            this.fingerprintAlg = alg;
            this.fingerprintHex = hex;
            return this;
        }

        public Builder addAcceptType(String t) {
            if (t != null && !t.isEmpty()) acceptTypes.add(t);
            return this;
        }

        public Builder acceptTypes(List<String> ts) {
            acceptTypes.clear();
            if (ts != null) {
                for (String t : ts) if (t != null && !t.isEmpty()) acceptTypes.add(t);
            }
            return this;
        }

        public Builder addAcceptWrappedType(String t) {
            if (t != null && !t.isEmpty()) acceptWrappedTypes.add(t);
            return this;
        }

        public Builder acceptWrappedTypes(List<String> ts) {
            acceptWrappedTypes.clear();
            if (ts != null) {
                for (String t : ts) if (t != null && !t.isEmpty()) acceptWrappedTypes.add(t);
            }
            return this;
        }

        public Builder extraAttribute(String key, String value) {
            this.extraAttributes.put(key.toLowerCase(Locale.ROOT),
                    value == null ? "" : value);
            return this;
        }

        public SdpOffer build() {
            if (sessionId == null || sessionId.isEmpty()) {
                throw new IllegalStateException("SDP sessionId required");
            }
            if (host == null || host.isEmpty()) {
                throw new IllegalStateException("SDP host required");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalStateException("SDP port out of range: " + port);
            }
            if (mediaProto == null || mediaProto.isEmpty()) {
                throw new IllegalStateException("SDP mediaProto required");
            }
            // accept-types is mandatory per UP 2.4 §A.1.3.5 for MSRP chat —
            // but we don't reject empty lists here so the builder can be used
            // both for construction (where we want to fail fast) and for
            // parsing (where peer may emit wonky SDP we still want to capture).
            return new SdpOffer(this);
        }
    }

    // -------- canned profiles --------

    /**
     * UP 2.4 §A.1.3.5 accept-types for 1:1 chat. Order matches Google Messages' emit.
     */
    public static List<String> upChatAcceptTypes() {
        List<String> l = new ArrayList<>();
        l.add("message/cpim");
        l.add("application/im-iscomposing+xml");
        return Collections.unmodifiableList(l);
    }

    /**
     * UP 2.4 §A.1.3.5 + Etouffee accept-wrapped-types for 1:1 chat. Order
     * matches Google Messages' emit. {@code application/vnd.google.rcs.encrypted}
     * appears because Google Messages wraps Etouffee E2EE ciphertext in CPIM with
     * that inner content-type.
     */
    public static List<String> upChatAcceptWrappedTypes() {
        List<String> l = new ArrayList<>();
        l.add("text/plain");
        l.add("application/vnd.gsma.rcs-ft-http+xml");
        l.add("application/vnd.google.rcs.encrypted");
        // MLS E2EE plane: both the RCC.16 §7.9 and Google Messages-Google inner
        // content-types, so a peer negotiates MLS ciphertext + control over this MSRP session.
        l.add("message/mls");
        l.add("message/mls-rcs-client");
        l.add("message/mls-rcs-server");
        l.add("message/imdn+xml");
        l.add("application/vnd.oma.cpm-groupdata+xml");
        return Collections.unmodifiableList(l);
    }
}
