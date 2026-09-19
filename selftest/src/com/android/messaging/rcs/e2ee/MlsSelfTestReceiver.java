/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.Rcc16Der;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.android.messaging.rcs.engine.mls.MlsConfig;
import com.android.messaging.rcs.engine.mls.MlsGroupArtifacts;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;
import com.android.messaging.rcs.engine.mls.Rcc16Version;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Debug-only end-to-end check of the MLS engine inside this process, with no network, no KDS and no
 * second device. It mints a local root and intermediate ({@link MlsCredential#mintLocalPki()}),
 * issues two RCC.16 identities through both halves of the CSR contract, then runs a 1:1 group: B
 * publishes a KeyPackage, A creates the group and adds B, B joins, and both directions round-trip.
 * Every peer certificate is checked by the engine's RCC.16 validator on the way. See
 * docs/mls/rust-core.md.
 *
 * <p>Inert on a {@code user} build. Logs under {@code MlsLog.TAG} with the prefix {@code
 * MlsSelfTest}.
 *
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_SELFTEST \
 *     -n com.android.messaging.mls.selftest/com.android.messaging.rcs.e2ee.MlsSelfTestReceiver
 * </pre>
 */
public final class MlsSelfTestReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsSelfTest: ";
    static final String ACTION = "com.android.messaging.debug.MLS_SELFTEST";

    /**
     * Mint a fresh chain and write it to {@code dir}, so the Rust test fixtures can be regenerated
     * from the code that mints on the device. The CA private keys are written too (PKCS#8): they
     * belong to a test-only trust anchor, and without them no leaf can be re-issued under the same
     * CA.
     *
     * <p>{@code msisdns} is an ordered, comma-separated list; repeating a number yields a second
     * device for that participant (distinct key and subject, same {@code tel:} SAN).
     *
     * <pre>
     *   adb shell am broadcast -a com.android.messaging.debug.MLS_DUMP_PKI \
     *     -n com.android.messaging.mls.selftest/com.android.messaging.rcs.e2ee.MlsSelfTestReceiver \
     *     --es dir &lt;dir&gt; --es msisdns &lt;E.164 list&gt;
     * </pre>
     */
    static final String ACTION_DUMP_PKI = "com.android.messaging.debug.MLS_DUMP_PKI";

    private static final String E164_A = "+15550000001";
    private static final String E164_B = "+15550000002";
    private static final long ERA = 0xF001L;   // RCC.16 Era GroupContext extension

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }
        final boolean dumpPki = ACTION_DUMP_PKI.equals(intent.getAction());
        if (!dumpPki && !ACTION.equals(intent.getAction())) {
            return;
        }
        // Build type, not FLAG_DEBUGGABLE: android:debuggable is false for system apps on userdebug
        // too.
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
            Log.w(TAG, P + "ignored: build is not debuggable (user)");
            return;
        }
        final Context appCtx = context.getApplicationContext();
        if (dumpPki) {
            final String dir = intent.getStringExtra("dir");
            final String msisdns = intent.getStringExtra("msisdns");
            new Thread(() -> dumpPki(dir, msisdns), "mls-dump-pki").start();
            return;
        }
        new Thread(() -> runLoopback(appCtx), "mls-selftest").start();
    }

    /** Mint root, intermediate and one leaf per listed number, and write them to {@code dir}. */
    private static void dumpPki(final String dir, final String msisdns) {
        if (dir == null || dir.isEmpty() || msisdns == null || msisdns.isEmpty()) {
            Log.e(TAG, P + "DUMP-PKI needs --es dir <path> --es msisdns <csv>");
            return;
        }
        try {
            final java.io.File out = new java.io.File(dir);
            if (!out.isDirectory() && !out.mkdirs()) {
                Log.e(TAG, P + "DUMP-PKI cannot create " + dir);
                return;
            }
            final MlsCredential.LocalPki pki = MlsCredential.mintLocalPki();
            write(out, "root.der", pki.root.der);
            write(out, "root_priv.p8", pki.root.keyPair.getPrivate().getEncoded());
            write(out, "ica.der", pki.ica.der);
            write(out, "ica_priv.p8", pki.ica.keyPair.getPrivate().getEncoded());

            // leaf_pa, leaf_pb, ... in the order given.
            final String[] list = msisdns.split(",");
            for (int i = 0; i < list.length; i++) {
                final String e164 = list[i].trim();
                if (e164.isEmpty()) continue;
                final String name = "leaf_p" + (char) ('a' + i);
                final MlsIdentity id = MlsCredential.issueIdentity(pki, e164);
                write(out, name + ".der", id.leafDer);
                write(out, name + "_priv.bin", id.subjectPriv);
                write(out, name + "_pub.bin", id.subjectPub);
                Log.i(TAG, P + "DUMP-PKI " + name + " e164=" + e164 + " leaf=" + id.leafDer.length
                        + "B priv=" + id.subjectPriv.length + "B pub=" + id.subjectPub.length
                        + "B");
            }
            // The negative fixture comes from the same CA, so a test using it fails on the proof of
            // possession and not on the chain. The .4 value is corrupted before the leaf is signed,
            // so the certificate signature stays valid and the proof is the only thing wrong.
            final MlsCredential.Csr bad = MlsCredential.buildCsr(list[0].trim());
            final byte[] ext4 = corruptPopSignature(bad.ext4Value);
            final MlsCredential.Csr badCsr = new MlsCredential.Csr(bad.e164, bad.identity,
                    bad.identityPub65, bad.subjectDer, bad.sanDer, ext4, bad.csrPop);
            write(out, "leaf_badpop.der", MlsCredential.issueLeafFromCsr(pki.ica, badCsr));
            Log.i(TAG, P + "DUMP-PKI leaf_badpop: same CA, valid cert signature, .4 PoP signature "
                    + "corrupted by one byte");

            Log.i(TAG, P + "DUMP-PKI wrote a fresh test chain to " + dir + " (root+ICA with their "
                    + "private keys, " + list.length + " leaves, and a bad-PoP negative). "
                    + "Regenerate testdata from here.");
        } catch (final Throwable t) {
            Log.e(TAG, P + "DUMP-PKI failed", t);
        }
    }

    /**
     * Flip one bit inside the {@code s} of the {@code .4} proof-of-possession signature. Length is
     * preserved, so the result fails the signature check rather than parsing.
     */
    private static byte[] corruptPopSignature(final byte[] ext4Value) {
        return Rcc16Der.corruptPopSignature(ext4Value);
    }

    private static void write(final java.io.File dir, final String name, final byte[] bytes)
            throws java.io.IOException {
        final java.io.FileOutputStream fos =
                new java.io.FileOutputStream(new java.io.File(dir, name));
        try { fos.write(bytes); } finally { fos.close(); }
    }

    private static void runLoopback(final Context ctx) {
        MlsSession a = null;
        MlsSession b = null;
        try {
            final OpenMlsEngine engine = new OpenMlsEngine(
                    OpenMlsEngine.KeyPackageLifetime.WITHIN_CERTIFICATE,
                    OpenMlsEngine.PeerCertificatePolicy.RCC16_STRICT,
                    // The RCC.16 revision is the configured one.
                    Rcc16Version.fromWire(android.os.SystemProperties.getInt(
                            MlsConfig.KEY_RCC16_VERSION, MlsConfig.DEF_RCC16_VERSION)));
            if (!engine.available()) {
                Log.e(TAG, P + "FAIL: OpenMLS native (libmlsengine) not available in this process");
                return;
            }

            // 1) Shared root and intermediate, and two identities issued under them.
            final MlsCredential.LocalPki pki = MlsCredential.mintLocalPki();
            final MlsIdentity idA = MlsCredential.issueIdentity(pki, E164_A);
            final MlsIdentity idB = MlsCredential.issueIdentity(pki, E164_B);
            Log.i(TAG, P + "minted PKI + identities: A leaf=" + idA.leafDer.length
                    + "B  B leaf=" + idB.leafDer.length + "B (shared root)");

            // 2) One session per identity.
            final com.android.messaging.rcs.engine.mls.MlsPorts ports =
                    SelfTestPorts.forSelfTest(ctx);
            final String root = SelfTestPorts.storageRoot(ctx);
            a = engine.startSession(root, idA, ports);
            b = engine.startSession(root, idB, ports);
            if (a == null || b == null) {
                Log.e(TAG, P + "FAIL: startSession returned null (a=" + a + " b=" + b + ")");
                return;
            }

            // 3) B publishes a KeyPackage; A creates the group and adds B. generateKeyPackages
            // returns a length-prefixed pool, createGroup takes one wrapped package.
            final byte[] kpB = OpenMlsSession.firstKeyPackage(b.generateKeyPackages(1));
            if (kpB == null || kpB.length == 0) {
                Log.e(TAG, P + "FAIL: B.generateKeyPackages returned empty");
                return;
            }
            Log.i(TAG, P + "B KeyPackage = " + kpB.length + "B (MLSMessage-wrapped, from pool)");
            final MlsGroupArtifacts art = a.createGroup(ERA, kpB);
            if (art == null || art.groupId == null || art.welcome == null) {
                Log.e(TAG, P + "FAIL: A.createGroup returned incomplete artifacts");
                return;
            }
            Log.i(TAG, P + "A createGroup OK: gid=" + art.groupId.length + "B welcome="
                    + art.welcome.length + "B");

            // 4) B joins. Our GroupInfo carries no ratchet_tree extension, so the Welcome is
            // treeless and the tree must be supplied separately, as the inbound path does.
            if (art.ratchetTree == null || art.ratchetTree.length == 0) {
                Log.e(TAG, P + "FAIL: A.createGroup exported no ratchet tree");
                return;
            }
            final byte[] gidB = ((OpenMlsSession) b).joinWithTree(art.welcome, art.ratchetTree);
            if (gidB == null || gidB.length == 0) {
                Log.e(TAG, P + "FAIL: B.joinWithTree returned empty");
                return;
            }
            Log.i(TAG, P + "B joined group gid=" + gidB.length + "B");

            // 5) Encrypt/decrypt both directions.
            if (!roundtrip(a, b, art.groupId, "hello from A (openmls, contained in messaging2)",
                    "A->B")) return;
            if (!roundtrip(b, a, art.groupId, "hi back from B (openmls)", "B->A")) return;

            Log.i(TAG, P
                    + "PASS: contained OpenMLS 1:1 E2EE round-trip A<->B (RCC.16 certs validated in-engine)");
        } catch (final Throwable t) {
            Log.e(TAG, P + "FAIL: exception", t);
        } finally {
            close(a);
            close(b);
        }
    }

    private static boolean roundtrip(final MlsSession from, final MlsSession to, final byte[] gid,
            final String msg, final String label) {
        final byte[] pt = msg.getBytes(StandardCharsets.UTF_8);
        final byte[] ct = from.encrypt(gid, pt);
        if (ct == null || ct.length == 0) {
            Log.e(TAG, P + "FAIL[" + label + "]: encrypt returned empty");
            return false;
        }
        final byte[] out = to.process(gid, ct);
        if (!Arrays.equals(pt, out)) {
            Log.e(TAG, P + "FAIL[" + label + "]: decrypt mismatch (ct=" + ct.length + "B out="
                    + (out == null ? "null" : out.length + "B") + ")");
            return false;
        }
        Log.i(TAG, P + "OK[" + label + "]: " + ct.length + "B ciphertext decrypted to \"" + msg
                + "\"");
        return true;
    }

    private static void close(final MlsSession s) {
        if (s != null) {
            try { s.close(); } catch (final Throwable ignore) { }
        }
    }
}
