/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link MlsAnchorProvenance} — it exists for ONE branch.
 *
 * <p>{@code SAME_EPOCH_MAY_BE_OUR_ANCHOR} <b>has never fired on a device</b>. Every real reading has
 * been {@code AHEAD_SERVER_STATE}: epoch 3 against our 1 on {@code g:b1189d9c}, 24 against 1 on
 * {@code g:f0a4792d}. A branch taken only in tests is exactly where a wrong answer survives — so the
 * decision was extracted out of the transport specifically so this one can be exercised, and
 * `floor-race` asked for it by name for that reason.
 *
 * <p>The stakes are not cosmetic. If the fetched bundle is our own anchor handed back rather than
 * the server's state, its {@code signer} is US BY CONSTRUCTION — we signed our own epoch — and a
 * fork attributed from it is attributed from nothing.
 */
@RunWith(JUnit4.class)
public class MlsAnchorProvenanceTest {

    /** The real readings, so the common branch is pinned to measured values rather than 1 vs 0. */
    @Test
    public void aheadIsTheMeasuredCaseAndItsSignerIsAttributable() {
        assertEquals(MlsAnchorProvenance.AHEAD_SERVER_STATE,
                MlsAnchorProvenance.of(true, 3L, 1L));          // g:b1189d9c, 00AU, 2026-09-11
        assertEquals(MlsAnchorProvenance.AHEAD_SERVER_STATE,
                MlsAnchorProvenance.of(true, 24L, 1L));         // g:f0a4792d, same run
        assertTrue(MlsAnchorProvenance.of(true, 3L, 1L).signerIsAttributable());
        assertTrue(MlsAnchorProvenance.of(true, 3L, 1L).line().contains("real committer"));
    }

    /**
     * THE BRANCH THIS CLASS EXISTS FOR, and the only one nobody has seen fire.
     *
     * <p>It must be reachable at any epoch, not just at zero — a test that only exercised
     * {@code 0 == 0} would pass on an implementation that special-cased the initial epoch.
     */
    @Test
    public void theSameEpochWeAnchoredAtIsNotAttributableToAnybody() {
        for (final long e : new long[] {0L, 1L, 3L, 24L, Long.MAX_VALUE}) {
            final MlsAnchorProvenance p = MlsAnchorProvenance.of(true, e, e);
            assertEquals("epoch " + e, MlsAnchorProvenance.SAME_EPOCH_MAY_BE_OUR_ANCHOR, p);
            assertFalse("epoch " + e + ": the signer must NOT be attributable — the bundle may be "
                    + "our own anchor handed back, in which case its signer is us by construction",
                    p.signerIsAttributable());
            assertTrue("epoch " + e + ": the sentence must say the signer is evidence about nobody, "
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
     * UNREADABLE IS NOT A COMPARISON RESULT. "We could not read the GroupInfo" must not collapse
     * into any of the three orderings — the epochs are meaningless when nothing parsed, and an
     * unreadable bundle reported as AHEAD would licence a fork attribution from bytes we never read.
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
