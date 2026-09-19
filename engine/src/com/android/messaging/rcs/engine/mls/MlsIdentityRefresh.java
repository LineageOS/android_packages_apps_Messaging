/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Keeps our MLS identity current: the weekly re-read from the provider, and what an identity change
 * does to the session, the KeyPackage pool and the group sweep. See docs/mls/credentials.md.
 */
public final class MlsIdentityRefresh {
    private MlsIdentityRefresh() {}

    /** When an identity read last failed; the refresh pref records successes only. */
    public static final String PREF_LAST_IDENTITY_ATTEMPT = "last_identity_attempt_ms";

    public static final String PREF_LAST_IDENTITY_REFRESH = "last_identity_refresh_ms";

    /**
     * Re-reads the identity weekly, with a separate short backoff after a failure. The success
     * stamp must record successes only: {@link #onIdentityChanged} clears it to force an immediate
     * re-read, and stamping on attempt would strand the identity for a week.
     */
    public static void maybeRefreshIdentity(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log) {
        try {
            final MlsPrefs p = shell.prefs();
            final long last = p.getLong(PREF_LAST_IDENTITY_REFRESH, 0L);
            final long now = System.currentTimeMillis();
            if (last != 0L && now - last < cfg.identityRefreshMs) return;
            final long lastAttempt = p.getLong(PREF_LAST_IDENTITY_ATTEMPT, 0L);
            if (lastAttempt != 0L && now - lastAttempt < cfg.identityRetryBackoffMs) return;
            if (shell.refreshIdentityFromProvider()) {
                // A success clears the failure stamp, so a later failure retries without delay.
                p.edit().putLong(PREF_LAST_IDENTITY_REFRESH, now)
                        .remove(PREF_LAST_IDENTITY_ATTEMPT).apply();
            } else {
                p.edit().putLong(PREF_LAST_IDENTITY_ATTEMPT, now).apply();
            }
        } catch (final Throwable t) {
            log.w("MlsIdentityRefresh: identity refresh check failed", t);
        }
    }

    /**
     * The provider replaced our MLS leaf certificate: re-read the identity, drop the session and
     * republish, since everything published embeds the old credential. Unconditional: the provider
     * notifies only when the chain changed, and that comparison belongs at the mint, not here too.
     */
    public static void onIdentityChanged(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String reason) {
        log.i("MlsIdentityRefresh: MLS identity changed (" + reason
                + ") — refreshing identity and republishing the KeyPackage pool");
        try {
            if (!shell.refreshIdentityFromProvider()) {
                // The read can race the provider binding. Do not publish under the old leaf; clear
                // the stamps so the next session bring-up retries at once, and arm the sweep
                // anyway.
                log.w("MlsIdentityRefresh: identity refresh failed after " + reason
                        + " — NOT publishing the pool under the OLD leaf. Clearing the refresh "
                        + "stamp so the next session bring-up re-reads the identity instead of "
                        + "waiting out the weekly cadence, and arming the sweep anyway: the "
                        + "certificate DID change, so every group's §9.5.3 answer has.");
                try {
                    shell.prefs().edit()
                            .remove(MlsIdentityRefresh.PREF_LAST_IDENTITY_REFRESH)
                            // Both throttles, or the backoff blocks the immediate retry.
                            .remove(MlsIdentityRefresh.PREF_LAST_IDENTITY_ATTEMPT).commit();
                } catch (final Throwable ignored) {
                    // A failed preference write must not turn a missed refresh into a crash.
                }
                MlsMaintenancePass.armGroupSweep(cfg, shell, log,
                        "identity change with a failed refresh (" + reason + ")");
                return;
            }
            // Drop the session strictly before generating KeyPackages, which embed the leaf held at
            // generation time; ensureSession() below generates.
            shell.dropSession();                          // reopen against the new identity
            shell.prefs().edit()
                    .putLong(MlsIdentityRefresh.PREF_LAST_IDENTITY_REFRESH,
                            System.currentTimeMillis())
                    // force the publish below past the weekly gate
                    .remove(MlsKeyPackagePool.PREF_LAST_KP_PUBLISH)
                    .apply();
            if (!shell.ensureSession()) {                 // ensureSession() publishes on the way up
                log.w("MlsIdentityRefresh: could not reopen the session after " + reason);
            }
            // Rewind the cursor so groups this walk already passed are re-examined; armGroupSweep
            // alone continues a walk. A page in flight may overwrite the rewind, which is no worse
            // than not rewinding.
            try {
                shell.prefs().edit()
                        .remove(MlsMaintenancePass.PREF_SWEEP_CURSOR).commit();
            } catch (final Throwable t) {
                log.w("MlsIdentityRefresh: could not rewind the sweep cursor after "
                        + reason + " — groups already walked in this pass keep the superseded "
                        + "certificate until the next arming", t);
            }
            MlsMaintenancePass.armGroupSweep(cfg, shell, log, "identity change (" + reason + ")");
        } catch (final Throwable t) {
            log.w("MlsIdentityRefresh: identity-change handling failed", t);
        }
    }
}
