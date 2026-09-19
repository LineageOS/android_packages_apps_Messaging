/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import java.util.List;
/**
 * Our own published KeyPackage pool: how many to publish, which refs we last uploaded so a consumed
 * one can be crossed off, and when to republish. A peer's pool, which we claim from, is
 * {@link MlsKeyPackageClaims}.
 */
public final class MlsKeyPackagePool {
    private MlsKeyPackagePool() {}

    /**
     * The total pool size including the last-resort package, so {@code count - 1} are claimable,
     * matching other clients' 10 + 1. Overridable via {@link MlsConfig#KEY_KP_COUNT}.
     */
    public static int kpPoolCount(final MlsConfig cfg) {
        return cfg.kpPoolCount;   // clamped to >= 2 in MlsConfig: one claimable + one last-resort
    }

    /**
     * Hex ref of the last-resort package, tracked apart because it is reusable; a Welcome sealed
     * to it is the one hard signal that the one-time pool is empty on the server.
     */
    public static final String PREF_KP_LAST_RESORT_REF = "kp_last_resort_ref";

    /**
     * Hex {@code KeyPackageRef}s published and not yet seen consumed: an upper bound on what
     * remains, since a claim whose Welcome never reaches us is invisible. Crossing off by ref is
     * idempotent under duplicate Welcomes.
     */
    public static final String PREF_KP_PUBLISHED_REFS = "kp_published_refs";

    /**
     * Records the refs of a pool just published, replacing the previous set: the KDS holds only
     * what we last uploaded.
     */
    public static void recordPublishedKeyPackages(final MlsShellPort shell, final MlsLogSink log,
            final byte[] pool, final byte[] lastResort) {
        final MlsSession eng = shell.openMlsSession();
        if (eng == null) return;
        try {
            final java.util.Set<String> refs = new java.util.LinkedHashSet<>();
            for (final byte[] kp
                    : MlsArtifactBundle.splitLenPrefixed(pool)) {
                final byte[] r = eng.keyPackageRef(kp);
                if (r != null && r.length > 0) refs.add(MlsHex.hex(r));
            }
            String lrRef = "";
            if (lastResort != null && lastResort.length > 0) {
                final byte[] r = eng.keyPackageRef(lastResort);
                if (r != null && r.length > 0) lrRef = MlsHex.hex(r);
            }
            shell.prefs().edit()
                    .putStringSet(PREF_KP_PUBLISHED_REFS, refs)
                    .putString(PREF_KP_LAST_RESORT_REF, lrRef)
                    .apply();
            log.i("MlsKeyPackagePool: recorded " + refs.size() + " published KeyPackage "
                    + "ref(s)"
                    + (lrRef.isEmpty() ? " (no last-resort ref)" : " + a last-resort ref")
                    + " — consumption is now crossed off by ref rather than counted.");
        } catch (final Throwable t) {
            // Never let bookkeeping fail a publish that the KDS accepted.
            log.w("MlsKeyPackagePool: recording published KeyPackage refs failed", t);
        }
    }

    /**
     * Diagnostic: runs the consume-side gate over a KeyPackage we mint, which exercises the whole
     * path without consuming a peer's package. Raise {@code debug.rcs.mls_kp_min_remaining_days}
     * past the minted lifetime to see the refusal arm.
     */
    public static String vetSelfKeyPackage(final MlsShellPort shell) {
        if (!shell.ensureSession()) return "no session";
        final byte[] pool = shell.session().generateKeyPackages(1);
        final List<byte[]> kps = (pool == null) ? null : MlsArtifactBundle.splitLenPrefixed(pool);
        if (kps == null || kps.isEmpty()) return "generateKeyPackages yielded nothing";
        final MlsSession.KeyPackageInfo info = shell.session().inspectKeyPackage(kps.get(0));
        // Our own E.164, not a label: a label would fail the identity-equality check.
        final boolean usable = shell.keyPackageUsable(kps.get(0), shell.selfE164());
        if (info == null) return "usable=" + usable + " (inspect returned nothing)";
        return "usable=" + usable + " lastResort=" + info.lastResort + " "
                + info.clocksText(System.currentTimeMillis() / 1000L);
    }

