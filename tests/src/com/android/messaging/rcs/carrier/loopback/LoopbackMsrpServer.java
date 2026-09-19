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

import com.android.messaging.rcs.carrier.msrp.MsrpException;
import com.android.messaging.rcs.carrier.msrp.MsrpFrameReader;
import com.android.messaging.rcs.carrier.msrp.MsrpHeaders;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;
import com.android.messaging.rcs.carrier.msrp.MsrpMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * In-process MSRP-over-TLS receiver for the loopback harness. Test scaffolding.
 *
 * <p>Listens on a {@link SSLServerSocket} bound to {@code 127.0.0.1:0} (or a
 * caller-supplied port). For each accepted connection, spawns a reader thread
 * that frames inbound MSRP traffic with the production {@link MsrpFrameReader}
 * library. Behavior per frame:
 *
 * <ul>
 *   <li><b>SEND</b> → records the body + headers in {@link #receivedSends},
 *       sends back {@code MSRP &lt;tid&gt; 200 OK}. If {@code Success-Report: yes}
 *       was set, also emits a {@code REPORT} request with {@code Status: 000 200 OK}
 *       referencing the SEND's Message-ID and Byte-Range. The 200 response /
 *       REPORT behavior matches what Google Messages' MSRP sender does on the
 *       wire, so {@code MsrpFrameReader}-driven
 *       clients see realistic responses.</li>
 *   <li><b>REPORT</b> → recorded; no response (REPORTs are bodiless acks).</li>
 *   <li><b>Response</b> (200 etc.) → recorded; no further action.</li>
 * </ul>
 *
 * <p>Failure-injection knobs: {@link Builder#sendStatusCodeForNextSends(int, String)}
 * makes the server respond with the supplied non-200 code to the next N SEND
 * frames, exercising the client's error path.
 *
 * <p>Threading: the accept loop and each connection's reader thread are
 * daemons spawned by {@link #start()}. {@link #close()} drains them with a
 * short join timeout; pending accept calls are interrupted by closing the
 * server socket.
 *
 * <p>Pure Java, no Android imports. Used only by host JUnit tests.
 */
final class LoopbackMsrpServer implements AutoCloseable {

    private final SSLServerSocket serverSocket;
    private final LoopbackCertGen cert;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread acceptThread;

    /** Reported by the harness API. Insertion-ordered, thread-safe. */
    private final List<MsrpMessage> receivedSends = new CopyOnWriteArrayList<>();
    private final List<MsrpMessage> receivedReports = new CopyOnWriteArrayList<>();
    private final List<MsrpMessage> receivedResponses = new CopyOnWriteArrayList<>();
    private final List<SSLSocket> openSockets = new CopyOnWriteArrayList<>();
    private final List<Thread> readerThreads = new CopyOnWriteArrayList<>();

    private final int sendFailStatus;
    private final String sendFailReason;
    private int sendFailRemaining;
    private final Object sendFailLock = new Object();

    private LoopbackMsrpServer(SSLServerSocket s, LoopbackCertGen cert,
            int sendFailStatus, String sendFailReason, int sendFailCount) {
        this.serverSocket = s;
        this.cert = cert;
        this.sendFailStatus = sendFailStatus;
        this.sendFailReason = sendFailReason;
        this.sendFailRemaining = sendFailCount;
    }

    int getPort() { return serverSocket.getLocalPort(); }

    String getFingerprintSha256ColonHex() {
        return cert.getSha256FingerprintColonHex();
    }

    X509Certificate getCertificate() { return cert.getCertificate(); }

    List<MsrpMessage> getReceivedSends() {
        return Collections.unmodifiableList(new ArrayList<>(receivedSends));
    }

    List<MsrpMessage> getReceivedReports() {
        return Collections.unmodifiableList(new ArrayList<>(receivedReports));
    }

    List<MsrpMessage> getReceivedResponses() {
        return Collections.unmodifiableList(new ArrayList<>(receivedResponses));
    }

    void start() {
        acceptThread = new Thread(this::acceptLoop, "loopback-msrp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                SSLSocket s = (SSLSocket) serverSocket.accept();
                openSockets.add(s);
                Thread t = new Thread(() -> connectionLoop(s),
                        "loopback-msrp-conn-" + s.getPort());
                t.setDaemon(true);
                readerThreads.add(t);
                t.start();
            } catch (IOException e) {
                if (closed.get()) return;
                // Otherwise keep looping; transient accept failures shouldn't kill
                // the server in tests.
            }
        }
    }

    private void connectionLoop(SSLSocket s) {
        try {
            // Force the handshake so any cert errors surface inside the test's
            // lifetime rather than after we try to write.
            s.startHandshake();
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            MsrpFrameReader reader = new MsrpFrameReader();
            byte[] buf = new byte[4096];
            while (!closed.get() && !s.isClosed()) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException ioe) {
                    return;
                }
                if (n < 0) return;
                if (n == 0) continue;
                List<MsrpMessage> frames;
                try {
                    frames = reader.append(buf, 0, n);
                } catch (MsrpException me) {
                    return;
                }
                for (MsrpMessage f : frames) {
                    handleFrame(f, out);
                }
            }
        } catch (IOException ignore) {
            // Connection-level failure; close and move on.
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    private void handleFrame(MsrpMessage frame, OutputStream out) throws IOException {
        if (frame.isResponse()) {
            receivedResponses.add(frame);
            return;
        }
        MsrpMethod method = frame.getMethod();
        if (method == MsrpMethod.SEND) {
            receivedSends.add(frame);
            int code;
            String reason;
            synchronized (sendFailLock) {
                if (sendFailRemaining > 0) {
                    sendFailRemaining--;
                    code = sendFailStatus;
                    reason = sendFailReason;
                } else {
                    code = 200;
                    reason = "OK";
                }
            }
            // Respond to SEND with a status response per RFC 4975 §7.1.
            MsrpMessage resp = MsrpMessage.newResponse(code, reason)
                    .transactionId(frame.getTransactionId())
                    .toPath(frame.getFromPath())
                    .fromPath(frame.getToPath())
                    .build();
            out.write(resp.encode());
            out.flush();
            // Optionally emit a REPORT 200 if the sender asked for success
            // reports (RFC 4975 §7.1.1 — Google Messages does this).
            if (code == 200) {
                String successReport = frame.getSuccessReport();
                if (MsrpHeaders.SUCCESS_REPORT_YES.equalsIgnoreCase(successReport)) {
                    String byteRange = frame.getByteRange();
                    if (byteRange == null) byteRange = "1-" + frame.getBodyLength()
                            + "/" + frame.getBodyLength();
                    MsrpMessage report = MsrpMessage.newReport()
                            .toPath(frame.getFromPath())
                            .fromPath(frame.getToPath())
                            .messageId(frame.getMessageId())
                            .byteRange(byteRange)
                            .status(200, "OK")
                            .build();
                    out.write(report.encode());
                    out.flush();
                }
            }
        } else if (method == MsrpMethod.REPORT) {
            receivedReports.add(frame);
            // REPORTs are themselves bodiless acks — no response per RFC 4975 §7.1.2.
        } else {
            // AUTH or unknown — just ack with 501 Not Implemented (RFC 4975 §7.4).
            MsrpMessage resp = MsrpMessage.newResponse(501, "Not Implemented")
                    .transactionId(frame.getTransactionId())
                    .toPath(frame.getFromPath())
                    .fromPath(frame.getToPath())
                    .build();
            out.write(resp.encode());
            out.flush();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { serverSocket.close(); } catch (IOException ignore) {}
        for (SSLSocket s : openSockets) {
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
        private int port = 0;
        private String dn = "CN=loopback.test,OU=MessagingTests,O=LineageOS";
        private int rsaBits = 2048;
        private long validitySec = 3600;
        private int sendFailStatus = 0;
        private String sendFailReason;
        private int sendFailCount;

        Builder port(int p) { this.port = p; return this; }
        Builder dn(String d) { this.dn = d; return this; }
        Builder rsaBits(int b) { this.rsaBits = b; return this; }
        Builder validitySec(long s) { this.validitySec = s; return this; }

        /** Make the server respond to the next {@code count} SEND frames with
         *  {@code status reason} instead of 200 OK. After {@code count} failures
         *  it reverts to 200 OK. Used to exercise the client's error path. */
        Builder sendStatusCodeForNextSends(int status, String reason, int count) {
            this.sendFailStatus = status;
            this.sendFailReason = reason;
            this.sendFailCount = count;
            return this;
        }

        LoopbackMsrpServer build() throws Exception {
            LoopbackCertGen cert = LoopbackCertGen.generate(dn, rsaBits, validitySec);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("loopback", cert.getPrivateKey(),
                    "loopback".toCharArray(),
                    new Certificate[]{cert.getCertificate()});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "loopback".toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            // We do NOT require client cert; this is a server-auth-only TLS
            // session (the MSRP client validates us via RFC 4572 fingerprint).
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{noopTrustManager()},
                    new SecureRandom());
            SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
            SSLServerSocket ss = (SSLServerSocket) ssf.createServerSocket(
                    port, 16, InetAddress.getByName("127.0.0.1"));
            return new LoopbackMsrpServer(ss, cert,
                    sendFailStatus, sendFailReason, sendFailCount);
        }

        private static X509TrustManager noopTrustManager() {
            return new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String s) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String s) {}
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
        }
    }
}
