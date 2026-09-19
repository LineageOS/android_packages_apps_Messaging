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

/**
 * The opaque E2EE scheme identifiers exchanged across the scheme-opaque v16
 * provider AIDL seam ({@code RcsE2eeInfo.schemeId},
 * {@code RcsIncomingMessage.e2eeSchemeId}).
 *
 * <p>These map 1:1 onto the two co-existing E2EE planes Google Messages
 * implements:
 * <ul>
 *   <li>{@link #ETOUFFEE} — Google's vendor Etouffee/Scytale-v3 plane, owned by
 *       the provider app over Tachyon. <b>DONE + device-verified.</b> messaging2
 *       only consumes its availability + per-message tag through the opaque seam.</li>
 *   <li>{@link #MLS} — the GSMA-standard RCS E2EE / MLS plane
 *       ({@code mls-version=v1}, RFC&nbsp;9420 {@code P256_AES128}). Built
 *       wholesale app-layer in messaging2.</li>
 * </ul>
 *
 * <p>The selection gate ({@link E2eeSchemeGate}) is the app-layer analogue of
 * Google Messages' plane resolver plus its capability eligibility tree, both of
 * which are pure Java-side logic there — hence app-layer here too.
 */
public final class RcsE2eeScheme {
    private RcsE2eeScheme() {}

    /** Google vendor Etouffee/Scytale-v3 (provider plane). */
    public static final String ETOUFFEE = "google.etouffee";

    /** GSMA-standard RCS E2EE over MLS ({@code mls-version=v1}). */
    public static final String MLS = "gsma.rcs-e2ee.mls";

    /** {@code null} schemeId means plaintext (no E2EE plane active). */
    public static final String NONE = null;

    /**
     * The single RFC-9420 ciphersuite Google Messages exercises:
     * {@code P256_AES128} = wire value {@code 2}.
     * Pin this everywhere the MLS
     * engine and the KDS Claim/Upload wire touch a ciphersuite.
     */
    public static final int MLS_CIPHERSUITE_P256_AES128 = 2;

    // --- The RCS capability feature-tags that drive MLS eligibility. ---

    /** GSMA-standard MLS support; value {@code "v1"}. Primary (cross-vendor) MLS path. */
    public static final String TAG_MLS_VERSION = "+g.gsma.rcs.mls.mls-version";

    /**
     * <b>Hard gate</b>; Google Messages advertises {@code "2"}. Also the per-claim
     * KDS-version selector (ClaimRequest field 4). A peer
     * lacking this tag is not MLS-capable regardless of other tags.
     */
    public static final String TAG_MLS_KDS = "+g.gsma.rcs.mls.mls-kds";

    /** Google-wave same-build fallback; value = {@code String.valueOf(launchIteration)}. */
    public static final String TAG_MLS_LAUNCH_ITERATION = "+g.google.rcs.mls-launch-iteration";

    /** Group-MLS fallback for the Google path; value {@code "true"}/{@code "false"}. */
    public static final String TAG_MLS_SUPPORTS_GROUPS = "+g.google.rcs.mls-supports-groups";

    /** The GSMA-standard MLS version value this build speaks. */
    public static final String MLS_VERSION_V1 = "v1";

    /**
     * The Google-wave launch iteration THIS build claims, for the
     * {@code mls-launch-iteration == ours} eligibility path.
     *
     * <p><b>Must equal what the provider actually advertises.</b> The provider registers
     * {@code +g.google.rcs.mls-launch-iteration = "1"}
     * (`OpenRCSChat/.../TachyonRegistrar.java`, in its MLS feature-tag tuple), so claiming a
     * different value here would make us assert one wave on the wire and test for another.
     *
     * <p><b>Why this exists.</b> Google Messages peers do <em>not</em> advertise
     * {@code mls-version} — that tag is gated by a server-side rollout flag on the
     * <em>advertising</em> side, so un-flagged lines omit it permanently and no app upgrade
     * changes that (verified on the wire across two Google Messages versions). The launch-iteration
     * match is the path Google Messages itself uses peer-to-peer, so matching it is fidelity to that
     * client rather than a workaround. {@code mls-version} remains the path for other peers — Apple
     * advertises it and is unaffected by Google's rollout.
     *
     * <p><b>Known failure mode.</b> If Google advances the wave, peers will advertise a different
     * iteration and this equality stops matching — MLS selection would silently stop for Google peers.
     * {@link MlsCapabilities#peerPassesMls} logs a WAVE-DRIFT warning naming both values so that shows
     * up as a diagnosable log line rather than a mysterious downgrade to Etouffee. Override without a
     * rebuild via {@code debug.rcs.mls_launch_iteration}.
     */
    public static final String OUR_LAUNCH_ITERATION_DEFAULT = "1";

    /** Sysprop override for {@link #OUR_LAUNCH_ITERATION_DEFAULT} (no rebuild needed on a wave bump). */
    public static final String PROP_LAUNCH_ITERATION = "debug.rcs.mls_launch_iteration";

    /** The launch iteration this build claims, honouring the sysprop override. */
    public static String ourLaunchIteration() {
        final String override = android.os.SystemProperties.get(PROP_LAUNCH_ITERATION, "");
        return (override == null || override.isEmpty()) ? OUR_LAUNCH_ITERATION_DEFAULT : override;
    }
}
