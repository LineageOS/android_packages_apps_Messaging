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
package com.android.messaging.rcs.carrier.loopback;

import com.android.messaging.rcs.carrier.msrp.session.SdpAnswer;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.carrier.msrp.session.SdpParseException;
import com.android.messaging.rcs.carrier.SipDigestAuth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process SIP-over-TCP server for the loopback harness. Test scaffolding.
 *
 * <p>Listens on a TCP {@link ServerSocket} bound to {@code 127.0.0.1:0} (or a
 * caller-supplied port). For each accepted connection, spawns a reader thread
 * that parses SIP messages via {@link SimpleSipMessage} and emits canned
 * responses for the four request types the carrier-RCS stack speaks:
 *
 * <ul>
 *   <li><b>REGISTER</b> — first attempt (no Authorization header) → respond
 *       401 with a {@code WWW-Authenticate: Digest} challenge. On retry with
 *       a valid Authorization header (per RFC 2617 — computed via the
 *       production {@link SipDigestAuth}) respond 200 OK. Bad digest →
 *       403 Forbidden so the client surfaces it as FAILED.</li>
 *   <li><b>INVITE</b> — parse the {@code application/sdp} body, build a
 *       complementary SDP answer via {@link SdpAnswer#build} pointing at the
 *       harness's MSRP server (with the SHA-256 fingerprint of the harness's
 *       cert), respond 200 OK with the answer in the body.</li>
 *   <li><b>MESSAGE</b> — respond 202 Accepted (RFC 3428 §7); record the body
 *       so the test can assert what was sent.</li>
 *   <li><b>BYE</b> — respond 200 OK; tear down the per-Call-ID session
 *       record.</li>
 *   <li><b>ACK</b> — no response (per RFC 3261 §17.1.1.3 ACK is method-only).</li>
 * </ul>
 *
 * <p>Per-session state is keyed by Call-ID — the harness tracks From/To tags,
 * Contribution-ID, the negotiated MSRP URI binding, etc. — and exposed via
 * {@link #getSessions()} for test assertions.
 *
 * <p>Pure Java, no Android imports, no JAIN-SIP. Used only by host JUnit tests.
 */
final class LoopbackSipServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread acceptThread;

    // Configurable per Builder.
    private final String digestRealm;
    private final String digestUsername;
    private final String digestPassword;
    /** When non-null, the harness rejects REGISTER with the supplied status code
     *  instead of going through the 401/200 dance — useful for failure tests. */
    private final Integer registerForcedStatus;
    private final String registerForcedReason;
    private final int msrpPort;
    private final String msrpFingerprintAlg;
    private final String msrpFingerprintHex;

    private final List<SimpleSipMessage> receivedRegisters = new CopyOnWriteArrayList<>();
    private final List<SimpleSipMessage> receivedInvites = new CopyOnWriteArrayList<>();
    private final List<SimpleSipMessage> receivedMessages = new CopyOnWriteArrayList<>();
    private final List<SimpleSipMessage> receivedByes = new CopyOnWriteArrayList<>();
    private final List<SimpleSipMessage> receivedAcks = new CopyOnWriteArrayList<>();
    private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
    private final List<Thread> readerThreads = new CopyOnWriteArrayList<>();

    /** Per-session info recorded at INVITE time. Keyed by Call-ID. */
    private final Map<String, SessionRecord> sessions =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private final AtomicReference<InboundMessageCallback> inboundCb = new AtomicReference<>();

    /**
     * Callback for tests that want to observe inbound MESSAGE / INVITE
     * payloads as they arrive (in addition to the recorded lists). Optional.
     */
    interface InboundMessageCallback {
        void onSipRequest(SimpleSipMessage msg);
    }

    void setInboundCallback(InboundMessageCallback cb) { inboundCb.set(cb); }

    static final class SessionRecord {
        final String callId;
        final String fromTag;
        String toTag;
        String contributionId;
        SdpOffer offer;
        SdpOffer answer;
        boolean byeSeen;

        SessionRecord(String callId, String fromTag) {
            this.callId = callId;
            this.fromTag = fromTag;
        }
    }

    private LoopbackSipServer(ServerSocket s, Builder b,
            int msrpPort, String msrpFingerprintAlg, String msrpFingerprintHex) {
        this.serverSocket = s;
        this.digestRealm = b.digestRealm;
        this.digestUsername = b.digestUsername;
        this.digestPassword = b.digestPassword;
        this.registerForcedStatus = b.registerForcedStatus;
        this.registerForcedReason = b.registerForcedReason;
        this.msrpPort = msrpPort;
        this.msrpFingerprintAlg = msrpFingerprintAlg;
        this.msrpFingerprintHex = msrpFingerprintHex;
    }

    int getPort() { return serverSocket.getLocalPort(); }

    List<SimpleSipMessage> getReceivedRegisters() { return Collections.unmodifiableList(new ArrayList<>(receivedRegisters)); }
    List<SimpleSipMessage> getReceivedInvites()   { return Collections.unmodifiableList(new ArrayList<>(receivedInvites)); }
    List<SimpleSipMessage> getReceivedMessages()  { return Collections.unmodifiableList(new ArrayList<>(receivedMessages)); }
    List<SimpleSipMessage> getReceivedByes()      { return Collections.unmodifiableList(new ArrayList<>(receivedByes)); }
    List<SimpleSipMessage> getReceivedAcks()      { return Collections.unmodifiableList(new ArrayList<>(receivedAcks)); }

    Map<String, SessionRecord> getSessions() {
        synchronized (sessions) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
        }
    }

    void start() {
        acceptThread = new Thread(this::acceptLoop, "loopback-sip-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket s = serverSocket.accept();
                openSockets.add(s);
                Thread t = new Thread(() -> connectionLoop(s),
                        "loopback-sip-conn-" + s.getPort());
                t.setDaemon(true);
                readerThreads.add(t);
                t.start();
            } catch (IOException e) {
                if (closed.get()) return;
            }
        }
    }

    private void connectionLoop(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (!closed.get() && !s.isClosed()) {
                SimpleSipMessage msg;
                try {
                    msg = SimpleSipMessage.read(in);
                } catch (IOException ioe) {
                    return;
                }
                if (msg == null) return; // EOF
                if (msg.kind != SimpleSipMessage.Kind.REQUEST) {
                    // Unexpected — drop.
                    continue;
                }
                InboundMessageCallback cb = inboundCb.get();
                if (cb != null) {
                    try { cb.onSipRequest(msg); } catch (Throwable ignore) {}
                }
                byte[] response = handleRequest(msg);
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (IOException ignore) {
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    private byte[] handleRequest(SimpleSipMessage msg) {
        String method = msg.getMethod();
        if (method == null) return null;
        switch (method.toUpperCase(Locale.ROOT)) {
            case "REGISTER":  return handleRegister(msg);
            case "INVITE":    return handleInvite(msg);
            case "MESSAGE":   return handleMessage(msg);
            case "BYE":       return handleBye(msg);
            case "ACK":       receivedAcks.add(msg); return null;
            case "CANCEL":    return msg.responseBuilder(200, "OK").build().encode();
            case "OPTIONS":   return msg.responseBuilder(200, "OK").build().encode();
            default:
                return msg.responseBuilder(405, "Method Not Allowed").build().encode();
        }
    }

    // ---------- REGISTER ----------

    private byte[] handleRegister(SimpleSipMessage msg) {
        receivedRegisters.add(msg);
        if (registerForcedStatus != null) {
            return msg.responseBuilder(registerForcedStatus,
                    registerForcedReason == null ? "Forced" : registerForcedReason)
                    .build().encode();
        }
        String auth = msg.getHeader("Authorization");
        if (auth == null) {
            // Issue a Digest challenge per RFC 3261 §22.4.
            String nonce = newNonce();
            SimpleSipMessage.Builder b = msg.responseBuilder(401, "Unauthorized");
            b.addHeader("WWW-Authenticate",
                    "Digest realm=\"" + digestRealm + "\","
                    + " nonce=\"" + nonce + "\","
                    + " algorithm=MD5,"
                    + " qop=\"auth\"");
            return b.build().encode();
        }
        // Validate the supplied digest. We re-compute via the production
        // SipDigestAuth using the supplied nonce + qop/cnonce/nc.
        Map<String, String> p = SimpleSipMessage.parseAuthHeader(auth);
        String user = p.get("username");
        String realm = p.get("realm");
        String nonce = p.get("nonce");
        String uri = p.get("uri");
        String resp = p.get("response");
        String qop = p.get("qop");
        String nc = p.get("nc");
        String cnonce = p.get("cnonce");
        if (user == null || realm == null || nonce == null || uri == null || resp == null) {
            return msg.responseBuilder(400, "Bad Request").build().encode();
        }
        if (!user.equals(digestUsername)) {
            return msg.responseBuilder(403, "Forbidden").build().encode();
        }
        String expect = SipDigestAuth.response(user, realm, digestPassword,
                "REGISTER", uri, nonce, qop, nc, cnonce);
        if (!expect.equalsIgnoreCase(resp)) {
            return msg.responseBuilder(403, "Forbidden").build().encode();
        }
        return msg.responseBuilder(200, "OK").build().encode();
    }

    // ---------- INVITE ----------

    private byte[] handleInvite(SimpleSipMessage msg) {
        receivedInvites.add(msg);
        String callId = msg.getHeader("Call-ID");
        String fromTag = SimpleSipMessage.extractTag(msg.getHeader("From"));
        SessionRecord rec = new SessionRecord(callId == null ? UUID.randomUUID().toString() : callId,
                fromTag);
        rec.toTag = "loopback-" + Integer.toHexString((int) (System.nanoTime() & 0xffffffL));
        rec.contributionId = msg.getHeader("Contribution-ID");

        SdpOffer offer = null;
        if (msg.body != null && msg.body.length > 0) {
            try {
                offer = SdpOffer.parse(msg.body);
                rec.offer = offer;
            } catch (SdpParseException spe) {
                return msg.responseBuilder(488, "Not Acceptable Here").build().encode();
            }
        }

        // Build SDP answer using the production helper. The answer points at
        // the harness's MSRP server with the cert's SHA-256 fingerprint.
        String sessionId = offer != null ? offer.getSessionId()
                : "loop-" + Long.toHexString(System.nanoTime());
        long sessionVersion = offer != null ? offer.getSessionVersion() + 1 : 1;
        String msrpPath = "msrps://127.0.0.1:" + msrpPort
                + "/" + Long.toHexString(System.nanoTime()) + ";tcp";

        SdpOffer answer;
        if (offer != null) {
            answer = SdpAnswer.build(offer,
                    "127.0.0.1", msrpPort, msrpPath,
                    msrpFingerprintAlg, msrpFingerprintHex,
                    SdpOffer.upChatAcceptTypes(),
                    SdpOffer.upChatAcceptWrappedTypes());
        } else {
            // No offer supplied: fabricate a minimal answer-shaped offer.
            answer = SdpOffer.builder()
                    .sessionId(sessionId).sessionVersion(sessionVersion)
                    .host("127.0.0.1").port(msrpPort)
                    .mediaProto(SdpOffer.MSRP_PROTO_TLS)
                    .msrpPath(msrpPath)
                    .setup(SdpOffer.SetupRole.PASSIVE)
                    .msrpCema(true).connectionNew(true)
                    .fingerprint(msrpFingerprintAlg, msrpFingerprintHex)
                    .acceptTypes(SdpOffer.upChatAcceptTypes())
                    .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                    .direction(SdpOffer.Direction.SENDRECV)
                    .build();
        }
        rec.answer = answer;
        if (callId != null) sessions.put(callId, rec);

        SimpleSipMessage.Builder rb = msg.responseBuilder(200, "OK");
        // Tag the To header with the answerer's tag (RFC 3261 §17.2.1).
        String toHdr = msg.getHeader("To");
        if (toHdr != null && !toHdr.toLowerCase(Locale.ROOT).contains(";tag=")) {
            // Replace the To header value with the same plus our tag.
            // Find and replace in the headers list of the response.
            String tagged = toHdr + ";tag=" + rec.toTag;
            rb = rebuildToHeader(rb, tagged);
        }
        rb.addHeader("Contact", "<sip:loopback@127.0.0.1:" + serverSocket.getLocalPort() + ">");
        byte[] sdp = answer.encode();
        rb.body(sdp);
        rb.addHeader("Content-Type", "application/sdp");
        return rb.build().encode();
    }

    /** Replace the To header in the in-flight builder with a tagged version. */
    private static SimpleSipMessage.Builder rebuildToHeader(SimpleSipMessage.Builder b, String taggedTo) {
        // SimpleSipMessage.Builder doesn't expose mutate-in-place; rebuild.
        SimpleSipMessage built = b.build();
        SimpleSipMessage.Builder nb = SimpleSipMessage.Builder.response(
                built.getStatusCode(),
                built.startC);
        for (String[] h : built.headers) {
            if ("To".equalsIgnoreCase(h[0])) {
                nb.addHeader("To", taggedTo);
            } else {
                nb.addHeader(h[0], h[1]);
            }
        }
        if (built.body != null) nb.body(built.body);
        return nb;
    }

    // ---------- MESSAGE ----------

    private byte[] handleMessage(SimpleSipMessage msg) {
        receivedMessages.add(msg);
        // RFC 3428 §7: 202 Accepted (or 200 OK — Google Messages accepts both).
        return msg.responseBuilder(202, "Accepted").build().encode();
    }

    // ---------- BYE ----------

    private byte[] handleBye(SimpleSipMessage msg) {
        receivedByes.add(msg);
        String callId = msg.getHeader("Call-ID");
        if (callId != null) {
            SessionRecord rec = sessions.get(callId);
            if (rec != null) rec.byeSeen = true;
        }
        return msg.responseBuilder(200, "OK").build().encode();
    }

    // ---------- helpers ----------

    private static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { serverSocket.close(); } catch (IOException ignore) {}
        for (Socket s : openSockets) {
            try { s.close(); } catch (IOException ignore) {}
        }
        try {
            if (acceptThread != null) acceptThread.join(500);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        for (Thread t : readerThreads) {
            try { t.join(200); } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------- builder ----------

    static final class Builder {
        int port = 0;
        String digestRealm = "loopback.test";
        String digestUsername = "+15715551234";
        String digestPassword = "test-secret";
        Integer registerForcedStatus;
        String registerForcedReason;

        Builder port(int p) { this.port = p; return this; }
        Builder digestRealm(String r) { this.digestRealm = r; return this; }
        Builder digestUsername(String u) { this.digestUsername = u; return this; }
        Builder digestPassword(String p) { this.digestPassword = p; return this; }
        Builder forceRegisterStatus(int code, String reason) {
            this.registerForcedStatus = code;
            this.registerForcedReason = reason;
            return this;
        }

        LoopbackSipServer build(int msrpPort, String msrpFingerprintAlg,
                String msrpFingerprintHex) throws IOException {
            ServerSocket ss = new ServerSocket(port, 16,
                    InetAddress.getByName("127.0.0.1"));
            return new LoopbackSipServer(ss, this,
                    msrpPort, msrpFingerprintAlg, msrpFingerprintHex);
        }
    }
}
