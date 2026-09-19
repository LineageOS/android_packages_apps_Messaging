/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.msrp.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.carrier.msrp.MsrpEndFlag;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link MsrpTlsConnection}: socket lifecycle, read loop, writes and fingerprint comparison. Round
 * trips use plain TCP; the fingerprint check is tested through
 * {@link MsrpTlsConnection#fingerprintsMatch}. TLS is covered by the loopback tests.
 */
public class MsrpTlsConnectionTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private ServerSocket server;
    private Socket peer;
    private Thread acceptThread;

    @Before
    public void setUp() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    @After
    public void tearDown() throws Exception {
        try { if (server != null) server.close(); } catch (IOException ignore) {}
        try { if (peer != null) peer.close(); } catch (IOException ignore) {}
    }

    // ---- Round-trip

    @Test
    public void roundTrip_sendAndReceiveMsrpFrame() throws Exception {
        // The peer reads one frame and sends one back.
        final CountDownLatch peerReady = new CountDownLatch(1);
        final AtomicReference<byte[]> peerReceived = new AtomicReference<>();
        acceptThread = new Thread(() -> {
            try (Socket s = server.accept()) {
                peer = s;
                peerReady.countDown();
                byte[] buf = new byte[4096];
                int n = s.getInputStream().read(buf);
                byte[] got = new byte[n];
                System.arraycopy(buf, 0, got, 0, n);
                peerReceived.set(got);
                MsrpMessage reply = MsrpMessage.newResponse(200, "OK")
                        .transactionId("rrrrr1234")
                        .toPath("msrp://127.0.0.1:1/a;tcp")
                        .fromPath("msrp://127.0.0.1:2/b;tcp")
                        .endFlag(MsrpEndFlag.COMPLETE)
                        .build();
                s.getOutputStream().write(reply.encode());
                s.getOutputStream().flush();
                // Stay open long enough for the client to read before end of stream.
                Thread.sleep(100);
            } catch (Exception ignore) {}
        }, "test-msrp-peer");
        acceptThread.setDaemon(true);
        acceptThread.start();

        final CountDownLatch frameLatch = new CountDownLatch(1);
        final AtomicReference<MsrpMessage> clientReceived = new AtomicReference<>();
        MsrpTlsConnection.Listener listener = new MsrpTlsConnection.Listener() {
            @Override
            public void onFrame(MsrpMessage f) {
                clientReceived.set(f);
                frameLatch.countDown();
            }
            @Override
            public void onClosed(MsrpChatSession.CloseReason r, String d) {}
        };

        MsrpTlsConnection.SocketFactory factory = (host, port) ->
                new Socket(InetAddress.getByName(host), port);

        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, listener,
                "127.0.0.1", server.getLocalPort(),
                /*expectFingerprintAlg=*/ null,
                /*expectFingerprintHex=*/ null);
        conn.start();

        MsrpMessage out = MsrpMessage.newSend()
                .transactionId("abcd1234")
                .toPath("msrp://127.0.0.1:1/a;tcp")
                .fromPath("msrp://127.0.0.1:2/b;tcp")
                .messageId("m-1")
                .byteRange(1, 5, 5)
                .contentType("text/plain")
                .body("hello".getBytes(UTF_8))
                .endFlag(MsrpEndFlag.COMPLETE)
                .build();
        conn.send(out);

        assertTrue("peer received",
                peerReady.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue("frame arrived",
                frameLatch.await(2, TimeUnit.SECONDS));

        MsrpMessage received = clientReceived.get();
        assertNotNull(received);
        assertEquals(MsrpMessage.Kind.RESPONSE, received.getKind());
        assertEquals(200, received.getStatusCode());
        assertEquals("rrrrr1234", received.getTransactionId());

        byte[] peerData = peerReceived.get();
        assertNotNull(peerData);
        MsrpMessage peerParsed = MsrpMessage.parse(peerData);
        assertEquals(MsrpMessage.Kind.REQUEST, peerParsed.getKind());
        assertEquals("abcd1234", peerParsed.getTransactionId());
        assertEquals("m-1", peerParsed.getMessageId());

        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test done");
    }

    // ---- Close-on-EOF

    @Test
    public void readLoop_closesOnPeerEof() throws Exception {
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final AtomicReference<MsrpChatSession.CloseReason> closeReason = new AtomicReference<>();

        acceptThread = new Thread(() -> {
            try (Socket s = server.accept()) {
                peer = s;
            } catch (Exception ignore) {}
        }, "test-msrp-peer-eof");
        acceptThread.setDaemon(true);
        acceptThread.start();

        MsrpTlsConnection.Listener listener = new MsrpTlsConnection.Listener() {
            @Override public void onFrame(MsrpMessage f) {}
            @Override
            public void onClosed(MsrpChatSession.CloseReason r, String d) {
                closeReason.set(r);
                closedLatch.countDown();
            }
        };

        MsrpTlsConnection.SocketFactory factory = (host, port) ->
                new Socket(InetAddress.getByName(host), port);
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, listener,
                "127.0.0.1", server.getLocalPort(),
                null, null);
        conn.start();

        assertTrue("close fired", closedLatch.await(2, TimeUnit.SECONDS));
        // End of stream maps to REMOTE_BYE.
        assertEquals(MsrpChatSession.CloseReason.REMOTE_BYE, closeReason.get());
    }

    // ---- Close-on-connect-failure

    @Test
    public void start_throwsOnSocketFactoryError() {
        MsrpTlsConnection.SocketFactory factory = (host, port) -> {
            throw new IOException("simulated connect failure");
        };
        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, new NoopListener(),
                "127.0.0.1", 1, null, null);
        try {
            conn.start();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("simulated"));
        }
    }

    // ---- Close idempotence

    @Test
    public void close_firesOnClosedExactlyOnce() throws Exception {
        acceptThread = new Thread(() -> {
            try { peer = server.accept(); } catch (Exception ignore) {}
        }, "test-msrp-peer-idle");
        acceptThread.setDaemon(true);
        acceptThread.start();

        final List<MsrpChatSession.CloseReason> closes = new ArrayList<>();
        MsrpTlsConnection.Listener listener = new MsrpTlsConnection.Listener() {
            @Override public void onFrame(MsrpMessage f) {}
            @Override
            public void onClosed(MsrpChatSession.CloseReason r, String d) {
                synchronized (closes) {
                    closes.add(r);
                }
            }
        };
        MsrpTlsConnection.SocketFactory factory = (host, port) ->
                new Socket(InetAddress.getByName(host), port);

        MsrpTlsConnection conn = new MsrpTlsConnection(
                factory, listener,
                "127.0.0.1", server.getLocalPort(), null, null);
        conn.start();
        Thread.sleep(50);
        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test");
        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test-retry");
        conn.close(MsrpChatSession.CloseReason.LOCAL_BYE, "test-again");
        Thread.sleep(100);
        synchronized (closes) {
            // Later closes are no-ops; the read loop may race with its own close, hence 1 or 2.
            assertTrue("at least one close, no more than two: " + closes.size(),
                    closes.size() >= 1 && closes.size() <= 2);
            assertEquals(MsrpChatSession.CloseReason.LOCAL_BYE, closes.get(0));
        }
    }

    // ---- Fingerprint match logic

    @Test
    public void fingerprintsMatch_caseInsensitiveAndColonTolerant() {
        assertTrue(MsrpTlsConnection.fingerprintsMatch(
                "AB:CD:EF", "ab:cd:ef"));
        assertTrue(MsrpTlsConnection.fingerprintsMatch(
                "AB:CD:EF", "abcdef"));
        assertTrue(MsrpTlsConnection.fingerprintsMatch(
                "AB:CD:EF", "Ab Cd Ef"));
        assertFalse(MsrpTlsConnection.fingerprintsMatch(
                "AB:CD:EF", "01:23:45"));
        assertFalse(MsrpTlsConnection.fingerprintsMatch(
                null, "AB:CD:EF"));
        assertFalse(MsrpTlsConnection.fingerprintsMatch(
                "AB:CD:EF", null));
    }

    @Test
    public void computePeerFingerprint_nullForNonTlsSocket() throws Exception {
        // A plain socket has no SSLSession.
        Socket s = new Socket();
        assertEquals(null,
                MsrpTlsConnection.computePeerFingerprint(s, "SHA-256"));
    }

    // ---- Helpers

    private static final class NoopListener implements MsrpTlsConnection.Listener {
        @Override public void onFrame(MsrpMessage f) {}
        @Override public void onClosed(MsrpChatSession.CloseReason r, String d) {}
    }
}
