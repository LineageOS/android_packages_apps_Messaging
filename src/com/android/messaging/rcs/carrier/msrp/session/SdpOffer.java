/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A single-media-line SDP body for an MSRP chat session (RFC 4566, RFC 4975 §8, RFC 4572,
 * RFC 6135, GSMA UP 2.4 §A.1.3.5), used for both offer and answer since the grammar is the same:
 * {@code v o s c t}, one {@code m=message} line, then {@code a=path}, {@code setup},
 * {@code connection}, {@code msrp-cema}, {@code fingerprint}, {@code accept-types},
 * {@code accept-wrapped-types} and the direction. Several media lines, ICE and {@code m=image}
 * are not supported.
 */
public final class SdpOffer {

    /** RFC 4566 §6. */
    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /** RFC 4566 §5; bare LF is accepted on parse. */
    public static final String CRLF = "\r\n";

    /** GSMA UP 2.4 §A.1.3.5. */
    public static final String MSRP_PROTO_TLS = "TCP/TLS/MSRP";

    /** RFC 4975 §8, without TLS. */
    public static final String MSRP_PROTO_TCP = "TCP/MSRP";

    /** RFC 4572 §5. */
    public static final String FINGERPRINT_ALG_SHA256 = "SHA-256";

    /** RFC 6135, RFC 4145 §4. */
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

    /** RFC 4566 §6. */
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

    /** {@code o=} user name (RFC 4566 §5.2), {@code -} by default. */
    private final String origUser;
    /** {@code o=} session id; also the MSRP session id in the path, and may be hex. */
    private final String sessionId;
    /** Incremented on each new offer in the session (RFC 3264 §8). */
    private final long sessionVersion;
    /** "IP4" or "IP6", for {@code c=} and {@code o=}. */
    private final String addrType;
    private final String host;
    /** The MSRP port on the {@code m=} line. */
    private final int port;
    /** {@link #MSRP_PROTO_TLS} or {@link #MSRP_PROTO_TCP}. */
    private final String mediaProto;
    /** Verbatim, e.g. {@code msrps://host:port/id;tcp}. */
    private final String msrpPath;
    private final SetupRole setup;
    /** Null without a fingerprint. */
    private final String fingerprintAlg;
    /** Lower-case colon-separated hex, or null. */
    private final String fingerprintHex;
    private final boolean msrpCema;
    private final boolean connectionNew;
    private final Direction direction;
    /** RFC 4975 §8.6, in order. */
    private final List<String> acceptTypes;
    /** RFC 4975 §8.6, in order. */
    private final List<String> acceptWrappedTypes;
    /** Other {@code a=} attributes, in order. */
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

    public boolean hasFingerprint() {
        return fingerprintAlg != null && fingerprintHex != null;
    }

    public boolean isMsrpTls() {
        return MSRP_PROTO_TLS.equalsIgnoreCase(mediaProto);
    }

    /** RFC 4566 §5 lines with CRLF, attributes in the order listed on the class. */
    public byte[] encode() {
        StringBuilder sb = new StringBuilder(256);
        line(sb, "v=0");
        line(sb, "o=" + nonNull(origUser, "-") + " " + numericSessId() + " "
                + sessionVersion + " IN " + addrType + " " + host);
        line(sb, "s=-");
        line(sb, "c=IN " + addrType + " " + host);
        line(sb, "t=0 0");
        line(sb, "m=message " + port + " " + mediaProto + " *");
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
     * RFC 4566 §5.2: the {@code o=} sess-id is numeric, and strict parsers reject a hex one. A
     * non-numeric session id is folded into a stable positive number here; the path keeps the hex.
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
            // already numeric, as when parsed off the wire
            return sessionId;
        }
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

