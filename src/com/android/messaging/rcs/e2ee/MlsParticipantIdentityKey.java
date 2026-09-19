/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The durable P-256 participant key that {@link RcsKdsClient} enrols with on the carrier path
 * (RCC.16 §4.1). The ACS retains this key and signs its {@code SignedEncryptionIdentityProof} over
 * it, and the KDS requires the {@code .4} participant key to equal the CSR's {@code identity_pub},
 * so the key must not change between the two.
 *
 * <p>It is derived from {@code Settings.Secure.ANDROID_ID} through
 * {@link MlsParticipantKeyDerivation} and then persisted. That survives {@code pm clear}, and the
 * provider, signed with the same key and running as an app in the same user, derives the identical
 * key without any key export; compare the {@code spki=} fingerprints in the two logs to check. It
 * rotates only on a factory reset or an explicit {@link #clear} in {@code random} mode: we emit no
 * RCC.16 key rolls (A.3.8.9), so a self-rotating key would orphan every proof and leaf bound to it.
 *
 * <p>Deriving a private key from a device identifier is weaker than a random hardware-backed key.
 * It suits a test trust domain; revisit it before using this shape in a production one.
 */
public final class MlsParticipantIdentityKey {

    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String P = "MlsParticipantIdentityKey: ";

    private MlsParticipantIdentityKey() {}

    /**
     * A separate preferences file, so clearing this key touches nothing else. The name is on-device
     * state and is frozen: a renamed file would lose a {@code random}-mode key, since a persisted
     * key always wins and only {@code derive} mode can re-create it.
     * {@code TestNetworkWordGuardTest} allowlists exactly this value.
     */
    private static final String PREFS = "mls_lab_participant_key";
    private static final String KEY_SPKI_B64 = "spki_der_b64";
    private static final String KEY_PKCS8_B64 = "pkcs8_der_b64";

    /**
     * {@code derive} (default): deterministic from the device secret and identical to the
     * provider's. {@code random}: a fresh JCE key, which is a new identity and diverges from the
     * provider. Read only on first use, since a persisted key always wins. The provider reads the
     * same property.
     */
    private static final String SYSPROP_KEY_MODE = "debug.rcs.acs_participant_key_mode";

    /** The participant keypair: loaded, or derived and persisted on first use. */
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
                    .commit();   // commit(): on disk before it is handed out
            if (!ok) {
                throw new IllegalStateException("failed to persist participant keypair");
            }
            return fresh;
        }
    }

    /** X.509 {@code SubjectPublicKeyInfo} DER, which the ACS stores as {@code participant_key}. */
    public static byte[] spkiDer(final Context context) throws Exception {
        return getOrCreate(context).getPublic().getEncoded();
    }

    /**
     * The 65-byte SEC1 point {@code 0x04 || X || Y}, the KDS's {@code identity_pub}. Computed by
     * the shared engine code so the two encodings cannot disagree.
     */
    public static byte[] sec1Point65(final Context context) throws Exception {
        return MlsParticipantKeyDerivation.sec1Point65(getOrCreate(context).getPublic());
    }

    /** The key's short fingerprint, or {@code "none"}, for comparing against the provider's log. */
    public static String fingerprintOrNone(final Context context) {
        try {
            return MlsParticipantKeyDerivation.spkiFingerprint(getOrCreate(context).getPublic());
        } catch (final Throwable t) {
            return "none";
        }
    }

    /**
     * Drop the persisted key. In {@code derive} mode the next call re-derives the same key; set
     * {@code random} first to rotate. Nothing is deregistered at the ACS or the KDS.
     */
    public static void clear(final Context context) {
        prefs(context.getApplicationContext()).edit()
                .remove(KEY_SPKI_B64).remove(KEY_PKCS8_B64).apply();
        Log.i(TAG, P + "cleared persisted participant key");
    }

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
            // Unreadable bytes read as absent; in derive mode the next call reproduces the same
            // key.
            Log.w(TAG, P + "stored participant key unreadable — re-deriving", t);
            return null;
        }
    }

    private static KeyPair mintOrDerive(final Context app) throws Exception {
        final String mode = SystemProperties.get(SYSPROP_KEY_MODE, "derive");
        if (!"random".equals(mode)) {
            final byte[] secret = MlsParticipantKeyDerivation.stableSecret(
                    Settings.Secure.getString(app.getContentResolver(),
                            Settings.Secure.ANDROID_ID));
            if (secret != null && secret.length > 0) {
                final KeyPair derived = MlsParticipantKeyDerivation.deriveP256(
                        secret, MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY);
                Log.i(TAG, P + "DERIVED participant key (label="
                        + MlsParticipantKeyDerivation.LABEL_PARTICIPANT_KEY + ", spki="
                        + MlsParticipantKeyDerivation.spkiFingerprint(derived.getPublic())
                        + ") — stable across enrolments and pm-clear; the RCS provider app must log the "
                        + "SAME spki= for the ACS leg to bind the key we enrol with");
                return derived;
            }
            Log.w(TAG, P + "acs_participant_key_mode=derive but ANDROID_ID is unreadable — RANDOM "
                    + "mint. This key will NOT survive a pm clear, the provider will NOT derive the "
                    + "same key, and an ACS proof bound to it dies with it.");
        } else {
            Log.w(TAG, P + "acs_participant_key_mode=random — RANDOM mint. This is a SECOND "
                    + "identity for this device: the provider derives its own key and the two will "
                    + "NOT match, so a proof the ACS signs over its key is refused at the KDS.");
        }
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }
}
