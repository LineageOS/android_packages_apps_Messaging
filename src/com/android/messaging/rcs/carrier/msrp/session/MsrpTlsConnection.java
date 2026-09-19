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

import com.android.messaging.rcs.carrier.msrp.MsrpException;
import com.android.messaging.rcs.carrier.msrp.MsrpFrameReader;
import com.android.messaging.rcs.carrier.msrp.MsrpMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the TCP/TLS socket for a single MSRP chat session. Frames out using
 * {@link MsrpMessage#encode()} and frames in using {@link MsrpFrameReader},
 * dispatching whole frames to a {@link Listener}.
 *
 * <p>Authenticates the peer cert by binding to the SDP {@code a=fingerprint:}
 * attribute (RFC 4572 §5) — NOT by hostname verification. RCS MSRP peer
 * URIs ({@code msrps://1.2.3.4:5678/opaque-session-id;tcp}) use private
 * addresses and opaque session-ids; hostname verification would fail.
 * The fingerprint binding is the spec-compliant auth mechanism for MSRP TLS
 * sessions and is what Google Messages' own validator does.
 *
 * <p>Pure java.net + javax.net.ssl + java.security — no Android imports, so
 * the class is host-testable against a real or mock {@link Socket}/
 * {@link javax.net.ssl.SSLSocket}.
 *
 * <p>Threading: one socket, one read thread spawned by {@link #start}. Write
 * calls ({@link #send(MsrpMessage)}, {@link #sendBytes}) are externally
 * serialized by the caller — the {@link CarrierMsrpSessionManager}'s
 * SIP thread is the canonical writer. We use a per-connection write lock
 * to defend against concurrent writers (defensive — the design says you
 * shouldn't, but a buggy caller would otherwise produce torn frames).
 *
 * <p>TODO: cert pinning beyond the RFC 4572 SDP-fingerprint binding.
 * RFC 4572 binding is sufficient per the spec but production deployments
 * may want pinned trust anchors.
 */
public final class MsrpTlsConnection {

    /** Observer for inbound frames and connection lifecycle events. */
    public interface Listener {
        /** A complete MSRP frame was framed off the wire. */
        void onFrame(MsrpMessage frame);

        /** The socket dropped or read/parse failed. Connection is dead. */
        void onClosed(MsrpChatSession.CloseReason reason, String detail);
    }

    /**
     * Pluggable strategy that opens the socket. Real impl uses
     * {@link javax.net.ssl.SSLSocketFactory#getDefault()}; tests use a
     * loopback {@code ServerSocket} pair without TLS. The factory must
     * complete the handshake (or whatever connection-level auth it needs)
     * before returning so {@link #peerCertificates(Socket)} can read out
     * the peer's cert chain.
     */
    public interface SocketFactory {
        Socket connect(String host, int port) throws IOException;
    }

    private final SocketFactory socketFactory;
    private final Listener listener;
    private final String remoteHost;
    private final int remotePort;
    /** Expected peer cert fingerprint (RFC 4572 alg + hex). When null, the
     *  connection skips fingerprint validation (used by cleartext MSRP /
     *  test loopback). */
    private final String expectFingerprintAlg;
    private final String expectFingerprintHex;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public MsrpTlsConnection(SocketFactory socketFactory, Listener listener,
            String remoteHost, int remotePort,
            String expectFingerprintAlg, String expectFingerprintHex) {
        if (socketFactory == null) throw new IllegalArgumentException("socketFactory");
        if (listener == null) throw new IllegalArgumentException("listener");
        if (remoteHost == null) throw new IllegalArgumentException("remoteHost");
        this.socketFactory = socketFactory;
        this.listener = listener;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.expectFingerprintAlg = expectFingerprintAlg;
        this.expectFingerprintHex = expectFingerprintHex;
    }

    /**
     * Connect, optionally validate peer fingerprint, spawn read thread.
     * Throws if the socket connect fails or the fingerprint mismatches.
     */
    public void start() throws IOException {
        if (closed.get()) {
            throw new IOException("MsrpTlsConnection: already closed");
        }
        socket = socketFactory.connect(remoteHost, remotePort);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        // Validate peer fingerprint per RFC 4572 §5.
        if (expectFingerprintAlg != null && expectFingerprintHex != null) {
            String peerHex = computePeerFingerprint(socket, expectFingerprintAlg);
            if (peerHex == null) {
                closeSilently();
                throw new IOException("MsrpTlsConnection: peer presented no certificate; "
                        + "cannot validate RFC 4572 a=fingerprint");
            }
            if (!fingerprintsMatch(expectFingerprintHex, peerHex)) {
                closeSilently();
                throw new IOException("MsrpTlsConnection: peer fingerprint mismatch; "
                        + "expected=" + expectFingerprintHex
                        + " actual=" + peerHex);
            }
        }

        readerThread = new Thread(this::readLoop,
                "rcs-msrp-rx-" + remoteHost + ":" + remotePort);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Adopt an already-connected socket (used on the passive/answerer side
     *  where the manager accepted the connection on its bound port). */
    public void adopt(Socket connected) throws IOException {
        if (closed.get()) {
            throw new IOException("MsrpTlsConnection: already closed");
        }
        this.socket = connected;
        this.in = connected.getInputStream();
        this.out = connected.getOutputStream();

        if (expectFingerprintAlg != null && expectFingerprintHex != null) {
            String peerHex = computePeerFingerprint(connected, expectFingerprintAlg);
            if (peerHex == null) {
                closeSilently();
                throw new IOException("MsrpTlsConnection: peer presented no certificate");
            }
            if (!fingerprintsMatch(expectFingerprintHex, peerHex)) {
                closeSilently();
                throw new IOException("MsrpTlsConnection: peer fingerprint mismatch");
            }
        }

        readerThread = new Thread(this::readLoop,
                "rcs-msrp-rx-adopted-" + connected.getInetAddress());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Send an MSRP frame. Thread-safe. */
    public void send(MsrpMessage frame) throws IOException {
        if (frame == null) throw new IllegalArgumentException("frame");
        sendBytes(frame.encode());
    }

    public void sendBytes(byte[] wire) throws IOException {
        if (closed.get()) throw new IOException("MsrpTlsConnection: closed");
        synchronized (writeLock) {
            out.write(wire);
            out.flush();
        }
    }

    /** Close the socket and dispatch onClosed exactly once. */
    public void close(MsrpChatSession.CloseReason reason, String detail) {
        if (!closed.compareAndSet(false, true)) return;
        closeSilently();
        try {
            listener.onClosed(reason, detail);
        } catch (Throwable t) {
            // Listener problems should never re-enter close; swallow.
        }
    }

    private void closeSilently() {
        try { if (socket != null) socket.close(); } catch (IOException ignore) {}
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ---------- read loop ----------

    private void readLoop() {
        final MsrpFrameReader reader = new MsrpFrameReader();
        final byte[] buf = new byte[4096];
        try {
            while (!closed.get()) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException ioe) {
                    if (closed.get()) return;
                    close(MsrpChatSession.CloseReason.TLS_ERROR,
                            "read: " + ioe.getClass().getSimpleName()
                                    + ": " + ioe.getMessage());
                    return;
                }
                if (n < 0) {
                    close(MsrpChatSession.CloseReason.REMOTE_BYE, "EOF");
                    return;
                }
                if (n == 0) continue;
                List<MsrpMessage> frames;
                try {
                    frames = reader.append(buf, 0, n);
                } catch (MsrpException me) {
                    close(MsrpChatSession.CloseReason.PEER_ERROR,
                            "parse: " + me.getMessage());
                    return;
                }
                for (MsrpMessage f : frames) {
                    try {
                        listener.onFrame(f);
                    } catch (Throwable t) {
                        // Don't let a listener bug kill the connection's read
                        // loop; log via the reason channel and continue.
                    }
                }
            }
        } finally {
            // Defensive: if the loop exited without an explicit close, mark closed.
            if (!closed.get()) {
                close(MsrpChatSession.CloseReason.TLS_ERROR, "read loop exited");
            }
        }
    }

    // ---------- fingerprint plumbing ----------

    /**
     * Extract the peer's certificate (the leaf) and hash it with the named
     * algorithm. Returns the colon-separated hex fingerprint form RFC 4572
     * §5 mandates. Returns null if the socket is not TLS or the peer
     * presented no certificate.
     */
    static String computePeerFingerprint(Socket socket, String alg) {
        if (!(socket instanceof javax.net.ssl.SSLSocket)) return null;
        javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) socket;
        try {
            Certificate[] chain = s.getSession().getPeerCertificates();
            if (chain == null || chain.length == 0) return null;
            Certificate leaf = chain[0];
            byte[] encoded;
            if (leaf instanceof X509Certificate) {
                encoded = ((X509Certificate) leaf).getEncoded();
            } else {
                encoded = leaf.getEncoded();
            }
            MessageDigest md = MessageDigest.getInstance(canonicalAlg(alg));
            return SdpOffer.formatFingerprint(md.digest(encoded));
        } catch (javax.net.ssl.SSLPeerUnverifiedException pue) {
            return null;
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Translate SDP fingerprint alg ("SHA-256") to JCE ("SHA-256"). */
    private static String canonicalAlg(String alg) {
        if (alg == null) return "SHA-256";
        // SDP form is "SHA-256"; JCE accepts the same. Normalize hyphenless variants too.
        String u = alg.toUpperCase(Locale.ROOT);
        if (u.equals("SHA256")) return "SHA-256";
        if (u.equals("SHA1") || u.equals("SHA-1")) return "SHA-1";
        return u;
    }

    /**
     * Compare two RFC 4572 fingerprint hex strings, tolerating colons and
     * whitespace and case differences (the only meaningful difference is
     * the raw bytes).
     */
    static boolean fingerprintsMatch(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String a = strip(expected);
        String b = strip(actual);
        return a.equalsIgnoreCase(b);
    }

    private static String strip(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && !Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }
}
