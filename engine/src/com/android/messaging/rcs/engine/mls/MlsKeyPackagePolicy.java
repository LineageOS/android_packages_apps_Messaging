/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * When our published KeyPackage pool is refreshed, and whether a claimed peer KeyPackage may be
 * consumed. The remaining-count estimate decrements only when we open a Welcome, while a package is
 * consumed when a peer claims it, so the estimate is an upper bound; every threshold here leans
 * toward republishing, which is cheap and idempotent, since serving a last-resort leaf makes peers
 * rotate on every commit.
 */
public final class MlsKeyPackagePolicy {

    private MlsKeyPackagePolicy() {}

    /** How often the published pool is refreshed absent a consumption signal: 24 hours. */
    public static final long KP_REPUBLISH_MS = 24L * 60 * 60 * 1000;

    /** Republish once the estimate reaches this, not zero: the estimate runs high. */
    public static final int KP_REPLENISH_AT = 3;

    /**
     * At most one pool repair (after an unopenable Welcome) per hour. Time-based because the
     * condition belongs to the published pool, not a peer. The trigger is peer-supplied (any blob
     * that looks like a Welcome), so do not loosen this without first making the repair conditional
     * on "no matching key package".
     */
    public static final long POOL_REPAIR_MIN_INTERVAL_MS = 60L * 60L * 1000L;

    /** {@link #POOL_REPAIR_MIN_INTERVAL_MS}, for log lines. */
    public static long poolRepairIntervalMs() {
        return POOL_REPAIR_MIN_INTERVAL_MS;
    }

    /**
     * Whether the published pool is low enough to replenish.
     *
     * @param remaining the lower of the two estimates; still an upper bound, so this means
     *     "possibly nearly empty"
     */
    public static boolean poolLow(final int remaining) {
        return remaining <= KP_REPLENISH_AT;
    }

    /**
     * {@link #poolLow}, only on a device that has published: before the first publish the estimate
     * is zero because nothing was counted. The first publish is driven by {@link #republishDue}.
     */
    public static boolean poolDrained(final long lastPublishMs, final int remaining) {
        return lastPublishMs != 0L && poolLow(remaining);
    }

    /**
     * Whether to republish now: on the timer, or when drained, since a busy device can drain
     * before the timer fires.
     *
     * @param lastPublishMs {@code 0} if we have never published, which always republishes
     * @param drained {@link #poolDrained} for this device
     */
    public static boolean republishDue(final long lastPublishMs, final long nowMs,
            final boolean drained) {
        if (lastPublishMs == 0L) return true;
        return drained || nowMs - lastPublishMs >= KP_REPUBLISH_MS;
    }

    /** Whether a pool repair after an unopenable Welcome is allowed; the first always is. */
    public static boolean poolRepairAllowed(final long lastRepairMs, final long nowMs) {
        return nowMs - lastRepairMs >= POOL_REPAIR_MIN_INTERVAL_MS;
    }