    /**
     * Parses an SDP body (RFC 4566 §5), accepting bare LF. Unknown attributes land in
     * {@link #getExtraAttributes()}, other unknown lines are dropped, and a body without {@code v=}
     * or with more than one media line is rejected.
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
                // Not "<letter>=<value>": skipped.
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
                    /* ignored */
                    break;
                case 'c':
                    parseConnectionLine(value, b);
                    break;
                case 't':
                    /* ignored; t=0 0 is always written */
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
                    // i, u, e, p, b, z, r, k: ignored
            }
        }
        if (!sawV) {
            throw new SdpParseException("SDP parse: missing v= line");
        }
        if (!sawM) {
            throw new SdpParseException("SDP parse: missing m=message line");
        }
        if (b.direction == null) {
            b.direction = Direction.SENDRECV;
        }
        return b.build();
    }

    private static String[] splitLines(String text) {
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
        if (!"IN".equalsIgnoreCase(parts[3])) {
            throw new SdpParseException("SDP parse: unsupported nettype: " + parts[3]);
        }
        b.addrType = parts[4];
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
        // c= overrides the o= address (RFC 4566 §5.7) and is what we connect to.
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
        // The format list, "*" for MSRP (RFC 4975 §8.4), is not checked.
    }

    private static void parseAttributeLine(String value, Builder b) {
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
                // "<alg> <hex>"
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

    /**
     * Compares a peer certificate's fingerprint, colon-separated or not and in any case, against
     * this SDP's {@code a=fingerprint} (RFC 4572); the check that replaces host-name verification.
     */
    public boolean fingerprintMatches(String peerCertFingerprintHex) {
        if (!hasFingerprint() || peerCertFingerprintHex == null) return false;
        String a = stripFingerprint(fingerprintHex);
        String b = stripFingerprint(peerCertFingerprintHex);
        return a.equalsIgnoreCase(b);
    }

    private static String stripFingerprint(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && !Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** RFC 4572 §5 lower-case colon-separated hex. */
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

    @Override
    public String toString() {
        return "SdpOffer{" + host + ":" + port + " proto=" + mediaProto
                + " path=" + msrpPath + " setup=" + setup
                + " fp=" + (hasFingerprint() ? fingerprintAlg : "none")
                + " accept=" + acceptTypes
                + " wrapped=" + acceptWrappedTypes
                + "}";
    }

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
        public Builder addrType(String t)         { this.addrType = t; return this; }
        public Builder host(String h)             { this.host = h; return this; }
        public Builder port(int p)                { this.port = p; return this; }
        public Builder mediaProto(String p)       { this.mediaProto = p; return this; }
        public Builder msrpPath(String p)         { this.msrpPath = p; return this; }
        public Builder setup(SetupRole r)         { this.setup = r; return this; }
        public Builder msrpCema(boolean v)        { this.msrpCema = v; return this; }
        public Builder connectionNew(boolean v)   { this.connectionNew = v; return this; }
        public Builder direction(Direction d)     { this.direction = d; return this; }

        public Builder fingerprint(String alg, String hex) {
            this.fingerprintAlg = alg;
            this.fingerprintHex = hex;
            return this;
        }

        public Builder acceptTypes(List<String> ts) {
            acceptTypes.clear();
            if (ts != null) {
                for (String t : ts) if (t != null && !t.isEmpty()) acceptTypes.add(t);
            }
            return this;
        }

        public Builder acceptWrappedTypes(List<String> ts) {
            acceptWrappedTypes.clear();
            if (ts != null) {
                for (String t : ts) if (t != null && !t.isEmpty()) acceptWrappedTypes.add(t);
            }
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
            // An empty accept-types list is allowed so parsed peer SDP can still be represented,
            // although GSMA UP 2.4 §A.1.3.5 makes it mandatory for chat.
            return new SdpOffer(this);
        }
    }

    /** GSMA UP 2.4 §A.1.3.5 accept-types for 1:1 chat, in the order peers send them. */
    public static List<String> upChatAcceptTypes() {
        List<String> l = new ArrayList<>();
        l.add("message/cpim");
        l.add("application/im-iscomposing+xml");
        return Collections.unmodifiableList(l);
    }

    /**
     * Accept-wrapped-types for 1:1 chat: the GSMA UP set. Etouffee's
     * {@code application/vnd.google.rcs.encrypted} is not offered: this transport cannot decrypt
     * it.
     */
    public static List<String> upChatAcceptWrappedTypes() {
        List<String> l = new ArrayList<>();
        l.add("text/plain");
        l.add("application/vnd.gsma.rcs-ft-http+xml");
        l.add("message/imdn+xml");
        l.add("application/vnd.oma.cpm-groupdata+xml");
        return Collections.unmodifiableList(l);
    }
}
