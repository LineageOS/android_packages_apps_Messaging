/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When replay material is released; a group message survives one member's success. */
public class MlsSendRetentionPolicyTest {

    private static final boolean GROUP = true;
    private static final boolean ONE_TO_ONE = false;

    /** One member's delivery says nothing about the others, who may still need the bytes. */
    @Test
    public void aPositiveReceiptDoesNotReleaseAGroupMessage() {
        assertFalse("a group member's success says nothing about the other members",
                MlsSendRetentionPolicy.releaseOnPositiveReceipt(GROUP));
    }

    /** The sole recipient confirming is terminal for a 1:1. */
    @Test
    public void aPositiveReceiptStillReleasesAOneToOne() {
        assertTrue("the sole recipient confirming IS terminal",
                MlsSendRetentionPolicy.releaseOnPositiveReceipt(ONE_TO_ONE));
    }

    /** A permanent send failure is terminal: nothing will resend it. */
    @Test
    public void aPermanentFailureReleasesEitherKind() {
        assertTrue(MlsSendRetentionPolicy.releaseOnPermanentFailure());
    }

    /**
     * A permanent failure leaves the chain live: its rows link a resend's id to the root holding
     * the body, and without them RCC.16 §10.3 declares a recoverable message lost.
     */
    @Test
    public void aPermanentFailureDoesNotRetireTheChain() {
        assertFalse("a failure means the message did NOT arrive — the chain is still live",
                MlsSendRetentionPolicy.retireChainOnTerminal(/*delivered=*/ false));
    }

    /** A delivery ends the chain as escalation evidence. */
    @Test
    public void aDeliveryRetiresTheChain() {
        assertTrue("evidence about a message that arrived is not evidence about anything",
                MlsSendRetentionPolicy.retireChainOnTerminal(/*delivered=*/ true));
    }

    @Test
    public void materialAgesOutAtTheWindow() {
        final long day = MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS;
        // The reading must exceed the window, or every "just inside" stamp would be negative
        // (UNSTAMPED).
        final long now = 3L * day;
        assertFalse("just inside the window is kept",
                MlsSendRetentionPolicy.expiredMonotonic(now, now - day + 1, day));
        assertTrue("exactly at the window is released",
                MlsSendRetentionPolicy.expiredMonotonic(now, now - day, day));
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(now, now - day - 1, day));
    }

    /** No recorded time means expired; 0 is a real reading meaning "written at boot". */
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
     * A stamp ahead of the reading is a reboot; the entry lives until the uptime reaches the
     * window.
     */
    @Test
    public void aStampAheadOfTheReadingMeansARebootAndIsRetained() {
        final long day = MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS;
        assertFalse("a pre-reboot entry must NOT be expired on 90s of uptime",
                MlsSendRetentionPolicy.expiredMonotonic(90_000L, 5_000_000L, day));
        // Still the reboot branch: the stamp stays ahead of now.
        assertTrue(MlsSendRetentionPolicy.expiredMonotonic(day, day + 5_000_000L, day));
        assertTrue(
                "the reboot branch answers the UPTIME, so any stamp ahead of it behaves the same",
                MlsSendRetentionPolicy.expiredMonotonic(day, Long.MAX_VALUE, day));
    }
}
