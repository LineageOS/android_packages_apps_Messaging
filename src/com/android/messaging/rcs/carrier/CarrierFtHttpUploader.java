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
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GSMA RCC.07 File-Transfer-over-HTTP content-server UPLOAD client for the
 * carrier-IMS DR path. Uploads a file to {@code ftHTTPCSURI} as a
 * {@code multipart/form-data} POST and returns the server's
 * {@code application/vnd.gsma.rcs-ft-http+xml} descriptor (which carries the
 * {@code <data url=..>} the peer downloads from).
 *
 * <p><b>Auth:</b> HTTP Digest (RFC 2617/7616), NOT Basic — the lab content
 * server challenges with {@code WWW-Authenticate: Digest} and the credentials are
 * the {@code ftHTTPCSUser}/{@code ftHTTPCSPwd} the ACS provisioned. The Digest
 * response is computed with the same {@link SipDigestAuth} MD5 math the SIP
 * registrar uses (HTTP and SIP Digest are the same algorithm; only the "method"
 * and request-URI differ).
 *
 * <p><b>Network:</b> runs in the {@code :ims} process, which
 * {@link CarrierSipRegistrar#acquireImsNetwork} has bound to the IMS APN
 * ({@code bindProcessToNetwork}); a plain {@link HttpURLConnection} therefore
 * resolves the {@code ft.rcs...} FQDN via the IMS-APN DNS and egresses the IMS
 * bearer, same as the SIP path.
 *
 * <p>Only the initial single-shot multipart upload is implemented here; the
 * RCC.07 resumable {@code PUT /ul/{token}} resume flow is a follow-up (test files
 * are small enough to complete in one POST).
 */
public final class CarrierFtHttpUploader {

    private static final String TAG = "CarrierFtHttp";
    private static final String BOUNDARY = "----rcsFtBoundary8f2a1c";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    /** Result of a successful upload: the raw ft-http+xml descriptor bytes plus
     *  the HTTP status of the final (authenticated) response. */
    public static final class Result {
        public final int httpStatus;
        public final byte[] descriptorXml;   // application/vnd.gsma.rcs-ft-http+xml
        public final String contentType;

        Result(int httpStatus, byte[] descriptorXml, String contentType) {
            this.httpStatus = httpStatus;
            this.descriptorXml = descriptorXml;
            this.contentType = contentType;
        }
    }

    private final String csUri;
    private final String user;
    private final String password;
    /** When non-null, the TCP connect target IP; the request keeps the csUri's
     *  FQDN for the Host header, TLS SNI, and cert verification (connect-by-IP,
     *  present-by-name — used when the FT server's FQDN can't be resolved on the
     *  bearer, analogous to using the PCO-signalled P-CSCF IP). */
    @Nullable private final String connectIp;

    /** Internet-capable network the FT HTTP leg runs over — the FT content server +
     *  its DNS live on the DATA APN, not the IMS APN the :ims process is bound to, so
     *  we must egress the internet network explicitly (Network#openConnection
     *  overrides the process-wide bindProcessToNetwork). Null => process default. */
    @Nullable private final Network net;

    public CarrierFtHttpUploader(final String csUri, final String user,
            final String password) {
        this(csUri, user, password, null, null);
    }

    public CarrierFtHttpUploader(final String csUri, final String user,
            final String password, @Nullable final String connectIp,
            @Nullable final Network net) {
        this.csUri = csUri;
        this.user = user;
        this.password = password;
        this.connectIp = (connectIp != null && !connectIp.isEmpty()) ? connectIp : null;
        this.net = net;
    }

    /** Acquire an internet-capable cellular {@link Network} (the data APN) so the FT
     *  HTTP leg egresses it rather than the IMS-bound process default. Caller owns
     *  the returned request until release. Returns null if unavailable in time. */
    @Nullable
    public static Network acquireInternetNetwork(final Context ctx,
            final ConnectivityManager.NetworkCallback[] outCb) {
        try {
            final ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            final NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final Network[] holder = new Network[1];
            final ConnectivityManager.NetworkCallback cb =
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(final Network network) {
                            holder[0] = network;
                            latch.countDown();
                        }
                    };
            cm.requestNetwork(req, cb);
            outCb[0] = cb;
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "internet network not available in 10s");
            }
            return holder[0];
        } catch (final Throwable t) {
            Log.w(TAG, "acquireInternetNetwork failed: " + t);
            return null;
        }
    }

    /**
     * Upload {@code fileBytes} (named {@code fileName}, MIME {@code contentType})
     * to the content server. Performs the Digest challenge/response handshake:
     * first POST (unauthenticated) expects 401, then re-POSTs with the
     * Authorization. Returns the descriptor on 2xx, or null on failure.
     */
    @Nullable
    public Result upload(final byte[] fileBytes, final String fileName,
            final String contentType) {
        try {
            // Attempt 1 — unauthenticated, to collect the Digest challenge.
            final HttpURLConnection c1 = openPost(null);
            writeMultipart(c1, fileBytes, fileName, contentType);
            final int s1 = c1.getResponseCode();
            if (s1 != HttpURLConnection.HTTP_UNAUTHORIZED) {
                // Server accepted without auth (or hard error) — handle directly.
                return finish(c1, s1);
            }
            final String challenge = c1.getHeaderField("WWW-Authenticate");
            c1.disconnect();
            if (challenge == null || !challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                Log.w(TAG, "upload: 401 without a Digest challenge: " + challenge);
                return null;
            }

            // Attempt 2 — authenticated.
            final String authz = buildAuthorization(challenge, "POST", pathOf(csUri));
            final HttpURLConnection c2 = openPost(authz);
            writeMultipart(c2, fileBytes, fileName, contentType);
            final int s2 = c2.getResponseCode();
            return finish(c2, s2);
        } catch (final IOException | RuntimeException e) {
            Log.w(TAG, "upload failed: " + e);
            return null;
        }
    }

    @Nullable
    private Result finish(final HttpURLConnection c, final int status) throws IOException {
        final byte[] body = readBody(c);
        final String ct = c.getHeaderField("Content-Type");
        c.disconnect();
        if (status >= 200 && status < 300) {
            Log.i(TAG, "upload OK status=" + status + " ct=" + ct
                    + " descriptorLen=" + (body == null ? -1 : body.length));
            return new Result(status, body, ct);
        }
        Log.w(TAG, "upload non-2xx status=" + status + " ct=" + ct
                + " bodyLen=" + (body == null ? -1 : body.length));
        return null;
    }

    private HttpURLConnection openPost(@Nullable final String authorization)
            throws IOException {
        final HttpURLConnection c = open("POST", csUri, authorization);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + BOUNDARY);
        return c;
    }

    /** Open a connection to {@code urlStr} with the given HTTP {@code method},
     *  applying the connect-by-IP + SNI + data-APN egress rules and optional
     *  Digest Authorization. Shared by upload (POST) and download (GET). */
    private HttpURLConnection open(final String method, final String urlStr,
            @Nullable final String authorization) throws IOException {
        final URL orig = new URL(urlStr);
        final String fqdn = orig.getHost();
        // Connect-by-IP, present-by-name: swap the host to the connect IP but keep
        // the FQDN for Host header + TLS SNI/verification.
        final URL target = (connectIp != null)
                ? new URL(orig.getProtocol(), connectIp, orig.getPort(), orig.getFile())
                : orig;
        // Egress the data-APN network (FT server lives there, not the IMS APN).
        final HttpURLConnection c = (HttpURLConnection)
                (net != null ? net.openConnection(target) : target.openConnection());
        if (connectIp != null && c instanceof javax.net.ssl.HttpsURLConnection) {
            final javax.net.ssl.HttpsURLConnection https =
                    (javax.net.ssl.HttpsURLConnection) c;
            https.setSSLSocketFactory(new SniSocketFactory(fqdn));
            https.setHostnameVerifier((hostname, session) ->
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                            .verify(fqdn, session));
        }
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);
        if (connectIp != null) {
            c.setRequestProperty("Host", fqdn);
        }
        c.setRequestProperty("User-Agent", "IM-client/OMA1.0 lineageos-messaging/1.0");
        if (authorization != null) {
            c.setRequestProperty("Authorization", authorization);
        }
        return c;
    }

    /**
     * Download the content at {@code urlStr} (a {@code <data url>} from an
     * ft-http descriptor) with the same HTTP Digest handshake + connect-by-IP/SNI
     * + data-APN egress as upload. Returns the bytes on 2xx, else null.
     */
    @Nullable
    public byte[] download(final String urlStr) {
        try {
            final HttpURLConnection c1 = open("GET", urlStr, null);
            final int s1 = c1.getResponseCode();
            if (s1 != HttpURLConnection.HTTP_UNAUTHORIZED) {
                final byte[] b = (s1 >= 200 && s1 < 300) ? readBody(c1) : null;
                Log.i(TAG, "download status=" + s1 + " len=" + (b == null ? -1 : b.length));
                c1.disconnect();
                return b;
            }
            final String challenge = c1.getHeaderField("WWW-Authenticate");
            c1.disconnect();
            if (challenge == null || !challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                Log.w(TAG, "download: 401 without Digest challenge");
                return null;
            }
            final String authz = buildAuthorization(challenge, "GET", pathOf(urlStr));
            final HttpURLConnection c2 = open("GET", urlStr, authz);
            final int s2 = c2.getResponseCode();
            final byte[] b = (s2 >= 200 && s2 < 300) ? readBody(c2) : null;
            Log.i(TAG, "download(authed) status=" + s2 + " len=" + (b == null ? -1 : b.length));
            c2.disconnect();
            return b;
        } catch (final IOException | RuntimeException e) {
            Log.w(TAG, "download failed: " + e);
            return null;
        }
    }

    /** SSLSocketFactory that forces the TLS SNI server-name to {@code sniHost}
     *  (so a connection made to a bare IP still presents the FQDN in ClientHello,
     *  matching the server cert). Wraps the platform default. */
    private static final class SniSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate =
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        private final String sniHost;

        SniSocketFactory(final String sniHost) { this.sniHost = sniHost; }

        private java.net.Socket withSni(final java.net.Socket s) {
            if (s instanceof javax.net.ssl.SSLSocket) {
                final javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) s;
                final javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                p.setServerNames(java.util.Collections.singletonList(
                        new javax.net.ssl.SNIHostName(sniHost)));
                ssl.setSSLParameters(p);
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }
        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
        @Override public java.net.Socket createSocket(final java.net.Socket sock,
                final String host, final int port, final boolean autoClose)
                throws IOException {
            return withSni(delegate.createSocket(sock, host, port, autoClose));
        }
        @Override public java.net.Socket createSocket(final String host, final int port)
                throws IOException {
            return withSni(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(final String host, final int port,
                final java.net.InetAddress localHost, final int localPort) throws IOException {
            return withSni(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public java.net.Socket createSocket(final java.net.InetAddress host,
                final int port) throws IOException {
            return withSni(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(final java.net.InetAddress address,
                final int port, final java.net.InetAddress localAddress, final int localPort)
                throws IOException {
            return withSni(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    private void writeMultipart(final HttpURLConnection c, final byte[] fileBytes,
            final String fileName, final String contentType) throws IOException {
        // GSMA RCC.07 §4.2.6 content-server multipart, ORDER- and CASE-strict:
        //   1. name="tid"  (a canonical RFC 4122 UUID) — MUST be first.
        //   [2. name="Thumbnail" — optional, before File. Not sent here.]
        //   3. name="File" (capital F) — the file bytes, mandatory.
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        // Part 1: tid
        body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Disposition: form-data; name=\"tid\"\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write(java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        // Part 3: File
        body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"File\"; filename=\""
                + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(fileBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        final byte[] payload = body.toByteArray();
        c.setFixedLengthStreamingMode(payload.length);
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload);
            out.flush();
        }
    }

    @Nullable
    private static byte[] readBody(final HttpURLConnection c) throws IOException {
        InputStream in = null;
        try {
            in = (c.getResponseCode() >= 400) ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try { in.close(); } catch (final IOException ignored) { }
            }
        }
    }

    /** Build the HTTP {@code Authorization: Digest ...} value from a
     *  {@code WWW-Authenticate: Digest ...} challenge, reusing {@link SipDigestAuth}
     *  for the response computation. */
    private String buildAuthorization(final String challenge, final String method,
            final String uri) {
        final Map<String, String> p = parseDigestParams(challenge);
        final String realm = p.get("realm");
        final String nonce = p.get("nonce");
        final String qopRaw = p.get("qop");
        final String opaque = p.get("opaque");
        final String algorithm = p.get("algorithm");
        // qop may be a comma list ("auth,auth-int"); pick "auth" when offered.
        final String qop = (qopRaw != null && qopRaw.toLowerCase().contains("auth"))
                ? "auth" : null;
        final String nc = "00000001";
        final String cnonce = SipDigestAuth.newCnonce();
        final String response = SipDigestAuth.response(user, realm, password,
                method, uri, nonce, qop, nc, cnonce);

        final StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(user).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        sb.append("response=\"").append(response).append("\"");
        if (algorithm != null) {
            sb.append(", algorithm=").append(algorithm);
        }
        if (qop != null) {
            sb.append(", qop=auth, nc=").append(nc)
              .append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (opaque != null) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        return sb.toString();
    }

    /** Parse the comma-separated {@code key=value} / {@code key="value"} params of a
     *  {@code Digest ...} challenge into a map (keys lower-cased). */
    private static Map<String, String> parseDigestParams(final String challenge) {
        final Map<String, String> out = new HashMap<>();
        final String body = challenge.substring("Digest".length()).trim();
        int i = 0;
        final int n = body.length();
        while (i < n) {
            final int eq = body.indexOf('=', i);
            if (eq < 0) break;
            final String key = body.substring(i, eq).trim().toLowerCase();
            int v = eq + 1;
            String value;
            if (v < n && body.charAt(v) == '"') {
                final int end = body.indexOf('"', v + 1);
                if (end < 0) break;
                value = body.substring(v + 1, end);
                i = end + 1;
            } else {
                int end = body.indexOf(',', v);
                if (end < 0) end = n;
                value = body.substring(v, end).trim();
                i = end;
            }
            out.put(key, value);
            while (i < n && (body.charAt(i) == ',' || body.charAt(i) == ' ')) i++;
        }
        return out;
    }

    /** Request-URI (path) for the Digest {@code uri=} / HA2, from an absolute URL. */
    private static String pathOf(final String url) {
        try {
            final URL u = new URL(url);
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path += "?" + u.getQuery();
            return path;
        } catch (final IOException e) {
            return "/";
        }
    }
}
