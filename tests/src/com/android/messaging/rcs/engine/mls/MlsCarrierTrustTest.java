/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.e2ee.MlsCarrierTrust;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * What carrier-path MLS trusts on each kind of build. A user build trusts only the provider's
 * anchors and, without them, runs no carrier-path MLS; a debug build keeps the compiled-in
 * fallback. {@code TestNetworkTrustGuardTest} checks that the real callers pass the real
 * debug-build test.
 */
public class MlsCarrierTrustTest {

    private static final byte[] COMPILED_IN_ROOT = {0x30, 0x01};
    private static final byte[] PROVIDER_ROOT = {0x30, 0x02};
    private static final String TEST_KDS = "https://kds.example.test";
    private static final String CONFIGURED_KDS = "https://kds.carrier.example";

    private final AtomicInteger mRootAsked = new AtomicInteger();
    private final AtomicInteger mKdsAsked = new AtomicInteger();

    private final Supplier<List<byte[]>> mCompiledInRoots = () -> {
        mRootAsked.incrementAndGet();
        return Collections.singletonList(COMPILED_IN_ROOT);
    };
    private final Supplier<String> mCompiledInKds = () -> {
        mKdsAsked.incrementAndGet();
        return TEST_KDS;
    };

    @Test
    public void userBuildWithoutProviderAnchors_refusesTheCompiledInRootAndMlsIsOff() {
        final List<List<byte[]>> nones = Arrays.asList(null, Collections.<byte[]>emptyList());
        for (final List<byte[]> none : nones) {
            final List<byte[]> anchors =
                    MlsCarrierTrust.anchors(none, /*debugBuild=*/ false, mCompiledInRoots);
            assertTrue("a user build must trust nothing without the provider's anchors",
                    anchors.isEmpty());
            final String kds =
                    MlsCarrierTrust.kdsBaseUrl("", /*debugBuild=*/ false, mCompiledInKds);
            assertNull("a user build has no default KDS", kds);
            assertFalse("carrier-path MLS must be off", MlsCarrierTrust.enabled(anchors, kds));
            assertFalse("off even with a configured KDS: there is nothing to trust",
                    MlsCarrierTrust.enabled(anchors, CONFIGURED_KDS));
        }
        assertEquals("the compiled-in root is not even read on a user build", 0,
                mRootAsked.get());
        assertEquals("nor is the test network's KDS", 0, mKdsAsked.get());
        assertFalse("the main process keeps the conversation off MLS",
                MlsCarrierTrust.readyInApp(/*attached=*/ true, /*debugBuild=*/ false));
    }

    @Test
    public void userBuildWithProviderAnchors_trustsThemAndOnlyThem() {
        final List<byte[]> supplied = Collections.singletonList(PROVIDER_ROOT);
        final List<byte[]> anchors =
                MlsCarrierTrust.anchors(supplied, /*debugBuild=*/ false, mCompiledInRoots);
        assertSame("the provider's verified list, as supplied", supplied, anchors);
        final String kds =
                MlsCarrierTrust.kdsBaseUrl(CONFIGURED_KDS, /*debugBuild=*/ false, mCompiledInKds);
        assertEquals(CONFIGURED_KDS, kds);
        assertTrue(MlsCarrierTrust.enabled(anchors, kds));
        assertFalse("anchors without a KDS to enrol with are not enough",
                MlsCarrierTrust.enabled(anchors,
                        MlsCarrierTrust.kdsBaseUrl("", /*debugBuild=*/ false, mCompiledInKds)));
        assertEquals(0, mRootAsked.get());
        assertEquals(0, mKdsAsked.get());
    }

    @Test
    public void debugBuild_keepsTheCompiledInFallback() {
        final List<byte[]> anchors = MlsCarrierTrust.anchors(
                Collections.<byte[]>emptyList(), /*debugBuild=*/ true, mCompiledInRoots);
        assertEquals(1, anchors.size());
        assertArrayEquals(COMPILED_IN_ROOT, anchors.get(0));
        final String kds = MlsCarrierTrust.kdsBaseUrl(null, /*debugBuild=*/ true, mCompiledInKds);
        assertEquals(TEST_KDS, kds);
        assertTrue(MlsCarrierTrust.enabled(anchors, kds));
        assertTrue(MlsCarrierTrust.readyInApp(/*attached=*/ true, /*debugBuild=*/ true));
        assertFalse("never ready while the :ims conduit is down",
                MlsCarrierTrust.readyInApp(/*attached=*/ false, /*debugBuild=*/ true));
    }

    @Test
    public void onADebugBuildTheProviderAnchorsAndTheConfiguredKdsStillWin() {
        final List<byte[]> supplied = Collections.singletonList(PROVIDER_ROOT);
        assertSame("replaced, never merged with the compiled-in root", supplied,
                MlsCarrierTrust.anchors(supplied, /*debugBuild=*/ true, mCompiledInRoots));
        assertEquals(CONFIGURED_KDS,
                MlsCarrierTrust.kdsBaseUrl(CONFIGURED_KDS, /*debugBuild=*/ true, mCompiledInKds));
        assertEquals(0, mRootAsked.get());
        assertEquals(0, mKdsAsked.get());
    }

    @Test
    public void aDebugBuildWhoseCompiledInValueIsMissingTrustsNothing() {
        assertTrue(MlsCarrierTrust.anchors(null, /*debugBuild=*/ true, () -> null).isEmpty());
        assertFalse(MlsCarrierTrust.enabled(null, TEST_KDS));
        assertFalse(MlsCarrierTrust.enabled(
                Collections.singletonList(COMPILED_IN_ROOT), ""));
    }
}
