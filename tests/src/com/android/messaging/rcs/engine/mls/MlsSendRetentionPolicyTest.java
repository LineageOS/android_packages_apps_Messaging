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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule that a group message's replay material survives one member's success.
 *
 * <p>These exist because the behaviour they pin was device-observed rather than reasoned: the
 * previous release-on-first-receipt rule looked correct in review — its comment even argued the
 * case — and only failed when we finally made ourselves resend to a group.
 */
public class MlsSendRetentionPolicyTest {

    private static final boolean GROUP = true;
    private static final boolean ONE_TO_ONE = false;

    /** THE BUG: one member delivering used to destroy the bytes the other members still need. */
    @Test
    public void aPositiveReceiptDoesNotReleaseAGroupMessage() {
        assertFalse("a group member's success says nothing about the other members",
                MlsSendRetentionPolicy.releaseOnPositiveReceipt(GROUP));
    }

    /** …and the 1:1 case, where the old rule was right, must keep working. */
    @Test
    public void aPositiveReceiptStillReleasesAOneToOne() {
        assertTrue("the sole recipient confirming IS terminal",
                MlsSendRetentionPolicy.releaseOnPositiveReceipt(ONE_TO_ONE));
    }

    /** A permanent send failure is terminal for everyone: nothing will ever resend it. */
    @Test
    public void aPermanentFailureReleasesEitherKind() {
        assertTrue(MlsSendRetentionPolicy.releaseOnPermanentFailure());
    }

    /**
     * THE BUG: a permanent failure used to DELETE the resend chain's rows.
     *
     * <p>Both terminals went through one method and it applied the delivery's scope to both. The
     * rows are the only link between a resend's bare-UUID id and the root that holds the body, so
     * deleting them on a failure orphans the chain: the next report resolves the resend id to
     * itself, finds no chat row — a resend never gets one — and §10.3 declares a recoverable
     * message lost. The failed attempt's own bytes are still released; it is the bookkeeping that
     * must survive.
     */
    @Test
    public void aPermanentFailureDoesNotForgetTheChain() {
        assertFalse("a failure means the message did NOT arrive — the chain is still live",
                MlsSendRetentionPolicy.forgetChainOnTerminal(/*delivered=*/ false));
    }

    /** …and a delivery still ends the chain, which is what the rows exist to stop being evidence for. */
    @Test
    public void aDeliveryStillForgetsTheChain() {
        assertTrue("evidence about a message that arrived is not evidence about anything",
                MlsSendRetentionPolicy.forgetChainOnTerminal(/*delivered=*/ true));
    }

    @Test
    public void materialAgesOutAtTheWindow() {
        final long day = MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS;
        // An elapsedRealtime reading, and it must be LARGER than the window: below it there is no
        // stamp that is "just inside" — every candidate is negative, i.e. UNSTAMPED. That is not a
        // fixture detail, it is the lower-bound rule showing through, and it cost this test a red.
        final long now = 3L * day;
        assertFalse("just inside the window is kept",
                MlsSendRetentionPolicy.expiredMonotonic(now, now - day + 1, day));
        assertTrue("exactly at the window is released",
                MlsSendRetentionPolicy.expiredMonotonic(now, now - day, day));
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(now, now - day - 1, day));
    }

    /**
     * An entry with no recorded time cannot be aged, so it must not be immortal.
     *
     * <p>{@link MlsSendRetentionPolicy#UNSTAMPED} is the ONLY spelling of that, and {@code 0} is
     * deliberately not one of them: zero is a legal {@code elapsedRealtime} reading in the first
     * millisecond after boot, and the wall-clock form's {@code storedAtMs <= 0} test conflated the
     * two. A stamp of 0 means "written at boot", which is the OLDEST an entry can be in this boot —
     * so it expires once the uptime reaches the window, and not before.
     */
    @Test
    public void anUntimedEntryIsExpiredRatherThanImmortal() {
        final long day = MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS;
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(40_000_000L,
                MlsSendRetentionPolicy.UNSTAMPED, day));
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(40_000_000L, -7L, day));
        assertFalse("0 is a reading, not a missing stamp — at 11h of uptime it is 11h old",
                MlsSendRetentionPolicy.expiredMonotonic(40_000_000L, 0L, day));
        assertTrue("…and at 24h of uptime an entry stamped at boot HAS reached the window",
                MlsSendRetentionPolicy.expiredMonotonic(day, 0L, day));
    }

    /**
     * THE BUG: a stamp AHEAD of the current reading is a REBOOT, and a rebooted
     * entry is RETAINED.
     *
     * <p>This assertion is the exact inverse of the one it replaces, and the inversion is the fix.
     * Under the wall clock a stored time in the future meant a clock that had gone backwards, and
     * the entry was dropped as unusable — which is a deletion decided by a clock anyone can move.
     * Under {@code elapsedRealtime} the reading cannot go backwards within a boot, so a stored value
     * ahead of {@code now} can only mean the device rebooted. {@link MlsMonotonicAge} then answers
     * with the current uptime, the largest age it can PROVE, and the entry survives until the device
     * has been up for the whole window.
     *
     * <p>These devices take NITZ time from the network and we have watched the clock jump on this
     * fleet, so this is a state that really occurs — which is why the thing being deleted on the
     * strength of it was worth moving off the wall clock.
     */
    @Test
    public void aStampAheadOfTheReadingMeansARebootAndIsRetained() {
        final long day = MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS;
        assertFalse("a pre-reboot entry must NOT be expired on 90s of uptime",
                MlsSendRetentionPolicy.expiredMonotonic(90_000L, 5_000_000L, day));
        // …and once the uptime reaches the window it HAS provably aged out. The stamp has to stay
        // ahead of `now` for this to still be the reboot branch — a stamp below the window would
        // simply be an ordinary same-boot entry, which is a different test.
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(day, day + 5_000_000L, day));
        assertTrue("the reboot branch answers the UPTIME, so any stamp ahead of it behaves the same",
                MlsSendRetentionPolicy.expiredMonotonic(day, Long.MAX_VALUE, day));
    }
}
