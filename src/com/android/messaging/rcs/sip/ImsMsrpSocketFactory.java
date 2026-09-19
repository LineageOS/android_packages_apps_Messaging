/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.android.messaging.rcs.carrier.msrp.session.MsrpTlsConnection;
import com.android.messaging.util.LogUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Opens the MSRP media socket to the relay for an SR chat session, bound to an IMS-capable
 * cellular {@link Network} so it leaves on the IMS bearer; signalling rides the delegate.
 *
 * <p>Any server certificate is trusted at the TLS layer: relays present certificates for opaque
 * private hosts, and {@link MsrpTlsConnection} checks the peer against the SDP fingerprint
 * (RFC 4572) after the handshake.
 */
public final class ImsMsrpSocketFactory implements MsrpTlsConnection.SocketFactory {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "ImsMsrpSocketFactory";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int NETWORK_WAIT_MS = 8000;

    private final Context mContext;
    private final boolean mTls;
    /** The TLS client certificate, advertised by its SDP fingerprint; null for cleartext. */
    private final MsrpClientCredentials mCreds;
    /** Set by the first connect: the local address to advertise in SDP. */
    private volatile String mLocalAddress;
    private volatile int mLocalPort;

    public ImsMsrpSocketFactory(Context context, boolean tls, MsrpClientCredentials creds) {
        this.mContext = context.getApplicationContext();
        this.mTls = tls;
        this.mCreds = creds;
    }

    public String getLocalAddress() { return mLocalAddress; }
    public int getLocalPort() { return mLocalPort; }

    @Override
    public Socket connect(String host, int port) throws IOException {
        final Network imsNet = requestImsNetwork();
        Socket raw = new Socket();
        try {
            if (imsNet != null) {
                imsNet.bindSocket(raw);
                LogUtil.i(TAG, SUBTAG + ": socket bound to IMS network " + imsNet);
            } else {
                LogUtil.w(TAG, SUBTAG + ": no IMS network; using default route");
            }
            raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            mLocalAddress = raw.getLocalAddress().getHostAddress();
            mLocalPort = raw.getLocalPort();
            LogUtil.i(TAG, SUBTAG + ": TCP connected " + raw.getLocalSocketAddress()
                    + " -> " + host + ":" + port);

            if (!mTls) {
                return raw;
            }
            SSLSocketFactory sf = (mCreds != null)
                    ? mCreds.sslSocketFactory()
                    : trustAllSslContext().getSocketFactory();
            SSLSocket tls = (SSLSocket) sf.createSocket(raw, host, port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();
            LogUtil.i(TAG, SUBTAG + ": TLS handshake ok proto="
                    + tls.getSession().getProtocol() + " cipher="
                    + tls.getSession().getCipherSuite());
            return tls;
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignore) {}
            throw e;
        } catch (Exception e) {
            try { raw.close(); } catch (IOException ignore) {}
            throw new IOException("MSRP TLS setup failed: " + e.getMessage(), e);
        }
    }

    /** Waits up to {@link #NETWORK_WAIT_MS} for an IMS-capable cellular network; null if none. */
    private Network requestImsNetwork() {
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        final NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();
        final AtomicReference<Network> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final ConnectivityManager.NetworkCallback cb =
                new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                out.compareAndSet(null, network);
                latch.countDown();
            }
        };
        try {
            cm.requestNetwork(req, cb, NETWORK_WAIT_MS);
            latch.await(NETWORK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (SecurityException se) {
            LogUtil.w(TAG, SUBTAG
                    + ": requestNetwork denied (CONNECTIVITY_USE_RESTRICTED_NETWORKS?)"
                    + " falling back to active network", se);
            // The IMS network may already be the active one on a cellular-only device.
            Network active = cm.getActiveNetwork();
            return active;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // The request stays registered to hold the network up for the socket's lifetime;
        // #release unregisters it at session teardown.
        mPendingCallback.set(cb);
        return out.get();
    }

    private final AtomicReference<ConnectivityManager.NetworkCallback> mPendingCallback =
            new AtomicReference<>();

    /** Releases the held network request; call at session teardown. */
    public void release() {
        ConnectivityManager.NetworkCallback cb = mPendingCallback.getAndSet(null);
        if (cb != null) {
            try {
                ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
                if (cm != null) cm.unregisterNetworkCallback(cb);
            } catch (Throwable ignore) {
            }
        }
    }

    private static SSLContext trustAllSslContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }
}
