/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.loopback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.session.CarrierMsrpSessionReceiver;
import com.android.messaging.rcs.carrier.msrp.session.CarrierMsrpSessionSender;
import com.android.messaging.rcs.carrier.msrp.session.MsrpChatSession;
import com.android.messaging.rcs.carrier.msrp.session.MsrpSessionInfo;
import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.rcs.carrier.msrp.session.SdpOffer;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The production MSRP session stack ({@link MsrpChatSession}, {@link MsrpTlsConnection},
 * {@link CarrierMsrpSessionSender}, {@link CarrierMsrpSessionReceiver}) against
 * {@link LoopbackSipMsrpServer}: the fingerprint-checked TLS handshake, a SEND and its 200 or
 * REPORT reaching {@code SENT}, and an inbound SEND reaching the listener. The SIP dialog is not
 * exercised; {@code CarrierMsrpSessionManager} needs JAIN-SIP and Android.
 */
public class MsrpChatSession_LoopbackTest {

    private LoopbackSipMsrpServer server;

    @Before
    public void setUp() throws Exception {
        server = new LoopbackSipMsrpServer.Builder()
                .sipPort(0)
                .msrpPort(0)
                .build()
                .start();
    }

    @After
    public void tearDown() {
        if (server != null) server.close();
    }

    @Test
    public void send_text_overEstablishedSession_completesWithStatusSent() throws Exception {
        // A session as if the SDP exchange had completed against the harness.
        MsrpChatSession session = new MsrpChatSession("loop-1");
        final List<Message.Status> statuses = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final CountDownLatch sentLatch = new CountDownLatch(1);
        Transport.Listener listener = new Transport.Listener() {
            @Override public void onIncomingMessage(String f, String b, String mid) {}
            @Override
            public void onMessageStatus(String mid, Message.Status st, String reason) {
                synchronized (statuses) {
                    statuses.add(st);
                    reasons.add(reason);
                    if (st == Message.Status.SENT || st == Message.Status.FAILED) {
                        sentLatch.countDown();
                    }
                }
            }
            @Override public void onRegistrationStateChanged(Transport.RegistrationState s,
                    String r) {}
        };

        final AtomicReference<MsrpTlsConnection> connRef = new AtomicReference<>();
        MsrpTlsConnection.Listener connListener = new MsrpTlsConnection.Listener() {
            @Override public void onFrame(MsrpMessage f) {
                session.onMsrpFrame(f);
            }
            @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
        };
        MsrpTlsConnection.SocketFactory factory = (host, port) -> {
            try { return openMsrpClient(port); }
            catch (IOException ioe) { throw ioe; }
            catch (Exception e) { throw new IOException(e); }
        };
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, connListener, "127.0.0.1", server.getMsrpPort(),
                "SHA-256", server.getMsrpFingerprintSha256());
        connRef.set(conn);
        conn.start();

        String localUri = "msrps://127.0.0.1:12345/client;tcp";
        String remoteUri = "msrps://127.0.0.1:" + server.getMsrpPort() + "/server;tcp";
        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .contributionId("loop-contrib")
                .sipCallId("loop-callid")
                .localMsrpUri(localUri)
                .remoteMsrpUri(remoteUri)
                .remoteFingerprint("SHA-256", server.getMsrpFingerprintSha256())
                .remoteHost("127.0.0.1")
                .remotePort(server.getMsrpPort())
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .build();
        session.inviting();
        session.established(info);

        CarrierMsrpSessionSender sender = new CarrierMsrpSessionSender(
                session, conn::send, listener);

        session.setListener(new MsrpChatSession.Listener() {
            @Override public void onStateChanged(MsrpChatSession.State s,
                    MsrpChatSession.CloseReason r, String d) {}
            @Override public void onMsrpMessage(MsrpMessage m) {
                if (m.isResponse()) sender.onResponse(m);
                else if (m.getMethod()
                        == com.android.messaging.rcs.carrier.msrp.MsrpMethod.REPORT) {
                    sender.onReport(m);
                }
            }
        });

        boolean dispatched = sender.send("ui-1", "message/cpim",
                "hello via msrp".getBytes("UTF-8"),
                /*wantsSuccessReport=*/ false);
        assertTrue("dispatch ok", dispatched);

        assertTrue("terminal status",
                sentLatch.await(3, TimeUnit.SECONDS));
        // Without a success report, the 200 alone completes the send.
        synchronized (statuses) {
            assertTrue("got SENDING first: " + statuses,
                    statuses.contains(Message.Status.SENDING));
            assertEquals(Message.Status.SENT, statuses.get(statuses.size() - 1));
        }

