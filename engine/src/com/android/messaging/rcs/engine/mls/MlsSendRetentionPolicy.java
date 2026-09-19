/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * How long a sent message's replay material (sealed ciphertext and pending body) is kept for an
 * RCC.16 §10.3 resend. A positive receipt is terminal for a 1:1 but not for a group, where one
 * member's success says nothing about the others: a group message is released once every current
 * member has confirmed, on permanent failure, or when it ages out. Keeping bytes too long costs a
 * slot; dropping them early loses a message for good.
 */
public final class MlsSendRetentionPolicy {

    private MlsSendRetentionPolicy() {}

    /**
     * The retention window for send-side replay material: 24 h, the app's own choice. Long enough
     * for a peer offline overnight to report a failure; short enough not to fill the store's cap.
     * It also backs the "Not sent" retry the app shows for rows failed at startup, so do not
     * shorten it, and do not reuse it as a delivery deadline.
     */
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /** Whether a positive delivery receipt may release replay material: only for a 1:1. */
    public static boolean releaseOnPositiveReceipt(final boolean isGroupMessage) {
        return !isGroupMessage;
    }

    /**
     * Whether a permanent send failure may release the failed attempt's material: always. Says
     * nothing about the rest of its resend chain; see {@link #retireChainOnTerminal}.
     */
    public static boolean releaseOnPermanentFailure() {
        return true;
    }

    /**
     * Whether a terminal event retires the resend chain's ledger rows: only a delivery. After a
     * delivery they are stale ladder evidence, so they stop counting; after a failure they are the
     * only link from a resend's id back to the root that holds the body, and they still count.
     * Retiring keeps the rows, because a receipt naming the resend (the delivery receipt itself,
     * which the status update resolves after this release, and a displayed receipt later) reaches
     * the original row only through them. They are deleted {@link #RETIRED_CHAIN_MAX_AGE_MS} later.
     *
     * @param delivered true for a positive delivery receipt, false for a permanent send failure
     */
    public static boolean retireChainOnTerminal(final boolean delivered) {
        return delivered;
    }

    /**
     * How long a delivered chain's rows keep mapping a resend's receipts to the original row: 7
     * days, for a displayed receipt from a peer who reads the message days later. The rows are a
     * few short strings each; the replay bytes are gone at delivery.
     */
    public static final long RETIRED_CHAIN_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * "No elapsed stamp". Negative, since 0 is a legal {@code elapsedRealtime()} reading just after
     * boot.
     */
    public static final long UNSTAMPED = -1L;

    /**
     * Whether material stamped {@code storedElapsedMs} has aged out. Ages by
     * {@link MlsMonotonicAge}, never the wall clock, whose forward jumps would delete in-flight
     * material: an entry may be retained past its window after a reboot but never dropped early.
     * {@link #UNSTAMPED} is expired, since nothing else could ever age it; an older-format row is
     * adopted by its caller instead.
     */
    public static boolean expiredMonotonic(final long nowElapsedMs, final long storedElapsedMs,
            final long maxAgeMs) {
        if (storedElapsedMs < 0L) return true;
        return MlsMonotonicAge.ageMs(storedElapsedMs, nowElapsedMs) >= maxAgeMs;
    }
}
