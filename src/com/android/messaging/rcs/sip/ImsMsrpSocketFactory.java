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
 * Opens the MSRP-over-TLS media socket to the relay, bound to the IMS-capable
 * cellular {@link Network}, for a CPM SESSION-mode chat. This is the SEPARATE
 * media transport (NOT over the SipDelegate): the SIP signaling rides the
 * delegate; the MSRP bytes ride this socket.
 *
 * <p>Network steering: Google Messages connects on the
 * default network then QoS-promotes the flow onto a dedicated bearer. On our
 * cellular-only test devices the default route is already the cellular PDN, but
 * per {@code project_test_devices_always_cellular} we bind explicitly to an
 * IMS-capable cellular Network via {@link ConnectivityManager#requestNetwork}
 * so the socket egresses on the IMS APN rather than the internet APN.
 *
 * <p>TLS trust: MSRP-over-TLS auth is the RFC 4572 SDP {@code a=fingerprint:}
 * binding (validated post-handshake by {@link MsrpTlsConnection} against the
 * peer's SDP answer), NOT PKI/hostname verification — RCS relays present certs
 * for opaque private hosts. So this factory installs a trust-all
 * {@link X509TrustManager}; the fingerprint check in {@link MsrpTlsConnection}
 * is the real gate (memory {@code msrp-tls-auth-fingerprint-not-hostname}).
 */
public final class ImsMsrpSocketFactory implements MsrpTlsConnection.SocketFactory {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "ImsMsrpSocketFactory";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int NETWORK_WAIT_MS = 8000;

    private final Context mContext;
    private final boolean mTls;
    /** Client credentials presented in the TLS handshake (cert bound via SDP
     *  fingerprint). Null for cleartext (TCP/MSRP). */
    private final MsrpClientCredentials mCreds;
    /** Captured after the first connect — the local address we advertise in SDP. */
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

    /**
     * Request an IMS-capable cellular network and block briefly for it. Returns
     * null if none becomes available in {@link #NETWORK_WAIT_MS}.
     */
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
            LogUtil.w(TAG, SUBTAG + ": requestNetwork denied (CONNECTIVITY_USE_RESTRICTED_NETWORKS?)"
                    + " falling back to active network", se);
            // Fallback: the already-up IMS network may be the active one on a
            // cellular-only device.
            Network active = cm.getActiveNetwork();
            // Don't unregister here; let it ride. Return active as best-effort.
            return active;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // Note: we intentionally do NOT unregister cb immediately — keeping the
        // request alive holds the IMS network up for the socket lifetime. The
        // engine unregisters on session teardown via #release.
        mPendingCallback.set(cb);
        return out.get();
    }

    private final AtomicReference<ConnectivityManager.NetworkCallback> mPendingCallback =
            new AtomicReference<>();

    /** Release the held IMS NetworkRequest (call on session teardown). */
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
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }
}
