/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Every MLS behavioural knob, resolved once from a {@link Source} (system properties on device,
 * literals in tests) so no policy knob changes mid-operation. Diagnostic knobs are also readable
 * live.
 */
public final class MlsConfig {

    public interface Source {
        boolean getBoolean(String key, boolean def);
        int getInt(String key, int def);
        long getLong(String key, long def);
    }

    public static final String KEY_ERA_YIELD_LOOKS = "debug.rcs.mls_era_yield_looks";
    public static final String KEY_DUMP_KP = "debug.rcs.mls_dump_kp";
    public static final String KEY_DUMP_AAD = "debug.rcs.mls_dump_aad";
    public static final String KEY_ERA_ADVANCE_MODE = "debug.rcs.mls_era_advance_mode";
    public static final String KEY_SAN_IDENTITY_CHECK = "debug.rcs.mls_san_identity_check";
    /**
     * Publish {@code external_pub} on our own commits, so others can resync-join by external commit
     * (default off; untested against the server).
     */
    public static final String KEY_PUBLISH_EXTERNAL_PUB = "debug.rcs.mls_publish_external_pub";

    /**
     * Plaintext delivery IMDN for MLS we hold no group for (default off: a peer may read it as a
     * signal to demote encryption). See docs/mls/health-and-recovery.md.
     */
    public static final String KEY_PLAINTEXT_RECONCILE_IMDN =
            "debug.rcs.mls_plaintext_reconcile_imdn";
    /**
     * Downgrade out of MLS on the repair-exhausted terminal rather than only record it (default
     * on): otherwise the group stalls, delivering nothing. See docs/mls/downgrade.md.
     */
    public static final String KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED =
            "debug.rcs.mls_downgrade_on_repair_exhausted";
    /** Run the maintenance pass when a conversation opens (default on); recovery is separate. */
    public static final String KEY_MAINTENANCE_ON_OPEN = "debug.rcs.mls_maintenance_on_open";
    /**
     * Run the maintenance sweep over groups nobody opened (default on). No interval: state changes
     * arm it and it disarms after one walk.
     */
    public static final String KEY_GROUP_SWEEP = "debug.rcs.mls_group_sweep";
    public static final String KEY_KP_MIN_DAYS = "debug.rcs.mls_kp_min_remaining_days";
    /**
     * Refuse an add or remove locally when a member's credential is inside the RCC.16 floor, which
     * the server refuses anyway (RCC.16 A.4.3.1), so the failure names the member (default on).
     */
    public static final String KEY_FLOOR_PRECHECK = "debug.rcs.mls_floor_precheck";
    /**
     * Refuse a claimed KeyPackage whose certificate is inside the floor (default off; always
     * logged). See docs/mls/credentials.md.
     */
    public static final String KEY_KP_CERT_FLOOR = "debug.rcs.mls_kp_cert_floor";
    /**
     * Let maintenance rebuild a group wedged by the floor (default off: a rebuild re-Welcomes every
     * member). See docs/mls/credentials.md.
     */
    public static final String KEY_FLOOR_REBUILD = "debug.rcs.mls_floor_rebuild";
    public static final String KEY_KP_COUNT = "debug.rcs.mls_kp_count";
    public static final String KEY_SELF_HEAL_RETRY_LIMIT = "debug.rcs.mls_self_heal_retries";
    public static final String KEY_SELF_HEAL_WINDOW_S = "debug.rcs.mls_self_heal_window_s";
    public static final String KEY_REUPGRADE_BASE_S = "debug.rcs.mls_reupgrade_base_s";
    public static final String KEY_REUPGRADE_MAX_SHIFT = "debug.rcs.mls_reupgrade_max_shift";
    public static final String KEY_REUPGRADE_STABILITY_S = "debug.rcs.mls_reupgrade_stability_s";
    /**
     * Debug builds only: a user build uses {@link #DEF_METADATA_KEYS_EXT}. {@code 0} makes the
     * maintenance add-arm report itself un-evaluable.
     */
    public static final String KEY_METADATA_KEYS_EXT = "debug.rcs.mls_metadata_keys_ext";

    /** {@code 30} = RCC.16 v3.0 (default), {@code 40} = v4.0; the engine refuses anything else. */
    public static final String KEY_RCC16_VERSION = "debug.rcs.mls_rcc16_version";

    public static final String KEY_FTD_MAX_ATTEMPTS = "debug.rcs.mls_ftd_max_attempts";

    public static final String KEY_IDENTITY_REFRESH_DAYS = "debug.rcs.mls_identity_refresh_days";

