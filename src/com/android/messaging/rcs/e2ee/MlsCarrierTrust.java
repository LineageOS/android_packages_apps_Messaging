/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * What carrier-path MLS may trust, and whether it runs at all. Plain JDK, so the host tests pin
 * it; {@link MlsTrustAnchors} supplies the inputs and holds the compiled-in values.
 *
 * <p>The compiled-in root and KDS belong to a test network. A debug build falls back to them, so
 * that network keeps working on userdebug. A user build never does: it trusts only the anchors the
 * RCS provider fetched and signature-verified, and without them carrier-path MLS is off. The
 * transport then reports MLS not ready, the send gate picks no MLS, and the conversation sends
 * through the ordinary non-MLS route. {@code TestNetworkTrustGuardTest} keeps the compiled-in
 * values behind the debug-build test.
 */
public final class MlsCarrierTrust {

    private MlsCarrierTrust() {}

    /**
     * The anchors to trust: the provider's when it supplied any, else the compiled-in ones on a
     * debug build, else none. Never a union, so a withdrawn anchor stays withdrawn.
     *
     * @param compiledIn asked only on a debug build
     */
    public static List<byte[]> anchors(final List<byte[]> providerAnchors,
            final boolean debugBuild, final Supplier<List<byte[]>> compiledIn) {
        if (providerAnchors != null && !providerAnchors.isEmpty()) {
            return providerAnchors;
        }
        if (!debugBuild) {
            return Collections.emptyList();
        }
        final List<byte[]> c = compiledIn.get();
        return (c == null) ? Collections.<byte[]>emptyList() : c;
    }

    /**
     * The configuration's KDS, else the compiled-in one on a debug build, else {@code null}: a
     * user build has no default KDS.
     *
     * @param compiledIn asked only on a debug build
     */
    public static String kdsBaseUrl(final String configured, final boolean debugBuild,
            final Supplier<String> compiledIn) {
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return debugBuild ? compiledIn.get() : null;
    }

    /** Carrier-path MLS runs only with at least one trust anchor and a KDS to enrol with. */
    public static boolean enabled(final List<byte[]> anchors, final String kdsBaseUrl) {
        return anchors != null && !anchors.isEmpty()
                && kdsBaseUrl != null && !kdsBaseUrl.isEmpty();
    }

    /**
     * The main process's answer to {@code isMlsReady} for the carrier transport. The carrier
     * path resolves its anchors in the {@code :ims} process, which never binds the provider
     * ({@code BugleApplication} skips the main-process initialisation there), so from
     * here the app can vouch only for the case that needs none of the provider's: a debug build,
     * which falls back to the compiled-in set. A user build answers false and its sends take the
     * non-MLS route. Widen this in the same change that carries the provider's anchors into
     * {@code :ims}, or the two processes disagree.
     */
    public static boolean readyInApp(final boolean attached, final boolean debugBuild) {
        return attached && debugBuild;
    }
}
