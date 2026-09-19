/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The socket of one MSRP chat session: writes encoded frames, reads with {@link MsrpFrameReader}
 * on its own thread, and hands whole frames to a {@link Listener}. When an SDP fingerprint is
 * expected, the peer is authenticated by it (RFC 4572 §5) rather than by host name, since relay
 * paths use private addresses. Writes are serialised by a lock.
 */
public final class MsrpTlsConnection {

    public interface Listener {
        void onFrame(MsrpMessage frame);

        /** The socket closed or a read or parse failed; the connection is dead. */
        void onClosed(MsrpChatSession.CloseReason reason, String detail);
    }

    /**
     * Opens the socket and completes any handshake before returning, so the peer chain is readable.
     */
    public interface SocketFactory {
        Socket connect(String host, int port) throws IOException;
    }

    private final SocketFactory socketFactory;
    private final Listener listener;
    private final String remoteHost;
    private final int remotePort;
    /** Expected peer fingerprint (RFC 4572 algorithm and hex); null skips the check. */
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

    /** Connects, checks the fingerprint if one is expected, and starts the read thread. */
    public void start() throws IOException {
        if (closed.get()) {
            throw new IOException("MsrpTlsConnection: already closed");
        }
        socket = socketFactory.connect(remoteHost, remotePort);
        in = socket.getInputStream();
        out = socket.getOutputStream();

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

    /** As {@link #start}, for a socket the answerer side has already accepted. */
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

    /** Thread-safe. */
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

    /** Closes the socket and calls {@code onClosed} exactly once. */
    public void close(MsrpChatSession.CloseReason reason, String detail) {
        if (!closed.compareAndSet(false, true)) return;
        closeSilently();
        try {
            listener.onClosed(reason, detail);
        } catch (Throwable t) {
        }
    }

    private void closeSilently() {
        try { if (socket != null) socket.close(); } catch (IOException ignore) {}
    }

    public boolean isClosed() {
        return closed.get();
    }

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
                        // A listener failure must not end the read loop.
                    }
                }
            }
        } finally {
            if (!closed.get()) {
                close(MsrpChatSession.CloseReason.TLS_ERROR, "read loop exited");
            }
        }
    }

    /**
     * The leaf certificate's fingerprint in RFC 4572 §5 form; null if not TLS or no certificate.
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

    /** Maps an SDP hash name to a JCE one, accepting forms without the hyphen. */
    private static String canonicalAlg(String alg) {
        if (alg == null) return "SHA-256";
        String u = alg.toUpperCase(Locale.ROOT);
        if (u.equals("SHA256")) return "SHA-256";
        if (u.equals("SHA1") || u.equals("SHA-1")) return "SHA-1";
        return u;
    }

    /** Compares fingerprints ignoring colons, whitespace and case. */
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
