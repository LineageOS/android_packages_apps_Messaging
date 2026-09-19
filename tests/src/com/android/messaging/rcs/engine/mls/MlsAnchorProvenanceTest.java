/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link MlsAnchorProvenance}. If the fetched bundle is our own anchor handed back rather than the
 * server's state, its {@code signer} is us by construction, and a fork attributed from it is
 * attributed from nothing. See docs/mls/health-and-recovery.md.
 */
@RunWith(JUnit4.class)
public class MlsAnchorProvenanceTest {

    /** The common branch, pinned to realistic readings rather than 1 vs 0. */
    @Test
    public void aheadIsTheMeasuredCaseAndItsSignerIsAttributable() {
        assertEquals(MlsAnchorProvenance.AHEAD_SERVER_STATE,
                MlsAnchorProvenance.of(true, 3L, 1L));
        assertEquals(MlsAnchorProvenance.AHEAD_SERVER_STATE,
                MlsAnchorProvenance.of(true, 24L, 1L));
        assertTrue(MlsAnchorProvenance.of(true, 3L, 1L).signerIsAttributable());
        assertTrue(MlsAnchorProvenance.of(true, 3L, 1L).line().contains("real committer"));
    }

    /**
     * {@code SAME_EPOCH_MAY_BE_OUR_ANCHOR} is reachable at any epoch, not only at zero, so an
     * implementation that special-cased the initial epoch fails.
     */
    @Test
    public void theSameEpochWeAnchoredAtIsNotAttributableToAnybody() {
        for (final long e : new long[] {0L, 1L, 3L, 24L, Long.MAX_VALUE}) {
            final MlsAnchorProvenance p = MlsAnchorProvenance.of(true, e, e);
            assertEquals("epoch " + e, MlsAnchorProvenance.SAME_EPOCH_MAY_BE_OUR_ANCHOR, p);
            assertFalse("epoch " + e + ": the signer must NOT be attributable — the bundle may be "
                    + "our own anchor handed back, in which case its signer is us by construction",
                    p.signerIsAttributable());
            assertTrue("epoch " + e
                            + ": the sentence must say the signer is evidence about nobody, "
                            + "because that is the whole warning: " + p.line(),
                    p.line().contains("NOBODY"));
            assertTrue("epoch " + e + ": and must say not to attribute a fork from it",
                    p.line().contains("Do not attribute a fork"));
        }
    }

    /** Behind is its own outcome: unexplained, and the signer is unread rather than attributed. */
    @Test
    public void behindIsUnexplainedAndNotAttributable() {
        final MlsAnchorProvenance p = MlsAnchorProvenance.of(true, 1L, 3L);
        assertEquals(MlsAnchorProvenance.BEHIND_UNEXPLAINED, p);
        assertFalse(p.signerIsAttributable());
        assertTrue(p.line().contains("unread"));
    }

    /**
     * Unreadable is not a comparison result: the epochs mean nothing when nothing parsed, and an
     * unreadable bundle reported as AHEAD would license a fork attribution from bytes never read.
     */
    @Test
    public void unreadableOutranksEveryOrdering() {
        for (final long[] pair : new long[][] {{3L, 1L}, {1L, 1L}, {1L, 3L}, {0L, 0L}}) {
            final MlsAnchorProvenance p = MlsAnchorProvenance.of(false, pair[0], pair[1]);
            assertEquals(MlsAnchorProvenance.UNREADABLE, p);
            assertFalse(p.signerIsAttributable());
        }
    }

    /** Exactly one outcome licenses attribution, and the enum cannot grow a second by accident. */
    @Test
    public void onlyOneOutcomeEverLicensesAttribution() {
        int attributable = 0;
        for (final MlsAnchorProvenance p : MlsAnchorProvenance.values()) {
            if (p.signerIsAttributable()) attributable++;
            assertFalse("every outcome must render a sentence", p.line().isEmpty());
        }
        assertEquals("only AHEAD_SERVER_STATE may license fork attribution — a new outcome that "
                + "also licenses it needs its own argument, not a default", 1, attributable);
    }
}
