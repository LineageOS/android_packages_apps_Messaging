/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.text.TextUtils;
import android.util.Log;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.MlsConfig;
import com.android.messaging.rcs.engine.mls.MlsGroupArtifacts;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;
import com.android.messaging.rcs.engine.mls.Rcc16Version;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Debug-only driver for the carrier-network MLS flow against a KDS: enrol (CSR to RCC.16 leaf),
 * generate and upload a KeyPackage pool, and optionally claim a peer's KeyPackage and create a
 * group. It exercises {@link RcsKdsClient} and the engine end to end without the provider. The
 * request is bound to the cellular network, where the KDS is reachable. Inert on a {@code user}
 * build; logs with the prefix {@code MlsEnroll}.
 *
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_ENROLL \
 *     -n com.android.messaging/.rcs.e2ee.MlsEnrollDebugReceiver \
 *     --es e164 &lt;E.164&gt; [--es kds &lt;url&gt;] [--ei kpcount 3] [--es claim &lt;E.164&gt;] \
 *     [--es acs_proof &lt;base64 SignedEncryptionIdentityProof&gt;]
 * </pre>
 *
 * <p>{@code acs_proof} is the ACS-signed {@code SignedEncryptionIdentityProof} (RCC.16 §7.12.1),
 * passed verbatim to {@link RcsKdsClient#enroll(String, String)} so the KDS embeds it as the
 * {@code .5} extension. {@code MlsCarrierTransport} reads it from the ACS configuration itself;
 * this driver has no configuration document, so it is supplied here. Without it the enrolment is
 * proofless, which a KDS requiring the proof refuses.
 */
public final class MlsEnrollDebugReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsEnroll: ";
    static final String ACTION = "com.android.messaging.debug.MLS_ENROLL";

    private static final long ERA = 0xF001L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!RcsDebug.isDebugBuild()) {
            Log.w(TAG, P + "ignored: build is not debuggable (user)");
            return;
        }
        final String e164 = intent.getStringExtra("e164");
        if (TextUtils.isEmpty(e164)) {
            Log.e(TAG, P + "FAIL: missing --es e164");
            return;
        }
        // --es kds <url>, else the test network's KDS (reachable on a debug build only).
        final String kds = intent.getStringExtra("kds") != null ? intent.getStringExtra("kds")
                : MlsTrustAnchors.compiledInKdsUrl();
        final int kpCount = intent.getIntExtra("kpcount", 3);
        final String claimPeer = intent.getStringExtra("claim");
        // Base64 SignedEncryptionIdentityProof, or null to enrol without one.
        final String acsProof = intent.getStringExtra("acs_proof");
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> run(appCtx, kds, e164, kpCount, claimPeer, acsProof),
                "mls-enroll").start();
    }

    private static void run(final Context ctx, final String kds, final String e164,
            final int kpCount, final String claimPeer, final String acsProof) {
        Network cell = null;
        ConnectivityManager cm = null;
        ConnectivityManager.NetworkCallback cb = null;
        MlsSession session = null;
        try {
            final OpenMlsEngine engine = new OpenMlsEngine(
                    OpenMlsEngine.KeyPackageLifetime.WITHIN_CERTIFICATE,
                    OpenMlsEngine.PeerCertificatePolicy.RCC16_STRICT,
                    // The RCC.16 revision is the configured one.
                    Rcc16Version.fromWire(android.os.SystemProperties.getInt(
                            MlsConfig.KEY_RCC16_VERSION, MlsConfig.DEF_RCC16_VERSION)));
            if (!engine.available()) {
                Log.e(TAG, P + "FAIL: OpenMLS native (libmlsengine) not available");
                return;
            }

            // The KDS is reached over the cellular data network.
            cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            final AtomicReference<Network> ref = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(final Network n) { ref.set(
                        n); latch.countDown(); }
            };
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.w(TAG, P + "cellular network not available in 15s — trying default route");
            }
            cell = ref.get();
            Log.i(TAG, P + "kds=" + kds + " e164=" + e164 + " cellNetwork=" + cell);

            final RcsKdsClient client = new RcsKdsClient(ctx, kds, cell);

            // 1) Enrol; enroll(e164, null) sends an empty acs_proof.
            if (acsProof == null || acsProof.isEmpty()) {
                Log.w(TAG, P + "no --es acs_proof — enrolling PROOFLESS. A KDS without the "
                        + ".5 requirement accepts that and one with it refuses; supply the ACS's "
                        + "openrcs-encryption-identity-proof (e.g. the provider's ACS debug "
                        + "receiver, mode=handoff).");
            } else {
                Log.i(TAG, P + "enrolling WITH an ACS proof (" + acsProof.length()
                        + " b64 chars) — the KDS embeds it as the .5 extension");
            }
            final MlsIdentity id = client.enroll(e164, acsProof);
            if (id == null) { Log.e(TAG, P
                    + "FAIL: enroll returned null (see RcsKdsClient log)"); return; }
            Log.i(TAG, P + "ENROLLED: leaf=" + id.leafDer.length
                    + "B chain[ica] anchor=compiled-in root");

            // 2) Start the engine session with the issued identity.
            session = engine.startSession(MlsHostPorts.storageRoot(ctx), id,
                    MlsHostPorts.forApp(ctx));
            if (session == null) { Log.e(TAG, P
                    + "FAIL: startSession null (leaf rejected by Rcc16Validator?)"); return; }

            // 3) Generate a pool and a last-resort KeyPackage and upload them. generateKeyPackages
            // returns a length-prefixed pool; the upload takes individual wrapped packages.
            final List<byte[]> pool =
                    OpenMlsSession.splitLenPrefixed(session.generateKeyPackages(kpCount));
            if (pool.isEmpty()) { Log.e(TAG, P + "FAIL: generateKeyPackages empty"); return; }
            final byte[] lastResort = session.generateLastResortKeyPackage();
            if (lastResort == null || lastResort.length == 0) { Log.e(TAG, P
                    + "FAIL: last-resort KP empty"); return; }
            final int stored = client.uploadKeyPackages(e164, pool, lastResort);
            if (stored < 0) { Log.e(TAG, P + "FAIL: uploadKeyPackages"); return; }
            Log.i(TAG, P + "UPLOADED: pool=" + pool.size() + " (+last-resort) stored=" + stored);

            // 4) Optionally claim a peer's KeyPackage and create a group.
            if (!TextUtils.isEmpty(claimPeer)) {
                final RcsKdsClient.ClaimedKeyPackage claimed = client.claimKeyPackage(claimPeer);
                if (claimed == null) {
                    Log.w(TAG, P + "CLAIM " + claimPeer + ": none (peer not enrolled yet?)");
                } else {
                    Log.i(TAG, P + "CLAIMED " + claimPeer + ": " + claimed.keyPackage.length
                            + "B lastResort=" + claimed.isLastResort);
                    final MlsGroupArtifacts art = session.createGroup(ERA, claimed.keyPackage);
                    if (art == null || art.welcome == null) {
                        Log.e(TAG, P + "FAIL: createGroup with claimed KP returned incomplete");
                        return;
                    }
                    Log.i(TAG, P + "GROUP OK: gid=" + art.groupId.length + "B welcome="
                            + art.welcome.length
                            + "B — carrier MLS key-delivery + group establishment OK");
                }
            }
            Log.i(TAG, P + "PASS: enrol -> publish"
                    + (TextUtils.isEmpty(claimPeer) ? "" : " -> claim -> group") + " complete for "
                    + e164);
        } catch (final Throwable t) {
            Log.e(TAG, P + "FAIL: exception", t);
        } finally {
            if (session != null) { try { session.close(); } catch (final Throwable ignore) { } }
            if (cm != null && cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (
                    final Throwable ignore) { } }
        }
    }
}
