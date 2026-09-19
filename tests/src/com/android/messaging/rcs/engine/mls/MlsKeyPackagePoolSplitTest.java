/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertSame;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class MlsKeyPackagePoolSplitTest {


    @Test
    public void thePoolSizeIsTheConfiguredOne() {
        assertEquals(MlsConfig.defaults().kpPoolCount,
                MlsKeyPackagePool.kpPoolCount(MlsConfig.defaults()));
    }


    @Test
    public void publishingRecordsTheRefsOfWhatWeUploadedAndNothingWithoutTheEngine() {
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f = new FakeShellPort().returns("prefs", p);
        f.returns("openMlsSession", f.stub(MlsSession.class,
                "keyPackageRef",
                (java.util.function.Function<Object[], Object>) a -> (byte[]) a[0]));
        final byte[] pool = MlsArtifactBundle.joinLenPrefixed(
                java.util.Arrays.asList(new byte[] {1}, new byte[] {2}));
        MlsKeyPackagePool.recordPublishedKeyPackages(f.port(), MlsLogSink.NONE, pool,
                new byte[] {9});
        assertEquals(
                new java.util.HashSet<>(java.util.Arrays.asList(MlsHex.hex(new byte[] {1}),
                        MlsHex.hex(new byte[] {2}))),
                p.values.get(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS));
        assertEquals(MlsHex.hex(new byte[] {9}),
                p.values.get(MlsKeyPackagePool.PREF_KP_LAST_RESORT_REF));
        final FakePrefs none = new FakePrefs();
        MlsKeyPackagePool.recordPublishedKeyPackages(new FakeShellPort().returns("prefs", none)
                .returns("openMlsSession", null).port(), MlsLogSink.NONE, pool, null);
        assertTrue("not the OpenMLS engine: nothing is recorded", none.values.isEmpty());
    }


    @Test
    public void theSelfKeyPackageVetSaysWhatItCouldAndCouldNotRead() {
        assertEquals("no session", MlsKeyPackagePool.vetSelfKeyPackage(
                new FakeShellPort().returns("ensureSession", false).port()));
        final FakeShellPort none = new FakeShellPort().returns("ensureSession", true);
        none.returns("session", none.stub(MlsSession.class, "generateKeyPackages", null));
        assertEquals("generateKeyPackages yielded nothing",
                MlsKeyPackagePool.vetSelfKeyPackage(none.port()));
        final FakeShellPort one = new FakeShellPort().returns("ensureSession", true)
                .returns("selfE164", "+1")
                .returns("keyPackageUsable", true);
        one.returns("session", one.stub(MlsSession.class, "generateKeyPackages",
                MlsArtifactBundle.joinLenPrefixed(
                        java.util.Collections.singletonList(new byte[] {1})),
                "inspectKeyPackage", null));
        assertEquals("usable=true (inspect returned nothing)",
                MlsKeyPackagePool.vetSelfKeyPackage(one.port()));
    }


    @Test
    public void anUnopenableWelcomeRepublishesThePoolAtMostOncePerInterval() {
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f =
                new FakeShellPort().returns("prefs", p).returns("publishKeyPackages", true);
        MlsKeyPackagePool.republishPoolAfterUnopenableWelcome(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, "+2");
        assertTrue(
                f.calls.contains("publishKeyPackages(" + MlsConfig.defaults().kpPoolCount + ")"));
        assertTrue(p.values.containsKey(MlsKeyPackagePool.PREF_LAST_POOL_REPAIR));
        final FakeShellPort again = new FakeShellPort().returns("prefs", p);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsKeyPackagePool.republishPoolAfterUnopenableWelcome(MlsConfig.defaults(), again.port(),
                log, "+2");
        assertFalse(again.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages")));
        assertTrue(log.said("W", "not repeating"));
    }


    @Test
    public void thePoolIsPublishedWhenNeverPublishedAndTheStampOnlyLandsOnSuccess() {
        final FakePrefs failed = new FakePrefs();
        MlsKeyPackagePool.maybePublishKeyPackages(MlsConfig.defaults(),
                new FakeShellPort().returns("prefs", failed).returns("publishKeyPackages", false)
                .port(), MlsLogSink.NONE);
        assertFalse("a failed publish is not recorded",
                failed.values.containsKey(MlsKeyPackagePool.PREF_LAST_KP_PUBLISH));
        final FakePrefs p = new FakePrefs();
        MlsKeyPackagePool.maybePublishKeyPackages(MlsConfig.defaults(),
                new FakeShellPort().returns("prefs", p).returns("publishKeyPackages", true).port(),
                MlsLogSink.NONE);
        assertTrue(p.values.containsKey(MlsKeyPackagePool.PREF_LAST_KP_PUBLISH));
        assertEquals(MlsConfig.defaults().kpPoolCount - 1,
                p.values.get(MlsKeyPackagePool.PREF_KP_REMAINING));
        final FakeShellPort soon = new FakeShellPort().returns("prefs", p);
        MlsKeyPackagePool.maybePublishKeyPackages(MlsConfig.defaults(), soon.port(),
                MlsLogSink.NONE);
        assertFalse("published a moment ago and not drained: not due",
                soon.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages")));
    }


    @Test
    public void aConsumedPackageCountsDownAndALowPoolRepublishes() {
        final FakePrefs p = new FakePrefs();
        p.values.put(MlsKeyPackagePool.PREF_KP_REMAINING, 50);
        final FakeShellPort f = new FakeShellPort().returns("prefs", p);
        MlsKeyPackagePool.noteKeyPackageConsumed(MlsConfig.defaults(), f.port(), MlsLogSink.NONE);
        assertEquals(49, p.values.get(MlsKeyPackagePool.PREF_KP_REMAINING));
        p.values.put(MlsKeyPackagePool.PREF_KP_REMAINING, 1);
        final FakeShellPort low =
                new FakeShellPort().returns("prefs", p).returns("publishKeyPackages", true);
        MlsKeyPackagePool.noteKeyPackageConsumed(MlsConfig.defaults(), low.port(), MlsLogSink.NONE);
        assertTrue("a low pool is replenished",
                low.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages")));
        assertEquals("and the replenish resets the estimate", MlsConfig.defaults().kpPoolCount - 1,
                p.values.get(MlsKeyPackagePool.PREF_KP_REMAINING));
    }


    @Test
    public void aWelcomeCrossesOffTheRefsItNamesAndTheLastResortRepublishes() {
        final FakePrefs p = new FakePrefs();
        p.values.put(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS, new java.util.HashSet<>(
                java.util.Arrays.asList(MlsHex.hex(new byte[] {1}), MlsHex.hex(new byte[] {2}))));
        p.values.put(MlsKeyPackagePool.PREF_KP_LAST_RESORT_REF, MlsHex.hex(new byte[] {9}));
        final FakeShellPort f = new FakeShellPort().returns("prefs", p);
        f.returns("openMlsSession", f.stub(MlsSession.class, "welcomeKeyPackageRefs",
                java.util.Collections.singletonList(new byte[] {1})));
        assertTrue(MlsKeyPackagePool.crossOffConsumedKeyPackage(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, new byte[] {7}));
        assertEquals(java.util.Collections.singleton(MlsHex.hex(new byte[] {2})),
                p.values.get(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS));
        final FakeShellPort lr =
                new FakeShellPort().returns("prefs", p).returns("publishKeyPackages", true);
        lr.returns("openMlsSession", lr.stub(MlsSession.class, "welcomeKeyPackageRefs",
                java.util.Collections.singletonList(new byte[] {9})));
        assertFalse(MlsKeyPackagePool.crossOffConsumedKeyPackage(MlsConfig.defaults(), lr.port(),
                MlsLogSink.NONE, new byte[] {7}));
        assertTrue("the reusable one was served: time to republish",
                lr.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages")));
        assertFalse(MlsKeyPackagePool.crossOffConsumedKeyPackage(MlsConfig.defaults(),
                new FakeShellPort().returns("openMlsSession", null).port(), MlsLogSink.NONE,
                new byte[] {7}));
    }

    /**
     * A Welcome sealed to our last-resort KeyPackage proves the one-time pool is empty on the
     * server, so it republishes even when the local estimate is high and the last publish recent,
     * the case where the ordinary republish check would wait up to a day.
     */
    @Test
    public void theLastResortRepublishesEvenWhenTheEstimateSaysThePoolIsFull() {
        final FakePrefs p = new FakePrefs();
        final java.util.Set<String> ten = new java.util.HashSet<>();
        for (int i = 10; i < 20; i++) ten.add(MlsHex.hex(new byte[] {(byte) i}));
        p.values.put(MlsKeyPackagePool.PREF_KP_PUBLISHED_REFS, ten);
        p.values.put(MlsKeyPackagePool.PREF_KP_REMAINING, 10);
        p.values.put(MlsKeyPackagePool.PREF_KP_LAST_RESORT_REF, MlsHex.hex(new byte[] {9}));
        p.values.put(MlsKeyPackagePool.PREF_LAST_KP_PUBLISH, System.currentTimeMillis() - 60_000L);
        final FakeShellPort f =
                new FakeShellPort().returns("prefs", p).returns("publishKeyPackages", true);
        f.returns("openMlsSession", f.stub(MlsSession.class, "welcomeKeyPackageRefs",
                java.util.Collections.singletonList(new byte[] {9})));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsKeyPackagePool.crossOffConsumedKeyPackage(MlsConfig.defaults(), f.port(), log,
                new byte[] {7});
        assertTrue(log.said("W", "LAST-RESORT"));
        assertTrue("the log says republishing now, and the pool was not republished: the estimate "
                + "(10) and the recent publish held it back",
                f.calls.stream().anyMatch(c -> c.startsWith("publishKeyPackages")));
    }
}
