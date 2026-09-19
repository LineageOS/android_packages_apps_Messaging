/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * {@link MlsKeyPackagePolicy}: the published-pool cadence (daily republish, replenish at three,
 * pool repair at most hourly) and the consume-side {@code keyPackageUsable} gate. See
 * docs/mls/credentials.md.
 */
public final class MlsKeyPackagePolicyTest {

    @Test
    public void theThreeBoundsAreThreeDifferentQuestions() {
        assertEquals(24L * 60 * 60 * 1000, MlsKeyPackagePolicy.KP_REPUBLISH_MS);
        assertEquals(60L * 60L * 1000L, MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS);
        assertEquals(3, MlsKeyPackagePolicy.KP_REPLENISH_AT);
        assertTrue("the repair gate must be SHORTER than the publish tick — it answers an "
                + "unopenable Welcome, which is a fault, not a schedule",
                MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS
                        < MlsKeyPackagePolicy.KP_REPUBLISH_MS);
    }

    /**
     * A device that never published always republishes and is never called drained: its estimate of
     * zero means nothing was counted, not that a pool ran out.
     */
    @Test
    public void aDeviceThatNeverPublishedPublishes_andIsNotDrained() {
        assertFalse(MlsKeyPackagePolicy.poolDrained(0L, 0));
        assertTrue(MlsKeyPackagePolicy.republishDue(0L, 0L, false));
        assertTrue(MlsKeyPackagePolicy.republishDue(0L, Long.MAX_VALUE / 2, false));
    }

    /** The drain signal republishes before the daily tick would. */
    @Test
    public void theDrainSignalRepublishesLongBeforeTheTick() {
        final long last = 1_000L;
        final long anHourLater = last + 60L * 60 * 1000;
        assertFalse("an hour is nowhere near the daily tick",
                MlsKeyPackagePolicy.republishDue(last, anHourLater, false));

        assertTrue(MlsKeyPackagePolicy.poolDrained(last, MlsKeyPackagePolicy.KP_REPLENISH_AT));
        assertTrue("a drained pool must not wait for the tick — a device popular for a day is "
                + "drained long before the day is out",
                MlsKeyPackagePolicy.republishDue(last, anHourLater, true));
    }

    /** Replenish at three, not at zero, or a peer arrives to an empty pool. */
    @Test
    public void thePoolIsLowAtThreeNotAtZero() {
        assertTrue(MlsKeyPackagePolicy.poolLow(0));
        assertTrue(MlsKeyPackagePolicy.poolLow(MlsKeyPackagePolicy.KP_REPLENISH_AT));
        assertFalse(MlsKeyPackagePolicy.poolLow(MlsKeyPackagePolicy.KP_REPLENISH_AT + 1));
    }

    /** The periodic tick fires at a day, not after it. */
    @Test
    public void theTickFiresAtTheDayBoundary() {
        final long last = 5_000L;
        assertFalse(MlsKeyPackagePolicy.republishDue(
                last, last + MlsKeyPackagePolicy.KP_REPUBLISH_MS - 1, false));
        assertTrue(MlsKeyPackagePolicy.republishDue(
                last, last + MlsKeyPackagePolicy.KP_REPUBLISH_MS, false));
    }

    /**
     * The first unopenable Welcome always repairs (a zero stamp is already an hour old); a second
     * within the hour does not.
     */
    @Test
    public void theFirstRepairIsAllowedAndTheSecondWithinTheHourIsNot() {
        final long now = 9_000_000L;
        assertTrue(MlsKeyPackagePolicy.poolRepairAllowed(0L, now));
        assertFalse(MlsKeyPackagePolicy.poolRepairAllowed(now - 1, now));
        assertFalse(MlsKeyPackagePolicy.poolRepairAllowed(
                now - MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS + 1, now));
        assertTrue(MlsKeyPackagePolicy.poolRepairAllowed(
                now - MlsKeyPackagePolicy.POOL_REPAIR_MIN_INTERVAL_MS, now));
    }

    // keyPackageUsable.

