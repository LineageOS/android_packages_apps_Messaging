/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** {@link MlsMetrics}: the create-client preflight and the state-size buckets. */
public class MlsMetricsTest {

    private static final byte[] LEAF = new byte[] {1};
    private static final byte[] ICA = new byte[] {2};
    private static final byte[] ROOT = new byte[] {3};
    private static final byte[] PRIV = new byte[32];
    private static final byte[] PUB = new byte[65];

    private static MlsIdentity id(final byte[] leaf, final List<byte[]> chain,
            final byte[] priv, final byte[] pub, final List<byte[]> roots) {
        return new MlsIdentity("+15551230000", leaf, chain, priv, pub, roots);
    }

    private static MlsIdentity complete() {
        return id(LEAF, Collections.singletonList(ICA), PRIV, PUB, Collections.singletonList(ROOT));
    }

    @Test
    public void completeIdentity_passesEveryPrecondition() {
        assertEquals(MlsMetrics.CLIENT_OK, MlsMetrics.preflightCreateClient(complete()));
    }

    /**
     * The chain is checked as a whole before its parts, so a null chain reports the chain code
     * rather than "missing leaf"; this order keeps the codes comparable with other clients' traces.
     */
    @Test
    public void eachPrecondition_reportsItsOwnReasonCode() {
        assertEquals(MlsMetrics.CLIENT_FAIL_CHAIN_NULL_OR_EMPTY,
                MlsMetrics.preflightCreateClient(
                        id(LEAF, null, PRIV, PUB, Collections.singletonList(ROOT))));
        assertEquals(MlsMetrics.CLIENT_FAIL_CHAIN_NULL_OR_EMPTY,
                MlsMetrics.preflightCreateClient(
                        id(LEAF, new ArrayList<byte[]>(), PRIV, PUB,
                                Collections.singletonList(ROOT))));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_LEAF,
                MlsMetrics.preflightCreateClient(
                        id(null, Collections.singletonList(ICA), PRIV, PUB,
                                Collections.singletonList(ROOT))));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_INTERMEDIATE,
                MlsMetrics.preflightCreateClient(
                        id(LEAF, Collections.singletonList(new byte[0]), PRIV, PUB,
                                Collections.singletonList(ROOT))));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_ROOT,
                MlsMetrics.preflightCreateClient(
                        id(LEAF, Collections.singletonList(ICA), PRIV, PUB, null)));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_ROOT,
                MlsMetrics.preflightCreateClient(
                        id(LEAF, Collections.singletonList(ICA), PRIV, PUB,
                                Collections.singletonList(new byte[0]))));
    }

    /** Either half of a keypair alone is "missing device keypair", not a partial success. */
    @Test
    public void halfAKeypair_isAMissingKeypair() {
        final List<byte[]> chain = Collections.singletonList(ICA);
        final List<byte[]> roots = Collections.singletonList(ROOT);
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_KEYPAIR,
                MlsMetrics.preflightCreateClient(id(LEAF, chain, null, PUB, roots)));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_KEYPAIR,
                MlsMetrics.preflightCreateClient(id(LEAF, chain, PRIV, null, roots)));
        assertEquals(MlsMetrics.CLIENT_FAIL_MISSING_KEYPAIR,
                MlsMetrics.preflightCreateClient(id(LEAF, chain, new byte[0], PUB, roots)));
    }

    /** A chain with an empty entry beside a real one still has a usable intermediate. */
    @Test
    public void aChainWithOneUsableEntry_passes() {
        assertEquals(MlsMetrics.CLIENT_OK, MlsMetrics.preflightCreateClient(
                id(LEAF, Arrays.asList(new byte[0], ICA), PRIV, PUB,
                        Collections.singletonList(ROOT))));
    }

    @Test
    public void nullIdentity_isUnknownFailure() {
        assertEquals(MlsMetrics.CLIENT_FAIL_UNKNOWN, MlsMetrics.preflightCreateClient(null));
    }

    /** Every code the preflight can return has its own sentence. */
    @Test
    public void everyReasonCode_hasItsOwnDistinctText() {
        assertEquals("Certificate chain is null or empty",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_CHAIN_NULL_OR_EMPTY));
        assertEquals("Missing leaf certificate",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_MISSING_LEAF));
        assertEquals("Missing intermediate certificate",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_MISSING_INTERMEDIATE));
        assertEquals("Missing trusted root certificate",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_MISSING_ROOT));
        assertEquals("Missing device keypair",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_MISSING_KEYPAIR));
        assertEquals("Unknown failure",
                MlsMetrics.clientFailureReason(MlsMetrics.CLIENT_FAIL_UNKNOWN));
    }

    @Test
    public void log2Bucket_isFloorLog2() {
        assertEquals(0, MlsMetrics.log2Bucket(1));
        assertEquals(1, MlsMetrics.log2Bucket(2));
        assertEquals(1, MlsMetrics.log2Bucket(3));
        assertEquals(2, MlsMetrics.log2Bucket(4));
        assertEquals(10, MlsMetrics.log2Bucket(1024));
        assertEquals(10, MlsMetrics.log2Bucket(2047));
        assertEquals(11, MlsMetrics.log2Bucket(2048));
        assertEquals(20, MlsMetrics.log2Bucket(1024L * 1024L));
    }

    /** 8 KiB and 12 KiB share a bucket; 8 KiB and 800 KiB do not. */
    @Test
    public void log2Bucket_separatesBloatFromNoise() {
        assertEquals(MlsMetrics.log2Bucket(8 * 1024), MlsMetrics.log2Bucket(12 * 1024));
        assertEquals(6, MlsMetrics.log2Bucket(800 * 1024) - MlsMetrics.log2Bucket(8 * 1024));
    }

    /** Nothing written is bucket 0. */
    @Test
    public void log2Bucket_isZeroForNothingWritten() {
        assertEquals(0, MlsMetrics.log2Bucket(0));
        assertEquals(0, MlsMetrics.log2Bucket(-1));
    }

    /** The discarding sink accepts everything and never throws. */
    @Test
    public void noneSink_swallowsEverything() {
        MlsTelemetry.NONE.count(MlsMetrics.ZINNIA_STATE_SIZE);
        MlsTelemetry.NONE.count(MlsMetrics.ZINNIA_STATE_SIZE, 13);
        MlsTelemetry.NONE.count(null);
        MlsTelemetry.NONE.count(null, Integer.MIN_VALUE);
    }
}