    /** Unmoved looks before advancing anyway; 0 yields forever. */
    public static final int DEF_ERA_YIELD_LOOKS = 3;
    /** Rebuild from KeyPackages; the other shapes violate RCC.16 §9.2. */
    public static final int DEF_ERA_ADVANCE_MODE = 0;
    /** RCC.16 A.4.1.2, A.4.2.2: the least certificate lifetime a consumed KeyPackage may have. */
    public static final long DEF_KP_MIN_REMAINING_DAYS = 30L;
    /** Claimable plus last-resort. */
    public static final int DEF_KP_POOL_TOTAL = 11;
    public static final int MIN_KP_POOL_TOTAL = 2;
    public static final int DEF_SELF_HEAL_RETRY_LIMIT = 5;
    public static final long DEF_SELF_HEAL_WINDOW_S = 86_400L;
    /** RCC.16 v4.0 §7.11.10.1; the engine decodes both framings and never encodes it. */
    public static final int DEF_METADATA_KEYS_EXT = 0xF007;

    public static final int DEF_RCC16_VERSION = 30;

    public static final long DEF_IDENTITY_REFRESH_DAYS = 7L;

    public final int eraYieldLooks;
    /** Debug builds only, like {@link #dumpAad}. */
    public final boolean dumpKeyPackages;
    /** Debug builds only: the AAD carries the message id, and the dump names the sender. */
    public final boolean dumpAad;
    public final int eraAdvanceMode;
    /**
     * RCC.16 A.4.1 SAN/MSISDN equality, a security control. Only a debug build can turn it off,
     * and only to diagnose.
     */
    public final boolean sanIdentityCheck;
    public final boolean publishExternalPub;
    public final boolean downgradeOnRepairExhausted;
    public final boolean plaintextReconcileImdn;
    public final boolean maintenanceOnOpen;
    public final boolean groupSweep;
    public final long kpMinRemainingDays;
    public final boolean floorPrecheck;
    public final boolean kpCertFloor;
    public final boolean floorRebuild;
    public final int kpPoolCount;
    public final int selfHealRetryLimit;
    public final long selfHealWindowMs;
    /** Re-upgrade backoff; see docs/mls/downgrade.md. */
    public final long reupgradeBackoffBaseS;
    public final int reupgradeBackoffMaxShift;
    public final long reupgradeStabilityWindowS;
    /** Or {@link #METADATA_KEYS_EXT_UNKNOWN}. */
    public final int metadataKeysExtType;

    /** Zero is not a valid MLS extension type; a sentinel, distinct from the default. */
    public static final int METADATA_KEYS_EXT_UNKNOWN = 0;

    public boolean metadataKeysExtKnown() {
        return metadataKeysExtType != METADATA_KEYS_EXT_UNKNOWN;
    }

    public final int rcc16Version;

    /** RCC.16 §10.3's FTD chain cap. */
    public final int ftdMaxAttempts;

    public final long identityRefreshMs;

    /**
     * Suppression after a failed identity read. Separate from {@link #identityRefreshMs}: stamping
     * that on failure could overwrite a forced re-read and strand the identity for a week.
     */
    public final long identityRetryBackoffMs;

    /** For the diagnostic knobs, read live. */
    private final Source mSource;