    private static final String PEER = "+15551230000";
    // RcsE2eeScheme.MLS_CIPHERSUITE_P256_AES128
    private static final int OURS = 2;
    // any fixed instant; the clock is a parameter
    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;
    private static final byte[] KP = {1, 2, 3, 4};

    /** Records what the decision logged, so a refusal is checked for its reason. */
    private static final class Recording implements MlsLogSink {
        final List<String> lines = new ArrayList<>();
        @Override public void d(final String m) { lines.add("D " + m); }
        @Override public void i(final String m) { lines.add("I " + m); }
        @Override public void w(final String m) { lines.add("W " + m); }
        @Override public void w(final String m, final Throwable t) { lines.add("W " + m); }
        @Override public void e(final String m) { lines.add("E " + m); }
        @Override public void e(final String m, final Throwable t) { lines.add("E " + m); }
        boolean said(final String level, final String fragment) {
            for (final String l : lines) if (l.startsWith(level + " ")
                    && l.contains(fragment)) return true;
            return false;
        }
    }

    private static MlsConfig cfg(final Object... kv) {
        final Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i],
                ((Number) kv[i + 1]).longValue());
        return MlsConfig.from(new MlsConfig.Source() {
            @Override public boolean getBoolean(final String k, final boolean d) {
                return m.containsKey(k) ? m.get(k) != 0 : d;
            }
            @Override public int getInt(final String k, final int d) {
                return m.containsKey(k) ? m.get(k).intValue() : d;
            }
            @Override public long getLong(final String k, final long d) {
                return m.containsKey(k) ? m.get(k) : d;
            }
        });
    }

    /** A package that passes every check: our suite, advertised, 363d leaf, 73d certificate. */
    private static MlsSession.KeyPackageInfo healthy(final String msisdn) {
        return new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, msisdn, OURS, new int[] {OURS},
                NOW - 2 * DAY, NOW + 73 * DAY);
    }

    private static boolean usable(final MlsSession.KeyPackageInfo info, final String who,
            final MlsConfig c, final Recording log) {
        return MlsKeyPackagePolicy.keyPackageUsable(KP, info, who, c, OURS, NOW, log);
    }

    @Test
    public void aHealthyPackageIsAcceptedAndTheAcceptLineCarriesBothClocksAndTheFingerprint() {
        final Recording log = new Recording();
        assertTrue(usable(healthy(PEER), PEER, MlsConfig.defaults(), log));
        assertTrue(log.said("I", "accepted (leafLifetime=363d"));
        assertTrue("the ordering experiment matches claims to publishes by this fingerprint",
                log.said("I", "KP-CLAIMED fp=" + MlsKeyPackagePolicy.kpFingerprint(KP)));
    }

    @Test
    public void anUnparseablePackageIsRefusedRatherThanPassingADateFloorByDefault() {
        final Recording log = new Recording();
        assertFalse(usable(null, PEER, MlsConfig.defaults(), log));
        assertTrue(log.said("W", "could not be parsed"));
        assertFalse(MlsKeyPackagePolicy.keyPackageUsable(new byte[0], healthy(PEER), PEER,
                MlsConfig.defaults(), OURS, NOW, log));
    }

    @Test
    public void aCertificateForADifferentNumberIsRefused_andALabelInWhoReadsAsADifferentNumber() {
        final Recording log = new Recording();
        assertFalse(usable(healthy("+15559990000"), PEER, MlsConfig.defaults(), log));
        assertTrue(log.said("E", "DIFFERENT number"));
        // `who` must be a number: a description passed there refuses our own certificate, which is
        // the right failure direction, so the comparison stays strict.
        assertFalse(
                usable(healthy(PEER), "self (diagnostic)", MlsConfig.defaults(), new Recording()));
    }

    @Test
    public void identityEnforcementOffLogsTheMismatchAndProceeds() {
        final Recording log = new Recording();
        assertTrue(usable(healthy("+15559990000"), PEER,
                cfg(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0, "ro.debuggable", 1), log));
        assertTrue(log.said("E", "would refuse it"));
    }

    @Test
    public void noSanIsNotApplicableRatherThanAMismatch() {
        final Recording log = new Recording();
        assertTrue(usable(healthy(""), PEER, MlsConfig.defaults(), log));
        assertTrue(log.said("I", "asserts no SAN tel: identity"));
    }

    @Test
    public void theTwoCipherSuiteChecksFailSeparately() {
        final Recording own = new Recording();
        assertFalse(usable(new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, 3,
                new int[] {3}, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(), own));
        assertTrue(own.said("W", "is for cipher suite 3, not ours (2)"));

        final Recording advertised = new Recording();
        assertFalse(usable(new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, OURS,
                new int[] {1}, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(), advertised));
        assertTrue("the RFC 9420 §7.2 check nothing else performs",
                advertised.said("W", "does not advertise cipher suite 2"));

        // An unreadable advertised set proceeds: not knowing is not evidence of incompatibility.
        assertTrue(usable(new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, OURS,
                null, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(), new Recording()));
    }

    @Test
    public void theLeafLifetimeFloorRefusesOnItsBoundary() {
        final long floor = MlsConfig.DEF_KP_MIN_REMAINING_DAYS * DAY;
        assertFalse(usable(new MlsSession.KeyPackageInfo(false, NOW + floor - 1, PEER, OURS,
                new int[] {OURS}, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(),
                new Recording()));
        assertTrue(usable(new MlsSession.KeyPackageInfo(false, NOW + floor, PEER, OURS,
                new int[] {OURS}, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(),
                new Recording()));
    }

    @Test
    public void aCertificateInsideTheFloorIsMeasuredAlwaysButRefusedOnlyBehindTheFlag() {
        // A 363-day leaf over a certificate with 20 days left: the leaf and certificate clocks
        // disagree.
        final MlsSession.KeyPackageInfo stale = new MlsSession.KeyPackageInfo(false,
                NOW + 363 * DAY, PEER, OURS, new int[] {OURS}, NOW - 55 * DAY, NOW + 20 * DAY);
        final Recording off = new Recording();
        assertTrue(usable(stale, PEER, MlsConfig.defaults(), off));
        assertTrue(off.said("W", "PROCEEDING"));
        final Recording on = new Recording();
        assertFalse(usable(stale, PEER, cfg(MlsConfig.KEY_KP_CERT_FLOOR, 1), on));
        assertTrue(on.said("W", "REFUSING it"));
    }

    @Test
    public void anUnreadableCertificateSaysTheFloorWasNotEvaluated() {
        final Recording log = new Recording();
        assertTrue(usable(new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, OURS,
                new int[] {OURS}), PEER, MlsConfig.defaults(), log));
        assertTrue("silence here once read as a result", log.said("I", "was NOT evaluated"));
    }

    @Test
    public void aLastResortPackageWarnsAndIsStillConsumed() {
        final Recording log = new Recording();
        assertTrue(usable(new MlsSession.KeyPackageInfo(true, NOW + 363 * DAY, PEER, OURS,
                new int[] {OURS}, NOW, NOW + 73 * DAY), PEER, MlsConfig.defaults(), log));
        assertTrue(log.said("W", "LAST-RESORT"));
    }

    @Test
    public void everyRefusalSaysWhyAtWarnOrAbove() {
        // A refusal with no line at W or E would be a gate whose silence reads as a result.
        final MlsSession.KeyPackageInfo[] refused = {
            null,
            healthy("+15559990000"),
            new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, 3, new int[] {3}, NOW, NOW
                    + 73 * DAY),
            new MlsSession.KeyPackageInfo(false, NOW + 363 * DAY, PEER, OURS, new int[] {1}, NOW,
                    NOW + 73 * DAY),
            new MlsSession.KeyPackageInfo(false, NOW + DAY, PEER, OURS, new int[] {OURS}, NOW, NOW
                    + 73 * DAY),
        };
        for (final MlsSession.KeyPackageInfo info : refused) {
            final Recording log = new Recording();
            assertFalse(usable(info, PEER, MlsConfig.defaults(), log));
            boolean loud = false;
            for (final String l : log.lines) loud |= l.startsWith("W ") || l.startsWith("E ");
            assertTrue("a refusal logged nothing at W/E: " + log.lines, loud);
        }
    }
}
