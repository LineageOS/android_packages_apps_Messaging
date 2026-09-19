/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * The opaque E2EE scheme ids carried across the provider AIDL ({@code RcsE2eeInfo.schemeId},
 * {@code RcsIncomingMessage.e2eeSchemeId}), and the capability tags that drive MLS eligibility.
 * {@link #ETOUFFEE} is a plane the provider owns end to end; the app only reads its availability
 * and per-message tag. {@link #MLS} is the GSMA RCS E2EE plane, implemented in the app. See
 * docs/rcs/provider-contract.md.
 */
public final class RcsE2eeScheme {
    private RcsE2eeScheme() {}

    /** The provider-owned plane. */
    public static final String ETOUFFEE = "google.etouffee";

    /** GSMA RCS E2EE over MLS ({@code mls-version=v1}). */
    public static final String MLS = "gsma.rcs-e2ee.mls";

    /** A {@code null} scheme id means plaintext. */
    public static final String NONE = null;

    /** The one RFC 9420 cipher suite in use, {@code P256_AES128} (wire value 2). */
    public static final int MLS_CIPHERSUITE_P256_AES128 = 2;

    /** GSMA MLS support, value {@code "v1"}: the primary eligibility path. */
    public static final String TAG_MLS_VERSION = "+g.gsma.rcs.mls.mls-version";

    /** Required for MLS whatever else a peer advertises; its value selects the KDS version. */
    public static final String TAG_MLS_KDS = "+g.gsma.rcs.mls.mls-kds";

    /** Same-build fallback path; value is the launch iteration as a decimal string. */
    public static final String TAG_MLS_LAUNCH_ITERATION = "+g.google.rcs.mls-launch-iteration";

    /** Group support for the launch-iteration path; {@code "true"} or {@code "false"}. */
    public static final String TAG_MLS_SUPPORTS_GROUPS = "+g.google.rcs.mls-supports-groups";

    /** The MLS version value this build speaks. */
    public static final String MLS_VERSION_V1 = "v1";

    /**
     * The launch iteration this build claims, for peers that match on it instead of advertising
     * {@code mls-version}. Must equal what the provider registers. On a mismatch {@link
     * MlsCapabilities#refusalReason} names both values; {@link #PROP_LAUNCH_ITERATION} overrides.
     */
    public static final String OUR_LAUNCH_ITERATION_DEFAULT = "1";

    /** Property override for {@link #OUR_LAUNCH_ITERATION_DEFAULT}. */
    public static final String PROP_LAUNCH_ITERATION = "debug.rcs.mls_launch_iteration";

    /** The launch iteration this build claims, honouring the sysprop override. */
    public static String ourLaunchIteration() {
        final String override = android.os.SystemProperties.get(PROP_LAUNCH_ITERATION, "");
        return (override == null || override.isEmpty()) ? OUR_LAUNCH_ITERATION_DEFAULT : override;
    }
}