        for (int i = 0; i < 50; i++) {
            if (!server.getReceivedMsrpSends().isEmpty()) break;
            Thread.sleep(20);
        }
        List<MsrpMessage> recorded = server.getReceivedMsrpSends();
        assertEquals(1, recorded.size());
        MsrpMessage send = recorded.get(0);
        assertEquals(remoteUri, send.getToPath());
        assertEquals(localUri, send.getFromPath());
        assertEquals("ui-1", send.getMessageId());
        assertEquals("hello via msrp", new String(send.getBody(), "UTF-8"));
        assertEquals("message/cpim", send.getContentType());

        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
    }

    @Test
    public void send_text_withSuccessReport_resolvesOnReport() throws Exception {
        MsrpChatSession session = new MsrpChatSession("loop-2");
        final CountDownLatch sentLatch = new CountDownLatch(1);
        final AtomicReference<Message.Status> finalStatus = new AtomicReference<>();
        Transport.Listener listener = new Transport.Listener() {
            @Override public void onIncomingMessage(String f, String b, String mid) {}
            @Override
            public void onMessageStatus(String mid, Message.Status st, String reason) {
                if (st == Message.Status.SENT || st == Message.Status.FAILED) {
                    finalStatus.set(st);
                    sentLatch.countDown();
                }
            }
            @Override public void onRegistrationStateChanged(Transport.RegistrationState s,
                    String r) {}
        };

        MsrpTlsConnection.SocketFactory factory = (host, port) -> {
            try { return openMsrpClient(port); }
            catch (IOException ioe) { throw ioe; }
            catch (Exception e) { throw new IOException(e); }
        };
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, new MsrpTlsConnection.Listener() {
                    @Override public void onFrame(MsrpMessage f) { session.onMsrpFrame(f); }
                    @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
                }, "127.0.0.1", server.getMsrpPort(),
                "SHA-256", server.getMsrpFingerprintSha256());
        conn.start();

        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .localMsrpUri("msrps://127.0.0.1:12345/client;tcp")
                .remoteMsrpUri("msrps://127.0.0.1:" + server.getMsrpPort() + "/server;tcp")
                .localRole(SdpOffer.SetupRole.ACTIVE)
                .remoteHost("127.0.0.1").remotePort(server.getMsrpPort())
                .build();
        session.inviting();
        session.established(info);

        CarrierMsrpSessionSender sender = new CarrierMsrpSessionSender(
                session, conn::send, listener);
        session.setListener(new MsrpChatSession.Listener() {
            @Override public void onStateChanged(MsrpChatSession.State s,
                    MsrpChatSession.CloseReason r, String d) {}
            @Override public void onMsrpMessage(MsrpMessage m) {
                if (m.isResponse()) sender.onResponse(m);
                else if (m.getMethod()
                        == com.android.messaging.rcs.carrier.msrp.MsrpMethod.REPORT) {
                    sender.onReport(m);
                }
            }
        });
        boolean dispatched = sender.send("ui-2", "message/cpim",
                "hello".getBytes("UTF-8"), /*wantsSuccessReport=*/ true);
        assertTrue(dispatched);

        // With a success report requested, both the 200 and the report are needed.
        assertTrue("terminal status", sentLatch.await(3, TimeUnit.SECONDS));
        assertEquals(Message.Status.SENT, finalStatus.get());

        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
    }

    @Test
    public void send_text_uponMsrpFailureResponse_dispatchesFailed() throws Exception {
        try (LoopbackSipMsrpServer s2 = new LoopbackSipMsrpServer.Builder()
                .msrpSendFailureForNext(1, 413, "Request Entity Too Large")
                .build().start()) {
            MsrpChatSession session = new MsrpChatSession("loop-3");
            final CountDownLatch failedLatch = new CountDownLatch(1);
            final AtomicReference<Message.Status> finalStatus = new AtomicReference<>();
            final AtomicReference<String> finalReason = new AtomicReference<>();
            Transport.Listener listener = new Transport.Listener() {
                @Override public void onIncomingMessage(String f, String b, String mid) {}
                @Override
                public void onMessageStatus(String mid, Message.Status st, String r) {
                    if (st == Message.Status.SENT || st == Message.Status.FAILED) {
                        finalStatus.set(st);
                        finalReason.set(r);
                        failedLatch.countDown();
                    }
                }
                @Override public void onRegistrationStateChanged(Transport.RegistrationState s,
                        String r) {}
            };

            MsrpTlsConnection.SocketFactory factory = (host, port) -> {
                try { return openMsrpClient(port); }
                catch (IOException ioe) { throw ioe; }
                catch (Exception e) { throw new IOException(e); }
            };
            MsrpTlsConnection conn = new MsrpTlsConnection(
                    factory, new MsrpTlsConnection.Listener() {
                        @Override public void onFrame(MsrpMessage f) { session.onMsrpFrame(f); }
                        @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
                    }, "127.0.0.1", s2.getMsrpPort(),
                    "SHA-256", s2.getMsrpFingerprintSha256());
            conn.start();

            MsrpSessionInfo info = MsrpSessionInfo.builder()
                    .localMsrpUri("msrps://127.0.0.1:1/c;tcp")
                    .remoteMsrpUri("msrps://127.0.0.1:" + s2.getMsrpPort() + "/s;tcp")
                    .localRole(SdpOffer.SetupRole.ACTIVE)
                    .remoteHost("127.0.0.1").remotePort(s2.getMsrpPort())
                    .build();
            session.inviting();
            session.established(info);
            CarrierMsrpSessionSender sender = new CarrierMsrpSessionSender(
                    session, conn::send, listener);
            session.setListener(new MsrpChatSession.Listener() {
                @Override public void onStateChanged(MsrpChatSession.State s,
                        MsrpChatSession.CloseReason r, String d) {}
                @Override public void onMsrpMessage(MsrpMessage m) {
                    if (m.isResponse()) sender.onResponse(m);
                    else if (m.getMethod()
                            == com.android.messaging.rcs.carrier.msrp.MsrpMethod.REPORT) {
                        sender.onReport(m);
                    }
                }
            });
            sender.send("ui-3", "message/cpim", "x".getBytes("UTF-8"), false);
            assertTrue("terminal status", failedLatch.await(3, TimeUnit.SECONDS));
            assertEquals(Message.Status.FAILED, finalStatus.get());
            assertNotNull("reason populated", finalReason.get());
            assertTrue("reason mentions 413: " + finalReason.get(),
                    finalReason.get().contains("413"));
            conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
        }
    }

    @Test
    public void receive_inboundSend_isDispatchedToTransportListener() throws Exception {
        MsrpChatSession session = new MsrpChatSession("loop-rx");
        final List<String[]> incoming = new ArrayList<>();
        final CountDownLatch rxLatch = new CountDownLatch(1);
        Transport.Listener listener = new Transport.Listener() {
            @Override
            public void onIncomingMessage(String from, String body, String mid) {
                synchronized (incoming) {
                    incoming.add(new String[]{from, body, mid});
                    rxLatch.countDown();
                }
            }
            @Override public void onMessageStatus(String mid, Message.Status st, String r) {}
            @Override public void onRegistrationStateChanged(Transport.RegistrationState s,
                    String r) {}
        };

        SSLSocket client = openMsrpClient(server.getMsrpPort());
        MsrpTlsConnection conn = new MsrpTlsConnection(
                /*factory*/ (host, port) -> { throw new IOException("not used"); },
                new MsrpTlsConnection.Listener() {
                    @Override public void onFrame(MsrpMessage f) { session.onMsrpFrame(f); }
                    @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
                },
                "127.0.0.1", server.getMsrpPort(),
                "SHA-256", server.getMsrpFingerprintSha256());
        conn.adopt(client);

        MsrpSessionInfo info = MsrpSessionInfo.builder()
                .localMsrpUri("msrps://127.0.0.1:1/c;tcp")
                .remoteMsrpUri("msrps://127.0.0.1:" + server.getMsrpPort() + "/s;tcp")
                .localRole(SdpOffer.SetupRole.PASSIVE)
                .remoteHost("127.0.0.1").remotePort(server.getMsrpPort())
                .build();
        session.inviting();
        session.established(info);

        // A no-op writer: this test checks delivery, not the receiver's responses.
        CarrierMsrpSessionReceiver receiver =
                new CarrierMsrpSessionReceiver(session, frame -> {}, listener);
        session.setListener(new MsrpChatSession.Listener() {
            @Override public void onStateChanged(MsrpChatSession.State s,
                    MsrpChatSession.CloseReason r, String d) {}
            @Override public void onMsrpMessage(MsrpMessage m) {
                if (m.isRequest()
                        && m.getMethod()
                        == com.android.messaging.rcs.carrier.msrp.MsrpMethod.SEND) {
                    receiver.onSendChunk(m);
                }
            }
        });

        // The harness never originates a SEND, so a peer's SEND is fed into the session directly.
        MsrpMessage synthetic = MsrpMessage.newSend()
                .toPath(info.getLocalMsrpUri())
                .fromPath(info.getRemoteMsrpUri())
                .messageId("peer-msg-1")
                .byteRange(1, 13, 13)
                .contentType("message/cpim")
                .body("from-the-peer".getBytes("UTF-8"))
                .build();
        session.onMsrpFrame(synthetic);

        assertTrue("inbound delivered", rxLatch.await(2, TimeUnit.SECONDS));
        synchronized (incoming) {
            assertEquals(1, incoming.size());
            // The receiver passes the body through without unwrapping CPIM.
            assertEquals("peer-msg-1", incoming.get(0)[2]);
        }
        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
    }

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
        }, new SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory()
                .createSocket(InetAddress.getByName("127.0.0.1"), port);
        s.startHandshake();
        return s;
    }
}