    /**
     * Applies the RCC.16 consume-side checks to a claimed peer KeyPackage: identity equality
     * (A.4.1), cipher suite, the LeafNode lifetime floor (A.4.1.2) and, behind
     * {@link MlsConfig#KEY_KP_CERT_FLOOR}, the certificate floor. A last-resort leaf is logged and
     * accepted: refusing would make a peer with an empty pool unreachable, and a re-claim returns
     * the same package. Log lines keep the {@code "MlsKeyPackagePolicy: "} prefix that capture
     * tooling searches for. See docs/mls/credentials.md.
     *
     * @param info the engine's reading of {@code kp}; {@code null} if it would not parse
     * @param who the E.164 that was queried: a number, never a label
     * @param ourSuite the group's cipher suite
     * @param nowSecs the current time, epoch seconds
     * @return {@code true} if the KeyPackage may be consumed
     */
    public static boolean keyPackageUsable(final byte[] kp,
            final MlsSession.KeyPackageInfo info, final String who, final MlsConfig cfg,
            final int ourSuite, final long nowSecs, final MlsLogSink log) {
        if (kp == null || kp.length == 0) return false;
        if (info == null) {
            log.w("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " could not be parsed or carries no lifetime — refusing to consume it");
            return false;
        }
        // RCC.16 A.4.1: the certificate must be for the number we queried, and only here is that
        // number known. A label in who normalises to empty and reads as a mismatch.
        if (!info.msisdn.isEmpty() && !RccIdentity.msisdnEquals(info.msisdn, who)) {
            final boolean enforce = cfg.sanIdentityCheck;
            log.e("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " carries a "
                    + "certificate for a DIFFERENT number (SAN tel:" + LogMask.number(info.msisdn)
                    + ") — RCC.16 "
                    + "A.4.1 identity equality " + (enforce ? "REFUSED it" : "would refuse it "
                    + "(enforcement off via " + MlsConfig.KEY_SAN_IDENTITY_CHECK + ")"));
            if (enforce) return false;
        } else if (info.msisdn.isEmpty()) {
            // A non-X.509 credential has no SAN; the chain validator already refuses an X.509 leaf
            // without a tel: URI.
            log.i("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " asserts no "
                    + "SAN tel: identity — A.4.1 equality not applicable, chain validation stands");
        }
        // Two suite checks. The package's own suite is also checked by mls-rs, so this only fails
        // earlier with the peer named. The advertised set (RFC 9420 §7.2 requires the group's suite
        // in every member's capabilities) is not checked by mls-rs at all. An unreadable set
        // proceeds: not knowing is not evidence of incompatibility.
        if (info.cipherSuite != 0 && info.cipherSuite != ourSuite) {
            log.w("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " is for "
                    + "cipher suite " + info.cipherSuite + ", not ours (" + ourSuite
                    + ") — refusing "
                    + "to consume it. mls-rs would raise CipherSuiteMismatch when the add is built; "
                    + "this only names the peer and fails earlier.");
            return false;
        }
        if (info.suitesKnown() && !info.advertisesSuite(ourSuite)) {
            log.w("MlsKeyPackagePolicy: " + LogMask.number(who)
                    + " does not advertise cipher suite "
                    + ourSuite + " (advertises " + info.suitesText() + ") — RFC 9420 §7.2 requires "
                    + "the group's suite in EVERY member's capabilities, so adding them would build "
                    + "a group they cannot lawfully be in. Refusing; this conversation should fall "
                    + "back to Etouffee. NOTHING ELSE CATCHES THIS — mls-rs 0.55.2 does not check "
                    + "the advertised set.");
            return false;
        }
        final long minDays = cfg.kpMinRemainingDays;
        // Two clocks, always printed together: the LeafNode Lifetime (RFC 9420 §7.2), which the
        // engine mints a fixed 365 days from the certificate's notBefore, and the certificate's own
        // validity, which is the clock the server judges.
        final long remainingSecs = info.notAfterSecs - nowSecs;
        if (remainingSecs < minDays * 86400L) {
            log.w("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " has a "
                    + "LeafNode Lifetime below the RCC.16 A.4.1.2 floor of " + minDays + "d — "
                    + info.clocksText(nowSecs) + "; refusing to consume it");
            return false;
        }
        // The certificate floor is always evaluated but refuses only behind the flag: the server is
        // known to apply it to the existing roster on commit, not yet to a member being added. A
        // stale pool can serve a leaf older than the peer's current certificate, so the arm is
        // reachable. membershipChangeAllowedByFloor cannot cover this: it reads existing members.
        if (MlsCredentialFloor.insideFloor(info.certNotAfterSecs, nowSecs, minDays)) {
            final boolean enforce = cfg.kpCertFloor;
            log.w("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " carries a "
                    + "leaf CERTIFICATE inside RCC.16's " + minDays + "-day remaining-lifetime "
                    + "floor — " + info.clocksText(nowSecs) + ". This is the clock the RCS SPN "
                    + "measures (A.4.3.1 §1(a); Invariant 17 evaluates the WHOLE post-Commit "
                    + "roster), so an Add naming " + LogMask.number(who)
                    + " is PREDICTED to come back "
                    + "PERMISSION_DENIED \"Time-related validation error\" — predicted and not "
                    + "proven: every device sample of that refusal is a Commit over an EXISTING "
                    + "roster. " + (enforce ? "REFUSING it (" + MlsConfig.KEY_KP_CERT_FLOOR
                            + "=1)." : "PROCEEDING — " + MlsConfig.KEY_KP_CERT_FLOOR + " is off. "
                            + "If the Add that follows is refused naming " + LogMask.number(who)
                            + ", that settles "
                            + "it and this should be turned on."));
            if (enforce) return false;
        } else if (!info.certWindowKnown()) {
            // Not evaluated is not fine; say which.
            log.i("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " carries no "
                    + "readable leaf CERTIFICATE window (non-X.509 credential, or a leaf that would "
                    + "not parse) — the certificate floor was NOT evaluated, only the LeafNode "
                    + "Lifetime: " + info.clocksText(nowSecs));
        }
        if (info.lastResort) {
            log.w("MlsKeyPackagePolicy: the KeyPackage claimed for " + LogMask.number(who)
                    + " is a "
                    + "LAST-RESORT leaf — their one-time pool is empty. Proceeding, but expect them "
                    + "to rotate on every commit until they replenish.");
        }
        // The fingerprint matches the publish-side ordering line, so claim order can be
        // compared with publish order. Both clocks are labelled.
        log.i("MlsKeyPackagePolicy: KeyPackage for " + LogMask.number(who) + " accepted ("
                + info.clocksText(nowSecs) + ", lastResort=" + info.lastResort + ") KP-CLAIMED fp="
                + kpFingerprint(kp));
        return true;
    }

    /**
     * A short identifier for one KeyPackage: an 8-byte SHA-256 prefix of the wire bytes, not the
     * RFC 9420 {@code KeyPackageRef}, so publisher and claimer can compare without an FFI call.
     */
    public static String kpFingerprint(final byte[] kp) {
        if (kp == null || kp.length == 0) return "none";
        try {
            final byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(kp);
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb + "/" + kp.length + "B";
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            return "nohash/" + kp.length + "B";
        }
    }
}