    /** When the pool was last repaired after an unopenable Welcome. */
    public static final String PREF_LAST_POOL_REPAIR = "last_pool_repair_ms";

    /**
     * Republishes the pool after a Welcome we could not open, at most once per
     * {@code MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS}. Logs loudly either way: the cause
     * (packages this engine cannot open) outlives the repair.
     */
    public static void republishPoolAfterUnopenableWelcome(final MlsConfig cfg,
            final MlsShellPort shell, final MlsLogSink log, final String fromE164) {
        final long now = System.currentTimeMillis();
        final long last = shell.prefs()
                .getLong(PREF_LAST_POOL_REPAIR, 0L);
        if (!MlsKeyPackagePolicy.poolRepairAllowed(last, now)) {
            log.w("MlsKeyPackagePool: Welcome from " + LogMask.number(fromE164)
                    + " was unopenable and "
                    + "the pool was already republished " + ((now - last) / 1000L) + "s ago — not "
                    + "repeating. If this keeps recurring the new pool is ALSO unopenable, which "
                    + "means the engine store is not the one that generated it.");
            return;
        }
        shell.prefs().edit()
                .putLong(PREF_LAST_POOL_REPAIR, now).commit();
        log.w("MlsKeyPackagePool: Welcome from " + LogMask.number(fromE164)
                + " was unopenable — the "
                + "KDS is serving a KeyPackage whose private half this engine does not hold. "
                + "Regenerating and republishing the pool so claimable packages are ones we can "
                + "open.");
        try {
            final boolean ok = shell.publishKeyPackages(MlsKeyPackagePool.kpPoolCount(cfg));
            log.i("MlsKeyPackagePool: pool repair republish ok=" + ok);
        } catch (final Throwable t) {
            // Best effort: the join already failed, and a throw would hide that.
            log.w("MlsKeyPackagePool: pool repair republish threw", t);
        }
    }

    /** Our estimate of how many published one-time KeyPackages are still unclaimed. */
    public static final String PREF_KP_REMAINING = "kp_pool_remaining";

    public static final String PREF_LAST_KP_PUBLISH = "last_kp_publish_ms";

    /**
     * Keeps the published pool fresh; when it empties nobody can reach us over MLS. The app
     * publishes rather than the provider, because the private halves must live in this engine.
     */
    public static void maybePublishKeyPackages(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log) {
        try {
            final MlsPrefs p = shell.prefs();
            final long last = p.getLong(PREF_LAST_KP_PUBLISH, 0L);
            final long now = System.currentTimeMillis();
            // The lower of the two estimates: both are upper bounds, so the minimum can only make
            // us replenish earlier.
            final int counted = p.getInt(PREF_KP_REMAINING, 0);
            final java.util.Set<String> publishedRefs =
                    p.getStringSet(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS,
                            java.util.Collections.emptySet());
            final int remaining = publishedRefs.isEmpty()
                    ? counted : Math.min(counted, publishedRefs.size());
            final boolean drained = MlsKeyPackagePolicy.poolDrained(last, remaining);
            if (!MlsKeyPackagePolicy.republishDue(last, now, drained)) return;
            if (drained) {
                log.i("MlsKeyPackagePool: published KeyPackage pool down to ~"
                        + remaining + " — replenishing ahead of the periodic tick");
            } else if (last != 0L) {
                // Print the estimate beside the elapsed time; it is the number most likely wrong.
                log.i("MlsKeyPackagePool: republishing the KeyPackage pool — "
                        + ((now - last) / 3600000L)
                        + "h since the last publish. The local estimate "
                        + "says ~" + remaining
                        + " remaining, but it only counts Welcomes we opened "
                        + "and a claim consumes a package whether or not a Welcome reaches us, so "
                        + "treat it as an UPPER BOUND, not a count.");
            }
            final int total = MlsKeyPackagePool.kpPoolCount(cfg);
            if (shell.publishKeyPackages(total)) {
                // Only the claimable packages deplete; the last-resort one is reusable.
                p.edit().putLong(PREF_LAST_KP_PUBLISH, now)
                        .putInt(PREF_KP_REMAINING, total - 1).apply();
            }
        } catch (final Throwable t) {
            log.w("MlsKeyPackagePool: KeyPackage republish check failed", t);
        }
    }

