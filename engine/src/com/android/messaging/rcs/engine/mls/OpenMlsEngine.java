/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import android.util.Log;

import java.util.List;

/** The in-process mls-rs engine, over {@link OpenMlsNative}. */
public final class OpenMlsEngine implements MlsEngine {
    private static final String TAG = MlsLog.TAG;

    @Override public String id() { return "openmls"; }
    @Override public boolean available() { return OpenMlsNative.available(); }

    /**
     * The LeafNode lifetime of the KeyPackages this engine mints. The key directory the transport
     * publishes to decides it, and the two directories we publish to have opposite rules: each
     * rejects the other's KeyPackages. So there is no default, and the transport chooses.
     */
    public enum KeyPackageLifetime {
        /**
         * RCC.16 §5.1: the package must not outlive the client certificate. From the certificate's
         * notBefore to 30 minutes before its notAfter, capped at 365 days.
         */
        WITHIN_CERTIFICATE,
        /**
         * Google's KDS: the certificate's notBefore plus 365 days, which outlives the certificate.
         * That KDS rejects a package that expires before it expects.
         */
        FIXED_365_DAYS
    }

    /**
     * How a peer's certificate is judged against the RCC.16 A.4.1 and §14.2.3 rules. The {@code .4}
     * proof-of-possession is not part of this: it is enforced under both.
     */
    public enum PeerCertificatePolicy {
        /**
         * Every rule rejects: a leaf lifetime over 76 days, under 30 days remaining, an unlisted
         * X.509 extension, a subject clientIdentifier that is not a UUID.
         */
        RCC16_STRICT,
        /**
         * Deployed leaves live about 75 days and would fail the 30-day floor for part of their
         * life. Only expiry rejects; the lifetime ceiling is not checked, and an unlisted
         * extension or a non-UUID clientIdentifier is logged.
         */
        DEPLOYMENT_TOLERANT
    }

    /**
     * Serialises every session start in the process. The native settings are process-global and
     * read while a session is built, and one process runs both transports, each with its own
     * engine instance. So the lock is the class's, not the instance's.
     */
    private static final Object START_LOCK = new Object();

    private final KeyPackageLifetime mKpLifetime;
    private final PeerCertificatePolicy mPeerPolicy;
    private final Rcc16Version mVersion;

    /** Uses {@link Rcc16Version#V3_0}. */
    public OpenMlsEngine(final KeyPackageLifetime kpLifetime,
            final PeerCertificatePolicy peerPolicy) {
        this(kpLifetime, peerPolicy, Rcc16Version.V3_0);
    }

    /** A null setting is taken as the RCC.16 one. */
    public OpenMlsEngine(final KeyPackageLifetime kpLifetime,
            final PeerCertificatePolicy peerPolicy, final Rcc16Version version) {
        this.mKpLifetime =
                (kpLifetime == null) ? KeyPackageLifetime.WITHIN_CERTIFICATE : kpLifetime;
        this.mPeerPolicy = (peerPolicy == null) ? PeerCertificatePolicy.RCC16_STRICT : peerPolicy;
        this.mVersion = (version == null) ? Rcc16Version.V3_0 : version;
    }

    /** The flags byte {@link OpenMlsNative#nativeSetEngineSettings} takes. */
    private int nativeSettings() {
        return (mKpLifetime == KeyPackageLifetime.FIXED_365_DAYS
                        ? OpenMlsNative.SETTING_KP_FIXED_365_DAYS : 0)
                | (mPeerPolicy == PeerCertificatePolicy.DEPLOYMENT_TOLERANT
                        ? OpenMlsNative.SETTING_PEER_CERT_TOLERANT : 0);
    }

    @Override public MlsSession startSession(final String storageDir, final MlsIdentity identity,
            final MlsPorts ports) {
        synchronized (START_LOCK) {
            return startSessionLocked(storageDir, identity, ports);
        }
    }

    private MlsSession startSessionLocked(final String storageDir, final MlsIdentity identity,
            final MlsPorts ports) {
        if (!available()) { Log.w(TAG, "startSession: native not available"); return null; }
        final MlsTelemetry telemetry = (ports == null) ? MlsTelemetry.NONE : ports.telemetry;
        // Host-side preconditions, checked first so each failure is reported by its own reason.
        final int pre = MlsMetrics.preflightCreateClient(identity);
        if (pre != MlsMetrics.CLIENT_OK) {
            telemetry.count(MlsMetrics.ZINNIA_CLIENT_FAILURE_REASON, pre);
            Log.e(TAG, "startSession: Failed to create client: "
                    + MlsMetrics.clientFailureReason(pre) + " (counter=" + pre + ")");
            return null;
        }
        // Process-global: set explicitly on every start, before any KeyPackage or GroupContext is
        // built, so an earlier session in the process cannot choose the settings or the RCC.16
        // revision. START_LOCK keeps another start from changing them before nativeSessionStart.
        OpenMlsNative.nativeSetEngineSettings(nativeSettings());
        final int effective = OpenMlsNative.nativeSetRcc16Version(mVersion.wire);
        if (effective != mVersion.wire) {
            // From here on the emitted revision differs from the one the transport asked for.
            Log.e(TAG, "startSession: engine REFUSED RCC.16 version " + mVersion + " (wire="
                    + mVersion.wire + "); it is running " + effective
                    + " instead. Wire shapes will "
                    + "NOT match what this transport announced.");
        }
        final byte[] chain = OpenMlsSession.joinLenPrefixed(identity.chainDer);
        final byte[] roots = OpenMlsSession.joinLenPrefixed(identity.roots);
        // Always passed: a present empty revocation list differs from an absent one.
        final byte[] revoked = OpenMlsSession.joinLenPrefixed(identity.revokedSerials);
        if (storageDir == null || storageDir.isEmpty()) {
            Log.e(TAG, "startSession: no storage directory");
            return null;
        }
        final String dir = storageDir + "/openmls_store/" + safe(identity.e164);
        final long h = OpenMlsNative.nativeSessionStart(
                identity.leafDer, chain, identity.subjectPriv, identity.subjectPub, roots,
                revoked, dir);
        if (h == 0L) { Log.w(TAG, "startSession: nativeSessionStart returned 0"); return null; }
        Log.i(TAG, "startSession: OpenMLS session up for " + LogMask.number(identity.e164)
                + " kpLifetime=" + mKpLifetime + " peerCerts=" + mPeerPolicy
                + " rcc16=" + mVersion);
        return new OpenMlsSession(h);
    }
    private static String safe(String e164) {
        return e164 == null ? "self" : e164.replaceAll("[^A-Za-z0-9+]", "_");
    }
}