    private MlsConfig(final Source s) {
        mSource = s;
        eraYieldLooks = s.getInt(KEY_ERA_YIELD_LOOKS, DEF_ERA_YIELD_LOOKS);
        dumpKeyPackages = debuggableBuild(s) && s.getBoolean(KEY_DUMP_KP, false);
        dumpAad = debuggableBuild(s) && s.getBoolean(KEY_DUMP_AAD, false);
        eraAdvanceMode = s.getInt(KEY_ERA_ADVANCE_MODE, DEF_ERA_ADVANCE_MODE);
        sanIdentityCheck = !debuggableBuild(s) || s.getInt(KEY_SAN_IDENTITY_CHECK, 1) != 0;
        downgradeOnRepairExhausted = s.getInt(KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED, 1) != 0;
        publishExternalPub = s.getInt(KEY_PUBLISH_EXTERNAL_PUB, 0) != 0;
        plaintextReconcileImdn = s.getInt(KEY_PLAINTEXT_RECONCILE_IMDN, 0) != 0;
        maintenanceOnOpen = s.getInt(KEY_MAINTENANCE_ON_OPEN, 1) != 0;
        groupSweep = s.getInt(KEY_GROUP_SWEEP, 1) != 0;
        kpMinRemainingDays = s.getLong(KEY_KP_MIN_DAYS, DEF_KP_MIN_REMAINING_DAYS);
        floorPrecheck = s.getInt(KEY_FLOOR_PRECHECK, 1) != 0;
        kpCertFloor = s.getInt(KEY_KP_CERT_FLOOR, 0) != 0;
        floorRebuild = s.getInt(KEY_FLOOR_REBUILD, 0) != 0;
        kpPoolCount = Math.max(MIN_KP_POOL_TOTAL, s.getInt(KEY_KP_COUNT, DEF_KP_POOL_TOTAL));
        selfHealRetryLimit = s.getInt(KEY_SELF_HEAL_RETRY_LIMIT, DEF_SELF_HEAL_RETRY_LIMIT);
        selfHealWindowMs = s.getLong(KEY_SELF_HEAL_WINDOW_S, DEF_SELF_HEAL_WINDOW_S) * 1000L;
        reupgradeBackoffBaseS =
                s.getLong(KEY_REUPGRADE_BASE_S, MlsReupgradeState.DEF_BACKOFF_BASE_S);
        reupgradeBackoffMaxShift =
                s.getInt(KEY_REUPGRADE_MAX_SHIFT, MlsReupgradeState.DEF_BACKOFF_MAX_SHIFT);
        reupgradeStabilityWindowS =
                s.getLong(KEY_REUPGRADE_STABILITY_S, MlsReupgradeState.DEF_STABILITY_WINDOW_S);
        metadataKeysExtType = debuggableBuild(s)
                ? s.getInt(KEY_METADATA_KEYS_EXT, DEF_METADATA_KEYS_EXT) : DEF_METADATA_KEYS_EXT;
        rcc16Version = s.getInt(KEY_RCC16_VERSION, DEF_RCC16_VERSION);
        ftdMaxAttempts = s.getInt(KEY_FTD_MAX_ATTEMPTS, MlsFtdEscalation.MAX_FTD_ATTEMPTS);
        identityRefreshMs =
                s.getLong(KEY_IDENTITY_REFRESH_DAYS, DEF_IDENTITY_REFRESH_DAYS) * 24L * 3600_000L;
        identityRetryBackoffMs = DEF_IDENTITY_RETRY_BACKOFF_MS;
    }

    private static final long DEF_IDENTITY_RETRY_BACKOFF_MS = 30_000L;

    /** Prefer over {@link #dumpAad}, which records the startup value. */
    public boolean dumpAadLive() {
        return debuggableBuild(mSource) && mSource.getBoolean(KEY_DUMP_AAD, false);
    }

    public boolean dumpKeyPackagesLive() {
        return debuggableBuild(mSource) && mSource.getBoolean(KEY_DUMP_KP, false);
    }

    /**
     * {@code ro.debuggable}. A {@code debug.*} knob that dumps or relaxes something needs it too:
     * adb can set a {@code debug.*} property on a user build, but not an {@code ro.*} one.
     */
    private static boolean debuggableBuild(final Source s) {
        return s.getInt("ro.debuggable", 0) == 1;
    }

    public static MlsConfig from(final Source s) {
        return new MlsConfig(s);
    }

    /** The shipped behaviour, and the baseline for host tests. */
    public static MlsConfig defaults() {
        return new MlsConfig(new Source() {
            @Override public boolean getBoolean(final String k, final boolean d) { return d; }
            @Override public int getInt(final String k, final int d) { return d; }
            @Override public long getLong(final String k, final long d) { return d; }
        });
    }

    @Override public String toString() {
        return "MlsConfig{eraYieldLooks=" + eraYieldLooks
                + " dumpKp=" + dumpKeyPackages
                + " dumpAad=" + dumpAad
                + " eraAdvanceMode=" + eraAdvanceMode
                + " sanIdentityCheck=" + sanIdentityCheck
                + " downgradeOnRepairExhausted=" + downgradeOnRepairExhausted
                + " plaintextReconcileImdn=" + plaintextReconcileImdn
                + " publishExternalPub=" + publishExternalPub
                + " maintenanceOnOpen=" + maintenanceOnOpen
                + " groupSweep=" + groupSweep
                + " kpMinDays=" + kpMinRemainingDays
                + " floorPrecheck=" + floorPrecheck
                + " kpCertFloor=" + kpCertFloor
                + " floorRebuild=" + floorRebuild
                + " kpPool=" + kpPoolCount
                + " selfHealRetries=" + selfHealRetryLimit
                + " selfHealWindowMs=" + selfHealWindowMs
                + " reupgradeBaseS=" + reupgradeBackoffBaseS
                + " reupgradeMaxShift=" + reupgradeBackoffMaxShift
                + " reupgradeStabilityS=" + reupgradeStabilityWindowS
                + " ftdMaxAttempts=" + ftdMaxAttempts
                + " identityRefreshMs=" + identityRefreshMs
                + " rcc16=" + rcc16Version
                + " metadataKeysExt=" + (metadataKeysExtKnown()
                        ? String.format("0x%04X", metadataKeysExtType) : "UNKNOWN")
                + "}";
    }
}
