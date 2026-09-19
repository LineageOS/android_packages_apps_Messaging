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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import com.android.messaging.rcs.engine.mls.MlsParticipantKeyDerivation;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * messaging2's half of the <b>lab (open5gs) MLS Public Participant Key</b> — the DURABLE P-256 key
 * {@link RcsKdsClient} enrols with. The provider's mirror is
 * the provider app's own participant-key derivation.
 *
 * <h2>The bug this closes</h2>
 *
 * <p>{@code MlsCredential.buildCsr(e164)} minted a FRESH P-256 keypair on every call and
 * {@code RcsKdsClient.enroll} called it per enrolment, so {@code identity_pub} — and the
 * {@code participantKey} inside the {@code .4} extension — was a different key every time. RCC.16
 * §4.1 has the ACS RETAIN the participant key and sign a {@code SignedEncryptionIdentityProof} over
 * it, and the KDS enforces {@code ext4.participantKey == SPKI(identity_pub)}. A key that changes
 * between the ACS call and the enrolment is refused at BOTH ends, with an error naming neither, so
 * no ACS proof could ever match: the proof was structurally unsatisfiable rather than merely wrong.
 * It was invisible only because {@code acs_proof} went out empty and the lab KDS defaults permissive.
 *
 * <p>The instability was not only across apps. A process restart re-ran {@code ensureProvisioned},
 * which re-enrolled — so the device changed MLS identity roughly as often as the app was killed,
 * and any peer holding a claimed KeyPackage was holding one for a participant that no longer existed.
 *
 * <h2>Why this derives instead of persisting a random key</h2>
 *
 * <p>The key is derived from {@code Settings.Secure.ANDROID_ID} through the SHARED
 * {@link MlsParticipantKeyDerivation} in {@code messaging-mls-engine}, which both APKs static-link, and then
 * persisted. Two things follow that a random persisted key would not give:
 *
 * <ul>
 *   <li>it survives a {@code pm clear} — nothing in app storage does, and a lab device is wiped
 *       between runs, which would otherwise invalidate the ACS's stored key every time; and</li>
 *   <li>{@code the RCS provider app} derives the IDENTICAL key, with no IPC and no private-key export,
 *       because {@code ANDROID_ID} is scoped to the app-signing key and both APKs are
 *       {@code certificate: "platform"}. That matters because the two legs live in two processes:
 *       the provider drives the RCC.16 §4.1 ACS registration and this app drives the KDS
 *       enrolment.</li>
 * </ul>
 *
 * <p>The second point is READ OFF THE PLATFORM SOURCE THIS TREE BUILDS rather than asserted:
 * {@code SettingsProvider.generateSsaidLocked} computes
 * {@code HMAC-SHA256(perUserKey, for each sig: len32(sig) || sig)} — <b>the package name is not in
 * the preimage</b> — so two same-signed packages in one user get byte-identical values. It holds
 * only while both APKs run at app UIDs ({@code isNewSsaidSetting} sends a system-UID caller to the
 * GLOBAL {@code android_id} instead) and in the same Android user. To OBSERVE it rather than trust
 * it, compare the {@code spki=} fingerprint logged here with the provider's {@code MlsProvider.LabKey}
 * line — both are {@link MlsParticipantKeyDerivation#spkiFingerprint} of the same SPKI DER.
 *
 * <h2>Rotation: on a factory reset, or on an explicit operator reset. Never on its own.</h2>
 *
 * <p>The derived key is a pure function of {@code (ANDROID_ID, label)}, so it rotates when
 * {@code ANDROID_ID} does — a factory reset — and otherwise not at all: not on {@code pm clear},
 * not on reinstall, not with age. That is deliberate. RCC.16 rotates a participant key through the
 * key-roll chain ({@code participantKeyRolls} inside {@code .4}, A.3.8.9) and the §10.1.1 resync
 * commit ({@code MlsParticipantKeyResync}); we emit no rolls, so a key that rotated by itself would
 * orphan every proof the ACS holds, every leaf the KDS minted, and every peer's pinned view, with
 * nothing on the wire to explain it. An expiry-driven rotation would be a second bug, not a safety
 * feature.
 *
 * <p>The one explicit rotation path: set {@code debug.rcs.lab_participant_key_mode=random} and call
 * {@link #clear}. That is a NEW IDENTITY rather than a roll — re-register at the ACS and re-Welcome
 * peers — and it DIVERGES this key from the provider's, which keeps its own random key. The log says
 * so at mint time.
 *
 * <p><b>Lab-grade, deliberately.</b> Deriving a private key from a device identifier is weaker than
 * a random key in hardware-backed storage. It is the same trade already taken on the Tachyon path,
 * for the same durability reason, in a closed lab whose CA we also own. Do not carry this shape to
 * a production trust domain without revisiting it.
 */
public final class MlsParticipantIdentityKey {

    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsParticipantIdentityKey: ";

    private MlsParticipantIdentityKey() {}

    /** Own prefs file, so clearing lab state cannot take anything else with it. */
    private static final String PREFS = "mls_lab_participant_key";
    private static final String KEY_SPKI_B64 = "spki_der_b64";
    private static final String KEY_PKCS8_B64 = "pkcs8_der_b64";

    /**
     * {@code derive} (default) &rarr; deterministic from the stable device secret, and identical to
     * the provider's; {@code random} &rarr; a random JCE mint (escape hatch — see the rotation note
     * in the class comment). Only consulted on FIRST use: a persisted key always wins, so flipping
     * this does not rotate an existing key. Use {@link #clear} for that.
     *
     * <p>Deliberately the SAME sysprop the provider reads, so one device-wide knob moves both sides
     * together instead of leaving them in different modes.
     */
    private static final String SYSPROP_KEY_MODE = "debug.rcs.lab_participant_key_mode";

    /**
     * The one lab participant keypair: loaded, or derived-and-persisted on first use. The same
     * logical key thereafter — across enrolments, process restarts and a {@code pm clear} (in
     * {@code derive} mode).
     */
    public static KeyPair getOrCreate(final Context context) throws Exception {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = prefs(app);
        final KeyPair existing = tryLoad(prefs);
        if (existing != null) {
            return existing;
        }
        synchronized (MlsParticipantIdentityKey.class) {
            final KeyPair raced = tryLoad(prefs);
            if (raced != null) {
                return raced;
            }
            final KeyPair fresh = mintOrDerive(app);
            final boolean ok = prefs.edit()
                    .putString(KEY_SPKI_B64, Base64.encodeToString(
                            fresh.getPublic().getEncoded(), Base64.NO_WRAP))
                    .putString(KEY_PKCS8_B64, Base64.encodeToString(
                            fresh.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .commit();   // commit(), not apply() — on disk before we hand it out
            if (!ok) {
                throw new IllegalStateException("failed to persist lab participant keypair");
            }
            return fresh;
        }
    }

    /** X.509 {@code SubjectPublicKeyInfo} DER — what the ACS stores as {@code participant_key}. */
    public static byte[] spkiDer(final Context context) throws Exception {
        return getOrCreate(context).getPublic().getEncoded();
    }

    /**
     * The 65-byte SEC1 point {@code 0x04 || X || Y} — the KDS's {@code identity_pub}.
     *
     * <p>Same key as {@link #spkiDer}, different encoding, computed in the shared engine so the two
     * call sites cannot each compute "the participant key" and disagree.
     */
    public static byte[] sec1Point65(final Context context) throws Exception {
        return MlsParticipantKeyDerivation.sec1Point65(getOrCreate(context).getPublic());
    }

    /**
     * The short fingerprint of the key in use, or {@code "none"} — the string to compare against the
     * provider's log line when checking that the two processes agree.
     */
    public static String fingerprintOrNone(final Context context) {
        try {
            return MlsParticipantKeyDerivation.spkiFingerprint(getOrCreate(context).getPublic());
        } catch (final Throwable t) {
            return "none";
        }
    }

    /**
     * Drop the persisted key. In {@code derive} mode the next call re-derives the SAME key, which is
     * the point; set {@code debug.rcs.lab_participant_key_mode=random} first if the intent is
     * actually to rotate. Clearing does not un-register anything at the ACS or the KDS.
     */
    public static void clear(final Context context) {
        prefs(context.getApplicationContext()).edit()
                .remove(KEY_SPKI_B64).remove(KEY_PKCS8_B64).apply();
        Log.i(TAG, P + "cleared persisted lab participant key");
    }

    // -----------------------------------------------------------------------------------------

    private static SharedPreferences prefs(final Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static KeyPair tryLoad(final SharedPreferences prefs) {
        final String spkiB64 = prefs.getString(KEY_SPKI_B64, null);
        final String pkcs8B64 = prefs.getString(KEY_PKCS8_B64, null);
        if (spkiB64 == null || pkcs8B64 == null) {
            return null;
        }
        try {
            final KeyFactory kf = KeyFactory.getInstance("EC");
            final PublicKey pub = kf.generatePublic(
                    new X509EncodedKeySpec(Base64.decode(spkiB64, Base64.NO_WRAP)));
            final PrivateKey priv = kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(pkcs8B64, Base64.NO_WRAP)));
            return new KeyPair(pub, priv);
        } catch (final Throwable t) {
            // Unreadable stored bytes are treated as absent: in derive mode the next call reproduces
            // the same key, so re-minting is recoverable rather than identity-losing.
            Log.w(TAG, P + "stored lab participant key unreadable — re-deriving", t);
            return null;
        }
    }

    private static KeyPair mintOrDerive(final Context app) throws Exception {
        final String mode = SystemProperties.get(SYSPROP_KEY_MODE, "derive");
        if (!"random".equals(mode)) {
            final byte[] secret = MlsParticipantKeyDerivation.stableSecret(
                    Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID));
            if (secret != null && secret.length > 0) {
                final KeyPair derived = MlsParticipantKeyDerivation.deriveP256(
                        secret, MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY);
                Log.i(TAG, P + "DERIVED lab participant key (label="
                        + MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY + ", spki="
                        + MlsParticipantKeyDerivation.spkiFingerprint(derived.getPublic())
                        + ") — stable across enrolments and pm-clear; the RCS provider app must log the "
                        + "SAME spki= for the ACS leg to bind the key we enrol with");
                return derived;
            }
            Log.w(TAG, P + "lab_participant_key_mode=derive but ANDROID_ID is unreadable — RANDOM "
                    + "mint. This key will NOT survive a pm clear, the provider will NOT derive the "
                    + "same key, and an ACS proof bound to it dies with it.");
        } else {
            Log.w(TAG, P + "lab_participant_key_mode=random — RANDOM mint. This is a SECOND lab "
                    + "identity for this device: the provider derives its own key and the two will "
                    + "NOT match, so a proof the ACS signs over its key is refused at the KDS.");
        }
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }
}
