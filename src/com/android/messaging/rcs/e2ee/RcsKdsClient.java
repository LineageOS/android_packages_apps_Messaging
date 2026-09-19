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
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsParticipantKeyDerivation;

import android.content.Context;
import android.net.Network;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the open5gs lab MLS Delivery/CA service (<code>openrcs-kds</code> @172.22.0.53, reachable
 * over the internet PDN). Implements the vendor JSON contract:
 * {@code POST /mls/v1/certificate} (enrol → RCC.16 leaf), {@code POST /mls/v1/keypackages} (upload),
 * {@code POST /mls/v1/keypackages/claim} (claim one), {@code GET /mls/v1/supported-ciphersuites}.
 *
 * <p>Fully self-contained: plain {@link HttpURLConnection} + {@link MlsCredential} + {@link MlsTrustAnchors};
 * no dependency on the RCS provider app. Because the lab devices are cellular-only, pass the cellular
 * {@link Network} (from {@code ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)}) so the socket
 * binds to the data bearer that routes to the KDS — else the default network may not reach it.
 *
 * <p>Not thread-safe per call target; each method opens its own short-lived connection.
 */
public final class RcsKdsClient {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    /** Origin prefix: this class used to BE the tag (rework 14.3 collapsed the tag, not the origin). */
    private static final String P = "RcsKdsClient: ";
    private static final int TIMEOUT_MS = 20_000;

    private final Context context;    // for the DURABLE lab participant key (MlsParticipantIdentityKey)
    private final String baseUrl;     // e.g. "http://kds.rcs.mnc001.mcc001.pub.3gppnetwork.org" or "http://172.22.0.53:8080"
    private final Network network;    // nullable; the cellular network to bind the socket to

    // The ACS's openrcs-trust-anchors-* pointers. Null/empty is the normal
    // unprovisioned state and NOT a refusal: the provider then falls through to what it holds,
    // and below that to the compiled-in floor.
    private String taUri;
    private long taGeneration;
    private String taSigner;

    /**
     * @param context any context; used ONLY to reach the durable lab participant key. It is a
     *     constructor parameter rather than an {@code enroll} argument so there is one place that
     *     decides which key this client enrols with — the previous shape let {@code enroll} mint its
     *     own, so each call enrolled a different identity.
     */
    public RcsKdsClient(final Context context, final String baseUrl, final Network network) {
        this.context = context.getApplicationContext();
        // trim a trailing slash so path concatenation is clean.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.network = network;
    }

    /**
     * Carry the ACS's trust-anchor pointers into {@link #enroll}, so the identity it assembles
     * anchors to the PROVIDER's fetched, signature-verified list rather than the compiled-in
     * constant. Fluent because the three call sites all build this client
     * inline from a {@code MlsCarrierTransport.Config}.
     *
     * <p>Not calling it is safe and is what the bench enrol driver does: the provider falls
     * through to its own stored pointers, and below those to the floor.
     */
    public RcsKdsClient withTrustAnchorPointers(final String uri, final long generation,
            final String signerSpkiB64) {
        this.taUri = uri;
        this.taGeneration = generation;
        this.taSigner = signerSpkiB64;
        return this;
    }

    /**
     * Enrol {@code e164}: build a CSR over the DURABLE lab participant key, POST it to the CA, and
     * assemble an {@link MlsIdentity} from the issued leaf + the lab trust anchors. Returns
     * {@code null} on failure. The private half comes from {@link MlsParticipantIdentityKey} and never
     * leaves the device.
     */
    /**
     * Enrol with no ACS proof — the OLD path, and it stops working at the KDS cutover.
     *
     * @deprecated pass the {@code openrcs-encryption-identity-proof} from the ACS config document.
     *     RCC.16 A.3.8.10 makes the {@code .5} ACS proof MANDATORY, and the KDS now fills that
     *     extension from what we send here. Kept only so the lab keeps working until the
     *     coordinated cutover.
     */
    @Deprecated
    public MlsIdentity enroll(final String e164) { return enroll(e164, null); }

