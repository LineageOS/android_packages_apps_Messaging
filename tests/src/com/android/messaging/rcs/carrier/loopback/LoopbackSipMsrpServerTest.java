/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.loopback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpFrameReader;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;
import com.android.messaging.rcs.carrier.SipDigestAuth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link LoopbackSipMsrpServer} itself, driven over raw sockets with canned SIP and MSRP input,
 * plus one exchange with the production {@code MsrpTlsConnection}.
 */
public class LoopbackSipMsrpServerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LoopbackSipMsrpServer server;

    @Before
    public void setUp() throws Exception {
        server = new LoopbackSipMsrpServer.Builder()
                .sipPort(0)
                .msrpPort(0)
                .digestRealm("loopback.test")
                .digestUsername("+15715551234")
                .digestPassword("test-secret")
                .build()
                .start();
    }

    @After
    public void tearDown() {
        if (server != null) server.close();
    }

    // ---- Boot

    @Test
    public void server_assignsEphemeralPorts() {
        assertTrue("sip port assigned", server.getSipPort() > 0);
        assertTrue("msrp port assigned", server.getMsrpPort() > 0);
        assertNotNull("fingerprint exposed", server.getMsrpFingerprintSha256());
        // RFC 4572 §5: 32 bytes as colon-separated hex.
        assertEquals(32 * 3 - 1, server.getMsrpFingerprintSha256().length());
    }

    @Test
    public void server_closeIsIdempotent() {
        server.close();
        server.close();
        server.close();
    }

    // ---- SIP: REGISTER + Digest

    @Test
    public void register_initialAttemptReceives401Challenge() throws Exception {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String reg =
                "REGISTER sip:loopback.test SIP/2.0\r\n"
                + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-1\r\n"
                + "From: <tel:+15715551234>;tag=fromtag1\r\n"
                + "To: <tel:+15715551234>\r\n"
                + "Call-ID: callid-reg-1\r\n"
                + "CSeq: 1 REGISTER\r\n"
                + "Max-Forwards: 70\r\n"
                + "Contact: <sip:client@127.0.0.1:1234>\r\n"
                + "Expires: 600000\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
            s.getOutputStream().write(reg.getBytes(UTF_8));
            s.getOutputStream().flush();

            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertNotNull(resp);
            assertEquals(401, resp.getStatusCode());
            String wwa = resp.getHeader("WWW-Authenticate");
            assertNotNull("challenge present", wwa);
            assertTrue("Digest scheme", wwa.startsWith("Digest "));
            assertTrue("realm in challenge", wwa.contains("realm=\"loopback.test\""));
        }
    }

    @Test
    public void register_validDigestReceives200Ok() throws Exception {
        String nonce;
        String realm;
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            byte[] reg = canonicalRegister(/*authHeader=*/null, /*callId=*/"callid-reg-2",
                    /*cseqNum=*/1).getBytes(UTF_8);
            s.getOutputStream().write(reg);
            s.getOutputStream().flush();
            SimpleSipMessage challenge = SimpleSipMessage.read(s.getInputStream());
            assertEquals(401, challenge.getStatusCode());
            java.util.Map<String, String> p = SimpleSipMessage.parseAuthHeader(
                    challenge.getHeader("WWW-Authenticate"));
            nonce = p.get("nonce");
            realm = p.get("realm");
            assertNotNull(nonce);
            assertEquals("loopback.test", realm);
        }
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String uri = "sip:loopback.test";
            String cnonce = SipDigestAuth.newCnonce();
            String nc = "00000001";
            String resp = SipDigestAuth.response("+15715551234", realm, "test-secret",
                    "REGISTER", uri, nonce, "auth", nc, cnonce);
            String authH = "Digest username=\"+15715551234\","
                    + " realm=\"" + realm + "\","
                    + " nonce=\"" + nonce + "\","
                    + " uri=\"" + uri + "\","
                    + " response=\"" + resp + "\","
                    + " algorithm=MD5,"
                    + " qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\"";
            s.getOutputStream().write(canonicalRegister(authH, "callid-reg-2", 2).getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage ok = SimpleSipMessage.read(s.getInputStream());
            assertEquals(200, ok.getStatusCode());
        }
        assertEquals(2, server.getReceivedSipRegisters().size());
    }

    @Test
    public void register_badDigestReceives403() throws Exception {
        // A bogus authorization without a prior challenge.
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String authH = "Digest username=\"+15715551234\","
                    + " realm=\"loopback.test\","
                    + " nonce=\"deadbeef\","
                    + " uri=\"sip:loopback.test\","
                    + " response=\"" + "0".repeat(32) + "\","
                    + " algorithm=MD5";
            s.getOutputStream().write(canonicalRegister(authH, "callid-reg-3", 1).getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(403, resp.getStatusCode());
        }
    }

    @Test
    public void register_forcedFailureReturnsConfiguredStatus() throws Exception {
        LoopbackSipMsrpServer s2 = new LoopbackSipMsrpServer.Builder()
                .digestRealm("loopback.test")
                .digestUsername("user")
                .digestPassword("pw")
                .forceRegisterStatus(503, "Service Unavailable")
                .build().start();
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), s2.getSipPort())) {
            s.getOutputStream().write(canonicalRegister(null, "callid-fr", 1).getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(503, resp.getStatusCode());
        } finally {
            s2.close();
        }
    }

    // ---- SIP: MESSAGE

    @Test
    public void message_returns202Accepted() throws Exception {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String body = "hello over CPIM"; // the harness does not parse the body
            String req =
                "MESSAGE tel:+15715559999 SIP/2.0\r\n"
                + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-m1\r\n"
                + "From: <tel:+15715551234>;tag=mt1\r\n"
                + "To: <tel:+15715559999>\r\n"
                + "Call-ID: msg-1\r\n"
                + "CSeq: 1 MESSAGE\r\n"
                + "Max-Forwards: 70\r\n"
                + "Contribution-ID: contrib-msg-1\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "\r\n"
                + body;
            s.getOutputStream().write(req.getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(202, resp.getStatusCode());
        }
        List<SimpleSipMessage> got = server.getReceivedSipMessages();
        assertEquals(1, got.size());
        assertEquals("contrib-msg-1", got.get(0).getHeader("Contribution-ID"));
        assertEquals("hello over CPIM", new String(got.get(0).body, UTF_8));
    }

    // ---- SIP: INVITE → SDP answer

    @Test
    public void invite_returns200WithSdpAnswer() throws Exception {
        SdpOffer offer = SdpOffer.builder()
                .sessionId("sess-1")
                .sessionVersion(1)
                .host("127.0.0.1").port(50001)
                .mediaProto(SdpOffer.MSRP_PROTO_TLS)
                .msrpPath("msrps://127.0.0.1:50001/client-1;tcp")
                .setup(SdpOffer.SetupRole.ACTIVE)
                .msrpCema(true).connectionNew(true)
                .fingerprint("SHA-256",
                        "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:"
                        + "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99")
                .acceptTypes(SdpOffer.upChatAcceptTypes())
                .acceptWrappedTypes(SdpOffer.upChatAcceptWrappedTypes())
                .direction(SdpOffer.Direction.SENDRECV)
                .build();
        byte[] sdp = offer.encode();
        String req =
            "INVITE tel:+15715559999 SIP/2.0\r\n"
            + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-i1\r\n"
            + "From: <tel:+15715551234>;tag=it1\r\n"
            + "To: <tel:+15715559999>\r\n"
            + "Call-ID: invite-1\r\n"
            + "CSeq: 1 INVITE\r\n"
            + "Max-Forwards: 70\r\n"
            + "Contribution-ID: contrib-inv-1\r\n"
            + "Content-Type: application/sdp\r\n"
            + "Content-Length: " + sdp.length + "\r\n"
            + "\r\n";
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            s.getOutputStream().write(req.getBytes(UTF_8));
            s.getOutputStream().write(sdp);
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(200, resp.getStatusCode());
            String ct = resp.getHeader("Content-Type");
            assertEquals("application/sdp", ct);
            assertNotNull(resp.body);
            SdpOffer answer = SdpOffer.parse(resp.body);
            assertEquals("127.0.0.1", answer.getHost());
            assertEquals(server.getMsrpPort(), answer.getPort());
            // RFC 6135 §5.2: an active offer gets a passive answer.
            assertEquals(SdpOffer.SetupRole.PASSIVE, answer.getSetup());
            assertTrue("fp present", answer.hasFingerprint());
            assertEquals("SHA-256", answer.getFingerprintAlg());
            assertEquals(server.getMsrpFingerprintSha256(), answer.getFingerprintHex());
            assertEquals(SdpOffer.upChatAcceptTypes(), answer.getAcceptTypes());
            String toHdr = resp.getHeader("To");
            assertTrue("To tagged: " + toHdr,
                    toHdr != null && toHdr.toLowerCase().contains(";tag="));
        }
        assertEquals(1, server.getSipSessions().size());
        LoopbackSipServer.SessionRecord rec = server.getSipSessions().get("invite-1");
        assertNotNull(rec);
        assertEquals("contrib-inv-1", rec.contributionId);
        assertNotNull(rec.offer);
        assertNotNull(rec.answer);
        assertFalse(rec.byeSeen);
    }

    @Test
    public void bye_returns200AndMarksSessionTerminated() throws Exception {
        invite_returns200WithSdpAnswer();
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String req =
                "BYE tel:+15715559999 SIP/2.0\r\n"
                + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-b1\r\n"
                + "From: <tel:+15715551234>;tag=it1\r\n"
                + "To: <tel:+15715559999>\r\n"
                + "Call-ID: invite-1\r\n"
                + "CSeq: 2 BYE\r\n"
                + "Max-Forwards: 70\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
            s.getOutputStream().write(req.getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(200, resp.getStatusCode());
        }
        LoopbackSipServer.SessionRecord rec = server.getSipSessions().get("invite-1");
        assertNotNull(rec);
        assertTrue(rec.byeSeen);
    }

    // ---- MSRP: SEND → 200 OK + REPORT

    @Test
    public void msrp_sendReceivesOkResponse() throws Exception {
        try (SSLSocket s = openMsrpClient(server.getMsrpPort())) {
            MsrpMessage send = MsrpMessage.newSend()
                    .transactionId("tx0001")
                    .toPath("msrps://127.0.0.1:" + server.getMsrpPort() + "/sess1;tcp")
                    .fromPath("msrps://127.0.0.1:9999/client1;tcp")
                    .messageId("m-1")
                    .byteRange(1, 5, 5)
                    .contentType("text/plain")
                    .body("hello".getBytes(UTF_8))
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            s.getOutputStream().write(send.encode());
            s.getOutputStream().flush();
            MsrpFrameReader reader = new MsrpFrameReader();
            java.util.ArrayDeque<MsrpMessage> pending = new java.util.ArrayDeque<>();
            MsrpMessage resp = readOneFrame(s.getInputStream(), s, reader, pending, 2000);
            assertNotNull(resp);
            assertEquals(MsrpMessage.Kind.RESPONSE, resp.getKind());
            assertEquals(200, resp.getStatusCode());
            assertEquals("tx0001", resp.getTransactionId());
        }
        // The server records the frame on its reader thread.
        waitFor(() -> server.getReceivedMsrpSends().size() >= 1, 1000);
        List<MsrpMessage> sends = server.getReceivedMsrpSends();
        assertEquals(1, sends.size());
        MsrpMessage got = sends.get(0);
        assertEquals("m-1", got.getMessageId());
        assertEquals("hello", new String(got.getBody(), UTF_8));
    }

    @Test
    public void msrp_sendWithSuccessReportYesAlsoTriggersReport() throws Exception {
        try (SSLSocket s = openMsrpClient(server.getMsrpPort())) {
            MsrpMessage send = MsrpMessage.newSend()
                    .transactionId("tx0002")
                    .toPath("msrps://127.0.0.1:" + server.getMsrpPort() + "/sess2;tcp")
                    .fromPath("msrps://127.0.0.1:9999/client2;tcp")
                    .messageId("m-with-success")
                    .byteRange(1, 5, 5)
                    .contentType("text/plain")
                    .successReport(true)
                    .body("hello".getBytes(UTF_8))
                    .endFlag(MsrpEndFlag.COMPLETE)
                    .build();
            s.getOutputStream().write(send.encode());
            s.getOutputStream().flush();
            // The 200 response, then the report.
            MsrpFrameReader reader = new MsrpFrameReader();
            java.util.ArrayDeque<MsrpMessage> pending = new java.util.ArrayDeque<>();
            MsrpMessage resp = readOneFrame(s.getInputStream(), s, reader, pending, 2000);
            assertEquals(MsrpMessage.Kind.RESPONSE, resp.getKind());
            assertEquals(200, resp.getStatusCode());
            MsrpMessage report = readOneFrame(s.getInputStream(), s, reader, pending, 2000);
            assertNotNull(report);
            assertEquals(MsrpMessage.Kind.REQUEST, report.getKind());
            assertEquals("REPORT", report.getMethod().name());
            assertEquals("m-with-success", report.getMessageId());
            String status = report.getStatus();
            assertNotNull(status);
            assertTrue("status 000 200: " + status, status.contains("000 200"));
        }
    }

    @Test
    public void msrp_sendInjectedFailureReturnsConfiguredStatus() throws Exception {
        LoopbackSipMsrpServer s2 = new LoopbackSipMsrpServer.Builder()
                .msrpSendFailureForNext(1, 413, "Request Entity Too Large")
                .build().start();
        try {
            try (SSLSocket s = openMsrpClient(s2.getMsrpPort())) {
                MsrpMessage send = MsrpMessage.newSend()
                        .transactionId("tx9999")
                        .toPath("msrps://127.0.0.1:" + s2.getMsrpPort() + "/sx;tcp")
                        .fromPath("msrps://127.0.0.1:9999/cx;tcp")
                        .messageId("m-fail")
                        .byteRange(1, 5, 5)
                        .body("hello".getBytes(UTF_8))
                        .build();
                s.getOutputStream().write(send.encode());
                s.getOutputStream().flush();
                MsrpFrameReader reader = new MsrpFrameReader();
                java.util.ArrayDeque<MsrpMessage> pending = new java.util.ArrayDeque<>();
                MsrpMessage resp = readOneFrame(s.getInputStream(), s, reader, pending, 2000);
                assertEquals(413, resp.getStatusCode());
            }
        } finally {
            s2.close();
        }
    }

    // ---- MSRP: fingerprint round-trip

    @Test
    public void msrp_fingerprintMatchesAdvertisedFromCert() throws Exception {
        try (SSLSocket s = openMsrpClient(server.getMsrpPort())) {
            X509Certificate peer = (X509Certificate)
                    s.getSession().getPeerCertificates()[0];
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(peer.getEncoded());
            String hex = SdpOffer.formatFingerprint(hash);
            assertEquals(server.getMsrpFingerprintSha256(), hex);
            // SdpOffer.fingerprintMatches performs the same comparison.
            SdpOffer offerWithFp = SdpOffer.builder()
                    .sessionId("x").host("127.0.0.1").port(server.getMsrpPort())
                    .msrpPath("msrps://127.0.0.1:" + server.getMsrpPort() + "/x;tcp")
                    .fingerprint("SHA-256", server.getMsrpFingerprintSha256())
                    .build();
            assertTrue(offerWithFp.fingerprintMatches(hex));
        }
    }

    @Test
    public void msrp_acceptsProductionMsrpTlsConnectionClient() throws Exception {
        // The production MsrpTlsConnection against the server.
        final CountDownLatch frameLatch = new CountDownLatch(1);
        final AtomicReference<MsrpMessage> received = new AtomicReference<>();
        MsrpTlsConnection.Listener l = new MsrpTlsConnection.Listener() {
            @Override public void onFrame(MsrpMessage f) {
                received.set(f);
                frameLatch.countDown();
            }
            @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
        };
        MsrpTlsConnection.SocketFactory factory = (host, port) -> {
            try {
                return openMsrpClient(port);
            } catch (IOException ioe) {
                throw ioe;
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, l, "127.0.0.1", server.getMsrpPort(),
                "SHA-256", server.getMsrpFingerprintSha256());
        conn.start();
        try {
            MsrpMessage send = MsrpMessage.newSend()
                    .transactionId("tx-prod-1")
                    .toPath("msrps://127.0.0.1:" + server.getMsrpPort() + "/p;tcp")
                    .fromPath("msrps://127.0.0.1:9999/q;tcp")
                    .messageId("m-prod-1")
                    .byteRange(1, 3, 3)
                    .contentType("text/plain")
                    .body("hi!".getBytes(UTF_8))
                    .build();
            conn.send(send);
            assertTrue("response arrived",
                    frameLatch.await(2, TimeUnit.SECONDS));
            MsrpMessage resp = received.get();
            assertEquals(MsrpMessage.Kind.RESPONSE, resp.getKind());
            assertEquals(200, resp.getStatusCode());
            assertEquals("tx-prod-1", resp.getTransactionId());
        } finally {
            conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
        }
        waitFor(() -> server.getReceivedMsrpSends().size() >= 1, 1000);
        assertEquals(1, server.getReceivedMsrpSends().size());
        assertEquals("m-prod-1", server.getReceivedMsrpSends().get(0).getMessageId());
    }

    @Test
    public void msrp_fingerprintMismatchRejectedByProductionClient() throws Exception {
        MsrpTlsConnection.SocketFactory factory = (host, port) -> {
            try { return openMsrpClient(port); }
            catch (IOException ioe) { throw ioe; }
            catch (Exception e) { throw new IOException(e); }
        };
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory,
                new MsrpTlsConnection.Listener() {
                    @Override public void onFrame(MsrpMessage f) {}
                    @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
                },
                "127.0.0.1", server.getMsrpPort(),
                "SHA-256",
                "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:"
                + "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00");
        try {
            conn.start();
            org.junit.Assert.fail("expected fingerprint mismatch IOException");
        } catch (IOException expected) {
            assertTrue("fp mismatch message: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("fingerprint"));
        }
    }

    // ---- helpers

    private static String canonicalRegister(String authHeader, String callId, int cseqNum) {
        StringBuilder sb = new StringBuilder();
        sb.append("REGISTER sip:loopback.test SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-r").append(cseqNum)
                .append("\r\n");
        sb.append("From: <tel:+15715551234>;tag=fromtag-").append(cseqNum).append("\r\n");
        sb.append("To: <tel:+15715551234>\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(cseqNum).append(" REGISTER\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Contact: <sip:client@127.0.0.1:1234>\r\n");
        sb.append("Expires: 600000\r\n");
        if (authHeader != null) {
            sb.append("Authorization: ").append(authHeader).append("\r\n");
        }
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /** A handshaken TLS client socket that trusts any certificate. */
    private static SSLSocket openMsrpClient(int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, new TrustManager[]{
                new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    @Override public void checkClientTrusted(X509Certificate[] c, String s) {}
                    @Override public void checkServerTrusted(X509Certificate[] c, String s) {}
                }
        }, new java.security.SecureRandom());
        SSLSocketFactory sf = ctx.getSocketFactory();
        SSLSocket s = (SSLSocket) sf.createSocket(InetAddress.getByName("127.0.0.1"), port);
        s.startHandshake();
        return s;
    }

    /**
     * The next MSRP frame, from {@code pending} first, else read with {@code reader}; extra frames
     * go to {@code pending}. Null at end of stream or timeout.
     */
    private static MsrpMessage readOneFrame(InputStream in, Socket s,
            MsrpFrameReader reader, java.util.ArrayDeque<MsrpMessage> pending,
            int timeoutMillis) throws Exception {
        if (!pending.isEmpty()) return pending.pollFirst();
        s.setSoTimeout(timeoutMillis);
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (java.net.SocketTimeoutException ste) {
                continue;
            }
            if (n < 0) return null;
            if (n == 0) continue;
            List<MsrpMessage> frames = reader.append(buf, 0, n);
            if (!frames.isEmpty()) {
                for (int i = 1; i < frames.size(); i++) pending.addLast(frames.get(i));
                return frames.get(0);
            }
        }
        return null;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, int timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    @Test
    public void assertNotNullOfMsrpFingerprintAlg() {
        assertEquals("SHA-256", server.getMsrpFingerprintAlg());
    }

    @Test
    public void unhandledMethodReturns405() throws Exception {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), server.getSipPort())) {
            String req =
                "SUBSCRIBE tel:+15715559999 SIP/2.0\r\n"
                + "Via: SIP/2.0/TCP 127.0.0.1:1234;branch=z9hG4bK-x1\r\n"
                + "From: <tel:+15715551234>;tag=x1\r\n"
                + "To: <tel:+15715559999>\r\n"
                + "Call-ID: subs-1\r\n"
                + "CSeq: 1 SUBSCRIBE\r\n"
                + "Max-Forwards: 70\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
            s.getOutputStream().write(req.getBytes(UTF_8));
            s.getOutputStream().flush();
            SimpleSipMessage resp = SimpleSipMessage.read(s.getInputStream());
            assertEquals(405, resp.getStatusCode());
        }
    }
}
