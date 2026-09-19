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
 * Debug-only proof that the OpenMLS (mls-rs) engine works <b>contained in messaging2's own
 * process</b> — the "built-in engine" gate, with no
 * network, no lab KDS, and no second device.
 *
 * <p>Drives the exact lab flow locally: {@link MlsCredential#mintLocalPki()} stands in for the
 * lab {@code openrcs-pki} root/ICA; {@link MlsCredential#issueIdentity} runs BOTH halves of the
 * {@code POST /mls/v1/certificate} CSR contract (client {@code buildCsr} + CA {@code issueLeafFromCsr})
 * to mint two RCC.16-conformant identities A/B under a shared root; then the {@link OpenMlsEngine}
 * does the full 1:1 group lifecycle — B publishes a KeyPackage, A creates the group + adds B, B joins
 * the Welcome, and both directions encrypt/decrypt. Every peer cert is validated in-engine by the
 * Rust {@code Rcc16Validator} (A.4.1 + the {@code .4} PoP), so a PASS also proves our on-device CA
 * output is RCC.16-valid to our own validator.
 *
 * <p>Exported so {@code adb shell am broadcast} can reach it, but inert on a {@code user} build
 * (gated on {@link Build#TYPE}). Read tag {@code MlsSelfTest} in logcat.
 *
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.MLS_SELFTEST \
 *     -n com.android.messaging/.rcs.e2ee.MlsSelfTestReceiver
 * </pre>
 */
public final class MlsSelfTestReceiver extends BroadcastReceiver {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    /** Origin prefix: this class used to BE the tag (rework 14.3 collapsed the tag, not the origin). */
    private static final String P = "MlsSelfTest: ";
    static final String ACTION = "com.android.messaging.debug.MLS_SELFTEST";

    /**
     * Mint a fresh lab chain and WRITE IT OUT, so {@code rust/rcs_mls_ffi/testdata/} can be
     * regenerated from the same code that mints on device.
     *
     * <pre>
     *   adb shell am broadcast -a com.android.messaging.debug.MLS_DUMP_PKI \
     *     -n com.android.messaging/.rcs.e2ee.MlsSelfTestReceiver \
     *     --es dir /data/local/tmp/labpki \
     *     --es msisdns "+15551110001,+15551110002,+15551110001"
     * </pre>
     *
     * <p><b>Why this exists at all</b>: the committed fixtures were minted once
     * and their CA private key was never kept, so no leaf could ever be re-issued under the existing
     * lab CA — which froze two §14.2.3 rules into conditional relaxations, because our own fixture
     * could not satisfy rules we hold every real peer to. A fixture set that cannot be regenerated is
     * a fixture set that silently sets the ceiling on how strict the validator is allowed to be.
     *
     * <p>So this dumps the CA private keys too (PKCS#8), and they are committed. They are a LAB trust
     * anchor used by tests and the on-device self-test — not a credential for anything real — and the
     * cost of withholding them has now been measured at one frozen rule each.
     *
     * <p>Repeating an MSISDN in {@code msisdns} yields a SECOND DEVICE for that participant: distinct
     * key, distinct subject, same {@code SAN=tel:}. That is the only way to build the N-devices
     * fixture, and it is why the list is ordered rather than a set.
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
        // Gate on the build TYPE (eng/userdebug), NOT ApplicationInfo.FLAG_DEBUGGABLE — the
        // latter reflects android:debuggable, which is false for system apps even on userdebug.
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
            Log.w(TAG, P + "ignored: build is not debuggable (user)");
            return;
        }
        // Run off the main thread — keygen + cert minting + native MLS are not instant.
        final Context appCtx = context.getApplicationContext();
        if (dumpPki) {
            final String dir = intent.getStringExtra("dir");
            final String msisdns = intent.getStringExtra("msisdns");
            new Thread(() -> dumpPki(dir, msisdns), "mls-dump-pki").start();
            return;
        }
        new Thread(() -> runLoopback(appCtx), "mls-selftest").start();
    }

    /** Mint root+ICA+N leaves and write them where {@code testdata/} can be refreshed from. */
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

            // leaf_pa, leaf_pb, leaf_pc, … in the order given, so a repeated MSISDN is simply the
            // next letter and the caller controls which participant gets two devices.
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
                        + "B priv=" + id.subjectPriv.length + "B pub=" + id.subjectPub.length + "B");
            }
            // THE NEGATIVE FIXTURE HAS TO COME FROM THE SAME CA, or migrating the suite to this
            // chain would leave the bad-PoP test asserting a CHAIN failure while claiming to assert
            // a PoP failure — a test that passes for the wrong reason, which is worse than no test.
            //
            // The corruption is applied to the .4 VALUE before the leaf is signed, so the
            // certificate's own signature stays valid and the ONLY thing wrong with this leaf is the
            // participant proof. Flipping a byte after issuance would break the cert signature
            // instead and prove nothing about the PoP.
            final MlsCredential.Csr bad = MlsCredential.buildCsr(list[0].trim());
            final byte[] ext4 = corruptPopSignature(bad.ext4Value);
            final MlsCredential.Csr badCsr = new MlsCredential.Csr(bad.e164, bad.identity,
                    bad.identityPub65, bad.subjectDer, bad.sanDer, ext4, bad.csrPop);
            write(out, "leaf_badpop.der", MlsCredential.issueLeafFromCsr(pki.ica, badCsr));
            Log.i(TAG, P + "DUMP-PKI leaf_badpop: same CA, valid cert signature, .4 PoP signature "
                    + "corrupted by one byte");

            Log.i(TAG, P + "DUMP-PKI wrote a fresh lab chain to " + dir + " (root+ICA with their "
                    + "private keys, " + list.length + " leaves, and a bad-PoP negative). "
                    + "Regenerate testdata from here.");
        } catch (final Throwable t) {
            Log.e(TAG, P + "DUMP-PKI failed", t);
        }
    }

    /**
     * Break the {@code .4} PoP SIGNATURE and nothing else.
     *
     * <p>{@code tbsParticipantInfo} is
     * {@code SEQUENCE { INTEGER vendorId, validity, sigAlg, BIT STRING signature, SPKI }} — the
     * signature is element <b>3</b> and the participant key is element 4, at the END.
     *
     * <p>The first attempt flipped the last byte of the whole structure, which lands in the
     * participant key's SEC1 point: the resulting fixture failed with {@code "vk: signature error"}
     * — an unparseable KEY, not a wrong signature. It would have passed a test asserting "bad PoP
     * rejected" while exercising a completely different branch, which is the same
     * cannot-discriminate trap as testing a rule against a fixture that cannot express it. Flip
     * inside element 3 so the key parses, the reconstruction runs, and the check that fails is the
     * signature verification.
     */
    private static byte[] corruptPopSignature(final byte[] ext4Value) throws java.io.IOException {
        final org.bouncycastle.asn1.ASN1Sequence in =
                org.bouncycastle.asn1.ASN1Sequence.getInstance(ext4Value);
        // BouncyCastle 1.72+ (Android 16) returns the ASN1BitString supertype here; the
        // older bundled version returned DERBitString. getBytes() is on the supertype.
        final org.bouncycastle.asn1.ASN1BitString sig =
                org.bouncycastle.asn1.DERBitString.getInstance(in.getObjectAt(3));
        final byte[] bits = sig.getBytes();
        // Last byte of the DER-encoded ECDSA signature: inside `s`, so the value stays a
        // structurally valid SEQUENCE{r,s} and only the arithmetic is wrong.
        bits[bits.length - 1] ^= 0x01;
        final org.bouncycastle.asn1.ASN1EncodableVector v =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        for (int i = 0; i < in.size(); i++) {
            v.add(i == 3 ? new org.bouncycastle.asn1.DERBitString(bits) : in.getObjectAt(i));
        }
        return new org.bouncycastle.asn1.DERSequence(v).getEncoded("DER");
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
            final OpenMlsEngine engine = new OpenMlsEngine(OpenMlsEngine.Profile.LAB_RCC16,
                    // The LAB is the transport that will actually move to v4.0, so it reads the
                    // override. Default is still v3.0 — openrcs has not been upgraded yet.
                    Rcc16Version.fromWire(android.os.SystemProperties.getInt(
                            MlsConfig.KEY_RCC16_VERSION, MlsConfig.DEF_RCC16_VERSION)));
            if (!engine.available()) {
                Log.e(TAG, P + "FAIL: OpenMLS native (libmlsengine) not available in this process");
                return;
            }

            // 1) Shared lab PKI (root+ICA) + two RCC.16 identities under it (drives the CSR contract).
            final MlsCredential.LocalPki pki = MlsCredential.mintLocalPki();
            final MlsIdentity idA = MlsCredential.issueIdentity(pki, E164_A);
            final MlsIdentity idB = MlsCredential.issueIdentity(pki, E164_B);
            Log.i(TAG, P + "minted PKI + identities: A leaf=" + idA.leafDer.length
                    + "B  B leaf=" + idB.leafDer.length + "B (shared root)");

            // 2) Start a contained session per identity (own mls-rs store dir).
            final com.android.messaging.rcs.engine.mls.MlsPorts ports = MlsHostPorts.forApp(ctx);
            final String root = MlsHostPorts.storageRoot(ctx);
            a = engine.startSession(root, idA, ports);
            b = engine.startSession(root, idB, ports);
            if (a == null || b == null) {
                Log.e(TAG, P + "FAIL: startSession returned null (a=" + a + " b=" + b + ")");
                return;
            }

            // 3) B publishes a KeyPackage; A creates the group and adds B (validates B's cert in-engine).
            // generateKeyPackages returns a len-prefixed POOL; createGroup wants ONE wrapped KP.
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
            Log.i(TAG, P + "A createGroup OK: gid=" + art.groupId.length + "B welcome=" + art.welcome.length + "B");

            // 4) B joins the Welcome + the separately exported ratchet tree (validates A's cert
            // in-engine).
            //
            // joinWithTree, NOT join. Our GroupInfo deliberately omits the ratchet_tree extension —
            // Google's server rejects an embedded one — so the Welcome we produce is TREE-LESS and a
            // plain join() can only ever return RatchetTreeNotFound. This self-test called join()
            // and had been failing at exactly that point ever since the tree-less GroupInfo landed;
            // nobody noticed because it is not part of the routine device pass. Using the same two
            // artifacts the production inbound path uses (routeOpen, for a WelcomeCommitBundle) also
            // makes the test cover the real shape rather than a simpler one that never occurs.
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
            if (!roundtrip(a, b, art.groupId, "hello from A (openmls, contained in messaging2)", "A->B")) return;
            if (!roundtrip(b, a, art.groupId, "hi back from B (openmls)", "B->A")) return;

            Log.i(TAG, P + "PASS: contained OpenMLS 1:1 E2EE round-trip A<->B (RCC.16 certs validated in-engine)");
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
        Log.i(TAG, P + "OK[" + label + "]: " + ct.length + "B ciphertext decrypted to \"" + msg + "\"");
        return true;
    }

    private static void close(final MlsSession s) {
        if (s != null) {
            try { s.close(); } catch (final Throwable ignore) { }
        }
    }
}