    public static void noteKeyPackageConsumed(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log) {
        try {
            final MlsPrefs p = shell.prefs();
            final int remaining = Math.max(0, p.getInt(MlsKeyPackagePool.PREF_KP_REMAINING,
                    MlsKeyPackagePool.kpPoolCount(cfg) - 1) - 1);
            p.edit().putInt(MlsKeyPackagePool.PREF_KP_REMAINING, remaining).apply();
            log.i("MlsKeyPackagePool: a peer consumed one of our KeyPackages (~"
                    + remaining + " left)");
            if (MlsKeyPackagePolicy.poolLow(remaining)) MlsKeyPackagePool.maybePublishKeyPackages(
                    cfg, shell, log);
        } catch (final Throwable t) {
            log.w("MlsKeyPackagePool: KeyPackage consumption accounting failed", t);
        }
    }

    /**
     * Crosses off the package this Welcome consumed, by ref, so duplicates change nothing. A match
     * on the last-resort ref means the one-time pool is empty: the estimate is set to zero and the
     * pool republished. No match means a package we have no record of publishing, which is logged.
     *
     * @return true if the pool set was changed
     */
    public static boolean crossOffConsumedKeyPackage(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final byte[] welcome) {
        final MlsSession eng = shell.openMlsSession();
        if (eng == null || welcome == null || welcome.length == 0) return false;
        try {
            final java.util.List<byte[]> refs = eng.welcomeKeyPackageRefs(welcome);
            if (refs.isEmpty()) return false;
            final MlsPrefs p = shell.prefs();
            final java.util.Set<String> published = new java.util.LinkedHashSet<>(
                    p.getStringSet(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS,
                            java.util.Collections.emptySet()));
            final String lrRef = p.getString(MlsKeyPackagePool.PREF_KP_LAST_RESORT_REF, "");
            boolean removed = false;
            boolean lastResortServed = false;
            for (final byte[] r : refs) {
                final String h = MlsHex.hex(r);
                // One secrets entry per added member; only the intersection with ours counts.
                if (published.remove(h)) {
                    removed = true;
                    log.i("MlsKeyPackagePool: KeyPackage " + h.substring(0,
                            Math.min(16, h.length())) + "… was consumed by this Welcome — crossed "
                            + "off; " + published.size()
                            + " published ref(s) not yet known spent.");
                } else if (!lrRef.isEmpty() && lrRef.equals(h)) {
                    lastResortServed = true;
                }
            }
            if (lastResortServed) {
                log.w("MlsKeyPackagePool: this Welcome was sealed to our LAST-RESORT "
                        + "KeyPackage. The KDS hands that out only when the one-time pool is EMPTY, "
                        + "so our real remaining count is ZERO regardless of what the local estimate "
                        + "says — republishing now.");
            }
            if (!removed && !lastResortServed) {
                log.i("MlsKeyPackagePool: this Welcome was sealed to a KeyPackage we "
                        + "have no published record of (an older pool, or one we never published). "
                        + "Not an error — recorded so it stops being invisible.");
            }
            if (removed || lastResortServed) {
                final MlsPrefs.Editor e = p.edit();
                if (removed) e.putStringSet(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS, published);
                // The server has no one-time package left, whatever we counted: a zero estimate is
                // what makes the check below republish now rather than on its daily tick.
                if (lastResortServed) e.putInt(MlsKeyPackagePool.PREF_KP_REMAINING, 0);
                e.apply();
            }
            if (lastResortServed) {
                // After the write above, so the republish sees a settled set and the zero.
                MlsKeyPackagePool.maybePublishKeyPackages(cfg, shell, log);
            }
            return removed;
        } catch (final Throwable t) {
            log.w("MlsKeyPackagePool: crossing off the consumed KeyPackage failed", t);
            return false;
        }
    }
}
