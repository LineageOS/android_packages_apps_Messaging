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
package com.android.messaging.rcs.engine.mls;

import android.util.Log;

import java.util.List;

/** OpenMLS (mls-rs) engine — owns the crypto in-process (no Google-engine bootstrap dependency). */
public final class OpenMlsEngine implements MlsEngine {
    private static final String TAG = MlsLog.TAG;

    @Override public String id() { return "openmls"; }
    @Override public boolean available() { return OpenMlsNative.available(); }

    /**
     * Which behaviour profile the shared Rust engine should run in.
     *
     * <p><b>This is deliberately NOT defaulted, and there is no no-arg constructor.</b> The profile is
     * process-global and changes certificate validation and KeyPackage lifetime, so a caller that
     * "just wants an engine" must still say which world it is in — the compiler asks, rather than a
     * silent default being wrong for one of the two apps.
     *
     * <p>Why the strictness: when this class was provider-only it hardcoded {@code
     * nativeSetTachyonProfile(true)}. Sharing it with messaging2 would have
     * silently forced the Tachyon profile onto the carrier/lab path, whose KDS rejects a 365-day
     * KeyPackage — a regression with no compile error and no obvious symptom.
     */
    public enum Profile {
        /** Google Tachyon: lenient {@code .4} PoP so Google Messages certs validate, 365-day KP lifetime
         *  (Google's KDS rejects the lab's cert-clamped KP as "expires too early"). */
        TACHYON,
        /** Lab / RCC.16 §5.1: KP lifetime clamped to the client cert. The Rust engine's default. */
        LAB_RCC16
    }

    private final Profile mProfile;
    private final Rcc16Version mVersion;

    /**
     * Defaults to {@link Rcc16Version#V3_0}.
     *
     * <p><b>The version has a default and {@link Profile} deliberately does not</b>, and the
     * difference is evidential rather than stylistic. There is no safe default profile — each app's
     * KDS rejects the other's KeyPackage, so the compiler has to ask. There IS a safe default
     * revision: v3.0 is the only one with wire evidence behind it. See {@link Rcc16Version}.
     */
    public OpenMlsEngine(final Profile profile) {
        this(profile, Rcc16Version.V3_0);
    }

    public OpenMlsEngine(final Profile profile, final Rcc16Version version) {
        this.mProfile = (profile == null) ? Profile.LAB_RCC16 : profile;
        this.mVersion = (version == null) ? Rcc16Version.V3_0 : version;
    }

    @Override public MlsSession startSession(final String storageDir, final MlsIdentity identity,
            final MlsPorts ports) {
        if (!available()) { Log.w(TAG, "startSession: native not available"); return null; }
        final MlsTelemetry telemetry = (ports == null) ? MlsTelemetry.NONE : ports.telemetry;
        // The five §3.3 host-side preconditions, BEFORE the FFI call — rework 13.3.
        //
        // There were none. nativeSessionStart took whatever the identity held and returned 0 for
        // every kind of failure, so a missing intermediate, an absent trust anchor and a null device
        // keypair were one undifferentiated value — at the exact point where "provisioning is
        // broken" and "the engine is broken" have completely different remedies.
        final int pre = MlsMetrics.preflightCreateClient(identity);
        if (pre != MlsMetrics.CLIENT_OK) {
            telemetry.count(MlsMetrics.ZINNIA_CLIENT_FAILURE_REASON, pre);
            Log.e(TAG, "startSession: Failed to create client: "
                    + MlsMetrics.clientFailureReason(pre) + " (counter=" + pre + ")");
            return null;
        }
        // Idempotent process-global; must precede nativeSessionStart + any KeyPackage generation
        //. Set explicitly BOTH ways — never leave it to whatever a previous session
        // in this process happened to select.
        OpenMlsNative.nativeSetTachyonProfile(mProfile == Profile.TACHYON);
        // Same contract as the profile: process-global, set EXPLICITLY on every session start so a
        // previous session in this process can never decide which spec revision we emit. Before any
        // KeyPackage or GroupContext is built, because the version selects extension_data shapes.
        final int effective = OpenMlsNative.nativeSetRcc16Version(mVersion.wire);
        if (effective != mVersion.wire) {
            // The engine refused the value and kept its own. Louder than a silent mismatch: from
            // here on we would be emitting a different spec revision than the transport asked for.
            Log.e(TAG, "startSession: engine REFUSED RCC.16 version " + mVersion + " (wire="
                    + mVersion.wire + "); it is running " + effective + " instead. Wire shapes will "
                    + "NOT match what this transport announced.");
        }
        final byte[] chain = OpenMlsSession.joinLenPrefixed(identity.chainDer);
        final byte[] roots = OpenMlsSession.joinLenPrefixed(identity.roots);
        // 0.9: the host-pushed revocation list. Passed on EVERY session start, empty or not —
        // Google Messages sends the message present-and-empty, and a present empty list is a different
        // statement from an absent one.
        final byte[] revoked = OpenMlsSession.joinLenPrefixed(identity.revokedSerials);
        // The per-identity subdirectory is still ours to choose — it is an engine-layout concern —
        // but the ROOT now comes from the caller. Deriving it from a Context is what put unbounded
        // ambient authority into this signature.
        if (storageDir == null || storageDir.isEmpty()) {
            Log.e(TAG, "startSession: no storage directory");
            return null;
        }
        final String dir = storageDir + "/openmls_store/" + safe(identity.e164);
        final long h = OpenMlsNative.nativeSessionStart(
                identity.leafDer, chain, identity.subjectPriv, identity.subjectPub, roots,
                revoked, dir);
        if (h == 0L) { Log.w(TAG, "startSession: nativeSessionStart returned 0"); return null; }
        Log.i(TAG, "startSession: OpenMLS session up for " + identity.e164 + " profile=" + mProfile
                + " rcc16=" + mVersion);
        return new OpenMlsSession(h);
    }
    private static String safe(String e164) {
        return e164 == null ? "self" : e164.replaceAll("[^A-Za-z0-9+]", "_");
    }
}
