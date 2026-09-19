/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsParticipantKeyDerivation;
import com.android.messaging.rcs.log.LogMask;

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
 * Client for a carrier-network KDS's JSON API: {@code POST /mls/v1/certificate} (enrol, returns an
 * RCC.16 leaf), {@code POST /mls/v1/keypackages} (upload) and {@code POST
 * /mls/v1/keypackages/claim} (claim one). Self-contained: {@link HttpURLConnection}, {@link
 * MlsCredential} and {@link MlsTrustAnchors}, no provider. Pass the cellular {@link Network} where
 * the default network does not route to the KDS. Each call opens its own connection.
 */
public final class RcsKdsClient {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "RcsKdsClient: ";
    private static final int TIMEOUT_MS = 20_000;

    private final Context context;    // for the participant key only
    private final String baseUrl;
    private final Network network;    // nullable; the network to bind the socket to

    // The ACS's openrcs-trust-anchors-* pointers; null is the normal unprovisioned state.
    private String taUri;
    private long taGeneration;
    private String taSigner;

    /**
     * @param context used only to reach {@link MlsParticipantIdentityKey}, so one place decides
     * which
     *     key this client enrols with
     */
    public RcsKdsClient(final Context context, final String baseUrl, final Network network) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.network = network;
    }

    /**
     * Pass the ACS's trust-anchor pointers through to {@link MlsTrustAnchors#roots(Context, String,
     * long, String)} when {@link #enroll} assembles the identity. Optional: without them the
     * provider uses its own stored pointers.
     */
    public RcsKdsClient withTrustAnchorPointers(final String uri, final long generation,
            final String signerSpkiB64) {
        this.taUri = uri;
        this.taGeneration = generation;
        this.taSigner = signerSpkiB64;
        return this;
    }

    /**
     * Enrol with no ACS proof.
     *
     * @deprecated RCC.16 A.3.8.10 makes the {@code .5} ACS proof mandatory and the KDS fills that
     *     extension from what we send; pass the ACS configuration's
     *     {@code openrcs-encryption-identity-proof} to {@link #enroll(String, String)}.
     */
    @Deprecated
    public MlsIdentity enroll(final String e164) { return enroll(e164, null); }

    /**
     * Enrol {@code e164}: build a CSR over the durable participant key, post it, and assemble an
     * {@link MlsIdentity} from the issued leaf and the trust anchors. The private key never leaves
     * the device. Returns {@code null} on failure.
     *
     * @param acsProof base64 {@code SignedEncryptionIdentityProof} (RCC.16 §7.12.1) from the ACS
     *     configuration, passed through untouched: the KDS verifies it and embeds it as the {@code
     *     .5} extension. We cannot sign it, so we never rebuild it.
     */
    public MlsIdentity enroll(final String e164, final String acsProof) {
        try {
            // The durable key, never a fresh one: the ACS signed its proof over this key and the
            // KDS requires ext4.participantKey == SPKI(identity_pub).
            final KeyPair identity = MlsParticipantIdentityKey.getOrCreate(context);
            // The provider's verified anchors, or on a debug build the compiled-in root; never a
            // union. None means carrier-path MLS is off on this build: do not enrol at all.
            final List<byte[]> roots =
                    MlsTrustAnchors.roots(context, taUri, taGeneration, taSigner);
            if (roots.isEmpty()) {
                Log.w(TAG, P + "enroll " + LogMask.number(e164) + ": no trust anchors on this "
                        + "build — not enrolling");
                return null;
            }
            final MlsCredential.Csr csr = MlsCredential.buildCsr(e164, identity);
            Log.i(TAG, P + "enroll " + LogMask.number(e164) + ": participant key spki="
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
            // The response chain is [ica, root]; MlsIdentity.chainDer takes the intermediate only.
            final List<byte[]> chain = new ArrayList<>();
            final JSONArray arr = res.optJSONArray("chain");
            if (arr != null && arr.length() >= 1) {
                chain.add(unb64(arr.getString(0)));
            } else {
                // The compiled-in intermediate, on a debug build only.
                final byte[] ica = MlsTrustAnchors.compiledInIca();
                if (ica == null) {
                    Log.w(TAG, P + "enroll " + LogMask.number(e164) + ": the KDS returned no "
                            + "chain");
                    return null;
                }
                chain.add(ica);
            }
            Log.i(TAG, P + "enroll " + LogMask.number(e164) + ": leaf=" + leaf.length
                    + "B, chain[ica], anchors=" + roots.size());
            return new MlsIdentity(e164, leaf, chain,
                    MlsCredential.scalar32(csr.identity.getPrivate()), csr.identityPub65, roots);
        } catch (final Throwable t) {
            Log.e(TAG, P + "enroll failed for " + LogMask.number(e164), t);
            return null;
        }
    }

    /**
     * Upload a KeyPackage pool and a last-resort package, each MLSMessage-wrapped as the engine
     * returns them. Returns the stored count, or -1 on failure.
     */
    public int uploadKeyPackages(final String e164, final List<byte[]> keyPackages,
            final byte[] lastResort) {
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
            Log.i(TAG, P + "uploadKeyPackages " + LogMask.number(e164) + ": stored=" + stored);
            return stored;
        } catch (final Throwable t) {
            Log.e(TAG, P + "uploadKeyPackages failed for " + LogMask.number(e164), t);
            return -1;
        }
    }

    /** A claimed KeyPackage (wrapped bytes) and whether it was the peer's last-resort package. */
    public static final class ClaimedKeyPackage {
        public final byte[] keyPackage;
        public final boolean isLastResort;
        ClaimedKeyPackage(final byte[] kp, final boolean lr) { this.keyPackage =
                kp; this.isLastResort = lr; }
    }

    /**
     * Claim one KeyPackage for {@code targetE164}, consuming it server-side (the last-resort
     * package when the pool is empty). {@code null} if the peer never uploaded, or on failure.
     */
    public ClaimedKeyPackage claimKeyPackage(final String targetE164) {
        try {
            final JSONObject req = new JSONObject().put("target_msisdn", "tel:" + targetE164);
            final JSONObject res = postJson("/mls/v1/keypackages/claim", req);
            if (res == null) { return null; }
            final byte[] kp = unb64(res.getString("key_package"));
            final boolean lr = res.optBoolean("is_last_resort", false);
            Log.i(TAG, P + "claimKeyPackage " + LogMask.number(targetE164) + ": " + kp.length
                    + "B lastResort="
                    + lr);
            return new ClaimedKeyPackage(kp, lr);
        } catch (final Throwable t) {
            Log.e(TAG, P + "claimKeyPackage failed for " + LogMask.number(targetE164), t);
            return null;
        }
    }

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
