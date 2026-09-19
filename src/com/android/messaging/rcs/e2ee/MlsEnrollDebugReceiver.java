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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

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
 * Debug-only driver for the REAL lab MLS flow against {@code openrcs-kds}: enrol (CSR → RCC.16 leaf)
 * → generate + upload a KeyPackage pool → optionally claim a peer's KeyPackage and open a group. This
 * exercises {@link RcsKdsClient} + the messaging2-owned OpenMLS engine end-to-end over the carrier lab
 * — no the RCS provider app involvement.
 *
 * <p>Lab devices are cellular-only and the KDS sits on the data PDN, so this binds the request socket to
 * the cellular {@link Network} before calling the KDS.
 *
 * <p>Gated on {@link Build#TYPE} (eng/userdebug). Read tag {@code MlsEnroll} in logcat.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_LAB_ENROLL \
 *     -n com.android.messaging/.rcs.e2ee.MlsEnrollDebugReceiver \
 *     --es e164 +11012026331 [--es kds http://kds.rcs.mnc001.mcc001.pub.3gppnetwork.org] \
 *     [--ei kpcount 3] [--es claim +11012026332] [--es acs_proof <base64 SignedEncryptionIdentityProof>]
 * </pre>
 *
 * <p><b>{@code --es acs_proof}</b> is the ACS-signed {@code SignedEncryptionIdentityProof}
 * (RCC.16 §7.12.1) to enrol WITH — passed to {@link RcsKdsClient#enroll(String, String)} verbatim
 * so the KDS embeds it as the {@code .5} extension. Without it this enrols proofless, which the lab
 * KDS accepts only until the coordinated cutover; after it, an empty proof is
 * refused. The value comes from the ACS config document's {@code openrcs-encryption-identity-proof}:
 * on the carrier/DR path {@code MlsCarrierTransport} reads it automatically, but this bench driver
 * has no config document, so the proof is supplied here. The provider holds one it obtained on its
 * own §4.1 leg; {@code LabAcsDebugReceiver --es mode handoff} reads it out of {@code LabAcsStore}
 * and re-broadcasts this action with {@code acs_proof} filled in ("the provider writing where
 * messaging2 reads" — a sanctioned crossing, no AIDL bump).
 */
public final class MlsEnrollDebugReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    /** Origin prefix: this class used to BE the tag (rework 14.3 collapsed the tag, not the origin). */
    private static final String P = "MlsEnroll: ";
    static final String ACTION = "com.android.messaging.debug.MLS_LAB_ENROLL";

    // HTTPS on 443 (=172.22.0.53), no mTLS; TLS root = OpenRCS TLS Root (already trusted on the
    // ACS-provisioned lab device). Override with --es kds <url>.
    private static final String DEFAULT_KDS = "https://kds.rcs.mnc001.mcc001.pub.3gppnetwork.org";
    private static final long ERA = 0xF001L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
            Log.w(TAG, P + "ignored: build is not debuggable (user)");
            return;
        }
        final String e164 = intent.getStringExtra("e164");
        if (TextUtils.isEmpty(e164)) {
            Log.e(TAG, P + "FAIL: missing --es e164");
            return;
        }
        final String kds = intent.getStringExtra("kds") != null ? intent.getStringExtra("kds") : DEFAULT_KDS;
        final int kpCount = intent.getIntExtra("kpcount", 3);
        final String claimPeer = intent.getStringExtra("claim");
        // The ACS proof to enrol WITH (base64 SignedEncryptionIdentityProof), or null to enrol
        // proofless. See the class comment.
        final String acsProof = intent.getStringExtra("acs_proof");
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> run(appCtx, kds, e164, kpCount, claimPeer, acsProof), "mls-lab-enroll").start();
    }

    private static void run(final Context ctx, final String kds, final String e164, final int kpCount,
            final String claimPeer, final String acsProof) {
        Network cell = null;
        ConnectivityManager cm = null;
        ConnectivityManager.NetworkCallback cb = null;
        MlsSession session = null;
        try {
            final OpenMlsEngine engine = new OpenMlsEngine(OpenMlsEngine.Profile.LAB_RCC16,
                    // The LAB is the transport that will actually move to v4.0, so it reads the
                    // override. Default is still v3.0 — openrcs has not been upgraded yet.
                    Rcc16Version.fromWire(android.os.SystemProperties.getInt(
                            MlsConfig.KEY_RCC16_VERSION, MlsConfig.DEF_RCC16_VERSION)));
            if (!engine.available()) {
                Log.e(TAG, P + "FAIL: OpenMLS native (libmlsengine) not available");
                return;
            }

            // Bind the cellular data bearer (the KDS lives on the internet PDN).
            cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            final AtomicReference<Network> ref = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(final Network n) { ref.set(n); latch.countDown(); }
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

            // 1) enrol: CSR -> RCC.16 leaf under the lab ICA, WITH the ACS proof when one was
            //    supplied. enroll(e164, null) sends an empty acs_proof — identical to the old
            //    no-proof path — so the KDS still accepts it until the .5 cutover.
            if (acsProof == null || acsProof.isEmpty()) {
                Log.w(TAG, P + "no --es acs_proof — enrolling PROOFLESS. The lab KDS accepts that "
                        + "until the .5 cutover and refuses it after; supply the ACS's "
                        + "openrcs-encryption-identity-proof (e.g. LabAcsDebugReceiver mode=handoff).");
            } else {
                Log.i(TAG, P + "enrolling WITH an ACS proof (" + acsProof.length()
                        + " b64 chars) — the KDS embeds it as the .5 extension");
            }
            final MlsIdentity id = client.enroll(e164, acsProof);
            if (id == null) { Log.e(TAG, P + "FAIL: enroll returned null (see RcsKdsClient log)"); return; }
            Log.i(TAG, P + "ENROLLED: leaf=" + id.leafDer.length + "B chain[ica] anchor=lab-root");

            // 2) start the engine session with the lab-issued identity.
            session = engine.startSession(MlsHostPorts.storageRoot(ctx), id,
                    MlsHostPorts.forApp(ctx));
            if (session == null) { Log.e(TAG, P + "FAIL: startSession null (leaf rejected by Rcc16Validator?)"); return; }

            // 3) generate a KeyPackage pool + last-resort, upload. generateKeyPackages returns a
            // len-prefixed pool; split into individual wrapped KPs for UploadKeyPackages.
            final List<byte[]> pool = OpenMlsSession.splitLenPrefixed(session.generateKeyPackages(kpCount));
            if (pool.isEmpty()) { Log.e(TAG, P + "FAIL: generateKeyPackages empty"); return; }
            final byte[] lastResort = session.generateLastResortKeyPackage();
            if (lastResort == null || lastResort.length == 0) { Log.e(TAG, P + "FAIL: last-resort KP empty"); return; }
            final int stored = client.uploadKeyPackages(e164, pool, lastResort);
            if (stored < 0) { Log.e(TAG, P + "FAIL: uploadKeyPackages"); return; }
            Log.i(TAG, P + "UPLOADED: pool=" + pool.size() + " (+last-resort) stored=" + stored);

            // 4) optional: claim a peer's KP and open a group (the cross-device leg).
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
                    Log.i(TAG, P + "GROUP OK: gid=" + art.groupId.length + "B welcome=" + art.welcome.length
                            + "B — carrier MLS key-delivery + group establishment OK");
                }
            }
            Log.i(TAG, P + "PASS: lab enrol -> publish"
                    + (TextUtils.isEmpty(claimPeer) ? "" : " -> claim -> group") + " complete for " + e164);
        } catch (final Throwable t) {
            Log.e(TAG, P + "FAIL: exception", t);
        } finally {
            if (session != null) { try { session.close(); } catch (final Throwable ignore) { } }
            if (cm != null && cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (final Throwable ignore) { } }
        }
    }
}