    /**
     * Enrol, handing the KDS the ACS-signed proof of our participant identity.
     *
     * @param acsProof base64 {@code SignedEncryptionIdentityProof} (§7.12.1), read verbatim from the
     *     ACS config document's {@code openrcs-encryption-identity-proof}. Passed THROUGH untouched:
     *     the KDS verifies the ACS signature over the rebuilt TBS and embeds it as the {@code .5}
     *     extension. We neither parse nor re-encode it — a client that rebuilt it would be asserting
     *     a proof it cannot sign.
     */
    public MlsIdentity enroll(final String e164, final String acsProof) {
        try {
            // The DURABLE key, never a fresh mint. The ACS signed its proof over exactly this key's
            // SPKI DER and the KDS enforces ext4.participantKey == SPKI(identity_pub); a per-call key
            // is refused at both ends with an error naming neither.
            final KeyPair identity = MlsParticipantIdentityKey.getOrCreate(context);
            final MlsCredential.Csr csr = MlsCredential.buildCsr(e164, identity);
            Log.i(TAG, P + "enroll " + e164 + ": participant key spki="
                    + MlsParticipantKeyDerivation.spkiFingerprint(identity.getPublic())
                    + " (must match the spki= the RCS provider app logged for the ACS leg)");
            final JSONObject req = new JSONObject()
                    .put("msisdn", "tel:" + e164)
                    .put("identity_pub", b64(csr.identityPub65))
                    .put("subject_der", b64(csr.subjectDer))
                    .put("san_der", b64(csr.sanDer))
                    .put("ext4_participant_info", b64(csr.ext4Value))
                    .put("acs_proof", acsProof == null ? "" : acsProof)
                    .put("csr_pop", b64(csr.csrPop));
            final JSONObject res = postJson("/mls/v1/certificate", req);
            if (res == null) { return null; }
            final byte[] leaf = unb64(res.getString("leaf"));
            // chain = [ica_der, root_der]; MlsIdentity.chainDer wants the ICA only (root is the anchor).
            final List<byte[]> chain = new ArrayList<>();
            final JSONArray arr = res.optJSONArray("chain");
            if (arr != null && arr.length() >= 1) {
                chain.add(unb64(arr.getString(0)));   // ICA
            } else {
                chain.add(MlsTrustAnchors.ica());          // fall back to the baked ICA
            }
            Log.i(TAG, P + "enroll " + e164 + ": leaf=" + leaf.length + "B, chain[ica], anchor=lab root");
            // The anchor set: the PROVIDER's fetched + signature-verified list when it can supply
            // one, else the compiled-in floor. Never both — a union would make a withdrawn anchor
            // un-withdrawable.
            return new MlsIdentity(e164, leaf, chain,
                    MlsCredential.scalar32(csr.identity.getPrivate()), csr.identityPub65,
                    MlsTrustAnchors.roots(context, taUri, taGeneration, taSigner));
        } catch (final Throwable t) {
            Log.e(TAG, P + "enroll failed for " + e164, t);
            return null;
        }
    }

    /**
     * Upload a KeyPackage pool + last-resort KP for {@code e164}. Each element is the MLSMessage-wrapped
     * KP bytes as {@code OpenMlsSession.generateKeyPackages}/{@code generateLastResortKeyPackage} return
     * them. Returns the stored count, or -1 on failure.
     */
    public int uploadKeyPackages(final String e164, final List<byte[]> keyPackages, final byte[] lastResort) {
        try {
            final JSONArray kps = new JSONArray();
            for (final byte[] kp : keyPackages) { kps.put(b64(kp)); }
            final JSONObject req = new JSONObject()
                    .put("msisdn", "tel:" + e164)
                    .put("key_packages", kps)
                    .put("last_resort", b64(lastResort));
            final JSONObject res = postJson("/mls/v1/keypackages", req);
            if (res == null) { return -1; }
            final int stored = res.optInt("stored", keyPackages.size());
            Log.i(TAG, P + "uploadKeyPackages " + e164 + ": stored=" + stored);
            return stored;
        } catch (final Throwable t) {
            Log.e(TAG, P + "uploadKeyPackages failed for " + e164, t);
            return -1;
        }
    }

    /** A claimed KeyPackage: the wrapped bytes + whether it was the peer's last-resort KP. */
    public static final class ClaimedKeyPackage {
        public final byte[] keyPackage;
        public final boolean isLastResort;
        ClaimedKeyPackage(final byte[] kp, final boolean lr) { this.keyPackage = kp; this.isLastResort = lr; }
    }

    /**
     * Claim one KeyPackage for {@code targetE164} (consumes it server-side; falls back to the peer's
     * last-resort). Returns {@code null} if the peer never uploaded (404) or on failure.
     */
    public ClaimedKeyPackage claimKeyPackage(final String targetE164) {
        try {
            final JSONObject req = new JSONObject().put("target_msisdn", "tel:" + targetE164);
            final JSONObject res = postJson("/mls/v1/keypackages/claim", req);
            if (res == null) { return null; }
            final byte[] kp = unb64(res.getString("key_package"));
            final boolean lr = res.optBoolean("is_last_resort", false);
            Log.i(TAG, P + "claimKeyPackage " + targetE164 + ": " + kp.length + "B lastResort=" + lr);
            return new ClaimedKeyPackage(kp, lr);
        } catch (final Throwable t) {
            Log.e(TAG, P + "claimKeyPackage failed for " + targetE164, t);
            return null;
        }
    }

    // ---- HTTP plumbing ----

    private JSONObject postJson(final String path, final JSONObject body) throws Exception {
        final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        final HttpURLConnection c = open(path);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            try (final OutputStream os = c.getOutputStream()) { os.write(payload); }
            final int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, P + "POST " + path + " -> HTTP " + code + " " + readBody(errStream(c)));
                return null;
            }
            final String resp = readBody(c.getInputStream());
            return new JSONObject(resp);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(final String path) throws Exception {
        final URL url = new URL(baseUrl + path);
        final HttpURLConnection c = (HttpURLConnection)
                (network != null ? network.openConnection(url) : url.openConnection());
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        return c;
    }

    private static InputStream errStream(final HttpURLConnection c) {
        final InputStream e = c.getErrorStream();
        return e != null ? e : new java.io.ByteArrayInputStream(new byte[0]);
    }

    private static String readBody(final InputStream in) throws Exception {
        if (in == null) { return ""; }
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String b64(final byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(final String s) { return Base64.decode(s, Base64.DEFAULT); }
}
